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
            [relay.hub :as hub]
            [relay.hub-test :as ht]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.serve :as serve]
            [relay.test-support :as support]
            [babashka.http-client.websocket :as ws]
            [relay.websocket]))

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

(def deaf-agent-script
  "An agent that answers initialize and session/new and then never reads
  its stdin again."
  (str "#!/usr/bin/perl\n"
       "$| = 1;\n"
       "for (1..2) { my $l = <STDIN>; my ($id) = $l =~ /\"id\":(\\d+)/;\n"
       "  if ($l =~ /initialize/) { print qq({\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"protocolVersion\":1}}\\n); }\n"
       "  else { print qq({\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"sessionId\":\"s1\"}}\\n); } }\n"
       "sleep 600;\n"))

(def ^:dynamic *dir* nil)
(def ^:dynamic *run-dir* nil)
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
  (let [proc (p/process [support/relay-bin "serve" "--data" (data) "--socket" *socket* "--run-dir" *run-dir*]
                        {:out :string :err :string})]
    (is (wait-for #(and (fs/exists? *socket*)
                        (try (.close (java.nio.channels.SocketChannel/open
                                      (java.net.UnixDomainSocketAddress/of ^String *socket*)))
                             true
                             (catch Exception _ false)))
                  30000)
        "the service listens")
    proc))

(defn- stop-service!
  "Stops the service. Returns its result, with what it said on stderr."
  [proc]
  (.destroy ^Process (:proc proc))
  (let [r (deref proc 30000 :timeout)]
    (is (not= :timeout r) "the service stops")
    (when-not (= :timeout r) (is (= 0 (:exit r)) (:err r)))
    r))

(defn- with-service
  "Runs (f service) with a data directory, the clerk and hub roles, and a
  service on a socket of its own, its agent the given script, and
  `settings` added to its relay.edn."
  [f & [script settings]]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-serve"}))
        sockdir (str (fs/create-temp-dir {:dir "/tmp" :prefix "rs"}))
        data (str dir "/data")
        agent (str dir "/agent.pl")]
    (spit agent (or script agent-script))
    (fs/set-posix-file-permissions agent "rwx------")
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str (merge {:timezone "UTC" :default-harness :a
                          :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin agent}}
                          :notify {:kind :none}}
                         settings)))
    (let [sys (inbound/system data)]
      (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title "Clerk" :instructions "x"}})
      (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "hub" :title "Hub" :instructions "x"}})
      (binding [*dir* dir *sys* sys *socket* (str sockdir "/s.sock") *run-dir* (str sockdir "/run")]
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
              ;; The agent's two chunks come back merged into one.
              (is (= ["SLOW job" "GOTREPLY"] (texts c)))
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
      (doseq [socket [*socket* (str (fs/parent *socket*) "/other.sock")]]
        (testing (str "on " socket)
          (let [proc (p/process [support/relay-bin "serve" "--data" (data) "--socket" socket "--run-dir" *run-dir*]
                                {:out :string :err :string})
                r (deref proc 15000 :timeout)]
            (when (= :timeout r) (.destroy ^Process (:proc proc)) (deref proc 15000 nil))
            (is (not= :timeout r) "it refuses at once")
            (is (= 1 (:exit r)))
            (is (str/includes? (str (:err r)) "already running"))))))))

(deftest ^:volley/serial ending-a-conversation-stops-an-agent-that-stopped-reading
  ;; Codex round 1: stopping only queued an end of input behind a prompt
  ;; the agent would never read, so the end hung and the role stayed taken.
  (with-service
    (fn [_]
      (let [a (connect! :role "clerk")]
        (try
          (new-session! a "clerk")
          (client/request-async! a "session/prompt" (prompt (apply str (repeat (* 1024 1024) "x"))))
          (Thread/sleep 500)
          (let [t0 (System/currentTimeMillis)
                r (client/request! a "_relay/conversations/end" {:sessionId "s1"} 30000)]
            (is (= {} (get r "result")) (pr-str r))
            (is (< (- (System/currentTimeMillis) t0) (+ dispatch/stop-grace-ms 10000)) "within the grace and a margin")
            (is (nil? (core/open-conversation (state) "clerk")) "the wake has ended"))
          (finally (done! a)))))
    deaf-agent-script))

(deftest the-socket-path-is-short-and-the-data-directory-s-own
  (binding [serve/*run-dir* "/Users/someone/.relay/run"]
    (let [a (serve/socket-path "/Users/someone/Sync/relay" nil)]
      (is (= a (serve/socket-path "/Users/someone/Sync/relay/." nil)))
      (is (not= a (serve/socket-path "/Users/someone/other" nil)))
      (is (< (count a) 104))
      (is (= "/tmp/x.sock" (serve/socket-path "/d" "/tmp/x.sock"))))))

;; --- bounds (Codex round 1)

(deftest a-sender-gives-up-past-its-backlog
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        over (atom 0)
        {:keys [send! taken!]} (#'serve/bounded-sender q 100 #(swap! over inc))]
    (send! (apply str (repeat 60 "a")))
    (is (zero? @over))
    (taken! 61)
    (send! (apply str (repeat 60 "b")))
    (is (zero? @over) "what was taken no longer counts")
    (send! (apply str (repeat 60 "c")))
    (is (= 1 @over) "past the backlog it gives up")
    (is (= 2 (.size q)) "and queues nothing more")))

(deftest the-event-queue-holds-producers-back
  (let [eng (#'serve/engine nil :service false {:capacity 2})
        q (:q eng)]
    (#'serve/enqueue! eng 1)
    (#'serve/enqueue! eng 2)
    (let [third (future (#'serve/enqueue! eng 3) :put)]
      (is (= :waiting (deref third 300 :waiting)) "a full queue makes the producer wait")
      (.take ^java.util.concurrent.BlockingQueue q)
      (is (= :put (deref third 2000 :waiting)) "until there is room")
      (is (= 2 (.size ^java.util.concurrent.BlockingQueue q))))))

;; --- Codex round 2

(deftest ^:volley/serial serve-never-removes-what-is-not-its-socket
  (with-service
    (fn [service]
      (stop-service! service)
      (doseq [[what make!] [["a file" #(spit % "Kira's notes")] ["a directory" #(fs/create-dirs %)]]]
        (testing what
          (let [path (str (fs/parent *socket*) "/taken")
                _ (make! path)
                r (deref (p/process [support/relay-bin "serve" "--data" (data) "--socket" path "--run-dir" *run-dir*]
                                    {:out :string :err :string})
                         15000 :timeout)]
            (is (= 1 (:exit r)) (pr-str r))
            (is (str/includes? (str (:err r)) "not a socket"))
            (if (= "a file" what)
              (is (= "Kira's notes" (slurp path)) "left as it was")
              (is (fs/directory? path)))
            (fs/delete-tree path)))))))

(deftest a-data-directory-is-known-by-its-real-path
  (let [base (str (fs/create-temp-dir {:dir "/tmp" :prefix "rsreal"}))
        data (str base "/data")
        link (str base "/link")]
    (try
      (fs/create-dirs data)
      (fs/create-sym-link link data)
      (binding [serve/*run-dir* "/r"]
        (is (= (serve/lock-path data) (serve/lock-path link)) "a link to it")
        (is (= (serve/socket-path data nil) (serve/socket-path link nil)))
        (is (= (serve/lock-path data) (serve/lock-path (str "/private" data))) "/tmp and /private/tmp on macOS"))
      (finally (fs/delete-tree base)))))

(deftest the-service-takes-only-so-many-connections
  (let [eng (#'serve/engine nil :service false {:max-conns 2})
        conn #(#'serve/add-connection! eng {:in (java.io.PipedInputStream. (java.io.PipedOutputStream.))
                                            :out (java.io.ByteArrayOutputStream.) :origin :socket})]
    (is (some? (conn)))
    (is (some? (conn)))
    (is (nil? (conn)) "a third is refused")))

;; --- Codex round 3

(deftest a-turn-end-the-log-refused-is-recorded-once-it-can-be
  ;; The shell carries out the hub's submits: one that throws (a full disk)
  ;; comes back as a failure, the hub keeps the role busy and says so, and
  ;; a later tick records it.
  (let [[h fx] (ht/say (ht/live) 1 (ht/req 30 "session/prompt" {:sessionId "s1" :prompt []}))
        h (ht/submit-ok h fx)
        up (:up (second (hub/running h "clerk")))
        eng (#'serve/engine nil :service false)
        replies (atom [])
        submitted (atom [])
        err (java.io.StringWriter.)]
    (reset! (:hub eng) h)
    (reset! (:conns eng) {1 {:send! #(swap! replies conj %)}})
    (binding [*err* err]
      (with-redefs [inbound/submit! (fn [_ cmd] (swap! submitted conj (:type cmd))
                                      (throw (java.io.IOException. "No space left on device")))]
        (#'serve/process! eng {:event :agent-line :role "clerk" :line (ht/res up {:stopReason "end_turn"})})))
    (is (str/includes? (str err) "could not record the end of clerk's turn") (str err))
    (is (= [:conversation/turn-end :conversation/turn-unrecorded] @submitted))
    (is (get-in @(:hub eng) [:convs "clerk" :turn-end-due]) "still busy")
    (reset! submitted [])
    (with-redefs [inbound/submit! (fn [_ cmd] (swap! submitted conj (:type cmd)) [])]
      (#'serve/process! eng {:event :tick}))
    (is (= [:conversation/turn-end] @submitted) "tried again")
    (is (nil? (get-in @(:hub eng) [:convs "clerk" :turn-end-due])) "and recorded")))

;; --- the WebSocket listener (relay#66)

(def ws-token "tok-for-tests-123")

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort s)))

(defn- ws-settings [port & [extra]]
  {:serve {:websocket (merge {:port port :token-command ["/bin/echo" ws-token]} extra)}})

(defn- port-open? [port]
  (try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] true)
       (catch Exception _ false)))

(defn- ws-connect!
  "A client over WebSocket (java.net.http's, an implementation that is not
  relay's), with the API of relay.acp-client."
  [port & {:keys [token] :or {token ws-token}}]
  (let [messages (atom []) lines (atom []) buf (StringBuilder.) closed (promise)
        sock (ws/websocket {:uri (str "ws://127.0.0.1:" port "/")
                            :headers {"Authorization" (str "Bearer " token)}
                            :on-message (fn [_ data last?]
                                          (locking buf
                                            (.append buf (str data))
                                            (when last?
                                              (let [l (str buf)]
                                                (.setLength buf 0)
                                                (swap! lines conj l)
                                                (swap! messages conj (json/parse-string l))))))
                            :on-close (fn [_ code _] (deliver closed code))
                            :on-error (fn [_ _] (deliver closed :error))})]
    {:messages messages :lines lines :next-id (atom 0) :ws sock :closed closed
     :send-fn (fn [text] (ws/send! sock text))}))

(defn- ws-close! [c] (try (ws/close! (:ws c)) (catch Exception _ nil)))

(defn- with-websocket-service
  "with-service with the WebSocket listener on a free port: (f service port)."
  [f & [extra]]
  (let [port (free-port)]
    (with-service
      (fn [service]
        (is (wait-for #(port-open? port) 15000) "the WebSocket listener is up")
        (f service port))
      nil (ws-settings port extra))))

(deftest ^:volley/serial a-phone-holds-a-conversation-over-websocket
  (with-websocket-service
    (fn [_ port]
      (let [c (ws-connect! port)]
        (try
          (let [init (client/request! c "initialize" {:protocolVersion 1 :clientCapabilities {}} 5000)]
            (is (= {"relay/hub" 1 "relay/keeps" true} (get-in init ["result" "_meta"]))))
          (is (= ["clerk" "hub"] (map #(get % "id") (get-in (client/request! c "_relay/roles" {} 5000) ["result" "roles"]))))
          (is (= "s1" (get-in (client/request! c "session/new" {:cwd *dir* :mcpServers [] :_meta {"relay/role" "clerk"}} 10000)
                              ["result" "sessionId"])))
          (is (= "end_turn" (get-in (client/request! c "session/prompt" (prompt "Hello") 10000) ["result" "stopReason"])))
          (is (= ["GOT" "REPLY"] (texts c)) "the reply, live, as the agent sent it")
          (is (client/await! c #(= "_relay/roles/changed" (get % "method")) 5000) "and the roster as it changes")
          (testing "every message is one JSON-RPC line"
            (is (every? #(and (map? (json/parse-string %)) (not (str/includes? % "\n"))) @(:lines c))))
          (finally (ws-close! c)))))))

(deftest ^:volley/serial a-phone-and-a-terminal-share-one-conversation
  (with-websocket-service
    (fn [_ port]
      (let [a (connect! :role "clerk" :approvals true)
            b (ws-connect! port)]
        (try
          (new-session! a "clerk")
          (client/request! b "initialize" {:protocolVersion 1} 5000)
          (is (= {"sessionId" "s1" "_meta" {"relay/attached" true}}
                 (get (client/request! b "session/new" {:cwd *dir* :mcpServers [] :_meta {"relay/role" "clerk"}} 5000) "result")))
          (let [id (client/request-async! a "session/prompt" (prompt "PERM please"))
                req-a (client/await! a #(= "session/request_permission" (get % "method")) 5000)
                req-b (client/await! b #(= "session/request_permission" (get % "method")) 5000)]
            (is (and req-a req-b) "the phone can approve, and is asked as the terminal is")
            (is (some #{"PERM please"} (texts b)) "the phone sees the terminal's prompt")
            (#'client/send! b {:jsonrpc "2.0" :id (get req-b "id") :result {:outcome {:outcome "selected" :optionId "a"}}})
            (is (= "end_turn" (get-in (client/response a id 5000) ["result" "stopReason"])))
            (is (= {"sessionId" "s1" "toolCallId" "c1" "outcome" "allow_once"}
                   (get (client/await! a #(= "_relay/permission/settled" (get % "method")) 5000) "params"))
                "the terminal is told the phone answered")
            (is (client/await! b #(= "PERMITTED a" (get-in % ["params" "update" "content" "text"])) 5000)))
          (testing "and the other way: the phone prompts, the terminal sees it"
            (is (= "end_turn" (get-in (client/request! b "session/prompt" (prompt "From the phone") 10000) ["result" "stopReason"])))
            (is (client/await! a #(= "From the phone" (get-in % ["params" "update" "content" "text"])) 5000)))
          (finally (ws-close! b) (done! a)))))))

(deftest ^:volley/serial a-phone-s-session-new-without-a-role-goes-to-the-default-role
  (with-websocket-service
    (fn [_ port]
      (let [c (ws-connect! port)]
        (try
          (client/request! c "initialize" {:protocolVersion 1} 5000)
          (is (= "s1" (get-in (client/request! c "session/new" {:cwd *dir* :mcpServers []} 10000) ["result" "sessionId"])))
          (is (core/open-conversation (state) "clerk") "the configured default role")
          (is (not (core/open-conversation (state) "hub")))
          (finally (ws-close! c)))))
    {:default-role "clerk"}))

(defn- raw-request
  "Sends a request's head to the listener and returns everything it
  answers until it closes."
  [port head]
  (with-open [s (java.net.Socket. "127.0.0.1" (int port))]
    (.setSoTimeout s 5000)
    (.write (.getOutputStream s) (.getBytes (str head "\r\n\r\n") "ISO-8859-1"))
    (.flush (.getOutputStream s))
    (String. (.readAllBytes (.getInputStream s)) "ISO-8859-1")))

(defn- upgrade-head [& extra]
  (str/join "\r\n" (concat ["GET / HTTP/1.1" "Host: 127.0.0.1" "Upgrade: websocket" "Connection: Upgrade"
                            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" "Sec-WebSocket-Version: 13"]
                           extra)))

(defn- upgraded
  "A raw socket past the upgrade, and its streams."
  [port]
  (let [s (java.net.Socket. "127.0.0.1" (int port))
        in (java.io.DataInputStream. (.getInputStream s))]
    (.setSoTimeout s 5000)
    (.write (.getOutputStream s) (.getBytes (str (upgrade-head (str "Authorization: Bearer " ws-token)) "\r\n\r\n") "ISO-8859-1"))
    (is (str/starts-with? (relay.websocket/read-head in) "HTTP/1.1 101 "))
    {:sock s :in in :out (.getOutputStream s)}))

(defn- close-code
  "The status of the close frame the listener sends next, and whether
  the connection then ends."
  [{:keys [^java.io.DataInputStream in]}]
  (let [b0 (.readUnsignedByte in) n (.readUnsignedByte in) code (.readUnsignedShort in)]
    [(= 0x88 b0) (= 2 n) code (try (= -1 (.read in)) (catch java.net.SocketException _ true))]))

(deftest ^:volley/serial the-listener-refuses-before-the-upgrade-and-closes-on-what-it-does-not-take
  (with-websocket-service
    (fn [_ port]
      (testing "refusals carry their status and nothing else"
        (doseq [[what head status] [["no token" (upgrade-head) 401]
                                    ["a wrong token" (upgrade-head "Authorization: Bearer tok-for-tests-12") 401]
                                    ["an Origin, even with the token" (upgrade-head (str "Authorization: Bearer " ws-token)
                                                                                    "Origin: http://localhost:3000") 403]
                                    ["a plain request" "GET / HTTP/1.1\r\nHost: 127.0.0.1" 404]
                                    ["a plain request with the token" (str "GET /x HTTP/1.1\r\nAuthorization: Bearer " ws-token) 404]]]
          (let [answer (raw-request port head)]
            (is (str/starts-with? answer (str "HTTP/1.1 " status " ")) (str what ": " answer))
            (is (str/ends-with? answer "Content-Length: 0\r\nConnection: close\r\n\r\n") what)
            (is (not (str/includes? (str/lower-case answer) "relay")) what))))
      (testing "java.net.http's client is refused without the token"
        (is (thrown? Exception (ws-connect! port :token "wrong"))))
      (testing "a binary frame closes the socket"
        (let [c (upgraded port)]
          (try (.write ^java.io.OutputStream (:out c) (relay.websocket/frame-bytes :binary (.getBytes "{}") (byte-array [1 2 3 4]) true))
               (is (= [true true 1003 true] (close-code c)))
               (finally (.close ^java.net.Socket (:sock c))))))
      (testing "a message over 8 MB closes the socket, before relay reads it"
        (let [c (upgraded port)
              ;; Only the frame's head: its length says 8 MB and one byte.
              head (java.util.Arrays/copyOfRange (relay.websocket/frame-bytes :text (byte-array (inc (* 8 1024 1024))) (byte-array [1 2 3 4]) true) 0 14)]
          (try (.write ^java.io.OutputStream (:out c) head)
               (is (= [true true 1009 true] (close-code c)))
               (finally (.close ^java.net.Socket (:sock c))))))
      (testing "the listener still serves after all that"
        (let [c (ws-connect! port)]
          (try (is (some? (client/request! c "initialize" {:protocolVersion 1} 5000)))
               (finally (ws-close! c))))))))

(defn- listening
  "The TCP addresses a process listens on, as lsof gives them."
  [proc]
  (let [{:keys [out]} (p/shell {:out :string :err :string :continue true}
                               "lsof" "-nP" "-a" "-p" (str (.pid ^Process (:proc proc))) "-iTCP" "-sTCP:LISTEN" "-Fn")]
    (vec (keep #(when (str/starts-with? % "n") (subs % 1)) (str/split-lines out)))))

(deftest ^:volley/serial the-listener-binds-only-the-loopback-address
  (with-websocket-service
    (fn [service port]
      (is (= [(str "127.0.0.1:" port)] (listening service)))
      (testing "another address of this Mac does not reach it"
        (doseq [addr (->> (re-seq #"inet (\d+\.\d+\.\d+\.\d+)" (:out (p/shell {:out :string} "ifconfig")))
                          (map second)
                          (remove #(str/starts-with? % "127."))
                          (take 2))]
          (is (thrown? java.io.IOException (with-open [_ (java.net.Socket. ^String addr (int port))]))
              addr))))))

(deftest ^:volley/serial no-listener-without-settings-or-a-token
  (testing "no :websocket, no listener"
    (with-service
      (fn [service]
        (is (= [] (listening service)))
        (is (not (str/includes? (:err (stop-service! service)) "WebSocket"))))))
  (doseq [[what command why] [["a token command that fails" ["/bin/sh" "-c" "echo nope >&2; exit 3"] "exited 3: nope"]
                              ["an empty token" ["/usr/bin/true"] "printed no token"]]]
    (testing what
      (let [port (free-port)]
        (with-service
          (fn [service]
            (Thread/sleep 300)
            (is (not (port-open? port)))
            (is (= [] (listening service)))
            (testing "and the service still runs for the socket"
              (let [a (connect! :role "clerk")]
                (try (is (= "s1" (get-in (new-session! a "clerk") ["result" "sessionId"])))
                     (finally (done! a)))))
            (let [err (:err (stop-service! service))]
              (is (str/includes? err (str "no WebSocket listener: its :token-command " why)) err)))
          nil (ws-settings port {:token-command command}))))))

(deftest the-websocket-and-the-socket-share-the-connection-cap
  (let [eng (#'serve/engine nil :service false {:max-conns 2})
        stream #(#'serve/add-connection! eng {:in (java.io.PipedInputStream. (java.io.PipedOutputStream.))
                                              :out (java.io.ByteArrayOutputStream.) :origin :socket})
        other #(#'serve/open-connection! eng {:write! (constantly true) :origin :websocket :approvals true})]
    (is (some? (stream)))
    (is (some? (other)))
    (is (nil? (stream)) "the socket's third is refused")
    (is (nil? (other)) "and so is the listener's")))

(deftest a-connection-whose-client-stops-reading-is-closed-past-the-backlog
  ;; The WebSocket's writer blocks on a client that does not read, as the
  ;; socket's does, so what waits for it is bounded the same way.
  (let [eng (#'serve/engine nil :service false)
        closed (promise)
        stuck (promise)]
    (with-redefs [serve/write-backlog 1000]
      (let [{:keys [send!]} (#'serve/open-connection! eng {:write! (fn [_] @stuck true) :origin :websocket
                                                           :close-transport! #(deliver closed true)})]
        (dotimes [_ 20] (send! (apply str (repeat 100 "x"))))
        (is (deref closed 2000 false) "relay gave up on it")
        (deliver stuck true)))))

;; --- Codex round 1 (relay#66)

(defn- in-process-listener
  "The WebSocket listener of an engine with no system, on a port the OS
  picks: [engine port server]."
  []
  (let [eng (#'serve/engine nil :service false)
        server (#'serve/websocket-listener! eng {:port 0 :token ws-token :allowed-origins [] :default-role "hub"})]
    [eng (.getLocalPort ^java.net.ServerSocket server) server]))

(deftest ^:volley/serial whatever-ends-a-websocket-reader-frees-its-place
  (testing "a reader that throws"
    (let [[eng port server] (in-process-listener)]
      (try
        (with-redefs [relay.websocket/read-message (fn [& _] (throw (RuntimeException. "boom")))]
          (let [c (upgraded port)]
            (try
              (is (= -1 (try (.read ^java.io.InputStream (:in c)) (catch java.io.IOException _ -1))) "it is closed")
              (is (wait-for #(empty? @(:conns eng)) 5000) "and its place is free")
              (finally (.close ^java.net.Socket (:sock c))))))
        (finally (.close ^java.net.ServerSocket server)))))
  (testing "a length that would overflow, after a fragment"
    (let [[eng port server] (in-process-listener)]
      (try
        (let [c (upgraded port)
              out ^java.io.OutputStream (:out c)
              m (byte-array [1 2 3 4])
              header (doto (java.nio.ByteBuffer/allocate 14)
                       (.put (unchecked-byte 0x80)) (.put (unchecked-byte (bit-or 0x80 127)))
                       (.putLong Long/MAX_VALUE) (.put m))]
          (try
            (.write out (relay.websocket/frame-bytes :text (.getBytes "x") m false))
            (.write out (.array header))
            (.flush out)
            (is (= [true true 1009 true] (close-code c)))
            (is (wait-for #(empty? @(:conns eng)) 5000) "its place is free")
            (finally (.close ^java.net.Socket (:sock c)))))
        (finally (.close ^java.net.ServerSocket server))))))

(deftest ^:volley/serial a-message-that-stalls-anywhere-is-stopped-at-its-deadline
  (let [m (byte-array [1 2 3 4])
        frame (relay.websocket/frame-bytes :text (.getBytes "{\"jsonrpc\":\"2.0\",\"method\":\"x\"}") m true)
        long-frame (relay.websocket/frame-bytes :text (byte-array 300) m true)
        fragment (relay.websocket/frame-bytes :text (.getBytes "x") m false)]
    (doseq [[what ^bytes sent] [["partway through the first frame's header" (java.util.Arrays/copyOfRange long-frame 0 3)]
                                ["partway through its mask" (java.util.Arrays/copyOfRange frame 0 4)]
                                ["partway through a single-frame message's payload" (java.util.Arrays/copyOfRange long-frame 0 100)]
                                ["after a whole fragment, before the next" fragment]]]
      (testing what
        (let [[eng port server] (in-process-listener)]
          (try
            (with-redefs [relay.websocket/max-message-ms 400]
              (let [c (upgraded port)
                    out ^java.io.OutputStream (:out c)
                    t0 (System/currentTimeMillis)]
                (try
                  (.write out sent)
                  (.flush out)
                  (is (= -1 (try (.read ^java.io.InputStream (:in c)) (catch java.io.IOException _ -1))) "closed")
                  (let [took (- (System/currentTimeMillis) t0)]
                    (is (<= 350 took 3000) (str "at the deadline, after " took " ms")))
                  (is (wait-for #(empty? @(:conns eng)) 5000) "and its place is free")
                  (finally (.close ^java.net.Socket (:sock c))))))
            (finally (.close ^java.net.ServerSocket server))))))))

(deftest ^:volley/serial an-idle-connection-between-messages-is-never-closed
  (let [[eng port server] (in-process-listener)
        m (byte-array [1 2 3 4])]
    (try
      (with-redefs [relay.websocket/max-message-ms 200]
        (let [c (upgraded port)
              out ^java.io.OutputStream (:out c)]
          (try
            (.write out (relay.websocket/frame-bytes :text (.getBytes "{\"jsonrpc\":\"2.0\",\"method\":\"x\"}") m true))
            (.write out (relay.websocket/frame-bytes :ping (.getBytes "p") m true))
            (.flush out)
            (Thread/sleep 1500)
            (is (= 1 (count @(:conns eng))) "still connected after many deadlines")
            (testing "and it still takes a message"
              (.write out (relay.websocket/frame-bytes :ping (.getBytes "q") m true))
              (.flush out)
              (let [in ^java.io.DataInputStream (:in c)]
                ;; The two pongs, in order.
                (is (= [0x8a 1 (int \p)] [(.readUnsignedByte in) (.readUnsignedByte in) (.readUnsignedByte in)]))
                (is (= [0x8a 1 (int \q)] [(.readUnsignedByte in) (.readUnsignedByte in) (.readUnsignedByte in)]))))
            (finally (.close ^java.net.Socket (:sock c))))))
      (finally (.close ^java.net.ServerSocket server)))))

(deftest ^:volley/serial a-token-command-that-hangs-or-says-too-much-gives-no-listener
  (let [tmp (str (fs/create-temp-dir {:prefix "relay-token"}))
        pidf (str tmp "/sleeper.pid")]
    (try
      (doseq [[what command why] [["one that hangs" ["/bin/sh" "-c" (str "sleep 600 & echo $! > " pidf "; wait")]
                                   "did not finish within 10 seconds"]
                                  ["one that prints a lot" ["/usr/bin/yes"] "printed more than 4096 bytes"]]]
        (testing what
          (let [port (free-port)]
            (with-service
              (fn [service]
                (testing "the socket works while the token command runs"
                  (let [a (connect! :role "clerk")]
                    (try (is (= "s1" (get-in (new-session! a "clerk") ["result" "sessionId"])))
                         (finally (done! a)))))
                (Thread/sleep 11000)
                (is (not (port-open? port)))
                (is (= [] (listening service)))
                (let [err (:err (stop-service! service))]
                  (is (str/includes? err (str "no WebSocket listener: its :token-command " why)) err)))
              nil (ws-settings port {:token-command command})))))
      (testing "what the hanging command started was killed too"
        (let [pid (parse-long (str/trim (slurp pidf)))]
          (is (not (.isPresent (java.lang.ProcessHandle/of pid))))))
      (finally (fs/delete-tree tmp)))))

;; --- reviews (relay#79)

(defn- review-flow!
  "A client that declares relay/review, on a conversation whose agent asks
  a permission (PERM): its allow without the review's digest is refused
  and the request sent again; with it, the call is allowed."
  [c keeps]
  (is (= keeps (get-in (client/request! c "initialize" {:protocolVersion 1 :clientCapabilities {:_meta {"relay/review" 1}}})
                       ["result" "_meta" "relay/keeps"]))
      "the mode this test is about")
  (client/request! c "session/new" {:cwd *dir* :mcpServers [] :_meta {"relay/role" "clerk"}})
  (let [id (client/request-async! c "session/prompt" (prompt "PERM please"))
        req (client/await! c #(= "session/request_permission" (get % "method")) 10000)
        {t "text" h "sha256"} (get-in req ["params" "_meta" "relay/review"])]
    (is (str/starts-with? (str t) "Permission request from role \"clerk\"\nTool \"post\"") (pr-str req))
    (is (= h (core/sha256 t)))
    (#'client/send! c {:jsonrpc "2.0" :id (get req "id") :result {:outcome {:outcome "selected" :optionId "a"}}})
    (let [again (client/await! c #(and (= "session/request_permission" (get % "method"))
                                       (get-in % ["params" "_meta" "relay/refused"]))
                               10000)]
      (is again "the allow without the digest is refused, and the request sent again")
      (is (not-any? #(str/starts-with? (str %) "PERMITTED") (texts c)) "nothing reached the agent")
      (#'client/send! c {:jsonrpc "2.0" :id (get again "id")
                         :result {:outcome {:outcome "selected" :optionId "a"} :_meta {"relay/review" h}}})
      (is (= "end_turn" (get-in (client/response c id 10000) ["result" "stopReason"])))
      (is (some #{"PERMITTED a"} (texts c))))))

(deftest ^:volley/serial a-reviewing-client-allows-only-what-it-was-sent-through-the-service
  (with-service
    (fn [_]
      (let [c (connect! :role "clerk" :approvals true)]
        (try (review-flow! c true) (finally (done! c)))))))

(deftest ^:volley/serial a-reviewing-client-allows-only-what-it-was-sent-in-relay-acp-alone
  (with-service
    (fn [service]
      ;; No service for this one: relay acp runs the hub itself.
      (stop-service! service)
      (let [c (client/start! [support/relay-bin "acp" "clerk" "--approvals" "--data" (data)
                              "--socket" (str *socket* ".none")]
                             {:dir *dir* ::client/manual true})]
        (try (review-flow! c false) (finally (done! c)))))))
