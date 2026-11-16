(ns relay.surfaces
  "What is inside a surface: a screen a role lays out for the operator's
  board from relay's catalog, a small part of A2UI v1.0
  (https://a2ui.org). Pure. relay.cards keeps a surface as one of its
  kinds of card and calls this namespace for the rest.

  A role writes two things. `components` is a flat list: each component
  has an id, a type and that type's properties, and names its children by
  id, and the one with the id root holds the others. `data` is the JSON
  the components read: a property may hold {\"@path\": \"/a/path\"} where
  it takes a value, a text may be a formatString call that fills
  ${/a/path} spots, and a container may draw one child per item of a
  list in the data.

  relay does the reading, not a client. `drawn` resolves a surface to a
  tree of components that hold final strings, and everything a reader is
  shown comes from that tree: `lines` for the text form and `message`
  for a client. So the link check runs on each string as the operator
  reads it, and no client can be handed two safe strings that it joins
  into an address. The only bindings a client is sent are the paths the
  operator's inputs write.

  `components!` and `data!` check what a role wrote and refuse what the
  catalog does not have, saying what to change. `written` and `removed`
  change one path of a data model. Nothing here knows about labels, revs
  or events; relay.cards does."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [relay.json :as strict]))

(def catalog-id "The id of relay's catalog, which a client is sent with every surface." "urn:relay:catalog:v1")

(def version "The A2UI version relay writes." "1.0")

;; --- limits
;;
;; Each is at or under what relay-tui's renderer takes (2,000 components,
;; 1,500 drawn, 16 deep, 500 items in a list), so a surface relay accepts
;; is one a client draws whole.

(def max-components "The most components a role may write for one surface." 200)
(def max-drawn "The most components a surface may come to once its lists are drawn." 1000)
(def max-depth "How deep components may nest, root being 1." 12)
(def max-list "The most items in a list of a data model, or children of one component." 200)
(def max-text "The most characters (code points) in one string." 8000)
(def max-shown "The most characters a surface may show in all, once it is resolved." 16000)
(def max-written
  "The most characters a surface's components and data may be together, as
  JSON. What a surface keeps is bounded as what it shows is: a role that
  reads the card is handed all of it."
  64000)
(def max-reads
  "The most times drawing a surface may read its data. A text may hold
  thousands of ${...} spots and a list may draw it hundreds of times, so
  without this a small surface could take seconds to draw."
  20000)
(def max-values "The most values a data model may hold, counting every list, object and leaf." 4000)
(def max-data-depth "How deep a data model may nest." 12)
(def max-path "The most parts in a path." 16)
(def max-whole "The largest whole number, either way: 2^53, which every reader of JSON holds exactly." 9007199254740992)

;; --- the catalog

(def catalog
  "The components a surface may hold, in the order relay lists them, each
  with the properties it takes. A property is :text (a string, a path or
  a call), :flag (true, false or a path), :one-of some words, :child (an
  id) or :children (ids, or a template). Anything else on a component is
  refused: relay
  takes only what a terminal can draw, so a role is never told that a
  hint worked when nothing drew it."
  [["Text" {:says "plain text, not Markdown"
            :props [["text" {:is :text :required true}]
                    ["variant" {:is :one-of :of ["body" "caption"]}]]}]
   ["Row" {:says "its children side by side"
           :props [["children" {:is :children :required true}]]}]
   ["Column" {:says "its children one under another"
              :props [["children" {:is :children :required true}]]}]
   ["List" {:says "its children as a list"
            :props [["children" {:is :children :required true}]
                    ["direction" {:is :one-of :of ["vertical" "horizontal"]}]]}]
   ["Card" {:says "one child in a box"
            :props [["child" {:is :child :required true}]]}]
   ["Divider" {:says "a rule"
               :props [["axis" {:is :one-of :of ["horizontal" "vertical"]}]]}]
   ["CheckBox" {:says "a box with a one-line label, which the operator ticks when its value is a path to true or false"
                :props [["label" {:is :text :required true}]
                        ["value" {:is :flag :required true}]]}]
   ;; A2UI's Button, without its borderless variant: relay-tui draws that
   ;; underlined, as it draws a link one can follow.
   ["Button" {:says "a button the operator presses, showing its child, a Text, as one line"
              :props [["child" {:is :child :required true}]
                      ["action" {:is :action :required true}]
                      ["variant" {:is :one-of :of ["primary"]}]]}]])

(def ^:private by-type (into {} catalog))

(def ^:private words
  "For each component, the words each of its :one-of properties may hold."
  (into {} (for [[type {:keys [props]}] catalog]
             [type (into {} (for [[k {:keys [is of]}] props :when (= :one-of is)] [k (set of)]))])))

(def functions
  "The functions a surface may call: formatString, which fills ${...}
  spots in a text with data, and @index, an item's position in the list
  that draws it."
  ["formatString" "@index"])

(def set-input
  "The one function a Button may call: it sets a box of the same surface,
  as the operator's tick does, with no model turn (Kira, 2026-11-17,
  relay#106)."
  "relay:setInput")

;; --- refusals

(defn- refuse!
  "Refuses, as relay.cards does: an ex-info carrying :relay/rejected.
  A refusal quotes only what the caller wrote in the call it refuses: a
  component's position and id, never text a surface already holds."
  ([message] (refuse! :invalid message))
  ([reason message] (throw (ex-info message {:relay/rejected reason}))))

;; --- plain JSON

(defn plain
  "JSON data as this namespace keeps it: every member name a string,
  every list a vector, every whole number a long. An event's data comes
  with strings from a tool or a client and with keywords from the log,
  and both read the same after this. A keyword's whole text is its name,
  slash included."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(if (keyword? k) (subs (str k) 1) (str k)) (plain v)])) x)
    (sequential? x) (mapv plain x)
    (and (integer? x) (<= Long/MIN_VALUE x Long/MAX_VALUE)) (long x)
    :else x))

(defn- characters [^String s] (.codePointCount s 0 (.length s)))

(defn- whole? [x] (and (integer? x) (<= (- max-whole) x max-whole)))

(defn- json
  "A value as JSON text with its members in the order of their names, so
  the same value always reads the same."
  [x]
  (cond
    (map? x) (str "{" (str/join "," (for [[k v] (sort-by key x)] (str (strict/generate k) ":" (json v)))) "}")
    (sequential? x) (str "[" (str/join "," (map json x)) "]")
    :else (strict/generate x)))

;; --- ids, keys and paths

(def ^:private id-pattern #"[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)*")

(defn- id?
  "Whether `s` can be a component's id: letters and digits, with single
  underscores between them, starting with a letter. An id never holds
  two underscores together, which is what relay puts before an item's
  position when it draws a list."
  [s]
  (boolean (and (string? s) (<= (count s) 64) (re-matches id-pattern s))))

(def ^:private key-pattern #"[A-Za-z0-9_-]{1,64}")

(defn- key?
  "Whether `s` can be a key of a data model. A - alone cannot: in a path
  it is the place after a list's last item."
  [s]
  (boolean (and (string? s) (not= "-" s) (re-matches key-pattern s))))

(defn path
  "A path as a component or an op writes it, as {:absolute? :parts}, or
  nil when it is not one. /items/0/done is absolute. / alone is the whole
  data model. A path with no leading / is read from the item a list's
  template is drawing, and the empty path is that item. Each part is a
  key of the data model or a position in a list, so a path needs no
  escaping. A last part of - is the place after a list's last item,
  which only a write names."
  [s]
  (when (string? s)
    (cond
      (= "/" s) {:absolute? true :parts []}
      (= "" s) {:absolute? false :parts []}
      :else (let [absolute? (str/starts-with? s "/")
                  parts (str/split (if absolute? (subs s 1) s) #"/" -1)]
              (when (and (<= (count parts) max-path)
                         (every? key? (butlast parts))
                         (or (key? (last parts)) (= "-" (last parts))))
                {:absolute? absolute? :parts (vec parts)})))))

(defn- reading-path
  "A path a component reads: any path but one that ends in -, where
  there is nothing to read."
  [s]
  (let [p (path s)]
    (when-not (= "-" (peek (:parts p))) p)))

(defn- path-text [parts] (str "/" (str/join "/" parts)))

(defn- position
  "The position a path's part names in a list, or nil."
  [part]
  (when (re-matches #"0|[1-9][0-9]{0,8}" part) (parse-long part)))

(defn at
  "What a data model holds at `parts`, or nil."
  [data parts]
  (reduce (fn [node part]
            (cond
              (map? node) (get node part)
              (vector? node) (when-let [i (position part)] (get node i))
              :else (reduced nil)))
          data parts))

(defn- blocked
  "Why nothing can be written at `parts` of a data model, or nil. A key
  that is missing on the way is made, as an object. A list is never made
  that way: a position, or a -, where there is no list is refused, since
  relay cannot tell that a list was meant and would make an object with
  that name. A list has to hold the position already, all but a last
  part of -, which adds to it."
  [data parts]
  (loop [node data
         [part & more] parts]
    (cond
      (nil? part) nil
      (or (nil? node) (map? node))
      (cond
        (= "-" part) "there is no list there to add to. Write the list first"
        (and (nil? node) (position part)) "there is no list there to hold that position. Write the list first"
        :else (recur (get node part) more))
      (vector? node) (let [i (position part)]
                       (cond
                         (and (= "-" part) (nil? more)) nil
                         (and i (< i (count node))) (recur (nth node i) more)
                         :else "a list on the way has no such item"))
      :else "it runs through a value that is neither a list nor an object")))

(defn- put [node [part & more] v]
  (cond
    (nil? part) v
    (vector? node) (if (= "-" part)
                     (conj node v)
                     (let [i (position part)] (assoc node i (put (nth node i) more v))))
    :else (assoc (if (map? node) node {}) part (put (get node part) more v))))

(defn- cut [node [part & more]]
  (cond
    (map? node) (cond
                  (not (contains? node part)) node
                  more (assoc node part (cut (get node part) more))
                  :else (dissoc node part))
    (vector? node) (let [i (position part)]
                     (cond
                       (not (and i (< i (count node)))) node
                       more (assoc node i (cut (nth node i) more))
                       :else (into (subvec node 0 i) (subvec node (inc i)))))
    :else node))

;; --- checking a data model

(defn- count-values
  "How many values `v` holds, itself included, or a refusal when a data
  model cannot hold it."
  [v depth]
  (when (> depth max-data-depth)
    (refuse! (str "A data model nests at most " max-data-depth " deep.")))
  (cond
    (string? v) (do (when (> (characters v) max-text)
                      (refuse! (str "A text in a data model is at most " max-text " characters.")))
                    1)
    (boolean? v) 1
    (integer? v) (do (when-not (whole? v)
                       (refuse! "A number in a data model is a whole number no larger than 2^53. Write any other as text."))
                     1)
    (number? v) (refuse! "A number in a data model is a whole number. Write any other as text, such as \"12.5\".")
    (nil? v) (refuse! "A data model holds no null. Leave the key out.")
    (map? v) (do (when-not (every? key? (keys v))
                   (refuse! "A key in a data model is 1 to 64 letters, digits, underscores or hyphens, and not a - by itself."))
                 (reduce + 1 (map #(count-values % (inc depth)) (vals v))))
    (vector? v) (do (when (> (count v) max-list)
                      (refuse! (str "A list in a data model holds at most " max-list " items.")))
                    (reduce + 1 (map #(count-values % (inc depth)) v)))
    :else (refuse! "A data model holds text, whole numbers, true and false, lists and objects.")))

(defn data!
  "A data model as relay keeps it, or a refusal that says what to
  change. Nothing given is an empty one."
  [x]
  (let [x (plain (if (nil? x) {} x))]
    (when-not (map? x) (refuse! "A surface's data is a JSON object."))
    (when (> (count-values x 1) max-values)
      (refuse! (str "A data model holds at most " max-values " values.")))
    x))

(defn written
  "A data model with `value` written at the absolute path `p` (as `path`
  reads it), checked again whole, or a refusal. Writing at / replaces the
  model. A path ending in - adds to a list."
  [data p value]
  (let [parts (:parts p)]
    (when-let [why (blocked data parts)]
      (refuse! (str "Nothing can be written at that path: " why ".")))
    (data! (if (empty? parts) value (put data parts (plain value))))))

(defn removed
  "A data model without what it holds at the absolute path `p`. Removing
  an item of a list moves the later ones down. What is not there is left
  as it is, and the whole model cannot be removed."
  [data p]
  (when (empty? (:parts p))
    (refuse! "The whole data model cannot be removed. Write {} at / to empty it."))
  (cut data (:parts p)))

(defn folded
  "A data model once a recorded write is applied: `value` at the path
  `p`, or with nil what is there removed. It never refuses: the write was
  checked before it was recorded, and one that no longer fits, such as
  an event another relay recorded under other limits, leaves the model
  as it was."
  [data p value]
  (let [parts (:parts p)]
    (cond
      (not (:absolute? p)) data
      (nil? value) (if (empty? parts) data (cut data parts))
      (blocked data parts) data
      (empty? parts) (let [v (plain value)] (if (map? v) v data))
      :else (put data parts (plain value)))))

;; --- checking components

(defn- who
  "How a refusal names the component at index `i`: its position, and its
  id when it has a good one."
  [i c]
  (str "Component " (inc i) (when (id? (get c "id")) (str " (" (get c "id") ")"))))

(def ^:private path-help
  "A path is / and then keys or positions with / between them, such as /items/0/done. Inside a list's template it may start without the /.")

(defn- binding!
  "The path a {\"@path\": ...} holds."
  [w v]
  (when-not (= ["@path"] (keys v))
    (refuse! (str w ": a path is written {\"@path\": \"/a/path\"}, with nothing else beside it.")))
  (or (reading-path (get v "@path"))
      (refuse! (str w ": that is not a path. " path-help))))

(defn- template
  "A formatString's text as pieces, each a string or {:path p} for a
  ${...} spot, or nil when a spot is not closed or holds anything but one
  path. \\${ is a literal ${."
  [^String s]
  (let [n (count s)]
    (loop [i 0
           out []
           sb (StringBuilder.)]
      (let [flush (fn [] (if (pos? (.length sb)) (conj out (str sb)) out))]
        (cond
          (>= i n) (flush)
          (.startsWith s "\\${" i) (recur (+ i 3) out (.append sb "${"))
          (.startsWith s "${" i) (let [end (.indexOf s "}" (int i))
                                       p (when (pos? end) (reading-path (subs s (+ i 2) end)))]
                                   (when p
                                     (recur (inc end) (conj (flush) {:path p}) (StringBuilder.))))
          :else (recur (inc i) out (.append sb (.charAt s i))))))))

(defn- text!
  "Checks a :text property, and says whether it reads from a list's item."
  [w what v]
  (let [length! (fn [s] (when (> (characters s) max-text)
                          (refuse! (str w ": " what " is at most " max-text " characters."))))]
    (cond
      (string? v) (do (length! v) false)

      (and (map? v) (contains? v "@path"))
      (not (:absolute? (binding! w v)))

      (and (map? v) (= "formatString" (get v "@call")))
      (let [args (get v "args")
            text (when (map? args) (get args "value"))]
        (when-not (and (= #{"@call" "args"} (set (keys v))) (map? args) (= ["value"] (keys args)) (string? text))
          (refuse! (str w ": formatString is written {\"@call\": \"formatString\", \"args\": {\"value\": \"${/done} of ${/total}\"}}.")))
        (length! text)
        (let [pieces (or (template text)
                         (refuse! (str w ": in formatString, ${...} holds one path and nothing else, such as ${/done}."
                                       " Write a literal ${ as \\${.")))]
          (boolean (some #(and (map? %) (not (:absolute? (:path %)))) pieces))))

      (and (map? v) (= "@index" (get v "@call")))
      (let [args (get v "args" {})]
        (when-not (and (set/subset? (set (keys v)) #{"@call" "args"}) (map? args)
                       (set/subset? (set (keys args)) #{"offset"})
                       (or (not (contains? args "offset")) (whole? (get args "offset"))))
          (refuse! (str w ": @index is written {\"@call\": \"@index\"}, or with \"args\": {\"offset\": 1} to count from 1.")))
        true)

      (and (map? v) (contains? v "@call"))
      (refuse! (str w ": relay's catalog has two functions, formatString and @index."))

      :else
      (refuse! (str w ": " what " is text, {\"@path\": \"/a/path\"}, or a call of formatString or @index.")))))

(defn- bound!
  "Checks a path the operator's input writes: not the whole data model.
  Says whether it reads from a list's item."
  [w v]
  (let [p (binding! w v)]
    (when (and (:absolute? p) (empty? (:parts p)))
      (refuse! (str w ": an input cannot be bound to /, the whole data model.")))
    (not (:absolute? p))))

(def ^:private name-pattern #"[A-Za-z][A-Za-z0-9_]{0,63}")

(defn- action!
  "Checks a Button's action, and says whether it reads from a list's
  item. An event has a name and nothing else: relay writes the role's
  line itself (relay#98), so a userMessage or a context would never be
  sent. A function call calls relay:setInput, with the path of a box and
  true or false."
  [w v]
  (let [shape (str w ": an action is {\"event\": {\"name\": \"done\"}}, or {\"functionCall\": {\"@call\": \""
                   set-input "\", \"args\": {\"path\": \"/items/0/done\", \"value\": true}}}.")]
    (when-not (and (map? v) (= 1 (count v)) (#{"event" "functionCall"} (first (keys v))))
      (refuse! shape))
    (if-let [ev (get v "event")]
      (do (when-not (map? ev) (refuse! shape))
          (when (some #{"userMessage" "context"} (keys ev))
            (refuse! (str w ": an event has a name and nothing else. relay tells the role which button was"
                          " pressed, on which card and in which item, in words of its own, so a userMessage or a"
                          " context would never reach it.")))
          (when-not (and (= ["name"] (keys ev)) (string? (get ev "name"))
                         (re-matches name-pattern (get ev "name")))
            (refuse! (str w ": an event's name is letters, digits and underscores, starting with a letter,"
                          " at most 64 characters.")))
          false)
      (let [fc (get v "functionCall")
            call (when (map? fc) (get fc "@call"))
            args (when (map? fc) (get fc "args"))]
        (when-not (map? fc) (refuse! shape))
        (when-not (= set-input call)
          (refuse! (str w ": a button may call one function, " set-input ", which sets a box on the same card."
                        " relay's catalog has no other, openUrl included.")))
        (when-not (and (= #{"@call" "args"} (set (keys fc))) (map? args) (= #{"path" "value"} (set (keys args))))
          (refuse! shape))
        (when-not (boolean? (get args "value"))
          (refuse! (str w ": " set-input " sets a box to true or false.")))
        (let [p (or (reading-path (get args "path")) (refuse! (str w ": that is not a path. " path-help)))]
          (when (and (:absolute? p) (empty? (:parts p)))
            (refuse! (str w ": " set-input " sets a box, not /, the whole data model.")))
          (not (:absolute? p)))))))

(defn- children!
  "Checks a :children property. Returns {:ids :template? :scoped?}."
  [w v]
  (cond
    (vector? v)
    (do (when-not (every? id? v) (refuse! (str w ": children are a list of ids.")))
        (when (> (count v) max-list) (refuse! (str w ": a component has at most " max-list " children.")))
        {:ids v})

    (and (map? v) (= #{"componentId" "path"} (set (keys v))) (id? (get v "componentId")))
    (let [p (or (reading-path (get v "path")) (refuse! (str w ": the template's path is not a path. " path-help)))]
      {:ids [(get v "componentId")] :template? true :scoped? (not (:absolute? p))})

    :else (refuse! (str w ": children are a list of ids, or {\"componentId\": \"item\", \"path\": \"/items\"}"
                        " to draw one child for each item of a list in the data."))))

(defn- component!
  "Checks one component by itself. Returns {:id :ids :template? :scoped?}:
  its children's ids, whether it draws them from a template, and whether
  anything in it reads from a list's item."
  [i c]
  (let [w (who i c)
        type (get c "component")
        spec (get by-type type)]
    (when-not (id? (get c "id"))
      (refuse! (str w ": an id is letters and digits, starting with a letter, with single underscores between them,"
                    " at most 64 characters.")))
    (when-not spec
      (refuse! (str w ": relay's catalog has no such component. It has " (str/join ", " (map first catalog)) ".")))
    (let [props (into {} (:props spec))
          given (dissoc c "id" "component")]
      (when-let [extra (seq (remove props (keys given)))]
        (refuse! (str w ": a " type " takes " (str/join ", " (map first (:props spec))) " and nothing else."
                      (when (some #(str/starts-with? % "@") extra)
                        " relay's catalog has no @checks, @accessibility, @metadata or @catalogId."))))
      (doseq [[k {:keys [required]}] (:props spec)]
        (when (and required (not (contains? given k)))
          (refuse! (str w ": a " type " needs " k "."))))
      (reduce (fn [found [k v]]
                (let [{:keys [is of]} (get props k)
                      what (str "a " type "'s " k)]
                  (case is
                    :text (update found :scoped? #(or (text! w what v) %))
                    :flag (update found :scoped? #(or (if (boolean? v)
                                                         false
                                                         (if (and (map? v) (contains? v "@path"))
                                                           (bound! w v)
                                                           (refuse! (str w ": " what " is true, false or {\"@path\": \"/a/path\"}."))))
                                                       %))
                    :one-of (do (when-not (some #{v} of)
                                  (refuse! (str w ": " what " is " (str/join " or " of) ".")))
                                found)
                    :child (do (when-not (id? v) (refuse! (str w ": " what " is the id of one component.")))
                               (assoc found :ids [v]))
                    :action (update found :scoped? #(or (action! w v) %))
                    :children (let [{:keys [ids template? scoped?]} (children! w v)]
                                (-> found (assoc :ids ids :template? template?)
                                    (update :scoped? #(or scoped? %)))))))
              {:id (get c "id") :ids [] :template? false :scoped? false :who w}
              given))))

(defn components!
  "A role's components as relay keeps them, or a refusal that says what
  to change. Each is checked against the catalog, and together they must
  be one tree under root: every child is in the list, has one parent and
  is reached from root, at most max-depth deep. A path with no leading /
  and @index are taken only where a list's template draws the component."
  [xs]
  (let [xs (plain xs)]
    (when-not (and (vector? xs) (seq xs) (every? map? xs))
      (refuse! "A surface's components are a list of objects, at least one."))
    (when (> (count xs) max-components)
      (refuse! (str "A surface has at most " max-components " components.")))
    (let [found (vec (map-indexed component! xs))
          by-id (into {} (map (juxt :id identity)) found)]
      (when-not (apply distinct? (map :id found))
        (refuse! "Two components have the same id."))
      (when-not (contains? by-id "root")
        (refuse! "A surface needs one component with the id root, which holds the others."))
      (doseq [{:keys [ids] w :who} found
              id ids]
        (when-not (contains? by-id id) (refuse! (str w ": it names a child that is not in the list.")))
        (when (= "root" id) (refuse! (str w ": root cannot be a child."))))
      (when-let [[id] (first (filter #(> (val %) 1) (frequencies (mapcat :ids found))))]
        (refuse! (str "The component " id " is a child twice. A component has one parent.")))
      ;; One parent each and none for root, so what root reaches is a tree.
      (let [reached (volatile! #{})]
        ((fn walk [id depth scoped?]
           (let [{:keys [ids template?] :as c} (get by-id id)]
             (when (> depth max-depth)
               (refuse! (str "Components nest at most " max-depth " deep.")))
             (when (and (:scoped? c) (not scoped?))
               (refuse! (str (:who c) ": a path with no leading / and @index mean something only where a list's"
                             " template draws the component.")))
             (vswap! reached conj id)
             (doseq [k ids] (walk k (inc depth) (or scoped? template?)))))
         "root" 1 false)
        (when-let [c (first (remove #(@reached (:id %)) found))]
          (refuse! (str (:who c) ": root does not reach it. Make it a child of a component root reaches, or leave it out."))))
      xs)))

;; --- drawing: a surface resolved against its data

(defn- as-text
  "A value where text is wanted: text as it is, a number, true or false
  as written, and nothing for anything else. A list or an object reads
  as nothing, where A2UI would give its JSON: a text shows words, and
  the JSON of a whole data model in every spot of a formatString is how
  a small surface would come to gigabytes."
  [v]
  (cond
    (string? v) v
    (or (boolean? v) (integer? v)) (str v)
    :else ""))

(defn- one-line
  "Text for a place that holds one line: its line breaks made spaces."
  [s]
  (str/replace s #"[\n\r\u000b\u000c\u0085\u2028\u2029]+" " "))

(defn- parts-in
  "The absolute parts of a path read in `scope`, a list item's {:base
  :indices}, or nil at the top."
  [scope {:keys [absolute? parts]}]
  (if absolute? parts (into (vec (:base scope)) parts)))

(defn- typed
  "`parts` with each position in a list as a number, going by what
  `data` holds on the way."
  [data parts]
  (loop [node data
         [part & more] parts
         out []]
    (cond
      (nil? part) out
      (vector? node) (let [i (position part)] (recur (when i (get node i)) more (conj out (or i part))))
      :else (recur (when (map? node) (get node part)) more (conj out part)))))

(defn- place
  "A data model for a client with `v` at `parts` (typed): a list is made
  as long as it must be, with null where nothing was put."
  [node [part & more] v]
  (cond
    (nil? part) v
    (integer? part) (let [xs (if (vector? node) node [])
                          xs (into xs (repeat (- (inc part) (count xs)) nil))]
                      (assoc xs part (place (get xs part) more v)))
    :else (assoc (if (map? node) node {}) part (place (get node part) more v))))

(defn- fill
  "A :text property's final string in `scope`. `reads`, a volatile, is
  how many more times the surface may read its data."
  [data scope v reads]
  (let [read (fn [p]
               (when (neg? (vswap! reads dec))
                 (refuse! (str "The surface would read its data more than " max-reads " times to be drawn."
                               " Shorten a list, or put fewer ${...} spots in a text.")))
               (as-text (at data (parts-in scope p))))
        length! (fn [s] (when (> (characters s) max-text)
                          (refuse! (str "A text of the surface would be over " max-text " characters once its data is read.")))
                  s)]
    (cond
      (string? v) v
      (contains? v "@path") (length! (read (path (get v "@path"))))
      (= "formatString" (get v "@call"))
      (let [sb (StringBuilder.)]
        (doseq [piece (template (get-in v ["args" "value"]))]
          (.append sb ^String (if (string? piece) piece (read (:path piece))))
          ;; A character is one or two chars, so past twice the limit in
          ;; chars the text is too long whatever it holds. Checking as it
          ;; grows keeps what is built small.
          (when (> (.length sb) (* 2 max-text)) (length! (str sb))))
        (length! (str sb)))
      (= "@index" (get v "@call")) (str (+ (or (peek (:indices scope)) 0) (get-in v ["args" "offset"] 0)))
      :else "")))

(defn- resolved
  "A surface, {:components :data}, resolved: {:root node :inputs
  {path kind} :model data}, or a refusal when it cannot be drawn within
  the limits.

  A node is {:id :type} and what its type holds, every string final:
  Text :text, a container :children (nodes), Card :child, CheckBox
  :label :value, Button :child (its Text) and :action, {:event name} or
  {:call set-input :path :value} with the path resolved. A component a template draws has its id, __ and its
  item's position.

  A CheckBox bound to the data has :path, where the operator's input
  writes. :inputs has every such place, by path, with the kind of input
  it is (:checkbox), and :model is a data model holding the values at
  those places and nothing else.

  An input is bound only where the data holds true or false already. So
  a tick puts one of the two where the other was: it makes nothing,
  replaces no list or object, and cannot be refused for where it lands.
  It cannot be refused by a limit either, since no limit is checked on
  a tick: see `unticked`."
  [{:keys [components data]}]
  (let [by-id (into {} (map (juxt #(get % "id") identity)) components)
        left (volatile! max-drawn)
        room (volatile! max-shown)
        reads (volatile! max-reads)
        drawn-ids (volatile! #{})
        inputs (volatile! {})
        sets (volatile! [])
        model (volatile! {})
        say (fn [s]
              (when (neg? (vswap! room - (characters s)))
                (refuse! (str "The surface would show more than " max-shown " characters in all."
                              " Shorten its text or a list.")))
              s)
        node (fn node [id scope depth]
               (let [c (get by-id id)
                     type (get c "component")
                     nid (apply str id (map #(str "__" %) (:indices scope)))
                     base {:id nid :type type}
                     text (fn [k] (fill data scope (get c k) reads))
                     ;; One of the words the catalog gives a property, or
                     ;; nothing: whatever else a recorded component holds
                     ;; there is not sent on.
                     word (fn [k] ((get-in words [type k] #{}) (get c k)))
                     kids (fn []
                            (let [ch (get c "children")]
                              (if (vector? ch)
                                (mapv #(node % scope (inc depth)) ch)
                                (let [parts (parts-in scope (path (get ch "path")))
                                      items (at data parts)]
                                  (vec (for [i (range (if (vector? items) (count items) 0))]
                                         (node (get ch "componentId")
                                               {:base (conj parts (str i)) :indices (conj (vec (:indices scope)) i)}
                                               (inc depth))))))))]
                 ;; A component drawn twice would be sent with one id twice.
                 (when (or (nil? c) (not (id? id)) (> depth (* 2 max-depth)) (contains? @drawn-ids nid))
                   (refuse! "relay cannot draw this surface: its components are not one tree under root."))
                 (vswap! drawn-ids conj nid)
                 (when (neg? (vswap! left dec))
                   (refuse! (str "The surface would draw more than " max-drawn " components. Shorten a list.")))
                 (case type
                   "Text" (cond-> (assoc base :text (say (text "text")))
                            (word "variant") (assoc :variant (word "variant")))
                   ("Row" "Column") (assoc base :children (kids))
                   "List" (cond-> (assoc base :children (kids))
                            (word "direction") (assoc :direction (word "direction")))
                   "Card" (assoc base :child (node (get c "child") scope (inc depth)))
                   "Divider" (cond-> base (word "axis") (assoc :axis (word "axis")))
                   ;; The label is the one Text the button shows, drawn
                   ;; as one line, so what the operator pressed is one
                   ;; string relay can name to the role.
                   "Button" (let [kid (get c "child")
                                  _ (when-not (= "Text" (get-in by-id [kid "component"]))
                                      (refuse! (str "The Button " id " shows one Text as its child, the words the"
                                                    " operator presses.")))
                                  shows (update (node kid scope (inc depth)) :text one-line)
                                  act (get c "action")
                                  _ (action! (str "The Button " id) act)
                                  action (if-let [ev (get act "event")]
                                           {:event (get ev "name")}
                                           (let [args (get-in act ["functionCall" "args"])
                                                 at (path-text (parts-in scope (path (get args "path"))))]
                                             (vswap! sets conj [id at])
                                             {:call set-input :path at :value (get args "value")}))]
                              (cond-> (assoc base :child shows :action action)
                                (word "variant") (assoc :variant (word "variant"))))
                   "CheckBox" (let [v (get c "value")
                                    parts (when (map? v) (parts-in scope (path (get v "@path"))))
                                    held (if parts (at data parts) v)
                                    value (true? held)]
                                (when parts
                                  (when-not (boolean? held)
                                    (refuse! (str "A CheckBox is bound to a path where the data does not hold true or false."
                                                  " Write false there first, so a tick has somewhere to go.")))
                                  (vswap! inputs assoc (path-text parts) :checkbox)
                                  (vswap! model place (typed data parts) value))
                                (cond-> (assoc base :label (say (one-line (text "label"))) :value value)
                                  parts (assoc :path (path-text parts))))
                   (refuse! "relay cannot draw this surface: it holds a component the catalog does not have."))))
        root (node "root" nil 1)]
    ;; A button sets only what the operator could tick by hand.
    (doseq [[id at] @sets]
      (when-not (contains? @inputs at)
        (refuse! (str "The Button " id " sets " at ", where no CheckBox is bound. " set-input
                      " sets a box on the same surface."))))
    {:root root :inputs @inputs :model (if (map? @model) @model {})}))

(defn drawn
  "A surface, {:components :data}, resolved (see `resolved`), or a
  refusal when it cannot be drawn. Components and data that never went
  through `components!` and `data!`, such as those of an event another
  relay recorded or someone edited by hand, never raise anything but a
  refusal. What can be drawn of them is, with only what the catalog
  knows: an id that is not one is refused, and a property the component
  does not take, or a word it does not allow, is left out."
  [surface]
  (try (resolved surface)
       (catch Exception e
         (if (:relay/rejected (ex-data e))
           (throw e)
           (refuse! "relay cannot draw this surface: its components or its data are not what the catalog allows.")))))

(def undrawable
  "What stands in for a surface that cannot be drawn, so that showing a
  card never fails. Every change is drawn before it is recorded, so only
  a surface an older or newer relay recorded under other limits comes to
  this."
  {:root {:id "root" :type "Text" :text "relay cannot draw this surface."} :inputs {} :model {}})

(defn shown
  "`drawn`, or `undrawable` when the surface cannot be drawn."
  [surface]
  (try (drawn surface)
       (catch clojure.lang.ExceptionInfo e
         (if (:relay/rejected (ex-data e)) undrawable (throw e)))))

(defn unticked
  "A surface as it is with every box unticked: false at each path an
  input is bound to. true is shorter than false wherever a surface shows
  or sends it, so this is a surface at its longest. relay.cards checks
  the limits on it, and so a tick, which only ever puts true where this
  has false, is never what takes a surface past one."
  [surface]
  (let [{:keys [inputs]} (drawn surface)]
    (assoc surface :data (reduce (fn [data p] (put data (:parts (path p)) false)) (:data surface) (keys inputs)))))

(defn written-size
  "How many characters a surface's components and data are as JSON."
  [{:keys [components data]}]
  (+ (count (json components)) (count (json data))))

(defn strings
  "Every string a surface shows, as relay resolved it and before it is
  made safe, in the order it is drawn: each Text's text and each
  CheckBox's label. Whatever reads a surface's text for what it holds,
  such as its links, reads it here and not in the components and data a
  role wrote, where a text that formatString joins does not exist yet.
  For a surface that cannot be drawn it is the one line that says so."
  [surface]
  (let [walk (fn walk [{:keys [type] :as node}]
               (case type
                 "Text" [(:text node)]
                 "CheckBox" [(:label node)]
                 ("Card" "Button") (walk (:child node))
                 ("Row" "Column" "List") (mapcat walk (:children node))
                 []))]
    (vec (walk (:root (shown surface))))))

(defn button
  "The Button with the drawn id `id` in a drawn surface's tree, or nil."
  [root id]
  (let [walk (fn walk [{:keys [type] :as node}]
               (cond
                 (and (= "Button" type) (= id (:id node))) node
                 (= "Card" type) (walk (:child node))
                 (#{"Row" "Column" "List"} type) (some walk (:children node))))]
    (walk root)))

;; --- the text form

(defn lines
  "A drawn node as lines of text, top to bottom. `mark` makes one of the
  surface's strings safe to show; everything else in a line is relay's.
  A Row's children share a line when each is one line, two spaces apart.
  An input the operator can change shows the path it writes."
  [{:keys [type] :as node} mark]
  (let [beside (fn [kids]
                 (let [each (map #(lines % mark) kids)]
                   (if (and (seq each) (every? #(= 1 (count %)) each))
                     [(str/join "  " (map first each))]
                     (vec (apply concat each)))))]
    (case type
      "Text" (str/split (mark (:text node)) #"\n" -1)
      "Divider" ["---"]
      "CheckBox" [(str (if (:value node) "[x] " "[ ] ") (mark (:label node))
                       (when (:path node) (str "  " (:path node))))]
      "Card" (lines (:child node) mark)
      ;; A role reads which id a press names, and what it does.
      "Button" [(str "[press] " (mark (get-in node [:child :text])) "  " (:id node)
                     (let [{:keys [event call path value]} (:action node)]
                       (if event
                         (str " tells you: " event)
                         (str " " call " " path " " value))))]
      "Column" (vec (mapcat #(lines % mark) (:children node)))
      "Row" (beside (:children node))
      "List" (if (= "horizontal" (:direction node))
               (beside (:children node))
               (vec (mapcat #(lines % mark) (:children node))))
      [])))

(defn source-lines
  "A surface as the role that keeps it has to read it: each component as
  it was written, one to a line, and the data model, so a role can
  replace the components or write a path."
  [{:keys [components data]}]
  (vec (concat ["components:"]
               (map json (if (sequential? components) components []))
               ["data:" (json data)])))

;; --- what a client is sent

(defn message
  "The A2UI createSurface message that builds a drawn surface from
  nothing, for the surface id `id`. `safe` makes one of the surface's
  strings safe to show. The components are flat, a parent before its
  children, and hold no call and no template; a bound CheckBox's value
  is {\"@path\": ...}, and the data model is `:model`, which holds none
  of the role's text."
  [id {:keys [root model]} safe]
  (let [flat (fn flat [{:keys [type] :as node}]
               (let [base {"id" (:id node) "component" type}
                     kids (case type
                            ("Row" "Column" "List") (:children node)
                            ("Card" "Button") [(:child node)]
                            nil)
                     own (case type
                           "Text" (cond-> (assoc base "text" (safe (:text node)))
                                    (:variant node) (assoc "variant" (:variant node)))
                           ("Row" "Column") (assoc base "children" (mapv :id kids))
                           "List" (cond-> (assoc base "children" (mapv :id kids))
                                    (:direction node) (assoc "direction" (:direction node)))
                           "Card" (assoc base "child" (:id (:child node)))
                           "Button" (cond-> (assoc base "child" (:id (:child node))
                                                   "action" (let [{:keys [event call path value]} (:action node)]
                                                              (if event
                                                                {"event" {"name" event}}
                                                                {"functionCall" {"@call" call
                                                                                 "args" {"path" path "value" value}}})))
                                      (:variant node) (assoc "variant" (:variant node)))
                           "Divider" (cond-> base (:axis node) (assoc "axis" (:axis node)))
                           "CheckBox" (assoc base "label" (safe (:label node))
                                             "value" (if (:path node) {"@path" (:path node)} (:value node))))]
                 (cons own (mapcat flat kids))))]
    {"version" version
     "createSurface" {"surfaceId" id
                      "catalogId" catalog-id
                      "components" (vec (flat root))
                      "dataModel" model}}))

;; --- the catalog, for a role and for a client

(def ^:private property-says
  {:action "an event or a function call; see below"
   :text "text, a path or a call"
   :flag "true, false or a path"
   :child "the id of one component"
   :children "a list of ids, or a template"})

(defn catalog-text
  "What a role is told a surface may hold: relay's catalog in words."
  []
  (str/join
   "\n"
   (concat
    ["A surface is a card you lay out yourself. Add one with card_add: kind surface, a title, `components` and `data`."
     ""
     "`components` is a flat list. Each has an `id`, a `component` and that component's properties, and names its"
     "children by id. One has the id root and holds the others. The card already has a frame with its title on"
     "it, so start from a Column and do not repeat the title. The components and what each takes:"
     ""]
    (for [[type {:keys [says props]}] catalog]
      (str "  " type ": " says ". "
           (str/join "; " (for [[k {:keys [is of required]}] props]
                            (str k " (" (if (= :one-of is) (str/join " or " of) (property-says is))
                                 (when-not required ", optional") ")")))
           "."))
    [""
     "A text is plain text. A client may draw **bold**, a # heading line and - list lines as such, and shows"
     "everything else as written, so leave out Markdown links, tables and code fences."
     ""
     "`data` is the JSON the components read. Where a property takes a path, write {\"@path\": \"/items/0/text\"}."
     "A path to text, a number, true or false reads as that; a path to a list or an object reads as nothing."
     "A text may also fill spots from the data: {\"@call\": \"formatString\", \"args\": {\"value\": \"${/done} of ${/total} done\"}}."
     ""
     "To draw one child for each item of a list in the data, give a Row, Column or List"
     "`children` of {\"componentId\": \"item\", \"path\": \"/items\"}. In that component a path with no leading /"
     "reads from the item ({\"@path\": \"text\"}), and {\"@call\": \"@index\", \"args\": {\"offset\": 1}} is the item's number."
     ""
     "The operator can tick and untick a CheckBox whose value is a path. The data must hold true or false there"
     "already, so write false for a box that starts unticked. Nothing else on a surface can be changed by them."
     "Reading the card shows you what they ticked."
     ""
     "A Button shows one Text, its child, and the operator presses it. Its action is one of two:"
     "  {\"event\": {\"name\": \"plan_route\"}} tells you. relay sends you a memo from the operator that names the"
     "  button, the card and, in a list, the item (its id, such as remove__2), so you can read the card and do it."
     "  The event has a name and nothing else, and the memo carries the card's labels and sources."
     (str "  {\"functionCall\": {\"@call\": \"" set-input "\", \"args\": {\"path\": \"/items/0/done\", \"value\": true}}}"
          " sets a box")
     "  on this card, as the operator's tick does, and wakes no one. Its path is one a CheckBox is bound to, and"
     "  in a list's template it may read from the item (\"done\")."
     "A button never changes the operator's settings and never stands for their consent to anything."
     ""
     "Change a surface with card_op. `data` writes `value` at `path`: a null value removes what is there, and a"
     "path ending in /- adds to a list that is already there. A key that is missing on the way is made; a list is"
     "not, so write [] first. `components` replaces every component. Both need `seen`, the card's rev, which the"
     "card's header and every answer to a change give."
     ""
     (str "Limits: " max-components " components, " max-drawn " once lists are drawn, " max-depth " deep; "
          max-list " items in a list; " max-text " characters in a text and " max-shown " in all. The data holds"
          " at most " max-values " values, " max-data-depth " deep, and a path at most " max-path " parts. Components"
          " and data together are at most " max-written " characters of JSON. Numbers are whole numbers. Keys of the"
          " data are letters, digits, underscores and hyphens, and never a - by itself. A component's id is letters and digits with single"
          " underscores between them.")])))

(defn catalog-document
  "relay's catalog as an A2UI catalog document, which
  resources/a2ui/relay_catalog.json holds and relay-tui keeps a copy of."
  []
  (let [common (fn [n] {"$ref" (str "common_types.json#/$defs/" n)})
        schema (fn [{:keys [is of]}]
                 (case is
                   :text (common "DynamicString")
                   :flag (common "DynamicBoolean")
                   :one-of {"type" "string" "enum" of}
                   :child (common "Child")
                   :action (common "Action")
                   :children (common "ChildList")))]
    {"$schema" "https://json-schema.org/draft/2020-12/schema"
     "$id" catalog-id
     "catalogId" catalog-id
     "protocolVersion" version
     "title" "relay catalog"
     "description" "The components relay takes from a role and sends to a client: layout, text, a checkbox and a button."
     "instructions" (catalog-text)
     "components" (into {} (for [[type {:keys [says props]}] catalog]
                             [type {"type" "object"
                                    "description" says
                                    "properties" (into {"component" {"const" type}}
                                                       (for [[k spec] props] [k (schema spec)]))
                                    "required" (into ["component"] (for [[k {:keys [required]}] props :when required] k))}]))
     ;; @index is A2UI's own, in its common types, so a catalog does not
     ;; list it.
     "functions" {"formatString" {"type" "object"
                                  "description" "Fills each ${...} spot in the value with what the data model holds at that path. A spot holds one path and nothing else. \\${ is a literal ${."
                                  "returnType" "string"
                                  "properties" {"@call" {"const" "formatString"}
                                                "args" {"type" "object"
                                                        "properties" {"value" {"type" "string"}}
                                                        "required" ["value"]
                                                        "unevaluatedProperties" false}}
                                  "required" ["@call" "args"]}}}))
