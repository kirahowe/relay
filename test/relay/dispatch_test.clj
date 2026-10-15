(ns relay.dispatch-test
  "Drives the dispatcher against a throwaway data directory with a stand-in
  harness: a shell script that does whatever the test wrote to its plan,
  calling the real `relay tool` command as a model would, and exits with the
  code the test chose."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cli :as cli]
            [relay.config :as config]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.harness :as harness]
            [relay.inbound :as inbound]
            [relay.log :as log])
  (:import (java.time Instant)))

(def relay-bin (str (fs/absolutize "bin/relay")))

(def ^:dynamic *dir* nil)
(def ^:dynamic *sys* nil)

(defn- script! [path body]
  (spit path (str "#!/bin/sh\n" body "\n"))
  (fs/set-posix-file-permissions path "rwxr-xr-x")
  (str path))

(defn- plan!
  "Sets what the stand-in harness does on its next runs."
  [body exit-code]
  (spit (str *dir* "/plan") (str body "\nexit " exit-code "\n")))

(def close-everything
  (str "ids=$($TOOL inbox </dev/null | sed -n 's/^Memo \\([0-9]*\\):.*/\\1/p')\n"
       "for id in $ids; do\n"
       "  echo \"{\\\"memo\\\": $id, \\\"outcome\\\": \\\"Filed it.\\\"}\" | $TOOL memo_close\n"
       "done"))

(def do-nothing ":")

(defn- report! [m]
  (str "cat > \"$REPORT\" <<'JSON'\n" (json/generate-string m) "\nJSON"))

(defn- write-config! [extra]
  (spit (str *dir* "/data/relay.edn")
        (pr-str (merge {:timezone "UTC"
                        :default-harness :fake
                        :harnesses {:fake {:kind :script :command [(str *dir* "/harness")]}}
                        :notify {:kind :command :command [(str *dir* "/notify")]}
                        :max-attempts 2
                        :grants {"hub" {:may-add true}}}
                       extra))))

(defn- system [] (inbound/system (str *dir* "/data")))

(use-fixtures :each
  (fn [run-test]
    (let [dir (str (fs/create-temp-dir {:prefix "relay-dispatch-test"}))
          before (System/getProperty "relay.bin")]
      (System/setProperty "relay.bin" relay-bin)
      (script! (str dir "/harness") (str "env > \"" dir "/env\"\n"
                                         "cp \"$1\" \"" dir "/args.json\"\n"
                                         "REPORT=$(sed -n 's/.*\"report\":\"\\([^\"]*\\)\".*/\\1/p' \"$1\")\n"
                                         "RESUME=$(sed -n 's/.*\"resume\":\"\\([^\"]*\\)\".*/\\1/p' \"$1\")\n"
                                         "TOOL=$(sed -n 's/.*\"tool_command\":\\[\\([^]]*\\)\\].*/\\1/p' \"$1\" | tr -d '\"' | tr ',' ' ')\n"
                                         "cat > \"" dir "/prompt\"\n"
                                         ". \"" dir "/plan\""))
      (script! (str dir "/notify") (str "echo \"$1\" >> \"" dir "/notifications\""))
      (log/init! (str dir "/data"))
      (binding [*dir* dir]
        (write-config! {})
        (binding [*sys* (system)]
          (inbound/submit! *sys* {:type :role/add :actor "operator" :args {:id "hub" :title "Hub" :instructions "Run relay."}})
          (inbound/submit! *sys* {:type :role/add :actor "operator" :args {:id "clerk" :title "Clerk" :instructions "File things."}})
          (plan! close-everything 0)
          (try (run-test)
               (finally
                 (if before (System/setProperty "relay.bin" before) (System/clearProperty "relay.bin"))
                 (fs/delete-tree dir))))))))

(defn- state [] (inbound/current-state *sys*))
(defn- notifications []
  (let [f (str *dir* "/notifications")]
    (if (fs/exists? f) (str/split-lines (slurp f)) [])))
(defn- memo! [to subject]
  (:seq (first (inbound/submit! *sys* {:type :memo/send :actor "operator"
                                       :args {:to to :subject subject :body "Body."}}))))

(deftest nothing-to-do
  (is (= :idle (:result (dispatch/wake! *sys* "clerk" {})))))

(deftest a-wake-the-role-finishes
  (let [id (memo! "clerk" "File this")
        result (dispatch/wake! *sys* "clerk" {})]
    (is (= {:result :ran :outcome "ran" :left-open 0} (dissoc result :wake)))
    (is (= :read (:status (core/memo (state) id))))
    (is (= "Filed it." (:outcome (core/memo (state) id))))
    (is (= "clerk" (:closed-by (core/memo (state) id))))
    (testing "the harness was given the prompt, and its transcript is kept"
      (is (str/includes? (slurp (str *dir* "/prompt")) "Memo 3: File this"))
      (is (fs/exists? (get-in (state) [:wakes (:wake result) :transcript]))))))

(deftest the-script-harness-gets-one-argument-a-json-file-and-a-clean-environment
  (memo! "clerk" "x")
  (let [result (dispatch/wake! *sys* "clerk" {})
        env (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp (str *dir* "/env")))))
        args (json/parse-string (slurp (str *dir* "/args.json")) true)]
    (is (= #{:system_prompt :tools_file :report :resume :tool_command} (set (keys args))))
    (is (str/includes? (slurp (:system_prompt args)) "File things."))
    (is (sequential? (json/parse-string (slurp (:tools_file args)))))
    (is (= [relay-bin "tool" "--data" (str (fs/absolutize (str *dir* "/data"))) "--role" "clerk"]
           (:tool_command args))
        "a tool call names the role, not the wake")
    (testing "no RELAY_ variables, and nothing leaks in from whoever started relay"
      (is (not-any? #(str/starts-with? % "RELAY_") (keys env)))
      (is (not-any? #(re-find #"^(CLAUDE|ANTHROPIC|OPENAI|AWS_|GITHUB)" %) (keys env))))))

(deftest a-clean-exit-that-closes-nothing-is-a-failed-attempt-then-a-dead-letter
  (let [id (memo! "clerk" "File this")]
    (plan! do-nothing 0)
    (is (= 1 (:left-open (dispatch/wake! *sys* "clerk" {}))))
    (is (= 1 (:attempts (core/memo (state) id))))
    (dispatch/wake! *sys* "clerk" {})
    (is (= :dead (:status (core/memo (state) id))))
    (let [[report] (core/unread-memos (state) "hub")]
      (is (str/starts-with? (:subject report) "Dead letter")))
    (is (empty? (notifications)) "the operator is not bothered")))

(deftest a-rate-limited-run-pauses-and-the-next-wake-resumes-its-session
  (let [id (memo! "clerk" "File this")]
    (plan! (report! {:outcome "limited" :session "s-1" :resets-at "2099-01-01T00:00:00Z"}) 1)
    (is (= "limited" (:outcome (dispatch/wake! *sys* "clerk" {}))))
    (is (zero? (:attempts (core/memo (state) id))))
    (is (some? (core/paused-until (state) "fake" (Instant/now))))
    (is (empty? (dispatch/tick! *sys* {:spawn! (fn [& _] (throw (ex-info "should not spawn" {})))})))
    (plan! (str "echo \"$RESUME\" > \"" *dir* "/resumed\"\n" close-everything) 0)
    (is (= "ran" (:outcome (dispatch/wake! *sys* "clerk" {}))))
    (is (= "s-1" (str/trim (slurp (str *dir* "/resumed")))))
    (is (str/includes? (slurp (str *dir* "/prompt")) "cut short"))
    (is (nil? (core/paused-until (state) "fake" (Instant/now))))))

(deftest a-harness-that-needs-a-person-tells-the-operator-once
  (memo! "clerk" "File this")
  (plan! (report! {:outcome "needs-attention" :said "Not logged in"}) 1)
  (dispatch/wake! *sys* "clerk" {})
  (dispatch/wake! *sys* "clerk" {})
  (is (= ["fake needs you before relay can work"] (notifications))))

(deftest a-run-past-its-deadline-is-stopped-and-counts-as-a-crash
  (write-config! {:wake-timeout-minutes 0.03})
  (binding [*sys* (system)]
    (let [id (memo! "clerk" "Slow")]
      (plan! "sleep 30" 0)
      (let [start (System/currentTimeMillis)
            result (dispatch/wake! *sys* "clerk" {})
            wake (get-in (state) [:wakes (:wake result)])]
        (is (< (- (System/currentTimeMillis) start) 15000))
        (is (= "timeout" (:exit wake)))
        (is (true? (:crashed wake)))
        (is (= 1 (:attempts (core/memo (state) id))))))))

(deftest a-missing-harness-is-reported-not-crashed-on
  ;; A role on a harness the operator has since taken out of relay.edn.
  (let [fake {:kind :script :command [(str *dir* "/harness")]}]
    (write-config! {:harnesses {:fake fake :other fake}})
    (inbound/submit! (system) {:type :role/update :actor "operator" :args {:id "clerk" :harness "other"}})
    (write-config! {:harnesses {:fake fake}}))
  (memo! "clerk" "x")
  (binding [*sys* (system)]
    (is (= "needs-attention" (:outcome (dispatch/wake! *sys* "clerk" {}))))
    (is (= 1 (count (notifications))))))

(deftest memos-to-the-operator-notify-and-a-failed-push-is-recorded
  (memo! "operator" "Approve the filing?")
  (is (= ["Approve the filing?"] (notifications)))
  (is (= [:delivered] (map :delivery (vals (:notifications (state))))))
  (script! (str *dir* "/notify") "exit 3")
  (memo! "operator" "Another")
  (is (= #{:delivered :failed} (set (map :delivery (vals (:notifications (state)))))))
  (testing "the memo is in the log either way"
    (is (= 2 (count (core/unread-memos (state) "operator"))))))

(deftest a-wake-whose-process-died-is-reaped-by-the-tick
  (let [id (memo! "clerk" "x")
        dead (p/process ["true"])
        _ @dead
        pid (.pid ^Process (:proc dead))]
    (inbound/submit! *sys* {:type :wake/start :actor "dispatcher"
                            :args {:role "clerk" :pid pid :pid-start "1970-01-01T00:00:00Z"}})
    (is (core/running? (state) "clerk"))
    (dispatch/tick! *sys* {:spawn! (fn [& _])})
    (is (not (core/running? (state) "clerk")))
    (is (= 1 (:attempts (core/memo (state) id))))))

(deftest a-live-wake-is-not-reaped
  (memo! "clerk" "x")
  (inbound/submit! *sys* {:type :wake/start :actor "dispatcher"
                          :args (merge {:role "clerk"} (dispatch/current-process))})
  (dispatch/tick! *sys* {:spawn! (fn [& _])})
  (is (core/running? (state) "clerk")))

(deftest the-tick-wakes-roles-in-their-own-processes
  (let [a (memo! "clerk" "One")
        b (memo! "hub" "Two")]
    (is (= ["clerk" "hub"] (dispatch/tick! *sys*)))
    ;; The agent reads the memo before its wake process records the end,
    ;; so wait for both.
    (loop [n 0]
      (when (and (< n 150)
                 (or (some #(not= :read (:status (core/memo (state) %))) [a b])
                     (< (count (filter #(= :ended (:status %)) (vals (:wakes (state))))) 2)))
        (Thread/sleep 200)
        (recur (inc n))))
    (is (= [:read :read] (map #(:status (core/memo (state) %)) [a b])))
    (is (= 2 (count (filter #(= :ended (:status %)) (vals (:wakes (state)))))))))

(deftest two-wakes-of-one-role-at-once-are-refused
  (memo! "clerk" "x")
  (plan! (str "sleep 2\n" close-everything) 0)
  (let [procs (doall (for [_ (range 3)]
                       (p/process [relay-bin "wake" "clerk" "--data" (str *dir* "/data")]
                                  {:out :string :err :string})))
        results (map (comp str/trim :out deref) procs)]
    (is (= 1 (count (filter #(str/starts-with? % "ran") results))) (pr-str results))
    (is (= 2 (count (filter #(str/starts-with? % "busy") results))) (pr-str results))))

(deftest the-data-directory-comes-from-the-flag-or-the-config-file
  (let [file (str *dir* "/config.edn")
        resolve (fn [opts] (binding [cli/*config-file* file] (cli/data-dir opts)))
        home (System/getProperty "user.home")]
    (testing "neither: an error naming both options"
      (let [e (try (resolve {}) (catch clojure.lang.ExceptionInfo e e))]
        (is (str/includes? (ex-message e) "--data <dir>"))
        (is (str/includes? (ex-message e) file))))
    (spit file (pr-str {:data "~/relay-data"}))
    (testing "the config file, with ~ expanded"
      (is (= (str home "/relay-data") (resolve {}))))
    (testing "--data wins"
      (is (= "/elsewhere" (resolve {:data "/elsewhere"}))))
    (spit file (pr-str {:data "/abs/data"}))
    (is (= "/abs/data" (resolve {})))
    (testing "a config file that is not EDN is an error"
      (spit file "{:data \"/x\"")
      (is (thrown? clojure.lang.ExceptionInfo (resolve {}))))))

(deftest a-role-can-only-call-the-tools-it-is-offered
  (write-config! {:tools {"echo" {:class :read :description "Echo." :command ["echo" "said:"]}}
                  :grants {"hub" {:may-add true :tools ["echo"]}}})
  (let [run (fn [role tool args]
              (p/shell {:out :string :err :string :continue true :in (json/generate-string args)
                        }
                       relay-bin "tool" "--data" (str *dir* "/data") "--role" role tool))]
    (is (= "said: hi" (str/trim (:out (run "hub" "echo" {:args ["hi"]})))))
    (let [{:keys [exit out]} (run "clerk" "echo" {:args ["hi"]})]
      (is (= 1 exit))
      (is (str/includes? out "no tool called echo")))
    (testing "registry tools need the grant"
      (is (= 1 (:exit (run "clerk" "role_add" {:id "x" :title "X" :instructions "c"}))))
      (is (zero? (:exit (run "hub" "role_add" {:id "x" :title "X" :instructions "c"})))))
    (testing "relay tool without a role or a data directory fails clearly"
      (doseq [argv [[relay-bin "tool" "--data" (str *dir* "/data") "echo"]
                    [relay-bin "tool" "--role" "hub" "echo"]]]
        (let [{:keys [exit err]} (apply p/shell {:out :string :err :string :continue true :in "{}"} argv)]
          (is (= 1 exit))
          (is (str/includes? err "usage: relay tool --data <dir> --role <role>")))))))

(deftest knowledge-goes-through-the-tools-and-notes-reach-the-next-wake
  (let [run (fn [tool args]
              (p/shell {:out :string :err :string :continue true :in (json/generate-string args)
                        }
                       relay-bin "tool" "--data" (str *dir* "/data") "--role" "clerk" tool))]
    (is (str/starts-with? (:out (run "fact_add" {:text "The cabinet key is in the drawer."
                                                 :topic "office" :valid_from "2026-01-01"}))
                          "Recorded fact"))
    (is (str/includes? (:out (run "facts" {:topic "office"})) "The cabinet key is in the drawer."))
    (is (= 1 (:exit (run "fact_correct" {:fact 999 :why "x" :text "y"}))))
    (is (zero? (:exit (run "note_write" {:name "keys" :text "Ask before moving the key."}))))
    (let [state (state)
          request (dispatch/wake-request *sys* state {:id 1 :role "clerk" :memos [] :harness "fake"}
                                         (dispatch/wake-inputs *sys*))]
      (is (str/includes? (:system-prompt request) "## keys\n\nAsk before moving the key."))
      (is (some #{"fact_add" "doc_put" "journal_write"} (map :name (:tools request))))
      (testing "and a role is told what it is granted"
        (is (str/includes? (:system-prompt request) "relay's own tools and nothing beyond them"))
        (let [hub (dispatch/wake-request *sys* state {:id 1 :role "hub" :memos [] :harness "fake"}
                                             (dispatch/wake-inputs *sys*))]
          (is (str/includes? (:system-prompt hub) "`role_add`"))
          (is (str/includes? (:system-prompt hub) "comes to you as dead letters")))))))

;; --- a conversation

(defn- chat-wakes [] (filter :chat (vals (:wakes (state)))))

(deftest a-conversation-ends-with-what-its-runner-reported
  (let [id (memo! "clerk" "Untouched by a conversation")
        seen (atom nil)
        report (dispatch/with-conversation! *sys* "clerk"
                 (fn [request]
                   (reset! seen {:request request :running (core/running? (state) "clerk")})
                   {:exit 0 :sessions ["s-1"]}))
        [wake] (chat-wakes)]
    (is (= {:exit 0 :sessions ["s-1"]} report))
    (is (:running @seen) "the conversation holds the role while it runs")
    (is (= [:ended "ran" 0 ["s-1"] "Conversation ended."]
           ((juxt :status :outcome :exit :sessions :said) wake)))
    (is (not (core/running? (state) "clerk")))
    (is (zero? (:attempts (core/memo (state) id))))
    (testing "the request has no prompt, says it is a conversation, and keeps its sessions apart"
      (let [{:keys [request]} @seen]
        (is (not (contains? request :prompt)))
        (is (str/includes? (:system-prompt request) "The operator has opened a conversation with you."))
        (is (= (str (fs/path *dir* "data" "runs" "clerk" "conversations")) (:session-dir request)))
        (is (= [relay-bin "tool" "--data" (str (fs/absolutize (str *dir* "/data"))) "--role" "clerk"]
               (:tool-command request)))))
    (testing "building the request wrote nothing: the harness makes its own directories"
      (is (not (fs/exists? (fs/path *dir* "data" "runs" "clerk" (str (:id wake)))))))))

(deftest a-conversation-whose-runner-throws-still-ends-and-says-why
  (let [e (try (dispatch/with-conversation! *sys* "clerk" (fn [_] (throw (ex-info "the line dropped" {}))))
               nil
               (catch clojure.lang.ExceptionInfo e e))
        [wake] (chat-wakes)]
    (is (= "the line dropped" (ex-message e)))
    (is (= [:ended true "the line dropped"] ((juxt :status :crashed :said) wake)))
    (is (not (core/running? (state) "clerk")))))

(deftest a-conversation-with-no-such-harness-ends-before-it-runs-anything
  ;; The role names a harness that relay.edn has since lost.
  (inbound/submit! *sys* {:type :role/update :actor "operator" :args {:id "clerk" :harness "fake"}})
  (write-config! {:default-harness :other :harnesses {:other {:kind :script :command ["true"]}}})
  (let [called (atom false)
        e (try (dispatch/with-conversation! (system) "clerk" (fn [_] (reset! called true) {}))
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= "relay.edn has no harness called fake." (ex-message e)))
    (is (false? @called))
    (is (= [:ended true] ((juxt :status :crashed) (first (chat-wakes)))))))

(def gen-arg (gen/such-that #(not= "--" %) gen/string-alphanumeric))

(def chat-gives-the-client-relay-acp-as-its-agent
  (prop/for-all [client (gen/not-empty (gen/vector gen-arg 1 4))
                 relay gen-arg
                 role gen-arg
                 data (gen/one-of [(gen/return nil) gen-arg])]
    (let [argv (dispatch/chat-argv client relay role data)
          [before [_ & agent]] (split-with #(not= "--" %) argv)]
      (and (= client before)
           (= (concat [relay "acp" role] (when data ["--data" data])) agent)))))

(deftest chat-runs-the-client-with-relay-acp-after-the-double-dash
  (let [result (tc/quick-check 300 chat-gives-the-client-relay-acp-as-its-agent)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

(deftest chat-without-its-client-says-so-and-opens-no-conversation
  (write-config! {:chat-client [(str *dir* "/no-such-client")]})
  (let [err (java.io.StringWriter.)
        exit (binding [*err* err] (dispatch/chat! (system) "clerk" {}))]
    (is (= 1 exit))
    (is (str/includes? (str err) "no-such-client, and it is not installed") (str err))
    (is (empty? (chat-wakes)))))

(deftest the-chat-client-must-be-a-command
  (let [cfg (fn [c] (config/resolve-config (cond-> {:timezone "UTC" :default-harness :a :notify {:kind :none}
                                                    :harnesses {:a {:kind :script}}}
                                             (some? c) (assoc :chat-client c))))]
    (is (= ["acp-tui"] (:chat-client (cfg nil))) "acp-tui by default")
    (is (= ["/opt/bin/tui" "--agent-log" "/tmp/x"] (:chat-client (cfg ["/opt/bin/tui" "--agent-log" "/tmp/x"]))))
    (doseq [bad [[] "acp-tui" [""] [:acp-tui] ["acp-tui" nil] {}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":chat-client" (cfg bad)) (pr-str bad)))))

(deftest a-conversation-killed-earlier-is-reaped-before-the-next-one-starts
  (let [dead (p/process ["true"])
        _ @dead]
    (inbound/submit! *sys* {:type :wake/start :actor "operator"
                            :args {:role "clerk" :chat true :pid (.pid ^Process (:proc dead))
                                   :pid-start "1970-01-01T00:00:00Z"}})
    (dispatch/with-conversation! *sys* "clerk" (fn [_] {}))
    (is (= 2 (count (chat-wakes))))
    (is (every? #(= :ended (:status %)) (chat-wakes)))))

;; Whatever a runner reports, the conversation's end is one core accepts:
;; a conversation that started always ends.

(def gen-junk
  (gen/one-of [(gen/return nil) gen/string gen/small-integer gen/boolean gen/keyword
               (gen/vector gen/any-printable-equatable 0 3) (gen/return {}) (gen/return [])]))

(def gen-report
  (gen/one-of
   [gen-junk
    (gen/fmap (fn [[exit sessions said crashed extra]]
                (cond-> {} exit (assoc :exit exit) sessions (assoc :sessions sessions)
                        said (assoc :said said) crashed (assoc :crashed crashed) extra (assoc :extra extra)))
              (gen/tuple (gen/one-of [gen/small-integer (gen/return "timeout") gen-junk])
                         (gen/one-of [(gen/vector (gen/one-of [gen/string-alphanumeric gen-junk]) 0 4)
                                      gen-junk])
                         (gen/one-of [gen/string gen-junk])
                         gen-junk gen-junk))]))

(def conversation-end-is-always-accepted
  (let [cfg (config/resolve-config {:timezone "UTC" :default-harness :x :harnesses {:x {:kind :script}}
                                    :notify {:kind :none}})
        now (Instant/parse "2026-10-15T12:00:00Z")
        cmd (fn [state actor type args]
              (first (core/run-command state {:type type :actor actor :args args} {:now now :config cfg})))
        started (-> core/empty-state
                    (cmd "operator" :role/add {:id "clerk" :title "Clerk" :instructions "x"})
                    (cmd "operator" :wake/start {:role "clerk" :chat true}))
        wake-id (:id (core/running-wake started "clerk"))]
    (prop/for-all [report gen-report]
      (let [args (dispatch/conversation-end-args wake-id report)
            ended (get-in (cmd started "dispatcher" :wake/end args) [:wakes wake-id])
            sessions (:sessions report)
            kept (when (sequential? sessions) (filter #(and (string? %) (not (str/blank? %))) sessions))]
        (and (= :ended (:status ended))
             (= "ran" (:outcome ended))
             (string? (:said ended))
             (not (str/blank? (:said ended)))
             ;; the session ids it reported, in order, and nothing else
             (= (seq kept) (seq (:sessions ended))))))))

(deftest a-conversation-always-ends-whatever-its-runner-reported
  (let [result (tc/quick-check 500 conversation-end-is-always-accepted)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

(deftest a-wake-report-becomes-its-end
  (is (= {:wake 7 :outcome "ran" :exit "timeout" :crashed true :cut-short true :session "s"
          :said "x" :resets-at "2026-10-15T12:00:00Z" :transcript "/t/transcript.jsonl"}
         (dispatch/wake-end-args 7 {:outcome "ran" :exit "timeout" :crashed true :cut-short true
                                    :session "s" :said "x" :resets-at (Instant/parse "2026-10-15T12:00:00Z")
                                    :transcript (fs/path "/t/transcript.jsonl") :ignored 1}))))

;; A wake's request is a pure function of the log's state and what
;; wake-inputs read at the edge.

(def gen-instant
  (gen/fmap #(Instant/ofEpochSecond %) (gen/choose 1700000000 1900000000)))

(def a-wake-request-is-a-pure-value
  (let [cfg (config/resolve-config {:timezone "UTC" :default-harness :x :harnesses {:x {:kind :script}}
                                    :notify {:kind :none}})
        t0 (Instant/parse "2026-10-15T12:00:00Z")
        cmd (fn [state actor type args]
              (first (core/run-command state {:type type :actor actor :args args} {:now t0 :config cfg})))
        started (-> core/empty-state
                    (cmd "operator" :role/add {:id "clerk" :title "Clerk" :instructions "File things."})
                    (cmd "operator" :memo/send {:to "clerk" :subject "File this" :body "Body."})
                    (cmd "operator" :wake/start {:role "clerk"}))
        wake (core/running-wake started "clerk")
        data-dir (str (fs/path (fs/temp-dir) (str "relay-never-made-" (random-uuid))))
        sys {:data-dir data-dir :config cfg
             :clock #(throw (ex-info "wake-request read the clock" {}))}
        shown (fn [^Instant t]
                (.format (.atZone t (java.time.ZoneId/of "UTC"))
                         (java.time.format.DateTimeFormatter/ofPattern "EEE d MMM yyyy, HH:mm")))]
    (prop/for-all [shared gen/string-alphanumeric
                   additions (gen/one-of [(gen/return nil) gen/string-alphanumeric])
                   now gen-instant
                   last-at gen-instant
                   relay-bin gen-arg
                   data-path gen-arg]
      (let [state (assoc started :last-at last-at)
            inputs {:shared shared :operator-additions additions :now now
                    :relay-bin relay-bin :data-path data-path}
            request (dispatch/wake-request sys state wake inputs)
            later (if (.isBefore ^Instant now last-at) last-at now)]
        (and (= request (dispatch/wake-request sys state wake inputs))
             (str/includes? (:prompt request) (str "It is " (shown later) "."))
             (= [relay-bin "tool" "--data" data-path "--role" "clerk"] (:tool-command request))
             (not (fs/exists? data-dir)))))))

(deftest a-wake-request-depends-only-on-its-arguments-and-never-goes-back-in-time
  (let [result (tc/quick-check 300 a-wake-request-is-a-pure-value)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

;; Whatever the script harness's program prints, exits with or writes as
;; its report, the wake's end is one core accepts: a wake that started
;; always ends.

(def gen-report-field
  (gen/one-of [gen-junk (gen/elements ["ran" "limited" "unreachable" "needs-attention" "bogus"])
               (gen/elements ["2099-01-01T00:00:00Z" "soon" ""])]))

(def gen-report-text
  (gen/one-of
   [(gen/return nil) gen/string (gen/return "{") (gen/return "[]") (gen/return "null")
    (gen/fmap json/generate-string
              (gen/map (gen/elements [:outcome :session :said :resets_at :resets-at :other])
                       gen-report-field))]))

(def a-script-wake-always-ends
  (let [cfg (config/resolve-config {:timezone "UTC" :default-harness :x :harnesses {:x {:kind :script}}
                                    :notify {:kind :none}})
        now (Instant/parse "2026-10-15T12:00:00Z")
        cmd (fn [state actor type args]
              (first (core/run-command state {:type type :actor actor :args args} {:now now :config cfg})))
        started (-> core/empty-state
                    (cmd "operator" :role/add {:id "clerk" :title "Clerk" :instructions "x"})
                    (cmd "operator" :memo/send {:to "clerk" :subject "s" :body "b"})
                    (cmd "operator" :wake/start {:role "clerk"}))
        wake-id (:id (core/running-wake started "clerk"))]
    (prop/for-all [exit (gen/one-of [gen/small-integer (gen/return "timeout")])
                   out gen/string
                   err gen/string
                   report gen-report-text]
      (let [r (harness/wake-report {:harness {:kind :script}} {}
                                   {:exit exit :out out :err err :report report :now now})
            ended (get-in (cmd started "dispatcher" :wake/end (dispatch/wake-end-args wake-id r))
                          [:wakes wake-id])]
        (and (= :ended (:status ended))
             (string? (:said r)))))))

(deftest a-script-wake-ends-whatever-its-program-reports
  (let [result (tc/quick-check 1000 a-script-wake-always-ends)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

(deftest a-script-that-reports-an-unknown-outcome-still-ends-its-wake
  ;; Regression: core refused the report, so the wake stayed running.
  (memo! "clerk" "x")
  (plan! (report! {:outcome "bogus" :said "all good"}) 0)
  (let [result (dispatch/wake! *sys* "clerk" {})
        wake (get-in (state) [:wakes (:wake result)])]
    (is (= [:ended "ran" true] ((juxt :status :outcome :crashed) wake)))
    (is (str/includes? (:said wake) "\"bogus\""))
    (is (not (core/running? (state) "clerk")))))
