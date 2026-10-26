(ns relay.ready
  "What is ready for the operator: the client channel's view. Pure.

  A fold over the state gives one list of items, newest first, each
  something relay prepared or told her, with where it came from in plain
  words:

    :message   what a role or relay told her (relay.messages), with any
               answers she gave
    :draft     what a role prepared with an edit tool that is not relay's
               own, such as a draft in her account, and where it went
    :event     an entry on the agent's calendar that has not ended
    :finding   an active card a role put on her board
    :move      a files_move call: what moved where, by which role, and
               its undo

  An item is {:id \"message:7\" :kind :at :seq :title :lines [text] :from
  \"from a mail by x@y\" or nil :labels [..]} and what its kind adds. A
  kind is one source, a function of the state that gives items (`sources`):
  a new kind is one more entry there, and a line in `kind-words`. Nothing
  else in this namespace knows the kinds.

  Labels are provenance. The operator is shown where a thing came from
  (`provenance`), never a label's name: a label of the form kind:detail,
  such as mail:x@y or web, reads as \"from a mail by x@y\" or \"from the
  web\". `wire` is the form a client is sent, with every text already
  made safe to show."
  (:require [clojure.string :as str]
            [relay.cards :as cards]
            [relay.core :as core]
            [relay.messages :as messages]
            [relay.move-view :as move-view]
            [relay.text :as text]))

;; --- where a thing came from

(def ^:private detail-limit 80)

(defn- detail [s]
  (let [s (messages/squeeze-line s)
        s (if (> (count s) detail-limit) (str (subs s 0 detail-limit) "…") s)]
    (text/visible-line s)))

(def ^:private sources-by-kind
  "How a label of a known kind reads: kind -> (fn [detail-or-nil] words)."
  {"mail" (fn [d] (if d (str "a mail by " d) "a mail"))
   "web" (fn [_] "the web")
   "github" (fn [d] (if d (str "GitHub (" d ")") "GitHub"))
   "calendar" (fn [d] (if d (str "a calendar (" d ")") "a calendar"))})

(defn outside?
  "Whether text with these labels came from outside: anything but her
  own things."
  [labels]
  (boolean (some #(not= "private" %) (filter string? labels))))

(defn provenance
  "Where text with these labels came from, in plain words for the
  operator, or nil when it is all her own. A label of the form kind:detail
  (or a bare kind) of a known kind reads by that kind; any other label
  that marks outside text reads as \"from outside\". No label's name ever
  appears, and a detail is cut and made visible."
  [labels]
  (let [labels (distinct (filter string? labels))
        known (for [l labels
                    :let [[_ k d] (re-matches #"([a-z]+)(?::(.+))?" l)
                          f (get sources-by-kind k)]
                    :when f]
                (f (when d (detail d))))
        outside? (outside? labels)]
    (cond
      (seq known) (str/join ", " (map #(str "from " %) (sort (distinct known))))
      outside? "from outside")))

;; --- the sources

(defn- message-item [state m]
  {:id (str "message:" (:id m)) :kind :message :at (:created-at m) :seq (:id m)
   :message (:id m) :message-kind (:kind m) :urgency (:urgency m) :thread (:thread m)
   :title (:subject m)
   ;; A daily note's item the operator acted on says so where it stands.
   :lines (vec (for [sec (:sections m) {:keys [text action]} (:items sec)
                     :let [done (get (:acted m) (:n action))]]
                 (cond-> text
                   (= "reverted" done) (str " (undone)")
                   (= "dismissed" done) (str " (dropped)"))))
   :labels (vec (:labels m))
   :replies (vec (:replies m))
   :acts (vec (for [[i it] (map-indexed vector (for [sec (:sections m) it (:items sec)] it))
                    :let [{:keys [op n]} (when (map? it) (:action it))]
                    :when (and op (integer? n))]
                {:n n :op op :line i :done (get-in m [:acted n])}))
   ;; The daily note is relay's own, but its writer hears the answer
   ;; ("revert 1", "not 2"), so it can be answered too.
   :reply-to (when (or (core/message-author state m) (= "daily-note" (:origin m))) (:id m))})

(defn- draft-item [p]
  {:id (str "prepared:" (:id p)) :kind :draft :at (:at p) :seq (:id p)
   :title (:title p)
   :lines (vec (remove str/blank? [(:where p) (:preview p)]))
   :labels (vec (:labels p))
   :tool (:tool p)})

(defn- event-item [e]
  {:id (str "event:" (:id e)) :kind :event :at (:recorded-at e) :seq (:seq e)
   :title (:title e)
   :lines (vec (remove str/blank? [(:location e) (:description e)]))
   :labels (vec (:labels e))
   :start (:start e) :end (:end e)
   ;; A whole day: its first day and the day after its last.
   :start-date (:start-date e) :end-date (:end-date e)})

(defn- finding-item [c]
  (let [p (cards/progress c)]
    {:id (str "card:" (:id c)) :kind :finding :at (:added-at c) :seq (:id c)
     :title (get-in c [:title :text])
     :lines (vec (when (and p (pos? (:total p))) [(str (:done p) " of " (:total p) " done")]))
     :labels (vec (cards/text-labels c))
     :card (:id c)}))

(defn- move-item
  "A files_move call: what moved where, which role moved it, and whether
  the operator can still undo it (relay undo, _relay/undo). A call relay
  never finished can still be asked to undo: undo then lists its moves to
  check by hand."
  [c]
  (let [undone (set (:undone c))
        moving? (= :moving (:status c))
        shown (vec (if moving?
                     (map-indexed (fn [i m] [i m]) (:moves c))
                     (for [{:keys [index]} (:done c)] [index ((:moves c) index)])))
        left (core/move-steps-left c)
        all-undone? (and (not moving?) (seq (:done c)) (empty? left))
        {:keys [title lines]} (when (seq shown) (move-view/view (:roots c) shown undone))]
    {:id (str "move:" (:id c)) :kind :move :at (or (:finished-at c) (:at c)) :seq (:id c)
     :title (cond
              moving? "relay stopped while it moved these; check them by hand"
              (empty? (:done c)) "Nothing was moved"
              :else title)
     :lines (vec (concat lines
                         (when (:stopped c) [(str "It stopped: " (:stopped c))])))
     :labels (vec (:labels c))
     :move (:id c) :role (:actor c)
     :undo (when (or moving? (seq left)) (:id c))
     :undone (boolean all-undone?)}))

(def sources
  "Where items come from: functions of the state giving items."
  [;; An alert about a trouble that is over (core/resolve-alerts) is no
   ;; longer anything for her to do.
   (fn [state] (map #(message-item state %) (remove :resolved (vals (:messages state)))))
   (fn [state] (map draft-item (vals (:prepared state))))
   (fn [state]
     (for [e (core/calendar-events state {})] (event-item e)))
   (fn [state]
     (for [c (core/board state false)
           :when (not= core/operator (:owner c))]
       (finding-item c)))
   (fn [state] (map move-item (core/move-calls state)))])

(def default-limit 60)

(defn newer-first
  "A comparator: the later :at first; at the same time, the later event
  in the log (:seq); then by id, so the order is total."
  [a b]
  (let [c (compare (:at b) (:at a))
        c (if (zero? c) (compare (or (:seq b) 0) (or (:seq a) 0)) c)]
    (if (zero? c) (compare (:id a) (:id b)) c)))

(defn ready
  "Everything ready for the operator in `state`: the newest `limit`
  items (60 unless given), newest first. Items with the same time are
  ordered by the log's sequence number of the event that made them,
  newest first, then by id."
  ([state] (ready state {}))
  ([state {:keys [limit] :or {limit default-limit}}]
   (->> sources
        (mapcat #(% state))
        (sort newer-first)
        (take limit)
        vec)))

;; --- what an edit call prepared

(def ^:private title-keys ["subject" "title" "name" "summary"])
(def ^:private preview-keys ["body" "text" "content" "description"])

(defn prepared-args
  "The :prepared/record args for a tool call {:call :tool :input :result},
  from the call's input and result: its title is the input's subject,
  title, name or summary, else the tool's name in words; where it went is
  the first line of the tool's result; the preview is the input's body,
  text, content or description. Pure and total."
  [{:keys [call tool input result]}]
  (let [input (if (map? input) input {})
        pick (fn [ks] (first (for [k ks :let [v (get input k)]
                                   :when (and (string? v) (not (str/blank? v)))]
                               v)))
        first-line (first (remove str/blank? (str/split-lines (str result))))]
    {:call call :tool tool
     :title (or (pick title-keys) (str/replace (str tool) #"[-_]+" " "))
     :where (or first-line "")
     :preview (pick preview-keys)}))

;; --- the wire

(defn- safe
  "Text for the operator: from outside its links are broken, and every
  hidden character is made visible. The \"from\" line says where it came
  from, so the text carries no mark of its own."
  [labels s]
  (text/visible (if (outside? labels) (text/defang s) (str s))))

(defn- safe-line [labels s]
  (text/visible-line (if (outside? labels) (text/defang (messages/squeeze-line s)) (messages/squeeze-line s))))

(defn- wire-item [{:keys [labels] :as it}]
  (let [from (provenance labels)]
    (cond-> {"id" (:id it) "kind" (name (:kind it)) "at" (str (:at it)) "seq" (:seq it)
             "title" (safe-line labels (:title it))
             "lines" (mapv #(safe labels %) (:lines it))
             "from" from
             "outsider" (outside? labels)}
      (:message it) (assoc "message" (:message it) "messageKind" (:message-kind it)
                           "urgency" (:urgency it) "thread" (:thread it)
                           "replyTo" (:reply-to it)
                           "replies" (mapv (fn [r] {"at" (str (:at r)) "text" (safe [] (:text r))})
                                           (:replies it)))
      (seq (:acts it)) (assoc "acts" (mapv (fn [a] {"n" (:n a) "op" (:op a) "line" (:line a) "done" (:done a)}) (:acts it)))
      (:start it) (assoc "start" (str (:start it)) "end" (str (:end it)))
      (:start-date it) (assoc "allDay" true "startDate" (str (:start-date it))
                              "lastDate" (str (.minusDays ^java.time.LocalDate (:end-date it) 1)))
      (:card it) (assoc "card" (:card it))
      (:move it) (assoc "move" (:move it) "role" (:role it) "undo" (:undo it) "undone" (:undone it)))))

(defn wire
  "An item as a client is sent it: string keys, every text safe to show,
  \"from\" in plain words, \"outsider\" true when it came from outside."
  [it]
  (wire-item it))

(defn answer
  "The _relay/ready answer: {\"rev\" log-rev \"items\" [wire form]}."
  [state rev opts]
  {"rev" rev "items" (mapv wire (ready state opts))})

(defn by-id
  "Wire items as a map from id to item."
  [items]
  (into {} (map (juxt #(get % "id") identity)) items))

(defn changed
  "The _relay/ready/changed params for a subscriber last sent `sent` (the
  wire items by id), given the items now: the new or changed ones, and
  the ids of those gone. Nil when nothing differs."
  [rev sent current]
  (let [now (by-id current)
        new (vec (remove #(= (get sent (get % "id")) %) current))
        gone (vec (sort (remove #(contains? now %) (keys sent))))]
    (when (or (seq new) (seq gone))
      {"rev" rev "items" new "gone" gone})))

(defn apply-changed
  "What a client holds after `params` (changed): its items by id."
  [held {:strs [items gone]}]
  (as-> held h (apply dissoc h gone) (merge h (by-id items))))
