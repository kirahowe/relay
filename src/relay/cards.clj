(ns relay.cards
  "Cards on the operator's board: small structured things the operator
  works through over days, such as a checklist or an accounts-by-month
  tracker. Pure. relay.core decides and folds card events through this
  namespace, which never requires relay.core.

  A card lives in the log as events. `added` checks a new card and gives
  the data of its card/added event, with the ids of its elements assigned;
  `decide` checks an op against a card and gives the op as its card/changed
  event records it. `add` and `change` fold those events into the cards,
  and invent nothing: every id is already in the event.

  The data says what a card means, never how to draw it. `text-form` is
  what a role reads and the command line prints, and `wire` is what a
  client is sent, with every text field already made safe to show.

  A card's id is the seq of the event that added it. Its elements
  (checklist items, tracker rows, tracker columns) get small integer ids
  from counters of the card's own, starting at 1. Nothing a role chooses
  becomes an id.

  Labels: every text element (the title, each item, row and column, a
  note's text) keeps the labels of the event that last wrote it, and that
  event's seq as its :rev. Ticks, cell states, archive and restore carry no
  text, so they add no labels to the card. `text-labels` is the union of
  the labels of the text the card shows now; relay.core adds private."
  (:require [clojure.string :as str]
            [relay.text :as text])
  (:import (java.time Instant)))

(def kinds
  "The kinds of card, a closed set. A kind's meaning never changes once
  shipped; a new one needs its own checks, ops, text form and wire form."
  ["checklist" "tracker" "note"])

(def cell-states
  "What a tracker cell can be. open is the default and is not stored."
  ["open" "done" "skip"])

(def ^:private all-kinds (set kinds))

(def ops
  "Every op, with the kinds it applies to, the arguments it needs, and
  whether it changes text, so that `seen` applies to it."
  {"retitle" {:kinds all-kinds :args [:text] :text :title}
   "archive" {:kinds all-kinds :args []}
   "restore" {:kinds all-kinds :args []}
   "add" {:kinds #{"checklist"} :args [:text]}
   "tick" {:kinds #{"checklist"} :args [:item]}
   "untick" {:kinds #{"checklist"} :args [:item]}
   "edit" {:kinds #{"checklist"} :args [:item :text] :text :item}
   "remove" {:kinds #{"checklist"} :args [:item]}
   "set" {:kinds #{"tracker"} :args [:row :column :state]}
   "add_row" {:kinds #{"tracker"} :args [:text]}
   "add_column" {:kinds #{"tracker"} :args [:text]}
   "rename_row" {:kinds #{"tracker"} :args [:row :text] :text :row}
   "rename_column" {:kinds #{"tracker"} :args [:column :text] :text :column}
   "remove_row" {:kinds #{"tracker"} :args [:row]}
   "remove_column" {:kinds #{"tracker"} :args [:column]}
   "write" {:kinds #{"note"} :args [:text] :text :note}})

(def op-fields
  "The fields of the one flat op shape that tools, the command line and
  clients all use."
  [:op :item :row :column :state :text :seen])

;; --- limits

(def max-line "The most characters in a title or an item, row or column." 200)
(def max-note "The most characters in a note." 8000)
(def max-items "The most items on a checklist." 100)
(def max-rows "The most rows on a tracker." 40)
(def max-columns "The most columns on a tracker." 24)
(def max-active "The most cards on the board at once." 30)

;; --- refusals

(defn- refuse!
  "Refuses, as relay.core/reject! does: an ex-info carrying
  :relay/rejected. No refusal here quotes a card's text, only numbers and
  words of the caller's own, so none carries labels."
  ([message] (refuse! :invalid message))
  ([reason message] (throw (ex-info message {:relay/rejected reason}))))

(defn- union [& colls] (vec (sort (distinct (apply concat colls)))))

(defn- contaminated? [labels] (text/contaminated? labels))

;; --- queries

(defn active? [card] (= :active (:status card)))

(defn active-count [cards] (count (filter active? (vals cards))))

(defn- element-key
  "Where a card keeps the elements an op argument names."
  [arg]
  ({:item :items :row :rows :column :columns} arg))

(defn element [card arg id]
  (first (filter #(= id (:id %)) (get card (element-key arg)))))

(defn progress
  "{:done :total}: a checklist's items done of all of them, a tracker's
  cells done or skipped of rows times columns. Nil for a note."
  [{:keys [kind items rows columns cells]}]
  (case kind
    "checklist" {:done (count (filter :done items)) :total (count items)}
    "tracker" {:done (count cells) :total (* (count rows) (count columns))}
    nil))

(defn complete?
  "A checklist with at least one item and every item done, or a tracker
  with at least one cell and every cell done or skipped. Never a note."
  [card]
  (let [{:keys [done total]} (progress card)]
    (boolean (and total (pos? total) (= done total)))))

(defn text-elements
  "The text the card shows now: its title, items, rows, columns and note."
  [{:keys [title items rows columns note]}]
  (concat [title] items rows columns (when note [note])))

(defn text-labels
  "The union of the labels of the text the card shows now. Removing an
  element, or writing it again clean, takes its labels off the card."
  [card]
  (apply union (map :labels (text-elements card))))

(defn revs
  "Each card's rev, by id."
  [cards]
  (into {} (map (fn [[id c]] [id (:rev c)])) cards))

(defn changed-since
  "The ids of the cards whose rev is not the one in `sent` (revs), in
  order."
  [sent cards]
  (vec (sort (keep (fn [[id c]] (when (not= (get sent id) (:rev c)) id)) cards))))

;; --- checking text

(defn- line-text
  "One line of a card's text, trimmed: 1 to max-line characters, with no
  line break."
  [s what]
  (when-not (string? s) (refuse! (str what " must be text.")))
  (let [t (str/trim s)]
    (when (re-find #"[\n\r\u000b\u000c\u0085\u2028\u2029]" t)
      (refuse! (str what " is one line, with no line breaks.")))
    (when (empty? t) (refuse! (str what " must not be empty.")))
    (when (> (count t) max-line) (refuse! (str what " is at most " max-line " characters.")))
    t))

(defn- note-text [s]
  (when-not (string? s) (refuse! "A note's text must be text."))
  (when (> (count s) max-note) (refuse! (str "A note is at most " max-note " characters.")))
  s)

(defn- line-texts
  "A list of lines of text as elements with ids from 1."
  [xs what most plural]
  (when-not (or (nil? xs) (and (sequential? xs) (every? string? xs)))
    (refuse! (str plural " must be a list of text.")))
  (when (> (count xs) most)
    (refuse! (str "A card has at most " most " " (str/lower-case plural) ".")))
  (vec (map-indexed (fn [i t] {:id (inc i) :text (line-text t what)}) xs)))

;; --- adding a card

(defn- room! [cards]
  (when (>= (active-count cards) max-active)
    (refuse! :conflict (str "The board holds at most " max-active " active cards. Archive one first."))))

(defn added
  "The data of the card/added event for a new card, or a refusal. `cards`
  are the cards there are now; `args` has :kind and :title, and :items for
  a checklist, :rows and :columns for a tracker, or :text for a note."
  [cards {:keys [kind title items rows columns text] :as args}]
  (when-not (all-kinds kind)
    (refuse! (str "A card's kind is checklist, tracker or note, not " (pr-str kind) ".")))
  (let [others (keep (fn [k] (when (some? (get args k)) (name k)))
                     (case kind
                       "checklist" [:rows :columns :text]
                       "tracker" [:items :text]
                       "note" [:items :rows :columns]))]
    (when (seq others)
      (refuse! (str "A " kind " takes " (case kind "checklist" "items" "tracker" "rows and columns" "note" "text")
                    ", not " (str/join " or " others) "."))))
  (room! cards)
  (let [title (line-text title "A card's title")]
    (case kind
      "checklist" (let [items (line-texts items "An item" max-items "Items")]
                    {:card-kind kind :title title :items items :next {:item (inc (count items))}})
      "tracker" (let [rows (line-texts rows "A row" max-rows "Rows")
                      columns (line-texts columns "A column" max-columns "Columns")]
                  {:card-kind kind :title title :rows rows :columns columns
                   :next {:row (inc (count rows)) :column (inc (count columns))}})
      "note" {:card-kind kind :title title :text (note-text (or text "")) :next {}})))

;; --- deciding an op

(defn- number! [op v what]
  (when (nil? v) (refuse! (str op " needs " what ".")))
  (when-not (integer? v) (refuse! (str what " must be a number."))))

(defn- element! [card op arg id]
  (number! op id (name arg))
  (or (element card arg id)
      (refuse! :not-found (str "Card " (:id card) " has no " (name arg) " " id "."))))

(defn- stale!
  "Refuses a text edit made by a client that last saw rev `seen`, when
  the text it changes was written after that."
  [what {:keys [rev]} seen]
  (when (some? seen)
    (when-not (integer? seen) (refuse! "seen must be a rev, a number."))
    (when (> rev seen)
      (refuse! :conflict (str what " was changed at rev " rev ", after rev " seen
                              ". Read the card again before you change it.")))))

(defn- unchanged
  "What `decide` gives for an op that would change nothing: the message,
  and the labels of the text it says something about."
  [message & [labels]]
  {:unchanged message :labels (vec labels)})

(defn decide
  "What op `raw` does to card `id` among `cards`: {:op decided}, the op
  as its card/changed event records it, with any id it assigns written
  in; {:unchanged message :labels} when it would change nothing; or a
  refusal. `seen`, a rev, makes an edit of text written after it a
  refusal, so a stale edit never overwrites a newer one. Ticks and cell
  states are last writer wins."
  [cards id {:keys [op item row column state text seen]}]
  (let [card (get cards id)
        spec (get ops op)]
    (when-not card (refuse! :not-found (str "There is no card " id ".")))
    (when-not spec
      (refuse! (str "There is no card op " (pr-str op) ". The ops are " (str/join ", " (sort (keys ops))) ".")))
    (when-not ((:kinds spec) (:kind card))
      (refuse! (str op " is not an op on a " (:kind card) ". A " (:kind card) "'s ops are "
                    (str/join ", " (sort (keep (fn [[o s]] (when ((:kinds s) (:kind card)) o)) ops))) ".")))
    (when (and (not (active? card)) (not= "restore" op))
      (refuse! (str "Card " id " is archived. Restore it first.")))
    (let [decided (fn [m] {:op (merge {:op op} m)})
          {:keys [next]} card]
      (case op
        "retitle" (let [t (line-text text "A card's title")]
                    (stale! "The title" (:title card) seen)
                    (if (= t (get-in card [:title :text]))
                      (unchanged (str "Card " id " already has that title.") (get-in card [:title :labels]))
                      (decided {:text t})))
        "archive" (decided {})
        "restore" (if (active? card)
                    (unchanged (str "Card " id " is already on the board."))
                    (do (room! cards) (decided {})))
        "add" (let [t (line-text text "An item")]
                (when (>= (count (:items card)) max-items)
                  (refuse! (str "A checklist has at most " max-items " items.")))
                (decided {:item (:item next) :text t}))
        ("tick" "untick") (let [el (element! card op :item item)
                                done (= "tick" op)]
                            (if (= done (:done el))
                              (unchanged (str "Item " item " is already " (if done "ticked" "unticked") "."))
                              (decided {:item item})))
        "edit" (let [el (element! card op :item item)
                     t (line-text text "An item")]
                 (stale! (str "Item " item) el seen)
                 (if (= t (:text el))
                   (unchanged (str "Item " item " already says that.") (:labels el))
                   (decided {:item item :text t})))
        "remove" (do (element! card op :item item) (decided {:item item}))
        "set" (do (element! card op :row row)
                  (element! card op :column column)
                  (when-not (some #{state} cell-states)
                    (refuse! (str "A cell's state is open, done or skip, not " (pr-str state) ".")))
                  (if (= state (get-in card [:cells [row column]] "open"))
                    (unchanged (str "Row " row ", column " column " is already " state "."))
                    (decided {:row row :column column :state state})))
        ("add_row" "add_column") (let [arg (if (= "add_row" op) :row :column)
                                       [most plural] (if (= :row arg) [max-rows "rows"] [max-columns "columns"])
                                       t (line-text text (str "A " (name arg)))]
                                   (when (>= (count (get card (element-key arg))) most)
                                     (refuse! (str "A tracker has at most " most " " plural ".")))
                                   (decided {arg (get next arg) :text t}))
        ("rename_row" "rename_column") (let [arg (if (= "rename_row" op) :row :column)
                                             n (if (= :row arg) row column)
                                             el (element! card op arg n)
                                             t (line-text text (str "A " (name arg)))]
                                         (stale! (str (str/capitalize (name arg)) " " n) el seen)
                                         (if (= t (:text el))
                                           (unchanged (str (str/capitalize (name arg)) " " n " already says that.")
                                                      (:labels el))
                                           (decided {arg n :text t})))
        ("remove_row" "remove_column") (let [arg (if (= "remove_row" op) :row :column)
                                             n (if (= :row arg) row column)]
                                         (element! card op arg n)
                                         (decided {arg n}))
        "write" (let [t (note-text text)]
                  (stale! "The note" (:note card) seen)
                  (if (= t (get-in card [:note :text]))
                    (unchanged (str "Card " id "'s note already says that.") (get-in card [:note :labels]))
                    (decided {:text t})))))))

;; --- folding events

(defn- written
  "A text element as an event writes it."
  [{:keys [seq labels]} m]
  (assoc m :labels (union labels) :rev seq))

(defn add
  "The cards once a card/added event is folded in."
  [cards {:keys [seq at actor data] :as event}]
  (let [{:keys [card-kind title items rows columns text next]} data
        el #(written event (select-keys % [:id :text]))]
    (assoc cards seq
           (cond-> {:id seq :kind card-kind :owner actor :added-at (Instant/parse at) :rev seq
                    :status :active :title (written event {:text title}) :next next}
             (= "checklist" card-kind) (assoc :items (mapv #(assoc (el %) :done false) items))
             (= "tracker" card-kind) (assoc :rows (mapv el rows) :columns (mapv el columns) :cells {})
             (= "note" card-kind) (assoc :note (written event {:text text}))))))

(defn- update-element [card arg id f]
  (update card (element-key arg) (fn [els] (mapv #(if (= id (:id %)) (f %) %) els))))

(defn- remove-element [card arg id]
  (update card (element-key arg) (fn [els] (filterv #(not= id (:id %)) els))))

(defn- append-element
  "A new element at the end, its id taken from the op, and the card's
  counter moved past it."
  [card arg el]
  (-> card
      (update (element-key arg) conj el)
      (update-in [:next arg] #(max (or % 1) (inc (:id el))))))

(defn apply-op
  "A card once a decided op, written by `event`, is applied to it."
  [card {:keys [op item row column state text]} event]
  (let [rewrite #(written event (assoc % :text text))]
    (case op
      "retitle" (update card :title rewrite)
      "archive" (assoc card :status :archived)
      "restore" (assoc card :status :active)
      "add" (append-element card :item (written event {:id item :text text :done false}))
      "tick" (update-element card :item item #(assoc % :done true))
      "untick" (update-element card :item item #(assoc % :done false))
      "edit" (update-element card :item item rewrite)
      "remove" (remove-element card :item item)
      "set" (if (= "open" state)
              (update card :cells dissoc [row column])
              (assoc-in card [:cells [row column]] state))
      "add_row" (append-element card :row (written event {:id row :text text}))
      "add_column" (append-element card :column (written event {:id column :text text}))
      "rename_row" (update-element card :row row rewrite)
      "rename_column" (update-element card :column column rewrite)
      "remove_row" (-> (remove-element card :row row)
                       (update :cells #(into {} (remove (fn [[[r _] _]] (= r row))) %)))
      "remove_column" (-> (remove-element card :column column)
                          (update :cells #(into {} (remove (fn [[[_ c] _]] (= c column))) %)))
      "write" (update card :note rewrite))))

(defn change
  "The cards once a card/changed event is folded in."
  [cards {:keys [seq data] :as event}]
  (update cards (:card data) #(assoc (apply-op % (:op data) event) :rev seq)))

;; --- the text form

(defn- plain-mark [_labels s] s)

(defn- plain-block [_labels s]
  (map #(str text/gutter %) (str/split s #"\n" -1)))

(defn- header [{:keys [id kind status] :as card} mark]
  (let [{:keys [done total] :as p} (progress card)]
    (str "Card " id ": " (mark (get-in card [:title :labels]) (get-in card [:title :text]))
         " (" kind
         (when p (str ", " done " of " total " done"))
         (when (complete? card) ", complete")
         (when-not (active? card) ", archived")
         ") [[card:" id "]]")))

(defn- tracker-lines [{:keys [rows columns cells]} mark]
  (let [col-text (fn [c] (mark (:labels c) (:text c)))]
    (concat
     [(if (seq columns)
        (str "Columns: " (str/join ", " (for [c columns] (str (:id c) " " (col-text c)))))
        "Columns: none yet")]
     (if (empty? rows)
       ["No rows yet."]
       (for [r rows
             :let [states (for [c columns] (get cells [(:id r) (:id c)] "open"))]]
         (str "Row " (:id r) " " (mark (:labels r) (:text r)) ": "
              (cond (empty? columns) "no columns yet"
                    (apply = states) (str "all " (first states))
                    :else (str/join ", " (map (fn [c s] (str (col-text c) " " s)) columns states)))))))))

(defn text-lines
  "A card as lines of text, with the ids of its elements so a role can
  name them in an op. Every element's text is one line (decide makes
  sure), and a note's text sits behind relay.text's gutter. `mark` (fn
  [labels text]) and `block` (fn [labels text] lines) render one-line
  and multi-line text from a source with those labels; by default they
  leave it as it is."
  ([card] (text-lines card {}))
  ([{:keys [kind items note] :as card} {:keys [mark block] :or {mark plain-mark block plain-block}}]
   (vec (concat
         [(header card mark)]
         (case kind
           "checklist" (if (empty? items)
                         ["No items yet."]
                         (for [{:keys [id text done labels]} items]
                           (str (if done "[x] " "[ ] ") id " " (mark labels text))))
           "tracker" (tracker-lines card mark)
           "note" (if (str/blank? (:text note))
                    ["The note is empty."]
                    (block (:labels note) (:text note)))
           [])))))

(defn text-form
  "A card as one text: what roles read, what the command line prints,
  and what a client shows for a kind it does not know."
  ([card] (text-form card {}))
  ([card opts] (str/join "\n" (text-lines card opts))))

;; --- the wire form

(defn safe-text
  "Text as a client is sent it: defanged when its labels are
  contaminated, then with every hidden character made visible."
  [labels s]
  (text/visible (if (contaminated? labels) (text/defang s) (str s))))

(defn- wire-element [{:keys [id text labels rev]}]
  (cond-> {"text" (safe-text labels text) "outsider" (contaminated? labels) "rev" rev}
    id (assoc "id" id)))

(defn wire
  "A card as a client is sent it: string keys, JSON-ready, and every text
  field already safe to show (safe-text), with outsider beside it when it
  is contaminated. A client works nothing out for itself. `labels` is what
  reading the card adds (relay.core/card-labels). `text` is the text form,
  for a client that does not know the kind."
  [{:keys [id kind status rev owner added-at title items rows columns cells note] :as card} labels]
  (let [p (progress card)]
    (cond-> {"id" id "kind" kind "status" (name status) "complete" (complete? card)
             "rev" rev "owner" owner "addedAt" (str added-at)
             "title" (wire-element title)
             "labels" (vec labels)
             "text" (text-form card {:mark (fn [ls s] (text/visible (text/for-operator ls s)))
                                     :block (fn [ls s] (map text/visible (text/block ls s)))})}
      p (assoc "progress" {"done" (:done p) "total" (:total p)})
      (= "checklist" kind) (assoc "items" (mapv #(assoc (wire-element %) "done" (:done %)) items))
      (= "tracker" kind) (assoc "rows" (mapv wire-element rows)
                                "columns" (mapv wire-element columns)
                                ;; Only the cells that are not open.
                                "cells" (vec (for [[[r c] s] (sort-by key cells)]
                                               {"row" r "column" c "state" s})))
      (= "note" kind) (assoc "note" (wire-element note)))))

;; --- what an op did, in words

(defn confirmation
  "A short sentence saying what a decided op did to card `id`. It quotes
  no card text, so it carries no labels."
  [id {:keys [op item row column state]}]
  (case op
    "retitle" (str "Retitled card " id ".")
    "archive" (str "Archived card " id ".")
    "restore" (str "Put card " id " back on the board.")
    "add" (str "Added item " item " to card " id ".")
    "tick" (str "Ticked item " item " on card " id ".")
    "untick" (str "Unticked item " item " on card " id ".")
    "edit" (str "Changed item " item " on card " id ".")
    "remove" (str "Removed item " item " from card " id ".")
    "set" (str "Set row " row ", column " column " of card " id " to " state ".")
    "add_row" (str "Added row " row " to card " id ".")
    "add_column" (str "Added column " column " to card " id ".")
    "rename_row" (str "Renamed row " row " on card " id ".")
    "rename_column" (str "Renamed column " column " on card " id ".")
    "remove_row" (str "Removed row " row " from card " id ".")
    "remove_column" (str "Removed column " column " from card " id ".")
    "write" (str "Wrote the note on card " id ".")))
