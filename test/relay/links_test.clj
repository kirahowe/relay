(ns relay.links-test
  "Live links (relay#94, relay#118). Text an outsider may have shaped is
  inert: every link in it is broken, whatever is live. The links relay
  can say came from somewhere it takes links from travel beside the
  text, as data, and each channel carries their addresses by a mechanism
  of its own."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cards :as cards]
            [relay.channels :as channels]
            [relay.cli :as cli]
            [relay.core :as core]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.push :as push]
            [relay.ready :as ready]
            [relay.render :as render]
            [relay.shown :as shown]
            [relay.test-support :as support]
            [relay.text :as text]
            [relay.workspace :as workspace]))

(def fund "https://grants.example.org/fund?year=2027")
(def apply-page "http://arts.example.net/apply")
(def live #{fund apply-page})
(def outside ["contaminated"])
(def broken-fund "hxxps://grants[.]example[.]org/fund?year=2027")

;; --- shown text is inert

(deftest outside-text-holds-no-link-that-can-be-followed-whatever-is-live
  (is (= (str "See " broken-fund " and hxxps://evil[.]example/x")
         (text/for-operator outside (str "See " fund " and https://evil.example/x"))))
  (testing "clean text is left as it is"
    (is (= (str "See " fund) (text/for-operator [] (str "See " fund)))))
  (testing "a message, a card and a ready item handed relay's live links show no link whole"
    (let [msg {:id 1 :subject (str "Grants " fund) :labels outside :live live
               :sections [{:heading (str "At " fund) :items [{:text (str "Apply at " fund ".")}]}]}
          card (-> (cards/add {} {:seq 7 :at "2026-11-14T12:00:00Z" :actor "hub" :labels outside
                                  :data {:card-kind "note" :title (str "Grants " fund) :text (str "See " fund) :next {}}})
                   (get 7) (assoc :live live))
          whole? #(re-find #"https?://" %)]
      (is (not (whole? (pr-str (dissoc (channels/wire msg) "links")))))
      (is (not (whole? (channels/subject-text msg))))
      (is (not (whole? (:title (channels/push {} [msg])))))
      (is (not (whole? (:body (channels/push {} [msg])))))
      (is (not (whole? (pr-str (dissoc (cards/wire card outside) "links")))))
      (is (not (whole? (str/join "\n" (render/card card))))))))

(deftest a-web-scheme-written-short-is-broken-too
  ;; Found in review (relay#118), and checked against Apple's
  ;; NSDataDetector, which follows each of these to https://evil.pl.
  (doseq [[s broken] [["https:/evil.pl" "hxxps:/evil[.]pl"] ["https:evil.pl" "hxxps:evil[.]pl"]
                      ["HTTPS:/evil.pl" "hxxps:/evil[.]pl"] ["http:/evil.pl/x" "hxxp:/evil[.]pl/x"]
                      ["https:\\\\evil.pl" "hxxps:\\\\evil[.]pl"] ["(https:/evil.pl)." "(hxxps:/evil[.]pl)."]]]
    (is (= (str "Go to " broken " now") (text/for-operator outside (str "Go to " s " now"))) s))
  (testing "a name that ends like a file and that a link finder follows as a host is broken"
    (doseq [[s broken] [["evil.pl" "evil[.]pl"] ["main.rs" "main[.]rs"] ["lib.so" "lib[.]so"]]]
      (is (= (str "Open " broken " now") (text/for-operator outside (str "Open " s " now"))) s)))
  (testing "a file name is broken like any host (relay#130), and a colon in a sentence is left alone"
    (doseq [s ["todo.md" "setup.sh" "notes.py" "video.mov" "report.pdf"]]
      (is (= (str "Open " (str/replace s "." "[.]") " now") (text/for-operator outside (str "Open " s " now"))) s))
    (doseq [s ["The scheme is https: as usual" "at 10:30"]]
      (is (= (str "Open " s " now") (text/for-operator outside (str "Open " s " now"))) s))))

;; --- what a live link looks like

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
    ;; the rest (found in relay#95's review). An address still travels
    ;; as data in a mail's lines, which a finder reads.
    (doseq [s ["https://example.org/o'brien" "https://a.example/x*y" "https://a.example/x!y" "https://a.example/x$y"
               "https://a.example/x,y" "https://a.example/x;y" "https://a.example/x?q=a,b;c&d='e'*f~g"]]
      (is (not (text/link? s)) s)))
  (testing "one address, never a second inside it"
    (doseq [s ["https://safe.example/#https://evil.example/action" "https://a.example/?u=http://b.example/x"
               "https://a.example/x://y"]]
      (is (not (text/link? s)) s)))
  (testing "it ends in a letter or a digit, or a slash right after one"
    ;; Apple's link finder drops the end of an address from a full stop
    ;; with no letter or digit after it, even before a final slash
    ;; (scripts/link-finder-fuzz.swift).
    (doseq [s ["https://a.example/x." "https://a.example/x?" "https://a.example/x?q=" "https://a.example/x_"
               "https://a.example/x-" "https://a.example/x~" "https://a.example/x:" "https://a.example/x#"
               "https://a.example/x.=" "https://a.example/a./" "https://a.example/a._/" "https://a.example/a//"
               "https://a.example/?"]]
      (is (not (text/link? s)) s)))
  (testing "no bracket"
    (doseq [s ["https://en.example.org/wiki/Fund_(arts)" "https://a.example/a(b" "https://a.example/a)b"
               "https://x.example/?q=[[1,2]]" "https://a.example/x[1]" "https://a.example/a]"]]
      (is (not (text/link? s)) s))))

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

(deftest a-link-on-a-screen-mends-to-its-address
  (is (= fund (text/mend (text/defang fund))))
  (is (= fund (text/mend broken-fund)))
  (is (= fund (text/mend (str "  " fund "\n"))))
  (is (= apply-page (text/mend "hxxp://arts[.]example[.]net/apply")))
  (is (= "not a link" (text/mend "not a link")))
  (testing "between angle brackets, as a role may write one"
    (is (= fund (text/mend (str "<" fund ">"))))
    (is (= fund (text/mend (str "<" broken-fund ">"))))))

;; --- the live links a role's text holds

(deftest relay-finds-the-live-links-a-text-holds
  (is (= [fund apply-page]
         (text/links-in live (str "Apply at " fund ". Or " apply-page ", or https://evil.example/x, or " fund " again."))))
  (is (= [] (text/links-in live "Nothing to follow here.")))
  (is (= [] (text/links-in #{} (str "See " fund))))
  (is (= [] (text/links-in nil (str "See " fund))))
  (testing "however a role writes one: in brackets, in Markdown, before a mark"
    (doseq [[before after] [["(" ")."] ["[the fund](" ")"] ["\"" "\"!"] ["" ";"] ["**" "**"] ["_" "_"] ["" "?"]
                            ["" ":"] ["" "'"] ["[" "]"] ["**[the fund](" ")**."] ["<" ">"] ["<" ">,"] ["x=" ""]
                            ["evil.example/SECRET/" ""]]]
      (is (= [fund] (text/links-in live (str "See " before fund after " now"))) (str before "…" after))))
  (testing "text run on after an address makes a different address, which is not live"
    (doseq [s [(str fund "&d=SECRET") (str fund ".SECRET") (str fund "/SECRET") (str fund "SECRET") (str fund ".......")
               (str fund ")" fund) (str fund "\u200bSECRET") (str fund "\u00a0SECRET") (str fund "@evil.example")
               (str "x" fund) (str "foo." fund) (str/replace fund "https" "HTTPS") (str/replace fund "grants" "grant")]]
      (is (= [] (text/links-in live s)) s)))
  (testing "a set cannot make a misshapen address a link"
    (is (= [] (text/links-in #{"https://user@a.example/"} "https://user@a.example/")))))

(deftest live-links-travel-beside-the-text-with-the-form-it-shows
  (is (= {"links" [{"address" fund "shown" broken-fund}
                   {"address" apply-page "shown" "hxxp://arts[.]example[.]net/apply"}]
          "linksLeftOut" 0}
         (text/listed [fund apply-page fund])))
  (is (= {"links" [] "linksLeftOut" 0} (text/listed [])))
  (testing "the form shown is in the text relay shows, character for character"
    (let [s (str "Apply at " fund ". Also (" apply-page ").")
          shown (text/visible (text/for-operator outside s))]
      (doseq [{:strs [shown-form]} (map #(hash-map "shown-form" (get % "shown")) (get (text/listed (text/links-in live s)) "links"))]
        (is (str/includes? shown shown-form)))))
  (testing "more than the most listed are counted, not dropped unseen"
    (let [many (for [i (range (+ 7 text/max-listed))] (str "https://n" i ".example.org/a"))
          l (text/listed many)]
      (is (= text/max-listed (count (get l "links"))))
      (is (= 7 (get l "linksLeftOut"))))))

;; --- a terminal: the broken form becomes a hyperlink

(defn- osc8 [address shown] (str "\u001b]8;;" address "\u001b\\" shown "\u001b]8;;\u001b\\"))

(defn- targets
  "The addresses a line's terminal hyperlinks point at."
  [line]
  (map second (re-seq #"\u001b\]8;;([^\u001b]+)\u001b\\" line)))

(defn- without-hyperlinks [line]
  (str/replace line #"\u001b\]8;;[^\u001b]*\u001b\\" ""))

(deftest a-terminal-is-told-the-address-and-reads-none-from-text
  (is (= (str "Apply at " (osc8 fund broken-fund) ". Also hxxps://evil[.]example/x")
         (text/anchored live (str "Apply at " broken-fund ". Also hxxps://evil[.]example/x"))))
  (testing "where a word holds it with nothing after it but the marks that follow a link"
    (doseq [[before after] [["(" ")."] ["[the fund](" ")"] ["**" "**"] ["" "?"] ["" ":"] ["<" ">,"] ["x=" ""]
                            ["evil[.]example/SECRET/" ""] ["" ")**."]]]
      (is (= (str before (osc8 fund broken-fund) after) (text/anchored live (str before broken-fund after)))
          (str before "…" after))))
  (testing "not inside a longer address, and not one that is not live"
    (doseq [s [(str broken-fund "/x") (str broken-fund "[.]html") (str broken-fund "&d=SECRET") (str broken-fund "SECRET")
               (str broken-fund ".......") "hxxps://evil[.]example/x" (str/replace broken-fund "grants" "grant")
               "hxxps://grants.example.org/fund?year=2027"]]
      (is (= s (text/anchored live s)) s)))
  (testing "a link shown whole in clean text is left to be read as it was written"
    (is (= (str "See " fund) (text/anchored live (str "See " fund)))))
  (testing "nothing live, nothing done"
    (is (= broken-fund (text/anchored #{} broken-fund)))
    (is (= broken-fund (text/anchored nil broken-fund)))))

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
    (doseq [written ["**[the trust](https://harbour.example.net/apply)**." "Is it https://harbour.example.net/apply?"
                     "_https://harbour.example.net/apply_" "trust:https://harbour.example.net/apply:"
                     "<https://harbour.example.net/apply>"]]
      (is (= ["https://harbour.example.net/apply"] (shown/links page written)) written))))

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

;; --- each channel carries the address its own way

(def message
  {:id 7 :subject "Grants" :labels outside :live live
   :sections [{:items [{:text (str "The fund is at " fund ". An arts page: (" apply-page "), and https://evil.example/x.")}
                       {:text "Nothing to follow here."}]}]})

(deftest a-client-is-sent-the-links-beside-the-text
  (let [w (channels/wire message)]
    (is (= [{"address" fund "shown" broken-fund} {"address" apply-page "shown" "hxxp://arts[.]example[.]net/apply"}]
           (get w "links")))
    (is (= 0 (get w "linksLeftOut")))
    (is (not (re-find #"https?://" (pr-str (dissoc w "links")))) "and the text holds none whole")
    (testing "each shown form is in the item's text"
      (let [t (get-in w ["sections" 0 "items" 0 "text"])]
        (is (every? #(str/includes? t (get % "shown")) (get w "links")))))
    (testing "a message with no live links, or shown without them, has an empty list"
      (is (= [] (get (channels/wire (dissoc message :live)) "links")))
      (is (= [] (get (channels/wire (assoc message :live #{})) "links")))))
  (testing "clean text lists none: it is shown as written"
    (is (= [] (get (channels/wire (assoc message :labels [])) "links")))))

(deftest a-mail-lists-an-items-links-after-it-one-address-to-a-line
  (let [body (channels/body-text message)
        lines (str/split-lines body)]
    (is (some #{fund} lines))
    (is (some #{apply-page} lines))
    (is (= 2 (count (filter #(re-find #"https?://" %) lines))) "each address once, alone on its line")
    (is (str/includes? body broken-fund) "and the sentence shows it broken")
    (is (not-any? #(and (re-find #"https?://" %) (not (live %))) lines))
    (testing "in a listed section the address sits under its item"
      (let [body (channels/body-text (assoc-in message [:sections 0 :list] true))]
        (is (str/includes? body (str "\n  " fund "\n")))))
    (testing "a subject's and a heading's links follow the body"
      (let [m (assoc message :subject (str "Grants: " fund)
                     :sections [{:heading (str "At " apply-page) :items [{:text "No link here."}]}])
            lines (str/split-lines (channels/body-text m))]
        (is (= [fund apply-page] (filter live lines)))
        (is (not (re-find #"https?://" (channels/subject-text m))))))
    (testing "without relay's live links a mail lists none"
      (is (not (re-find #"https?://" (channels/body-text (dissoc message :live))))))))

(deftest a-push-carries-up-to-three-links-as-actions
  (let [p (channels/push {} [message])]
    (is (= [fund apply-page] (:links p)))
    (is (not (re-find #"https?://" (str (:title p) (:body p)))) "the title and body are inert")
    (is (= [{:action "view" :url fund :label "Open grants.example.org"}
            {:action "view" :url apply-page :label "Open arts.example.net"}]
           (push/link-actions (:links p)))))
  (testing "more than three: the body says how many are left"
    (let [more (for [i (range 5)] (str "https://n" i ".example.org/a"))
          m {:id 8 :subject "Many" :labels outside :live (set more) :sections [{:items [{:text (str/join " " more)}]}]}
          p (channels/push {} [m])]
      (is (= (vec (take 3 more)) (:links p)))
      (is (str/ends-with? (:body p) "(2 more links in relay)"))))
  (testing "the same host twice is told apart"
    (is (= ["Open a.example" "Open a.example (2)"]
           (map :label (push/link-actions ["https://a.example/x" "https://a.example/y"])))))
  (testing "a push with no live links has no actions"
    (is (nil? (:links (channels/push {} [(dissoc message :live)]))))))

(deftest a-card-carries-its-links-beside-its-text
  (let [state (-> {} (cards/add {:seq 9 :at "2026-11-14T12:00:00Z" :actor "hub" :labels outside
                                 :data {:card-kind "checklist" :title "Grants"
                                        :items [{:id 1 :text (str "Read " fund)} {:id 2 :text "Call the office"}]
                                        :next {:item 3}}}))
        card (assoc (get state 9) :live live)
        w (cards/wire card outside)]
    (is (= [{"address" fund "shown" broken-fund}] (get w "links")))
    (is (= (str "Read " broken-fund) (get-in w ["items" 0 "text"])))
    (is (not (re-find #"https?://" (get w "text"))) "the text form is inert too")
    (is (= [] (get (cards/wire (get state 9) outside) "links")) "and without the live links, none")))

;; --- cutting and wrapping

(deftest a-cut-never-leaves-the-start-of-a-link-that-can-be-followed
  ;; Clean text keeps its links as written, so a cut could leave the
  ;; start of one, which is a different address.
  (let [s (str "See " fund " now")]
    (doseq [n (range 6 (+ 4 (count fund)))]
      (let [out (text/clip-shown s n)]
        (is (<= (count out) n) n)
        (is (not (re-find #"https?://" out)) (str n ": " out))))
    (is (= (str "See " fund "…") (text/clip-shown s (+ 5 (count fund)))))
    (is (= s (text/clip-shown s (count s))))
    (testing "the same at the push edge, which cuts by its own rule"
      (doseq [n (range 6 (+ 4 (count fund)))]
        (is (not (re-find #"https?://" (push/clip s n))) n)))))

(deftest wrapping-never-cuts-a-word-that-holds-a-link
  ;; relay ready wraps at 60 columns and used to cut any longer word. A
  ;; whole link cut in two leaves a different address to follow, and a
  ;; broken one has to stay one word to become a hyperlink on a terminal.
  (doseq [long-link [(str "https://grants.example.org/" (apply str (repeat 70 "a")) "/apply")
                     (str "hxxps://grants[.]example[.]org/" (apply str (repeat 70 "a")) "/apply")]]
    (let [lines (render/wrap (str "Apply at " long-link " before June. " (apply str (repeat 90 "x"))) 60)]
      (is (some #{long-link} lines) "the link is one word, on a line of its own")
      (is (every? #(or (= long-link %) (<= (count %) 60)) lines))))
  (testing "a long name with a host inside it is broken before it is wrapped"
    ;; Found in review (relay#118): the name was left as a file, and the
    ;; wrap cut it after the host, which a link finder then followed.
    (let [name (str (apply str (repeat 38 "a")) ".secret.attacker.com.txt")
          lines (render/wrap (text/for-operator outside name) 60)]
      (is (not-any? #(re-find #"[a-z]\.[a-z]" %) lines) (pr-str lines))
      (is (= (str/replace name "." "[.]") (apply str lines)))))
  (testing "a long file name is not cut into a piece that reads as a host"
    ;; Found in the third round: ...-v02.ini, cut after 58 columns, left
    ;; ...-v02.in on a line, which Apple's link finder follows.
    (let [name "Quarterly-board-pack-for-the-trustees-meeting-final-v02.ini"
          shown (text/for-operator outside (str "Open " name " first"))
          lines (render/wrap shown 58)]
      (is (str/includes? shown (str/replace name "." "[.]")) "its dot is broken like any host's")
      (is (not-any? #(re-find #"[A-Za-z0-9]\.[A-Za-z]" %) lines) (pr-str lines))
      (is (some #(str/includes? % "v02[.]") lines) "its dot is broken where it was cut")
      (testing "a name that fits is not touched"
        (is (= ["Open notes.ini first"] (render/wrap "Open notes.ini first" 58))))))
  (testing "the list of what is ready cuts an item short without leaving the start of a link"
    (let [long-link (str "https://grants.example.org/" (apply str (repeat 70 "a")) "/apply")
          item {"id" "message:1" "kind" "message" "at" "2026-11-14T12:00:00Z" "seq" 1 "title" "Grants"
                ;; The list shows four lines and marks the last as cut short.
                "lines" ["one" "two" "three" long-link "five"]
                "from" nil "outsider" false "messageKind" "note" "urgency" "normal"}
          out (render/ready [item] (java.time.ZoneId/of "UTC") (java.time.Instant/parse "2026-11-14T12:30:00Z"))]
      (is (some #(str/ends-with? % " …") out) "an item was cut short")
      (is (every? #(or (str/includes? % long-link) (not (re-find #"https?://" %))) out) (pr-str out)))))

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

(defspec outside-text-is-broken-whatever-is-live 500
  ;; The invariant relay#118 restores: what is live changes nothing in
  ;; the text. No scheme a reader could follow is left in it.
  (prop/for-all [links (gen/vector gen-link 1 4)
                 s gen-text]
    (let [text (str s " " (str/join " " links) " " s)
          out (text/for-operator outside text)]
      (and (= (text/defang text) out)
           (not (re-find #"(?i)(?<![a-z0-9+.\-])https?://" out))))))

(defspec a-listed-link-is-live-and-its-shown-form-is-in-the-text 500
  (prop/for-all [links (gen/vector gen-link 1 4)
                 s gen-text]
    (let [live (set (take 2 links))
          text (str s " " (str/join " " links) " " s)
          found (text/links-in live text)
          shown (text/for-operator outside text)]
      (and (every? live found)
           ;; the ones written on their own are found
           (every? (set found) live)
           (every? #(str/includes? shown (text/defang %)) found)))))

(defspec a-terminal-follows-only-live-links-and-the-text-is-unchanged 500
  (prop/for-all [links (gen/vector gen-link 1 4)
                 s gen-text]
    (let [live (set (take 2 links))
          text (str s " " (str/join " " links) " " s)
          lines (str/split-lines (text/visible (text/for-operator outside text)))
          out (map #(text/anchored live %) lines)]
      (and (= lines (map without-hyperlinks out))
           (every? live (mapcat targets out))
           ;; each live link written on its own became a hyperlink
           (every? (set (mapcat targets out)) live)))))

(defspec cutting-shown-text-leaves-no-link-to-follow 500
  (prop/for-all [links (gen/vector gen-link 1 4)
                 s gen-text
                 n (gen/choose 2 200)]
    (let [text (str s " " (str/join " " links) " " s)
          out (text/clip-shown (text/for-operator outside text) n)]
      (and (<= (.codePointCount ^String out 0 (count out)) n)
           (not (re-find #"(?i)(?<![a-z0-9+.\-])https?://" out))))))

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
            live-now #(core/live-links (inbound/current-state (inbound/system data)))]
        (testing "typed out, and it says what it kept, in the broken form"
          (let [r (run "link" fund)]
            (is (= 0 (:exit r)))
            (is (str/includes? (out r) (str "Kept " broken-fund)))
            (is (not (re-find #"https?://" (out r))))
            (is (= #{fund} (live-now)))))
        (testing "as relay shows a broken one, with the full stop that ended its sentence"
          (is (str/includes? (out (run "link" "hxxp://arts[.]example[.]net/apply.")) "Kept hxxp://arts[.]example[.]net/apply"))
          (is (= #{fund apply-page} (live-now))))
        (testing "twice changes nothing"
          (is (str/includes? (out (run "link" fund)) "already live"))
          (is (= 2 (count (filter #(= "link/kept" (:type %)) (log/read-events data))))))
        (testing "relay links lists them, broken, as all text relay prints is"
          (let [o (out (run "links"))]
            (is (str/includes? o broken-fund))
            (is (str/includes? o "hxxp://arts[.]example[.]net/apply"))
            (is (not (re-find #"https?://" o)))))
        (testing "on a terminal each live one is a hyperlink to its address, and a dropped one is not"
          (run "link" "drop" apply-page)
          (let [r (run "links")
                lines (cli/terminal-lines (:out r) (:live (meta r)))]
            (is (= [fund] (mapcat targets lines)))
            (is (= (vec (:out r)) (mapv without-hyperlinks lines)) "the text is the same")
            (is (not (re-find #"(?<!;)https?://" (str/join "\n" (map without-hyperlinks lines)))))))
        (testing "piped, the command itself writes the same text and no escape sequence"
          (let [{:keys [exit out]} (p/shell {:out :string :err :string :in "" :continue true}
                                            support/relay-bin "links" "--data" data)]
            (is (= 0 exit))
            (is (str/includes? out broken-fund))
            (is (not (str/includes? out "\u001b")))
            (is (not (re-find #"https?://" out)))))
        (testing "dropped, it is no longer live, and relay links says so"
          (is (str/includes? (out (run "link" "drop" broken-fund)) (str "Dropped " broken-fund)))
          (is (= #{} (live-now)))
          (is (str/includes? (out (run "links")) "Dropped links"))
          (is (str/includes? (out (run "link" "drop" fund)) "already dropped")))
        (testing "and kept again, it is live again"
          (run "link" fund)
          (is (= #{fund} (live-now))))
        (testing "what is not one plain http or https address is refused, and nothing is kept"
          (doseq [bad ["ftp://a.example/x" "https://user@a.example/" "https://a.ex\u0430mple/"
                       "https://a.example/x https://b.example/y" "a.example/x" "javascript:alert(1)" "not a link"
                       "see https://a.example/x" "https://example.org/o'brien" "https://a.example/x?q="]]
            (is (thrown-with-msg? Exception #"one http or https address" (run "link" bad)) bad))
          (is (= #{fund} (live-now))))
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
  ;; A link change moves no rev, so what to send again is decided by
  ;; comparing wire forms: the text is the same, and its links differ.
  (with-data
    (fn [data]
      (let [sys (inbound/system data)
            state #(inbound/current-state sys)
            other "https://elsewhere.example.org/"
            link! (fn [type url] (inbound/submit! sys {:type type :actor "operator" :args {:url url}}))
            listed (fn [thing] (map #(get % "address") (get thing "links")))]
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
              held (ready/by-id (workspace/ready-items s0 {}))]
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
            (testing "kept: the card and the message that hold it are sent again, with the link, and no other"
              (let [cards (get (workspace/cards-changed s1 board) "cards")
                    ms (get (workspace/messages-changed s1 msgs) "messages")
                    items (get (workspace/ready-changed s1 held 60) "items")]
                (is (= 1 (count cards)))
                (is (= [fund] (listed (first cards))))
                (is (= (str "See " broken-fund) (get-in (first cards) ["note" "text"])) "its text is as it was")
                (is (= 1 (count ms)))
                (is (= [fund] (listed (first ms))))
                (is (some #(= [fund] (listed %)) items))))
            (let [board (workspace/subscribed board s1)
                  msgs (workspace/messages-sent s1)
                  held (ready/by-id (workspace/ready-items s1 {}))]
              (is (nil? (workspace/cards-changed s1 board)) "and then the client is up to date")
              (link! :link/drop fund)
              (let [s2 (state)]
                (testing "dropped: they are sent again, without it"
                  (is (= [[]] (map listed (get (workspace/cards-changed s2 board) "cards"))))
                  (is (= [[]] (map listed (get (workspace/messages-changed s2 msgs) "messages"))))
                  (is (seq (get (workspace/ready-changed s2 held 60) "items")))
                  (is (not (str/includes? (pr-str (workspace/ready-changed s2 held 60)) fund))))))))
        (testing "a link written as a ref is listed too, though its text holds another spelling"
          ;; Found in review: a scan of the raw text for the link missed
          ;; [[web:...]], which relay shows unescaped.
          (inbound/submit! sys {:type :memo/send :actor "hub" :labels ["contaminated"]
                                :args {:to "operator" :subject "A page" :body "Source [[web:https://safe.example/a%2525b]]"}})
          (link! :link/keep "https://safe.example/a%25b")
          (let [s3 (state)
                msgs (workspace/messages-sent s3)
                holds? (fn [params] (some #(= ["https://safe.example/a%25b"] (listed %)) (get params "messages")))]
            (is (holds? (workspace/messages-answer s3 {})) "it is listed when sent")
            (link! :link/drop "https://safe.example/a%25b")
            (let [again (workspace/messages-changed (state) msgs)]
              (is (= 1 (count (get again "messages"))) "the message is sent again")
              (is (not (holds? again)) "and without it"))))))))
