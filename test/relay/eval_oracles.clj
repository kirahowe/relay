(ns relay.eval-oracles
  "Every eval case's oracle and foils against the mock model, in shards.
  Volley runs tests in parallel only by namespace, and a scenario run sets
  the relay.bin property for its whole process, so the shards are
  namespaces of their own (relay.eval-oracles-N-test) rather than threads."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing]]
            [relay.eval-trial :as et]))

(def shards 3)

(defn check-shard!
  "Checks every case whose place among the case files is `i` modulo
  `shards`: the oracle passes every grader, and each foil fails exactly
  the graders it names."
  [i]
  (doseq [[n f] (map-indexed vector (sort (map str (et/eval-case-files))))
          :when (= i (mod n shards))
          :let [{:keys [oracle foils]} (et/mock-verdicts f)]]
    (testing f
      (is (empty? oracle) (str "oracle: " (str/join "; " oracle)))
      (is (seq foils) "every case has a foil")
      (doseq [[nm problems] foils]
        (is (empty? problems) (str "foil " nm ": " (str/join "; " problems)))))))
