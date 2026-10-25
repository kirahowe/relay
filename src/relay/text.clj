(ns relay.text
  "Making text a role wrote safe to show the operator. Pure.

    visible     shows control and format characters (escape sequences,
                bidi overrides, zero-width characters, variation selectors
                and anything else Unicode draws as nothing) as \\u{XXXX},
                so text cannot rewrite the terminal or hide what it says.
    defang      breaks URLs and domains so a tap or click cannot follow
                them.
    for-operator  defang, with a [contaminated] marker, for text whose
                labels say an outsider may have written it. The edge
                that shows text applies visible to it once, last."
  (:require [clojure.string :as str]))

(def default-ignorable
  "Unicode's Default_Ignorable_Code_Point ranges (DerivedCoreProperties):
  code points a renderer draws as nothing, such as variation selectors and
  Hangul fillers, so a run of them can carry text no one sees. Some are
  marks or letters rather than format characters, so their type alone does
  not catch them."
  [[0x00AD 0x00AD] [0x034F 0x034F] [0x061C 0x061C] [0x115F 0x1160] [0x17B4 0x17B5]
   [0x180B 0x180F] [0x200B 0x200F] [0x202A 0x202E] [0x2060 0x206F] [0x3164 0x3164]
   [0xFE00 0xFE0F] [0xFEFF 0xFEFF] [0xFFA0 0xFFA0] [0xFFF0 0xFFF8] [0x1BCA0 0x1BCA3]
   [0x1D173 0x1D17A] [0xE0000 0xE0FFF]])

(defn default-ignorable? [^long cp]
  (boolean (some (fn [[lo hi]] (<= lo cp hi)) default-ignorable)))

(def renders-blank
  "Code points that are not default-ignorable but draw as an empty space
  all the same: the braille blank, the musical null notehead, the Khitan
  filler, and the Hangul fillers (default-ignorable too)."
  #{0x2800 0x1D159 0x16FE4 0x115F 0x1160 0x3164 0xFFA0})

(defn blank-glyph?
  "Whether a code point draws as nothing or as an empty space without
  being a space: default-ignorable or in renders-blank."
  [^long cp]
  (or (default-ignorable? cp) (contains? renders-blank cp)))

(defn hidden?
  "Whether a code point is one visible shows escaped. An emoji's variation
  selector (U+FE0F) is among them: it is shown, as \\u{fe0f}, since the same
  code points can hide bytes of text."
  [^long cp]
  (and (not (#{10 9} cp))
       (let [t (Character/getType (int cp))]
         (or (= t Character/CONTROL) (= t Character/FORMAT) (= t Character/SURROGATE)
             (= t Character/PRIVATE_USE) (= t Character/UNASSIGNED)
             (= t Character/LINE_SEPARATOR) (= t Character/PARAGRAPH_SEPARATOR)
             (blank-glyph? cp)))))

(defn- escape-by
  "s with every code point `escape?` picks shown as \\u{XXXX} and every
  backslash as two."
  [s escape?]
  (let [s (str s)
        sb (StringBuilder.)]
    (doseq [cp (iterator-seq (.iterator (.codePoints s)))]
      (cond
        (escape? cp) (.append sb (format "\\u{%04x}" cp))
        (= 92 cp) (.append sb "\\\\")
        :else (.appendCodePoint sb (int cp))))
    (str sb)))

(defn- escape
  [s line?]
  (escape-by s (fn [cp] (or (hidden? cp) (and line? (= 10 cp))))))

(defn visible
  "Text with every control, format, separator, private-use or unassigned
  code point except newline and tab shown as \\u{XXXX}, braced so the
  escape cannot run into the text after it. A backslash is shown as two,
  so text that spells out an escape cannot pass for one: no two texts look
  the same. Everything else, accented letters included, is left as it is."
  [s]
  (escape s false))

(defn visible-line
  "visible for one line of a screen: a newline is escaped too, as
  \\u{000a}, so nothing in the line can start another. The command line
  shows every line it prints through this, once."
  [s]
  (escape s true))

(def ^:private printable-types
  "The general categories a review's text may hold as they are: letters,
  marks, numbers, punctuation and symbols. Go's unicode.IsPrint takes the
  same set, plus the ASCII space, so relay-tui's review pager can check
  a review text by it."
  #{Character/UPPERCASE_LETTER Character/LOWERCASE_LETTER Character/TITLECASE_LETTER
    Character/MODIFIER_LETTER Character/OTHER_LETTER
    Character/NON_SPACING_MARK Character/ENCLOSING_MARK Character/COMBINING_SPACING_MARK
    Character/DECIMAL_DIGIT_NUMBER Character/LETTER_NUMBER Character/OTHER_NUMBER
    Character/CONNECTOR_PUNCTUATION Character/DASH_PUNCTUATION Character/START_PUNCTUATION
    Character/END_PUNCTUATION Character/INITIAL_QUOTE_PUNCTUATION Character/FINAL_QUOTE_PUNCTUATION
    Character/OTHER_PUNCTUATION
    Character/MATH_SYMBOL Character/CURRENCY_SYMBOL Character/MODIFIER_SYMBOL Character/OTHER_SYMBOL})

(defn printable?
  "Whether a code point may stand as it is in one line of a review text:
  the space (U+0020), or a letter, mark, number, punctuation or symbol
  that visible would not escape. Tabs, newlines and every other space
  (no-break, ideographic, ...) are not."
  [^long cp]
  (or (= 32 cp)
      (and (not (hidden? cp))
           (contains? printable-types (Character/getType (int cp))))))

(defn visible-strict
  "visible for one line of a review text (relay.review): every code point
  that is not printable? shown as \\u{XXXX}, the tab, the newline and
  spaces other than U+0020 included, and a backslash as two. What it
  returns holds only printable? code points, and no two lines give the
  same result. visible itself keeps tabs and other spaces, which the
  command line relies on."
  [s]
  (escape-by s (fn [cp] (not (printable? cp)))))

(defn defang
  "Text with every URL broken so it cannot be followed: http becomes
  hxxp, https hxxps and ftp fxp, any other scheme's :// becomes [:]//, and
  every dot inside a word becomes [.], which breaks bare domains too. A
  plain number keeps its dots (12.50, 1.5.2), so amounts in an approval
  read as written; a dotted quad such as 10.0.0.1 is an address and does
  not."
  [s]
  (-> (str s)
      (str/replace #"(?i)([a-z][a-z0-9+.\-]*)://"
                   (fn [[_ scheme]]
                     (case (str/lower-case scheme)
                       "http" "hxxp://" "https" "hxxps://" "ftp" "fxp://"
                       ("hxxp" "hxxps" "fxp") (str scheme "://")
                       (str scheme "[:]//"))))
      (str/replace #"\S+"
                   (fn [word]
                     (if (and (re-matches #"[$€£¥(+\-]?\d+(\.\d+){1,2}[),.;:%]*" word)
                              (not (re-find #"\d+\.\d+\.\d+\.\d+" word)))
                       word
                       ;; Fullwidth and ideographic full stops work as dots
                       ;; in a host too.
                       (str/replace word #"(?<=\S)[.\u3002\uFF0E\uFF61](?=\S)" "[.]"))))))

(defn contaminated? [labels] (boolean (some #{"contaminated"} labels)))

(defn for-operator
  "Text for the operator from a source with these labels: from a
  contaminated source defanged and marked. It is not yet visible: the edge
  that shows it (the command line, a push) applies visible, exactly once,
  since visible is not idempotent (it doubles a backslash)."
  [labels s]
  (if (contaminated? labels)
    (str "[contaminated] " (defang s))
    (str s)))

(def gutter
  "What every line of untrusted multi-line text is shown behind. Nothing
  else on a screen starts with it, so a role's text cannot pass for a
  line of the screen's own."
  "  │ ")

(defn block
  "Untrusted multi-line text as screen lines: every line, the first
  included, behind `gutter`, and from a contaminated source, defanged and
  headed by a [contaminated] line of the screen's own, which covers the
  whole block. Not yet visible: the edge applies visible-line to each
  line. The one helper for showing a role's or an outsider's text."
  [labels s]
  (let [bad (contaminated? labels)
        t (if bad (defang (str s)) (str s))]
    (vec (concat (when bad ["  [contaminated]"])
                 (map #(str gutter %) (str/split t #"\n" -1))))))

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
       ;; The word as it is: the edge that shows it applies visible once.
       (str "\"" w "\" mixes " (str/join " and " (sort scripts))))
     (for [cp (distinct cps) :when (> cp 127)]
       (format "U+%04X %s" cp (or (Character/getName (int cp)) "unnamed"))))))

;; --- links

(def ^:private link-pattern
  ;; A scheme, then the authority: everything up to a path, query,
  ;; fragment, space or a character that ends a link in prose or markdown.
  #"(?i)\b(?:https?|ftp)://([^\s/?#<>()\[\]{}\"'`\\|^]+)")

(defn- host-of
  "The host of a link's authority, lower case, with the fullwidth and
  ideographic full stops a browser reads as dots made dots. A host with
  lookalike letters keeps them, so it never equals the host it imitates."
  [authority]
  (-> authority
      (str/replace #"^.*@" "")
      (str/replace #"[\u3002\uFF0E\uFF61]" ".")
      (str/replace #":\d*$" "")
      (str/replace #"\.$" "")
      str/lower-case))

(def ^:private named-entities
  {"colon" ":" "sol" "/" "period" "." "commat" "@" "amp" "&" "lt" "<" "gt" ">"
   "quot" "\"" "apos" "'" "num" "#" "quest" "?" "bsol" "\\" "nbsp" " "})

(defn- unescape
  "s as a markdown or HTML renderer would read its characters: character
  references (&#58;, &#x3a;, &colon;) decoded and backslash escapes of
  punctuation dropped. Close enough to find a link a renderer would make."
  [s]
  (-> (str s)
      (str/replace #"(?i)&#x([0-9a-f]{1,6});"
                   (fn [[_ h]] (let [cp (Long/parseLong h 16)] (if (<= cp 0x10FFFF) (String. (Character/toChars cp)) ""))))
      (str/replace #"&#([0-9]{1,7});"
                   (fn [[_ d]] (let [cp (Long/parseLong d)] (if (<= cp 0x10FFFF) (String. (Character/toChars cp)) ""))))
      (str/replace #"&([a-zA-Z]+);" (fn [[m n]] (get named-entities (str/lower-case n) m)))
      (str/replace #"\\([!-/:-@\[-`{-~])" "$1")))

(def ^:private placeholder-pattern
  ;; A single-bracketed phrase starting with a letter: [Venue], [Date of
  ;; the concert]. Not a ref ([[fact:1]]), a markdown link ([x](url)) or
  ;; a checkbox ([x]).
  #"(?<!\[)\[(\p{L}[^\[\]\n]{1,79})\](?![\](])")

(defn placeholders
  "The placeholders left in s, such as [Venue] or [Photographer], in
  order, without repeats: text a role marked as unknown and that would go
  out as written if the operator approved it."
  [s]
  (vec (distinct (map first (re-seq placeholder-pattern (str s))))))

(defn link-hosts
  "The hosts of the links in s that a reader could follow (http, https and
  ftp URLs), in order, without repeats: those in s as written, and those a
  markdown or HTML renderer would find once it decodes character
  references and escapes, so a link cannot hide its host from the check."
  [s]
  (vec (distinct (for [t [(str s) (unescape s)]
                       [_ authority] (re-seq link-pattern t)]
                   (host-of authority)))))
