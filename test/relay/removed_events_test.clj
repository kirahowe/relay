(ns relay.removed-events-test
  "Every old log still folds. The log is append-only, so the events older
  versions of relay wrote for research reports, findings, the ledger and
  trust levels stay in Kira's log. The state they used to build is gone,
  and so are they: a log with them folds to what the same log folds to
  without them."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.core :as core]
            [relay.core-test :as ct]))

(def old-kinds
  "The nine kinds the pre-pivot relay wrote, listed here on their own so
  that dropping one from core/removed-event-kinds fails a test instead of
  silently dropping it from the tests too."
  ["report/submitted" "report/verify-started" "report/verify-failed" "report/verified"
   "finding/reviewed" "ledger/entry-added" "ledger/entry-withdrawn" "ledger/status-set"
   "role/trust-level"])

(deftest the-removed-kinds-are-exactly-the-ones-old-relay-wrote
  (is (= (set old-kinds) core/removed-event-kinds))
  (is (= 9 (count old-kinds))))

(def gen-removed
  "An event of a removed kind, as an old relay wrote it: any actor, any
  labels, public or not, any data."
  (gen/let [type (gen/elements old-kinds)
            actor (gen/elements ["operator" "dispatcher" "scout" "hub"])
            labels (gen/elements [[] ["contaminated"] ["private"] ["contaminated" "private"]])
            public gen/boolean
            data (gen/map gen/keyword (gen/one-of [gen/small-integer gen/string-alphanumeric
                                                    (gen/vector gen/small-integer 0 3)])
                          {:max-elements 4})]
    [:removed (cond-> {:type type :actor actor :labels labels :data data}
                public (assoc :public true))]))

(def gen-command
  (gen/one-of [(gen/fmap (fn [s] [:memo (str "memo " s)]) gen/string-alphanumeric)
               (gen/fmap (fn [s] [:fact (str "fact " s)]) gen/string-alphanumeric)
               (gen/fmap (fn [[n s]] [:note n (str "note " s)])
                         (gen/tuple (gen/elements ["a" "b"]) gen/string-alphanumeric))]))

(defn- start []
  (-> (ct/world)
      (ct/run "operator" :role/add {:id "hub" :title "Hub" :instructions "Run relay."})
      (ct/run "operator" :role/add {:id "scout" :title "Scout" :instructions "Look." :public true} true)))

(defn- apply-step
  "One step on a world: a command, or an old event put into the log as the
  next one."
  [w [kind a b]]
  (case kind
    :removed (let [e (assoc a :seq (:next-seq (:state w)) :at (str (:now w)))]
               (-> w (update :state core/evolve e) (update :events conj e)))
    :memo (ct/run w "operator" :memo/send {:to "hub" :subject a :body "b"})
    :fact (ct/run w "operator" :fact/add {:topic "home" :text a})
    :note (ct/run w "hub" :note/write {:owner "hub" :name a :text b})))

(defn- meaning
  "What a state says, without the numbers that depend on how many events
  came before."
  [state]
  {:roles (sort (keys (:roles state)))
   :memos (sort (map :subject (vals (:memos state))))
   :facts (sort (map #(:text (first (:versions %))) (vals (:facts state))))
   :notes (into (sorted-map) (for [[o ns] (:notes state), [n v] ns] [[o n] (:text v)]))})

(defspec removed-events-fold-to-nothing 200
  (prop/for-all [steps (gen/vector (gen/frequency [[3 gen-command] [2 gen-removed]]) 0 25)]
    (let [with (reduce apply-step (start) steps)
          without (reduce apply-step (start) (remove #(= :removed (first %)) steps))
          replayed (core/replay (:events with))]
      (and (= (:state with) replayed)
           (= (meaning (:state with)) (meaning (:state without)))
           (= (count (:events with)) (dec (:next-seq replayed)))
           (every? #(not (contains? replayed %)) [:reports :ledger :trust-levels])))))

(deftest removed-events-at-the-end-change-nothing-at-all
  (doseq [type old-kinds]
    (testing type
      (let [w (reduce apply-step (start) [[:memo "m"] [:fact "f"] [:note "a" "x"]])
            old {:type type :actor "scout" :labels ["contaminated"] :public true :data {:report 1 :finding 0}}
            w' (apply-step w [:removed old])]
        (is (= (dissoc (:state w) :seen-labels :next-seq :last-at)
               (dissoc (:state w') :seen-labels :next-seq :last-at)))
        (is (= (dissoc (:state w') :seen-labels :next-seq :last-at)
               (dissoc (core/replay (:events w')) :seen-labels :next-seq :last-at)))))))

(deftest a-removed-event-still-counts-its-labels-as-seen
  (let [w (apply-step (start) [:removed {:type "report/verified" :actor "dispatcher" :labels ["contaminated"]
                                         :data {:report 3}}])]
    (is (some #{"contaminated"} (:seen-labels (core/replay (:events w)))))))

(deftest an-event-kind-relay-never-wrote-is-still-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not know event type"
                        (core/evolve (:state (start))
                                     {:type "report/never-existed" :actor "operator" :labels [] :data {}
                                      :seq (:next-seq (:state (start))) :at "2026-10-14T12:00:00Z"}))))

(deftest an-old-log-with-realistic-events-folds-like-the-log-without-them
  (let [w (-> (start)
              (apply-step [:memo "m"]) (apply-step [:fact "f"]) (apply-step [:note "a" "x"]))
        old [{:type "report/submitted" :actor "scout" :labels ["contaminated"] :public true
              :data {:report 1 :title "Heat pumps" :body "Findings." :sources ["https://example.org"]}}
             {:type "report/verify-started" :actor "dispatcher" :labels [] :data {:report 1}}
             {:type "report/verify-failed" :actor "dispatcher" :labels [] :data {:report 1 :reason "no source"}}
             {:type "report/verified" :actor "dispatcher" :labels ["contaminated"] :data {:report 1}}
             {:type "finding/reviewed" :actor "operator" :labels [] :data {:report 1 :finding 0 :verdict "ok"}}
             {:type "ledger/entry-added" :actor "operator" :labels [] :data {:entry 1 :text "Bought a lamp" :amount 20}}
             {:type "ledger/status-set" :actor "operator" :labels [] :data {:entry 1 :status "paid"}}
             {:type "ledger/entry-withdrawn" :actor "operator" :labels [] :data {:entry 1}}
             {:type "role/trust-level" :actor "operator" :labels [] :data {:role "scout" :level "low"}}]
        with (reduce (fn [w e] (apply-step w [:removed e])) w old)
        without (:state w)
        replayed (core/replay (:events with))]
    (is (= (count old) (- (:next-seq replayed) (:next-seq without))))
    (is (= (meaning without) (meaning replayed)))
    (is (= (:state with) replayed))
    (is (= (set (map :type old)) (set old-kinds)))))
