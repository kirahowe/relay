(ns relay.brief
  "The morning brief: one notification a day, at the time `:brief {:at
  \"07:30\"}` in relay.edn names, with today's calendar, the reminders due
  today, the reports waiting for review and the held calls. relay composes
  it itself; no role is woken. Pure: the config, the state, the time and
  what the cal and rem tools printed come in as arguments. relay.dispatch
  runs the tools and sends it, and relay.render/brief makes its text.

  A day's brief is due once its time has come that day, in the operator's
  zone, until one is recorded for that day (relay.core keeps the last
  date). A tick missed while the Mac slept sends it late the same day. A
  day that ends before any tick past its time has no brief: a brief for a
  day already gone would be news nobody needs. A crash before the brief is
  recorded sends it at the next tick; one after is never sent again."
  (:require [clojure.string :as str]
            [relay.config :as config]
            [relay.core :as core])
  (:import (java.time Instant LocalDate LocalTime ZoneId ZonedDateTime)))

(def default-tools
  "The tools the brief runs when :brief names none, by section: the names
  setup/relay.example.edn gives cal and the read half of rem."
  {:calendar "cal" :reminders "rem-read"})

(def tool-args
  "What the brief asks each tool, after the tool's own command, for `date`."
  {:calendar (fn [date] ["events" "--from" (str date) "--to" (str date)])
   :reminders (fn [_] ["ls" "--today"])})

(def default-timeout-seconds
  "How long the brief waits for cal or rem when relay.edn gives the tool
  no :timeout-seconds. The tick waits on it, and a tool that hangs, such
  as one waiting on a permission prompt nobody sees, must not hold up
  the tick for the ten minutes a role's call may take."
  60)

(def lines-per-section
  "The most items a section lists; the rest are counted."
  8)

(def title-limit
  "The most characters of a title, a list or a calendar name shown."
  80)

;; --- when

(defn parse-at
  "The LocalTime in an \"HH:MM\" string, or nil when it is not one."
  [s]
  (when-let [[_ h m] (and (string? s) (re-matches #"(\d{2}):(\d{2})" s))]
    (let [h (parse-long h) m (parse-long m)]
      (when (and (<= 0 h 23) (<= 0 m 59)) (LocalTime/of h m)))))

(defn at-time
  "When the brief goes out each day, or nil when relay.edn has no :brief."
  [config]
  (parse-at (get-in config [:brief :at])))

(defn slot
  "The instant a day's brief comes due. A time that a clock change skips
  is moved forward by the length of the gap, as relay.schedule does."
  ^Instant [^LocalDate date ^LocalTime at ^ZoneId zone]
  (.toInstant (ZonedDateTime/of date at zone)))

(defn due-date
  "The date whose brief is due at `now`, or nil. It is today's, in `zone`,
  once today's time has come and when `last`, the date of the last brief
  recorded, is earlier than today or nil."
  [^LocalTime at ^ZoneId zone ^Instant now ^LocalDate last]
  (when at
    (let [today (.toLocalDate (.atZone now zone))]
      (when (and (or (nil? last) (.isAfter today last))
                 (not (.isBefore now (slot today at zone))))
        today))))

(defn due
  "due-date for a config and a state."
  [config state now]
  (due-date (at-time config) (config/zone config) now (core/brief-date state)))

;; --- the tools

(defn source
  "How the brief reads a section's tool from `config`: {:tool name :spec
  spec} for a tool it can run, {:missing name} when relay.edn defines no
  such tool, or {:unusable name :why text} when it is not one the brief
  may run unattended. Only a :read tool given its arguments on its command
  line is run: the brief runs it with no one to approve a call."
  [config section]
  (let [n (or (get-in config [:brief section]) (default-tools section))
        spec (get-in config [:tools n])]
    (cond
      (nil? spec) {:missing n}
      (not= :read (:class spec)) {:unusable n :why (str "is not a :read tool, and the brief runs only those")}
      (= :envelope (:protocol spec)) {:unusable n :why "speaks the envelope protocol, and the brief gives a tool its arguments on the command line"}
      :else {:tool n :spec spec})))

(defn- clip
  "`s` cut to title-limit code points, so a surrogate pair is never split.
  The text holds no escapes yet (fields decodes them, and the push escapes
  once, last), so the cut cannot fall inside one."
  [s]
  (let [s (str s)]
    (if (> (.codePointCount s 0 (count s)) title-limit)
      (str (subs s 0 (.offsetByCodePoints s 0 title-limit)) "...")
      s)))

(defn unescape
  "A field as cal and rem printed it (tools/shared/Escape.swift), back as
  the text it was: \\\\ is a backslash, \\t, \\n and \\r are themselves,
  and \\u{XXXX} is that code point. Nil when a backslash starts anything
  else, which their escaper never prints. The push makes the text
  visible again, once (relay.notify); decoding here keeps it from being
  escaped twice."
  [s]
  (let [s (str s) n (count s) sb (StringBuilder.)]
    (loop [i 0]
      (if (>= i n)
        (str sb)
        (let [c (.charAt s i)]
          (if (not= \\ c)
            (do (.append sb c) (recur (inc i)))
            (let [nxt (when (< (inc i) n) (.charAt s (inc i)))
                  simple ({\\ \\ \t \tab \n \newline \r \return} nxt)]
              (cond
                simple (do (.append sb simple) (recur (+ i 2)))
                (= \u nxt) (let [[m hex] (re-find #"^\{([0-9A-Fa-f]{1,6})\}" (subs s (+ i 2)))
                                  cp (some-> hex (Long/parseLong 16))]
                              (when (and cp (<= cp 0x10FFFF))
                                (.appendCodePoint sb (int cp))
                                (recur (+ i 2 (count m)))))
                :else nil))))))))

(defn squeeze
  "Text with every run of whitespace, Unicode's spaces and line breaks
  included, made one space, and none at either end. A title padded with
  spaces cannot then wrap on the phone so that its tail starts a line of
  its own and looks like a heading."
  [s]
  (str/trim (str/replace (str s) #"(?U)\s+" " ")))

(defn- fields
  "A line's tab-separated fields, each decoded and squeezed, or nil when
  one holds an escape cal and rem do not print."
  [line]
  (let [fs (map unescape (str/split line #"\t" -1))]
    (when (every? some? fs) (mapv squeeze fs))))

(defn- lines [out] (remove str/blank? (str/split-lines (str out))))

(defn calendar-items
  "cal events' output as {:items [{:start :end :title :calendar}] :more n},
  or nil when a line is neither an event nor cal's note of events left
  out. Each item is one line of the output, so its text holds no line
  break."
  [out]
  (reduce (fn [acc line]
            (let [fs (fields line)]
              (cond
                (= 6 (count fs)) (let [[_ start end title cal] fs]
                                   (update acc :items conj {:start start :end end :title title :calendar cal}))
                (re-matches #"note: (\d+) more events not shown.*" line)
                (assoc acc :more (parse-long (second (re-matches #"note: (\d+) .*" line))))
                :else (reduced nil))))
          {:items [] :more 0}
          (lines out)))

(defn reminder-items
  "rem ls's output as {:items [{:list :due :title}] :more 0}, or nil when a
  line is not a reminder."
  [out]
  (reduce (fn [acc line]
            (let [fs (fields line)]
              (if (= 5 (count fs))
                (let [[_ list due title] fs]
                  (update acc :items conj {:list list :due due :title title}))
                (reduced nil))))
          {:items [] :more 0}
          (lines out)))

(def ^:private parsers {:calendar calendar-items :reminders reminder-items})

(defn tool-section
  "A section from its source and what running the tool gave: `ran` is
  {:out text :labels} or {:error text}, nil when it did not run. The
  section is {:status :ok :items :more :labels}, {:status :missing :tool},
  or {:status :unavailable :tool :why}."
  [section src ran]
  (cond
    (:missing src) {:status :missing :tool (:missing src)}
    (:unusable src) {:status :unavailable :tool (:unusable src) :why (:why src)}
    (:error ran) {:status :unavailable :tool (:tool src) :why (if (:timed-out ran) (:error ran) "failed")}
    :else (if-let [parsed ((parsers section) (:out ran))]
            (assoc parsed :status :ok :labels (core/union (:labels ran)))
            {:status :unavailable :tool (:tool src) :why "printed something the brief cannot read"})))

;; --- what it says

(defn- event-line
  "An event as a short line: its times on `date` and its title and
  calendar."
  [date {:keys [start end title calendar]}]
  (let [d (str date)
        at (fn [s] (if (str/starts-with? s (str d " ")) (subs s (inc (count d))) s))
        when (cond (str/starts-with? end "all-day") (if (= d start) "all day" (str "all day from " start))
                   :else (str (at start) "-" (at end)))]
    (str when " " (clip title) " (" (clip calendar) ")")))

(defn- reminder-line [date {:keys [list due title]}]
  (let [d (str date)]
    (str (clip title) " (" (clip list)
         (cond (= d due) ""
               (str/starts-with? due (str d " ")) (str ", due " (subs due (inc (count d))))
               (= "-" due) ""
               :else (str ", due " due))
         ")")))

(defn- report-line [{:keys [id role findings] :as r}]
  (str "Report " id " from " role ": " (count (core/pending-findings r)) " of " (count findings)
       " findings to review (relay review " id ")"))

(defn- hold-line [{:keys [id role tool]}]
  (str "Hold " id ": " role " wants to use " tool))

(defn- listed
  "A section of items as {:status :ok :count :lines :labels}, the lines cut
  at lines-per-section."
  [items line-of more labels]
  (let [shown (take lines-per-section items)
        left (+ more (- (count items) (count shown)))]
    {:status :ok
     :count (+ (count items) more)
     :lines (cond-> (mapv line-of shown) (pos? left) (conj (str "and " left " more")))
     :labels (if (seq items) (core/union labels) [])}))

(defn compose
  "The brief for `date`: its four sections, in order, from the calendar
  and reminders sections (tool-section) and the state. Each section is
  {:key :status ...}; one with items has :count, :lines and :labels, the
  labels of the text it shows."
  [state date calendar reminders]
  (let [tool (fn [k sec line-of]
               (assoc (if (= :ok (:status sec))
                        (listed (:items sec) #(line-of date %) (:more sec) (:labels sec))
                        sec)
                      :key k))
        reports (core/reports-waiting state)
        holds (core/open-holds state)]
    {:date date
     :sections [(tool :calendar calendar event-line)
                (tool :reminders reminders reminder-line)
                (assoc (listed reports report-line 0 (core/ids-labels state (map :role reports))) :key :reports)
                (assoc (listed holds hold-line 0 (core/ids-labels state (map :role holds))) :key :holds)]}))

(defn empty-brief?
  "Whether the brief has nothing to say: no section has an item or is
  unavailable. A tool that is not configured says nothing new each day,
  so it does not make a brief worth sending; a failed one does."
  [{:keys [sections]}]
  (every? #(case (:status %) :ok (zero? (:count %)) :missing true :unavailable false) sections))

(defn labels
  "The labels of everything the brief shows."
  [{:keys [sections]}]
  (apply core/union (map :labels sections)))

(defn summary
  "What the log records of each section: its count, or \"unavailable\" or
  \"not-configured\"."
  [{:keys [sections]}]
  (into {} (for [{:keys [key status count]} sections]
             [key (case status :ok count :unavailable "unavailable" :missing "not-configured")])))
