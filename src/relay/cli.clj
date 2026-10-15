(ns relay.cli
  "The relay command."
  (:require [babashka.cli :as cli]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [relay.config :as config]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.export :as export]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.render :as render]
            [relay.setup :as setup]
            [relay.text :as text]
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
  relay acp <role> [--trace <file>] [--approvals]
                                      talk with a role from an ACP client, which
                                      runs this as its agent command; --trace
                                      appends the traffic to <file> as JSON lines;
                                      --approvals lets the client answer held tool
                                      calls, which relay otherwise refuses
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
  relay holds                         tool calls waiting for you to decide, in full
  relay approve <hold>                let a held tool call run
  relay deny <hold> [why]             refuse a held tool call
  relay release <memo>                deliver a quarantined memo to its role
  relay trust <kind> <ref>            vouch for contaminated text you have read:
                                      memo <n>, fact <n>, doc <path>, note <role>/<name>,
                                      routine <role>/<name>, role <id> or journal <n>
  relay tool --data <dir> --role <r> <name>
                                      run a role's tool call (harnesses use this)

approve, deny, release and trust show you what they are about on the
terminal and ask you to type a code back there, so only a person at the
terminal can make those decisions.

--untrusted on memo, put, add, update and routine marks what you write as
contaminated, for text you copied from somewhere you do not trust.")

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
  (if-not (str/blank? (:data opts))
    ;; The file is not even read when --data is given, so a command run
    ;; with --data can never be steered by it.
    (:data opts)
    (let [file (config-file)
          configured (when (fs/exists? file)
                       (let [c (try (edn/read-string (slurp file))
                                    (catch Exception _
                                      (fail! (str "Could not read " file " as EDN."))))]
                         (when (map? c) (:data c))))]
      (if (and (string? configured) (not (str/blank? configured)))
        (expand-home configured)
        (fail! (str "Which data directory? Pass --data <dir>, or set :data in " file
                    ", for example {:data \"~/relay-data\"}."))))))

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

(def ^:dynamic *untrusted*
  "Labels for what the operator writes: contaminated with --untrusted."
  [])

(defn- submit! [sys type args]
  (inbound/submit! sys {:type type :actor core/operator :args args :labels *untrusted*}))

(defn- run-tool
  "A harness runs this for each tool call: an envelope on stdin, {input,
  labels, call_id}, and an envelope on stdout, {content, is_error,
  labels}. A refusal is an ordinary result with is_error set, so the model
  can read why. A call that is not an exact envelope exits 1, with the
  reason on stderr only, and the harness tells the model the tool failed."
  [sys role tool-name]
  (let [{:keys [problem input labels]} (tools/parse-envelope (stdin-text))]
    (cond
      problem
      {:exit 1 :out [] :err [problem]}
      ;; A script harness can claim any labels, so it gets no tools.
      (tools/script-role? (inbound/current-state sys) (:config sys) role)
      {:exit 1 :out [] :err [(str role " runs on a :script harness, which gets no tools.")]}
      :else
      (result 0 (tools/envelope (tools/call! sys role tool-name input labels))))))

(defn- tool-output
  "Runs one of relay's own read tools as the operator, for its text. The
  operator sees everything, contaminated or not."
  [sys tool-name args]
  (result 0 (tools/operator-text sys tool-name (assoc args :accept_contamination true))))

;; --- decisions only a person at the terminal makes

(defn- open-tty
  "The controlling terminal, never stdin: a harness or a pipe cannot answer
  for the operator. Throws when there is none."
  []
  (let [r (io/reader "/dev/tty")
        w (io/writer "/dev/tty")]
    {:say (fn [text] (.write w (str text)) (.flush w))
     :ask (fn [] (.readLine ^java.io.BufferedReader r))}))

(def ^:dynamic *tty*
  "Opens the terminal for a confirmation, as {:say (fn [text]) :ask (fn []
  line)}. Bound in tests."
  open-tty)

(def ^:private code-chars "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")

(defn- confirmation-code []
  (let [rng (java.security.SecureRandom.)]
    (apply str (repeatedly 4 #(nth code-chars (.nextInt rng (count code-chars)))))))

(defn- confirm!
  "Shows `lines` on the terminal and asks for a fresh code to be typed back.
  Throws, changing nothing, when there is no terminal or the code is wrong."
  [verb lines]
  (let [{:keys [say ask]} (try (*tty*)
                               (catch Exception _
                                 (fail! (str "relay " verb " asks you to confirm on a terminal, and there is none."))))
        code (confirmation-code)]
    (say (str (str/join "\n" (map text/visible lines)) "\n\nType " code " to " verb ": "))
    (when-not (= code (some-> (ask) str/trim str/upper-case))
      (fail! "That is not the code. Nothing was changed."))))

(defn- labels-line [labels]
  (str "Labels: " (if (seq labels) (str/join ", " labels) "none")))

(defn- decide-hold! [sys verb verdict hold-id reason]
  (let [h (core/hold (inbound/current-state sys) hold-id)]
    (when-not h (fail! (str "There is no hold " hold-id ".")))
    (confirm! verb (concat (render/hold h) [(labels-line (:labels h))]))
    (submit! sys :hold/resolve {:hold hold-id :verdict verdict :reason reason})
    (result 0 (str (if (= "allow" verdict) "Approved" "Denied") " hold " hold-id ". "
                   (:role h) " carries on at the next tick."))))

(def ^:private spec
  {:alias {:s :subject :m :message :n :tail}
   :coerce {:subject :string :message :string :title :string :reports-to :string
            :harness :string :model :string :schedule :string :note :string
            :tail :long :data :string :search :string :as-of :string :known-at :string
            :version :long :role :string :trace :string :untrusted :boolean :approvals :boolean}})

(defn- command
  "Runs one command with the data directory's system. Returns {:exit :out},
  :out being the lines to print."
  [sys dir cmd more opts]
  (let [zone (config/zone (:config sys))
        state #(inbound/current-state sys)
        lines (fn [ls] {:exit 0 :out ls})]
    (case cmd
      "status" (lines (render/status (state) zone ((:clock sys)) (:config sys)))
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
              (result (dispatch/acp! sys r {:trace (:trace opts) :approvals (:approvals opts)})))
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
      "holds" (lines (render/holds (core/open-holds (state))))
      "approve" (let [[h] more]
                  (decide-hold! sys "approve" "allow" (some-> h parse-long) nil))
      "deny" (let [[h & why] more]
               (decide-hold! sys "deny" "deny" (some-> h parse-long)
                             (or (:message opts) (when (seq why) (str/join " " why)))))
      "release" (let [id (some-> (first more) parse-long)
                      m (core/memo (state) id)]
                  (when-not (and m (core/quarantined? (:config sys) m)) (fail! (str "Memo " (first more) " is not quarantined.")))
                  (confirm! "release" (concat (render/memo zone m) ["" (labels-line (:labels m))
                                                                    (str "Releasing it shows it to " (:to m)
                                                                         ", which then counts as contaminated.")]))
                  (submit! sys :memo/release {:memo id})
                  (result 0 (str "Released memo " id " to " (:to m) ".")))
      "trust" (let [[kind ref] more
                    v (core/trusted-version (state) kind ref)]
                (when-not v (fail! "usage: relay trust <kind> <ref>, naming something relay keeps"))
                (confirm! "trust" [(str kind " " ref ":") "" (:text v) "" (labels-line (:labels v))
                                   "Trusting it clears contaminated from what it says now."])
                ;; What was shown, so a change while the operator read it
                ;; trusts nothing.
                (submit! sys :label/trust {:kind kind :ref ref :version (:version v)
                                           :sha256 (core/sha256 (:text v))})
                (result 0 (str "Trusted " kind " " ref ".")))
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
          (let [dir (data-dir opts)
                result (if (= "init" cmd)
                         (init! dir)
                         (binding [*untrusted* (if (:untrusted opts) [core/contaminated-label] [])]
                           (command (inbound/system dir) dir cmd more opts)))]
            ;; Everything printed may hold text a role wrote, so nothing
            ;; that could drive the terminal is printed raw. relay tool's
            ;; output is a JSON envelope for the harness, not a terminal.
            (if (= "tool" cmd)
              result
              (cond-> result
                (:out result) (update :out #(mapv text/visible %))
                (:err result) (update :err #(mapv text/visible %)))))))))

(defn run
  "Runs one command line, prints what it says, and returns the exit code."
  [args]
  (let [{:keys [exit out err]} (execute args)]
    (doseq [line out] (println line))
    (binding [*out* *err*] (doseq [line err] (println line)))
    exit))

(defn -main [& args]
  (let [code (try (run args)
                  (catch clojure.lang.ExceptionInfo e
                    (binding [*out* *err*] (println (text/visible (ex-message e))))
                    (if (or (core/rejection e) (:relay/usage (ex-data e))) 1 2)))]
    (System/exit code)))
