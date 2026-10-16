(ns relay.clock
  "Dates and times for a role, which should not do calendar arithmetic in
  its head: the time now, a date plus a span, the gap between two dates and
  the next given weekday. Pure: the time now and the operator's zone come
  in as arguments, and only the tool that calls this reads the clock.

  A date is a LocalDate (2026-10-14) or a ZonedDateTime. Text without a zone
  is read in the operator's zone; text with an offset or a zone keeps it."
  (:require [clojure.string :as str])
  (:import (java.time DateTimeException DayOfWeek Instant LocalDate LocalDateTime ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter TextStyle)
           (java.time.temporal ChronoUnit)
           (java.util Locale)))

(defn- fail
  "Throws a diagnostic. The message is fixed text: it says which argument is
  wrong and what form it should have, and never quotes what was sent. A
  tool's errors carry no labels, so a message that echoed the call would
  hand a session's text back unlabelled."
  [& parts]
  (throw (ex-info (apply str parts) {})))

;; --- reading

(defn parse-when
  "A date or date-time from its text: `now`, `today`, 2026-10-14, a local
  date and time (2026-10-14 09:00), or one with an offset or zone
  (2026-10-14T09:00:00-03:00, 2026-10-14T09:00:00Z,
  2026-10-14T09:00-03:00[America/Halifax]). A date-time is read to the
  second. Throws when it is none of those, naming `field` as the argument
  at fault."
  ([s now zone] (parse-when s now zone "date"))
  ([s ^Instant now ^ZoneId zone field]
   (let [s (str/trim (str s))
         bad #(fail field " is not in a form I can read. Use a date such as 2026-10-14, a date and time such as"
                    " 2026-10-14 09:00, an ISO date-time with an offset such as 2026-10-14T09:00:00-03:00,"
                    " or now or today.")
         seconds #(.truncatedTo ^ZonedDateTime % ChronoUnit/SECONDS)]
     (try
       (cond
         (= "now" (str/lower-case s)) (seconds (.atZone now zone))
         (= "today" (str/lower-case s)) (.toLocalDate (.atZone now zone))
         (re-matches #"\d{4}-\d\d-\d\d" s) (LocalDate/parse s)
         (re-matches #"\d{4}-\d\d-\d\d[T ]\d\d:\d\d(:\d\d)?" s)
         (ZonedDateTime/of (LocalDateTime/parse (str/replace-first s " " "T")) zone)
         :else (seconds (ZonedDateTime/parse (str/replace-first s " " "T"))))
       (catch DateTimeException _ (bad))))))

(defn parse-span
  "A span as {:months :days :minutes}, from text such as 3w, 2d, 90m, 1mo,
  1y, 2h or -3d. Several terms may follow one another (1w2d, 1h-15m). Weeks
  and years are days and months; months and days move the calendar, hours
  and minutes move the clock. Throws on anything else."
  [s]
  (let [text (str/lower-case (str/replace (str s) #"\s+" ""))
        terms (re-seq #"([+-]?)(\d{1,6})(mo|[ywdhm])" text)]
    (when (or (empty? terms) (not= text (apply str (map first terms))))
      (fail "span is not in a form I can read. Use a number and a unit: y, mo, w, d, h or m,"
            " such as 3w, 2d, 90m, 1mo or -1d."))
    (reduce (fn [span [_ sign digits unit]]
              (let [n (* (if (= "-" sign) -1 1) (parse-long digits))]
                (case unit
                  "y" (update span :months + (* 12 n))
                  "mo" (update span :months + n)
                  "w" (update span :days + (* 7 n))
                  "d" (update span :days + n)
                  "h" (update span :minutes + (* 60 n))
                  "m" (update span :minutes + n))))
            {:months 0 :days 0 :minutes 0}
            terms)))

(def ^:private weekdays
  (into {} (for [^DayOfWeek d (DayOfWeek/values)
                 :let [full (str/lower-case (.getDisplayName d TextStyle/FULL Locale/ENGLISH))]
                 k [full (subs full 0 3)]]
             [k d])))

(defn parse-weekday
  "A weekday from mon..sun or its full name, in any case. Throws otherwise."
  ^DayOfWeek [s]
  (or (weekdays (str/lower-case (str/trim (str s))))
      (fail "weekday is not in a form I can read. Use mon, tue, wed, thu, fri, sat or sun.")))

;; --- arithmetic

(defn- guarded [f]
  (try (f)
       (catch DateTimeException _ (fail "That result is outside the dates I can work with."))
       (catch ArithmeticException _ (fail "That result is outside the dates I can work with."))))

(defn add
  "A date or date-time plus a parsed span. A plain date has no time of day,
  so it takes months and days only. Months and days keep the wall clock
  time across a daylight saving change; hours and minutes are elapsed time."
  [when {:keys [months days minutes]}]
  (guarded
   (fn []
     (if (instance? LocalDate when)
       (do (when-not (zero? minutes)
             (fail "A date has no time of day, so hours and minutes do not apply to it. Give a date and"
                   " time, such as 2026-10-14 09:00, or use now."))
           (-> ^LocalDate when (.plusMonths months) (.plusDays days)))
       (-> ^ZonedDateTime when (.plusMonths months) (.plusDays days) (.plusMinutes minutes))))))

(defn- zoned ^ZonedDateTime [when ^ZoneId zone]
  (if (instance? LocalDate when) (.atStartOfDay ^LocalDate when zone) when))

(defn between
  "The gap from one date to another, negative when the second is earlier.
  Two plain dates give {:days}. Otherwise {:days :hours :minutes :seconds
  :elapsed}: from plus those calendar days, then those hours, minutes and
  seconds, is to, exactly, and :elapsed is the seconds that passed on the
  clock. The two differ when the clocks changed in between. A plain date
  counts as the start of that day. Date-times are read to the second."
  [from to ^ZoneId zone]
  (guarded
   (fn []
     (if (and (instance? LocalDate from) (instance? LocalDate to))
       {:days (.until ^LocalDate from to ChronoUnit/DAYS)}
       (let [a (zoned from zone)
             b (zoned to zone)
             ;; Calendar days come from the local dates, so across a change
             ;; of clocks the rest can come out the other way (a day and
             ;; minus half an hour). Count one day fewer then, so the
             ;; pieces all lean the same way.
             [days rest-seconds]
             (loop [days (.until a b ChronoUnit/DAYS)]
               (let [rest-seconds (.until (.plusDays a days) b ChronoUnit/SECONDS)]
                 (if (or (and (pos? days) (neg? rest-seconds)) (and (neg? days) (pos? rest-seconds)))
                   (recur (if (pos? days) (dec days) (inc days)))
                   [days rest-seconds])))]
         {:days days
          :hours (quot rest-seconds 3600)
          :minutes (rem (quot rest-seconds 60) 60)
          :seconds (rem rest-seconds 60)
          :elapsed (.until a b ChronoUnit/SECONDS)})))))

(defn earlier?
  "Whether the date `a` is before `b` as a moment, whatever offsets they are
  written in. A plain date is the start of that day."
  [a b ^ZoneId zone]
  (.isBefore (.toInstant (zoned a zone)) (.toInstant (zoned b zone))))

(def max-nth
  "The largest nth next accepts: a couple of centuries of weeks."
  10000)

(defn next-weekday
  "The first `weekday` on or after a date, or the nth such day (1 is the
  first, up to max-nth). A date-time keeps its zone and its time of day,
  moved forward as java.time does when that time does not exist on the
  day. A zone can skip a whole date (Samoa skipped 2011-12-30): such a
  date is not counted, so the nth weekday is the nth that happens. That
  holds for a plain date too, which is a date in `zone` (a date-time uses
  its own). A weekday whose date exists but whose time of day would land on
  the next day is refused rather than answered with another weekday."
  [when ^DayOfWeek weekday n ^ZoneId zone]
  (when-not (and (integer? n) (<= 1 n max-nth))
    (fail "nth must be a whole number from 1 to " max-nth "."))
  (guarded
   (fn []
     (let [plain? (instance? LocalDate when)
           start ^LocalDate (if plain? when (.toLocalDate ^ZonedDateTime when))
           zone (if plain? zone (.getZone ^ZonedDateTime when))
           ahead (mod (- (.getValue weekday) (.getValue (.getDayOfWeek start))) 7)
           exists? (fn [^LocalDate d] (= d (.toLocalDate (.atStartOfDay d zone))))
           date ^LocalDate (nth (filter exists? (iterate #(.plusDays ^LocalDate % 7) (.plusDays start ahead)))
                                (dec (long n)))]
       (if plain?
         date
         (let [found (.with ^ZonedDateTime when date)]
           (when-not (= date (.toLocalDate found))
             (fail "That time of day does not exist on the weekday's date in this time zone."))
           found))))))

;; --- showing

(defn- weekday-name [^DayOfWeek d] (.getDisplayName d TextStyle/FULL Locale/ENGLISH))

(defn show
  "A date or date-time as text: the date, or the ISO 8601 date-time with its
  offset, then the weekday, then the zone."
  [when]
  (if (instance? LocalDate when)
    (str when " " (weekday-name (.getDayOfWeek ^LocalDate when)))
    (let [z (.truncatedTo ^ZonedDateTime when ChronoUnit/SECONDS)]
      (str (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME z) " " (weekday-name (.getDayOfWeek z))
           " (" (.getId (.getZone z)) ")"))))

(defn- unit [n word] (str n " " word (when-not (= 1 (abs n)) "s")))

(defn- show-gap [{:keys [days hours minutes seconds elapsed]}]
  (if-not hours
    (unit days "day")
    (str (str/join ", " (concat [(unit days "day") (unit hours "hour") (unit minutes "minute")]
                                (when-not (zero? seconds) [(unit seconds "second")])))
         (when (not= elapsed (+ (* 86400 days) (* 3600 hours) (* 60 minutes) seconds))
           (str " (" (unit (quot elapsed 3600) "hour") ", " (unit (rem (quot elapsed 60) 60) "minute")
                (when-not (zero? (rem elapsed 60)) (str ", " (unit (rem elapsed 60) "second")))
                " of clock time, because the clocks changed in between)")))))

(defn- show-span
  "A parsed span as text, rather than the text it was parsed from."
  [{:keys [months days minutes]}]
  (let [parts (cond-> []
                (not (zero? months)) (conj (unit months "month"))
                (not (zero? days)) (conj (unit days "day"))
                (not (zero? minutes)) (conj (unit minutes "minute")))]
    (if (empty? parts) "0 days" (str/join ", " parts))))

(defn- ordinal [n]
  (str n (case (if (<= 11 (mod n 100) 13) 0 (mod n 10)) 1 "st" 2 "nd" 3 "rd" "th")))

;; --- the tool's operations

(def ^:private operations
  "Each operation's arguments, and which of them it needs."
  {"now" {:takes #{} :needs []}
   "add" {:takes #{:date :span} :needs [:span]}
   "between" {:takes #{:from :to} :needs [:from :to]}
   "next" {:takes #{:weekday :date :nth} :needs [:weekday]}})

(defn answer
  "The text answering a clock call, from the instant now, the operator's
  zone and the call's arguments ({:op ...} with keyword keys). Throws an
  ex-info that explains what is wrong when the call is not valid. Nothing
  in the answer or the explanation is the caller's own text: dates, spans
  and weekdays are shown as parsed, and explanations are fixed."
  [^Instant now ^ZoneId zone {:keys [op] :as args}]
  (try
    (let [{:keys [takes needs]} (or (and (string? op) (operations op))
                                    (fail "op must be one of " (str/join ", " (sort (keys operations)))
                                          ", in lower case."))
          given (disj (set (keys args)) :op)
          parse #(parse-when (get args %) now zone (name %))]
      (when (seq (remove takes given))
        (fail op " takes " (if (empty? takes)
                             "no arguments besides op"
                             (str "only " (str/join ", " (map name (sort takes))) " besides op")) "."))
      (when-let [missing (first (remove given needs))]
        (fail op " needs " (name missing) "."))
      (case op
        "now" (show (parse-when "now" now zone))
        "add" (let [start (parse-when (get args :date "now") now zone "date")
                    span (parse-span (:span args))]
                (str (show start) " plus " (show-span span) " is " (show (add start span))))
        "between" (let [from (parse :from) to (parse :to)]
                    (str "From " (show from) " to " (show to) ": " (show-gap (between from to zone))
                         (when (earlier? to from zone) " (the second is earlier)") "."))
        "next" (let [nth (get args :nth 1)
                     start (parse-when (get args :date "today") now zone "date")
                     day (parse-weekday (:weekday args))
                     result (next-weekday start day nth zone)]
                 (str "The " (if (= 1 nth) "first" (ordinal nth)) " " (weekday-name day) " on or after "
                      (show start) " is " (show result)))))
    (catch clojure.lang.ExceptionInfo e (throw e))
    ;; Whatever else goes wrong has a message made by Java, which can quote
    ;; the input.
    (catch Exception _ (fail "That call could not be worked out; check each argument."))))
