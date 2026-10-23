(ns relay.deploy-test
  "Deploy and rollback: the pure decisions, and the whole commands against
  temporary jj repositories. Nothing here reaches a live checkout: every
  path is passed in."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [babashka.process]
            [relay.cli]
            [relay.deploy :as deploy]
            [relay.deploy-run :as run]
            [relay.dispatch :as dispatch]
            [relay.inbound :as inbound]
            [relay.pause :as pause]
            [relay.staging :as staging]))

;; --- the pure decisions

(def hex (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "0123456789abcdef")) 8 12)))
(def commit (gen/fmap (fn [[c ch]] {:commit c :change ch}) (gen/tuple hex hex)))

(def observation
  (gen/fmap (fn [[wc parents target empty? exists? jj?]]
              {:path "/x" :exists? exists? :jj? jj?
               :wc (assoc wc :empty? empty? :conflict? false)
               :parents parents :target target})
            (gen/tuple commit (gen/vector commit 0 3) (gen/one-of [commit (gen/return nil)])
                       (gen/frequency [[4 (gen/return true)] [1 (gen/return false)]])
                       (gen/frequency [[9 (gen/return true)] [1 (gen/return false)]])
                       (gen/frequency [[9 (gen/return true)] [1 (gen/return false)]]))))

(def world (gen/fmap #(zipmap deploy/repo-keys %) (gen/vector observation 3)))

(deftest a-deploy-moves-only-empty-single-parent-checkouts-and-only-when-all-can-move
  (let [r (tc/quick-check
           500
           (prop/for-all [observed world]
             (let [{:keys [problems steps]} (deploy/plan-deploy {:observed observed :bookmark "main"})
                   movable? (fn [o] (and (:exists? o) (:jj? o) (:empty? (:wc o)) (:target o)
                                         (= 1 (count (:parents o)))))]
               (if (every? movable? (vals observed))
                 (and (nil? problems)
                      (= deploy/repo-keys (map :repo steps))
                      (every? #(= (deploy/ids (get-in observed [(:repo %) :target])) (:to %)) steps))
                 (and (seq problems) (nil? steps))))))]
    (is (:pass? r) (pr-str (:shrunk r)))))

(def c1 {:commit "aaa" :change "xxx"})
(def c2 {:commit "bbb" :change "yyy"})
(def c3 {:commit "ccc" :change "zzz"})
(defn- checkout [parent & {:as more}]
  (merge {:path "/x" :exists? true :jj? true :wc {:commit "ddd" :change "www" :empty? true :conflict? false}
          :parents [parent] :target c2}
         more))

(deftest a-checkout-already-on-main-does-not-move
  (let [o (checkout c2)
        {:keys [steps]} (deploy/plan-deploy {:observed {:relay o :agentloop o :relay-tui o} :bookmark "main"})]
    (is (every? (comp false? :moves?) steps))))

(deftest problems-say-which-checkout-and-why
  (let [ok (checkout c1)
        problems #(:problems (deploy/plan-deploy {:observed (merge {:relay ok :agentloop ok :relay-tui ok} %) :bookmark "main"}))]
    (is (str/includes? (first (problems {:agentloop (assoc-in ok [:wc :empty?] false)})) "agentloop: its working copy has changes"))
    (is (str/includes? (first (problems {:relay-tui {:path "/gone" :exists? false}})) "does not exist"))
    (is (str/includes? (first (problems {:relay (assoc ok :jj? false)})) "not a jj repository"))
    (is (str/includes? (first (problems {:relay (assoc ok :target nil)})) "no main bookmark"))
    (testing "a working copy on a merge is refused: a rollback could not restore it"
      (is (str/includes? (first (problems {:relay (assoc ok :parents [c1 c3])})) "is a merge")))
    (testing "every problem is listed, not just the first"
      (is (= 2 (count (problems {:relay (assoc ok :target nil) :agentloop (assoc-in ok [:wc :empty?] false)})))))))

(deftest a-checkout-that-changed-since-the-plan-is-noticed
  (let [planned (checkout c1)
        changes #(deploy/observation-changes :relay planned %)]
    (is (empty? (changes planned)))
    (testing "an edit makes the working copy a different commit"
      (is (str/includes? (first (changes (assoc-in planned [:wc :commit] "eee"))) "working copy changed")))
    (testing "moved by someone else"
      (is (str/includes? (first (changes (assoc planned :parents [c3]))) "is now on")))
    (testing "the bookmark moved after the test"
      (is (str/includes? (first (changes (assoc planned :target c3))) "bookmark moved")))
    (is (seq (changes {:path "/x" :exists? false})))))

(defn- record-for [kind id & {:as more}]
  (deploy/make-record
   (merge {:id id :kind kind :at id :status :ok
           :repos (zipmap deploy/repo-keys
                          (repeat {:path "/x" :previous c1 :target c2 :new c2 :state :built :wc {:commit "ddd" :change "www"}}))}
          more)))

(deftest a-rollback-needs-a-deploy-that-is-not-already-rolled-back
  (is (seq (:problem (deploy/rollback-source []))))
  (let [d (record-for :deploy "d1")]
    (is (= d (:record (deploy/rollback-source [d]))))
    (testing "a finished rollback of it leaves nothing to do"
      (is (str/includes? (:problem (deploy/rollback-source [d (record-for :rollback "r1" :of "d1")])) "already rolled back")))
    (testing "a rollback that failed does not: it can be run again, to the same target"
      (is (= d (:record (deploy/rollback-source [d (record-for :rollback "r1" :of "d1" :status :failed)])))))
    (testing "a rollback that stopped at its first line, then finished, counts as finished"
      (is (some? (:problem (deploy/rollback-source [d (record-for :rollback "r1" :of "d1" :status :started)
                                                    (record-for :rollback "r1" :of "d1" :status :ok)])))))
    (testing "with an earlier deploy still open, a rollback of the later one leads back to it"
      (let [d2 (record-for :deploy "d2")]
        (is (= d (:record (deploy/rollback-source [d d2 (record-for :rollback "r2" :of "d2")]))))))
    (testing "a later deploy is the one to roll back"
      (let [d2 (record-for :deploy "d2")]
        (is (= d2 (:record (deploy/rollback-source [d (record-for :rollback "r1" :of "d1") d2]))))))
    (testing "an interrupted deploy, with only its first line, can be rolled back"
      (is (= (record-for :deploy "d3" :status :started)
             (:record (deploy/rollback-source [(record-for :deploy "d3" :status :started)])))))))

(deftest a-rollback-only-touches-the-checkouts-the-record-names-where-it-left-them
  (let [paths (zipmap deploy/repo-keys (repeat "/x"))
        record (record-for :deploy "d1")
        observed (fn [parent] (zipmap deploy/repo-keys (repeat (checkout parent))))
        plan #(deploy/plan-rollback {:observed %1 :record record :paths (or %2 paths)})]
    (let [{:keys [steps]} (plan (observed c2) nil)]
      (is (every? #(= c1 (:to %)) steps))
      (is (every? :moves? steps)))
    (testing "a checkout still where the deploy found it has nothing to undo"
      (is (every? (comp false? :moves?) (:steps (plan (observed c1) nil)))))
    (testing "a checkout somewhere else is not rolled back over"
      (is (str/includes? (first (:problems (plan (observed c3) nil))) "Something else moved it")))
    (testing "the record is for other paths"
      (is (str/includes? (first (:problems (plan (observed c2) (assoc paths :relay "/y")))) "the record is for /x, not /y")))
    (testing "a dirty checkout"
      (is (seq (:problems (plan (assoc-in (observed c2) [:relay :wc :empty?] false) nil)))))
    (testing "a deploy that stopped partway left some on the new commit and some on the old"
      (let [mixed (assoc (observed c2) :agentloop (checkout c1))
            {:keys [steps]} (plan mixed nil)]
        (is (= [false true true] (map :moves? steps)))))))

(deftest records-round-trip-and-the-last-line-of-an-id-wins
  (let [r (tc/quick-check
           200
           (prop/for-all [a commit b commit junk gen/string-alphanumeric]
             (let [rec (fn [id status] (deploy/make-record
                                        {:id id :kind :deploy :at "t" :status status
                                         :repos {:relay {:path "/x" :previous a :target b :new b :wc a :state :moved}}}))
                   started (rec "d1" :started)
                   done (rec "d1" :ok)
                   other (rec "d2" :ok)
                   text (str (deploy/record->line started) (deploy/record->line other) (deploy/record->line done))]
               (and (= [started other done] (deploy/read-records text))
                    (= [other done] (deploy/latest-per-id (deploy/read-records text)))
                    (= [done] (deploy/read-records (str (deploy/record->line done) "(((" junk "\n"))) ; a damaged tail is skipped
                    (= [] (deploy/read-records ""))))))]
    (is (:pass? r) (pr-str (:shrunk r)))))

(deftest jj-lines-are-read-back
  (is (= {:commit "abc123" :change "xyzxyz" :empty? true :conflict? false}
         (deploy/parse-commit "abc123 xyzxyz empty clean\n")))
  (is (= {:commit "abc123" :change "xyzxyz" :empty? false :conflict? true}
         (deploy/parse-commit "abc123 xyzxyz changed conflict")))
  (is (nil? (deploy/parse-commit "Error: no such revision")))
  (is (= 2 (count (deploy/parse-commits "abc123 xyzxyz empty clean\ndef456 uvwuvw changed clean\n")))))

(deftest only-wakes-with-a-live-process-hold-a-deploy-up
  (let [state {:wakes {1 {:id 1 :role "hub" :status :running :chat false :pid 10}
                       2 {:id 2 :role "clerk" :status :running :chat true :pid 11}
                       3 {:id 3 :role "hub" :status :ended :pid 12}}}]
    (is (= [{:id 1 :role "hub" :chat false} {:id 2 :role "clerk" :chat true}]
           (deploy/busy-wakes state (constantly true))))
    (is (= [{:id 2 :role "clerk" :chat true}] (deploy/busy-wakes state #(= 11 (:pid %)))))
    (is (= [] (deploy/busy-wakes state (constantly false))))))

(deftest the-record-stays-out-of-sync-and-the-checkouts
  (let [p #(deploy/record-path-problem {:path % :sync "/Users/op/Sync" :repo-paths ["/Users/op/code/projects/relay"]})]
    (is (nil? (p "/Users/op/Library/Application Support/relay/deploys.edn")))
    (is (some? (p "/Users/op/Sync/relay/deploys.edn")))
    (is (some? (p "/Users/op/code/projects/relay/deploys.edn")))
    (is (nil? (p "/Users/op/code/projects/relay2/deploys.edn")))))

;; --- against real jj repositories

(def jj? (some? (fs/which "jj")))

(defn- sh! [dir & cmd]
  (let [r (run/sh dir (vec cmd))]
    (when-not (zero? (:exit r)) (throw (ex-info (str (str/join " " cmd) " failed: " (:err r) (:out r)) r)))
    (str/trim (:out r))))

(defn- commit-id [dir rev]
  (sh! dir "jj" "--no-pager" "log" "--no-graph" "-r" rev "-T" "commit_id"))

(def makefile "build:\n\tcp version built\n")

(defn- make-repo!
  "A jj repo whose bookmark main is one commit ahead of its working copy:
  version 1 under the working copy, version 2 at main. The relay that
  knows the pause file has src/relay/pause.clj; pass :pause-aware? false
  for one that does not."
  [dir & {:keys [pause-aware? pause-in-target?] :or {pause-aware? true pause-in-target? true}}]
  (fs/create-dirs dir)
  (sh! dir "jj" "--quiet" "git" "init")
  (when pause-aware?
    (fs/create-dirs (str dir "/src/relay"))
    (spit (str dir "/src/relay/pause.clj") "(ns relay.pause)\n"))
  (spit (str dir "/.gitignore") "built\n")
  (spit (str dir "/Makefile") makefile)
  (spit (str dir "/version") "1\n")
  (sh! dir "jj" "commit" "-m" "one")
  (sh! dir "jj" "bookmark" "create" "main" "-r" "@-")
  (let [one (commit-id dir "@-")]
    (spit (str dir "/version") "2\n")
    (when-not pause-in-target? (fs/delete-if-exists (str dir "/src/relay/pause.clj")))
    (sh! dir "jj" "commit" "-m" "two")
    (sh! dir "jj" "bookmark" "set" "main" "-r" "@-")
    (sh! dir "jj" "new" one)
    {:one (commit-id dir "@-") :two (commit-id dir "main")}))

(defn- with-world [f & {:as repo-opts}]
  (let [base (str (fs/create-temp-dir {:prefix "relay-deploy-test"}))
        repos (into {} (for [r deploy/repo-keys] [r (str base "/" (name r))]))
        commits (into {} (for [[r d] repos] [r (apply make-repo! d (mapcat identity repo-opts))]))
        data (staging/init! {:dir (str base "/data") :check? false})]
    (try (f {:base base :repos repos :commits commits :data data
             :record (str base "/state/deploys.edn") :pause-file (str base "/state/paused")})
         (finally (fs/delete-tree base)))))

(defn- version [dir] (str/trim (slurp (str dir "/version"))))

(defn- opts [{:keys [repos record base data pause-file]} & {:as more}]
  (merge {:paths repos :record record :home base :from (:relay repos) :data data :pause-file pause-file
          :confirm (fn [_ _] true) :say (fn [_])
          :poll-ms 20 :settle-ms 0 :quiet-timeout-ms 2000
          :test-cmds [["sh" "-c" "test \"$(cat version)\" = 2"]]
          :status-cmd (fn [path _data] ["cat" (str path "/version")])}
         more))

(defn- threw [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(defn- workspaces [dir] (sh! dir "jj" "--no-pager" "workspace" "list"))

(defn- on-previous? [{:keys [repos commits]}]
  (every? (fn [[r d]] (= (:one (commits r)) (commit-id d "@-"))) repos))

(defn- hooked-sh
  "run/sh, calling `hook` with the directory and command first."
  [hook]
  (fn [dir cmd] (hook dir cmd) (run/sh dir cmd)))

(defn- records [{:keys [record]}] (deploy/read-records (slurp record)))

(deftest deploy-moves-every-checkout-to-main-and-rollback-puts-them-back
  (when jj?
    (with-world
      (fn [{:keys [repos commits record pause-file] :as w}]
        (let [paused-during (atom nil)
              rec (run/deploy! (opts w :status-cmd (fn [path _] (reset! paused-during (pause/reason pause-file))
                                                       ["cat" (str path "/version")])))]
          (testing "each checkout is on main, with an empty working copy"
            (doseq [[r d] repos]
              (is (= (:two (commits r)) (commit-id d "@-")) (name r))
              (is (= "2" (version d)))
              (is (= "true" (sh! d "jj" "--no-pager" "log" "--no-graph" "-r" "@" "-T" "empty"))))
            (testing "the agentloop and relay-tui were built"
              (is (fs/exists? (str (:agentloop repos) "/built")))
              (is (fs/exists? (str (:relay-tui repos) "/built")))
              (is (not (fs/exists? (str (:relay repos) "/built"))))))
          (testing "relay was paused while it moved, and runs again afterwards"
            (is (some? @paused-during))
            (is (nil? (pause/reason pause-file))))
          (testing "the record has the previous and new commit of each"
            (is (= :ok (:status rec)))
            (is (= rec (last (deploy/read-records (slurp record)))))
            (doseq [r deploy/repo-keys]
              (is (= (:one (commits r)) (get-in rec [:repos r :previous :commit])))
              (is (= (:two (commits r)) (get-in rec [:repos r :new :commit])))
              (is (= (:two (commits r)) (get-in rec [:repos r :target :commit])))
              (is (= :built (get-in rec [:repos r :state])))
              (is (string? (get-in rec [:repos r :wc :commit])))))
          (testing "it was recorded before anything moved, and as each checkout did"
            (let [lines (records w)]
              (is (= :started (:status (first lines))))
              (is (every? #(= :pending (:state %)) (vals (:repos (first lines)))))
              (is (= (:one (commits :relay)) (get-in (first lines) [:repos :relay :previous :commit])))
              (is (< 3 (count lines)))))
          (testing "the check workspaces are gone"
            (doseq [[_ d] repos]
              (is (= 1 (count (str/split-lines (workspaces d)))) (workspaces d))))
          (testing "rollback goes back and records it"
            (let [back (run/rollback! (opts w))]
              (doseq [[r d] repos]
                (is (= (:one (commits r)) (commit-id d "@-")) (name r))
                (is (= "1" (version d))))
              (is (= :rollback (:kind back)))
              (is (= :ok (:status back)))
              (is (nil? (pause/reason pause-file)))))
          (testing "a second rollback has nothing to undo"
            (is (str/includes? (threw #(run/rollback! (opts w))) "already rolled back"))))))))

(deftest a-checkout-with-changes-stops-the-deploy-before-anything-moves
  (when jj?
    (with-world
      (fn [{:keys [repos record pause-file] :as w}]
        (spit (str (:agentloop repos) "/scratch") "edit")
        (let [msg (threw #(run/deploy! (opts w)))]
          (is (str/includes? msg "agentloop: its working copy has changes"))
          (is (str/includes? msg "Nothing was changed")))
        (is (on-previous? w))
        (is (nil? (pause/reason pause-file)))
        (is (= [] (records w)) "nothing but the check that it can be written")))))

(deftest the-working-copy-it-runs-from-must-be-clean
  (when jj?
    (with-world
      (fn [{:keys [base] :as w}]
        (fs/create-dirs (str base "/elsewhere"))
        (sh! (str base "/elsewhere") "jj" "--quiet" "git" "init")
        (spit (str base "/elsewhere/dirty") "x")
        (is (str/includes? (threw #(run/deploy! (opts w :from (str base "/elsewhere")))) "has changes"))
        (is (str/includes? (threw #(run/deploy! (opts w :from base))) "not a jj working copy"))))))

(deftest failing-tests-or-a-missing-confirmation-stop-the-deploy
  (when jj?
    (with-world
      (fn [{:keys [repos pause-file] :as w}]
        (is (str/includes? (threw #(run/deploy! (opts w :test-cmds [["false"]]))) "on relay failed"))
        (is (on-previous? w))
        (is (str/includes? (threw #(run/deploy! (opts w :confirm (fn [_ _] false)))) "Not confirmed"))
        (is (on-previous? w))
        (is (nil? (pause/reason pause-file)))
        (doseq [[_ d] repos]
          (is (= 1 (count (str/split-lines (workspaces d)))) "the check workspaces are cleaned up even on failure"))))))

(deftest a-failing-relay-status-fails-loudly-leaves-relay-paused-and-can-be-rolled-back
  (when jj?
    (with-world
      (fn [{:keys [repos commits record pause-file] :as w}]
        (let [msg (threw #(run/deploy! (opts w :status-cmd (fn [_ _] ["sh" "-c" "echo broken >&2; exit 3"]))))]
          (is (str/includes? msg "DEPLOY FAILED"))
          (is (str/includes? msg "relay status failed (exit 3)"))
          (is (str/includes? msg "PAUSED"))
          (is (str/includes? msg "bb rollback")))
        (is (some? (pause/reason pause-file)) "checkouts moved, so relay stays paused")
        (let [rec (last (records w))]
          (is (= :failed (:status rec)))
          (is (= (:one (commits :relay)) (get-in rec [:repos :relay :previous :commit]))))
        (testing "deploying again is refused while it is paused"
          (is (str/includes? (threw #(run/deploy! (opts w))) "already paused")))
        (run/rollback! (opts w))
        (is (on-previous? w))
        (is (nil? (pause/reason pause-file)) "a rollback that works lifts the pause")))))

(deftest the-record-is-written-before-the-first-move-so-a-crash-leaves-a-way-back
  (when jj?
    (with-world
      (fn [{:keys [commits pause-file] :as w}]
        ;; The process dies (here: throws) while building relay-tui, after
        ;; agentloop has moved.
        (let [crash (hooked-sh (fn [dir cmd]
                                 (when (and (= ["make" "build"] cmd) (str/ends-with? dir "/relay-tui")
                                            (not (str/includes? dir "relay-deploy-check")))
                                   (throw (ex-info "killed" {})))))
              msg (threw #(run/deploy! (opts w :sh crash)))]
          (is (str/includes? msg "DEPLOY FAILED")))
        (let [lines (records w)]
          (is (= :started (:status (first lines))))
          (is (= (:one (commits :relay)) (get-in (first lines) [:repos :relay :previous :commit]))))
        (is (some? (pause/reason pause-file)))
        (run/rollback! (opts w))
        (is (on-previous? w))))))

(deftest a-record-file-that-cannot-be-written-is-found-before-anything-moves
  (when jj?
    (with-world
      (fn [{:keys [base] :as w}]
        (fs/create-dirs (str base "/a-directory"))
        (is (str/includes? (threw #(run/deploy! (opts w :record (str base "/a-directory")))) "cannot be written"))
        (is (on-previous? w))))))

(deftest a-failed-rollback-can-be-run-again-to-the-same-target
  (when jj?
    (with-world
      (fn [{:keys [repos commits pause-file] :as w}]
        (run/deploy! (opts w))
        (let [fail-once (atom true)
              flaky (hooked-sh (fn [dir cmd]
                                 (when (and (= ["make" "build"] cmd) (str/ends-with? dir "/relay-tui") @fail-once)
                                   (reset! fail-once false)
                                   (throw (ex-info "build broke" {})))))
              msg (threw #(run/rollback! (opts w :sh flaky)))]
          (is (str/includes? msg "ROLLBACK FAILED"))
          (is (= ["1" "1" "2"] (map #(version (get repos %)) [:agentloop :relay-tui :relay]))
              "agentloop and relay-tui went back (relay-tui's build failed), relay has not yet")
          (is (some? (pause/reason pause-file))))
        (run/rollback! (opts w))
        (is (on-previous? w))
        (is (= ["1" "1" "1"] (map #(version (get repos %)) [:agentloop :relay-tui :relay])))
        (is (nil? (pause/reason pause-file)))
        (is (str/includes? (threw #(run/rollback! (opts w))) "already rolled back"))))))

(deftest an-edit-made-while-the-operator-confirms-stops-the-move
  (when jj?
    (with-world
      (fn [{:keys [repos pause-file] :as w}]
        (let [msg (threw #(run/deploy! (opts w :confirm (fn [_ _] (spit (str (:relay-tui repos) "/edit") "mine") true))))]
          (is (str/includes? msg "A checkout changed since it was tested"))
          (is (str/includes? msg "relay-tui's working copy changed")))
        (is (= "mine" (slurp (str (:relay-tui repos) "/edit"))) "the edit is still in the working copy")
        (is (on-previous? (assoc w :repos (dissoc repos :relay-tui))))
        (is (nil? (pause/reason pause-file)) "nothing moved, so relay runs again")))))

(deftest an-edit-made-during-a-build-stops-the-checkout-it-was-made-in
  (when jj?
    (with-world
      (fn [{:keys [repos commits pause-file] :as w}]
        ;; While agentloop builds, the operator edits relay, which moves last.
        (let [edit (hooked-sh (fn [dir cmd]
                                (when (and (= ["make" "build"] cmd) (str/ends-with? dir "/agentloop")
                                           (not (str/includes? dir "relay-deploy-check")))
                                  (spit (str (:relay repos) "/mine") "work"))))
              msg (threw #(run/deploy! (opts w :sh edit)))]
          (is (str/includes? msg "Stopped before touching relay"))
          (is (str/includes? msg "PAUSED")))
        (is (= "work" (slurp (str (:relay repos) "/mine"))))
        (is (= (:one (commits :relay)) (commit-id (:relay repos) "@-")) "relay was not moved")
        (is (= (:two (commits :agentloop)) (commit-id (:agentloop repos) "@-")))
        (is (some? (pause/reason pause-file)) "agentloop moved and relay did not: mixed")))))

(deftest an-edit-made-while-the-operator-confirms-a-rollback-stops-it
  (when jj?
    (with-world
      (fn [{:keys [repos] :as w}]
        (run/deploy! (opts w))
        (let [msg (threw #(run/rollback! (opts w :confirm (fn [_ _] (spit (str (:relay repos) "/mine") "work") true))))]
          (is (str/includes? msg "A checkout changed since the plan")))
        (is (= "work" (slurp (str (:relay repos) "/mine"))))
        (is (= "2" (version (:agentloop repos))) "nothing was rolled back")))))

(deftest what-is-installed-is-what-was-tested
  (when jj?
    (with-world
      (fn [{:keys [repos commits pause-file] :as w}]
        ;; main moves in agentloop after verification, during confirmation.
        (let [msg (threw #(run/deploy!
                           (opts w :confirm (fn [_ _]
                                              (let [d (:agentloop repos)]
                                                (sh! d "jj" "new" "main")
                                                (spit (str d "/version") "3\n")
                                                (sh! d "jj" "commit" "-m" "three")
                                                (sh! d "jj" "bookmark" "set" "main" "-r" "@-")
                                                (sh! d "jj" "new" (:one (commits :agentloop)))
                                                true)))))]
          (is (str/includes? msg "bookmark moved")))
        (is (on-previous? w))
        (is (nil? (pause/reason pause-file)))))))

(deftest the-tested-commit-is-the-one-that-was-planned
  (when jj?
    (with-world
      (fn [{:keys [repos commits] :as w}]
        (let [seen (atom nil)]
          (run/deploy! (opts w :test-cmds [["sh" "-c" "cat version"]]
                             :say (fn [s] (swap! seen conj s))))
          (is (some #(str/includes? % (subs (:two (commits :relay)) 0 12)) @seen)))))))

(deftest a-working-copy-on-a-merge-is-refused
  (when jj?
    (with-world
      (fn [{:keys [repos commits] :as w}]
        (let [d (:relay repos)]
          (sh! d "jj" "new" (:one (commits :relay)))
          (spit (str d "/side") "x")
          (sh! d "jj" "commit" "-m" "side")
          (let [side (commit-id d "@-")]
            (sh! d "jj" "new" (:one (commits :relay)) side))
          (is (= 2 (count (str/split-lines (sh! d "jj" "--no-pager" "log" "--no-graph" "-r" "@-" "-T" "commit_id ++ \"\\n\"")))))
          (is (str/includes? (threw #(run/deploy! (opts w))) "is a merge")))))))

(deftest a-rollback-is-bound-to-the-checkouts-the-record-names
  (when jj?
    (with-world
      (fn [{:keys [repos commits base] :as w}]
        (run/deploy! (opts w))
        (testing "something else moved a checkout after the deploy"
          (sh! (:agentloop repos) "jj" "new" (:one (commits :agentloop)))
          (spit (str (:agentloop repos) "/other") "x")
          (sh! (:agentloop repos) "jj" "commit" "-m" "other")
          (let [msg (threw #(run/rollback! (opts w)))]
            (is (str/includes? msg "Something else moved it"))
            (is (str/includes? msg "agentloop"))))
        (testing "other checkouts with the same history are not the ones recorded"
          (let [other (str base "/other-world")
                paths (into {} (for [r deploy/repo-keys] [r (str other "/" (name r))]))]
            (doseq [[_ d] paths] (make-repo! d))
            (sh! (:agentloop repos) "jj" "new" (:two (commits :agentloop)))
            (let [msg (threw #(run/rollback! (opts w :paths paths)))]
              (is (str/includes? msg "the record is for")))))))))

(deftest the-record-may-not-go-in-sync-or-a-checkout-even-through-a-link
  (when jj?
    (with-world
      (fn [{:keys [repos base] :as w}]
        (fs/create-dirs (str base "/real-sync"))
        (fs/create-sym-link (str base "/Sync") (str base "/real-sync"))
        (fs/create-sym-link (str base "/shortcut") (:relay repos))
        (is (str/includes? (threw #(run/deploy! (opts w :record (str base "/Sync/deploys.edn")))) "inside"))
        (is (str/includes? (threw #(run/deploy! (opts w :record (str base "/real-sync/deploys.edn")))) "inside"))
        (is (str/includes? (threw #(run/deploy! (opts w :record (str (:relay repos) "/deploys.edn")))) "inside a checkout"))
        (is (str/includes? (threw #(run/deploy! (opts w :record (str base "/shortcut/deploys.edn")))) "inside a checkout"))
        (is (not (fs/exists? (str (:relay repos) "/deploys.edn"))))
        (is (on-previous? w))))))

(deftest check-workspaces-that-cannot-be-forgotten-are-reported
  (when jj?
    (with-world
      (fn [{:keys [repos] :as w}]
        (let [stuck (hooked-sh (fn [_ _]))
              no-forget (fn [dir cmd]
                          (if (some #{"forget"} cmd) {:exit 1 :out "" :err "locked"} (run/sh dir cmd)))
              msg (threw #(run/deploy! (opts w :sh no-forget)))]
          (is (str/includes? msg "could not all be forgotten"))
          (is (str/includes? msg "jj workspace forget deploy-check-"))
          (is (str/includes? msg (:relay repos))))
        (is (on-previous? w))))))

(deftest a-check-directory-that-will-not-delete-does-not-hide-stuck-workspaces
  ;; Regression (relay#33): the delete threw before the message was built.
  (when jj?
    (with-world
      (fn [{:keys [repos] :as w}]
        (let [no-forget (fn [dir cmd]
                          (if (some #{"forget"} cmd) {:exit 1 :out "" :err "locked"} (run/sh dir cmd)))
              msg (with-redefs [fs/delete-tree (fn [_] (throw (ex-info "disk says no" {})))]
                    (threw #(run/deploy! (opts w :sh no-forget))))]
          (is (str/includes? msg "could not all be forgotten"))
          (is (str/includes? msg (:relay repos)))
          (is (str/includes? msg "could not be deleted: disk says no")))
        (is (on-previous? w))))))

(deftest an-interrupt-reports-check-workspaces-it-could-not-forget
  ;; Regression (relay#31): the hook ignored jj's answer and said nothing
  ;; was changed, though a workspace was left in a checkout's list.
  (when jj?
    (with-world
      (fn [{:keys [repos] :as w}]
        (let [printed (atom nil)
              sh (fn [dir cmd]
                   (cond
                     (some #{"forget"} cmd) {:exit 1 :out "" :err "locked"}
                     (= ["interrupt-here"] cmd) (do (reset! printed (with-out-str (#'run/run-exit-cleanup!)))
                                                    {:exit 1 :out "" :err "interrupted"})
                     :else (run/sh dir cmd)))]
          (threw #(run/deploy! (opts w :sh sh :test-cmds [["interrupt-here"]])))
          (is (str/starts-with? @printed "Interrupted."))
          (is (str/includes? @printed "could not all be forgotten"))
          (is (str/includes? @printed (str (fs/file-name (:relay repos)) ": jj workspace forget deploy-check-relay-")))
          (is (on-previous? w))))))
  (testing "after the pause was made, it says the pause was lifted and nothing else was changed"
    (let [on-exit @#'run/on-exit]
      (try
        (reset! on-exit {:pause (constantly nil) :workspaces (constantly "Run these by hand: jj workspace forget x")})
        (let [printed (with-out-str (#'run/run-exit-cleanup!))]
          (is (str/includes? printed "relay is not paused"))
          (is (not (str/includes? printed "Nothing was changed")))
          (is (str/includes? printed "jj workspace forget x")))
        (finally (reset! on-exit {}))))))

;; --- coordinating with wakes

(defn- start-wake! [data]
  (let [sys (inbound/system data)
        events (inbound/submit! sys {:type :wake/start :actor "dispatcher"
                                     :args (merge {:role "hub" :note "Go." :dispatched true} (dispatch/current-process))})]
    {:sys sys :id (:seq (first events))}))

(defn- end-wake! [{:keys [sys id]}]
  (inbound/submit! sys {:type :wake/end :actor "dispatcher"
                        :args {:wake id :outcome "ran" :exit 0 :session "s" :said ""}}))

(deftest a-deploy-waits-for-running-wakes-to-finish
  (when jj?
    (with-world
      (fn [{:keys [data pause-file] :as w}]
        (let [wake (start-wake! data)
              paused-while-waiting (atom nil)
              ;; The deploy verifies first, then pauses; end the wake a little
              ;; after the pause shows.
              done (future (loop [n 0]
                             (when (and (nil? (pause/reason pause-file)) (< n 1000))
                               (Thread/sleep 20)
                               (recur (inc n))))
                           (Thread/sleep 200)
                           (reset! paused-while-waiting (pause/reason pause-file))
                           (end-wake! wake))]
          (run/deploy! (opts w))
          @done
          (is (some? @paused-while-waiting) "relay was already paused while the wake was running")
          (is (not (on-previous? w)))
          (is (nil? (pause/reason pause-file))))))))

(deftest a-deploy-that-gets-no-quiet-gives-up-and-lets-relay-run
  (when jj?
    (with-world
      (fn [{:keys [data pause-file] :as w}]
        (let [wake (start-wake! data)
              msg (threw #(run/deploy! (opts w :quiet-timeout-ms 200)))]
          (is (str/includes? msg "still running"))
          (is (str/includes? msg "hub (wake"))
          (is (on-previous? w))
          (is (nil? (pause/reason pause-file)))
          (end-wake! wake))))))


;; --- the holes round 2 found

(deftest an-observation-jj-could-not-read-is-never-taken-as-unchanged
  (let [planned (checkout c1)]
    (is (seq (deploy/observation-changes :relay planned (assoc planned :wc nil :parents [] :error "stale"))))
    (is (str/includes? (first (deploy/observation-changes :relay planned (assoc planned :error "stale"))) "could not be read"))
    (is (str/includes? (first (:problems (deploy/plan-deploy
                                          {:observed (zipmap deploy/repo-keys (repeat (assoc planned :error "stale")))
                                           :bookmark "main"})))
                       "update-stale"))))

(deftest the-pause-gate-needs-both-the-relay-now-and-the-relay-left-in-place
  (let [p #(deploy/pause-problem (merge {:current-supports? true :target-supports? true} %))]
    (is (nil? (p {})))
    (is (str/includes? (p {:current-supports? false}) "running now"))
    (is (str/includes? (p {:target-supports? false}) "leave in place"))
    (is (some? (p {:current-supports? false :target-supports? false})))
    (testing "ticks stopped another way make it moot"
      (is (every? nil? (for [c [true false] t [true false]]
                         (p {:current-supports? c :target-supports? t :external? true})))))))

(deftest a-cut-off-line-in-the-record-file-cannot-swallow-the-next-record
  (let [r (tc/quick-check
           200
           (prop/for-all [a commit junk (gen/such-that seq gen/string-alphanumeric)]
             (let [rec (fn [id] (deploy/make-record {:id id :kind :deploy :at "t" :status :started
                                                     :repos {:relay {:path "/x" :previous a :state :pending}}}))
                   append (fn [text record] (str text (deploy/repair-before-append text) (deploy/record->line record)))
                   old-line (deploy/record->line (rec "old"))
                   ;; the old run died partway through writing its line, or just before its newline
                   cut (subs old-line 0 (quot (count old-line) 2))
                   unterminated (str/trimr old-line)
                   trailing (str (str/trimr old-line) " " junk)]
               (and (= ["new"] (map :id (deploy/read-records (append cut (rec "new")))))
                    (= ["old" "new"] (map :id (deploy/read-records (append unterminated (rec "new")))))
                    (= ["new"] (map :id (deploy/read-records (append trailing (rec "new")))))
                    (= ["new"] (map :id (deploy/read-records (append "" (rec "new")))))))))]
    (is (:pass? r) (pr-str (:shrunk r)))))

(deftest ticks-must-be-stopped-some-way-before-an-old-relay-is-involved
  (when jj?
    (with-world
      (fn [{:keys [pause-file] :as w}]
        (is (str/includes? (threw #(run/deploy! (opts w))) "does not know the pause file"))
        (is (on-previous? w))
        (testing "with ticks stopped another way, it goes ahead"
          (run/deploy! (opts w :paused-externally true))
          (is (not (on-previous? w))))
        (testing "a rollback onto a relay that cannot honour the pause needs the same word"
          (is (str/includes? (threw #(run/rollback! (opts w))) "does not know the pause file"))
          (is (not (on-previous? w)))
          (run/rollback! (opts w :paused-externally true))
          (is (on-previous? w))))
      :pause-aware? false)))

(deftest a-pause-from-a-failed-run-survives-a-rollback-that-cannot-start
  (when jj?
    (with-world
      (fn [{:keys [data pause-file] :as w}]
        (threw #(run/deploy! (opts w :status-cmd (fn [_ _] ["false"]))))
        (is (some? (pause/reason pause-file)))
        (let [before (pause/reason pause-file)
              wake (start-wake! data)
              msg (threw #(run/rollback! (opts w :quiet-timeout-ms 200)))]
          (is (str/includes? msg "still running"))
          (is (str/includes? msg "still paused"))
          (is (= before (pause/reason pause-file)) "left exactly as it was")
          (is (not (on-previous? w)))
          (end-wake! wake))
        (run/rollback! (opts w))
        (is (nil? (pause/reason pause-file)))))))

(defn- lock-free-in-another-process? [file]
  (let [script (str "(let [ch (java.nio.channels.FileChannel/open (java.nio.file.Paths/get \"" file "\" (make-array String 0))"
                    " (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE java.nio.file.StandardOpenOption/WRITE]))]"
                    " (println (if (.tryLock ch) \"free\" \"held\")))")]
    (= "free" (str/trim (:out (run/sh "." ["bb" "-e" script]))))))

(deftest only-one-deploy-or-rollback-runs-at-a-time
  (when jj?
    (with-world
      (fn [{:keys [base] :as w}]
        (let [file (run/lock-file base)
              held (run/try-lock! file)]
          (testing "a holder of the lock stops both commands"
            (is (some? held))
            (is (str/includes? (threw #(run/deploy! (opts w))) "Another bb deploy or rollback is running"))
            (is (str/includes? (threw #(run/rollback! (opts w))) "Another bb deploy or rollback is running"))
            (is (on-previous? w)))
          (testing "another record file, pause file or set of checkouts does not get round it"
            (is (str/includes? (threw #(run/deploy! (opts w :record (str base "/elsewhere/deploys.edn")
                                                          :pause-file (str base "/elsewhere/paused"))))
                               "Another bb")))
          (testing "being refused does not let go of the lock, for another process either"
            (is (nil? (run/try-lock! file)))
            (is (not (lock-free-in-another-process? file))))
          (run/release-lock! held)
          (is (lock-free-in-another-process? file) "released for real"))
        (testing "released, a run can take it again"
          (run/deploy! (opts w))
          (is (not (on-previous? w))))))))

(deftest a-deploy-waits-for-a-tick-that-is-already-running
  (when jj?
    (with-world
      (fn [{:keys [pause-file] :as w}]
        (let [saw-pause (atom nil)
              tick (future (pause/while-ticking
                            pause-file
                            (fn []
                              (loop [n 0]
                                (when (and (nil? (pause/reason pause-file)) (< n 1000))
                                  (Thread/sleep 20)
                                  (recur (inc n))))
                              (Thread/sleep 300)
                              (reset! saw-pause (pause/reason pause-file)))))]
          (Thread/sleep 100)
          (run/deploy! (opts w))
          @tick
          (is (some? @saw-pause) "the tick was still going when the pause appeared, and was waited for")
          (is (not (on-previous? w))))))))

(deftest a-tick-that-never-ends-stops-the-deploy
  (when jj?
    (with-world
      (fn [{:keys [pause-file] :as w}]
        (let [release (promise)
              tick (future (pause/while-ticking pause-file (fn [] @release)))]
          (Thread/sleep 100)
          (let [msg (threw #(run/deploy! (opts w :quiet-timeout-ms 200)))]
            (is (str/includes? msg "a relay tick (pid"))
            (is (on-previous? w))
            (is (nil? (pause/reason pause-file))))
          (deliver release true)
          @tick)))))

(deftest a-jj-new-that-fails-is-treated-as-having-moved
  (when jj?
    (with-world
      (fn [{:keys [repos commits pause-file] :as w}]
        ;; jj makes the move and then reports failure, as it can when it
        ;; cannot update the files.
        (let [lying (fn [dir cmd]
                      (let [r (run/sh dir cmd)]
                        (if (and (= "new" (nth cmd 2 nil)) (str/ends-with? dir "/agentloop")
                                 (not (str/includes? dir "relay-deploy-check")))
                          (assoc r :exit 255 :err "could not update the working copy")
                          r)))
              msg (threw #(run/deploy! (opts w :sh lying)))]
          (is (str/includes? msg "DEPLOY FAILED"))
          (is (str/includes? msg "may have changed the working copy"))
          (is (str/includes? msg "update-stale")))
        (is (some? (pause/reason pause-file)) "paused: the checkout may have moved")
        (is (= :moving (get-in (last (records w)) [:repos :agentloop :state])))
        (testing "and a rollback can still put it back"
          (run/rollback! (opts w))
          (is (on-previous? w)))))))

(deftest a-checkout-jj-cannot-read-at-the-last-look-is-not-moved
  (when jj?
    (with-world
      (fn [{:keys [repos commits pause-file] :as w}]
        (let [armed (atom false)
              broken (fn [dir cmd]
                       (when (and (= ["make" "build"] cmd) (str/ends-with? dir "/agentloop")
                                  (not (str/includes? dir "relay-deploy-check")))
                         (spit (str (:relay repos) "/mine") "work")
                         (reset! armed true))
                       (if (and @armed (str/ends-with? dir "/relay") (some #{"@"} cmd) (some #{"log"} cmd))
                         {:exit 1 :out "" :err "The working copy is stale"}
                         (run/sh dir cmd)))
              msg (threw #(run/deploy! (opts w :sh broken)))]
          (is (str/includes? msg "Stopped before touching relay"))
          (is (str/includes? msg "could not be read")))
        (is (= "work" (slurp (str (:relay repos) "/mine"))))
        (is (= (:one (commits :relay)) (commit-id (:relay repos) "@-")))))))

(deftest an-edit-that-lands-between-the-last-look-and-the-move-is-reported
  (when jj?
    (with-world
      (fn [{:keys [repos pause-file] :as w}]
        (let [racing (fn [dir cmd]
                       (when (and (= "new" (nth cmd 2 nil)) (str/ends-with? dir "/relay")
                                  (not (str/includes? dir "relay-deploy-check")))
                         (spit (str dir "/mine") "work"))
                       (run/sh dir cmd))
              msg (threw #(run/deploy! (opts w :sh racing)))
              change (second (re-find #"in change (\w+)" (str msg)))]
          (is (str/includes? msg "was written to"))
          (is (some? change))
          (is (= "work" (sh! (:relay repos) "jj" "--no-pager" "file" "show" "-r" change "mine"))
              "jj kept the edit in a change of its own"))
        (is (some? (pause/reason pause-file)))))))


(deftest the-gate-also-needs-the-relay-that-would-be-left-in-place
  (when jj?
    (with-world
      (fn [w]
        (let [msg (threw #(run/deploy! (opts w)))]
          (is (str/includes? msg "leave in place"))
          (is (on-previous? w))))
      :pause-in-target? false)))

(deftest a-rebuild-that-fails-without-a-move-keeps-relay-paused
  (when jj?
    (with-world
      (fn [{:keys [repos pause-file] :as w}]
        (run/deploy! (opts w))
        ;; Deploy again: nothing to move, but the build runs and breaks its artifact.
        (let [breaking (fn [dir cmd]
                         (if (and (= ["make" "build"] cmd) (str/ends-with? dir "/relay-tui")
                                  (not (str/includes? dir "relay-deploy-check")))
                           (do (spit (str dir "/built") "BROKEN") {:exit 2 :out "" :err "build broke"})
                           (run/sh dir cmd)))
              msg (threw #(run/deploy! (opts w :sh breaking)))]
          (is (str/includes? msg "DEPLOY FAILED"))
          (is (some? (pause/reason pause-file)) "the artifact changed, so relay stays paused")
          (is (= :building (get-in (last (records w)) [:repos :relay-tui :state]))))))))

(deftest a-lookup-that-fails-after-a-move-does-not-pass-for-no-edit
  (when jj?
    (with-world
      (fn [{:keys [repos pause-file] :as w}]
        (let [broken (fn [dir cmd]
                       (cond
                         (and (= "new" (nth cmd 2 nil)) (str/ends-with? dir "/relay")
                              (not (str/includes? dir "relay-deploy-check")))
                         (do (spit (str dir "/mine") "work") (run/sh dir cmd))
                         (and (some #(str/starts-with? (str %) "present(") cmd) (str/ends-with? dir "/relay"))
                         {:exit 1 :out "" :err "could not read the operation log"}
                         :else (run/sh dir cmd)))
              msg (threw #(run/deploy! (opts w :sh broken)))]
          (is (str/includes? msg "could not say whether something was written")))
        (is (some? (pause/reason pause-file)))))))

(deftest a-pause-that-cannot-be-lifted-does-not-look-like-a-finished-run
  (when jj?
    (with-world
      (fn [{:keys [pause-file] :as w}]
        (let [msg (with-redefs [pause/resume! (fn [_] (throw (ex-info "disk says no" {})))]
                    (threw #(run/deploy! (opts w))))]
          (is (str/includes? msg "could not be unpaused"))
          (is (str/includes? msg pause-file)))
        (is (= :failed (:status (last (records w)))) "not recorded as done while relay is paused")
        (testing "a rollback is still offered, and lifts the pause"
          (run/rollback! (opts w))
          (is (on-previous? w))
          (is (nil? (pause/reason pause-file))))))))

(deftest a-rebuild-that-succeeds-without-a-move-still-counts-as-touched
  (when jj?
    (with-world
      (fn [{:keys [pause-file] :as w}]
        (run/deploy! (opts w))
        ;; The same commits again: nothing moves, but both builds run, and then relay status fails.
        (let [msg (threw #(run/deploy! (opts w :status-cmd (fn [_ _] ["false"]))))]
          (is (str/includes? msg "relay status failed")))
        (is (some? (pause/reason pause-file)) "artifacts were rebuilt, so relay stays paused")
        (let [states (update-vals (:repos (last (records w))) :state)]
          (is (= {:agentloop :built :relay-tui :built :relay :kept} states)))))))

;; --- the Opus review of round 5

(deftest describe-plan-names-the-build
  (let [step (fn [repo moves?] {:repo repo :path (str "/p/" (name repo)) :from c1 :to c2 :moves? moves?})
        lines (deploy/describe-plan {:steps [(step :agentloop true) (step :relay-tui false) (step :relay true)]})]
    (is (= ["  agentloop (/p/agentloop): aaa -> bbb, then make build"
            "  relay-tui (/p/relay-tui): already on bbb, then make build"
            "  relay (/p/relay): aaa -> bbb"]
           lines))))

(deftest a-deploy-that-moved-nothing-is-not-what-a-rollback-undoes
  (let [moved (record-for :deploy "d1")
        nothing (deploy/make-record {:id "d2" :kind :deploy :at "d2" :status :failed
                                     :repos (zipmap deploy/repo-keys
                                                    (repeat {:path "/x" :previous c2 :target c2 :state :pending}))})
        kept (deploy/make-record {:id "d3" :kind :deploy :at "d3" :status :ok
                                  :repos (zipmap deploy/repo-keys
                                                 (repeat {:path "/x" :previous c2 :target c2 :new c2 :state :built}))})
        moving (deploy/make-record {:id "d4" :kind :deploy :at "d4" :status :failed
                                    :repos (assoc (zipmap deploy/repo-keys
                                                          (repeat {:path "/x" :previous c1 :target c2 :state :pending}))
                                                  :agentloop {:path "/x" :previous c1 :target c2 :state :moving})})]
    (is (true? (deploy/moved? moved)))
    (is (false? (deploy/moved? nothing)))
    (is (false? (deploy/moved? kept)) "rebuilt in place")
    (is (true? (deploy/moved? moving)) "a jj new that may have worked counts")
    (testing "the rollback is of the deploy that moved, not the later one that did not"
      (is (= moved (:record (deploy/rollback-source [moved nothing kept]))))
      (is (= moving (:record (deploy/rollback-source [moved nothing kept moving])))))
    (is (str/includes? (:problem (deploy/rollback-source [nothing kept])) "moved a checkout"))))

(deftest a-corrupt-live-log-lifts-the-pause-it-just-made
  (when jj?
    (with-world
      (fn [{:keys [data pause-file] :as w}]
        (let [segment (first (fs/glob (str data "/log") "*.jsonl"))]
          (spit (str segment) "this is not an event\n" :append true))
        (let [msg (try (run/deploy! (opts w)) nil (catch Exception e (ex-message e)))]
          (is (some? msg) "the deploy stops")
          (is (str/includes? (str msg) "corrupt")))
        (is (nil? (pause/reason pause-file)) "and relay is not left paused with nothing done")
        (is (on-previous? w))))))

(deftest an-interrupt-before-anything-is-touched-lifts-the-pause-and-the-workspaces
  (when jj?
    (with-world
      (fn [{:keys [repos data pause-file] :as w}]
        (testing "while waiting for wakes"
          (let [wake (start-wake! data)
                after (atom :unset)
                printed (atom nil)
                interrupter (future
                              (loop [n 0]
                                (when (and (nil? (pause/reason pause-file)) (< n 1000)) (Thread/sleep 20) (recur (inc n))))
                              (reset! printed (with-out-str (#'run/run-exit-cleanup!)))
                              (reset! after (pause/reason pause-file))
                              (end-wake! wake))]
            ;; The process would be gone; here it carries on, and must not touch anything.
            (is (str/includes? (threw #(run/deploy! (opts w))) "Interrupted"))
            @interrupter
            (is (nil? @after) "the cleanup a Ctrl-C runs lifted the pause")
            ;; Regression (relay#24): the stop printed nothing.
            (is (str/includes? @printed "Nothing was changed and relay is not paused") "and said so")
            (is (on-previous? w))))
        (testing "while the commits are being tested"
          (let [seen (atom nil)
                interrupt (fn [dir cmd]
                            (if (= ["interrupt-here"] cmd)
                              (do (#'run/run-exit-cleanup!)
                                  (reset! seen (mapv #(count (str/split-lines (workspaces (get repos %)))) deploy/repo-keys))
                                  {:exit 1 :out "" :err "interrupted"})
                              (run/sh dir cmd)))]
            (threw #(run/deploy! (opts w :sh interrupt :test-cmds [["interrupt-here"]])))
            (is (= [1 1 1] @seen) "the check workspaces were forgotten")
            (is (on-previous? w))))))))

(deftest an-interrupt-after-a-checkout-was-touched-leaves-relay-paused
  (when jj?
    (with-world
      (fn [{:keys [pause-file] :as w}]
        (let [during (atom :unset)
              status (fn [path _]
                       (#'run/run-exit-cleanup!)
                       (reset! during (pause/reason pause-file))
                       ["cat" (str path "/version")])]
          (run/deploy! (opts w :status-cmd status))
          (is (some? @during) "still paused when the interrupt came after the moves"))))))

(deftest only-a-failed-status-after-everything-moved-says-so-and-how-to-lift-the-pause
  (when jj?
    (with-world
      (fn [{:keys [pause-file] :as w}]
        (let [msg (threw #(run/deploy! (opts w :status-cmd (fn [_ _] ["sh" "-c" "echo oops >&2; exit 3"]))))]
          (is (str/includes? msg "Every checkout reached its target and was built. Only `relay status` failed."))
          (is (str/includes? msg (str "rm '" (staging/canonical pause-file (System/getProperty "user.home")) "'")))
          (is (str/includes? msg "bb rollback")))))))

(deftest the-data-directory-must-be-a-relay-data-directory
  (when jj?
    (with-world
      (fn [{:keys [base data] :as w}]
        (let [refused (fn [d] (threw #(run/deploy! (opts w :data d))))]
          (is (str/includes? (refused "") "--data is empty"))
          (is (str/includes? (refused (str base "/no-such-dir")) "not a relay data directory"))
          (fs/create-dirs (str base "/just-a-dir"))
          (is (str/includes? (refused (str base "/just-a-dir")) "not a relay data directory"))
          (fs/create-dirs (str base "/no-log"))
          (spit (str base "/no-log/relay.edn") "{}")
          (is (str/includes? (refused (str base "/no-log")) "not a relay data directory"))
          (is (str/includes? (threw #(run/rollback! (opts w :data ""))) "--data is empty"))
          (is (on-previous? w)))))))

(deftest the-three-checkouts-must-be-three-places
  (when jj?
    (with-world
      (fn [{:keys [repos] :as w}]
        (let [msg (threw #(run/deploy! (opts w :paths (assoc repos :relay-tui (:agentloop repos)))))]
          (is (str/includes? msg "three different directories"))
          (is (on-previous? w)))))))

;; --- Opus round 6

(defn- breaking-relay-tui-build [dir cmd]
  (if (and (= ["make" "build"] cmd) (str/ends-with? dir "/relay-tui") (not (str/includes? dir "relay-deploy-check")))
    {:exit 2 :out "" :err "build broke"}
    (run/sh dir cmd)))

(deftest a-failed-rebuild-that-moved-nothing-can-be-recovered-when-it-is-the-first-deploy
  (when jj?
    (with-world
      (fn [{:keys [repos pause-file] :as w}]
        ;; Everything is already on main, so the first deploy moves nothing.
        (doseq [[_ d] repos] (sh! d "jj" "new" "main"))
        (let [msg (threw #(run/deploy! (opts w :sh breaking-relay-tui-build)))]
          (is (str/includes? msg "DEPLOY FAILED")))
        (is (some? (pause/reason pause-file)))
        (testing "the refusal to deploy again says how to lift the pause"
          (let [msg (threw #(run/deploy! (opts w)))]
            (is (str/includes? msg "already paused"))
            (is (str/includes? msg (str "rm '" (staging/canonical pause-file (System/getProperty "user.home")) "'")))))
        (testing "rollback rebuilds in place and lifts it"
          (let [rec (run/rollback! (opts w))]
            (is (= :ok (:status rec))))
          (is (nil? (pause/reason pause-file)))
          (is (every? #(= "2" (version %)) (vals repos)) "nothing was reverted"))
        (testing "and deploying works again"
          (run/deploy! (opts w))
          (is (nil? (pause/reason pause-file))))))))

(deftest a-failed-rebuild-after-a-good-deploy-is-undone-before-the-good-deploy-is
  (when jj?
    (with-world
      (fn [{:keys [repos pause-file] :as w}]
        (run/deploy! (opts w))
        (threw #(run/deploy! (opts w :sh breaking-relay-tui-build)))
        (is (some? (pause/reason pause-file)))
        (testing "the first rollback is of the failed rebuild: the good deploy stays"
          (run/rollback! (opts w))
          (is (nil? (pause/reason pause-file)))
          (is (every? #(= "2" (version %)) (vals repos))))
        (testing "the next is of the good deploy"
          (run/rollback! (opts w))
          (is (on-previous? w)))))))

(deftest a-deploy-that-failed-after-touching-nothing-or-finished-in-place-is-not-undone
  (let [rec (fn [id status states]
              (deploy/make-record {:id id :kind :deploy :at id :status status
                                   :repos (zipmap deploy/repo-keys
                                                  (map (fn [st] {:path "/x" :previous c2 :target c2 :state st}) states))}))]
    (is (true? (deploy/undoable? (rec "a" :failed [:built :building :pending]))))
    (is (false? (deploy/undoable? (rec "b" :failed [:pending :pending :pending]))) "refused at its first look")
    (is (false? (deploy/undoable? (rec "c" :ok [:built :built :kept]))) "finished and rebuilt in place")
    (is (false? (deploy/undoable? (rec "d" :failed [:kept :kept :kept]))))))

(deftest every-refusal-while-paused-names-the-way-out
  (when jj?
    (with-world
      (fn [{:keys [repos pause-file] :as w}]
        (threw #(run/deploy! (opts w :status-cmd (fn [_ _] ["false"]))))
        (let [file (staging/canonical pause-file (System/getProperty "user.home"))
              wanted (str "rm '" file "'")]
          (spit (str (:relay repos) "/mine") "work")
          (is (str/includes? (threw #(run/rollback! (opts w))) wanted) "a rollback refused for a dirty checkout")
          (is (str/includes? (threw #(run/rollback! (opts w :data ""))) wanted) "and for a bad --data")
          (is (str/includes? (threw #(run/deploy! (opts w :data ""))) wanted)))))))

(deftest a-stop-that-comes-first-keeps-the-run-from-touching-anything
  (when jj?
    (with-world
      (fn [{:keys [repos record pause-file] :as w}]
        ;; The hook runs just before the first checkout would be looked at
        ;; for the move, after the :started record is written.
        (let [fired (atom false)
              stopping (fn [dir cmd]
                         (when (and (not @fired) (some #{"root"} cmd) (fs/exists? record)
                                    (str/includes? (slurp record) ":started")
                                    (not (str/includes? dir "relay-deploy-check")))
                           (reset! fired true)
                           (#'run/run-exit-cleanup!))
                         (run/sh dir cmd))
              msg (threw #(run/deploy! (opts w :sh stopping)))]
          (is (str/includes? msg "Interrupted before touching agentloop")))
        (is (on-previous? w))
        (is (nil? (pause/reason pause-file)) "and the stop lifted the pause")))))

(deftest a-touch-that-comes-first-keeps-the-pause-through-a-stop
  (testing "the two atomic steps, either way round"
    (let [on-exit @#'run/on-exit
          lifted (atom 0)]
      (try
        (reset! on-exit {:pause #(swap! lifted inc) :notice #(swap! lifted + 100)})
        (is (true? (#'run/begin-touch!)))
        (with-out-str (#'run/run-exit-cleanup!))
        (is (= 100 @lifted) "touched first: the pause was left, and the notice shown instead")
        (reset! lifted 0)
        (reset! on-exit {:pause #(swap! lifted inc) :notice #(swap! lifted + 100)})
        (#'run/run-exit-cleanup!)
        (is (= 1 @lifted) "stopped first: the pause lifted")
        (is (false? (#'run/begin-touch!)) "and nothing may be touched afterwards")
        (finally (reset! on-exit {}))))))

(deftest a-stop-after-a-touch-says-how-to-recover
  (when jj?
    (with-world
      (fn [{:keys [pause-file] :as w}]
        (let [printed (atom "")
              status (fn [path _]
                       (swap! printed str (with-out-str (#'run/run-exit-cleanup!)))
                       ["cat" (str path "/version")])]
          (run/deploy! (opts w :status-cmd status))
          (is (str/includes? @printed "Interrupted. relay is PAUSED"))
          (is (str/includes? @printed "bb rollback"))
          (is (str/includes? @printed (str "rm '" (staging/canonical pause-file (System/getProperty "user.home")) "'"))))))))

(deftest the-paused-message-fits-what-happened
  (when jj?
    (with-world
      (fn [w]
        (run/deploy! (opts w))
        (let [said (atom [])
              _ (threw #(run/deploy! (opts w :sh breaking-relay-tui-build :say (fn [s] (swap! said conj s)))))]
          (is (some #(str/includes? % "a checkout was touched") @said))
          (is (not-any? #(str/includes? % "some checkouts moved") @said)))
        (run/rollback! (opts w))
        (testing "a rollback whose only failure is status says it is back where it was"
          (let [msg (threw #(run/rollback! (opts w :status-cmd (fn [_ _] ["false"]))))]
            (is (str/includes? msg "back where the deploy found it"))
            (is (not (str/includes? msg "otherwise run bb rollback")))
            (is (str/includes? msg "rm '"))))))))

(deftest an-interrupt-while-a-check-workspace-is-being-made-forgets-it
  (when jj?
    (with-world
      (fn [{:keys [repos] :as w}]
        (let [count-after (atom nil)
              racing (fn [dir cmd]
                       (let [r (run/sh dir cmd)]
                         (when (and (some #{"add"} cmd) (some #{"workspace"} cmd) (str/ends-with? dir "/agentloop")
                                    (nil? @count-after))
                           ;; the workspace now exists but verify! has not noted it
                           (#'run/run-exit-cleanup!)
                           (reset! count-after (count (str/split-lines (workspaces dir)))))
                         r))]
          (threw #(run/deploy! (opts w :sh racing)))
          (is (= 1 @count-after) "forgotten by name, whether or not it was noted as made"))))))

;; --- Opus round 7

(deftest a-run-killed-during-a-rebuild-that-moves-nothing-is-undone-first
  (let [d1 {:id "deploy 1" :kind :deploy :at "t1" :status :ok
            :repos (zipmap deploy/repo-keys (repeat {:path "/p" :previous c1 :target c2 :new c2 :state :built}))}
        d2 {:id "deploy 2" :kind :deploy :at "t2" :status :started
            :repos (zipmap deploy/repo-keys (repeat {:path "/p" :previous c2 :target c2 :state :building}))}]
    (is (true? (deploy/undoable? d2)))
    (is (= d2 (:record (deploy/rollback-source [d1 d2]))) "not the older good deploy")
    (is (false? (deploy/undoable? (assoc d2 :status :ok))))))

(deftest a-run-that-never-touched-anything-leaves-only-a-pause-to-lift
  (let [rec (fn [id kind status state & {:as more}]
              (deploy/make-record (merge {:id id :kind kind :at id :status status
                                          :repos (zipmap deploy/repo-keys (repeat {:path "/p" :previous c1 :target c2 :state state}))}
                                         more)))
        good (assoc-in (rec "d1" :deploy :ok :built) [:repos :relay :new] c2)]
    (testing "the newest run began and was killed before touching anything"
      (is (= "d2" (:id (:lift-only (deploy/rollback-source [good (rec "d2" :deploy :started :pending)]))))))
    (testing "a run that finished or was closed is not one"
      (is (= good (:record (deploy/rollback-source [good (rec "d2" :deploy :failed :pending)]))))
      (is (= good (:record (deploy/rollback-source [good (rec "d2" :deploy :started :pending)
                                                    (rec "r1" :rollback :ok :pending :of "d2" :lift-only true)])))))
    (testing "a touched run is undone, not lifted"
      (is (some? (:record (deploy/rollback-source [good (rec "d2" :deploy :started :building)])))))
    (testing "a rollback that found relay already paused does not own that pause"
      (is (= "d2" (:id (:record (deploy/rollback-source [good (rec "d2" :deploy :failed :building)
                                                         (rec "r1" :rollback :started :pending :of "d2" :inherited true)]))))
          "the failed deploy before it is still the thing to undo, not a lift"))))

(defn- child-deploy!
  "Runs deploy! in a process of its own, as `how` says: dying (halt, like
  SIGKILL) at the first live make build, or left to run until killed."
  [w how]
  (let [wf (str (:base w) "/world.edn")
        script (str "(babashka.classpath/add-classpath \"test\")"
                    "(require '[relay.deploy-test :as t] '[relay.deploy-run :as run] '[clojure.string :as str])"
                    "(let [w (read-string (slurp \"" wf "\"))"
                    "      sh (fn [dir cmd]"
                    "           (when (and " (pr-str (= how :halt-in-build)) " (= [\"make\" \"build\"] cmd) (str/ends-with? (str dir) \"/agentloop\")"
                    "                      (not (str/includes? (str dir) \"relay-deploy-check\")))"
                    "             (.halt (Runtime/getRuntime) 137))"
                    "           (run/sh dir cmd))]"
                    "  (run/deploy! (#'t/opts w :sh sh :quiet-timeout-ms 30000)))")]
    (spit wf (pr-str (select-keys w [:base :repos :record :data :pause-file])))
    (babashka.process/process {:out :string :err :string} "bb" "-e" script)))

(deftest a-killed-rebuild-leaves-a-record-that-rollback-reads-first
  (when jj?
    (with-world
      (fn [{:keys [repos pause-file] :as w}]
        (run/deploy! (opts w))
        (let [child (child-deploy! w :halt-in-build)
              r @child]
          (is (= 137 (:exit r))))
        (let [last-line (last (records w))]
          (is (= :started (:status last-line)))
          (is (= :building (get-in last-line [:repos :agentloop :state]))))
        (is (some? (pause/reason pause-file)) "left paused")
        (testing "rollback undoes that run (a rebuild in place), not the good deploy before it"
          (run/rollback! (opts w))
          (is (nil? (pause/reason pause-file)))
          (is (every? #(= "2" (version %)) (vals repos)) "still on the deployed version"))
        (testing "the next rollback is of the good deploy"
          (run/rollback! (opts w))
          (is (on-previous? w)))))))

(deftest a-run-killed-while-waiting-leaves-only-a-pause-to-lift
  (when jj?
    (with-world
      (fn [{:keys [data pause-file] :as w}]
        (let [wake (start-wake! data)
              child (child-deploy! w :wait)]
          ;; Wait for the child to have paused relay and be waiting on the wake.
          (loop [n 0]
            (when (and (nil? (pause/reason pause-file)) (< n 2000))
              (Thread/sleep 25) (recur (inc n))))
          (is (some? (pause/reason pause-file)))
          (.destroyForcibly (:proc child))
          @child
          (end-wake! wake))
        (is (= :started (:status (last (records w)))) "its record was written before it paused")
        (is (some? (pause/reason pause-file)) "SIGKILL left the pause")
        (let [plans (atom nil)]
          (run/rollback! (opts w :confirm (fn [lines _] (reset! plans lines) true)))
          (is (str/includes? (str/join " " @plans) "stopped before it touched anything"))
          (is (str/includes? (str/join " " @plans) "only lifts the pause")))
        (is (nil? (pause/reason pause-file)))
        (is (on-previous? w) "nothing was moved")
        (testing "and that closes the record, so a deploy can follow"
          (is (:lift-only (last (records w))))
          (run/deploy! (opts w))
          (is (not (on-previous? w))))))))

(deftest the-pause-is-only-made-when-the-stop-had-not-come
  (when jj?
    (with-world
      (fn [{:keys [pause-file] :as w}]
        (let [made (atom 0)
              real pause/pause!
              on-exit @#'run/on-exit]
          (with-redefs [pause/pause! (fn [& a] (swap! made inc) (apply real a))]
            (reset! on-exit {:closing true})
            (try (is (str/includes? (threw #(run/deploy! (opts w))) "Interrupted"))
                 (finally (reset! on-exit {}))))
          (is (zero? @made))
          (is (nil? (pause/reason pause-file)))
          (is (on-previous? w)))))))

(deftest a-stop-during-a-rollback-of-a-failed-deploy-says-how-to-recover
  (when jj?
    (with-world
      (fn [{:keys [pause-file data] :as w}]
        (threw #(run/deploy! (opts w :status-cmd (fn [_ _] ["false"]))))
        (testing "an interrupt mid-rollback prints the advice, though the pause was not made by it"
          (let [printed (atom "")
                status (fn [path _]
                         (swap! printed str (with-out-str (#'run/run-exit-cleanup!)))
                         ["cat" (str path "/version")])]
            (run/rollback! (opts w :status-cmd status))
            (is (str/includes? @printed "Interrupted. relay is PAUSED"))))
        (testing "the wait running out names the pause file and how to lift it"
          (threw #(run/deploy! (opts w :status-cmd (fn [_ _] ["false"]))))
          (let [wake (start-wake! data)
                msg (threw #(run/rollback! (opts w :quiet-timeout-ms 200)))]
            (is (str/includes? msg "still running"))
            (is (str/includes? msg (str "rm '" (staging/canonical pause-file (System/getProperty "user.home")) "'")))
            (end-wake! wake)))))))

(deftest the-final-status-does-not-print-the-pause-advice
  (let [cmd ((#'run/default-status-cmd "/p/paused") "/r" "/d")]
    (is (= ["/r/bin/relay" "status" "--data" "/d" "--pause-file" "/p/paused.status-check"] cmd)))
  (let [dir (str (fs/create-temp-dir {:prefix "relay-final-status"}))
        file (str dir "/paused")
        data (staging/init! {:dir (str dir "/data") :check? false})]
    (try
      (pause/pause! file "bb deploy at now")
      (let [args (drop 1 ((#'run/default-status-cmd file) "/r" data))
            shown (str/join "\n" (:out (relay.cli/execute (vec args))))]
        (is (not (str/includes? shown "paused")) "although the pause file is there")
        (is (str/includes? shown "Roles")))
      (finally (fs/delete-tree dir)))))

(deftest a-workspace-made-but-not-noted-is-still-forgotten
  (when jj?
    (with-world
      (fn [{:keys [repos] :as w}]
        ;; jj makes the workspace and then reports failure.
        (let [lying (fn [dir cmd]
                      (let [r (run/sh dir cmd)]
                        (if (and (some #{"workspace"} cmd) (some #{"add"} cmd) (str/ends-with? dir "/agentloop"))
                          (assoc r :exit 1 :err "disk full?")
                          r)))]
          (is (str/includes? (threw #(run/deploy! (opts w :sh lying))) "Making a workspace of agentloop"))
          (doseq [[_ d] repos]
            (is (= 1 (count (str/split-lines (workspaces d)))) d)))))))

;; --- Opus round 8

(deftest the-hook-lifts-the-pause-before-it-closes-the-record
  (let [order (atom [])
        on-exit @#'run/on-exit]
    (try
      (reset! on-exit {:finish #(swap! order conj :finish) :workspaces #(swap! order conj :workspaces)
                       :pause #(swap! order conj :pause)})
      (#'run/run-exit-cleanup!)
      (is (= [:pause :workspaces :finish] @order))
      (finally (reset! on-exit {})))))

(deftest a-closed-record-with-a-pause-left-is-still-only-a-pause-to-lift
  (let [rec (fn [id status state & {:as more}]
              (deploy/make-record (merge {:id id :kind :deploy :at id :status status
                                          :repos (zipmap deploy/repo-keys (repeat {:path "/p" :previous c1 :target c2 :state state}))}
                                         more)))
        good (assoc-in (rec "d1" :ok :built) [:repos :relay :new] c2)
        closed (rec "d2" :failed :pending)]
    (is (= good (:record (deploy/rollback-source [good closed] false))) "no pause: nothing to lift, the good deploy is next")
    (is (= good (:record (deploy/rollback-source [good closed]))))
    (is (= "d2" (:id (:lift-only (deploy/rollback-source [good closed] true)))) "pause left behind a closed record")
    (testing "a touched run, or one that found the pause already there, is not a lift"
      (is (some? (:record (deploy/rollback-source [good (rec "d3" :failed :building)] true))))
      (is (some? (:record (deploy/rollback-source [good (assoc (rec "r1" :failed :pending :inherited true) :kind :rollback)] true)))))))

(deftest a-kill-between-closing-the-record-and-lifting-the-pause-only-lifts-it
  (when jj?
    (with-world
      (fn [{:keys [pause-file record] :as w}]
        (run/deploy! (opts w))
        ;; What the hook's old order could leave: the run closed as failed, the pause still there.
        (spit record (deploy/record->line
                      (deploy/make-record {:id "deploy x" :kind :deploy :at "x" :status :failed
                                           :failure "stopped before anything was changed"
                                           :repos (zipmap deploy/repo-keys (repeat {:path "/p" :state :pending}))}))
              :append true)
        (pause/pause! pause-file "bb deploy at x")
        (let [seen (atom nil)]
          (run/rollback! (opts w :confirm (fn [lines _] (reset! seen lines) true)))
          (is (str/includes? (str/join " " @seen) "only lifts the pause")))
        (is (nil? (pause/reason pause-file)))
        (is (not (on-previous? w)) "the good deploy was not undone")))))

(deftest an-interrupt-at-the-confirmation-of-a-rollback-says-relay-is-still-paused
  (when jj?
    (with-world
      (fn [{:keys [pause-file] :as w}]
        (threw #(run/deploy! (opts w :status-cmd (fn [_ _] ["false"]))))
        (let [printed (atom "")
              confirm (fn [_ _] (swap! printed str (with-out-str (#'run/run-exit-cleanup!))) false)]
          (is (str/includes? (threw #(run/rollback! (opts w :confirm confirm))) "Not confirmed"))
          (is (str/includes? @printed "Interrupted. relay is PAUSED"))
          (is (str/includes? @printed (str "rm '" (staging/canonical pause-file (System/getProperty "user.home")) "'"))))
        (is (some? (pause/reason pause-file)) "and it stays paused")))))

(deftest a-late-interrupt-after-the-pause-was-lifted-prints-nothing
  (when jj?
    (with-world
      (fn [{:keys [pause-file] :as w}]
        (let [printed (atom "")
              real pause/resume!]
          (with-redefs [pause/resume! (fn [f]
                                        ;; the stop lands just as the pause is being lifted
                                        (swap! printed str (with-out-str (#'run/run-exit-cleanup!)))
                                        (real f))]
            (run/deploy! (opts w)))
          (is (= "" @printed)))))))

(deftest a-throw-after-a-touch-does-not-lift-the-pause
  (when jj?
    (with-world
      (fn [{:keys [data pause-file base] :as w}]
        (let [o (assoc (opts w) :kind :deploy :pause-file pause-file :data data)
              say (:say o)
              touched-then-throws (fn []
                                    (#'run/begin-touch!)
                                    (throw (ex-info "boom" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                                (#'run/under-pause o "bb deploy at test" touched-then-throws)))
          (is (some? (pause/reason pause-file)) "a checkout may have changed, so relay stays paused")
          (testing "but one before any touch does lift it"
            (pause/resume! pause-file)
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                                  (#'run/under-pause o "bb deploy at test" (fn [] (throw (ex-info "boom" {}))))))
            (is (nil? (pause/reason pause-file)))))))))

;; --- relay#24

(deftest a-stop-between-registering-the-pause-and-making-it-lifts-it
  ;; Regression (relay#24): the hook could run after the pause was
  ;; registered and before the file was made, lift nothing, and leave the
  ;; file to be made after it.
  (when jj?
    (with-world
      (fn [{:keys [data pause-file] :as w}]
        (let [o (assoc (opts w) :kind :deploy :pause-file pause-file :data data)
              hook (atom nil)
              real pause/pause!
              on-exit @#'run/on-exit]
          (try
            (with-redefs [pause/pause! (fn [& a]
                                         (reset! hook (doto (Thread. #(binding [*out* (java.io.StringWriter.)]
                                                                       (#'run/run-exit-cleanup!)))
                                                        .start))
                                         (Thread/sleep 300)
                                         (apply real a))]
              (threw #(#'run/under-pause o "bb deploy at test" (fn [] (throw (ex-info "stopped" {}))))))
            (.join ^Thread @hook 5000)
            (is (nil? (pause/reason pause-file)) "relay is not left paused")
            (finally (reset! on-exit {}))))))))

(deftest the-interrupt-notice-says-paused-only-while-it-is
  ;; Regression (relay#24): a rollback that found relay paused registers the
  ;; notice, and it said PAUSED after the rollback had lifted the pause.
  (when jj?
    (with-world
      (fn [{:keys [pause-file record] :as w}]
        (run/deploy! (opts w))
        (spit record (deploy/record->line
                      (deploy/make-record {:id "deploy x" :kind :deploy :at "x" :status :started
                                           :repos (zipmap deploy/repo-keys (repeat {:path "/p" :state :pending}))}))
              :append true)
        (pause/pause! pause-file "bb deploy at x")
        (let [printed (atom "")
              real pause/resume!]
          (with-redefs [pause/resume! (fn [f]
                                        (let [r (real f)]
                                          (swap! printed str (with-out-str (#'run/run-exit-cleanup!)))
                                          r))]
            (run/rollback! (opts w)))
          (is (nil? (pause/reason pause-file)))
          (is (not (str/includes? @printed "PAUSED")) @printed))))))

(deftest the-pause-is-read-again-once-the-lock-is-held
  ;; Regression (relay#24): *inherited-pause* was read before the lock, so
  ;; a pause made by a run that held the lock went without its advice.
  (when jj?
    (with-world
      (fn [{:keys [pause-file] :as w}]
        (let [real run/try-lock!
              msg (with-redefs [run/try-lock! (fn [f]
                                                (pause/pause! pause-file "bb deploy at x")
                                                (real f))]
                    (threw #(run/deploy! (opts w))))]
          (is (str/includes? msg "already paused"))
          (is (str/includes? msg (str "rm '" (staging/canonical pause-file (System/getProperty "user.home")) "'"))
              "the refusal names the way out"))))))
