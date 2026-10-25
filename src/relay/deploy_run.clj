(ns relay.deploy-run
  "`bb deploy` and `bb rollback`: the shell around relay.deploy-flow, which
  decides everything. For the operator to run, never an agent:

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
            [relay.deploy-flow :as flow]
            [relay.durable :as durable]
            [relay.pause :as pause]
            [relay.staging :as staging])
  (:import (java.time Instant)))

(defn- usage-error [msg] (ex-info msg {:relay/usage true}))

;; --- jj, at the edge

(defn sh
  "Runs a command in a directory and returns {:exit :out :err}. A command
  that cannot start has exit -1."
  [dir cmd]
  (try (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} cmd)]
         (select-keys r [:exit :out :err]))
       (catch Exception e {:exit -1 :out "" :err (str (ex-message e))})))

(defn- ok? [r] (zero? (:exit r)))

(defn- jj [sh dir & args] (sh dir (into ["jj" "--no-pager"] args)))

(defn read-commits
  "[commits nil] for the commits `rev` names, or [nil error] when jj could
  not say."
  [sh dir rev]
  (let [r (jj sh dir "log" "--no-graph" "-r" rev "-T" deploy/template)]
    (if (ok? r)
      [(deploy/parse-commits (:out r)) nil]
      [nil (->> (str/split-lines (str (:err r) (:out r))) (remove str/blank?) (take-last 2) (str/join "\n"))])))

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

;; --- the record file

(defn read-records [file]
  (if (fs/exists? file) (deploy/read-records (slurp (str file))) []))

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

;; --- running wakes and ticks

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

;; --- performing what the flow asks for

(defn perform
  "Does one of relay.deploy-flow's effects and returns its result. Never
  throws: what goes wrong comes back as {:thrown message}."
  [{:keys [sh say confirm pause-file record] :as o} {:keys [op] :as e}]
  (try
    (case op
      :observe {:observation (observe sh (:path e) (:bookmark e))}
      :observe-all {:observations (into {} (for [[r path] (:paths e)] [r (observe sh path (:bookmark e))]))}
      :has-file (let [r (jj sh (:path e) "file" "list" "-r" (:commit e) (:file e))]
                  {:present? (and (ok? r) (not (str/blank? (:out r))))})
      :workspace-add (do (fs/create-dirs (fs/parent (:dir e)))
                         (jj sh (:path e) "workspace" "add" "--name" (:name e) "-r" (:commit e) (:dir e)))
      :workspace-forget (jj sh (:path e) "workspace" "forget" (:name e))
      :delete-dir (do (fs/delete-tree (:dir e)) {})
      :run (sh (:dir e) (:cmd e))
      :jj-new (jj sh (:path e) "new" (:commit e))
      :commits (let [[cs error] (read-commits sh (:path e) (:rev e))] (if error {:error error} {:commits cs}))
      :say (do (say (:text e)) {})
      :confirm {:confirmed? (boolean (confirm (:lines e) (:verb e)))}
      :append-record (do (append-record! record (:record e)) {})
      :pause (do (pause/pause! pause-file (:why e)) {})
      :resume (do (pause/resume! pause-file) {})
      :busy {:busy (busy-now o) :now (System/currentTimeMillis)}
      :sleep (do (Thread/sleep (long (:ms e))) {})
      :pause-reason {:reason (pause/reason pause-file)})
    (catch Throwable t {:thrown (or (ex-message t) (str (class t)))})))

;; --- an interrupt
;;
;; The JVM runs the shutdown hook on its own thread while the main thread
;; carries on, so the two share `gate`. The main thread, holding it, checks
;; that no stop has come and publishes the run it is about to act on; an
;; effect marked :gated (making the pause file) is performed inside, so a
;; stop cannot fall between the run saying the pause is its own and the file
;; existing. The hook, holding it, marks the stop and takes the run as last
;; published. It then does relay.deploy-flow/interrupt's cleanup for that
;; run, and the main thread stops at its next effect.

(def ^:private gate (Object.))

;; {:do-effect f :run published-run :closing? bool}, or nil between runs. :do-effect
;; does one effect; it is `perform` over the settled options, outside tests.
(def ^:private current (atom nil))

(defn- drive
  "Runs `run` to its outcome with `do-effect`, with no gate: for cleanup."
  [do-effect run]
  (loop [run run]
    (if-let [out (:outcome run)] out (recur (flow/step run (do-effect (:effect run)))))))

(defn- run-exit-cleanup! []
  (let [{:keys [do-effect run closing?]} (locking gate (first (swap-vals! current #(some-> % (assoc :closing? true)))))]
    (when (and run (not closing?))
      (when-let [text (:print (drive do-effect (flow/interrupt run)))]
        (println text)))))

(defn- crashed
  "A step that threw is a bug or a record the flow cannot read. Stops as a
  Ctrl-C would, unless one already did: the cleanup for `run`, the last
  run the flow made, then a failure that says what happened."
  [do-effect run ^Throwable t]
  (let [{:keys [closing?]} (locking gate (first (swap-vals! current assoc :closing? true)))
        cleanup (when-not closing? (:print (drive do-effect (flow/interrupt run))))]
    {:failed (str "Unexpected error: " (or (ex-message t) (str (class t))) ". It stopped as a Ctrl-C would."
                  (when cleanup (str "\n" cleanup)))}))

(defn- drive-main!
  "Runs `run` to its outcome, publishing it to the hook before each
  effect. {:stopped text} when the hook ran first."
  [do-effect run]
  (loop [run run]
    (if-let [out (:outcome run)]
      ;; Published too, so a stop that comes now finds nothing left to do.
      (do (locking gate (swap! current assoc :run run)) out)
      (let [e (:effect run)
            r (locking gate
                (if (:closing? @current)
                  ::stopped
                  (do (swap! current assoc :run run)
                      (if (:gated e) (do-effect e) ::outside))))]
        (if (= ::stopped r)
          {:stopped (flow/stopped-text run)}
          (let [result (if (= ::outside r) (do-effect e) r)
                next-run (try (flow/step run result) (catch Throwable t t))]
            (if (instance? Throwable next-run)
              (crashed do-effect run next-run)
              (recur next-run))))))))

(defn- with-exit-cleanup [do-effect f]
  (let [hook (Thread. ^Runnable run-exit-cleanup!)]
    (reset! current {:do-effect do-effect})
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try (f)
         (finally (reset! current nil)
                  (try (.removeShutdownHook (Runtime/getRuntime) hook)
                       (catch IllegalStateException _ nil))))))

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

;; --- settling the options

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

(defn- preflight!
  "Everything that can be checked before any work: that the three checkouts
  are three places, where the record goes and that it can be written, and
  which data directory to watch, which must be a relay data directory. A
  refusal says how to lift a pause that is already there."
  [{:keys [record sync paths data home pause-file] :as o}]
  (let [refuse! (fn [problem] (throw (usage-error (flow/refusal-text [problem] pause-file (pause/reason pause-file)))))]
    (when-not (= (count deploy/repo-keys) (count (set (vals paths))))
      (refuse! (str "relay, agentloop and relay-tui must be three different directories; got "
                    (str/join ", " (for [r deploy/repo-keys] (str (name r) " " (get paths r)))) ".")))
    (when-let [p (deploy/record-path-problem {:path record :sync sync :repo-paths (vals paths)})]
      (refuse! p))
    (try (durable/append! record "")
         (catch Exception e (refuse! (str "The record file " record " cannot be written: " (ex-message e)))))
    (when (and (some? data) (str/blank? data))
      (refuse! "--data is empty. Pass the live data directory."))
    (let [data (or data (try ((requiring-resolve 'relay.cli/data-dir) {}) (catch Exception _ nil)))]
      (when (str/blank? data)
        (refuse! "Which data directory? Pass --data <dir>; it is read to see which wakes are running."))
      (let [data (staging/canonical data home)]
        (when-not (and (fs/regular-file? (fs/path data "relay.edn")) (fs/directory? (fs/path data "log")))
          (refuse! (str data " is not a relay data directory (it needs relay.edn and a log directory). "
                        "Without the real one a deploy cannot tell whether a wake is running.")))
        (assoc o :data data)))))

(defn- flow-options
  "What relay.deploy-flow/start needs, read once the lock is held."
  [kind {:keys [paths clock data pause-file record status-cmd] :as o} opts]
  (let [suffix (str (System/currentTimeMillis))]
    (merge (select-keys o [:paths :bookmark :record :pause-file :data :test-cmds :poll-ms :settle-ms :quiet-timeout-ms])
           {:at (str (clock))
            :from (str (fs/normalize (fs/absolutize (or (:from opts) "."))))
            :status-cmd (status-cmd (:relay paths) data)
            :suffix suffix
            :check-dir (str (fs/path (fs/temp-dir) (str "relay-deploy-check-" suffix "-" (rand-int 1000000))))
            :paused-externally (boolean (:paused-externally opts))
            :current-supports? (fs/exists? (fs/path (:relay paths) "src" "relay" "pause.clj"))
            :inherited (pause/reason pause-file)}
           (when (= :rollback kind) {:records (read-records record)}))))

;; --- the commands

(defn- run-kind!
  [kind opts]
  (let [o (preflight! (settle opts))
        file (lock-file (:home o))]
    (if-let [l (try-lock! file)]
      (try
        ;; The pause is read again here: a run that held the lock may have
        ;; made or lifted it.
        (let [do-effect #(perform o %)
              out (with-exit-cleanup do-effect #(drive-main! do-effect (flow/start kind (flow-options kind o opts))))]
          (if (:ok out)
            (:ok out)
            (throw (usage-error (or (:stopped out) (:refused out) (:failed out))))))
        (finally (release-lock! l)))
      (throw (usage-error (str "Another bb deploy or rollback is running; it holds " file "."))))))

(defn deploy!
  "Deploys the bookmark. Options: :paths {:relay :agentloop :relay-tui}, :record,
  :bookmark, :data (the live data directory, read for running wakes),
  :from (the working copy it is run from, default the current directory),
  :paused-externally (ticks are stopped some other way), and for tests
  :home, :sh, :confirm, :say, :clock, :test-cmds, :status-cmd, :pause-file,
  :poll-ms, :settle-ms, :quiet-timeout-ms. Returns the record."
  [opts]
  (run-kind! :deploy opts))

(defn rollback!
  "Puts each checkout back where the last deploy found it. Options as for
  deploy!. When the newest run never touched anything it only lifts the
  pause."
  [opts]
  (run-kind! :rollback opts))

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
