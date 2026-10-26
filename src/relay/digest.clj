(ns relay.digest
  "The daily note: one digest message a day, at the time relay.edn's
  `:daily-note {:at \"07:30\" :role \"steward\"}` names. Pure: the
  state's records, the date and the zone come in; the message's parts
  come out. relay.core records it and relay.dispatch asks for it when it
  is due.

  The note has two kinds of content. relay works out the first in code:
  every change to how a role works (a skill, a routine, a note it keeps,
  or a role itself) written since the last note by a session that had
  read something from outside, with what changed, where the outside text
  came from, and how to undo it (decision 14). No model writes or can
  leave out that section. The rest is what the role named in
  :daily-note wrote with daily_note_write before the note was made: what
  is coming up in the next two weeks with an offer of help each, grants,
  new GitHub items, and what it learned. Suggestions the operator
  dismissed are left out, and stay out.

  The note numbers what the operator can act on: a change to undo
  (\"revert 1\") and a suggestion to drop (\"not 2\"). The numbers are
  kept on the message, so a reply, the command line and the client all
  act on the same item.

  A day's note is due once its time has come that day, in the
  operator's zone, until one is recorded for that day, as the morning
  brief before it was. A note with nothing to say is recorded and not
  sent."
  (:require [clojure.string :as str]
            [relay.messages :as messages]
            [relay.text :as text])
  (:import (java.time Instant LocalDate LocalTime ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

;; --- the parts a role writes

(def parts
  "The parts of the note a role writes, in the order the note shows them."
  ["ahead" "grants" "github" "learned"])

(def part-headings
  {"ahead" "Coming up" "grants" "Grants" "github" "GitHub" "learned" "What I learned"})

(def dismissable
  "The parts whose items are suggestions the operator can turn down."
  #{"ahead" "grants"})

(def items-per-part 20)
(def text-limit 500)
(def offer-limit 200)
(def key-limit 80)

(defn- cp-count [^String s] (.codePointCount s 0 (count s)))

(defn slug
  "A string as a key: lower case letters and digits, runs of anything
  else one hyphen, none at either end, at most key-limit long."
  [s]
  (let [k (-> (str/lower-case (str s))
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"^-+|-+$" ""))
        k (if (> (count k) key-limit) (str/replace (subs k 0 key-limit) #"-+$" "") k)]
    (if (str/blank? k) "item" k)))

(defn item-keys
  "The keys a suggestion is known by: the one its writer gave, if any,
  and one made from its text, each under its part. Dismissing it records
  both, and a later suggestion with either is left out."
  [part {:keys [key text]}]
  (vec (distinct (remove nil? [(when-not (str/blank? key) (str part "/" (slug key)))
                               (str part "/" (slug text))]))))

(defn dismissed?
  "Whether the operator turned down a suggestion like this one."
  [dismissed part item]
  (boolean (some #(contains? dismissed %) (item-keys part item))))

(defn part-problem
  "What is wrong with a part a role writes, or nil."
  [part items]
  (cond
    (not (some #{part} parts)) (str "part is one of " (str/join ", " parts) ".")
    (not (sequential? items)) "items is a list of {text, offer, key}."
    (> (count items) items-per-part) (str "A part has at most " items-per-part " items.")
    (not (every? map? items)) "Each item is {text, offer, key}."
    (some #(seq (remove #{:text :offer :key} (keys %))) items) "An item has only text, offer and key."
    (some #(or (not (string? (:text %))) (str/blank? (:text %))) items) "Each item needs its text."
    (some #(> (cp-count (:text %)) text-limit) items) (str "An item's text is at most " text-limit " characters.")
    (some #(and (some? (:offer %)) (not (string? (:offer %)))) items) "An offer is text."
    (some #(> (cp-count (str (:offer %))) offer-limit) items) (str "An offer is at most " offer-limit " characters.")
    (some #(and (some? (:key %)) (not (string? (:key %)))) items) "A key is text."))

(defn clean-items
  "A part's items as kept: text made one line, an offer only when there
  is one, a key only when given."
  [items]
  (vec (for [{:keys [text offer key]} items]
         (cond-> {:text (messages/squeeze-line text)}
           (not (str/blank? offer)) (assoc :offer (messages/squeeze-line offer))
           (not (str/blank? key)) (assoc :key (slug key))))))

;; --- when

(defn at-time
  "When the note is made each day, or nil when relay.edn has no :daily-note."
  ^LocalTime [config]
  (messages/parse-hhmm (get-in config [:daily-note :at])))

(defn role-of [config] (get-in config [:daily-note :role]))

(defn slot ^Instant [^LocalDate date ^LocalTime at ^ZoneId zone]
  ;; A time a clock change skips moves forward by the gap.
  (.toInstant (ZonedDateTime/of date at zone)))

(defn due-date
  "The date whose note is due at `now`, or nil: today's, in `zone`, once
  its time has come and when `last`, the date of the last note recorded,
  is earlier than today or nil. A day that ends before any tick past its
  time gets no note: the next day's says what that one would have."
  [^LocalTime at ^ZoneId zone ^Instant now ^LocalDate last]
  (when at
    (let [today (.toLocalDate (.atZone now zone))]
      (when (and (or (nil? last) (.isAfter today last))
                 (not (.isBefore now (slot today at zone))))
        today))))

(defn settings-problem
  "What is wrong with relay.edn's :daily-note, or nil. `public?` says
  whether a role is public: the note's writer reads the operator's
  mail and board, which a public role never sees."
  [daily-note public?]
  (let [{:keys [at role]} daily-note]
    (cond
      (nil? daily-note) nil
      (not (and (map? daily-note) (every? #{:at :role} (keys daily-note))
                (messages/parse-hhmm at)))
      (str "relay.edn's :daily-note is {:at \"07:30\" :role \"steward\"}: the time of day as HH:MM, and the"
           " role that writes the note's suggestions, not " (pr-str daily-note) ".")
      (not (and (string? role) (re-matches #"[a-z][a-z0-9-]{0,39}" role)))
      "relay.edn's :daily-note needs :role, the role that writes the note's suggestions, such as \"steward\"."
      (public? role)
      (str "relay.edn's :daily-note names " role ", a public role. The note's writer reads your mail and board,"
           " so it is a role that is not public."))))

;; --- changes to how a role works

(def ^:private source-pattern #"[a-z]+:.+")

(defn outside?
  "Whether labels say a session read something from outside: text
  marked contaminated, or a label naming an outside source (mail:,
  github:, ics:)."
  [labels]
  (boolean (some #(or (= "contaminated" %) (and (string? %) (re-matches source-pattern %))) labels)))

(defn- source-words [label]
  (let [[kind what] (str/split label #":" 2)]
    (case kind
      "mail" (cond (= "unverified-operator" what) "a mail that says it is from you, not verified"
                   (= "unknown" what) "a mail from an unknown sender"
                   :else (str "a mail that says it is from " (text/defang what)))
      "github" (str "GitHub, " what)
      "ics" (str "the calendar " what)
      nil)))

(defn source-phrase
  "Where the outside text a change was shaped by came from, in plain
  words: each source label's, or \"something from outside\" when no
  label says more, as from the web."
  [labels]
  (let [words (vec (keep source-words (sort (distinct (filter #(and (string? %) (re-matches source-pattern %)) labels)))))]
    (case (count words)
      0 "something from outside"
      1 (first words)
      2 (str (first words) " and " (second words))
      (str (first words) ", " (second words) " and " (- (count words) 2) " more"))))

(defn line-delta
  "How many lines `after` has that `before` lacks, and the reverse,
  counting repeats."
  [before after]
  (let [lines (fn [s] (frequencies (when-not (str/blank? s) (str/split-lines s))))
        b (lines before) a (lines after)
        more (fn [x y] (reduce + 0 (for [[l n] x] (max 0 (- n (get y l 0))))))]
    {:added (more a b) :removed (more b a)}))

(defn- lines-text [{:keys [added removed]}]
  (let [n (fn [k w] (str k " line" (when-not (= 1 k) "s") " " w))]
    (cond (and (pos? added) (pos? removed)) (str " (" (n added "added") ", " removed " removed)")
          (pos? added) (str " (" (n added "added") ")")
          (pos? removed) (str " (" (n removed "removed") ")")
          :else "")))

(defn- thing [{:keys [what name labels]}]
  ;; A name chosen in a session that read outside text may be that text's
  ;; choice: a domain in it must not become a link.
  (let [name (if (outside? labels) (text/defang name) name)]
   (case what
    "skill" (str "how-to \"" name "\"")
    "routine" (str "routine \"" name "\"")
    "note" (str "note \"" name "\"")
    "role" (str "helper \"" name "\""))))

(defn change-line
  "One line saying what a change did, in plain words."
  [{:keys [change delta schedule] :as c}]
  (str (case change
         "added" (str "New " (thing c))
         "changed" (str "Changed the " (thing c))
         "removed" (str "Removed the " (thing c)))
       (when (and delta (not= "removed" change)) (lines-text delta))
       (when schedule (str ", now " schedule))))

(defn flagged
  "The changes to check: those after event `since` that a role (never
  the operator) wrote from a session that had read something from
  outside, oldest first."
  [changes since]
  (vec (filter #(and (> (:seq %) (or since 0))
                     (not (#{"operator" "dispatcher"} (:actor %)))
                     (outside? (:labels %)))
               changes)))

(defn quiet
  "The changes a role made since event `since` from nothing but the
  operator's words and its own work: what it learned."
  [changes since]
  (vec (filter #(and (> (:seq %) (or since 0))
                     (not (#{"operator" "dispatcher"} (:actor %)))
                     (not (outside? (:labels %))))
               changes)))

;; --- the note

(def ^:private date-format (DateTimeFormatter/ofPattern "EEEE d MMMM" Locale/ENGLISH))

(defn- plural [n w] (str n " " w (when-not (= 1 n) "s")))

(def flags-shown
  "The most changes the note lists one by one, within a message's
  limits; it counts the rest, which relay changes lists."
  40)

(defn compose
  "The note for `date`: {:subject :sections :numbered :flagged}, or nil
  sections when there is nothing to say. `changes` are the recorded
  changes to how roles work, `since` the event of the last note,
  `written` the parts written since, {part {:items :labels}}, and
  `dismissed` the keys of suggestions the operator turned down.
  :numbered is what each number in the note stands for:
  {:n :op \"revert\" :change seq :text} or {:n :op \"dismiss\" :part
  :keys :text}."
  [{:keys [^LocalDate date changes since written dismissed]}]
  (let [flags (flagged changes since)
        n (atom 0)
        numbered (atom [])
        number! (fn [entry] (let [k (swap! n inc)] (swap! numbered conj (assoc entry :n k)) k))
        shown-flags (take flags-shown flags)
        flag-items (vec (for [c shown-flags]
                          (let [k (number! {:op "revert" :change (:seq c) :text (change-line c)})]
                            {:text (str k ". " (change-line c) ", shaped by " (source-phrase (:labels c))
                                        ". To undo it: relay revert " (:seq c) ", or reply \"revert " k "\".")
                             :action {:op "revert" :n k}})))
        flag-items (cond-> flag-items
                     (> (count flags) flags-shown)
                     (conj {:text (str "And " (plural (- (count flags) flags-shown) "more change")
                                       " like these. relay changes lists them all.")}))
        part-section
        (fn [part]
          (let [{:keys [items labels]} (get written part)
                items (cond->> items (dismissable part) (remove #(dismissed? dismissed part %)))]
            (when (seq items)
              {:heading (part-headings part)
               :items (vec (for [{:keys [text offer] :as it} items]
                             (if (dismissable part)
                               (let [k (number! {:op "dismiss" :part part :keys (item-keys part it) :text text :labels (vec labels)})]
                                 {:text (str k ". " text (when offer (str " " offer)))
                                  :labels labels :action {:op "dismiss" :n k}})
                               {:text (str text (when offer (str " " offer))) :labels labels})))})))
        sections (vec (concat
                       (when (seq flag-items) [{:heading "Changes to check" :items flag-items}])
                       (keep part-section parts)))
        dismiss? (some #(= "dismiss" (:op %)) @numbered)
        sections (cond-> sections
                   dismiss? (conj {:items [{:text "Reply \"not N\" to drop a suggestion. It will not come back."}]}))]
    {:subject (str "Daily note for " (.format date-format date)
                   (when (seq flags) (str ": " (plural (count flags) "change") " to check")))
     :sections (when (seq (mapcat :items sections)) sections)
     :numbered @numbered
     :flagged (mapv :seq flags)}))

;; --- what the operator says back

(def ^:private act-words
  {"revert" "revert" "undo" "revert" "not" "dismiss" "dismiss" "dismiss" "drop" "dismiss" "skip" "dismiss"})

(defn reply-acts
  "What a reply to a note asks for, as [{:op \"revert\"|\"dismiss\" :n}],
  in order: lines such as \"revert 1\", \"undo 1 and 3\", \"not 2\",
  \"dismiss 2, 4\". A reply that is only \"not this one\" (or \"not
  this\") turns down the one suggestion when the note has exactly one.
  Anything else is left to whoever reads the reply."
  [text numbered]
  (let [lines (map str/trim (str/split-lines (str text)))
        acts (vec (for [l lines
                        :let [[_ w nums] (re-matches #"(?i)(revert|undo|not|dismiss|drop|skip)\s+#?(\d+(?:\s*(?:,|and|&)\s*#?\d+)*)\s*[.!]?" l)]
                        :when w
                        num (re-seq #"\d+" nums)
                        :let [k (parse-long num)]
                        :when (and k (< k 1000000))]
                    {:op (act-words (str/lower-case w)) :n k}))
        dismissals (filter #(= "dismiss" (:op %)) numbered)]
    (cond
      (seq acts) (vec (distinct acts))
      (and (re-matches #"(?i)\s*not\s+this(\s+one)?\s*[.!]?\s*" (str/join " " (remove str/blank? lines)))
           (= 1 (count dismissals)))
      [{:op "dismiss" :n (:n (first dismissals))}]
      :else [])))

;; --- for the command line

(defn parse-change-ref
  "The change a ref names, as its number: 123, change:123 or
  [[change:123]]. Nil for anything else."
  [s]
  (some-> (re-matches #"\s*(?:\[\[)?(?:change:)?(\d{1,18})(?:\]\])?\s*" (str s)) second parse-long))

(defn changes-lines
  "The latest `n` changes to how roles work, newest last, one line each:
  its number, when, what it did, and where outside text came from when
  some shaped it."
  [changes reverted ^ZoneId zone n]
  (let [fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")]
    (if (empty? changes)
      ["No helper has changed how it works yet."]
      (vec (for [{:keys [seq at labels actor] :as c} (take-last n changes)]
             (str seq "  " (.format fmt (.atZone (Instant/parse (str at)) zone)) "  " (change-line c)
                  (when (= "operator" actor) ", by you")
                  (when (and (not (#{"operator" "dispatcher"} actor)) (outside? labels))
                    (str ", shaped by " (source-phrase labels)))
                  (when (contains? reverted seq) ", undone")))))))
