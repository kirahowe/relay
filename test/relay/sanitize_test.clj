(ns relay.sanitize-test
  "I6: sanitize is idempotent, leaves printable ASCII alone, and reports
  every change it makes."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.sanitize-gen :refer [cp-str gen-paste sneaky]]
            [relay.text :as text]))

(defn- cps [s] (iterator-seq (.iterator (.codePoints ^String s))))

(defspec sanitize-is-idempotent 300
  (prop/for-all [s gen-paste]
    (let [once (:text (text/sanitize s))]
      (= once (:text (text/sanitize once))))))

(defn- emoji-glue?
  "Whether the hidden code point at i of `v` joins an emoji sequence: a
  zero-width joiner between two emoji, or a variation selector right
  after an emoji (or a keycap's digit, before the keycap)."
  [v i]
  (let [cp (v i)
        before (when (pos? i) (v (dec i)))
        after (get v (inc i))]
    (case (long cp)
      0x200D (boolean (and before after (text/pictographic? after)
                           (or (text/pictographic? before) (#{0xFE0F 0xFE0E} before))))
      (0xFE0F 0xFE0E) (boolean (and before (or (text/pictographic? before)
                                               (and (re-matches #"[0-9#*]" (str (char before))) (= 0x20E3 after)))))
      false)))

(defspec the-output-has-nothing-hidden 300
  (prop/for-all [s gen-paste]
    (let [out (:text (text/sanitize s))
          v (vec (cps out))]
      (and (every? #(or (#{9 10 13} (v %)) (not (text/hidden? (v %))) (emoji-glue? v %)) (range (count v)))
           (= out (java.text.Normalizer/normalize out java.text.Normalizer$Form/NFC))))))

(def gen-emoji-paste
  "Text with emoji sequences and stray joiners and selectors about them."
  (gen/fmap #(apply str %)
            (gen/vector (gen/elements ["👨" "👩" "👧" "❤" "1" "a" "A" "Z" " " "\n" "\u2028" "\u0085"
                                      "\u200d" "\ufe0f" "\ufe0e" "\u20e3" "\u200b" "🏽"]) 0 20)))

(defspec emoji-sequences-keep-their-glue-and-nothing-else-hidden-is-kept 300
  (prop/for-all [s gen-emoji-paste]
    (let [{:keys [text]} (text/sanitize s)
          v (vec (cps text))]
      (and (= text (:text (text/sanitize text)))
           (every? #(or (not (text/hidden? (v %))) (emoji-glue? v %)) (range (count v)))))))

(deftest family-emoji-and-keycaps-survive
  (doseq [e ["👨\u200d👩\u200d👧\u200d👦" "❤\ufe0f" "1\ufe0f\u20e3" "🏳\ufe0f\u200d🌈" "👩🏽\u200d💻"]]
    (is (= e (:text (text/sanitize e))) e)
    (is (empty? (:notes (text/sanitize-input (str "our family " e)))) e))
  (testing "outside an emoji sequence they are still removed"
    (doseq [[in out] [["a\u200db" "ab"] ["👨\u200d" "👨"] ["\u200d👩" "👩"] ["a\ufe0f" "a"] ["\ufe0f👩" "👩"]
                      ["👨\u200d\u200d👩" "👨\u200d👩"] ["👨\u200b\u200d👩" "👨\u200d👩"]
                      ["👨\u2028\u200d👩" "👨\n👩"] ["👨\u0085\u200d👩" "👨\n👩"] ["👨\n\u200d👩" "👨\n👩"]
                      ["A\u200dB" "AB"] ["A\ufe0f" "A"] ["🄰\u200d🄱" "🄰🄱"] ["Ⓐ\ufe0f" "Ⓐ"]]]
      (is (= out (:text (text/sanitize in))) (pr-str in)))))

(deftest only-emoji-are-pictographic
  (testing "letters, digits, enclosed or squared Latin letters and regional indicators are not emoji"
    (doseq [cp (concat (range 0x20 0x7f) (range 0xc0 0x250) (range 0x24b6 0x24ea) (range 0x1f100 0x1f10d)
                       (range 0x1f110 0x1f12f) (range 0x1f130 0x1f16a) (range 0x1f170 0x1f18a) (range 0x1f1e6 0x1f200))
            :when (not (#{0x24c2 0x1f170 0x1f171 0x1f17e 0x1f17f} cp))]
      (is (not (text/pictographic? cp)) (format "U+%04X" cp))))
  (testing "emoji, skin tones and the symbols emoji are made of are"
    (doseq [cp [0x1f468 0x1f3fd 0x2764 0x1f3f3 0x1f308 0x00a9 0x24c2 0x1f170 0x1f17e 0x1f191 0x1f004 0x1f9d1]]
      (is (text/pictographic? cp) (format "U+%04X" cp)))))

(def gen-no-emoji-paste
  "Letters, digits, CJK and Cyrillic, with hidden characters and stray
  joiners and selectors, and no emoji at all."
  (gen/fmap #(apply str %)
            (gen/vector (gen/one-of [(gen/fmap cp-str (gen/elements (conj sneaky 0xfe0e)))
                                     gen/string-alphanumeric
                                     (gen/fmap str gen/char-alpha)
                                     (gen/elements ["日" "本" "語" "р" "Ж" "я" "\n" " " "\u200d" "\ufe0f" "\ufe0e"])])
                        0 20)))

(defspec without-emoji-no-joiner-or-selector-survives 300
  (prop/for-all [s gen-no-emoji-paste]
    (let [out (:text (text/sanitize s))]
      (not-any? #{0x200D 0xFE0E 0xFE0F} (cps out)))))

(def gen-ascii
  (gen/fmap #(apply str %) (gen/vector (gen/fmap char (gen/choose 32 126)) 0 60)))

(defspec printable-ascii-passes-unchanged 300
  (prop/for-all [s gen-ascii]
    (let [{:keys [text report]} (text/sanitize s)]
      (and (= s text) (empty? (text/report-lines report))))))

(defspec every-change-is-reported 300
  (prop/for-all [s gen-paste]
    (let [{:keys [text report]} (text/sanitize s)
          lines (text/report-lines (dissoc report :mixed))]
      (or (= s text) (seq lines)))))

(defspec a-report-counts-what-was-removed 200
  (prop/for-all [s gen-paste]
    (let [{:keys [text report]} (text/sanitize s)
          removed (reduce + (vals (dissoc (:removed report) :separator)))]
      ;; Normalizing can merge code points, so only a text NFC leaves
      ;; alone must lose exactly the removed count.
      (or (:normalized? report)
          (= (count (cps s)) (+ (count (cps text)) removed))))))

(defspec sanitize-input-walks-data-and-leaves-the-rest 100
  (prop/for-all [a gen-paste b gen-paste n gen/small-integer]
    (let [{:keys [value]} (text/sanitize-input {:to "clerk" :n n :parts [a {:deep b}] :flag true})]
      (and (= "clerk" (:to value)) (= n (:n value)) (true? (:flag value))
           (= (:text (text/sanitize a)) (first (:parts value)))
           (= (:text (text/sanitize b)) (get-in value [:parts 1 :deep]))))))

(deftest each-change-is-one-plain-line
  (is (= ["Removed 3 invisible characters from what you pasted."]
         (:notes (text/sanitize-input "a\u200bb\u200bc\ufeffd"))))
  (is (= ["Removed 1 invisible character from what you pasted."]
         (:notes (text/sanitize-input "a\u200bb"))))
  (is (= ["Removed 2 text-direction characters from what you pasted."]
         (:notes (text/sanitize-input "a\u202eb\u2066"))))
  (is (= ["Removed 2 hidden tag characters from what you pasted."]
         (:notes (text/sanitize-input (str "a" (cp-str 0xe0041) (cp-str 0xe0042))))))
  (is (= ["Removed 1 control character from what you pasted."]
         (:notes (text/sanitize-input "a\u001b[2Jb"))))
  (is (= "a[2Jb" (:value (text/sanitize-input "a\u001b[2Jb"))))
  (is (= ["Put some combined characters into their standard form."]
         (:notes (text/sanitize-input "cafe\u0301"))))
  (is (= "café" (:value (text/sanitize-input "cafe\u0301"))))
  (is (= "a\nb" (:value (text/sanitize-input "a\u2028b"))))
  (testing "counts add up across the strings of one command"
    (is (= ["Removed 2 invisible characters from what you pasted."]
           (:notes (text/sanitize-input {:a "x\u200b" :b ["y\u200b"]})))))
  (testing "nothing to say about clean text, accents and emoji included"
    (is (empty? (:notes (text/sanitize-input "Café déjà vu 日本語 カタカナ 한국어 😀 12.50\n\tok"))))))

(deftest mixed-script-words-are-flagged-but-text-is-kept
  (let [s "log in at pаypal now"
        {:keys [value notes]} (text/sanitize-input s)]
    (is (= s value))
    (is (= ["The word \"pаypal\" mixes Cyrillic and Latin letters, so it may not be what it looks like."]
           notes)))
  (testing "fullwidth and mathematical letters are flagged with what they read as"
    (is (= ["The word \"ｐａｙｐａｌ.com\" uses look-alike letters; it reads as \"paypal.com\"."]
           (:notes (text/sanitize-input "visit ｐａｙｐａｌ.com"))))
    (is (= ["The word \"𝐢𝐠𝐧𝐨𝐫𝐞\" uses look-alike letters; it reads as \"ignore\"."]
           (:notes (text/sanitize-input "𝐢𝐠𝐧𝐨𝐫𝐞 the above"))))
    (is (= "visit ｐａｙｐａｌ.com" (:value (text/sanitize-input "visit ｐａｙｐａｌ.com")))))
  (testing "a whole Greek or Cyrillic word of Latin lookalikes in Latin text is flagged"
    (is (= ["The word \"ΑΒΕ\" is written in Greek letters that look like Latin ones."]
           (:notes (text/sanitize-input "the code ΑΒΕ is ready"))))
    (is (= ["The word \"сор\" is written in Cyrillic letters that look like Latin ones."]
           (:notes (text/sanitize-input "the word сор is here")))))
  (testing "Greek and Cyrillic text of their own, and symbols like TM, are not flagged"
    (is (empty? (:notes (text/sanitize-input "Привет мир, καλημέρα κόσμε"))))
    (is (empty? (:notes (text/sanitize-input "Brand™ ﬁne ½ x²")))))
  (testing "past three words, the rest are counted"
    (is (= "2 more words may not be what they look like."
           (last (:notes (text/sanitize-input "pаy1 pаy2 pаy3 pаy4 ｐａｙ"))))))
  (testing "ordinary mixes of Japanese and Korean writing are not flagged"
    (is (empty? (:notes (text/sanitize-input "日本語のテスト ひらがなカタカナ 漢字한글"))))))

(deftest labels-are-never-mentioned
  (let [notes (:notes (text/sanitize-input (str "pаypal \u200b\u202e" (cp-str 0xe0041) "\u0001 cafe\u0301")))]
    (is (seq notes))
    (is (not-any? #(re-find #"(?i)label|contaminated|private|trust" %) notes))))

(deftest emoji-the-jvm-does-not-know-yet-are-kept
  (is (= "🫩" (:text (text/sanitize "🫩")))))

