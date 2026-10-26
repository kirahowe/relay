(ns relay.render
  "What the relay command shows. Pure: each function takes the data to
  show and returns lines of text, which relay.cli prints, each through
  text/visible-line.

  One rule keeps a role's or an outsider's text from passing for the
  screen's own: it never decides where a line starts. A one-line field is
  part of a line the renderer writes, and any line break in it is escaped
  where it is printed. Text that may run to several lines is shown with
  text/block: every line behind text/gutter, which nothing else starts
  with. Text an outsider may have written has its links broken. Where a
  thing came from is said once, in words (relay.provenance), at the end
  of its line. Nothing here names a label or a class."
  (:require [cheshire.core :as json]
            [clojure.walk]
            [clojure.string :as str]
            [relay.cards :as cards]
            [relay.config :as config]
            [relay.core :as core]
            [relay.digest :as digest]
            [relay.provenance :as provenance]
            [relay.spend :as spend]
            [relay.text :as text]
            [relay.week :as week])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)))

(defn local
  "An instant as the operator's local time, or nil."
  [^Instant at zone]
  (when at (.format (.atZone at ^ZoneId zone) (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))))

(defn when*
  "An instant as the operator's local time, as a bare date when it is
  midnight there, or nil."
  [^Instant at zone]
  (when at
    (let [z (.atZone at ^ZoneId zone)]
      (if (= java.time.LocalTime/MIDNIGHT (.toLocalTime z))
        (str (.toLocalDate z))
        (local at zone)))))

(defn- came-from
  "Where a memo came from, in words: what brought it in, for one an
  ingestor brought (relay.provenance), else who sent it."
  [state {:keys [id from labels]}]
  (let [ws (provenance/phrases (core/sources-of state id labels))]
    (if (and (= core/dispatcher from) (seq ws))
      (let [w (str/join ", " ws)] (str (str/upper-case (subs w 0 1)) (subs w 1)))
      (str "from " (if (= core/operator from) "you" from)))))

(defn memo
  "One memo, as the operator's inbox shows it: its subject, where it came
  from and when, and its text behind the gutter, links broken when an
  outsider may have written it."
  [state zone {:keys [id subject body sent-at labels] :as m}]
  (concat
   ["" (str "[" id "] " (text/for-operator labels subject))
    (str "    " (came-from state m) ", " (local sent-at zone))]
   ;; Refs read as words, as in every message.
   (text/block labels (text/refs-as-words body))))

(defn inbox
  [state zone memos]
  (if (empty? memos)
    ["Nothing waiting."]
    (mapcat #(memo state zone %) memos)))

(declare wrap)

(def status-width
  "The widest line relay status writes for a memo: a phone-width
  terminal."
  60)

(def ^:private source-indent "            ")

(defn- names-source?
  "Whether a memo's subject, as the status line shows it (`shown`, cut to
  fit), already names where it came from: it has one source, and starts
  with the words relay itself titles that source's memos with
  (provenance/subject-prefix), shown whole. What an outsider wrote later
  in a subject never counts."
  [shown srcs]
  (let [prefix (when (= 1 (count srcs)) (provenance/subject-prefix (first srcs)))]
    (boolean (and prefix
                  (str/starts-with? (str/lower-case (str shown)) (str/lower-case prefix))))))

(defn- memo-source-lines
  "The lines after a status line for a memo: where its text came from,
  when an outsider wrote any of it and the subject as shown (`shown`)
  does not already say so, on lines of their own below it, indented and
  wrapped to fit."
  [state {:keys [id labels]} shown]
  (let [srcs (core/sources-of state id labels)]
    (when-let [s (and (not (names-source? shown srcs)) (provenance/summary srcs))]
      (map #(str source-indent %) (wrap (str "from " s) (- status-width (count source-indent)))))))

(defn- cut-subject
  "A memo's subject as a status line shows it after `head` and before
  `tail`: cut to fit."
  [head subject tail]
  (let [room (max 20 (- status-width (count head) (count tail)))
        subject (str subject)]
    (if (> (count subject) room) (str (subs subject 0 (dec room)) "…") subject)))

(defn- open-memo [state zone {:keys [id subject attempts retry-at labels] :as m}]
  (let [head (format "      open  [%d] " id)
        tail (cond-> ""
               (pos? attempts) (str " (" attempts " failed attempts")
               (and (pos? attempts) retry-at) (str ", next " (local retry-at zone))
               (pos? attempts) (str ")"))
        shown (cut-subject head subject tail)]
    (map #(text/for-operator labels %)
         (cons (str head shown tail) (memo-source-lines state m shown)))))

(defn- role-line-labels [state id]
  (core/union (core/role-labels state id) (core/id-labels state (:reports-to (core/role state id)))))

(defn- activity
  "What a role is doing, after its line: working through its memos, in
  a conversation with the operator, or both."
  [working conversation]
  (str (when working "  [working]")
       (when conversation (if (core/in-turn? conversation) "  [in a conversation turn]" "  [conversation open]"))))

(defn- recipe-mark
  "The end of a listing's line for a recipe (a routine or a skill) whose
  latest version came from a wake that read outside text directly: a
  short mark of what kind of thing it was (relay.provenance/mark), so
  the operator can see where a recipe came from (decision 14)."
  [state seq labels]
  (some->> (provenance/mark (core/sources-of state seq labels)) (str " ")))

(defn- role-status [state zone now id]
  (let [{:keys [title reports-to]} (core/role state id)
        id-ls (core/id-labels state id)
        unavailable (get-in state [:roles id :tools-unavailable])
        dead (core/dead-memos state id)]
      (concat
       [(text/for-operator (role-line-labels state id)
                           (format "  %-20s %s%s%s" id title
                                   (if reports-to (str ", reports to " reports-to) "")
                                   (activity (core/working-wake state id) (core/open-conversation state id))))]
       (for [{:keys [name schedule labels version]} (core/active-routines state id)]
         (text/for-operator (core/union labels id-ls) (str (format "      routine %-16s %s" name schedule)
                                                           (recipe-mark state version labels))))
       (mapcat #(map (fn [l] (text/for-operator id-ls l)) (open-memo state zone %)) (core/unread-memos state id))
       (mapcat (fn [{memo-id :id :keys [subject labels] :as m}]
                 (let [head (format "      dead  [%d] " memo-id)
                       shown (cut-subject head subject "")]
                   (map #(text/for-operator (core/union labels id-ls) %)
                        (cons (str head shown) (memo-source-lines state m shown)))))
               dead)
       ;; Retrying cannot help while its tools are out, so it is only
       ;; offered once they are back.
       (when (and (seq dead) (not unavailable))
         [(text/for-operator id-ls (str "      relay retry " id " tries the set-aside memos again"))])
       (when-let [{:keys [server tool retry-at]} unavailable]
         [(text/for-operator id-ls
                             (str "      tools unavailable"
                                  (cond tool (str ": " tool) server (str ": server " server))
                                  (if (and retry-at (.isAfter ^Instant retry-at now))
                                    (str ", next try " (local retry-at zone))
                                    ", tried again at the next tick")))]))))

(defn- dollars
  "An amount in US dollars to the cent. One above zero that rounds to
  nothing is shown as under a cent, so a priced wake never reads as free."
  [x]
  (let [x (double x)]
    (if (and (pos? x) (< x 0.005)) "<$0.01" (format "$%.2f" x))))

(def spend-weeks
  "How many weeks `relay status` shows spend for: this one and the ones
  before it."
  5)

(defn- paragraph
  "Text for relay status, as lines that fit status-width: each line of
  `s` wrapped, except one that starts indented, such as a command to
  copy, which is kept whole on its own line."
  [s]
  (mapcat (fn [l] (if (re-find #"^\s" l) [l] (wrap l status-width))) (str/split-lines (str s))))

(defn spend
  "This week's spend per role and the total, against the weekly budget if
  there is one, and the total of each week before since the log began.
  `weeks` is relay.spend/weekly's result, newest first. A role's id
  carries its labels. `zone` is the operator's."
  [state zone weeks budget]
  (let [[{:keys [week roles cost-usd unpriced]} & earlier] weeks
        ;; The week the log began, as spend/weekly counts weeks: in the
        ;; operator's zone.
        began (some-> ^Instant (:first-at state) (spend/week-of zone))
        ;; An empty log has no earlier weeks.
        earlier (filter #(and began (not (.isBefore ^java.time.LocalDate (:week %) began))) earlier)
        without (fn [n] (when (pos? n) (str ", " n (if (= 1 n) " wake" " wakes") " without a cost")))]
    (concat
     ["" (str "Spend this week, from Monday " week)]
     (for [[role {c :cost-usd n :unpriced}] (sort-by key roles)]
       (text/for-operator (core/id-labels state role)
                          (format "  %-20s %s%s" role (dollars c) (str (without n)))))
     [(str (format "  %-20s %s" "total" (dollars cost-usd))
           (when budget (str " of a " (dollars budget) " weekly budget"))
           (without unpriced))]
     (when (seq earlier)
       (map #(str "  " %)
            (wrap (str "earlier weeks: " (str/join ", " (for [w earlier] (str (:week w) " " (dollars (:cost-usd w))))))
                  (- status-width 2)))))))

(def tick-overdue-minutes
  "How long after the last tick relay status says the tick seems
  stopped. The tick records itself at most every few minutes
  (relay.cli), so this is well past that."
  20)

(defn setup-notices
  "What relay status says about how relay is set up, apart from relay.edn
  itself: a tick that has never run or seems stopped (`last-tick`, the
  instant the tick last recorded, or nil; nothing while `paused?`, when
  a paused tick records nothing and the pause says why, or with
  `no-log?`), and a mailbox whose replies can never count as the
  operator's for want of :authserv-id. Each a paragraph, a command on its
  own indented line. Pure."
  [config {:keys [last-tick ^Instant now paused? no-log?]}]
  (concat
   (when-not (or paused? no-log?)
     (cond
       (nil? last-tick)
       [(str "The tick has never run, so no role is woken. Install its launchd job"
             " (README: Setup, step 4).")]
       (.isBefore ^Instant last-tick (.minusSeconds now (* 60 tick-overdue-minutes)))
       [(str "The last tick was " (quot (.toMinutes (java.time.Duration/between ^Instant last-tick now)) 1)
             " minutes ago, so no role is woken. Check that its launchd job is loaded:\n"
             "  launchctl print gui/$(id -u)/relay.tick")]))
   (for [[n {:keys [kind authserv-id]}] (sort-by (comp str key) (:ingest config))
         :when (and (= :pop3 kind) (str/blank? authserv-id))]
     (str "Your replies by mail will not count as yours: the mailbox " n " has no :authserv-id."
          " Open a mail the agent's address received, find the line Authentication-Results: <id>; ...,"
          " and set :authserv-id \"<id>\" on " n ". Until then, reply with relay reply or from the"
          " dashboard."))))

(defn status
  "Roles with their routines and open work, harnesses, the operator's
  inbox, what the roles spent this week and the weeks before, and the
  latest wakes, as of `now`. `paused` is the lines to show
  when relay is paused (relay.pause/recovery-lines), or nil, and
  `notices` lines about the house from its edges, such as a shell with no
  image."
  [state zone ^Instant now & [config paused notices {:keys [no-log?]}]]
  (concat
   (when paused
     (concat paused [""]))
   ["Roles"]
   (cond
     ;; A directory with only relay.edn, such as the one an upgrade
     ;; checks a new file in: init is not what it needs.
     no-log?
     (map #(str "  " %) (wrap (str "relay.edn is valid. This directory has no log, so role names are"
                                   " checked once the file is in place.")
                              (- status-width 2)))
     (empty? (core/active-role-ids state))
     ;; One hint: relay init adds the steward to a log with no roles,
     ;; and works from wherever relay is installed.
     ["  No roles yet. relay init adds your steward."]
     :else
     (mapcat #(role-status state zone now %) (core/active-role-ids state)))
   (when (and config (seq (core/active-role-ids state)))
     (for [id (sort (keys (:grants config)))
           :when (not (core/role state id))]
       (str "  relay.edn grants " id ", but there is no " id " role. Add it: relay add " id
            (when (get-in config [:grants id :public]) " --public") " < its instructions")))
   (mapcat (fn [line] (cons "" (paragraph line)))
    (for [[h {:keys [condition paused-until said]}] (sort-by key (:harnesses state))]
     (str "Harness " h ": "
          (if (and paused-until (.isAfter ^Instant paused-until now))
            (str condition ", next try " (local paused-until zone)
                 ;; One line; what it said is the harness's or relay's own words.
                 (when-not (str/blank? said)
                   (let [l (str/trim (str/replace (str said) #"\s+" " "))]
                     (str ". " (if (> (count l) 160) (str (subs l 0 160) "...") l)))))
            "available"))))
   (mapcat (fn [line] (cons "" (paragraph line)))
           (distinct (keep (fn [[_ problem]] (get-in core/setup-problems [problem :status]))
                           (sort-by key (:tools-broken state)))))
   ;; A setting naming the steward by an id no role has, said plainly.
   (mapcat (fn [line] (cons "" (paragraph line)))
           (when config (config/role-name-problems config (core/active-role-ids state))))
   (mapcat (fn [line] (cons "" (paragraph line))) notices)
   ["" (format "Your inbox: %d open%s" (count (core/unread-memos state core/operator))
               (if (seq (core/unread-memos state core/operator)) " (relay inbox shows them)" ""))]
   (for [id (core/active-role-ids state)
         :let [why (when config (core/unwakeable-reason state config id))]
         :when why]
     (text/for-operator (core/id-labels state id) (str "Public role " id " cannot run: " why)))
   (let [budget (get-in config [:budget :weekly-usd])]
     (when (or budget (seq (:wakes state)))
       (spend state zone (spend/weekly (vals (:wakes state)) zone now spend-weeks) budget)))
   (when-let [recent (seq (take-last 5 (sort-by :id (vals (:wakes state)))))]
     (list* "" "Recent wakes"
           (for [{:keys [id role started-at status outcome crashed chat]} recent]
             (text/for-operator (core/id-labels state role)
                                (format "  %-6d %s  %-20s %s%s" id (local started-at zone) role
                                        (if (= :running status) "running"
                                            (str outcome (when crashed ", crashed")))
                                        ;; Its id is what relay flag takes.
                                        (if chat ", conversation" ""))))))))

(defn- plural [n one many] (str n " " (if (= 1 n) one many)))

(defn roles
  "The roster (relay.roster) as `relay roles` shows it: a line for each
  role, with whom it reports to and what it is doing. A line carries the
  labels of the role's id and title and of the id it reports to."
  [state roster]
  (for [{:keys [id title labels working? conversation]} roster
        :let [reports-to (:reports-to (core/role state id))
              {:keys [turn turns unread?]} conversation]]
    (text/for-operator (core/union labels (core/id-labels state reports-to))
                       (str id ": " title (when reports-to (str ", reports to " reports-to))
                            (when working? "  [working]")
                            (when conversation
                              (str "  [conversation: " (if (= :running turn) "turn running" "idle")
                                   ", " (plural turns "turn" "turns")
                                   (when unread? ", unread")
                                   "]"))))))

(defn routines
  "The routines of the given owners, or of every active role."
  [state zone owners]
  (for [owner (or (seq owners) (core/active-role-ids state))
        {:keys [name schedule baseline labels version]} (core/active-routines state owner)]
    (text/for-operator (core/union labels (core/id-labels state owner))
                       (str (format "%-16s %-18s %s (since %s)" owner name schedule (local baseline zone))
                            (recipe-mark state version labels)))))

(defn notes [zone notes]
  (mapcat (fn [{:keys [name text written-at labels]}]
            (concat [(str "## " name "  (" (local written-at zone) ")")] (text/block labels text) [""]))
          notes))

(defn- validity [zone {:keys [valid-from valid-to]}]
  (let [t #(when* % zone)]
    (cond (and valid-from valid-to) (str "from " (t valid-from) " until " (t valid-to))
          valid-from (str "from " (t valid-from))
          valid-to (str "until " (t valid-to))
          :else "with no known start or end")))

(defn facts
  "Facts as relay facts shows them, each a line of its own and its text
  behind the gutter."
  [state zone facts]
  (if (empty? facts)
    ["No facts match."]
    (mapcat (fn [{:keys [id topic text supersedes superseded-by labels] :as f}]
              (concat [(str "Fact " id (when topic (str " [" topic "]")) ", holds " (validity zone f)
                            (when supersedes (str ", replacing fact " supersedes))
                            (when superseded-by (str ", superseded by fact " superseded-by)))]
                      (text/block labels text)))
            facts)))

(defn fact-history
  "Every version of a fact, as relay fact <id> shows it."
  [state zone {:keys [id supersedes versions]}]
  (cons (str "Fact " id (when supersedes (str ", which replaced fact " supersedes)))
        (mapcat (fn [{:keys [change recorded-at actor why superseded-by labels] :as v}]
                  (concat [(str "- " (when* recorded-at zone) " by " actor ": "
                                (case change
                                  :added "recorded"
                                  :superseded (str "superseded by fact " superseded-by)
                                  :corrected (str "corrected, because " (text/for-operator labels why))
                                  :retracted (str "retracted, because " (text/for-operator labels why))))]
                          (when-not (#{:superseded :retracted} change)
                            (cons (str "  holds " (validity zone v) (when (:topic v) (str " [" (:topic v) "]")))
                                  (text/block labels (:text v))))))
                versions)))

(defn journal
  "Journal entries, oldest first, each under its time."
  [zone entries]
  (if (empty? entries)
    ["The journal is empty."]
    (mapcat (fn [{:keys [at text labels]}] (concat [(when* at zone)] (text/block labels text) [""])) entries)))

(defn docs
  "The docs, one line each."
  [state zone docs]
  (if (empty? docs)
    ["There are no docs."]
    (for [{:keys [path at actor content labels seq]} docs]
      (text/for-operator labels (str path " (" (count content) " characters, last written "
                                     (when* at zone) " by " actor ")"
                                     (when (str/starts-with? path "skills/") (recipe-mark state seq labels)))))))

(defn doc
  "One version of a doc: its content, all of it behind the gutter."
  [zone {:keys [path seq content removed labels at]}]
  (if removed
    [(str path " was removed" (if at (str " on " (when* at zone)) "") ".")]
    (concat [(str path ", version " seq ":")] (text/block labels content))))

(defn doc-history
  "The versions of a doc, one line each."
  [zone path versions]
  (cons path
        (for [{:keys [seq at actor content removed labels]} versions]
          (text/for-operator labels (str "  version " seq ", " (when* at zone) " by " actor
                                         (if removed ": removed" (str ", " (count content) " characters")))))))

(defn log
  "Events one to a line, the last `tail` of them when given: as JSON, or
  as a summary with the event's data cut to 120 characters."
  [events {:keys [tail json]}]
  (for [e (if tail (take-last tail events) events)]
    (if json
      ;; Still JSON, and plain ASCII: every other code point is written as
      ;; \uXXXX by the encoder, so nothing can drive the terminal and
      ;; every line still parses. It is the stored event exactly: JSON is
      ;; for a program to read, not a page to tap on.
      ;; The encoder leaves DEL as it is, the one ASCII control it does.
      (-> (json/generate-string e {:escape-non-ascii true})
          (str/replace "\u007f" "\\u007f"))
      (text/for-operator (:labels e)
                         (format "%5d %s %-22s %-12s %s" (:seq e) (:at e) (:type e) (:actor e)
                                 (let [s (pr-str (:data e))] (subs s 0 (min 120 (count s)))))))))

(defn wake-result
  "What `relay wake` says it did, in a sentence."
  [role {:keys [result wake outcome left-open message]}]
  (let [who (str/capitalize (str role))]
    [(case result
       :ran (case outcome
              "ran" (str who " ran (wake " wake ")"
                         (if (pos? (or left-open 0))
                           (str " and left " left-open " memo" (when-not (= 1 left-open) "s") " open; it will come back to them.")
                           " and finished its memos."))
              "limited" (str who " stopped early (wake " wake "): it hit a limit. relay ready says more.")
              "unreachable" (str who " could not reach its model service (wake " wake "). relay tries again at the next tick.")
              "needs-attention" (str who " could not start (wake " wake "). relay ready says what it needs.")
              (str who " ran (wake " wake ")."))
       :idle (str who " had nothing to do.")
       :busy (str who " is busy already. Try again when it finishes.")
       :paused (str who " is paused, so it was not woken.")
       :runaway (str who " was woken too often, so relay is holding it. relay ready says more.")
       (or message (str who " was not woken.")))]))

(defn doc-put [path events]
  [(if (seq events)
     (str "Wrote " path ", version " (:seq (first events)))
     (str path " already says that."))])

(defn card
  "One card as relay card shows it: its text form, with each element's
  text marked and defanged when it came from outside, and a note's text
  behind the gutter."
  [c]
  (cards/text-lines c {:mark text/for-operator :block text/block}))

(defn board
  "The cards on the board, a blank line between them."
  [cs]
  (if (empty? cs)
    ["The board is empty."]
    (vec (rest (mapcat #(cons "" (card %)) cs)))))

;; --- what is ready (relay ready)

(def ready-width
  "The widest line relay ready writes: a phone-width terminal."
  60)

(defn wrap
  "`s` as lines of at most `width` characters, broken at spaces. A word
  longer than the width is cut."
  [s width]
  (let [words (mapcat #(map (partial apply str) (partition-all width %))
                      (remove str/blank? (str/split (str s) #"\s+")))]
    (loop [words words line nil out []]
      (if-let [w (first words)]
        (cond
          (nil? line) (recur (rest words) w out)
          (<= (+ (count line) 1 (count w)) width) (recur (rest words) (str line " " w) out)
          :else (recur words nil (conj out line)))
        (if line (conj out line) out)))))

(defn- ready-lines-each
  "How many lines of an item's body relay ready shows: more for a move,
  whose full paths are what it is about."
  [{:strs [kind]}]
  (if (= "move" kind) 12 4))

(defn- ready-kind [{:strs [kind message card undone]}]
  (case kind
    "message" (str "Message " message)
    "draft" "Draft"
    "event" "Calendar"
    "finding" (str "Board, card " card)
    "move" (if undone "Moved back" "Moved")
    (str/capitalize kind)))

(defn ready-ref
  "What `relay ready <ref>` takes to show an item whole: a message's
  number, else the item's id."
  [{:strs [id kind message]}]
  (if (and (= "message" kind) message) (str message) id))

(defn- ready-when [^String iso zone]
  (when iso
    (.format (.atZone (Instant/parse iso) ^ZoneId zone) (DateTimeFormatter/ofPattern "EEE d MMM HH:mm"))))

(defn- ready-day [^String iso-date]
  (.format (java.time.LocalDate/parse iso-date) (DateTimeFormatter/ofPattern "EEE d MMM")))

(defn ready-age
  "How long ago `iso` was, briefly: \"just now\", \"5m ago\", \"3h ago\",
  \"2d ago\", and the date once it is a week old."
  [^String iso ^Instant now zone]
  (when iso
    (let [at (Instant/parse iso)
          secs (max 0 (.getSeconds (java.time.Duration/between at now)))]
      (cond
        (< secs 60) "just now"
        (< secs 3600) (str (quot secs 60) "m ago")
        (< secs 86400) (str (quot secs 3600) "h ago")
        (< secs (* 7 86400)) (str (quot secs 86400) "d ago")
        :else (.format (.atZone at ^ZoneId zone) (DateTimeFormatter/ofPattern "d MMM"))))))

(defn- cut-mark
  "`line` with the mark that more was cut after it, within `width`."
  [line width]
  (let [l (str line " …")]
    (if (<= (count l) width) l (str (subs line 0 (- width 2)) " …"))))

(defn ready
  "What is ready, from the wire items (relay.ready/wire), as lines of at
  most ready-width characters. Each item is a heading at the left edge,
  with how long ago it came, and its text indented two spaces, so text
  that came from elsewhere never starts a line of the screen's own. Text
  cut short ends with '…'. `more` is how many items were left out."
  ([items zone now] (ready items zone now 0))
  ([items zone now more]
   (if (empty? items)
     ["Nothing is ready."]
     (let [indent "  "
           room (- ready-width 2)
           body (fn [lines n ref]
                  (let [all (->> lines
                                 (mapcat #(str/split-lines (str %)))
                                 (remove str/blank?)
                                 (mapcat #(wrap % room)))
                        shown (vec (take n all))
                        shown (if (> (count all) n)
                                (update shown (dec n) cut-mark room)
                                shown)]
                    (concat (map #(str indent %) shown)
                            (when (> (count all) n) [(str indent "All of it: relay ready " ref)]))))]
       (vec (concat
             (rest (mapcat
                    (fn [{:strs [title lines from replies start at] :as it}]
                      (concat [""]
                              (map-indexed (fn [i l] (if (zero? i) l (str indent l)))
                                           (wrap (str (ready-kind it) ": " title
                                                      (when-let [a (ready-age at now zone)] (str " (" a ")")))
                                                 room))
                              (cond
                                (get it "allDay")
                                [(str indent (ready-day (get it "startDate"))
                                      (when-not (= (get it "startDate") (get it "lastDate"))
                                        (str " to " (ready-day (get it "lastDate"))))
                                      ", all day")]
                                start [(str indent (ready-when start zone))])
                              (body lines (ready-lines-each it) (ready-ref it))
                              (when from (map #(str indent %) (wrap from room)))
                              (for [r (take-last 1 replies)]
                                (str indent "You replied: " (let [t (get r "text")] (if (> (count t) 30) (str (subs t 0 29) "…") t))))
                              (when (and (get it "replyTo") (empty? replies))
                                [(str indent "Reply: relay reply " (get it "replyTo") " -m \"...\"")])
                              (when-let [u (get it "undo")]
                                [(str indent "Undo: relay undo " u)])))
                    items))
             (when (pos? more)
               ["" (str "… and " more " older. relay ready --all shows every one.")])))))))

(defn with-commands
  "A ready item's wire form with the commands that act on what it numbers
  (a daily note's \"acts\"), for the command line: each change still to
  undo ends with how, and a line under it all says how to act on the
  rest (relay.digest/act-footer). An item done says nothing more."
  [it]
  (if-let [acts (seq (get it "acts"))]
    (let [open (remove #(get % "done") acts)
          undo (into {} (for [a open :when (= "revert" (get a "op"))] [(get a "line") (get a "n")]))
          add (fn [i l] (if-let [n (get undo i)] (str l " " (digest/undo-hint :cli n)) l))
          dismiss (filter #(= "dismiss" (get % "op")) open)
          footer (when (seq dismiss) (digest/act-footer :cli (some #(get % "offer") dismiss)))
          secs (when-let [ss (seq (get it "sections"))]
                 (second (reduce (fn [[i out] s]
                                   (let [ls (get s "lines")]
                                     [(+ i (count ls))
                                      (conj out (assoc s "lines" (vec (map-indexed #(add (+ i %1) %2) ls))))]))
                                 [0 []] ss)))]
      (cond-> (assoc it "lines" (cond-> (vec (map-indexed add (get it "lines"))) footer (conj footer)))
        secs (assoc "sections" (cond-> secs footer (conj {"heading" nil "lines" [footer]})))))
    it))

(declare ready-one*)

(defn ready-one
  "One ready item from its wire form, whole: nothing is cut. Lines are
  at most ready-width characters; text that came from elsewhere is
  indented, so it never starts a line of the screen's own. A daily note
  says how to act on its items (with-commands)."
  [it zone now]
  (let [{:strs [title lines from replies start at] :as it} (with-commands it)]
    (ready-one* it title lines from replies start at zone now)))

(defn- ready-one*
  [it title lines from replies start at zone now]
  (let [indent "  "
        room (- ready-width 2)
        para (fn [s] (map #(str indent %) (wrap s room)))]
    (vec (concat
          (map-indexed (fn [i l] (if (zero? i) l (str indent l)))
                       (wrap (str (ready-kind it) ": " title
                                  (when-let [a (ready-age at now zone)] (str " (" a ")")))
                             room))
          (cond
            (get it "allDay")
            [(str indent (ready-day (get it "startDate"))
                  (when-not (= (get it "startDate") (get it "lastDate"))
                    (str " to " (ready-day (get it "lastDate"))))
                  ", all day")]
            start [(str indent (ready-when start zone))])
          (let [text (fn [ls] (mapcat (fn [l] (mapcat #(para %) (remove str/blank? (str/split-lines (str l))))) ls))]
            (if-let [secs (seq (get it "sections"))]
              ;; A message read whole keeps its sections' headings, each
              ;; after a blank line.
              (mapcat (fn [{:strs [heading lines]}]
                        (concat (when heading (cons "" (para (str heading ":")))) (text lines)))
                      secs)
              (text lines)))
          (when from (para from))
          (mapcat (fn [r] (para (str "You replied: " (get r "text")))) replies)
          (when (and (get it "replyTo") (empty? replies))
            [(str indent "Reply: relay reply " (get it "replyTo") " -m \"...\"")])
          (when-let [u (get it "undo")]
            [(str indent "Undo: relay undo " u)])))))

;; --- eval runs (bb eval)

(defn- money [x] (if x (format "$%.4f" (double x)) "?"))
(defn- ratio [x] (if x (format "%.2f" (double x)) "-"))
(defn- pad [s n] (let [s (str s)] (if (>= (count s) n) s (str s (apply str (repeat (- n (count s)) " "))))))

(defn eval-report
  "The terminal report for an eval run. `run` is run.json's data, `summary`
  summary.json's, both with keyword keys."
  [run summary]
  (let [{:keys [run-id model effort live trials prompt-digest relay-commit agentloop-commit]} run]
    (concat
     [(str "relay evals  " run-id "  " (if live (str model " effort " effort) "mock model")
           "  " (if trials (str trials " trial(s)") "default trials"))
      (str "prompt digest " (subs (str prompt-digest "            ") 0 12)
           "  relay " (or relay-commit "?") "  agentloop " (or agentloop-commit "?"))
      ""
      (str (pad "case" 26) (pad "tier" 6) (pad "pass" 7) (pad "pass^3" 8) (pad "errors" 8)
           (pad "cost/trial" 12) "failed graders")]
     (for [[id c] (:cases summary)]
       (str (pad id 26) (pad (if (= :capability (:tier c)) "cap" "reg") 6)
            (pad (str (:passed c) "/" (:scored c)) 7)
            (pad (ratio (get-in c [:pass-hat 3])) 8)
            (pad (:errors c) 8)
            (pad (money (:mean-cost-usd c)) 12)
            (str/join ", " (for [[g k] (:grader-fails c)] (str (name g) " x" k)))))
     [""]
     (for [tier [:regression :capability]
           :let [t (get summary tier)]
           :when t]
       (str (name tier) ": pass " (ratio (:pass-rate t))
            (when-let [[lo hi] (:interval t)] (str " [" (ratio lo) ", " (ratio hi) "]"))
            "  pass^3 " (ratio (:pass-hat-3 t)) "  errors " (:errors t)))
     [(str "total " (money (:cost-usd summary)) " model; " (:trials summary) " trials, " (:wakes summary)
           " wakes, " (:requests summary) " requests; " (money (:cost-per-wake summary)) " per wake")])))


;; --- relay week

(defn duration
  "Seconds as a short span: 45s, 12m, 3h 05m, 2d 4h."
  [s]
  (when s
    (let [s (long (Math/round (double s)))]
      (cond (< s 60) (str s "s")
            (< s 3600) (str (quot s 60) "m")
            (< s 86400) (format "%dh %02dm" (quot s 3600) (quot (mod s 3600) 60))
            :else (format "%dd %dh" (quot s 86400) (quot (mod s 86400) 3600))))))

(defn- interruption-ref [{:keys [kind ref]}]
  (case kind
    "harness" (str "harness " ref)
    "dead-letter" (str "dead letter " ref)
    (str kind " " ref)))

(defn- days-between
  "Every date from the window's first day to its last, in `zone`, for a
  window of up to a month; for a longer one, the days with any."
  [{:keys [^Instant from ^Instant until]} ^ZoneId zone per-day]
  (if (and from until (<= (.toDays (java.time.Duration/between from until)) 31))
    (let [first-day (java.time.LocalDate/ofInstant from zone)
          last-day (java.time.LocalDate/ofInstant (.minusNanos until 1) zone)]
      (map str (take-while #(not (.isAfter ^java.time.LocalDate % last-day)) (iterate #(.plusDays ^java.time.LocalDate % 1) first-day))))
    (keys per-day)))

(defn- wake-name [chat id] (str (if chat "conversation " "wake ") id))

(def ^:private told-words
  "What relay told the operator, by what it records it as, in her words."
  {"told" "notes" "memo-for-operator" "notes" "daily-note" "daily notes"
   "daily-note-acted" "answers to your replies" "github-news" "GitHub news"})

(defn- told-word [k] (get told-words k "alerts"))

(def ^:private check-words
  "Each health check, as what went wrong."
  {"no-journal-entry" "kept no record of what it did"
   "memos-left-open" "left work unfinished"
   "tool-calls-refused-or-failed" "some steps failed"
   "cut-short" "was cut short"
   "crashed" "crashed"
   "many-operator-memos" "told you a lot at once"})

(defn week-report
  "relay week's report (relay.week/report) as lines."
  [{:keys [window interruptions actions messages roles health flags]} ^ZoneId zone]
  (let [{:keys [from until]} window
        names (into {} (map (juxt first second) week/interruption-kinds))
        how (into {} (map (juxt first #(nth % 2)) week/interruption-kinds))
        role-line (fn [labels s] (text/for-operator labels s))]
    (concat
     [(str "relay week, " (or (local from zone) "the start") " to " (or (local until zone) "now")
           " (" zone ")")
      ""
      "Interruptions: each time relay needed you"
      (format "  %-32s %6s %8s %8s %8s %8s" "" "arose" "decided" "pending" "median" "longest")]
     (mapcat (fn [{:keys [kind arose decided pending decisions median-seconds max-seconds oldest-pending-seconds]}]
               (cons (format "  %-32s %6d %8d %8d %8s %8s" (names kind) arose decided pending
                             (or (duration median-seconds) "-") (or (duration max-seconds) "-"))
                     (when (pos? arose)
                       [(str "    " (how kind)
                             (when (seq decisions)
                               (str "; " (str/join ", " (for [[d n] decisions] (str d " " n)))))
                             (when oldest-pending-seconds
                               (str "; oldest waiting " (duration oldest-pending-seconds))))])))
             (:kinds interruptions))
     (when-let [ps (seq (:pending interruptions))]
       (cons "  Still waiting:"
             (for [{:keys [role arose-at] :as p} ps]
               (str "    " (interruption-ref p) (when role (str ", " role)) ", since " (local arose-at zone)))))
     [(str "  Your own decisions: " (if (seq actions) (str/join ", " (for [[a n] actions] (str a " " n))) "none"))
      ""
      (str "Messages to you: " (:total messages))]
     (let [per-day (:per-day messages)]
       (for [d (days-between window zone per-day)] (format "  %s  %d" d (get per-day d 0))))
     (when (seq (:per-kind messages))
       [(str "  by kind: " (str/join ", " (for [[k n] (sort-by (juxt (comp - val) key)
                                                                 (reduce (fn [m [k n]] (update m (told-word k) (fnil + 0) n))
                                                                         {} (:per-kind messages)))]
                                            (str k " " n))))])
     (when (seq (:memos-by-role messages))
       [(str "  memos to you, by sender: "
             (str/join ", " (for [[r n] (:memos-by-role messages)] (str r " " n))))])
     ["" "Wakes by role"]
     (if (empty? roles)
       ["  none"]
       (mapcat (fn [{:keys [role role-labels wakes conversations memos-closed memos-blocked memos-left-open
                            memos-dead-lettered cut-short crashed tools-unavailable cost-usd
                            conversation-cost-usd priced-wakes cost-per-wake unpriced]}]
                 (map #(role-line role-labels %)
                      [(str "  " role ": " wakes (if (= 1 wakes) " wake, " " wakes, ")
                            conversations (if (= 1 conversations) " conversation" " conversations"))
                       (str "    memos: " memos-closed " closed, " memos-blocked " blocked, " memos-left-open
                            " left open, " memos-dead-lettered " dead-lettered")
                       (str "    " cut-short " cut short, " crashed " crashed, "
                            tools-unavailable " without their tools")
                       (str "    cost " (dollars cost-usd)
                            (when cost-per-wake (str ", " (dollars cost-per-wake) " a wake over " priced-wakes " priced"))
                            (when (pos? conversation-cost-usd) (str ", conversations " (dollars conversation-cost-usd)))
                            (when (pos? unpriced) (str "; " unpriced " without a cost")))]))
               roles))
     ["" "Health checks: wakes that look wrong"]
     (if (empty? health)
       ["  none"]
       (for [{:keys [id role role-labels chat started-at failed]} health]
         (role-line role-labels (str "  " (wake-name chat id) ", " role ", " (local started-at zone) ": "
                                     (str/join ", " (map #(get check-words % %) failed))))))
     ["" "Flags"]
     (if (empty? flags)
       ["  none (relay flag <wake> good|bad [note] marks one)"]
       (for [{:keys [wake role role-labels chat started-at value note]} flags]
         (role-line role-labels (str "  " (wake-name chat wake) ", " role ", " (local started-at zone) ": " value
                                     (when note (str ": " note)))))))))
