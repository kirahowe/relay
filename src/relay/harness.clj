(ns relay.harness
  "The interface every harness sits behind. relay owns it, so the program
  that runs a role, and where its tool calls execute, can change without
  touching anything else.

  `run!` takes a request:

    :harness        the harness's settings from relay.edn, with :kind
    :role           the role being run
    :model          the model to use, if the role or harness names one
    :system-prompt  the role's standing instructions
    :prompt         the prompt for this wake
    :tools          the tools the role is offered (relay.tools/manifest)
    :tool-command   the command a harness runs for a tool call, given the
                    tool's name as its last argument: relay's own `tool`
                    command with the data directory and role already named.
                    Tool calls get base-env and nothing else.
    :run-dir        a directory for this wake's transcript and files, which
                    the harness creates
    :session-dir    where the harness keeps sessions for this role
    :resume         a session to carry on from, if the last wake was cut short
    :timeout-ms     how long the run may take

  and returns a report:

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
    :transcript  where the full transcript is

  A clean exit is not a verdict on the memos. Only the role closing them is.

  A conversation is not run by the harness. The harness describes, as
  data, the program that speaks ACP on stdin and stdout for it
  (`acp-command`), and relay's dispatcher starts the process. It returns
  {:argv :dir :env :session-dir}, or {:problem text} when it cannot run.
  It may write files (a request file, say) into the run directory, but it
  starts no process."
  (:refer-clojure :exclude [run!])
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defmulti run! (fn [request] (get-in request [:harness :kind])))

(defmethod run! :default [request]
  (throw (ex-info (str "No harness kind " (pr-str (get-in request [:harness :kind])) ".") {})))

(defmulti acp-command
  "Describes an ACP agent for a conversation request: a program that
  speaks the Agent Client Protocol on stdin and stdout and keeps each ACP
  session it opens in the session-dir."
  (fn [request] (get-in request [:harness :kind])))

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

(defn run-process!
  "Runs a command to completion or the deadline, writing its output to
  `out-file`. Returns the exit code, or \"timeout\" when it was stopped."
  [argv {:keys [dir env in out-file timeout-ms]}]
  (let [proc (p/process (vec argv) {:dir (str dir) :env env :in (or in "")
                                    :out :write :out-file (fs/file out-file)
                                    :err :write :err-file (fs/file (str out-file ".err"))})
        result (deref proc timeout-ms ::timeout)]
    (if (= ::timeout result)
      (do (stop-tree! proc) "timeout")
      (:exit result))))

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
;;                  run counts as having got to work
;;   resume         the session to carry on from, or ""
;;   tool_command   the argv that runs a tool call, given the tool name last
(defmethod run! :script
  [{:keys [harness system-prompt prompt tools tool-command run-dir resume timeout-ms]}]
  (let [file #(str (fs/path run-dir %))
        _ (fs/create-dirs run-dir)
        _ (spit (file "system-prompt.md") system-prompt)
        _ (spit (file "tools.json") (json/generate-string tools))
        out (file "transcript.log")
        report-file (file "report.json")
        args-file (file "args.json")
        _ (spit args-file (json/generate-string {:system_prompt (file "system-prompt.md")
                                                 :tools_file (file "tools.json")
                                                 :report report-file
                                                 :resume (str resume)
                                                 :tool_command (vec tool-command)}))
        exit (run-process! (conj (vec (:command harness)) args-file)
                           {:dir run-dir :in prompt :out-file out :timeout-ms timeout-ms
                            :env (base-env {})})
        report (when (fs/exists? report-file)
                 (json/parse-string (slurp report-file) true))]
    {:outcome (or (:outcome report) "ran")
     :exit exit
     :crashed (not= 0 exit)
     :session (:session report)
     :said (or (:said report) (tail (str (slurp out) (slurp (str out ".err"))) 10))
     :resets-at (:resets-at report)
     :transcript out}))
