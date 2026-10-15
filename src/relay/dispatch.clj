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
            [relay.config :as config]
            [relay.core :as core]
            [relay.harness :as harness]
            [relay.harness.agentloop]
            [relay.inbound :as inbound]
            [relay.prompt :as prompt]
            [relay.tools :as tools])
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
        {shown-notes :shown withheld-notes :withheld} (core/prompt-notes state role)
        holds (map #(core/hold state %) resolves)
        manifest (tools/manifest config role {:script? (tools/script-role? state config role)})]
    {:harness (get-in config [:harnesses (keyword harness)])
     :harness-name harness
     :role role-row
     :model (or (:model role-row) (get-in config [:harnesses (keyword harness) :model]))
     :system-prompt (prompt/instructions {:shared shared
                                          :operator-additions operator-additions
                                          :notes shown-notes
                                          :withheld-notes withheld-notes
                                          :granted (tools/grants-summary config role)}
                                         role-row)
     :prompt (prompt/wake {:role role-row
                           :memos (map #(core/shown-memo state (core/memo state %)) memos)
                           :note note
                           :now (not-before (:now inputs) (:last-at state))
                           :zone (config/zone config)
                           :resumed? (some? resumes)
                           :decided holds})
     :tools manifest
     ;; The session starts with the labels of everything in its prompts,
     ;; and each tool is policed by its class and static labels.
     :policy (tools/policy manifest (core/wake-labels state wake))
     :resolve (vec (for [{:keys [call status reason input-sha256]} holds]
                     (cond-> {:call_id call :verdict (if (= :allowed status) "allow" "deny")}
                       (not (str/blank? reason)) (assoc :reason reason)
                       input-sha256 (assoc :input_sha256 input-sha256))))
     :tool-command [relay-bin "tool" "--data" data-path "--role" role]
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
  (-> (select-keys report [:outcome :exit :crashed :cut-short :session :said :resets-at :held :labels])
      (update :exit exit-field)
      (update :resets-at #(some-> % str))
      (assoc :wake wake-id
             :transcript (some-> (:transcript report) str))))

(defn wake!
  "Starts a wake of a role, runs it and ends it. Returns {:result ...}:
  :idle when there was nothing to do, :busy, :paused or :runaway when it was
  not started, and otherwise the wake's id and outcome."
  [sys role-id {:keys [note dispatched]}]
  (let [started (try
                  (inbound/submit! sys {:type :wake/start
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
      (empty? started) {:result :idle}
      :else
      (let [wake-id (:seq (first started))
            state (inbound/current-state sys)
            report (run-harness! sys (wake-request sys state (get-in state [:wakes wake-id])
                                                   (wake-inputs sys)))]
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
  only conversations."
  [{:keys [data-dir] :as sys} state wake inputs]
  (-> (wake-request sys state wake inputs)
      (update :system-prompt str "\n\n" conversation-note "\n")
      (dissoc :prompt)
      (assoc :session-dir (str (fs/path data-dir "runs" (:role wake) "conversations")))))

(defn conversation-end-args
  "The :wake/end args for a conversation, from what the code that ran it
  reported: {:exit :sessions :said :crashed}, any of which may be missing.
  Total: whatever the report holds, core accepts the result, so a
  conversation that started always ends."
  [wake-id {:keys [exit sessions said crashed]}]
  (let [sessions (when (sequential? sessions)
                   (filterv #(and (string? %) (not (str/blank? %))) sessions))]
    (cond-> {:wake wake-id :outcome "ran" :exit (exit-field exit) :crashed (true? crashed)
             :cut-short false
             :said (if (and (string? said) (not (str/blank? said))) said "Conversation ended.")}
      (seq sessions) (assoc :sessions sessions))))

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
        wake-id (:seq (first started))
        report (volatile! nil)]
    (try
      (let [state (inbound/current-state sys)
            request (conversation-request sys state (get-in state [:wakes wake-id])
                                          (wake-inputs sys))]
        (when-not (:harness request)
          (throw (ex-info (str "relay.edn has no harness called " (:harness-name request) ".") {})))
        (vreset! report (f request)))
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

(defn- pump!
  "Copies from `in` to `out` chunk by chunk, flushing each, and passes each
  complete line to (tap line). Once a write fails, calls (on-write-failed)
  and keeps reading, so the writer on the far side of `in` is never
  blocked, but writes nothing more. Returns at the end of `in`, after
  tapping any last unfinished line."
  [^java.io.InputStream in out tap on-write-failed]
  (let [buf (byte-array 65536)]
    (loop [rest acp/no-rest writing? true]
      (let [n (try (.read in buf) (catch java.io.IOException _ -1))]
        (if (neg? n)
          (let [last-bytes (acp/left-over rest)]
            (when (pos? (alength last-bytes)) (tap (acp/text last-bytes))))
          (let [writing? (and writing?
                              (try (write-chunk! out buf n) true
                                   (catch java.io.IOException _ (on-write-failed) false)))
                [rest lines] (acp/split rest (java.util.Arrays/copyOf buf n))]
            (doseq [line lines] (tap (acp/text line)))
            (recur rest writing?)))))))

(defn- tracer
  "A function that writes one line of traffic to the trace file as JSON,
  with its direction, or nil without a file. Both directions write to it,
  so the writes are serialised."
  [file]
  (when file
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
  pid, with its descendants. Returns {:exit :observed}."
  [{:keys [argv dir env]} {:keys [in out trace stop grace-ms] :or {grace-ms stop-grace-ms}}]
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
        to-agent (spawn (fn []
                          (pump! in agent-in (tap :client) #(deliver stop :agent-gone))
                          (deliver stop :client-gone)))
        to-client (spawn (fn [] (pump! (:out proc) out (tap :agent) #(deliver stop :client-gone))))
        exit (.waitFor jp)]
    ;; The agent's stdout ends with it, unless something it started holds
    ;; the pipe open; that cannot keep relay waiting for long.
    (deref to-client 5000 nil)
    (deliver stop ::done)
    (deref stopper 1000 nil)
    {:exit exit :observed @observed}))

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
  [sys role-id {:keys [trace in out] :or {in System/in out System/out}}]
  (binding [*out* *err*]
    (let [stop (promise)]
      (try
        (let [{:keys [exit]}
              (with-signals (assoc terminal-signals "TERM" #(deliver stop :terminated))
                #(with-conversation! sys role-id
                   (fn [request]
                     (let [{:keys [problem] :as command} (harness/acp-command request)]
                       (when problem (throw (ex-info problem {})))
                       (let [{:keys [exit observed]}
                             (proxy! command {:in in :out out :trace trace :stop stop})]
                         (acp/report observed exit))))))]
          (if (integer? exit) exit 1))
        (catch Exception e
          (println (or (ex-message e) (str (class e))))
          1)))))

;; --- a conversation on this terminal

(defn chat-argv
  "The command `relay chat` runs: the chat client, given relay's own `acp`
  command for the role as its agent, with the data directory when one was
  named."
  [client relay role data]
  (vec (concat client ["--" relay "acp" role] (when data ["--data" data]))))

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

;; --- the tick

(def ^:private stale-after (Duration/ofMinutes 2))

(defn- stranded-notifications
  "Notifications recorded but never pushed, because whoever recorded them
  stopped first. Fresh ones are left to their own process."
  [state ^Instant now]
  (filter #(and (= :pending (:delivery %))
                (.isBefore (.plus ^Instant (:created-at %) stale-after) now))
          (vals (:notifications state))))

(defn spawn-wake!
  "Starts a dispatched wake in its own process and does not wait for it."
  [{:keys [data-dir]} role-id]
  (p/process [(relay-bin) "wake" role-id "--dispatched" "--data" (str (fs/absolutize data-dir))]
             {:in "" :out :discard :err :discard}))

(defn tick!
  "One pass: end wakes whose process has gone, fire due routines, report what
  needs reporting, push notifications left stranded, then start a wake for
  every role with work waiting. Returns the roles woken."
  ([sys] (tick! sys {}))
  ([{:keys [config] :as sys} {:keys [spawn!] :or {spawn! spawn-wake!}}]
   (let [state (inbound/current-state sys)]
     (inbound/submit! sys {:type :tick :actor core/dispatcher
                           :args {:lost-wakes (lost-wakes state)}}))
   (let [state (inbound/current-state sys)
         t (now sys state)]
     (doseq [n (sort-by :id (stranded-notifications state t))]
       (inbound/deliver! sys {:seq (:id n) :data n}))
     (let [roles (core/roles-to-wake state config t)]
       (doseq [r roles] (spawn! sys r))
       roles))))
