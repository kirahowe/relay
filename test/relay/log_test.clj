(ns relay.log-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.log :as log])
  (:import (java.time Instant)))

(defn temp-data-dir []
  (log/init! (str (fs/create-temp-dir {:prefix "relay-log-test"}))))

(defmacro with-data-dir [[sym] & body]
  `(let [~sym (temp-data-dir)]
     (try ~@body (finally (fs/delete-tree ~sym)))))

(def gen-key (gen/fmap keyword (gen/such-that seq gen/string-alphanumeric 50)))

(def gen-scalar
  (gen/one-of [gen/string ; includes control characters and newlines
               (gen/fmap str/join (gen/vector (gen/elements ["é" "日本" "😀" "\n" "\"" "\\" " " "a"])))
               gen/large-integer
               (gen/double* {:NaN? false :infinite? false})
               gen/boolean
               (gen/return nil)]))

(def gen-json
  (gen/recursive-gen (fn [inner]
                       (gen/one-of [(gen/vector inner 0 4)
                                    (gen/map gen-key inner {:max-elements 4})]))
                     gen-scalar))

(def gen-draft
  (gen/hash-map :type (gen/elements ["memo/sent" "fact/added" "x"])
                :actor (gen/elements ["operator" "hub" "dispatcher"])
                :labels (gen/vector (gen/elements ["private" "contaminated"]) 0 3)
                :data (gen/map gen-key gen-json {:max-elements 4})))

(def gen-batches (gen/vector (gen/vector gen-draft 0 4) 1 8))

(defn- clock-from
  "A clock that replays these offsets in seconds from a fixed moment, so it
  can run backwards."
  [offsets]
  (let [ticks (atom (cycle offsets))
        epoch (Instant/parse "2026-10-31T23:59:58Z")]
    (fn [] (let [[t] @ticks] (swap! ticks rest) (.plusSeconds epoch t)))))

(defn- append-batches! [dir batches clock]
  (vec (mapcat (fn [batch] (log/transact! dir clock (constantly batch))) batches)))

(defspec what-is-appended-is-what-is-read 100
  (prop/for-all [batches gen-batches
                 offsets (gen/not-empty (gen/vector (gen/choose -100000 100000)))]
    (with-data-dir [dir]
      (let [written (append-batches! dir batches (clock-from offsets))
            read (log/read-events dir)
            expected-drafts (apply concat batches)]
        (and (= written read)
             (= (range 1 (inc (count read))) (map :seq read))
             (= (map :data expected-drafts) (map :data read))
             (= (map :type expected-drafts) (map :type read))
             (= (map #(vec (sort (distinct (:labels %)))) expected-drafts) (map :labels read))
             (testing "recording time never goes backwards, whatever the clock does"
               (apply <= 0 (map #(.toEpochMilli (Instant/parse (:at %))) read)))
             (testing "every event is in the segment for its recording month"
               (every? (fn [file]
                         (let [month (subs (str (fs/file-name file)) 0 7)]
                           (every? #(str/includes? % (str "\"at\":\"" month))
                                   (str/split-lines (slurp file)))))
                       (log/segments dir))))))))

(defspec a-partial-last-line-is-ignored-then-cut-off 100
  (prop/for-all [batches gen-batches
                 junk (gen/such-that #(and (seq %) (not (str/includes? % "\n")))
                                     (gen/fmap str/join (gen/vector (gen/elements ["{" "\"seq\":" "9" "é" "x" "}"]) 1 20))
                                     100)
                 more (gen/not-empty (gen/vector gen-draft 1 3))]
    (with-data-dir [dir]
      (let [clock (constantly (Instant/parse "2026-10-14T12:00:00Z"))
            written (append-batches! dir batches clock)]
        (if (empty? written)
          true
          (let [file (peek (log/segments dir))]
            (spit file junk :append true)
            (and (= written (log/read-events dir))
                 (let [appended (log/transact! dir clock (constantly more))]
                   (and (= (inc (count written)) (:seq (first appended)))
                        (= (concat written appended) (log/read-events dir)))))))))))

(deftest corruption-is-an-error-not-a-skip
  (with-data-dir [dir]
    (let [clock (constantly (Instant/parse "2026-10-14T12:00:00Z"))]
      (log/transact! dir clock (constantly (repeat 3 {:type "x" :actor "operator"})))
      (let [file (peek (log/segments dir))
            lines (str/split-lines (slurp file))]
        (testing "a line that is not JSON"
          (spit file (str (str/join "\n" (assoc lines 1 "not json")) "\n"))
          (is (thrown-with-msg? Exception #"corrupt" (log/read-events dir))))
        (testing "a gap in seq"
          (spit file (str (str/join "\n" [(lines 0) (lines 2)]) "\n"))
          (is (thrown-with-msg? Exception #"expected event 2" (log/read-events dir))))
        (testing "a well-formed line that is not an event"
          (spit file (str (str/join "\n" (assoc lines 1 "{\"seq\":2}")) "\n"))
          (is (thrown-with-msg? Exception #"well-formed" (log/read-events dir))))
        (testing "and a writer refuses to append to it"
          (is (thrown? Exception (log/transact! dir clock (constantly [{:type "x" :actor "a"}])))))))))

(deftest a-decision-that-throws-writes-nothing
  (with-data-dir [dir]
    (let [clock (constantly (Instant/parse "2026-10-14T12:00:00Z"))]
      (log/transact! dir clock (constantly [{:type "x" :actor "operator"}]))
      (is (thrown? Exception (log/transact! dir clock (fn [_] (throw (ex-info "no" {}))))))
      (is (thrown? Exception (log/transact! dir clock (constantly [{:type :not-a-string :actor "a"}]))))
      (is (= 1 (count (log/read-events dir)))))))

(deftest decide-sees-every-event-so-far
  (with-data-dir [dir]
    (let [clock (constantly (Instant/parse "2026-10-14T12:00:00Z"))]
      (dotimes [_ 3]
        (log/transact! dir clock (fn [events]
                                   [{:type "count" :actor "a" :data {:seen (count events)}}])))
      (is (= [0 1 2] (map (comp :seen :data) (log/read-events dir)))))))

(deftest an-uninitialized-directory-is-refused
  (let [dir (str (fs/create-temp-dir))]
    (try
      (is (thrown-with-msg? Exception #"relay init"
                            (log/transact! dir #(Instant/now) (constantly [{:type "x" :actor "a"}]))))
      (is (not (fs/exists? (log/log-dir dir))))
      (finally (fs/delete-tree dir)))))

(deftest threads-in-one-process-never-interleave
  (with-data-dir [dir]
    (let [clock #(Instant/now)
          workers (doall (for [w (range 8)]
                           (future (dotimes [i 15]
                                     (log/transact! dir clock
                                                    (constantly [{:type "w" :actor (str w) :data {:i i}}
                                                                 {:type "w2" :actor (str w) :data {:i i}}]))))))]
      (run! deref workers)
      (let [events (log/read-events dir)]
        (is (= 240 (count events)))
        (testing "each transaction's events are adjacent"
          (is (every? (fn [[a b]] (and (= "w" (:type a)) (= "w2" (:type b))
                                       (= (:actor a) (:actor b)) (= (:data a) (:data b))))
                      (partition 2 events))))
        (testing "each writer's events are in its own order"
          (is (every? (fn [[_ evs]] (= (range 15) (map (comp :i :data) (filter #(= "w" (:type %)) evs))))
                      (group-by :actor events))))))))

(deftest separate-processes-never-interleave
  (with-data-dir [dir]
    (let [script (str "(require '[relay.log :as log])"
                      "(let [[dir w] *command-line-args*]"
                      "  (dotimes [i 20]"
                      "    (log/transact! dir #(java.time.Instant/now)"
                      "      (fn [evs] [{:type \"p\" :actor w :data {:i i :seen (count evs)}}"
                      "                 {:type \"p2\" :actor w :data {:i i}}]))))")
          procs (doall (for [w (range 6)]
                         (p/process ["bb" "--classpath" (str (fs/absolutize "src")) "-e" script dir (str w)]
                                    {:err :string :out :string})))
          results (mapv deref procs)]
      (is (every? zero? (map :exit results)) (str/join "\n" (map :err results)))
      (let [events (log/read-events dir)]
        (is (= 240 (count events)))
        (is (every? (fn [[a b]] (and (= "p" (:type a)) (= "p2" (:type b)) (= (:actor a) (:actor b))))
                    (partition 2 events)))
        (testing "every writer saw all events before its own"
          (is (every? #(= (dec (:seq %)) (get-in % [:data :seen]))
                      (filter #(= "p" (:type %)) events))))))))
