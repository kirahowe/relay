(ns relay.deploy-test
  "The pure decisions behind deploy and rollback (relay.deploy): plans,
  observations, records and which run a rollback undoes. The run itself
  is relay.deploy-flow-test; jj and the files are relay.deploy-run-test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.deploy :as deploy]))

(def c1 {:commit "aaa" :change "xxx"})
(def c2 {:commit "bbb" :change "yyy"})
(def c3 {:commit "ccc" :change "zzz"})
(defn- checkout [parent & {:as more}]
  (merge {:path "/x" :exists? true :jj? true :wc {:commit "ddd" :change "www" :empty? true :conflict? false}
          :parents [parent] :target c2}
         more))

(defn- record-for [kind id & {:as more}]
  (deploy/make-record
   (merge {:id id :kind kind :at id :status :ok
           :repos (zipmap deploy/repo-keys
                          (repeat {:path "/x" :previous c1 :target c2 :new c2 :state :built :wc {:commit "ddd" :change "www"}}))}
          more)))

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

(deftest a-deploy-that-failed-after-touching-nothing-or-finished-in-place-is-not-undone
  (let [rec (fn [id status states]
              (deploy/make-record {:id id :kind :deploy :at id :status status
                                   :repos (zipmap deploy/repo-keys
                                                  (map (fn [st] {:path "/x" :previous c2 :target c2 :state st}) states))}))]
    (is (true? (deploy/undoable? (rec "a" :failed [:built :building :pending]))))
    (is (false? (deploy/undoable? (rec "b" :failed [:pending :pending :pending]))) "refused at its first look")
    (is (false? (deploy/undoable? (rec "c" :ok [:built :built :kept]))) "finished and rebuilt in place")
    (is (false? (deploy/undoable? (rec "d" :failed [:kept :kept :kept]))))))

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

