(ns relay.text
  "Making text a role wrote safe to show the operator. Pure.

    visible     shows control and format characters (escape sequences,
                bidi overrides, zero-width characters, variation selectors
                and anything else Unicode draws as nothing) as \\u{XXXX},
                so text cannot rewrite the terminal or hide what it says.
    defang      breaks links and hosts so a tap or click cannot follow
                them, and leaves file names, paths and times alone.
    for-operator  defang, for text whose labels say an outsider may have
                written it. The edge that shows text applies visible to
                it once, last.
    links-in    the live links such text holds, which travel beside it
                as data and are never left whole in it.

    sanitize    cleans what the operator pastes, at the entry (see
                sanitize-input, the one function every entry point calls),
                and says what it changed.

  The operator is never shown a label's name. Where something came from
  is said in words, once, by relay.provenance."
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
  or as nothing all the same: the braille blank, the musical null
  notehead, the Khitan filler, the Hangul fillers (default-ignorable
  too), and two marks that are invisible by design, the Brahmi number
  joiner and the Duployan thick letter selector."
  #{0x2800 0x1D159 0x16FE4 0x115F 0x1160 0x3164 0xFFA0 0x1107F 0x1BC9D})

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

(defn- break-dots [s] (str/replace s "." "[.]"))

(defn- scheme-char? [c]
  (let [c (int c)]
    (or (<= (int \a) c (int \z)) (<= (int \A) c (int \Z)) (<= (int \0) c (int \9))
        (= c (int \+)) (= c (int \.)) (= c (int \-)))))

(defn- ascii-letter? [c]
  (let [c (int c)]
    (or (<= (int \a) c (int \z)) (<= (int \A) c (int \Z)))))

(defn- scheme-start
  "Where the scheme that ends at index `at` of `s` starts, `at` being
  where a :// stands: the first letter in the run of scheme characters
  (letters, digits, + . -) before it, looking back no further than
  `from`. Nil when that run holds no letter. One walk back over the run,
  so finding every scheme in a text takes time in its length, however
  long a word is; a pattern that tries each letter as a start takes time
  in its square."
  [^String s from at]
  (let [run (loop [i (long at)]
              (if (and (> i (long from)) (scheme-char? (.charAt s (dec i)))) (recur (dec i)) i))]
    (loop [i run]
      (cond (>= i (long at)) nil
            (ascii-letter? (.charAt s i)) i
            :else (recur (inc i))))))

(defn- break-schemes
  "`s` with every scheme before a :// broken: http, https and ftp to
  hxxp, hxxps and fxp, one of those left as it is, and any other
  scheme's :// to [:]//."
  [^String s]
  (let [sb (StringBuilder.)]
    (loop [from 0]
      (let [at (.indexOf s "://" (int from))]
        (if (neg? at)
          (str (.append sb (subs s from)))
          (if-let [start (scheme-start s from at)]
            (let [scheme (subs s start at)]
              (.append sb (subs s from start))
              (.append sb (case (str/lower-case scheme)
                            "http" "hxxp://" "https" "hxxps://" "ftp" "fxp://"
                            ("hxxp" "hxxps" "fxp") (str scheme "://")
                            (str scheme "[:]//")))
              (recur (+ at 3)))
            (do (.append sb (subs s from (+ at 3)))
                (recur (+ at 3)))))))))

(def ^:private slashless-scheme
  ;; A scheme a link finder follows with nothing but a colon after it.
  ;; mailto, tel and sms are not here: whether those are broken is
  ;; relay#121's question. It counts after anything but a letter or a
  ;; digit: Ghostty's pattern takes ssh: wherever it stands, so one
  ;; written straight after a dot, a plus or a broken dot's bracket
  ;; (x.ssh:u@h.example) is a link there. One run on from a letter or a
  ;; digit (Makefile:12, profile:dev) is left as written: it is how
  ;; ordinary text reads, and nobody writes an address that way unless
  ;; they have read this.
  #"(?i)(?<![a-z0-9])(ssh|sftp|file|magnet|news|git):(?=\S)(?!//)")

(defn break-every-dot
  "A word that holds a link, with every dot in it broken: the ASCII dot
  and the fullwidth and ideographic full stops a browser reads as one.
  Also for a word about to be cut in two."
  [word]
  (-> word
      (str/replace #"(?<=\S)[。．｡](?=\S)" "[.]")
      (str/replace #"(?<=[^\s\[])\.(?=[^\s\]])" "[.]")))

(defn- break-word-schemes
  "A word with its schemes broken: one before a ://, a web scheme written
  short, and a scheme a link finder follows with no slashes at all."
  [word]
  (-> (break-schemes word)
      ;; A web scheme written short, https:/x.example, https:x.example or
      ;; https:\\x.example. After anything but a letter or a digit, as
      ;; for slashless-scheme.
      (str/replace #"(?i)(?<![a-z0-9])(https?|ftp):(?=[/\\A-Za-z0-9])(?!//)"
                   (fn [[_ scheme]]
                     (case (str/lower-case scheme) "http" "hxxp:" "https" "hxxps:" "fxp:")))
      (str/replace slashless-scheme (fn [[_ scheme]] (str scheme "[:]")))))

(def ^:private no-width
  ;; A character with no width of its own that is not hidden?: a
  ;; combining or enclosing mark, or a conjoining jamo, which draws on the
  ;; letter before it.
  #"[\p{Mn}\p{Me}\x{1160}-\x{11FF}\x{D7B0}-\x{D7FF}]")

(defn- mark?
  "Whether a code point is a combining or enclosing mark, which draws on
  the character before it."
  [^long cp]
  (let [t (Character/getType (int cp))]
    (or (= t Character/NON_SPACING_MARK) (= t Character/ENCLOSING_MARK))))

(defn- holds-an-address?
  "Whether a word, its schemes already broken, holds a scheme or www.:
  every dot in such a word is broken, before a digit too."
  [word]
  (boolean (or (str/includes? word "://") (str/includes? word "[:]")
               (re-find #"(?i)(?<![a-z0-9])www\." word)
               (re-find #"(?i)(?<![a-z0-9])(?:hxxps?|fxp):(?=\S)" word))))

(defn- closes-a-sentence?
  "Whether a code point is punctuation that may stand after a full stop
  at the end of a sentence: a closing quote, a bracket, a comma, another
  dot. Not an underscore, and not a fullwidth or ideographic full stop."
  [^long cp]
  (if (< cp 128)
    (and (<= 0x21 cp 0x7E) (not (Character/isLetterOrDigit (int cp))) (not= cp (int \_)))
    (let [t (Character/getType (int cp))]
      (and (or (= t Character/DASH_PUNCTUATION) (= t Character/START_PUNCTUATION) (= t Character/END_PUNCTUATION)
               (= t Character/INITIAL_QUOTE_PUNCTUATION) (= t Character/FINAL_QUOTE_PUNCTUATION)
               (= t Character/OTHER_PUNCTUATION))
           (not (#{0x3002 0xFF0E 0xFF61} cp))))))

(defn- break-host-dots
  "A word with every dot broken that a host name could have: a fullwidth
  or ideographic full stop between two characters, and an ASCII dot with
  something before it unless what follows it, marks aside, is an ASCII
  digit or nothing but punctuation to the end of the word.

  This rests on how addresses are written, not on any reader: a host is
  labels joined by dots and ends in a top-level domain, and every
  top-level domain begins with a letter. A dot before a digit can end no
  host name, so amounts, times and versions (12.50, 08:06:36.382648Z,
  v1.2) stay as written, and so does a full stop that ends a sentence,
  inside a closing quote too. A host made only of digits is an IPv4
  address, broken on its own (break-dotted-quads)."
  [^String word]
  (let [^String word (str/replace word #"(?<=\S)[。．｡](?=\S)" "[.]")
        cps (vec (iterator-seq (.iterator (.codePoints word))))
        n (count cps)
        ;; From which index on the word holds nothing but punctuation and
        ;; marks: one walk back from its end.
        tail (loop [i n]
               (if (and (pos? i) (let [cp (long (cps (dec i)))] (or (closes-a-sentence? cp) (mark? cp))))
                 (recur (dec i))
                 i))
        sb (StringBuilder.)]
    (dotimes [i n]
      (let [cp (long (cps i))
            keep? (or (not= cp 46) (zero? i) (= 91 (long (cps (dec i))))
                      (let [j (loop [j (inc i)] (if (and (< j n) (mark? (cps j))) (recur (inc j)) j))]
                        (or (>= j tail) (<= 0x30 (long (cps j)) 0x39))))]
        (if keep? (.appendCodePoint sb (int cp)) (.append sb "[.]"))))
    (str sb)))

(defn- break-dotted-quads
  "A word with the dots of any dotted quad in it broken, one with more
  dots after it too (10.0.0.1.example): an IPv4 address is a host made
  only of digits."
  [word]
  (str/replace word #"(?<![\w\]])\d{1,3}(?:\.\d{1,3}){3}(?!\w)" break-dots))

(defn- break-word
  "One word of outside text, with nothing in it left that could be
  followed (see defang)."
  [word]
  (let [bare #(str/replace % no-width "")
        as-written (break-word-schemes word)
        as-read (break-word-schemes (bare word))
        ;; Where taking the marks out finds a scheme the word as written
        ;; does not show, a mark was hiding it: the word is shown without
        ;; its marks, broken as the address it spells.
        w (if (= as-read (bare as-written)) as-written as-read)]
    (-> (if (or (holds-an-address? w) (holds-an-address? (bare w))) (break-every-dot w) w)
        break-dotted-quads
        break-host-dots)))

(defn defang
  "Text with every link broken so it cannot be followed. http becomes
  hxxp, https hxxps and ftp fxp, written with or without slashes; any
  other scheme's :// becomes [:]//, and ssh:, file: and their kind
  ssh[:]. In a word that holds a scheme or www. every dot becomes [.].
  In any other word, every dot that a host name could have becomes [.]:
  one with a letter, a symbol or anything but a digit after it, unless
  only punctuation follows it to the end of the word (break-host-dots),
  and the dots of a dotted quad. So todo[.]md, kira@mail[.]example and
  résumé[.]pdf, while 12.50, 08:06:36.382648Z, v1.2 and the full stop
  of a sentence stay as written.

  It recognises nothing as a file name, a mail address or a link it may
  leave whole. Whether text can be followed then depends on a fact about
  addresses, not on what any reader's link finder makes of it (relay#130:
  three review rounds each found a name relay left whole and Apple's
  finder followed).

  Text is taken a word at a time, a word being what stands between
  spaces of any kind. A scheme a combining mark was hiding is broken
  with the mark taken out. Hidden characters (hidden?) are not looked
  through: a caller that removes them must do so first, or a host split
  by one would come together again after this has looked at it."
  [s]
  (str/replace (str s) #"[^\s\p{Z}]+" break-word))

(defn contaminated?
  "Whether labels say an outsider may have written the text: contaminated,
  or a label naming an outside source (mail:..., github:...)."
  [labels]
  (boolean (some #(and (string? %) (or (= "contaminated" %) (re-matches #"[a-z]+:.+" %))) labels)))

;; Where something came from is said once, in words, where it matters: a
;; message's footer (relay.provenance/drew-on), the end of a status line,
;; the "from" line of a ready item. Text itself carries no mark, so a
;; subject or a line reads as written.

;; --- live links
;;
;; Text an outsider may have shaped is inert: every link in it is broken
;; (defang), always. The links relay can say came from somewhere it takes
;; links from travel beside the text, as data: relay.core keeps them as
;; the live links (what the web showed a scout, whole, and what the
;; operator called safe), `links-in` finds the ones a text holds, and
;; each channel carries their addresses by a mechanism of its own, never
;; by leaving one whole for a link finder to read. A live link is trusted
;; for where it came from, never for the host it points at.

(def max-link "The most characters in a live link." 2000)

(def ^:private link-shape
  ;; http or https in lower case, a host of ASCII letters, digits, dots
  ;; and hyphens, a port, then letters, digits and - . _ ~ / ? # & = % : +
  ;; @ and nothing else. No user name before the host, which could pass
  ;; for the host, and no backslash or character outside ASCII, so
  ;; `visible` leaves a live link exactly as it is.
  #"https?://[A-Za-z0-9](?:[A-Za-z0-9.\-]*[A-Za-z0-9])?(?::\d{1,5})?(?:[/?#][A-Za-z0-9\-._~/?#&=%:+@]*)?")

(def ^:private whole-ending
  ;; A letter or a digit, or a slash right after one.
  #"[A-Za-z0-9]/?$")

(defn link?
  "Whether a string can be a live link: one http or https address in
  plain ASCII (link-shape), at most max-link characters, that every
  common link finder reads to its end. Finders disagree about the rest
  of what RFC 3986 allows. Apple's, which mail and the phone use,
  balances brackets across whatever follows an address and drops the
  end of one from a full stop, comma, semicolon or exclamation mark,
  even between angle brackets. Ghostty's stops at an apostrophe. A mail
  still shows an address as text, alone on a line, for a finder to
  read, and one rule for what enters the set is simpler than two. So a
  live link holds only letters, digits and - . _ ~ / ? # & = % : + @,
  one :// and no second, and ends in a letter or a digit, or a slash
  right after one. An address of any other shape is never live. Of
  600,000 random addresses shown between angle brackets with text
  before and after, 188,377 were of this shape, and NSDataDetector
  followed every one of them exactly (scripts/link-finder-fuzz.swift,
  2026-11-14)."
  [s]
  (boolean (and (string? s) (<= (count s) max-link) (re-matches link-shape s)
                (re-find whole-ending s)
                ;; One address, never a second inside it: a finder that
                ;; looks back for a scheme (kitty's) would follow the
                ;; inner one.
                (= 1 (count (re-seq #"://" s))))))

(defn mend
  "The address a link on a screen stood for, when `s` is one link as
  relay shows it and nothing else: a broken one has hxxp and hxxps as
  http and https again and every [.] a dot, and one between angle
  brackets, as a role may write it, loses them. Any other text comes
  back trimmed and otherwise as it is. The operator copies a link off a
  screen as it is shown, so what keeps or drops a link takes any of
  these forms."
  [s]
  (let [s (str/trim (str s))
        s (if-let [[_ inner] (re-matches #"<(\S+)>" s)] inner s)]
    (if-let [[_ scheme more] (re-matches #"(hxxps?)://(\S*)" s)]
      (str (if (= "hxxps" scheme) "https" "http") "://" (str/replace more "[.]" "."))
      s)))

(def address-closers
  "The marks that end a clause or close a bracket or a quote after an
  address, which are no part of it: Apple's link finder leaves these off
  the end of an address (checked against NSDataDetector)."
  #{\) \> \" \. \, \; \!})

(def link-closers
  "What a role may write straight after a link without it being part of
  the link: address-closers, and the marks of a question, a list and
  Markdown's links and emphasis."
  (into address-closers #{\] \' \: \? \* \_ \~}))

(def max-closers
  "How many link-closers may follow a link a role wrote, as in
  **[a fund](https://x.example/a)**."
  6)

(defn- first-scheme
  "Where a word's first scheme starts, or nil: the scheme before its
  first :// that has one (scheme-start)."
  [^String word]
  (loop [from 0]
    (let [at (.indexOf word "://" (int from))]
      (when-not (neg? at)
        (or (scheme-start word from at)
            (recur (+ at 3)))))))

(defn address-in
  "For one piece of text with no space in it, [before address after]
  when it holds an address, or nil: from its first scheme to its end,
  less the address-closers after it. This reads text that is not a
  role's, a web page's or what the operator typed, where an address is
  cut once, as a link finder cuts it."
  [word]
  (when-let [start (first-scheme word)]
    (let [run (subs word start)
          end (loop [end (count run)]
                (if (and (pos? end) (address-closers (.charAt ^String run (dec end)))) (recur (dec end)) end))
          address (subs run 0 end)]
      (when (link? address)
        [(subs word 0 start) address (subs run end)]))))

(defn link-in
  "For one word a role wrote (no space in it), [before link after] when
  `ok?` takes a link it holds, or nil. The link starts at the word's
  first scheme and runs to the end of the word, less at most max-closers
  link-closers; the longest that `ok?` takes wins. Text that runs on
  after an address is part of it, so it makes a different address, never
  a taken one with more after it."
  [ok? word]
  (when-let [start (first-scheme word)]
    (let [run (subs word start)]
      (some (fn [n]
              (let [cut (- (count run) n)]
                (when (pos? cut)
                  (let [link (subs run 0 cut)
                        after (subs run cut)]
                    (when (and (every? link-closers after) (link? link) (ok? link))
                      [(subs word 0 start) link after])))))
            (range 0 (inc max-closers))))))

(defn links-in
  "The links of `live` (a set of addresses) that text `s` holds, in the
  order written, each once: for each word, the one `link-in` finds."
  [live s]
  (if (empty? live)
    []
    (vec (distinct (keep #(second (link-in (fn [l] (contains? live l)) %)) (re-seq #"\S+" (str s)))))))

(def max-listed "The most live links listed beside one card, message or ready item." 50)

(defn listed
  "Live links as they travel beside text an outsider may have shaped, for
  a client: {\"links\" [{\"address\" a \"shown\" broken}] \"linksLeftOut\"
  n}. `shown` is the broken form the text shows for the address (defang),
  so that whatever draws the text can tie the two together. At most
  max-listed are listed, and linksLeftOut says how many more there were,
  so that a link missing from the list reads as a limit."
  [addresses]
  (let [addresses (vec (distinct addresses))]
    {"links" (mapv (fn [a] {"address" a "shown" (defang a)}) (take max-listed addresses))
     "linksLeftOut" (max 0 (- (count addresses) max-listed))}))

(defn- osc8
  "`shown` as a terminal hyperlink to `address` (OSC 8). An address is of
  link? shape, so it holds nothing that could end the sequence early."
  [address shown]
  (str "\u001b]8;;" address "\u001b\\" shown "\u001b]8;;\u001b\\"))

(defn anchored
  "A line already made safe to show, with each live link in it turned
  into a terminal hyperlink: where a word holds the broken form of a
  link in `live`, with nothing after it but at most max-closers
  link-closers, that broken form becomes the text of an OSC 8 hyperlink
  to the address. The text stays inert, so a terminal's link finder has
  nothing to read, and the terminal follows exactly the address relay
  names. This is the rule a client follows for a `shown` too. For a
  terminal only: the edge that prints decides."
  [live line]
  (if (or (empty? live) (not (str/includes? (str line) "hxxp")))
    (str line)
    (str/replace (str line) #"\S+"
                 (fn [word]
                   (if-let [start (str/index-of word "hxxp")]
                     (let [run (subs word start)
                           hit (some (fn [n]
                                       (let [cut (- (count run) n)]
                                         (when (pos? cut)
                                           (let [shown (subs run 0 cut)
                                                 after (subs run cut)
                                                 address (mend shown)]
                                             (when (and (every? link-closers after) (contains? live address)
                                                        (link? address) (= shown (defang address)))
                                               [address shown after])))))
                                     (range 0 (inc max-closers)))]
                       (if-let [[address shown after] hit]
                         (str (subs word 0 start) (osc8 address shown) after)
                         word))
                     word)))))

(defn break-cut-word
  "`head`, the start a cut kept of `s`, text already made safe to show,
  with the links in its last word broken (defang) when the cut fell
  inside that word. A cut word holds only the start of what it held, and
  the start of a live link is an address nobody took."
  [^String s ^String head]
  (let [mid-word? (and (< (count head) (count s))
                       (not (Character/isWhitespace (.charAt s (count head)))))
        start (if-let [m (re-find #"^(?s)(.*\s)\S*$" head)] (count (second m)) 0)]
    (if mid-word?
      (str (subs head 0 start) (defang (subs head start)))
      head)))

(defn clip-shown
  "`s`, text already made safe to show, cut to at most `n` code points
  with an ellipsis at its end when it was longer. The links in a word
  the cut fell inside are broken (break-cut-word). Breaking a link makes
  it longer, so the text is then cut once more; a broken link cut short
  is still broken."
  [s n]
  (let [s (str s)
        over? (fn [^String t m] (> (.codePointCount t 0 (count t)) m))
        cut (fn [^String t] (subs t 0 (.offsetByCodePoints t 0 (dec n))))]
    (if-not (over? s n)
      s
      (let [head (cut s)
            safe (break-cut-word s head)]
        (str (if (over? safe (dec n)) (cut safe) safe) "…")))))

(defn for-operator
  "Text for the operator from a source with these labels: when an
  outsider may have written any of it (the labels carry contaminated),
  its links broken (defang), every one of them. A live link in it
  travels beside the text, never in it (links-in). It is not yet visible:
  the edge that shows it (the command line, a push) applies visible,
  exactly once, since visible is not idempotent (it doubles a
  backslash)."
  [labels s]
  (if (contaminated? labels) (defang s) (str s)))

(def gutter
  "What every line of untrusted multi-line text is shown behind. Nothing
  else on a screen starts with it, so a role's text cannot pass for a
  line of the screen's own."
  "  │ ")

(defn block
  "A role's or an outsider's multi-line text as screen lines: every line,
  the first included, behind `gutter`, its links broken when an outsider
  may have written it. Not yet visible: the edge applies visible-line to
  each line. The one helper for showing a role's or an outsider's text."
  [labels s]
  (vec (map #(str gutter %) (str/split (for-operator labels s) #"\n" -1))))

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

(def ^:private pictographic-ranges
  "Unicode's Extended_Pictographic, near enough: the code points emoji
  sequences are made of. The JVM relay runs on has no table for it.
  The enclosed and squared Latin letters and the regional indicators
  are left out, as Unicode leaves them out, so a joiner between two
  of them is removed."
  [[0x00A9 0x00A9] [0x00AE 0x00AE] [0x203C 0x203C] [0x2049 0x2049] [0x2122 0x2122] [0x2139 0x2139]
   [0x2194 0x2199] [0x21A9 0x21AA] [0x231A 0x231B] [0x2328 0x2328] [0x2388 0x2388] [0x23CF 0x23CF]
   [0x23E9 0x23F3] [0x23F8 0x23FA] [0x24C2 0x24C2] [0x25AA 0x25AB] [0x25B6 0x25B6] [0x25C0 0x25C0]
   [0x25FB 0x25FE] [0x2600 0x27BF] [0x2934 0x2935] [0x2B05 0x2B07] [0x2B1B 0x2B1C] [0x2B50 0x2B50]
   [0x2B55 0x2B55] [0x3030 0x3030] [0x303D 0x303D] [0x3297 0x3297] [0x3299 0x3299]
   [0x1F000 0x1F0FF] [0x1F10D 0x1F10F] [0x1F12F 0x1F12F] [0x1F16C 0x1F171] [0x1F17E 0x1F17F]
   [0x1F18E 0x1F18E] [0x1F191 0x1F19A] [0x1F1AD 0x1F1E5] [0x1F201 0x1FAFF] [0x1FC00 0x1FFFD]])

(defn pictographic?
  "Whether a code point is an emoji, or part of one such as a skin tone,
  that a zero-width joiner or a variation selector may follow."
  [^long cp]
  (boolean (some (fn [[lo hi]] (<= lo cp hi)) pictographic-ranges)))

(defn- emoji-glue?
  "Whether `cp`, a zero-width joiner or variation selector, holds an
  emoji sequence together, so sanitize keeps it: a joiner between an
  emoji (or its selector) and the next emoji, as in a family; a selector
  right after an emoji, or after a keycap's digit before the keycap.
  `prev` is the code point kept before it, `next` the one after it in
  the input. Anywhere else they are hidden, and removed."
  [cp prev next]
  (boolean
   (case (long cp)
     0x200D (and prev next (pictographic? next) (or (pictographic? prev) (#{0xFE0F 0xFE0E} prev)))
     (0xFE0F 0xFE0E) (and prev (or (pictographic? prev)
                                   (and (#{0x23 0x2A} prev) (= 0x20E3 next))
                                   (and (<= 0x30 prev 0x39) (= 0x20E3 next))))
     false)))

(defn- strip
  "[text counts]: s without what strip-kind removes, and how many of each.
  A joiner or selector inside an emoji sequence stays (emoji-glue?)."
  [^String s]
  (let [sb (StringBuilder.)
        counts (volatile! {})
        v (vec (iterator-seq (.iterator (.codePoints s))))]
    (loop [i 0 prev nil]
      (when (< i (count v))
        (let [cp (v i)
              kind (strip-kind cp)
              kind (if (and (= :invisible kind) (emoji-glue? cp prev (get v (inc i)))) nil kind)]
          (case kind
            nil (do (.appendCodePoint sb (int cp)) (recur (inc i) cp))
            :separator (do (.append sb \newline) (vswap! counts update :separator (fnil inc 0)) (recur (inc i) 10))
            (do (vswap! counts update kind (fnil inc 0)) (recur (inc i) prev))))))
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
  private-use characters removed (a zero-width joiner or variation
  selector inside an emoji sequence stays, so a family emoji is one) (tab, newline and carriage return stay;
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
  change, empty when nothing changed. Never mentions labels. `from` names
  where the characters were removed from, \"from what you pasted.\" unless
  given, for an entry point whose reader is not the operator."
  ([report] (report-lines report "from what you pasted."))
  ([{:keys [removed normalized? mixed]} from]
   (let [n #(get removed % 0)
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
         [(str (- (count mixed) 3) " more words may not be what they look like.")]))))))

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

;; --- refs, as the operator reads them

(def ^:private ref-pattern #"[ \t]?\[\[([a-z]+):([^\[\]\n]+)\]\]")

(defn- unescape-ref [s]
  (str/replace s #"%(25|5B|5D|0A)" (fn [[_ code]] (case code "25" "%" "5B" "[" "5D" "]" "0A" " "))))

(defn refs-as-words
  "Text with every ref (relay.refs, [[kind:id]]) made words the operator
  can use: a file or a web page as its path or address in brackets, and
  any other (a memo, a fact, a journal entry, a card, something shown)
  left out, with the space before it, since its number means nothing to
  her. The refs stay on the item, for a client to open."
  [s]
  (str/replace (str s) ref-pattern
               (fn [[whole kind id]]
                 (let [lead (if (re-find #"^[ \t]" whole) " " "")]
                   (case kind
                     ("file" "web") (str lead "(" (unescape-ref id) ")")
                     "")))))

(defn strip-hidden
  "Text with every hidden code point (hidden?) taken out, newlines and
  tabs kept, and how many were: [text n]."
  [s]
  (let [s (str s)
        sb (StringBuilder.)
        n (volatile! 0)]
    (doseq [cp (iterator-seq (.iterator (.codePoints s)))]
      (if (hidden? cp) (vswap! n inc) (.appendCodePoint sb (int cp))))
    [(str sb) @n]))

(defn without-hidden
  "Text with every hidden code point taken out (strip-hidden)."
  [s]
  (first (strip-hidden s)))

(defn hidden-note
  "What is said after text that had `n` hidden characters taken out, or
  \"\" when it had none."
  [n]
  (if (pos? n) (str " (" (if (= 1 n) "a hidden character" "hidden characters") " removed)") ""))

(defn visible-plain
  "Text for a reader that is not a terminal, such as a mail app or a
  phone's notification: every hidden code point (hidden?) taken out,
  since nothing there can drive a screen, with \"(hidden characters
  removed)\" after it when any were, so nothing hides unseen. Newlines and
  tabs stay. Text to be defanged too is stripped before it is defanged
  (relay.messages/plain), never after."
  [s]
  (let [[t n] (strip-hidden s)]
    (str t (hidden-note n))))
