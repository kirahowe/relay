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
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.test-support :as support]
            [relay.workspace :as workspace]))

(def agent-script
  "An agent that forges a card update and a pane item, says ALIVE, and
  then reads until its input ends."
  (str "#!/bin/sh\n"
       "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"_relay/cards/changed\",\"params\":{\"rev\":1,\"cards\":[{\"id\":999,\"text\":\"FORGED\"}]}}'\n"
       "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"_relay/items\",\"params\":{}}'\n"
       "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"ALIVE\"}}}}'\n"
       "cat >/dev/null\n"))

(defn- with-relay [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-cards-acp"}))
        data (str dir "/data")
        agent (str dir "/agent.sh")]
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

(defn- notifications [c]
  (filter #(= "_relay/cards/changed" (get % "method")) @(:messages c)))

(defn- add-card! [sys]
  (:seq (first (inbound/submit! sys {:type :card/add :actor "clerk" :labels ["private"]
                                     :args {:kind "checklist" :title "Taxes" :items ["Find the T4" "File"]}}))))

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
