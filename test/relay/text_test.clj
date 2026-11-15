(ns relay.text-test
  "Text a role wrote reaches the operator's terminal and phone only in a
  form that cannot drive the terminal, hide what it says, or be followed
  with a tap."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cli :as cli]
            [relay.core :as core]
            [relay.test-support :as support]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.messages :as messages]
            [relay.render :as render]
            [relay.text :as text]))

(def nasty-chars
  "Code points that could do harm on a terminal, or hide text."
  [0 7 8 13 27 127 0x85 0x9b 0x200b 0x200e 0x202a 0x202e 0x2066 0x2069 0x2028 0x2029 0xfeff
   0xe000 0x10ffff 0x1f600 0xe9])

(def gen-text
  (gen/fmap (fn [parts] (apply str parts))
            (gen/vector (gen/one-of [(gen/fmap #(String. (Character/toChars (int %))) (gen/elements nasty-chars))
                                     gen/string-alphanumeric
                                     (gen/elements ["[2J" "]0;title" "\n" "\t" " " "é"])])
                        1 12)))

(defn- raw-hidden [s]
  (some text/hidden? (iterator-seq (.iterator (.codePoints ^String s)))))

(defn- with-data [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-text-test"}))
        data (str dir "/data")]
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :agentloop
                   :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}}))
    (try (f data) (finally (fs/delete-tree dir)))))

(defspec nothing-a-role-wrote-reaches-the-terminal-raw 25
  (prop/for-all [t gen-text]
    (with-data
      (fn [data]
        (let [sys (inbound/system data)
              submit (fn [actor type args & [labels]]
                       (try (inbound/submit! sys {:type type :actor actor :args args :labels labels})
                            (catch clojure.lang.ExceptionInfo _ nil)))
              s (str "x" t)]
          (submit "operator" :role/add {:id "clerk" :title s :instructions s})
          (submit "clerk" :memo/send {:to "operator" :subject (str/replace s "\n" " ") :body s})
          (submit "clerk" :memo/send {:to "operator" :subject "web" :body s} ["contaminated"])
          (submit "clerk" :fact/add {:text s})
          (submit "clerk" :doc/put {:path "a.md" :content s})
          (submit "clerk" :note/write {:name "n" :text s})
          (submit "clerk" :journal/write {:text s})
          (submit "clerk" :routine/write {:name "r" :schedule "every 1d" :instructions s})
          (submit "operator" :wake/start {:role "clerk" :note s})
          (let [wake (support/calling-wake (inbound/current-state sys) "clerk")]
            (submit "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :session "s" :said s
                                            :held [{:id "c1" :name "post" :input {:text s}}]}))
          (let [outs (for [args [["inbox"] ["status"] ["holds"] ["log"] ["log" "--json"] ["docs"]
                                 ["doc" "a.md"] ["doc" "a.md" "--history"] ["facts" "--all"] ["fact" "5"]
                                 ["notes" "clerk"] ["journal" "clerk"] ["roles"] ["routines"]]]
                       (try (:out (cli/execute (concat ["--data" data] args)))
                            (catch clojure.lang.ExceptionInfo e [(ex-message e)])))
                bad (filter raw-hidden (mapcat #(str/split-lines (str/join "\n" %)) outs))]
            (or (empty? bad) (do (prn bad) false))))))))

(def gen-urlish
  (gen/fmap (fn [parts] (apply str parts))
            (gen/vector (gen/elements ["http://" "HTTPS://" "hTtP://" "ftp://" "javascript://" "data://"
                                       "www." "WWW." "evil" ".com" "." "/path" "?q=1" " " "\n" "x"
                                       "‮" "​" ":" "//" "。" "．" "｡"])
                        1 15)))

(defspec text-from-outside-for-the-operator-has-no-followable-url 500
  (prop/for-all [s gen-urlish]
    (let [out (text/visible (text/for-operator ["contaminated"] s))]
      (and (not (str/includes? out "contaminated"))
           (not (re-find #"(?i)\b(https?|ftp)://" out))
           ;; Only the defanged schemes keep their ://.
           (every? #{"hxxp" "hxxps" "fxp"}
                   (map (comp str/lower-case second) (re-seq #"(?i)([a-z][a-z0-9+.\-]*)://" out)))
           (not (re-find #"(?i)\bwww\.[^\s\[]" out))
           ;; No host with a known ending keeps a dot, and no fullwidth
           ;; or ideographic full stop sits inside a word.
           (not (re-find #"(?i)(?<![@\w.\-/])(?:[a-z0-9](?:[a-z0-9\-]*[a-z0-9])?\.)+com(?![\w\-])" out))
           (not (re-find #"[^\s\[][\u3002\uFF0E\uFF61][^\s\]]" out))
           (not (raw-hidden out))))))

(deftest clean-text-is-only-made-visible
  (is (= "see https://example.com é \\u{202e}" (text/visible (text/for-operator ["private"] "see https://example.com é ‮")))))

(deftest the-operator-s-inbox-breaks-links-from-outside-and-marks-nothing
  (let [lines (render/inbox core/empty-state java.time.ZoneOffset/UTC
                            [{:id 3 :from "clerk" :subject "Click https://evil.example/x" :body "www.evil.example"
                              :sent-at (java.time.Instant/now) :labels ["contaminated" "private"]}])
        out (str/join "\n" lines)]
    (is (str/includes? out "[3] Click hxxps://evil[.]example/x"))
    (is (not (str/includes? out "[from")))
    (is (str/includes? out "www[.]evil[.]example"))))


(deftest every-operator-view-breaks-contaminated-urls
  (with-data
    (fn [data]
      (let [sys (inbound/system data)
            C ["contaminated"]
            url "see https://evil.example/x and evil。com"
            submit #(inbound/submit! sys (assoc %1 :labels C :actor %2))]
        (submit {:type :role/add :args {:id "clerk" :title url :instructions url}} "operator")
        (submit {:type :fact/add :args {:text url}} "clerk")
        (submit {:type :doc/put :args {:path "a.md" :content url}} "clerk")
        (submit {:type :note/write :args {:name "n" :text url}} "clerk")
        (submit {:type :journal/write :args {:text url}} "clerk")
        (submit {:type :routine/write :args {:name "r" :schedule "every 1d" :instructions url}} "clerk")
        (submit {:type :memo/send :args {:to "clerk" :subject url :body url}} "clerk")
        ;; relay log --json prints the stored events exactly: it is for a
        ;; program, not a page to tap on.
        (doseq [args [["status"] ["roles"] ["routines"] ["log"] ["docs"] ["doc" "a.md"]
                      ["facts" "--all"] ["notes" "clerk"] ["journal" "clerk"] ["inbox"]]
                :let [out (str/join "\n" (:out (cli/execute (concat ["--data" data] args))))]]
          (is (not (str/includes? out "https://evil")) (str/join " " args))
          (is (not (str/includes? out "evil.example")) (str/join " " args))
          (is (not (str/includes? out "evil。com")) (str/join " " args)))))))


(defspec the-json-log-always-parses-and-is-plain-ascii 25
  (prop/for-all [t gen-text]
    (with-data
      (fn [data]
        (let [sys (inbound/system data)
              s (str "x" t)]
          (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title s :instructions s}})
          (inbound/submit! sys {:type :journal/write :actor "clerk" :labels ["contaminated"] :args {:text (str s " https://evil.example")}})
          (let [lines (:out (cli/execute ["--data" data "log" "--json"]))]
            (and (every? (fn [line]
                           (or (and (map? (json/parse-string line))
                                    (every? #(< 31 % 127) (iterator-seq (.iterator (.codePoints ^String line)))))
                               false))
                         lines)
                 ;; The stored event, exactly: nothing defanged in place.
                 (= (map #(json/parse-string % true) lines) (log/read-events data)))))))))

;; --- invisible code points

(def gen-any-text
  "Text from anywhere in Unicode, heavy on what renders as nothing."
  (gen/fmap (fn [cps] (apply str (map #(String. (Character/toChars (int %))) cps)))
            (gen/vector (gen/one-of [(gen/choose 0 0x10FFFF)
                                     (gen/bind (gen/elements text/default-ignorable)
                                               (fn [[lo hi]] (gen/choose lo hi)))
                                     (gen/fmap int gen/char-alphanumeric)
                                     (gen/elements [92 (int \u) (int \{) (int \})])])
                        0 20)))

(defn- unescape
  "visible undone: each \\u{XXXX} back to its code point, and each
  doubled backslash back to one."
  [s]
  (str/replace s #"\\\\|\\u\{([0-9a-f]+)\}"
               (fn [[_ hex]] (if hex (String. (Character/toChars (Integer/parseInt hex 16))) "\\"))))

(defspec visible-shows-nothing-that-draws-as-nothing 500
  (prop/for-all [s gen-any-text]
    (let [shown (text/visible s)
          cps (iterator-seq (.iterator (.codePoints ^String shown)))]
      (and (not-any? text/default-ignorable? cps)
           (not-any? text/hidden? cps)
           ;; Nothing is lost, and no two texts are shown alike.
           (= s (unescape shown))))))

(deftest text-that-spells-an-escape-is-not-shown-as-one
  (is (not= (text/visible (str "ok" (String. (Character/toChars 0xFE0F)))) (text/visible "ok\\u{fe0f}")))
  (is (= "a\\\\b" (text/visible "a\\b"))))

(deftest variation-selectors-cannot-hide-text
  ;; Regression: bytes of a secret as variation selectors passed visible
  ;; untouched, so the operator was shown nothing of them.
  (let [hide (fn [b] (String. (Character/toChars (if (< b 16) (+ 0xFE00 b) (+ 0xE0100 (- b 16))))))
        s (str "arts grants" (apply str (map #(hide (int %)) "PIN 0912")))]
    (is (= (count (re-seq #"\\u\{" (text/visible s))) 8))
    (doseq [cp [0x034F 0x115F 0x1160 0x180B 0x180F 0x2060 0x206F 0x3164 0xFFA0 0xFE0F 0xE0100 0xE01EF]]
      (is (text/hidden? cp) (format "U+%04X" cp)))
    (is (not (text/hidden? (int \a))))
    (is (not (text/hidden? 0xE9)))))

;; --- each view escapes once

(deftest every-view-shows-an-escape-once
  ;; Regression: visible doubles a backslash, and render and the command
  ;; line both applied it, so a real U+200B read like the text \u{200b}.
  (with-data
    (fn [data]
      (spit (str data "/relay.edn")
            (pr-str {:timezone "UTC" :default-harness :agentloop
                     :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}
                     :tools {"post" {:class :edit :labels [] :description "Post." :command ["true"]}}
                     :grants {"clerk" {:tools ["post"]}}}))
      (let [sys (inbound/system data)
            s "zw​bs\\end"
            once "zw\\u{200b}bs\\\\end"
            twice "\\\\u{200b}"
            out (fn [& args] (str/join "\n" (:out (cli/execute (concat ["--data" data] args)))))]
        (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title s :instructions s}})
        (inbound/submit! sys {:type :memo/send :actor "clerk" :args {:to "operator" :subject s :body s}})
        (doseq [[what text] [["inbox" (out "inbox")] ["status" (out "status")] ["roles" (out "roles")]]]
          (is (str/includes? text once) what)
          (is (not (str/includes? text twice)) what))))))

;; --- a role's text never decides where a line starts

(def gen-forging-text
  "Text a role writes to pass for lines of an operator's screen."
  (gen/fmap #(str/join "\n" %)
            (gen/vector (gen/elements ["Memo 12: urgent" "flags: none" "[3] Lunch" "    from hub, 2026-10-15 12:00"
                                       "  | fake" "Fact 9, holds from 2026-01-01" "Hold 4: clerk wants to use post"
                                       "## plan" "Harness x: available" "  [from outside]" "" "Roles"
                                       "Labels: none" "\t- version 3" "zz"])
                        1 6)))

(defn- screens
  "Every operator view of a world whose role-written text is `t`, as the
  terminal shows it."
  [t]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-forge"}))
        data (str dir "/data")]
    (try
      (log/init! data)
      (spit (str data "/relay.edn")
            (pr-str {:timezone "UTC" :default-harness :agentloop
                     :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}
                     :tools {"post" {:class :edit :labels [] :description "Post." :command ["true"]}}
                     :grants {"clerk" {:tools ["post"]}}}))
      (let [sys (inbound/system data)
            submit (fn [actor type args & [labels]] (inbound/submit! sys {:type type :actor actor :args args :labels labels}))
            state #(inbound/current-state sys)
            out (fn [& args] (:out (cli/execute (concat ["--data" data] args))))]
        (submit "operator" :role/add {:id "clerk" :title t :instructions t})
        (submit "clerk" :memo/send {:to "operator" :subject "s" :body t})
        (submit "clerk" :memo/send {:to "clerk" :subject "web" :body t} ["contaminated"])
        (submit "clerk" :fact/add {:text t :topic "home"})
        (submit "clerk" :doc/put {:path "a.md" :content t})
        (submit "clerk" :note/write {:name "n" :text t})
        (submit "clerk" :journal/write {:text t})
        (submit "clerk" :routine/write {:name "r" :schedule "every 1d" :instructions t})
        (submit "operator" :wake/start {:role "clerk" :note "go"})
        (submit "dispatcher" :wake/end {:wake (:id (support/calling-wake (state) "clerk")) :outcome "ran" :session "s" :said t})
        (let [fact (str (first (keys (:facts (state)))))]
          (into (sorted-map)
                (for [args [["inbox"] ["status"] ["roles"] ["routines"] ["notes" "clerk"] ["journal" "clerk"]
                            ["facts" "--all"] ["fact" fact] ["docs"] ["doc" "a.md"] ["doc" "a.md" "--history"]
                            ["log"]]]
                  [(str/join " " args) (apply out args)]))))
      (finally (fs/delete-tree dir)))))

(defn- structure
  "The lines a screen's renderer wrote: every line but those behind the
  gutter, with the role's text, its times and its counts taken out."
  [lines t]
  (->> lines
       (remove #(str/starts-with? % text/gutter))
       (map #(-> % (str/replace (text/visible-line t) "<T>")
                 (str/replace #"\d+" "9")))))

(defspec a-role-s-text-never-starts-a-line-of-an-operator-s-screen 12
  (prop/for-all [t gen-forging-text]
    (let [t (str "<" t ">")
          forged (screens t)
          plain (screens "<zz>")]
      (every? (fn [[view lines]]
                (let [;; A log line is one event, its data cut to a length,
                      ;; so only where each line starts is compared.
                      cut (if (= "log" view) #(map (fn [l] (subs l 0 (min 60 (count l)))) %) identity)
                      a (cut (structure lines t)) b (cut (structure (get plain view) "<zz>"))]
                  (or (= a b) (do (prn view a b) false))))
              forged))))

(defspec defanging-twice-is-defanging-once 500
  ;; Text defanged where it was written (a name in a daily note's line)
  ;; is defanged again where it is shown; it must read the same.
  (prop/for-all [s gen-urlish]
    (= (text/defang s) (text/defang (text/defang s)))))

(deftest defang-keeps-amounts-and-breaks-addresses
  (is (= "evil[.]example" (text/defang "evil[.]example")) "a dot already broken stays as it is")
  (is (= "$12.50 for 1.5 hours" (text/defang "$12.50 for 1.5 hours")))
  (is (= "10[.]0[.]0[.]1 and evil[.]example" (text/defang "10.0.0.1 and evil.example")))
  (is (= "hxxps://pay[.]example/1[.]5" (text/defang "https://pay.example/1.5"))))

(deftest text-is-never-marked-only-its-links-broken
  (is (= "Approve it at hxxps://bank[.]com" (text/for-operator ["contaminated" "mail:security@bank.com"] "Approve it at https://bank.com")))
  (is (= "mine https://a.example" (text/for-operator ["private"] "mine https://a.example")))
  (is (= ["  │ see hxxps://a[.]example"] (text/block ["contaminated"] "see https://a.example")))
  (is (= ["  │ hi"] (text/block [] "hi"))))

(deftest defang-breaks-every-dot-a-host-name-could-have
  ;; relay#130: relay took some dotted words for file names and mail
  ;; addresses and left them whole, and three review rounds each found one
  ;; that Apple's link finder followed. Now no name is recognised: a dot
  ;; before a letter is broken, since every top-level domain begins with
  ;; one, and a dot before a digit, which ends no host, stays.
  (doseq [[s broken] [["todo.md" "todo[.]md"] ["interview.txt" "interview[.]txt"]
                      ["projects/choir.md" "projects/choir[.]md"] ["dana@riverbend.test" "dana@riverbend[.]test"]
                      ["Did you read todo.md?" "Did you read todo[.]md?"] ["notes.2026.txt" "notes.2026[.]txt"]]]
    (is (= broken (text/defang s)) s))
  (testing "times, amounts, versions and the end of a sentence stay as written"
    (doseq [s ["2026-10-26T08:06:39.118474Z" "12.50" "v1.2.3" "It ended." "wait..." "(see 10:30.)" "3.5%"]]
      (is (= s (text/defang s)) s)))
  (testing "but a name with a label inside it that could end a host is a host, whatever it ends in"
    ;; Found in review (relay#118): a link finder reads the host and stops
    ;; before the rest, and a host carries data in its labels.
    (doseq [[s broken] [["secret.attacker.com-report.txt" "secret[.]attacker[.]com-report[.]txt"]
                        ["secret.attacker.com_report.pdf" "secret[.]attacker[.]com_report[.]pdf"]
                        ["a.b.com.txt" "a[.]b[.]com[.]txt"]
                        ["jquery.min.js" "jquery[.]min[.]js"]
                        ["archive.tar.gz" "archive[.]tar[.]gz"]]]
      (is (= broken (text/defang s)) s)))
  (testing "and an @ that no mail address has before it exempts nothing"
    (doseq [[s broken] [[".@secret.attacker.com" ".@secret[.]attacker[.]com"]
                        ["@secret.attacker.com" "@secret[.]attacker[.]com"]
                        ["(@secret.attacker.com)" "(@secret[.]attacker[.]com)"]
                        ["x.@secret.attacker.com" "x[.]@secret[.]attacker[.]com"]]]
      (is (= broken (text/defang s)) s))
    (is (= "dana@riverbend[.]test" (text/defang "dana@riverbend.test")))
    (is (= "d_a+n-a@riverbend[.]test" (text/defang "d_a+n-a@riverbend.test")))
    ;; Found in the third round: Apple's finder takes none of these for a
    ;; mail address and follows the host (checked against NSDataDetector).
    (doseq [before ["+" "see +" ".+" "-+" "+-" "__" "--" "(-" "a." "\u01c5"]]
      (is (= (str (str/replace before #"(?<=\S)\." "[.]") "@secret[.]attacker[.]com") (text/defang (str before "@secret.attacker.com"))) before)))
  (testing "a host written straight after a port's digit is broken too"
    ;; Found in the third round: the port took its first digit with it,
    ;; and the host after it then began where no host may begin.
    (doseq [[s broken] [["docs.example.org:1secret-token.attacker.com/x" "docs[.]example[.]org:1secret-token[.]attacker[.]com/x"]
                        ["a.b:1secret.attacker.com" "a[.]b:1secret[.]attacker[.]com"]
                        ["example.org:8080attacker.com" "example[.]org:8080attacker[.]com"]
                        ["a.https:0x7f.evil.pl/x" "a[.]hxxps:0x7f[.]evil[.]pl/x"]
                        ;; relay#124's three, found again in relay#97's review.
                        ["a.bc:1evil.com/steal?d=1" "a[.]bc:1evil[.]com/steal?d=1"]
                        ["x.yz:9www.evil.com" "x[.]yz:9www[.]evil[.]com"]
                        ["me@a.bc:1evil.com/x" "me@a[.]bc:1evil[.]com/x"]]]
      (is (= broken (text/defang s)) s)))
  (testing "a scheme a terminal follows with no slashes after it is broken"
    ;; relay#126, from relay#95's review: Ghostty's link finder takes each
    ;; of these, and Apple's makes ssh:user@host a link.
    (doseq [[s broken] [["ssh:CANARY@evil.test" "ssh[:]CANARY@evil[.]test"]
                        ["magnet:?xt=urn:btih:abc" "magnet[:]?xt=urn:btih:abc"]
                        ["file:/etc/passwd" "file[:]/etc/passwd"]
                        ["news:comp.lang.c" "news[:]comp[.]lang[.]c"]
                        ["SSH:x@evil.test" "SSH[:]x@evil[.]test"]]]
      (is (= broken (text/defang s)) s))
    (testing "a word and a colon in a sentence are left alone, and so are the schemes relay#121 asks about"
      (doseq [s ["Re: the news: nothing" "key:value" "at 10:30" "ssh: later" "mailto:a@b" "tel:+15551234"]]
        (is (= s (text/defang s)) s))))
  (testing "www after an underscore or a hyphen is still www to a link finder"
    (doseq [[s broken] [["_www.a1.docx" "_www[.]a1[.]docx"] ["-WWW.123.txt" "-WWW[.]123[.]txt"]]]
      (is (= broken (text/defang s)) s)))
  (is (= "github[.]com/kira/tidy" (text/defang "github.com/kira/tidy")))
  (is (= "www[.]riverbend[.]zz" (text/defang "www.riverbend.zz")))
  (testing "a host is broken whatever its ending, once a path, query or port follows it"
    ;; Regression: only hosts with a listed ending were broken, so
    ;; evil.sh/c?d=SECRET reached a mail app as a link.
    (doseq [[s d] [["evil.sh/c?d=SECRET" "evil[.]sh/c?d=SECRET"] ["evil.icu/x" "evil[.]icu/x"]
                   ["evil.md:8080" "evil[.]md:8080"] ["-evil.com/x" "-evil[.]com/x"] ["_evil.com" "_evil[.]com"]
                   ["(evil.icu)" "(evil[.]icu)"] ["evil.lol" "evil[.]lol"] ["пример.рф/x" "пример[.]рф/x"]
                   ["evil.xn--p1ai/x" "evil[.]xn--p1ai/x"] ["a.b.md" "a[.]b[.]md"] ["x@evil.com/s" "x@evil[.]com/s"]]]
      (is (= d (text/defang s)) s))))

(deftest refs-become-words
  (is (= "Closed Mondays." (text/refs-as-words "Closed Mondays [[fact:4]].")))
  (is (= "See the choir notes (projects/choir.md)." (text/refs-as-words "See the choir notes [[file:projects/choir.md]].")))
  (is (= "Remembrance Day (office-holidays.html)." (text/refs-as-words "Remembrance Day [[web:office-holidays.html]].")))
  (is (= "No grants found." (text/refs-as-words "No grants found [[memo:45]][[journal:60]].")))
  (is (= "a [b] c (x)" (text/refs-as-words "a [b] c [[file:x]]"))))

(deftest hidden-characters-in-mail-are-taken-out-and-said
  (is (= "samwise (a hidden character removed)" (text/visible-plain "sam\u200bwise")))
  (is (= "plain\ttext\n" (text/visible-plain "plain\ttext\n"))))

;; --- finding schemes takes time in the length of the text (relay#125)

(defn- break-schemes-by-pattern
  "What defang's first step was: one pattern, which tries every letter of
  a long word as the start of a scheme."
  [s]
  (str/replace (str s) #"(?i)([a-z][a-z0-9+.\-]*)://"
               (fn [[_ scheme]]
                 (case (str/lower-case scheme)
                   "http" "hxxp://" "https" "hxxps://" "ftp" "fxp://"
                   ("hxxp" "hxxps" "fxp") (str scheme "://")
                   (str scheme "[:]//")))))

(def gen-schemy
  (gen/fmap #(apply str %)
            (gen/vector (gen/elements ["a" "B" "1" "+" "." "-" ":" "/" "://" " " "http" "https" "HTTPS" "ftp" "hxxp"
                                       "x-app" "é" "\n" "//" ":/"])
                        0 24)))

(defspec the-one-pass-scheme-finder-breaks-what-the-pattern-broke 2000
  (prop/for-all [s gen-schemy]
    (= (break-schemes-by-pattern s) (#'text/break-schemes s))))

(deftest a-very-long-word-does-not-slow-link-breaking
  ;; relay#125: one word of 8,000 letters took half a second, and every
  ;; piece of outside text goes through defang. It took 1 ms after the
  ;; fix; the bound leaves room for a slow machine.
  (let [ms (fn [f] (let [t0 (System/nanoTime)] (f) (/ (- (System/nanoTime) t0) 1e6)))
        live #{"https://x.example/a"}]
    (text/defang "warm up https://x.example/a")
    (doseq [[what word] [["letters" (apply str (repeat 100000 "a"))]
                         ;; This one overflowed the stack: the pattern that
                         ;; reads dotted names takes stack for every label.
                         ["dotted" (apply str (repeat 25000 "ab.c"))]
                         ["schemes" (apply str (repeat 20000 "a://b"))]
                         ;; A word outside ASCII is read one character at
                         ;; a time, dots and marks alike.
                         ["accented" (apply str (repeat 25000 "\u00e9.c\u0301"))]]]
      (is (< (ms #(text/defang word)) 1500) what)
      (is (< (ms #(text/links-in live word)) 1500) what)
      (is (< (ms #(text/anchored live (text/for-operator ["contaminated"] word))) 1500) what))
    (testing "relay#125's own case, one word of 8,000 letters: the best of five runs is under 50 ms"
      (let [word (apply str (repeat 8000 "a"))]
        (is (< (apply min (repeatedly 5 #(ms (fn [] (text/defang word))))) 50))))
    (testing "a very long dotted word has every dot broken"
      (is (not (re-find #"[a-z]\.[a-z]" (text/defang (apply str (repeat 25000 "ab.c")))))))))


;; --- relay#130: only a word of printable ASCII gets the benefit of the doubt

(def ^:private outside-text ["contaminated"])

(defn- cp [n] (String. (Character/toChars (int n))))

(defn- passed-over
  "`s` as a reader sees it that passes over what has no width of its
  own: marks and conjoining jamo. A hidden character is not among them,
  since no channel shows one raw: mail takes it out before links are
  broken and a screen shows it as an escape after."
  [s]
  (str/replace s #"[\p{Mn}\p{Me}\x{1160}-\x{11FF}\x{D7B0}-\x{D7FF}]" ""))

(defn- followable?
  "Whether text holds what a link finder needs, read as written or with
  the characters a reader passes over gone: a web scheme, www. before a
  name, or a dot with a letter, a digit, a symbol or an underscore after
  it, a digit aside. It restates what relay.text promises for a word in
  the test's own terms. What finders do with such words is the fuzz's
  and the sweep's to show (scripts/inert-text-fuzz.sh,
  scripts/inert-text-sweep.clj)."
  [s]
  (boolean (some (fn [s]
                   (or (re-find #"(?i)(?<![a-z0-9])(?:https?|ftp):/{0,2}(?:[a-z0-9]|\[(?!\.\]))" s)
                       (re-find #"(?i)(?<![a-z0-9])www\.[\p{L}\p{N}]" s)
                       (re-find #"\.[\p{L}\p{S}_]" s)))
                 [s (passed-over s)])))

(deftest no-name-is-taken-for-one-a-link-finder-will-not-follow
  (testing "Kira's examples (relay#121), as the rule she then chose (relay#130) shows them"
    (doseq [[s shown] [["todo.md" "todo[.]md"]
                       ["a@x.example" "a@x[.]example"]
                       ["r\u00e9sum\u00e9.pdf" "r\u00e9sum\u00e9[.]pdf"]
                       ["\u043e\u0442\u0447\u0451\u0442.pdf" "\u043e\u0442\u0447\u0451\u0442[.]pdf"]
                       ["j\u00f6rg@m\u00fcller.de" "j\u00f6rg@m\u00fcller[.]de"]
                       ["example.com\u00b2" "example[.]com\u00b2"]
                       ["\u24d4\u24e5\u24d8\u24db.com" "\u24d4\u24e5\u24d8\u24db[.]com"]
                       ["\u201cIt ended.\u201d she said" "\u201cIt ended.\u201d she said"]
                       ["\u20ac12.50" "\u20ac12.50"]]]
      (is (= shown (text/defang s)) s)))
  (testing "what relay#130's review found Apple's link finder following, each left whole before"
    (doseq [[what s shown] [["a superscript after the ending" "see example.com\u00b2 for details" "see example[.]com\u00b2 for details"]
                            ["a half after the ending" "evil.com\u00bd" "evil[.]com\u00bd"]
                            ["another script after the ending" "\u8bf7\u8bbf\u95eeevil.com\u7b2c1\u671f" "\u8bf7\u8bbf\u95eeevil[.]com\u7b2c1\u671f"]
                            ["a modifier letter after the @" "a@\u02b9evil.com" "a@\u02b9evil[.]com"]
                            ["a length mark inside a mail address's domain" "a@e\u30fcvil.com" "a@e\u30fcvil[.]com"]
                            ["a spacing mark after the @" "a@\u0982evil.com" "a@\u0982evil[.]com"]
                            ["circled letters" "evil\u24d0.com" "evil\u24d0[.]com"]
                            ["a squared letter" (str "evil" (cp 0x1F170) ".com") (str "evil" (cp 0x1F170) "[.]com")]]]
      (is (= shown (text/defang s)) what)))
  (testing "a hidden letter in a word, which a screen shows as an escape after links are broken"
    ;; The Hangul fillers are letters to a pattern and hidden to a screen:
    ;; the escape's closing brace gave a link finder the start of a host.
    (doseq [n [0x3164 0x115F 0xFFA0]]
      (is (= (format "a@\\u{%04x}evil[.]com" n) (messages/shown outside-text (str "a@" (cp n) "evil.com"))))
      (is (= (format "evil[.]com\\u{%04x}1" n) (messages/shown outside-text (str "evil.com" (cp n) "1"))))))
  (testing "a full stop that ends a sentence stays, in any script and inside any quote"
    (doseq [s ["na\u00efve..." "caf\u00e9." "\u00abfin.\u00bb" "(voir le r\u00e9sum\u00e9.)" "\u201eEnde.\u201c"
               "\u7ec8\u4e86.\u300d" "\u05e1\u05d5\u05e3." "end.\u0301\u201d"]]
      (is (= s (text/defang s)) (pr-str s))))
  (testing "and one with a name going on after it is broken"
    (doseq [[s shown] [["\u00e9.g." "\u00e9[.]g."] ["M.M\u00fcller" "M[.]M\u00fcller"]
                       ["c.-\u00e0-d." "c[.]-\u00e0-d."] ["(voir r\u00e9sum\u00e9.pdf)." "(voir r\u00e9sum\u00e9[.]pdf)."]
                       ["3.50\u20ac" "3.50\u20ac"] ["10.5\u00b0C" "10.5\u00b0C"]]]
      (is (= shown (text/defang s)) s)))
  (testing "any kind of space parts words, so a sentence's end next to one stays"
    (is (= "voir\u00a0todo[.]md \u00e0 10:30, 12.50 pay\u00e9.\u202fOui" (text/defang "voir\u00a0todo.md \u00e0 10:30, 12.50 pay\u00e9.\u202fOui")))
    (is (= "write to\u202fa@secretdata[.]evil[.]com" (text/defang "write to\u202fa@secretdata.evil.com")))))

(deftest a-mark-in-an-address-does-not-hide-it-from-the-breaker
  (testing "the forms Apple's link finder follows as written, and the others the review named"
    (doseq [[what s] [["U+0301 after https:" "https:\u0301//evil.com/steal?d=1"]
                      ["U+1161 after https:" "https:\u1161//evil.com/steal?d=1"]
                      ["U+1107F after https:" (str "https:" (cp 0x1107F) "//evil.com/steal?d=1")]
                      ["U+1BC9D after https:" (str "https:" (cp 0x1BC9D) "//evil.com/steal?d=1")]
                      ["keycap glue after https:" "https:\ufe0f\u20e3//evil.com/steal?d=1"]
                      ["U+0301 between the slashes" "https:/\u0301/evil.com/steal?d=1"]
                      ["U+0301 inside https" "htt\u0301ps://evil.com/steal?d=1"]
                      ["U+0323 inside www" "ww\u0323w.evil.com/steal?d=1"]
                      ["U+0301 inside a bare host" "evil\u0301.com/steal?d=1"]
                      ["U+0301 inside the ending" "evil.co\u0301m"]
                      ["the Markdown case" "https:*\ufe0f\u20e3//evil.test*"]]]
      (let [out (text/for-operator outside-text (str "see " s " now"))]
        (is (not (followable? out)) (str what ": " (pr-str out)))
        (is (= out (text/defang out)) (str what " is broken once and for all")))))
  (testing "a scheme a mark was hiding is broken as the address it spells, the mark gone"
    (is (= "hxxps://evil[.]com/steal?d=1" (text/defang "https:\u0301//evil.com/steal?d=1")))
    (is (= "hxxps://evil[.]com/x" (text/defang "htt\u0301ps://evil.com/x"))))
  (testing "the three things relay used to leave as written after such a scheme"
    (testing "the scheme itself"
      (is (str/starts-with? (text/defang "https:\u0301//evil.com/x") "hxxps://")))
    (testing "a host with no dot"
      (doseq [host ["localhost" "3232235777" "0xC0A80001" "[2001:db8::1]"]]
        (is (= (str "hxxps://" host "/steal?d=1") (text/defang (str "https:\u0301//" host "/steal?d=1"))) host)))
    (testing "a one-dot host that ends like a file, with nothing after it"
      (doseq [[host broken] [["evil.md" "evil[.]md"] ["setup.sh" "setup[.]sh"] ["notes.zip" "notes[.]zip"]
                             ["notes.py" "notes[.]py"]]]
        (is (= (str "hxxps://" broken) (text/defang (str "https:\u0301//" host))) host))))
  (testing "a word with a mark in it is taken for no file name and no mail address"
    ;; Found by the fuzz once its alphabet had marks: Apple's link finder
    ;; followed the host in each of these as relay showed it.
    (doseq [[s broken] [["a@\u0301a-b.a1.rs" "a@\u0301a-b[.]a1[.]rs"]
                        ["a@w\u0323w.a-b.ly%2Fx" "a@w\u0323w[.]a-b[.]ly%2Fx"]
                        ["x@Quart\u0489erly-pack.a_b.co" "x@Quart\u0489erly-pack[.]a_b[.]co"]
                        ["re\u0301sume\u0301.md" "re\u0301sume\u0301[.]md"]]]
      (is (= broken (text/defang s)) s)))
  (testing "a mark that hides nothing stays, in an address too"
    (is (= "hxxps://x[.]example/re\u0301sume\u0301" (text/defang "https://x.example/re\u0301sume\u0301")))))

(deftest ordinary-text-outside-ascii-comes-out-as-written
  (doseq [s ["cafe\u0301 au lait, nai\u0308ve re\u0301sume\u0301."          ; marks on Latin letters
             "नमस्ते दुनिया।" ; Devanagari, vowel signs and a virama
             "สวัสดีครับ"        ; Thai
             "\u1112\u1161\u11ab\u1100\u116e\u11a8\u110b\u1165."                 ; Korean in conjoining jamo
             "שָׁלוֹם, 10:30."               ; Hebrew with points, then a time
             "1\ufe0f\u20e3 first, then 10:30"                              ; a keycap, and a file name beside it
             "Привет, мир. Это тест."   ; Cyrillic sentences
             "今天天气很好。"]]                 ; Chinese, one sentence and its full stop
    (is (= s (text/defang s)) (pr-str s)))
  (testing "but an ideographic full stop between two characters is broken, as it always was"
    ;; A browser reads it as a dot, and Chinese has no space between
    ;; sentences to tell one from a host.
    (is (= "今天天气很好[.]明天见。" (text/defang "今天天气很好。明天见。")))))

(deftest the-two-marks-that-draw-as-nothing-are-hidden
  (doseq [n [0x1107F 0x1BC9D]]
    (is (text/hidden? n) (format "U+%X" n))
    (is (= (format "a\\u{%x}b" n) (text/visible (str "a" (cp n) "b"))))))

(def gen-any-character
  "A character from anywhere outside ASCII that is not a space: the
  ones the reviews found by name, and any code point at all."
  (gen/one-of
   [(gen/elements ["\u0301" "\u0323" "\u0489" "\u1161" "\u20e3" "\ufe0f\u20e3" (cp 0x1107F) (cp 0x1BC9D)
                   "\u00b2" "\u00bd" "\u7b2c" "\u02b9" "\u30fc" "\u0982" "\u3164" "\u24d0" (cp 0x1F170)
                   "\u200b" "\u200d" "\u202e" "\u00e9" "\u201d" "\u3002" "\uff0e" "\u2024" "\uff3f"])
    (gen/fmap cp (gen/such-that #(and (Character/isDefined (int %)) (not (<= 0xD800 % 0xDFFF))
                                      (not (Character/isWhitespace (int %))) (not (Character/isSpaceChar (int %))))
                                (gen/one-of [(gen/choose 0x80 0xFFFD) (gen/choose 0x10000 0x3FFFF)])
                                100))]))

(def gen-foreign-word
  "One word that tries to pass for an address, with one or two
  characters from outside ASCII put in at random places."
  (gen/let [before (gen/elements ["" "" "(" "a@" "x:" "+" "_" "a.b:1" "see:"])
            scheme (gen/elements ["" "" "https://" "http://" "HTTPS://" "https:" "https:/" "ftp://" "www." "ssh:" "feed:"])
            labels (gen/vector (gen/elements ["evil" "secret" "x" "a1" "a-b" "a_b" "com" "www" "0x7f" "123"]) 1 3)
            ending (gen/elements ["com" "co" "md" "sh" "txt" "pdf" "zip" "py" "in" "ly" "rs" "example"])
            after (gen/elements ["" "" "/" "/a?b=c" "?q=1" "#f" ":80" "." "," ")" "@x" "%2Fx" "1"])
            extra (gen/vector gen-any-character 1 2)
            places (gen/vector (gen/double* {:min 0 :max 1 :NaN? false :infinite? false}) 2)]
    (reduce (fn [s [c at]]
              (let [i (int (* at (count s)))
                    ;; Never between the two halves of a pair.
                    i (if (and (< 0 i (count s)) (Character/isLowSurrogate (.charAt ^String s i))) (dec i) i)]
                (str (subs s 0 i) c (subs s i))))
            (str before scheme (str/join "." labels) "." ending after)
            (map vector extra places))))

(defspec a-word-with-anything-outside-ascii-is-left-with-nothing-to-follow 3000
  ;; relay#130 and relay#121.
  (prop/for-all [word gen-foreign-word]
    (let [out (text/defang word)]
      (and (= out (text/defang out))
           ;; As each channel shows it: hidden characters escaped last (a
           ;; screen), or taken out first (mail). A word left in plain
           ;; ASCII once they are out is an ASCII word, with its
           ;; exceptions, and the fuzz's to judge.
           (not (followable? (messages/shown outside-text word)))
           (or (every? #(< (int %) 128) (text/without-hidden word))
               (not (followable? (messages/plain outside-text word))))))))

;; --- a scheme with no slashes, wherever a link finder takes it

(deftest a-scheme-straight-after-a-dot-or-a-sign-is-broken
  ;; From relay#130's review round and from the port of relay#126's rule
  ;; to relay-tui. The rule counted only at the start of a word, and
  ;; Ghostty's pattern takes ssh: wherever it stands, so
  ;; x[.]ssh:u@evil.test was a link there. The same held for a web scheme
  ;; written short, which relay then broke only on a second pass.
  (doseq [[s broken] [["https:/x.ssh:u@evil.test" "hxxps:/x[.]ssh[:]u@evil[.]test"]
                      ["x.ssh:u@evil.test" "x[.]ssh[:]u@evil[.]test"]
                      ["a+ssh:u@evil.test" "a+ssh[:]u@evil[.]test"]
                      ["a-file:/etc/passwd" "a-file[:]/etc/passwd"]
                      ["a.news:comp.lang.c" "a[.]news[:]comp[.]lang[.]c"]
                      ["a.https:/evil.com/x" "a[.]hxxps:/evil[.]com/x"]
                      ["+https:/evil.com/x" "+hxxps:/evil[.]com/x"]
                      ["1.https:/com.co.txt#f" "1[.]hxxps:/com[.]co[.]txt#f"]]]
    (is (= broken (text/defang s)) s))
  (testing "one run on from a letter or a digit is how ordinary text reads, and is left as written"
    (doseq [s ["Makefile:12: error" "profile:dev" "logfile:/var/log/x" "digit:5" "Re: the news: nothing"]]
      (is (= s (text/defang s)) s))))

(def gen-addressy
  "Text made of the pieces addresses are made of, in any order."
  (gen/fmap #(apply str %)
            (gen/vector (gen/elements ["a" "evil" "com" "co" "txt" "md" "1" "80" "." "." "/" ":" "@" "-" "+" "_" "?"
                                       "#" " " "https:" "http:" "ftp:" "ssh:" "file:" "news:" "www." "//" "://"
                                       "[.]" "[:]" "hxxps:" "́" "é" "(" ")" "。"])
                        0 16)))

(defspec breaking-links-twice-changes-nothing 3000
  ;; Text is broken again where it is cut (break-cut-word), so a second
  ;; pass must find nothing left to do.
  (prop/for-all [s gen-addressy]
    (let [once (text/defang s)]
      (= once (text/defang once)))))

(deftest a-mail-domain-a-link-finder-takes-for-no-mail-address-is-broken
  ;; relay#130's second review round. Apple's link finder reads none of
  ;; these as a mail address and follows the host inside each.
  (doseq [[s broken] [["a@-evil.com" "a@-evil[.]com"]
                      ["a@_evil.com" "a@_evil[.]com"]
                      ["x@-secret.attacker.com" "x@-secret[.]attacker[.]com"]
                      ["a@foo--bar.com" "a@foo--bar[.]com"]
                      ["a@x-.evil.com" "a@x-[.]evil[.]com"]
                      ["a@x_.evil.com" "a@x_[.]evil[.]com"]
                      ["a@b.-c.de" "a@b[.]-c[.]de"]
                      ["a@x._Devil.com" "a@x[.]_Devil[.]com"]
                      ["mail me at a@-evil.co.uk" "mail me at a@-evil[.]co[.]uk"]
                      ["a@-evil.com." "a@-evil[.]com."]]]
    (is (= broken (text/defang s)) s))
  (testing "and a well-formed one is broken too, like any host (round three's shapes among them)"
    (doseq [s ["a@x.example" "kira@mail.example.org" "a@my-host.example" "Totals 12,5@c2V.attacker.com"
               (str "a@" (apply str (repeat 290 "b")) "-evil.com")]]
      (is (= (str/replace s "." "[.]") (text/defang s)) s))))
