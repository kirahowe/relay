(ns relay.deploy-flow-test
  "bb deploy and bb rollback, played through relay.deploy-flow with no IO:
  each effect is answered from a world of plain data (the checkouts as
  observations, the record's lines, the pause, what is running), and a
  test can stop at any effect to interrupt there."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.deploy :as deploy]
            [relay.deploy-flow :as flow]))

;; --- a world

(def one (apply str (repeat 40 "1")))
(def two (apply str (repeat 40 "2")))
(def three (apply str (repeat 40 "3")))

(defn- commit [id] {:commit id :change (str "ch" (subs id 0 6))})

(def paths {:agentloop "/w/agentloop" :relay-tui "/w/relay-tui" :relay "/w/relay"})

(defn- wc [n] {:commit (str "e" n (apply str (repeat 38 "0"))) :change (str "wc" n) :empty? true :conflict? false})

(defn world
  "Every checkout on `one` with an empty working copy, main at `two`."
  []
  {:repos (into {} (for [[r p] paths] [r {:path p :wc (wc (name r)) :parents [(commit one)] :main (commit two)}]))
   :from {:path "/w/from" :exists? true :jj? true :wc (wc "from") :parents [(commit one)]}
   :has-pause? true :records [] :paused nil :busy [] :now 0 :n 0
   :said [] :runs [] :workspaces #{} :effects []})

(defn- repo-at [w path] (first (for [[r m] (:repos w) :when (= path (:path m))] r)))

(defn- observation [w path bookmark]
  (if-let [r (repo-at w path)]
    (let [{:keys [wc parents main gone? error]} (get-in w [:repos r])]
      (cond gone? {:path path :exists? false}
            :else (cond-> {:path path :exists? true :jj? true :wc wc :parents parents :target (when bookmark main)}
                    error (assoc :error error))))
    (if (= path (:path (:from w))) (:from w) {:path path :exists? false})))

(defn respond
  "The result of effect `e` in world `w`, and the world after it: [result w']."
  [w {:keys [op] :as e}]
  (let [w (update w :effects conj e)]
    (case op
      :observe [{:observation (observation w (:path e) (:bookmark e))} w]
      :observe-all [{:observations (into {} (for [[r p] (:paths e)] [r (observation w p (:bookmark e))]))} w]
      :has-file [{:present? (:has-pause? w)} w]
      :workspace-add [{:exit 0} (update w :workspaces conj (:name e))]
      :workspace-forget [{:exit 0} (update w :workspaces disj (:name e))]
      :delete-dir [{} w]
      :run [{:exit 0 :out "" :err ""} (update w :runs conj [(:dir e) (:cmd e)])]
      :jj-new (let [r (repo-at w (:path e)) n (inc (:n w))]
                [{:exit 0} (-> w (assoc :n n)
                               (update-in [:repos r] assoc :parents [(commit (:commit e))] :wc (wc (str (name r) n))))])
      :commits (let [r (repo-at w (:path e))]
                 [(if (= "@-" (:rev e)) {:commits (get-in w [:repos r :parents])} {:commits []}) w])
      :say [{} (update w :said conj (:text e))]
      :confirm [{:confirmed? true} w]
      :append-record [{} (update w :records conj (:record e))]
      :pause [{} (assoc w :paused (:why e))]
      :resume [{} (assoc w :paused nil)]
      :busy [{:busy (or (first (:busy w)) []) :now (:now w)} (update w :busy #(if (next %) (rest %) %))]
      :sleep [{} (update w :now + (:ms e))]
      :pause-reason [{:reason (:paused w)} w])))

(defn- answer
  "respond, unless the world's :on (fn [w e]) answers first with [result w']."
  [w e]
  (or (when-let [f (:on w)] (f w e)) (respond w e)))

(defn play
  "Plays `run` in world `w` to its outcome, or until (stop? effect w) is
  true. Returns {:run :world :outcome}."
  [run w & {:keys [stop?]}]
  (loop [run run w w]
    (cond
      (:outcome run) {:run run :world w :outcome (:outcome run)}
      (and stop? (stop? (:effect run) w)) {:run run :world w}
      :else (let [[r w'] (answer w (:effect run))] (recur (flow/step run r) w')))))

(defn interrupt
  "Plays to the effect `stop?` picks, then does the stop's cleanup there.
  Returns {:print :world :run (the run as the stop found it)}."
  [run w stop?]
  (let [{:keys [run world]} (play run w :stop? stop?)
        _ (is (:effect run) "the stop came before the run ended")
        {:keys [outcome] cleaned :world} (play (flow/interrupt run) world)]
    {:print (:print outcome) :world cleaned :run run}))

(defn opts [w & {:as more}]
  (merge {:paths paths :bookmark "main" :from "/w/from" :record "/w/state/deploys.edn"
          :pause-file "/w/state/paused" :data "/w/data" :at "T1" :test-cmds [["bb" "test"]]
          :status-cmd ["relay" "status"] :check-dir "/tmp/check" :suffix "1"
          :poll-ms 10 :settle-ms 0 :quiet-timeout-ms 100 :current-supports? true
          :inherited (:paused w) :records (:records w)}
         more))

;; Each run begins at its own time, which is its record's id.
(defn- at [w] (str "T" (count (:effects w))))
(defn deploy [w & more] (play (flow/start :deploy (apply opts w :at (at w) more)) w))
(defn rollback [w & more] (play (flow/start :rollback (apply opts w :at (at w) more)) w))

(defn- parent [w r] (:commit (first (get-in w [:repos r :parents]))))
(defn- on? [w id] (every? #(= id (parent w %)) deploy/repo-keys))
(defn- text [outcome] (or (:refused outcome) (:failed outcome)))
(defn- latest [w] (last (deploy/latest-per-id (:records w))))
(defn- index-of [w pred] (first (keep-indexed (fn [i e] (when (pred e) i)) (:effects w))))
(defn- first-move [w] (index-of w #(= :jj-new (:op %))))

(defn- on-op
  "An :on that answers effects matching `pred` with (f w e), once if `once?`."
  [pred f]
  (fn [w e] (when (pred e) (f w e))))

(defn- run-of? [repo-path cmd] #(and (= :run (:op %)) (= repo-path (:dir %)) (= cmd (:cmd %))))
(def build-relay-tui (run-of? "/w/relay-tui" ["make" "build"]))
(def build-agentloop (run-of? "/w/agentloop" ["make" "build"]))
(defn- edit [w r] (update-in w [:repos r :wc] assoc :commit three :empty? false))

;; --- deploying and rolling back

(deftest deploy-moves-every-checkout-to-main-and-rollback-puts-them-back
  (let [{:keys [outcome] w :world} (deploy (world))
        rec (:ok outcome)]
    (testing "each checkout is on main, and agentloop and relay-tui were built"
      (is (on? w two))
      (is (some #{["/w/agentloop" ["make" "build"]]} (:runs w)))
      (is (some #{["/w/relay-tui" ["make" "build"]]} (:runs w)))
      (is (not-any? #(= "/w/relay" (first %)) (filter #(= ["make" "build"] (second %)) (:runs w)))))
    (testing "the planned commits were built and tested in check workspaces first"
      (is (some #{["/tmp/check/relay" ["bb" "test"]]} (:runs w)))
      (is (< (index-of w #(= :workspace-add (:op %))) (index-of w #(= :confirm (:op %))) (first-move w)))
      (is (empty? (:workspaces w)) "and the check workspaces are gone"))
    (testing "relay was paused before anything moved and runs again after status"
      (is (< (index-of w #(= :pause (:op %))) (index-of w #(= :busy (:op %))) (first-move w)))
      (is (< (index-of w #(= ["relay" "status"] (:cmd %))) (index-of w #(= :resume (:op %)))))
      (is (nil? (:paused w))))
    (testing "the record was written before anything moved, and as each checkout did"
      (is (< (index-of w #(= :append-record (:op %))) (index-of w #(= :pause (:op %)))))
      (is (= :started (:status (first (:records w)))))
      (is (every? #(= :pending (:state %)) (vals (:repos (first (:records w))))))
      (is (< 3 (count (:records w))))
      (is (= :ok (:status rec)))
      (is (= rec (last (:records w))))
      (doseq [r deploy/repo-keys]
        (is (= one (get-in rec [:repos r :previous :commit])))
        (is (= two (get-in rec [:repos r :new :commit])))
        (is (= two (get-in rec [:repos r :target :commit])))
        (is (= :built (get-in rec [:repos r :state])))))
    (is (some #(str/includes? % (str "on relay at " (subs two 0 12))) (:said w)) "the tested commit is the planned one")
    (testing "rollback goes back and records it"
      (let [{:keys [outcome] w :world} (rollback w)]
        (is (on? w one))
        (is (= :rollback (:kind (:ok outcome))))
        (is (= :ok (:status (:ok outcome))))
        (is (nil? (:paused w)))
        (testing "a second rollback has nothing to undo"
          (is (str/includes? (text (:outcome (rollback w))) "already rolled back")))))))

(deftest a-checkout-with-changes-stops-the-deploy-before-anything-moves
  (let [{:keys [outcome] w :world} (deploy (edit (world) :agentloop))]
    (is (str/includes? (text outcome) "agentloop: its working copy has changes"))
    (is (str/includes? (text outcome) "Nothing was changed"))
    (is (on? w one))
    (is (nil? (:paused w)))
    (is (= [] (:records w)))))

(deftest the-working-copy-it-runs-from-must-be-clean
  (is (str/includes? (text (:outcome (deploy (update-in (world) [:from :wc] assoc :empty? false)))) "has changes"))
  (is (str/includes? (text (:outcome (deploy (assoc-in (world) [:from :jj?] false)))) "not a jj working copy")))

(deftest failing-tests-or-a-missing-confirmation-stop-the-deploy
  (let [failing (assoc (world) :on (on-op #(= ["bb" "test"] (:cmd %)) (fn [w _] [{:exit 1 :out "" :err "red"} w])))
        {:keys [outcome] w :world} (deploy failing)]
    (is (str/includes? (text outcome) "bb test on relay failed"))
    (is (on? w one))
    (is (empty? (:workspaces w)) "the check workspaces are cleaned up even on failure")
    (is (nil? (:paused w))))
  (let [{:keys [outcome] w :world} (deploy (assoc (world) :on (on-op #(= :confirm (:op %)) (fn [w _] [{:confirmed? false} w]))))]
    (is (str/includes? (text outcome) "Not confirmed"))
    (is (on? w one))
    (is (nil? (:paused w)))
    (is (empty? (:workspaces w)))))

(defn- status-fails [w] (assoc w :on (on-op #(= ["relay" "status"] (:cmd %)) (fn [w _] [{:exit 3 :out "" :err "broken"} w]))))

(deftest a-failing-relay-status-fails-loudly-leaves-relay-paused-and-can-be-rolled-back
  (let [{:keys [outcome] w :world} (deploy (status-fails (world)))]
    (is (str/includes? (text outcome) "DEPLOY FAILED"))
    (is (str/includes? (text outcome) "relay status failed (exit 3)"))
    (is (str/includes? (text outcome) "Every checkout reached its target and was built. Only `relay status` failed."))
    (is (str/includes? (text outcome) "rm '/w/state/paused'"))
    (is (str/includes? (text outcome) "bb rollback"))
    (is (some? (:paused w)) "checkouts moved, so relay stays paused")
    (is (= :failed (:status (latest w))))
    (let [w (dissoc w :on)]
      (testing "deploying again is refused while it is paused, and says how to lift it"
        (let [t (text (:outcome (deploy w)))]
          (is (str/includes? t "already paused"))
          (is (str/includes? t "rm '/w/state/paused'"))))
      (let [{:keys [outcome] w :world} (rollback w)]
        (is (:ok outcome))
        (is (on? w one))
        (is (nil? (:paused w)) "a rollback that works lifts the pause")))))

(deftest the-record-is-written-before-the-first-move-so-a-crash-leaves-a-way-back
  ;; relay-tui's build throws after agentloop has moved.
  (let [{:keys [outcome] w :world} (deploy (assoc (world) :on (on-op build-relay-tui (fn [w _] [{:thrown "killed"} w]))))]
    (is (str/includes? (text outcome) "DEPLOY FAILED"))
    (is (= :started (:status (first (:records w)))))
    (is (some? (:paused w)))
    (let [{:keys [outcome] w :world} (rollback (dissoc w :on))]
      (is (:ok outcome))
      (is (on? w one)))))

(deftest a-failed-rollback-can-be-run-again-to-the-same-target
  (let [w (:world (deploy (world)))
        {:keys [outcome] w :world} (rollback (assoc w :on (on-op build-relay-tui (fn [w _] [{:exit 2 :out "" :err "build broke"} w]))))]
    (is (str/includes? (text outcome) "ROLLBACK FAILED"))
    (is (= [one one two] (map #(parent w %) deploy/repo-keys)) "agentloop and relay-tui went back, relay has not yet")
    (is (some? (:paused w)))
    (let [{:keys [outcome] w :world} (rollback (dissoc w :on))]
      (is (:ok outcome))
      (is (on? w one))
      (is (nil? (:paused w)))
      (is (str/includes? (text (:outcome (rollback w))) "already rolled back")))))

;; --- what changed while it waited

(defn- at-confirm [f] (fn [w e] (when (= :confirm (:op e)) [{:confirmed? true} (f w)])))

(deftest an-edit-made-while-the-operator-confirms-stops-the-move
  (let [{:keys [outcome] w :world} (deploy (assoc (world) :on (at-confirm #(edit % :relay-tui))))]
    (is (str/includes? (text outcome) "A checkout changed since it was tested"))
    (is (str/includes? (text outcome) "relay-tui's working copy changed"))
    (is (nil? (first-move w)))
    (is (nil? (:paused w)) "nothing moved, so relay runs again")
    (is (= :failed (:status (latest w))) "and the record is closed")))

(deftest what-is-installed-is-what-was-tested
  (let [{:keys [outcome] w :world} (deploy (assoc (world) :on (at-confirm #(assoc-in % [:repos :agentloop :main] (commit three)))))]
    (is (str/includes? (text outcome) "bookmark moved"))
    (is (on? w one))
    (is (nil? (:paused w)))))

(deftest an-edit-made-while-the-operator-confirms-a-rollback-stops-it
  (let [w (:world (deploy (world)))
        {:keys [outcome] w :world} (rollback (assoc w :on (at-confirm #(edit % :relay))))]
    (is (str/includes? (text outcome) "A checkout changed since the plan"))
    (is (on? w two) "nothing was rolled back")))

(deftest an-edit-made-during-a-build-stops-the-checkout-it-was-made-in
  ;; While agentloop builds, the operator edits relay, which moves last.
  (let [{:keys [outcome] w :world} (deploy (assoc (world) :on (on-op build-agentloop (fn [w _] [{:exit 0} (edit w :relay)]))))]
    (is (str/includes? (text outcome) "Stopped before touching relay"))
    (is (str/includes? (text outcome) "PAUSED"))
    (is (= one (parent w :relay)) "relay was not moved")
    (is (= two (parent w :agentloop)))
    (is (some? (:paused w)) "agentloop moved and relay did not: mixed")))

(deftest a-checkout-jj-cannot-read-at-the-last-look-is-not-moved
  (let [{:keys [outcome] w :world} (deploy (assoc (world) :on (on-op build-agentloop
                                                                      (fn [w _] [{:exit 0} (assoc-in w [:repos :relay :error] "The working copy is stale")]))))]
    (is (str/includes? (text outcome) "Stopped before touching relay"))
    (is (str/includes? (text outcome) "could not be read"))
    (is (= one (parent w :relay)))))

(deftest a-jj-new-that-fails-is-treated-as-having-moved
  ;; jj makes the move and then reports failure, as it can when it cannot
  ;; update the files.
  (let [lying (fn [w e] (when (and (= :jj-new (:op e)) (= "/w/agentloop" (:path e)))
                          (let [[_ w] (respond w e)] [{:exit 255 :out "" :err "could not update the working copy"} w])))
        {:keys [outcome] w :world} (deploy (assoc (world) :on lying))]
    (is (str/includes? (text outcome) "DEPLOY FAILED"))
    (is (str/includes? (text outcome) "may have changed the working copy"))
    (is (str/includes? (text outcome) "update-stale"))
    (is (some? (:paused w)) "paused: the checkout may have moved")
    (is (= :moving (get-in (latest w) [:repos :agentloop :state])))
    (testing "and a rollback can still put it back"
      (let [{:keys [outcome] w :world} (rollback (dissoc w :on))]
        (is (:ok outcome))
        (is (on? w one))))))

(deftest an-edit-that-lands-between-the-last-look-and-the-move-is-reported
  (let [racing (fn [w e] (when (and (= :commits (:op e)) (str/starts-with? (:rev e) "present(") (= "/w/relay" (:path e)))
                           [{:commits [{:commit three :change "kept1" :empty? false}]} w]))
        {:keys [outcome] w :world} (deploy (assoc (world) :on racing))]
    (is (str/includes? (text outcome) "was written to /w/relay"))
    (is (str/includes? (text outcome) "in change kept1"))
    (is (some? (:paused w)))))

(deftest a-lookup-that-fails-after-a-move-does-not-pass-for-no-edit
  (let [broken (fn [w e] (when (and (= :commits (:op e)) (str/starts-with? (:rev e) "present(") (= "/w/relay" (:path e)))
                           [{:error "could not read the operation log"} w]))
        {:keys [outcome] w :world} (deploy (assoc (world) :on broken))]
    (is (str/includes? (text outcome) "could not say whether something was written"))
    (is (some? (:paused w)))))

;; --- builds and status

(deftest a-rebuild-that-fails-without-a-move-keeps-relay-paused
  (let [w (:world (deploy (world)))
        {:keys [outcome] w :world} (deploy (assoc w :on (on-op build-relay-tui (fn [w _] [{:exit 2 :out "" :err "build broke"} w]))))]
    (is (str/includes? (text outcome) "DEPLOY FAILED"))
    (is (some? (:paused w)) "the artifact changed, so relay stays paused")
    (is (= :building (get-in (latest w) [:repos :relay-tui :state])))
    (is (some #(str/includes? % "a checkout was touched") (:said w)) "and it says so")))

(deftest a-rebuild-that-succeeds-without-a-move-still-counts-as-touched
  (let [w (:world (deploy (world)))
        {:keys [outcome] w :world} (deploy (status-fails w))]
    (is (str/includes? (text outcome) "relay status failed"))
    (is (some? (:paused w)) "artifacts were rebuilt, so relay stays paused")
    (is (= {:agentloop :built :relay-tui :built :relay :kept} (update-vals (:repos (latest w)) :state)))))

(deftest a-failed-rebuild-that-moved-nothing-can-be-recovered-when-it-is-the-first-deploy
  ;; Everything is already on main, so the first deploy moves nothing.
  (let [w (reduce #(assoc-in %1 [:repos %2 :parents] [(commit two)]) (world) deploy/repo-keys)
        {:keys [outcome] w :world} (deploy (assoc w :on (on-op build-relay-tui (fn [w _] [{:exit 2 :out "" :err "x"} w]))))]
    (is (str/includes? (text outcome) "DEPLOY FAILED"))
    (is (some? (:paused w)))
    (testing "rollback rebuilds in place and lifts it"
      (let [{:keys [outcome] w :world} (rollback (dissoc w :on))]
        (is (= :ok (:status (:ok outcome))))
        (is (nil? (:paused w)))
        (is (on? w two) "nothing was reverted")
        (testing "and deploying works again"
          (is (:ok (:outcome (deploy w)))))))))

(deftest a-failed-rebuild-after-a-good-deploy-is-undone-before-the-good-deploy-is
  (let [w (:world (deploy (world)))
        w (:world (deploy (assoc w :on (on-op build-relay-tui (fn [w _] [{:exit 2 :out "" :err "x"} w])))))
        _ (is (some? (:paused w)))
        {w :world} (rollback (dissoc w :on))]
    (testing "the first rollback is of the failed rebuild: the good deploy stays"
      (is (nil? (:paused w)))
      (is (on? w two)))
    (testing "the next is of the good deploy"
      (is (on? (:world (rollback w)) one)))))

(deftest the-paused-message-fits-a-rollback-whose-only-failure-is-status
  (let [w (:world (deploy (world)))
        t (text (:outcome (rollback (status-fails w))))]
    (is (str/includes? t "back where the deploy found it"))
    (is (not (str/includes? t "otherwise run bb rollback")))
    (is (str/includes? t "rm '"))))

(deftest a-pause-that-cannot-be-lifted-does-not-look-like-a-finished-run
  (let [stuck (fn [w e] (when (= :resume (:op e)) [{:thrown "disk says no"} w]))
        {:keys [outcome] w :world} (deploy (assoc (world) :on stuck))]
    (is (str/includes? (text outcome) "could not be unpaused"))
    (is (str/includes? (text outcome) "/w/state/paused"))
    (is (= :failed (:status (latest w))) "not recorded as done while relay is paused")
    (testing "a rollback is still offered, and lifts the pause"
      (let [{:keys [outcome] w :world} (rollback (dissoc w :on))]
        (is (:ok outcome))
        (is (on? w one))
        (is (nil? (:paused w)))))))

(deftest a-record-that-cannot-be-written-midway-fails-the-run
  (let [n (atom 0)
        flaky (fn [w e] (when (and (= :append-record (:op e)) (= 3 (swap! n inc))) [{:thrown "disk full"} w]))
        {:keys [outcome] w :world} (deploy (assoc (world) :on flaky))]
    (is (str/includes? (text outcome) "Unexpected error: disk full"))
    (is (some? (:paused w)))))

;; --- the check workspaces

(defn- forget-fails [w e] (when (= :workspace-forget (:op e)) [{:exit 1 :out "" :err "locked"} w]))

(deftest check-workspaces-that-cannot-be-forgotten-are-reported
  (let [{:keys [outcome] w :world} (deploy (assoc (world) :on forget-fails))]
    (is (str/includes? (text outcome) "could not all be forgotten"))
    (is (str/includes? (text outcome) "in /w/relay: jj workspace forget deploy-check-relay-1"))
    (is (on? w one))))

(deftest a-check-directory-that-will-not-delete-does-not-hide-stuck-workspaces
  ;; Regression (relay#33): the delete threw before the message was built.
  (let [both (fn [w e] (or (forget-fails w e) (when (= :delete-dir (:op e)) [{:thrown "disk says no"} w])))
        t (text (:outcome (deploy (assoc (world) :on both))))]
    (is (str/includes? t "could not all be forgotten"))
    (is (str/includes? t "/w/relay"))
    (is (str/includes? t "could not be deleted: disk says no"))))

(deftest a-workspace-made-but-not-noted-is-still-forgotten
  ;; jj makes the workspace and then reports failure.
  (let [lying (fn [w e] (when (and (= :workspace-add (:op e)) (= "/w/agentloop" (:path e)))
                          [{:exit 1 :out "" :err "disk full?"} (update w :workspaces conj (:name e))]))
        {:keys [outcome] w :world} (deploy (assoc (world) :on lying))]
    (is (str/includes? (text outcome) "Making a workspace of agentloop"))
    (is (empty? (:workspaces w)))))

;; --- the pause, and what is running

(deftest a-deploy-waits-for-running-wakes-to-finish
  (let [{:keys [outcome] w :world} (deploy (assoc (world) :busy [["hub (wake 3)"] ["hub (wake 3)"] []]))]
    (is (:ok outcome))
    (is (some #{"Waiting for hub (wake 3)"} (:said w)) "said once what it waits for")
    (is (= 1 (count (filter #(str/starts-with? % "Waiting") (:said w)))))
    (is (< (index-of w #(= :pause (:op %))) (index-of w #(= :busy (:op %)))) "paused before it looked")
    (is (on? w two))))

(deftest a-deploy-that-gets-no-quiet-gives-up-and-lets-relay-run
  (doseq [what ["hub (wake 3)" "a relay tick (pid 41)"]]
    (let [{:keys [outcome] w :world} (deploy (assoc (world) :busy [[what]]))]
      (is (str/includes? (text outcome) "still running"))
      (is (str/includes? (text outcome) what))
      (is (str/includes? (text outcome) "relay is running again"))
      (is (on? w one))
      (is (nil? (:paused w)))
      (is (= :failed (:status (latest w)))))))

(deftest a-live-log-that-cannot-be-read-lifts-the-pause-it-just-made
  (let [{:keys [outcome] w :world} (deploy (assoc (world) :on (fn [w e] (when (= :busy (:op e)) [{:thrown "log is corrupt at line 3"} w]))))]
    (is (str/includes? (text outcome) "corrupt"))
    (is (nil? (:paused w)) "relay is not left paused with nothing done")
    (is (on? w one))))

(deftest ticks-must-be-stopped-some-way-before-an-old-relay-is-involved
  (let [old (assoc (world) :has-pause? false)]
    (is (str/includes? (text (:outcome (deploy old :current-supports? false))) "running now does not know the pause file"))
    (is (str/includes? (text (:outcome (deploy old))) "leave in place"))
    (testing "with ticks stopped another way, it goes ahead"
      (let [{w :world} (deploy old :current-supports? false :paused-externally true)]
        (is (on? w two))
        (testing "a rollback onto a relay that cannot honour the pause needs the same word"
          (is (str/includes? (text (:outcome (rollback w :current-supports? false))) "does not know the pause file"))
          (is (on? (:world (rollback w :current-supports? false :paused-externally true)) one)))))))

(deftest a-pause-from-a-failed-run-survives-a-rollback-that-cannot-start
  (let [w (dissoc (:world (deploy (status-fails (world)))) :on)
        before (:paused w)
        {:keys [outcome] w2 :world} (rollback (assoc w :busy [["hub (wake 3)"]]))]
    (is (str/includes? (text outcome) "still running"))
    (is (str/includes? (text outcome) "still paused"))
    (is (str/includes? (text outcome) "rm '/w/state/paused'") "the wait running out says how to lift it")
    (is (= before (:paused w2)) "left exactly as it was")
    (is (not-any? #(#{:pause :resume} (:op %)) (drop (count (:effects w)) (:effects w2))) "never touched")
    (is (nil? (:paused (:world (rollback (assoc w2 :busy []))))))))

(deftest every-refusal-while-paused-names-the-way-out
  (let [w (dissoc (:world (deploy (status-fails (world)))) :on)]
    (is (str/includes? (text (:outcome (rollback (edit w :relay)))) "rm '/w/state/paused'") "a rollback refused for a dirty checkout")))

;; --- runs that died

(deftest a-killed-rebuild-leaves-a-record-that-rollback-reads-first
  (let [w (:world (deploy (world)))
        ;; The process dies at agentloop's build: what was written by then is all there is.
        {w2 :world} (play (flow/start :deploy (opts w)) w :stop? (fn [e _] (build-agentloop e)))
        last-line (last (:records w2))]
    (is (= :started (:status last-line)))
    (is (= :building (get-in last-line [:repos :agentloop :state])))
    (is (some? (:paused w2)) "left paused")
    (testing "rollback undoes that run (a rebuild in place), not the good deploy before it"
      (let [{w3 :world} (rollback w2)]
        (is (nil? (:paused w3)))
        (is (on? w3 two) "still on the deployed version")
        (testing "the next rollback is of the good deploy"
          (is (on? (:world (rollback w3)) one)))))))

(deftest a-run-killed-while-waiting-leaves-only-a-pause-to-lift
  (let [{w :world} (play (flow/start :deploy (opts (world))) (assoc (world) :busy [["hub (wake 3)"]])
                         :stop? (fn [e _] (= :sleep (:op e))))]
    (is (= :started (:status (last (:records w)))) "its record was written before it paused")
    (is (some? (:paused w)))
    (let [{:keys [outcome] w2 :world} (rollback (assoc w :busy []))
          lines (str/join " " (:lines (first (filter #(= :confirm (:op %)) (drop (count (:effects w)) (:effects w2))))))
          w w2]
      (is (str/includes? lines "stopped before it touched anything"))
      (is (str/includes? lines "only lifts the pause"))
      (is (:lift-only (:ok outcome)))
      (is (nil? (:paused w)))
      (is (on? w one) "nothing was moved")
      (testing "and that closes the record, so a deploy can follow"
        (is (on? (:world (deploy w)) two))))))

(deftest a-kill-between-closing-the-record-and-lifting-the-pause-only-lifts-it
  (let [w (:world (deploy (world)))
        w (-> w
              (update :records conj (deploy/make-record {:id "deploy x" :kind :deploy :at "x" :status :failed
                                                         :failure "stopped before anything was changed"
                                                         :repos (zipmap deploy/repo-keys (repeat {:path "/p" :state :pending}))}))
              (assoc :paused "bb deploy at x"))
        {w2 :world} (rollback w)]
    (is (str/includes? (str/join " " (:lines (first (filter #(= :confirm (:op %)) (drop (count (:effects w)) (:effects w2))))))
                       "only lifts the pause"))
    (is (nil? (:paused w2)))
    (is (on? w2 two) "the good deploy was not undone")))

;; --- interrupts

(defn- op? [op] (fn [e _] (= op (:op e))))

(deftest an-interrupt-before-anything-is-touched-lifts-the-pause-and-the-workspaces
  (testing "while waiting for wakes"
    (let [w (assoc (world) :busy [["hub (wake 3)"]])
          {:keys [print] w :world run :run} (interrupt (flow/start :deploy (opts w)) w (op? :sleep))]
      (is (= "Interrupted before any checkout was touched. Nothing was changed and relay is not paused." print))
      (is (nil? (:paused w)))
      (is (= :failed (:status (latest w))) "the record is closed")
      (is (= "stopped before anything was changed" (:failure (latest w))))
      (is (= "Interrupted before anything was changed." (flow/stopped-text run)))))
  (testing "while the commits are being tested"
    (let [w (world)
          {:keys [print] w :world} (interrupt (flow/start :deploy (opts w)) w (fn [e _] (= ["bb" "test"] (:cmd e))))]
      (is (empty? (:workspaces w)) "the check workspaces were forgotten")
      (is (nil? print) "nothing was paused or recorded yet, so there is nothing to say"))))

(deftest an-interrupt-reports-check-workspaces-it-could-not-forget
  ;; Regression (relay#31): the hook ignored jj's answer and said nothing
  ;; was changed, though a workspace was left in a checkout's list.
  (let [w (assoc (world) :on forget-fails)
        {:keys [print]} (interrupt (flow/start :deploy (opts w)) w (fn [e _] (= ["bb" "test"] (:cmd e))))]
    (is (str/starts-with? print "Interrupted."))
    (is (str/includes? print "could not all be forgotten"))
    (is (str/includes? print "in /w/relay: jj workspace forget deploy-check-relay-1"))))

(deftest an-interrupt-while-a-check-workspace-is-being-made-forgets-it
  ;; The workspace may exist before verification has noted it.
  (let [w (world)
        {w :world} (interrupt (flow/start :deploy (opts w)) w
                              (fn [e _] (and (= :workspace-add (:op e)) (= "/w/relay-tui" (:path e)))))]
    (is (= #{"deploy-check-agentloop-1" "deploy-check-relay-tui-1" "deploy-check-relay-1"}
           (set (map :name (filter #(= :workspace-forget (:op %)) (:effects w)))))
        "forgotten by name, whether or not it was noted as made")))

(deftest a-stop-that-comes-first-keeps-the-run-from-touching-anything
  ;; The stop comes while agentloop gets its last look, so the hook sees the
  ;; run before it marked the touch; the main thread then finds the stop
  ;; and does not make the touch.
  (let [w (world)
        last-look (fn [e _] (and (= :observe (:op e)) (= "/w/agentloop" (:path e))))
        {:keys [print run] w :world} (interrupt (flow/start :deploy (opts w)) w last-look)
        next-run (flow/step run (first (respond w (:effect run))))]
    (is (:touch (:effect next-run)) "the next effect would have touched it")
    (is (= "Interrupted before touching agentloop." (flow/stopped-text next-run)))
    (is (str/includes? print "relay is not paused"))
    (is (nil? (:paused w)) "the stop lifted the pause")
    (is (nil? (first-move w)))))

(deftest a-stop-after-a-touch-keeps-the-pause-and-says-how-to-recover
  (let [w (world)
        {:keys [print run] w :world} (interrupt (flow/start :deploy (opts w)) w (fn [e _] (= ["relay" "status"] (:cmd e))))]
    (is (some? (:paused w)) "still paused when the interrupt came after the moves")
    (is (str/includes? print "Interrupted. relay is PAUSED"))
    (is (str/includes? print "bb rollback"))
    (is (str/includes? print "rm '/w/state/paused'"))
    (is (not= :failed (:status (latest w))) "a touched run's record is not closed as untouched")
    (is (= "Interrupted after a checkout was touched." (flow/stopped-text run)))))

(deftest the-stop-lifts-the-pause-before-it-closes-the-record
  (let [w (assoc (world) :busy [["hub (wake 3)"]])
        {:keys [run world]} (play (flow/start :deploy (opts w)) w :stop? (op? :sleep))
        {w2 :world} (play (flow/interrupt run) world)
        after (map :op (drop (count (:effects world)) (:effects w2)))]
    (is (= [:resume :append-record] after))))

(deftest a-stop-during-a-rollback-of-a-failed-deploy-says-how-to-recover
  (let [w (dissoc (:world (deploy (status-fails (world)))) :on)]
    (testing "an interrupt mid-rollback prints the advice, though the pause was not made by it"
      (let [{:keys [print] w :world} (interrupt (flow/start :rollback (opts w :at "T2")) w (fn [e _] (= :jj-new (:op e))))]
        (is (str/includes? print "Interrupted. relay is PAUSED"))
        (is (some? (:paused w)))))
    (testing "at the confirmation, too, and it stays paused"
      (let [{:keys [print] w :world} (interrupt (flow/start :rollback (opts w :at "T2")) w (op? :confirm))]
        (is (str/includes? print "Interrupted. relay is PAUSED"))
        (is (str/includes? print "rm '/w/state/paused'"))
        (is (some? (:paused w)))))))

(deftest a-late-interrupt-after-the-pause-was-lifted-prints-nothing
  (let [w (world)
        ;; The stop lands just as the pause is being lifted.
        {:keys [print]} (interrupt (flow/start :deploy (opts w)) w (op? :resume))]
    (is (nil? print))))

(deftest the-interrupt-notice-says-paused-only-while-it-is
  ;; Regression (relay#24): a rollback that found relay paused registers the
  ;; notice, and it said PAUSED after the rollback had lifted the pause.
  (let [w (dissoc (:world (deploy (status-fails (world)))) :on)
        {:keys [run world]} (play (flow/start :rollback (opts w :at "T2")) w :stop? (op? :resume))
        [_ world] (respond world (:effect run))
        {:keys [outcome]} (play (flow/interrupt run) world)]
    (is (nil? (:paused world)))
    (is (not (str/includes? (str (:print outcome)) "PAUSED")))))

(deftest an-interrupt-at-any-effect-leaves-a-pause-only-when-a-checkout-was-touched
  ;; Every effect of a whole deploy, a stop just before it: the pause stays
  ;; exactly when a checkout was touched by then, and a stop before the
  ;; touch closes the record.
  (let [w (world)
        n (count (:effects (:world (deploy w))))]
    (doseq [i (range n)]
      (let [{:keys [run world]} (play (flow/start :deploy (opts w)) w :stop? (fn [_ w] (= i (count (:effects w)))))
            {cleaned :world} (play (flow/interrupt run) world)
            ;; The run the hook sees has marked the touch once its effect is the touching one.
            touched? (or (:touch (:effect run)) (some :touch (:effects world)))
            lifted-by-run? (some #(= :resume (:op %)) (:effects world))]
        (is (= (boolean (and touched? (not lifted-by-run?))) (some? (:paused cleaned))) (str "stopped before effect " i))
        (is (empty? (:workspaces cleaned)) (str "stopped before effect " i))))))
