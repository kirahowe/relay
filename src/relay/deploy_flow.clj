(ns relay.deploy-flow
  "`bb deploy` and `bb rollback` as a pure state machine. relay.deploy-run
  is the shell: it performs each effect this asks for and hands back what
  came of it. Nothing here reads a file, runs a command or looks at a
  clock.

  A run is a map. `start` makes one; `step` takes it and the result of
  its :effect and returns it with the next :effect, or with an :outcome
  when it is over:

    {:ok record}        it worked
    {:refused text}     it stopped before changing anything that stays
    {:failed text}      it stopped partway; the text says what is left

  An effect is {:op ... :k ...} plus what the op needs. :k names the
  handler for its result and is not the shell's concern. An effect with
  :touch is the first change to a checkout, and one with :gated must be
  performed while the shell holds the gate it shares with the interrupt
  hook (see relay.deploy-run). Every result is a map; an effect that
  threw comes back as {:thrown message}.

    :observe        {:path :bookmark}      -> {:observation o}
    :observe-all    {:paths :bookmark}     -> {:observations {repo o}}
    :has-file       {:path :commit :file}  -> {:present? bool}
    :workspace-add  {:path :name :commit :dir}  -> {:exit :out :err}
    :workspace-forget {:path :name}        -> {:exit :out :err}
    :delete-dir     {:dir}                 -> {}
    :run            {:dir :cmd}            -> {:exit :out :err}
    :jj-new         {:path :commit}        -> {:exit :out :err}
    :commits        {:path :rev}           -> {:commits [...]} or {:error text}
    :say            {:text}                -> {}
    :confirm        {:lines :verb}         -> {:confirmed? bool}
    :append-record  {:record}              -> {}
    :pause          {:why}                 -> {}
    :resume         {}                     -> {}
    :busy           {}                     -> {:busy [text ...] :now ms}
    :sleep          {:ms}                  -> {}
    :pause-reason   {}                     -> {:reason text-or-nil}

  `interrupt` turns a run into the cleanup a Ctrl-C does at that moment,
  itself a run driven by `step`, whose outcome is {:print text-or-nil}."
  (:require [clojure.string :as str]
            [relay.deploy :as deploy]
            [relay.pause :as pause]))

;; --- the machinery

(declare recur-abort recur-forget finish c-forget recur-c-forget c-close c-say)

(defn- emit
  "The run, waiting on `effect` (an op, its arguments) whose result goes
  to handler `k`."
  [run op args k]
  (-> run (dissoc :outcome) (assoc :effect (merge args {:op op :k k}))))

(defn- end [run outcome] (-> run (dissoc :effect) (assoc :outcome outcome)))

(defn- ok? [r] (and (not (:thrown r)) (= 0 (:exit r))))

(defn- tail [s n] (->> (str/split-lines (str s)) (remove str/blank?) (take-last n) (str/join "\n")))

(defn- failure-text [what r]
  (if (:thrown r)
    (str what " failed: " (:thrown r))
    (str what " failed (exit " (:exit r) "): " (tail (str (:err r) "\n" (:out r)) 8))))

;; --- what the operator reads

(defn refusal-text
  "A refusal: nothing was changed, why, and how to lift a pause that was
  already there when the run began (`inherited`, its reason)."
  [problems pause-file inherited]
  (str "Nothing was changed.\n"
       (str/join "\n" (map #(str "  " %) (concat problems (pause/recovery-lines pause-file inherited))))))

(defn paused-advice
  "How to get out of a pause a failed or interrupted run left, as text."
  [pause-file kind status-only?]
  (cond
    (and status-only? (= :rollback kind))
    (str "relay is PAUSED (" pause-file "). Look at what status said. If relay is fine, lift the pause with\n"
         "  rm '" pause-file "'")
    status-only?
    (str "relay is PAUSED (" pause-file "). Look at what status said. If relay is fine, lift the pause with\n"
         "  rm '" pause-file "'\n"
         "otherwise run bb rollback to put the checkouts back; it lifts the pause when it works.")
    :else
    (str "relay is PAUSED (" pause-file "). Run bb rollback to put the checkouts back"
         (when (= :rollback kind) " (again: it carries on from where it stopped)")
         "; it lifts the pause when it succeeds. To lift it by hand: rm '" pause-file "'")))

(defn failed-text
  "What a run that failed partway, or whose last record would not write,
  tells the operator."
  [{:keys [kind failure written-error status-only? paused? pause-file]}]
  (str (str/upper-case (name kind)) (if failure " FAILED. " " DONE, BUT ")
       (or failure (str "its last record could not be written (" written-error "). The checkouts are as intended."))
       (when (and failure written-error) (str " Its last record could not be written either (" written-error ")."))
       "\n"
       (if status-only?
         (if (= :rollback kind)
           "Every checkout is back where the deploy found it and was rebuilt. Only `relay status` failed."
           "Every checkout reached its target and was built. Only `relay status` failed.")
         "The checkouts are as far as it got; the record file lists where each was before.")
       (when paused? (str "\n" (paused-advice pause-file kind status-only?)))))

;; --- refusing before the run's record is written

(defn- refuse [{:keys [o] :as run} problems]
  (end run {:refused (refusal-text problems (:pause-file o) (:inherited o))}))

;; --- stopping inside the run, before anything is touched

(defn- abort
  "Stops a run that has written its record (and maybe paused relay) but
  touched nothing: lifts the pause if this run made it, closes the record
  as failed, and refuses with `text`."
  [run text]
  (let [run (assoc run :abort text)]
    (if (and (= :ours (:pause run)) (not (:touched? run)))
      (emit (assoc run :pause nil :notice? false) :resume {} :abort-lifted)
      (recur-abort run))))

(defn- recur-abort [{:keys [abort record-open? rec] :as run}]
  (if record-open?
    (emit (assoc run :record-open? false) :append-record
          {:record (deploy/make-record (assoc rec :status :failed :failure abort))} :abort-closed)
    (end run {:refused abort})))

(defn- abort-refusal [{:keys [o] :as run} problems]
  (abort run (refusal-text problems (:pause-file o) (:inherited o))))

;; --- the plan, until the run starts

(defn- relay-target [plan] (:commit (:to (first (filter #(= :relay (:repo %)) (:steps plan))))))

(defn- check-pause-support [run plan]
  (emit (assoc run :plan plan) :has-file
        {:path (get-in run [:o :paths :relay]) :commit (relay-target plan) :file "src/relay/pause.clj"}
        :target-supports))

(defn- start-record [{:keys [kind o of]} steps]
  {:id (str (name kind) " " (:at o)) :kind kind :of of :at (:at o) :status :started
   :inherited (and (= :rollback kind) (some? (:inherited o)))
   :repos (into {} (for [{:keys [repo path observed from to]} steps]
                     [repo {:path path :wc (:wc observed) :previous from :target to :new nil :state :pending}]))})

(defn- confirm-lines [{:keys [kind o plan source]}]
  (concat [(if (= :deploy kind)
             (str "Deploy " (:bookmark o) " to the live checkouts (the commits that were just tested):")
             (str "Roll back to before the deploy of " (:at source) ":"))]
          (deploy/describe-plan plan)))

(defn- ask [run]
  (emit run :confirm {:lines (confirm-lines run) :verb (if (= :deploy (:kind run)) "deploy" "roll back")} :confirmed))

;; --- verifying the planned commits in fresh workspaces (deploy only)

(defn- check-names [{:keys [o]}]
  (into {} (for [r deploy/repo-keys] [r (str "deploy-check-" (name r) "-" (:suffix o))])))

(defn- check-dir [{:keys [o]} r] (str (:check-dir o) "/" (name r)))

(defn- commit-of [run r] (:commit (:to (first (filter #(= r (:repo %)) (:steps (:plan run)))))))

(defn- add-workspace [run [r & more]]
  (emit (assoc run :adding more) :workspace-add
        {:repo r :path (get-in run [:o :paths r]) :name (get (:workspaces run) r)
         :commit (commit-of run r) :dir (check-dir run r)}
        :ws-added))

(defn- verify [run]
  ;; Named before any is made, so an interrupt forgets one made but not
  ;; yet noted: jj exits 0, with a warning, for a name it does not know.
  (add-workspace (assoc run :workspaces (check-names run) :made #{} :problem nil :checks [])
                 deploy/repo-keys))

(defn- check-commands
  "The builds and tests to run in the check workspaces, in order."
  [{:keys [o] :as run}]
  (concat (for [r [:agentloop :relay-tui]]
            {:say (str "Building " (name r) " at " (deploy/short-id {:commit (commit-of run r)}) " in a fresh workspace")
             :dir (check-dir run r) :cmd (deploy/builds r) :what (str (name r) " would not build")})
          (for [cmd (:test-cmds o)]
            {:say (str "Running " (str/join " " cmd) " on relay at " (deploy/short-id {:commit (commit-of run :relay)}))
             :dir (check-dir run :relay) :cmd cmd :what (str (str/join " " cmd) " on relay")})))

(defn- forget-workspaces
  "Forgets every check workspace by name, then deletes the directory."
  [{:keys [workspaces] :as run}]
  ;; The names stay on the run until the directory is gone, so an
  ;; interrupt partway forgets the rest; forgetting twice is harmless.
  (recur-forget (assoc run :forgetting (vec deploy/repo-keys) :forget-names workspaces :stuck [])))

(defn- recur-forget [{:keys [forgetting forget-names o] :as run}]
  (if-let [r (first forgetting)]
    (emit (assoc run :forgetting (rest forgetting)) :workspace-forget
          {:repo r :path (get-in o [:paths r]) :name (get forget-names r)} :forgot)
    (emit run :delete-dir {:dir (:check-dir o)} :deleted)))

(defn- forget-problem
  "What could not be cleaned up, as text, or nil."
  [{:keys [stuck undeleted]}]
  (let [lines (cond-> []
                (seq stuck) (conj (str "The check workspaces could not all be forgotten. Run these by hand:\n"
                                       (str/join "\n" stuck)))
                undeleted (conj undeleted))]
    (when (seq lines) (str/join "\n" lines))))

(defn- next-check [run]
  (if-let [c (and (nil? (:problem run)) (first (:checks run)))]
    (emit (assoc run :checks (rest (:checks run)) :check c) :say {:text (:say c)} :check-said)
    (forget-workspaces run)))

;; --- the run, under the pause

(defn- begin-run [{:keys [plan] :as run}]
  (let [rec0 (start-record run (:steps plan))]
    (emit (assoc run :rec rec0) :append-record {:record (deploy/make-record rec0)} :rec0-written)))

(defn- start-waiting [run]
  (emit (assoc run :wait {}) :busy {} :busy))

(defn- step-of [run] (first (:to-apply run)))

(defn- next-step [run]
  (if-let [{:keys [path]} (step-of run)]
    (emit run :observe {:path path :bookmark nil} :looked)
    (emit (assoc run :running-status? true) :say {:text "Running relay status"} :status-said)))

(defn- fail [run text] (assoc run :failure text))

(defn- save
  "Writes the record as it now is, then carries on at `k`."
  [run k]
  (emit run :append-record {:record (deploy/make-record (:rec run))} k))

(defn- set-state [run repo state] (assoc-in run [:rec :repos repo :state] state))

(defn- done-step [run]
  (let [{:keys [repo moves? build]} (step-of run)]
    (-> run (set-state repo (if (or moves? build) :built :kept))
        (update :to-apply rest)
        (save :step-saved))))

(defn- build-step [run & [touch]]
  (let [{:keys [repo build]} (step-of run)]
    (if build
      (emit run :say (cond-> {:text (str "Building " (name repo))} touch (assoc :touch repo)) :build-said)
      (done-step run))))

(defn move-failure
  "Why a move went wrong, from what jj said, or nil. `landed` is the
  checkout's parent after `jj new`; `kept` what jj says of the planned
  working copy's change, which jj drops once it is left, if it was empty.
  If it is still there with content, an edit arrived between the last look
  and the move. Pure."
  [{:keys [repo path to observed new-result landed kept kept-error]}]
  (cond
    ;; jj can fail after it has recorded its operation, leaving the
    ;; working copy stale, so a failed move is treated as a move.
    (not (ok? new-result))
    (str (failure-text (str "jj new in " path) new-result)
         "\nIt may have changed the working copy anyway. If jj says it is stale, run "
         "`jj workspace update-stale` in " path ".")
    (not (deploy/same-commit? landed to))
    (str (name repo) " landed on " (deploy/short-id landed) ", not on " (deploy/short-id to) ".")
    kept-error
    (str "jj could not say whether something was written to " path " while it moved (" kept-error
         "). Look at change " (:change (:wc observed)) " there.")
    (some (complement :empty?) kept)
    (let [c (:change (first (remove :empty? kept)))]
      (str "Something was written to " path " while it moved. jj kept it in change " c
           ", which is not in the new working copy. Find it with `jj log -r " c "`."))))

(defn- end-under-pause
  "The steps and status are done or stopped at :failure. Lifts the pause if
  that is right, then writes the last record."
  [{:keys [failure touched?] :as run}]
  (cond
    (not failure)
    ;; Lifted before the run is recorded as finished, so a crash in
    ;; between leaves a record a retry can complete, not a finished one
    ;; with relay still paused.
    (emit (assoc run :notice? false :pause nil) :resume {} :lifted)
    touched?
    (emit run :say {:text (str "relay stays paused (" (get-in run [:o :pause-file]) "): a checkout was touched.")}
          :finish)
    (= :ours (:pause run))
    (emit (assoc run :pause nil :notice? false) :resume {} :finish)
    :else (finish run)))

(defn- finish [{:keys [rec failure] :as run}]
  (let [final (deploy/make-record (assoc rec :status (if failure :failed :ok) :failure failure))]
    (emit (assoc run :final final :record-open? false) :append-record {:record final} :final-written)))

;; --- handlers

(defmulti ^:private handle (fn [run _result] (get-in run [:effect :k])))

(defn step
  "The run after its effect gave `result`."
  [run result]
  (handle run result))

(defmethod handle :here [run {:keys [observation thrown]}]
  (let [from (get-in run [:o :from])
        here observation]
    (cond
      thrown (refuse run [(str "jj could not read " from ": " thrown)])
      (not (:jj? here)) (refuse run [(str "You ran this from " from ", which is not a jj working copy.")])
      (:error here) (refuse run [(str "jj could not read " from ": " (:error here))])
      (not (:empty? (:wc here)))
      (refuse run [(str "The relay working copy you ran this from (" from ") has changes. Commit or abandon them first.")])
      :else (emit run :observe-all {:paths (get-in run [:o :paths]) :bookmark (get-in run [:o :bookmark])} :observed))))

(defmethod handle :observed [run {:keys [observations thrown]}]
  (if thrown
    (refuse run [(str "The checkouts could not be read: " thrown)])
    (let [plan (if (= :deploy (:kind run))
                 (deploy/plan-deploy {:observed observations :bookmark (get-in run [:o :bookmark])})
                 (deploy/plan-rollback {:observed observations :record (:source run) :paths (get-in run [:o :paths])}))]
      (if (:problems plan)
        (refuse run (:problems plan))
        (check-pause-support run plan)))))

(defmethod handle :target-supports [{:keys [o] :as run} {:keys [present? thrown]}]
  (if-let [p (deploy/pause-problem {:current-supports? (:current-supports? o) :external? (:paused-externally o)
                                    :target-supports? (and (not thrown) present?)})]
    (refuse run [p])
    (if (= :deploy (:kind run)) (verify run) (ask run))))

(defmethod handle :ws-added [{:keys [effect] :as run} r]
  (let [repo (:repo effect)]
    (if (ok? r)
      (let [run (update run :made conj repo)]
        (if (seq (:adding run))
          (add-workspace run (:adding run))
          (next-check (assoc run :checks (check-commands run)))))
      (forget-workspaces (assoc run :problem (failure-text (str "Making a workspace of " (name repo) " at " (:commit effect)) r))))))

(defmethod handle :check-said [{:keys [check] :as run} _]
  (emit run :run {:dir (:dir check) :cmd (:cmd check)} :checked))

(defmethod handle :checked [{:keys [check] :as run} r]
  (next-check (cond-> run (not (ok? r)) (assoc :problem (failure-text (:what check) r)))))

(defmethod handle :forgot [{:keys [effect made forget-names] :as run} r]
  (recur-forget (cond-> run
                  (and (not (ok? r)) (contains? made (:repo effect)))
                  (update :stuck conj (str "  in " (:path effect) ": jj workspace forget " (get forget-names (:repo effect)))))))

(defmethod handle :deleted [{:keys [o problem] :as run} {:keys [thrown]}]
  (let [run (cond-> (assoc run :workspaces nil) thrown (assoc :undeleted (str "The check directory " (:check-dir o) " could not be deleted: " thrown)))
        stuck (forget-problem run)
        problem (cond (and problem stuck) (str problem "\n" stuck) :else (or problem stuck))]
    (if problem
      (end run {:refused (str "Nothing was changed in the checkouts.\n" problem)})
      (ask run))))

(defmethod handle :confirmed [run {:keys [confirmed?]}]
  (if confirmed?
    (begin-run run)
    (refuse run ["Not confirmed on the terminal."])))

(defmethod handle :rec0-written [{:keys [o] :as run} {:keys [thrown]}]
  (cond
    thrown (end run {:refused (str "Nothing was changed.\n  The record file " (:record o) " could not be written: " thrown)})
    ;; The pause is looked at again just before pausing: the operator may
    ;; have made or lifted one by hand while the commits were tested or
    ;; the code was asked.
    :else (emit (assoc run :record-open? true) :pause-reason {} :pre-pause)))

(defmethod handle :pre-pause [{:keys [o kind] :as run} {:keys [reason thrown]}]
  (cond
    ;; Not knowing is not the same as not paused: making a pause here
    ;; could overwrite, and later lift, the operator's.
    thrown (abort-refusal run [(str "The pause file " (:pause-file o) " could not be read: " thrown)])
    ;; A pause that is already there (a failed deploy's, or the
    ;; operator's) is not this run's to lift: the machine may be mixed.
    reason (start-waiting (-> run (assoc-in [:o :inherited] reason) (assoc :pause :inherited :notice? true)))
    :else (emit (-> run (assoc-in [:o :inherited] nil) (assoc :pause :ours :notice? true)) :pause
                {:why (str "bb " (name kind) " at " (:at o)) :gated true} :paused)))

(defmethod handle :paused [run {:keys [thrown]}]
  (if thrown
    (abort (assoc run :pause nil) (str "relay could not be paused: " thrown))
    (start-waiting run)))

(defmethod handle :abort-lifted [{:keys [o] :as run} {:keys [thrown]}]
  (recur-abort (cond-> run
                 thrown (update :abort str "\n  But relay could not be unpaused (" thrown "): it is still paused. "
                                "Remove " (:pause-file o) " by hand."))))

(defmethod handle :abort-closed [run {:keys [thrown]}]
  (end run {:refused (cond-> (:abort run)
                       thrown (str "\n  The run's record could not be closed either (" thrown ")."))}))

(defmethod handle :busy [{:keys [o wait] :as run} {:keys [busy now thrown]}]
  (let [{:keys [started quiet-since told?]} wait
        started (or started now)]
    (cond
      thrown (abort run thrown)
      (and (empty? busy) quiet-since (>= (- now quiet-since) (:settle-ms o)))
      (emit run :observe-all {:paths (:paths o) :bookmark (when (= :deploy (:kind run)) (:bookmark o))} :re-observed)
      (> (- now started) (:quiet-timeout-ms o))
      (abort-refusal run [(str "These were still running after " (quot (:quiet-timeout-ms o) 1000) " seconds: "
                               (str/join ", " busy) ". "
                               (if (= :inherited (:pause run)) "relay is still paused." "relay is running again."))])
      :else
      (let [run (assoc run :wait {:started started
                                  :quiet-since (when (empty? busy) (or quiet-since now))
                                  :told? (or told? (boolean (seq busy)))})]
        (if (and (seq busy) (not told?))
          (emit run :say {:text (str "Waiting for " (str/join ", " busy))} :wait-said)
          (emit run :sleep {:ms (:poll-ms o)} :slept))))))

(defmethod handle :wait-said [run _] (emit run :sleep {:ms (get-in run [:o :poll-ms])} :slept))
(defmethod handle :slept [run _] (emit run :busy {} :busy))

(defmethod handle :re-observed [{:keys [plan kind] :as run} {:keys [observations thrown]}]
  ;; The confirmation, and the wait for wakes, took a while: refuse if
  ;; anything is not as it was tested.
  (let [changes (if thrown
                  [(str "The checkouts could not be read: " thrown)]
                  (vec (mapcat (fn [{:keys [repo observed]}]
                                 (deploy/observation-changes repo (cond-> observed (= :rollback kind) (dissoc :target))
                                                             (get observations repo)))
                               (:steps plan))))]
    (if (seq changes)
      (abort-refusal run (cons (if (= :deploy kind) "A checkout changed since it was tested:" "A checkout changed since the plan:")
                               changes))
      (next-step (assoc run :to-apply (:steps plan))))))

(defmethod handle :looked [run {:keys [observation thrown]}]
  ;; Just before this checkout moves: is it still the one that was
  ;; planned, with an empty working copy?
  (let [{:keys [repo moves? build observed to]} (step-of run)
        changes (if thrown
                  [(str (name repo) " could not be read: " thrown ".")]
                  (deploy/observation-changes repo (dissoc observed :target) observation))]
    (cond
      (seq changes)
      (end-under-pause (fail run (str "Stopped before touching " (name repo) ": " (str/join " " changes))))
      moves?
      (emit (assoc run :touched? true) :say {:text (str "Moving " (name repo) " to " (deploy/short-id to))
                                             :touch repo} :move-said)
      build
      (build-step (assoc run :touched? true) :touch)
      :else (done-step run))))

(defmethod handle :move-said [run _]
  (save (set-state run (:repo (step-of run)) :moving) :moving-saved))

(defmethod handle :moving-saved [run {:keys [thrown]}]
  (if thrown
    (end-under-pause (fail run (str "Unexpected error: " thrown)))
    (let [{:keys [path to]} (step-of run)]
      (emit run :jj-new {:path path :commit (:commit to)} :moved))))

(defmethod handle :moved [run r]
  (emit (assoc run :new-result r) :commits {:path (:path (step-of run)) :rev "@-"} :landed))

(defmethod handle :landed [run {:keys [commits]}]
  (let [run (assoc run :landed (first commits))]
    (if (ok? (:new-result run))
      (emit run :commits {:path (:path (step-of run)) :rev (str "present(" (:change (:wc (:observed (step-of run)))) ")")}
            :kept)
      (end-under-pause (fail run (move-failure (merge (step-of run) (select-keys run [:new-result :landed]))))))))

(defmethod handle :kept [run {:keys [commits error thrown]}]
  (let [{:keys [repo]} (step-of run)
        run (-> run
                (assoc :move-failure (move-failure (merge (step-of run) (select-keys run [:new-result :landed])
                                                          {:kept commits :kept-error (or error thrown)})))
                (update-in [:rec :repos repo] assoc :new (deploy/ids (:landed run)) :state :moved))]
    (save run :moved-saved)))

(defmethod handle :moved-saved [run {:keys [thrown]}]
  (cond
    thrown (end-under-pause (fail run (str "Unexpected error: " thrown)))
    (:move-failure run) (end-under-pause (fail run (:move-failure run)))
    :else (build-step run)))

(defmethod handle :build-said [run _]
  ;; A build rewrites files whether or not the revision moved, so a failed
  ;; one leaves the checkout touched.
  (save (set-state run (:repo (step-of run)) :building) :building-saved))

(defmethod handle :building-saved [run {:keys [thrown]}]
  (if thrown
    (end-under-pause (fail run (str "Unexpected error: " thrown)))
    (let [{:keys [path build]} (step-of run)]
      (emit run :run {:dir path :cmd build} :built))))

(defmethod handle :built [run r]
  (let [{:keys [repo build]} (step-of run)]
    (cond
      (:thrown r) (end-under-pause (fail run (str "Unexpected error: " (:thrown r))))
      (not (ok? r)) (end-under-pause (fail run (failure-text (str (name repo) ": " (str/join " " build)) r)))
      :else (done-step run))))

(defmethod handle :step-saved [run {:keys [thrown]}]
  (if thrown
    (end-under-pause (fail run (str "Unexpected error: " thrown)))
    (next-step run)))

(defmethod handle :status-said [{:keys [o] :as run} _]
  (emit run :run {:dir (get-in o [:paths :relay]) :cmd (:status-cmd o)} :status))

(defmethod handle :status [run r]
  (emit (assoc run :status-result r) :say {:text (str/trim (str (:out r)))} :status-shown))

(defmethod handle :status-shown [{:keys [status-result] :as run} _]
  (end-under-pause
   (cond
     (:thrown status-result) (fail run (str "Unexpected error: " (:thrown status-result)))
     (not (ok? status-result)) (-> run (assoc :status-only? true) (fail (failure-text "relay status" status-result)))
     :else run)))

(defmethod handle :lifted [{:keys [o] :as run} {:keys [thrown]}]
  (finish (cond-> run
            thrown (assoc :touched? true
                          :failure (str "relay could not be unpaused: " thrown ". Remove " (:pause-file o) " by hand.")))))

(defmethod handle :finish [run _] (finish run))

(defmethod handle :final-written [run {:keys [thrown]}]
  (emit (assoc run :written-error thrown) :pause-reason {} :final-paused))

(defmethod handle :final-paused [{:keys [o kind failure written-error] :as run} {:keys [reason]}]
  (if (or failure written-error)
    (end run {:failed (failed-text {:kind kind :failure failure :written-error written-error
                                    :status-only? (:status-only? run) :paused? (some? reason)
                                    :pause-file (:pause-file o)})})
    (emit run :say {:text (str (if (= :deploy kind) "Deployed" "Rolled back") ". Records are in " (:record o) ".")}
          :done-said)))

(defmethod handle :done-said [run _] (end run {:ok (:final run)}))

;; --- a rollback that only lifts a pause

(defmethod handle :lift-confirmed [run {:keys [confirmed?]}]
  (if confirmed?
    (emit run :resume {} :lift-resumed)
    (refuse run ["Not confirmed on the terminal."])))

(defmethod handle :lift-resumed [{:keys [o lift-run] :as run} {:keys [thrown]}]
  (if thrown
    (end run {:failed (str "relay could not be unpaused: " thrown ". Remove " (:pause-file o) " by hand.")})
    (let [rec (deploy/make-record {:id (str "rollback " (:at o)) :kind :rollback :of (:id lift-run) :at (:at o)
                                   :status :ok :lift-only true :repos {}})]
      (emit (assoc run :final rec) :append-record {:record rec} :lift-written))))

(defmethod handle :lift-written [{:keys [o] :as run} {:keys [thrown]}]
  (if thrown
    (end run {:failed (str "ROLLBACK DONE, BUT its record could not be written (" thrown "). "
                           (if (:inherited o) "The pause is lifted; nothing was moved." "Nothing was moved."))})
    (emit run :say {:text (if (:inherited o) "Lifted the pause. Nothing was moved." "Closed the record. Nothing was moved.")}
          :done-said)))

;; --- starting

(defn start
  "A run of `kind` (:deploy or :rollback), waiting on its first effect.
  `o` is what the shell settled and read before the run:

    :paths {:relay :agentloop :relay-tui}  :bookmark  :from (deploy)
    :record  :pause-file  :data  :at (the time, as text)
    :test-cmds  :status-cmd (a command)  :check-dir  :suffix
    :poll-ms  :settle-ms  :quiet-timeout-ms  :paused-externally
    :current-supports? (the relay checkout has the pause file now)
    :inherited (the pause file's reason, read once the lock is held)
    :records (rollback: the record file's records)"
  [kind o]
  (let [run {:kind kind :o o :notice? (some? (:inherited o))}]
    (case kind
      :deploy
      (if-let [why (:inherited o)]
        (refuse run [(str "relay is already paused (" (:pause-file o) "): " why)
                     "If a deploy failed partway, run bb rollback; it lifts the pause when it succeeds."])
        (emit run :observe {:path (:from o) :bookmark nil} :here))
      :rollback
      (let [{:keys [problem lift-only] source :record} (deploy/rollback-source (:records o) (some? (:inherited o)))]
        (cond
          problem (refuse run [problem])
          lift-only
          (emit (assoc run :lift-run lift-only) :confirm
                {:lines [(str "The last run (" (:id lift-only) ") stopped before it touched anything, so there is nothing to roll back.")
                         (if (:inherited o)
                           (str "This only lifts the pause (" (:pause-file o) "): " (:inherited o))
                           "relay is not paused either; this only closes that run's record.")]
                 :verb "lift the pause"}
                :lift-confirmed)
          :else
          (emit (assoc run :source source :of (:id source)) :observe-all {:paths (:paths o) :bookmark nil} :observed))))))

;; --- an interrupt

(defn interrupt
  "The cleanup a stop (Ctrl-C, a closing terminal) does to `run` as it is
  now, as a run for `step`. It lifts the pause if this run made it and has
  touched no checkout, forgets the check workspaces, closes the record if
  nothing was touched, and then says what it did, or, when the pause stays,
  how to recover. The pause goes first: a kill between the two then leaves
  a closed record and no pause, which is safe, not a pause with a closed
  record."
  [run]
  (let [c {:kind (:kind run) :o (:o run) :cleanup true
           :lift? (and (= :ours (:pause run)) (not (:touched? run)))
           :forget-names (:workspaces run) :made (or (:made run) #{}) :stuck []
           :close (when (and (:record-open? run) (not (:touched? run))) (:rec run))
           :notice? (:notice? run)}]
    (if (:lift? c)
      (emit c :resume {} :c-lifted)
      (c-forget (assoc c :lifted? false)))))

(defn- c-forget [{:keys [forget-names] :as c}]
  (if forget-names
    (recur-c-forget (assoc c :forgetting (vec deploy/repo-keys)))
    (c-close c)))

(defn- recur-c-forget [{:keys [forgetting forget-names o] :as c}]
  (if-let [r (first forgetting)]
    (emit (assoc c :forgetting (rest forgetting)) :workspace-forget
          {:repo r :path (get-in o [:paths r]) :name (get forget-names r)} :c-forgot)
    (emit c :delete-dir {:dir (:check-dir o)} :c-deleted)))

(defn- c-close [{:keys [close] :as c}]
  (if close
    (emit c :append-record {:record (deploy/make-record (assoc close :status :failed :failure "stopped before anything was changed"))}
          :c-closed)
    (c-say c)))

(defn- c-say [{:keys [lifted? notice?] :as c}]
  (let [stuck (forget-problem c)]
    (cond
      (and lifted? stuck) (end c {:print (str "Interrupted before any checkout was touched. relay is not paused.\n" stuck)})
      lifted? (end c {:print "Interrupted before any checkout was touched. Nothing was changed and relay is not paused."})
      notice? (emit (assoc c :stuck-text stuck) :pause-reason {} :c-reason)
      stuck (end c {:print (str "Interrupted. " stuck)})
      :else (end c {:print nil}))))

(defmethod handle :c-lifted [c {:keys [thrown]}] (c-forget (assoc c :lifted? (not thrown))))

(defmethod handle :c-forgot [{:keys [effect made forget-names] :as c} r]
  (recur-c-forget (cond-> c
                    (and (not (ok? r)) (contains? made (:repo effect)))
                    (update :stuck conj (str "  in " (:path effect) ": jj workspace forget " (get forget-names (:repo effect)))))))

(defmethod handle :c-deleted [{:keys [o] :as c} {:keys [thrown]}]
  (c-close (cond-> c thrown (assoc :undeleted (str "The check directory " (:check-dir o) " could not be deleted: " thrown)))))

(defmethod handle :c-closed [c _] (c-say c))

(defmethod handle :c-reason [{:keys [o kind stuck-text] :as c} {:keys [reason]}]
  (end c {:print (not-empty (str/join "\n" (remove nil? [(when stuck-text (str "Interrupted. " stuck-text))
                                              (when reason (str "Interrupted. " (paused-advice (:pause-file o) kind false)))])))}))

(defn stopped-text
  "What the main thread says when it finds the interrupt hook has run: the
  checkout it was about to touch, if any. The hook has already said
  whether relay stays paused."
  [run]
  (cond
    (get-in run [:effect :touch]) (str "Interrupted before touching " (name (get-in run [:effect :touch])) ".")
    (:touched? run) "Interrupted after a checkout was touched."
    :else "Interrupted before anything was changed."))
