(ns relay.traces
  "Real wakes and conversations as OpenTelemetry traces, offline, and the
  facts relay week reads from their transcripts.

  relay traces writes one OTLP/JSON request per line: for each wake or
  conversation, one invoke_workflow span of relay's own, carrying its
  role, outcome, labels, cost and counts, with its health checks and the
  operator's flags as gen_ai.evaluation.result events; then one line per
  run file from `agentloop otel`, with every run hung under that span.
  A trace holds no content: no memo, fact, tool input or result, and
  never a flag's note. agentloop leaves content out unless asked, and
  relay never asks. So a trace can be loaded into any OTel viewer.

  The span requests are pure (wake-request). The rest is the edge: it
  reads run files and runs agentloop, which itself makes no requests."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [relay.otel :as otel]
            [relay.week :as week]))

;; --- pure

(defn wake-ids
  "A wake's trace and span ids, from its id and start alone."
  [{:keys [id started-at]}]
  (otel/ids "relay-wake" id (str started-at)))

(defn wake-request
  "The OTLP/JSON request (a map) for one ended wake or conversation, a
  row of relay.week/wake-rows. Names, counts and labels only."
  [{:keys [id role chat started-at ended-at outcome crashed cut-short memos closed blocked
           left-open dead-lettered cost-usd labels flags] :as row}]
  (let [{:keys [trace-id span-id]} (wake-ids row)]
    (otel/request
     "relay" "relay.traces"
     [(otel/workflow-span
       {:trace-id trace-id :span-id span-id
        :workflow (if chat "relay.conversation" "relay.wake")
        :started started-at :ended ended-at
        :attributes (cond-> [(otel/s-attr "relay.role" role)
                             (otel/i-attr "relay.wake.id" id)
                             (otel/s-attr "relay.wake.kind" (if chat "conversation" "wake"))
                             (otel/s-attr "relay.wake.outcome" outcome)
                             (otel/strings-attr "relay.wake.labels" labels)
                             (otel/i-attr "relay.wake.memos" memos)
                             (otel/i-attr "relay.wake.memos_closed" closed)
                             (otel/i-attr "relay.wake.memos_blocked" blocked)
                             (otel/i-attr "relay.wake.memos_left_open" left-open)
                             (otel/i-attr "relay.wake.memos_dead_lettered" dead-lettered)
                             (otel/b-attr "relay.wake.cut_short" cut-short)
                             (otel/b-attr "relay.wake.crashed" crashed)]
                      ;; Unknown is not free: no attribute at all.
                      cost-usd (conj (otel/d-attr "relay.wake.cost_usd" cost-usd)))
        :events (concat
                 (for [{:keys [check pass]} (week/checks row)]
                   (otel/evaluation-event ended-at (str "relay.check." check) (if pass "pass" "fail")))
                 ;; The value only. The note is the operator's own text.
                 (for [{:keys [value at]} flags]
                   (otel/evaluation-event at "relay.operator_flag" value)))
        :error (cond crashed "crashed"
                     (not= "ran" outcome) outcome)})])))

(def ^:private session-id-pattern #"[A-Za-z0-9][A-Za-z0-9._-]*")

(defn run-files
  "The files a wake's runs may be in, each {:file :conversation-id}, in
  the order to read them: a wake's transcript, or each of a
  conversation's session files. Whether they exist is the edge's to find
  out."
  [data-dir {:keys [id role chat session sessions]}]
  (if chat
    (for [s sessions
          :when (and (string? s) (re-matches session-id-pattern s) (not (str/includes? s "..")))
          dir ["conversations-labelled" "conversations-public" "conversations"]]
      {:file (str (fs/path data-dir "runs" role dir (str s ".jsonl"))) :conversation-id s :session s})
    [{:file (str (fs/path data-dir "runs" role (str id) "transcript.jsonl"))
      :conversation-id (or session (str "relay-wake-" id))}]))

;; --- the edge

(defn existing-runs
  "The run files of a wake that exist: a conversation's session in the
  first directory that has it."
  [data-dir row]
  (->> (run-files data-dir row)
       (filter #(fs/regular-file? (:file %)))
       (reduce (fn [acc f] (if (some #(= (:conversation-id %) (:conversation-id f)) acc) acc (conj acc f))) [])))

(defn read-facts
  "{wake-id transcript-facts} for the wakes whose run files could be read."
  [data-dir wakes]
  (into {}
        (keep (fn [w]
                (let [runs (existing-runs data-dir w)]
                  (when (seq runs)
                    [(:id w) (reduce week/add-facts nil
                                     (map #(week/transcript-facts (week/parse-transcript (slurp (:file %)))) runs))]))))
        wakes))

(defn agentloop-otel!
  "One run file through `agentloop otel` (binary `bin`), hung under the
  span `parent`: {:line} or {:problem}."
  [bin conversation-id {:keys [trace-id span-id]} file]
  (let [{:keys [exit out err]} (try (p/shell {:out :string :err :string :continue true}
                                             bin "otel" "--conversation-id" conversation-id
                                             "--parent-trace" trace-id "--parent-span" span-id (str file))
                                    (catch Exception e {:exit -1 :err (ex-message e)}))
        line (str/trim (str out))]
    (if (and (zero? exit) (otel/request-line? line))
      {:line line}
      {:problem (str "agentloop otel failed on " file ": " (str/trim (str err)))})))

(defn export!
  "Writes <out>/traces.otlp.jsonl for the ended wakes and conversations
  among `rows`. Returns {:file :wakes :conversations :lines :problems}."
  [data-dir bin rows out]
  (let [rows (filter #(= :ended (:status %)) rows)
        lines (atom [])
        problems (atom [])]
    (doseq [row rows]
      (swap! lines conj (json/generate-string (wake-request row)))
      (doseq [{:keys [file conversation-id]} (existing-runs data-dir row)]
        (let [{:keys [line problem]} (agentloop-otel! bin conversation-id (wake-ids row) file)]
          (if line (swap! lines conj line) (swap! problems conj problem)))))
    (fs/create-dirs out)
    (let [file (str (fs/path out "traces.otlp.jsonl"))]
      (spit file (str (str/join "\n" @lines) (when (seq @lines) "\n")))
      {:file file :lines (count @lines) :problems @problems
       :wakes (count (remove :chat rows)) :conversations (count (filter :chat rows))})))
