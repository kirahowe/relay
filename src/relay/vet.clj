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

(def ^:private json-types #{"string" "number" "integer" "boolean" "object" "array" "null"})

(def ^:private keywords
  "JSON Schema's own keywords: a key that is one of these is not text the
  server chose. Any other key is, and is listed and checked."
  #{"$schema" "$id" "$ref" "$anchor" "$dynamicRef" "$dynamicAnchor" "$defs" "$comment" "$vocabulary" "definitions"
    "type" "enum" "const" "default" "examples" "title" "description" "format" "pattern"
    "properties" "patternProperties" "additionalProperties" "unevaluatedProperties" "propertyNames"
    "required" "dependentRequired" "dependentSchemas" "items" "prefixItems" "additionalItems" "unevaluatedItems"
    "contains" "minContains" "maxContains" "minItems" "maxItems" "uniqueItems" "minProperties" "maxProperties"
    "minLength" "maxLength" "minimum" "maximum" "exclusiveMinimum" "exclusiveMaximum" "multipleOf"
    "allOf" "anyOf" "oneOf" "not" "if" "then" "else" "readOnly" "writeOnly" "deprecated"
    "contentEncoding" "contentMediaType" "contentSchema"})

(defn- data-texts
  "Every string, key and scalar in a JSON value given as data (an enum,
  a default), with its path."
  [v path]
  (cond
    (map? v) (mapcat (fn [[k x]] (cons [(str path " (key)") (kw-name k)] (data-texts x (str path " " (kw-name k)))))
                     (sort-by (comp kw-name key) v))
    (sequential? v) (mapcat #(data-texts % path) v)
    (string? v) [[path v]]
    (nil? v) nil
    :else [[path (pr-str v)]]))

(defn- schema-texts
  "Every piece of text anywhere in a JSON Schema, with its path: every
  key that is not one of JSON Schema's keywords, every string, and all of
  any value given as data (enum, const, default, examples), at any depth
  and under any keyword, since the model is shown the schema whole. Only
  the values of type that are JSON's own type names are left out."
  [schema path]
  (let [here (fn [k] (str path (when-not (str/blank? path) " ") (kw-name k)))]
    (cond
      (map? schema)
      (mapcat (fn [[k v]]
                (let [kn (kw-name k)]
                  (concat
                   (when-not (keywords kn) [[(str (here k) " (key)") kn]])
                   (cond
                     (and (= "type" kn) (or (json-types v) (and (sequential? v) (every? json-types v)))) nil
                     (and (#{"properties" "patternProperties" "$defs" "definitions" "dependentSchemas"} kn) (map? v))
                     (do
                       (mapcat (fn [[pk pv]]
                                 (let [where (str (here k) " " (kw-name pk))]
                                   (cons [(str where " (name)") (kw-name pk)] (schema-texts pv where))))
                               (sort-by (comp kw-name key) v)))
                     (#{"enum" "examples" "const" "default"} kn) (data-texts v (here k))
                     :else (schema-texts v (here k))))))
              (sort-by (comp kw-name key) schema))
      (sequential? schema) (mapcat #(schema-texts %1 (str path "[" %2 "]")) schema (range))
      (string? schema) [[path schema]]
      :else nil)))

(defn- every-text
  "Every key and every string in a JSON value, whatever it means: the
  net under schema-texts, so nothing the model is shown can be missed by
  a walk that reads the schema wrongly."
  [v path]
  (cond
    (map? v) (mapcat (fn [[k x]] (cons [(str path " (key)") (kw-name k)] (every-text x (str path " " (kw-name k)))))
                     (sort-by (comp kw-name key) v))
    (sequential? v) (mapcat #(every-text % path) v)
    (string? v) [[path v]]
    :else nil))

(defn- with-the-rest
  "The structured texts, then every text the exhaustive walk found that
  they lack (other than JSON Schema's keywords and type names)."
  [structured schema path]
  (let [have (set (map second structured))
        rest (for [[w t] (every-text schema path)
                   :when (not (have t))
                   :when (not (and (str/ends-with? w "(key)") (keywords t)))
                   :when (not (and (json-types t) (re-find #" type$" w)))]
               [w t])]
    (concat structured (distinct rest))))

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
        (with-the-rest (schema-texts input_schema "input") input_schema "input")
        (with-the-rest (schema-texts output_schema "output") output_schema "output")
        (for [[k v] (sort-by (comp kw-name key) annotations) :when (and (string? v) (not= :title k))]
          [(str "annotations " (kw-name k)) v]))))

;; --- what looks wrong

(def ^:private imperative
  "Phrasing that tells the model what to do, rather than saying what the
  tool does. Each is narrow, so ordinary descriptions (\"Lists the
  subscribers of a group\", \"Ignored when empty\") pass."
  [[#"(?i)\b(ignore|disregard|forget|override|bypass|overlook|skip)\s+(all\s+|any\s+|the\s+|your\s+|of\s+)*(previous|prior|earlier|above|preceding|system|original|other|existing)?\s*(instructions?|rules|prompts?|directions|guidelines|guardrails|constraints)\b"
    "tells the model to set its instructions aside"]
   [#"(?i)\b(print|output|reveal|repeat|show|dump|return|display)\s+(the\s+|your\s+)?(entire|whole|full|complete|initial|original)\s+(conversation|context|prompt|chat|history|instructions)\b|\boutput\s+only\b|\brespond\s+only\s+with\b"
    "tells the model what to answer"]
   [#"(?i)\b(before|after|prior to|instead of)\s+(calling|using|running|invoking|answering|responding|replying|you\s+(call|use|answer|respond|reply))\b"
    "tells the model what to do around its calls"]
   [#"(?i)\bignore\s+(all|any|the|previous|prior|earlier|above|your|other|these|those|every)\b"
    "tells the model to ignore something"]
   [#"(?i)\b(always|never)\s+(call|use|send|include|ask|tell|mention|reveal|say|run|invoke|forward|share|output|respond|reply|answer|inform|show|follow|obey)\b"
    "tells the model what it must always or never do"]
   [#"(?i)\byou\s+(must|should|shall|need\s+to|have\s+to|are\s+(required|instructed|allowed)\s+to|will\s+now|are\s+now)\b"
    "gives the model orders"]
   [#"(?i)\b(send|forward|include|share|post|upload|e-?mail|attach|reveal|leak|exfiltrate|copy|paste|transmit)\b(\W+\w+){0,3}?\W+(private|secret|confidential|credentials?|passwords?|api[ -]?keys?|system\s+prompt|conversation|memor(y|ies)|chat\s+history|everything|every\s+(fact|note|detail)|all\s+(facts|notes|data|files|messages|details))\b"
    "asks for private things to be sent or included"]
   [#"(?i)\b(do\s+not|don'?t|never|without)\s+(tell|telling|inform|informing|mention|mentioning|show|showing|let|letting|notify|notifying|ask|asking|alert|alerting)\s+(the\s+)?(user|operator|human|owner|anyone|them)\b|\b(keep\s+(this|it)\s+(secret|hidden|between\s+us)|secretly|silently|quietly)\b"
    "tells the model to keep something from the person"]
   [#"(?i)(~/|\$HOME\b|/etc/(passwd|shadow)|\.ssh\b|\bid_(rsa|ed25519|ecdsa)\b|\.aws/|\.env\b|\bprivate\s+keys?\b|\bkeychain\b|\bbrowser\s+(cookies|passwords)\b)"
    "mentions secret files or keys"]
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

(defn- styled-letter?
  "A letter outside ASCII whose compatibility form is plain ASCII letters:
  fullwidth Ａ, mathematical 𝐀, the ligature ﬁ. Accented letters, and
  symbols such as ™, are not."
  [cp]
  (and (> cp 127) (Character/isLetter (int cp))
       (re-matches #"[A-Za-z]+" (java.text.Normalizer/normalize (String. (Character/toChars (int cp)))
                                                                java.text.Normalizer$Form/NFKC))))

(defn- nfkc [s] (java.text.Normalizer/normalize (str s) java.text.Normalizer$Form/NFKC))

(defn text-flags
  "What looks wrong in one piece of text, as reasons, each naming
  `where`. `others` are the names of tools elsewhere (relay's own and
  relay.edn's), which a definition has no reason to name. The patterns
  are matched on the text as written and in its NFKC form, so fullwidth
  letters and ligatures cannot spell an instruction past them."
  [where s others]
  (let [s (str s)
        folded (nfkc s)
        say #(str where ": " %)]
    (concat
     (for [[re why] imperative :when (or (re-find re s) (re-find re folded))] (say why))
     (when (some styled-letter? (iterator-seq (.iterator (.codePoints s))))
       [(say "has letters written in another form (fullwidth, styled or joined letters)")])
     (when-let [cps (seq (hidden-chars s))]
       [(say (str "holds characters that show as nothing (" (str/join ", " (take 5 cps)) ")"))])
     (for [w (take 3 (mixed-words s))] (say (str "has a word that could pass for another: " w)))
     (when (or (seq (text/link-hosts s)) (seq (text/link-hosts folded))
               (re-find bare-domain-pattern s) (re-find bare-domain-pattern folded))
       [(say "holds a link")])
     (when (or (re-find email-pattern s) (re-find email-pattern folded)) [(say "holds an email address")])
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
