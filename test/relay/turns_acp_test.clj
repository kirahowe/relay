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
       "  next unless $l =~ /\"method\":\"session\\/prompt\"/;\n"
       "  my ($id) = $l =~ /\"id\":(\\d+)/;\n"
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

(defn- got? [c id]
  (some #(= (str "GOT " id) (get-in % ["params" "update" "content" "text"])) @(:messages c)))

(deftest what-relay-reads-of-a-prompt-and-its-answer
  (is (= {:id 4 :key [:number "4"] :session "s1"}
         (workspace/prompt-request "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"s1\"}}")))
  (is (= {:id {} :key nil :session nil}
         (workspace/prompt-request "{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"session/prompt\",\"params\":[]}")))
  (is (nil? (workspace/prompt-request "{\"jsonrpc\":\"2.0\",\"method\":\"session/prompt\",\"params\":{}}"))
      "a notification is no request")
  (is (nil? (workspace/prompt-request "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session/cancel\"}")))
  (is (= ["end_turn" "error" "other"]
         (map workspace/stop-reason ["{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"stopReason\":\"end_turn\"}}"
                                     "{\"jsonrpc\":\"2.0\",\"id\":4,\"error\":{\"code\":1,\"message\":\"m\"}}"
                                     "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}"])))
  (testing "only an answer to a prompt that was passed on ends a turn, once"
    (let [conv (workspace/prompt-passed workspace/conversation [:number "4"])
          [conv a] (workspace/prompt-answered conv [:number "5"])
          [conv b] (workspace/prompt-answered conv [:number "4"])
          [conv c] (workspace/prompt-answered conv [:number "4"])]
      (is (= [false true false] [a b c]))
      (is (= {} (:prompts conv))))))

(deftest a-prompt-is-a-turn-and-waits-for-scheduled-work
  (with-relay
    (fn [dir sys]
      (let [c (client/start! [support/relay-bin "acp" "clerk" "--data" (:data-dir sys)] {:dir dir})
            state #(inbound/current-state sys)]
        (try
          (is (client/await! c #(= "session/update" (get % "method")) 30000) "the agent is running")
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
            (testing "while a wake of the role works, a prompt is refused and never reaches the agent"
              (let [started (inbound/submit! sys {:type :wake/start :actor "operator"
                                                  :args (merge {:role "clerk" :note "Busy."} (dispatch/current-process))})
                    wake (:seq (first started))
                    id (inc @(:next-id c))
                    r (client/request! c "session/prompt" (prompt "Are you there?"))]
                (is (= id (get r "id")))
                (is (= -32000 (get-in r ["error" "code"])) (pr-str r))
                (is (str/includes? (get-in r ["error" "message"]) "busy with scheduled work"))
                (Thread/sleep 300)
                (is (not (got? c id)) "the agent never saw it")
                (is (= 2 (count (turn-events sys))) "and no turn started")
                (testing "and once the wake ends, the next one runs"
                  (inbound/submit! sys {:type :wake/end :actor "dispatcher" :args {:wake wake :outcome "ran" :said ""}})
                  (let [r (client/request! c "session/prompt" (prompt "Now?"))]
                    (is (= "end_turn" (get-in r ["result" "stopReason"])) (pr-str r))
                    (is (= 2 (get-in (last (turn-events sys)) [:data :turn])))))))
            (testing "an error from the agent ends the turn too"
              (let [r (client/request! c "session/prompt" (prompt "FAIL"))]
                (is (= -32603 (get-in r ["error" "code"])) (pr-str r))
                (is (= {:turn 3 :stop-reason "error"} (select-keys (:data (last (turn-events sys))) [:turn :stop-reason])))))
            (testing "a prompt with no session the log can keep is refused"
              (let [r (client/request! c "session/prompt" {:prompt [{:type "text" :text "x"}]})]
                (is (= -32000 (get-in r ["error" "code"])) (pr-str r))))
            (client/close-input! c)
            (is (= 0 (client/wait! c 20000)))
            (is (not (core/open? (state) "clerk")) "the conversation ended")
            (is (= :idle (get-in (state) [:wakes chat :turn]))))
          (finally (client/sever! c)))))))
