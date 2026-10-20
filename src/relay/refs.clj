(ns relay.refs
  "References to things relay knows: `[[kind:id]]`, where kind is one of
  fact, doc, note, memo, journal, rem, file, web, shown, report and card. A role
  puts a ref after every claim drawn from relay's knowledge or from a
  source, and relay's read tools print one beside each item they show.
  Pure.

  The ids:

    [[fact:12]]                  a fact, by number
    [[doc:hear-here/style.md]]   a doc, its latest version
    [[doc:hear-here/style.md@3]] a doc as of version 3
    [[note:hub/venues]]          a note, as <role>/<name>
    [[memo:7]]                   a memo, by number
    [[journal:31]]               a journal entry, by number
    [[report:5]]                 a research report, by number
    [[card:1840]]                a card on the operator's board, by number
    [[shown:2026-10-18/call-9]]  something the operator was shown
    [[rem:<id>]]                 a reminder in the operator's list
    [[file:/Users/kira/x.pdf]]   a file on the operator's disk
    [[web:https://example.org/]] a web page

  `resolve` is the lookup a chat client uses to check a ref and open it."
  (:refer-clojure :exclude [ref resolve])
  (:require [clojure.string :as str]
            [relay.cards :as cards]
            [relay.core :as core]))

(def kinds
  "Every kind a ref can have, in the order the design lists them."
  [:fact :doc :note :memo :journal :rem :file :web :shown :report :card])

(def ^:private kind-names (set (map name kinds)))

(def ^:private external #{:rem :file :web})

;; The id sits between `[[kind:` and `]]`, so `[`, `]` and a line break
;; cannot appear in it as they are. They are written as %5B, %5D and %0A,
;; and a % that would read as one of those escapes is written %25.
(defn- escape [s]
  (-> s
      (str/replace #"%(?=25|5B|5D|0A)" "%25")
      (str/replace "[" "%5B")
      (str/replace "]" "%5D")
      (str/replace "\n" "%0A")))

(defn- unescape [s]
  (str/replace s #"%(25|5B|5D|0A)"
               (fn [[_ code]] (case code "25" "%" "5B" "[" "5D" "]" "0A" "\n"))))

(defn ref
  "The ref text for a kind (a keyword) and an id. Nil when the kind is not
  one of `kinds` or the id is blank. An id that holds brackets or line
  breaks is escaped, and `parse` undoes that."
  [kind id]
  (when (and (some #{kind} kinds) (not (str/blank? (some-> id str))))
    (str "[[" (name kind) ":" (escape (str id)) "]]")))

(defn doc-ref
  "The ref for a doc at `path`, as of `version` when one is given."
  [path version]
  (ref :doc (if version (str path "@" version) path)))

(def ^:private ref-pattern #"\[\[([a-z]+):([^\[\]\n]+)\]\]")

(defn parse
  "Every ref in a text, in order, as {:ref the text as written, :kind
  keyword, :id the unescaped id, :start offset, :end offset}. Text that
  looks like a ref with an unknown kind is not one. Never throws; a
  non-string has no refs."
  [text]
  (if-not (string? text)
    []
    (let [m (re-matcher ref-pattern text)]
      (loop [acc []]
        (if (.find m)
          (recur (if (kind-names (.group m 1))
                   (conj acc {:ref (.group m) :kind (keyword (.group m 1))
                              :id (unescape (.group m 2)) :start (.start m) :end (.end m)})
                   acc))
          acc)))))

(defn doc-id
  "A doc ref's id split into its path and version: {:path .. :version n}.
  The version is nil when the id has no @ followed by digits at its end."
  [id]
  (if-let [[_ path v] (re-matches #"(?s)(.+)@(\d+)" id)]
    {:path path :version (parse-long v)}
    {:path id :version nil}))

;; --- resolving

(defn- cut [s n]
  (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "...") s)))

(defn- doc-result [state path version prefix]
  (let [v (core/doc-version state path {:version version})]
    (if (or (nil? v) (:removed v))
      {:status :missing}
      {:status :ok
       :title (str prefix path (when version (str " (version " version ")")))
       :text (:content v)
       :labels (core/union (core/item-labels v (core/version-labels state v)) (core/path-labels state path))})))

(defn- resolve-one [state kind id]
  (let [n (parse-long (str/trim id))]
    (case kind
      :fact (if-let [v (some-> (core/fact state n) (core/fact-as-known nil))]
              {:status :ok
               :title (str "Fact " n (when (:topic v) (str " [" (:topic v) "]")) (when (:retracted v) " (retracted)"))
               :text (:text v)
               :labels (core/item-labels v (core/version-labels state v))}
              {:status :missing})
      :doc (let [{:keys [path version]} (doc-id id)] (doc-result state path version ""))
      :shown (doc-result state (str "shown/" id ".md") nil "Shown: ")
      :note (let [[owner nm] (str/split id #"/" 2)]
              (if-let [note (get-in state [:notes owner nm])]
                {:status :ok :title (str "Note " nm " of " owner) :text (:text note) :labels (core/item-labels note)}
                {:status :missing}))
      :memo (if-let [m (core/memo state n)]
              {:status :ok :title (str "Memo " n ": " (:subject m)) :text (:body m) :labels (core/memo-labels state m)}
              {:status :missing})
      :journal (if-let [e (first (filter #(= n (:id %)) (mapcat val (:journals state))))]
                 {:status :ok :title (str "Journal entry " n " of " (:owner e)) :text (:text e)
                  :labels (core/item-labels e)}
                 {:status :missing})
      :report (if-let [r (core/report state n)]
                {:status :ok :title (str "Report " n " by " (:role r)) :text (:summary r) :labels (core/item-labels r)}
                {:status :missing})
      :card (if-let [c (core/card state n)]
              {:status :ok :title (str "Card " n ": " (get-in c [:title :text])) :text (cards/text-form c)
               :labels (core/card-labels c)}
              {:status :missing})
      ;; relay cannot look these up. The client may.
      {:status :external :target id})))

(defn resolve
  "Look a ref up against relay's state. `ref-string` is `[[kind:id]]` or
  the bare `kind:id`. Returns a map:

    :ref     the ref as given
    :kind    its kind as a keyword, nil when the string is not a ref
    :status  :ok       it exists; :title, :text and :labels describe it
             :missing  it does not exist, was removed, or is not a ref
             :external it is a rem, file or web ref, which relay cannot
                       look up; :target is the id (the reminder id, the
                       path or the URL) for the client to open

  :text is the current text (a doc's latest version, or the version
  asked for with @n). :labels are what reading it would add to a
  session. Pure, and never throws."
  [state ref-string]
  (let [s (str/trim (str ref-string))
        [_ kind id] (or (re-matches #"(?s)\[\[([a-z]+):(.+)\]\]" s) (re-matches #"(?s)([a-z]+):(.+)" s))
        kind (when (kind-names kind) (keyword kind))
        id (when id (unescape id))]
    (merge {:ref ref-string :kind kind}
           (if (and kind (not (str/blank? id)))
             (try (resolve-one state kind id)
                  (catch Exception _ {:status :missing}))
             {:status :missing}))))

(defn plain
  "A ref as short plain text for a notification: relay's own kinds as
  \"fact 12\", a reminder as \"reminder <id>\", and a file or web page
  as its target, with no brackets."
  [text]
  (reduce (fn [s {:keys [ref kind id]}]
            (str/replace s ref (case kind
                                 (:file :web) (cut id 60)
                                 :rem (str "reminder " (cut id 60))
                                 (str (name kind) " " id))))
          text (reverse (parse text))))
