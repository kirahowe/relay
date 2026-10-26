(ns relay.render-test
  "What the relay command shows, checked as data: no stdout to capture."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cli :as cli]
            [relay.config :as config]
            [relay.core :as core]
            [relay.log :as log]
            [relay.render :as render]
            [relay.roster :as roster])
  (:import (java.time Instant ZoneId)))

(def utc (ZoneId/of "UTC"))
(def t0 (Instant/parse "2026-10-15T12:00:00Z"))
(def cfg (config/resolve-config {:timezone "UTC" :default-harness :x :harnesses {:x {:kind :script}}}))

(defn- cmd [state type args]
  (first (core/run-command state {:type type :actor core/operator :args args} {:now t0 :config cfg})))

(defn- quick [n property]
  (let [result (tc/quick-check n property)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

;; --- the log

(def gen-event
  (gen/fmap (fn [[n type actor data]]
              {:seq n :at (str (.plusSeconds t0 n)) :type type :actor actor :data data})
            (gen/tuple gen/nat (gen/elements ["memo/sent" "wake/started" "tick"])
                       (gen/elements ["operator" "dispatcher" "clerk"])
                       (gen/map gen/keyword gen/string-alphanumeric))))

(def the-log-shows-the-last-events-one-per-line
  (prop/for-all [events (gen/vector gen-event 0 30)
                 tail (gen/one-of [(gen/return nil) gen/nat])
                 json? gen/boolean]
    (let [lines (render/log events {:tail tail :json json?})
          shown (if tail (take-last tail events) events)]
      (and (= (count shown) (count lines))
           (not-any? #(str/includes? % "\n") lines)
           (if json?
             (= (map #(update-keys % keyword) shown)
                (map #(json/parse-string % true) lines))
             (every? (fn [[e line]] (str/starts-with? (str/triml line) (str (:seq e) " ")))
                     (map vector shown lines)))))))

(deftest the-log-is-one-line-per-event
  (quick 300 the-log-shows-the-last-events-one-per-line))

;; --- status

(def gen-role-ids
  (gen/fmap #(vec (distinct %))
            (gen/vector (gen/fmap #(str "r" %) (gen/choose 0 30)) 0 8)))

(def status-lists-every-role-once
  (prop/for-all [ids gen-role-ids
                 memos gen/nat
                 woken gen/nat]
    (let [state (reduce #(cmd %1 :role/add {:id %2 :title (str "T " %2) :instructions "x"})
                        core/empty-state ids)
          state (reduce (fn [s i] (cmd s :memo/send {:to (first ids) :subject (str "s" i) :body "b"}))
                        state (when (seq ids) (range (min memos 5))))
          state (reduce (fn [s id] (cmd s :wake/start {:role id :chat true}))
                        state (take woken ids))
          lines (vec (render/status state utc t0))
          headers (filter #(re-matches #"  r\d+ +T r\d+.*" %) lines)
          recent (rest (drop-while #(not= "Recent wakes" %) lines))]
      (and (= "Roles" (first lines))
           (= (sort ids) (sort (map #(second (re-find #"^  (\S+)" %)) headers)))
           (= (min woken (count ids)) (count (filter #(str/includes? % "[conversation open]") headers)))
           (<= (count recent) 5)
           (some #{"Your inbox: 0 open"} lines)))))

(deftest status-shows-every-role-and-at-most-five-wakes
  (quick 200 status-lists-every-role-once))

(deftest a-paused-harness-says-when-it-is-tried-next
  (let [state (assoc core/empty-state :harnesses {"x" {:condition "limited"
                                                       :paused-until (.plusSeconds t0 3600)}
                                                  "y" {:condition "limited"
                                                       :paused-until (.minusSeconds t0 60)}})
        lines (set (render/status state utc t0))]
    (is (lines "Harness x: limited, next try 2026-10-15 13:00"))
    (is (lines "Harness y: available"))))

(deftest status-shows-this-weeks-spend-per-role
  (let [at (Instant/parse "2026-10-13T12:00:00Z")
        wake (fn [id role cost] {:id id :role role :outcome "ran" :status :ended :started-at at :ended-at at
                                 :cost-usd cost})
        state (-> core/empty-state
                  (assoc :wakes {1 (wake 1 "hub" 0.5) 2 (wake 2 "hub" 0.25) 3 (wake 3 "clerk" nil)
                                 4 (assoc (wake 4 "clerk" 1.0) :ended-at (Instant/parse "2026-10-06T12:00:00Z"))}))
        lines (vec (render/status state utc t0 {:budget {:weekly-usd 15}}))
        section (take 5 (drop-while #(not (str/starts-with? % "Spend this week")) lines))]
    (is (= ["Spend this week, from Monday 2026-10-12"
            "  clerk                $0.00, 1 wake without a cost"
            "  hub                  $0.75"
            "  total                $0.75 of a $15.00 weekly budget, 1 wake without a cost"
            "  earlier weeks: 2026-10-05 $1.00, 2026-09-28 $0.00, 2026-09-21 $0.00, 2026-09-14 $0.00"]
           section))
    (testing "without a budget, the total stands alone"
      (is (some #{"  total                $0.75, 1 wake without a cost"} (render/status state utc t0))))
    (testing "a cost under a cent is not shown as nothing"
      (is (some #{"  total                <$0.01"}
                (render/status (assoc core/empty-state :wakes {1 (wake 1 "hub" 0.0000105)}) utc t0))))
    (testing "nothing to show without wakes or a budget"
      (is (not-any? #(str/starts-with? % "Spend") (render/status core/empty-state utc t0))))))

;; --- memos, notes and the rest

(deftest an-inbox-indents-every-line-of-a-memo
  (is (= ["Nothing waiting."] (render/inbox core/empty-state utc [])))
  (is (= ["" "[4] Lunch" "    from clerk, 2026-10-15 12:00" "  │ Noon?" "  │ " "  │ Or one."]
         (render/inbox core/empty-state utc [{:id 4 :from "clerk" :subject "Lunch" :body "Noon?\n\nOr one." :sent-at t0}]))))

(deftest routines-are-shown-for-one-role-or-all
  (let [state (-> core/empty-state
                  (cmd :role/add {:id "a" :title "A" :instructions "x"})
                  (cmd :role/add {:id "b" :title "B" :instructions "x"})
                  (cmd :routine/write {:owner "a" :name "daily" :schedule "daily 09:00" :instructions "x"})
                  (cmd :routine/write {:owner "b" :name "weekly" :schedule "weekly mon 09:00" :instructions "x"}))]
    (is (= 2 (count (render/routines state utc nil))))
    (is (= [(format "%-16s %-18s %s (since %s)" "b" "weekly" "weekly mon 09:00" "2026-10-15 12:00")]
           (render/routines state utc ["b"])))
    (is (= ["a: A" "b: B"] (render/roles state (roster/roster state cfg t0))))))

(deftest a-recipe-shaped-from-outside-says-where-it-came-from
  ;; Regression (U1b review, s6): a routine written by a session that
  ;; read a web page listed with no mark at all.
  (let [as (fn [state actor type args sources]
             (first (core/run-command state {:type type :actor actor :args args :labels ["contaminated"]
                                             :sources sources}
                                      {:now t0 :config cfg})))
        state (-> core/empty-state
                  (cmd :role/add {:id "a" :title "A" :instructions "x"})
                  (as "a" :routine/write {:name "pay" :schedule "every 1m" :instructions "x"} ["web:https://evil.example/p"])
                  (as "a" :routine/write {:name "tidy" :schedule "daily 09:00" :instructions "x"} [])
                  (as "a" :doc/put {:path "skills/a/pay.md" :content "# Pay\nUse when: paying"} ["mail:x@evil.example"]))]
    (is (= [(format "%-16s %-18s %s (since %s) (from a web page)" "a" "pay" "every 1m" "2026-10-15 12:00")
            (format "%-16s %-18s %s (since %s)" "a" "tidy" "daily 09:00" "2026-10-15 12:00")]
           (render/routines state utc ["a"]))
        "the mark says what kind of thing, and never the page itself")
    (is (some #(= (format "      routine %-16s %s (from a web page)" "pay" "every 1m") %)
              (render/status state utc t0))
        "status marks it the same way")
    (is (some #(and (str/starts-with? % "skills/a/pay.md") (str/ends-with? % "(from a mail)"))
              (render/docs state utc (core/docs state nil)))
        "and so does a skill in relay docs")))

(deftest relay-roles-shows-what-each-role-is-doing
  (let [as (fn [state actor type args]
             (first (core/run-command state {:type type :actor actor :args args} {:now t0 :config cfg})))
        state (-> core/empty-state
                  (cmd :role/add {:id "a" :title "A" :instructions "x"})
                  (cmd :role/add {:id "b" :title "B" :instructions "x" :reports-to "a"})
                  (cmd :memo/send {:to "a" :subject "s" :body "b"})
                  (as "dispatcher" :wake/start {:role "a" :dispatched true})
                  (cmd :wake/start {:role "b" :chat true}))
        chat (:id (core/open-conversation state "b"))
        state (-> state
                  (cmd :conversation/turn-start {:wake chat :session "s1"})
                  (as "dispatcher" :conversation/turn-end {:wake chat :stop-reason "end_turn"})
                  (cmd :conversation/turn-start {:wake chat :session "s1"}))]
    (is (= ["a: A  [working]"
            "b: B, reports to a  [conversation: turn running, 2 turns, unread]"]
           (render/roles state (roster/roster state cfg t0))))))

(deftest a-wake-and-a-doc-say-what-happened
  ;; Regression: it printed raw EDN, such as ran {:wake 4, :outcome ...}.
  (is (= ["Hub had nothing to do."] (render/wake-result "hub" {:result :idle})))
  (is (= ["Hub ran (wake 3) and finished its memos."]
         (render/wake-result "hub" {:result :ran :wake 3 :outcome "ran" :left-open 0})))
  (is (= ["Hub ran (wake 3) and left 1 memo open; it will come back to them."]
         (render/wake-result "hub" {:result :ran :wake 3 :outcome "ran" :left-open 1})))
  (is (= ["Hub could not start (wake 4). relay ready says what it needs."]
         (render/wake-result "hub" {:result :ran :wake 4 :outcome "needs-attention" :left-open 1})))
  (is (= ["scout is a public role, so a note for it needs --public."]
         (render/wake-result "scout" {:result :invalid :message "scout is a public role, so a note for it needs --public."})))
  (is (= ["Wrote a/b, version 9"] (render/doc-put "a/b" [{:seq 9}])))
  (is (= ["a/b already says that."] (render/doc-put "a/b" []))))

;; --- the command returns what it prints

(deftest a-command-returns-its-lines-instead-of-printing-them
  (let [dir (str (fs/create-temp-dir {:prefix "relay-render-test"}))
        data (str dir "/data")]
    (try
      (fs/create-dirs data)
      (spit (str data "/relay.edn") (pr-str {:timezone "UTC" :default-harness :x
                                             :harnesses {:x {:kind :script :command ["true"]}}}))
      (log/init! data)
      (let [printed (with-out-str
                      (is (= {:exit 0 :out ["Nothing waiting."]} (cli/execute ["--data" data "inbox"])))
                      (is (= ["Roles" "  No roles yet. relay init adds your steward."
                              "" "Your inbox: 0 open"]
                             (:out (cli/execute ["--data" data "status"]))))
                      (testing "an unknown command says so, shows the short usage and fails"
                        (is (= {:exit 1 :out (concat ["relay has no command nonsense." ""] (str/split-lines cli/short-usage))}
                               (cli/execute ["--data" data "nonsense"])))))]
        (is (= "" printed)))
      (finally (fs/delete-tree dir)))))

(deftest a-memo-says-where-it-came-from-once-and-marks-nothing
  (let [m {:id 4 :from "scout" :subject "Found https://grants.example" :body "Three grants." :sent-at t0 :labels ["contaminated"]}]
    (is (= ["" "[4] Found hxxps://grants[.]example" "    from scout, 2026-10-15 12:00" "  │ Three grants."]
           (render/memo core/empty-state utc m)))
    (testing "what an ingestor brought says what it is"
      (is (some #{"    GitHub (o/r), 2026-10-15 12:00"}
                (render/memo core/empty-state utc (assoc m :from "dispatcher" :labels ["contaminated" "github:o/r"]))))
      (is (some #{"    Your calendar, 2026-10-15 12:00"}
                (render/memo core/empty-state utc (assoc m :from "dispatcher" :labels ["contaminated" "ics:calendar"])))))))

(deftest status-lines-sit-under-their-role-with-where-they-came-from-last
  ;; Regression: the marker went first, ahead of the indent, so a memo
  ;; did not sit under its role: "[from the calendar calendar]     open  [26] ...".
  (let [state (-> core/empty-state
                  (cmd :role/add {:id "hub" :title "Hub" :instructions "x"}))
        [state] (core/run-command state {:type :memo/send :actor core/operator :labels ["contaminated" "ics:calendar"]
                                         :args {:to "hub" :subject "Your calendar: 2 new" :body "b"}}
                                  {:now t0 :config cfg})
        lines (vec (render/status state utc t0))]
    ;; Regression (U1b review): "GitHub kira/shelf: 1 new (from GitHub
    ;; (kira/shelf))" said its source twice.
    (is (some #{"      open  [2] Your calendar: 2 new"} lines) "the subject names the source, so it is not said again")
    (is (not-any? #(str/includes? % "(from") lines) (pr-str lines))))

(deftest only-a-subject-relay-built-and-shown-whole-names-its-source
  (let [state (-> core/empty-state (cmd :role/add {:id "steward" :title "Steward" :instructions "x"}))
        send (fn [state labels subject]
               (first (core/run-command state {:type :memo/send :actor core/operator :labels labels
                                               :args {:to "steward" :subject subject :body "b"}}
                                        {:now t0 :config cfg})))
        lines-after (fn [state n]
                      (let [lines (vec (render/status state utc t0))
                            i (first (keep-indexed (fn [i l] (when (str/starts-with? l (str "      open  [" n "] ")) i)) lines))]
                        (take-while #(str/starts-with? % "            ") (drop (inc i) lines))))]
    (testing "a subject an outsider wrote that happens to hold the source's name does not hide it"
      (let [st (send state ["contaminated" "search:web"] "Found web grants for the choir")]
        (is (= ["            from a web search"] (lines-after st 2)))))
    (testing "relay's own subject for a mail names it"
      (let [st (send state ["contaminated" "mail:eve@example.net"] "Mail from eve@example.net: Venue")]
        (is (empty? (lines-after st 2)))))
    (testing "the same words not at the start, as an outsider could write them, do not"
      (let [st (send state ["contaminated" "mail:eve@example.net"] "Re: Mail from eve@example.net: Venue")]
        (is (seq (lines-after st 2)))))
    (testing "a name cut off at the end of the line is not shown, so it is said below"
      (let [addr (str (apply str (repeat 50 "a")) "@example.net")
            st (send state ["contaminated" (str "mail:" addr)] (str "Mail from " addr ": Venue"))]
        (is (seq (lines-after st 2)))))))

(deftest a-status-memo-line-fits-a-phone-and-says-its-source-below
  ;; Regression (U1b review): open-memo lines ran to 130 columns.
  (let [subject (str "Holiday hours for the office and the hall, and what the page said about the "
                     "booking deadline in November")
        state (-> core/empty-state
                  (cmd :role/add {:id "steward" :title "Steward" :instructions "x"}))
        [state] (core/run-command state {:type :memo/send :actor core/operator
                                         :labels ["contaminated" "web:https://example.org/office-holidays.html"]
                                         :args {:to "steward" :subject subject :body "b"}}
                                  {:now t0 :config cfg})
        [state] (core/run-command state {:type :memo/send :actor core/operator :labels ["contaminated" "github:kira/shelf"]
                                         :args {:to "steward" :subject "GitHub kira/shelf: 1 new" :body "b"}}
                                  {:now t0 :config cfg})
        lines (vec (render/status state utc t0))
        i (first (keep-indexed (fn [i l] (when (str/starts-with? l "      open  [2] Holiday") i)) lines))]
    (is i (pr-str lines))
    (is (<= (count (lines i)) 60) (lines i))
    (is (str/ends-with? (lines i) "…") "the subject is cut")
    (is (str/starts-with? (lines (inc i)) "            from the web page") (lines (inc i)))
    (is (every? #(<= (count %) 60) (take-while #(str/starts-with? % "            ") (drop (inc i) lines))))
    (is (some #{"      open  [3] GitHub kira/shelf: 1 new"} lines) (pr-str lines))))
