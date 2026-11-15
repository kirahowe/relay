(ns relay.links-test
  "Live links (relay#94): a link in text an outsider may have shaped is
  broken unless relay can say where it came from. These are the pure
  parts: what a live link looks like, how text keeps one whole, and which
  of a scout's links the web showed it."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.channels :as channels]
            [relay.cli :as cli]
            [relay.core :as core]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.push :as push]
            [relay.ready :as ready]
            [relay.render :as render]
            [relay.shown :as shown]
            [relay.text :as text]
            [relay.workspace :as workspace]))

(def fund "https://grants.example.org/fund?year=2027")
(def apply-page "http://arts.example.net/apply")
(def live #{fund apply-page})
(def outside ["contaminated"])

(def <fund> (str "<" fund ">"))

(deftest a-live-link-stays-whole-in-outside-text
  (is (= (str "See " <fund> " and hxxps://evil[.]example/x")
         (text/for-operator live outside (str "See " fund " and https://evil.example/x"))))
  (testing "with no live links every link is broken, as before"
    (is (= "See hxxps://grants[.]example[.]org/fund?year=2027"
           (text/for-operator outside (str "See " fund))
           (text/for-operator nil outside (str "See " fund))
           (text/for-operator #{} outside (str "See " fund)))))
  (testing "clean text is left as it is"
    (is (= "See https://evil.example/x" (text/for-operator live [] "See https://evil.example/x")))
    (is (= (str "See " fund) (text/for-operator live [] (str "See " fund))))))

(deftest a-live-link-is-shown-between-angle-brackets
  ;; Whatever reads the text follows what is between the brackets, and
  ;; nothing a role wrote around it. Checked against Apple's
  ;; NSDataDetector on 2026-11-14 for every mark below, before and after.
  (doseq [[before after] [["(" ")."] ["[the fund](" ")"] ["\"" "\"!"] ["" ";"] ["**" "**"] ["_" "_"] ["" "?"]
                          ["" ":"] ["" "'"] ["[" "]"] ["**[the fund](" ")**"] ["Row:" ","] ["x=" ""] ["" "~"]]]
    (is (= (str before <fund> after) (text/for-operator live outside (str before fund after)))
        (str before "…" after)))
  (testing "brackets a role already wrote are not doubled"
    (is (= (str "See " <fund> ", please") (text/for-operator live outside (str "See " <fund> ", please"))))
    (is (= (str "(" <fund> ").") (text/for-operator live outside (str "(" <fund> ").")))))
  (testing "nothing a role wrote puts a second closing bracket after a live link"
    ;; Found in relay#95's review: WezTerm reads <...> to the last > in a
    ;; word, so what stood between the two would ride in the address.
    (doseq [s [(str fund ">") (str fund ">>") (str <fund> ">") (str fund ").>") (str <fund> "SECRET>") (str fund "?>")]]
      (is (= (text/defang s) (text/for-operator live outside s)) s)
      (is (not (str/includes? (text/for-operator live outside s) "https://")) s)))
  (testing "at most three marks follow a live link"
    (is (= (str <fund> ").!") (text/for-operator live outside (str fund ").!"))))
    (doseq [s [(str fund ")**.") (str fund "....") (str fund "!?!?")]]
      (is (= (text/defang s) (text/for-operator live outside s)) s)))
  (testing "the marks after a link are no part of it, and a slash is"
    (is (= ["" "https://a.example/x" "?"] (text/link-in #{"https://a.example/x"} "https://a.example/x?")))
    (is (= ["" "https://a.example/x/" ")."] (text/link-in #{"https://a.example/x/" "https://a.example/x"} "https://a.example/x/).")))
    (is (nil? (text/link-in #{"https://a.example/x"} "https://a.example/x/")))))

(deftest text-run-on-after-a-live-link-breaks-it
  (doseq [s [(str fund "&d=SECRET")
             (str fund ".SECRET")
             (str fund "/SECRET")
             (str fund "SECRET")
             (str fund ".......")
             (str fund ")" fund)
             (str fund "\u200bSECRET")
             (str fund "\u00a0SECRET")
             (str fund "@evil.example")
             (str "x" fund)
             (str "foo." fund)
             (str/replace fund "https" "HTTPS")
             (str/replace fund "grants" "grant")]]
    (is (not (str/includes? (text/for-operator live outside s) "https://")) s)
    (is (= (text/defang s) (text/for-operator live outside s)) s)))

(deftest what-comes-before-a-live-link-in-its-word-is-still-broken
  (is (= (str "evil[.]example/SECRET/" <fund>)
         (text/for-operator live outside (str "evil.example/SECRET/" fund))))
  (is (= (str "u=" <fund>) (text/for-operator live outside (str "u=" fund))))
  (testing "a name that would pass for a file on its own is a host before a link"
    (doseq [[before broken] [["notes.md#" "notes[.]md#"] ["todo.sh?" "todo[.]sh?"] ["10.0.0.1/" "10[.]0[.]0[.]1/"]
                             ["www.evil.example/" "www[.]evil[.]example/"] ["evil。example/" "evil[.]example/"]]]
      (is (= (str broken <fund>) (text/for-operator live outside (str before fund))) before))))

(deftest a-live-link-has-one-plain-shape
  (doseq [s [fund apply-page "https://a.example" "https://a.example/" "https://a.example:8443/x#top" "http://localhost/"
             "https://a.example/x?q=a&b=c%2Cd#e:f+g@h~i" "https://a.example/x/" "https://a.example/a.b/c-d_e"
             "https://a.example/x%2C"]]
    (is (text/link? s) s))
  (doseq [s ["ftp://a.example/x" "HTTPS://a.example/" "https://user@a.example/" "https://a.example\\x"
             "https://a.ex\u0430mple/" "https://a.example/x y" "https://a.example/\u200b" "https://" "a.example/x"
             "javascript://a.example/%0aalert(1)" "https://a.example/\"x" "https://a.example/<x>"
             "https://a.example/a|b" "https://a.example/a`b" "https://a.example/{a}"
             (str "https://a.example/" (apply str (repeat text/max-link "x"))) nil 3]]
    (is (not (text/link? s)) (pr-str s)))
  (testing "only the marks every link finder reads through: no ' * ! $ , ;"
    ;; Ghostty's finder stops at an apostrophe, and terminals differ on
    ;; the rest (found in relay#95's review).
    (doseq [s ["https://example.org/o'brien" "https://a.example/x*y" "https://a.example/x!y" "https://a.example/x$y"
               "https://a.example/x,y" "https://a.example/x;y" "https://a.example/x?q=a,b;c&d='e'*f~g"]]
      (is (not (text/link? s)) s)
      (is (= (text/defang (str "See " s " now")) (text/for-operator #{s} outside (str "See " s " now"))) s)))
  (testing "one address, never a second inside it"
    ;; Found in relay#95's review: kitty looks back from the pointer for
    ;; a scheme, and would follow the inner address.
    (doseq [s ["https://safe.example/#https://evil.example/action" "https://a.example/?u=http://b.example/x"
               "https://a.example/x://y"]]
      (is (not (text/link? s)) s)
      (is (= (text/defang (str "See " s " now")) (text/for-operator #{s} outside (str "See " s " now"))) s)))
  (testing "it ends in a letter or a digit, or a slash right after one"
    ;; Apple's link finder drops the end of an address from a full stop
    ;; with no letter or digit after it, even between angle brackets and
    ;; even before a final slash (fuzzed against NSDataDetector).
    (doseq [s ["https://a.example/x." "https://a.example/x?" "https://a.example/x?q=" "https://a.example/x_"
               "https://a.example/x-" "https://a.example/x~" "https://a.example/x:" "https://a.example/x#"
               "https://a.example/x.=" "https://a.example/a./" "https://a.example/a._/" "https://a.example/a//"
               "https://a.example/?"]]
      (is (not (text/link? s)) s)
      (is (= (text/defang (str "See " s " now")) (text/for-operator #{s} outside (str "See " s " now"))) s)))
  (testing "no bracket: Apple's link finder balances them across what follows the address"
    (doseq [s ["https://en.example.org/wiki/Fund_(arts)" "https://a.example/a(b" "https://a.example/a)b" "https://a.example/a)"
               "https://x.example/?q=[[1,2]]" "https://a.example/x[1]" "https://a.example/a]"]]
      (is (not (text/link? s)) s)
      (is (= (text/defang (str "See " s)) (text/for-operator #{s} outside (str "See " s))) s)))
  (testing "a live set cannot make a misshapen address whole"
    (is (= "hxxps://user@a[.]example/" (text/for-operator #{"https://user@a.example/"} outside "https://user@a.example/")))))

(deftest an-address-is-cut-once-where-a-link-finder-cuts-it
  ;; address-in reads text that is not a role's: a page, or what the
  ;; operator typed.
  (doseq [[word address] [["https://a.example/x." "https://a.example/x"] ["(https://a.example/x)," "https://a.example/x"]
                          ["https://a.example/x!!!" "https://a.example/x"] ["\"https://a.example/x\";" "https://a.example/x"]
                          ["https://a.example/x/." "https://a.example/x/"] ["href=https://a.example/x" "https://a.example/x"]]]
    (is (= address (second (text/address-in word))) word))
  (testing "a mark a link finder would take into the address makes it no address relay takes"
    (doseq [word ["https://a.example/x?" "https://a.example/x:" "https://a.example/x_" "https://a.example/x**"
                  "https://a.example/x|y" "xhttps://a.example/x" "https://a.example/x(y)" "a.example/x"
                  "https://a.example/\u00e9" "https://a.example/o'brien"]]
      (is (nil? (text/address-in word)) word))))

(deftest a-broken-link-mends-to-its-address
  (is (= fund (text/mend (text/defang fund))))
  (is (= fund (text/mend (str "  " fund "\n"))))
  (is (= apply-page (text/mend "hxxp://arts[.]example[.]net/apply")))
  (is (= "not a link" (text/mend "not a link")))
  (testing "a live link as relay shows it, between angle brackets"
    (is (= fund (text/mend <fund>)))
    (is (= fund (text/mend (str "<" (text/defang fund) ">"))))
    (is (= "<<https://a.example/x>" (text/mend "<<<https://a.example/x>>")) "one pair only")))

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
           (shown/links page "https://grants.example.org/ and again https://grants.example.org/"))))
  (testing "however the scout writes it: in Markdown, in a question, in a list"
    (doseq [written ["**[the trust](https://harbour.example.net/apply)**" "Is it https://harbour.example.net/apply?"
                     "_https://harbour.example.net/apply_" "trust:https://harbour.example.net/apply:"
                     "<https://harbour.example.net/apply>"]]
      (is (= ["https://harbour.example.net/apply"] (shown/links page written)) written))
    (testing "but not behind more than three marks, or a closing bracket of the scout's own"
      (doseq [written ["**[the trust](https://harbour.example.net/apply)**." "https://harbour.example.net/apply>"]]
        (is (= [] (shown/links page written)) written)))))

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
  (testing "the start of a longer address is not an address the web showed"
    (doseq [[seen written] [["https://safe.example/a\u00e9" "https://safe.example/a"]
                            ["https://safe.example/a\u3002b" "https://safe.example/a"]
                            ["https://safe.example/wiki/Fund_(arts)" "https://safe.example/wiki/Fund_(arts"]
                            ["https://safe.example/wiki/Fund_(arts)" "https://safe.example/wiki/Fund_"]
                            ["https://safe.example/a?x=1" "https://safe.example/a"]
                            ["https://safe.example/a:8" "https://safe.example/a"]
                            ["https://safe.example/a'b" "https://safe.example/a"]
                            ["https://safe.example/a|b" "https://safe.example/a"]
                            ["https://safe.example/search?filters=art|music" "https://safe.example/search?filters=art"]
                            ["https://safe.example/a`b" "https://safe.example/a"]
                            ["https://safe.example/a\\b" "https://safe.example/a"]
                            ["https://safe.example/a^b" "https://safe.example/a"]
                            ["https://safe.example/a_" "https://safe.example/a"]
                            ["https://safe.example/a?" "https://safe.example/a"]
                            ;; Hidden characters: Apple's finder takes a soft hyphen
                            ;; and a zero-width joiner into an address (found in review).
                            ["https://safe.example/a\u00adb" "https://safe.example/a"]
                            ["https://safe.example/a\u200db" "https://safe.example/a"]
                            ["https://safe.example/a\u200bnext" "https://safe.example/a"]
                            ["https://safe.example/a\u00a0next" "https://safe.example/a"]
                            ["https://safe.example/a\u0000b" "https://safe.example/a"]
                            ["https://x.example/?q=[[1,2]]" "https://x.example/?q=[[1,2]]"]
                            ["xhttps://safe.example/a" "https://safe.example/a"]]]
      (is (= [] (shown/links (str "Read " seen " today") (str "See " written))) seen)))
  (testing "what HTML and JSON put around an address, and the marks that end a clause, end it"
    (doseq [seen ["<a href=\"https://safe.example/a\">Title</a>" "{\"url\": \"https://safe.example/a\"}"
                  "[the page](https://safe.example/a)." "https://safe.example/a next" "(https://safe.example/a),"
                  "https://safe.example/a\tnext" "https://safe.example/a...." "Read https://safe.example/a!"]]
      (is (= ["https://safe.example/a"] (shown/links seen "See https://safe.example/a.")) seen)))
  (testing "an address with a round bracket is never handed on"
    (is (= [] (shown/links "https://safe.example/wiki/Fund_(arts)" "See https://safe.example/wiki/Fund_(arts)."))))
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

;; --- cutting shown text

(deftest a-cut-never-leaves-the-start-of-a-live-link
  (let [s (str "See " <fund> " now")]
    (testing "a cut inside the link breaks what is left of it"
      (doseq [n (range 6 (+ 6 (count fund)))]
        (let [out (text/clip-shown s n)]
          (is (<= (count out) n) n)
          (is (not (re-find #"https?://" out)) (str n ": " out)))))
    (testing "a cut after it leaves it whole"
      (is (= (str "See " <fund> "…") (text/clip-shown s (+ 5 (count <fund>)))))
      (is (= s (text/clip-shown s (count s)))))
    (testing "the same at the push edge, which cuts by its own rule"
      (doseq [n (range 6 (+ 5 (count fund)))]
        (is (not (re-find #"https?://" (push/clip s n))) n))
      (is (str/includes? (push/clip s (+ 6 (count <fund>))) <fund>)))))

(deftest a-push-cut-short-holds-no-part-of-a-live-link
  ;; Found in review: the body was cut after its links were judged, so a
  ;; live link at the limit came out as a shorter address nobody took.
  (doseq [pad (range 180 245)]
    (let [msg {:id 1 :subject "Grants" :labels outside :live live
               :sections [{:items [{:text (str (apply str (repeat pad "x")) " " fund)}]}]}
          body (:body (channels/push {} [msg]))]
      (is (<= (count body) channels/body-limit) pad)
      (is (or (not (re-find #"https?://" body)) (str/includes? body <fund>)) (str pad ": " body))))
  (testing "several messages at once: their subjects run together and are cut"
    (let [msg (fn [i] {:id i :subject (str (apply str (repeat 70 "y")) " " fund) :labels outside :live live
                       :sections [{:items [{:text "x"}]}]})
          body (:body (channels/push {} (map msg (range 1 6))))]
      (is (= (count (re-seq #"https?://" body)) (count (re-seq (re-pattern (java.util.regex.Pattern/quote <fund>)) body)))
          body))))

(deftest wrapping-never-cuts-a-link-that-can-be-followed
  ;; relay ready wraps at 60 columns and used to cut any longer word,
  ;; which left the start of a long live link on a line of its own.
  (let [long-link (str "<https://grants.example.org/" (apply str (repeat 70 "a")) "/apply>")
        lines (render/wrap (str "Apply at " long-link " before June. " (apply str (repeat 90 "x"))) 60)]
    (is (some #{long-link} lines) "the link is whole, on a line of its own")
    (is (every? #(or (= long-link %) (<= (count %) 60)) lines))
    (testing "a broken link is cut like any long word"
      (is (every? #(<= (count %) 60) (render/wrap (text/defang (str "Apply at " long-link)) 60))))
    (testing "the list of what is ready cuts an item short without leaving the start of a link"
      (let [item {"id" "message:1" "kind" "message" "at" "2026-11-14T12:00:00Z" "seq" 1 "title" "Grants"
                  ;; The list shows four lines and marks the last as cut short.
                  "lines" ["one" "two" "three" long-link "five"]
                  "from" nil "outsider" true "messageKind" "note" "urgency" "normal"}
            out (render/ready [item] (java.time.ZoneId/of "UTC") (java.time.Instant/parse "2026-11-14T12:30:00Z"))]
        (is (some #(str/ends-with? % " …") out) "an item was cut short")
        (is (every? #(or (str/includes? % long-link) (not (re-find #"https?://" %))) out) (pr-str out))))))

;; --- properties

(def gen-word (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcXYZ019-_")) 1 8)))

(def gen-link
  "An address of a live link's shape: it ends in a letter or a digit, or
  a slash right after one."
  (gen/let [scheme (gen/elements ["https" "http"])
            host (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcxyz019")) 1 8))
            tld (gen/elements ["example.org" "example.net" "test"])
            path (gen/vector gen-word 0 3)
            query (gen/one-of [(gen/return "") (gen/fmap #(str "?q=" %) gen-word)])
            end (gen/elements ["a" "9" "Z" "b/" "0/"])]
    (str scheme "://" host "." tld (when (seq path) (str "/" (str/join "/" path))) query
         (if (or (seq path) (seq query)) end (when (str/ends-with? end "/") "/")))))

(def gen-piece
  "A piece of text: a link, a word, a mark, a host with no scheme, or
  something hidden."
  (gen/one-of [gen-link gen-link gen-word
               (gen/elements [")" "]" ">" "." "," ";" ":" "!" "?" "*" "_" "~" "(" "[" "<" "\"" "'" "/" "&d=" "#" "|"])
               (gen/elements ["evil.example/x" "www.evil.example" "\u200b" " " "\n" "\t"])]))

(def gen-text
  (gen/fmap (fn [parts] (apply str parts))
            (gen/vector (gen/one-of [(gen/return " ") gen-piece gen-piece gen-piece]) 0 14)))

(defn- only-live-links-are-whole?
  "Whether every http or https address a reader could follow in `out` is
  a live link between angle brackets: each scheme in it opens one."
  [live out]
  (= (count (re-seq #"(?i)https?://" out))
     (count (filter live (map second (re-seq #"<(https?://[^\s<>]*)>" out))))))

(defn- unbracket [s] (str/replace s #"[<>]" ""))

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
      (and (only-live-links-are-whole? live out)
           ;; and the ones written on their own are whole
           (every? #(str/includes? out (str "<" % ">")) live)))))

(defspec keeping-links-live-changes-nothing-else 500
  ;; Breaking the links in what relay shows gives what breaking them in
  ;; the text itself gives, the angle brackets aside: the live links are
  ;; all that differs.
  (prop/for-all [links (gen/vector gen-link 1 4)
                 s gen-text]
    (let [live (set (take 2 links))
          text (str s " " (str/join " " links) " " s (first links) s)]
      (= (unbracket (text/defang text)) (unbracket (text/defang (text/for-operator live outside text)))))))

(defspec cutting-shown-text-leaves-no-link-outside-the-live-set 500
  (prop/for-all [links (gen/vector gen-link 1 4)
                 s gen-text
                 n (gen/choose 2 200)]
    (let [live (set (take 2 links))
          text (str s " " (str/join " " links) " " s)
          out (text/clip-shown (text/for-operator live outside text) n)]
      (and (<= (.codePointCount ^String out 0 (count out)) n)
           (only-live-links-are-whole? live out)))))

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

;; --- the operator calls a link safe

(defn- with-data [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-links-test"}))
        data (str dir "/data")]
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :agentloop
                   :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}}))
    (try (f data) (finally (fs/delete-tree dir)))))

(deftest the-operator-keeps-and-drops-a-link-on-the-command-line
  (with-data
    (fn [data]
      (let [run (fn [& args] (binding [cli/*stdin* ""] (cli/execute (concat ["--data" data] args))))
            out (fn [r] (str/join "\n" (flatten [(:out r)])))
            live #(core/live-links (inbound/current-state (inbound/system data)))]
        (testing "whole, and it says what it kept"
          (let [r (run "link" fund)]
            (is (= 0 (:exit r)))
            (is (str/includes? (out r) (str "Kept " fund)))
            (is (= #{fund} (live)))))
        (testing "as relay shows a broken one, with the full stop that ended its sentence"
          (is (str/includes? (out (run "link" "hxxp://arts[.]example[.]net/apply.")) (str "Kept " apply-page)))
          (is (= #{fund apply-page} (live))))
        (testing "twice changes nothing"
          (is (str/includes? (out (run "link" fund)) "already live"))
          (is (= 2 (count (filter #(= "link/kept" (:type %)) (log/read-events data))))))
        (testing "relay links lists them"
          (let [o (out (run "links"))]
            (is (str/includes? o fund))
            (is (str/includes? o apply-page))))
        (testing "dropped, as it is shown on a screen, it is no longer live, and relay links says so"
          ;; Found by a second reader: a live link is shown between angle
          ;; brackets, and that is what the operator copies.
          (is (str/includes? (out (run "link" "drop" <fund>)) (str "Dropped " (text/defang fund))))
          (is (= #{apply-page} (live)))
          (is (str/includes? (out (run "links")) "Dropped links"))
          ;; Found in review: the list and the answer showed a dropped
          ;; link whole, which a terminal follows.
          (is (not (str/includes? (out (run "links")) fund)) "a dropped link is listed broken")
          (is (str/includes? (out (run "links")) (text/defang fund)))
          (is (str/includes? (out (run "links")) apply-page) "a live one is listed whole")
          (is (str/includes? (out (run "link" "drop" fund)) "already dropped")))
        (testing "and kept again, it is live again"
          (run "link" fund)
          (is (= #{fund apply-page} (live))))
        (testing "what is not one plain http or https address is refused, and nothing is kept"
          (doseq [bad ["ftp://a.example/x" "https://user@a.example/" "https://a.ex\u0430mple/"
                       "https://a.example/x https://b.example/y" "a.example/x" "javascript:alert(1)" "not a link"
                       "see https://a.example/x"]]
            (is (thrown-with-msg? Exception #"one http or https address" (run "link" bad)) bad))
          (is (= #{fund apply-page} (live))))
        (testing "with no address it says how"
          (is (thrown-with-msg? Exception #"usage: relay link" (run "link"))))))))

(deftest only-the-operator-keeps-or-drops-a-link
  (with-data
    (fn [data]
      (let [sys (inbound/system data)]
        (inbound/submit! sys {:type :role/add :actor "operator"
                              :args {:id "hub" :title "Hub" :instructions "Help."}})
        (doseq [type [:link/keep :link/drop]]
          (is (thrown-with-msg? Exception #"Only the operator"
                                (inbound/submit! sys {:type type :actor "hub" :args {:url fund}}))))
        (testing "an address with a hidden character in it is refused where the command is decided"
          ;; The command line takes hidden characters out of what the
          ;; operator pastes and says so, before this is asked.
          (doseq [bad ["https://a.example/\u200b" "https://a.\u200bexample/" nil 3 ""]]
            (is (thrown-with-msg? Exception #"one http or https address"
                                  (inbound/submit! sys {:type :link/keep :actor "operator" :args {:url bad}}))
                (pr-str bad))))
        (is (= #{} (core/live-links (inbound/current-state sys))))))))

;; --- a client that already holds a card or a message

(deftest a-link-kept-or-dropped-reaches-a-client-that-holds-it
  ;; Found in review: a link change moves no rev, so a subscribed client
  ;; kept showing a dropped link whole until it asked again.
  (with-data
    (fn [data]
      (let [sys (inbound/system data)
            state #(inbound/current-state sys)
            other "https://elsewhere.example.org/"
            link! (fn [type url] (inbound/submit! sys {:type type :actor "operator" :args {:url url}}))]
        (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "hub" :title "Hub" :instructions "Help."}})
        (inbound/submit! sys {:type :card/add :actor "hub" :labels ["contaminated"]
                              :args {:kind "note" :title "Grants" :text (str "See " fund)}})
        (inbound/submit! sys {:type :card/add :actor "hub" :labels ["contaminated"]
                              :args {:kind "note" :title "Other" :text "Nothing to follow here."}})
        (inbound/submit! sys {:type :memo/send :actor "hub" :labels ["contaminated"]
                              :args {:to "operator" :subject "Grants" :body (str "Apply at " fund)}})
        (let [s0 (state)
              board (workspace/subscribed {} s0)
              msgs (workspace/messages-sent s0)
              held (ready/by-id (workspace/ready-items s0 {}))
              card-texts (fn [params] (map #(get-in % ["note" "text"]) (get params "cards")))]
          (testing "with nothing changed, nothing is sent"
            (is (nil? (workspace/cards-changed s0 board)))
            (is (nil? (workspace/messages-changed s0 msgs)))
            (is (nil? (workspace/ready-changed s0 held 60))))
          (testing "a link no card or message holds changes nothing a client has"
            (link! :link/keep other)
            (is (nil? (workspace/cards-changed (state) board)))
            (is (nil? (workspace/messages-changed (state) msgs))))
          (link! :link/keep fund)
          (let [s1 (state)]
            (testing "kept: the card and the message that hold it are sent again, whole, and no other"
              (is (= [(str "See " <fund>)] (card-texts (workspace/cards-changed s1 board))))
              (is (str/includes? (pr-str (workspace/messages-changed s1 msgs)) fund))
              (is (= 1 (count (get (workspace/messages-changed s1 msgs) "messages"))))
              (is (str/includes? (pr-str (workspace/ready-changed s1 held 60)) fund)))
            (let [board (workspace/subscribed board s1)
                  msgs (workspace/messages-sent s1)
                  held (ready/by-id (workspace/ready-items s1 {}))]
              (is (nil? (workspace/cards-changed s1 board)) "and then the client is up to date")
              (link! :link/drop fund)
              (let [s2 (state)]
                (testing "dropped: they are sent again, broken"
                  (is (= ["See hxxps://grants[.]example[.]org/fund?year=2027"]
                         (card-texts (workspace/cards-changed s2 board))))
                  (is (not (str/includes? (pr-str (workspace/messages-changed s2 msgs)) fund)))
                  (is (= 1 (count (get (workspace/messages-changed s2 msgs) "messages"))))
                  (is (not (str/includes? (pr-str (workspace/ready-changed s2 held 60)) fund)))
                  (is (seq (get (workspace/ready-changed s2 held 60) "items"))))))))
        (testing "a link written as a ref reads differently too, though its text holds another spelling"
          ;; Found in review: a scan of the raw text for the link missed
          ;; [[web:...]], which relay shows unescaped.
          (inbound/submit! sys {:type :memo/send :actor "hub" :labels ["contaminated"]
                                :args {:to "operator" :subject "A page" :body "Source [[web:https://safe.example/a%2525b]]"}})
          (link! :link/keep "https://safe.example/a%25b")
          (let [s3 (state)
                msgs (workspace/messages-sent s3)
                shown? (fn [params] (str/includes? (pr-str params) "<https://safe.example/a%25b>"))]
            (is (shown? (workspace/messages-answer s3 {})) "it is whole when sent")
            (link! :link/drop "https://safe.example/a%25b")
            (let [again (workspace/messages-changed (state) msgs)]
              (is (= 1 (count (get again "messages"))) "the message is sent again")
              (is (not (shown? again)) "and broken"))))))))
