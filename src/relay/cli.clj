(ns relay.cli
  "The relay command."
  (:require [babashka.cli :as cli]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [relay.config :as config]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.export :as export]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.render :as render]
            [relay.setup :as setup]
            [relay.tools :as tools]))

(def usage
  "relay: a relay of agent roles, with an append-only log as the record.

Every command needs the data directory, from --data <dir> or from the file
~/.config/relay/config.edn, which holds a map such as {:data \"~/relay-data\"}.

  relay init                          make the data directory, asking for its settings
  relay status                        roles, open work, harnesses, recent wakes
  relay inbox                         memos waiting for the operator
  relay memo <to> -s <subject>        send a memo; body from -m or stdin
  relay close <memo> -m <outcome>     close a memo
  relay roles                         the registry
  relay add <id> [--title <t>] [--reports-to <id>] [--harness <h>] [--model <m>]
                                      add a role; its instructions on stdin
  relay update <id> [--title <t>] [--harness <h>] [--model <m>] [--instructions]
                                      change a role; --instructions reads stdin
  relay remove <id>                   remove a role
  relay routines [<role>]             list routines
  relay routine <role> <name> --schedule <s>
                                      write a routine; instructions on stdin
  relay unroutine <role> <name>       remove a routine
  relay retry <role>                  requeue a role's dead-lettered memos
  relay wake <role> [--note <text>]   run a role now, ignoring retry delays
  relay chat <role>                   talk with a role in this terminal, through
                                      the chat client (acp-tui unless relay.edn
                                      names another with :chat-client)
  relay acp <role> [--trace <file>]   talk with a role from an ACP client, which
                                      runs this as its agent command; --trace
                                      appends the traffic to <file> as JSON lines
  relay tick                          one dispatcher pass (launchd runs this)
  relay facts [<topic>] [--search <s>] [--as-of <t>] [--known-at <t>] [--all]
                                      what relay knows; times as for facts
  relay fact <id>                     every version of a fact
  relay notes <role>                  a role's notes
  relay journal <role> [-n <count>]   a role's latest journal entries
  relay docs                          the docs
  relay doc <path> [--version <n>] [--history]
                                      read a doc, or list its versions
  relay put <path>                    write a doc from stdin
  relay export <dir>                  write a read-only snapshot of the knowledge
  relay log [--tail <n>] [--json]     the events, newest last
  relay tool --data <dir> --role <r> <name>
                                      run a role's tool call (harnesses use this)")

(defn- fail! [& msg]
  (throw (ex-info (str/join " " msg) {:relay/usage true})))

(def ^:dynamic *config-file*
  "The per-user config file. Bound in tests."
  nil)

(defn config-file []
  (or *config-file* (str (fs/path (System/getProperty "user.home") ".config" "relay" "config.edn"))))

(defn- expand-home [path]
  (let [home (System/getProperty "user.home")]
    (cond (= "~" path) home
          (str/starts-with? path "~/") (str (fs/path home (subs path 2)))
          :else path)))

(defn data-dir
  "The data directory: --data, else :data in the per-user config file, with
  a leading ~ expanded."
  [opts]
  (let [file (config-file)
        configured (when (fs/exists? file)
                     (let [c (try (edn/read-string (slurp file))
                                  (catch Exception _
                                    (fail! (str "Could not read " file " as EDN."))))]
                       (when (map? c) (:data c))))]
    (cond
      (not (str/blank? (:data opts))) (:data opts)
      (and (string? configured) (not (str/blank? configured))) (expand-home configured)
      :else (fail! (str "Which data directory? Pass --data <dir>, or set :data in " file
                        ", for example {:data \"~/relay-data\"}.")))))

(defn- result
  "A command's result: its exit code and the lines it prints."
  [exit & lines]
  {:exit exit :out lines})

(defn- init!
  "Makes a data directory, asking for its settings when it has none. An
  existing relay.edn is checked, never rewritten."
  [dir]
  (let [file (str (fs/path dir "relay.edn"))
        ready (fn []
                (log/init! dir)
                (result 0 (str "relay data directory ready at " (fs/absolutize dir))))]
    (cond
      (fs/exists? file) (do (config/load-config dir) (ready))
      (not (System/console))
      (fail! "relay init asks for settings, so run it in a terminal. Or write"
             file "yourself, from setup/relay.example.edn.")
      (setup/create! {:ask (fn [prompt] (print prompt) (flush) (read-line))
                      :say println}
                     file)
      (ready)
      :else (result 1))))

(defn- stdin-text []
  (if (System/console) "" (slurp *in*)))

(defn- submit! [sys type args]
  (inbound/submit! sys {:type type :actor core/operator :args args}))

(defn- run-tool
  "A harness runs this for each tool call: JSON arguments on stdin, the
  result as text on stdout. A refused call exits 1 with the reason, which
  the harness hands back to the model."
  [sys role tool-name]
  (let [raw (stdin-text)
        args (try (if (str/blank? raw) {} (json/parse-string raw true))
                  (catch Exception _ ::bad))]
    (if (= ::bad args)
      (result 1 "The arguments were not valid JSON.")
      (let [{:keys [ok error]} (tools/call! sys role tool-name args)]
        (result (if error 1 0) (or ok error))))))

(defn- tool-output
  "Runs one of relay's own read tools as the operator, for its text."
  [sys tool-name args]
  (result 0 ((get-in tools/builtin [tool-name :run]) sys core/operator args)))

(def ^:private spec
  {:alias {:s :subject :m :message :n :tail}
   :coerce {:subject :string :message :string :title :string :reports-to :string
            :harness :string :model :string :schedule :string :note :string
            :tail :long :data :string :search :string :as-of :string :known-at :string
            :version :long :role :string :trace :string}})

(defn- command
  "Runs one command with the data directory's system. Returns {:exit :out},
  :out being the lines to print."
  [sys dir cmd more opts]
  (let [zone (config/zone (:config sys))
        state #(inbound/current-state sys)
        lines (fn [ls] {:exit 0 :out ls})]
    (case cmd
      "status" (lines (render/status (state) zone ((:clock sys))))
      "inbox" (lines (render/inbox zone (core/unread-memos (state) core/operator)))
      "memo" (let [[to] more
                   body (or (:message opts) (stdin-text))]
               (when-not to (fail! "usage: relay memo <to> -s <subject> [-m <body>]"))
               (let [events (submit! sys :memo/send {:to to :subject (:subject opts) :body body})]
                 (result 0 (str "Sent memo " (:seq (first events))))))
      "close" (let [[id] more]
                (submit! sys :memo/close {:memo (some-> id parse-long) :outcome (:message opts)})
                (result 0 (str "Closed memo " id)))
      "roles" (lines (render/roles (state)))
      "add" (let [[id] more]
              (submit! sys :role/add {:id id :title (:title opts) :reports-to (:reports-to opts)
                                      :harness (:harness opts) :model (:model opts)
                                      :instructions (stdin-text)})
              (result 0 (str "Added " id)))
      "update" (let [[id] more]
                 (submit! sys :role/update
                          (cond-> (select-keys opts [:title :harness :model :reports-to])
                            true (assoc :id id)
                            (:instructions opts) (assoc :instructions (stdin-text))))
                 (result 0 (str "Updated " id)))
      "remove" (let [[id] more]
                 (submit! sys :role/remove {:id id})
                 (result 0 (str "Removed " id)))
      "routines" (lines (render/routines (state) zone (take 1 more)))
      "routine" (let [[owner rname] more]
                  (submit! sys :routine/write {:owner owner :name rname :schedule (:schedule opts)
                                               :instructions (stdin-text)})
                  (result 0 (str "Routine " rname " is set for " owner)))
      "unroutine" (let [[owner rname] more]
                    (submit! sys :routine/remove {:owner owner :name rname})
                    (result 0 (str "Removed routine " rname)))
      "retry" (let [[r] more]
                (result 0 (str (count (submit! sys :memo/retry {:role r})) " memo(s) requeued")))
      "wake" (let [[r] more
                   woke (dispatch/wake! sys r {:note (:note opts) :dispatched (:dispatched opts)})]
               {:exit (if (#{:ran :idle} (:result woke)) 0 1) :out (render/wake-result woke)})
      "chat" (let [[r] more]
               (when-not r (fail! "usage: relay chat <role>"))
               (result (dispatch/chat! sys r {:data (when (:data opts) (str (fs/absolutize dir)))})))
      "acp" (let [[r] more]
              (when-not r (fail! "usage: relay acp <role> [--trace <file>]"))
              (result (dispatch/acp! sys r {:trace (:trace opts)})))
      "tick" (do (dispatch/tick! sys) (result 0))
      "facts" (tool-output sys "facts" {:topic (first more) :search (:search opts)
                                        :as_of (:as-of opts) :known_at (:known-at opts)
                                        :all (:all opts)})
      "fact" (tool-output sys "fact_history" {:fact (some-> (first more) parse-long)})
      "notes" (let [[r] more]
                (when-not r (fail! "usage: relay notes <role>"))
                (lines (render/notes zone (core/notes (state) r))))
      "journal" (let [[r] more]
                  (when-not r (fail! "usage: relay journal <role> [-n <count>]"))
                  (tool-output sys "journal" {:owner r :limit (:tail opts)}))
      "docs" (tool-output sys "docs" {})
      "doc" (let [[path] more]
              (when-not path (fail! "usage: relay doc <path> [--version <n>] [--history]"))
              (if (:history opts)
                (tool-output sys "doc_history" {:path path})
                (tool-output sys "doc_get" {:path path :version (:version opts)})))
      "put" (let [[path] more]
              (lines (render/doc-put path (submit! sys :doc/put {:path path :content (stdin-text)}))))
      "export" (let [[dir] more]
                 (when-not dir (fail! "usage: relay export <dir>"))
                 (result 0 (str "Wrote " (export/export! sys dir) " files to " dir)))
      "log" (lines (render/log (log/read-events (:data-dir sys)) opts))
      "tool" (run-tool sys (:role opts) (first more))
      (result 1 usage))))

(defn execute
  "Runs one command line. Returns {:exit :out}, :out being the lines to
  print. Prints nothing itself, except what a command that talks with the
  operator directly (init, chat, acp) prints along the way."
  [args]
  ;; Options may come before the command (--data) and after it, and
  ;; babashka.cli stops at the first argument, so parse in two passes.
  (let [{global :opts rest-args :args} (cli/parse-args args spec)
        {[cmd & more] :args local-opts :opts} (cli/parse-args rest-args spec)
        opts (merge global local-opts)]
    (if (or (nil? cmd) (#{"help" "-h" "--help"} cmd))
      (result 0 usage)
      (do (when (and (= "tool" cmd) (or (str/blank? (:role opts)) (str/blank? (:data opts))))
            (fail! "usage: relay tool --data <dir> --role <role> <tool name>"))
          (let [dir (data-dir opts)]
            (if (= "init" cmd)
              (init! dir)
              (command (inbound/system dir) dir cmd more opts)))))))

(defn run
  "Runs one command line, prints what it says, and returns the exit code."
  [args]
  (let [{:keys [exit out]} (execute args)]
    (doseq [line out] (println line))
    exit))

(defn -main [& args]
  (let [code (try (run args)
                  (catch clojure.lang.ExceptionInfo e
                    (binding [*out* *err*] (println (ex-message e)))
                    (if (or (core/rejection e) (:relay/usage (ex-data e))) 1 2)))]
    (System/exit code)))
