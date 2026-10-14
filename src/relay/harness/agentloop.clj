(ns relay.harness.agentloop
  "Runs a role with agentloop (../agentloop), a small program that runs a
  model in a loop with exactly the tools it is given.

  Settings in relay.edn, under :harnesses:

    {:agentloop {:kind :agentloop
                 :provider \"anthropic\"       ; or \"openai\"; the default is anthropic
                 :model \"claude-opus-5-5\"    ; the default
                 :effort \"medium\"            ; low, medium, high, xhigh or max
                 :api-key-command [\"security\" \"find-generic-password\" \"-s\" \"relay-anthropic\" \"-w\"]
                 ;; or :api-key-env \"ANTHROPIC_API_KEY\", read from relay's environment
                 :base-url \"https://...\"      ; the provider's own API by default
                 :bin \"/path/to/agentloop\"
                 :retry {:retries 2 :backoff-seconds 2 :max-wait-seconds 30}
                 ;; stops a run that loops; a run that hits one ends as
                 ;; having run, with its memos still open
                 :limits {:max-turns 200 :max-tool-calls 400 :max-tokens 2000000}}}

  The binary is found in this order: :bin, then `agentloop` on PATH, then
  `../agentloop/bin/agentloop` beside this repository (what `make -C
  ../agentloop build` makes).

  Each wake writes request.json in its run directory and runs
  `agentloop run request.json`. The model gets relay's tools and nothing
  else. A tool call runs `relay tool <name>` with the environment the request
  names, which never holds the API key. The transcript is agentloop's output:
  one JSON line per session entry, then an outcome line. The session itself
  is a file in the role's session directory, named by the session id.

  How a run ended is read from the outcome line, never from the exit code:
  agentloop exits 0 whenever it reaches an outcome."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [relay.harness :as harness])
  (:import (java.time Instant)))

(def default-model "claude-opus-5-5")
(def default-effort "medium")
(def default-provider "anthropic")

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

(defn api-key
  "The API key, from a command (such as a keychain lookup) or an environment
  variable. Nil when there is none."
  [{:keys [provider api-key-command api-key-env]}]
  (let [api-key-env (or api-key-env (if (= "openai" provider) "OPENAI_API_KEY" "ANTHROPIC_API_KEY"))]
    (if api-key-command
      (let [{:keys [exit out]} (apply p/shell {:out :string :err :string :continue true :in ""}
                                      api-key-command)]
        (when (zero? exit) (not-empty (str/trim out))))
      (not-empty (System/getenv api-key-env)))))

;; --- reading a run back

(defn parse-outcome
  "The last outcome line in agentloop's output, or nil."
  [text]
  (->> (str/split-lines (str text))
       (keep (fn [line]
               (when (str/starts-with? (str/triml line) "{")
                 (try (let [e (json/parse-string line true)]
                        (when (and (map? e) (= "outcome" (:type e))) e))
                      (catch Exception _ nil)))))
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

(defn classify
  "How a run ended. Pure: takes the parsed outcome line (nil when there was
  none), the exit code, stderr, the session id and the time."
  [{:keys [outcome exit stderr session now]}]
  (let [{:keys [reason text error]} (when (map? outcome) outcome)
        error (when (map? error) error)
        ;; Whatever the outcome line held, only strings are shown to a person.
        said-from (fn [x] (when (and (string? x) (not (str/blank? x))) x))
        said (harness/tail (or (said-from text) (said-from (:message error)) "") 8)
        report (fn [result & {:as more}]
                 (merge {:outcome result :exit exit :session session :said said
                         :crashed (= "timeout" exit)}
                        more))]
    (cond
      (= "timeout" exit) (report "ran" :said "Stopped at the wake deadline.")
      (nil? outcome) (report "needs-attention"
                             :said (str (if (and (number? exit) (> exit 128))
                                          (str "agentloop was killed (exit " exit ") before it finished. ")
                                          "agentloop did not start. ")
                                        (harness/tail stderr 5)))
      :else
      (case reason
        ("completed" "max_tokens" "limit" "held") (report "ran")
        "refused" (report "ran" :crashed true)
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
  "No API key: set :api-key-command or :api-key-env for the agentloop harness in relay.edn.")

(def ^:private no-bin
  "agentloop not found: set :bin, put agentloop on PATH, or run `make -C ../agentloop build`.")

(defn request-json
  "The request agentloop reads. `prompt` is nil for an interactive session."
  [{:keys [harness model system-prompt prompt tools env session-dir timeout-ms]} id work]
  (let [{:keys [provider effort base-url retry limits]} harness
        limit-keys {:max-turns :max_turns :max-tool-calls :max_tool_calls :max-tokens :max_tokens}
        retry-keys {:retries :retries :backoff-seconds :backoff_seconds
                    :max-wait-seconds :max_wait_seconds}]
    (cond-> {:provider (or provider default-provider)
             :model (or model (:model harness) default-model)
             :effort (or effort default-effort)
             :api_key_env "AGENTLOOP_API_KEY"
             :system system-prompt
             :session (str (fs/path session-dir (str id ".jsonl")))
             :tools (mapv #(select-keys % [:name :description :parameters]) tools)
             :tool_command [(or (get env "RELAY_BIN") (System/getProperty "relay.bin") "relay") "tool"]
             :tool_env (harness/base-env env)
             :tool_dir (str work)}
      base-url (assoc :base_url base-url)
      prompt (assoc :prompt prompt)
      timeout-ms (assoc :timeout_seconds (max 1 (- (quot timeout-ms 1000) 10)))
      (seq limits) (assoc :limits (into {} (keep (fn [[k v]] (when-let [k2 (limit-keys k)] [k2 v])) limits)))
      (seq retry) (assoc :retry (into {} (keep (fn [[k v]] (when-let [k2 (retry-keys k)] [k2 v])) retry))))))

(defn- prepare!
  "Writes request.json into the run directory. Returns {:id :work :file :bin
  :env}, or {:problem text} when it cannot run."
  [{:keys [harness run-dir session-dir resume] :as request}]
  (let [bin (agentloop-bin harness)
        key (api-key harness)]
    (cond
      (nil? key) {:problem no-key}
      (nil? bin) {:problem no-bin}
      :else
      (let [id (or resume (str (random-uuid)))
            work (str (fs/path run-dir "work"))
            file (str (fs/path run-dir "request.json"))]
        (fs/create-dirs work)
        (fs/create-dirs session-dir)
        (spit file (json/generate-string (request-json request id work)))
        {:id id :work work :file file :bin bin
         :env (harness/base-env {"AGENTLOOP_API_KEY" key})}))))

(defmethod harness/run! :agentloop
  [{:keys [run-dir timeout-ms] :as request}]
  (let [{:keys [problem id work file bin env]} (prepare! request)]
    (if problem
      {:outcome "needs-attention" :exit nil :crashed false :said problem}
      (let [out (str (fs/path run-dir "transcript.jsonl"))
            exit (harness/run-process! [bin "run" file]
                                       {:dir work :out-file out :env env
                                        :timeout-ms (+ timeout-ms 15000)})]
        (assoc (classify {:outcome (parse-outcome (slurp out))
                          :exit exit
                          :stderr (slurp (str out ".err"))
                          :session id
                          :now (Instant/now)})
               :transcript out)))))

(defmethod harness/chat! :agentloop [request]
  (let [{:keys [problem work file bin env]} (prepare! (dissoc request :prompt))]
    (when problem (throw (ex-info problem {})))
    (:exit (p/shell {:dir work :env env :continue true} bin "chat" file))))
