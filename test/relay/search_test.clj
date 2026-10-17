(ns relay.search-test
  "Search ranks short texts by BM25 and matches the way a person types."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.search :as search]))

(def gen-word
  (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcdefghklmnoprt")) 3 7)))

(def gen-text (gen/fmap #(str/join " " %) (gen/vector gen-word 0 12)))

(def gen-docs
  (gen/fmap (fn [ts] (vec (map-indexed (fn [i t] {:id i :text t}) ts)))
            (gen/vector gen-text 0 8)))

(def gen-query (gen/fmap #(str/join " " %) (gen/vector gen-word 1 4)))

(defn- rank [docs q] (search/rank docs q {:text-fn :text :tie-fn :id}))

(defspec results-are-a-subset-of-the-docs 100
  (prop/for-all [docs gen-docs q gen-query]
    (every? (set docs) (rank docs q))))

(defspec order-is-deterministic 100
  (prop/for-all [docs gen-docs q gen-query]
    (= (rank docs q) (rank (shuffle docs) q))))

(defspec docs-with-equal-text-tie-by-the-tie-fn 100
  (prop/for-all [t gen-text q gen-query n (gen/choose 1 6)]
    (let [docs (mapv (fn [i] {:id i :text t}) (range n))
          ids (map :id (rank (shuffle docs) q))]
      (= ids (sort ids)))))

(defspec a-doc-with-every-token-ranks-above-one-missing-a-token 200
  (prop/for-all [q-words (gen/vector-distinct gen-word {:min-elements 1 :max-elements 4})
                 filler (gen/vector gen-word 0 6)
                 pick gen/nat
                 others gen-docs]
    (let [q (str/join " " q-words)
          ;; One surface word per stem, so replacing a word removes its token.
          qt (vec (vals (into (sorted-map) (map (juxt search/stem identity) q-words))))
          missing (nth qt (mod pick (count qt)))
          ;; Same length: the missing token is replaced, not dropped.
          full (str/join " " (concat qt filler))
          short (str/join " " (concat (map #(if (= % missing) "zzzz" %) qt) filler))
          a {:id -2 :text full}
          b {:id -1 :text short}
          result (rank (into [b a] others) q)
          pos (fn [d] (.indexOf ^java.util.List (vec result) d))]
      (and (<= 0 (pos a))
           (or (neg? (pos b)) (< (pos a) (pos b)))))))

(defspec case-and-accents-do-not-change-the-ranking 100
  (prop/for-all [docs gen-docs q gen-query]
    (let [accent (fn [s] (str/replace s #"e" "é"))
          docs' (mapv #(update % :text (comp str/upper-case accent)) docs)]
      (= (map :id (rank docs q)) (map :id (rank docs' (str/upper-case q)))))))

(defspec an-unrelated-doc-never-changes-which-docs-match 100
  (prop/for-all [docs gen-docs q gen-query extra gen-text]
    (let [more (conj docs {:id 999 :text extra})]
      (= (set (map :id (rank docs q)))
         (disj (set (map :id (rank more q))) 999)))))

(defspec a-query-found-in-no-doc-returns-empty 100
  (prop/for-all [docs gen-docs]
    (empty? (rank docs "zzzzqqqq"))))

(defspec a-blank-query-returns-nil 50
  (prop/for-all [docs gen-docs blank (gen/elements ["" " " "\n\t" "!!" "-" nil])]
    (nil? (rank docs blank))))

(deftest examples
  (let [docs [{:id 1 :text "The car insurance renews on 3 March"}
              {:id 2 :text "Dentist appointment moved to Friday"}
              {:id 3 :text "Buy milk"}]]
    (is (= [1] (map :id (rank docs "car insurance renewal"))))
    (is (= [2] (map :id (rank docs "dentist"))))
    (is (= [1] (map :id (rank docs "renewing"))))
    (is (= [2] (map :id (rank docs "appointments moved"))))
    (is (= ["renew" "renew" "renew"] (map search/stem ["renewal" "renews" "renewed"])))
    (is (= (search/stem "moves") (search/stem "moved")))
    (is (= ["cafe" "3" "x1"] (search/tokens "Café 3 a x1")))
    (is (= [{:id 1 :text "cafe"}] (rank [{:id 1 :text "cafe"}] "Café")))))

(deftest fields-count-with-their-weights
  (let [docs [{:id 1 :topic "tax" :text "pay later"}
              {:id 2 :topic "misc" :text "tax tax tax"}]
        fields {:text 1.0 :topic 0.5}]
    (is (= #{1 2} (set (map :id (search/rank docs "tax" {:fields fields})))))
    (is (= [2] (map :id (search/rank docs "tax" {:fields {:text 1.0}}))))
    (is (= [1] (map :id (search/rank docs "tax" {:fields {:topic 1.0}}))))))

(deftest a-better-match-ranks-first
  (let [docs [{:id 1 :text "dentist"} {:id 2 :text "dentist dentist friday"}
              {:id 3 :text "friday friday friday"}]]
    (is (= [1 2] (map :id (take 2 (rank docs "dentist")))))
    (is (= 2 (:id (first (rank docs "dentist friday dentist")))) "the doc with both terms wins")))
