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
            [relay.digest :as digest]
            [relay.config :as config]
            [relay.core :as core]
            [relay.harness :as harness]
            [relay.harness.agentloop :as agentloop]
            [relay.inbound :as inbound]
            [relay.ingest-run :as ingest-run]
            [relay.json :as strict]
            [relay.log :as log]
            [relay.prompt :as prompt]
            [relay.ready :as ready]
            [relay.refs :as refs]
            [relay.render :as render]
            [relay.shell :as shell]
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
  [{:keys [data-dir config]} state {:keys [id role memos note resumes harness] :as wake}
   {:keys [shared operator-additions relay-bin data-path] :as inputs}]
  (let [role-row (core/role state role)
        run-dir (fs/path data-dir "runs" role (str id))
        resumed (when resumes (get-in state [:wakes resumes]))
        script {:script? (tools/script-role? state config role)}
        ;; Each MCP tool is held to the definition relay.edn's :digest
        ;; gives it, else the one relay accepted last, else the one pinned
        ;; when the operator connected its server, so a changed one stops
        ;; any session, not only its own, and the next starts with it.
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
                                          :notes (core/prompt-notes state config role)
                                          :skills (core/prompt-skills state config role)
                                          :granted (tools/grants-summary config role script)}
                                         role-row)
     :prompt (prompt/wake {:role role-row
                           :memos (map #(core/memo state %) memos)
                           :note note
                           :now (not-before (:now inputs) (:last-at state))
                           :zone (config/zone config)
                           :resumed? (some? resumes)
                           ;; What the role would otherwise spend its first
                           ;; calls on.
                           :journal (core/prompt-journal state config role)
                           :fact-topics (core/prompt-fact-topics state config role)
                           :cards (core/prompt-cards state config role)})
     :tools manifest
     ;; The session starts with the labels of everything in its prompts,
     ;; and each call adds its tool's static labels. A public role's
     ;; session may never hold private (I3).
     :policy (tools/policy manifest labels {:public? public?})
     ;; The MCP servers the role's granted tools come from, and no others.
     :mcp-servers (config/mcp-servers-for config (map :name manifest))
     ;; Where the role's displays are kept, by the day the wake started.
     :display-ref-prefix (core/shown-ref-prefix (not-before (:now inputs) (:last-at state)) (config/zone config))
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
  (-> (select-keys report [:outcome :exit :crashed :cut-short :session :said :resets-at :labels
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

(defn- held-labels
  "The labels a prepared item is recorded with: all that the session held,
  with any label relay does not keep read as contaminated (the safe way)."
  [& label-seqs]
  (vec (sort (distinct (for [l (apply concat label-seqs) :when (string? l)]
                         (if (= core/private-label l) l "contaminated"))))))

(defn store-prepared!
  "Records what the role's edit tools prepared for the operator, from a
  session's entries (`text`): one :prepared/record for each allowed,
  successful call to a tool relay.edn gives class edit, written as the
  role for its wake, with the labels the session held at the call. A
  call already recorded changes nothing. `known` is labels relay already
  knows the session held (the wake's, and what the harness reported at the
  end): a transcript of a resumed session may lack the earliest entries, so
  they are added to every call's. One relay cannot record is reported on
  stderr and passed over: the wake goes on."
  [sys role-id wake-id text known]
  (let [edit? (set (for [[n t] (:tools (:config sys)) :when (= :edit (:class t))] (name n)))]
    (when (seq edit?)
      (doseq [c (agentloop/parse-calls text)
              :when (edit? (:tool c))]
        (try (inbound/submit! sys {:type :prepared/record :actor role-id :session-wake wake-id
                                   :labels (held-labels known (:labels c))
                                   :args (ready/prepared-args c)})
             (catch Exception e
               (binding [*out* *err*]
                 (println (str "relay could not record what " (:tool c) " prepared: " (ex-message e))))))))))

(defn- transcript-text [{:keys [transcript]}]
  (when (and transcript (fs/exists? transcript))
    (slurp (str transcript))))

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
            ;; Kept while the wake still runs, so they are written as the
            ;; role's session, and the end of the wake can mention them.
            shown (mapv :path (store-displays! sys role-id wake-id (transcript-text report)))
            _ (store-prepared! sys role-id wake-id (transcript-text report)
                               (concat (:labels report)
                                       (core/wake-labels state (:config sys) (get-in state [:wakes wake-id]))))
            report (cond-> report (seq shown) (assoc :shown shown))]
        (inbound/submit! sys {:type :wake/end :actor core/dispatcher
                              :args (wake-end-args wake-id report)})
        (let [state (inbound/current-state sys)]
          {:result :ran
           :wake wake-id
           :outcome (:outcome report)
           :left-open (count (filter #(= :unread (:status (core/memo state %)))
                                     (get-in state [:wakes wake-id :memos])))})))))

(defn reap-lost-wakes!
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
              (prompt/board (core/prompt-cards state config (:role wake))))
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
  request. The conversation holds the role only while a turn of it runs
  (relay.core/busy?), and carries no memos. f returns a report for
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

(defn with-signals
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

(def terminal-signals
  "SIGINT and SIGHUP, ignored. The terminal sends them to relay as well as
  to the program relay runs on it, and relay has to outlive that program:
  `relay acp` to record that the conversation ended, the dashboard to wait
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
  on unread. Far longer than any ACP message relay looks at."
  (* 8 1024 1024))

(defn- iso [^bytes bs n] (String. bs 0 (int n) java.nio.charset.StandardCharsets/ISO_8859_1))

(defn- note-oversized
  "Folds a piece of an oversized line into what is kept of it: its first
  4 KB and its length."
  [{:keys [prefix size] :as o} ^bytes piece n]
  (let [text (iso piece n)]
    (assoc o
           :prefix (if (< (count prefix) 4096) (subs (str prefix text) 0 (min 4096 (+ (count prefix) (count text)))) prefix)
           :size (+ (or size 0) n))))

(defn pump!
  "Copies from `in` to `out`, flushing each write, and passes each complete
  line to (tap line), read once (relay.acp/line) for the tap and the
  screen alike. Once a write fails, calls (on-write-failed) and keeps
  reading, so the writer on the far side of `in` is never blocked, but
  writes nothing more. Returns at the end of `in`, after tapping any last
  unfinished line.

  Without `screen`, bytes are copied chunk by chunk as they come. With it,
  they are copied line by line, and (screen line) decides each: :pass
  copies it, nil drops it, and a string is written in its place. A line
  longer than `limit` cannot be screened whole. With `on-oversized` it is
  dropped as it streams and (on-oversized {:prefix :size}) is called once
  it ends; without, it is copied unscreened as it comes.
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
        long-done! (fn [over] (when on-oversized (on-oversized over)))]
    (loop [pending acp/no-rest over nil]
      (let [n (try (.read in buf) (catch java.io.IOException _ -1))]
        (if (neg? n)
          (let [last-bytes (acp/left-over pending)]
            (cond
              over (long-done! over)
              (pos? (alength last-bytes))
              ;; A last line with no newline is screened like any other:
              ;; a client may still read it, so it must not get past.
              (let [l (acp/line last-bytes)
                    decision (when screen (screen l))]
                (tap l)
                (cond
                  (= :pass decision) (write! last-bytes (alength last-bytes))
                  (string? decision) (let [bs (.getBytes (str decision) java.nio.charset.StandardCharsets/UTF_8)]
                                       (write! bs (alength bs)))
                  :else nil))))
          (let [chunk (java.util.Arrays/copyOf buf n)
                [rest' lines] (acp/split pending chunk)]
            (if-not screen
              (do (write! chunk n)
                  (doseq [line lines] (tap (acp/line line)))
                  (recur rest' nil))
              (let [;; An oversized line ends at the first newline of this chunk.
                    nl (when over (let [i (.indexOf (iso chunk n) 10)] (when (>= i 0) i)))
                    over (if over (long-piece! over chunk (if nl (inc nl) n)) over)
                    _ (when (and over nl) (long-done! over))
                    [lines over] (cond (nil? over) [lines nil]
                                       nl [(rest lines) nil]
                                       :else [[] over])]
                (doseq [^bytes line lines
                        ;; Read once, for the screen and the tap alike.
                        :let [l (acp/line line)
                              decision (screen l)]]
                  (tap l)
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

(defn private-trace-file!
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

(defn tracer
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
  client: `in` to the agent's stdin and its stdout to `out`, one line at
  a time, flushing each. The agent's stderr is relay's.

  Every complete line in either direction goes to relay.acp/observe, and
  to the trace file when there is one. The agent's stdin is closed when
  the client's input ends, when writing to the client fails, or when `stop`
  is delivered; an agent still running after `grace-ms` is killed by its
  pid, with its descendants. Returns {:exit :observed}.

  relay never asks a client to approve anything: an agent's
  session/request_permission (agentloop sends none) is answered cancelled
  and never passed on. An agent line longer than `line-limit` is never
  passed on, since it cannot be read whole, and (on-oversized {:size}) is
  called. A client line that long is dropped too, since it might be one of
  relay's methods, and answered with an error when relay can find its id.

  More hooks serve the workspace pane. (extension line) sees each of the
  client's lines first, as relay.acp/line read it: a string it returns
  is written to the client as the answer and the line goes no further; a
  function it returns is called with the writer to the client, to write
  the answer itself; :none drops the line; nil passes it on.
  (on-display session) is called for an agent line that carries a
  display, before the line is passed on.

  Only relay speaks relay's methods to the client: an agent line whose
  method starts with _relay/ is dropped, so an agent cannot fake a card
  update or a pane item. Nor can it answer for relay: a response from the
  agent passes only when it answers a request of the client's that relay
  passed on and the agent has not answered yet (relay.workspace/settle)."
  [{:keys [argv dir env]} {:keys [in out trace stop grace-ms on-oversized line-limit extension on-display]
                           :or {grace-ms stop-grace-ms on-oversized (fn [_]) line-limit line-limit
                                extension (fn [_]) on-display (fn [_])}}]
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
                           ;; Closed on a thread of its own: a write the
                           ;; agent is not reading holds the stream, and
                           ;; must not hold up the kill.
                           (spawn #(try (.close agent-in) (catch java.io.IOException _ nil)))
                           (when-not (.waitFor jp grace-ms java.util.concurrent.TimeUnit/MILLISECONDS)
                             (let [h (java.lang.ProcessHandle/of (.pid jp))
                                   family (concat (some-> (.orElse h nil) .descendants .iterator iterator-seq)
                                                  [(.orElse h nil)])]
                               (doseq [^java.lang.ProcessHandle x (remove nil? family)]
                                 (.destroyForcibly x)))))))
        to-agent! (fn [^String text]
                    (let [bs (.getBytes (str text "\n") java.nio.charset.StandardCharsets/UTF_8)]
                      (try (locking agent-in (write-chunk! agent-in bs (alength bs)))
                           (catch java.io.IOException _ nil))))
        ;; The answers the agent owes the client: one for each request
        ;; of the client's that relay passed it (relay.workspace/owe).
        owed (atom {})
        owed? (fn [line]
                (if-let [{k :key} (workspace/agent-response line)]
                  (let [[before] (swap-vals! owed #(first (workspace/settle % k)))]
                    (second (workspace/settle before k)))
                  true))
        from-agent (fn [line]
                     (let [msg (acp/message line)]
                       (cond
                         ;; relay's clients are never asked to approve a
                         ;; call: the agent is told it was cancelled.
                         (and (= :request (acp/kind msg)) (= "session/request_permission" (get msg "method")))
                         (do (binding [*out* *err*] (println "relay answered a permission request from the agent as cancelled."))
                             (to-agent! (strict/generate {"jsonrpc" "2.0" "id" (get msg "id")
                                                          "result" {"outcome" {"outcome" "cancelled"}}}))
                             nil)
                         ;; A response to a request the client never sent the
                         ;; agent, or one already answered: it could pass for
                         ;; relay's own answer to the client.
                         (and (not (workspace/drop-agent-line? line)) (not (owed? line)))
                         (do (binding [*out* *err*] (println "relay dropped a response from the agent to no request it was waiting on."))
                             nil)
                         ;; relay's own methods are relay's to send, and a
                         ;; line the strict reader refuses is passed on to
                         ;; no one. A blank line carries nothing to tell.
                         (workspace/drop-agent-line? line)
                         (do (when-not (str/blank? (:text line))
                               (binding [*out* *err*] (println "relay dropped an agent line that was not strict JSON or used one of relay's methods.")))
                             nil)
                         :else
                         ;; A display is kept before the client sees it, so
                         ;; the pane can ask about it at once.
                         (do (when-let [session (acp/display-session line)]
                               (try (on-display session)
                                    (catch Exception e
                                      (binding [*out* *err*] (println "relay could not keep a display:" (ex-message e))))))
                             ;; A client that knows nothing of displays sees
                             ;; their text inert, whatever the agent sent.
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
                        :pass))
        ;; A client line too long to read whole could be one of relay's
        ;; methods, which must not reach the agent, so it goes nowhere. A
        ;; request whose id relay can find is told why.
        client-oversized (fn [{:keys [prefix]}]
                           (binding [*out* *err*]
                             (println "relay dropped a message from the client too long to read (over 8 MB)."))
                           (when-let [id (acp/line-id prefix)]
                             (to-client! (workspace/error-line id -32600 "relay does not pass on a message over 8 MB."))))
        to-agent (spawn (fn []
                          ;; Line by line, so nothing relay writes to the
                          ;; agent lands inside one of the client's messages.
                          (pump! in agent-in (tap :client) #(deliver stop :agent-gone)
                                 {:screen (fn [line]
                                            (let [k (workspace/client-request-key line)]
                                              ;; A request for relay cancels any debt
                                              ;; under its id before relay works on
                                              ;; its answer, which may take a while.
                                              (when (and k (acp/relay-method? (acp/message line)))
                                                (swap! owed workspace/forget k))
                                              (let [decision (from-client line)]
                                                ;; Owed before the agent can read it.
                                                (when (and k (= :pass decision))
                                                  (swap! owed workspace/owe k))
                                                decision)))
                                  :limit line-limit :on-oversized client-oversized})
                          (deliver stop :client-gone)))
        ;; An agent line too long to read whole never reaches the client,
        ;; and the operator is told.
        oversized (fn [{:keys [size]}] (on-oversized {:size size}))
        to-client (spawn (fn [] (pump! (:out proc) out (tap :agent) #(deliver stop :client-gone)
                                       {:screen from-agent :limit line-limit :on-oversized oversized})))
        exit (.waitFor jp)]
    ;; The agent's stdout ends with it, unless something it started holds
    ;; the pipe open; that cannot keep relay waiting for long.
    (deref to-client 5000 nil)
    (deliver stop ::done)
    (deref stopper 1000 nil)
    {:exit exit :observed @observed}))

(defn- oversize-recorder
  "Records an agent line relay dropped for being too long to read, and
  tells the operator."
  [sys wake-id]
  (fn [{:keys [size]}]
    (try
      (inbound/submit! sys {:type :conversation/line-dropped :actor core/dispatcher
                            :args {:wake wake-id :size size}})
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
        (let [text (slurp (str file))]
          (store-prepared! sys (:id role) wake-id text
                           (let [state (inbound/current-state sys)]
                             (core/wake-labels state (:config sys) (get-in state [:wakes wake-id]))))
          (swap! conv workspace/shown session (store-displays! sys (:id role) wake-id text)))))))

(defn- extension-handler
  "Answers the workspace pane's extension methods (relay.workspace) for
  one conversation."
  [sys conv]
  (fn [line]
    (when-let [{:keys [id method params refused]} (workspace/extension-request line)]
      (cond
        ;; Never passed on to the agent, which could read it as one of
        ;; relay's methods; a request is told why.
        refused (if (some? id) (workspace/error-line id -32600 refused) :none)
        (nil? id) :none
        :else
        (try
          (case method
            "_relay/items"
            (workspace/result-line id (workspace/items (inbound/current-state sys) @conv (get params "sessionId")))
            "_relay/resolve"
            (let [{:keys [result error]} (workspace/resolve-answer refs/resolve (inbound/current-state sys) params)]
              (if error (workspace/error-line id -32602 error) (workspace/result-line id result)))
            (workspace/error-line id -32601 (str "relay has no method " method ".")))
          (catch Exception e
            (workspace/error-line id -32000 (or (ex-message e) (str (class e))))))))))

(defn log-stamp
  "The last segment of the log, its size and when it was modified: what
  changes whenever an event is appended."
  [data-dir]
  (when-let [f (last (log/segments data-dir))]
    [(str f) (fs/size f) (str (fs/last-modified-time f))]))

(defn run-conversation!
  "Runs a conversation with a role: opens its chat wake, runs the role's
  ACP agent behind relay's proxy (proxy!) with `in` and `out` as the
  proxy's client side, and ends the wake when the agent stops. Every
  screen stays the proxy's: displays, the pane's methods, oversized lines
  and answers the agent does not owe. The board is relay.hub's.

  (on-started wake-id) is called once the wake has started and before
  the agent runs. Throws, having started nothing, when the conversation
  cannot open (the role has one open already, say). Returns {:exit}."
  [sys role-id {:keys [in out stop trace on-started] :or {on-started (fn [_])}}]
  (with-conversation! sys role-id
    (fn [request]
      (let [{:keys [problem] :as command} (harness/acp-command request)
            conv (atom workspace/conversation)]
        (when problem (throw (ex-info problem {})))
        (on-started (:wake-id request))
        (let [{:keys [exit observed]}
              (proxy! command {:in in :out out :trace trace :stop stop
                               :on-oversized (oversize-recorder sys (:wake-id request))
                               :extension (extension-handler sys conv)
                               :on-display (fn [session] (keep-session-displays! sys request conv session))})]
          ;; Whatever a display line missed is kept now, while the
          ;; conversation still holds its wake.
          (doseq [session (:sessions observed)]
            (try (keep-session-displays! sys request conv session)
                 (catch Exception e
                   (binding [*out* *err*] (println "relay could not keep a display:" (ex-message e))))))
          (acp/report observed exit))))))

;; --- a conversation on this terminal

(defn client-argv
  "The command bare `relay` runs: the client, told which role to open
  first when one was named, given relay's own `acp` command for every
  role as its agent, with the data directory when one was named."
  [client relay role data]
  (vec (concat client (when role ["--open" role]) ["--" relay "acp"] (when data ["--data" data]))))

(defn dashboard!
  "Opens the dashboard on this terminal by running the client (the
  :client setting, relay-tui by default) with `relay acp` as its
  agent, opening `role-id` first when given. relay acp opens and ends the
  conversations, or relay serve keeps them; this only waits for the
  client. Returns the client's exit code, or 1 when there is no client."
  [{:keys [config]} role-id {:keys [data]}]
  (let [[program & args] (:client config)
        found (some-> (fs/which program) str)]
    (if-not found
      (binding [*out* *err*]
        (println (str "relay needs the client " program ", and it is not installed. "
                      "Install relay-tui, or name another ACP client with :client in relay.edn."))
        1)
      (with-signals terminal-signals
        #(:exit (apply p/shell {:continue true}
                       (client-argv (cons found args) (relay-bin) role-id data)))))))

;; --- the daily note (relay.digest)

(defn send-daily-note!
  "Makes the daily note when one is due at `t` and records it, which
  sends it as a digest message when it has something to say. relay
  composes it in code from the log: the flagged changes and whatever the
  note's role wrote for it. Returns the events appended, or nil when no
  note is due."
  [{:keys [config] :as sys} state ^Instant t]
  (when-let [date (digest/due-date (digest/at-time config) (config/zone config) t (core/daily-date state))]
    (inbound/submit! sys {:type :digest/compose :actor core/dispatcher :args {:date (str date)}})))

(defn announce-github!
  "Sends one message for the new GitHub items from people that came in
  since the last, when there are any. Returns the events appended."
  [sys]
  (when (seq (:github-news (inbound/current-state sys)))
    (inbound/submit! sys {:type :github/announce :actor core/dispatcher :args {}})))

;; --- the tick

(def ^:private stale-after (Duration/ofMinutes 2))

(defn spawn-wake!
  "Starts a dispatched wake in its own process and does not wait for it."
  [{:keys [data-dir]} role-id]
  (p/process [(relay-bin) "wake" role-id "--dispatched" "--data" (str (fs/absolutize data-dir))]
             {:in "" :out :discard :err :discard}))

(defn tick!
  "One pass: end wakes whose process has gone, fire due
  routines, report what needs reporting, deliver messages that are due
  (held for quiet hours or the digest, left stranded, or failed and due
  again), poll the ingest sources that are due, then start a wake for
  every role with work waiting, and make the daily note when it is due.
  Returns the roles woken."
  ([sys] (tick! sys {}))
  ([{:keys [config] :as sys} {:keys [spawn!] :or {spawn! spawn-wake!}}]
   (let [state (inbound/current-state sys)]
     (inbound/submit! sys {:type :tick :actor core/dispatcher
                           :args {:lost-wakes (lost-wakes state)}}))
   (let [state (inbound/current-state sys)
         t (now sys state)]
     (doseq [{:keys [channel messages]} (core/messages-due state t (.minus ^Instant t stale-after))]
       (inbound/deliver! sys channel messages))
     ;; Before any wake, so a role granted the shell finds it running.
     ;; A shell that will not start stops nothing else: the reason goes
     ;; to the tick's log, and `relay house status` says it.
     (when (:house config)
       (try (shell/up! (shell/context (:data-dir sys) config))
            (catch Exception e
              (binding [*out* *err*] (println "relay tick: the shell is not running:" (ex-message e))))))
     ;; What came in from outside becomes memos before anyone is woken,
     ;; so the role that reads them is woken by this tick.
     (when (seq (:ingest config))
       (ingest-run/poll-due! sys t)
       ;; What people opened on GitHub this tick, in one push.
       (try (announce-github! sys)
            (catch Exception e
              (when-not (core/rejection e)
                (binding [*out* *err*] (println "relay tick: could not announce GitHub items:" (ex-message e)))))))
     (let [state (if (seq (:ingest config)) (inbound/current-state sys) state)
           roles (core/roles-to-wake state config t)]
       (doseq [r roles] (spawn! sys r))
       ;; A failure leaves the day's note unrecorded, so the next tick
       ;; tries again; a rejection is another tick that recorded it first.
       (try (send-daily-note! sys (inbound/current-state sys) t)
            (catch Exception e
              (when-not (core/rejection e)
                (binding [*out* *err*] (println "relay tick: the daily note failed:" (ex-message e))))))
       roles))))
