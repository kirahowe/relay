(ns relay.week
  "What a stretch of relay's use looked like, so the operator can judge
  it: each time relay needed them and how long it waited,
  what each role's wakes did and cost, the wakes that look wrong, and the
  operator's own flags. relay week shows it and relay traces exports it.

  Pure: the log's events, the time now, the window and what each wake's
  transcript says come in as arguments, and the report is data. Nothing
  here reads a file; the transcripts are read at the edge (relay.cli) and
  passed in as facts (transcript-facts).

  An interruption arises at the event that asks the operator for
  something and is decided at the event that answers it. It belongs to
  the window it arose in. One decided after the window ends is pending as
  far as the window goes, so every kind has arisen = decided + pending."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk]
            [relay.core :as core]
            [relay.json :as strict])
  (:import (java.time Duration Instant LocalDate ZoneId)))

;; --- the window

(def default-days 7)

(defn- day-start ^Instant [^LocalDate d ^ZoneId zone] (.toInstant (.atStartOfDay d zone)))

(defn window
  "The window [from, until) for --since and --until, dates in `zone` (nil
  when not given): from the start of `since` to the end of `until`. With
  no `until` it ends now, and with no `since` it starts seven days before
  its end."
  [^ZoneId zone ^Instant now ^LocalDate since ^LocalDate until]
  (let [end (if until (day-start (.plusDays until 1) zone) now)]
    {:from (if since (day-start since zone) (.minus end (Duration/ofDays default-days)))
     :until end}))

(defn in-window? [{:keys [^Instant from ^Instant until]} ^Instant at]
  (and at
       (or (nil? from) (not (.isBefore at from)))
       (or (nil? until) (.isBefore at until))))

(defn- seconds [^Instant a ^Instant b] (.getSeconds (Duration/between a b)))

(defn median
  "The middle of some numbers, or the mean of the two middle ones; nil
  for none."
  [xs]
  (when (seq xs)
    (let [v (vec (sort xs)) n (count v) m (quot n 2)]
      (if (odd? n) (nth v m) (/ (+ (nth v (dec m)) (nth v m)) 2.0)))))

;; --- what a transcript says

(defn parse-transcript
  "The entries of an agentloop transcript or session file, its JSON
  lines, with keyword keys. A line that is not a JSON object is left
  out, as is a partial last line."
  [text]
  (vec (keep #(when-not (str/blank? %)
                (strict/object % {:keywords? true}))
             (str/split-lines (str text)))))

(defn transcript-facts
  "What a run's entries say about its tool calls: how many were made,
  how many agentloop's policy refused and how many failed, with the tools
  each named. A denial a person resolved, which only an older agentloop
  wrote, is not a refusal. Names only, never inputs or results."
  [entries]
  (let [decisions (keep :decision (filter #(= "decision" (:type %)) entries))
        results (keep #(get-in % [:tool_result :result]) (filter #(= "tool_result" (:type %)) entries))
        tool-of (into {} (map (juxt :call_id :tool)) decisions)
        refused (filter #(and (= "deny" (:verdict %)) (not (:resolved %))) decisions)
        denied (set (map :call_id (filter #(= "deny" (:verdict %)) decisions)))
        failed (filter #(and (:is_error %) (not (denied (:call_id %)))) results)]
    {:calls (count (distinct (keep :call_id decisions)))
     :refused (count refused)
     :failed (count failed)
     :refused-tools (vec (sort (distinct (keep :tool refused))))
     :failed-tools (vec (sort (distinct (keep #(tool-of (:call_id %)) failed))))}))

(defn add-facts
  "Two runs' facts together, as for a conversation's sessions."
  [a b]
  (cond (nil? a) b
        (nil? b) a
        :else {:calls (+ (:calls a) (:calls b)) :refused (+ (:refused a) (:refused b))
               :failed (+ (:failed a) (:failed b))
               :refused-tools (vec (sort (distinct (concat (:refused-tools a) (:refused-tools b)))))
               :failed-tools (vec (sort (distinct (concat (:failed-tools a) (:failed-tools b)))))}))

;; --- interruptions

(def interruption-kinds
  "Each way relay asks the operator for something, in the order shown,
  with what answers it."
  [["harness" "a harness that needs you" "fix it; it runs again by itself"]
   ["dead-letter" "work given up, sent to you" "relay retry"]])

(defn- arise [acc kind ref role at]
  (if (get-in acc [:open [kind ref]])
    acc
    (-> acc
        (assoc-in [:open [kind ref]] (count (:records acc)))
        (update :records conj {:kind kind :ref ref :role role :arose-at at}))))

(defn- settle [acc kind ref decision at]
  (if-let [i (get-in acc [:open [kind ref]])]
    (-> acc
        (update-in [:records i] assoc :decision decision :decided-at at)
        (update :open dissoc [kind ref]))
    acc))

(defn- open-refs [acc kind] (keep (fn [[[k r] _]] (when (= k kind) r)) (:open acc)))

(defn- wake-of
  "The wake a role's own event is taken to come from, by its id, from the
  walk's own record: the wake it is working in, else its open
  conversation. Both may run at once."
  [acc role]
  (let [running (get-in acc [:running role])]
    (or (:wake running) (:chat running))))

(defn- bump [acc wake k]
  (if wake (update-in acc [:wakes wake k] (fnil inc 0)) acc))

(defn- step
  "Folds one event, with the states before and after it, into the walk."
  [acc [_before after {:keys [seq type actor data] :as event}]]
  (let [at (core/inst (:at event))
        memo-to #(get-in after [:memos % :to])]
    (case type
      "wake/started" (-> acc (assoc-in [:running (:role data) (if (:chat data) :chat :wake)] seq)
                         (assoc-in [:wakes seq :start] seq))
      "wake/ended" (let [{:keys [role chat]} (get-in after [:wakes (:wake data)])]
                     (-> acc (update-in [:running role] dissoc (if chat :chat :wake))
                         (assoc-in [:wakes (:wake data) :tools-unavailable] (some? (:tools-error data)))))
      "notification/created"
      (let [acc (update acc :notifications conj
                        {:kind (:kind data) :at at
                         :from (when (= "memo-for-operator" (:kind data))
                                 (get-in after [:memos (:memo data) :from]))})]
        (case (:kind data)
          "harness-needs-attention" (arise acc "harness" (:harness data) nil at)
          "work-abandoned" (arise acc "dead-letter" (:dead-letter data) (:role data) at)
          acc))
      "memo/closed" (let [m (:memo data)
                          w (wake-of acc (memo-to m))]
                      (cond-> acc
                        (some #{m} (get-in after [:wakes w :memos])) (bump w :closed)))
      "memo/dead-lettered" (let [m (:memo data)
                                 w (wake-of acc (memo-to m))]
                             (cond-> acc
                               (and (= "blocked" (:reason data)) (some #{m} (get-in after [:wakes w :memos])))
                               (bump w :blocked)
                               (= "never-closed" (:reason data)) (bump (:wake data) :dead-lettered)))
      "memo/attempt-failed" (bump acc (:wake data) :left-open)
      ("memo/requeued" "memo/superseded")
      (reduce (fn [acc letter]
                (if (= (:memo data) (get-in after [:dead-letters letter :memo]))
                  (settle acc "dead-letter" letter (if (= "memo/requeued" type) "retried" "superseded") at)
                  acc))
              acc (open-refs acc "dead-letter"))
      "harness/recovered" (settle acc "harness" (:harness data) "recovered" at)
      "journal/written" (let [w (wake-of acc (:owner data))]
                          (cond-> acc
                            (and w (= actor (:owner data))) (assoc-in [:wakes w :journal] true)))
      "memo/sent" (let [w (wake-of acc actor)]
                    (cond-> acc (and w (= core/operator (:to data))) (bump w :operator-memos)))
      "wake/flagged" (update acc :actions conj {:action "flag" :at at})
      acc)))

(defn- replay-states
  "[before after event] for each event, folded as core/replay folds them."
  [events]
  (let [states (reductions core/evolve core/empty-state (core/with-legacy-labels events))]
    (map vector states (rest states) events)))

(defn walk-log
  "Everything the report needs from the log, in one pass: the
  interruptions, notifications and operator actions, what each wake did,
  and the final state."
  [events]
  (let [triples (replay-states events)
        acc (reduce step {:records [] :open {} :running {} :wakes {} :notifications [] :actions []}
                    triples)]
    (assoc acc :state (if-let [t (last triples)] (second t) core/empty-state))))

(defn- kind-summary [kind records {:keys [until]} ^Instant as-of]
  (let [decided? #(and (:decided-at %) (or (nil? until) (.isBefore ^Instant (:decided-at %) until)))
        done (filter decided? records)
        waits (map #(seconds (:arose-at %) (:decided-at %)) done)
        pending (remove decided? records)]
    {:kind kind
     :arose (count records)
     :decided (count done)
     :pending (count pending)
     :decisions (into (sorted-map) (frequencies (map :decision done)))
     :median-seconds (median waits)
     :max-seconds (when (seq waits) (apply max waits))
     :oldest-pending-seconds (when (seq pending) (apply max (map #(max 0 (seconds (:arose-at %) as-of)) pending)))}))

(defn interruptions [{:keys [records]} win as-of]
  (let [ours (filter #(in-window? win (:arose-at %)) records)
        by-kind (group-by :kind ours)
        until (:until win)
        pending (remove #(and (:decided-at %) (or (nil? until) (.isBefore ^Instant (:decided-at %) until))) ours)]
    {:kinds (vec (for [[kind] interruption-kinds] (kind-summary kind (get by-kind kind) win as-of)))
     :pending (vec (sort-by (juxt :arose-at :kind) (map #(select-keys % [:kind :ref :role :arose-at]) pending)))}))

(defn notifications
  "The notifications sent in the window: in all, per day in `zone`, per
  kind, and the memos to the operator per role that sent them."
  [{ns :notifications} win ^ZoneId zone]
  (let [ours (filter #(in-window? win (:at %)) ns)]
    {:total (count ours)
     :per-day (into (sorted-map) (frequencies (map #(str (LocalDate/ofInstant (:at %) zone)) ours)))
     :per-kind (into (sorted-map) (frequencies (map :kind ours)))
     :memos-by-role (into (sorted-map) (frequencies (keep :from ours)))}))

;; --- wakes

(def many-operator-memos
  "How many memos to the operator in one wake count as unusually many."
  3)

(defn wake-row
  "One wake or conversation as the report and the traces see it."
  [state walked-wakes facts {:keys [id] :as w}]
  (let [f (get walked-wakes id)]
    {:id id :role (:role w) :chat (boolean (:chat w)) :status (:status w)
     :started-at (:started-at w) :ended-at (:ended-at w)
     :outcome (:outcome w) :crashed (boolean (:crashed w)) :cut-short (boolean (:cut-short w))
     :memos (count (:memos w))
     :closed (:closed f 0) :blocked (:blocked f 0) :left-open (:left-open f 0)
     :dead-lettered (:dead-lettered f 0) :journal (boolean (:journal f))
     :operator-memos (:operator-memos f 0) :tools-unavailable (boolean (:tools-unavailable f))
     :cost-usd (core/wake-cost (:cost-usd w)) :labels (vec (:labels w))
     :session (:session w) :sessions (vec (:sessions w))
     :flags (mapv #(select-keys % [:id :value :at]) (:flags w))
     :role-labels (core/id-labels state (:role w))
     :transcript (get facts id)}))

(defn checks
  "The health checks for a wake, each {:check :pass}: a wake that ran is
  checked for a journal entry, memos left open, refused or failed tool
  calls (when its transcript could be read), being cut short or crashing,
  and too many memos to the operator. A conversation is checked only for
  its tool calls. A wake the harness could not run, or still running, has
  none: the harness is counted elsewhere."
  [{:keys [chat status outcome journal left-open transcript cut-short crashed operator-memos]}]
  (let [tools (when transcript
                [{:check "tool-calls-refused-or-failed"
                  :pass (zero? (+ (:refused transcript) (:failed transcript)))}])]
    (cond
      (not= :ended status) []
      chat (vec tools)
      (not= "ran" outcome) []
      :else (vec (concat
                  [{:check "no-journal-entry" :pass journal}
                   {:check "memos-left-open" :pass (zero? left-open)}]
                  tools
                  [{:check "cut-short" :pass (not cut-short)}
                   {:check "crashed" :pass (not crashed)}
                   {:check "many-operator-memos" :pass (< operator-memos many-operator-memos)}])))))

(defn failed-checks [row] (vec (keep #(when-not (:pass %) (:check %)) (checks row))))

(defn wake-rows
  "The wakes and conversations that started in the window, oldest first."
  [{:keys [state wakes]} win facts]
  (vec (for [w (sort-by :id (vals (:wakes state)))
             :when (in-window? win (:started-at w))]
         (wake-row state wakes facts w))))

(defn- usd-sum [xs] (reduce + 0M (map bigdec xs)))

(defn role-summary [role rows]
  (let [wakes (remove :chat rows)
        chats (filter :chat rows)
        priced (filter :cost-usd wakes)
        sum #(reduce + 0 (map % wakes))
        cost (usd-sum (keep :cost-usd rows))]
    {:role role
     :role-labels (:role-labels (first rows))
     :wakes (count wakes) :conversations (count chats)
     :memos-closed (sum :closed) :memos-blocked (sum :blocked) :memos-left-open (sum :left-open)
     :memos-dead-lettered (sum :dead-lettered)
     :cut-short (count (filter :cut-short wakes)) :crashed (count (filter :crashed wakes))
     :tools-unavailable (count (filter :tools-unavailable wakes))
     :cost-usd cost
     :conversation-cost-usd (usd-sum (keep :cost-usd chats))
     :priced-wakes (count priced)
     :cost-per-wake (when (seq priced) (with-precision 10 (/ (usd-sum (map :cost-usd priced)) (count priced))))
     ;; Ran with no cost known: never counted as free.
     :unpriced (count (filter #(and (nil? (:cost-usd %)) (= "ran" (:outcome %)) (= :ended (:status %))) rows))}))

(defn report
  "The report for a window ({:from :until}, either nil for no bound) of
  the log `events`, as of `now`. `facts` is {wake-id transcript-facts}
  for the wakes whose transcripts could be read."
  [events ^ZoneId zone ^Instant now win facts]
  (let [walked (walk-log events)
        as-of (if (and (:until win) (.isBefore ^Instant (:until win) now)) (:until win) now)
        rows (wake-rows walked win facts)]
    {:window win
     :zone (str zone)
     :interruptions (interruptions walked win as-of)
     :actions (into (sorted-map) (frequencies (map :action (filter #(in-window? win (:at %)) (:actions walked)))))
     :notifications (notifications walked win zone)
     :roles (vec (for [[role rs] (sort-by key (group-by :role rows))] (role-summary role rs)))
     :health (vec (for [r rows :let [failed (failed-checks r)] :when (seq failed)]
                    (assoc (select-keys r [:id :role :role-labels :chat :started-at]) :failed failed)))
     :flags (vec (for [w (sort-by :id (vals (:wakes (:state walked))))
                       f (:flags w)
                       :when (in-window? win (:at f))]
                   {:wake (:id w) :role (:role w) :role-labels (core/id-labels (:state walked) (:role w))
                    :chat (boolean (:chat w)) :started-at (:started-at w)
                    :value (:value f) :note (:note f) :at (:at f)}))}))

(defn json-ready
  "The report as plain JSON data: instants as ISO strings."
  [x]
  (clojure.walk/postwalk #(if (instance? Instant %) (str %) %) x))
