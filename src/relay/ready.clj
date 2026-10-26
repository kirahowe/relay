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

  An item is {:id \"message:7\" :kind :at :seq :title :lines [text]
  :labels [..] :sources [..]} and what its kind adds. A kind is one
  source, a function of the state that gives items (`sources`): a new
  kind is one more entry there. Nothing else in this namespace knows the
  kinds.

  The operator is shown where a thing came from (`provenance`) in words,
  from its sources: the outside text the session that made it read
  directly (relay.provenance), never a label's name, and never what that
  session merely had in context. Labels still decide whether its links
  are broken. `wire` is the form a client is sent, with every text
  already made safe to show."
  (:require [clojure.string :as str]
            [relay.cards :as cards]
            [relay.core :as core]
            [relay.messages :as messages]
            [relay.move-view :as move-view]
            [relay.provenance :as provenance]
            [relay.text :as text]))

;; --- where a thing came from

(defn provenance
  "Where a thing came from, in plain words for the operator, from its
  sources (relay.provenance): the one line a message's footer would be,
  \"Drew on: the web page x; a mail from y\", or nil when it is all her
  own or relay's. No label's name ever appears, and nothing in it can
  drive a screen."
  [sources]
  (some-> (provenance/drew-on sources) text/visible-line))

(defn- sources-of-item
  "An item's sources: its own, or for one made without them, what its
  labels name."
  [{:keys [sources labels] :as it}]
  (if (contains? it :sources) sources (provenance/sources labels)))

;; --- the sources

(def ^:private done-marks
  "How a daily note's item done since reads, after its text."
  {"reverted" " (undone)" "dismissed" " (dropped)" "declined" " (offer declined)" "accepted" " (offer taken up)"})

(defn- message-item [state m]
  (let [;; What was done with each numbered item, from this note or any
        ;; other, or the command line.
        acted (core/note-acted state m)
        item-line (fn [{:keys [text action]}] (str text (get done-marks (get acted (:n action)))))
        ;; A listed section's items behind a dash, as mail shows them.
        line (fn [sec it] (cond->> (item-line it) (:list sec) (str "- ")))
        numbered (into {} (map (juxt :n identity)) (:numbered m))]
    {:id (str "message:" (:id m)) :kind :message :at (:created-at m) :seq (:id m)
     :message (:id m) :message-kind (:kind m) :urgency (:urgency m) :thread (:thread m)
     :title (:subject m)
     :lines (vec (for [sec (:sections m) it (:items sec)] (line sec it)))
     ;; The same lines under their sections' headings, for a message read
     ;; whole; :lines stays flat, and an act's :line indexes it.
     :sections (vec (for [sec (:sections m)]
                      {:heading (:heading sec) :lines (mapv #(line sec %) (:items sec))}))
     ;; Links relay built itself, by line, kept followable.
     :links (into {} (keep-indexed (fn [i it] (when (:link it) [i (:link it)]))
                                   (for [sec (:sections m) it (:items sec)] it)))
     :labels (vec (:labels m))
     ;; Only a role's message says where it came from; relay's own say
     ;; what they are.
     :sources (if (messages/role-origins (:origin m)) (vec (:sources m)) [])
     :replies (vec (:replies m))
     :acts (vec (for [[i it] (map-indexed vector (for [sec (:sections m) it (:items sec)] it))
                      :let [{:keys [op n]} (when (map? it) (:action it))]
                      :when (and op (integer? n))]
                  (cond-> {:n n :op op :line i :done (get acted n)}
                    ;; An item whose offer can be turned down, keeping it.
                    (:offer (numbered n)) (assoc :offer true))))
     ;; The daily note is relay's own, but its writer hears the answer
     ;; ("revert 1", "not 2"), so it can be answered too.
     :reply-to (when (or (core/message-author state m) (= "daily-note" (:origin m))) (:id m))}))

(defn- draft-item [p]
  {:id (str "prepared:" (:id p)) :kind :draft :at (:at p) :seq (:id p)
   :title (:title p)
   :lines (vec (remove str/blank? [(:where p) (:preview p)]))
   :labels (vec (:labels p))
   :sources (if (sequential? (:sources p)) (:sources p) (provenance/sources (:labels p)))
   :tool (:tool p)})

(defn- event-item [state e]
  {:id (str "event:" (:id e)) :kind :event :at (:recorded-at e) :seq (:seq e)
   :title (:title e)
   :lines (vec (remove str/blank? [(:location e) (:description e)]))
   :labels (vec (:labels e))
   :sources (core/sources-of state (:seq e) (:labels e))
   :start (:start e) :end (:end e)
   ;; A whole day: its first day and the day after its last.
   :start-date (:start-date e) :end-date (:end-date e)})

(defn- finding-item [state c]
  (let [p (cards/progress c)]
    {:id (str "card:" (:id c)) :kind :finding :at (:added-at c) :seq (:id c)
     :title (get-in c [:title :text])
     :lines (vec (when (and p (pos? (:total p))) [(str (:done p) " of " (:total p) " done")]))
     :labels (vec (cards/text-labels c))
     ;; Every event that wrote the card counts, its edits included.
     :sources (or (:sources c) (core/sources-of state (:id c) (cards/text-labels c)))
     :card (:id c)}))

(defn- move-item
  "A files_move call: what moved where, which role moved it, and whether
  the operator can still undo it (relay undo, _relay/undo). A call relay
  never finished can still be asked to undo: undo then lists its moves to
  check by hand. `calls` are every call, to tell which folders it left
  empty are empty still."
  [calls c]
  (let [undone (set (:undone c))
        moving? (= :moving (:status c))
        shown (vec (if moving?
                     (map-indexed (fn [i m] [i m]) (:moves c))
                     (for [{:keys [index]} (:done c)] [index ((:moves c) index)])))
        left (core/move-steps-left c)
        all-undone? (and (not moving?) (seq (:done c)) (empty? left))
        {:keys [title lines]} (when (seq shown) (move-view/view (:roots c) shown undone (move-view/still-empty calls c)))]
    {:id (str "move:" (:id c)) :kind :move :at (or (:finished-at c) (:at c)) :seq (:id c)
     :title (cond
              moving? "relay stopped while it moved these; check them by hand"
              (empty? (:done c)) "Nothing was moved"
              :else title)
     :lines (vec (concat lines
                         (when (:stopped c) [(str "It stopped: " (:stopped c))])))
     :labels (vec (:labels c))
     :sources (if (sequential? (:sources c)) (:sources c) (provenance/sources (:labels c)))
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
     (for [e (core/calendar-events state {})] (event-item state e)))
   (fn [state]
     (for [c (core/board state false)
           :when (not= core/operator (:owner c))]
       (finding-item state c)))
   (fn [state] (let [calls (core/move-calls state)] (map #(move-item calls %) calls)))])

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
  "Text for the operator: refs as words, links broken when an outsider
  may have written it, and every hidden character taken out and said so
  (\"(a hidden character removed)\"), as mail says it, so a screen that
  escapes what it prints never shows an escape twice. The \"from\" line
  says where it came from, so the text carries no mark of its own."
  [labels s]
  (messages/plain labels s))

(defn- safe-line [labels s]
  (messages/plain-line labels s))

(defn- wire-item [{:keys [labels] :as it}]
  (let [sources (sources-of-item it)
        from (provenance sources)]
    (cond-> {"id" (:id it) "kind" (name (:kind it)) "at" (str (:at it)) "seq" (:seq it)
             "title" (safe-line labels (:title it))
             "lines" (vec (map-indexed (fn [i l]
                                         (cond-> (safe labels l)
                                           (get (:links it) i) (str "\n" (text/visible-line (get (:links it) i)))))
                                       (:lines it)))
             "from" from
             "outsider" (provenance/outside? sources)}
      (:message it) (assoc "message" (:message it) "messageKind" (:message-kind it)
                           "urgency" (:urgency it) "thread" (:thread it)
                           "replyTo" (:reply-to it)
                           "replies" (mapv (fn [r] {"at" (str (:at r)) "text" (safe [] (:text r))})
                                           (:replies it)))
      (seq (:acts it)) (assoc "acts" (mapv (fn [a] (cond-> {"n" (:n a) "op" (:op a) "line" (:line a) "done" (:done a)}
                                                     (:offer a) (assoc "offer" true)))
                                           (:acts it)))
      ;; Headed sections, so a message read whole keeps its headings.
      (some :heading (:sections it))
      (assoc "sections" (mapv (fn [{:keys [heading lines]}]
                                {"heading" (when heading (safe-line labels heading))
                                 "lines" (mapv #(safe labels %) lines)})
                              (:sections it)))
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
