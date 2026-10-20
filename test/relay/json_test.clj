(ns relay.json-test
  "relay reads JSON another process wrote by one strict rule, the same
  rule agentloop and acp-tui read it by (relay#58)."
  (:require [cheshire.core :as cheshire]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.acp :as acp]
            [relay.json :as json])
  (:import (java.util Base64)))

;; --- the shared table

(def cases
  "The table of lines relay, agentloop and acp-tui must read alike. Each
  repo keeps a byte-identical copy."
  (mapv json/parse (remove str/blank? (str/split-lines (slurp "testdata/strict-json/cases.jsonl")))))

(deftest every-line-in-the-shared-table-reads-as-the-table-says
  (is (< 30 (count cases)))
  (doseq [{n "name" b64 "b64" expect "expect"} cases
          :let [bs (.decode (Base64/getDecoder) ^String b64)
                msg (acp/message bs)]]
    (testing n
      (is (= expect (if msg {"method" (get msg "method")} "reject"))))))

;; --- round trips

(def gen-code-point
  "Any Unicode scalar value, astral ones often."
  (gen/one-of [(gen/choose 0x20 0x7e)
               (gen/choose 0 0x1f)
               (gen/choose 0x80 0xd7ff)
               (gen/choose 0xe000 0xffff)
               (gen/choose 0x10000 0x10ffff)]))

(def gen-text
  (gen/fmap (fn [cps] (let [sb (StringBuilder.)] (doseq [cp cps] (.appendCodePoint sb (int cp))) (str sb)))
            (gen/vector gen-code-point 0 12)))

(def gen-digits
  (gen/fmap #(apply str %) (gen/vector (gen/elements "0123456789") 1 120)))

(def gen-number
  (gen/one-of [gen/large-integer
               ;; Integers far past a long.
               (gen/fmap (fn [[neg ds]] (bigint (BigInteger. (str (when neg "-") ds)))) (gen/tuple gen/boolean gen-digits))
               ;; Decimals with more digits than a double holds, and
               ;; exponents far past a double's.
               (gen/fmap (fn [[neg ds scale]] (BigDecimal. (BigInteger. (str (when neg "-") ds)) (int scale)))
                         (gen/tuple gen/boolean gen-digits (gen/choose -500 500)))]))

(def gen-scalar (gen/one-of [gen-text gen-number gen/boolean (gen/return nil)]))

(def gen-value
  (gen/recursive-gen (fn [inner] (gen/one-of [(gen/vector inner 0 4) (gen/map gen-text inner {:max-elements 4})]))
                     gen-scalar))

(defn- same?
  "Equal, with an integer read back as the integer type relay reads."
  [a b]
  (cond
    (map? a) (and (map? b) (= (set (keys a)) (set (keys b))) (every? #(same? (get a %) (get b %)) (keys a)))
    (sequential? a) (and (sequential? b) (= (count a) (count b)) (every? true? (map same? a b)))
    (integer? a) (and (integer? b) (== a b))
    ;; A decimal with no fraction digits is written as an integer literal
    ;; (0M as 0) or with an exponent (0E+1M as 0E+1), and read back as an
    ;; integer or a decimal of the same value.
    (and (decimal? a) (<= (.scale ^BigDecimal a) 0)) (and (or (integer? b) (decimal? b)) (== a b))
    :else (= a b)))

(defspec any-value-reads-back-exactly 300
  (prop/for-all [v gen-value]
    (let [s (cheshire/generate-string v)]
      (and (same? v (json/parse s))
           (same? v (json/parse (.getBytes s "UTF-8")))
           ;; Escaped to ASCII, astral characters as surrogate pairs.
           (same? v (json/parse (cheshire/generate-string v {:escape-non-ascii true})))))))

(deftest numbers-keep-every-digit
  (is (= [123456789012345678901234567890N 0.1000000000000000055511151231257827M 1E+400M -0.0M]
         (json/parse "[123456789012345678901234567890, 0.1000000000000000055511151231257827, 1e400, -0.0]")))
  (is (= "[0.1000000000000000055511151231257827,1E+400]"
         (cheshire/generate-string (json/parse "[0.1000000000000000055511151231257827,1e400]"))))
  (testing "a figure only summed and shown may be a double"
    (is (= [1.5 1] (json/parse "[1.5, 1]" {:doubles? true})))))

;; --- what is never accepted

(def gen-object
  (gen/such-that seq (gen/map gen-text gen-value {:max-elements 4})))

(defn- with-duplicate
  "The JSON text of `obj`, nested `depth` levels down, with its first
  member written a second time with `v`."
  [obj v depth]
  (let [[k] (first obj)
        inner (str (subs (cheshire/generate-string obj) 0 (dec (count (cheshire/generate-string obj))))
                   "," (cheshire/generate-string k) ":" (cheshire/generate-string v) "}")]
    (reduce (fn [s i] (if (even? i) (str "{\"a\":" s "}") (str "[1," s "]"))) inner (range depth))))

(defspec a-duplicate-name-is-never-accepted 200
  (prop/for-all [obj gen-object v gen-value depth (gen/choose 0 5)]
    (= ::bad (json/parse-or (with-duplicate obj v depth) ::bad))))

(defspec a-second-value-is-never-accepted 200
  (prop/for-all [a gen-value b gen-value ws (gen/elements ["" " " "\n" "\t" "\r\n"])]
    ;; Two numbers with nothing between them are one number: 1 and 0 are 10.
    (or (and (= "" ws) (number? a) (number? b))
        (= ::bad (json/parse-or (str (cheshire/generate-string a) ws (cheshire/generate-string b)) ::bad)))))

;; The exponent is bounded by value, so leading zeros do not count
;; (relay#58).
(defspec an-exponent-passes-exactly-within-the-bound 300
  (prop/for-all [n (gen/choose (* -3 json/max-exponent) (* 3 json/max-exponent))
                 zeros (gen/choose 0 30)
                 plus gen/boolean
                 e (gen/elements ["e" "E"])]
    (let [lit (str "1" e (cond (neg? n) "-" plus "+" :else "") (apply str (repeat zeros "0")) (abs n))
          ok (map? (json/parse-or (str "{\"n\":" lit "}") nil))]
      (= ok (<= (abs n) json/max-exponent)))))

(deftest what-the-rule-refuses
  (doseq [[why s] [["nothing" ""] ["only whitespace" " \t"] ["a duplicate after unescaping" "{\"a\":1,\"\\u0061\":2}"]
                   ["a lone high surrogate" "[\"\\ud83d\"]"] ["a lone low surrogate" "[\"\\ude00\"]"]
                   ["a lone surrogate in a name" "{\"\\ud800\":1}"] ["pair halves reversed" "[\"\\ude00\\ud83d\"]"]
                   ["a raw control character" "[\"a\tb\"]"] ["a negative literal over 1000 characters" (str "[-" (apply str (repeat 1000 "1")) "]")]
                   ["a decimal literal over 1000 characters" (str "[1." (apply str (repeat 999 "1")) "]")]
                   ["an exponent past an int" "[1e99999999999]"] ["NaN" "[NaN]"] ["a vertical tab" "[1]\u000b"]]]
    (testing why (is (= ::bad (json/parse-or s ::bad))))))

(deftest a-lone-surrogate-in-a-keyword-namespace-is-refused
  ;; (keyword "\ud800/x") puts the surrogate in the namespace, where a
  ;; check of the name alone missed it.
  (doseq [s ["{\"\\ud800/x\":1}" "{\"type\":\"outcome\",\"\\ud800/x\":1}" "{\"a/\\udc00\":1}"]]
    (testing s
      (is (= ::bad (json/parse-or s ::bad {:keywords? true})))
      (is (= ::bad (json/parse-or s ::bad))))))

(deftest what-the-rule-takes
  (is (= ["😀" "a\u2028b"] (json/parse "[\"\\ud83d\\ude00\", \"a\u2028b\"]")))
  (is (= [(str (apply str (repeat 999 "1")) "1")] (json/parse (str "[\"" (apply str (repeat 1000 "1")) "\"]")))
      "a long run of digits in a string is no number")
  (is (= (bigint (apply str (repeat 1000 "9"))) (first (json/parse (str "[" (apply str (repeat 1000 "9")) "]")))))
  (is (= {:Method 1 :method 2} (json/parse "{\"Method\":1,\"method\":2}" {:keywords? true}))
      "names differing only in case are two names"))

(deftest bytes-that-are-not-utf-8-never-parse-but-can-be-searched
  (let [bs (byte-array (map unchecked-byte (concat (.getBytes "{\"id\":7,\"s\":\"" "UTF-8") [0xc0 0xaf] (.getBytes "\"}" "UTF-8"))))]
    (is (nil? (json/parse-or bs nil)))
    (is (str/includes? (json/text bs) "\"id\":7"))
    (is (= "{\"id\":7,\"s\":\"\uFFFD\uFFFD\"}" (json/shown (json/text bs))))))
