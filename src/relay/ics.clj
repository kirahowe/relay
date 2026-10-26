(ns relay.ics
  "The agent's calendar as an iCalendar feed (RFC 5545). Pure: events in,
  text out. relay.serve serves the text; relay.core says which events
  there are.

  The feed is written so that a calendar app can subscribe to it and
  never be surprised:

    - Lines end CRLF and are folded at 75 octets, on a code point, never
      inside a UTF-8 sequence.
    - TEXT values escape backslash, semicolon, comma and newline, and
      drop every control, format or invisible code point, so text a model
      wrote cannot start another property or hide what it says.
    - Times are UTC. An all-day event is a DATE: DTSTART;VALUE=DATE is
      its first day and DTEND;VALUE=DATE the day after its last, so a
      calendar app shows it as a whole day wherever it is.
    - An event's UID is made from its number, so it never changes.
    - SEQUENCE is the number of changes the event has had, so it rises
      with each one.
    - DTSTAMP is the time of the event's last change, not the time of the
      request, so the same calendar renders to the same bytes.
    - An event that ended more than ended-days ago is dropped, so the feed
      holds what is coming or just happened. The log keeps the history.
    - An event that was removed stays in the feed as STATUS:CANCELLED for
      cancelled-days, then is dropped. A subscriber that polls less often
      than that still hears of the removal; one that has been away longer
      keeps a stale event until it refreshes from scratch, and the feed
      does not grow without bound."
  (:require [clojure.string :as str]
            [relay.text :as text])
  (:import (java.time Duration Instant ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def line-limit
  "The most octets a physical line may have, not counting its CRLF."
  75)

(def cancelled-days
  "How long a removed event stays in the feed as cancelled."
  30)

(def ended-days
  "How long a live event stays in the feed after it has ended. History
  stays in the log; the feed is for what is coming or just happened."
  30)

(def crlf "\r\n")

;; --- text

(defn- code-points [^String s]
  (vec (.toArray (.codePoints s))))

(defn clean-text
  "The text as the feed carries it: every line break (CRLF, CR, LF, NEL,
  LINE and PARAGRAPH SEPARATOR) is one newline, and every other code
  point that is hidden (relay.text/hidden?: controls, format characters,
  private use, unassigned) is dropped. Tab stays."
  [s]
  (let [sb (StringBuilder.)
        cps (code-points (str s))
        n (count cps)]
    (loop [i 0]
      (when (< i n)
        (let [cp (nth cps i)]
          (cond
            (and (= cp 13) (< (inc i) n) (= 10 (nth cps (inc i)))) (do (.append sb "\n") (recur (+ i 2)))
            (#{10 13 0x85 0x2028 0x2029} cp) (do (.append sb "\n") (recur (inc i)))
            (= cp 9) (do (.append sb "\t") (recur (inc i)))
            (text/hidden? cp) (recur (inc i))
            :else (do (.appendCodePoint sb (int cp)) (recur (inc i)))))))
    (str sb)))

(defn escape-text
  "A TEXT value (RFC 5545, 3.3.11): clean-text, then backslash, semicolon
  and comma each behind a backslash and a newline as \\n."
  [s]
  (-> (clean-text s)
      (str/replace "\\" "\\\\")
      (str/replace ";" "\\;")
      (str/replace "," "\\,")
      (str/replace "\n" "\\n")))

;; --- folding

(defn- octets
  "How many octets a code point takes in UTF-8. A lone surrogate counts as
  three, more than the one Java writes for it, so a line is never too long."
  ^long [^long cp]
  (cond (< cp 0x80) 1 (< cp 0x800) 2 (< cp 0x10000) 3 :else 4))

(defn fold-line
  "A content line (no line break in it) folded at line-limit octets: the
  physical lines joined by CRLF and a space. The space counts toward the
  limit of the line it starts. A break falls only between code points."
  [line]
  (let [sb (StringBuilder.)]
    (loop [cps (seq (code-points (str line))) used 0]
      (when-let [[cp & more] cps]
        (let [w (octets cp)]
          (if (> (+ used w) line-limit)
            (do (.append sb crlf) (.append sb " ")
                (recur cps 1))
            (do (.appendCodePoint sb (int cp))
                (recur more (+ used w)))))))
    (str sb)))

;; --- values

(def ^:private utc-format (.withZone (DateTimeFormatter/ofPattern "uuuuMMdd'T'HHmmss'Z'") ZoneOffset/UTC))

(defn utc
  "An instant as an iCalendar UTC date-time, to the second."
  [^Instant t]
  (.format ^DateTimeFormatter utc-format t))

(def ^:private date-format (DateTimeFormatter/ofPattern "uuuuMMdd"))

(defn date
  "A day as an iCalendar DATE."
  [^java.time.LocalDate d]
  (.format ^DateTimeFormatter date-format d))

(defn uid
  "The UID of the event numbered `id`: the same for every version of it."
  [id]
  (str "relay-event-" id "@relay.invalid"))

;; --- events

(defn- property [name value]
  (fold-line (str name ":" value)))

(defn- text-property [name value]
  (property name (escape-text value)))

(defn event-lines
  "One event's VEVENT, as unfolded-property strings (each folded already).
  The event is {:id :title :start :end :location :description :sequence
  :created :modified :removed-at}, times as Instants. :end, :location
  and :description may be nil. A :removed-at makes it cancelled. An
  all-day event has :start-date and :end-date too, LocalDates, the first
  day and the day after the last, and is written with those."
  [{:keys [id title start end start-date end-date location description sequence created modified removed-at]}]
  (concat
   ["BEGIN:VEVENT"
    (property "UID" (uid id))
    (property "DTSTAMP" (utc modified))
    (property "CREATED" (utc created))
    (property "LAST-MODIFIED" (utc modified))
    (property "SEQUENCE" (str sequence))
    (if start-date (property "DTSTART;VALUE=DATE" (date start-date)) (property "DTSTART" (utc start)))]
   (cond start-date [(property "DTEND;VALUE=DATE" (date end-date))]
         end [(property "DTEND" (utc end))])
   [(text-property "SUMMARY" title)]
   (when-not (str/blank? location) [(text-property "LOCATION" location)])
   (when-not (str/blank? description) [(text-property "DESCRIPTION" description)])
   [(property "STATUS" (if removed-at "CANCELLED" "CONFIRMED"))
    "END:VEVENT"]))

(defn listed?
  "Whether the feed carries the event at `now`. A removed event is carried
  until it has been cancelled for cancelled-days, if it was still listed
  when it was removed. A live one is carried
  until it has been over for ended-days (it is over at its end, or at its
  start when it has none). One rule, used for the feed and for the limit
  on how many events there may be, so the two cannot disagree."
  [now {:keys [removed-at start end]}]
  (let [over (.plus ^Instant (or end start) (Duration/ofDays ended-days))]
    (if removed-at
      ;; An event that had already left the feed when it was removed stays
      ;; off it: a subscriber dropped it long ago, and listing it again
      ;; would let add-then-remove grow the feed without bound.
      (and (.isBefore ^Instant now (.plus ^Instant removed-at (Duration/ofDays cancelled-days)))
           (.isBefore ^Instant removed-at over))
      (.isBefore ^Instant now over))))

(defn listed
  "The events the feed carries at `now` (listed?), by id."
  [now events]
  (->> events (filter #(listed? now %)) (sort-by :id)))

(defn render
  "The calendar as iCalendar text, CRLF after every line. `now` decides
  which removed events are still listed; nothing else in the text
  depends on it. `calendar-name` is what a subscriber calls it."
  [{:keys [calendar-name now events]}]
  (let [lines (concat ["BEGIN:VCALENDAR"
                       "VERSION:2.0"
                       "PRODID:-//relay//calendar//EN"
                       "CALSCALE:GREGORIAN"
                       "METHOD:PUBLISH"
                       (text-property "X-WR-CALNAME" (or calendar-name "Agenda"))]
                      (mapcat event-lines (listed now events))
                      ["END:VCALENDAR"])]
    (str (str/join crlf lines) crlf)))
