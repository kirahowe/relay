(ns relay.text
  "Making text a role wrote safe to show the operator. Pure.

    visible     shows control and format characters (escape sequences,
                bidi overrides, zero-width characters, variation selectors
                and anything else Unicode draws as nothing) as \\u{XXXX},
                so text cannot rewrite the terminal or hide what it says.
    defang      breaks URLs and domains so a tap or click cannot follow
                them.
    for-operator  defang, with a [from outside] marker, for text whose
                labels say an outsider may have written it. The edge
                that shows text applies visible to it once, last.

    sanitize    cleans what the operator pastes, at the entry (see
                sanitize-input, the one function every entry point calls),
                and says what it changed.

  The operator is never shown a label's name: text from outside is
  marked as from outside."
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

(defn defang
  "Text with every URL broken so it cannot be followed: http becomes
  hxxp, https hxxps and ftp fxp, any other scheme's :// becomes [:]//, and
  every dot inside a word becomes [.], which breaks bare domains too. A
  plain number keeps its dots (12.50, 1.5.2), so amounts read as
  written; a dotted quad such as 10.0.0.1 is an address and does not."
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

(def outside-marker
  "What marks text from outside for the operator: where it came from, in
  plain words, never the name of a label."
  "[from outside]")

(defn source-words
  "Where something came from, in plain words, from the one label among
  `labels` that names a source (mail:..., github:..., ics:...), or nil
  when none or several do."
  [labels]
  (let [sources (filter #(and (string? %) (re-matches #"[a-z]+:.+" %)) labels)]
    (when (= 1 (count sources))
      (let [[kind what] (str/split (first sources) #":" 2)]
        (case kind
          ;; A mail's sender is what its From header says, which anyone
          ;; can write, so it is shown as a claim, from outside. A mail
          ;; that names the operator and was not verified never carries
          ;; their address here (relay.ingest/mail-label).
          "mail" (cond
                   (= "unverified-operator" what) "from outside: a mail that says it is from you, not verified"
                   (str/includes? what "@") (str "from outside: a mail that says it is from " what))
          "github" (str "from GitHub, " what)
          "ics" (str "from the calendar " what)
          nil)))))

(defn marker
  "What marks text from outside: where it came from when one label says
  so, its links broken too, else [from outside]."
  [labels]
  (if-let [w (source-words labels)] (str "[" (defang w) "]") outside-marker))

(defn for-operator
  "Text for the operator from a source with these labels: from outside
  defanged and marked. It is not yet visible: the edge that shows it (the
  command line, a push) applies visible, exactly once, since visible is
  not idempotent (it doubles a backslash)."
  [labels s]
  (if (contaminated? labels)
    (str (marker labels) " " (defang s))
    (str s)))

(def gutter
  "What every line of untrusted multi-line text is shown behind. Nothing
  else on a screen starts with it, so a role's text cannot pass for a
  line of the screen's own."
  "  │ ")

(defn block
  "A role's or an outsider's multi-line text as screen lines: every line,
  the first included, behind `gutter`, and from outside, defanged and
  headed by a [from outside] line of the screen's own, which covers the
  whole block. Not yet visible: the edge applies visible-line to each
  line. The one helper for showing a role's or an outsider's text."
  [labels s]
  (let [bad (contaminated? labels)
        t (if bad (defang (str s)) (str s))]
    (vec (concat (when bad [(str "  " (marker labels))])
                 (map #(str gutter %) (str/split t #"\n" -1))))))

(def ^:private scripts
  "The scripts whose letters most often pass for one another's."
  (into {} (for [s ["Latin" "Cyrillic" "Greek" "Armenian" "Cherokee" "Hebrew" "Arabic" "Han"
                    "Hiragana" "Katakana" "Hangul" "Georgian" "Thai" "Devanagari"]]
             [s (re-pattern (str "\\p{Is" s "}"))])))

(defn- script-of [cp]
  (let [c (String. (Character/toChars (int cp)))]
    (some (fn [[s re]] (when (re-matches re c) s)) scripts)))

(defn lookalike-findings
  "What in a text could pass for something it is not, as data: for each
  word that mixes scripts (Latin with Cyrillic, say) {:word w :scripts
  [...]}, then for each distinct character outside ASCII {:cp cp :name
  its Unicode name, or \"unnamed\"}. Empty when the text is plain ASCII."
  [s]
  (let [s (str s)]
    (if (every? #(< (int %) 128) s)
      []
      (vec
       (concat
        (for [w (re-seq #"\S+" s)
              :when (some #(> (int %) 127) w)
              :let [scripts (set (keep script-of (iterator-seq (.iterator (.codePoints ^String w)))))]
              :when (> (count scripts) 1)]
          {:word w :scripts (vec (sort scripts))})
        (for [cp (distinct (iterator-seq (.iterator (.codePoints s)))) :when (> cp 127)]
          {:cp cp :name (or (Character/getName (int cp)) "unnamed")}))))))

;; --- what the operator pastes
;;
;; Pastes are the operator's, so nothing is withheld. They are cleaned
;; deterministically at the entry, and the operator is told each change,
;; so text that hides characters (bidi overrides, zero-width runs, tag
;; characters that spell out a message) cannot ride in on a paste.

(defn- bidi? [^long cp]
  (or (<= 0x202A cp 0x202E) (<= 0x2066 cp 0x2069) (contains? #{0x200E 0x200F 0x061C} cp)))

(defn- tag? [^long cp] (<= 0xE0000 cp 0xE007F))

(defn- emoji-block-unassigned?
  "An unassigned code point in the emoji and symbol planes' blocks. The
  JVM's Unicode tables lag the newest emoji, so these are left alone
  rather than deleted from what the operator wrote."
  [^long cp]
  (and (<= 0x1F000 cp 0x1FFFF) (= Character/UNASSIGNED (Character/getType (int cp)))))

(defn- strip-kind
  "What sanitize does with a code point: nil to keep it, else :separator
  (becomes a line break), :bidi, :tag, :control or :invisible (removed).
  Tab, newline and carriage return stay."
  [^long cp]
  (cond
    (contains? #{9 10 13} cp) nil
    (contains? #{0x85 0x2028 0x2029} cp) :separator
    (bidi? cp) :bidi
    (tag? cp) :tag
    (= Character/CONTROL (Character/getType (int cp))) :control
    (emoji-block-unassigned? cp) nil
    (hidden? cp) :invisible
    :else nil))

(defn- strip
  "[text counts]: s without what strip-kind removes, and how many of each."
  [^String s]
  (let [sb (StringBuilder.)
        counts (volatile! {})]
    (doseq [cp (iterator-seq (.iterator (.codePoints s)))]
      (case (strip-kind cp)
        nil (.appendCodePoint sb (int cp))
        :separator (do (.append sb \newline) (vswap! counts update :separator (fnil inc 0)))
        (vswap! counts update (strip-kind cp) (fnil inc 0))))
    [(str sb) @counts]))

(def ^:private cjk #{"Han" "Hiragana" "Katakana" "Hangul"})

(def ^:private latin-lookalikes
  "Cyrillic and Greek letters that pass for Latin ones."
  (set "АВЕКМНОРСТУХаеорсухѕіјԁԛԝΑΒΕΖΗΙΚΜΝΟΡΤΥΧαοινρυ"))

(defn- single-ascii-letter?
  "Whether cp, normalized by NFKC, is exactly one ASCII letter (fullwidth
  and mathematical letters, circled letters), but not a ligature or a
  symbol like the trademark sign."
  [cp]
  (and (> cp 127)
       (let [n (java.text.Normalizer/normalize (String. (Character/toChars (int cp))) java.text.Normalizer$Form/NFKC)]
         (and (= 1 (count n)) (< (int (first n)) 128) (Character/isLetter (first n))))))

(defn- cps [^String w] (iterator-seq (.iterator (.codePoints w))))

(defn- lookalike-words
  "Words that are not what they look like, beyond the script mixes
  lookalike-findings reports: words with fullwidth or mathematical
  letters ({:word w :reads-as ascii}), and whole Cyrillic or Greek words
  of Latin-looking letters among Latin text ({:word w :scripts [s]
  :whole-script true})."
  [s]
  (let [words (re-seq #"\S+" s)
        latin-text? (some #(re-find #"[A-Za-z]{2}" %) words)]
    (for [w words
          :when (some #(> (int %) 127) w)
          :let [nfkc (java.text.Normalizer/normalize ^String w java.text.Normalizer$Form/NFKC)
                letters (filter #(Character/isLetter (int %)) (cps w))
                scripts (set (keep script-of letters))]
          :let [finding
                (cond
                  (and (not= nfkc w) (some single-ascii-letter? (cps w)))
                  {:word w :reads-as nfkc}
                  (and latin-text?
                       (= 1 (count scripts))
                       (#{"Cyrillic" "Greek"} (first scripts))
                       (>= (count letters) 2)
                       (every? #(contains? latin-lookalikes (char %)) letters))
                  {:word w :scripts (vec scripts) :whole-script true})]
          :when finding]
      finding)))

(defn- mixed-words
  "Words worth a second look, as data: those that mix alphabets, as
  lookalike-findings finds them, less the ordinary mixes of Japanese and
  Korean writing, then lookalike-words."
  [s]
  (vec (distinct
        (concat
         (for [{:keys [word scripts]} (lookalike-findings s)
               :when word
               :when (> (count (set (map #(if (cjk %) "CJK" %) scripts))) 1)]
           {:word word :scripts scripts})
         (lookalike-words s)))))

(def empty-report {:removed {} :normalized? false :mixed []})

(defn merge-reports
  "One report from several: counts added, words kept once."
  [& rs]
  (reduce (fn [a b]
            {:removed (merge-with + (:removed a) (:removed b))
             :normalized? (boolean (or (:normalized? a) (:normalized? b)))
             :mixed (vec (distinct (concat (:mixed a) (:mixed b))))})
          empty-report rs))

(defn sanitize
  "A pasted string, cleaned: invisible, bidi-control, tag, control and
  private-use characters removed (tab, newline and carriage return stay;
  U+0085, U+2028 and U+2029 become a newline), then NFC, until nothing
  changes, so sanitize is idempotent. Printable ASCII is never touched.
  Returns {:text cleaned :report r}, r saying what was removed by kind,
  whether normalizing changed anything, and the words that mix alphabets or pass for other letters.
  Pure."
  [s]
  (loop [t (str s) removed {} normalized? false]
    (let [[t1 counts] (strip t)
          t2 (java.text.Normalizer/normalize t1 java.text.Normalizer$Form/NFC)
          removed (merge-with + removed counts)
          normalized? (or normalized? (not= t1 t2))]
      (if (= t2 t)
        {:text t2 :report {:removed removed :normalized? normalized? :mixed (mixed-words t2)}}
        (recur t2 removed normalized?)))))

(defn- plural [n one many] (str n " " (if (= 1 n) one many)))

(defn- clip [^String w]
  (if (> (.codePointCount w 0 (count w)) 40)
    (str (subs w 0 (.offsetByCodePoints w 0 40)) "…")
    w))

(defn report-lines
  "What to tell the operator about a report: one plain line per kind of
  change, empty when nothing changed. Never mentions labels."
  [{:keys [removed normalized? mixed]}]
  (let [from "from what you pasted."
        n #(get removed % 0)
        shown (take 3 mixed)]
    (vec
     (concat
      (when (pos? (n :control)) [(str "Removed " (plural (n :control) "control character" "control characters") " " from)])
      (when (pos? (n :invisible)) [(str "Removed " (plural (n :invisible) "invisible character" "invisible characters") " " from)])
      (when (pos? (n :bidi)) [(str "Removed " (plural (n :bidi) "text-direction character" "text-direction characters") " " from)])
      (when (pos? (n :tag)) [(str "Removed " (plural (n :tag) "hidden tag character" "hidden tag characters") " " from)])
      (when (pos? (n :separator))
        [(str "Turned " (plural (n :separator) "unusual line separator" "unusual line separators") " into ordinary line breaks.")])
      (when normalized? ["Put some combined characters into their standard form."])
      (for [{:keys [word scripts reads-as whole-script]} shown]
        (cond
          reads-as (str "The word \"" (clip word) "\" uses look-alike letters; it reads as \"" (clip reads-as) "\".")
          whole-script (str "The word \"" (clip word) "\" is written in " (first scripts)
                            " letters that look like Latin ones.")
          :else (str "The word \"" (clip word) "\" mixes " (str/join " and " scripts)
                     " letters, so it may not be what it looks like.")))
      (when (> (count mixed) 3)
        [(str (- (count mixed) 3) " more words mix letters from different alphabets.")])))))

(defn sanitize-input
  "The one function every operator entry point calls: a string, or any
  data holding strings (map values, vector items; keys are left alone),
  with every string sanitized. Returns {:value cleaned :report r :notes
  lines}, :notes being what to tell the operator, one plain line per
  change, empty when nothing changed. Entry points that exist today (the
  command line, conversation prompts and resources, board edits) call
  it; the channels that accept replies call it too."
  [x]
  (let [reports (volatile! [])
        walk (fn walk [v]
               (cond
                 (string? v) (let [{:keys [text report]} (sanitize v)]
                               (vswap! reports conj report)
                               text)
                 (map? v) (reduce-kv (fn [m k x] (assoc m k (walk x))) v v)
                 (vector? v) (mapv walk v)
                 (sequential? v) (doall (map walk v))
                 :else v))
        value (walk x)
        report (apply merge-reports empty-report @reports)]
    {:value value :report report :notes (report-lines report)}))
