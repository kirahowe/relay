(ns relay.serve-support
  "Running relay serve for the serve tests: a service on a socket of its
  own under /tmp, clients through relay acp's bridge, and a fake agent (a
  perl script) behind each conversation."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.test :refer [is]]
            [relay.acp-client :as client]
            [relay.inbound :as inbound]
            [relay.log :as log]
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

(defn data [] (:data-dir *sys*))
(defn state [] (inbound/current-state *sys*))
(defn events [] (log/read-events (data)))

(defn wait-for [pred & [timeout-ms]]
  (let [deadline (+ (System/currentTimeMillis) (or timeout-ms 15000))]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline) (Thread/sleep 50) (recur))))))

(defn said
  "What the service has written to stderr so far. It goes to a file, so a
  test can wait for a line without stopping the service."
  [service]
  (let [f (:err-file service)] (if (fs/exists? f) (slurp f) "")))

(defn start-service! []
  (let [err-file (str (fs/create-temp-file {:dir *dir* :prefix "serve-err"}))
        proc (-> (p/process [support/relay-bin "serve" "--data" (data) "--socket" *socket* "--run-dir" *run-dir*]
                            {:out :string :err (fs/file err-file)})
                 (assoc :err-file err-file))]
    (is (wait-for #(and (fs/exists? *socket*)
                        (try (.close (java.nio.channels.SocketChannel/open
                                      (java.net.UnixDomainSocketAddress/of ^String *socket*)))
                             true
                             (catch Exception _ false)))
                  30000)
        "the service listens")
    proc))

(defn stop-service!
  "Stops the service. Returns its result, with what it said on stderr."
  [proc]
  (.destroy ^Process (:proc proc))
  (let [r (deref proc 30000 :timeout)
        r (if (= :timeout r) r (assoc r :err (said proc)))]
    (is (not= :timeout r) "the service stops")
    (when-not (= :timeout r) (is (= 0 (:exit r)) (:err r)))
    r))

(defn with-service
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

(defn connect!
  "A client through relay acp's bridge."
  [& {:keys [role approvals]}]
  (client/start! (cond-> [support/relay-bin "acp"]
                   role (conj role)
                   true (into ["--data" (data) "--socket" *socket*])
                   approvals (conj "--approvals"))
                 {:dir *dir* ::client/manual true}))

(defn new-session!
  "initialize and session/new for the role. Returns the answer."
  [c role]
  (client/request! c "initialize" {:protocolVersion 1 :clientCapabilities {}})
  (client/request! c "session/new" {:cwd *dir* :mcpServers [] :_meta {"relay/role" role}}))

(defn prompt [text] {:sessionId "s1" :prompt [{:type "text" :text text}]})

(defn texts
  "The text of every session/update a client got, in order."
  [c]
  (keep #(when (= "session/update" (get % "method")) (get-in % ["params" "update" "content" "text"])) @(:messages c)))

(defn methods-of [c] (keep #(get % "method") @(:messages c)))

(defn done! [& cs] (doseq [c cs] (client/close-input! c) (client/wait! c 10000) (client/sever! c)))
