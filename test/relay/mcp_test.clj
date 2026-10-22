(ns relay.mcp-test
  "Tools from MCP servers: relay.edn names the servers and the tools taken
  from them, relay checks both, the agentloop request carries exactly the
  servers and tools a role is granted, and a run whose tools could not be
  set up waits for the operator without pausing its harness. A tool whose
  definition changed under a session waits for relay fresh."
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
   :notify {:kind :none}
   :mcp-servers {"files" {:command ["/usr/local/bin/files-mcp" "--token-file" "{credentials_file}"]
                          :credentials-command ["cat" "/secret/files-token"]}
                 "search" {:command ["/usr/local/bin/search-mcp"] :send-labels true}}
   :tools {"file_read" {:protocol :mcp-stdio :server "files" :tool "read" :class :read :labels ["private"]}
           "file_write" {:protocol :mcp-stdio :server "files" :tool "write" :class :write :labels []
                         :always-hold true :digest digest}
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
  (testing "an MCP tool may be read through the quarantined reader and may always hold"
    (is (nil? (problem #(assoc-in % [:grants "hub" :quarantine] ["search"]))))
    (is (nil? (problem #(assoc-in % [:tools "search" :always-hold] true)))))
  (testing "the brief does not run an MCP tool"
    (is (= {:unusable "file_read" :why "comes from an MCP server, which only agentloop runs"}
           ((requiring-resolve 'relay.brief/source)
            (config/resolve-config (-> settings
                                       (assoc :brief {:at "07:30" :calendar "file_read"})))
            :calendar))))
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
               :notify {:kind :none}
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
           (every? #(contains? (get-in req ["policy" "tools"]) %) (keys mcp))
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
    (is (= {"class" "write" "always_hold" true} (get-in hub ["policy" "tools" "file_write"])))
    (is (= {"class" "read" "labels" ["private"]} (get-in hub ["policy" "tools" "file_read"])))
    (is (= 300 (get hub "tool_timeout_seconds")))
    (is (not (contains? clerk "mcp_servers")) "a role granted no MCP tool starts no server")
    (is (= ["search"] (keys (get scout "mcp_servers"))) "only the servers of the role's own tools")
    (testing "a call to an MCP tool through relay's tool command is refused: agentloop runs it"
      (is (= ["file_read" "file_write" "search"]
             (sort (keep #(when (:mcp %) (:name %)) (tools/manifest cfg "hub"))))))))

;; --- how a run ended

(def pinned (apply str (repeat 64 "1")))
(def got (apply str (repeat 64 "2")))

(deftest tools-unavailable-needs-the-operator-and-keeps-the-labels
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

(defn- fresh
  "relay fresh as the command line sends it: with what it showed."
  ([w role] (fresh w role t0))
  ([w role now]
   (run w "operator" :role/fresh {:role role :expect (core/fresh-expectation (:state w) role)} now)))

(defn- notifications [w kind]
  (filter #(= kind (:kind %)) (vals (:notifications (:state w)))))

(defn- setup []
  (-> (world)
      (run "operator" :role/add {:id "hub" :title "Hub" :instructions "Route."})
      (run "operator" :memo/send {:to "hub" :subject "Look" :body "Look."})))

(defn- rejection [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e [(core/rejection e) (ex-message e)])))

(deftest a-changed-definition-waits-for-relay-fresh
  (let [w (-> (setup) (start t0)
              (end {:outcome "ran" :session "s1" :cut-short true} t0)
              (run "operator" :wake/start {:role "hub"} t0))
        _ (is (= "s1" (:session (get-in (:state w) [:wakes (:resumes (support/calling-wake (:state w) "hub"))]))))
        w (end w {:outcome "needs-attention" :session "s1" :labels ["private"]
                  :tools-error {:server "files" :tool "file_read" :pinned pinned :got got
                                :message "The definition of the tool file_read is not the one this session pinned."}}
               t0)
        later (.plusSeconds t0 (* 3600 24 30))]
    (testing "the harness is not paused, and nothing about it is said"
      (is (nil? (core/paused-until (:state w) "agentloop" t0)))
      (is (empty? (notifications w "harness-needs-attention"))))
    (testing "the operator is told once, with what agentloop said and relay fresh"
      (let [[n & more] (notifications w "tools-unavailable")]
        (is (empty? more))
        (is (str/includes? (:body n) "is not the one this session pinned"))
        (is (str/includes? (:body n) "relay fresh hub"))
        (is (= ["private"] (:labels n)) "with the session's labels")))
    (testing "the memo is not counted as a failed attempt: only the cut-short wake counted"
      (is (= 1 (:attempts (first (core/unread-memos (:state w) "hub"))))))
    (testing "the dispatcher never wakes the role, however long it waits"
      (is (= [] (core/roles-to-wake (:state w) (:config w) later)))
      (is (= :paused (first (rejection #(start w later))))))
    (testing "status says so"
      (is (some #(str/includes? % "tools unavailable: file_read changed (relay fresh hub)")
                (render/status (:state w) (ZoneId/of "UTC") t0 (:config w)))))
    (testing "neither a wake by hand nor a conversation starts while it waits (D1)"
      (is (= :paused (first (rejection #(run w "operator" :wake/start {:role "hub"} later)))))
      (is (= :paused (first (rejection #(run w "operator" :wake/start {:role "hub" :chat true} later))))))
    (testing "only the operator starts it afresh"
      (is (= :forbidden (first (rejection #(run w "hub" :role/fresh {:role "hub"})))))
      (is (= :forbidden (first (rejection #(run w "dispatcher" :role/fresh {:role "hub"}))))))
    (testing "relay fresh ends the session; the next wake starts a new one"
      (let [w (fresh w "hub")
            ended (last (:events w))]
        (is (= {:tool "file_read" :digest got} (:data (last (filter #(= "tool/pinned" (:type %)) (:events w)))))
            "the changed definition is accepted")
        (is (= got (core/tool-pin (:state w) "file_read")))
        (is (some #(and (= "hold/session-ended" (:type %))
                        (= {:role "hub" :session "s1" :why "the operator started it afresh" :fresh true} (:data %)))
                  (:events w)))
        (is (not (core/waiting-on-tools? (:state w) "hub" t0)))
        (is (= ["hub"] (core/roles-to-wake (:state w) (:config w) later)))
        (let [w (start w later)]
          (is (nil? (:resumes (support/calling-wake (:state w) "hub"))) "a fresh session")
          (testing "and relay fresh has nothing more to end"
            (let [w (end w {:outcome "ran" :session "s2"} later)]
              (is (= :invalid (first (rejection #(fresh w "hub" later))))))))))))

(deftest a-server-that-did-not-start-is-tried-again-later-in-the-same-session
  (let [w (-> (setup) (start t0)
              (end {:outcome "needs-attention" :session "s1"
                    :tools-error {:server "files" :message "The MCP server files did not start: boom"}} t0))
        retry (get-in (:state w) [:roles "hub" :tools-unavailable :retry-at])
        n (first (notifications w "tools-unavailable"))]
    (is (= (.plusSeconds t0 60) retry) "after the first retry delay")
    (is (str/includes? (:body n) "did not start: boom"))
    (is (str/includes? (:body n) "relay wake hub"))
    (is (not (str/includes? (:body n) "relay fresh")))
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
    (is (str/includes? (:body n) (str "set :digest to " got)))
    (is (some? (get-in (:state w) [:roles "hub" :tools-unavailable :retry-at])) "tried again after a wait")))

(deftest an-outcome-without-tools-error-still-pauses-the-harness
  (let [w (-> (setup) (start t0) (end {:outcome "needs-attention" :session "s1" :said "bad key"} t0))]
    (is (some? (core/paused-until (:state w) "agentloop" t0)))
    (is (nil? (get-in (:state w) [:roles "hub" :tools-unavailable])))))

(deftest relay-fresh-denies-what-the-ended-session-left-open
  (let [w (-> (setup) (start t0)
              (end {:outcome "ran" :session "s1"
                    :held [{:id "c1" :name "fetch" :input {:args ["x"]} :input-sha256 "abc"}]} t0))
        h (first (core/open-holds (:state w) "hub"))
        _ (is (some? h))
        w (fresh w "hub")]
    (is (= :denied (:status (core/hold (:state w) (:id h)))))
    (is (empty? (core/open-holds (:state w) "hub")))
    (is (get-in (:state w) [:ended-sessions "s1"]))
    (testing "a role that does not exist, or is running"
      (is (= :not-found (first (rejection #(fresh w "nobody")))))
      (let [w (start w t0)]
        (is (= :busy (first (rejection #(fresh w "hub")))))))))

;; --- review fixes

(deftest a-conversation-never-clears-a-block-and-only-the-blocked-session-clears-a-fresh-one
  (let [w (-> (setup) (start t0)
              (end {:outcome "needs-attention" :session "s1"
                    :tools-error {:server "files" :message "did not start"}} t0))
        later (.plusSeconds t0 600)
        w (run w "operator" :wake/start {:role "hub" :chat true} later)
        chat (support/calling-wake (:state w) "hub")
        w (run w "dispatcher" :wake/end {:wake (:id chat) :outcome "ran" :sessions ["c1"]} later)]
    (is (some? (get-in (:state w) [:roles "hub" :tools-unavailable])) "a conversation that ran clears nothing")))

(deftest a-public-role-blocked-on-a-changed-tool-is-not-started-afresh-by-hand
  ;; N1: making the role public ends its old session, which used to clear
  ;; the block and let relay wake start a fresh session.
  (let [base (assoc settings :dead-letter-handler "clerk")
        cfg (config/resolve-config (assoc-in base [:grants "hub"] {:public true :tools ["search"]}))
        pub (fn [w actor type args now]
              (let [[state events] (core/run-command (:state w) {:type type :actor actor :args args :public true}
                                                     {:now now :config (:config w)})]
                (assoc w :state state :events (into (:events w) events))))
        w (-> {:state core/empty-state :config (config/resolve-config (assoc-in base [:grants "hub"] {:tools ["search"]}))
               :events []}
              (pub "operator" :role/add {:id "hub" :title "Hub" :instructions "Route."} t0))
        w (-> w (pub "operator" :memo/send {:to "hub" :subject "Look" :body "Look."} t0)
              (start t0)
              (end {:outcome "needs-attention" :session "s1"
                    :tools-error {:tool "search" :pinned pinned :got got :message "changed"}} t0))
        w (assoc w :config cfg)]
    (is (= :paused (first (rejection #(run w "operator" :wake/start {:role "hub"} t0)))))
    (is (get-in (:state w) [:roles "hub" :tools-unavailable :fresh]))
    (let [w (fresh w "hub")]
      (is (nil? (get-in (:state w) [:roles "hub" :tools-unavailable])))
      (is (= got (core/tool-pin (:state w) "search"))))))

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
    (testing "a drift in a new session, after a wake that completed, blocks the role; fresh accepts it for every role"
      (let [w (-> w (run "operator" :role/add {:id "clerk" :title "C" :instructions "x"})
                  (run "operator" :memo/send {:to "hub" :subject "Again" :body "Again."})
                  (start t0)
                  (end {:outcome "needs-attention" :session "s3"
                        :tools-error {:server "files" :tool "file_read" :pinned pinned :got got
                                      :message "The MCP server files gives a definition of the tool file_read that is not the one the request pins"}}
                       t0))]
        (is (core/waiting-on-tools? (:state w) "hub" t0))
        (is (get-in (:state w) [:roles "hub" :tools-unavailable :fresh]))
        (testing "another role offered the changed tool is stopped too"
          (let [w (-> w (run "operator" :memo/send {:to "clerk" :subject "Go" :body "Go."})
                      (run "dispatcher" :wake/start {:role "clerk" :dispatched true}))
                cw (support/calling-wake (:state w) "clerk")
                w (run w "dispatcher" :wake/end {:wake (:id cw) :exit 0 :outcome "needs-attention" :session "c1"
                                                 :tools-error {:tool "file_read" :pinned pinned :got got :message "changed"}})]
            (is (get-in (:state w) [:roles "clerk" :tools-unavailable :fresh]))
            (let [w (fresh w "hub")]
              (is (= got (core/tool-pin (:state w) "file_read")))
              (is (not (core/waiting-on-tools? (:state w) "hub" t0)))
              (is (not (core/waiting-on-tools? (:state w) "clerk" t0))
                  "accepting it for one role accepts it for every role granted it"))))))))

(deftest relay-fresh-ends-the-blocked-session-and-lists-what-it-drops
  ;; N3: a held session and a later blocked one; fresh ends the blocked one.
  (let [w (-> (setup) (start t0)
              (end {:outcome "ran" :session "s1" :cut-short true} t0)
              (run "operator" :wake/start {:role "hub"} t0)
              (end {:outcome "needs-attention" :session "s2"
                    :tools-error {:tool "file_read" :pinned pinned :got got :message "changed"}} t0))]
    (is (= "s2" (core/session-to-carry-on (:state w) "hub"))))
  (let [w (-> (setup) (start t0)
              (end {:outcome "ran" :session "s1"
                    :held [{:id "c1" :name "fetch" :input {:args ["x"]} :input-sha256 "abc"}
                           {:id "c2" :name "fetch" :input {:args ["y"]} :input-sha256 "def"}]} t0))
        [h1 h2] (core/open-holds (:state w) "hub")
        w (-> w (run "operator" :hold/resolve {:hold (:id h1) :verdict "allow"})
              (run "operator" :hold/resolve {:hold (:id h2) :verdict "allow"})
              (run "dispatcher" :wake/start {:role "hub" :dispatched true})
              (end {:outcome "needs-attention" :session "s1"
                    :tools-error {:tool "file_read" :pinned pinned :got got :message "changed"}} t0))]
    (is (= [(:id h1) (:id h2)] (mapv :id (core/holds-dropped-by-fresh (:state w) "hub")))
        "approved calls whose resume never ran")))

(deftest no-tool-may-take-the-readers-name
  (is (str/includes? (str (problem #(-> % (assoc-in [:tools "read_quarantined"] {:class :read :labels [] :command ["x"]}))))
                     "quarantined reader"))
  (is (str/includes? (str (problem #(-> % (assoc-in [:tools "read_quarantined"] (get-in % [:tools "search"]))
                                        (update :tools dissoc "search")
                                        (assoc-in [:grants "hub" :tools] ["read_quarantined"])
                                        (assoc-in [:grants "scout" :tools] ["read_quarantined"]))))
                     "quarantined reader")))

(deftest the-tools-message-is-one-line-and-status-never-shows-a-past-retry
  (is (= "a b c" (:message (agentloop/tools-error {:message "a\n\nb\r\n c"}))))
  (let [w (-> (setup) (start t0)
              (end {:outcome "needs-attention" :session "s1" :tools-error {:server "files" :message "down"}} t0))
        lines (fn [now] (render/status (:state w) (ZoneId/of "UTC") now (:config w)))]
    (is (some #(str/includes? % "next try") (lines t0)))
    (is (not-any? #(str/includes? % "next try") (lines (.plusSeconds t0 3600))))
    (is (some #(str/includes? % "tried again at the next tick") (lines (.plusSeconds t0 3600))))))

(deftest relay-fresh-acts-only-on-what-the-operator-saw
  ;; R3: the state changes between showing and typing the code.
  (let [w (-> (setup) (start t0)
              (end {:outcome "needs-attention" :session "s1"
                    :tools-error {:tool "file_read" :pinned pinned :got got :message "changed"}} t0))
        shown (core/fresh-expectation (:state w) "hub")
        _ (is (= {:session "s1" :tool "file_read" :got got} shown))
        ;; Meanwhile the block is replaced by one for another change.
        other (apply str (repeat 64 "3"))
        moved (update-in w [:state :roles "hub" :tools-unavailable] assoc :got other)
        r (rejection #(run moved "operator" :role/fresh {:role "hub" :expect shown}))]
    (is (= :invalid (first r)))
    (is (str/includes? (second r) "look again"))
    (is (= :invalid (first (rejection #(run w "operator" :role/fresh {:role "hub"}))))
        "without what was shown, nothing is done")
    (is (nil? (core/tool-pin (:state (fresh moved "hub")) "nope")))
    (is (= other (core/tool-pin (:state (fresh moved "hub")) "file_read")) "a fresh look accepts what it shows")))

(deftest a-first-use-never-adopts-a-definition-already-seen-to-change
  ;; R1: hub is stopped by a change before relay accepted any definition;
  ;; another role's first use of the changed one must not lift it or be
  ;; recorded.
  (let [w (-> (setup)
              (run "operator" :role/add {:id "clerk" :title "C" :instructions "x"})
              (start t0)
              (end {:outcome "needs-attention" :session "s1"
                    :tools-error {:tool "file_read" :pinned pinned :got got :message "changed"}} t0))
        _ (is (= pinned (core/tool-pin (:state w) "file_read")) "the definition it changed from is held")
        w (-> w (run "operator" :memo/send {:to "clerk" :subject "Go" :body "Go."})
              (run "dispatcher" :wake/start {:role "clerk" :dispatched true}))
        cw (support/calling-wake (:state w) "clerk")
        w (run w "dispatcher" :wake/end {:wake (:id cw) :exit 0 :outcome "ran" :session "c1" :pins {"file_read" got}})]
    (is (= pinned (core/tool-pin (:state w) "file_read")))
    (is (get-in (:state w) [:roles "hub" :tools-unavailable :fresh]) "only relay fresh lifts it")
    (testing "tool/pinned alone lifts nothing"
      (let [[state] (core/run-command (:state w) {:type :role/fresh :actor "operator"
                                                  :args {:role "hub" :expect (core/fresh-expectation (:state w) "hub")}}
                                      {:now t0 :config (:config w)})]
        (is (nil? (get-in state [:roles "hub" :tools-unavailable])))
        (let [after (core/evolve (:state w) {:seq (:next-seq (:state w)) :at (str t0) :type "tool/pinned"
                                             :data {:tool "file_read" :digest got}})]
          (is (= got (core/tool-pin after "file_read")))
          (is (= (:roles (:state w)) (:roles after))))))))
