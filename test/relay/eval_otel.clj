(ns relay.eval-otel
  "A run's traces.otlp.jsonl: OpenTelemetry GenAI spans in OTLP/JSON, one
  request per line. Per trial there is one line of our own, an
  invoke_workflow span for the case carrying one gen_ai.evaluation.result
  event per grader (workflow-request, pure), and one line per wake from
  `agentloop otel`, with every wake's runs hung under the workflow span.

  The spans are built by relay.otel, which relay traces uses too, and
  agentloop is run through relay.traces/agentloop-otel!."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [relay.otel :as otel]
            [relay.traces :as traces]))

(def convention-version otel/convention-version)

(defn ids
  "The workflow span's ids for a trial, from (run id, case, trial) alone, so
  exporting the same run twice gives the same ids."
  [run-id case-id trial]
  (otel/ids run-id case-id trial))

(defn- label [value] (case value :pass "pass" :fail "fail" :skipped "skipped" (name value)))

(defn workflow-request
  "The OTLP/JSON request (a map) for one trial: an invoke_workflow span named
  for the case, with a gen_ai.evaluation.result event per grader, in grader
  id order. `scores` is {grader-id {:value :explanation}}; `errors` is the
  trial's errors (nil for a scored trial), which make the span ERROR."
  [{:keys [run-id case-id tier version trial scores errors started ended]}]
  (let [{:keys [trace-id span-id]} (ids run-id case-id trial)
        end (or ended started)]
    (otel/request
     "relay-eval" "relay.eval-otel"
     [(otel/workflow-span
       {:trace-id trace-id :span-id span-id :workflow case-id :started started :ended end
        :attributes (cond-> [(otel/s-attr "relay.eval.run_id" run-id)
                             (otel/s-attr "relay.eval.case_id" case-id)
                             (otel/i-attr "relay.eval.trial" trial)
                             (otel/s-attr "relay.eval.tier" (name tier))
                             (otel/i-attr "relay.eval.case_version" version)]
                      (seq errors) (conj (otel/s-attr "error.type" "trial_error")))
        :events (for [[id {:keys [value explanation]}] (sort-by (comp str key) scores)]
                  (otel/evaluation-event end (name id) (label value) explanation))
        :error (when (seq errors) "trial error")})])))

(def transcript-session-id otel/transcript-session-id)

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
           {:keys [line problem]} (traces/agentloop-otel! bin conv {:trace-id trace-id :span-id span-id} (:file w))]
       (if line
         (update acc :lines conj line)
         (update acc :problems conj (str case-id " #" trial " " (:role w) "/" (:wake w) ": " problem)))))
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
