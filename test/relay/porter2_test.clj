(ns relay.porter2-test
  "The Porter2 stemmer agrees with Snowball's own English vocabulary."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.search.porter2 :as porter2]))

(defn- lines [f] (str/split-lines (slurp (io/file "test/fixtures/snowball" f))))

(deftest every-pair-in-the-official-vocabulary-stems-as-expected
  (let [pairs (map vector (lines "voc.txt") (lines "output.txt"))
        bad (remove (fn [[word expected]] (= expected (porter2/stem word))) pairs)]
    (is (> (count pairs) 40000))
    (is (empty? (take 10 bad)))))

(defspec stemming-never-throws-and-never-grows-a-word-by-more-than-one 300
  (prop/for-all [w (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcdeghilmnorstuvwxyz'")) 0 14))]
    (<= (count (porter2/stem w)) (inc (count w)))))

(deftest renewal-forms-meet
  (is (= #{"renew"} (set (map porter2/stem ["renewal" "renews" "renewed" "renew" "renewing"])))))
