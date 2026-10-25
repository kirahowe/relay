(ns relay.cards-acp-test
  "The board over relay acp: _relay/cards, _relay/cards/op, and the
  _relay/cards/changed notification after another process writes. The
  agent is a small script that speaks no ACP at all: these methods are
  relay's, and never reach it. It also tries to send the client a card
  update of its own, which relay must drop."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.acp :as acp]
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

(defn- conversation-running? [sys]
  (let [deadline (+ (System/currentTimeMillis) 30000)]
    (loop []
      (or (some? (core/open-conversation (inbound/current-state sys) "clerk"))
          (when (< (System/currentTimeMillis) deadline) (Thread/sleep 50) (recur))))))

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
  (testing "the board is the connection's, so a sessionId is refused like any parameter it does not know"
    (let [{:keys [command error]} (workspace/card-op-command {"sessionId" "s1" "card" 1 "op" "tick" "item" 2})]
      (is (nil? command))
      (is (str/includes? (str error) "no parameter sessionId") error))))

(deftest a-cards-request-with-a-parameter-it-does-not-know-is-refused
  (is (nil? (workspace/cards-params-error {})))
  (is (nil? (workspace/cards-params-error {"archived" true})))
  (doseq [bad ["sessionId" "archive"]]
    (is (str/includes? (str (workspace/cards-params-error {bad "x"})) (str "no parameter " bad)))))

(deftest the-board-over-relay-acp
  (with-relay
    (fn [dir sys]
      (let [id (add-card! sys)
            c (client/start! [support/relay-bin "acp" "clerk" "--data" (:data-dir sys)] {:dir dir})]
        (try
          ;; The hub passes a session/update only to a client attached to
          ;; the session, and this agent opens none, so the log says when
          ;; it runs.
          (is (conversation-running? sys) "the agent is running")
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
  (3 is the client's second _relay/cards request below, which relay
  answers itself; the hub passes ping on as 1, so 1 is not forged) and
  answers ping twice."
  (str "#!/bin/sh\n"
       "forge() { i=0; while [ $i -lt 300 ]; do\n"
       "  for id in 3 3.0 '\"zz-none\"' null; do\n"
       "    printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"rev\":999,\"cards\":[{\"id\":66,\"text\":\"FORGEDRESP\"}]}}\\n' \"$id\"\n"
       "  done; i=$((i+1)); sleep 0.01; done; }\n"
       "forge &\n"
       "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"ALIVE\"}}}}'\n"
       "while IFS= read -r line; do\n"
       "  case \"$line\" in *'\"method\":\"slow\"'*)\n"
       "    sid=$(printf '%s' \"$line\" | sed 's/.*\"id\":\\([0-9]*\\).*/\\1/')\n"
       "    (sleep 1; printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"rev\":999,\"cards\":[{\"id\":66,\"text\":\"LATEANSWER\"}]}}\\n' \"$sid\") & ;;\n"
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
          ;; The hub passes a session/update only to a client attached to
          ;; the session, and this agent opens none, so the log says when
          ;; it runs.
          (is (conversation-running? sys) "the agent is running")
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
          (testing "a client that reuses the id of a request still outstanding is refused, and relay's own method never gets the agent's answer"
            ;; Codex on #58: the debt under id 50 outlived the client's giving
            ;; up on it, so the agent's late answer passed as the answer to
            ;; _relay/cards under the same id. Codex round 2 on #64: the hub
            ;; remapped both, and the agent (which answers the id it was
            ;; sent) still answered the second under 50. An id in use is
            ;; refused now.
            (#'client/send! c {:jsonrpc "2.0" :id 50 :method "slow" :params {}})
            (#'client/send! c {:jsonrpc "2.0" :id 50 :method "_relay/cards" :params {}})
            (Thread/sleep 2000)
            (let [answers (filter #(= 50 (get % "id")) @(:messages c))]
              (is (= [-32600] (keep #(get-in % ["error" "code"]) answers)) (pr-str answers))
              (is (= [[{"id" 66 "text" "LATEANSWER"}]] (keep #(get-in % ["result" "cards"]) answers))
                  "the one answer is slow's own, once")))
          (client/close-input! c)
          (client/wait! c 20000)
          (finally (client/sever! c)))))
    forging-agent))

(deftest relay-passes-only-answers-the-agent-owes
  (testing "ids match as the client matches them: numbers by value, strings by text"
    (is (= (acp/id-key 3) (acp/id-key 3.0M) (acp/id-key 3.00M) (acp/id-key 30E-1M)))
    (is (not= (acp/id-key 3) (acp/id-key "3")))
    (is (= (acp/id-key 0) (acp/id-key 0.000M)))
    (is (nil? (acp/id-key {})))
    (is (nil? (acp/id-key [1]))))
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
                    (when (str/includes? (:text line) "_relay/cards")
                      (fn [send!] (Thread/sleep 1500) (send! (workspace/result-line 50 {"cards" []})))))]
    (relay.dispatch/proxy! {:argv agent} {:in in :out out :extension extension :grace-ms 1000})
    (is (= (str (workspace/result-line 50 {"cards" []}) "\n") (.toString out "UTF-8")))))

(defn- tainted-card! [sys]
  (:seq (first (inbound/submit! sys {:type :card/add :actor "clerk" :labels ["contaminated"]
                                     :args {:kind "checklist" :title "From the web" :items ["a"]}}))))

(deftest an-unknown-trust-parameter-is-refused
  (doseq [[bad params] [["iten" {"card" 1 "rev" 2 "sha256" (apply str (repeat 64 "a")) "iten" 1}]]]
    (let [{:keys [command error]} (workspace/card-trust-command params)]
      (is (nil? command))
      (is (str/includes? (str error) (str "no parameter " bad)) error)))
  (is (= {:kind "card" :ref "7" :version 9 :sha256 (apply str (repeat 64 "a")) :via "chat"}
         (get-in (workspace/card-trust-command {"card" 7 "rev" 9 "sha256" (apply str (repeat 64 "a"))})
                 [:command :args])))
  (testing "a sessionId is refused: the board is the connection's"
    (let [{:keys [command error]} (workspace/card-trust-command {"sessionId" "s" "card" 7 "rev" 9 "sha256" (apply str (repeat 64 "a"))})]
      (is (nil? command))
      (is (str/includes? (str error) "no parameter sessionId") error)))
  (is (= "operator" (get-in (workspace/card-trust-command {"card" 7 "rev" 9 "sha256" (apply str (repeat 64 "a"))})
                            [:command :actor])))
  (doseq [params [{"rev" 9 "sha256" "x"} {"card" 7 "sha256" (apply str (repeat 64 "a"))} {"card" 7 "rev" 9}
                  {"card" 7 "rev" 9 "sha256" "nothex"}]]
    (is (:error (workspace/card-trust-command params)) (pr-str params))))

(deftest _relay-cards-trust-over-relay-acp
  (doseq [approvals [false true]]
    (with-relay
      (fn [dir sys]
        (let [id (tainted-card! sys)
              c (client/start! (into [support/relay-bin "acp" "clerk"]
                                     (concat (when approvals ["--approvals"]) ["--data" (:data-dir sys)]))
                               {:dir dir})]
          (try
            (is (conversation-running? sys))
            (let [[card] (get-in (client/request! c "_relay/cards" {}) ["result" "cards"])
                  params {:card id :rev (get card "rev") :sha256 (get card "sha256")}]
              (is (string? (get card "sha256")) (pr-str card))
              (if-not approvals
                (testing "without approvals it is refused and nothing is trusted"
                  (let [r (client/request! c "_relay/cards/trust" params)]
                    (is (= -32000 (get-in r ["error" "code"])) (pr-str r))
                    (is (str/includes? (str (get-in r ["error" "message"])) "relay trust card"))
                    (is (core/contaminated? (core/card-labels (core/card (inbound/current-state sys) id))))))
                (do
                  (testing "an unknown parameter is refused"
                    (let [r (client/request! c "_relay/cards/trust" (assoc params :iten 1))]
                      (is (= -32602 (get-in r ["error" "code"])) (pr-str r))
                      (is (str/includes? (str (get-in r ["error" "message"])) "no parameter iten"))))
                  (testing "a stale digest trusts nothing"
                    (let [r (client/request! c "_relay/cards/trust" (assoc params :sha256 (apply str (repeat 64 "0"))))]
                      (is (str/includes? (str (get-in r ["error" "message"])) "Nothing was trusted; look again.") (pr-str r))
                      (is (core/contaminated? (core/card-labels (core/card (inbound/current-state sys) id))))))
                  (testing "the operator's trust clears the card, is logged from the chat, and every client is told"
                    (let [r (client/request! c "_relay/cards/trust" params)
                          e (last (log/read-events (:data-dir sys)))]
                      (is (true? (get-in r ["result" "trusted"])) (pr-str r))
                      (is (not (contains? (get-in r ["result" "card"]) "sha256")))
                      (is (= [false] (distinct (map #(get % "outsider") (get-in r ["result" "card" "items"])))))
                      (is (= ["label/trusted" "operator" "chat"] [(:type e) (:actor e) (get-in e [:data :via])]))
                      (is (not (core/contaminated? (core/card-labels (core/card (inbound/current-state sys) id)))))))
                  (testing "a trust made elsewhere reaches a subscribed client as a changed card"
                    (let [id2 (tainted-card! sys)
                          v (core/trusted-version (inbound/current-state sys) "card" (str id2))]
                      (inbound/submit! sys {:type :label/trust :actor "operator" :labels []
                                            :args {:kind "card" :ref (str id2) :version (:version v)
                                                   :sha256 (core/trust-digest (inbound/current-state sys) "card" (str id2))}})
                      (is (client/await! c #(and (= "_relay/cards/changed" (get % "method"))
                                                 (some (fn [x] (and (= id2 (get x "id")) (not (contains? x "sha256"))
                                                                    (> (get x "rev") (:version v))))
                                                       (get-in % ["params" "cards"])))
                                         10000)))))))
            (client/close-input! c)
            (is (= 0 (client/wait! c 20000)))
            (finally (client/sever! c))))))))

;; relay#73: relay acp with no role, the hub in this process, opens no
;; conversation, and its client works the board all the same: the board
;; is the connection's.
(deftest the-board-over-relay-acp-with-no-conversation
  (with-relay
    (fn [dir sys]
      (let [plain (add-card! sys)
            tainted (tainted-card! sys)
            c (client/start! [support/relay-bin "acp" "--approvals" "--data" (:data-dir sys)] {:dir dir})]
        (try
          (is (contains? (get-in (client/request! c "initialize" {:protocolVersion 1 :clientCapabilities {}})
                                 ["result" "_meta"])
                         "relay/hub"))
          (let [cards (get-in (client/request! c "_relay/cards" {}) ["result" "cards"])
                card (first (filter #(= tainted (get % "id")) cards))]
            (is (= #{plain tainted} (set (map #(get % "id") cards))))
            (testing "an op is taken, as the operator's"
              (let [r (client/request! c "_relay/cards/op" {:card plain :op "tick" :item 1})]
                (is (true? (get-in r ["result" "changed"])) (pr-str r))))
            (testing "a trust is taken"
              (let [r (client/request! c "_relay/cards/trust" {:card tainted :rev (get card "rev") :sha256 (get card "sha256")})]
                (is (true? (get-in r ["result" "trusted"])) (pr-str r))))
            (testing "a change elsewhere reaches the client"
              (inbound/submit! sys {:type :card/op :actor "clerk" :labels [] :args {:card plain :op "add" :text "Pay"}})
              (is (client/await! c #(= "_relay/cards/changed" (get % "method")) 10000)))
            (testing "a sessionId is refused on each board method"
              (doseq [[method params] [["_relay/cards" {:sessionId "s"}]
                                       ["_relay/cards/op" {:sessionId "s" :card plain :op "tick" :item 2}]
                                       ["_relay/cards/trust" {:sessionId "s" :card tainted :rev 1 :sha256 (apply str (repeat 64 "a"))}]]]
                (let [r (client/request! c method params)]
                  (is (= -32602 (get-in r ["error" "code"])) (pr-str method r))
                  (is (str/includes? (str (get-in r ["error" "message"])) "no parameter sessionId") (pr-str r))))))
          (is (nil? (core/open-conversation (inbound/current-state sys) "clerk")) "and no conversation opened")
          (client/close-input! c)
          (is (= 0 (client/wait! c 20000)))
          (finally (client/sever! c)))))))
