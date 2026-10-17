(ns relay.search.porter2
  "The Snowball English stemmer, also called Porter2, in pure Clojure.

  It follows Snowball's own source for the English stemmer, algorithms/english.sbl
  in https://github.com/snowballstem/snowball (fetched 2026-10-17, see the
  fixture README for commits), which is what the page
  https://snowballstem.org/algorithms/english/stemmer.html describes plus
  Snowball's later refinements: more R1 prefixes (gener, commun, arsen, past,
  univers, later, emerg, organ, inter), no undoubling after a, e or o in a
  three-letter stem (add, ebb, err), the -ogist ending, the proc/exc/succ
  exception to -eed, and -ing words such as inning, outing and evening left
  alone. The exceptional forms, the y/Y handling, R1 and R2 and steps 0 to 5
  are all there.

  The tests check every pair of the official English vocabulary against its
  expected output (test/fixtures/snowball).

  Input is a lowercase word of a-z and apostrophes. Anything else comes back
  unchanged."
  (:require [clojure.string :as str]))

(def ^:private exceptions
  {"skis" "ski" "skies" "sky" "idly" "idl" "gently" "gentl" "ugly" "ugli"
   "early" "earli" "only" "onli" "singly" "singl"
   "sky" "sky" "news" "news" "howe" "howe" "atlas" "atlas" "cosmos" "cosmos"
   "bias" "bias" "andes" "andes"})

(defn- vowel? [c] (contains? #{\a \e \i \o \u \y} c))

(defn- mark-y
  "Initial y, and y after a vowel, become Y so they count as consonants."
  [w]
  (reduce (fn [acc c]
            (str acc (if (and (= c \y) (or (empty? acc) (vowel? (last acc)))) \Y c)))
          ""
          w))

(def ^:private r1-prefixes ["gener" "commun" "arsen" "past" "univers" "later" "emerg" "organ" "inter"])

(defn- region-after
  "Index after the first non-vowel that follows a vowel, from index start,
  or the word length when there is none."
  [w start]
  (let [n (count w)]
    (loop [i (inc start)]
      (cond (>= i n) n
            (and (vowel? (nth w (dec i))) (not (vowel? (nth w i)))) (inc i)
            :else (recur (inc i))))))

(defn- regions [w]
  (let [r1 (or (some #(when (str/starts-with? w %) (count %)) r1-prefixes)
               (region-after w 0))]
    [r1 (region-after w r1)]))

(defn- short-syllable?
  "Snowball's shortv, tested at the end of w."
  [w]
  (let [n (count w)
        v-at (fn [i] (vowel? (nth w i)))]
    (boolean
     (or (and (>= n 3) (not (v-at (- n 3))) (v-at (- n 2)) (not (v-at (dec n)))
              (not (contains? #{\w \x \Y} (nth w (dec n)))))
         (and (= n 2) (v-at 0) (not (v-at 1)))
         (str/ends-with? w "past")))))

(defn- longest [w suffixes]
  (->> suffixes (filter #(str/ends-with? w %)) (sort-by count >) first))

(defn- drop-suffix [w s] (subs w 0 (- (count w) (count s))))

(defn- has-vowel? [s] (boolean (some vowel? s)))

(defn- step-0 [w]
  (if-let [s (longest w ["'s'" "'s" "'"])] (drop-suffix w s) w))

(defn- step-1a [w]
  (if-let [s (longest w ["sses" "ied" "ies" "us" "ss" "s"])]
    (case s
      "sses" (str (drop-suffix w s) "ss")
      ("ied" "ies") (let [stem (drop-suffix w s)]
                      (str stem (if (> (count stem) 1) "i" "ie")))
      ("us" "ss") w
      "s" (if (has-vowel? (subs w 0 (max 0 (- (count w) 2)))) (drop-suffix w s) w))
    w))

(def ^:private doubles #{"bb" "dd" "ff" "gg" "mm" "nn" "pp" "rr" "tt"})

(defn- ing-exception
  "What to do with -ing before the general rule: [:done word] when settled,
  nil to go on."
  [w stem]
  (let [at-limit (fn [prefix] (= stem prefix))
        m (longest stem ["y" "inn" "out" "cann" "herr" "earr" "even"])]
    (case m
      "y" (when (and (= (count stem) 2) (not (vowel? (first stem))))
            [:done (str (subs stem 0 1) "ie")])
      ("inn" "out" "cann" "herr" "earr" "even") (when (at-limit m) [:done w])
      nil)))

(defn- step-1b [w r1]
  (if-let [s (longest w ["eedly" "eed" "ingly" "edly" "ing" "ed"])]
    (let [stem (drop-suffix w s)]
      (if (#{"eed" "eedly"} s)
        (cond (< (count stem) r1) w
              (#{"proc" "exc" "succ"} stem) w
              :else (str stem "ee"))
        (if-let [[_ done] (when (= s "ing") (ing-exception w stem))]
          done
          (if-not (has-vowel? stem)
            w
            (let [n (count stem)
                  tail2 (when (>= n 2) (subs stem (- n 2)))]
              (cond (some #(str/ends-with? stem %) ["at" "bl" "iz"]) (str stem "e")
                    (doubles tail2) (if (and (= n 3) (contains? #{\a \e \o} (first stem)))
                                      stem
                                      (subs stem 0 (dec n)))
                    (and (= n r1) (short-syllable? stem)) (str stem "e")
                    :else stem))))))
    w))

(defn- step-1c [w]
  (let [n (count w)]
    (if (and (> n 2) (contains? #{\y \Y} (nth w (dec n))) (not (vowel? (nth w (- n 2)))))
      (str (subs w 0 (dec n)) "i")
      w)))

(def ^:private step-2-rules
  {"tional" "tion" "enci" "ence" "anci" "ance" "abli" "able" "entli" "ent"
   "izer" "ize" "ization" "ize" "ational" "ate" "ation" "ate" "ator" "ate"
   "alism" "al" "aliti" "al" "alli" "al" "fulness" "ful" "ousli" "ous"
   "ousness" "ous" "iveness" "ive" "iviti" "ive" "biliti" "ble" "bli" "ble"
   "ogist" "og" "ogi" "og" "fulli" "ful" "lessli" "less" "li" ""})

(defn- step-2 [w r1]
  (if-let [s (longest w (keys step-2-rules))]
    (let [stem (drop-suffix w s)]
      (cond (< (count stem) r1) w
            (= s "ogi") (if (str/ends-with? stem "l") (str stem "og") w)
            (= s "li") (if (and (seq stem) (contains? #{\c \d \e \g \h \k \m \n \r \t} (last stem))) stem w)
            :else (str stem (step-2-rules s))))
    w))

(def ^:private step-3-rules
  {"tional" "tion" "ational" "ate" "alize" "al" "icate" "ic" "iciti" "ic"
   "ical" "ic" "ful" "" "ness" "" "ative" ""})

(defn- step-3 [w r1 r2]
  (if-let [s (longest w (keys step-3-rules))]
    (let [stem (drop-suffix w s)]
      (cond (< (count stem) r1) w
            (= s "ative") (if (>= (count stem) r2) stem w)
            :else (str stem (step-3-rules s))))
    w))

(defn- step-4 [w r2]
  (if-let [s (longest w ["al" "ance" "ence" "er" "ic" "able" "ible" "ant" "ement" "ment"
                         "ent" "ism" "ate" "iti" "ous" "ive" "ize" "ion"])]
    (let [stem (drop-suffix w s)]
      (cond (< (count stem) r2) w
            (= s "ion") (if (and (seq stem) (contains? #{\s \t} (last stem))) stem w)
            :else stem))
    w))

(defn- step-5 [w r1 r2]
  (let [stem (subs w 0 (max 0 (dec (count w))))]
    (cond (str/ends-with? w "e")
          (if (or (>= (count stem) r2)
                  (and (>= (count stem) r1) (not (short-syllable? stem))))
            stem
            w)
          (and (str/ends-with? w "ll") (>= (count stem) r2)) stem
          :else w)))

(defn stem
  "The Porter2 stem of a lowercase word."
  [word]
  (cond
    (or (<= (count word) 2) (not (re-matches #"[a-z']+" word))) word
    (contains? exceptions word) (exceptions word)
    :else
    (let [w (mark-y (if (str/starts-with? word "'") (subs word 1) word))
          [r1 r2] (regions w)
          w (-> w step-0 step-1a (step-1b r1) step-1c (step-2 r1) (step-3 r1 r2)
                (step-4 r2) (step-5 r1 r2))]
      (str/replace w "Y" "y"))))
