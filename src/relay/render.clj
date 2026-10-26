(ns relay.render
  "What the relay command shows. Pure: each function takes the data to
  show and returns lines of text, which relay.cli prints, each through
  text/visible-line.

  One rule keeps a role's or an outsider's text from passing for the
  screen's own: it never decides where a line starts. A one-line field is
  part of a line the renderer writes, and any line break in it is escaped
  where it is printed. Text that may run to several lines is shown with
  text/block: every line behind text/gutter, which nothing else starts
  with, under a line saying it came from outside when it did. Nothing
  here names a label or a class: the operator sees where a thing came
  from."
  (:require [cheshire.core :as json]
            [clojure.walk]
            [clojure.string :as str]
            [relay.cards :as cards]
            [relay.core :as core]
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

(defn memo
  "One memo, as the operator's inbox shows it. One from outside is marked
  so and its URLs broken."
  [zone {:keys [id from subject body sent-at labels]}]
  (concat
   ["" (str "[" id "] " (text/for-operator labels subject))
    (str "    from " from ", " (local sent-at zone))]
   (text/block labels body)))

(defn inbox [zone memos]
  (if (empty? memos)
    ["Nothing waiting."]
    (mapcat #(memo zone %) memos)))

(defn- open-memo [zone {:keys [id subject attempts retry-at]}]
  (format "    open  [%d] %s%s" id subject
          (cond-> ""
            (pos? attempts) (str " (" attempts " failed attempts")
            (and (pos? attempts) retry-at) (str ", next " (local retry-at zone))
            (pos? attempts) (str ")"))))

(defn- role-line-labels [state id]
  (core/union (core/role-labels state id) (core/id-labels state (:reports-to (core/role state id)))))

(defn- activity
  "What a role is doing, after its line: working through its memos, in
  a conversation with the operator, or both."
  [working conversation]
  (str (when working "  [working]")
       (when conversation (if (core/in-turn? conversation) "  [in a conversation turn]" "  [conversation open]"))))

(defn- role-status [state zone now id]
  (let [{:keys [title reports-to]} (core/role state id)
        id-ls (core/id-labels state id)]
    (concat
     [(text/for-operator (role-line-labels state id)
                         (format "  %-20s %s%s%s" id title
                                 (if reports-to (str ", reports to " reports-to) "")
                                 (activity (core/working-wake state id) (core/open-conversation state id))))]
     (for [{:keys [name schedule labels]} (core/active-routines state id)]
       (text/for-operator (core/union labels id-ls) (format "    routine %-18s %s" name schedule)))
     (map #(text/for-operator (core/union (:labels %) id-ls) (open-memo zone %)) (core/unread-memos state id))
     (for [{memo-id :id :keys [subject labels]} (core/dead-memos state id)]
       (text/for-operator (core/union labels id-ls)
                          (format "    dead  [%d] %s (relay retry %s)" memo-id subject id)))
     (when-let [{:keys [server tool retry-at]} (get-in state [:roles id :tools-unavailable])]
       [(text/for-operator id-ls
                           (str "    tools unavailable"
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

(defn spend
  "This week's spend per role and the total, against the weekly budget if
  there is one, and the total of each week before. `weeks` is
  relay.spend/weekly's result, newest first. A role's id carries its
  labels."
  [state weeks budget]
  (let [[{:keys [week roles cost-usd unpriced]} & earlier] weeks
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
       [(str "  earlier weeks: " (str/join ", " (for [w earlier] (str (:week w) " " (dollars (:cost-usd w))))))]))))

(defn status
  "Roles with their routines and open work, harnesses, the operator's
  inbox, what the roles spent this week and the weeks before, and the
  latest wakes, as of `now`. `paused` is the lines to show
  when relay is paused (relay.pause/recovery-lines), or nil."
  [state zone ^Instant now & [config paused]]
  (concat
   (when paused
     (concat paused [""]))
   ["Roles"]
   (mapcat #(role-status state zone now %) (core/active-role-ids state))
   (mapcat (fn [line] ["" line])
    (for [[h {:keys [condition paused-until]}] (sort-by key (:harnesses state))]
     (str "Harness " h ": "
          (if (and paused-until (.isAfter ^Instant paused-until now))
            (str condition ", next try " (local paused-until zone))
            "available"))))
   ["" (format "Operator's inbox: %d open" (count (core/unread-memos state core/operator)))]
   (for [id (core/active-role-ids state)
         :let [why (when config (core/unwakeable-reason state config id))]
         :when why]
     (text/for-operator (core/id-labels state id) (str "Public role " id " cannot run: " why)))
   (let [budget (get-in config [:budget :weekly-usd])]
     (when (or budget (seq (:wakes state)))
       (spend state (spend/weekly (vals (:wakes state)) zone now spend-weeks) budget)))
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
        {:keys [name schedule baseline labels]} (core/active-routines state owner)]
    (text/for-operator (core/union labels (core/id-labels state owner))
                       (format "%-16s %-18s %s (since %s)" owner name schedule (local baseline zone)))))

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
    (for [{:keys [path at actor content labels]} docs]
      (text/for-operator labels (str path " (" (count content) " characters, last written "
                                     (when* at zone) " by " actor ")")))))

(defn doc
  "One version of a doc: its content, all of it behind the gutter."
  [{:keys [path seq content removed labels]}]
  (if removed
    [(str path " was removed in version " seq ".")]
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
  (for [e (if tail (take-last tail events) events)
        :let [tainted? (text/contaminated? (:labels e))]]
    (if json
      ;; Still JSON, and plain ASCII: every other code point is written as
      ;; \uXXXX by the encoder, so nothing can drive the terminal and
      ;; every line still parses. A contaminated event's strings are
      ;; defanged in place.
      ;; The encoder leaves DEL as it is, the one ASCII control it does.
      (-> (json/generate-string (cond-> e tainted? (update :data #(clojure.walk/postwalk
                                                                   (fn [x] (if (string? x) (text/defang x) x)) %)))
                                {:escape-non-ascii true})
          (str/replace "\u007f" "\\u007f"))
      (text/for-operator (:labels e)
                         (format "%5d %s %-22s %-12s %s" (:seq e) (:at e) (:type e) (:actor e)
                                 (let [s (pr-str (:data e))] (subs s 0 (min 120 (count s)))))))))

(defn wake-result
  "What `relay wake` says it did."
  [result]
  [(str (name (:result result)) " " (pr-str (dissoc result :result)))])

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


;; --- the morning brief (relay.brief)
;;
;; One push: a title of counts and a body of short lines. The text from
;; the calendar and reminders is one field of one line of what the tool
;; printed, so it holds no line break; the push edge (relay.notify) makes
;; every character visible and, when the brief's labels say it came from
;; outside, marks the whole brief and breaks its URLs.

(def ^:private brief-names
  {:calendar ["Calendar" "event" "events"]
   :reminders ["Reminders due" "reminder" "reminders"]})

(defn- counted [n one many] (str n " " (if (= 1 n) one many)))

(defn- brief-count
  "A section in the title: its count, or that it is unavailable. Nil for
  a tool that is not configured."
  [{:keys [key status count]}]
  (let [[_ one many] (brief-names key)]
    (case status
      :ok (counted count one many)
      :unavailable (str (name key) " unavailable")
      :missing nil)))

(defn- brief-section [{:keys [key status count lines tool why]}]
  (let [[heading] (brief-names key)]
    (case status
      :ok (if (zero? count)
            [(str heading ": none")]
            (cons (str heading ": " count) (map #(str "  " %) lines)))
      :unavailable [(str heading ": unavailable, the tool " tool " " why ".")]
      :missing [(str heading ": not configured, relay.edn has no tool " tool ".")])))

(defn brief
  "A composed brief (relay.brief/compose) as the push that carries it:
  {:title :body}."
  [{:keys [date sections]}]
  {:title (str "Morning brief for " date ": " (str/join ", " (keep brief-count sections)))
   :body (str/join "\n" (mapcat brief-section sections))})

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

(defn week-report
  "relay week's report (relay.week/report) as lines."
  [{:keys [window interruptions actions notifications roles health flags]} ^ZoneId zone]
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
      (str "Notifications: " (:total notifications))]
     (let [per-day (:per-day notifications)]
       (for [d (days-between window zone per-day)] (format "  %s  %d" d (get per-day d 0))))
     (when (seq (:per-kind notifications))
       [(str "  by kind: " (str/join ", " (for [[k n] (sort-by (comp - val) (:per-kind notifications))] (str k " " n))))])
     (when (seq (:memos-by-role notifications))
       [(str "  memos to you, by sender: "
             (str/join ", " (for [[r n] (:memos-by-role notifications)] (str r " " n))))])
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
                                     (str/join ", " failed)))))
     ["" "Flags"]
     (if (empty? flags)
       ["  none (relay flag <wake> good|bad [note] marks one)"]
       (for [{:keys [wake role role-labels chat started-at value note]} flags]
         (role-line role-labels (str "  " (wake-name chat wake) ", " role ", " (local started-at zone) ": " value
                                     (when note (str ": " note)))))))))
