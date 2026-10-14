(ns relay.schedule
  "Routine schedules: parsing a schedule string and working out when it next
  comes due. Pure; every function takes the clock and the zone as arguments."
  (:require [clojure.string :as str])
  (:import (java.time DayOfWeek Duration Instant LocalDate LocalDateTime LocalTime
                      ZoneId ZonedDateTime)
           (java.time.temporal TemporalAdjusters)))

(def days
  {"mon" DayOfWeek/MONDAY "tue" DayOfWeek/TUESDAY "wed" DayOfWeek/WEDNESDAY
   "thu" DayOfWeek/THURSDAY "fri" DayOfWeek/FRIDAY "sat" DayOfWeek/SATURDAY
   "sun" DayOfWeek/SUNDAY})

(defn- time-of
  "A LocalTime from hour and minute strings, or nil when out of range."
  [h m]
  (let [h (parse-long h) m (parse-long m)]
    (when (and (<= 0 h 23) (<= 0 m 59))
      (LocalTime/of h m))))

(defn- date-of [s]
  (try (LocalDate/parse s) (catch java.time.format.DateTimeParseException _ nil)))

(defn parse
  "Turns a schedule string into a map, or nil when it is not understood. Never
  throws.

    every 30m | every 2h | every 1d
    daily 07:30
    weekly mon 09:00
    once 2026-10-08 09:00"
  [s]
  (let [s (str/lower-case (str/trim (str s)))]
    (or (when-let [[_ n unit] (re-matches #"every (\d{1,6})\s*(m|h|d)" s)]
          (let [n (parse-long n)]
            (when (pos? n)
              {:kind :every
               :duration (case unit
                           "m" (Duration/ofMinutes n)
                           "h" (Duration/ofHours n)
                           "d" (Duration/ofDays n))})))
        (when-let [[_ h m] (re-matches #"daily (\d{1,2}):(\d{2})" s)]
          (when-let [t (time-of h m)]
            {:kind :daily :time t}))
        (when-let [[_ d h m] (re-matches #"weekly (mon|tue|wed|thu|fri|sat|sun) (\d{1,2}):(\d{2})" s)]
          (when-let [t (time-of h m)]
            {:kind :weekly :day (days d) :time t}))
        (when-let [[_ date h m] (re-matches #"once (\d{4}-\d{2}-\d{2})[t ](\d{1,2}):(\d{2})" s)]
          (let [d (date-of date) t (time-of h m)]
            (when (and d t)
              {:kind :once :at (LocalDateTime/of d t)}))))))

(defn render
  "The canonical string for a parsed schedule; parse of it gives the same map."
  [{:keys [kind duration time day at]}]
  (let [hhmm #(format "%02d:%02d" (.getHour ^LocalTime %) (.getMinute ^LocalTime %))]
    (case kind
      :every (let [m (.toMinutes ^Duration duration)]
               (cond (zero? (mod m 1440)) (str "every " (quot m 1440) "d")
                     (zero? (mod m 60)) (str "every " (quot m 60) "h")
                     :else (str "every " m "m")))
      :daily (str "daily " (hhmm time))
      :weekly (str "weekly " (some (fn [[k v]] (when (= v day) k)) days) " " (hhmm time))
      :once (str "once " (.toLocalDate ^LocalDateTime at) " " (hhmm (.toLocalTime ^LocalDateTime at))))))

(defn- at-local ^Instant [^LocalDate date ^LocalTime time ^ZoneId zone]
  ;; A wall time that falls in a daylight-saving gap is moved forward by the
  ;; length of the gap, as ZonedDateTime/of does.
  (.toInstant (ZonedDateTime/of date time zone)))

(defn next-due
  "The first instant strictly after `baseline` that the schedule calls for, or
  nil when there is none. Wall-clock schedules are read in `zone`."
  ^Instant [{:keys [kind duration time day at]} ^Instant baseline ^ZoneId zone]
  (let [after? #(.isAfter ^Instant % baseline)
        start (.toLocalDate (.atZone baseline zone))]
    (case kind
      :every (.plus baseline ^Duration duration)
      ;; Looking at the baseline's own date and the next two covers a daylight
      ;; saving shift that pushes one day's occurrence past the baseline.
      :daily (->> (range 3)
                  (map #(at-local (.plusDays start %) time zone))
                  (filter after?)
                  first)
      :weekly (let [first-day (.with start (TemporalAdjusters/nextOrSame day))]
                (->> (range 3)
                     (map #(at-local (.plusWeeks first-day %) time zone))
                     (filter after?)
                     first))
      :once (let [instant (at-local (.toLocalDate ^LocalDateTime at)
                                    (.toLocalTime ^LocalDateTime at) zone)]
              (when (after? instant) instant)))))

(defn due?
  "True when the schedule called for a run after `baseline` and at or before
  `now`. A run missed while the machine slept is due once, not once per missed
  slot, because the baseline moves to the moment it fires."
  [schedule ^Instant baseline ^Instant now ^ZoneId zone]
  (boolean (when-let [due (next-due schedule baseline zone)]
             (not (.isAfter due now)))))

(defn one-off? [schedule] (= :once (:kind schedule)))
