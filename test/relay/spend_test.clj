(ns relay.spend-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.spend :as spend])
  (:import (java.time Instant LocalDate ZoneId)))

(def zones
  "Zones with clock changes at different hours, one that once skipped
  Monday midnight (Asia/Tehran, 2021-03-22), and one with none."
  (mapv #(ZoneId/of %) ["UTC" "America/Toronto" "Europe/London" "America/Santiago" "Australia/Lord_Howe"
                        "Asia/Tehran" "America/Havana"]))

(def start-2020 (.getEpochSecond (Instant/parse "2020-01-01T00:00:00Z")))
(def span (* 10 365 86400))

(def gen-now (gen/fmap #(Instant/ofEpochSecond (+ start-2020 %)) (gen/choose 0 span)))

(defn- gen-wakes
  "Wakes ending in the eight weeks around `now`, some priced, some not,
  some that never ran."
  [^Instant now]
  (gen/vector
   (gen/fmap (fn [[id role back nanos cost outcome]]
               {:id id :role role :outcome outcome
                :ended-at (.plusNanos (.minusSeconds now back) nanos)
                :cost-usd cost})
             (gen/tuple gen/nat (gen/elements ["hub" "clerk" "scout"])
                        (gen/choose (- (* 7 86400)) (* 49 86400)) (gen/choose 0 999999999)
                        (gen/one-of [(gen/return nil)
                                     (gen/fmap #(/ % 10000.0) (gen/choose 0 50000))])
                        (gen/elements ["ran" "ran" "limited"])))
   0 40))

(def gen-case
  (gen/bind (gen/tuple gen-now (gen/elements zones) (gen/choose 1 8))
            (fn [[now zone weeks]] (gen/fmap #(vector now zone weeks %) (gen-wakes now)))))

(defspec totals-are-the-sum-of-their-parts 300
  (prop/for-all [[now zone weeks wakes] gen-case]
    (every? (fn [{:keys [roles cost-usd unpriced]}]
              (and (= cost-usd (reduce + 0M (map :cost-usd (vals roles))))
                   (= unpriced (reduce + 0 (map :unpriced (vals roles))))))
            (spend/weekly wakes zone now weeks))))

(defspec every-priced-wake-lands-in-exactly-one-week 300
  (prop/for-all [[now zone weeks wakes] gen-case]
    (let [result (spend/weekly wakes zone now weeks)
          ;; The window, by each week's first instant, worked out apart
          ;; from how weekly finds a wake's week.
          from (spend/week-start (:week (peek result)) zone)
          until (spend/week-start (.plusWeeks ^LocalDate (:week (first result)) 1) zone)
          in-window (filter #(and (some? (:cost-usd %))
                                  (not (.isBefore ^Instant (:ended-at %) from))
                                  (.isBefore ^Instant (:ended-at %) until))
                            wakes)]
      (and (= (count in-window) (reduce + (for [w result r (vals (:roles w))] (:priced r))))
           (= (reduce + 0M (map #(bigdec (:cost-usd %)) in-window))
              (reduce + 0M (map :cost-usd result)))
           ;; and each in the week whose bounds hold it
           (every? (fn [{:keys [ended-at role]}]
                     (let [wk (spend/week-of ended-at zone)]
                       (and (not (.isBefore ^Instant ended-at (spend/week-start wk zone)))
                            (.isBefore ^Instant ended-at (spend/week-start (.plusWeeks wk 1) zone))
                            (some #(and (= wk (:week %)) (contains? (:roles %) role)) result))))
                   in-window)))))

(defspec unpriced-wakes-add-nothing-to-cost 300
  (prop/for-all [[now zone weeks wakes] gen-case]
    (let [priced (filter :cost-usd wakes)]
      (= (map :cost-usd (spend/weekly wakes zone now weeks))
         (map :cost-usd (spend/weekly priced zone now weeks))))))

(defspec monday-midnight-starts-the-new-week 300
  (prop/for-all [days (gen/choose 0 3650) zone (gen/elements zones)]
    (let [monday (.plusWeeks (LocalDate/of 2020 1 6) (long (quot days 7)))
          start (spend/week-start monday zone)]
      (and (= monday (spend/week-of start zone))
           (= (.minusWeeks monday 1) (spend/week-of (.minusNanos start 1) zone))))))

(deftest weeks-start-on-monday-in-the-operators-zone
  (let [toronto (ZoneId/of "America/Toronto")
        wake (fn [at cost] {:id 1 :role "hub" :outcome "ran" :ended-at (Instant/parse at) :cost-usd cost})]
    (testing "Monday 00:00 local is the new week; a nanosecond before is the old one"
      ;; 2026-10-19 is a Monday; Toronto is UTC-4 then.
      (let [now (Instant/parse "2026-10-20T12:00:00Z")
            [this last-week] (spend/weekly [(wake "2026-10-19T04:00:00Z" 1.0)
                                            (wake "2026-10-19T03:59:59.999999999Z" 2.0)]
                                           toronto now 2)]
        (is (= [(LocalDate/of 2026 10 19) 1.0M] ((juxt :week :cost-usd) this)))
        (is (= [(LocalDate/of 2026 10 12) 2.0M] ((juxt :week :cost-usd) last-week)))))
    (testing "the week Toronto falls back is 169 hours long"
      ;; Clocks go back on Sunday 2026-11-01.
      (is (= (* 169 3600)
             (- (.getEpochSecond (spend/week-start (LocalDate/of 2026 11 2) toronto))
                (.getEpochSecond (spend/week-start (LocalDate/of 2026 10 26) toronto)))))))
  (testing "a Monday whose midnight was skipped starts at its first moment"
    (let [tehran (ZoneId/of "Asia/Tehran")]
      (is (= (Instant/parse "2021-03-21T20:30:00Z") (spend/week-start (LocalDate/of 2021 3 22) tehran)))
      (is (= (LocalDate/of 2021 3 15) (spend/week-of (Instant/parse "2021-03-21T20:29:59Z") tehran))))))

(deftest a-week-counts-priced-and-unpriced-wakes-by-role
  (let [now (Instant/parse "2026-10-14T12:00:00Z")
        at (Instant/parse "2026-10-13T12:00:00Z")
        w (fn [role cost outcome] {:id 1 :role role :cost-usd cost :outcome outcome :ended-at at})]
    (is (= {:week (LocalDate/of 2026 10 12)
            :roles {"hub" {:cost-usd 0.75M :priced 2 :unpriced 1}
                    "clerk" {:cost-usd 0M :priced 0 :unpriced 1}}
            :cost-usd 0.75M :unpriced 2}
           (spend/this-week [(w "hub" 0.5 "ran") (w "hub" 0.25 "limited") (w "hub" nil "ran")
                             (w "clerk" nil "ran") (w "clerk" nil "limited")
                             {:id 9 :role "hub" :cost-usd 9.0 :outcome nil :ended-at nil}]
                            (ZoneId/of "UTC") now)))))

(deftest the-budget-threshold-reached
  (is (nil? (spend/threshold-reached 15 11.99 nil)))
  (is (= 80 (spend/threshold-reached 15 12 nil)))
  (is (nil? (spend/threshold-reached 15 14 80)) "80 already told")
  (is (= 100 (spend/threshold-reached 15 15 80)))
  (is (= 100 (spend/threshold-reached 15 20 nil)) "past both at once: only 100")
  (is (nil? (spend/threshold-reached 15 30 100))))
