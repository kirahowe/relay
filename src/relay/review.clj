(ns relay.review
  "What the operator consents to, as relay writes it for a client to show
  verbatim: a permission request, a held call, a card, a doc. Pure.

  A review is {\"text\" T \"sha256\" H}. H is the sha256 hex of T's UTF-8
  bytes. A client that declares relay/review sends H back with an allow or
  a trust, and relay takes it only when it is the H of the subject as
  relay has it now (relay.hub, relay.core :label/trust). Every consent
  surface, the command line's included, shows T itself.

  T is built by one structural rule, so it can be read back into the
  subject exactly:

    - every line holds only printable characters (relay.text/printable?)
      and spaces, and T's only other character is the newline;
    - an untrusted value never stands bare inside one of relay's own
      lines. It is either a JSON string (quoted, its escapes visible) on a
      labelled line, followed by \" [contaminated]\" when its source is, or
      a block of lines each behind relay.text/gutter, headed by a
      \"  [contaminated]\" line when its source is;
    - each line is escaped once: by relay.text/visible-strict, or by
      relay.text/visible-defanged for a value from a contaminated source,
      which breaks its links with escapes. Both show a backslash as two,
      so the escaping can be undone;
    - the review ends with \"Characters to check:\": every untrusted value
      that holds a word mixing scripts or a character outside ASCII is
      named there by its field, with each such character's code point and
      Unicode name (relay.text/lookalike-findings).

  Nothing is cut for length."
  (:require [clojure.string :as str]
            [relay.cards :as cards]
            [relay.json :as strict]
            [relay.text :as text]))

(defn digest
  "The hex sha256 of a text's UTF-8 bytes."
  [s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes (str s) java.nio.charset.StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn review
  "A review text and its digest, as clients are sent them."
  [t]
  {"text" t "sha256" (digest t)})

(defn text-of
  "Lines already made safe, as a review text."
  [lines]
  (str/join "\n" lines))

;; --- pieces

(defn- own
  "One of the review's own lines, from text relay wrote: through
  visible-strict, which leaves it as it is when it is plain ASCII."
  [s]
  (text/visible-strict s))

(defn- escaped
  "Text from a source with these labels, for one line: visible-defanged
  when they are contaminated, else visible-strict."
  [labels s]
  (if (text/contaminated? labels) (text/visible-defanged s) (text/visible-strict s)))

(defn- q
  "A value as a JSON string, escaped by its labels, for one of relay's
  own lines."
  [labels s]
  (escaped labels (strict/generate (str s))))

(defn- quoted
  "A labelled line: relay's label, then the value as a JSON string, marked
  when its source is contaminated."
  [label labels s]
  (str (own label) (q labels s) (when (text/contaminated? labels) " [contaminated]")))

(defn- block
  "Text from a source with these labels as the review's lines: each line
  behind the gutter, headed by a [contaminated] line when contaminated."
  [labels s]
  (concat (when (text/contaminated? labels) ["  [contaminated]"])
          (map #(str text/gutter (escaped labels %)) (str/split (str s) #"\n" -1))))

(defn- labels-text [labels]
  (if (nil? labels) "none sent" (strict/generate (vec labels))))

(defn- disclosures
  "The Characters to check lines for one untrusted value: `field` is
  relay's name for where it is (already safe), `labels` its source's."
  [field labels s]
  (for [{:keys [word scripts cp name]} (text/lookalike-findings s)]
    (if word
      (str "  " field ": word " (q labels word) " mixes " (own (str/join " and " scripts)))
      (str "  " field ": " (own (format "U+%04X %s" cp name))))))

(defn- characters-to-check
  "The closing section, from [field labels value] for every untrusted
  value."
  [values]
  (let [ls (mapcat (fn [[field labels s]] (disclosures field labels s)) values)]
    (if (seq ls)
      (cons "Characters to check:" ls)
      ["Characters to check: none"])))

(defn- json-strings
  "Every string in a JSON value, keys included, with its path: [path s]."
  [x path]
  (cond
    (map? x) (mapcat (fn [[k v]] (let [k (if (keyword? k) (subs (str k) 1) (str k))]
                                   (cons [(conj path k) k] (json-strings v (conj path k)))))
                     (sort-by (comp str key) x))
    (sequential? x) (mapcat (fn [i v] (json-strings v (conj path i))) (range) x)
    (string? x) [[path x]]
    :else []))

;; --- a permission request, or a held call

(defn request-lines
  "A permission request (or, with :heading, a held call) as the review's
  lines, in the order the operator reads them: the role, the tool and its
  class, what the call does (relay's preview, under its own labels), the
  whole input as JSON with members in name order, the arguments the
  operator's settings fix, the session's labels, every option the agent
  offers with its kind (no section when :options is nil), and the
  characters to check.

  The preview's links are broken when its labels are contaminated, and
  the input's when the session's or the preview's are."
  [{:keys [heading role tool class preview input labels fixed-args options]}]
  (let [{p-text :text p-labels :labels p-error :error} preview
        input-labels (vec (concat labels p-labels))]
    (vec
     (concat
      [(str (own (or heading "Permission request")) (own " from role ") (q [] role))
       (str (own "Tool ") (q [] tool) (own ", class ") (if class (q [] class) "not known to relay"))
       ""]
      (if (nil? preview)
        ["What it does: this tool has no preview."]
        (concat [(own (str "What it does, by the tool's preview (its labels " (labels-text p-labels) "):"))]
                (when p-error ["  The preview command could not say what this call would do."])
                (block p-labels p-text)))
      [""
       "Input, all of it, as JSON:"]
      (block input-labels (strict/pretty input))
      (when (seq fixed-args)
        [(own (str "Set by your settings, not by the role: " (strict/generate (vec (sort (map str (keys fixed-args)))))))])
      [""
       (own (str "Labels of the conversation: " (labels-text labels)))]
      (when (some? options)
        (concat ["" "Options:"]
                (if (seq options)
                  (for [o options]
                    (str "  " (q [] (get o "name")) (own ", kind ") (q [] (get o "kind"))
                         (own ", id ") (q [] (get o "optionId"))))
                  ["  none"])))
      [""]
      (characters-to-check
       (concat [["tool" [] tool]]
               (when preview [["preview" p-labels p-text]])
               (for [[path s] (json-strings input [])]
                 [(str "input " (escaped input-labels (strict/generate path))) input-labels s])
               (for [[i o] (map-indexed vector options) k ["name" "kind" "optionId"]]
                 [(str "option " (inc i) " " k) [] (get o k)])))))))

(defn request-review
  "The review of a permission request (request-lines)."
  [req]
  (review (text-of (request-lines req))))

;; --- a card

(defn- el-labels [el] (:labels el))

(defn- header [{:keys [id kind rev] :as card}]
  (let [{:keys [done total] :as p} (cards/progress card)]
    (own (str "Card " id " (" kind ", rev " rev
              (when p (str ", " done " of " total " done"))
              (when (cards/complete? card) ", complete")
              (when-not (cards/active? card) ", archived")
              ")"))))

(defn card-lines
  "A card as the review's lines: its id, kind, rev and state, its title,
  then each item, column, row and cell that is not open, or its note,
  each text a JSON string marked by its own labels (a note's a block),
  and the characters to check."
  [{:keys [kind title items rows columns cells note] :as card}]
  (vec
   (concat
    [(header card)
     (quoted "Title: " (el-labels title) (:text title))]
    (case kind
      "checklist" (if (empty? items)
                    ["No items yet."]
                    (for [{:keys [id text done labels]} items]
                      (quoted (str "Item " id ", " (if done "done" "open") ": ") labels text)))
      "tracker" (concat
                 (if (empty? columns)
                   ["No columns yet."]
                   (for [{:keys [id text labels]} columns] (quoted (str "Column " id ": ") labels text)))
                 (if (empty? rows)
                   ["No rows yet."]
                   (for [{:keys [id text labels]} rows] (quoted (str "Row " id ": ") labels text)))
                 (for [[[r c] st] (sort-by key cells) :when (not= "open" st)]
                   (own (str "Row " r ", column " c ": " st))))
      "note" (concat [(if (text/contaminated? (:labels note)) "Note, from a contaminated source:" "Note:")]
                     (block (:labels note) (:text note)))
      [])
    [""]
    (characters-to-check
     (concat [["title" (el-labels title) (:text title)]]
             (for [{:keys [id text labels]} items] [(str "item " id) labels text])
             (for [{:keys [id text labels]} columns] [(str "column " id) labels text])
             (for [{:keys [id text labels]} rows] [(str "row " id) labels text])
             (when note [["note" (:labels note) (:text note)]]))))))

(defn card-text
  "A card's review text (card-lines)."
  [card]
  (text-of (card-lines card)))

;; --- a doc, and anything else relay trusts

(defn subject-lines
  "Something relay trusts, other than a card, as the review's lines: its
  kind, its ref (a doc's path) and version, then its text behind the
  gutter, and the characters to check."
  [kind ref version labels content]
  (vec (concat [(str (own (str (str/capitalize (str kind)) " ")) (q [] ref) (own (str ", version " version)))]
               (block labels content)
               [""]
               (characters-to-check [[(if (= "doc" kind) "path" "ref") [] ref]
                                     [(if (= "doc" kind) "content" "text") labels content]]))))

(defn doc-text
  "A doc's review text: its path and version, its content behind the
  gutter."
  [path version labels content]
  (text-of (subject-lines "doc" path version labels content)))

(defn subject-text
  "The review text of something relay trusts (relay.core/trusted-version
  gives :version :labels :text), for any kind but a card, whose is
  card-text, made from the card."
  [kind ref {:keys [version labels text]}]
  (text-of (subject-lines kind ref version labels text)))

;; --- a memo declassified for a public role

(defn declassify-lines
  "A memo about to be declassified, as the review's lines: `d` is the memo
  as declassifying makes it (relay.core/declassified), which is exactly
  what the public role is given, so its text is escaped by visible-strict
  alone (core already defanged it when contaminated) and marked by the
  memo's labels: its id, from and to roles, subject, body, labels, and
  the characters to check."
  [{:keys [id from to subject body labels]}]
  (let [mark (when (text/contaminated? labels) " [contaminated]")]
    (vec (concat [(str (own (str "Declassify memo " id " from role ")) (q [] from) (own " to the public role ") (q [] to))
                  (str (own "Subject: ") (q [] subject) mark)
                  (if mark "Body, from a contaminated source:" "Body:")]
                 (block [] body)
                 [(own (str "Labels of the memo: " (labels-text labels)))
                  ""]
                 (characters-to-check [["subject" [] subject] ["body" [] body]])))))

(defn declassify-text
  "A memo's declassify review text (declassify-lines)."
  [d]
  (text-of (declassify-lines d)))
