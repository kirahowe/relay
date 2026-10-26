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

(deftest defang-leaves-file-names-paths-and-times-alone
  ;; Regression: every dot in a word was broken, so todo.md read todo[.]md
  ;; and a log's 08:06:36.382648Z read 08:06:36[.]382648Z.
  (doseq [s ["todo.md" "interview.txt" "projects/choir.md" "hub/riverbend-interview-prep.md"
             "2026-10-26T08:06:39.118474Z" "dana@riverbend.test" "Did you read todo.md?"
             "jquery.min.js" "setup.sh"]]
    (is (= s (text/defang s)) s))
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
