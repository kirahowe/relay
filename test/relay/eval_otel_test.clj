(ns relay.eval-otel-test
  "traces.otlp.jsonl: the workflow request's shape, its ids, and a mock run
  that writes a file of valid OTLP/JSON lines."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.eval-otel :as eo]
            [relay.eval-runner :as er]
            [relay.scenario-runner :as runner]
            [relay.test-support :as support])
  (:import (java.time Instant)))

(def info
  {:run-id "20261017T000000Z-ab12" :case-id "supersede-on-change" :tier :regression :version 2 :trial 3
   :scores {:b-closed {:value :fail :explanation "No closure."}
            :a-recorded {:value :pass :explanation "Recorded."}
            :judged {:value :skipped}}
   :started (Instant/parse "2026-10-17T09:00:00Z") :ended (Instant/parse "2026-10-17T09:00:02.5Z")})

(deftest the-workflow-request-has-a-span-and-an-event-per-grader
  (let [req (eo/workflow-request info)
        [span :as spans] (get-in req [:resourceSpans 0 :scopeSpans 0 :spans])
        attr (fn [x k] (some #(when (= k (:key %)) (:value %)) (:attributes x)))]
    (is (= 1 (count spans)))
    (is (= "invoke_workflow supersede-on-change" (:name span)))
    (is (= {:stringValue "invoke_workflow"} (attr span "gen_ai.operation.name")))
    (is (= {:stringValue "supersede-on-change"} (attr span "relay.eval.case_id")))
    (is (= {:intValue "3"} (attr span "relay.eval.trial")))
    (is (= {:stringValue "regression"} (attr span "relay.eval.tier")))
    (is (= {:intValue "2"} (attr span "relay.eval.case_version")))
    (is (= "1792227600000000000" (:startTimeUnixNano span)))
    (is (= "1792227602500000000" (:endTimeUnixNano span)))
    (is (nil? (:status span)))
    (is (= ["a-recorded" "b-closed" "judged"]
           (map #(:stringValue (attr % "gen_ai.evaluation.name")) (:events span))) "in grader id order")
    (is (= ["pass" "fail" "skipped"]
           (map #(:stringValue (attr % "gen_ai.evaluation.score.label")) (:events span))))
    (is (= ["Recorded." "No closure." nil]
           (map #(:stringValue (attr % "gen_ai.evaluation.explanation")) (:events span))))
    (is (every? #(= "gen_ai.evaluation.result" (:name %)) (:events span)))
    (is (re-matches #"[0-9a-f]{32}" (:traceId span)))
    (is (re-matches #"[0-9a-f]{16}" (:spanId span)))
    (is (= req (json/parse-string (json/generate-string req) true)) "plain JSON data")))

(deftest a-trial-with-errors-is-an-error-span
  (let [span (get-in (eo/workflow-request (assoc info :errors [{:class :step}] :scores nil))
                     [:resourceSpans 0 :scopeSpans 0 :spans 0])]
    (is (= 2 (get-in span [:status :code])))
    (is (empty? (:events span)))))

(def ^:private id-part (gen/such-that seq gen/string-alphanumeric))

(deftest ids-are-deterministic-and-differ-by-trial
  (is (:pass? (tc/quick-check 200 (prop/for-all [r id-part c id-part t gen/nat]
                                    (let [a (eo/ids r c t)]
                                      (and (= a (eo/ids r c t))
                                           (not= (:trace-id a) (:trace-id (eo/ids r c (inc t))))
                                           (re-matches #"[0-9a-f]{32}" (:trace-id a))
                                           (re-matches #"[0-9a-f]{16}" (:span-id a)))))))))

(deftest a-session-id-comes-from-the-outcome-line
  (is (= "abc-123" (eo/transcript-session-id "{\"seq\":1}\n{\"type\":\"outcome\",\"session\":\"/x/sessions/abc-123.jsonl\"}\n")))
  (is (nil? (eo/transcript-session-id "{\"seq\":1}\n")))
  (is (nil? (eo/transcript-session-id "not json")))
  (is (nil? (eo/transcript-session-id ""))))

(defn- supports-otel? [bin]
  (and bin (let [{:keys [err]} (p/shell {:out :string :err :string :continue true} bin "otel")]
             (str/includes? (str err) "agentloop otel"))))

(deftest a-mock-run-writes-a-traces-file-of-valid-lines
  (if-not (supports-otel? support/agentloop-bin)
    (println "SKIP: this agentloop build has no `otel` command.")
    (let [out (str (fs/create-temp-dir {:prefix "relay-eval-traces-"}))
          names ["no-needless-notify" "correct-on-mistake"]
          files (filter #(contains? (set names) (runner/scenario-name %)) (runner/scenario-files))]
      (try
        (with-out-str
          (let [{:keys [dir]} (er/run! files {:out out :trials 1 :repo-root "." :args []})
                lines (str/split-lines (slurp (str (fs/path dir "traces.otlp.jsonl"))))
                reqs (mapv json/parse-string lines)
                span-lists (mapv #(get-in % ["resourceSpans" 0 "scopeSpans" 0 "spans"]) reqs)
                workflows (filterv #(str/starts-with? (get-in % [0 "name"]) "invoke_workflow") span-lists)]
            (is (seq lines))
            (is (every? #(contains? % "resourceSpans") reqs))
            (is (= 2 (count workflows)) "one workflow line per trial")
            (testing "wake runs hang under their trial's workflow span"
              (let [by-trace (group-by #(get (first %) "traceId") span-lists)]
                (doseq [[_ ls] by-trace
                        :let [wf (first (filter #(str/starts-with? (get (first %) "name") "invoke_workflow") ls))
                              wakes (remove #{wf} ls)]]
                  (is (seq wakes))
                  (is (every? #(= (get (first wf) "spanId") (get (first %) "parentSpanId")) wakes)))))))
        (finally (fs/delete-tree out))))))
