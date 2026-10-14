(ns relay.schedule-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.schedule :as s])
  (:import (java.time Duration Instant LocalDateTime LocalTime ZoneId)))

(def zones
  ;; Lord Howe moves its clocks by half an hour, which catches arithmetic
  ;; that assumes daylight saving shifts are whole hours.
  (mapv #(ZoneId/of %) ["UTC" "America/Toronto" "Europe/London" "Asia/Kolkata"
                        "Australia/Lord_Howe" "America/St_Johns"]))

(def gen-zone (gen/elements zones))

(def gen-instant
  ;; 2020 to 2035, at whole seconds.
  (gen/fmap #(Instant/ofEpochSecond %) (gen/choose 1577836800 2051222400)))

(def gen-time (gen/fmap (fn [[h m]] (LocalTime/of h m))
                        (gen/tuple (gen/choose 0 23) (gen/choose 0 59))))

(def gen-schedule
  (gen/one-of
   [(gen/fmap (fn [[n unit]] {:kind :every :duration (case unit
                                                       :m (Duration/ofMinutes n)
                                                       :h (Duration/ofHours n)
                                                       :d (Duration/ofDays n))})
              (gen/tuple (gen/choose 1 500) (gen/elements [:m :h :d])))
    (gen/fmap (fn [t] {:kind :daily :time t}) gen-time)
    (gen/fmap (fn [[d t]] {:kind :weekly :day (s/days d) :time t})
              (gen/tuple (gen/elements (keys s/days)) gen-time))
    (gen/fmap (fn [[i t]] {:kind :once :at (LocalDateTime/of (.toLocalDate (.atZone ^Instant i (ZoneId/of "UTC"))) t)})
              (gen/tuple gen-instant gen-time))]))

(defn- canonical [sched]
  ;; Durations compare by value, but render picks the largest whole unit, so
  ;; compare through the minutes.
  (update sched :duration #(some-> ^Duration % .toMinutes)))

(deftest parsing
  (is (= {:kind :daily :time (LocalTime/of 7 30)} (s/parse "daily 07:30")))
  (is (= {:kind :daily :time (LocalTime/of 7 30)} (s/parse "  Daily 7:30 ")))
  (is (= :weekly (:kind (s/parse "weekly mon 09:00"))))
  (is (= :once (:kind (s/parse "once 2026-10-08 09:00"))))
  (testing "out-of-range values are not understood rather than thrown"
    (is (nil? (s/parse "daily 25:00")))
    (is (nil? (s/parse "daily 07:60")))
    (is (nil? (s/parse "once 2026-02-30 09:00")))
    (is (nil? (s/parse "every 0m"))))
  (is (nil? (s/parse nil)))
  (is (nil? (s/parse "whenever"))))

(defspec parse-never-throws 500
  (prop/for-all [s (gen/one-of [gen/string
                                (gen/fmap #(apply str %)
                                          (gen/vector (gen/elements ["every " "daily " "weekly " "once "
                                                                     "mon " "1" "2" "9" ":" "-" "m" "h" "d" " "])
                                                      0 12))])]
    (let [r (s/parse s)] (or (nil? r) (map? r)))))

(defspec render-then-parse-round-trips 300
  (prop/for-all [sched gen-schedule]
    (= (canonical sched) (canonical (s/parse (s/render sched))))))

(defspec next-due-is-strictly-after-the-baseline 1000
  (prop/for-all [sched gen-schedule base gen-instant zone gen-zone]
    (let [due (s/next-due sched base zone)]
      (or (nil? due) (.isAfter due base)))))

(defspec only-a-once-in-the-past-has-no-next-due 500
  (prop/for-all [sched gen-schedule base gen-instant zone gen-zone]
    (let [due (s/next-due sched base zone)]
      (= (nil? due)
         (and (= :once (:kind sched))
              (not (.isAfter (.toInstant (.atZone ^LocalDateTime (:at sched) zone)) base)))))))

(defspec every-is-the-baseline-plus-the-interval 300
  (prop/for-all [sched (gen/such-that #(= :every (:kind %)) gen-schedule 100)
                 base gen-instant zone gen-zone]
    (= (.plus base ^Duration (:duration sched)) (s/next-due sched base zone))))

(defn- wall-time [^Instant i ^ZoneId zone] (.toLocalTime (.atZone i zone)))

(defn- in-gap?
  "True when the wall time does not exist on that date in that zone."
  [^Instant due ^LocalTime t ^ZoneId zone]
  (let [date (.toLocalDate (.atZone due zone))]
    (empty? (.getValidOffsets (.getRules zone) (LocalDateTime/of date t)))))

(defspec wall-clock-schedules-land-on-their-time-and-day 1000
  (prop/for-all [sched (gen/such-that #(#{:daily :weekly} (:kind %)) gen-schedule 100)
                 base gen-instant zone gen-zone]
    (let [due (s/next-due sched base zone)
          {:keys [time day kind]} sched
          gap-days (/ (.toMinutes (Duration/between base due)) 1440.0)]
      (and (or (= time (wall-time due zone))
               ;; A time that does not exist that day is moved forward.
               (in-gap? due time zone))
           (if (= :weekly kind)
             (and (= day (.getDayOfWeek (.atZone due zone))) (<= gap-days 7.05))
             (<= gap-days 1.05))))))

(defspec no-occurrence-is-skipped 500
  ;; Nothing the schedule calls for lies strictly between the baseline and
  ;; next-due: stepping the baseline forward a minute at a time from just
  ;; before next-due never finds an earlier one.
  (prop/for-all [sched (gen/such-that #(#{:daily :weekly :every} (:kind %)) gen-schedule 100)
                 base gen-instant zone gen-zone
                 frac (gen/choose 0 999)]
    (let [due (s/next-due sched base zone)
          span (.toSeconds (Duration/between base due))
          probe (.plusSeconds base (quot (* span frac) 1000))]
      ;; From any point in (base, due) the next occurrence is still `due`,
      ;; unless the probe is the due moment itself.
      (or (not (.isBefore probe due))
          (= due (s/next-due sched probe zone))
          ;; `every` measures from the baseline, so it moves with it.
          (= :every (:kind sched))))))

(defspec due-agrees-with-next-due 500
  (prop/for-all [sched gen-schedule base gen-instant zone gen-zone
                 later (gen/choose 0 (* 60 60 24 30))]
    (let [now (.plusSeconds base later)
          due (s/next-due sched base zone)]
      (= (s/due? sched base now zone)
         (boolean (and due (not (.isAfter due now))))))))

(defspec firing-resets-the-clock 500
  ;; Once it fires (the baseline moves to now), it is not due again at now.
  (prop/for-all [sched gen-schedule base gen-instant zone gen-zone
                 later (gen/choose 0 (* 60 60 24 30))]
    (let [now (.plusSeconds base later)]
      (not (s/due? sched now now zone)))))

(deftest a-missed-run-is-due-once
  (let [zone (ZoneId/of "America/Toronto")
        sched (s/parse "daily 07:30")
        base (Instant/parse "2026-10-01T12:00:00Z")
        week-later (Instant/parse "2026-10-08T12:00:00Z")]
    (is (s/due? sched base week-later zone))
    (is (not (s/due? sched week-later week-later zone)))))

(deftest daylight-saving
  (let [zone (ZoneId/of "America/Toronto")]
    (testing "a daily time inside the spring-forward gap fires once, an hour later"
      (let [due (s/next-due (s/parse "daily 02:30") (Instant/parse "2026-03-08T05:00:00Z") zone)]
        (is (= (Instant/parse "2026-03-08T07:30:00Z") due))))
    (testing "a daily time in the repeated hour in autumn fires once"
      (let [sched (s/parse "daily 01:30")
            first-due (s/next-due sched (Instant/parse "2026-11-01T04:00:00Z") zone)
            second-due (s/next-due sched first-due zone)]
        (is (= (Instant/parse "2026-11-01T05:30:00Z") first-due))
        (is (= (Instant/parse "2026-11-02T06:30:00Z") second-due))))))
