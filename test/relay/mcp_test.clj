(ns relay.mcp-test
  "Tools from MCP servers: relay.edn names the servers and the tools taken
  from them, relay checks both, the agentloop request carries exactly the
  servers and tools a role is granted, and a run whose tools could not be
  set up is tried again without pausing its harness. When a tool's
  definition changes under a session, relay takes the new one, tells the
  operator, and the next session starts with it."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.config :as config]
            [relay.core :as core]
            [relay.test-support :as support]
            [relay.dispatch :as dispatch]
            [relay.harness.agentloop :as agentloop]
            [relay.render :as render]
            [relay.tools :as tools])
  (:import (java.time Instant ZoneId)))

(def digest (apply str (repeat 64 "a")))

(def settings
  {:timezone "UTC"
   :default-harness :agentloop
   :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}}
   :mcp-servers {"files" {:command ["/usr/local/bin/files-mcp" "--token-file" "{credentials_file}"]
                          :credentials-command ["cat" "/secret/files-token"]}
                 "search" {:command ["/usr/local/bin/search-mcp"] :send-labels true}}
   :tools {"file_read" {:protocol :mcp-stdio :server "files" :tool "read" :class :read :labels ["private"]}
           "file_write" {:protocol :mcp-stdio :server "files" :tool "write" :class :write :labels []
                         :digest digest}
           "search" {:protocol :mcp-stdio :server "search" :tool "query" :class :read :labels ["contaminated"]
                     :allow-content ["image"]}
           "fetch" {:class :read :labels ["contaminated"] :description "Fetch." :command ["true"]}}
   :grants {"hub" {:tools ["file_read" "file_write" "search" "fetch"]}
            "clerk" {:tools ["fetch"]}
            "scout" {:public true :tools ["search"]}}})

(defn- problem [f]
  (try (config/resolve-config (f settings)) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

;; --- relay.edn

(deftest relay-checks-mcp-servers-and-tools-as-agentloop-would
  (is (nil? (problem identity)))
  (doseq [[why f expected]
          [["an unknown server" #(assoc-in % [:tools "search" :server] "nope") "no MCP server \"nope\""]
           ["no server" #(update % :tools update "search" dissoc :server) "needs :server"]
           ["no remote name" #(update % :tools update "search" dissoc :tool) "needs :tool"]
           ["a blank remote name" #(assoc-in % [:tools "search" :tool] " ") "needs :tool"]
           ["a server no tool uses" #(assoc-in % [:mcp-servers "spare"] {:command ["x"]}) "gives none of :tools"]
           ["one remote tool under two names"
            #(-> % (assoc-in [:tools "file_read2"] (get-in % [:tools "file_read"])))
            "both the tool \"read\""]
           ["a description" #(assoc-in % [:tools "search" :description] "d") "cannot have :description"]
           ["parameters" #(assoc-in % [:tools "search" :parameters] {:type "object"}) "cannot have :parameters"]
           ["a command" #(assoc-in % [:tools "search" :command] ["x"]) "cannot have :command"]
           ["a timeout of its own" #(assoc-in % [:tools "search" :timeout-seconds] 5) ":tool-timeout-seconds"]
           ["a bad digest" #(assoc-in % [:tools "file_write" :digest] "ABC") "SHA-256 in lowercase hex"]
           ["an upper case digest" #(assoc-in % [:tools "file_write" :digest] (str/upper-case digest)) "lowercase hex"]
           ["unknown content" #(assoc-in % [:tools "search" :allow-content] ["video"]) ":allow-content"]
           ["repeated content" #(assoc-in % [:tools "search" :allow-content] ["image" "image"]) ":allow-content"]
           ["a name agentloop refuses" #(-> % (assoc-in [:tools "file.read"] (get-in % [:tools "file_read"]))
                                            (update :tools dissoc "file_read")
                                            (assoc-in [:grants "hub" :tools] ["file.read" "file_write" "search"]))
            "1 to 64 letters"]
           ["server fields on a command tool" #(assoc-in % [:tools "fetch" :server] "files") "only a tool with :protocol :mcp-stdio"]
           ["a bad server name" #(-> % (assoc-in [:mcp-servers "a b"] {:command ["x"]})
                                     (assoc-in [:tools "search" :server] "a b"))
            "not a server name"]
           ["a server with no command" #(assoc-in % [:mcp-servers "search"] {:send-labels true}) "needs a :command"]
           ["an empty command" #(assoc-in % [:mcp-servers "search" :command] []) "needs a :command"]
           ["a blank program" #(assoc-in % [:mcp-servers "search" :command] [""]) "needs a :command"]
           ["a NUL" #(assoc-in % [:mcp-servers "search" :command] ["x" "a\u0000b"]) "needs a :command"]
           ["an unknown server key" #(assoc-in % [:mcp-servers "search" :env] {"K" "v"}) "nothing else"]
           ["send-labels not a boolean" #(assoc-in % [:mcp-servers "search" :send-labels] "yes") ":send-labels"]
           ["the placeholder as the program" #(assoc-in % [:mcp-servers "files" :command] ["{credentials_file}"])
            "cannot run {credentials_file}"]
           ["the placeholder with no credentials command"
            #(update-in % [:mcp-servers "files"] dissoc :credentials-command) "no :credentials-command"]
           ["a credentials command with no placeholder"
            #(assoc-in % [:mcp-servers "files" :command] ["/usr/local/bin/files-mcp"]) "no argument of its :command holds"]
           ["a credentials command that is not one"
            #(assoc-in % [:mcp-servers "files" :credentials-command] "cat /secret") "not a command"]
           ["a public role granted a private MCP tool"
            #(assoc-in % [:grants "scout" :tools] ["file_read"]) "whose output is private"]
           ["an MCP tool with no labels" #(update % :tools update "search" dissoc :labels) "needs :labels"]
           [":mcp-servers not a map" #(assoc % :mcp-servers [["files"]]) ":mcp-servers is a map"]]]
    (testing why
      (is (str/includes? (str (problem f)) expected) (str why ": " (problem f)))))
  (testing "the placeholder may sit inside an argument"
    (is (nil? (problem #(assoc-in % [:mcp-servers "files" :command] ["files-mcp" "--token-file={credentials_file}"])))))
  (testing "nor may it hold its calls"
    (is (str/includes? (str (problem #(assoc-in % [:tools "search" :always-hold] true))) "no longer has")))
  (testing "no MCP servers at all is the default"
    (is (= {} (:mcp-servers (config/resolve-config (-> settings (dissoc :mcp-servers)
                                                       (update :tools select-keys ["fetch"])
                                                       (assoc :grants {"clerk" {:tools ["fetch"]}}))))))))

;; --- the request

(def secret "s3cr3t-token-7f1e")

(defn- request-for
  "The agentloop request a wake of `role` writes, under `cfg`, as JSON read
  back, and the JSON text."
  [cfg role]
  (let [t0 (Instant/parse "2026-10-15T12:00:00Z")
        public (config/public? cfg role)
        cmd (fn [state actor type args]
              (first (core/run-command state {:type type :actor actor :args args :public public}
                                       {:now t0 :config cfg})))
        state (-> core/empty-state
                  (cmd "operator" :role/add {:id role :title "R" :instructions "Work."})
                  (cmd "operator" :memo/send {:to role :subject "Go" :body "Go."})
                  (cmd "operator" :wake/start {:role role}))
        sys {:data-dir (str (fs/path (fs/temp-dir) (str "relay-never-made-" (random-uuid)))) :config cfg}
        inputs {:shared "" :now t0 :relay-bin "relay" :data-path "/d"}
        request (dispatch/wake-request sys state (support/calling-wake state role) inputs)
        text (json/generate-string (agentloop/request-json request "sid" "/w"))]
    [(json/parse-string text) text]))

(def gen-server-name (gen/elements ["alpha" "beta" "gamma"]))

(def gen-settings
  "relay.edn with some MCP servers, a tool or two from each, and a grant of
  any subset of them (and of a command tool) to role \"r\"."
  (gen/let [tools (gen/vector (gen/tuple gen-server-name (gen/elements ["one" "two"]) gen/boolean
                                         (gen/elements [nil digest]))
                              1 6)
            tool-timeout (gen/elements [nil 30 900])
            grant-picks (gen/vector gen/boolean 7)]
    (let [tools (vec (vals (into {} (map (fn [[s t _ _ :as x]] [[s t] x]) tools))))
          mcp-tools (into {} (for [[s t send d] tools]
                               [(str s "_" t) (cond-> {:protocol :mcp-stdio :server s :tool t :class :read :labels []}
                                                d (assoc :digest d))]))
          servers (into {} (for [[s _ send] tools]
                             [s (cond-> {:command [(str "/bin/" s) "--token" "{credentials_file}"]
                                         :credentials-command ["cat" (str "/secret/" s)]}
                                  send (assoc :send-labels true))]))
          all (into ["plain"] (sort (keys mcp-tools)))
          granted (vec (keep-indexed (fn [i n] (when (nth grant-picks i false) n)) all))]
      (cond-> {:timezone "UTC" :default-harness :agentloop
               :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}
               :mcp-servers servers
               :tools (assoc mcp-tools "plain" {:class :read :labels [] :description "d" :command ["true"]})
               :grants {"r" {:tools granted}}}
        tool-timeout (assoc-in [:harnesses :agentloop :tool-timeout-seconds] tool-timeout)))))

(def the-request-has-exactly-the-granted-mcp-tools-and-their-servers
  (prop/for-all [settings gen-settings]
    (let [cfg (config/resolve-config settings)
          granted (set (get-in settings [:grants "r" :tools]))
          mcp (into {} (filter #(and (granted (key %)) (config/mcp-tool? (val %)))) (:tools settings))
          [req text] (request-for cfg "r")
          listed (into {} (keep #(when (= "mcp-stdio" (get % "protocol")) [(get % "name") %])) (get req "tools"))]
      (and (= (set (keys mcp)) (set (keys listed)))
           (every? (fn [[n {:keys [server tool digest]}]]
                     (= (cond-> {"name" n "protocol" "mcp-stdio" "server" server "tool" tool}
                          digest (assoc "digest" digest))
                        (get listed n)))
                   mcp)
           ;; Exactly the servers those tools come from, each as relay.edn
           ;; gives it, and the policy names each tool.
           (= (set (map :server (vals mcp))) (set (keys (get req "mcp_servers"))))
           (every? (fn [[s m]]
                     (let [given (get-in settings [:mcp-servers s])]
                       (= (cond-> {"command" (:command given) "credentials_command" (:credentials-command given)}
                            (:send-labels given) (assoc "send_labels" true))
                          m)))
                   (get req "mcp_servers"))
           ;; The policy gives labels only, and only for tools that have
           ;; some: these have none.
           (empty? (get-in req ["policy" "tools"]))
           (or (seq mcp) (not (contains? req "mcp_servers")))
           ;; Always the harness's timeout, never agentloop's default.
           (= (or (get-in settings [:harnesses :agentloop :tool-timeout-seconds])
                  agentloop/default-tool-timeout-seconds)
              (get req "tool_timeout_seconds"))
           (not (str/includes? text secret))))))

(deftest a-request-carries-exactly-the-granted-mcp-tools-and-their-servers
  (let [result (tc/quick-check 200 the-request-has-exactly-the-granted-mcp-tools-and-their-servers)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

(deftest the-request-for-the-example-settings
  (let [cfg (config/resolve-config settings)
        [hub] (request-for cfg "hub")
        [clerk] (request-for cfg "clerk")
        [scout] (request-for cfg "scout")]
    (is (= {"files" {"command" ["/usr/local/bin/files-mcp" "--token-file" "{credentials_file}"]
                     "credentials_command" ["cat" "/secret/files-token"]}
            "search" {"command" ["/usr/local/bin/search-mcp"] "send_labels" true}}
           (get hub "mcp_servers")))
    (is (= [{"name" "file_read" "protocol" "mcp-stdio" "server" "files" "tool" "read"}
            {"name" "file_write" "protocol" "mcp-stdio" "server" "files" "tool" "write" "digest" digest}
            {"name" "search" "protocol" "mcp-stdio" "server" "search" "tool" "query" "allow_content" ["image"]}]
           (filterv #(get % "protocol") (get hub "tools"))))
    (is (nil? (get-in hub ["policy" "tools" "file_write"])) "no labels, so no entry")
    (is (= {"labels" ["private"]} (get-in hub ["policy" "tools" "file_read"])))
    (is (= 300 (get hub "tool_timeout_seconds")))
    (is (not (contains? clerk "mcp_servers")) "a role granted no MCP tool starts no server")
    (is (= ["search"] (keys (get scout "mcp_servers"))) "only the servers of the role's own tools")
    (testing "a call to an MCP tool through relay's tool command is refused: agentloop runs it"
      (is (= ["file_read" "file_write" "search"]
             (sort (keep #(when (:mcp %) (:name %)) (tools/manifest cfg "hub"))))))))

;; --- how a run ended

(def pinned (apply str (repeat 64 "1")))
(def got (apply str (repeat 64 "2")))

(deftest tools-unavailable-keeps-the-labels
  (let [run #(agentloop/classify {:outcome % :exit 0 :session "s1" :now (Instant/parse "2026-10-15T12:00:00Z")})]
    (testing "a server that did not start"
      (let [r (run {:type "outcome" :reason "tools_unavailable" :labels ["private"]
                    :tools_error {:server "files" :message "The MCP server files did not start: exit status 1"}})]
        (is (= "needs-attention" (:outcome r)))
        (is (false? (:crashed r)))
        (is (= ["private"] (:labels r)))
        (is (= {:server "files" :message "The MCP server files did not start: exit status 1"} (:tools-error r)))
        (is (= (get-in r [:tools-error :message]) (:said r)))))
    (testing "a changed definition"
      (is (= {:server "files" :tool "file_read" :pinned pinned :got got :message "changed"}
             (:tools-error (run {:type "outcome" :reason "tools_unavailable" :labels []
                                 :tools_error {:server "files" :tool "file_read" :pinned pinned :got got
                                               :message "changed"}})))))
    (testing "the message is the server's text: cut short, links broken"
      (let [long-msg (str "see https://evil.example/x " (apply str (repeat 400 "é")))
            m (get-in (run {:type "outcome" :reason "tools_unavailable"
                            :tools_error {:message long-msg}}) [:tools-error :message])]
        (is (str/starts-with? m "see hxxps://evil[.]example/x"))
        (is (<= (.codePointCount ^String m 0 (count m)) (+ agentloop/tools-message-limit 3)))
        (is (str/ends-with? m "..."))))
    (testing "fields of the wrong shape are dropped, and no message still needs the operator"
      (let [r (run {:type "outcome" :reason "tools_unavailable"
                    :tools_error {:server "a b" :tool 7 :pinned "xyz" :got got :message ""}})]
        (is (= "needs-attention" (:outcome r)))
        (is (= {:message "agentloop could not set up the role's MCP tools."} (:tools-error r))))
      (is (= "needs-attention" (:outcome (run {:type "outcome" :reason "tools_unavailable"})))))))

;; --- what relay does about it

(def t0 (Instant/parse "2026-10-15T12:00:00Z"))

(defn- world []
  {:state core/empty-state :config (config/resolve-config settings) :events []})

(defn- run
  ([w actor type args] (run w actor type args t0))
  ([w actor type args now]
   (let [[state events] (core/run-command (:state w) {:type type :actor actor :args args}
                                          {:now now :config (:config w)})]
     (assoc w :state state :events (into (:events w) events)))))

(defn- start [w now]
  (run w "dispatcher" :wake/start {:role "hub" :dispatched true} now))

(defn- end [w args now]
  (run w "dispatcher" :wake/end (merge {:wake (:id (support/calling-wake (:state w) "hub")) :exit 0} args) now))

(defn- notifications [w kind]
  (filter #(= kind (:origin %)) (vals (:messages (:state w)))))

(defn- setup []
  (-> (world)
      (run "operator" :role/add {:id "hub" :title "Hub" :instructions "Route."})
      (run "operator" :memo/send {:to "hub" :subject "Look" :body "Look."})))

(deftest a-changed-definition-is-taken-and-the-next-session-starts-with-it
  (let [w (-> (setup) (start t0)
              (end {:outcome "ran" :session "s1" :cut-short true :pins {"file_read" pinned}} t0)
              (run "operator" :wake/start {:role "hub"} t0))
        _ (is (= "s1" (:session (get-in (:state w) [:wakes (:resumes (support/calling-wake (:state w) "hub"))]))))
        w (end w {:outcome "needs-attention" :session "s1" :labels ["private"]
                  :tools-error {:server "files" :tool "file_read" :pinned pinned :got got
                                :message "The definition of the tool file_read is not the one this session pinned."}}
               t0)]
    (testing "the harness is not paused, and nothing about it is said"
      (is (nil? (core/paused-until (:state w) "agentloop" t0)))
      (is (empty? (notifications w "harness-needs-attention"))))
    (testing "the new definition is pinned, for every role granted it"
      (is (= got (core/tool-pin (:state w) "file_read"))))
    (testing "the operator is told once, plainly"
      (let [[n & more] (notifications w "tool-changed")]
        (is (empty? more))
        (is (str/includes? (support/message-text n) "relay uses the new"))
        (is (not (re-find #"(?i)relay fresh|label|hold" (support/message-text n))))
        (is (= ["private"] (:labels n)) "with the session's labels")))
    (testing "the memo is not counted as a failed attempt: only the cut-short wake counted"
      (is (= 1 (:attempts (first (core/unread-memos (:state w) "hub"))))))
    (testing "nothing waits: the next wake starts a fresh session with the new definition"
      (is (not (core/waiting-on-tools? (:state w) "hub" t0)))
      (is (= ["hub"] (core/roles-to-wake (:state w) (:config w) (.plusSeconds t0 3600))) "once its retry delay is up")
      (let [w (start w (.plusSeconds t0 3600))
            inputs {:shared "" :now t0 :relay-bin "relay" :data-path "/d"}
            req (dispatch/wake-request {:data-dir "/nonexistent" :config (:config w)} (:state w)
                                       (support/calling-wake (:state w) "hub") inputs)]
        (is (nil? (:resumes (support/calling-wake (:state w) "hub"))) "a fresh session")
        (is (= got (get-in (first (filter #(= "file_read" (:name %)) (:tools req))) [:mcp :digest])))))
    (testing "status shows no block"
      (is (not-any? #(str/includes? % "tools unavailable") (render/status (:state w) (ZoneId/of "UTC") t0 (:config w)))))))

(deftest a-definition-that-changes-on-every-connect-backs-off-and-is-told-once
  ;; A server whose description carries a timestamp or a counter differs
  ;; every time. relay takes the first change at once; a second in a row
  ;; waits, doubling, and nothing more is said.
  (let [d (fn [i] (apply str (repeat 64 (str (mod i 10)))))
        changed (fn [w i now]
                  (end w {:outcome "needs-attention" :session (str "s" i)
                          :tools-error {:server "files" :tool "file_read" :pinned (d i) :got (d (inc i))
                                        :message "The definition of the tool file_read changed."}}
                       now))
        w (-> (setup) (start t0) (changed 1 t0))
        _ (testing "the first change is taken at once"
            (is (= (d 2) (core/tool-pin (:state w) "file_read")))
            (is (not (core/waiting-on-tools? (:state w) "hub" t0))))
        t1 (.plusSeconds t0 60)
        w (-> w (start t1) (changed 2 t1))
        retry (get-in (:state w) [:roles "hub" :tools-unavailable :retry-at])]
    (testing "a second change in a row is taken, but the role waits"
      (is (= (d 3) (core/tool-pin (:state w) "file_read")) "so a server that settles works at the retry")
      (is (= (.plusSeconds t1 60) retry))
      (is (core/waiting-on-tools? (:state w) "hub" t1))
      (is (= [] (core/roles-to-wake (:state w) (:config w) t1))))
    (let [w (reduce (fn [w i]
                      (let [now (get-in (:state w) [:roles "hub" :tools-unavailable :retry-at])]
                        (-> w (start now) (changed i now))))
                    w (range 3 8))]
      (testing "the waits double"
        (is (= (.plusSeconds (get-in (:state w) [:roles "hub" :tools-unavailable :since])
                             (config/retry-delay-seconds (:config w) 6))
               (get-in (:state w) [:roles "hub" :tools-unavailable :retry-at]))))
      (testing "the operator is told once in all"
        (is (= 1 (count (notifications w "tool-changed"))))
        (is (empty? (notifications w "tools-unavailable"))))
      (testing "the memo is still there, not counted as failed"
        (is (= 0 (:attempts (first (core/unread-memos (:state w) "hub"))))))
      (testing "a wake that runs ends the streak: the next change is taken at once again"
        (let [now (get-in (:state w) [:roles "hub" :tools-unavailable :retry-at])
              w (-> w (start now) (end {:outcome "ran" :session "s9"} now)
                    (run "operator" :memo/send {:to "hub" :subject "Again" :body "Again."} now)
                    (start now) (changed 8 now))]
          (is (not (core/waiting-on-tools? (:state w) "hub" now)))
          (is (= 2 (count (notifications w "tool-changed")))))))))

(deftest a-server-that-did-not-start-is-tried-again-later-in-the-same-session
  (let [w (-> (setup) (start t0)
              (end {:outcome "needs-attention" :session "s1"
                    :tools-error {:server "files" :message "The MCP server files did not start: boom"}} t0))
        retry (get-in (:state w) [:roles "hub" :tools-unavailable :retry-at])
        n (first (notifications w "tools-unavailable"))]
    (is (= (.plusSeconds t0 60) retry) "after the first retry delay")
    (is (str/includes? (support/message-text n) "did not start: boom"))
    (is (str/includes? (support/message-text n) "relay wake hub"))
    (is (not (re-find #"(?i)relay fresh|label|hold" (support/message-text n))))
    (is (= [] (core/roles-to-wake (:state w) (:config w) t0)))
    (is (= ["hub"] (core/roles-to-wake (:state w) (:config w) retry)))
    (let [w (start w retry)
          _ (is (= "s1" (:session (get-in (:state w) [:wakes (:resumes (support/calling-wake (:state w) "hub"))])))
                "no new session is needed")
          w2 (end w {:outcome "needs-attention" :session "s1"
                     :tools-error {:server "files" :message "still down"}} retry)]
      (testing "a second failure waits twice as long and is not told again"
        (is (= (.plusSeconds retry 120) (get-in (:state w2) [:roles "hub" :tools-unavailable :retry-at])))
        (is (= 1 (count (notifications w2 "tools-unavailable")))))
      (testing "a wake that ran clears it"
        (let [w3 (end w {:outcome "ran" :session "s1"} retry)]
          (is (nil? (get-in (:state w3) [:roles "hub" :tools-unavailable]))))))))

(deftest a-definition-relay-edn-pins-is-a-config-change-not-a-fresh-session
  (let [w (-> (setup) (start t0)
              (end {:outcome "needs-attention" :session "s1"
                    :tools-error {:server "files" :tool "file_write" :pinned digest :got got
                                  :message "not the one the request pins"}} t0))
        n (first (notifications w "tools-unavailable"))]
    (is (str/includes? (support/message-text n) (str "set :digest to " got)))
    (is (some? (get-in (:state w) [:roles "hub" :tools-unavailable :retry-at])) "tried again after a wait")))

(deftest an-outcome-without-tools-error-still-pauses-the-harness
  (let [w (-> (setup) (start t0) (end {:outcome "needs-attention" :session "s1" :said "bad key"} t0))]
    (is (some? (core/paused-until (:state w) "agentloop" t0)))
    (is (nil? (get-in (:state w) [:roles "hub" :tools-unavailable])))))

;; --- review fixes

(deftest a-conversation-never-clears-a-block
  (let [w (-> (setup) (start t0)
              (end {:outcome "needs-attention" :session "s1"
                    :tools-error {:server "files" :message "did not start"}} t0))
        later (.plusSeconds t0 600)
        w (run w "operator" :wake/start {:role "hub" :chat true} later)
        chat (support/calling-wake (:state w) "hub")
        w (run w "dispatcher" :wake/end {:wake (:id chat) :outcome "ran" :sessions ["c1"]} later)]
    (is (some? (get-in (:state w) [:roles "hub" :tools-unavailable])) "a conversation that ran clears nothing")))

(defn- pins-line [m]
  (json/generate-string {:type "pins" :pins {:pins m}}))

(deftest relay-remembers-each-mcp-tools-first-definition
  (testing "agentloop's pins entries are read from the run's output"
    (is (= {"file_read" pinned}
           (agentloop/parse-pins (str/join "\n" ["{\"type\":\"run_started\"}" (pins-line {"file_read" pinned})
                                                (pins-line {"bad name" pinned "x" "not-hex"})
                                                "{\"type\":\"outcome\",\"reason\":\"completed\"}"])))))
  (let [w (-> (setup) (start t0)
              (end {:outcome "ran" :session "s1" :pins {"file_read" pinned "fetch" got "nope" got}} t0))]
    (testing "first use records the digest, for MCP tools only"
      (is (= pinned (core/tool-pin (:state w) "file_read")))
      (is (nil? (core/tool-pin (:state w) "fetch")))
      (is (nil? (core/tool-pin (:state w) "nope"))))
    (testing "a later session does not move it"
      (let [w (-> w (run "operator" :memo/send {:to "hub" :subject "Again" :body "Again."})
                  (start t0) (end {:outcome "ran" :session "s2" :pins {"file_read" got}} t0))]
        (is (= pinned (core/tool-pin (:state w) "file_read")))))
    (testing "every later request, a conversation's too, names it; relay.edn's :digest wins"
      (let [inputs {:shared "" :now t0 :relay-bin "relay" :data-path "/d"}
            sys {:data-dir "/nonexistent" :config (:config w)}
            w2 (run w "operator" :memo/send {:to "hub" :subject "Again" :body "Again."})
            w2 (start w2 t0)
            req (dispatch/wake-request sys (:state w2) (support/calling-wake (:state w2) "hub") inputs)
            chat (-> w (run "operator" :wake/start {:role "hub" :chat true}))
            creq (dispatch/wake-request sys (:state chat) (support/calling-wake (:state chat) "hub") inputs)
            digest-of (fn [r n] (get-in (first (filter #(= n (:name %)) (:tools r))) [:mcp :digest]))]
        (is (= pinned (digest-of req "file_read")))
        (is (= pinned (digest-of creq "file_read")))
        (is (= digest (digest-of req "file_write")))
        (is (nil? (digest-of req "search")) "a tool relay has not seen yet")
        (is (= pinned (get (first (filter #(= "file_read" (get % "name"))
                                          (get (json/parse-string (json/generate-string (agentloop/request-json req "sid" "/w"))) "tools")))
                           "digest")))))
    (testing "a drift in a new session takes the new definition for every role, told once"
      (let [w (-> w (run "operator" :role/add {:id "clerk" :title "C" :instructions "x"})
                  (run "operator" :memo/send {:to "hub" :subject "Again" :body "Again."})
                  (start t0)
                  (end {:outcome "needs-attention" :session "s3"
                        :tools-error {:server "files" :tool "file_read" :pinned pinned :got got
                                      :message "The MCP server files gives a definition of the tool file_read that is not the one the request pins"}}
                       t0))]
        (is (= got (core/tool-pin (:state w) "file_read")))
        (is (not (core/waiting-on-tools? (:state w) "hub" t0)))
        (testing "another role's old session stopped by the same change takes it quietly"
          (let [w (-> w (run "operator" :memo/send {:to "clerk" :subject "Go" :body "Go."})
                      (run "dispatcher" :wake/start {:role "clerk" :dispatched true}))
                cw (support/calling-wake (:state w) "clerk")
                w (run w "dispatcher" :wake/end {:wake (:id cw) :exit 0 :outcome "needs-attention" :session "c1"
                                                 :tools-error {:tool "file_read" :pinned pinned :got got :message "changed"}})]
            (is (not (core/waiting-on-tools? (:state w) "clerk" t0)))
            (is (nil? (:interrupted-wake (core/role (:state w) "clerk"))))
            (is (= 1 (count (notifications w "tool-changed"))))))))))

(deftest the-tools-message-is-one-line-and-status-never-shows-a-past-retry
  (is (= "a b c" (:message (agentloop/tools-error {:message "a\n\nb\r\n c"}))))
  (let [w (-> (setup) (start t0)
              (end {:outcome "needs-attention" :session "s1" :tools-error {:server "files" :message "down"}} t0))
        lines (fn [now] (render/status (:state w) (ZoneId/of "UTC") now (:config w)))]
    (is (some #(str/includes? % "next try") (lines t0)))
    (is (not-any? #(str/includes? % "next try") (lines (.plusSeconds t0 3600))))
    (is (some #(str/includes? % "tried again at the next tick") (lines (.plusSeconds t0 3600))))))
