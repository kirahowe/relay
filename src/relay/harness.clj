(ns relay.harness
  "The interface every harness sits behind. relay owns it, so the program
  that runs a role, and where its tool calls execute, can change without
  touching anything else.

  A wake runs in two steps, so that relay's dispatcher is the only code
  that starts a process. `wake-command` takes a request:

    :harness        the harness's settings from relay.edn, with :kind
    :role           the role being run
    :model          the model to use, if the role or harness names one
    :system-prompt  the role's standing instructions
    :prompt         the prompt for this wake
    :tools          the tools the role is offered (relay.tools/manifest)
    :quarantine     the tools the role may read through a quarantined
                    reader (its :quarantine grant), or nil
    :tool-command   the command a harness runs for a tool call, given the
                    tool's name as its last argument: relay's own `tool`
                    command with the data directory and role already named.
                    Tool calls get base-env and nothing else.
    :run-dir        a directory for this wake's transcript and files, which
                    the harness creates
    :session-dir    where the harness keeps sessions for this role
    :resume         a session to carry on from, if the last wake was cut short
    :timeout-ms     how long the run may take

  writes whatever files its program needs into the run directory, and
  describes the program as data:

    :argv         the command line
    :dir          the directory to run it in
    :env          its whole environment
    :in           what it reads on stdin, if anything
    :out-file     where its stdout goes; stderr goes beside it, in
                  <out-file>.err. This is the wake's transcript.
    :timeout-ms   how long it may run before it is stopped, with its
                  descendants
    :report-file  a file the program may write, read back after it exits

  It may add keys of its own for wake-report, or return {:problem text}
  when it cannot run. The dispatcher runs the command and calls
  `wake-report`, which is pure, with the request, the command, and what
  came back: {:exit :out :err :report :now}, where :exit is the exit code
  or \"timeout\", :out and :err are the output's text, :report is the text
  of the report file or nil, and :now is the time. It returns the report:

    :outcome     \"ran\" when the role got to work, whatever came of it.
                 \"limited\", \"unreachable\" or \"needs-attention\" when the
                 harness itself could not run. Those count against no memo.
    :exit        the exit code, or \"timeout\"
    :crashed     true when the run errored or was stopped at the deadline
    :cut-short   true when the harness stopped the run at the wake deadline.
                 The wake is kept, and the next ordinary wake resumes its
                 session. It still counts as having run.
    :session     the session id, so an interrupted wake can be resumed
    :said        the last thing the harness said, for a person to read
    :resets-at   when a rate limit lifts, if the harness said
    :usage       what the run's model calls used, {:input :output
                 :cache-read :cache-write} in tokens, if the harness said
    :cost-usd    what they cost in US dollars, if the harness knows;
                 left out, never 0, when it does not

  The dispatcher adds :transcript, the out-file.

  A clean exit is not a verdict on the memos. Only the role closing them is.

  A conversation is not run by the harness. The harness describes, as
  data, the program that speaks ACP on stdin and stdout for it
  (`acp-command`), and relay's dispatcher starts the process. It returns
  {:argv :dir :env :session-dir}, or {:problem text} when it cannot run.
  It may write files (a request file, say) into the run directory, but it
  starts no process."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [relay.core :as core])
  (:import (java.time Instant)))

(defn- kind [request] (get-in request [:harness :kind]))

(defmulti wake-command
  "Describes the program that runs one wake, writing the files it needs."
  kind)

(defmethod wake-command :default [request]
  (throw (ex-info (str "No harness kind " (pr-str (kind request)) ".") {})))

(defmulti wake-report
  "The report on a wake, from its request, its command and what came back
  from running it. Pure."
  (fn [request _command _ran] (kind request)))

(defmulti acp-command
  "Describes an ACP agent for a conversation request: a program that
  speaks the Agent Client Protocol on stdin and stdout and keeps each ACP
  session it opens in the session-dir."
  kind)

(defmethod acp-command :default [request]
  {:problem (str "The " (pr-str (get-in request [:harness :kind])) " harness has no ACP mode.")})

(defn base-env
  "A minimal environment for a harness: enough to find programs and a home,
  and nothing inherited from whoever started relay, such as an enclosing
  agent session's variables."
  [extra]
  (let [env (System/getenv)
        keep-vars ["PATH" "HOME" "USER" "LOGNAME" "LANG" "TMPDIR" "SHELL"]]
    (merge (into {} (keep (fn [k] (when-let [v (get env k)] [k v])) keep-vars))
           {"PATH" (str/join ":" (distinct (concat ["/opt/homebrew/bin" "/usr/local/bin"]
                                                   (str/split (or (get env "PATH") "/usr/bin:/bin") #":"))))}
           extra)))

(defn tail
  "The last few non-blank lines of a text, for a person to read."
  [s n]
  (->> (str/split-lines (str s)) (remove str/blank?) (take-last n) (str/join "\n")))

;; --- script: any program, for tests and simple automations

;; Any program, for tests and simple automations. It gets the prompt on
;; stdin and one argument: the path of a JSON file in the run directory.
;; The file holds
;;
;;   system_prompt  path of a file with the system prompt
;;   tools_file     path of a file with the tools, as JSON
;;   report         path where the program may write a JSON report
;;                  ({outcome, session, said, resets_at}); without one, a
;;                  run counts as having got to work. A report that is not
;;                  an object, or names an outcome relay does not know,
;;                  counts as a crash.
;;   resume         the session to carry on from, or ""
;;   tool_command   the argv that runs a tool call, given the tool name last;
;;                  it reads an envelope on stdin and writes one on stdout
;;   policy         the session's starting labels and each tool's class
;;   resolve        the operator's decisions on calls the session held
;;
;; The report may also list `held` calls ({id, name, input}) that wait for
;; the operator, as agentloop's outcome does.
(defmethod wake-command :script
  [{:keys [harness system-prompt prompt tools tool-command run-dir resume timeout-ms policy resolve]}]
  (let [file #(str (fs/path run-dir %))
        args-file (file "args.json")
        report-file (file "report.json")]
    (fs/create-dirs run-dir)
    (spit (file "system-prompt.md") system-prompt)
    (spit (file "tools.json") (json/generate-string tools))
    (spit args-file (json/generate-string {:system_prompt (file "system-prompt.md")
                                           :tools_file (file "tools.json")
                                           :report report-file
                                           :resume (str resume)
                                           :tool_command (vec tool-command)
                                           :policy policy
                                           :resolve (vec resolve)}))
    {:argv (conj (vec (:command harness)) args-file)
     :dir run-dir :env (base-env {}) :in prompt
     :out-file (file "transcript.log") :timeout-ms timeout-ms
     :report-file report-file}))

(defn- text [x] (when (and (string? x) (not (str/blank? x))) x))

(defn- instant-text
  "An instant as text, or nil when x is not one."
  [x]
  (when (string? x) (try (str (Instant/parse x)) (catch Exception _ nil))))

(defmethod wake-report :script [_ _ {:keys [exit out err report]}]
  ;; The program is anything at all, so its report is checked here rather
  ;; than trusted: a report core would refuse would leave the wake running.
  (let [parsed (when report (try (json/parse-string report true) (catch Exception _ ::unreadable)))
        outcome (when (map? parsed) (:outcome parsed))
        said (or (when (map? parsed) (text (:said parsed))) (tail (str out err) 10))
        crash (fn [why] {:outcome "ran" :exit exit :crashed true :said why})]
    (cond
      (and (some? parsed) (not (map? parsed)))
      (crash "The program's report.json is not a JSON object.")

      (and (some? outcome) (not (core/harness-outcomes outcome)))
      (crash (str "The program reported an outcome relay does not know: " (pr-str outcome) "."))

      :else
      {:outcome (or outcome "ran")
       :exit exit
       :crashed (not= 0 exit)
       :session (text (:session parsed))
       :said said
       :resets-at (instant-text (or (:resets_at parsed) (:resets-at parsed)))
       :held (vec (for [{:keys [id name input]} (when (sequential? (:held parsed)) (:held parsed))
                        :when (and (string? id) (string? name))]
                    {:id id :name name :input input}))})))
