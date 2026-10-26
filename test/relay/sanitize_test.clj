(ns relay.sanitize-test
  "I6: sanitize is idempotent, leaves printable ASCII alone, and reports
  every change it makes."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.sanitize-gen :refer [cp-str gen-paste]]
            [relay.text :as text]))

(defn- cps [s] (iterator-seq (.iterator (.codePoints ^String s))))

(defspec sanitize-is-idempotent 300
  (prop/for-all [s gen-paste]
    (let [once (:text (text/sanitize s))]
      (= once (:text (text/sanitize once))))))

(defspec the-output-has-nothing-hidden 300
  (prop/for-all [s gen-paste]
    (let [out (:text (text/sanitize s))]
      (and (every? #(or (#{9 10 13} %) (not (text/hidden? %))) (cps out))
           (= out (java.text.Normalizer/normalize out java.text.Normalizer$Form/NFC))))))

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
  (testing "ordinary mixes of Japanese and Korean writing are not flagged"
    (is (empty? (:notes (text/sanitize-input "日本語のテスト ひらがなカタカナ 漢字한글"))))))

(deftest labels-are-never-mentioned
  (let [notes (:notes (text/sanitize-input (str "pаypal \u200b\u202e" (cp-str 0xe0041) "\u0001 cafe\u0301")))]
    (is (seq notes))
    (is (not-any? #(re-find #"(?i)label|contaminated|private|trust" %) notes))))

(deftest emoji-the-jvm-does-not-know-yet-are-kept
  (is (= "🫩" (:text (text/sanitize "🫩")))))

