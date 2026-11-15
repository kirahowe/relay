(ns relay.links-test
  "Live links (relay#94): a link in text an outsider may have shaped is
  broken unless relay can say where it came from. These are the pure
  parts: what a live link looks like, how text keeps one whole, and which
  of a scout's links the web showed it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.shown :as shown]
            [relay.text :as text]))

(def fund "https://grants.example.org/fund?year=2027")
(def apply-page "http://arts.example.net/apply")
(def live #{fund apply-page})
(def outside ["contaminated"])

(deftest a-live-link-stays-whole-in-outside-text
  (is (= (str "See " fund " and hxxps://evil[.]example/x")
         (text/for-operator live outside (str "See " fund " and https://evil.example/x"))))
  (testing "with no live links every link is broken, as before"
    (is (= "See hxxps://grants[.]example[.]org/fund?year=2027"
           (text/for-operator outside (str "See " fund))
           (text/for-operator nil outside (str "See " fund))
           (text/for-operator #{} outside (str "See " fund)))))
  (testing "clean text is left as it is"
    (is (= "See https://evil.example/x" (text/for-operator live [] "See https://evil.example/x")))))

(deftest closing-marks-after-a-live-link-are-not-part-of-it
  (doseq [s [(str "(" fund ").")
             (str "[the fund](" fund ")")
             (str "<" fund ">,")
             (str "**" fund "**")
             (str "\"" fund "\"!")]]
    (is (= s (text/for-operator live outside s)) s))
  (testing "a bracket the address opened is part of it"
    (let [wiki "https://en.example.org/wiki/Fund_(arts)"]
      (is (= (str "(" wiki ")") (text/for-operator #{wiki} outside (str "(" wiki ")"))))
      (is (= ["" wiki ")"] (text/link-in #{wiki} (str wiki ")"))))
      (is (nil? (text/link-in #{"https://en.example.org/wiki/Fund_(arts"} (str wiki ")")))))))

(deftest text-run-on-after-a-live-link-breaks-it
  (doseq [s [(str fund "&d=SECRET")
             (str fund ".SECRET")
             (str fund "/SECRET")
             (str fund "....")
             (str fund ")" fund)
             (str fund "​SECRET")
             (str fund " SECRET")
             (str "x" fund)
             (str "foo." fund)
             (str/replace fund "https" "HTTPS")
             (str/replace fund "grants" "grant")]]
    (is (not (str/includes? (text/for-operator live outside s) "https://")) s)
    (is (= (text/defang s) (text/for-operator live outside s)) s)))

(deftest what-comes-before-a-live-link-in-its-word-is-still-broken
  (is (= (str "evil[.]example/SECRET/" fund)
         (text/for-operator live outside (str "evil.example/SECRET/" fund))))
  (is (= (str "u=" fund) (text/for-operator live outside (str "u=" fund)))))

(deftest a-live-link-has-one-plain-shape
  (doseq [s [fund apply-page "https://a.example" "https://a.example:8443/x#top" "http://localhost/"]]
    (is (text/link? s) s))
  (doseq [s ["ftp://a.example/x" "HTTPS://a.example/" "https://user@a.example/" "https://a.example\\x"
             "https://a.exаmple/" "https://a.example/x y" "https://a.example/​" "https://" "a.example/x"
             "javascript://a.example/%0aalert(1)" "https://a.example/\"x" "https://a.example/<x>"
             (str "https://a.example/" (apply str (repeat text/max-link "x"))) nil 3]]
    (is (not (text/link? s)) (pr-str s)))
  (testing "a live set cannot make a misshapen address whole"
    (is (= "hxxps://user@a[.]example/" (text/for-operator #{"https://user@a.example/"} outside "https://user@a.example/")))))

(deftest a-broken-link-mends-to-its-address
  (is (= fund (text/mend (text/defang fund))))
  (is (= fund (text/mend (str "  " fund "\n"))))
  (is (= apply-page (text/mend "hxxp://arts[.]example[.]net/apply")))
  (is (= "not a link" (text/mend "not a link"))))

;; --- which of a scout's links the web showed it

(def page
  (str "url: https://grants.example.org/\nstatus: 200\n\n"
       "Open calls. Arts fund <https://arts.example.org/fund?year=2027>, and the harbour trust"
       " (https://harbour.example.net/apply).\n"
       "Also http://localhost:8384/rest and http://192.168.1.1/admin and https://intranet/x"
       " and https://xn--80ak6aa92e.example/ and https://deep.example.org/a/b/c\n"))

(deftest a-scout-hands-on-the-links-it-was-shown
  (is (= ["https://arts.example.org/fund?year=2027" "https://harbour.example.net/apply" "https://grants.example.org/"]
         (shown/links page (str "Two funds: https://arts.example.org/fund?year=2027, and"
                                " [the harbour trust](https://harbour.example.net/apply)."
                                " Found on https://grants.example.org/ today."))))
  (testing "written twice, kept once"
    (is (= ["https://grants.example.org/"]
           (shown/links page "https://grants.example.org/ and again https://grants.example.org/")))))

(deftest a-link-the-scout-built-is-not-handed-on
  (doseq [l ["https://grants.example.org/?q=SECRET"
             "https://exfil.example.com/"
             "https://arts.example.org/fund?year=2028"
             "HTTPS://grants.example.org/"
             "ftp://grants.example.org/"
             "https://harbour.example.net/app"
             "https://deep.example.org/a/b"
             "https://deep.example.org/a"
             "ttps://grants.example.org/"]]
    (is (= [] (shown/links page (str "See " l " for more"))) l))
  (testing "nothing is shown at the start of a session"
    (is (= [] (shown/links "" "See https://grants.example.org/")))))

(deftest a-link-to-the-operators-own-network-is-not-handed-on
  (doseq [l ["http://localhost:8384/rest" "http://192.168.1.1/admin" "https://intranet/x"]]
    (is (str/includes? page l))
    (is (= [] (shown/links page (str "Open " l))) l))
  (testing "a punycode host is ASCII and public, so it is handed on as written"
    (is (= ["https://xn--80ak6aa92e.example/"] (shown/links page "Open https://xn--80ak6aa92e.example/")))))

(deftest a-memo-hands-on-a-bounded-number-of-links
  (let [all (for [i (range (+ 20 shown/max-links))] (str "https://n" i ".example.org/"))
        text (str/join " " all)]
    (is (= (vec (take shown/max-links all)) (shown/links text text)))))

;; --- properties

(def gen-word (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcXYZ019-_")) 1 8)))

(def gen-link
  (gen/let [scheme (gen/elements ["https" "http"])
            host (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcxyz019")) 1 8))
            tld (gen/elements ["example.org" "example.net" "test"])
            path (gen/vector gen-word 0 3)
            query (gen/one-of [(gen/return "") (gen/fmap #(str "?q=" %) gen-word)])]
    (str scheme "://" host "." tld (when (seq path) (str "/" (str/join "/" path))) query)))

(def gen-piece
  "A piece of text: a link, a word, a mark that closes a clause, a host
  with no scheme, or something hidden."
  (gen/one-of [gen-link gen-link gen-word
               (gen/elements [")" "]" ">" "." "," ";" ":" "!" "?" "*" "_" "(" "[" "<" "\"" "'" "/" "&d=" "#"])
               (gen/elements ["evil.example/x" "www.evil.example" "​" " " "\n" "\t"])]))

(def gen-text
  (gen/fmap (fn [parts] (apply str parts))
            (gen/vector (gen/one-of [(gen/return " ") gen-piece gen-piece gen-piece]) 0 14)))

(defn- whole-links
  "The http and https addresses a reader could follow in text as shown:
  each run from a scheme to the next space."
  [s]
  (re-seq #"https?://\S*" s))

(defn- ends-live?
  "Whether a run is a live link with at most three closing marks after."
  [live run]
  (some (fn [n]
          (let [cut (- (count run) n)]
            (and (pos? cut) (contains? live (subs run 0 cut)) (every? text/link-closers (subs run cut)))))
        (range 0 4)))

(defspec text-with-no-live-links-is-broken-as-before 300
  (prop/for-all [s gen-text
                 other (gen/set gen-link {:max-elements 3})]
    (and (= (text/defang s) (text/defang-but #{} s) (text/defang-but nil s))
         ;; Live links that the text does not hold change nothing.
         (or (some #(str/includes? s %) other)
             (= (text/defang s) (text/defang-but other s))))))

(defspec no-link-outside-the-live-set-is-ever-whole 500
  (prop/for-all [links (gen/vector gen-link 1 4)
                 s gen-text]
    (let [live (set (take 2 links))
          ;; Put the generated links into the text too, live ones and not.
          text (str s " " (str/join " " links) " " s)
          out (text/for-operator live outside text)]
      (every? #(ends-live? live %) (whole-links out)))))

(defspec a-broken-link-mends-to-the-link-it-was 300
  (prop/for-all [l gen-link]
    (and (text/link? l) (= l (text/mend (text/defang l))))))

(defspec a-scout-hands-on-only-what-it-could-have-fetched 300
  (prop/for-all [shown-links (gen/vector gen-link 0 5)
                 written (gen/vector gen-link 0 5)
                 s gen-text]
    (let [seen (str s "\n" (str/join "\n" shown-links) "\n")
          memo (str s " " (str/join " " (concat written shown-links)))
          kept (shown/links seen memo)]
      (and (every? text/link? kept)
           (every? #(nil? (shown/refusal seen {:url %})) kept)
           (every? #(str/includes? memo %) kept)))))
