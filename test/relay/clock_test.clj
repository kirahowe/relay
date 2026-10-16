(ns relay.clock-test
  "The clock tool: its arithmetic, checked against java.time across the
  daylight saving changes in America/Halifax, and how it is offered as a
  tool."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.clock :as clock]
            [relay.config :as config]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.tools :as tools])
  (:import (java.time Duration Instant LocalDate LocalDateTime LocalTime ZoneId ZoneOffset ZonedDateTime)
           (java.time.temporal ChronoUnit)))

(def halifax (ZoneId/of "America/Halifax"))
(def now (Instant/parse "2026-10-16T17:05:12Z")) ; Friday 14:05:12 in Halifax

(defn- answer [args] (clock/answer now halifax args))

(defn- problem [args]
  (try (answer args) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(def gen-date
  (gen/fmap #(LocalDate/ofEpochDay %) (gen/choose 18262 25567))) ; 2020 to 2039

(def gen-zdt
  ;; Mid-day times: a wall clock time inside a spring gap does not exist on
  ;; every day, and the tests of that live in the examples.
  (gen/fmap (fn [[d h m]] (ZonedDateTime/of (LocalDateTime/of ^LocalDate d (LocalTime/of h m)) halifax))
            (gen/tuple gen-date (gen/choose 5 22) (gen/choose 0 59))))

(def gen-seconds (gen/choose 0 59))

(def gen-when (gen/one-of [gen-date gen-zdt]))

;; --- examples

(deftest now-names-the-time-weekday-and-zone
  (is (= "2026-10-16T14:05:12-03:00 Friday (America/Halifax)" (answer {:op "now"})))
  (is (= "2026-10-16T17:05:12Z Friday (UTC)" (clock/answer now (ZoneId/of "UTC") {:op "now"})))
  (testing "the offset is the one in force on that day"
    (is (str/includes? (clock/answer (Instant/parse "2026-01-16T17:05:12Z") halifax {:op "now"}) "-04:00"))))

(deftest add-gives-the-result-and-its-weekday
  (is (= "2026-10-14 Wednesday plus 21 days is 2026-11-04 Wednesday" (answer {:op "add" :date "2026-10-14" :span "3w"})))
  (is (str/ends-with? (answer {:op "add" :date "2026-10-14" :span "-2d"}) "is 2026-10-12 Monday"))
  (is (str/ends-with? (answer {:op "add" :date "2026-01-31" :span "1mo"}) "is 2026-02-28 Saturday"))
  (is (str/ends-with? (answer {:op "add" :date "2026-10-14" :span "1y"}) "is 2027-10-14 Thursday"))
  (is (str/ends-with? (answer {:op "add" :date "2026-10-14 09:00" :span "90m"})
                      "is 2026-10-14T10:30:00-03:00 Wednesday (America/Halifax)"))
  (is (str/ends-with? (answer {:op "add" :date "2026-10-14 23:30" :span "1h"}) "is 2026-10-15T00:30:00-03:00 Thursday (America/Halifax)"))
  (testing "a span with several terms"
    (is (str/ends-with? (answer {:op "add" :date "2026-10-14" :span "1w2d"}) "is 2026-10-23 Friday")))
  (testing "no date means now"
    (is (str/ends-with? (answer {:op "add" :span "3w"}) "is 2026-11-06T14:05:12-04:00 Friday (America/Halifax)")))
  (testing "a date with an offset or zone keeps it"
    (is (str/ends-with? (answer {:op "add" :date "2026-10-14T12:00:00Z" :span "1d"}) "is 2026-10-15T12:00:00Z Thursday (Z)"))
    (is (str/ends-with? (answer {:op "add" :date "2026-03-07T12:00-04:00[America/Halifax]" :span "1d"})
                        "is 2026-03-08T12:00:00-03:00 Sunday (America/Halifax)"))))

(deftest between-gives-days-and-for-date-times-hours-and-minutes
  (is (= "From 2026-10-16 Friday to 2026-12-25 Friday: 70 days."
         (answer {:op "between" :from "2026-10-16" :to "2026-12-25"})))
  (is (= "From 2026-10-16 Friday to 2026-10-01 Thursday: -15 days (the second is earlier)."
         (answer {:op "between" :from "2026-10-16" :to "2026-10-01"})))
  (is (= "From 2026-10-16T09:00:00-03:00 Friday (America/Halifax) to 2026-10-17T11:30:00-03:00 Saturday (America/Halifax): 1 day, 2 hours, 30 minutes."
         (answer {:op "between" :from "2026-10-16 09:00" :to "2026-10-17 11:30"})))
  (testing "the same day is zero days"
    (is (str/includes? (answer {:op "between" :from "2026-10-16" :to "2026-10-16"}) ": 0 days.")))
  (testing "a clock change is explained"
    (is (str/includes? (answer {:op "between" :from "2026-03-07 12:00" :to "2026-03-08 12:00"})
                       "1 day, 0 hours, 0 minutes (23 hours, 0 minutes of clock time, because the clocks changed in between)")))
  (testing "now and today work as dates"
    (is (str/includes? (answer {:op "between" :from "today" :to "2026-10-20"}) ": 4 days."))))

(deftest next-gives-the-weekday-on-or-after-a-date
  (is (= "The first Monday on or after 2026-10-16 Friday is 2026-10-19 Monday" (answer {:op "next" :weekday "mon"})))
  (is (str/ends-with? (answer {:op "next" :weekday "Friday"}) "is 2026-10-16 Friday") "today counts")
  (is (str/ends-with? (answer {:op "next" :weekday "fri" :nth 2}) "is 2026-10-23 Friday"))
  (is (str/starts-with? (answer {:op "next" :weekday "fri" :nth 3}) "The 3rd Friday"))
  (is (str/ends-with? (answer {:op "next" :weekday "sun" :date "2026-10-14 09:00"})
                      "is 2026-10-18T09:00:00-03:00 Sunday (America/Halifax)")
      "a date-time keeps its time of day"))

(deftest bad-input-is-explained
  (testing "the explanations are fixed text that says which argument is wrong"
    (is (= "op must be one of add, between, next, now, in lower case." (problem {:op "later"})))
    (is (= "add needs span." (problem {:op "add" :date "2026-10-14"})))
    (is (= "between needs to." (problem {:op "between" :from "2026-10-14"})))
    (is (= "next needs weekday." (problem {:op "next"})))
    (is (= "now takes no arguments besides op." (problem {:op "now" :span "1d"})))
    (is (= "between takes only from, to besides op." (problem {:op "between" :date "2026-10-14" :from "a" :to "b"})))
    (is (str/starts-with? (problem {:op "add" :date "tomorrow" :span "1d"}) "date is not in a form I can read."))
    (is (str/starts-with? (problem {:op "add" :date "2026-02-30" :span "1d"}) "date is not in a form I can read."))
    (is (str/starts-with? (problem {:op "between" :from "2026-10-14" :to "soon"}) "to is not in a form I can read."))
    (is (str/starts-with? (problem {:op "add" :date "2026-10-14" :span "soon"}) "span is not in a form I can read."))
    (is (str/starts-with? (problem {:op "add" :date "2026-10-14" :span "1d!"}) "span is not in a form I can read."))
    (is (str/starts-with? (problem {:op "next" :weekday "funday"}) "weekday is not in a form I can read."))
    (is (= "nth must be a whole number from 1 to 10000." (problem {:op "next" :weekday "mon" :nth 0})))
    (is (str/includes? (problem {:op "add" :date "2026-10-14" :span "2h"}) "A date has no time of day"))
    (is (str/starts-with? (problem {:op "add" :date "+999999999-01-01" :span "1d"}) "date is not in a form I can read.")))
  (testing "a result outside the dates java.time can hold"
    (is (str/includes? (problem {:op "add" :date "2026-10-14" 
:span (apply str (repeat 2000 "999999y"))})
                       "outside the dates"))
    (is (str/includes? (problem {:op "add" :date "2026-10-14 09:00" :span (apply str (repeat 2000 "999999y"))})
                       "outside the dates")))
  (testing "op is exactly as the schema lists it"
    (doseq [op ["NOW" "Now" " now" "now " "" nil 1 :now]]
      (is (= "op must be one of add, between, next, now, in lower case." (problem {:op op})) (pr-str op)))))

(deftest nth-is-range-checked-before-it-reaches-java
  (testing "numbers past a long, which wrap when cast"
    (doseq [nth [18446744073709551617N 18446744073709551616N 9223372036854775808N -9223372036854775809N
                 4294967296 9223372036854775807 10001 0 -1 Long/MIN_VALUE]]
      (is (= "nth must be a whole number from 1 to 10000." (problem {:op "next" :weekday "mon" :nth nth})) (str nth))))
  (testing "the edges"
    (is (str/starts-with? (answer {:op "next" :weekday "mon" :nth 1}) "The first Monday"))
    (is (str/starts-with? (answer {:op "next" :weekday "mon" :nth 10000}) "The 10000th Monday"))
    (is (= "nth must be a whole number from 1 to 10000." (problem {:op "next" :weekday "mon" :nth 1.0})))
    (is (= "nth must be a whole number from 1 to 10000." (problem {:op "next" :weekday "mon" :nth "2"})))))

(defspec nth-is-answered-or-refused-never-wrapped 500
  (prop/for-all [nth (gen/one-of [gen/large-integer
                                  (gen/fmap (fn [[a b]] (+ (* (bigint a) 18446744073709551616N) b))
                                            (gen/tuple gen/large-integer gen/large-integer))
                                  (gen/choose -20000 20000)])
                 start gen-date]
    (let [text (try (clock/answer now halifax {:op "next" :weekday "mon" :date (str start) :nth nth})
                    (catch clojure.lang.ExceptionInfo e (ex-message e)))]
      (if (<= 1 nth 10000)
        (let [first-monday (clock/next-weekday start (clock/parse-weekday "mon") 1 halifax)]
          (str/ends-with? text (str "is " (clock/show (.plusDays ^LocalDate first-monday (* 7 (dec (long nth))))))))
        (= "nth must be a whole number from 1 to 10000." text)))))

;; --- properties

(defspec adding-days-then-measuring-gives-the-days-back 500
  (prop/for-all [start gen-when n (gen/choose -1500 1500)]
    (let [result (clock/add start (clock/parse-span (str n "d")))
          gap (clock/between start result halifax)]
      (and (= n (:days gap))
           (if (instance? LocalDate start)
             (nil? (:hours gap))
             (= [0 0 0] [(:hours gap) (:minutes gap) (:seconds gap)]))))))

(defspec adding-weeks-is-seven-days-each 300
  (prop/for-all [start gen-when n (gen/choose -300 300)]
    (= (clock/add start (clock/parse-span (str n "w")))
       (clock/add start (clock/parse-span (str (* 7 n) "d"))))))

(defspec adding-minutes-then-measuring-gives-the-minutes-back 500
  (prop/for-all [start gen-zdt n (gen/choose -100000 100000)]
    (let [result (clock/add start (clock/parse-span (str n "m")))]
      (= (* 60 n) (:elapsed (clock/between start result halifax))))))

(def gen-moment
  "A date-time in one of several zones, at any second, not only mid-day, so
  that gaps and overlaps and odd offsets all come up."
  (gen/fmap (fn [[d minute s zone]]
              (-> (.atStartOfDay ^LocalDate d zone) (.plusMinutes minute) (.plusSeconds s)))
            (gen/tuple gen-date (gen/choose 0 1439) gen-seconds
                       (gen/elements [halifax (ZoneId/of "UTC") (ZoneId/of "Asia/Kolkata")
                                      (ZoneId/of "Australia/Lord_Howe") (ZoneOffset/ofHours -3)]))))

(defspec between-leads-exactly-from-the-first-to-the-second 1000
  (prop/for-all [a gen-moment b gen-moment]
    (let [{:keys [days hours minutes seconds elapsed]} (clock/between a b halifax)
          gap [days hours minutes seconds]]
      (and
       ;; from plus the days (in from's own zone), then the clock time, is
       ;; to, to the second
       (= (.toInstant b)
          (.toInstant (-> a (.plusDays days)
                          (.plusHours hours) (.plusMinutes minutes) (.plusSeconds seconds))))
       (= elapsed (- (.getEpochSecond (.toInstant b)) (.getEpochSecond (.toInstant a))))
       ;; the pieces all lean the same way
       (or (every? #(>= % 0) gap) (every? #(<= % 0) gap))
       (< (abs minutes) 60) (< (abs seconds) 60)))))

(defspec the-second-is-earlier-by-the-moment-not-the-offset 500
  (prop/for-all [a gen-moment b gen-moment]
    (let [text (clock/answer now halifax {:op "between" :from (str (.toOffsetDateTime a)) :to (str (.toOffsetDateTime b))})]
      (= (.isBefore (.toInstant b) (.toInstant a))
         (str/includes? text "(the second is earlier)")))))

(deftest the-same-moment-in-two-offsets-is-no-gap
  (let [text (answer {:op "between" :from "2026-10-16T12:00:00-03:00" :to "2026-10-16T15:00:00Z"})]
    (is (str/ends-with? text ": 0 days, 0 hours, 0 minutes."))
    (is (not (str/includes? text "earlier"))))
  (testing "and 15:00Z is earlier than 11:59:59 in -03:00 only when it is"
    (is (str/includes? (answer {:op "between" :from "2026-10-16T12:00:01-03:00" :to "2026-10-16T15:00:00Z"}) "earlier"))
    (is (not (str/includes? (answer {:op "between" :from "2026-10-16T11:59:59-03:00" :to "2026-10-16T15:00:00Z"}) "earlier")))))

(deftest between-counts-seconds
  (is (str/ends-with? (answer {:op "between" :from "2026-10-16 09:00:30" :to "2026-10-17 11:30:15"})
                      ": 1 day, 2 hours, 29 minutes, 45 seconds."))
  (is (str/ends-with? (answer {:op "between" :from "2026-10-16 09:00:30" :to "2026-10-16 09:00:00"})
                      ": 0 days, 0 hours, 0 minutes, -30 seconds (the second is earlier)."))
  (testing "fractions of a second are dropped when a date is read"
    (is (str/ends-with? (answer {:op "between" :from "2026-10-16T09:00:00.9-03:00" :to "2026-10-16T09:00:01.1-03:00"})
                        ": 0 days, 0 hours, 0 minutes, 1 second."))))

(def weekday-names ["mon" "tue" "wed" "thu" "fri" "sat" "sun"])

(defspec next-weekday-is-that-day-on-or-after-the-start 500
  (prop/for-all [start gen-when name (gen/elements weekday-names)]
    (let [day (clock/parse-weekday name)
          result (clock/next-weekday start day 1 halifax)
          local #(if (instance? LocalDate %) % (.toLocalDate ^ZonedDateTime %))
          gap (.until (local start) (local result) ChronoUnit/DAYS)]
      (and (= day (.getDayOfWeek (local result)))
           (<= 0 gap 6)
           (or (instance? LocalDate start)
               (not (.isBefore (.toInstant ^ZonedDateTime result) (.toInstant ^ZonedDateTime start))))))))

(defspec the-nth-weekday-is-a-week-apart-each-time 300
  (prop/for-all [start gen-when name (gen/elements weekday-names) n (gen/choose 1 60)]
    (let [day (clock/parse-weekday name)
          local #(if (instance? LocalDate %) % (.toLocalDate ^ZonedDateTime %))]
      (= (* 7 (dec n))
         (.until (local (clock/next-weekday start day 1 halifax)) (local (clock/next-weekday start day n halifax)) ChronoUnit/DAYS)))))

(defspec spans-read-back-what-was-written 300
  (prop/for-all [terms (gen/vector (gen/tuple (gen/choose -999 999)
                                              (gen/elements ["y" "mo" "w" "d" "h" "m"]))
                                   1 5)]
    (let [span (clock/parse-span (apply str (for [[n u] terms] (str (if (neg? n) n (str "+" n)) u))))
          sum #(reduce + (for [[n u] terms :when (% u)] (* n (% u))))]
      (= span {:months (sum {"y" 12 "mo" 1}) :days (sum {"w" 7 "d" 1}) :minutes (sum {"h" 60 "m" 1})}))))

(defspec any-text-is-read-or-explained 500
  (prop/for-all [s gen/string-ascii]
    (every? #(try (% s) true (catch clojure.lang.ExceptionInfo _ true))
            [#(clock/parse-when % now halifax) clock/parse-span clock/parse-weekday])))

;; Errors from a tool carry no labels, so nothing a caller sent may come
;; back in one. Each value here holds a random token that no fixed message
;; contains.

(def gen-token
  (gen/fmap #(apply str %) (gen/vector (gen/elements "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789") 10)))

(defn- tainted [token]
  (gen/elements [token (str token "d") (str "1d" token) (str "2026-10-14" token) (str "2026-10-14 " token)
                 (str "2026-10-14T09:00:00" token) (str "2026-10-14T09:00:00-03:00[" token "]")
                 (str token "[America/Halifax]") (str " " token " ")]))

(def gen-call
  (gen/let [token gen-token
            op (gen/one-of [(gen/elements ["now" "add" "between" "next"]) (tainted token)])
            fields (gen/fmap (partial into {})
                             (gen/vector (gen/tuple (gen/elements [:date :span :from :to :weekday :nth :op2])
                                                    (gen/one-of [(tainted token) (gen/return "2026-10-14")
                                                                 (gen/return "3w") (gen/return "mon")
                                                                 (gen/return 2) (gen/return 18446744073709551617N)]))
                                         0 5))
            extra (gen/vector (gen/fmap #(keyword (str token %)) (gen/elements ["" "x"])) 0 1)]
    (gen/return {:token token
                 :args (merge (dissoc fields :op2) {:op op} (zipmap extra (repeat "x")))})))

(defspec nothing-the-caller-sent-comes-back-in-an-answer-or-an-error 2000
  (prop/for-all [{:keys [token args]} gen-call]
    (let [text (try (clock/answer now halifax args) (catch clojure.lang.ExceptionInfo e (ex-message e)))]
      (and (string? text)
           (not-any? #(str/includes? text %) (map #(apply str %) (partition 6 1 token)))))))

(deftest a-span-is-shown-as-parsed-not-as-sent
  (is (= "2026-10-14 Wednesday plus 21 days is 2026-11-04 Wednesday"
         (answer {:op "add" :date "2026-10-14" :span " 3 W "})))
  (is (= "2026-10-14T10:00:00-03:00 Wednesday (America/Halifax) plus 1 month, 5 days, -90 minutes is 2026-11-19T08:30:00-04:00 Thursday (America/Halifax)"
         (try (answer {:op "add" :date "2026-10-14 10:00" :span "1mo+5d-90m"})
              (catch clojure.lang.ExceptionInfo e (ex-message e))))))

;; --- the same days as java.time, across the changes in Halifax

(def changes
  "Local midnight of the days the clocks changed."
  [(LocalDate/of 2026 3 8) (LocalDate/of 2026 11 1)])

(def gen-near-a-change
  ;; Instant arithmetic from midnight, so the hour that happens twice on
  ;; 2026-11-01 comes up with both of its offsets.
  (gen/fmap (fn [[change days minute s]]
              (-> (.atStartOfDay ^LocalDate change halifax) (.plusDays days) (.plusMinutes minute) (.plusSeconds s)))
            (gen/tuple (gen/elements changes) (gen/choose -3 3) (gen/choose 0 1439) gen-seconds)))

(defspec java-time-agrees-near-the-clock-changes 2000
  (prop/for-all [start gen-near-a-change
                 days (gen/choose -4 4)
                 minutes (gen/choose -3000 3000)]
    (let [by-days (clock/add start (clock/parse-span (str days "d")))
          by-minutes (clock/add start (clock/parse-span (str minutes "m")))
          gap (clock/between start by-minutes halifax)]
      (and
       ;; days move the calendar, and the result is the whole date-time
       ;; java.time gives, offset included
       (= by-days (.plusDays start days))
       (= (.toLocalDate (.toLocalDateTime by-days)) (.plusDays (.toLocalDate (.toLocalDateTime start)) days))
       ;; minutes move the clock: exactly that much time passes
       (= by-minutes (.plusMinutes start minutes))
       (= (.plus (.toInstant start) (Duration/ofMinutes minutes)) (.toInstant by-minutes))
       (= (* 60 minutes) (:elapsed gap))
       ;; the pieces of the gap lead from start to the end
       (= by-minutes (-> start (.plusDays (:days gap))
                         (.plusHours (:hours gap)) (.plusMinutes (:minutes gap)) (.plusSeconds (:seconds gap))))))))

(deftest the-days-the-clocks-changed
  (testing "spring: 2026-03-08 has 23 hours"
    (let [start (ZonedDateTime/of 2026 3 7 12 0 0 0 halifax)]
      (is (= (ZonedDateTime/of 2026 3 8 12 0 0 0 halifax) (clock/add start (clock/parse-span "1d"))))
      (is (= (ZonedDateTime/of 2026 3 8 13 0 0 0 halifax) (clock/add start (clock/parse-span "24h"))))
      (is (= {:days 1 :hours 0 :minutes 0 :seconds 0 :elapsed 82800}
             (clock/between start (ZonedDateTime/of 2026 3 8 12 0 0 0 halifax) halifax)))))
  (testing "autumn: 2026-11-01 has 25 hours"
    (let [start (ZonedDateTime/of 2026 10 31 12 0 0 0 halifax)]
      (is (= (ZonedDateTime/of 2026 11 1 12 0 0 0 halifax) (clock/add start (clock/parse-span "1d"))))
      (is (= {:days 1 :hours 0 :minutes 0 :seconds 0 :elapsed 90000}
             (clock/between start (ZonedDateTime/of 2026 11 1 12 0 0 0 halifax) halifax)))
      (is (= (ZonedDateTime/of 2026 11 1 11 0 0 0 halifax) (clock/add start (clock/parse-span "24h"))))))
  (testing "a time that does not exist on the day moves forward, as java.time does"
    (is (= (ZonedDateTime/of 2026 3 8 3 30 0 0 halifax)
           (clock/add (ZonedDateTime/of 2026 3 7 2 30 0 0 halifax) (clock/parse-span "1d"))))))

(deftest a-gap-in-the-day-does-not-split-the-pieces
  ;; 02:30 does not exist on 2026-03-08, so a day on from 03-07 02:30 is
  ;; 03:30. Counting to 03:00 is then not a day and minus half an hour.
  (is (str/ends-with? (answer {:op "between" :from "2026-03-07 02:30" :to "2026-03-08 03:00"})
                      ": 0 days, 23 hours, 30 minutes."))
  (is (str/ends-with? (answer {:op "between" :from "2026-03-08 03:00" :to "2026-03-07 02:30"})
                      ": -1 day, 0 hours, -30 minutes (-23 hours, -30 minutes of clock time, because the clocks changed in between) (the second is earlier)."))
  (testing "an overlap makes a day of 25 hours, which is hours rather than a second day"
    (is (str/ends-with? (answer {:op "between" :from "2026-11-01 00:30" :to "2026-11-02 00:15"})
                        ": 0 days, 24 hours, 45 minutes."))))

(deftest a-zone-that-skips-a-date
  ;; Pacific/Apia went from 2011-12-29 to 2011-12-31.
  (let [apia (ZoneId/of "Pacific/Apia")
        next-in (fn [args] (try (clock/answer now apia args) (catch clojure.lang.ExceptionInfo e (ex-message e))))
        thursday "2011-12-29T12:00-10:00[Pacific/Apia]"]
    (testing "a Friday that does not exist is not counted, so the first Friday is the next one that happens"
      (is (str/ends-with? (next-in {:op "next" :weekday "fri" :date thursday}) "is 2012-01-06T12:00:00+14:00 Friday (Pacific/Apia)"))
      (is (str/ends-with? (next-in {:op "next" :weekday "fri" :nth 2 :date thursday}) "is 2012-01-13T12:00:00+14:00 Friday (Pacific/Apia)")))
    (testing "the days around it still work"
      (is (str/ends-with? (next-in {:op "next" :weekday "sat" :date thursday}) "is 2011-12-31T12:00:00+14:00 Saturday (Pacific/Apia)")))
    (testing "a plain date is a date in the operator's zone, so the skipped one is not counted either"
      (is (str/ends-with? (next-in {:op "next" :weekday "fri" :date "2011-12-29"}) "is 2012-01-06 Friday"))
      (is (str/ends-with? (next-in {:op "next" :weekday "fri" :nth 2 :date "2011-12-29"}) "is 2012-01-13 Friday"))
      (is (str/ends-with? (next-in {:op "next" :weekday "sat" :date "2011-12-29"}) "is 2011-12-31 Saturday")))
    (testing "in a zone with no skipped date it is plain arithmetic"
      (is (str/ends-with? (answer {:op "next" :weekday "fri" :date "2011-12-29"}) "is 2011-12-30 Friday")))))

(deftest a-time-of-day-that-does-not-exist
  (testing "it moves forward, as an addition does"
    (is (str/ends-with? (answer {:op "next" :weekday "sun" :date "2026-03-07 02:30"})
                        "is 2026-03-08T03:30:00-03:00 Sunday (America/Halifax)")))
  (testing "but not onto another weekday: Nuuk skipped 23:30 on 2024-03-30, which would be Sunday"
    (is (= "That time of day does not exist on the weekday's date in this time zone."
           (try (clock/answer now (ZoneId/of "America/Nuuk") {:op "next" :weekday "sat" :date "2024-03-29 23:30"})
                (catch clojure.lang.ExceptionInfo e (ex-message e)))))))

(defspec the-nth-weekday-counts-days-that-happen 500
  (prop/for-all [start (gen/fmap (fn [[d h]] (-> (.atStartOfDay ^LocalDate (LocalDate/of 2011 12 d) (ZoneId/of "Pacific/Apia"))
                                                  (.plusHours h)))
                                 (gen/tuple (gen/choose 20 31) (gen/choose 0 23)))
                 name (gen/elements weekday-names)
                 n (gen/choose 1 6)]
    ;; counted one day at a time, with no arithmetic in steps of seven
    (let [day (clock/parse-weekday name)
          happens? (fn [^LocalDate d] (= d (.toLocalDate (.atStartOfDay d (.getZone ^ZonedDateTime start)))))
          expected (->> (iterate #(.plusDays ^LocalDate % 1) (.toLocalDate ^ZonedDateTime start))
                        (filter #(and (= day (.getDayOfWeek ^LocalDate %)) (happens? %)))
                        (drop (dec n)) first)
          result (clock/next-weekday start day n halifax)]
      (= expected (.toLocalDate ^ZonedDateTime result)))))

(defspec next-weekday-is-that-weekday-or-refused-in-any-zone 1000
  (prop/for-all [moment (gen/one-of [gen-moment
                                     (gen/fmap (fn [[d h]] (-> (.atStartOfDay ^LocalDate (LocalDate/of 2011 12 d) (ZoneId/of "Pacific/Apia"))
                                                               (.plusHours h)))
                                               (gen/tuple (gen/choose 24 31) (gen/choose 0 23)))])
                 name (gen/elements weekday-names)
                 n (gen/choose 1 5)]
    (let [day (clock/parse-weekday name)
          result (try (clock/next-weekday moment day n halifax) (catch clojure.lang.ExceptionInfo _ ::refused))]
      (or (= ::refused result)
          (and (= day (.getDayOfWeek (.toLocalDate ^ZonedDateTime result)))
               (not (.isBefore (.toInstant ^ZonedDateTime result) (.toInstant moment))))))))

(deftest the-hour-that-happens-twice
  ;; 2026-11-01 01:30 is 01:30 ADT (-03:00) and then 01:30 AST (-04:00).
  (let [earlier "2026-11-01T01:30:00-03:00 Sunday (America/Halifax)"
        later "2026-11-01T01:30:00-04:00 Sunday (America/Halifax)"]
    (testing "a local time in the overlap is the earlier one, and an explicit offset picks the other"
      (is (str/starts-with? (answer {:op "add" :date "2026-11-01 01:30" :span "0d"}) (str earlier " plus 0 days is " earlier)))
      (is (str/starts-with? (answer {:op "add" :date "2026-11-01T01:30-04:00[America/Halifax]" :span "0d"})
                            (str later " plus 0 days is " later))))
    (testing "minutes cross from one to the other"
      (is (str/ends-with? (answer {:op "add" :date "2026-11-01 01:30" :span "60m"}) (str "is " later)))
      (is (str/ends-with? (answer {:op "add" :date "2026-11-01T01:30-04:00[America/Halifax]" :span "-60m"}) (str "is " earlier))))
    (testing "days keep the offset when the time still has it, and take the day's own when not"
      (is (str/ends-with? (answer {:op "add" :date "2026-10-31 01:30" :span "1d"}) (str "is " earlier)))
      (is (str/ends-with? (answer {:op "add" :date "2026-11-01T01:30-04:00[America/Halifax]" :span "1d"})
                          "is 2026-11-02T01:30:00-04:00 Monday (America/Halifax)"))
      (is (str/ends-with? (answer {:op "add" :date "2026-11-01 01:30" :span "1d"})
                          "is 2026-11-02T01:30:00-04:00 Monday (America/Halifax)")))
    (testing "the two are an hour apart"
      (is (str/ends-with? (answer {:op "between" :from "2026-11-01 01:30" :to "2026-11-01T01:30-04:00[America/Halifax]"})
                          ": 0 days, 1 hour, 0 minutes.")))
    (testing "a day, counted from the earlier time, and then the hour that repeats"
      (is (str/ends-with? (answer {:op "between" :from "2026-10-31 01:30" :to "2026-11-01T01:30-04:00[America/Halifax]"})
                          ": 1 day, 1 hour, 0 minutes.")))
    (testing "counted backwards, the days come from the later time, so the pieces differ but still land on the start"
      (is (str/ends-with? (answer {:op "between" :from "2026-11-01T01:30-04:00[America/Halifax]" :to "2026-10-31 01:30"})
                          ": -1 day, 0 hours, 0 minutes (-25 hours, 0 minutes of clock time, because the clocks changed in between) (the second is earlier).")))))

;; --- as a tool

(def cfg (config/resolve-config {:timezone "America/Halifax"
                                 :default-harness :agentloop
                                 :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"]}}
                                 :notify {:kind :none}}))

(defn- with-sys [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-clock-test"}))
        data (str dir "/data")]
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "America/Halifax" :default-harness :a
                   :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}
                   :notify {:kind :none}}))
    (let [sys (assoc (inbound/system data) :clock (constantly now))]
      (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title "C" :instructions "x"}})
      (try (f sys) (finally (fs/delete-tree dir))))))

(defn- call-json
  "A call as a harness sends it: the envelope's JSON text, parsed and run
  the way `relay tool` does."
  [sys input-json labels-json]
  (let [{:keys [input labels call-id problem]}
        (tools/parse-envelope (str "{\"input\":" input-json ",\"labels\":" labels-json ",\"call_id\":\"c1\"}"))]
    (or (when problem {:error problem})
        (tools/call! sys "clerk" "clock" input labels call-id))))

(deftest numbers-too-big-for-a-long-are-refused-through-the-envelope
  (with-sys
    (fn [sys]
      (doseq [n ["18446744073709551617" "18446744073709551616" "9223372036854775808" "-18446744073709551615" "10001"]]
        (is (= {:error "nth must be a whole number from 1 to 10000." :labels []}
               (call-json sys (str "{\"op\":\"next\",\"weekday\":\"mon\",\"nth\":" n "}") "[]"))
            n))
      (is (= {:ok "The 2nd Monday on or after 2026-10-16 Friday is 2026-10-26 Monday" :labels []}
             (call-json sys "{\"op\":\"next\",\"weekday\":\"mon\",\"nth\":2}" "[\"private\"]")))
      (testing "a span's digits are bounded by its grammar"
        (is (str/starts-with? (:error (call-json sys "{\"op\":\"add\",\"span\":\"18446744073709551617d\"}" "[]"))
                              "span is not in a form I can read."))))))

(deftest what-a-session-sent-never-comes-back-in-an-error
  (with-sys
    (fn [sys]
      (doseq [input ["{\"op\":\"add\",\"span\":\"secretXYZ123\"}"
                     "{\"op\":\"add\",\"span\":\"1d\",\"date\":\"secretXYZ123\"}"
                     "{\"op\":\"secretXYZ123\"}"
                     "{\"op\":\"next\",\"weekday\":\"secretXYZ123\"}"
                     "{\"op\":\"now\",\"secretXYZ123\":\"x\"}"
                     "{\"op\":\"now\",\"span\":\"secretXYZ123\"}"
                     "{\"op\":\"add\",\"span\":5,\"secretXYZ123\":true}"]
              :let [{:keys [error labels]} (call-json sys input "[\"private\",\"contaminated\"]")]]
        (is (string? error) input)
        (is (not (str/includes? error "secretXYZ")) input)
        (is (= [] labels) "an error carries no labels, so it holds nothing from the call")))))

(deftest the-tool-is-a-read-that-shows-nothing-relay-keeps
  (let [tool (get tools/builtin "clock")
        sys {:config cfg :clock (constantly now)}]
    (is (= :read (:class tool)))
    (is (empty? (:labels tool)) "no static labels")
    (testing "the policy sees a read with no labels"
      (is (= {:class "read"} (get-in (tools/policy (tools/manifest cfg "clerk") []) [:tools "clock"]))))
    (testing "a result carries no labels, whatever the session holds"
      (is (= {:ok "2026-10-16T14:05:12-03:00 Friday (America/Halifax)" :labels []}
             (tools/run-tool sys "clerk" tool {:op "now"} ["contaminated" "private"]))))
    (testing "a read does not need the log: the time comes from the context"
      (is (= "2026-10-16T14:05:12-03:00 Friday (America/Halifax)"
             ((:read tool) {:config cfg :now now} "clerk" {:op "now"}))))
    (testing "bad input comes back as an error without labels"
      (is (= {:error "add needs span." :labels []}
             (tools/run-tool sys "clerk" tool {:op "add"} ["private"]))))
    (testing "the schema checks types and names"
      (is (= "The input needs op." (tools/input-problem (:parameters tool) {})))
      (is (= "nth must be a integer." (tools/input-problem (:parameters tool) {:op "next" :weekday "mon" :nth "x"})))
      (is (= "The input has an argument this tool does not take. It takes date, from, nth, op, span, to, weekday."
             (tools/input-problem (:parameters tool) {:op "now" :when "x"}))))
    (testing "op is exactly as the schema lists it"
      (is (= ["now" "add" "between" "next"] (get-in tool [:parameters :properties :op :enum])))
      (is (= {:error "op must be one of add, between, next, now, in lower case." :labels []}
             (tools/run-tool sys "clerk" tool {:op "Now"} []))))))
