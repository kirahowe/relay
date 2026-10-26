(ns relay.removed-events-test
  "Every old log still folds (I8). The log is append-only, so the events
  older versions of relay wrote for machinery that is gone stay in Kira's
  log: research reports, findings, the ledger and trust levels, and then
  holds, permission requests, trust, release, declassify and the labels
  marker. The state they used to build is gone, and so are they: a log
  with them folds to what the same log folds to without them."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.core :as core]
            [relay.core-test :as ct]
            [relay.text :as text]))

(def old-kinds
  "Every kind the pre-house relay wrote for machinery that no longer
  exists, listed here on their own so that dropping one from
  core/removed-event-kinds fails a test instead of silently dropping it
  from the tests too."
  [;; research reports, findings, the ledger, trust levels (B1)
   "report/submitted" "report/verify-started" "report/verify-failed" "report/verified"
   "finding/reviewed" "ledger/entry-added" "ledger/entry-withdrawn" "ledger/status-set"
   "role/trust-level"
   ;; holds, permission requests, trust, release, declassify and the
   ;; labels marker (B2)
   "hold/opened" "hold/previewed" "hold/resolved" "hold/requeued" "hold/session-ended"
   "permission/asked" "permission/answered"
   "label/trusted" "memo/released" "memo/declassified" "labels/enabled"])

(deftest the-removed-kinds-are-exactly-the-ones-old-relay-wrote
  (is (= (set old-kinds) core/removed-event-kinds))
  (is (= 20 (count old-kinds))))

(def gen-data
  (gen/map gen/keyword (gen/one-of [gen/small-integer gen/string-alphanumeric gen/boolean
                                    (gen/vector gen/small-integer 0 3)])
           {:max-elements 4}))

(def gen-removed
  "An event of a removed kind, as an old relay wrote it: any actor, any
  labels, public or not, any data, often naming the memos, roles and
  sessions the log really has."
  (gen/let [type (gen/elements old-kinds)
            actor (gen/elements ["operator" "dispatcher" "scout" "hub"])
            labels (gen/elements [[] ["contaminated"] ["private"] ["contaminated" "private"]])
            public gen/boolean
            data gen-data
            refs (gen/map (gen/elements [:memo :hold :role :wake :session :kind :ref :version :verdict])
                          (gen/one-of [(gen/choose 1 30) (gen/elements ["hub" "scout" "memo" "doc" "allow" "s1"])])
                          {:max-elements 4})]
    [:removed (cond-> {:type type :actor actor :labels labels :data (merge data refs)}
                public (assoc :public true))]))

(def gen-command
  (gen/one-of [(gen/fmap (fn [s] [:memo (str "memo " s)]) gen/string-alphanumeric)
               (gen/fmap (fn [s] [:fact (str "fact " s)]) gen/string-alphanumeric)
               (gen/fmap (fn [[n s]] [:note n (str "note " s)])
                         (gen/tuple (gen/elements ["a" "b"]) gen/string-alphanumeric))
               (gen/fmap (fn [[p s]] [:doc p (str "doc " s)])
                         (gen/tuple (gen/elements ["a.md" "b/c.md"]) gen/string-alphanumeric))
               (gen/fmap (fn [s] [:journal (str "entry " s)]) gen/string-alphanumeric)
               (gen/fmap (fn [s] [:card (str "card " s)]) gen/string-alphanumeric)
               (gen/fmap (fn [[n s]] [:routine n s])
                         (gen/tuple (gen/elements ["a" "b"]) (gen/elements ["every 1h" "daily 07:30"])))]))

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
    :note (ct/run w "hub" :note/write {:owner "hub" :name a :text b})
    :doc (ct/run w "hub" :doc/put {:path a :content b})
    :journal (ct/run w "hub" :journal/write {:text a})
    :card (ct/run w "operator" :card/add {:kind "note" :title a :text "t"})
    :routine (ct/run w "hub" :routine/write {:name a :schedule b :instructions "i"})))

(defn- meaning
  "What a state says, without the numbers that depend on how many events
  came before."
  [state]
  {:roles (into (sorted-map) (for [[id r] (:roles state)] [id (select-keys r [:title :instructions :status])]))
   :memos (sort (map (juxt :subject :status) (vals (:memos state))))
   :facts (sort (map #(:text (first (:versions %))) (vals (:facts state))))
   :notes (into (sorted-map) (for [[o ns] (:notes state), [n v] ns] [[o n] (:text v)]))
   :docs (into (sorted-map) (for [[p d] (:docs state)] [p (mapv #(or (:content %) :removed) (:versions d))]))
   :journals (into (sorted-map) (for [[o es] (:journals state)] [o (mapv :text es)]))
   :cards (sort (map #(get-in % [:title :text]) (vals (:cards state))))
   :routines (into (sorted-map) (for [[o rs] (:routines state), [n r] rs] [[o n] (select-keys r [:schedule :status])]))})

(defspec removed-events-fold-to-nothing 200
  (prop/for-all [steps (gen/vector (gen/frequency [[3 gen-command] [2 gen-removed]]) 0 25)]
    (let [with (reduce apply-step (start) steps)
          without (reduce apply-step (start) (remove #(= :removed (first %)) steps))
          replayed (core/replay (:events with))
          marker (some #(= "labels/enabled" (:type (second %))) steps)]
      ;; The labels marker still says where labels began, so with one in
      ;; the log replay gives what came before it legacy labels
      ;; (what-roles-wrote-before-the-labels-marker-is-from-outside);
      ;; what the log says is the same either way.
      (and (or marker (= (:state with) replayed))
           (= (meaning (:state with)) (meaning replayed) (meaning (:state without)))
           (= (count (:events with)) (dec (:next-seq replayed)))
           (every? #(not (contains? replayed %)) [:reports :ledger :trust-levels :holds :permissions])))))

(deftest removed-events-at-the-end-change-nothing-at-all
  (doseq [type old-kinds]
    (testing type
      (let [w (reduce apply-step (start) [[:memo "m"] [:fact "f"] [:note "a" "x"] [:doc "a.md" "d"]])
            old {:type type :actor "scout" :labels ["contaminated"] :public true
                 :data {:report 1 :finding 0 :memo 3 :hold 3 :kind "memo" :ref "3" :version 3}}
            w' (apply-step w [:removed old])]
        (is (= (dissoc (:state w) :seen-labels :next-seq :last-at)
               (dissoc (:state w') :seen-labels :next-seq :last-at)))
        ;; A labels marker at the end makes everything before it legacy
        ;; on replay; what-roles-wrote-before-the-labels-marker-is-from-outside.
        (when-not (= "labels/enabled" type)
          (is (= (dissoc (:state w') :seen-labels :next-seq :last-at)
                 (dissoc (core/replay (:events w')) :seen-labels :next-seq :last-at))))))))

(deftest a-removed-event-still-counts-its-labels-as-seen
  (let [w (apply-step (start) [:removed {:type "hold/opened" :actor "dispatcher" :labels ["contaminated"]
                                         :data {:role "hub" :tool "post"}}])]
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
             {:type "role/trust-level" :actor "operator" :labels [] :data {:role "scout" :level "low"}}
             {:type "labels/enabled" :actor "dispatcher" :labels [] :data {}}
             {:type "hold/opened" :actor "dispatcher" :labels ["contaminated" "private"]
              :data {:role "hub" :wake 7 :session "s1" :call "toolu_1" :tool "campaign_send"
                     :input {:campaign 4} :input-sha256 "ab" :input-json "{\"campaign\":4}"}}
             {:type "hold/previewed" :actor "dispatcher" :labels ["contaminated"]
              :data {:hold 1 :content "Sends to 4,000 people." :is-error false}}
             {:type "hold/resolved" :actor "operator" :labels [] :data {:hold 1 :verdict "allow" :reason "ok"}}
             {:type "hold/requeued" :actor "dispatcher" :labels [] :data {:hold 1}}
             {:type "hold/session-ended" :actor "operator" :labels []
              :data {:role "hub" :session "s1" :why "the operator started it afresh" :fresh true}}
             {:type "permission/asked" :actor "dispatcher" :labels ["private"]
              :data {:wake 9 :role "hub" :session "s2" :call "c1" :tool "post" :input-sha256 "cd"
                     :preview {:content "Posts it." :is-error false}}}
             {:type "permission/answered" :actor "dispatcher" :labels ["private"]
              :data {:wake 9 :role "hub" :session "s2" :call "c1" :outcome "allow_once" :by "client"}}
             {:type "label/trusted" :actor "operator" :labels [] :data {:kind "memo" :ref "4" :version 4}}
             {:type "label/trusted" :actor "operator" :labels [] :data {:kind "note" :ref "hub/a" :version 6 :via "chat"}}
             {:type "memo/released" :actor "operator" :labels [] :data {:memo 4}}
             {:type "memo/declassified" :actor "operator" :labels ["contaminated"]
              :data {:memo 4 :subject "m" :body "b" :sha256 "ef" :from-labels []}}]
        with (reduce (fn [w e] (apply-step w [:removed e])) w old)
        without (:state w)
        replayed (core/replay (:events with))]
    (is (= (count old) (- (:next-seq replayed) (:next-seq without))))
    (is (= (meaning without) (meaning replayed)))
    (testing "what the hub wrote before the labels marker is from outside"
      (is (= #{"contaminated" "private"} (set (get-in replayed [:notes "hub" "a" :labels])))))
    (testing "a memo once trusted, released or declassified is as it was written"
      (let [m (first (filter #(= "m" (:subject %)) (vals (:memos replayed))))]
        (is (not (:public m)))
        (is (= (:labels (first (filter #(= "m" (:subject %)) (vals (:memos without))))) (:labels m)))))
    (is (= (set (map :type old)) (set old-kinds)))))

(deftest kept-kinds-with-fields-that-are-gone-still-fold
  ;; wake/started once carried the holds it carried back, wake/ended the
  ;; calls it left held, role/tools-unavailable whether it waited for
  ;; relay fresh, and conversation/line-dropped whether a dropped line was
  ;; a permission request.
  (let [w (start)
        at (str (:now w))
        n (:next-seq (:state w))
        events [{:seq n :at at :type "wake/started" :actor "dispatcher" :labels []
                 :data {:role "hub" :memos [] :note "go" :harness "agentloop" :resolves [3 4] :resumes nil}}
                {:seq (+ n 1) :at at :type "wake/ended" :actor "dispatcher" :labels ["private"]
                 :data {:wake n :outcome "ran" :exit 0 :session "s1" :said "held" :held ["toolu_1"]}}
                {:seq (+ n 2) :at at :type "role/tools-unavailable" :actor "dispatcher" :labels []
                 :data {:role "hub" :wake n :session "s1" :tool "t1" :got (apply str (repeat 64 "b")) :fresh true :tries 1}}
                {:seq (+ n 3) :at at :type "wake/started" :actor "operator" :labels []
                 :data {:role "hub" :memos [] :note "talk" :harness "agentloop" :chat true}}
                {:seq (+ n 4) :at at :type "conversation/line-dropped" :actor "dispatcher" :labels []
                 :data {:wake (+ n 3) :role "hub" :size 9000000 :permission true :refused true}}]
        s (reduce core/evolve (:state w) events)]
    (is (= :ended (get-in s [:wakes n :status])))
    (testing "a role once waiting for relay fresh is not left waiting for ever"
      (is (not (core/waiting-on-tools? s "hub" (:now w)))))
    (testing "a session once left with held calls is not carried on"
      (is (nil? (:interrupted-wake (core/role s "hub")))))))

(deftest what-roles-wrote-before-the-labels-marker-is-from-outside
  ;; relay did not record what a role had seen before it kept labels, so
  ;; anything a role or the dispatcher wrote before the log's first
  ;; labels/enabled marker is taken to have seen everything. The marker is
  ;; a removed kind and folds to nothing, but replay still reads where it
  ;; is. What the operator wrote is hers.
  (let [at "2026-10-01T12:00:00Z"
        ev (fn [seq type actor data & {:as more}] (merge {:seq seq :at at :type type :actor actor :labels [] :data data} more))
        events [(ev 1 "role/added" "operator" {:id "hub" :title "Hub" :instructions "Run relay."})
                (ev 2 "role/added" "operator" {:id "scout" :title "S" :instructions "x"} :public true)
                (ev 3 "memo/sent" "hub" {:from "hub" :to "operator" :subject "Look"
                                         :body "see https://evil.example/?q=secret"})
                (ev 4 "fact/added" "hub" {:topic "home" :text "The code is at evil.example"})
                (ev 5 "memo/sent" "operator" {:from "operator" :to "hub" :subject "Mine" :body "https://kira.example"})
                (ev 6 "memo/sent" "scout" {:from "scout" :to "scout" :subject "s" :body "b"} :public true)
                (ev 7 "labels/enabled" "dispatcher" {})
                (ev 8 "memo/sent" "hub" {:from "hub" :to "operator" :subject "Later" :body "https://later.example"})
                (ev 9 "labels/enabled" "dispatcher" {})]
        s (core/replay events)
        shown (fn [labels s] (text/for-operator labels s))]
    (testing "a role's memo from before the marker is from outside, its link broken"
      (let [m (core/memo s 3)]
        (is (= #{"contaminated" "private"} (set (:labels m))))
        (is (= "[from outside] see hxxps://evil[.]example/?q=secret" (shown (:labels m) (:body m))))))
    (testing "a role's fact from before the marker likewise"
      (let [f (core/fact-as-known (first (vals (:facts s))) nil)]
        (is (= #{"contaminated" "private"} (set (:labels f))))
        (is (re-find #"^\[from outside\] .*evil\[\.\]example" (shown (:labels f) (:text f))))))
    (testing "what the operator wrote is hers"
      (is (= [] (:labels (core/memo s 5)))))
    (testing "a public memo from before the marker is not public any more"
      (is (not (:public (core/memo s 6)))))
    (testing "only the first marker counts, and what came after it carries its own labels"
      (is (= [] (:labels (core/memo s 8)))))
    (testing "a log with no marker carries its own labels"
      (is (= [] (:labels (core/memo (core/replay (take 6 events)) 3)))))))

(deftest a-declassified-memo-keeps-the-text-the-operator-vetted
  ;; Declassify replaced a memo's subject and body with the text the
  ;; operator was shown and vouched for. It is gone, and the memo is no
  ;; longer public, but its text is still the vetted one, as before.
  (let [at "2026-10-16T12:00:00Z"
        events [{:seq 1 :at at :type "role/added" :actor "operator" :labels [] :data {:id "hub" :title "Hub" :instructions "x"}}
                {:seq 2 :at at :type "labels/enabled" :actor "dispatcher" :labels [] :data {}}
                {:seq 3 :at at :type "memo/sent" :actor "hub" :labels ["private"]
                 :data {:from "hub" :to "hub" :subject "Raw" :body "Raw, with Kira's address."}}
                {:seq 4 :at at :type "memo/declassified" :actor "operator" :labels []
                 :data {:memo 3 :subject "Vetted" :body "Vetted." :sha256 "ef" :from-labels []}}]
        m (core/memo (core/replay events) 3)]
    (is (= ["Vetted" "Vetted."] [(:subject m) (:body m)]))
    (is (not (:public m)))
    (is (= ["private"] (:labels m)))))
