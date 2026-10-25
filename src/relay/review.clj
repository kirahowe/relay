(ns relay.review
  "What the operator consents to, as relay writes it for a client to show
  verbatim: a permission request, a card, a doc. Pure.

  A review is {\"text\" T \"sha256\" H}. T is plain text whose every line
  went through relay.text/visible-strict, or relay.text/visible-defanged
  where its source is contaminated, so it holds only printable characters
  (relay.text/printable?), the space and \"\\n\", and can be read back
  exactly. H is the sha256 hex of T's UTF-8 bytes. A client that declares relay/review sends H back with
  an allow or a trust, and relay takes it only when it is the H of the
  subject as relay has it now (relay.hub, relay.core :label/trust).

  Text from a role or an outsider sits behind relay.text/gutter, one
  gutter line per line of it, so nothing in it can pass for one of the
  review's own lines; a string inside one of the review's own lines is
  written as a JSON string, so it cannot end early either. Nothing is cut
  for length."
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

(defn- own
  "One of the review's own lines, from raw text: through visible-strict. A
  newline in it is escaped with the rest, so it stays one line."
  [s]
  (text/visible-strict s))

(defn- safe
  "Text from a source with these labels, for one line: visible-defanged
  when they are contaminated, else visible-strict."
  [labels s]
  (if (text/contaminated? labels) (text/visible-defanged s) (text/visible-strict s)))

(defn- block
  "Text from a source with these labels as the review's lines: each line
  behind relay.text/gutter, headed by a [contaminated] line when
  contaminated."
  [labels s]
  (concat (when (text/contaminated? labels) ["  [contaminated]"])
          (map #(str text/gutter (safe labels %)) (str/split (str s) #"\n" -1))))

(defn text-of
  "Lines already made safe, as a review text."
  [lines]
  (str/join "\n" lines))

(defn review
  "A review text and its digest, as clients are sent them."
  [t]
  {"text" t "sha256" (digest t)})

(defn- q
  "A string of the review's own line, as a JSON string."
  [s]
  (strict/generate (str s)))

(defn- labels-text [labels]
  (if (nil? labels) "none sent" (strict/generate (vec labels))))

;; --- a permission request

(defn request-lines
  "A permission request as the review's raw lines, in the order the
  operator reads them: the role, the tool and its class, what the call
  does (relay's preview, under its own labels), the whole input as JSON
  with members in name order, the arguments the operator's settings fix,
  the session's labels, and every option the agent offers with its kind.

  The preview's links are broken (visible-defanged) when its labels are
  contaminated, and the input's when the session's or the preview's are."
  [{:keys [role tool class preview input labels fixed-args options]}]
  (let [{p-text :text p-labels :labels p-error :error} preview
        input-labels (concat labels p-labels)]
    (vec
     (concat
      [(own (str "Permission request from role " (q role)))
       (own (str "Tool " (q tool) ", class " (if class (q class) "not known to relay")))
       ""]
      (cond
        (nil? preview) ["What it does: this tool has no preview."]
        :else (concat [(own (str "What it does, by the tool's preview (its labels "
                                 (labels-text p-labels) "):"))]
                      (when p-error ["  The preview command could not say what this call would do."])
                      (block p-labels p-text)))
      [""
       "Input, all of it, as JSON:"]
      (block input-labels (strict/pretty input))
      (when (seq fixed-args)
        [(own (str "Set by your settings, not by the role: " (strict/generate (vec (sort (map str (keys fixed-args)))))))])
      [""
       (own (str "Labels of the conversation: " (labels-text labels)))
       ""
       "Options:"]
      (if (seq options)
        (for [o options]
          (own (str "  " (q (get o "name")) ", kind " (q (get o "kind")) ", id " (q (get o "optionId")))))
        ["  none"])))))

(defn request-review
  "The review of a permission request (request-lines)."
  [req]
  (review (text-of (request-lines req))))

;; --- a card

(defn card-text
  "A card's review text: its text form (relay.cards/text-form) with each
  element's text made safe by its own labels, and marked [contaminated]
  when they are. The rest of the text form is relay's: ids, the kind,
  counts and states, which need no escaping."
  [card]
  (text-of (cards/text-lines card {:mark (fn [ls s] (str (when (text/contaminated? ls) "[contaminated] ") (safe ls s)))
                                   :block block})))

;; --- a doc, and anything else relay trusts

(defn doc-text
  "A doc's review text: its path and version, and its content behind the
  gutter, made unfollowable when its labels are contaminated."
  [path version labels content]
  (text-of (concat [(own (str "Doc " (q path) ", version " version))]
                   (block labels content))))

(defn subject-text
  "The review text of something relay trusts (relay.core/trusted-version
  gives :version :labels :text): a doc's is doc-text; any other kind but a
  card, its kind, ref and version and then its text, the same way. A
  card's is card-text, made from the card."
  [kind ref {:keys [version labels text]}]
  (if (= "doc" kind)
    (doc-text ref version labels text)
    (text-of (concat [(own (str (str/capitalize (str kind)) " " (q ref) ", version " version))]
                     (block labels text)))))
