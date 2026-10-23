(ns relay.json
  "The one strict JSON reader, for every read of JSON that another process
  wrote: ACP lines in both directions, tool envelopes, external tool
  output, agentloop's output and session files. relay, agentloop and
  relay-tui read such JSON by one rule (relay#58), so a message means the
  same thing to every component or is refused by all of them:

    - RFC 8259 JSON in valid UTF-8, with no byte order mark;
    - exactly one value, with only whitespace after it;
    - no member name twice in one object, at any depth, compared after
      unescaping;
    - no unpaired surrogate, escaped or not;
    - nesting at most 128 deep, the top value being level 1;
    - a number literal at most 1000 characters;
    - a number's exponent between -9999 and 9999, by value, so leading
      zeros in it are allowed as JSON allows them;
    - numbers exact: integers as Long or BigInt, others as BigDecimal.

  Member names are kept as written, so a lookup matches them exactly and
  case-sensitively.

  Two readers do it between them, both compiled into babashka. Jackson,
  through cheshire, checks: duplicate names, nesting, control characters,
  and the end of the input. clojure.data.json then reads the value, since
  babashka's cheshire has no way to keep a decimal exact. Each reads only
  RFC 8259 JSON, so once Jackson has passed a text they read it alike.
  The checks neither makes (surrogates, the length of a whole number
  literal, the exponent bound) are made here, on a fast path that skips them for a text that
  cannot break them.

  `generate` writes JSON that relay passes on, so that every number the
  reader took is written in a form the rule takes too.

  Pure: nothing here does I/O."
  (:refer-clojure :exclude [read])
  (:require [cheshire.core :as cheshire]
            [cheshire.factory :as factory]
            [clojure.data.json :as data-json]
            [clojure.string :as str])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CodingErrorAction StandardCharsets)))

(def max-depth
  "How deep values may nest. The top value is level 1."
  128)

(def max-number-length
  "The most characters a number literal may have, sign and exponent included."
  1000)

(def max-exponent
  "The largest exponent a number may have, either way. Every reader of
  the dialect enforces it, so none meets an exponent it cannot hold."
  9999)

(def ^:private checker
  (factory/make-json-factory
   {:strict-duplicate-detection true
    :allow-unquoted-control-chars false
    :max-input-nesting-depth max-depth
    ;; Jackson counts digits, not the whole literal; numbers-too-long?
    ;; counts the literal.
    :max-input-number-length max-number-length}))

(def ^:private not-utf-8
  "What text puts in place of a byte that is not part of valid UTF-8: a
  lone low surrogate, which no valid UTF-8 decodes to and which parse
  refuses wherever it stands."
  "\uDC80")

(defn text
  "Bytes as text. Valid UTF-8 decodes as it is, a byte order mark kept as
  U+FEFF. Every byte that is not part of valid UTF-8 (a stray byte, an
  overlong form, an encoded surrogate) becomes a lone surrogate, so the
  text can never parse, though it can still be searched or shown."
  ^String [^bytes bs]
  (let [d (doto (.newDecoder StandardCharsets/UTF_8)
            (.onMalformedInput CodingErrorAction/REPLACE)
            (.onUnmappableCharacter CodingErrorAction/REPLACE)
            (.replaceWith not-utf-8))]
    (str (.decode d (ByteBuffer/wrap bs)))))

(defn shown
  "s with every lone surrogate as U+FFFD, for writing out as UTF-8."
  ^String [^String s]
  (.replaceAll s "[\\x{D800}-\\x{DFFF}]" "\uFFFD"))

;; --- the checks Jackson and data.json do not make

(def ^:private long-run
  (re-pattern (str "[-+.0-9eE]{" (inc max-number-length) "}")))

;; A string, consumed whole, or a run of number characters. Unrolled and
;; possessive, so a long string with many escapes cannot overflow the
;; regex engine's stack.
(def ^:private token
  #"\"[^\"\\]*+(?:\\.[^\"\\]*+)*+\"|[-+.0-9eE]++")

(defn- numbers-too-long?
  "Whether a number literal in s is longer than max-number-length. Only a
  text with such a run of characters anywhere is walked token by token,
  to see whether the run is in a string."
  [^String s]
  (boolean
   (and (re-find long-run s)
        (let [m (re-matcher token s)]
          (loop []
            (cond
              (not (.find m)) false
              (and (not= \" (.charAt s (.start m))) (> (- (.end m) (.start m)) max-number-length)) true
              :else (recur)))))))

(def ^:private big-exponent
  ;; An exponent of five or more significant digits, which may be past
  ;; max-exponent. Only a text with one anywhere is walked.
  #"[eE][-+]?0*[1-9][0-9]{4}")

(defn- exponent-in-bounds?
  "Whether the exponent of the number literal n, if it has one, is within
  max-exponent either way. Leading zeros do not count."
  [^String n]
  (let [i (max (.indexOf n "e") (.indexOf n "E"))]
    (or (neg? i)
        (let [digits (str/replace (subs n (inc i)) #"^[-+]?0*" "")]
          (and (<= (count digits) 4)
               (or (= "" digits) (<= (Long/parseLong digits) max-exponent)))))))

(defn- exponent-too-big?
  "Whether a number literal in s has an exponent past max-exponent. s has
  passed Jackson, so outside strings every run of number characters is a
  number literal, or the e that ends true or false."
  [^String s]
  (boolean
   (and (re-find big-exponent s)
        (let [m (re-matcher token s)]
          (loop []
            (cond
              (not (.find m)) false
              (and (not= \" (.charAt s (.start m)))
                   (not (exponent-in-bounds? (subs s (.start m) (.end m))))) true
              :else (recur)))))))

(def ^:private lone-surrogate
  ;; Java's regex reads a valid pair as one code point, so the class
  ;; matches only a surrogate standing alone.
  #"[\x{D800}-\x{DFFF}]")

(def ^:private surrogate-escape
  ;; An escape that may decode to half of a pair.
  #"\\u[dD][89a-fA-F]")

(defn- lone-surrogate-in-text?
  "Whether s has a surrogate standing alone, not as half of a pair. The
  encoder's check is a good deal faster than a regex over a large line."
  [^String s]
  (not (.canEncode (.newEncoder StandardCharsets/UTF_8) s)))

(defn- maybe-escaped-surrogate?
  "Whether s has an escape that may decode to half of a pair. Most lines
  have no escape of the form at all, which indexOf rules out at once."
  [^String s]
  (and (not (neg? (.indexOf s "\\u")))
       (boolean (re-find surrogate-escape s))))

(defn- lone-surrogate? [v]
  (cond
    (string? v) (boolean (re-find lone-surrogate v))
    (map? v) (boolean (some (fn [[k x]] (or (lone-surrogate? k) (lone-surrogate? x))) v))
    (sequential? v) (boolean (some lone-surrogate? v))
    ;; The whole keyword: a name with a slash puts part of it in the
    ;; namespace.
    (keyword? v) (lone-surrogate? (subs (str v) 1))
    :else false))

;; --- reading

(defn- invalid [why]
  (ex-info (str "Not strict JSON: " why) {:type ::invalid}))

(defn- check!
  "Throws unless Jackson reads s as exactly one value, within the limits."
  [^String s]
  (binding [factory/*json-factory* checker]
    (let [vs (cheshire/parsed-seq (java.io.StringReader. s))
          v (first vs)]
      ;; cheshire reads a top-level array lazily: it is read to its end
      ;; before looking past it.
      (when (sequential? v) (dorun v))
      (when (empty? vs) (throw (invalid "no value")))
      (when (next vs) (throw (invalid "more than one value"))))))

(defn parse
  "The one JSON value in x, a string or bytes (decoded by `text`), read by
  the rule in the namespace doc. Throws an ex-info of :type
  :relay.json/invalid when x breaks it.

  Options: :keywords? reads member names as keywords, case kept;
  :doubles? reads every non-integer as a double, for figures relay only
  sums and shows and never passes on."
  ([x] (parse x nil))
  ([x {:keys [keywords? doubles?]}]
   (let [^String s (if (bytes? x) (text x) (str x))]
     (try (check! s)
          (catch clojure.lang.ExceptionInfo e (throw e))
          (catch Throwable e (throw (ex-info (str "Not strict JSON: " (ex-message e)) {:type ::invalid} e))))
     (when (numbers-too-long? s)
       (throw (invalid (str "a number literal over " max-number-length " characters"))))
     (when (exponent-too-big? s)
       (throw (invalid (str "an exponent outside ±" max-exponent))))
     (when (lone-surrogate-in-text? s)
       (throw (invalid "an unpaired surrogate")))
     (let [v (try (data-json/read-str s :bigdec (not doubles?) :key-fn (if keywords? keyword identity))
                  (catch Throwable e (throw (ex-info (str "Not strict JSON: " (ex-message e)) {:type ::invalid} e))))]
       (when (and (maybe-escaped-surrogate? s) (lone-surrogate? v))
         (throw (invalid "an unpaired surrogate")))
       v))))

(defn parse-or
  "The one JSON value in x, as parse reads it, or `not-found` when x breaks
  the rule."
  ([x not-found] (parse-or x not-found nil))
  ([x not-found opts]
   (try (parse x opts)
        (catch clojure.lang.ExceptionInfo e
          (if (= ::invalid (:type (ex-data e))) not-found (throw e))))))

(defn object
  "The JSON object in x, as parse reads it, or nil when x breaks the rule
  or holds another kind of value. Every ACP message is one."
  ([x] (object x nil))
  ([x opts]
   (let [v (parse-or x nil opts)]
     (when (map? v) v))))

;; --- writing

(defn- literal-ok?
  "Whether a number literal s is within the rule's bounds."
  [^String s]
  (and (<= (count s) max-number-length) (exponent-in-bounds? s)))

(defn- literal
  "The literal for the decimal with digits ds (no sign), scale `scale` and
  sign neg?, written with exponent e: ds * 10^-scale as mantissa * 10^e,
  with no exponent when e is 0."
  [neg? ^String ds scale e]
  (let [;; The value is ds * 10^shift * 10^e.
        shift (- (- scale) e)
        mantissa (if (>= shift 0)
                   (str ds (str/join (repeat shift \0)))
                   (let [k (- shift)
                         padded (str (str/join (repeat (max 0 (- k (count ds))) \0)) ds)
                         i (- (count padded) k)]
                     (str (if (zero? i) "0" (subs padded 0 i)) "." (subs padded i))))]
    (str (when neg? "-") mantissa (when-not (zero? e) (str "e" e)))))

(defn number-text
  "A decimal as a number literal the rule takes. BigDecimal's own text
  writes the exponent of the first digit, so a number read as 0.1e-9999
  comes out as 1.0E-10000, past the exponent bound, and 111...1e1 (998
  ones) as 1.11...1E+998, past the length bound. Such a number is written
  in the shortest of a few forms that fit: its digits as they are, or with
  a point among them, at the exponent nearest 0 that allows it, held
  within the exponent bound. Every decimal the reader returns has a form
  among these no longer than the literal it was read from. A form with
  neither point nor exponent is never used, since it would read back as
  an integer."
  [^BigDecimal d]
  (let [s (str d)]
    (if (literal-ok? s)
      s
      (let [u (.unscaledValue d)
            neg? (neg? (.signum u))
            ds (str (.abs u))
            scale (.scale d)
            n (count ds)
            ;; Exponents that put the point after the last digit, among
            ;; the digits (nearest 0, or the ends of that range), or
            ;; before the first digit.
            lo (inc (- scale)) hi (+ (- scale) (dec n))
            es [(- scale) lo hi (max lo (min hi 0)) (+ (- scale) n) 0]
            clamp #(-> % (min max-exponent) (max (- max-exponent)))
            forms (for [e (distinct (map clamp es))
                        :let [t (literal neg? ds scale e)]
                        :when (and (or (not (zero? e)) (str/includes? t "."))
                                   (literal-ok? t))]
                    t)]
        (or (first (sort-by count forms)) s)))))

(defn- odd-decimal?
  "Whether x holds a decimal whose own text the rule refuses."
  [x]
  (cond
    (map? x) (some odd-decimal? (vals x))
    (or (sequential? x) (set? x)) (some odd-decimal? x)
    (decimal? x) (not (literal-ok? (str x)))
    :else false))

(defn- write! [^StringBuilder sb x]
  (cond
    (map? x)
    (do (.append sb "{")
        (doseq [[i [k v]] (map-indexed vector x)]
          (when (pos? i) (.append sb ","))
          (.append sb (cheshire/generate-string (if (keyword? k) (subs (str k) 1) (str k))))
          (.append sb ":")
          (write! sb v))
        (.append sb "}"))

    (or (sequential? x) (set? x))
    (do (.append sb "[")
        (doseq [[i v] (map-indexed vector x)]
          (when (pos? i) (.append sb ","))
          (write! sb v))
        (.append sb "]"))

    (decimal? x) (.append sb (number-text x))
    :else (.append sb (cheshire/generate-string x))))

(defn generate
  "x as JSON text, as cheshire writes it, but with every decimal written
  so that the rule takes it (number-text). Use it to write what relay
  read from another process and passes on, such as a line it rewrites, a
  tool's input, or an answer carrying a client's id."
  [x]
  (if (odd-decimal? x)
    (let [sb (StringBuilder.)] (write! sb x) (str sb))
    (cheshire/generate-string x)))
