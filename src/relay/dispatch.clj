(ns relay.dispatch
  "The dispatcher. Nothing runs between ticks: something outside relay
  (launchd, once a minute) runs `relay tick`, which brings the log up to date
  with the clock and starts a wake for each role with work waiting. Each wake
  runs in its own process, so a slow role holds up neither the others nor
  the next tick."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.string :as str]
            [relay.acp :as acp]
            [relay.brief :as brief]
            [relay.config :as config]
            [relay.core :as core]
            [relay.harness :as harness]
            [relay.harness.agentloop :as agentloop]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.prompt :as prompt]
            [relay.refs :as refs]
            [relay.render :as render]
            [relay.reports :as reports]
            [relay.tools :as tools]
            [relay.workspace :as workspace])
  (:import (java.time Duration Instant)))

;; --- whether a wake's process is still there

(defn current-process
  "This process's pid and start time. The start time tells a live process
  apart from a later one that was given the same pid."
  []
  (let [me (java.lang.ProcessHandle/current)]
    {:pid (.pid me)
     :pid-start (some-> (.startInstant (.info me)) (.orElse nil) str)}))

(defn alive? [pid pid-start]
  (boolean
   (when pid
     (let [handle (java.lang.ProcessHandle/of (long pid))]
       (when (.isPresent handle)
         (let [h (.get handle)
               started (some-> (.startInstant (.info h)) (.orElse nil) str)]
           (and (.isAlive h)
                (or (nil? pid-start) (nil? started) (= pid-start started)))))))))

(defn lost-wakes
  "Running wakes whose process has gone, so nobody will ever end them."
  [state]
  (vec (for [{:keys [id pid pid-start status]} (vals (:wakes state))
             :when (and (= :running status) (not (alive? pid pid-start)))]
         id)))

(defn lost-verifications
  "Reports being checked whose process has gone, each with that process,
  so a check started since is not taken for it."
  [state]
  (vec (for [{:keys [id verifying]} (vals (:reports state))
             :when (and verifying (not (alive? (:pid verifying) (:pid-start verifying))))]
         {:report id :pid (:pid verifying) :pid-start (:pid-start verifying)})))

;; --- checking a report

(defn draw-sample
  "For each of `n` findings, whether it is drawn for the operator to review
  at trust level 1: one in reports/sample-one-in, from SecureRandom."
  [n]
  (let [rng (java.security.SecureRandom.)]
    (vec (repeatedly n #(zero? (.nextInt rng (int reports/sample-one-in)))))))

(defn verify!
  "Checks a report in this process: records that it started, runs the
  :verifier on every check the report needs, draws the sample, and
  records what came back, or that it failed. Flags are worked out by
  relay.core from what is recorded. `run-check` and `draw` stand in for
  the verifier and the random draw in tests. Returns {:result ...}."
  [{:keys [config] :as sys} report-id {:keys [by-hand run-check draw]
                                       :or {draw draw-sample
                                            run-check (fn [sys r request i]
                                                        (tools/run-verifier (:verifier (:config sys)) (:labels r) request
                                                                            (str "report-" (:id r) "-" i)))}}]
  (let [me (current-process)
        started (try (inbound/submit! sys {:type :report/verify-start :actor core/dispatcher
                                           :args (merge {:report report-id :by-hand (boolean by-hand)} me)})
                     (catch clojure.lang.ExceptionInfo e
                       (if-let [reason (core/rejection e)]
                         {:result reason :message (ex-message e)}
                         (throw e))))]
    (if (map? started)
      started
      (let [r (core/report (inbound/current-state sys) report-id)
            fail! (fn [why]
                    (inbound/submit! sys {:type :report/verify-fail :actor core/dispatcher
                                          :args (merge {:report report-id :error why} me)})
                    {:result :failed :message why})]
        (try
          (let [answers (mapv (fn [i request]
                                (let [{:keys [content labels]} (run-check sys r request i)]
                                  {:items (or (reports/parse-check-items request content)
                                              (throw (ex-info "The verifier's answer does not match what was asked." {})))
                                   :labels labels}))
                              (range) (reports/check-requests r))]
            (inbound/submit! sys {:type :report/verify :actor core/dispatcher
                                  :args (merge {:report report-id :results (vec (mapcat :items answers))
                                                :labels (vec (distinct (mapcat :labels answers)))
                                                :sampled (draw (count (:findings r)))}
                                               me)})
            {:result :verified :report report-id})
          (catch Exception e
            (fail! (or (ex-message e) (str (class e))))))))))

;; --- one wake

(defn relay-bin
  "The relay command, for harnesses to run tool calls with. bin/relay names
  itself in the relay.bin property."
  []
  (or (System/getProperty "relay.bin") "relay"))

(defn shared-instructions []
  (slurp (io/resource "AGENTS.md")))

(defn- operator-additions [data-dir]
  (let [f (fs/path data-dir "AGENTS.md")]
    (when (fs/exists? f) (slurp (str f)))))

(defn- not-before ^Instant [^Instant t ^Instant floor]
  (if (and floor (.isBefore t floor)) floor t))

(defn- now
  "The clock, but never earlier than the last thing the log recorded."
  ^Instant [{:keys [clock]} state]
  (not-before (clock) (:last-at state)))

(defn wake-inputs
  "What a wake's request needs from outside the log, read once at the edge:
  the shared instructions, the operator's additions, the clock, the relay
  command, and the data directory as an absolute path for tool calls."
  [{:keys [data-dir clock]}]
  {:shared (shared-instructions)
   :operator-additions (operator-additions data-dir)
   :now (clock)
   :relay-bin (relay-bin)
   :data-path (str (fs/absolutize data-dir))})

(defn wake-request
  "Everything a harness needs for one wake. Pure: everything read from
  outside the log comes in `inputs` (wake-inputs). The prompt's time is
  never earlier than the log's last entry."
  [{:keys [data-dir config]} state {:keys [id role memos note resumes harness resolves] :as wake}
   {:keys [shared operator-additions relay-bin data-path] :as inputs}]
  (let [role-row (core/role state role)
        run-dir (fs/path data-dir "runs" role (str id))
        resumed (when resumes (get-in state [:wakes resumes]))
        {shown-notes :shown withheld-notes :withheld} (core/prompt-notes state config role)
        {shown-skills :shown withheld-skills :withheld} (core/prompt-skills state config role)
        holds (map #(core/hold state %) resolves)
        script {:script? (tools/script-role? state config role)}
        ;; Each MCP tool is held to the definition relay.edn's :digest
        ;; gives it, else the one relay accepted first or with relay
        ;; fresh, else the one pinned when the operator connected its
        ;; server, so a changed one stops any session, not only its own.
        manifest (mapv (fn [{tool-name :name :keys [mcp] :as t}]
                         (if-let [d (and mcp (or (:digest mcp) (core/tool-pin state tool-name) (:pin mcp)))]
                           (assoc-in t [:mcp :digest] d)
                           t))
                       (tools/manifest config role script))
        public? (core/public-role? config role)
        labels (core/wake-labels state config wake)]
    ;; decide refuses a wake whose prompts would give a public role
    ;; private; this is the last check before anything reaches a harness.
    (when (and public? (core/private? labels))
      (throw (ex-info (str role " is a public role, and its prompts would hold private data.") {})))
    {:harness (get-in config [:harnesses (keyword harness)])
     :harness-name harness
     :role role-row
     :model (or (:model role-row) (get-in config [:harnesses (keyword harness) :model]))
     ;; The operator's own additions are unlabelled text that may hold
     ;; private details, so a public role is never given them.
     :system-prompt (prompt/instructions {:shared shared
                                          :operator-additions (when-not public? operator-additions)
                                          :notes shown-notes
                                          :withheld-notes withheld-notes
                                          :skills shown-skills
                                          :withheld-skills withheld-skills
                                          :granted (tools/grants-summary config role script)}
                                         role-row)
     :prompt (prompt/wake {:role role-row
                           :memos (map #(core/shown-memo state (core/memo state %)) memos)
                           :note note
                           :now (not-before (:now inputs) (:last-at state))
                           :zone (config/zone config)
                           :resumed? (some? resumes)
                           :decided holds
                           ;; What the role would otherwise spend its first
                           ;; calls on, as far as the wake's labels cover.
                           :journal (core/prompt-journal state config role labels)
                           :fact-topics (core/prompt-fact-topics state config role labels)
                           :cards (core/prompt-cards state config role labels)
                           :public? public?})
     :tools manifest
     ;; The session starts with the labels of everything in its prompts,
     ;; and each tool is policed by its class and static labels.
     :policy (tools/policy manifest labels {:public? public?})
     ;; The tools the role may read through agentloop's quarantined
     ;; reader, if the operator granted any.
     :quarantine (when-not (:script? script) (config/quarantine-tools config role))
     ;; Of those, the ones the model is never offered.
     :reader-only (when-not (:script? script) (config/reader-only-tools config role))
     ;; The sandboxed workbench, if the operator granted it. Its
     ;; commands may not read relay's own data.
     :workbench (when-not (:script? script) (config/workbench config role))
     :data-dir (str (fs/absolutize data-dir))
     ;; The MCP servers the role's granted tools come from, and no others.
     :mcp-servers (config/mcp-servers-for config (map :name manifest))
     ;; Where the role's displays are kept, by the day the wake started.
     :display-ref-prefix (core/shown-ref-prefix (not-before (:now inputs) (:last-at state)) (config/zone config))
     :resolve (vec (for [{:keys [call status reason input-sha256]} holds]
                     (cond-> {:call_id call :verdict (if (= :allowed status) "allow" "deny")}
                       (not (str/blank? reason)) (assoc :reason reason)
                       input-sha256 (assoc :input_sha256 input-sha256))))
     ;; The wake is named so a call is tied to the session that made it.
     :tool-command [relay-bin "tool" "--data" data-path "--role" role "--wake" (str id)]
     :run-dir (str run-dir)
     :session-dir (str (fs/path data-dir "runs" role "sessions"))
     :resume (:session resumed)
     :timeout-ms (long (* 60 1000 (:wake-timeout-minutes config)))}))

(defn- stop-tree!
  "Stops a process and its descendants, and returns once they have gone, so
  that nothing of a run outlives its wake. Asks first; anything still there
  after a few seconds is killed."
  [proc]
  (let [^Process jp (:proc proc)
        tree (cons (.toHandle jp) (iterator-seq (.iterator (.descendants jp))))]
    (doseq [^java.lang.ProcessHandle h tree] (.destroy h))
    (doseq [^java.lang.ProcessHandle h tree]
      (when (= ::alive (deref (.onExit h) 3000 ::alive))
        (.destroyForcibly h)
        (deref (.onExit h) 3000 nil)))))

(defn run-command!
  "Runs a harness's wake command (relay.harness/wake-command) to completion
  or its deadline, with stdout in its :out-file and stderr beside it.
  Returns what came back, for relay.harness/wake-report: {:exit :out :err
  :report}, where :exit is \"timeout\" when the command was stopped and
  :report is the text of its :report-file, if it wrote one."
  [{:keys [argv dir env in out-file timeout-ms report-file]}]
  (let [err-file (str out-file ".err")
        proc (p/process (vec argv) {:dir (str dir) :env env :in (or in "")
                                    :out :write :out-file (fs/file out-file)
                                    :err :write :err-file (fs/file err-file)})
        result (deref proc timeout-ms ::timeout)
        exit (if (= ::timeout result)
               (do (stop-tree! proc) "timeout")
               (:exit result))]
    {:exit exit
     :out (slurp out-file)
     :err (slurp err-file)
     :report (when (and report-file (fs/exists? report-file)) (slurp report-file))}))

(defn run-harness!
  "Runs a wake's request: has the harness describe the command, runs it,
  and has the harness read the result. Anything thrown becomes a report,
  so that a wake that started always ends."
  [{:keys [clock]} request]
  (try
    (if-not (:harness request)
      {:outcome "needs-attention" :exit nil :crashed false
       :said (str "relay.edn has no harness called " (:harness-name request) ".")}
      (let [{:keys [problem out-file] :as command} (harness/wake-command request)]
        (if problem
          {:outcome "needs-attention" :exit nil :crashed false :said problem}
          (let [ran (assoc (run-command! command) :now (clock))]
            (assoc (harness/wake-report request command ran) :transcript out-file)))))
    (catch Exception e
      {:outcome "ran" :exit nil :crashed true :cut-short false
       :said (str "The harness adapter failed: " (or (ex-message e) (class e)))})))

(defn- exit-field
  "An exit code as the log keeps it: a number, or a word such as \"timeout\"."
  [exit]
  (if (number? exit) exit (some-> exit str)))

(defn wake-end-args
  "The :wake/end args for a harness's report on a wake."
  [wake-id report]
  (-> (select-keys report [:outcome :exit :crashed :cut-short :session :said :resets-at :held :labels
                           :refused :forbidden :usage :cost-usd :tools-error :pins :shown])
      (update :exit exit-field)
      (update :resets-at #(some-> % str))
      (assoc :wake wake-id
             :transcript (some-> (:transcript report) str))))

(defn store-displays!
  "Keeps each display in `text`, a session's entries one JSON line each,
  as a doc under shown/, written as the role for its wake with the
  display's labels (core :display/show). A display already kept changes
  nothing. Returns the displays that are kept, each {:call :ref :path}.
  One relay cannot keep is reported on stderr and passed over: the wake
  goes on."
  [sys role-id wake-id text]
  (vec (for [d (agentloop/parse-displays text)
             :let [path (core/shown-ref-path (:ref d))
                   kept (when path
                          (try (inbound/submit! sys {:type :display/show :actor role-id :session-wake wake-id
                                                     :labels (:labels d)
                                                     :args (select-keys d [:ref :title :body :sources :call])})
                               true
                               (catch Exception e
                                 (binding [*out* *err*]
                                   (println (str "relay could not keep the display " (:ref d) ": " (ex-message e))))
                                 false)))]
             :when kept]
         {:call (:call d) :ref (:ref d) :path path})))

(defn- transcript-text [{:keys [transcript]}]
  (when (and transcript (fs/exists? transcript))
    (slurp (str transcript))))

(defn with-previews
  "A harness report with a preview on each held call whose tool has a
  :preview command and that relay has not recorded yet: the command's
  answer for the call's input, under the session's labels. The preview is
  run now, at hold time, so the operator sees what the service says the
  call would do. It is given the input the call would run on, with the
  arguments the operator fixed."
  [{:keys [config]} state report]
  (let [known (set (map (juxt :session :call) (vals (:holds state))))]
    (update report :held
            (fn [held]
              (when held
                (mapv (fn [{:keys [id name input effective-input] :as c}]
                        (let [[tool tool-input] (core/preview-target name (or effective-input input))
                              command (get-in config [:tools tool :preview])]
                          (if (and command (map? c) (not (known [(:session report) id])))
                            (assoc c :preview (tools/run-preview! tool command tool-input (:labels report) id))
                            c)))
                      held))))))
(defn wake!
  "Starts a wake of a role, runs it and ends it. Returns {:result ...}:
  :idle when there was nothing to do, :busy, :paused or :runaway when it was
  not started, and otherwise the wake's id and outcome. :public says the
  operator's note may go out, as a public role's needs."
  [sys role-id {:keys [note dispatched public]}]
  (let [started (try
                  (inbound/submit! sys {:type :wake/start
                                        :public (boolean public)
                                        :actor (if dispatched core/dispatcher core/operator)
                                        :args (merge {:role role-id :note note
                                                      :dispatched (boolean dispatched)}
                                                     (current-process))})
                  (catch clojure.lang.ExceptionInfo e
                    (if-let [reason (core/rejection e)]
                      {:result reason :message (ex-message e)}
                      (throw e))))]
    (cond
      (map? started) started
      ;; Starting can write other events first (ending a public role's
      ;; sessions from before it was public) and then find nothing to do.
      (not-any? #(= "wake/started" (:type %)) started) {:result :idle}
      :else
      (let [wake-id (:seq (first (filter #(= "wake/started" (:type %)) started)))
            state (inbound/current-state sys)
            report (run-harness! sys (wake-request sys state (get-in state [:wakes wake-id])
                                                   (wake-inputs sys)))
            report (with-previews sys (inbound/current-state sys) report)
            ;; Kept while the wake still runs, so they are written as the
            ;; role's session, and the end of the wake can mention them.
            shown (mapv :path (store-displays! sys role-id wake-id (transcript-text report)))
            report (cond-> report (seq shown) (assoc :shown shown))]
        (inbound/submit! sys {:type :wake/end :actor core/dispatcher
                              :args (wake-end-args wake-id report)})
        (let [state (inbound/current-state sys)]
          {:result :ran
           :wake wake-id
           :outcome (:outcome report)
           :left-open (count (filter #(= :unread (:status (core/memo state %)))
                                     (get-in state [:wakes wake-id :memos])))})))))

(defn- reap-lost-wakes!
  "Ends the running wakes whose process has gone, as a tick would, so a
  conversation that was killed does not hold its role until the next tick."
  [sys]
  (when-let [lost (seq (lost-wakes (inbound/current-state sys)))]
    (inbound/submit! sys {:type :tick :actor core/dispatcher :args {:lost-wakes (vec lost)}})))

;; --- a conversation

(def conversation-note
  "The operator has opened a conversation with you. Talk with them directly;
  use your tools as you would in a wake.")

(defn conversation-request
  "The harness request for a conversation: a wake's request with no prompt,
  told it is a conversation, keeping its sessions apart from the role's
  wake sessions, so that a client listing or loading sessions can reach
  only conversations. Its system prompt says when it started."
  [{:keys [data-dir config] :as sys} state wake inputs]
  (-> (wake-request sys state wake inputs)
      (update :system-prompt str "\n\n" conversation-note " "
              (prompt/conversation-start (not-before (:now inputs) (:last-at state)) (config/zone config))
              "\n"
              ;; A conversation has no wake prompt, so the board, as far as
              ;; its labels cover, goes at the end of what it is told.
              (prompt/board (core/prompt-cards state config (:role wake) (core/wake-labels state config wake))))
      (dissoc :prompt)
      ;; A directory of its own, kept apart from any conversation from
      ;; before relay kept labels, so a client cannot load one of those.
      ;; A public role's are kept apart again, since one from before it
      ;; was public may hold private text that no label records.
      (assoc :session-dir (str (fs/path data-dir "runs" (:role wake)
                                        (if (core/public-role? (:config sys) (:role wake))
                                          "conversations-public"
                                          "conversations-labelled"))))))

(defn conversation-end-args
  "The :wake/end args for a conversation, from what the code that ran it
  reported: {:exit :sessions :said :crashed :cost-usd}, any of which may
  be missing. Total: whatever the report holds, core accepts the result,
  so a conversation that started always ends."
  [wake-id {:keys [exit sessions said crashed cost-usd]}]
  (let [sessions (when (sequential? sessions)
                   (filterv #(and (string? %) (not (str/blank? %))) sessions))]
    (cond-> {:wake wake-id :outcome "ran" :exit (exit-field exit) :crashed (true? crashed)
             :cut-short false
             :said (if (and (string? said) (not (str/blank? said))) said "Conversation ended.")}
      (seq sessions) (assoc :sessions sessions)
      (number? cost-usd) (assoc :cost-usd cost-usd))))

(defn with-conversation!
  "Opens a conversation with a role and calls (f request) with its harness
  request. The conversation holds the role like a wake, so no wake runs
  alongside it, and carries no memos. f returns a report for
  conversation-end-args, and the wake always ends with it. When f throws,
  the wake ends saying why and the exception goes on to the caller.
  Returns f's report."
  [sys role-id f]
  (reap-lost-wakes! sys)
  (let [started (inbound/submit! sys {:type :wake/start :actor core/operator
                                      :args (merge {:role role-id :chat true
                                                    :note conversation-note}
                                                   (current-process))})
        wake-id (or (:seq (first (filter #(= "wake/started" (:type %)) started)))
                    (throw (ex-info (str "relay could not open a conversation with " role-id ".") {})))
        report (volatile! nil)]
    (try
      (let [state (inbound/current-state sys)
            request (conversation-request sys state (get-in state [:wakes wake-id])
                                          (wake-inputs sys))]
        (when-not (:harness request)
          (throw (ex-info (str "relay.edn has no harness called " (:harness-name request) ".") {})))
        (vreset! report (f (assoc request :wake-id wake-id))))
      (catch Exception e
        (vreset! report {:crashed true :said (or (ex-message e) (str (class e)))})
        (throw e))
      (finally
        (inbound/submit! sys {:type :wake/end :actor core/dispatcher
                              :args (conversation-end-args wake-id @report)})))))

(defn- with-signals
  "Calls f with the named signals handled by the given functions, and puts
  the old handlers back after."
  [handlers f]
  (let [installed (mapv (fn [[sig handle]]
                          (let [s (sun.misc.Signal. sig)]
                            [s (sun.misc.Signal/handle s (reify sun.misc.SignalHandler
                                                           (handle [_ _] (handle))))]))
                        handlers)]
    (try (f)
         (finally (doseq [[s old] installed] (sun.misc.Signal/handle s old))))))

(def ^:private terminal-signals
  "SIGINT and SIGHUP, ignored. The terminal sends them to relay as well as
  to the program relay runs on it, and relay has to outlive that program:
  `relay acp` to record that the conversation ended, `relay chat` to wait
  for its client. The program decides what they mean."
  {"INT" (fn []) "HUP" (fn [])})

;; --- a conversation over ACP

(def stop-grace-ms
  "How long an ACP agent has to exit after its stdin closes, before relay
  kills it."
  5000)

(defn- spawn
  "Runs f on a daemon thread. Returns a promise of f's value, or of the
  exception it threw."
  [f]
  (let [done (promise)
        t (Thread. ^Runnable (fn [] (deliver done (try (f) (catch Throwable e e)))))]
    (.setDaemon t true)
    (.start t)
    done))

(defn- write-chunk!
  "Writes a chunk and flushes it. A PrintStream (System/out) swallows
  write errors, so they are looked for after the flush."
  [^java.io.OutputStream out ^bytes buf n]
  (.write out buf 0 (int n))
  (.flush out)
  (when (and (instance? java.io.PrintStream out) (.checkError ^java.io.PrintStream out))
    (throw (java.io.IOException. "the output stream failed"))))

(def line-limit
  "How long a line relay will hold back to read whole before passing it
  on unread. Far longer than any ACP message relay looks at: a permission
  request carries one tool call's input."
  (* 8 1024 1024))

(defn- iso [^bytes bs n] (String. bs 0 (int n) java.nio.charset.StandardCharsets/ISO_8859_1))

(defn- note-oversized
  "Folds a piece of an oversized line into what is kept of it: its first
  4 KB, whether `request_permission` appears anywhere in it (looking
  across piece boundaries), and its length."
  [{:keys [prefix tail size] :as o} ^bytes piece n]
  (let [text (iso piece n)]
    (assoc o
           :prefix (if (< (count prefix) 4096) (subs (str prefix text) 0 (min 4096 (+ (count prefix) (count text)))) prefix)
           :permission? (or (:permission? o) (str/includes? (str tail text) "request_permission"))
           :tail (let [t (str tail text)] (subs t (max 0 (- (count t) 32))))
           :size (+ (or size 0) n))))

(defn- pump!
  "Copies from `in` to `out`, flushing each write, and passes each complete
  line to (tap line). Once a write fails, calls (on-write-failed) and keeps
  reading, so the writer on the far side of `in` is never blocked, but
  writes nothing more. Returns at the end of `in`, after tapping any last
  unfinished line.

  Without `screen`, bytes are copied chunk by chunk as they come. With it,
  they are copied line by line, and (screen line) decides each: :pass
  copies it, nil drops it, and a string is written in its place. A line
  longer than `limit` cannot be screened whole. With `on-oversized` it is
  dropped as it streams and (on-oversized {:prefix :permission? :size}) is
  called once it ends; without, it is copied unscreened as it comes.
  Writes to `out` are made holding a lock on it, so another thread may
  write whole lines to it too."
  [^java.io.InputStream in out tap on-write-failed & [{:keys [screen limit on-oversized] :or {limit line-limit}}]]
  (let [buf (byte-array 65536)
        failed? (atom false)
        write! (fn [^bytes bs n]
                 (when-not @failed?
                   (try (locking out (write-chunk! out bs n))
                        (catch java.io.IOException _ (reset! failed? true) (on-write-failed)))))
        ;; An oversized line is passed on, or dropped and noted.
        long-piece! (fn [over ^bytes bs n]
                      (if on-oversized (note-oversized over bs n) (do (write! bs n) over)))
        long-done! (fn [over] (when on-oversized (on-oversized (dissoc over :tail))))]
    (loop [pending acp/no-rest over nil]
      (let [n (try (.read in buf) (catch java.io.IOException _ -1))]
        (if (neg? n)
          (let [last-bytes (acp/left-over pending)]
            (cond
              over (long-done! over)
              (pos? (alength last-bytes))
              ;; A last line with no newline is screened like any other:
              ;; a client may still read it, so it must not get past.
              (let [text (acp/text last-bytes)
                    decision (when screen (screen text))]
                (tap text)
                (cond
                  (= :pass decision) (write! last-bytes (alength last-bytes))
                  (string? decision) (let [bs (.getBytes (str decision) java.nio.charset.StandardCharsets/UTF_8)]
                                       (write! bs (alength bs)))
                  :else nil))))
          (let [chunk (java.util.Arrays/copyOf buf n)
                [rest' lines] (acp/split pending chunk)]
            (if-not screen
              (do (write! chunk n)
                  (doseq [line lines] (tap (acp/text line)))
                  (recur rest' nil))
              (let [;; An oversized line ends at the first newline of this chunk.
                    nl (when over (let [i (.indexOf (iso chunk n) 10)] (when (>= i 0) i)))
                    over (if over (long-piece! over chunk (if nl (inc nl) n)) over)
                    _ (when (and over nl) (long-done! over))
                    [lines over] (cond (nil? over) [lines nil]
                                       nl [(rest lines) nil]
                                       :else [[] over])]
                (doseq [^bytes line lines
                        :let [text (acp/text line)
                              decision (screen text)]]
                  (tap text)
                  (cond
                    (= :pass decision) (let [bs (byte-array (inc (alength line)))]
                                         (System/arraycopy line 0 bs 0 (alength line))
                                         (aset-byte bs (alength line) (byte 10))
                                         (write! bs (alength bs)))
                    (string? decision) (let [bs (.getBytes (str decision "\n") java.nio.charset.StandardCharsets/UTF_8)]
                                         (write! bs (alength bs)))
                    :else nil))
                (let [left (acp/left-over rest')]
                  (if (and (nil? over) (> (alength left) limit))
                    (recur acp/no-rest (long-piece! {} left (alength left)))
                    (recur (if over acp/no-rest rest') over)))))))))))

(defn- private-trace-file!
  "Makes sure a trace file is the operator's alone: refused in a directory
  others can write to, such as /tmp, and created, or kept, readable by its
  owner only. A trace holds everything the conversation said."
  [file]
  (let [parent (or (fs/parent (fs/absolutize file)) (fs/path "."))
        perms (fs/posix-file-permissions parent)]
    (when (some #{java.nio.file.attribute.PosixFilePermission/OTHERS_WRITE
                  java.nio.file.attribute.PosixFilePermission/GROUP_WRITE} perms)
      (throw (ex-info (str "The trace file must be in a directory only you can write to, not "
                           parent ".") {})))
    (when-not (fs/exists? file)
      (fs/create-file file {:posix-file-permissions "rw-------"}))
    (fs/set-posix-file-permissions file "rw-------")))

(defn- tracer
  "A function that writes one line of traffic to the trace file as JSON,
  with its direction, or nil without a file. Both directions write to it,
  so the writes are serialised."
  [file]
  (when file
    (private-trace-file! file)
    (let [w (io/writer (fs/file file) :append true)]
      (fn [direction line]
        (locking w
          (.write w (str (json/generate-string (acp/trace-entry direction line)) "\n"))
          (.flush w))))))

(defn proxy!
  "Starts the described ACP agent and copies bytes between it and the
  client unchanged: `in` to the agent's stdin and its stdout to `out`, one
  chunk at a time, flushing each. The agent's stderr is relay's.

  Every complete line in either direction goes to relay.acp/observe, and
  to the trace file when there is one. The agent's stdin is closed when
  the client's input ends, when writing to the client fails, or when `stop`
  is delivered; an agent still running after `grace-ms` is killed by its
  pid, with its descendants. Returns {:exit :observed}.

  The one exception to copying unchanged is the agent's
  session/request_permission. With :approvals, it goes to the client, and
  the client's answer goes back only if it picks an option the agent
  offered (or cancels); anything else is replaced by a refusal, and a
  request still open when the client goes away is refused. Without
  :approvals, the client never sees it: relay refuses it. Each request
  and each answer is passed to (on-permission :asked req) and
  (on-permission :answered req {:outcome :by}), :by being client or
  relay. An agent line longer than `line-limit` is never passed on, since
  it cannot be read whole: relay refuses it if it is a permission request
  whose id it can find, and calls (on-oversized {:size :permission?
  :refused}). A client line that long is passed on unread.

  More hooks serve the workspace pane. (extension line) sees each of the
  client's lines first: a string it returns is written to the client as
  the answer and the line goes no further; a function it returns is
  called with the writer to the client, to write the answer itself; :none
  drops the line; nil passes it on. (on-display session) is called for an
  agent line that carries a display, before the line is passed on.
  (watch send! done) runs on a thread of its own from the start, with
  `send!` writing one line to the client, until the promise `done` is
  delivered as the proxy ends.

  Only relay speaks relay's methods to the client: an agent line whose
  method starts with _relay/ is dropped, so an agent cannot fake a card
  update or a pane item.

  (preview req) gives a request the client will see its tool's preview,
  {:markdown ...} and the preview relay records, or nil. The request is
  passed on with the markdown added (relay.acp/with-preview), and
  on-permission gets the request as read from that line, so its :preview
  is the text the client was shown, and the record as :preview-record."
  [{:keys [argv dir env]} {:keys [in out trace stop grace-ms approvals on-permission on-oversized line-limit
                                  extension on-display preview watch]
                           :or {grace-ms stop-grace-ms on-permission (fn [& _]) on-oversized (fn [_])
                                line-limit line-limit extension (fn [_]) on-display (fn [_]) preview (fn [_] nil)}}]
  (let [proc (p/process (vec argv) (cond-> {:err :inherit} dir (assoc :dir (str dir)) env (assoc :env env)))
        ^Process jp (:proc proc)
        ^java.io.OutputStream agent-in (:in proc)
        stop (or stop (promise))
        observed (atom acp/observer)
        trace (tracer trace)
        tap (fn [direction]
              (fn [line]
                (swap! observed acp/observe direction line)
                (when trace (trace direction line))))
        stopper (spawn (fn []
                         (when-not (= ::done @stop)
                           (try (.close agent-in) (catch java.io.IOException _ nil))
                           (when-not (.waitFor jp grace-ms java.util.concurrent.TimeUnit/MILLISECONDS)
                             (let [h (java.lang.ProcessHandle/of (.pid jp))
                                   family (concat (some-> (.orElse h nil) .descendants .iterator iterator-seq)
                                                  [(.orElse h nil)])]
                               (doseq [^java.lang.ProcessHandle x (remove nil? family)]
                                 (.destroyForcibly x)))))))
        outstanding (atom {})
        client-gone (atom false)
        refuse! (fn [req why]
                  (let [bs (.getBytes (str (json/generate-string (acp/refusal req)) "\n")
                                      java.nio.charset.StandardCharsets/UTF_8)]
                    ;; Without an id there is nothing to answer; the line is
                    ;; still never passed on.
                    (when (some? (:id req))
                      (try (locking agent-in (write-chunk! agent-in bs (alength bs)))
                           (catch java.io.IOException _ nil)))
                    (on-permission :answered req {:outcome (if (some #(= "reject_once" (get % "kind")) (:options req))
                                                             "reject_once" "cancelled")
                                                  :by "relay" :why why})))
        from-agent (fn [line]
                     (if (workspace/relay-method-line? line)
                       ;; relay's own methods are relay's to send.
                       (do (binding [*out* *err*] (println "relay dropped an agent line that used one of relay's methods."))
                           nil)
                     (if-let [req (or (acp/permission-request line)
                                      ;; One relay cannot read is never passed on.
                                      (acp/unreadable-permission-request line))]
                       (let [passing (and approvals (not @client-gone) (not (:unreadable req)))
                             ;; Only a request the client will see gets a
                             ;; preview, which goes with it.
                             {:keys [markdown display-labels] :as p} (when passing (preview req))
                             line' (when markdown (acp/with-preview line markdown display-labels))
                             ;; Read again from the line the client gets, so
                             ;; what is recorded and listed is what it shows.
                             req (cond-> (or (some-> line' acp/permission-request) req)
                                   p (assoc :preview-record (-> (dissoc p :markdown :display-labels)
                                                                (assoc :labels (vec (or display-labels (:labels p)))))))]
                         (on-permission :asked req)
                         (if passing
                             (do (swap! outstanding assoc (:id req) req)
                                 ;; The client may have gone in the meantime.
                                 (when (and @client-gone (get @outstanding (:id req)))
                                   (swap! outstanding dissoc (:id req))
                                   (refuse! req "client-gone"))
                                 (or line' :pass))
                             (do (refuse! req (cond (:unreadable req) "unreadable"
                                                    (not approvals) "no-approvals"
                                                    :else "client-gone"))
                                 nil)))
                       ;; A display is kept before the client sees it, so
                       ;; the pane can ask about it at once.
                       (do (when-let [session (acp/display-session line)]
                             (try (on-display session)
                                  (catch Exception e
                                    (binding [*out* *err*] (println "relay could not keep a display:" (ex-message e))))))
                           ;; A client that knows nothing of displays
                           ;; sees their text inert, whatever the agent
                           ;; sent.
                           (or (acp/with-inert-displays line) :pass)))))
        to-client! (fn [^String text]
                     (let [bs (.getBytes (str text "\n") java.nio.charset.StandardCharsets/UTF_8)]
                       (try (locking out (write-chunk! out bs (alength bs)))
                            (catch java.io.IOException _ nil))))
        from-client (fn [line]
                      (if-let [answer (extension line)]
                        ;; relay's own methods are answered here and never
                        ;; reach the agent.
                        (do (cond (string? answer) (to-client! answer)
                                  (fn? answer) (answer to-client!))
                            nil)
                      (if-let [[req answer] (some (fn [req] (when-let [a (acp/answer-of req line)] [req a]))
                                                  (vals @outstanding))]
                        (do (swap! outstanding dissoc (:id req))
                            (on-permission :answered req {:outcome (:outcome answer) :by "client"})
                            (if (:valid answer) :pass (json/generate-string (acp/refusal req))))
                        (if (and (seq @outstanding) (acp/stray-answer? line))
                          ;; An answer to no open request: the client is
                          ;; confused, so what is open is refused rather
                          ;; than left to hang the turn.
                          (do (doseq [req (vals @outstanding)]
                                (swap! outstanding dissoc (:id req))
                                (refuse! req "client-error"))
                              nil)
                          :pass))))
        watch-done (promise)
        watcher (when watch (spawn (fn [] (watch to-client! watch-done))))
        to-agent (spawn (fn []
                          ;; Line by line, so a refusal relay writes to the agent
                          ;; never lands inside one of the client's messages.
                          (pump! in agent-in (tap :client) #(deliver stop :agent-gone)
                                 {:screen from-client :limit line-limit})
                          ;; A request the client never answered is refused,
                          ;; and so is any asked from now on.
                          (reset! client-gone true)
                          (doseq [req (vals @outstanding)]
                            (swap! outstanding dissoc (:id req))
                            (refuse! req "client-gone"))
                          (deliver stop :client-gone)))
        ;; An agent line too long to read whole could be a permission
        ;; request, so it never reaches the client: relay refuses it when it
        ;; can tell it is one and which, and tells the operator either way.
        oversized (fn [{:keys [prefix permission? size]}]
                    (let [id (when permission?
                               (some-> (re-find #"\"id\"\s*:\s*(-?\d+|\"(?:[^\"\\\\]|\\\\.)*\")" (str prefix))
                                       second json/parse-string))
                          req (when (some? id) {:id id :options []})]
                      (when req
                        (let [bs (.getBytes (str (json/generate-string (acp/refusal req)) "\n")
                                            java.nio.charset.StandardCharsets/UTF_8)]
                          (try (locking agent-in (write-chunk! agent-in bs (alength bs)))
                               (catch java.io.IOException _ nil))))
                      (on-oversized {:size size :permission? (boolean permission?) :refused (some? req)})))
        to-client (spawn (fn [] (pump! (:out proc) out (tap :agent) #(deliver stop :client-gone)
                                       {:screen from-agent :limit line-limit :on-oversized oversized})))
        exit (.waitFor jp)
        ;; A request still open when the agent stopped was never answered.
        _ (doseq [req (vals @outstanding)]
            (swap! outstanding dissoc (:id req))
            (on-permission :answered req {:outcome "abandoned" :by "relay" :why "agent-gone"}))]
    ;; The agent's stdout ends with it, unless something it started holds
    ;; the pipe open; that cannot keep relay waiting for long.
    (deref to-client 5000 nil)
    (deliver watch-done true)
    (when watcher (deref watcher 5000 nil))
    (deliver stop ::done)
    (deref stopper 1000 nil)
    {:exit exit :observed @observed}))

(defn- permission-recorder
  "Records a conversation's permission requests and their answers in the
  log. A record that fails is dropped: the conversation goes on."
  [sys wake-id]
  (fn [kind {:keys [session call tool input labels preview-record]} & [{:keys [outcome by why]}]]
    (try
      (inbound/submit! sys {:type (if (= :asked kind) :permission/asked :permission/answered)
                            :actor core/dispatcher
                            :args (cond-> {:wake wake-id :session session :call call :tool tool
                                           :input-sha256 (core/sha256 (json/generate-string input))
                                           :labels labels :outcome outcome :by by :why why}
                                    (and preview-record (= :asked kind)) (assoc :preview preview-record))})
      (catch Exception e
        (binding [*out* *err*] (println "relay could not record a permission request:" (ex-message e)))))))

(defn- permission-previewer
  "Runs the :preview command of the tool a conversation's permission
  request is for, and gives the preview with the markdown the client is
  shown, or nil when the tool has none. A preview that cannot be had is
  shown as such: the request goes to the client all the same.

  :display-labels are the labels the client renders the preview by: the
  preview command's, the tool's static ones and the session's, as for a
  wake's hold (relay.core/preview-labels). The input is the one the call
  would run on: agentloop puts the arguments the operator fixed in it."
  [{:keys [config] :as sys}]
  (fn [{:keys [tool input labels call]}]
    (let [[tool input] (when (string? tool) (core/preview-target tool input))]
      (when-let [command (and (string? tool) (get-in config [:tools tool :preview]))]
        (let [p (tools/run-preview! tool command input labels call)]
          (assoc p
                 :markdown (acp/preview-markdown p (core/approved-hosts (inbound/current-state sys)))
                 :display-labels (core/preview-labels config tool p labels)))))))

(defn- oversize-recorder
  "Records an agent line relay dropped for being too long to read, and
  tells the operator."
  [sys wake-id]
  (fn [{:keys [size permission? refused]}]
    (try
      (inbound/submit! sys {:type :conversation/line-dropped :actor core/dispatcher
                            :args {:wake wake-id :size size :permission permission? :refused refused}})
      (catch Exception e
        (binding [*out* *err*] (println "relay could not record a dropped line:" (ex-message e)))))))

(def ^:private session-id-pattern
  #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

(defn- keep-session-displays!
  "Keeps the displays in one of the conversation's session files, and
  records them in `conv`. The id comes from agentloop's own traffic, and
  only an id agentloop could have made names a file."
  [sys {:keys [role session-dir wake-id]} conv session]
  (when (and (string? session) (re-matches session-id-pattern session))
    (let [file (fs/path session-dir (str session ".jsonl"))]
      (when (fs/exists? file)
        (swap! conv workspace/shown session (store-displays! sys (:id role) wake-id (slurp (str file))))))))

(defn- extension-handler
  "Answers the workspace pane's extension methods (relay.workspace) for
  one conversation."
  [sys conv approvals]
  (fn [line]
    (when-let [{:keys [id method params]} (workspace/extension-request line)]
      (if (nil? id)
        :none
        (try
          (case method
            "_relay/items"
            (workspace/result-line id (workspace/items (inbound/current-state sys) @conv
                                                       (get params "sessionId") (boolean approvals)))
            "_relay/resolve"
            (let [{:keys [result error]} (workspace/resolve-answer refs/resolve (inbound/current-state sys) params)]
              (if error (workspace/error-line id -32602 error) (workspace/result-line id result)))
            "_relay/trust"
            (let [{:keys [command error]} (workspace/trust-command @conv (boolean approvals) params)]
              (if error
                (workspace/error-line id -32000 error)
                (do (inbound/submit! sys command)
                    (workspace/result-line id {"trusted" true
                                               "labels" (vec (:labels (core/trusted-version (inbound/current-state sys)
                                                                                            "doc" (get params "path"))))}))))
            ;; The board's answers are written holding the conversation,
            ;; as the watcher's notifications are, so the client never
            ;; gets an older card after a newer one.
            "_relay/cards"
            (fn [send!]
              (locking conv
                (let [state (inbound/current-state sys)]
                  (swap! conv workspace/subscribed state)
                  (send! (workspace/result-line id (workspace/cards-answer state params))))))
            "_relay/cards/op"
            (let [{:keys [command error]} (workspace/card-op-command params)]
              (if error
                (workspace/error-line id -32602 error)
                (fn [send!]
                  (locking conv
                    (send! (try
                             (let [events (inbound/submit! sys command)
                                   state (inbound/current-state sys)
                                   card-id (get params "card")]
                               ;; The client gets this card in the answer, so
                               ;; the watcher need not send it again.
                               (when (:cards @conv)
                                 (swap! conv assoc-in [:cards card-id] (:rev (core/card state card-id))))
                               (workspace/result-line id (workspace/card-op-answer state card-id (seq events))))
                             (catch Exception e
                               (workspace/error-line id -32000 (or (ex-message e) (str (class e)))))))))))
            (workspace/error-line id -32601 (str "relay has no method " method ".")))
          (catch Exception e
            (workspace/error-line id -32000 (or (ex-message e) (str (class e))))))))))

(def board-poll-ms
  "How often relay acp looks at the log for card changes to send a
  subscribed client."
  1000)

(defn- log-stamp
  "The last segment of the log, its size and when it was modified: what
  changes whenever an event is appended."
  [data-dir]
  (when-let [f (last (log/segments data-dir))]
    [(str f) (fs/size f) (str (fs/last-modified-time f))]))

(defn- board-watcher
  "proxy!'s :watch hook for a conversation: about once a second, while
  the client is subscribed to the board, looks at the log, and when it
  has changed (another process may have written), sends
  _relay/cards/changed with every card whose rev the client has not been
  sent. Never throws: a look that fails is tried again at the next.

  The stamp is taken holding the conversation, before the state is read,
  and kept only while subscribed. Taken outside, a write landing between
  _relay/cards reading the state and subscribing could be stamped as
  seen, and its card would wait for some later write."
  [sys conv]
  (fn [send! done]
    (loop [stamp nil]
      (when (= ::waiting (deref done board-poll-ms ::waiting))
        (recur
         (try
           (when (:cards @conv)
             (locking conv
               (let [now (log-stamp (:data-dir sys))]
                 (when (not= now stamp)
                   (let [state (inbound/current-state sys)]
                     (when-let [params (workspace/cards-changed state @conv)]
                       (send! (workspace/notification-line "_relay/cards/changed" params)))
                     (swap! conv workspace/subscribed state)))
                 now)))
           (catch Exception e
             (binding [*out* *err*] (println "relay could not look at the board:" (ex-message e)))
             stamp)))))))

(defn acp!
  "Opens a conversation with a role for an ACP client on stdin and stdout,
  running the role's ACP agent behind a pass-through proxy (proxy!). relay
  itself never writes to stdout: when the conversation cannot start (the
  role is busy, say), it says why on stderr and returns 1 before any
  traffic. SIGINT and SIGHUP are ignored, as for chat; SIGTERM closes the
  agent's stdin and kills it if it has not gone after a few seconds.
  Returns the agent's exit code.

  Options: :trace, a file to append both directions of the traffic to; :in
  and :out, the client's streams (stdin and stdout by default)."
  [sys role-id {:keys [trace in out approvals] :or {in System/in out System/out}}]
  (binding [*out* *err*]
    (let [stop (promise)]
      (try
        ;; Before any conversation opens, so a refused file costs nothing.
        (when trace (private-trace-file! trace))
        (let [{:keys [exit]}
              (with-signals (assoc terminal-signals "TERM" #(deliver stop :terminated))
                #(with-conversation! sys role-id
                   (fn [request]
                     (let [{:keys [problem] :as command} (harness/acp-command request)
                           conv (atom workspace/conversation)
                           record (permission-recorder sys (:wake-id request))]
                       (when problem (throw (ex-info problem {})))
                       (let [{:keys [exit observed]}
                             (proxy! command {:in in :out out :trace trace :stop stop :approvals approvals
                                              :on-permission (fn [kind req & [answer]]
                                                               (if (= :asked kind)
                                                                 (swap! conv workspace/asked req ((:clock sys)))
                                                                 (swap! conv workspace/answered req (:outcome answer)))
                                                               (apply record kind req (when answer [answer])))
                                              :preview (permission-previewer sys)
                                              :on-oversized (oversize-recorder sys (:wake-id request))
                                              :extension (extension-handler sys conv approvals)
                                              :watch (board-watcher sys conv)
                                              :on-display (fn [session] (keep-session-displays! sys request conv session))})]
                         ;; Whatever a display line missed is kept now,
                         ;; while the conversation still holds the role.
                         (doseq [session (:sessions observed)]
                           (try (keep-session-displays! sys request conv session)
                                (catch Exception e
                                  (binding [*out* *err*] (println "relay could not keep a display:" (ex-message e))))))
                         (acp/report observed exit))))))]
          (if (integer? exit) exit 1))
        (catch Exception e
          (println (or (ex-message e) (str (class e))))
          1)))))

;; --- a conversation on this terminal

(defn chat-argv
  "The command `relay chat` runs: the chat client, given relay's own `acp`
  command for the role as its agent, with the data directory when one was
  named. The client is the operator's terminal, so it may answer
  permission requests: --approvals."
  [client relay role data]
  (vec (concat client ["--" relay "acp" role "--approvals"] (when data ["--data" data]))))

(defn chat!
  "Talks with a role on this terminal by running the chat client (the
  :chat-client setting, acp-tui by default) with `relay acp <role>` as its
  agent. relay acp opens and ends the conversation; this only waits for the
  client. Returns the client's exit code, or 1 when there is no client."
  [{:keys [config]} role-id {:keys [data]}]
  (let [[program & args] (:chat-client config)
        found (some-> (fs/which program) str)]
    (if-not found
      (binding [*out* *err*]
        (println (str "relay chat needs the chat client " program ", and it is not installed. "
                      "Install acp-tui, or name another ACP client with :chat-client in relay.edn."))
        1)
      (with-signals terminal-signals
        #(:exit (apply p/shell {:continue true}
                       (chat-argv (cons found args) (relay-bin) role-id data)))))))

;; --- the morning brief (relay.brief)

(defn send-brief!
  "Composes the morning brief when one is due at `t` and records it, which
  pushes it when it has something to say. Returns the events appended, or
  nil when no brief is due."
  [{:keys [config] :as sys} state ^Instant t]
  (when-let [date (brief/due config state t)]
    (let [section (fn [k]
                    (let [src (brief/source config k)]
                      (brief/tool-section k src (when (:tool src)
                                                  (tools/run-external! (:tool src) (:spec src)
                                                                       ((brief/tool-args k) date)
                                                                       brief/default-timeout-seconds)))))
          composed (brief/compose state date (section :calendar) (section :reminders))]
      (inbound/submit! sys {:type :brief/send :actor core/dispatcher
                            :args (merge {:date (str date) :labels (brief/labels composed)
                                          :sections (brief/summary composed)}
                                         (when-not (brief/empty-brief? composed)
                                           (render/brief composed)))}))))

;; --- the tick

(def ^:private stale-after (Duration/ofMinutes 2))

(defn spawn-wake!
  "Starts a dispatched wake in its own process and does not wait for it."
  [{:keys [data-dir]} role-id]
  (p/process [(relay-bin) "wake" role-id "--dispatched" "--data" (str (fs/absolutize data-dir))]
             {:in "" :out :discard :err :discard}))

(defn spawn-verify!
  "Starts checking a report in its own process and does not wait for it."
  [{:keys [data-dir]} report-id]
  (p/process [(relay-bin) "verify" (str report-id) "--dispatched" "--data" (str (fs/absolutize data-dir))]
             {:in "" :out :discard :err :discard}))

(defn tick!
  "One pass: end wakes and report checks whose process has gone, fire due
  routines, report what needs reporting, push notifications left
  stranded, then start a wake for every role with work waiting and a
  check of every report waiting for one, and send the morning brief when
  it is due. Returns the roles woken."
  ([sys] (tick! sys {}))
  ([{:keys [config] :as sys} {:keys [spawn! spawn-verify!] :or {spawn! spawn-wake! spawn-verify! spawn-verify!}}]
   (let [state (inbound/current-state sys)]
     (inbound/submit! sys {:type :tick :actor core/dispatcher
                           :args {:lost-wakes (lost-wakes state)
                                  :lost-verifications (lost-verifications state)}}))
   (let [state (inbound/current-state sys)
         t (now sys state)]
     (doseq [n (core/notifications-to-push state t (.minus ^Instant t stale-after))]
       (inbound/deliver! sys {:seq (:id n) :data n}))
     (doseq [r (core/reports-to-verify state config t)] (spawn-verify! sys r))
     (let [roles (core/roles-to-wake state config t)]
       (doseq [r roles] (spawn! sys r))
       ;; Last, since it waits on cal and rem. A failure leaves the day's
       ;; brief unrecorded, so the next tick tries again; a rejection is
       ;; another tick that recorded it first.
       (try (send-brief! sys (inbound/current-state sys) t)
            (catch Exception e
              (when-not (core/rejection e)
                (binding [*out* *err*] (println "relay tick: the morning brief failed:" (ex-message e))))))
       roles))))
