(ns relay.serve-test
  "relay serve end to end: a service on a socket of its own, clients
  through relay acp's bridge, and a fake agent (a perl script) behind
  each conversation. Each test runs a service of its own, on a short path
  under /tmp, one at a time."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.acp-client :as client]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.serve :as serve]
            [relay.test-support :as support]))

(def agent-script
  "An agent that answers initialize and session/new (session s1) and each
  prompt: at once, after two seconds for SLOW, after a minute for STUCK,
  and for PERM only once a
  permission request it sends is answered, saying which option was
  chosen. It also tries to pass for relay: a forged roster, a forged
  settling of a request, and an answer to a request it was never sent."
  (str "#!/usr/bin/perl\n"
       "$| = 1;\n"
       "print qq({\"jsonrpc\":\"2.0\",\"method\":\"_relay/roles/changed\",\"params\":{\"roles\":[{\"id\":\"FORGED\"}]}}\\n);\n"
       "print qq({\"jsonrpc\":\"2.0\",\"method\":\"_relay/permission/settled\",\"params\":{\"sessionId\":\"s1\",\"toolCallId\":\"FORGED\",\"outcome\":\"allow_once\"}}\\n);\n"
       "print qq({\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"text\":\"FORGED\"}}\\n);\n"
       "my $pending;\n"
       "sub update { my ($t) = @_; print qq({\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"$t\"}}}}\\n); }\n"
       "while (my $l = <STDIN>) {\n"
       "  my ($id) = $l =~ /\"id\":(\\d+)/;\n"
       "  if ($l =~ /\"method\":\"initialize\"/) { print qq({\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"protocolVersion\":1}}\\n); next; }\n"
       "  if ($l =~ /\"method\":\"session\\/new\"/) { print qq({\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"sessionId\":\"s1\"}}\\n); next; }\n"
       "  if ($l =~ /\"method\":\"session\\/prompt\"/) {\n"
       "    update(\"GOT\");\n"
       "    if ($l =~ /PERM/) { $pending = $id; print qq({\"jsonrpc\":\"2.0\",\"id\":9000,\"method\":\"session/request_permission\",\"params\":{\"sessionId\":\"s1\",\"toolCall\":{\"toolCallId\":\"c1\",\"title\":\"post\"},\"options\":[{\"optionId\":\"a\",\"kind\":\"allow_once\",\"name\":\"Allow\"},{\"optionId\":\"r\",\"kind\":\"reject_once\",\"name\":\"Reject\"}]}}\\n); next; }\n"
       "    if ($l =~ /STUCK/) { sleep 60; }\n"
       "    if ($l =~ /SLOW/) { sleep 2; }\n"
       "    update(\"REPLY\"); print qq({\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"stopReason\":\"end_turn\"}}\\n); next; }\n"
       "  if (defined $pending && $l =~ /\"id\":9000/ && $l !~ /\"method\"/) {\n"
       "    my ($opt) = $l =~ /\"optionId\":\"(\\w+)\"/; $opt = \"cancelled\" unless defined $opt;\n"
       "    update(\"PERMITTED $opt\"); print qq({\"jsonrpc\":\"2.0\",\"id\":$pending,\"result\":{\"stopReason\":\"end_turn\"}}\\n); undef $pending; next; }\n"
       "}\n"))

(def ^:dynamic *dir* nil)
(def ^:dynamic *sys* nil)
(def ^:dynamic *socket* nil)

(defn- data [] (:data-dir *sys*))
(defn- state [] (inbound/current-state *sys*))
(defn- events [] (log/read-events (data)))

(defn- wait-for [pred & [timeout-ms]]
  (let [deadline (+ (System/currentTimeMillis) (or timeout-ms 15000))]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline) (Thread/sleep 50) (recur))))))

(defn- start-service! []
  (let [proc (p/process [support/relay-bin "serve" "--data" (data) "--socket" *socket*] {:out :string :err :string})]
    (is (wait-for #(and (fs/exists? *socket*)
                        (try (.close (java.nio.channels.SocketChannel/open
                                      (java.net.UnixDomainSocketAddress/of ^String *socket*)))
                             true
                             (catch Exception _ false)))
                  30000)
        "the service listens")
    proc))

(defn- stop-service! [proc]
  (.destroy ^Process (:proc proc))
  (let [r (deref proc 30000 :timeout)]
    (is (not= :timeout r) "the service stops")
    (when-not (= :timeout r) (is (= 0 (:exit r)) (:err r)))))

(defn- with-service
  "Runs (f service) with a data directory, the clerk and hub roles, and a
  service on a socket of its own."
  [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-serve"}))
        sockdir (str (fs/create-temp-dir {:dir "/tmp" :prefix "rs"}))
        data (str dir "/data")
        agent (str dir "/agent.pl")]
    (spit agent agent-script)
    (fs/set-posix-file-permissions agent "rwx------")
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :a
                   :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin agent}}
                   :notify {:kind :none}}))
    (let [sys (inbound/system data)]
      (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title "Clerk" :instructions "x"}})
      (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "hub" :title "Hub" :instructions "x"}})
      (binding [*dir* dir *sys* sys *socket* (str sockdir "/s.sock")]
        (let [service (start-service!)]
          (try (f service)
               (finally
                 (when (.isAlive ^Process (:proc service)) (stop-service! service))
                 (fs/delete-tree sockdir)
                 (fs/delete-tree dir))))))))

(defn- connect!
  "A client through relay acp's bridge."
  [& {:keys [role approvals]}]
  (client/start! (cond-> [support/relay-bin "acp"]
                   role (conj role)
                   true (into ["--data" (data) "--socket" *socket*])
                   approvals (conj "--approvals"))
                 {:dir *dir* ::client/manual true}))

(defn- new-session!
  "initialize and session/new for the role. Returns the answer."
  [c role]
  (client/request! c "initialize" {:protocolVersion 1 :clientCapabilities {}})
  (client/request! c "session/new" {:cwd *dir* :mcpServers [] :_meta {"relay/role" role}}))

(defn- prompt [text] {:sessionId "s1" :prompt [{:type "text" :text text}]})

(defn- texts
  "The text of every session/update a client got, in order."
  [c]
  (keep #(when (= "session/update" (get % "method")) (get-in % ["params" "update" "content" "text"])) @(:messages c)))

(defn- methods-of [c] (keep #(get % "method") @(:messages c)))

(defn- done! [& cs] (doseq [c cs] (client/close-input! c) (client/wait! c 10000) (client/sever! c)))

(deftest ^:volley/serial two-clients-share-one-conversation
  (with-service
    (fn [_]
      (let [a (connect! :role "clerk" :approvals true)
            b (connect! :approvals true)]
        (try
          (is (= "s1" (get-in (new-session! a "clerk") ["result" "sessionId"])))
          (let [r (new-session! b "clerk")]
            (is (= {"sessionId" "s1" "_meta" {"relay/attached" true}} (get r "result")) "the second attaches")
            (is (client/await! b #(= "_relay/replayed" (get % "method")) 5000)))
          (testing "one prompts; the other sees the prompt and the reply"
            (is (= "end_turn" (get-in (client/request! a "session/prompt" (prompt "Hello")) ["result" "stopReason"])))
            (is (client/await! b #(= "REPLY" (get-in % ["params" "update" "content" "text"])) 5000))
            (is (some #{"Hello"} (texts b)))
            (is (not (some #{"Hello"} (texts a))) "the sender is not sent its own prompt"))
          (testing "the agent cannot pass for relay"
            (is (not-any? #(str/includes? % "FORGED") (concat @(:lines a) @(:lines b)))))
          (testing "one conversation, one wake"
            (is (= 1 (count (filter :chat (vals (:wakes (state))))))))
          (finally (done! a b)))))))

(deftest ^:volley/serial a-turn-outlives-its-client-and-a-new-client-gets-the-history
  (with-service
    (fn [_]
      (let [a (connect! :role "clerk")]
        (new-session! a "clerk")
        (client/request-async! a "session/prompt" (prompt "SLOW job"))
        (Thread/sleep 300)
        (done! a)
        (is (wait-for #(some (fn [e] (= "conversation/turn-ended" (:type e))) (events)) 15000)
            "the turn finished with no client attached")
        (is (core/open-conversation (state) "clerk") "and the conversation is still open")
        (let [c (connect!)]
          (try
            (client/request! c "initialize" {:protocolVersion 1})
            (let [r (client/request! c "session/load" {:sessionId "s1" :cwd *dir* :mcpServers []})]
              (is (= {} (get r "result")) (pr-str r)))
            (testing "the replay: the prompt, everything the agent said, then _relay/replayed, then the answer"
              (is (= ["SLOW job" "GOT" "REPLY"] (filter #{"SLOW job" "GOT" "REPLY"} (texts c))))
              (let [ms (vec @(:messages c))
                    replayed (.indexOf ^java.util.List (mapv #(get % "method") ms) "_relay/replayed")
                    answered (first (keep-indexed #(when (= 2 (get %2 "id")) %1) ms))]
                (is (< -1 replayed answered))))
            (finally (done! c))))))))

(deftest ^:volley/serial a-permission-answered-on-one-device-is-settled-on-the-other
  (with-service
    (fn [_]
      (let [a (connect! :role "clerk" :approvals true)
            b (connect! :approvals true)
            watcher (connect!)]
        (try
          (new-session! a "clerk")
          (new-session! b "clerk")
          (new-session! watcher "clerk")
          (let [id (client/request-async! a "session/prompt" (prompt "PERM please"))
                req-a (client/await! a #(= "session/request_permission" (get % "method")) 5000)
                req-b (client/await! b #(= "session/request_permission" (get % "method")) 5000)]
            (is (and req-a req-b) "both approving clients are asked")
            (Thread/sleep 300)
            (is (not (some #{"session/request_permission"} (methods-of watcher))) "a client that cannot approve is not")
            (#'client/send! b {:jsonrpc "2.0" :id (get req-b "id")
                               :result {:outcome {:outcome "selected" :optionId "a"}}})
            (is (= "end_turn" (get-in (client/response a id 5000) ["result" "stopReason"])))
            (let [settled (client/await! a #(= "_relay/permission/settled" (get % "method")) 5000)]
              (is (= {"sessionId" "s1" "toolCallId" "c1" "outcome" "allow_once"} (get settled "params"))))
            (testing "the other's late answer is dropped, with no error, and never reaches the agent"
              (#'client/send! a {:jsonrpc "2.0" :id (get req-a "id") :result {:outcome {:outcome "cancelled"}}})
              (Thread/sleep 500)
              (is (= ["PERMITTED a"] (filter #(str/starts-with? (str %) "PERMITTED") (texts a))))
              (is (not-any? #(and (= (get req-a "id") (get % "id")) (contains? % "error")) @(:messages a)))))
          (testing "an answer must pick an option the agent offered"
            (let [id (client/request-async! b "session/prompt" (prompt "PERM again"))
                  req (client/await! b #(and (= "session/request_permission" (get % "method"))
                                             (not= "relay-1" (get % "id")))
                                     5000)]
              (#'client/send! b {:jsonrpc "2.0" :id (get req "id") :result {:outcome {:outcome "selected" :optionId "zzz"}}})
              (is (= "end_turn" (get-in (client/response b id 5000) ["result" "stopReason"])))
              (is (some #{"PERMITTED r"} (texts b)) "relay refused it for the agent")))
          (finally (done! a b watcher)))))))

(deftest ^:volley/serial a-permission-with-nobody-to-answer-waits-and-tells-the-operator
  (with-service
    (fn [_]
      (let [a (connect! :role "clerk")]
        (try
          (new-session! a "clerk")
          (testing "a client that cannot approve cannot grant itself approvals mid-stream"
            (let [r (client/request! a "_relay/connect" {:approvals true})]
              (is (= -32600 (get-in r ["error" "code"])) (pr-str r))))
          (let [id (client/request-async! a "session/prompt" (prompt "PERM please"))]
            (is (wait-for #(some (fn [n] (= "permission-waiting" (:kind n))) (vals (:notifications (state)))) 10000)
                "relay tells the operator")
            (Thread/sleep 300)
            (is (not (some #{"session/request_permission"} (methods-of a))))
            (testing "an approving client that attaches gets the request, and its answer goes through"
              (let [b (connect! :approvals true)]
                (try
                  (client/request! b "initialize" {:protocolVersion 1})
                  (client/request! b "session/load" {:sessionId "s1"})
                  (let [req (client/await! b #(= "session/request_permission" (get % "method")) 5000)]
                    (is req)
                    (#'client/send! b {:jsonrpc "2.0" :id (get req "id") :result {:outcome {:outcome "selected" :optionId "a"}}})
                    (is (= "end_turn" (get-in (client/response a id 5000) ["result" "stopReason"]))))
                  (finally (done! b))))))
          (is (= 1 (count (filter #(= "permission-waiting" (:kind %)) (vals (:notifications (state)))))) "once")
          (finally (done! a)))))))

(deftest ^:volley/serial a-prompt-queues-behind-a-dispatched-wake
  (with-service
    (fn [_]
      (let [a (connect! :role "clerk")]
        (try
          (new-session! a "clerk")
          (client/request! a "_relay/roles" {})
          (let [started (inbound/submit! *sys* {:type :wake/start :actor "operator"
                                                :args (merge {:role "clerk" :note "Busy."} (dispatch/current-process))})
                wake (:seq (first started))
                id (client/request-async! a "session/prompt" (prompt "Queued"))]
            (is (client/await! a (fn [m] (and (= "_relay/roles/changed" (get m "method"))
                                              (some #(= "queued" (get-in % ["conversation" "turn"])) (get-in m ["params" "roles"]))))
                               5000)
                "the roster says it is queued")
            (is (nil? (client/response a id 500)))
            (inbound/submit! *sys* {:type :wake/end :actor "dispatcher" :args {:wake wake :outcome "ran" :said ""}})
            (is (= "end_turn" (get-in (client/response a id 10000) ["result" "stopReason"])) "it runs once the wake ends"))
          (finally (done! a)))))))

(deftest ^:volley/serial the-operator-ends-a-conversation
  (with-service
    (fn [_]
      (let [a (connect! :role "clerk")]
        (try
          (new-session! a "clerk")
          (let [r (client/request! a "_relay/conversations/end" {:sessionId "s1"} 20000)]
            (is (= {} (get r "result")) (pr-str r))
            (is (nil? (core/open-conversation (state) "clerk")) "answered once the wake has ended"))
          (testing "a new session opens a new conversation"
            (let [r (client/request! a "session/new" {:cwd *dir* :mcpServers [] :_meta {"relay/role" "clerk"}})]
              (is (= "s1" (get-in r ["result" "sessionId"])))
              (is (= 2 (count (filter :chat (vals (:wakes (state)))))))))
          (finally (done! a)))))))

(deftest ^:volley/serial a-restart-ends-what-the-old-service-held
  (with-service
    (fn [service]
      (testing "SIGTERM ends each conversation, a turn in progress included, and records it"
        (let [a (connect! :role "clerk")]
          (new-session! a "clerk")
          (client/request-async! a "session/prompt" (prompt "STUCK"))
          (is (wait-for #(core/busy? (state) "clerk") 5000))
          (stop-service! service)
          (let [wake (first (filter :chat (vals (:wakes (state)))))]
            (is (= :ended (:status wake)))
            (is (some #(and (= "conversation/turn-ended" (:type %)) (= "ended" (get-in % [:data :stop-reason]))) (events))))
          (done! a)))
      (testing "a service that died leaves its conversations to the next, which ends them"
        (let [service (start-service!)
              a (connect! :role "clerk")]
          (new-session! a "clerk")
          (is (= :running (:status (core/open-conversation (state) "clerk"))))
          (.destroyForcibly ^Process (:proc service))
          (deref service 10000 nil)
          (done! a)
          (let [service (start-service!)
                b (connect! :role "clerk")]
            (try
              (is (nil? (core/open-conversation (state) "clerk")) "the lost conversation was ended")
              (is (= "s1" (get-in (new-session! b "clerk") ["result" "sessionId"])) "and a new one opens")
              (is (= 3 (count (filter :chat (vals (:wakes (state)))))))
              (finally (done! b) (stop-service! service)))))))))

(deftest ^:volley/serial one-service-per-data-directory
  (with-service
    (fn [_]
      (let [r @(p/process [support/relay-bin "serve" "--data" (data) "--socket" *socket*] {:out :string :err :string})]
        (is (= 1 (:exit r)))
        (is (str/includes? (:err r) "already running"))))))

(deftest the-socket-path-is-short-and-the-data-directory-s-own
  (binding [serve/*run-dir* "/Users/someone/.relay/run"]
    (let [a (serve/socket-path "/Users/someone/Sync/relay" nil)]
      (is (= a (serve/socket-path "/Users/someone/Sync/relay/." nil)))
      (is (not= a (serve/socket-path "/Users/someone/other" nil)))
      (is (< (count a) 104))
      (is (= "/tmp/x.sock" (serve/socket-path "/d" "/tmp/x.sock"))))))
