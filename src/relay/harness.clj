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
    :env            variables the harness passes to every tool call it runs
    :run-dir        an empty directory for this wake's transcript and files
    :session-dir    where the harness keeps sessions for this role
    :resume         a session to carry on from, if the last wake was cut short
    :timeout-ms     how long the run may take

  and returns a report:

    :outcome     \"ran\" when the role got to work, whatever came of it.
                 \"limited\", \"unreachable\" or \"needs-attention\" when the
                 harness itself could not run. Those count against no memo.
    :exit        the exit code, or \"timeout\"
    :crashed     true when the run errored or was stopped at the deadline
    :session     the session id, so an interrupted wake can be resumed
    :said        the last thing the harness said, for a person to read
    :resets-at   when a rate limit lifts, if the harness said
    :transcript  where the full transcript is

  A clean exit is not a verdict on the memos. Only the role closing them is."
  (:refer-clojure :exclude [run!])
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defmulti run! (fn [request] (get-in request [:harness :kind])))

(defmethod run! :default [request]
  (throw (ex-info (str "No harness kind " (pr-str (get-in request [:harness :kind])) ".") {})))

(defmulti chat!
  "Runs an interactive session on the terminal with the same request, less
  the prompt: the operator talks to the role directly. Returns the exit
  code."
  (fn [request] (get-in request [:harness :kind])))

(defmethod chat! :default [request]
  (throw (ex-info (str "The " (pr-str (get-in request [:harness :kind]))
                       " harness has no interactive mode.") {})))

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

(defn run-process!
  "Runs a command to completion or the deadline, writing its output to
  `out-file`. Returns the exit code, or \"timeout\" when it was stopped."
  [argv {:keys [dir env in out-file timeout-ms]}]
  (let [proc (p/process (vec argv) {:dir (str dir) :env env :in (or in "")
                                    :out :write :out-file (fs/file out-file)
                                    :err :write :err-file (fs/file (str out-file ".err"))})
        result (deref proc timeout-ms ::timeout)]
    (if (= ::timeout result)
      (do (p/destroy-tree proc) "timeout")
      (:exit result))))

(defn tail
  "The last few non-blank lines of a text, for a person to read."
  [s n]
  (->> (str/split-lines (str s)) (remove str/blank?) (take-last n) (str/join "\n")))

;; --- script: any program, for tests and simple automations

(defmethod run! :script
  [{:keys [harness system-prompt prompt tools env run-dir resume timeout-ms]}]
  ;; The program gets the prompt on stdin and finds everything else in
  ;; files named by its environment. It may write a JSON report to
  ;; $RELAY_REPORT; without one, a run counts as having got to work.
  (let [file #(str (fs/path run-dir %))
        _ (spit (file "system-prompt.md") system-prompt)
        _ (spit (file "tools.json") (json/generate-string tools))
        out (file "transcript.log")
        report-file (file "report.json")
        exit (run-process! (:command harness)
                           {:dir run-dir :in prompt :out-file out :timeout-ms timeout-ms
                            :env (merge env {"RELAY_SYSTEM_PROMPT" (file "system-prompt.md")
                                             "RELAY_TOOLS_FILE" (file "tools.json")
                                             "RELAY_REPORT" report-file
                                             "RELAY_RESUME" (str resume)})})
        report (when (fs/exists? report-file)
                 (json/parse-string (slurp report-file) true))]
    {:outcome (or (:outcome report) "ran")
     :exit exit
     :crashed (not= 0 exit)
     :session (:session report)
     :said (or (:said report) (tail (str (slurp out) (slurp (str out ".err"))) 10))
     :resets-at (:resets-at report)
     :transcript out}))
