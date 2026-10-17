(ns relay.search
  "Rank short texts against a query with Okapi BM25. Pure: no IO, no state.

  Scoring follows Lucene's BM25Similarity (apache/lucene at commit 8a8ef48,
  the 11.0.0 development line, file
  lucene/core/src/java/org/apache/lucene/search/similarities/BM25Similarity.java,
  checked 2026-10-17): k1 = 1.2, b = 0.75, idf = log(1 + (N - n + 0.5) / (n + 0.5)),
  and a term's score is idf * tf / (tf + k1 * (1 - b + b * dl / avgdl)). Lucene
  dropped the old (k1 + 1) numerator, which scales every score by the same
  amount and so never changes an order. The idf is never negative, so a term in
  most documents cannot push a match below zero. A document matches when its
  score is above zero, which happens exactly when it shares a token with the
  query.

  Several fields count together the way BM25F does: each field's term count
  and length are scaled by its weight and summed, and the sum is scored as
  one text. A document counts toward a term's document frequency when any
  field holds the term.

  Tokens: text is normalised with NFKD, combining marks are dropped (so
  \"café\" is \"cafe\"), the result is lowercased and split on anything that is
  not a letter or digit. Tokens keep when they have two or more characters,
  or are a digit of any length. Tokens made only of a-z are stemmed with the
  Snowball English stemmer (Porter2), see relay.search.porter2, so \"renewal\",
  \"renews\" and \"renewed\" all become \"renew\"."
  (:require [clojure.string :as str]
            [relay.search.porter2 :as porter2])
  (:import (java.text Normalizer Normalizer$Form)))

(def k1 1.2)
(def b 0.75)

(defn stem
  "The Porter2 stem of a lowercase token. Tokens with anything but a-z in
  them come back unchanged."
  [w]
  (porter2/stem w))

(defn tokens
  "The stemmed tokens of a text, in order, repeats kept. Nil gives none."
  [text]
  (->> (-> (Normalizer/normalize (str text) Normalizer$Form/NFKD)
           (str/replace #"\p{M}+" "")
           str/lower-case
           (str/split #"[^\p{L}\p{N}]+"))
       (filter #(or (>= (count %) 2) (re-matches #"\p{N}" %)))
       (map stem)))

(defn- field-stats
  "For one doc: {:len weighted length, :tf {term weighted count}} over fields."
  [doc fields]
  (reduce (fn [acc [f w]]
            (let [ts (tokens (f doc))]
              (-> acc
                  (update :len + (* w (count ts)))
                  (update :tf #(reduce (fn [m t] (update m t (fnil + 0.0) w)) % ts)))))
          {:len 0.0 :tf {}}
          fields))

(defn- idf [n-docs df]
  (Math/log (+ 1.0 (/ (+ (- n-docs df) 0.5) (+ df 0.5)))))

(defn rank
  "The docs matching the query, best first, or nil when the query has no
  tokens (so the caller can fall back to unranked behaviour).

  opts:
    :text-fn  doc -> text; default :text. Used when :fields is absent.
    :fields   {field-fn weight}, e.g. {:text 1.0 :topic 0.5}. Keywords work
              as field fns.
    :tie-fn   doc -> comparable key breaking equal scores, ascending. Without
              it, equal scores keep input order."
  [docs query {:keys [text-fn fields tie-fn]}]
  (let [terms (distinct (tokens query))
        fields (or fields {(or text-fn :text) 1.0})]
    (when (seq terms)
      (let [stats (mapv #(field-stats % fields) docs)
            n (count docs)
            avgdl (if (pos? n) (/ (reduce + (map :len stats)) n) 0.0)
            idfs (into {} (for [t terms]
                            [t (idf n (count (filter #(contains? (:tf %) t) stats)))]))
            score (fn [{:keys [len tf]}]
                    (let [norm (+ (- 1.0 b) (* b (if (pos? avgdl) (/ len avgdl) 1.0)))]
                      (reduce + 0.0
                              (for [t terms :let [f (get tf t 0.0)] :when (pos? f)]
                                (* (idfs t) (/ f (+ f (* k1 norm))))))))
            scored (keep-indexed (fn [i d]
                                   (let [s (score (nth stats i))]
                                     (when (pos? s) {:doc d :score s})))
                                 docs)
            by-score (fn [x y] (compare (:score y) (:score x)))
            cmp (if tie-fn
                  (fn [x y] (let [c (by-score x y)]
                              (if (zero? c) (compare (tie-fn (:doc x)) (tie-fn (:doc y))) c)))
                  by-score)]
        (map :doc (sort cmp scored))))))
