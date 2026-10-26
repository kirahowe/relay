(ns relay.deploy-run-test
  "The shell around relay.deploy-flow: jj, the files and the live log at
  the edge, the lock, the checks on the options, and the gate between the
  main thread and the interrupt hook. One real deploy and rollback check
  the wiring; every decision is tested in relay.deploy-flow-test. Nothing
  here reaches a live checkout: every path is passed in."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.cli]
            [relay.deploy :as deploy]
            [relay.deploy-flow :as flow]
            [relay.deploy-flow-test :as ft]
            [relay.deploy-run :as run]
            [relay.dispatch :as dispatch]
            [relay.inbound :as inbound]
            [relay.pause :as pause]
            [relay.staging :as staging]))

;; --- real jj repositories

(def jj? (some? (fs/which "jj")))

(defn- sh! [dir & cmd]
  (let [r (run/sh dir (vec cmd))]
    (when-not (zero? (:exit r)) (throw (ex-info (str (str/join " " cmd) " failed: " (:err r) (:out r)) r)))
    (str/trim (:out r))))

(defn- commit-id [dir rev]
  (sh! dir "jj" "--no-pager" "log" "--no-graph" "-r" rev "-T" "commit_id"))

(defn- make-repo!
  "A jj repo whose bookmark main is one commit ahead of its working copy:
  version 1 under the working copy, version 2 at main. Both know the pause
  file (src/relay/pause.clj)."
  [dir]
  (fs/create-dirs (str dir "/src/relay"))
  (sh! dir "jj" "--quiet" "git" "init")
  (spit (str dir "/.gitignore") "built\n")
  (spit (str dir "/Makefile") "build:\n\tcp version built\n")
  (spit (str dir "/version") "1\n")
  (spit (str dir "/src/relay/pause.clj") "(ns relay.pause)\n")
  (sh! dir "jj" "commit" "-m" "one")
  (sh! dir "jj" "bookmark" "create" "main" "-r" "@-")
  (let [one (commit-id dir "@-")]
    (spit (str dir "/version") "2\n")
    (sh! dir "jj" "commit" "-m" "two")
    (sh! dir "jj" "bookmark" "set" "main" "-r" "@-")
    (sh! dir "jj" "new" one)
    {:one one :two (commit-id dir "main")}))

(defn- version [dir] (str/trim (slurp (str dir "/version"))))

(defn- workspaces [dir] (str/split-lines (sh! dir "jj" "--no-pager" "workspace" "list")))

;; --- a place to run in

(defn- with-base
  "Runs (f w) with a temporary directory: plain directories for the four
  checkouts, a staging data directory, and where the record and pause go."
  [f]
  (let [base (str (fs/create-temp-dir {:prefix "relay-deploy-run-test"}))
        repos (into {} (for [r deploy/repo-keys] [r (str base "/" (name r))]))]
    (doseq [[_ d] repos] (fs/create-dirs d))
    (try (f {:base base :repos repos
             :data (staging/init! {:dir (str base "/data") :check? false})
             :record (str base "/state/deploys.edn") :pause-file (str base "/state/paused")})
         (finally (fs/delete-tree base)))))

(defn- opts [{:keys [repos record base data pause-file]} & {:as more}]
  (merge {:paths repos :record record :home base :from (:relay repos) :data data :pause-file pause-file
          :confirm (fn [_ _] true) :say (fn [_])
          :poll-ms 20 :settle-ms 0 :quiet-timeout-ms 2000
          :test-cmds [["sh" "-c" "test \"$(cat version)\" = 2"]]
          :status-cmd (fn [path _data] ["cat" (str path "/version")])}
         more))

(defn- threw [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(defn- home-canonical [p] (staging/canonical p (System/getProperty "user.home")))

;; --- jj at the edge

(deftest the-jj-edge-reads-and-moves-a-real-checkout
  (when jj?
    (with-base
      (fn [{:keys [base repos] :as w}]
        (let [d (:relay repos)
              {:keys [one two]} (make-repo! d)
              o (assoc (#'run/settle (opts w)) :data (:data w))
              perform #(run/perform o %)]
          (testing "an observation names the working copy, its parent and the bookmark"
            (let [{:keys [exists? jj? wc parents target]} (run/observe run/sh d "main")]
              (is (and exists? jj?))
              (is (:empty? wc))
              (is (= [one] (map :commit parents)))
              (is (= two (:commit target)))))
          (is (= {:path (str base "/nowhere") :exists? false} (run/observe run/sh (str base "/nowhere") "main")))
          (is (false? (:jj? (run/observe run/sh (:agentloop repos) "main"))) "a directory that is not a repository")
          (is (true? (:present? (perform {:op :has-file :path d :commit two :file "src/relay/pause.clj"}))))
          (is (false? (:present? (perform {:op :has-file :path d :commit one :file "src/relay/no-such.clj"}))))
          (is (:error (perform {:op :commits :path d :rev "no-such-revision"})))
          (testing "a check workspace is made at the commit and forgotten by name"
            (is (zero? (:exit (perform {:op :workspace-add :path d :name "check-x" :commit two :dir (str base "/check/relay")}))))
            (is (= "2" (version (str base "/check/relay"))))
            (is (= 2 (count (workspaces d))))
            (is (zero? (:exit (perform {:op :workspace-forget :path d :name "check-x"}))))
            (is (= 1 (count (workspaces d))))
            (is (zero? (:exit (perform {:op :workspace-forget :path d :name "never-made"}))) "jj only warns for a name it does not know"))
          (testing "jj new moves the checkout, and the empty working copy it left is dropped"
            (let [old (:change (:wc (run/observe run/sh d nil)))]
              (is (zero? (:exit (perform {:op :jj-new :path d :commit two}))))
              (is (= [two] (map :commit (:commits (perform {:op :commits :path d :rev "@-"})))))
              (is (= [] (:commits (perform {:op :commits :path d :rev (str "present(" old ")")}))))
              (is (= "2" (version d))))))))))

(deftest a-real-deploy-and-rollback
  (when jj?
    (with-base
      (fn [{:keys [repos record pause-file] :as w}]
        (let [commits (into {} (for [[r d] repos] [r (make-repo! d)]))
              rec (run/deploy! (opts w))]
          (doseq [[r d] repos]
            (is (= (:two (commits r)) (commit-id d "@-")) (name r))
            (is (= "2" (version d)))
            (is (= 1 (count (workspaces d))) "the check workspace is gone"))
          (is (fs/exists? (str (:agentloop repos) "/built")))
          (is (fs/exists? (str (:relay-tui repos) "/built")))
          (is (fs/exists? (str (:webtool repos) "/built")))
          (is (not (fs/exists? (str (:relay repos) "/built"))))
          (is (= :ok (:status rec)))
          (is (= rec (last (deploy/read-records (slurp record)))))
          (is (nil? (pause/reason pause-file)))
          (let [back (run/rollback! (opts w))]
            (is (= :ok (:status back)))
            (doseq [[r d] repos]
              (is (= (:one (commits r)) (commit-id d "@-")) (name r))
              (is (= "1" (version d))))))))))

;; --- what is checked before anything else

(deftest the-options-are-checked-before-anything-else
  (with-base
    (fn [{:keys [base repos pause-file] :as w}]
      (testing "the data directory"
        (let [refused #(threw (fn [] (run/deploy! (opts w :data %))))]
          (is (str/includes? (refused "") "--data is empty"))
          (is (str/includes? (refused (str base "/no-such-dir")) "not a relay data directory"))
          (fs/create-dirs (str base "/just-a-dir"))
          (is (str/includes? (refused (str base "/just-a-dir")) "not a relay data directory"))
          (fs/create-dirs (str base "/no-log"))
          (spit (str base "/no-log/relay.edn") "{}")
          (is (str/includes? (refused (str base "/no-log")) "not a relay data directory"))
          (is (str/includes? (threw #(run/rollback! (opts w :data ""))) "--data is empty"))))
      (is (str/includes? (threw #(run/deploy! (opts w :paths (assoc repos :relay-tui (:agentloop repos)))))
                         "four different directories"))
      (testing "the record file"
        (fs/create-dirs (str base "/a-directory"))
        (is (str/includes? (threw #(run/deploy! (opts w :record (str base "/a-directory")))) "cannot be written"))
        (fs/create-dirs (str base "/real-sync"))
        (fs/create-sym-link (str base "/Sync") (str base "/real-sync"))
        (fs/create-sym-link (str base "/shortcut") (:relay repos))
        (is (str/includes? (threw #(run/deploy! (opts w :record (str base "/Sync/deploys.edn")))) "inside"))
        (is (str/includes? (threw #(run/deploy! (opts w :record (str base "/real-sync/deploys.edn")))) "inside"))
        (is (str/includes? (threw #(run/deploy! (opts w :record (str (:relay repos) "/deploys.edn")))) "inside a checkout"))
        (is (str/includes? (threw #(run/deploy! (opts w :record (str base "/shortcut/deploys.edn")))) "inside a checkout"))
        (is (not (fs/exists? (str (:relay repos) "/deploys.edn")))))
      (testing "while relay is paused, a refusal says how to lift it"
        (pause/pause! pause-file "bb deploy at x")
        (let [wanted (str "rm '" (home-canonical pause-file) "'")]
          (is (str/includes? (threw #(run/rollback! (opts w :data ""))) wanted))
          (is (str/includes? (threw #(run/deploy! (opts w :data ""))) wanted)))))))

(defn- lock-free-in-another-process? [file]
  (let [script (str "(let [ch (java.nio.channels.FileChannel/open (java.nio.file.Paths/get \"" file "\" (make-array String 0))"
                    " (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE java.nio.file.StandardOpenOption/WRITE]))]"
                    " (println (if (.tryLock ch) \"free\" \"held\")))")]
    (= "free" (str/trim (:out (run/sh "." ["bb" "-e" script]))))))

(deftest only-one-deploy-or-rollback-runs-at-a-time
  (with-base
    (fn [{:keys [base] :as w}]
      (let [file (run/lock-file base)
            held (run/try-lock! file)]
        (testing "a holder of the lock stops both commands"
          (is (some? held))
          (is (str/includes? (threw #(run/deploy! (opts w))) "Another bb deploy or rollback is running"))
          (is (str/includes? (threw #(run/rollback! (opts w))) "Another bb deploy or rollback is running")))
        (testing "another record file or pause file does not get round it"
          (is (str/includes? (threw #(run/deploy! (opts w :record (str base "/elsewhere/deploys.edn")
                                                        :pause-file (str base "/elsewhere/paused"))))
                             "Another bb")))
        (testing "being refused does not let go of the lock, for another process either"
          (is (nil? (run/try-lock! file)))
          (is (not (lock-free-in-another-process? file))))
        (run/release-lock! held)
        (is (lock-free-in-another-process? file) "released for real")))))

(deftest the-pause-is-read-again-once-the-lock-is-held
  ;; Regression (relay#24): the pause was read before the lock, so a pause
  ;; made by a run that held the lock went without its advice.
  (with-base
    (fn [{:keys [pause-file] :as w}]
      (let [real run/try-lock!
            msg (with-redefs [run/try-lock! (fn [f] (pause/pause! pause-file "bb deploy at x") (real f))]
                  (threw #(run/deploy! (opts w))))]
        (is (str/includes? msg "already paused"))
        (is (str/includes? msg (str "rm '" (home-canonical pause-file) "'")) "the refusal names the way out")))))

;; --- the gate between the main thread and the interrupt hook

(defn- world-effects
  "A do-effect over relay.deploy-flow-test's world, kept in an atom. `before`
  runs first for each effect."
  [world before]
  (fn [e] (before e) (let [[r w] (ft/respond @world e)] (reset! world w) r)))

(deftest a-stop-before-the-run-means-the-pause-is-never-made
  (let [world (atom (ft/world))
        do-effect (world-effects world (fn [_]))
        out (#'run/with-exit-cleanup do-effect
             (fn []
               (#'run/run-exit-cleanup!)
               (#'run/drive-main! do-effect (flow/start :deploy (ft/opts @world)))))]
    (is (= {:stopped "Interrupted before anything was changed."} out))
    (is (= [] (:effects @world)) "it did nothing at all")))

(deftest a-stop-while-the-pause-is-being-made-lifts-it
  ;; Regression (relay#24): the hook could run after the pause was claimed
  ;; and before the file was made, lift nothing, and leave the file to be
  ;; made after it.
  (let [world (atom (ft/world))
        hook (atom nil)
        do-effect (world-effects world (fn [e]
                                         (cond
                                           (and (= :pause (:op e)) (nil? @hook))
                                           (do (reset! hook (doto (Thread. #(binding [*out* (java.io.StringWriter.)]
                                                                             (#'run/run-exit-cleanup!)))
                                                              .start))
                                               (Thread/sleep 300))
                                           ;; The gate is not fair: hold the main thread's next
                                           ;; effect until the waiting hook has had its turn, so
                                           ;; the stop lands right after the pause, untouched.
                                           (and @hook (= :busy (:op e)))
                                           (loop [n 0]
                                             (when (and (not (:closing? @@#'run/current)) (< n 500))
                                               (Thread/sleep 10)
                                               (recur (inc n)))))))
        out (#'run/with-exit-cleanup do-effect
             #(#'run/drive-main! do-effect (flow/start :deploy (ft/opts @world))))]
    (.join ^Thread @hook 5000)
    (is (:stopped out))
    (is (some #(= :pause (:op %)) (:effects @world)) "the pause was made")
    (is (nil? (:paused @world)) "and the stop lifted it")
    (is (= :failed (:status (last (:records @world)))) "and closed the record")))

;; --- what is running, from the live log and the tick markers

(deftest what-is-running-comes-from-the-live-log-and-the-tick-markers
  (with-base
    (fn [{:keys [data pause-file] :as w}]
      (let [o (#'run/settle (opts w))
            busy #(#'run/busy-now (assoc o :data data))]
        (is (= [] (busy)))
        (let [sys (inbound/system data)
              id (:seq (first (inbound/submit! sys {:type :wake/start :actor "dispatcher"
                                                    :args (merge {:role "steward" :note "Go." :dispatched true}
                                                                 (dispatch/current-process))})))]
          (is (= [(str "steward (wake " id ")")] (busy)))
          (inbound/submit! sys {:type :wake/end :actor "dispatcher"
                                :args {:wake id :outcome "ran" :exit 0 :session "s" :said ""}}))
        (is (some #(str/starts-with? % "a relay tick (pid ") (pause/while-ticking (home-canonical pause-file) busy)))
        (testing "a log that cannot be read is an error, not quiet"
          (spit (str (first (fs/glob (str data "/log") "*.jsonl"))) "this is not an event\n" :append true)
          (is (str/includes? (:thrown (run/perform (assoc o :data data) {:op :busy})) "corrupt")))))))

(deftest the-final-status-does-not-print-the-pause-advice
  (let [cmd ((run/default-status-cmd "/p/paused") "/r" "/d")]
    (is (= ["/r/bin/relay" "status" "--data" "/d" "--pause-file" "/p/paused.status-check"] cmd)))
  (let [dir (str (fs/create-temp-dir {:prefix "relay-final-status"}))
        file (str dir "/paused")
        data (staging/init! {:dir (str dir "/data") :check? false})]
    (try
      (pause/pause! file "bb deploy at now")
      (let [args (drop 1 ((run/default-status-cmd file) "/r" data))
            shown (str/join "\n" (:out (relay.cli/execute (vec args))))]
        (is (not (str/includes? shown "paused")) "although the pause file is there")
        (is (str/includes? shown "Roles")))
      (finally (fs/delete-tree dir)))))

(deftest a-step-that-throws-cleans-up-as-a-stop-would
  ;; A bug in the flow, or a record it cannot read, must not leave relay
  ;; paused with nothing touched and the record open.
  (let [world (atom (ft/world))
        do-effect (world-effects world (fn [_]))
        real flow/step
        out (with-redefs [flow/step (fn [run r]
                                      (if (= :looked (get-in run [:effect :k]))
                                        (throw (ex-info "boom" {}))
                                        (real run r)))]
              (#'run/with-exit-cleanup do-effect
               #(#'run/drive-main! do-effect (flow/start :deploy (ft/opts @world)))))]
    (is (str/includes? (:failed out) "Unexpected error: boom"))
    (is (str/includes? (:failed out) "relay is not paused"))
    (is (nil? (:paused @world)) "the pause it made is lifted")
    (is (= :failed (:status (last (:records @world)))) "and the record is closed")))

(deftest the-house-commands-are-read-from-the-live-relay-edn
  (with-base
    (fn [{:keys [base data]}]
      (let [edn (str data "/relay.edn")
            original (slurp edn)]
        (is (nil? (run/house-commands "/r" data)) "the staging relay.edn has no :house")
        (spit edn (str (subs original 0 (str/last-index-of original "}")) " :house {:image \"x\"}}"))
        (is (= {:check ["container" "system" "status"] :build ["/r/bin/relay" "house" "build" "--data" data]}
               (run/house-commands "/r" data)))
        (spit edn "{:house")
        (is (nil? (run/house-commands "/r" data)) "unreadable: relay status says why")
        (is (nil? (run/house-commands "/r" (str base "/nowhere"))))))))
