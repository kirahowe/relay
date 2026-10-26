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

  The request follows agentloop's contract: no call is held for anyone.
  Its policy carries only labels: the session's starting labels, the
  static labels of the tools that have any, and for a public role
  forbid_labels [\"private\"], so its session never holds the operator's
  private things (I3).

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
            [relay.json :as strict]
            [relay.text :as text])
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
                 (let [e (strict/parse-or line ::unreadable {:keywords? true})]
                   (cond
                     (and (map? e) (= "outcome" (:type e))) e
                     (= ::unreadable e) (when (str/includes? line "\"outcome\"") ::unreadable))))))
       last))

(defn parse-pins
  "The tool definitions a run's session pinned, from its pins entries
  (agentloop writes one when a session first pins a tool), as {tool-name
  sha256}. Lines that are not such an entry are passed over."
  [text]
  (into {}
        (for [line (str/split-lines (str text))
              :when (str/includes? line "\"pins\"")
              :let [e (strict/object line)]
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
  (let [{:keys [reason text error labels refused forbidden cost_usd] :as line}
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

;; --- displays

(defn parse-displays
  "The displays in a session's entries, one JSON line each (a run's
  transcript or a session file), in order: {:call :ref :title :body
  :labels :sources} from every tool_result entry with a display that has
  a ref. Lines that are not such an entry, and displays without the shape
  agentloop gives them, are passed over."
  [text]
  (vec (for [line (str/split-lines (str text))
             :when (and (str/includes? line "\"display\"") (str/includes? line "\"tool_result\""))
             :let [e (strict/object line)
                   r (when (and (map? e) (= "tool_result" (get e "type"))) (get e "tool_result"))
                   d (when (map? r) (get r "display"))
                   call (when (map? r) (get-in r ["result" "call_id"]))
                   labels (when (map? d) (get d "labels"))
                   sources (when (map? d) (get d "sources"))]
             :when (and (map? d) (string? call)
                        (string? (get d "ref")) (string? (get d "title")) (string? (get d "body"))
                        (sequential? labels) (every? #{"private" "contaminated"} labels)
                        (or (nil? sources)
                            (and (sequential? sources)
                                 (every? #(and (map? %) (string? (get % "title")) (string? (get % "ref"))) sources))))]
         {:call call :ref (get d "ref") :title (get d "title") :body (get d "body")
          :labels (vec (sort (distinct labels)))
          :sources (mapv (fn [x] {:title (get x "title") :ref (get x "ref")}) sources)})))

;; --- prepared: what edit tools did for the operator

(defn- strings [x] (filter string? (when (sequential? x) x)))

(defn parse-calls
  "The tool calls in a session's entries, one JSON line each, that were
  allowed and ended without an error, in order: {:call :tool :input
  :result :labels}. :labels are what the session held when the call ended,
  folded over the entries in order: the starting labels, the labels each
  earlier allowed decision and each earlier result added, and this call's
  own. (A decision holds only the labels the policy adds for that call, not
  what the session had seen.) Over-labelling is the safe direction. Lines
  that are not entries are passed over."
  [text]
  (let [entries (vec (for [line (str/split-lines (str text))
                           :when (or (str/includes? line "\"tool_call\"") (str/includes? line "\"decision\"")
                                     (str/includes? line "\"tool_result\"") (str/includes? line "\"labels\""))
                           :let [e (strict/object line)]
                           :when (map? e)]
                       e))
        ;; call id -> the session's labels once that call's result was in
        held (:at (reduce
                   (fn [{:keys [labels] :as acc} e]
                     (case (get e "type")
                       "labels" (assoc acc :labels (into labels (strings (get-in e ["labels" "labels"]))))
                       "decision" (let [d (get e "decision")]
                                    (if (and (map? d) (= "allow" (get d "verdict")))
                                      (assoc acc :labels (into labels (strings (get d "labels"))))
                                      acc))
                       "tool_result" (let [r (get e "tool_result")
                                           labels (into labels (strings (when (map? r) (get r "labels"))))
                                           id (when (map? r) (get-in r ["result" "call_id"]))]
                                       (cond-> (assoc acc :labels labels)
                                         (string? id) (assoc-in [:at id] labels)))
                       acc))
                   {:labels #{} :at {}} entries))
        by-type (fn [t k] (into {} (for [e entries :when (= t (get e "type"))
                                         :let [x (get e k)] :when (map? x)]
                                     [(if (= "decision" t) (get x "call_id") (get-in x ["result" "call_id"])) x])))
        decisions (by-type "decision" "decision")
        results (by-type "tool_result" "tool_result")]
    (vec (for [e entries
               :when (and (= "message" (get e "type")) (= "assistant" (get-in e ["message" "message" "role"])))
               part (get-in e ["message" "message" "parts"])
               :when (and (map? part) (= "tool_call" (get part "type")))
               :let [{:strs [id name input]} (get part "tool_call")
                     d (get decisions id)
                     r (get-in results [id "result"])]
               :when (and (string? id) (string? name) (= "allow" (get d "verdict")) (map? r)
                          (not (get r "is_error")) (string? (get r "content")))]
           {:call id :tool name :input (if (map? input) input {}) :result (get r "content")
            :labels (vec (sort (into (set (get held id)) (strings (get d "labels")))))}))))

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
    (let [{:keys [server digest allow-content fixed-args hidden-args]} mcp]
      (cond-> {:name (:name tool) :protocol (if (= :mcp-http (:protocol mcp)) "mcp-http" "mcp-stdio")
               :server server :tool (:tool mcp)}
        digest (assoc :digest digest)
        (seq allow-content) (assoc :allow_content (vec allow-content))
        ;; Enforced by agentloop before the server sees the call.
        (seq fixed-args) (assoc :fixed_args fixed-args)
        (seq hidden-args) (assoc :hidden_args (vec hidden-args))))
    (select-keys tool [:name :description :parameters])))

(defn request-mcp-servers
  "The MCP servers as the request gives them. agentloop runs a
  :credentials-command and puts what it prints in a private file for a
  stdio server, or sends it as the bearer token to a remote one (:url);
  relay passes only the command, never a secret."
  [servers]
  (into (sorted-map)
        (for [[n {:keys [command url credentials-command send-labels]}] servers]
          [n (cond-> (if url {:url url} {:command (vec command)})
               credentials-command (assoc :credentials_command (vec credentials-command))
               send-labels (assoc :send_labels true))])))

(defn- base-request
  "What every agentloop request holds, whatever the mode."
  [{:keys [harness model system-prompt tools tool-command policy mcp-servers display-ref-prefix]} work]
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
             ;; The session's starting labels and each tool's static
             ;; labels, with calls and results in envelopes that carry
             ;; labels both ways.
             :policy policy
             :tool_protocol "envelope"
             :tool_env (harness/base-env {})
             :tool_dir (str work)
             ;; Always given, so agentloop's own default (120 seconds once
             ;; there are MCP servers) never applies: every tool call, an
             ;; MCP one included, gets the harness's timeout.
             :tool_timeout_seconds (or tool-timeout-seconds default-tool-timeout-seconds)}
      (seq mcp-servers) (assoc :mcp_servers (request-mcp-servers mcp-servers))
      ;; Where agentloop says a display is kept: relay stores each one
      ;; under that ref (parse-displays), and the model is told it.
      display-ref-prefix (assoc :display_ref_prefix display-ref-prefix)
      base-url (assoc :base_url base-url)
      (seq limits) (assoc :limits (into {} (keep (fn [[k v]] (when-let [k2 (limit-keys k)] [k2 v])) limits)))
      (seq retry) (assoc :retry (into {} (keep (fn [[k v]] (when-let [k2 (retry-keys k)] [k2 v])) retry))))))

(defn request-json
  "The request `agentloop run` reads: the wake's prompt, and one session,
  the file named by `id` in the session directory."
  [{:keys [prompt session-dir timeout-ms] :as request} id work]
  (cond-> (assoc (base-request request work)
                 :session (str (fs/path session-dir (str id ".jsonl")))
                 :prompt prompt)
    timeout-ms (assoc :timeout_seconds (max 1 (- (quot timeout-ms 1000) 10)))))

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
  (let [pins (parse-pins out)]
    (cond-> (classify {:outcome (parse-outcome out) :exit exit :stderr err :session session :now now})
      (seq pins) (assoc :pins pins))))

(defmethod harness/acp-command :agentloop [{:keys [session-dir] :as request}]
  (let [{:keys [problem work file bin env] :as prepared} (prepare! request (fn [_ work] (acp-request-json request work)))]
    (if problem
      prepared
      {:argv [bin "acp" file] :dir work :env env :session-dir session-dir})))
