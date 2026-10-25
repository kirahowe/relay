(ns relay.deploy-run
  "`bb deploy` and `bb rollback`: the jj, make and relay calls around the
  decisions in relay.deploy. For the operator to run, never an agent:

    bb deploy   [--relay <dir>] [--agentloop <dir>] [--relay-tui <dir>]
                [--bookmark main] [--record <file>] [--data <dir>]
                [--paused-externally]
    bb rollback [same flags]

  The defaults are the live checkouts under ~/code/projects, the record
  ~/Library/Application Support/relay/deploys.edn, and the data directory
  named in ~/.config/relay/config.edn. The data directory is read only, to
  see which wakes are running, and is passed to the final `relay status`.
  One run at a time: it holds an operating system lock on
  ~/Library/Application Support/relay/deploy.lock. If it is stopped (Ctrl-C)
  before a checkout is touched, it lifts the pause it made, forgets the
  check workspaces and says so.

  A deploy first checks that the relay working copy it runs from is clean
  and that every checkout has an empty working copy on a single commit that
  jj can read. It names the bookmark's commit in each repository and from
  then on uses only those ids. It builds and tests them in fresh jj
  workspaces in a temporary directory (relay's `bb test`, the mock
  scenarios among them, against agentloop built from the same commits),
  shows what it will do and asks for a code typed on the terminal.

  Then it pauses relay (relay.pause), so `relay tick` starts nothing, waits
  until no wake or tick is running, writes the record, and moves each
  checkout's empty working copy onto its commit with `jj new <commit>`,
  building agentloop and relay-tui with `make build`, and runs the live
  `relay status`. Each checkout is looked at again just before it is moved,
  and left alone if anything about it changed since the plan or jj cannot
  read it. A write that lands between that look and the move is found
  afterwards and reported. The pause is lifted when all of that worked. If
  it failed after a checkout was touched (a `jj new` that errors counts),
  relay stays paused and the message says so.

  The pause only works for a relay that knows the pause file. Deploy and
  rollback each check that the relay checkout has src/relay/pause.clj now and
  that the commit it will be on has it, and refuse otherwise unless
  --paused-externally says the tick job is stopped another way.

  A rollback moves each checkout back to where the last deploy found it,
  in the same order, but only a checkout that is where that deploy left it
  (or still where it found it). It can be run again after it fails partway,
  and it never lifts a pause that it did not make until it has worked.

  Either appends records to the record file, outside every checkout and
  outside ~/Sync. The record is written before the first checkout moves and
  again as each one does."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [relay.deploy :as deploy]
            [relay.durable :as durable]
            [relay.pause :as pause]
            [relay.staging :as staging])
  (:import (java.time Instant)))

(defn- usage-error [msg] (ex-info msg {:relay/usage true}))

;; --- calls

(defn sh
  "Runs a command in a directory and returns {:exit :out :err}. A command
  that cannot start has exit -1."
  [dir cmd]
  (try (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} cmd)]
         (select-keys r [:exit :out :err]))
       (catch Exception e {:exit -1 :out "" :err (str (ex-message e))})))

(defn- ok? [r] (zero? (:exit r)))

(defn- tail [s n] (->> (str/split-lines (str s)) (remove str/blank?) (take-last n) (str/join "\n")))

(defn- failure [what r]
  (str what " failed (exit " (:exit r) "): " (tail (str (:err r) "\n" (:out r)) 8)))

(defn- jj [sh dir & args] (sh dir (into ["jj" "--no-pager"] args)))

;; --- observing

(defn- read-commits
  "[commits nil] for the commits `rev` names, or [nil error] when jj could
  not say."
  [sh dir rev]
  (let [r (jj sh dir "log" "--no-graph" "-r" rev "-T" deploy/template)]
    (if (ok? r)
      [(deploy/parse-commits (:out r)) nil]
      [nil (tail (str (:err r) (:out r)) 2)])))

(defn- commit-at [sh dir rev] (first (first (read-commits sh dir rev))))

(defn observe
  "What the plans need to know about one checkout. Reading `@` makes jj
  snapshot the working copy, as any jj command does, so changes made since
  the last command are seen. When jj cannot read it the result has :error,
  and nothing is guessed."
  [sh path bookmark]
  (cond
    (not (fs/directory? path)) {:path path :exists? false}
    (not (ok? (sh path ["jj" "--ignore-working-copy" "--no-pager" "root"]))) {:path path :exists? true :jj? false}
    :else
    (let [[wcs wc-error] (read-commits sh path "@")
          [parents parents-error] (read-commits sh path "@-")
          [targets target-error] (if bookmark
                                   (read-commits sh path (str "bookmarks(exact:" (pr-str bookmark) ")"))
                                   [[] nil])
          error (or wc-error parents-error target-error
                    (when (not= 1 (count wcs)) "the working copy is not one commit")
                    (when (< 1 (count targets)) (str "the " bookmark " bookmark is conflicted")))]
      (cond-> {:path path :exists? true :jj? true :wc (first wcs) :parents (or parents [])
               :target (first targets)}
        error (assoc :error error)))))

(defn- observe-all [sh paths bookmark]
  (into {} (for [r deploy/repo-keys] [r (observe sh (get paths r) bookmark)])))

;; --- the record file

(defn read-records [file]
  (if (fs/exists? file) (deploy/read-records (slurp (str file))) []))

(defn read-last-record [file] (last (read-records file)))

(defn- append-record!
  "Appends a record on its own line, even after a line that was cut off."
  [file record]
  (let [before (if (fs/exists? file) (deploy/repair-before-append (slurp (str file))) "")]
    (durable/append! file (str before (deploy/record->line record)))))

;; --- confirming

(defn- tty-confirm
  "Shows `lines` on the terminal and asks for a fresh code typed back.
  Returns true only when it is. With no terminal, false."
  [lines verb]
  (try
    (let [{:keys [say ask]} ((requiring-resolve 'relay.cli/*tty*))
          chars "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
          rng (java.security.SecureRandom.)
          code (apply str (repeatedly 4 #(nth chars (.nextInt rng (count chars)))))]
      (say (str (str/join "\n" lines) "\n\nType " code " to " verb ": "))
      (= code (some-> (ask) str/trim str/upper-case)))
    (catch Exception _ false)))

;; --- cleaning up when interrupted

;; What to undo if the process is stopped (Ctrl-C, a terminal closing). The
;; JVM runs the hook on its own thread while the main thread carries on, so
;; the two settle who goes first with one atomic swap each:
;;
;;   begin-touch!         takes :pause out, unless :closing is already there
;;   run-exit-cleanup!    swaps in :closing and runs what it found
;;
;; If the hook goes first, begin-touch! sees :closing and the run touches
;; nothing, and the hook has lifted the pause. If begin-touch! goes first,
;; the hook finds no :pause and leaves relay paused, as a failure after a
;; touch would, and says so.
;;
;;   :pause       lift the pause this run made
;;   :workspaces  forget the check workspaces; returns how to forget by
;;                hand the ones it could not, or nil
;;   :finish      close the run's record, while nothing has been touched
;;   :notice      say how to recover, shown when the pause stays
;;
;; A stop that lifts :pause says so, since nothing was touched. Registering
;; :pause and making the pause file happen under `pause-gate`, as does the
;; hook's swap, so the hook cannot lift the pause between the two and leave
;; the file to be made after it.
(def ^:private on-exit (atom {}))

(def ^:private pause-gate (Object.))

(defn- begin-touch!
  "Called before the first change to a checkout. True when it may go ahead."
  []
  (let [[before _] (swap-vals! on-exit (fn [m] (if (:closing m) m (dissoc m :pause :finish))))]
    (not (:closing before))))

(defn- run-exit-cleanup! []
  (let [[before _] (locking pause-gate (swap-vals! on-exit (constantly {:closing true})))
        run! (fn [k] (when-let [g (get before k)] (try (g) true (catch Throwable _ false))))
        ;; The pause first: a kill between the two then leaves a closed record
        ;; and no pause, which is safe, not a pause with a closed record.
        lifted? (run! :pause)
        ;; :workspaces returns what it could not forget, as a message.
        stuck (when-let [g (:workspaces before)]
                (try (let [m (g)] (when (string? m) m)) (catch Throwable _ nil)))]
    (run! :finish)
    (cond
      (:closing before) nil
      (and lifted? stuck) (println (str "Interrupted before any checkout was touched. relay is not paused.\n" stuck))
      lifted? (println "Interrupted before any checkout was touched. Nothing was changed and relay is not paused.")
      :else (do (when stuck (println (str "Interrupted. " stuck)))
                (when-let [n (:notice before)] (try (n) (catch Throwable _ nil)))))))

(defn- with-exit-cleanup [f]
  (let [hook (Thread. ^Runnable run-exit-cleanup!)]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try (f)
         (finally (reset! on-exit {})
                  (try (.removeShutdownHook (Runtime/getRuntime) hook)
                       (catch IllegalStateException _ nil))))))

;; --- verifying the commits in fresh workspaces

(defn- verify!
  "Builds the planned commits of every repo in fresh jj workspaces under a
  temporary directory, then runs relay's tests, scenarios included,
  there against agentloop built from its commit. Nothing is changed in
  the live checkouts but jj's list of workspaces, which is put back; if
  that cannot be, this throws and says how to put it back by hand."
  [{:keys [sh paths test-cmds say]} steps]
  (let [commit-of (into {} (for [{:keys [repo to]} steps] [repo (:commit to)]))
        tmp (str (fs/create-temp-dir {:prefix "relay-deploy-check-"}))
        suffix (str (System/currentTimeMillis))
        names (into {} (for [r deploy/repo-keys] [r (str "deploy-check-" (name r) "-" suffix)]))
        dirs (into {} (for [r deploy/repo-keys] [r (str (fs/path tmp (name r)))]))
        made (atom [])
        problem (atom nil)
        run! (fn [what cmd dir]
               (when-not @problem
                 (let [res (sh dir cmd)]
                   (when-not (ok? res) (reset! problem (failure what res))))))
        ;; Forgets by name every repo's workspace, so one made but not yet
        ;; noted is forgotten too: jj exits 0 with a warning for a name it
        ;; does not know. Only a failure for one known to be made is news,
        ;; and comes back as the commands to run by hand. A temp directory
        ;; that will not delete is reported too, and never hides those.
        forget-all! (fn []
                      (let [stuck (vec (for [r deploy/repo-keys
                                             :let [res (jj sh (get paths r) "workspace" "forget" (names r))]
                                             :when (and (not (ok? res)) (some #{r} @made))]
                                         (str "  in " (get paths r) ": jj workspace forget " (names r))))
                            undeleted (try (fs/delete-tree tmp) nil
                                           (catch Exception e
                                             (str "The check directory " tmp " could not be deleted: " (ex-message e))))
                            lines (cond-> []
                                    (seq stuck) (conj (str "The check workspaces could not all be forgotten. Run these by hand:\n"
                                                           (str/join "\n" stuck)))
                                    undeleted (conj undeleted))]
                        (when (seq lines) (str/join "\n" lines))))]
    ;; Registered before any is made; the interrupt hook reports what it returns.
    (swap! on-exit assoc :workspaces forget-all!)
    (try
      (doseq [r deploy/repo-keys :while (nil? @problem)]
        (let [res (jj sh (get paths r) "workspace" "add" "--name" (names r) "-r" (commit-of r) (dirs r))]
          (if (ok? res)
            (swap! made conj r)
            (reset! problem (failure (str "Making a workspace of " (name r) " at " (commit-of r)) res)))))
      (doseq [r [:agentloop :relay-tui]]
        (when-not @problem (say (str "Building " (name r) " at " (subs (commit-of r) 0 12) " in a fresh workspace")))
        (run! (str (name r) " would not build") (deploy/builds r) (dirs r)))
      (doseq [cmd test-cmds]
        (when-not @problem (say (str "Running " (str/join " " cmd) " on relay at " (subs (commit-of :relay) 0 12))))
        (run! (str (str/join " " cmd) " on relay") cmd (dirs :relay)))
      (finally
        (swap! on-exit dissoc :workspaces)
        (when-let [stuck (forget-all!)]
          (swap! problem #(str (when % (str % "\n")) stuck)))))
    (when @problem (throw (usage-error (str "Nothing was changed in the checkouts.\n" @problem))))))

;; --- waiting for wakes and ticks to finish

(defn- busy-now
  "What is running: wakes (from the live log, read only) and ticks (from
  their markers), as short descriptions."
  [{:keys [data pause-file]}]
  (let [state ((requiring-resolve 'relay.core/replay) ((requiring-resolve 'relay.log/read-events) data))
        alive? (requiring-resolve 'relay.dispatch/alive?)]
    (vec (concat (for [{:keys [id role chat]} (deploy/busy-wakes state (fn [{:keys [pid pid-start]}] (alive? pid pid-start)))]
                   (str role (when chat " (conversation)") " (wake " id ")"))
                 (for [{:keys [pid]} (pause/ticks-running pause-file)]
                   (str "a relay tick (pid " pid ")"))))))

(defn- wait-quiet!
  "Waits until nothing has been running for `settle-ms`, then returns nil.
  Returns what is still running when `quiet-timeout-ms` is up. The settle
  time is also what lets a wake that a tick has just started show up in the
  log."
  [{:keys [poll-ms settle-ms quiet-timeout-ms say] :as o}]
  (let [started (System/currentTimeMillis)]
    (loop [quiet-since nil told? false]
      (let [busy (busy-now o)
            now (System/currentTimeMillis)]
        (cond
          (and (empty? busy) quiet-since (>= (- now quiet-since) settle-ms)) nil
          (> (- now started) quiet-timeout-ms) busy
          :else (do (when (and (seq busy) (not told?))
                      (say (str "Waiting for " (str/join ", " busy))))
                    (Thread/sleep (long poll-ms))
                    (recur (when (empty? busy) (or quiet-since now)) (or told? (seq busy)))))))))

;; --- one run at a time

;; The lock this process holds, or nil. A second channel opened on a locked
;; file and then closed would drop the process's lock on it (POSIX locks
;; belong to the process, not the channel), so while one is held another
;; attempt is refused without opening anything.
(def ^:private held (atom nil))

(defn lock-file
  "The one lock every deploy and rollback takes."
  [home]
  (str home "/Library/Application Support/relay/deploy.lock"))

(defn try-lock!
  "Takes an operating system lock on `file`, making it if need be, and
  returns the lock to give to release-lock!, or nil when someone holds it,
  this process included. The system drops the lock when its holder dies, so
  there is no stale lock to judge and no file to delete."
  [file]
  (fs/create-dirs (fs/parent file))
  (let [[before _] (swap-vals! held #(or % ::opening))]
    (when-not before
      (try
        (let [ch (java.nio.channels.FileChannel/open
                  (fs/path file)
                  (into-array java.nio.file.OpenOption
                              [java.nio.file.StandardOpenOption/CREATE java.nio.file.StandardOpenOption/WRITE]))
              lock (try (.tryLock ch) (catch Throwable t (.close ch) (throw t)))]
          (if lock
            (reset! held {:ch ch :lock lock})
            (do (.close ch) (reset! held nil) nil)))
        (catch Throwable t (reset! held nil) (throw t))))))

(defn release-lock! [{:keys [ch lock]}]
  (try (.release lock) (catch Exception _ nil))
  (try (.close ch) (catch Exception _ nil))
  (reset! held nil))

(defn- with-lock [{:keys [home]} f]
  (let [file (lock-file home)]
    (if-let [l (try-lock! file)]
      (try (with-exit-cleanup f) (finally (release-lock! l)))
      (throw (usage-error (str "Another bb deploy or rollback is running; it holds " file "."))))))

;; --- applying a plan

(defn- start-record
  "The record for a run, before anything has moved."
  [{:keys [id kind of at inherited]} steps]
  {:id id :kind kind :of of :at at :status :started :inherited inherited
   :repos (into {} (for [{:keys [repo path observed from to]} steps]
                     [repo {:path path :wc (:wc observed) :previous from :target to :new nil :state :pending}]))})

(defn- move!
  "Moves one checkout with `jj new`, recording that it is about to. Returns
  a failure text or nil."
  [{:keys [sh say]} rec save! {:keys [repo path observed to]}]
  (say (str "Moving " (name repo) " to " (subs (:commit to) 0 12)))
  (swap! rec assoc-in [:repos repo :state] :moving)
  (save!)
  (let [r (jj sh path "new" (:commit to))
        landed (commit-at sh path "@-")
        ;; The planned working copy was empty, so jj drops it once left. If
        ;; it is still there with content, an edit arrived between the last
        ;; look and the move. present() is empty for a change that is gone
        ;; and an error only when jj could not look.
        [kept kept-error] (when (ok? r)
                            (read-commits sh path (str "present(" (:change (:wc observed)) ")")))]
    (cond
      ;; jj can fail after it has recorded its operation, leaving the
      ;; working copy stale, so a failed move is treated as a move.
      (not (ok? r)) (str (failure (str "jj new in " path) r)
                         "\nIt may have changed the working copy anyway. If jj says it is stale, run "
                         "`jj workspace update-stale` in " path ".")
      :else
      (do (swap! rec update-in [:repos repo] assoc :new (deploy/ids landed) :state :moved)
          (save!)
          (cond
            (not (deploy/same-commit? landed to))
            (str (name repo) " landed on " (deploy/short-id landed) ", not on " (deploy/short-id to) ".")
            kept-error
            (str "jj could not say whether something was written to " path " while it moved (" kept-error
                 "). Look at change " (:change (:wc observed)) " there.")
            (some (complement :empty?) kept)
            (str "Something was written to " path " while it moved. jj kept it in change "
                 (:change (first (remove :empty? kept))) ", which is not in the new working copy. "
                 "Find it with `jj log -r " (:change (first (remove :empty? kept))) "`."))))))

(defn- apply-plan!
  "Moves, builds and checks each step, in order, writing the record as it
  goes. Returns {:failure text-or-nil :touched? bool :rec rec}, the record
  not yet finished. Never throws: whatever goes wrong becomes the failure.
  Stops at the first."
  [{:keys [sh status-cmd say record data] :as o} steps rec0]
  (let [rec (atom rec0)
        fail (atom nil)
        status-only? (atom false)
        save! (fn [] (append-record! record (deploy/make-record @rec)))]
    (try
      (doseq [{:keys [repo path moves? observed build] :as step} steps
              :while (nil? @fail)]
        ;; Just before this checkout moves: is it still the one that was
        ;; planned, with an empty working copy?
        (let [now (observe sh path nil)
              changes (deploy/observation-changes repo (dissoc observed :target) now)]
          (cond
            (seq changes)
            (reset! fail (str "Stopped before touching " (name repo) ": " (str/join " " changes)))
            ;; From here an interrupt must leave the pause alone.
            (and (or moves? build) (not (begin-touch!)))
            (reset! fail (str "Interrupted before touching " (name repo) "."))
            :else
            (do
              (when moves? (reset! fail (move! o rec save! step)))
              (when (and (nil? @fail) build)
                (say (str "Building " (name repo)))
                ;; A build rewrites files whether or not the revision moved,
                ;; so a failed one leaves the checkout touched.
                (swap! rec assoc-in [:repos repo :state] :building)
                (save!)
                (let [r (sh path build)]
                  (when-not (ok? r)
                    (reset! fail (failure (str (name repo) ": " (str/join " " build)) r)))))
              ;; Moved and, where it has a build, built.
              (when-not @fail
                (swap! rec assoc-in [:repos repo :state] (if (or moves? build) :built :kept))
                (save!))))))
      (when-not @fail
        (let [relay-path (:path (first (filter #(= :relay (:repo %)) steps)))
              _ (say "Running relay status")
              r (sh relay-path (status-cmd relay-path data))]
          (say (str/trim (str (:out r))))
          (when-not (ok? r)
            (reset! status-only? true)
            (reset! fail (failure "relay status" r)))))
      (catch Exception e (reset! fail (str "Unexpected error: " (ex-message e)))))
    {:failure @fail :touched? (deploy/touched? @rec) :status-only? @status-only? :rec @rec}))

;; --- what to tell the operator

(defn- paused-advice
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

;; --- pausing around it

(declare refuse!)

(defn- paused-notice
  "What an interrupt prints when this run is not the one that will lift the
  pause: how to recover, if the pause file is there."
  [{:keys [pause-file kind]}]
  #(when (pause/reason pause-file)
     (println (str "Interrupted. " (paused-advice pause-file kind false)))))

(defn- register-notice!
  "Registers the advice an interrupt prints when relay is paused and this run
  is not the one that will lift it."
  [o]
  (swap! on-exit (fn [m] (if (:closing m) m (assoc m :notice (paused-notice o))))))

(defn- under-pause
  "Pauses relay, waits for wakes and ticks to finish, runs `f` and lifts the
  pause. `f` throws to refuse before changing anything, or returns
  apply-plan!'s map. Anything that goes wrong before a checkout is touched,
  the wait included, lifts the pause again. The pause stays when `f` failed
  after a checkout was touched. A pause that was already there (a failed
  deploy's) is never lifted by anything but a run that works, since the
  machine may be mixed. `o` carries :kind, :deploy or :rollback. The run's
  record is already written, since a pause made here must have one."
  [{:keys [pause-file say] :as o} why f]
  (let [inherited? (some? (pause/reason pause-file))
        lift! #(when-not inherited? (pause/resume! pause-file))
        forget! #(swap! on-exit dissoc :pause :notice :finish)
        notice (paused-notice o)]
    ;; Registered before the file exists, so a stop at any moment can lift
    ;; it; and the file is only made if that registration took. The gate
    ;; holds a stop back until the file is made, so it lifts that file.
    (locking pause-gate
      (let [[before _] (swap-vals! on-exit (fn [m] (if (:closing m) m
                                                       (cond-> (assoc m :notice notice)
                                                         (not inherited?) (assoc :pause lift!)))))]
        (when (:closing before) (throw (usage-error "Interrupted before anything was changed.")))
        (when-not inherited? (pause/pause! pause-file why))))
    (let [out (try
                (when (:closing @on-exit)
                  (throw (usage-error "Interrupted before anything was changed.")))
                (when-let [busy (wait-quiet! o)]
                  (refuse! [(str "These were still running after " (quot (:quiet-timeout-ms o) 1000) " seconds: "
                                 (str/join ", " busy) ". "
                                 (if inherited? "relay is still paused." "relay is running again."))]))
                (f)
                (catch Throwable t
                  ;; Lift only if no checkout was touched: the same swap the
                  ;; hook and begin-touch! use says whether :pause is still ours.
                  (let [[before _] (swap-vals! on-exit dissoc :pause :notice :finish)]
                    (when (:pause before) (lift!)))
                  (throw t)))]
      (cond
        (not (:failure out))
        ;; Lifted before the run is recorded as finished, so a crash in
        ;; between leaves a record a retry can complete, not a finished one
        ;; with relay still paused.
        (try (swap! on-exit dissoc :notice) (pause/resume! pause-file) (forget!) out
             (catch Exception e
               (assoc out :touched? true
                      :failure (str "relay could not be unpaused: " (ex-message e) ". Remove " pause-file " by hand."))))
        (:touched? out) (do (forget!)
                            (say (str "relay stays paused (" pause-file "): a checkout was touched."))
                            out)
        :else (do (forget!) (lift!) out)))))

;; The first record is written before relay is paused, so every pause made
;; here has one for a rollback to read. A refusal or a stop before anything is
;; touched closes it as failed; a kill leaves it :started, which a rollback
;; reads as "stopped before touching anything: only lift the pause".
(defn- guarded-run
  [{:keys [record] :as o} rec0 why f]
  (let [close! (fn [msg] (append-record! record (deploy/make-record (assoc rec0 :status :failed :failure msg))))]
    (append-record! record (deploy/make-record rec0))
    (swap! on-exit (fn [m] (if (:closing m) m (assoc m :finish #(close! "stopped before anything was changed")))))
    (try (under-pause o why f)
         (catch clojure.lang.ExceptionInfo e
           (swap! on-exit dissoc :finish)
           (close! (ex-message e))
           (throw e)))))

;; --- the commands

(defn- defaults [home]
  {:paths {:relay (str home "/code/projects/relay")
           :agentloop (str home "/code/projects/agentloop")
           :relay-tui (str home "/code/projects/relay-tui")}
   :record (str home "/Library/Application Support/relay/deploys.edn")
   :pause-file (pause/default-file home)
   :bookmark "main"
   :test-cmds [["bb" "test"]]
   :poll-ms 2000 :settle-ms 3000 :quiet-timeout-ms 600000})

(defn default-status-cmd
  "The command that checks relay at the end of a run. It points at a pause
  file that is not there, so it does not print the recovery advice for the
  pause this very run is holding just before it says Deployed."
  [pause-file]
  (fn [relay-path data]
    [(str relay-path "/bin/relay") "status" "--data" data "--pause-file" (str pause-file ".status-check")]))

(defn- settle
  "Options over the defaults, with every path absolute and with links
  resolved."
  [opts]
  (let [home (or (:home opts) (System/getProperty "user.home"))
        d (defaults home)
        o (merge d (dissoc opts :paths))
        canon #(staging/canonical % home)]
    (assoc o
           :home home
           :sync (canon (str home "/Sync"))
           :sh (or (:sh opts) sh)
           :say (or (:say opts) println)
           :confirm (or (:confirm opts) tty-confirm)
           :clock (or (:clock opts) #(Instant/now))
           :paths (into {} (for [r deploy/repo-keys] [r (canon (get (merge (:paths d) (:paths opts)) r))]))
           :record (canon (:record o))
           :pause-file (canon (:pause-file o))
           :status-cmd (or (:status-cmd opts) (default-status-cmd (canon (:pause-file o)))))))

(def ^:dynamic *inherited-pause*
  "The pause file when relay was already paused as this run began (a failed
  deploy's), else nil. A refusal then says how to lift it. Read once for
  the checks before the lock and again once it is held, since a run that
  held it may have made or lifted the pause in between."
  nil)

(defn- paused-file [pause-file] (when (pause/reason pause-file) pause-file))

(defn- refuse! [problems]
  (throw (usage-error (str "Nothing was changed.\n"
                           (str/join "\n" (map #(str "  " %) (concat problems (when *inherited-pause* (pause/recovery-lines *inherited-pause*)))))))))

(defn- preflight!
  "Everything that can be checked before any work: that the three checkouts
  are three places, where the record goes and that it can be written, and
  which data directory to watch, which must be a relay data directory."
  [{:keys [record sync paths data home] :as o}]
  (when-not (= (count deploy/repo-keys) (count (set (vals paths))))
    (refuse! [(str "relay, agentloop and relay-tui must be three different directories; got "
                   (str/join ", " (for [r deploy/repo-keys] (str (name r) " " (get paths r)))) ".")]))
  (when-let [p (deploy/record-path-problem {:path record :sync sync :repo-paths (vals paths)})]
    (refuse! [p]))
  (try (durable/append! record "")
       (catch Exception e (refuse! [(str "The record file " record " cannot be written: " (ex-message e))])))
  (when (and (some? data) (str/blank? data))
    (refuse! ["--data is empty. Pass the live data directory."]))
  (let [data (or data (try ((requiring-resolve 'relay.cli/data-dir) {}) (catch Exception _ nil)))]
    (when (str/blank? data)
      (refuse! ["Which data directory? Pass --data <dir>; it is read to see which wakes are running."]))
    (let [data (staging/canonical data home)]
      (when-not (and (fs/regular-file? (fs/path data "relay.edn")) (fs/directory? (fs/path data "log")))
        (refuse! [(str data " is not a relay data directory (it needs relay.edn and a log directory). "
                       "Without the real one a deploy cannot tell whether a wake is running.")]))
      (assoc o :data data))))

(defn- supports-pause-now? [{:keys [paths]}]
  (fs/exists? (fs/path (:relay paths) "src" "relay" "pause.clj")))

(defn- supports-pause-at? [{:keys [sh paths]} commit]
  (let [r (jj sh (:relay paths) "file" "list" "-r" commit "src/relay/pause.clj")]
    (and (ok? r) (not (str/blank? (:out r))))))

(defn- finished
  "Writes the run's last record and returns it, or throws saying how the run
  failed."
  [{:keys [pause-file record]} kind {:keys [failure rec status-only?]}]
  (let [final (deploy/make-record (assoc rec :status (if failure :failed :ok) :failure failure))
        written (try (append-record! record final) nil
                     (catch Exception e (ex-message e)))
        paused? (pause/reason pause-file)]
    (when (or failure written)
      (throw (ex-info (str (str/upper-case (name kind)) (if failure " FAILED. " " DONE, BUT ")
                           (or failure (str "its last record could not be written (" written "). The checkouts are as intended."))
                           (when (and failure written) (str " Its last record could not be written either (" written ").")) "\n"
                           (if status-only?
                             (if (= :rollback kind)
                               "Every checkout is back where the deploy found it and was rebuilt. Only `relay status` failed."
                               "Every checkout reached its target and was built. Only `relay status` failed.")
                             "The checkouts are as far as it got; the record file lists where each was before.")
                           (when paused? (str "\n" (paused-advice pause-file kind status-only?))))
                      {:relay/usage true})))
    final))

(defn deploy!
  "Deploys the bookmark. Options: :paths {:relay :agentloop :relay-tui}, :record,
  :bookmark, :data (the live data directory, read for running wakes),
  :from (the working copy it is run from, default the current directory),
  :paused-externally (ticks are stopped some other way), and for tests
  :home, :sh, :confirm, :say, :clock, :test-cmds, :status-cmd, :pause-file,
  :poll-ms, :settle-ms, :quiet-timeout-ms. Returns the record."
  [opts]
  (let [o0 (assoc (settle opts) :kind :deploy)]
   (binding [*inherited-pause* (paused-file (:pause-file o0))]
    (let [{:keys [sh paths bookmark say confirm pause-file clock record] :as o} (preflight! o0)]
    (with-lock
      o
      (fn []
       (binding [*inherited-pause* (paused-file (:pause-file o))]
        (when *inherited-pause* (register-notice! o))
        (let [from (str (fs/normalize (fs/absolutize (or (:from opts) "."))))
              at (str (clock))]
          (when-let [why (pause/reason pause-file)]
            (refuse! [(str "relay is already paused (" pause-file "): " why)
                      "If a deploy failed partway, run bb rollback; it lifts the pause when it succeeds."]))
          (let [here (observe sh from nil)]
            (cond
              (not (:jj? here)) (refuse! [(str "You ran this from " from ", which is not a jj working copy.")])
              (:error here) (refuse! [(str "jj could not read " from ": " (:error here))])
              (not (:empty? (:wc here)))
              (refuse! [(str "The relay working copy you ran this from (" from ") has changes. Commit or abandon them first.")])))
          (let [plan (deploy/plan-deploy {:observed (observe-all sh paths bookmark) :bookmark bookmark})]
            (when (:problems plan) (refuse! (:problems plan)))
            (when-let [p (deploy/pause-problem
                          {:current-supports? (supports-pause-now? o) :external? (:paused-externally opts)
                           :target-supports? (supports-pause-at? o (:commit (:to (first (filter #(= :relay (:repo %)) (:steps plan))))))})]
              (refuse! [p]))
            (verify! o (:steps plan))
            (when-not (confirm (concat [(str "Deploy " bookmark " to the live checkouts (the commits that were just tested):")]
                                       (deploy/describe-plan plan))
                               "deploy")
              (refuse! ["Not confirmed on the terminal."]))
            (let [out (guarded-run
                       o (start-record {:id (str "deploy " at) :kind :deploy :at at} (:steps plan)) (str "bb deploy at " at)
                       (fn []
                         ;; The confirmation, and the wait for wakes, took a while:
                         ;; refuse if anything is not as it was tested.
                         (let [now (observe-all sh paths bookmark)
                               changes (vec (mapcat (fn [{:keys [repo observed]}]
                                                      (deploy/observation-changes repo observed (get now repo)))
                                                    (:steps plan)))]
                           (when (seq changes) (refuse! (cons "A checkout changed since it was tested:" changes))))
                         (apply-plan! o (:steps plan) (start-record {:id (str "deploy " at) :kind :deploy :at at} (:steps plan)))))
                  rec (finished o :deploy out)]
              (say (str "Deployed. Records are in " record "."))
              rec))))))))))

(defn- lift-only!
  "The newest run stopped before it touched anything, so there is nothing to
  move. Lifts the pause and closes that run's record."
  [{:keys [pause-file confirm say record clock]} run]
  (let [at (str (clock))
        paused? (pause/reason pause-file)]
    (when-not (confirm [(str "The last run (" (:id run) ") stopped before it touched anything, so there is nothing to roll back.")
                        (if paused?
                          (str "This only lifts the pause (" pause-file "): " paused?)
                          "relay is not paused either; this only closes that run's record.")]
                       "lift the pause")
      (refuse! ["Not confirmed on the terminal."]))
    (pause/resume! pause-file)
    (let [rec (deploy/make-record {:id (str "rollback " at) :kind :rollback :of (:id run) :at at
                                   :status :ok :lift-only true :repos {}})]
      (append-record! record rec)
      (say (if paused? "Lifted the pause. Nothing was moved." "Closed the record. Nothing was moved."))
      rec)))

(defn rollback!
  "Puts each checkout back where the last deploy found it. Options as for
  deploy!. When the newest run never touched anything it only lifts the
  pause."
  [opts]
  (let [o0 (assoc (settle opts) :kind :rollback)]
   (binding [*inherited-pause* (paused-file (:pause-file o0))]
    (let [{:keys [sh paths say confirm record clock] :as o} (preflight! o0)]
    (with-lock
      o
      (fn []
       (binding [*inherited-pause* (paused-file (:pause-file o))]
        (when *inherited-pause* (register-notice! o))
        (let [at (str (clock))
              {:keys [problem lift-only] source :record} (deploy/rollback-source (read-records record) (some? (pause/reason (:pause-file o))))]
          (when problem (refuse! [problem]))
          (if lift-only
            (lift-only! o lift-only)
            (let [plan (deploy/plan-rollback {:observed (observe-all sh paths nil) :record source :paths paths})]
              (when (:problems plan) (refuse! (:problems plan)))
              (when-let [p (deploy/pause-problem
                            {:current-supports? (supports-pause-now? o) :external? (:paused-externally opts)
                             :target-supports? (supports-pause-at? o (:commit (:to (first (filter #(= :relay (:repo %)) (:steps plan))))))})]
                (refuse! [p]))
              (when-not (confirm (concat [(str "Roll back to before the deploy of " (:at source) ":")]
                                         (deploy/describe-plan plan))
                                 "roll back")
                (refuse! ["Not confirmed on the terminal."]))
              ;; In the same order as a deploy: what relay runs on first, relay
              ;; last, so the relay that knows the pause file is the one moved last.
              (let [steps (:steps plan)
                    out (guarded-run
                         o (start-record {:id (str "rollback " at) :kind :rollback :of (:id source) :at at
                                          :inherited (some? *inherited-pause*)}
                                         steps)
                         (str "bb rollback at " at)
                         (fn []
                           (let [now (observe-all sh paths nil)
                                 changes (vec (mapcat (fn [{:keys [repo observed]}]
                                                        (deploy/observation-changes repo (dissoc observed :target) (get now repo)))
                                                      steps))]
                             (when (seq changes) (refuse! (cons "A checkout changed since the plan:" changes))))
                           (apply-plan! o steps (start-record {:id (str "rollback " at) :kind :rollback :of (:id source) :at at
                                                               :inherited (some? *inherited-pause*)}
                                                              steps))))
                    rec (finished o :rollback out)]
                (say (str "Rolled back. Records are in " record "."))
                rec)))))))))))

;; --- bb tasks

(def ^:private spec {:coerce {:relay :string :agentloop :string :relay-tui :string
                              :bookmark :string :record :string :data :string
                              :paused-externally :boolean}})

(defn- options [args]
  (let [{o :opts} (cli/parse-args args spec)]
    (cond-> {}
      (:bookmark o) (assoc :bookmark (:bookmark o))
      (:record o) (assoc :record (:record o))
      (:data o) (assoc :data (:data o))
      (:paused-externally o) (assoc :paused-externally true)
      (seq (select-keys o [:relay :agentloop :relay-tui])) (assoc :paths (select-keys o [:relay :agentloop :relay-tui])))))

(defn- run-command [f args]
  (try (f (options args))
       (System/exit 0)
       (catch clojure.lang.ExceptionInfo e
         (binding [*out* *err*] (println (ex-message e)))
         (System/exit 1))))

(defn deploy-main [& args] (run-command deploy! args))
(defn rollback-main [& args] (run-command rollback! args))
