(ns relay.eventkit
  "Apple Calendar and Reminders through relay's own EventKit binary
  (tools/eventkit/eventkit). Pure: what the binary prints comes in, and
  what relay does with it comes out. relay.eventkit-run runs the binary,
  relay.reminders writes through it.

  The binary prints one JSON object on stdout and exits 0, or prints
  {\"error\": <text>, \"code\": <code>} and exits 1. Its commands:

    access                           ask for Calendar and Reminders access
    calendars, lists                 what exists
    events --from I --to I [--calendar T]...
    reminders --list T...            unfinished, and done in the last 14 days
    [--allow T]... add --list T --title T [--due D] [--notes N]
    [--allow T]... set ID [--title T] [--due D|none] [--notes N|none] [--list T]
    [--allow T]... done ID | reopen ID

  There is no delete command, so nothing relay does can delete one of the
  operator's reminders. The binary refuses a write to any list not given
  as --allow, and relay refuses it first (allowed?).

  Calendar events become the event maps relay.ics-feed reads from a feed,
  so a calendar from either source has one shape, one diff and one memo.
  Their text is from outside: invites and shared calendars carry what
  others wrote. Reminders are the operator's own: private, never outside."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [relay.ics-feed :as ics-feed]
            [relay.text :as text])
  (:import (java.security MessageDigest)
           (java.time Duration Instant LocalDate LocalDateTime ZoneId)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)))

(def look-back
  "How far before now a calendar read starts. An event that ended less
  than a day ago is still news (relay.ics-feed/upcoming?); the rest of
  the week is read so that an event aging out of it is let go of
  quietly, not reported as removed."
  (Duration/ofDays 8))

(def look-ahead "How far past now a calendar read reaches." (Duration/ofDays 60))

;; --- what the binary printed

(defn- first-line [s n]
  (let [l (str/trim (or (first (str/split-lines (str s))) ""))]
    (if (> (count l) n) (str (subs l 0 n) "…") l)))

(def access-help
  (str "Run `relay eventkit-access` in a terminal and allow Calendar and Reminders when macOS asks."
       " If it does not ask, allow eventkit in System Settings, under Privacy & Security."))

(defn failure-text
  "Why a call to the binary failed, in plain words, from its
  {:error :code}. A lost grant says how to give it again."
  [{:keys [error code]}]
  (case (str code)
    "no-access" (str "relay may not read Calendar or Reminders. " access-help)
    "usage" (str "relay called its eventkit tool wrongly: " (first-line error 200))
    "not-found" (str "eventkit could not find it: " (first-line error 200))
    "refused" (str "eventkit refused it: " (first-line error 200))
    (str "eventkit failed: " (first-line error 200))))

(defn output
  "What the binary printed, from its exit code and stdout: {:ok map} for
  a JSON object it printed on success, else {:error text :code code}.
  Anything else it did, such as printing nothing or something that is
  not JSON, is an error that says so."
  [exit out]
  (let [parsed (try (json/parse-string (str out) true) (catch Exception _ ::bad))]
    (cond
      (and (map? parsed) (contains? parsed :error))
      {:error (failure-text parsed) :code (str (:code parsed))}
      (and (zero? exit) (map? parsed)) {:ok parsed}
      (zero? exit) {:error "eventkit printed something relay could not read." :code "failed"}
      :else {:error (str "eventkit stopped with exit code " exit " and said nothing relay could read.")
             :code "failed"})))

;; --- calendar events

(def ^:private date-only #"\d{4}-\d{2}-\d{2}")

(defn- when-of [s]
  (try (let [s (str/trim (str s))]
         (cond (re-matches date-only s) {:date (LocalDate/parse s)}
               (re-matches #"\d{4}-\d{2}-\d{2}T[0-9:.]+Z" s) {:instant (Instant/parse s)}))
       (catch Exception _ nil)))

(defn- present [s] (when (and (string? s) (not (str/blank? s))) (str/trim s)))

(defn event
  "One event the binary printed, as relay.ics-feed's event: {:uid :summary
  :start :end :location :description :status :calendar}. :uid is the
  binary's key, which is stable for one occurrence of a repeating event.
  Nil without a key or a start relay can read."
  [{:keys [key title start end location notes status calendar]}]
  (let [uid (present key)
        s (when start (when-of start))]
    (when (and uid s)
      (let [e (when end (when-of end))
            st (some-> (present status) str/lower-case (str/replace "canceled" "cancelled"))]
        (cond-> {:uid uid :start s :summary (or (present title) "(no title)")}
          e (assoc :end e)
          (present location) (assoc :location (present location))
          (present notes) (assoc :description (present notes))
          (and st (not= "none" st)) (assoc :status st)
          (present calendar) (assoc :calendar (present calendar)))))))

(defn poll-events
  "What a read of the calendar found, in relay.ics-feed/poll's shape:
  {:items [{:key :version :event}] :keys [...]}. `events` are the
  binary's, `now` the time of the read."
  [events ^ZoneId zone ^Instant now]
  (let [evs (vec (keep event events))]
    {:items (ics-feed/items-of evs zone now)
     :keys (vec (distinct (map ics-feed/event-key evs)))}))

(defn- whole-seconds
  "An instant as the binary reads one: UTC, to the second. Instant's own
  text carries a fraction whenever it has one, which the binary refuses."
  [^Instant i]
  (str (.truncatedTo i ChronoUnit/SECONDS)))

(defn events-argv
  "The binary's arguments to read events from `from` to `to`, in the
  named calendars, or in every calendar when there are none."
  [^Instant from ^Instant to calendars]
  (into ["events" "--from" (whole-seconds from) "--to" (whole-seconds to)]
        (mapcat (fn [c] ["--calendar" c]) calendars)))

(defn window
  "The span a calendar read covers at `now`: {:from :to}."
  [^Instant now]
  {:from (.minus now look-back) :to (.plus now look-ahead)})

;; --- reminders

(defn reminder
  "One reminder the binary printed, as {:id :list :title :notes :due
  :completed :completed-at :modified}, or nil without an id and a list."
  [{:keys [id list title notes due completed completed_at modified]}]
  (when (and (present id) (present list))
    (cond-> {:id (str id) :list (str list) :title (str title) :completed (true? completed)}
      (present notes) (assoc :notes (str notes))
      (present due) (assoc :due (str/trim due))
      (present completed_at) (assoc :completed-at (str completed_at))
      (present modified) (assoc :modified (str modified)))))

(defn- digest [x]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (pr-str x) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) (take 12 d)))))

(defn fields
  "What makes a reminder the same reminder: its list, title, notes, due
  date and whether it is done. Not when it was last modified, so a touch
  that changes nothing is no news."
  [r]
  {:list (:list r) :title (str (:title r)) :notes (str (:notes r)) :due (str (:due r))
   :completed (true? (:completed r))})

(defn version [r] (digest (fields r)))

(defn poll-reminders
  "What a read of the reminders found: {:items [{:key :version :reminder}]
  :keys [every id]}. `reminders` are the binary's."
  [reminders]
  (let [rs (vec (keep reminder reminders))]
    {:items (vec (for [r rs] {:key (:id r) :version (version r) :reminder r}))
     :keys (vec (distinct (map :id rs)))}))

(defn reminders-argv [lists]
  (into ["reminders"] (mapcat (fn [l] ["--list" l]) lists)))

(defn- day-text [^LocalDate d]
  (.format d (DateTimeFormatter/ofPattern "EEE yyyy-MM-dd" java.util.Locale/ENGLISH)))

(defn due-text
  "A due date as a role reads it: a date, or a date and a local time."
  [due]
  (let [s (str/trim (str due))]
    (try (cond (re-matches date-only s) (day-text (LocalDate/parse s))
               (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}" s)
               (let [t (LocalDateTime/parse s)]
                 (str (day-text (.toLocalDate t)) " " (.format t (DateTimeFormatter/ofPattern "HH:mm"))))
               :else s)
         (catch Exception _ s))))

(defn- one-line [s n]
  (let [l (str/trim (str/replace (str s) #"(?U)\s+" " "))]
    (if (> (count l) n) (str (subs l 0 (dec n)) "…") l)))

(defn reminder-text
  "A reminder as a few lines a role reads, with its id, which the tools
  that change it take."
  [{:keys [id list title notes due completed]}]
  (str (if completed "[done] " "[open] ") (one-line title 300)
       (when (seq due) (str ", due " (due-text due)))
       " (list " (one-line list 80) ", id " id ")"
       (when (seq notes)
         (str "\n  " (str/replace (let [n (str/trim notes)] (if (> (count n) 500) (str (subs n 0 500) "…") n))
                                  "\n" "\n  ")))))

(defn reminders-text
  "The reminders in a snapshot, grouped by list in the order the lists
  are given, open ones first. `lists` are the source's lists."
  [lists snapshot]
  (let [by (group-by :list snapshot)]
    (str/join "\n\n"
              (for [l lists
                    :let [rs (get by l)
                          open (remove :completed rs)
                          done (filter :completed rs)]]
                (str "List " (one-line l 80) ": " (count open) " open"
                     (when (seq done) (str ", " (count done) " done lately"))
                     (when (seq rs) (str "\n" (str/join "\n" (map reminder-text (concat open done))))))))))

;; --- writes: what relay allows, and what it asks the binary

(defn allowed?
  "Whether the source's `lists` include `list`, exactly."
  [lists list]
  (boolean (and (string? list) (some #(= list %) lists))))

(defn- allowed-text [lists] (str/join ", " (map #(str "\"" % "\"") lists)))

(defn- clean
  "Text a role writes into a reminder, cleaned like text sent to the
  operator: hidden characters removed, and when the session holds
  outside labels, links broken."
  [labels s]
  (text/for-operator labels (:text (text/sanitize (str s)))))

(defn- clean-line [labels s]
  (str/trim (str/replace (clean labels s) #"(?U)\s+" " ")))

(def title-limit 500)
(def notes-limit 5000)

(defn due-problem
  "Why `due` is not a due date, or nil: a date (2026-10-28) or a local
  date and time (2026-10-28T09:00)."
  [due]
  (let [s (str due)]
    (when-not (try (cond (re-matches date-only s) (LocalDate/parse s)
                         (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}" s) (LocalDateTime/parse s))
                   (catch Exception _ nil))
      (str "A due date is a date such as 2026-10-28, or a date and a local time such as 2026-10-28T09:00, not "
           (pr-str (one-line s 40)) "."))))

(defn- blank-or [s] (when-not (str/blank? s) s))

(defn plan-write
  "What a write asks of the binary, or why relay refuses it, before the
  binary is called. `op` is \"add\", \"edit\", \"done\" or \"reopen\";
  `lists` the source's lists; `snapshot` its reminders as relay last
  read them; `labels` the calling session's; `args` the call's.
  {:error why} or {:id :tail :args}: :tail the binary's arguments after
  the --allow flags, :args what the log records (cleaned text, and for
  an edit only what changes), :id the reminder it is to, nil for an add."
  [op lists snapshot labels {:keys [id list title due notes] :as args}]
  (let [refuse-list (fn [l] (str "The list " (pr-str (one-line l 80)) " is not one relay may write to. It may write to "
                                 (allowed-text lists) "."))
        text-problem (fn [what s n] (when (> (count s) n) (str "The " what " is longer than " n " characters.")))]
    (case op
      "add"
      (let [t (clean-line labels title)
            n (blank-or (str/trim (clean labels notes)))]
        (cond
          (not (string? list)) {:error "Say which list the reminder goes in."}
          (not (allowed? lists list)) {:error (refuse-list list)}
          (str/blank? t) {:error "A reminder needs a title."}
          (text-problem "title" t title-limit) {:error (text-problem "title" t title-limit)}
          (and n (text-problem "notes" n notes-limit)) {:error (text-problem "notes" n notes-limit)}
          (and (not (str/blank? due)) (due-problem due)) {:error (due-problem due)}
          :else (let [due (blank-or (str due))]
                  {:id nil
                   :args (cond-> {:list list :title t} due (assoc :due due) n (assoc :notes n))
                   :tail (cond-> ["add" "--list" list "--title" t]
                           due (into ["--due" due])
                           n (into ["--notes" n]))})))

      ("edit" "done" "reopen")
      (let [r (some #(when (= (str id) (:id %)) %) snapshot)]
        (cond
          (str/blank? id) {:error "Say which reminder, by the id your reminders list shows."}
          (nil? r) {:error (str "relay has no reminder with that id. Read your reminders and use an id from there.")}
          (not (allowed? lists (:list r))) {:error (refuse-list (:list r))}
          (#{"done" "reopen"} op) {:id (:id r) :args {:id (:id r)} :tail [op (:id r)]}
          :else
          (let [t (when (some? title) (clean-line labels title))
                n (when (some? notes) (str/trim (clean labels notes)))
                clear-due? (and (some? due) (or (str/blank? due) (= "none" (str/lower-case (str/trim due)))))
                set-due? (and (some? due) (not clear-due?))]
            (cond
              (and (some? title) (str/blank? t)) {:error "A reminder needs a title."}
              (and t (text-problem "title" t title-limit)) {:error (text-problem "title" t title-limit)}
              (and n (text-problem "notes" n notes-limit)) {:error (text-problem "notes" n notes-limit)}
              (and n (= "none" (str/lower-case n))) {:error "Notes that say only \"none\" would clear the notes. Say something else, or send empty notes to clear them."}
              (and set-due? (due-problem due)) {:error (due-problem due)}
              (and (some? list) (not (allowed? lists list))) {:error (refuse-list list)}
              (and (nil? t) (nil? n) (not clear-due?) (not set-due?) (nil? list))
              {:error "Say what to change: the title, due date, notes or list."}
              :else
              (let [args (cond-> {:id (:id r)}
                           t (assoc :title t)
                           (some? n) (assoc :notes n)
                           clear-due? (assoc :due "")
                           set-due? (assoc :due due)
                           (some? list) (assoc :list list))]
                {:id (:id r) :args args
                 :tail (cond-> ["set" (:id r)]
                         t (into ["--title" t])
                         clear-due? (into ["--due" "none"])
                         set-due? (into ["--due" due])
                         (some? n) (into ["--notes" (if (str/blank? n) "none" n)])
                         (some? list) (into ["--list" list]))})))))
      {:error (str "relay has no reminder write called " op ".")})))

(defn write-argv
  "The whole argument list of a write: every list the source may write to
  as --allow, then the command (plan-write's :tail)."
  [lists tail]
  (into (vec (mapcat (fn [l] ["--allow" l]) lists)) tail))

;; --- what a write did

(defn change-name
  "What an op did to a reminder, as the change record says it."
  [op]
  (case op "add" "added" "edit" "changed" "done" "completed" "reopen" "reopened"))

(defn- differs [a b]
  (into {} (for [k [:title :notes :due :list]
                 :when (not= (str (get a k)) (str (get b k)))]
             [k (get b k)])))

(defn revert-plan
  "How to put back a change to a reminder: `change` is what the log
  records of it ({:change \"added\" | \"changed\" | \"completed\" |
  \"reopened\" :before :after}), `current` the reminder as it is now in
  its list, or nil. One of
    {:op \"done\" | \"reopen\" :id}            mark it done, or open again
    {:op \"edit\" :id :set {field value}}      put back what the edit changed
    {:noop why}                              it is already as undoing leaves it
    {:refuse why}                            relay does not touch it
  Undoing an add completes the reminder and never deletes it. Undoing an
  edit puts back the fields only while the reminder still reads as relay
  left it; if the operator changed it since, relay does not overwrite
  that."
  [{:keys [change before after]} current]
  (let [where (str "\"" (one-line (:title after) 80) "\"")]
    (cond
      (nil? current)
      {:refuse (str "The reminder " where " is not in its list any more, or was finished too long ago for"
                    " relay to find, so there is nothing to put back.")}
      (= "added" change)
      (if (:completed current)
        {:noop (str "The reminder " where " is done already.")}
        {:op "done" :id (:id current)})
      (= "completed" change)
      (if (:completed current)
        {:op "reopen" :id (:id current)}
        {:noop (str "The reminder " where " is open already.")})
      (= "reopened" change)
      (if (:completed current)
        {:noop (str "The reminder " where " is done already.")}
        {:op "done" :id (:id current)})
      (= "changed" change)
      (cond
        (not= (fields current) (fields after))
        {:refuse (str "The reminder " where " has changed since relay edited it, so relay leaves it alone."
                      " Fix it by hand: before the edit it was "
                      (let [b (fields before)]
                        (str "titled " (pr-str (one-line (:title b) 80))
                             (when (seq (:due b)) (str ", due " (due-text (:due b))))
                             " in the list " (pr-str (:list b)) "."))
                      )}
        (empty? (differs after before))
        {:noop (str "The edit of " where " changed nothing to put back.")}
        :else {:op "edit" :id (:id current) :set (differs after before)})
      :else {:refuse "relay does not know how to undo that."})))

(defn revert-tail
  "The binary's arguments (after --allow) that carry out a revert-plan."
  [{:keys [op id set]}]
  (case op
    ("done" "reopen") [op id]
    "edit" (cond-> ["set" id]
             (contains? set :title) (into ["--title" (:title set)])
             (contains? set :due) (into ["--due" (if (str/blank? (:due set)) "none" (:due set))])
             (contains? set :notes) (into ["--notes" (if (str/blank? (:notes set)) "none" (:notes set))])
             (contains? set :list) (into ["--list" (:list set)]))))

(defn snapshot-with
  "A reminders snapshot with `r` in it, replacing the reminder of its id
  or added at the end."
  [snapshot r]
  (let [snapshot (vec snapshot)
        i (first (keep-indexed (fn [i x] (when (= (:id x) (:id r)) i)) snapshot))]
    (if i (assoc snapshot i r) (conj snapshot r))))

(defn stuck-lines
  "What to tell a role about reminder calls that were recorded and never
  finished: `calls` are core's reminder calls, `now` the time. A relay
  stopped between the record before the binary ran and the one after, so
  the log does not say whether the change happened. A call under five
  minutes old may still be running."
  [calls ^Instant now]
  (vec (for [{:keys [at op args status source]} calls
             :when (and (= :changing status) (.isBefore ^Instant at (.minus now (Duration/ofMinutes 5))))]
         (str "relay stopped while it ran your " op " call on " source " at " at ", so the log does not say whether"
              " it happened. Check that reminder by hand"
              (when-let [t (or (:title args) (:id args))] (str " (" (pr-str (one-line t 80)) ")")) "."))))
