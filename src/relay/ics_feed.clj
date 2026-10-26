(ns relay.ics-feed
  "Reading a calendar feed someone else publishes (RFC 5545), such as the
  operator's own calendar, into events a role can read. Pure. relay only
  reads such a feed; it never writes one back (relay.ics renders the
  agent's own calendar).

  The reader is lenient: a line it cannot read is skipped, never fatal,
  since the feed is not relay's. Its text is from outside all the same."
  (:require [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (java.security MessageDigest)
           (java.time Duration Instant LocalDate LocalDateTime ZoneId ZoneOffset)
           (java.time.format DateTimeFormatter)))

(defn unfold
  "A feed's text as its content lines: folds (a line break, then a space
  or tab) removed, CRLF or LF line ends."
  [s]
  (->> (str/split (str/replace (str s) #"\r?\n[ \t]" "") #"\r?\n")
       (remove str/blank?)
       vec))

(defn- split-params
  "The part of a content line before its value's colon, split at its
  semicolons, quotes kept whole: [name {PARAM value}]."
  [s]
  (let [parts (re-seq #"(?:[^;\"]|\"[^\"]*\")+" s)
        [n & ps] parts]
    [(str/upper-case (str n))
     (into {} (for [p ps :let [[_ k v] (re-matches #"([^=]+)=(.*)" p)] :when k]
                [(str/upper-case k) (str/replace v #"^\"|\"$" "")]))]))

(defn content-line
  "One content line as {:name :params :value}, or nil when it is not one."
  [line]
  (when-let [[_ head value] (re-matches #"((?:[^:\"]|\"[^\"]*\")+):(.*)" (str line))]
    (let [[n ps] (split-params head)]
      (when (re-matches #"[A-Z0-9\-]+" n)
        {:name n :params ps :value value}))))

(defn unescape
  "A TEXT value's escapes undone: \\n and \\N a newline, \\, \\; and \\\\ the
  character itself."
  [s]
  (str/replace (str s) #"\\([nN,;\\])" (fn [[_ c]] (if (#{"n" "N"} c) "\n" c))))

(defn components
  "The VEVENTs of a feed, each its properties in order, nested components
  (alarms) left out."
  [lines]
  (loop [[l & more] (map content-line lines) depth 0 cur nil out []]
    (cond
      (and (nil? l) (empty? more)) out
      (nil? l) (recur more depth cur out)
      (and (= "BEGIN" (:name l)) (= "VEVENT" (str/upper-case (:value l))) (nil? cur))
      (recur more 0 [] out)
      (and cur (= "BEGIN" (:name l))) (recur more (inc depth) cur out)
      (and cur (= "END" (:name l)) (pos? depth)) (recur more (dec depth) cur out)
      (and cur (= "END" (:name l)) (= "VEVENT" (str/upper-case (:value l)))) (recur more 0 nil (conj out cur))
      (and cur (zero? depth)) (recur more depth (conj cur l) out)
      :else (recur more depth cur out))))

(def ^:private basic-date (DateTimeFormatter/ofPattern "uuuuMMdd"))
(def ^:private basic-local (DateTimeFormatter/ofPattern "uuuuMMdd'T'HHmmss"))

(defn when-of
  "A DATE or DATE-TIME property's time: {:date LocalDate} for a whole
  day, {:instant Instant} for a moment. A floating time, or one whose
  TZID this machine does not know, is read in `zone`. Nil when it cannot
  be read."
  [{:keys [params value]} ^ZoneId zone]
  (let [v (str/trim (str value))]
    (try
      (cond
        (re-matches #"\d{8}" v) {:date (LocalDate/parse v basic-date)}
        (re-matches #"\d{8}T\d{6}Z" v)
        {:instant (.toInstant (.atOffset (LocalDateTime/parse (subs v 0 15) basic-local) ZoneOffset/UTC))}
        (re-matches #"\d{8}T\d{6}" v)
        (let [z (or (some-> (get params "TZID") (#(try (ZoneId/of %) (catch Exception _ nil)))) zone)]
          {:instant (.toInstant (.atZone (LocalDateTime/parse v basic-local) z))}))
      (catch Exception _ nil))))

(defn- prop [props n] (first (filter #(= n (:name %)) props)))

(defn- text-of [props n]
  (some-> (prop props n) :value unescape str/trim not-empty))

(defn- duration-end [start props]
  (when-let [d (some-> (prop props "DURATION") :value str/trim)]
    (when-let [dur (try (Duration/parse (str/replace d #"^([+-]?)P(\d+)W" (fn [[_ s w]] (str s "P" (* 7 (parse-long w)) "D"))))
                        (catch Exception _ nil))]
      (cond
        (:instant start) {:instant (.plus ^Instant (:instant start) dur)}
        (:date start) {:date (.plusDays ^LocalDate (:date start) (.toDays dur))}))))

(defn event
  "A VEVENT's properties as {:uid :recurrence-id :summary :start :end
  :location :description :status :rrule :organizer}, times as when-of
  gives them. Nil without a UID or a start it can read."
  [props zone]
  (let [uid (some-> (prop props "UID") :value str/trim not-empty)
        start (some-> (prop props "DTSTART") (when-of zone))
        end (or (some-> (prop props "DTEND") (when-of zone)) (when start (duration-end start props)))]
    (when (and uid start)
      (cond-> {:uid uid :start start :summary (or (text-of props "SUMMARY") "(no title)")}
        end (assoc :end end)
        (prop props "RECURRENCE-ID") (assoc :recurrence-id (str/trim (:value (prop props "RECURRENCE-ID"))))
        (text-of props "LOCATION") (assoc :location (text-of props "LOCATION"))
        (text-of props "DESCRIPTION") (assoc :description (text-of props "DESCRIPTION"))
        (text-of props "STATUS") (assoc :status (str/lower-case (text-of props "STATUS")))
        (prop props "RRULE") (assoc :rrule (str/trim (:value (prop props "RRULE"))))
        (prop props "ORGANIZER") (assoc :organizer (let [o (prop props "ORGANIZER")]
                                                     (or (get-in o [:params "CN"])
                                                         (str/replace (str (:value o)) #"(?i)^mailto:" ""))))))))

(defn events
  "Every event a feed's text holds that event can read."
  [text zone]
  (vec (keep #(event % zone) (components (unfold text)))))

(defn event-key
  "What names an event across polls: its UID, and for one occurrence of a
  repeating event, its RECURRENCE-ID too."
  [{:keys [uid recurrence-id]}]
  (if recurrence-id (str uid "#" recurrence-id) uid))

(defn version
  "A short digest of everything an event says, which changes when any of
  it does."
  [ev]
  (let [stable (walk/postwalk #(cond (map? %) (into (sorted-map) %) (coll? %) % :else (str %)) ev)
        d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (pr-str stable) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) (take 12 d)))))

(defn- until-of [rrule zone]
  (when-let [[_ u] (re-find #"(?i)UNTIL=([0-9TZ]+)" (str rrule))]
    (when-of {:params {} :value u} zone)))

(defn- instant-of ^Instant [w ^ZoneId zone]
  (or (:instant w) (some-> ^LocalDate (:date w) (.atStartOfDay zone) .toInstant)))

(defn upcoming?
  "Whether an event is worth a role's attention at `now`: it has not been
  over for more than a day, or it repeats with no end before now."
  [{:keys [start end rrule]} ^ZoneId zone ^Instant now]
  (let [over (instant-of (or end start) zone)
        since (.minus now (Duration/ofDays 1))]
    (boolean
     (or (and over (not (.isBefore over since)))
         (and rrule (let [u (until-of rrule zone)]
                      (or (nil? u) (not (.isBefore (instant-of u zone) since)))))))))

(declare one-line)

(defn calendar?
  "Whether a feed's text is a whole calendar: it starts with
  BEGIN:VCALENDAR and holds the END:VCALENDAR that closes it. An error
  page, an empty answer or a feed cut off on the way is not."
  [text]
  (let [names (map #(some-> (content-line %) ((juxt :name (comp str/upper-case str/trim :value)))) (unfold text))]
    (boolean (and (= ["BEGIN" "VCALENDAR"] (first names))
                  (some #{["END" "VCALENDAR"]} names)))))

(defn poll
  "What a poll of a feed found: {:items [{:key :version :event}] :keys
  [every key in the feed]}, the items those upcoming at `now`. Throws
  when the text is not a whole calendar, so an answer that is something
  else never reads as a feed whose events were all removed."
  [text ^ZoneId zone ^Instant now]
  (when-not (calendar? text)
    (throw (ex-info "the calendar's answer was not a whole calendar feed" {})))
  (let [evs (events text zone)]
    ;; The version carries the title after the digest, so a removal can
    ;; still say what was removed once the event is gone from the feed.
    {:items (vec (for [e evs :when (upcoming? e zone now)]
                   {:key (event-key e) :version (str (version e) " " (one-line (:summary e) 80)) :event e}))
     :keys (vec (distinct (map event-key evs)))}))

;; --- what a role reads

(def ^:private day-format (DateTimeFormatter/ofPattern "EEE yyyy-MM-dd" java.util.Locale/ENGLISH))
(def ^:private time-format (DateTimeFormatter/ofPattern "HH:mm"))

(defn- when-text [{:keys [start end]} ^ZoneId zone]
  (let [day #(.format ^DateTimeFormatter day-format %)
        at (fn [i] (.atZone ^Instant i zone))]
    (cond
      (:date start)
      (let [last-day (when (:date end) (.minusDays ^LocalDate (:date end) 1))]
        (if (and last-day (.isAfter last-day (:date start)))
          (str (day (:date start)) " to " (day last-day) ", all day")
          (str (day (:date start)) ", all day")))
      :else
      (let [s (at (:instant start))
            e (some-> (:instant end) at)]
        (str (day s) " " (.format ^DateTimeFormatter time-format s)
             (when e
               (if (= (.toLocalDate s) (.toLocalDate e))
                 (str " to " (.format ^DateTimeFormatter time-format e))
                 (str " to " (day e) " " (.format ^DateTimeFormatter time-format e)))))))))

(defn- one-line [s n]
  (let [l (str/trim (str/replace (str s) #"(?U)\s+" " "))]
    (if (> (count l) n) (str (subs l 0 (dec n)) "…") l)))

(defn event-text
  "An event as a few lines a role reads, times in `zone`."
  [{:keys [summary location description status rrule organizer] :as ev} ^ZoneId zone]
  (str (one-line summary 200) ", " (when-text ev zone)
       (when (= "cancelled" status) " (cancelled)")
       (when rrule (str ", repeating (" (one-line rrule 120) ")"))
       (when location (str "\n  At: " (one-line location 300)))
       (when organizer (str "\n  Organizer: " (one-line organizer 200)))
       (when description (str "\n  " (str/replace (let [d (str/trim description)]
                                                    (if (> (count d) 1500) (str (subs d 0 1500) "…") d))
                                                  "\n" "\n  ")))))
