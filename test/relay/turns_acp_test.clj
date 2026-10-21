(ns relay.turns-acp-test
  "Turns over relay acp (relay#63): each prompt starts a turn of the
  conversation before the agent sees it, and the agent's answer ends it.
  A prompt sent while a wake of the role works is answered with an error
  and goes no further. The agent is a small script that answers every
  prompt at once, with an error when the prompt says FAIL."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.acp-client :as client]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.test-support :as support]
            [relay.workspace :as workspace]))

(def agent-script
  (str "#!/usr/bin/perl\n"
       "$| = 1;\n"
       "print qq({\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s1\",\"update\":"
       "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"ALIVE\"}}}}\\n);\n"
       "while (my $l = <STDIN>) {\n"
       "  my ($id) = $l =~ /\"id\":(\\d+)/;\n"
       "  if ($l =~ /\"method\":\"initialize\"/) { print qq({\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"protocolVersion\":1}}\\n); next; }\n"
       "  if ($l =~ /\"method\":\"session\\/new\"/) { print qq({\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"sessionId\":\"s1\"}}\\n); next; }\n"
       "  next unless $l =~ /\"method\":\"session\\/prompt\"/;\n"
       "  print qq({\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s1\",\"update\":"
       "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"GOT $id\"}}}}\\n);\n"
       "  if ($l =~ /FAIL/) { print qq({\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":{\"code\":-32603,\"message\":\"boom\"}}\\n); }\n"
       "  else { print qq({\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"stopReason\":\"end_turn\"}}\\n); }\n"
       "}\n"))

(defn- with-relay [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-turns-acp"}))
        data (str dir "/data")
        agent (str dir "/agent.pl")]
    (spit agent agent-script)
    (fs/set-posix-file-permissions agent "rwx------")
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :a
                   :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin agent}}
                   :notify {:kind :none}}))
    (try
      (let [sys (inbound/system data)]
        (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title "Clerk" :instructions "x"}})
        (f dir sys))
      (finally (fs/delete-tree dir)))))

(defn- prompt [text]
  {:sessionId "s1" :prompt [{:type "text" :text text}]})

(defn- turn-events [sys]
  (filter #(str/starts-with? (:type %) "conversation/turn") (log/read-events (:data-dir sys))))

(deftest what-relay-reads-of-an-answer-to-a-prompt
  (is (= ["end_turn" "error" "other"]
         (map workspace/stop-reason ["{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"stopReason\":\"end_turn\"}}"
                                     "{\"jsonrpc\":\"2.0\",\"id\":4,\"error\":{\"code\":1,\"message\":\"m\"}}"
                                     "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}"]))))

(defn- gots [c]
  (count (filter #(str/starts-with? (str (get-in % ["params" "update" "content" "text"])) "GOT ") @(:messages c))))

(deftest a-prompt-is-a-turn-and-queues-behind-scheduled-work
  (with-relay
    (fn [dir sys]
      (let [c (client/start! [support/relay-bin "acp" "clerk" "--data" (:data-dir sys)] {:dir dir})
            state #(inbound/current-state sys)]
        (try
          (is (= "s1" (client/handshake! c dir)))
          (is (client/await! c #(= "ALIVE" (get-in % ["params" "update" "content" "text"])) 10000)
              "what the agent said before the session opened is replayed")
          (let [chat (:id (core/open-conversation (state) "clerk"))]
            (testing "an idle conversation leaves its role free"
              (is (not (core/busy? (state) "clerk"))))
            (testing "a prompt runs as a turn, ended before the client reads the answer"
              (let [r (client/request! c "session/prompt" (prompt "Hello."))]
                (is (= "end_turn" (get-in r ["result" "stopReason"])) (pr-str r))
                (is (= [["conversation/turn-started" {:wake chat :role "clerk" :session "s1" :turn 1}]
                        ["conversation/turn-ended" {:wake chat :role "clerk" :turn 1 :stop-reason "end_turn"}]]
                       (map (juxt :type :data) (turn-events sys))))
                (is (= ["operator" "dispatcher"] (map :actor (turn-events sys))))))
            (testing "while a wake of the role works, a prompt waits and does not reach the agent"
              (let [started (inbound/submit! sys {:type :wake/start :actor "operator"
                                                  :args (merge {:role "clerk" :note "Busy."} (dispatch/current-process))})
                    wake (:seq (first started))
                    seen (gots c)
                    id (client/request-async! c "session/prompt" (prompt "Are you there?"))]
                (Thread/sleep 1000)
                (is (nil? (client/response c id 10)) "no answer yet")
                (is (= seen (gots c)) "the agent never saw it")
                (is (= 2 (count (turn-events sys))) "and no turn started")
                (testing "and once the wake ends, it runs"
                  (inbound/submit! sys {:type :wake/end :actor "dispatcher" :args {:wake wake :outcome "ran" :said ""}})
                  (let [r (client/response c id 10000)]
                    (is (= "end_turn" (get-in r ["result" "stopReason"])) (pr-str r))
                    (is (= 2 (get-in (last (turn-events sys)) [:data :turn])))))))
            (testing "an error from the agent ends the turn too"
              (let [r (client/request! c "session/prompt" (prompt "FAIL"))]
                (is (= -32603 (get-in r ["error" "code"])) (pr-str r))
                (is (= {:turn 3 :stop-reason "error"} (select-keys (:data (last (turn-events sys))) [:turn :stop-reason])))))
            (testing "a prompt for a session that is not live is refused"
              (let [r (client/request! c "session/prompt" {:sessionId "nope" :prompt [{:type "text" :text "x"}]})]
                (is (= -32602 (get-in r ["error" "code"])) (pr-str r))))
            (client/close-input! c)
            (is (= 0 (client/wait! c 20000)))
            (is (not (core/open? (state) "clerk")) "the conversation ended")
            (is (= :idle (get-in (state) [:wakes chat :turn]))))
          (finally (client/sever! c)))))))
