(ns relay.eval-otel
  "A run's traces.otlp.jsonl: OpenTelemetry GenAI spans in OTLP/JSON, one
  request per line. Per trial there is one line of our own, an
  invoke_workflow span for the case carrying one gen_ai.evaluation.result
  event per grader (workflow-request, pure), and one line per wake from
  `agentloop otel`, with every wake's runs hung under the workflow span.

  The GenAI conventions are at Development stability, so every
  gen_ai.* name here may be renamed. convention-version names the schema
  this file follows, the same one agentloop's otel package pins. The
  agentloop.* and relay.* attributes are ours."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.security MessageDigest)
           (java.time Instant)))

(def convention-version
  "The GenAI semantic conventions schema these spans follow (stability:
  development)."
  "https://opentelemetry.io/schemas/gen-ai-dev/1.42.0-dev")

(defn- sha256-hex [^String s]
  (apply str (map #(format "%02x" %) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))

(defn ids
  "The workflow span's ids for a trial, from (run id, case, trial) alone, so
  exporting the same run twice gives the same ids."
  [run-id case-id trial]
  (let [h (sha256-hex (pr-str [run-id case-id trial]))]
    {:trace-id (subs h 0 32) :span-id (subs (sha256-hex (str h "/workflow")) 0 16)}))

(defn- nanos [^Instant t] (str (+ (* (.getEpochSecond t) 1000000000) (.getNano t))))

(defn- s-attr [k v] {:key k :value {:stringValue (str v)}})
(defn- i-attr [k v] {:key k :value {:intValue (str v)}})

(defn- label [value] (case value :pass "pass" :fail "fail" :skipped "skipped" (name value)))

(defn workflow-request
  "The OTLP/JSON request (a map) for one trial: an invoke_workflow span named
  for the case, with a gen_ai.evaluation.result event per grader, in grader
  id order. `scores` is {grader-id {:value :explanation}}; `errors` is the
  trial's errors (nil for a scored trial), which make the span ERROR."
  [{:keys [run-id case-id tier version trial scores errors started ended]}]
  (let [{:keys [trace-id span-id]} (ids run-id case-id trial)
        end (or ended started)]
    {:resourceSpans
     [{:resource {:attributes [(s-attr "service.name" "relay-eval")]}
       :scopeSpans
       [{:scope {:name "relay.eval-otel"}
         :schemaUrl convention-version
         :spans
         [(cond-> {:traceId trace-id :spanId span-id
                   :name (str "invoke_workflow " case-id)
                   :kind 1
                   :startTimeUnixNano (nanos started)
                   :endTimeUnixNano (nanos end)
                   :attributes (cond-> [(s-attr "gen_ai.operation.name" "invoke_workflow")
                                        (s-attr "gen_ai.workflow.name" case-id)
                                        (s-attr "relay.eval.run_id" run-id)
                                        (s-attr "relay.eval.case_id" case-id)
                                        (i-attr "relay.eval.trial" trial)
                                        (s-attr "relay.eval.tier" (name tier))
                                        (i-attr "relay.eval.case_version" version)]
                                 (seq errors) (conj (s-attr "error.type" "trial_error")))
                   :events (vec (for [[id {:keys [value explanation]}] (sort-by (comp str key) scores)]
                                  {:timeUnixNano (nanos end)
                                   :name "gen_ai.evaluation.result"
                                   :attributes (cond-> [(s-attr "gen_ai.evaluation.name" (name id))
                                                        (s-attr "gen_ai.evaluation.score.label" (label value))]
                                                 explanation (conj (s-attr "gen_ai.evaluation.explanation" explanation)))}))}
            (seq errors) (assoc :status {:code 2 :message "trial error"}))]}]}]}))

(defn transcript-session-id
  "The agentloop session id a transcript names, from its closing outcome
  line (the session file's name without its extension), or nil."
  [transcript-text]
  (when-let [line (last (remove str/blank? (str/split-lines transcript-text)))]
    (when-let [session (try (get (json/parse-string line) "session") (catch Exception _ nil))]
      (when (string? session)
        (let [n (str (fs/file-name session))]
          (not-empty (str/replace n #"\.[^.]*$" "")))))))

(defn wake-transcripts
  "[{:role :wake :file}] for the wakes under a trial's data dir, by role and
  wake number."
  [data-dir]
  (vec (sort-by (juxt :role :wake)
                (for [t (fs/glob (str data-dir) "runs/*/*/transcript.jsonl")
                      :let [wake-dir (fs/parent t)
                            n (str (fs/file-name wake-dir))
                            role (str (fs/file-name (fs/parent wake-dir)))]
                      :when (re-matches #"\d+" n)]
                  {:role role :wake (parse-long n) :file (str t)}))))

(defn wake-conversation-id
  "The wake's conversation id: its agentloop session id when the transcript
  names one, else <case>/<trial>/<role>/<wake>."
  [case-id trial {:keys [role wake file]}]
  (or (try (transcript-session-id (slurp file)) (catch Exception _ nil))
      (str case-id "/" trial "/" role "/" wake)))

(defn wake-lines
  "One line of OTLP/JSON per wake of a trial, from `agentloop otel` (the
  binary `bin`). Returns {:lines [...] :problems [...]}; a wake whose export
  failed or printed something that is not JSON is a problem, not a line."
  [bin case-id trial data-dir {:keys [trace-id span-id]}]
  (reduce
   (fn [acc w]
     (let [conv (wake-conversation-id case-id trial w)
           {:keys [exit out err]} (p/shell {:out :string :err :string :continue true}
                                           bin "otel" "--conversation-id" conv
                                           "--parent-trace" trace-id "--parent-span" span-id (:file w))
           line (str/trim (str out))]
       (if (and (zero? exit)
                (try (contains? (json/parse-string line) "resourceSpans") (catch Exception _ false)))
         (update acc :lines conj line)
         (update acc :problems conj (str case-id " #" trial " " (:role w) "/" (:wake w)
                                         ": agentloop otel failed: " (str/trim (str err)))))))
   {:lines [] :problems []}
   (wake-transcripts data-dir)))

(defn trial-lines
  "All the lines for one trial: the workflow request, then each wake's."
  [bin {:keys [data-dir] :as info}]
  (let [wf (workflow-request info)
        {:keys [lines problems]} (when (and bin data-dir)
                                   (wake-lines bin (:case-id info) (:trial info) data-dir
                                               (ids (:run-id info) (:case-id info) (:trial info))))]
    {:lines (into [(json/generate-string wf)] lines) :problems (or problems [])}))
