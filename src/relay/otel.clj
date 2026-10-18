(ns relay.otel
  "OpenTelemetry spans in OTLP/JSON, built as plain data. Pure: no SDK, no
  network, no files. relay traces (relay.traces) and the eval runner's
  traces file (relay.eval-otel) both build their spans here, and hang
  agentloop's own spans (`agentloop otel`) under them.

  The GenAI conventions are at Development stability, so every gen_ai.*
  name here may be renamed. convention-version names the schema these
  spans follow, the same one agentloop's otel package pins. The
  agentloop.* and relay.* attributes are ours."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.security MessageDigest)
           (java.time Instant)))

(def convention-version
  "The GenAI semantic conventions schema these spans follow (stability:
  development)."
  "https://opentelemetry.io/schemas/gen-ai-dev/1.42.0-dev")

(defn sha256-hex [^String s]
  (apply str (map #(format "%02x" %) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))

(defn ids
  "A trace id and a span id from `parts` alone, so exporting the same
  thing twice gives the same ids."
  [& parts]
  (let [h (sha256-hex (pr-str (vec parts)))]
    {:trace-id (subs h 0 32) :span-id (subs (sha256-hex (str h "/workflow")) 0 16)}))

(defn nanos [^Instant t] (str (+ (* (.getEpochSecond t) 1000000000) (.getNano t))))

(defn s-attr [k v] {:key k :value {:stringValue (str v)}})
(defn i-attr [k v] {:key k :value {:intValue (str v)}})
(defn d-attr [k v] {:key k :value {:doubleValue (double v)}})
(defn b-attr [k v] {:key k :value {:boolValue (boolean v)}})
(defn strings-attr [k vs] {:key k :value {:arrayValue {:values (mapv (fn [v] {:stringValue (str v)}) vs)}}})

(defn evaluation-event
  "A gen_ai.evaluation.result span event: what was judged, its label, and
  an explanation when there is one."
  [^Instant at evaluation label & [explanation]]
  {:timeUnixNano (nanos at)
   :name "gen_ai.evaluation.result"
   :attributes (cond-> [(s-attr "gen_ai.evaluation.name" evaluation)
                        (s-attr "gen_ai.evaluation.score.label" label)]
                 explanation (conj (s-attr "gen_ai.evaluation.explanation" explanation)))})

(defn workflow-span
  "An invoke_workflow span (INTERNAL) named for `workflow`, from `started`
  to `ended`, with more attributes and events. `error` makes it ERROR,
  with that message."
  [{:keys [trace-id span-id workflow ^Instant started ^Instant ended attributes events error]}]
  (cond-> {:traceId trace-id :spanId span-id
           :name (str "invoke_workflow " workflow)
           :kind 1
           :startTimeUnixNano (nanos started)
           :endTimeUnixNano (nanos (or ended started))
           :attributes (into [(s-attr "gen_ai.operation.name" "invoke_workflow")
                              (s-attr "gen_ai.workflow.name" workflow)]
                             attributes)
           :events (vec events)}
    error (assoc :status {:code 2 :message error})))

(defn request
  "An OTLP/JSON export request (a map) holding `spans`, for the service
  and instrumentation scope named."
  [service scope spans]
  {:resourceSpans
   [{:resource {:attributes [(s-attr "service.name" service)]}
     :scopeSpans [{:scope {:name scope} :schemaUrl convention-version :spans (vec spans)}]}]})

(defn request-line?
  "Whether a line of text is one OTLP/JSON export request."
  [line]
  (try (contains? (json/parse-string line) "resourceSpans") (catch Exception _ false)))

(defn transcript-session-id
  "The agentloop session id a transcript names, from its closing outcome
  line (the session file's name without its extension), or nil."
  [transcript-text]
  (when-let [line (last (remove str/blank? (str/split-lines transcript-text)))]
    (when-let [session (try (get (json/parse-string line) "session") (catch Exception _ nil))]
      (when (string? session)
        (let [n (str (fs/file-name session))]
          (not-empty (str/replace n #"\.[^.]*$" "")))))))
