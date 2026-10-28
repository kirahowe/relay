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
    [--allow T]... set ID [--title T] [--due D | --clear-due] [--notes N | --clear-notes] [--list T]
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
            [relay.provenance :as provenance]
            [relay.text :as text])
  (:import (java.security MessageDigest)
           (java.time Duration Instant LocalDate LocalDateTime ZoneId)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)
           (java.text Normalizer Normalizer$Form)))

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
                clear-due? (and (some? due) (str/blank? due))
                set-due? (and (some? due) (not clear-due?))]
            (cond
              (and (some? title) (str/blank? t)) {:error "A reminder needs a title."}
              (and t (text-problem "title" t title-limit)) {:error (text-problem "title" t title-limit)}
              (and n (text-problem "notes" n notes-limit)) {:error (text-problem "notes" n notes-limit)}
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
                         clear-due? (into ["--clear-due"])
                         set-due? (into ["--due" due])
                         (some? n) (into (if (str/blank? n) ["--clear-notes"] ["--notes" n]))
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

(declare provenance-of-texts)

(def undo-field-names [:list :title :notes :due :completed])

(defn- same-value? [k x y]
  (if (= :completed k) (= (boolean x) (boolean y)) (= (str x) (str y))))

(defn undo-fields
  "The fields a change set, each with the value it had before and after:
  {field {:before :after}}. An edit, a completion or a reopening set the
  fields whose values differ between its images. An add set nothing it
  could put back except that the reminder is open: undoing it completes
  the reminder, as relay never deletes one."
  [{:keys [before after]}]
  (let [a (fields after)]
    (if (nil? before)
      {:completed {:before true :after (:completed a)}}
      (let [b (fields before)]
        (into {} (for [k undo-field-names :when (not (same-value? k (get b k) (get a k)))]
                   [k {:before (get b k) :after (get a k)}]))))))

(defn- outside-text? [outside k text]
  (and (#{:title :notes} k)
       (some #(not= "private" %) (:labels (provenance-of-texts outside [text])))))

(defn- field-name [k] (case k :due "due date" :list "list" :completed "done state" (name k)))

(defn- and-join [xs]
  (case (count xs) 0 "" 1 (first xs) (str (str/join ", " (butlast xs)) " and " (last xs))))

(defn revert-plan
  "How to undo a change to a reminder, field by field, with no history.
  `change` is {:before :after} as the log records it, `current` the reminder
  as the list holds it now (nil if gone), `outside` the set of outside
  texts (core/reminder-outside) and `lists` the lists relay may write to.
  For each field the change set:
    the reminder already holds the before value  -> nothing to do
    it holds the change's after value            -> put the before value back,
                                                    unless that is outside text
                                                    or a list relay may not write
    anything else (the operator changed it)      -> leave it alone, and say so
  Fields the change did not set are never touched. One of
    {:steps [{:op \"edit\" :set {field value}} {:op \"done\" | \"reopen\"}]
     :left [lines] :summary line}   writes the fields it can; :left says what it did not
    {:noop why :left [lines]}        nothing to write (and the change counts as undone)
    {:refuse why}                    nothing it may write, and some field changed since
                                     or is outside text: the change stays not undone"
  [{:keys [before after] :as change} current {:keys [outside lists]}]
  (let [where (str "\"" (one-line (:title after) 80) "\"")
        uf (undo-fields change)
        cur (fields current)
        verdicts (into {} (for [[k {b :before a :after}] uf]
                            [k (cond
                                 (same-value? k (get cur k) b) :at-before
                                 (not (same-value? k (get cur k) a)) :changed-since
                                 (outside-text? outside k (str b)) :withheld
                                 (and (= :list k) (not (allowed? lists b))) :not-allowed
                                 :else :restore)]))
        of (fn [v] (filter #(= v (verdicts %)) undo-field-names))
        restores (of :restore)
        left (vec (concat
                   (when-let [ks (seq (of :changed-since))]
                     [(str "Left alone because you changed " (if (next ks) "them" "it") " since: " (and-join (map field-name ks)) ".")])
                   (for [k (of :withheld)
                         :let [srcs (:sources (provenance-of-texts outside [(str (get-in uf [k :before]))]))]]
                     (str "Its earlier " (field-name k) " came from "
                          (text/defang (or (provenance/summary srcs) "something from outside"))
                          ", so relay won't put it back; change it by hand if you want it."))
                   (for [k (of :not-allowed)]
                     (str "Its earlier " (field-name k) " is not a list relay may write to, so it stays."))))]
    (cond
      (nil? current)
      {:refuse (str "The reminder " where " is not in its list any more, or was finished too long ago for"
                    " relay to find, so there is nothing to put back.")}
      (empty? uf) {:noop (str "The change to " where " set nothing to put back.")}
      (and (empty? restores) (empty? left))
      {:noop (str "The reminder " where " is already as undoing the change leaves it.") :left []}
      (empty? restores)
      (if (seq (of :at-before))
        {:noop (str "Nothing was written to " where ": what relay may put back is back already.") :left left}
        {:refuse (str "Nothing of the change to " where " could be undone. " (str/join " " left)
                      (when (seq (of :changed-since)) " Fix it by hand."))})
      :else
      {:steps (vec (concat
                    (when-let [ks (seq (remove #{:completed} restores))]
                      [{:op "edit" :id (:id current) :set (into {} (for [k ks] [k (get-in uf [k :before])]))}])
                    (when (some #{:completed} restores)
                      [{:op (if (get-in uf [:completed :before]) "done" "reopen") :id (:id current)}])))
       :left left
       :summary (if (= [:completed] (vec restores))
                  (if (get-in uf [:completed :before]) (str "Marked " where " as done.") (str "Opened " where " again."))
                  (str "Put back the " (and-join (map field-name restores)) " of " where "."))})))

(defn revert-tail
  "The binary's arguments (after --allow) that carry out a revert-plan."
  [{:keys [op id set]}]
  (case op
    ("done" "reopen") [op id]
    "edit" (cond-> ["set" id]
             (contains? set :title) (into ["--title" (:title set)])
             (contains? set :due) (into (if (str/blank? (:due set)) ["--clear-due"] ["--due" (:due set)]))
             (contains? set :notes) (into (if (str/blank? (:notes set)) ["--clear-notes"] ["--notes" (:notes set)]))
             (contains? set :list) (into ["--list" (:list set)]))))

;; --- where reminder text came from
;;
;; Reminders are the operator's, so what the lists hold reads as private
;; only. But text a role wrote from a session that had read outside text is
;; still that text's, however long it sits in a list. relay keeps one
;; monotonic set: every title or notes text such a session asked to write,
;; recorded before the binary runs (reminders/changing), mapped to the
;; outside labels and sources of the write. Nothing leaves the set: not an
;; undo, a clean edit, a failure or a completion. A text relay shows to a
;; role is outside exactly when it is in the set, wherever it appears, in
;; whichever source's snapshot, after a crash or a move. Only the operator
;; changing the words takes them out of reach. Text she happens to type
;; that matches stays outside, which is the safe side.

(defn- outside-label? [l]
  (and (string? l) (or (= "contaminated" l) (boolean (re-matches #"[a-z]+:.+" l)))))

(defn- trimmed
  "The key a text has in the set of outside texts, or nil when blank. It
  is folded (compatibility normalization, lower case, every run of
  whitespace one space) so that text stored a little differently from
  what relay sent, such as with other line ends or composed accents,
  still matches. Folding only ever matches more, never less."
  [s]
  (let [t (-> (Normalizer/normalize (str s) Normalizer$Form/NFKC)
              (str/lower-case)
              (str/replace #"(?U)\s+" " ")
              str/trim)]
    (when-not (str/blank? t) t)))

(defn note-outside
  "The set of outside texts (`outside`, {text {:labels :sources}}) after a
  write recorded with these session `labels` and `sources` asked for
  `args` (its :title and :notes, as sent). A session that held no outside
  label adds nothing."
  [outside labels sources args]
  (let [ls (vec (filter outside-label? labels))]
    (if (empty? ls)
      (or outside {})
      (reduce (fn [m t]
                (update m t (fn [e] {:labels (vec (sort (distinct (concat (:labels e) ls))))
                                     :sources (vec (sort (distinct (concat (:sources e) sources))))})))
              (or outside {})
              (keep trimmed [(:title args) (:notes args)])))))

(defn provenance-of-texts
  "What showing these `texts` carries: private, and the labels and sources
  of each that is in the `outside` set. The one function every display of
  reminder text goes through. {:labels :sources}."
  [outside texts]
  (let [hits (keep #(get outside (trimmed %)) texts)]
    {:labels (vec (sort (distinct (cons "private" (mapcat :labels hits)))))
     :sources (vec (sort (distinct (mapcat :sources hits))))}))

(defn reminder-texts
  "The words of a reminder a role is shown: its title and notes."
  [r]
  [(:title r) (:notes r)])

(defn shown-provenance
  "provenance-of-texts for the words of `reminders`."
  [outside reminders]
  (provenance-of-texts outside (mapcat reminder-texts reminders)))

(defn snapshot-with
  "A reminders snapshot with `r` in it, replacing the reminder of its id
  or added at the end."
  [snapshot r]
  (let [snapshot (vec snapshot)
        i (first (keep-indexed (fn [i x] (when (= (:id x) (:id r)) i)) snapshot))]
    (if i (assoc snapshot i r) (conj snapshot r))))

;; --- what a write did is decided by reading, and only what relay can attribute is recorded
;;
;; A write's record holds only the fields the call asked to set, and each only
;; if a read shows it now holding the requested value. Every other field is
;; the operator's whatever the read shows, so it is never in the record and an
;; undo's per-field compare-and-swap can never touch it. An add is attributed
;; by the id the binary reported and a read confirms, never by its title.

(defn requested-fields
  "What a call asked to set, as {field value}: an edit's title, notes, due
  date and list (an empty notes or due clears it); done and reopen, whether
  the reminder is done. An add is attributed by id, not by fields."
  [op args]
  (case op
    "edit" (into {} (for [k [:title :notes :due :list] :when (contains? args k)] [k (get args k)]))
    "done" {:completed true}
    "reopen" {:completed false}
    {}))

(defn- with-field [r k v]
  (if (and (#{:notes :due} k) (str/blank? (str v))) (dissoc r k) (assoc r k v)))

(defn attribute-fields
  "What a read shows of a change to a reminder: `pre` is the reminder as
  read before, `requested` the {field value} the change asked for, `post`
  the reminder as read now (nil if it cannot be found). One of
    {:status :changed :before pre :after pre-with-the-confirmed-fields :confirmed [field ...]}
    {:status :failed}      none of the requested values is there
    {:status :gone}        no such reminder now
  The after-image is the pre-read with only the requested fields that hold
  their requested values replaced."
  [pre requested post]
  (if (nil? post)
    {:status :gone}
    (let [ok (filterv (fn [[k v]] (same-value? k (get post k) v)) requested)]
      (if (seq ok)
        {:status :changed :before pre :confirmed (mapv first ok)
         :after (reduce (fn [r [k v]] (with-field r k v)) pre ok)}
        {:status :failed}))))

(defn- add-image [args hit]
  (cond-> {:id (:id hit) :list (:list hit) :title (:title hit) :completed false}
    (and (seq (str (:notes args))) (= (str (:notes args)) (str (:notes hit)))) (assoc :notes (:notes hit))
    (and (seq (str (:due args))) (= (str (:due args)) (str (:due hit)))) (assoc :due (:due hit))))

(defn attribute
  "What a successful reminder write did, from the images the binary itself
  printed, and only what the call asked for. `call` has :op and :args;
  `before` and `after` are the binary's images (before is nil for an add).
  edit, done, reopen: attribute-fields of the requested fields. add: the
  reminder the binary reported, by its id:
    {:status :changed :before nil :after image}, or {:status :failed} with no id."
  [{:keys [op args]} before after]
  (if (= "add" op)
    (if (:id after)
      {:status :changed :before nil :after (add-image args after)}
      {:status :failed})
    (attribute-fields before (requested-fields op args) after)))
