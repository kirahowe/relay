(ns relay.text
  "Making text a role wrote safe to show the operator. Pure.

    visible     shows control and format characters (escape sequences,
                bidi overrides, zero-width characters) as \\u{XXXX}, so
                text cannot rewrite the terminal or hide what it says.
    defang      breaks URLs and domains so a tap or click cannot follow
                them.
    for-operator  both, with a [contaminated] marker, for text whose
                labels say an outsider may have written it."
  (:require [clojure.string :as str]))

(defn hidden?
  "Whether a code point is one visible shows escaped."
  [^long cp]
  (and (not (#{10 9} cp))
       (let [t (Character/getType (int cp))]
         (or (= t Character/CONTROL) (= t Character/FORMAT) (= t Character/SURROGATE)
             (= t Character/PRIVATE_USE) (= t Character/UNASSIGNED)
             (= t Character/LINE_SEPARATOR) (= t Character/PARAGRAPH_SEPARATOR)))))

(defn visible
  "Text with every control, format, separator, private-use or unassigned
  code point except newline and tab shown as \\u{XXXX}, braced so the
  escape cannot run into the text after it. Everything else, accented
  letters included, is left as it is."
  [s]
  (let [s (str s)
        sb (StringBuilder.)]
    (doseq [cp (iterator-seq (.iterator (.codePoints s)))]
      (if (hidden? cp)
        (.append sb (format "\\u{%04x}" cp))
        (.appendCodePoint sb (int cp))))
    (str sb)))

(defn defang
  "Text with every URL broken so it cannot be followed: http becomes
  hxxp, https hxxps and ftp fxp, any other scheme's :// becomes [:]//, and
  every dot inside a word becomes [.], which breaks bare domains too."
  [s]
  (-> (str s)
      (str/replace #"(?i)([a-z][a-z0-9+.\-]*)://"
                   (fn [[_ scheme]]
                     (case (str/lower-case scheme)
                       "http" "hxxp://" "https" "hxxps://" "ftp" "fxp://"
                       ("hxxp" "hxxps" "fxp") (str scheme "://")
                       (str scheme "[:]//"))))
      ;; Fullwidth and ideographic full stops work as dots in a host too.
      (str/replace #"(?<=\S)[.\u3002\uFF0E\uFF61](?=\S)" "[.]")))

(defn contaminated? [labels] (boolean (some #{"contaminated"} labels)))

(defn for-operator
  "Text for the operator from a source with these labels: visible always,
  and from a contaminated source defanged and marked."
  [labels s]
  (if (contaminated? labels)
    (str "[contaminated] " (visible (defang s)))
    (visible s)))

(def ^:private scripts
  "The scripts whose letters most often pass for one another's."
  (into {} (for [s ["Latin" "Cyrillic" "Greek" "Armenian" "Cherokee" "Hebrew" "Arabic" "Han"
                    "Hiragana" "Katakana" "Hangul" "Georgian" "Thai" "Devanagari"]]
             [s (re-pattern (str "\\p{Is" s "}"))])))

(defn- script-of [cp]
  (let [c (String. (Character/toChars (int cp)))]
    (some (fn [[s re]] (when (re-matches re c) s)) scripts)))

(defn lookalikes
  "What in a text could pass for something it is not, for a person about
  to approve it: each word that mixes scripts (Latin with Cyrillic, say),
  and each character outside ASCII, by its Unicode name. Empty when the
  text is plain ASCII."
  [s]
  (let [s (str s)
        cps (iterator-seq (.iterator (.codePoints s)))
        words (re-seq #"\S+" s)]
    (concat
     (for [w words
           :let [scripts (set (keep script-of (iterator-seq (.iterator (.codePoints ^String w)))))]
           :when (> (count scripts) 1)]
       (str "\"" (visible w) "\" mixes " (str/join " and " (sort scripts))))
     (for [cp (distinct cps) :when (> cp 127)]
       (format "U+%04X %s" cp (or (Character/getName (int cp)) "unnamed"))))))
