(ns relay.traces-test
  "relay traces: a wake's span holds names and counts only, and over a
  staged data directory with a mock wake and a conversation, every line
  is OTLP/JSON with agentloop's runs under relay's span."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.cli :as cli]
            [relay.core :as core]
            [relay.inbound :as inbound]
            [relay.scenario-runner :as runner]
            [relay.test-support :as support]
            [relay.traces :as traces])
  (:import (java.time Instant)))

(def row
  {:id 12 :role "hub" :chat false :status :ended :started-at (Instant/parse "2026-10-14T12:00:00Z")
   :ended-at (Instant/parse "2026-10-14T12:01:00Z") :outcome "ran" :crashed false :cut-short false
   :held 1 :memos 2 :closed 1 :blocked 0 :left-open 0 :dead-lettered 0 :journal true :operator-memos 0
   :cost-usd 0.25 :labels ["private"] :flags [{:id 30 :value "bad" :at (Instant/parse "2026-10-14T13:00:00Z")}]
   :transcript nil})

(defn- span [req] (get-in req [:resourceSpans 0 :scopeSpans 0 :spans 0]))
(defn- attr [x k] (some #(when (= k (:key %)) (:value %)) (:attributes x)))

(deftest a-wake-span-has-its-numbers-checks-and-flags
  (let [s (span (traces/wake-request row))
        evals (for [e (:events s)] [(:stringValue (attr e "gen_ai.evaluation.name"))
                                    (:stringValue (attr e "gen_ai.evaluation.score.label"))])]
    (is (= "invoke_workflow relay.wake" (:name s)))
    (is (= {:stringValue "hub"} (attr s "relay.role")))
    (is (= {:intValue "12"} (attr s "relay.wake.id")))
    (is (= {:doubleValue 0.25} (attr s "relay.wake.cost_usd")))
    (is (= {:intValue "1"} (attr s "relay.wake.held_calls")))
    (is (= {:arrayValue {:values [{:stringValue "private"}]}} (attr s "relay.wake.labels")))
    (is (nil? (:status s)))
    (is (some #{["relay.check.memos-left-open" "pass"]} evals))
    (is (some #{["relay.operator_flag" "bad"]} evals))
    (is (every? #(= "gen_ai.evaluation.result" (:name %)) (:events s)))
    (testing "an unknown cost is left out, and a crash is an error"
      (let [s (span (traces/wake-request (assoc row :cost-usd nil :crashed true)))]
        (is (nil? (attr s "relay.wake.cost_usd")))
        (is (= 2 (get-in s [:status :code])))))))

(deftest a-conversation-reads-each-session-file-and-only-safe-names
  (let [dir (str (fs/create-temp-dir {:prefix "relay-traces-"}))]
    (try
      (fs/create-dirs (fs/path dir "runs/hub/conversations-labelled"))
      (spit (str (fs/path dir "runs/hub/conversations-labelled/s-1.jsonl"))
            "{\"type\":\"decision\",\"decision\":{\"call_id\":\"a\",\"tool\":\"post\",\"verdict\":\"deny\"}}\n")
      (let [chat {:id 5 :role "hub" :chat true :sessions ["s-1" "s-2" "../x"]}]
        (is (= ["s-1"] (map :conversation-id (traces/existing-runs dir chat))))
        (is (not-any? #(str/includes? (:file %) "..") (traces/run-files dir chat)))
        (is (= {5 {:calls 1 :refused 1 :failed 0 :refused-tools ["post"] :failed-tools []}}
               (traces/read-facts dir [chat]))))
      (finally (fs/delete-tree dir)))))

(defn- operator! [dir & args]
  (let [{:keys [exit out]} (cli/execute (into ["--data" dir] args))]
    (is (= 0 exit) (pr-str args out))
    out))

(deftest traces-of-a-staged-week-parse-and-nest
  (if-not support/agentloop-bin
    (println "SKIP: agentloop is not built.")
    (let [file (first (filter #(= "operator-memo-closed" (runner/scenario-name %)) (runner/scenario-files)))
          result (runner/run-scenario! file {:keep? true})
          dir (:dir result)
          out (str (fs/create-temp-dir {:prefix "relay-traces-out-"}))]
      (try
        (is (:pass? result) (pr-str (:failures result)))
        (let [sys (inbound/system dir)
              wake (first (filter #(= "hub" (:role %)) (vals (:wakes (inbound/current-state sys)))))
              ;; A conversation, recorded as relay acp records one, whose
              ;; session file is the wake's own run.
              started (inbound/submit! sys {:type :wake/start :actor core/operator
                                            :args {:role "hub" :chat true :note "talk"}})
              chat (:seq (first (filter #(= "wake/started" (:type %)) started)))
              _ (inbound/submit! sys {:type :wake/end :actor core/dispatcher
                                      :args {:wake chat :outcome "ran" :said "Conversation ended."
                                             :sessions ["conv-1"]}})
              _ (fs/create-dirs (fs/path dir "runs/hub/conversations-labelled"))
              _ (fs/copy (fs/path dir "runs/hub" (str (:id wake)) "transcript.jsonl")
                         (fs/path dir "runs/hub/conversations-labelled/conv-1.jsonl"))]
          (operator! dir "flag" (str (:id wake)) "good" "the" "secret" "note")
          (operator! dir "flag" (str chat) "bad")
          (is (= :not-found (try (cli/execute ["--data" dir "flag" "999999" "good"]) nil
                                (catch clojure.lang.ExceptionInfo e (core/rejection e))))
              "an unknown id is refused")
          (testing "relay week shows both and the flags"
            (let [text (str/join "\n" (operator! dir "week"))]
              (is (str/includes? text (str "wake " (:id wake) ", hub")))
              (is (str/includes? text (str "conversation " chat ", hub")))
              (is (str/includes? text "good: the secret note"))))
          (testing "and --json is the same data, as JSON"
            (let [r (json/parse-string (first (operator! dir "week" "--json")) true)]
              (is (= 2 (count (:flags r))))
              (is (= ["hub"] (map :role (:roles r))))))
          (let [[said] (operator! dir "traces" "--out" out "--agentloop" support/agentloop-bin)
                text (slurp (str (fs/path out "traces.otlp.jsonl")))
                reqs (mapv #(json/parse-string % true) (str/split-lines text))
                spans (mapcat #(get-in % [:resourceSpans 0 :scopeSpans 0 :spans]) reqs)
                ours (filter #(str/starts-with? (:name %) "invoke_workflow relay.") spans)
                by-id (into {} (map (juxt :spanId identity)) spans)]
            (is (str/starts-with? said "Wrote ") said)
            (is (= 2 (count ours)) "a span for the wake and one for the conversation")
            (is (< 2 (count reqs)) "with agentloop's lines after them")
            (testing "every run hangs under the span of its wake or conversation"
              (doseq [s spans :when (not (some #{s} ours))]
                (is (= (:traceId s) (:traceId (by-id (:parentSpanId s) s))))
                (is (some #(= (:traceId s) (:traceId %)) ours))))
            (testing "each of ours has a run under it"
              (doseq [o ours]
                (is (some #(= (:spanId o) (:parentSpanId %)) spans) (:name o))))
            (testing "a trace holds no content: no memo text, no outcome, no flag note"
              (is (not (str/includes? text "Where is the office")))
              (is (not (str/includes? text "closed on Mondays")))
              (is (not (str/includes? text "secret note")))
              (is (str/includes? text "relay.operator_flag")))))
        (finally (fs/delete-tree dir) (fs/delete-tree out))))))
