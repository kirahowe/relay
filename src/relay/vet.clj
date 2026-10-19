(ns relay.vet
  "A remote MCP tool's definition, read the way a careful operator would
  before letting a role have it. A server writes every word of a
  definition (its name, title, description and the text in its input
  schema), and the model reads all of it as it chooses what to do, so
  text there can carry instructions the operator never meant to give.

  texts lists every piece of text in a definition, with where it is.
  flags says what about them looks wrong, each with a short reason: text
  that speaks to the model in the imperative, hidden or lookalike
  characters, links and email addresses, and text far longer than a
  description needs. Pure and deterministic. It is a filter for a person
  to look closer, not a proof: relay connect turns a flagged tool off and
  shows its whole text, and the operator decides."
  (:require [clojure.string :as str]
            [relay.text :as text]))

;; --- the text in a definition

(defn- kw-name [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn- schema-texts
  "Every piece of text in a JSON Schema, with its path: titles,
  descriptions, enum and const values, defaults, examples, and property
  names, through properties, items and the combining keywords."
  [schema path depth]
  (when (and (map? schema) (< depth 12))
    (concat
     (for [k [:title :description :$comment] :let [v (get schema k)] :when (string? v)]
       [(str path " " (kw-name k)) v])
     (for [k [:enum :examples] :let [vs (get schema k)] :when (sequential? vs)
           v vs :when (some? v)]
       [(str path " " (kw-name k)) (if (string? v) v (pr-str v))])
     (for [k [:const :default] :when (contains? schema k) :let [v (get schema k)] :when (some? v)]
       [(str path " " (kw-name k)) (if (string? v) v (pr-str v))])
     (mapcat (fn [[p sub]]
               (let [p (kw-name p)
                     where (str path (when-not (str/blank? path) ".") p)]
                 (cons [(str where " (argument name)") p] (schema-texts sub where (inc depth)))))
             (sort-by (comp kw-name key) (when (map? (:properties schema)) (:properties schema))))
     (when-let [items (:items schema)]
       (if (sequential? items)
         (mapcat #(schema-texts %1 (str path "[]") (inc depth)) items)
         (schema-texts items (str path "[]") (inc depth))))
     (mapcat (fn [k] (when (sequential? (get schema k))
                       (mapcat #(schema-texts % path (inc depth)) (get schema k))))
             [:anyOf :oneOf :allOf]))))

(defn texts
  "Every piece of text in a listed tool's definition, as [where text], in
  a fixed order: its name, title, description, then its input and output
  schemas' text."
  [{:keys [name title description annotations input_schema output_schema]}]
  (vec (concat
        [["name" (str name)]]
        (when (string? title) [["title" title]])
        (when (and (string? (:title annotations)) (not= title (:title annotations)))
          [["annotations title" (:title annotations)]])
        (when (string? description) [["description" description]])
        (for [[w t] (schema-texts input_schema "" 0)] [(str "input" (when-not (str/starts-with? w " ") " ") w) t])
        (for [[w t] (schema-texts output_schema "" 0)] [(str "output" (when-not (str/starts-with? w " ") " ") w) t]))))

;; --- what looks wrong

(def ^:private imperative
  "Phrasing that tells the model what to do, rather than saying what the
  tool does. Each is narrow, so ordinary descriptions (\"Lists the
  subscribers of a group\", \"Ignored when empty\") pass."
  [[#"(?i)\b(before|after|prior to|instead of)\s+(calling|using|running|invoking|answering|responding|replying|you\s+(call|use|answer|respond|reply))\b"
    "tells the model what to do around its calls"]
   [#"(?i)\bignore\s+(all|any|the|previous|prior|earlier|above|your|other|these|those|every)\b"
    "tells the model to ignore something"]
   [#"(?i)\b(always|never)\s+(call|use|send|include|ask|tell|mention|reveal|say|run|invoke|forward|share|output|respond|reply|answer|inform|show|follow|obey)\b"
    "tells the model what it must always or never do"]
   [#"(?i)\byou\s+(must|should|shall|need\s+to|have\s+to|are\s+(required|instructed|allowed)\s+to|will\s+now|are\s+now)\b"
    "gives the model orders"]
   [#"(?i)\b(send|forward|include|share|post|upload|e-?mail|attach|reveal|leak|exfiltrate|copy|paste|transmit)\b(\W+\w+){0,3}?\W+(private|secret|confidential|credentials?|passwords?|api[ -]?keys?|system\s+prompt|conversation|memor(y|ies)|chat\s+history|everything|every\s+(fact|note|detail)|all\s+(facts|notes|data|files|messages|details))\b"
    "asks for private things to be sent or included"]
   [#"(?i)(\b(system|assistant|developer)\s+(prompt|message|instructions?)\b|<\|?\s*(system|im_start|im_end|assistant)\b|\[/?(system|inst)\]|</?\s*(system|instructions?|important)\s*>|(^|\n|[.!?]\s)\s*(system|assistant|user)\s*:)"
    "uses chat role words, as a prompt would"]
   [#"(?i)\b(other|another|any|all|every)\s+(other\s+)?tools?\b|\btools?\s+(like|such\s+as|named|called)\b"
    "talks about other tools"]
   [#"(?i)\b(new|updated|real|actual|important|secret|hidden)\s+instructions?\b|\binstructions?\s+(for|to)\s+(the\s+)?(ai|model|assistant|agent|llm)\b|\b(ai|language\s+model|llm)\s*[,:]\s|\b(dear|hey|hello|attention|note\s+to\s+the)\s+(ai|assistant|model|agent|llm)\b"
    "speaks to the model"]])

(def ^:private email-pattern
  #"(?i)\b[a-z0-9._%+-]+@[a-z0-9-]+(\.[a-z0-9-]+)*\.[a-z]{2,}\b")

(def ^:private bare-domain-pattern
  ;; www.x.y, or a word.word.tld that is not a version number or a file.
  #"(?i)\bwww\.[a-z0-9-]+\.[a-z]{2,}\b|\b[a-z0-9-]+\.(com|net|org|io|ai|co|app|dev|xyz|info|biz|ru|cn|tk|top|me|ly|gg|sh)\b(/\S*)?")

(def description-limit
  "The most characters a description needs. A real tool's says what it
  does and what its arguments mean in far fewer."
  1200)

(def text-limit
  "The most characters any other piece of a definition needs."
  400)

(def total-limit
  "The most characters a whole definition needs."
  4000)

(defn- hidden-chars [s]
  (distinct (for [cp (iterator-seq (.iterator (.codePoints (str s)))) :when (text/hidden? cp)]
              (format "U+%04X" cp))))

(defn- mixed-words [s]
  (filter #(str/includes? % " mixes ") (text/lookalikes s)))

(defn text-flags
  "What looks wrong in one piece of text, as reasons, each naming
  `where`. `others` are the names of tools elsewhere (relay's own and
  relay.edn's), which a definition has no reason to name."
  [where s others]
  (let [s (str s)
        say #(str where ": " %)]
    (concat
     (for [[re why] imperative :when (re-find re s)] (say why))
     (when-let [cps (seq (hidden-chars s))]
       [(say (str "holds characters that show as nothing (" (str/join ", " (take 5 cps)) ")"))])
     (for [w (take 3 (mixed-words s))] (say (str "has a word that could pass for another: " w)))
     (when (or (seq (text/link-hosts s)) (re-find bare-domain-pattern s))
       [(say "holds a link")])
     (when (re-find email-pattern s) [(say "holds an email address")])
     (for [o (sort others)
           :when (re-find (re-pattern (str "(?i)(?<![A-Za-z0-9_-])" (java.util.regex.Pattern/quote o) "(?![A-Za-z0-9_-])")) s)]
       (say (str "names " o ", a tool from somewhere else")))
     (let [limit (if (= "description" where) description-limit text-limit)]
       (when (> (count s) limit)
         [(say (str "is " (count s) " characters long, far longer than it needs to be"))])))))

(defn flags
  "What looks wrong in a listed tool's definition: short reasons, in a
  fixed order, without repeats. Empty means nothing does. `others` are
  tool names from outside this server (only names with _ or -, since
  plain words such as notes are ordinary English)."
  [listed others]
  (let [others (set (filter #(re-find #"[_-]" %) others))
        ts (texts listed)
        own (str (:name listed))]
    (vec (distinct
          (concat
           (mapcat (fn [[w t]] (text-flags w t (disj others own))) ts)
           (let [n (reduce + (map (comp count second) ts))]
             (when (> n total-limit)
               [(str "the whole definition is " n " characters long, far longer than it needs to be")])))))))
