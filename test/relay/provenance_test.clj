(ns relay.provenance-test
  "Provenance that means something: what the operator is told about where
  a thing came from follows what the session read directly in its wake
  (its sources), never what it merely had in context, while labels keep
  bounding everything it may have seen."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.channels :as channels]
            [relay.core :as core]
            [relay.digest :as digest]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.messages :as messages]
            [relay.provenance :as provenance]
            [relay.ready :as ready]
            [relay.render :as render]
            [relay.text :as text]
            [relay.tools :as tools]))

;; --- the pure part

(def gen-label
  (gen/elements ["private" "contaminated" "mail:dana@riverbend.test" "github:kira/tidy"
                 "ics:calendar" "ics:holidays" "web:office-holidays.html" "search:web"
                 "mail:unverified-operator" "mail:unknown"]))

(def gen-labels (gen/vector gen-label 0 8))

(defspec sources-are-idempotent-and-never-private 300
  (prop/for-all [ls gen-labels]
    (let [s (provenance/sources ls)]
      (and (= s (provenance/sources s))
           (not (some #{"private"} s))
           (every? (set ls) s)
           ;; contaminated alone says no more, so it never sits beside a
           ;; source that does.
           (or (= ["contaminated"] s) (not (some #{"contaminated"} s)))
           (= (boolean (some #(not= "private" %) ls)) (provenance/outside? s))))))

(defspec direct-counts-a-session-label-only-for-an-mcp-tool 300
  (prop/for-all [carried gen-labels read gen-labels session gen-labels mcp gen-labels]
    (let [d (set (provenance/direct {:carried carried :read read :session session :mcp mcp}))]
      (and (every? d (remove #{"contaminated"} (provenance/sources (concat carried read))))
           (= (provenance/outside? (concat carried read (filter (set mcp) session))) (boolean (seq d)))
           (every? (set (concat carried read (filter (set mcp) session))) d)))))

(defspec drew-on-is-one-plain-line-that-names-no-label 300
  (prop/for-all [ls gen-labels]
    (let [line (provenance/drew-on ls)]
      (if (provenance/outside? ls)
        (and (str/starts-with? line "Drew on: ")
             (not (str/includes? line "\n"))
             (not (re-find #"contaminated|private|ics:|github:|mail:|web:|search:" line)))
        (nil? line)))))

(deftest sources-in-words
  (is (= "Drew on: a mail from dana@riverbend.test; the web page office-holidays.html"
         (provenance/drew-on ["web:office-holidays.html" "mail:dana@riverbend.test" "private"])))
  (testing "a calendar called calendar is the operator's own, said once"
    (is (= ["your calendar"] (provenance/phrases ["ics:calendar" "contaminated"])))
    (is (= ["the holidays calendar"] (provenance/phrases ["ics:holidays"]))))
  (is (= ["something from outside"] (provenance/phrases ["contaminated"])))
  (is (= ["GitHub (kira/tidy)"] (provenance/phrases ["github:kira/tidy"])))
  (testing "an address an outsider chose is never a link"
    (is (= ["the web page hxxps://evil[.]example/x"] (provenance/phrases ["web:https://evil.example/x"]))))
  (is (= "GitHub (a/b), the work calendar, a mail from x@y.test and 1 more"
         (provenance/summary ["mail:x@y.test" "github:a/b" "ics:work" "web:p.html"])))
  (is (= "GitHub (a/b) and a mail from x@y.test" (provenance/summary ["mail:x@y.test" "github:a/b"]))))

(defspec a-mark-is-short-and-names-no-label 300
  (prop/for-all [ls gen-labels]
    (let [m (provenance/mark ls)]
      (if (provenance/outside? ls)
        (and (re-matches #"\(from [a-zA-Z ]+\)" m)
             (<= (count m) 40)
             (not (re-find #"contaminated|private" m)))
        (nil? m)))))

(deftest a-mark-says-only-what-kind-of-thing-it-came-from
  (is (= "(from a web page)" (provenance/mark ["web:https://evil.example/x" "contaminated" "private"])))
  (is (= "(from a web search)" (provenance/mark ["search:web"])))
  (is (= "(from a mail)" (provenance/mark ["mail:x@y.test" "mail:z@y.test"])))
  (is (= "(from GitHub)" (provenance/mark ["github:a/b"])))
  (is (= "(from a calendar)" (provenance/mark ["ics:work"])))
  (is (= "(from outside)" (provenance/mark ["contaminated"])))
  (is (= "(from a mail and a web page)" (provenance/mark ["mail:x@y.test" "web:p.html"])))
  (is (= "(from outside)" (provenance/mark ["mail:x@y.test" "web:p.html" "github:a/b"]))
      "three kinds say no more than outside")
  (is (nil? (provenance/mark ["private"]))))

(deftest a-tool-call-names-the-page-it-read
  (is (= ["web:office-holidays.html"] (provenance/tool-sources {:page "office-holidays.html"} ["contaminated"])))
  (is (= ["web:https://a.example/x"] (provenance/tool-sources {"url" "https://a.example/x"} ["contaminated"])))
  (is (= ["search:web"] (provenance/tool-sources {:query "grants"} ["contaminated"])))
  (is (= [] (provenance/tool-sources {:page "x"} [])) "a tool the operator trusts is no source"))

;; --- defanging only what is a link

(def gen-plain-word
  "Words that are not links: file names, paths, times, amounts."
  (gen/elements ["todo.md" "interview.txt" "projects/choir.md" "hub/riverbend-interview-prep.md"
                 "2026-10-26T08:06:36.382648Z" "08:15:02.662139Z" "setup.sh"
                 "office-holidays.html" "$12.50" "1.5" "v1.2.3" "e.g." "dana@riverbend.test"
                 "banana-bread.txt" "board" "mtg.md" "skills/clerk/office-hours-questions.md"]))

(defspec defang-leaves-text-without-links-alone 300
  (prop/for-all [ws (gen/vector gen-plain-word 1 8)]
    (let [s (str/join " " ws)]
      (= s (text/defang s)))))

(def gen-linky
  (gen/fmap (fn [parts] (apply str parts))
            (gen/vector (gen/elements ["https://" "http://" "www." "evil" ".com" ".example" "github.com/x"
                                       "." "/path" " " "todo.md" "。" "10.0.0.1" "x"])
                        1 10)))

(def gen-tld
  "Any ending an attacker might pick: random letters, a punycode label, a
  non-ASCII one, or one that is also a file extension."
  (gen/one-of [(gen/fmap #(apply str %) (gen/vector (gen/elements (map char (range 97 123))) 2 6))
               (gen/elements ["xn--p1ai" "рф" "中国" "md" "sh" "rs" "so" "pl" "ps" "cc" "zip" "mov" "py" "icu"])]))

(def gen-host-link
  "label.tld followed by a path, query or port, after anything that may
  sit before a host."
  (gen/let [pre (gen/elements ["" "-" "_" "(" "see " "x@" "/" "\"" "a/"])
            labels (gen/vector (gen/elements ["evil" "d-secret" "a1" "пример" "www2"]) 1 3)
            tld gen-tld
            after (gen/elements ["/" "/steal?d=secret" "?d=1" "#x" ":8080/x"])]
    (str pre (str/join "." labels) "." tld after)))

(defspec any-host-with-a-path-is-broken-whatever-its-ending 500
  (prop/for-all [s gen-host-link]
    (let [d (text/defang s)]
      (and (= d (text/defang d))
           (not (re-find #"[\p{L}\p{N}]\.(?:\p{L}{2,}|xn--)[\p{L}\p{N}\-]*(?:/|\?|#|:\d)" d))))))

(defspec a-bare-host-is-broken-unless-it-reads-as-a-file-name 300
  (prop/for-all [labels (gen/vector (gen/elements ["evil" "d-secret" "a1"]) 1 3)
                 tld gen-tld]
    (let [s (str (str/join "." labels) "." tld)
          d (text/defang s)]
      ;; A name stays whole only with a file-like ending, and with more
      ;; than one dot only when that ending is no top-level domain: a host
      ;; under one could carry data in its labels.
      (or (= d (str/replace s "." "[.]"))
          (and (= d s) (contains? text/file-extensions tld)
               (or (= 1 (count labels)) (contains? text/never-domains tld)))))))

(def gen-hidden
  (gen/elements ["\u200b" "\u00ad" "\u2060" "\ufeff" "\u200d" "\u180e" "\udb40\udc41"]))

(defspec hidden-characters-never-put-a-link-back-together 500
  (prop/for-all [s (gen/one-of [gen-linky gen-host-link])
                 inserts (gen/vector (gen/tuple gen/nat gen-hidden) 1 4)]
    (let [s (reduce (fn [s [i h]] (let [i (mod i (inc (count s)))] (str (subs s 0 i) h (subs s i)))) s inserts)
          out (messages/plain ["contaminated"] s)
          line (messages/plain-line ["contaminated"] s)]
      (every? (fn [o]
                (and (not (re-find #"(?i)\b(https?|ftp)://" o))
                     (not (re-find #"(?i)(?<![\w.])www\.[^\s\[]" o))
                     (not (re-find #"[\p{L}\p{N}]\.(?:\p{L}{2,}|xn--)[\p{L}\p{N}\-]*(?:/|\?|#|:\d)" o))
                     (not (re-find #"(?i)(?<![@\w.\-/])(?:[a-z0-9](?:[a-z0-9\-]*[a-z0-9])?\.)+(?:com|example)(?![\w\-])" o))))
              [out line]))))

(deftest hidden-characters-are-taken-out-before-links-are-broken
  ;; Regression: defang ran first and the hidden character was taken out
  ;; after, so "evil\u200b.com/steal" reached a mail app as a live link.
  (is (= "click evil[.]com/steal?d=secret or 10[.]0[.]0[.]1 (hidden characters removed)"
         (messages/plain ["contaminated"] "click evil\u200b.com/steal?d=secret or 10\u200b.0.0.1")))
  (is (= "www[.]evil[.]com (a hidden character removed)" (messages/plain ["contaminated"] "www\u200b.evil.com")))
  (is (= "evil[.]com/x (a hidden character removed)" (messages/plain-line ["contaminated"] "evil\u00ad.com/x")))
  (testing "a page name in the footer too"
    (is (= "Drew on: the web page evil[.]com/x" (provenance/drew-on ["web:evil\u200b.com/x"])))
    (is (= ["a mail from x@evil[.]com/steal"] (provenance/phrases ["mail:x@evil\u200b.com/steal"])))))

(defspec defang-is-idempotent-and-leaves-no-followable-link 500
  (prop/for-all [s gen-linky]
    (let [d (text/defang s)]
      (and (= d (text/defang d))
           (not (re-find #"(?i)\b(https?|ftp)://" d))
           (not (re-find #"(?i)(?<![\w.])www\.[^\s\[]" d))
           (not (re-find #"(?i)(?<![@\w.\-/])(?:[a-z0-9](?:[a-z0-9\-]*[a-z0-9])?\.)+(?:com|example)(?![\w\-])" d))
           (not (re-find #"\S[。．｡]\S" d))))))

;; --- through relay: a wake's own reads, not its context

(defn- with-relay
  ([f] (with-relay {} f))
  ([more f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-provenance"}))
        data (str dir "/data")]
    (try
      (log/init! data)
      (spit (str data "/relay.edn")
            (pr-str (merge
                     {:timezone "UTC" :default-harness :agentloop
                     :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}
                     :messages {:channels {"mail" {:kind :email :url "smtp://127.0.0.1:2525"
                                                   :from "agent@house.test" :from-name "Your assistant"
                                                   :to "kira@house.test"}}
                                :routes []}
                     :grants {"hub" {}}}
                     more)))
      (f (inbound/system data))
      (finally (fs/delete-tree dir))))))

(defn- submit [sys actor type args & [labels]]
  (inbound/submit! sys {:type type :actor actor :args args :labels (or labels [])}))

(defn- start-wake [sys]
  (submit sys "dispatcher" :wake/start {:role "hub"})
  (:id (core/working-wake (inbound/current-state sys) "hub")))

(defn- end-wake [sys wake]
  (submit sys "dispatcher" :wake/end {:wake wake :outcome "ran"}))

(defn- call [sys tool args labels wake]
  (tools/call! sys "hub" tool args labels "c1" wake))

(defn- last-message [sys]
  (last (sort-by :id (vals (:messages (inbound/current-state sys))))))

(def calendar ["contaminated" "ics:calendar"])

(deftest what-a-wake-had-in-context-is-not-where-its-work-came-from
  (with-relay
    (fn [sys]
      (submit sys "operator" :role/add {:id "hub" :title "Hub" :instructions "Help."})
      (testing "a wake for a calendar memo draws on the calendar"
        (submit sys "operator" :memo/send {:to "hub" :subject "Calendar: 1 new" :body "Interview Thu"} calendar)
        (let [w (start-wake sys)
              session (core/union calendar ["private"])]
          (is (:ok (call sys "journal_write" {:text "Saw the interview on the calendar."} session w)))
          (is (:ok (call sys "tell_operator" {:subject "Interview on Thursday" :body "It is at 11."} session w)))
          (let [m (last-message sys)]
            (is (= ["ics:calendar"] (:sources m)))
            (is (= "Drew on: your calendar" (messages/drew-on m)))
            (is (str/ends-with? (channels/body-text m) "Drew on: your calendar"))
            (is (= "Interview on Thursday" (channels/subject-text m)) "never on the subject"))
          (is (:ok (call sys "memo_close" {:memo (first (get-in (inbound/current-state sys) [:wakes w :memos]))
                                           :outcome "noted"} session w)))
          (end-wake sys w)))
      (testing "a later wake for the operator's own request, with the calendar only in its prompt, is hers"
        (submit sys "operator" :memo/send {:to "hub" :subject "Tidy my notes" :body "Please."})
        (let [w (start-wake sys)
              ;; The journal tail in its prompt carries the calendar.
              session (core/union calendar ["private"])]
          (is (:ok (call sys "tell_operator" {:subject "Notes tidied" :body "All done."} session w)))
          (let [m (last-message sys)]
            (is (= [] (:sources m)))
            (is (nil? (messages/drew-on m)))
            (is (not (str/includes? (channels/body-text m) "Drew on")))
            (testing "but its labels still bound what it may have seen"
              (is (core/contaminated? (:labels m)))
              (is (core/private? (:labels m)))))
          (testing "reading the journal entry by a tool is reading it, and counts"
            (is (:ok (call sys "journal" {} session w)))
            (is (:ok (call sys "card_add" {:kind "note" :title "Prep" :text "Interview prep"} session w)))
            (let [st (inbound/current-state sys)
                  c (last (sort-by :id (vals (:cards st))))]
              (is (= ["ics:calendar"] (core/sources-of st (:id c) [])))
              (is (= "Drew on: your calendar"
                     (get (first (filter #(= (str "card:" (:id c)) (get % "id")) (map ready/wire (ready/ready st))))
                          "from")))))
          (end-wake sys w))))))

(deftest a-change-the-operator-asked-for-is-not-flagged
  (with-relay
    (fn [sys]
      (submit sys "operator" :role/add {:id "hub" :title "Hub" :instructions "Help."})
      (submit sys "operator" :memo/send {:to "hub" :subject "Keep a note on the choir" :body "Please."})
      (let [w (start-wake sys)
            ;; Inherited: what a GitHub memo of an earlier wake left in its notes.
            session ["contaminated" "github:kira/shelf" "private"]]
        (is (:ok (call sys "doc_put" {:path "skills/hub/choir.md" :content "# Choir\nUse when: choir\nRehearsals Tue."} session w)))
        (let [c (last (core/recipe-changes (inbound/current-state sys)))]
          (is (= [] (:sources c)))
          (is (empty? (digest/flagged [c] 0)))
          (is (= [c] (digest/quiet [c] 0)))))
      (testing "one shaped by a page read in the wake is flagged, its source named, with a peek"
        (let [w (core/working-wake (inbound/current-state sys) "hub")
              c (-> (inbound/current-state sys) core/recipe-changes last)]
          (is w)
          (let [cs [(assoc c :sources ["web:office-holidays.html"] :labels ["contaminated" "private"]
                           :peek "Ask about holiday hours before booking.")]
                note (digest/compose {:date (java.time.LocalDate/parse "2026-10-26") :changes cs :since 0
                                      :written {} :dismissed #{}})
                t (str/join "\n" (map :text (mapcat :items (:sections note))))]
            (is (str/includes? t "shaped by the web page office-holidays.html."))
            (is (str/includes? t "It says: \"Ask about holiday hours before booking.\""))))))))

;; --- what each kind of read counts

(def fixture (str (fs/absolutize "test/fixtures/envelope-tool")))

(def page-tool
  {:class :read :labels ["contaminated"] :protocol :envelope :description "Read the web."
   :command [fixture "page"]
   :parameters {:type "object" :properties {:page {:type "string"} :query {:type "string"}}
                :additionalProperties false}})

(defn- note-text [state]
  (let [{:keys [since changes]} (core/note-changes state (java.time.Instant/now))
        note (digest/compose {:date (java.time.LocalDate/parse "2026-10-26") :changes changes :since since
                              :written {} :dismissed #{}})]
    (str/join "\n" (map :text (mapcat :items (:sections note))))))

(deftest a-refusal-of-relay-s-own-tool-reads-nothing
  ;; Mutant M2: a refusal counted every label the session held as read,
  ;; which brought back provenance that accumulates across wakes.
  (with-relay
    (fn [sys]
      (submit sys "operator" :role/add {:id "hub" :title "Hub" :instructions "Help."})
      (submit sys "operator" :memo/send {:to "hub" :subject "Tidy my notes" :body "Please."})
      (let [w (start-wake sys)
            session ["contaminated" "ics:calendar" "private"]]
        (is (:error (call sys "memo_close" {:memo 999999 :outcome "noted"} session w)))
        (is (:error (call sys "doc" {:path "../nowhere"} session w)))
        (is (:ok (call sys "tell_operator" {:subject "Done" :body "All tidy."} session w)))
        (let [m (last-message sys)]
          (is (= [] (:sources m)))
          (is (nil? (messages/drew-on m))))))))

(deftest a-page-an-outside-tool-read-shapes-what-follows
  ;; Mutant M12: what a relay.edn outside tool returned was never counted.
  (with-relay {:tools {"web" page-tool} :grants {"hub" {:tools ["web"]}}}
    (fn [sys]
      (submit sys "operator" :role/add {:id "hub" :title "Hub" :instructions "Help."})
      (submit sys "operator" :memo/send {:to "hub" :subject "Holiday hours" :body "Find out."})
      (let [w (start-wake sys)
            session ["private"]]
        (is (:ok (call sys "web" {:page "office-holidays.html"} session w)))
        (is (:ok (call sys "doc_put" {:path "skills/hub/holidays.md"
                                      :content "# Holidays\nUse when: booking\nAsk about holiday hours first."}
                       (core/union session ["contaminated"]) w)))
        (let [st (inbound/current-state sys)
              c (last (core/recipe-changes st))]
          (is (= ["web:office-holidays.html"] (:sources c)))
          (is (= [(:seq c)] (mapv :seq (digest/flagged [c] 0))))
          (is (str/includes? (note-text st) "shaped by the web page office-holidays.html")))
        (testing "a search counts as a web search"
          (is (:ok (call sys "web" {:query "choir grants"} session w)))
          (is (:ok (call sys "doc_put" {:path "skills/hub/grants.md"
                                        :content "# Grants\nUse when: grants\nLook for small choir funds."}
                         (core/union session ["contaminated"]) w)))
          (is (some #{"search:web"} (:sources (last (core/recipe-changes (inbound/current-state sys)))))))))))

(def mcp-search
  {:protocol :mcp-stdio :server "web" :tool "search" :class :read :labels ["contaminated"]})

(deftest an-mcp-tool-s-labels-count-when-the-session-holds-them
  ;; Mutants M10 and the :mcp key: relay.edn's MCP tools have :protocol,
  ;; never :mcp, so their labels never counted.
  (let [run (fn [more]
              (with-relay more
                (fn [sys]
                  (submit sys "operator" :role/add {:id "hub" :title "Hub" :instructions "Help."})
                  (submit sys "operator" :memo/send {:to "hub" :subject "Find choir grants" :body "Please."})
                  (let [w (start-wake sys)
                        session ["contaminated" "private"]]
                    (is (:ok (call sys "tell_operator" {:subject "Grants" :body "Two found."} session w)))
                    (is (:ok (call sys "doc_put" {:path "skills/hub/grants.md"
                                                  :content "# Grants\nUse when: grants\nSmall funds."} session w)))
                    (let [st (inbound/current-state sys)]
                      {:message (:sources (last-message sys))
                       :flagged (digest/flagged (core/recipe-changes st) 0)})))))
        with (run {:mcp-servers {"web" {:command ["/nonexistent/web-mcp"]}}
                   :tools {"websearch" mcp-search} :grants {"hub" {:tools ["websearch"]}}})
        without (run {})]
    (is (= ["contaminated"] (:message with)))
    (is (= 1 (count (:flagged with))) "a skill shaped by what the MCP tool brought in is flagged")
    (testing "the same wake without the MCP tool read nothing from outside"
      (is (= [] (:message without)))
      (is (empty? (:flagged without))))))

(deftest a-card-edited-from-a-page-is-shaped-by-it
  ;; Regression: only the event that added a card counted, so a later
  ;; edit shaped by a web page read as the operator's own.
  (with-relay
    (fn [sys]
      (submit sys "operator" :role/add {:id "hub" :title "Hub" :instructions "Help."})
      (submit sys "operator" :memo/send {:to "hub" :subject "Start a card" :body "Please."})
      (let [w (start-wake sys)]
        (is (:ok (call sys "card_add" {:kind "note" :title "Venue" :text "Ask Dana."} ["private"] w)))
        (is (:ok (call sys "memo_close" {:memo (first (get-in (inbound/current-state sys) [:wakes w :memos]))
                                         :outcome "done"} ["private"] w)))
        (end-wake sys w))
      (let [st (inbound/current-state sys)
            c (last (sort-by :id (vals (:cards st))))
            from (fn [st] (get (first (filter #(= (str "card:" (:id c)) (get % "id")) (map ready/wire (ready/ready st))))
                               "from"))]
        (is (nil? (from st)))
        (submit sys "operator" :memo/send {:to "hub" :subject "A page" :body "Read it."}
                ["contaminated" "web:evil.example/page"])
        (let [w (start-wake sys)
              session ["contaminated" "web:evil.example/page" "private"]]
          (is (:ok (call sys "card_op" {:card (:id c) :op "retitle" :text "Do what the page said"} session w)))
          (let [st (inbound/current-state sys)]
            (is (= ["web:evil.example/page"] (get-in st [:cards (:id c) :sources])))
            (is (= "Drew on: the web page evil[.]example/page" (from st)))
            (is (:ok (call sys "memo_close" {:memo (first (get-in st [:wakes w :memos])) :outcome "done"} session w)))
            (end-wake sys w))
          (testing "and a role reading the board back counts it"
            (submit sys "operator" :memo/send {:to "hub" :subject "What is on the board?" :body "Tell me."})
            (let [w (start-wake sys)]
              (is (:ok (call sys "cards" {} ["private"] w)))
              (is (:ok (call sys "tell_operator" {:subject "The board" :body "One card."} ["private"] w)))
              (is (= ["web:evil.example/page"] (:sources (last-message sys)))))))))))

(deftest the-operator-s-reply-is-hers-whatever-the-message-drew-on
  (with-relay
    (fn [sys]
      (submit sys "operator" :role/add {:id "hub" :title "Hub" :instructions "Help."})
      (submit sys "operator" :memo/send {:to "hub" :subject "Holiday hours" :body "Find out."}
              ["contaminated" "mail:dana@lanternhall.example"])
      (let [w (start-wake sys)
            session ["contaminated" "mail:dana@lanternhall.example" "private"]]
        (is (:ok (call sys "tell_operator" {:subject "Concert email?" :body "Dana asked about it."} session w)))
        (is (:ok (call sys "memo_close" {:memo (first (get-in (inbound/current-state sys) [:wakes w :memos]))
                                         :outcome "told"} session w)))
        (end-wake sys w))
      (let [m (last-message sys)]
        (is (= ["mail:dana@lanternhall.example"] (:sources m)))
        (submit sys "operator" :message/reply {:in-reply-to (:id m) :text "Yes, write a how-to for it."})
        (let [st (inbound/current-state sys)
              reply (last (sort-by :id (filter #(= "operator" (:from %)) (vals (:memos st)))))]
          (is (= [] (core/sources-of st (:id reply) (:labels reply))) "her words have no outside source")
          (is (core/contaminated? (:labels reply)) "while its labels still bound what it holds")
          (is (not (str/includes? (str/join "\n" (render/inbox st java.time.ZoneOffset/UTC [reply])) "(from "))
              "her reply's status line names no source")
          (let [w (start-wake sys)
                session ["contaminated" "mail:dana@lanternhall.example" "private"]]
            (is (= [(:id reply)] (get-in (inbound/current-state sys) [:wakes w :memos])))
            (is (:ok (call sys "doc_put" {:path "skills/hub/concert-email.md"
                                          :content "# Concert email\nUse when: a concert\nDraft it."} session w)))
            (let [st (inbound/current-state sys)]
              (is (empty? (digest/flagged (core/recipe-changes st) 0)))
              (is (not (str/includes? (note-text st) "shaped by"))))))))))

(deftest the-operator-s-request-wakes-apart-from-the-mailbox
  ;; Regression: one wake took her verified request with a stranger's
  ;; mail, so the draft read as drawn on that mail.
  (with-relay
    (fn [sys]
      (submit sys "operator" :role/add {:id "hub" :title "Hub" :instructions "Help."})
      (submit sys "operator" :memo/send {:to "hub" :subject "Mail from dana" :body "Hello."}
              ["contaminated" "mail:dana@lanternhall.example"])
      (submit sys "operator" :memo/send {:to "hub" :subject "Draft the concert email" :body "Please."})
      (let [st (inbound/current-state sys)
            [outside own] (sort (keys (:memos st)))
            w (start-wake sys)]
        (is (= [own] (get-in (inbound/current-state sys) [:wakes w :memos])) "hers first, alone")
        (is (:ok (call sys "tell_operator" {:subject "Draft ready" :body "In your account."} ["contaminated" "private"] w)))
        (is (= [] (:sources (last-message sys))))
        (is (:ok (call sys "memo_close" {:memo own :outcome "done"} ["private"] w)))
        (end-wake sys w)
        (let [w (start-wake sys)
              st (inbound/current-state sys)]
          (is (= [outside] (get-in st [:wakes w :memos])) "then the mailbox")
          ;; Regression (U1b review): the pentest's attack wake ran
          ;; private only, so the split must never narrow labels: the
          ;; wake that takes the mail holds all it carries.
          (is (contains? (set (core/wake-labels st (:config sys) (get-in st [:wakes w]))) "contaminated")
              "and that wake is contaminated, as before the split"))))))

(deftest a-search-key-that-does-not-work-is-kept-on-the-wake
  (with-relay {:tools {"web" (assoc page-tool :command [fixture "nokey"])} :grants {"hub" {:tools ["web"]}}}
    (fn [sys]
      (submit sys "operator" :role/add {:id "hub" :title "Hub" :instructions "Help."})
      (submit sys "operator" :memo/send {:to "hub" :subject "Grants" :body "Search."})
      (let [w (start-wake sys)
            r (binding [*err* (java.io.StringWriter.)] (call sys "web" {:query "choir grants"} ["private"] w))]
        (is (= tools/not-set-up (:error r)) "relay tells the operator; the role is told not to")
        (is (= #{"search-key"} (get-in (inbound/current-state sys) [:wakes w :tool-failures])))))))
