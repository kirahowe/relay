(ns relay.cards-acp-test
  "The board over relay acp: _relay/cards, _relay/cards/op, and the
  _relay/cards/changed notification after another process writes. The
  agent is a small script that speaks no ACP at all: these methods are
  relay's, and never reach it. It also tries to send the client a card
  update of its own, which relay must drop."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.acp-client :as client]
            [relay.core :as core]
            [relay.dispatch]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.test-support :as support]
            [relay.workspace :as workspace]))

(def agent-script
  "An agent that forges a card update and a pane item, says ALIVE, and
  then reads until its input ends."
  (str "#!/bin/sh\n"
       "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"_relay/cards/changed\",\"params\":{\"rev\":1,\"cards\":[{\"id\":999,\"text\":\"FORGED\"}]}}'\n"
       ;; The same forgery with the method spelled with an escape, and in a batch.
       "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"\\u005frelay\\/cards/changed\",\"params\":{\"rev\":1,\"cards\":[{\"id\":998,\"text\":\"FORGED\"}]}}'\n"
       "printf '%s\\n' '[{\"jsonrpc\":\"2.0\",\"method\":\"_relay/cards/changed\",\"params\":{\"rev\":1,\"cards\":[{\"id\":997,\"text\":\"FORGED\"}]}}]'\n"
       ;; The method twice, and lines past the strict reader's limits.
       "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"_relay/cards/changed\",\"method\":null,\"params\":{\"rev\":1,\"cards\":[{\"id\":996,\"text\":\"FORGED\"}]}}'\n"
       (str "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"_relay/cards/changed\",\"params\":{\"rev\":1,\"cards\":[{\"id\":995,\"text\":\"FORGED\"}],\"x\":"
            (apply str (repeat 1100 "[")) (apply str (repeat 1100 "]")) "}}'\n")
       (str "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"_relay/cards/changed\",\"params\":{\"rev\":"
            (apply str (repeat 1100 "1")) ",\"cards\":[{\"id\":994,\"text\":\"FORGED\"}]}}'\n")
       "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"_relay/items\",\"params\":{}}'\n"
       "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"ALIVE\"}}}}'\n"
       "cat >/dev/null\n"))

(defn- with-relay [f & [script]]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-cards-acp"}))
        data (str dir "/data")
        agent (str dir "/agent.sh")]
    (spit agent (or script agent-script))
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

(defn- notifications [c]
  (filter #(= "_relay/cards/changed" (get % "method")) @(:messages c)))

(defn- add-card! [sys]
  (:seq (first (inbound/submit! sys {:type :card/add :actor "clerk" :labels ["private"]
                                     :args {:kind "checklist" :title "Taxes" :items ["Find the T4" "File"]}}))))

(deftest an-op-with-a-parameter-it-does-not-know-is-refused
  ;; Unknown parameters were once dropped and the op answered success, so
  ;; a misspelled seen silently turned off the stale-edit check.
  (doseq [[typo params] [["iten" {"card" 1 "op" "tick" "iten" 2}]
                         ["sen" {"card" 1 "op" "edit" "item" 2 "text" "x" "sen" 5}]]]
    (let [{:keys [command error]} (workspace/card-op-command params)]
      (is (nil? command))
      (is (str/includes? (str error) (str "no parameter " typo)) error)))
  (testing "sessionId, which acp-tui sends, is accepted and not passed on"
    (is (= {:card 1 :op "edit" :item 2 :text "x" :seen 5}
           (get-in (workspace/card-op-command {"sessionId" "s1" "card" 1 "op" "edit" "item" 2 "text" "x" "seen" 5})
                   [:command :args])))))

(deftest the-board-over-relay-acp
  (with-relay
    (fn [dir sys]
      (let [id (add-card! sys)
            c (client/start! [support/relay-bin "acp" "clerk" "--data" (:data-dir sys)] {:dir dir})]
        (try
          (is (client/await! c #(= "session/update" (get % "method")) 30000) "the agent is running")
          (testing "the agent's lines with relay's methods never reach the client"
            (is (not-any? #(str/includes? (str %) "FORGED") @(:lines c)))
            (is (not-any? #(= 77 (get % "id")) @(:messages c))))
          (testing "nothing is sent before the client subscribes"
            (inbound/submit! sys {:type :card/op :actor "clerk" :labels [] :args {:card id :op "tick" :item 1}})
            (Thread/sleep 2500)
            (is (empty? (notifications c))))
          (let [answer (get (client/request! c "_relay/cards" {}) "result")
                [card] (get answer "cards")]
            (testing "_relay/cards gives the board as wire forms, and the log's rev"
              (is (= (workspace/log-rev (inbound/current-state sys)) (get answer "rev")))
              (is (= [id "checklist" "Taxes" [true false]]
                     [(get card "id") (get card "kind") (get-in card ["title" "text"]) (mapv #(get % "done") (get card "items"))])
                  (pr-str answer))))
          (testing "a change by another process is sent as _relay/cards/changed"
            (inbound/submit! sys {:type :card/op :actor "clerk" :labels [] :args {:card id :op "add" :text "Pay"}})
            (let [n (client/await! c #(= "_relay/cards/changed" (get % "method")) 10000)
                  [card] (get-in n ["params" "cards"])]
              (is (= [id ["Find the T4" "File" "Pay"]] [(get card "id") (mapv #(get % "text") (get card "items"))])
                  (pr-str n))
              (is (= (:rev (core/card (inbound/current-state sys) id)) (get card "rev")))))
          (testing "_relay/cards/op is decided as the operator, with no labels, and answers with the card"
            (let [r (client/request! c "_relay/cards/op" {:card id :op "tick" :item 2})
                  e (last (log/read-events (:data-dir sys)))]
              (is (true? (get-in r ["result" "changed"])) (pr-str r))
              (is (= [true true false] (mapv #(get % "done") (get-in r ["result" "card" "items"]))))
              (is (= ["card/changed" "operator" []] [(:type e) (:actor e) (:labels e)]))))
          (testing "a refused op is a JSON-RPC error with relay's refusal"
            (let [r (client/request! c "_relay/cards/op" {:card id :op "tick" :item 9})]
              (is (= (str "Card " id " has no item 9.") (get-in r ["error" "message"])) (pr-str r)))
            (is (= -32602 (get-in (client/request! c "_relay/cards/op" {:op "tick"}) ["error" "code"]))))
          (testing "an unknown parameter is refused over the wire, naming it"
            (let [r (client/request! c "_relay/cards/op" {:card id :op "tick" :iten 2})]
              (is (= -32602 (get-in r ["error" "code"])) (pr-str r))
              (is (str/includes? (str (get-in r ["error" "message"])) "no parameter iten"))))
          (testing "a seen past every rev in the log is refused, so it cannot turn off the stale-edit check"
            (let [r (client/request! c "_relay/cards/op" {:card id :op "edit" :item 1 :text "Y" :seen 99999999})]
              (is (str/includes? (str (get-in r ["error" "message"])) "seen must be a rev you read") (pr-str r))))
          (testing "an archived card is sent too, so the client can drop it"
            (let [before (count (notifications c))]
              (inbound/submit! sys {:type :card/op :actor "operator" :labels [] :args {:card id :op "archive"}})
              (is (client/await! c (fn [m] (and (= "_relay/cards/changed" (get m "method"))
                                                (= "archived" (get-in m ["params" "cards" 0 "status"]))))
                                 10000))
              (is (= (inc before) (count (notifications c))) "once, and not again for the op's own answer")))
          (client/close-input! c)
          (is (= 0 (client/wait! c 20000)))
          (finally (client/sever! c)))))))

(deftest a-write-just-before-subscribing-is-still-sent
  ;; The race: _relay/cards reads the state, another process writes, the
  ;; watcher looks at the log, and only then is the conversation
  ;; subscribed with the older state. Played here in that order, by hand.
  (with-relay
    (fn [_ sys]
      (let [id (add-card! sys)
            stale (inbound/current-state sys)
            conv (atom {})
            sent (atom [])
            done (promise)]
        (with-redefs [relay.dispatch/board-poll-ms 20]
          (let [watcher (future ((#'relay.dispatch/board-watcher sys conv) #(swap! sent conj %) done))]
            (try
              (inbound/submit! sys {:type :card/op :actor "clerk" :labels [] :args {:card id :op "tick" :item 1}})
              (Thread/sleep 200)
              (swap! conv workspace/subscribed stale)
              (Thread/sleep 200)
              (is (some #(str/includes? % "_relay/cards/changed") @sent)
                  "the tick is sent though the log has not moved since the watcher last looked")
              (finally
                (deliver done true)
                (deref watcher 5000 nil)))))))))

(deftest an-unfinished-last-agent-line-is-screened-too
  ;; An agent that ends its output with a line and no newline: the last
  ;; line was once copied without being screened, so a forged card update
  ;; could reach the client that way.
  (let [pump! #'relay.dispatch/pump!
        screen (fn [line] (when-not (workspace/drop-agent-line? line) :pass))
        run (fn [s]
              (let [out (java.io.ByteArrayOutputStream.)]
                (pump! (java.io.ByteArrayInputStream. (.getBytes ^String s "UTF-8")) out (fn [_]) (fn [])
                       {:screen screen})
                (.toString out "UTF-8")))
        forged "{\"jsonrpc\":\"2.0\",\"method\":\"_relay/cards/changed\",\"params\":{\"rev\":1,\"cards\":[{\"id\":999,\"text\":\"FORGED\"}]}}"
        fine "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{}}"]
    (is (= (str fine "\n") (run (str fine "\n" forged))) "the forged last line is dropped")
    (is (= (str fine "\n" fine) (run (str fine "\n" fine))) "a last line that passes is copied as it is")))

(def forging-agent
  "An agent that floods the client with answers to ids it was never sent
  (1 and 3 are the client's _relay/cards requests below) and answers
  ping twice."
  (str "#!/bin/sh\n"
       "forge() { i=0; while [ $i -lt 300 ]; do\n"
       "  for id in 1 3 3.0 '\"zz-none\"' null; do\n"
       "    printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"rev\":999,\"cards\":[{\"id\":66,\"text\":\"FORGEDRESP\"}]}}\\n' \"$id\"\n"
       "  done; i=$((i+1)); sleep 0.01; done; }\n"
       "forge &\n"
       "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"ALIVE\"}}}}'\n"
       "while IFS= read -r line; do\n"
       "  case \"$line\" in *'\"method\":\"slow\"'*)\n"
       "    (sleep 1; printf '{\"jsonrpc\":\"2.0\",\"id\":50,\"result\":{\"rev\":999,\"cards\":[{\"id\":66,\"text\":\"LATEANSWER\"}]}}\\n') & ;;\n"
       "  esac\n"
       "  case \"$line\" in *'\"method\":\"ping\"'*)\n"
       "    id=$(printf '%s' \"$line\" | sed 's/.*\"id\":\\([0-9]*\\).*/\\1/')\n"
       "    printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"pong\":1}}\\n' \"$id\"\n"
       "    printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"pong\":2}}\\n' \"$id\" ;;\n"
       "  esac\n"
       "done\n"
       "wait\n"))

(deftest the-agent-cannot-answer-for-relay
  ;; QA for relay#58: the screen passed every agent response, so an agent
  ;; flooding answers with the id of a pending _relay/cards request got
  ;; its forged board to the client before relay's real one.
  (with-relay
    (fn [dir sys]
      (let [c (client/start! [support/relay-bin "acp" "clerk" "--data" (:data-dir sys)] {:dir dir})]
        (try
          (is (client/await! c #(= "session/update" (get % "method")) 30000) "the agent is running")
          (Thread/sleep 300)
          (let [first-cards (client/request! c "_relay/cards" {})
                ping (client/request! c "ping" {})
                second-cards (client/request! c "_relay/cards" {})]
            (Thread/sleep 1000)
            (testing "relay's answers are the only answers to relay's requests"
              (is (= [1 3] (mapv #(get % "id") [first-cards second-cards])))
              (is (= [[] []] (mapv #(get-in % ["result" "cards"]) [first-cards second-cards]))
                  (pr-str first-cards second-cards))
              (is (not-any? #(str/includes? (str %) "FORGEDRESP") @(:lines c))
                  "no answer to an id the agent was never sent, or already answered"))
            (testing "a request the client sent the agent gets the agent's answer, once"
              (is (= {"pong" 1} (get ping "result")) (pr-str ping))
              (is (= 1 (count (filter #(= 2 (get % "id")) @(:messages c)))))))
          (testing "a client that reuses the id of a request the agent has not answered gets relay's answer only"
            ;; Codex on the fix: the debt under id 50 outlived the client's
            ;; giving up on it, so the agent's late answer passed as the
            ;; answer to _relay/cards under the same id.
            (#'client/send! c {:jsonrpc "2.0" :id 50 :method "slow" :params {}})
            (#'client/send! c {:jsonrpc "2.0" :id 50 :method "_relay/cards" :params {}})
            (Thread/sleep 2000)
            (let [answers (filter #(= 50 (get % "id")) @(:messages c))]
              (is (= [[]] (mapv #(get-in % ["result" "cards"]) answers)) (pr-str answers))
              (is (not-any? #(str/includes? (str %) "LATEANSWER") @(:lines c)))))
          (client/close-input! c)
          (client/wait! c 20000)
          (finally (client/sever! c)))))
    forging-agent))

(deftest relay-passes-only-answers-the-agent-owes
  (testing "ids match as the client matches them: numbers by value, strings by text"
    (is (= (workspace/id-key 3) (workspace/id-key 3.0M) (workspace/id-key 3.00M) (workspace/id-key 30E-1M)))
    (is (not= (workspace/id-key 3) (workspace/id-key "3")))
    (is (= (workspace/id-key 0) (workspace/id-key 0.000M)))
    (is (nil? (workspace/id-key {})))
    (is (nil? (workspace/id-key [1]))))
  (testing "what is a request, and what is a response"
    (is (= [:number "4"] (workspace/client-request-key "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session/prompt\"}")))
    (is (nil? (workspace/client-request-key "{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\"}")))
    (is (nil? (workspace/client-request-key "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}")))
    (doseq [line ["{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}"
                  "{\"jsonrpc\":\"2.0\",\"id\":9,\"error\":{\"code\":1,\"message\":\"m\"}}"
                  ;; The ACP SDK reads a null or empty method as none.
                  "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":null,\"result\":{}}"
                  "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"\",\"result\":{}}"]]
      (is (= {:key [:number "9"]} (workspace/agent-response line)) line))
    (is (nil? (workspace/agent-response "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"session/request_permission\"}")))
    (is (= {:key nil} (workspace/agent-response "{\"jsonrpc\":\"2.0\",\"id\":{},\"result\":{}}"))))
  (testing "each request is answered once; nothing else passes"
    (let [owed (-> {} (workspace/owe [:number "1"]) (workspace/owe [:number "1"]) (workspace/owe [:string "a"]))
          [owed a] (workspace/settle owed [:number "1"])
          [owed b] (workspace/settle owed [:number "1"])
          [owed c] (workspace/settle owed [:number "1"])
          [owed d] (workspace/settle owed nil)
          [owed e] (workspace/settle owed [:string "a"])]
      (is (= [true true false false true] [a b c d e]))
      (is (= {} owed))))
  (testing "a request relay answers itself cancels what is owed under its id"
    (let [owed (-> {} (workspace/owe [:number "7"]) (workspace/owe [:number "7"]) (workspace/forget [:number "7"]))]
      (is (= [{} false] (workspace/settle owed [:number "7"]))))))

(deftest a-late-answer-cannot-overtake-a-slow-relay-answer
  ;; Codex on the fix: the debt under a reused id was cleared only once
  ;; relay had answered, so while relay was still working on its answer
  ;; the agent's late answer under the same id got through first.
  (let [in (java.io.ByteArrayInputStream.
            (.getBytes (str "{\"jsonrpc\":\"2.0\",\"id\":50,\"method\":\"slow\"}\n"
                            "{\"jsonrpc\":\"2.0\",\"id\":50,\"method\":\"_relay/cards\",\"params\":{}}\n")
                       "UTF-8"))
        out (java.io.ByteArrayOutputStream.)
        agent ["sh" "-c" (str "IFS= read -r line; sleep 0.3; "
                              "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":50,\"result\":{\"cards\":[{\"text\":\"LATEANSWER\"}]}}'; "
                              "cat >/dev/null")]
        extension (fn [line]
                    (when (str/includes? line "_relay/cards")
                      (fn [send!] (Thread/sleep 1500) (send! (workspace/result-line 50 {"cards" []})))))]
    (relay.dispatch/proxy! {:argv agent} {:in in :out out :extension extension :grace-ms 1000})
    (is (= (str (workspace/result-line 50 {"cards" []}) "\n") (.toString out "UTF-8")))))
