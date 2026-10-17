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
                 :tool-timeout-seconds 300
                 ;; the quarantined reader, for roles granted :quarantine;
                 ;; these are the defaults
                 :reader {:model \"claude-haiku-5-5\" :effort \"low\"}}}

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

  A role granted :quarantine also gets agentloop's read_quarantined: the
  request carries a quarantine block naming the tools it reads through,
  and the policy an entry for it of class read. agentloop adds the tool
  itself and decides the labels of its answers.

  A role granted tools from MCP servers (relay.edn's :mcp-servers) gets
  them in its request as agentloop names them: each tool by its relay.edn
  name with its server and the server's name for it, and mcp_servers
  holding only the servers those tools come from, each with its command
  and credentials command. agentloop runs the servers and their calls.
  tool_timeout_seconds is always sent, so every call, an MCP one
  included, gets the harness's :tool-timeout-seconds (300 by default),
  never agentloop's own default. A run whose MCP tools could not be set
  up (reason tools_unavailable) needs the operator: relay.core decides
  what happens to the role.

  How a run ended is read from the outcome line, never from the exit code:
  agentloop exits 0 whenever it reaches an outcome."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [relay.acp :as acp]
            [relay.harness :as harness]
            [relay.text :as text])
  (:import (java.time Instant)))

(def default-model "claude-opus-5-5")
(def default-effort "medium")
(def default-provider "anthropic")
(def default-tool-timeout-seconds 300)
(def default-reader {:model "claude-haiku-5-5" :effort "low"})

(def reader-tool
  "The tool agentloop adds for a quarantine block."
  "read_quarantined")

(defn- with-reader
  "A request with the quarantined reader turned on over `inner`, the tools
  the role may read through, when there are any. The reader's own policy
  entry is class read with no labels: agentloop gives each answer its
  labels from the inner tool's."
  [req harness inner]
  (if (seq inner)
    (let [{:keys [model effort]} (merge default-reader (:reader harness))]
      (-> req
          (assoc :quarantine {:model model :effort effort :tools (vec inner)})
          (assoc-in [:policy :tools reader-tool] {:class "read"})))
    req))

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

(defn parse-pins
  "The tool definitions a run's session pinned, from its pins entries
  (agentloop writes one when a session first pins a tool), as {tool-name
  sha256}. Lines that are not such an entry are passed over."
  [text]
  (into {}
        (for [line (str/split-lines (str text))
              :when (str/includes? line "\"pins\"")
              :let [e (try (json/parse-string line) (catch Exception _ nil))]
              :when (and (map? e) (= "pins" (get e "type")))
              [tool digest] (let [p (get-in e ["pins" "pins"])] (when (map? p) p))
              :when (and (string? tool) (re-matches #"[A-Za-z0-9_-]{1,64}" tool)
                         (string? digest) (re-matches #"[0-9a-f]{64}" digest))]
          [tool digest])))

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

(defn usage
  "A run's token counts from its outcome line, as relay records them, or
  nil when the line has none. A count that is not a whole number of zero
  or more is taken as 0."
  [u]
  (when (map? u)
    (let [n (fn [k] (let [x (get u k)] (if (and (integer? x) (>= x 0)) x 0)))]
      {:input (n :input_tokens) :output (n :output_tokens)
       :cache-read (n :cache_read_tokens) :cache-write (n :cache_write_tokens)})))

(defn cost-usd
  "What a run cost in US dollars, from its outcome line's cost_usd, or nil.
  agentloop leaves it out when any call's model is unpriced, and nil
  stays nil: an unknown cost is never taken as 0."
  [x]
  (when (and (number? x) (Double/isFinite (double x)) (>= (double x) 0.0))
    (double x)))

(def tools-message-limit
  "The most of a tools_error message relay keeps, in code points. The
  message can quote what an MCP server printed."
  300)

(defn tools-error
  "An outcome line's tools_error as relay records it: {:message :server
  :tool :pinned :got}, each only when it has the shape agentloop gives it.
  The message can hold a server's own text, so it is cut to
  tools-message-limit and its links are broken (relay.text/defang); the
  edge that shows it applies visible. Nil when there is no message."
  [e]
  (when (map? e)
    (let [{:keys [message server tool pinned got]} e
          name? #(and (string? %) (re-matches #"[A-Za-z0-9_-]{1,64}" %))
          digest? #(and (string? %) (re-matches #"[0-9a-f]{64}" %))
          cut (fn [^String s]
                (if (> (.codePointCount s 0 (count s)) tools-message-limit)
                  (str (subs s 0 (.offsetByCodePoints s 0 tools-message-limit)) "...")
                  s))]
      (when (and (string? message) (not (str/blank? message)))
        ;; One line: it goes into notifications and status lines.
        (cond-> {:message (cut (text/defang (str/replace (str/trim message) #"\s+" " ")))}
          (name? server) (assoc :server server)
          (name? tool) (assoc :tool tool)
          (digest? pinned) (assoc :pinned pinned)
          (digest? got) (assoc :got got))))))

(defn classify
  "How a run ended. Pure: takes the parsed outcome line (nil when there was
  none), the exit code, stderr, the session id and the time."
  [{:keys [outcome exit stderr session now]}]
  (let [{:keys [reason text error held labels label_sources refused forbidden cost_usd] :as line}
        (when (map? outcome) outcome)
        error (when (map? error) error)
        ;; Whatever the outcome line held, only strings are shown to a person.
        said-from (fn [x] (when (and (string? x) (not (str/blank? x))) x))
        said (harness/tail (or (said-from text) (said-from (:message error)) "") 8)
        report (fn [result & {:as more}]
                 (cond-> (merge {:outcome result :exit exit :session session :said said
                                 :crashed (= "timeout" exit)
                                 :cut-short (or (= "timeout" exit) (= "canceled" reason))}
                                more)
                   ;; What the run used and cost, whatever came of it.
                   (usage (:usage line)) (assoc :usage (usage (:usage line)))
                   (cost-usd cost_usd) (assoc :cost-usd (cost-usd cost_usd))
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
        ;; The role's MCP tools could not be set up, so nothing ran: a
        ;; server did not start, or a tool's definition is not the one the
        ;; request or the session pinned. The role needs the operator; its
        ;; harness is fine.
        "tools_unavailable"
        (let [te (or (tools-error (:tools_error line))
                     {:message "agentloop could not set up the role's MCP tools."})]
          (report "needs-attention" :said (:message te) :tools-error te))
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

(defn request-tool
  "One tool as the request names it. A command tool gives its description
  and schema. An MCP tool gives its server and the server's name for it,
  and the digest and content kinds relay.edn gives it, if any; its server
  gives the rest."
  [{:keys [mcp] :as tool}]
  (if mcp
    (let [{:keys [server digest allow-content]} mcp]
      (cond-> {:name (:name tool) :protocol "mcp-stdio" :server server :tool (:tool mcp)}
        digest (assoc :digest digest)
        (seq allow-content) (assoc :allow_content (vec allow-content))))
    (select-keys tool [:name :description :parameters])))

(defn request-mcp-servers
  "The MCP servers as the request gives them. agentloop runs a
  :credentials-command and puts what it prints in a private file for the
  server; relay passes only the command, never a secret."
  [servers]
  (into (sorted-map)
        (for [[n {:keys [command credentials-command send-labels]}] servers]
          [n (cond-> {:command (vec command)}
               credentials-command (assoc :credentials_command (vec credentials-command))
               send-labels (assoc :send_labels true))])))

(defn- base-request
  "What every agentloop request holds, whatever the mode."
  [{:keys [harness model system-prompt tools tool-command policy quarantine mcp-servers]} work]
  (let [{:keys [provider effort base-url retry limits tool-timeout-seconds api-key-command]} harness
        limit-keys {:max-turns :max_turns :max-tool-calls :max_tool_calls :max-tokens :max_tokens}
        retry-keys {:retries :retries :backoff-seconds :backoff_seconds
                    :max-wait-seconds :max_wait_seconds}]
    (cond-> {:provider (or provider default-provider)
             :model (or model (:model harness) default-model)
             :effort (or effort default-effort)
             :api_key_command (vec api-key-command)
             :system system-prompt
             :tools (mapv request-tool tools)
             :tool_command (vec tool-command)
             ;; Every tool is policed: the session's starting labels and
             ;; each tool's class and labels, with calls and results in
             ;; envelopes that carry labels both ways.
             :policy policy
             :tool_protocol "envelope"
             :tool_env (harness/base-env {})
             :tool_dir (str work)
             ;; Always given, so agentloop's own default (120 seconds once
             ;; there are MCP servers) never applies: every tool call, an
             ;; MCP one included, gets the harness's timeout.
             :tool_timeout_seconds (or tool-timeout-seconds default-tool-timeout-seconds)}
      (seq mcp-servers) (assoc :mcp_servers (request-mcp-servers mcp-servers))
      base-url (assoc :base_url base-url)
      (seq limits) (assoc :limits (into {} (keep (fn [[k v]] (when-let [k2 (limit-keys k)] [k2 v])) limits)))
      (seq retry) (assoc :retry (into {} (keep (fn [[k v]] (when-let [k2 (retry-keys k)] [k2 v])) retry)))
      ;; A role granted :quarantine gets read_quarantined.
      :always (with-reader harness quarantine))))

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
  by. read_quarantined, when granted, is of kind read. agentloop asks the provider for the model's context window itself."
  [{:keys [session-dir tools role quarantine] :as request} work]
  (let [kinds (cond-> (acp/tool-kinds tools)
                ;; agentloop adds the reader; a client shows it as a read.
                (seq quarantine) (assoc reader-tool "read"))]
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
  (let [pins (parse-pins out)]
    (cond-> (classify {:outcome (parse-outcome out) :exit exit :stderr err :session session :now now})
      (seq pins) (assoc :pins pins))))

(defmethod harness/acp-command :agentloop [{:keys [session-dir] :as request}]
  (let [{:keys [problem work file bin env] :as prepared} (prepare! request (fn [_ work] (acp-request-json request work)))]
    (if problem
      prepared
      {:argv [bin "acp" file] :dir work :env env :session-dir session-dir})))
