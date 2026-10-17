(ns relay.harness.agentloop
  "Runs a role with agentloop (../agentloop), a small program that runs a
  model in a loop with exactly the tools it is given.

  Settings in relay.edn, under :harnesses:

    {:agentloop {:kind :agentloop
                 :provider \"anthropic\"       ; or \"openai\"; the default is anthropic
                 :model \"claude-opus-5-5\"    ; the default
                 :effort \"medium\"            ; low, medium, high, xhigh or max
                 :api-key-command [\"security\" \"find-generic-password\" \"-s\" \"relay-anthropic\" \"-w\"]
                 ;; required: agentloop runs this itself and takes its
                 ;; output as the key; relay never sees the key
                 :base-url \"https://...\"      ; the provider's own API by default
                 :bin \"/path/to/agentloop\"
                 :retry {:retries 2 :backoff-seconds 2 :max-wait-seconds 30}
                 ;; stops a run that loops; a run that hits one ends as
                 ;; having run, with its memos still open
                 :limits {:max-turns 200 :max-tool-calls 400 :max-tokens 2000000}
                 ;; stops one tool call that hangs, so it cannot eat the
                 ;; whole wake; the default is 300
                 :tool-timeout-seconds 300}}

  A run stopped at the wake deadline, by agentloop (reason \"canceled\") or
  by relay killing it (exit \"timeout\"), is reported as :cut-short, so the
  next wake resumes its session.

  The binary is found in this order: :bin, then `agentloop` on PATH, then
  `../agentloop/bin/agentloop` beside this repository (what `make -C
  ../agentloop build` makes).

  Each wake writes request.json in its run directory and runs
  `agentloop run request.json`. The model gets relay's tools and nothing
  else. A tool call runs relay's `tool` command (the request's :tool-command,
  with the data directory and role named) with only the base
  environment, which never holds the API key. The transcript is agentloop's output:
  one JSON line per session entry, then an outcome line. The session itself
  is a file in the role's session directory, named by the session id.

  A conversation writes its request the same way and is described, not
  run: `agentloop acp request.json`, whose request names the conversations
  directory instead of one session.

  How a run ended is read from the outcome line, never from the exit code:
  agentloop exits 0 whenever it reaches an outcome."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [relay.acp :as acp]
            [relay.harness :as harness])
  (:import (java.time Instant)))

(def default-model "claude-opus-5-5")
(def default-effort "medium")
(def default-provider "anthropic")
(def default-tool-timeout-seconds 300)

(defn- repo-root []
  (some-> (System/getProperty "relay.bin") fs/real-path fs/parent fs/parent str))

(defn agentloop-bin
  "The agentloop binary: :bin, else `agentloop` on PATH, else the sibling
  checkout's build. Nil when there is none."
  [{:keys [bin]}]
  (or bin
      (some-> (fs/which "agentloop") str)
      (let [sibling (some-> (repo-root) (fs/path ".." "agentloop" "bin" "agentloop"))]
        (when (and sibling (fs/executable? sibling)) (str (fs/normalize sibling))))))

;; --- reading a run back

(defn parse-outcome
  "The last outcome line in agentloop's output, or nil when there is none.
  A line that looks like an outcome but cannot be read is ::unreadable."
  [text]
  (->> (str/split-lines (str text))
       (keep (fn [line]
               (when (str/starts-with? (str/triml line) "{")
                 (try (let [e (json/parse-string line true)]
                        (when (and (map? e) (= "outcome" (:type e))) e))
                      (catch Exception _
                        (when (str/includes? line "\"outcome\"") ::unreadable))))))
       last))

(defn- instant [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (Instant/parse s) (catch Exception _ nil))))

(defn- reset-time
  "When a rate limit lifts: the provider's reset time if still ahead,
  otherwise the time to retry after, if still ahead."
  [{:keys [resets_at retry_after]} ^Instant now]
  (some->> [(instant resets_at) (instant retry_after)]
           (filter #(and % (.isAfter ^Instant % now)))
           first str))

(defn held-calls
  "The calls an outcome line says are held, as relay records them."
  [held]
  (vec (for [{:keys [id name input input_sha256]} (when (sequential? held) held)
             :when (and (string? id) (string? name))]
         (cond-> {:id id :name name :input input}
           (string? input_sha256) (assoc :input-sha256 input_sha256)))))

(defn classify
  "How a run ended. Pure: takes the parsed outcome line (nil when there was
  none), the exit code, stderr, the session id and the time."
  [{:keys [outcome exit stderr session now]}]
  (let [{:keys [reason text error held labels label_sources refused forbidden]} (when (map? outcome) outcome)
        error (when (map? error) error)
        ;; Whatever the outcome line held, only strings are shown to a person.
        said-from (fn [x] (when (and (string? x) (not (str/blank? x))) x))
        said (harness/tail (or (said-from text) (said-from (:message error)) "") 8)
        report (fn [result & {:as more}]
                 (cond-> (merge {:outcome result :exit exit :session session :said said
                                 :crashed (= "timeout" exit)
                                 :cut-short (or (= "timeout" exit) (= "canceled" reason))}
                                more)
                   ;; The session's labels at the end, which what it said
                   ;; carries. Without them relay assumes the worst.
                   (sequential? labels) (assoc :labels (vec labels))
                   ;; Results agentloop kept from the session for carrying
                   ;; a label it may not hold, for the operator.
                   (and (sequential? refused) (seq refused))
                   (assoc :refused (vec (for [r refused :when (map? r)]
                                          (select-keys r [:call_id :tool :labels]))))))]
    (cond
      (= "timeout" exit) (report "ran" :said "Stopped at the wake deadline.")
      ;; agentloop ran and said how it ended, but relay could not read it:
      ;; the run crashed, and with no labels reported relay assumes the
      ;; worst. It says nothing about the harness, so nothing is paused.
      (= ::unreadable outcome) (report "ran" :crashed true :said "agentloop's outcome could not be read.")
      (nil? outcome) (report "needs-attention"
                             :said (str (if (and (number? exit) (> exit 128))
                                          (str "agentloop was killed (exit " exit ") before it finished. ")
                                          "agentloop did not start. ")
                                        (harness/tail stderr 5)))
      :else
      (case reason
        ("completed" "max_tokens" "limit") (report "ran")
        ;; Calls that wait for the operator: relay records a hold for each.
        ;; Where each of the session's labels came from, for the operator.
        "held" (report "ran" :held (mapv #(cond-> % (map? label_sources) (assoc :label-sources label_sources))
                                         (held-calls held)))
        "refused" (report "ran" :crashed true)
        ;; The session holds a label it may not, so agentloop ran nothing.
        ;; The next wake starts a fresh session.
        "forbidden" (report "ran" :crashed true
                            :said "agentloop did not run the session: it holds a label it may not hold."
                            :forbidden (if (sequential? forbidden) (vec forbidden) []))
        "canceled" (report "ran" :crashed true :said "Stopped at the wake deadline.")
        "model_error"
        (case (:kind error)
          "rate_limited" (report "limited" :resets-at (reset-time error now))
          ("overloaded" "unreachable") (report "unreachable")
          ("unauthorized" "billing" "not_found") (report "needs-attention")
          (report "ran" :crashed true))
        (report "ran" :crashed true)))))

;; --- running

(def ^:private no-key
  "No API key command: set :api-key-command for the agentloop harness in relay.edn.")

(def ^:private no-bin
  "agentloop not found: set :bin, put agentloop on PATH, or run `make -C ../agentloop build`.")

(defn- base-request
  "What every agentloop request holds, whatever the mode."
  [{:keys [harness model system-prompt tools tool-command policy]} work]
  (let [{:keys [provider effort base-url retry limits tool-timeout-seconds api-key-command]} harness
        limit-keys {:max-turns :max_turns :max-tool-calls :max_tool_calls :max-tokens :max_tokens}
        retry-keys {:retries :retries :backoff-seconds :backoff_seconds
                    :max-wait-seconds :max_wait_seconds}]
    (cond-> {:provider (or provider default-provider)
             :model (or model (:model harness) default-model)
             :effort (or effort default-effort)
             :api_key_command (vec api-key-command)
             :system system-prompt
             :tools (mapv #(select-keys % [:name :description :parameters]) tools)
             :tool_command (vec tool-command)
             ;; Every tool is policed: the session's starting labels and
             ;; each tool's class and labels, with calls and results in
             ;; envelopes that carry labels both ways.
             :policy policy
             :tool_protocol "envelope"
             :tool_env (harness/base-env {})
             :tool_dir (str work)
             :tool_timeout_seconds (or tool-timeout-seconds default-tool-timeout-seconds)}
      base-url (assoc :base_url base-url)
      (seq limits) (assoc :limits (into {} (keep (fn [[k v]] (when-let [k2 (limit-keys k)] [k2 v])) limits)))
      (seq retry) (assoc :retry (into {} (keep (fn [[k v]] (when-let [k2 (retry-keys k)] [k2 v])) retry))))))

(defn request-json
  "The request `agentloop run` reads: the wake's prompt, and one session,
  the file named by `id` in the session directory."
  [{:keys [prompt session-dir timeout-ms resolve] :as request} id work]
  (cond-> (assoc (base-request request work)
                 :session (str (fs/path session-dir (str id ".jsonl")))
                 :prompt prompt)
    timeout-ms (assoc :timeout_seconds (max 1 (- (quot timeout-ms 1000) 10)))
    ;; The operator's decisions on the calls the session left held.
    (seq resolve) (assoc :resolve (vec resolve))))

(defn acp-request-json
  "The request `agentloop acp` reads. The client opens sessions, so the
  request names their directory instead of one session, and has no prompt
  and no deadline: an ACP turn ends when the client cancels it. Every
  conversation starts empty: new_sessions_only stops a client loading,
  resuming or listing an earlier one, whose history and labels it would
  carry on. It adds what only a client shows: each tool's ACP kind, and a
  title, \"relay · <role id>\", for the client to name the conversation
  by. agentloop asks the provider for the model's context window itself."
  [{:keys [session-dir tools role] :as request} work]
  (let [kinds (acp/tool-kinds tools)]
    (cond-> (assoc (base-request request work) :session_dir (str session-dir) :new_sessions_only true)
      (:id role) (assoc :title (str "relay · " (:id role)))
      (seq kinds) (assoc :tool_kinds kinds))))

(defn- prepare!
  "Finds the binary and writes the request that `build` makes into the run
  directory. Returns {:id :work :file :bin :env}, or {:problem text} when
  agentloop cannot run."
  [{:keys [harness run-dir session-dir resume]} build]
  (let [bin (agentloop-bin harness)
        key (not-empty (:api-key-command harness))]
    (cond
      (nil? key) {:problem no-key}
      (nil? bin) {:problem no-bin}
      :else
      (let [id (or resume (str (random-uuid)))
            work (str (fs/path run-dir "work"))
            file (str (fs/path run-dir "request.json"))]
        (fs/create-dirs work)
        (fs/create-dirs session-dir)
        (spit file (json/generate-string (build id work)))
        {:id id :work work :file file :bin bin
         :env (harness/base-env {})}))))

(defmethod harness/wake-command :agentloop
  [{:keys [run-dir timeout-ms] :as request}]
  (let [{:keys [problem id work file bin env] :as prepared}
        (prepare! request #(request-json request %1 %2))]
    (if problem
      prepared
      ;; agentloop stops itself at the deadline (request-json), so relay
      ;; waits a little longer before stopping it.
      {:argv [bin "run" file] :dir work :env env
       :out-file (str (fs/path run-dir "transcript.jsonl"))
       :timeout-ms (+ timeout-ms 15000)
       :session id})))

(defmethod harness/wake-report :agentloop [_ {:keys [session]} {:keys [exit out err now]}]
  (classify {:outcome (parse-outcome out) :exit exit :stderr err :session session :now now}))

(defmethod harness/acp-command :agentloop [{:keys [session-dir] :as request}]
  (let [{:keys [problem work file bin env] :as prepared} (prepare! request (fn [_ work] (acp-request-json request work)))]
    (if problem
      prepared
      {:argv [bin "acp" file] :dir work :env env :session-dir session-dir})))
