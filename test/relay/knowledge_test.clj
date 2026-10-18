(ns relay.knowledge-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.core :as core]
            [relay.core-test :refer [advance run t0 world]]
            [relay.tools :as tools])
  (:import (java.time Instant)))

(defn- seed []
  (-> (world)
      (run "operator" :role/add {:id "hub" :title "Hub" :instructions "Run relay."})
      (run "operator" :role/add {:id "clerk" :title "Clerk" :instructions "File things."})))

(defn- created [w] (:seq (last (:last w))))
(defn- texts [w q] (mapv :text (core/facts (:state w) q)))
(defn- at [s] (Instant/parse s))

(deftest a-change-in-the-world-supersedes-a-fact-and-keeps-it
  (let [w (-> (seed) (run "clerk" :fact/add {:topic "household/car" :text "The car is blue."
                                             :valid-from "2026-01-01"}))
        blue (created w)
        before-change (:now (advance w 10))
        w (-> w (advance 3600)
              (run "clerk" :fact/supersede {:fact blue :text "The car is red."
                                            :valid-from "2026-10-01"}))
        red (created w)]
    (testing "now it is red, and in September it was blue"
      (is (= ["The car is red."] (texts w {:as-of (:now w)})))
      (is (= ["The car is blue."] (texts w {:as-of (at "2026-09-15T00:00:00Z")}))))
    (testing "the old fact is still there, ended where the change begins, pointing at the new one"
      (let [old (core/fact-as-known (core/fact (:state w) blue) nil)]
        (is (= red (:superseded-by old)))
        (is (= (at "2026-10-01T04:00:00Z") (:valid-to old)) "midnight in Toronto")
        (is (= 2 (count (:versions (core/fact (:state w) blue)))))
        (is (= blue (:supersedes (core/fact (:state w) red))))))
    (testing "before the change was recorded, relay thought it was still blue"
      (is (= ["The car is blue."] (texts w {:as-of (:now w) :known-at before-change}))))
    (testing "a fact is superseded once; the newest one is the one to supersede"
      (is (= :conflict (:rejected (run w "clerk" :fact/supersede {:fact blue :text "Green."})))))
    (testing "a change cannot come before the fact began"
      (is (= :invalid (:rejected (run w "clerk" :fact/supersede {:fact red :text "Green."
                                                                 :valid-from "2026-09-01"})))))
    (testing "topics are a hierarchy"
      (is (= ["The car is red."] (texts w {:as-of (:now w) :topic "household"})))
      (is (= [] (texts w {:as-of (:now w) :topic "house"}))))))

(deftest a-correction-rewrites-the-record-not-the-world
  (let [w (-> (seed) (run "clerk" :fact/add {:text "The dentist is on Elm Street."
                                             :valid-from "2026-01-01"}))
        id (created w)
        before (:now w)
        w (-> w (advance 3600)
              (run "clerk" :fact/correct {:fact id :text "The dentist is on Oak Street."
                                          :why "Misread the card."}))
        march (at "2026-03-01T00:00:00Z")]
    (testing "as known now, it was Oak Street all along"
      (is (= ["The dentist is on Oak Street."] (texts w {:as-of march}))))
    (testing "as known before the correction, it was Elm Street"
      (is (= ["The dentist is on Elm Street."] (texts w {:as-of march :known-at before}))))
    (testing "the correction is a version of the same fact, with its reason"
      (let [[v1 v2] (:versions (core/fact (:state w) id))]
        (is (= [:added :corrected] [(:change v1) (:change v2)]))
        (is (= "Misread the card." (:why v2)))))
    (testing "a correction says why and changes something"
      (is (= :invalid (:rejected (run w "clerk" :fact/correct {:fact id :text "Pine Street."}))))
      (is (= :invalid (:rejected (run w "clerk" :fact/correct {:fact id :text "The dentist is on Oak Street."
                                                               :why "again"})))))
    (testing "a bound can be corrected open"
      (let [w (run w "clerk" :fact/correct {:fact id :valid-from "open" :why "It always was."})]
        (is (= ["The dentist is on Oak Street."] (texts w {:as-of (at "2020-01-01T00:00:00Z")})))))))

(deftest retracting-a-change-puts-back-the-fact-it-replaced
  (let [w (-> (seed) (run "clerk" :fact/add {:text "Bins go out on Tuesday." :valid-to "2027-01-01"}))
        tuesday (created w)
        w (-> w (advance 60) (run "clerk" :fact/supersede {:fact tuesday :text "Bins go out on Monday."}))
        monday (created w)
        w (-> w (advance 60) (run "clerk" :fact/correct {:fact monday :retract true
                                                         :why "That was the neighbour's street."}))]
    (is (= ["Bins go out on Tuesday."] (texts w {:as-of (:now w)})))
    (let [restored (core/fact-as-known (core/fact (:state w) tuesday) nil)]
      (is (nil? (:superseded-by restored)))
      (is (= (at "2027-01-01T05:00:00Z") (:valid-to restored)) "its own end comes back"))
    (is (= :invalid (:rejected (run w "clerk" :fact/correct {:fact monday :retract true :why "x"}))))
    (is (= [] (texts w {:all true :search "monday"})))
    (testing "but not one in the middle of a chain of changes"
      (let [w (-> w (run "clerk" :fact/supersede {:fact tuesday :text "Wednesday."}))
            wednesday (created w)
            w (-> w (advance 60) (run "clerk" :fact/supersede {:fact wednesday :text "Thursday."}))]
        (is (= :invalid (:rejected (run w "clerk" :fact/correct {:fact wednesday :retract true :why "x"}))))))))

(deftest fact-times-and-validation
  (let [w (seed)]
    (is (= :invalid (:rejected (run w "clerk" :fact/add {:text "x" :valid-from "next tuesday"}))))
    (is (= :invalid (:rejected (run w "clerk" :fact/add {:text "x" :valid-from "2026-02-01"
                                                         :valid-to "2026-01-01"}))))
    (is (= :invalid (:rejected (run w "clerk" :fact/add {:text "x" :topic "Household Car"}))))
    (is (= :invalid (:rejected (run w "clerk" :fact/add {:text "  "}))))
    (is (= :not-found (:rejected (run w "clerk" :fact/supersede {:fact 999 :text "x"}))))
    (let [w (run w "clerk" :fact/add {:text "Holiday." :valid-from "2026-12-24 17:00"
                                      :valid-to "2027-01-02T00:00:00Z"})]
      (is (= (at "2026-12-24T22:00:00Z") (:valid-from (first (core/facts (:state w) {:all true})))))
      (is (= [] (texts w {:as-of t0})) "not yet")
      (is (= ["Holiday."] (texts w {:as-of (at "2026-12-25T12:00:00Z")}))))))

(deftest notes-belong-to-their-role
  (let [w (-> (seed) (run "clerk" :note/write {:name "filing" :text "Blue folders first."}))]
    (is (= ["Blue folders first."] (map :text (core/notes (:state w) "clerk"))))
    (is (empty? (core/notes (:state w) "hub")))
    (testing "rewriting the same text records nothing"
      (is (empty? (:last (run w "clerk" :note/write {:name "filing" :text "Blue folders first."})))))
    (testing "the operator names whose note it is"
      (is (= :forbidden (:rejected (run w "operator" :note/write {:name "x" :text "y"}))))
      (is (= ["y"] (map :text (core/notes (:state (run w "operator" :note/write {:owner "hub" :name "x"
                                                                                 :text "y"}))
                                          "hub")))))
    (testing "a role cannot reach another's notes"
      (is (= :not-found (:rejected (run w "hub" :note/remove {:owner "clerk" :name "filing"})))))
    (is (empty? (core/notes (:state (run w "clerk" :note/remove {:name "filing"})) "clerk")))))

(deftest the-journal-only-grows
  (let [w (reduce (fn [w n] (-> w (advance 60) (run "clerk" :journal/write {:text (str "Entry " n)})))
                  (seed) (range 5))]
    (is (= ["Entry 3" "Entry 4"] (map :text (core/journal (:state w) "clerk" 2))))
    (is (= 5 (count (core/journal (:state w) "clerk" nil))))
    (is (= :invalid (:rejected (run w "clerk" :journal/write {:text ""}))))))

(deftest docs-keep-every-version
  (let [w (-> (seed) (run "clerk" :doc/put {:path "household/plan.md" :content "v1"}))
        v1 (created w)
        w (-> w (advance 60) (run "hub" :doc/put {:path "household/plan.md" :content "v2"}))
        v2 (created w)]
    (is (= "v2" (:content (core/doc-version (:state w) "household/plan.md" {}))))
    (is (= "v1" (:content (core/doc-version (:state w) "household/plan.md" {:version v1}))))
    (is (= "v1" (:content (core/doc-version (:state w) "household/plan.md" {:known-at t0}))))
    (is (= "hub" (:actor (core/doc-version (:state w) "household/plan.md" {:version v2}))))
    (testing "an unchanged put records nothing"
      (is (empty? (:last (run w "clerk" :doc/put {:path "household/plan.md" :content "v2"})))))
    (testing "paths are checked"
      (is (= :invalid (:rejected (run w "clerk" :doc/put {:path "../etc/passwd" :content "x"}))))
      (is (= :invalid (:rejected (run w "clerk" :doc/put {:path "a//b" :content "x"}))))
      (is (= :conflict (:rejected (run w "clerk" :doc/put {:path "household" :content "x"}))))
      (is (= :conflict (:rejected (run w "clerk" :doc/put {:path "household/plan.md/x" :content "x"})))))
    (testing "a removed doc is gone from the list, and its history stays"
      (let [w (run w "clerk" :doc/remove {:path "household/plan.md"})]
        (is (empty? (core/docs (:state w) nil)))
        (is (= 1 (count (core/docs (:state w) t0))))
        (is (= 3 (count (get-in (:state w) [:docs "household/plan.md" :versions]))))
        (is (= :not-found (:rejected (run w "clerk" :doc/remove {:path "household/plan.md"}))))))))

;; --- search

(defn- add-fact
  "Adds a fact as clerk, from a session holding `labels`."
  [w text & [{:keys [topic labels]}]]
  (let [[state events] (core/run-command (:state w) {:type :fact/add :actor "clerk" :labels (or labels [])
                                                     :args (cond-> {:text text} topic (assoc :topic topic))}
                                         {:now (:now w) :config relay.core-test/cfg})]
    (-> w (assoc :state state :last events) (update :events into events))))

(defn- search-tool
  "The facts tool's answer to a search, as clerk."
  [w args]
  (:content ((:read (get tools/builtin "facts")) {:state (:state w) :config relay.core-test/cfg :now (:now w)}
             "clerk" args)))

(deftest search-ranks-facts-by-how-well-they-match
  (let [w (-> (seed)
              (add-fact "The car insurance renews in March." {:topic "household/car"})
              (add-fact "The car is blue." {:topic "household/car"})
              (add-fact "Home insurance is with Acme." {:topic "household"})
              (add-fact "The dentist is on Main Street."))
        ids (fn [q] (mapv :id (core/facts (:state w) (assoc q :as-of (:now w)))))
        [renews blue home _] (ids {})]
    (testing "a fact matching every word comes first, and one matching none is left out"
      (is (= renews (first (ids {:search "car insurance"}))))
      (is (= #{renews blue home} (set (ids {:search "car insurance"})))))
    (testing "words match by their stem"
      (is (= [renews] (ids {:search "renewal"}))))
    (testing "the topic filter applies before the ranking"
      (is (= [renews blue] (ids {:search "car insurance" :topic "household/car"}))))
    (testing "the tool keeps its format, best match first"
      (let [out (search-tool w {:search "insurance renewal"})]
        (is (str/starts-with? out (str "Fact " renews " [household/car], holds with no known start or end [[fact:" renews "]]\n"
                                       "  The car insurance renews in March.\nFact " home)))))))

(deftest equal-matches-go-by-fact-id
  (let [w (-> (seed) (add-fact "Bins go out on Tuesday.") (add-fact "Bins go out on Tuesday.")
              (add-fact "Bins go out on Tuesday."))
        ids (mapv :id (core/facts (:state w) {:as-of (:now w)}))]
    (is (= ids (mapv :id (core/facts (:state w) {:as-of (:now w) :search "tuesday bins"}))))
    (is (= ids (sort ids)))))

(deftest a-contaminated-fact-does-not-move-the-ranking-of-the-others
  ;; BM25 weighs a word by how many facts hold it, so a contaminated fact
  ;; ranked alongside the others could reorder them. It is left out first.
  (let [clean (-> (seed) (add-fact "Boat on the lake by the lake.") (add-fact "The car."))
        dirty (add-fact clean "Car car car car." {:labels ["contaminated"]})
        q {:search "boat car"}]
    (is (= (search-tool clean q) (search-tool dirty q)))
    (is (str/includes? (search-tool clean q) "The car.\nFact"))
    (testing "accepted, it is ranked with them, and here it reorders them"
      (let [out (search-tool dirty (assoc q :accept_contamination true))]
        (is (str/includes? out "Car car car car."))
        (is (< (str/index-of out "Boat") (str/index-of out "The car.")))))))
