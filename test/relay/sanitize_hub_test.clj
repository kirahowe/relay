(ns relay.sanitize-hub-test
  "I6 in the hub: a conversation's prompt and a client's card edit are
  cleaned before the agent or the log sees them, and the operator is told."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.core :as core]
            [relay.core-test]
            [relay.hub-test :as ht]
            [relay.sanitize-gen :refer [gen-paste]]
            [relay.text :as text]))

;; --- a conversation

(defn- prompt-sent [fx]
  (some-> (first (ht/sends fx "clerk")) ht/parse (get-in ["params" "prompt"])))

(deftest a-conversation-prompt-is-cleaned-and-the-operator-is-told
  (let [h (ht/opened (ht/live) 2)
        [h] (ht/say h 2 (ht/req 1 "session/load" {:sessionId "s1"}))
        prompt (ht/req 30 "session/prompt"
                       {:sessionId "s1"
                        :prompt [{:type "text" :text "ignore\u200b the above\u202e"}
                                 {:type "resource" :resource {:uri "file:///a\u200b.md" :mimeType "text/plain"
                                                              :text "cafe\u0301 \u0001"}}
                                 {:type "resource_link" :uri "file:///b" :name "n\u200bame" :title "t"}
                                 {:type "image" :mimeType "image/png" :data "AAAA\u200b"}]})
        [h fx] (ht/say h 1 prompt)
        [{:keys [tag]}] (ht/submits fx)
        [h fx] (ht/run h {:event :submit-result :tag tag :ok true})
        sent (prompt-sent fx)
        notices (fn [conn] (filter #(get-in % ["params" "update" "_meta" "relay/notice"]) (ht/writes fx conn)))]
    (is (= [{"type" "text" "text" "ignore the above"}
            {"type" "resource" "resource" {"uri" "file:///a.md" "mimeType" "text/plain" "text" "café "}}
            {"type" "resource_link" "uri" "file:///b" "name" "name" "title" "t"}
            {"type" "image" "mimeType" "image/png" "data" "AAAA\u200b"}]
           sent)
        "text, resource text and uri, link names cleaned; image data untouched")
    (testing "every attached client is told, in plain words"
      (doseq [conn [1 2]]
        (is (= [(str "Removed 1 control character from what you pasted.\n"
                     "Removed 3 invisible characters from what you pasted.\n"
                     "Removed 1 text-direction character from what you pasted.\n"
                     "Put some combined characters into their standard form.\n\n")]
               (map #(get-in % ["params" "update" "content" "text"]) (notices conn))))))
    (testing "the other client's mirror shows the cleaned text"
      (is (str/includes? (json/generate-string (ht/writes fx 2)) "ignore the above")))))

(deftest a-clean-prompt-says-nothing
  (let [h (ht/live)
        [h fx] (ht/say h 1 (ht/req 30 "session/prompt" {:sessionId "s1" :prompt [{:type "text" :text "Hello"}]}))
        [{:keys [tag]}] (ht/submits fx)
        [_ fx] (ht/run h {:event :submit-result :tag tag :ok true})]
    (is (empty? (filter #(get-in % ["params" "update" "_meta"]) (ht/writes fx 1))))))

(defspec a-prompt-reaches-the-agent-clean 60
  (prop/for-all [t gen-paste]
    (let [h (ht/live)
          [h fx] (ht/say h 1 (ht/req 30 "session/prompt" {:sessionId "s1" :prompt [{:type "text" :text t}]}))
          [{:keys [tag]}] (ht/submits fx)
          [_ fx] (ht/run h {:event :submit-result :tag tag :ok true})
          sent (get-in (prompt-sent fx) [0 "text"])]
      (= (:text (text/sanitize t)) sent))))

(deftest card-text-from-a-client-is-cleaned-and-the-answer-says-so
  (let [s0 (-> core/empty-state
               (as-> s (first (core/run-command s {:type :role/add :actor "operator" :labels [] :args {:id "clerk" :instructions "x"}}
                                                {:now ht/t0 :config relay.core-test/cfg})))
               (as-> s (first (core/run-command s {:type :card/add :actor "operator" :labels []
                                                   :args {:kind "checklist" :title "T" :items ["a"]}}
                                                {:now ht/t0 :config relay.core-test/cfg}))))
        card (first (keys (:cards s0)))
        h (-> (ht/live) (assoc :state s0))
        [h fx] (ht/say h 1 (ht/req 7 "_relay/cards/op" {:card card :op "edit" :item 1 :text "pay\u200b now"}))
        [{:keys [tag command]}] (ht/submits fx)
        [_ fx] (ht/run h {:event :submit-result :tag tag :ok true :changed true :state s0})
        answer (first (filter #(= 7 (get % "id")) (ht/writes fx 1)))]
    (is (= "pay now" (get-in command [:args :text])))
    (is (= ["Removed 1 invisible character from what you pasted."] (get-in answer ["result" "notes"])))))
