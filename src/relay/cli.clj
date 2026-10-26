(ns relay.cli
  "The relay command."
  (:require [babashka.cli :as cli]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [relay.cards :as cards]
            [relay.config :as config]
            [relay.channels :as channels]
            [relay.connect :as connect]
            [relay.core :as core]
            [cheshire.core :as json]
            [relay.digest :as digest]
            [relay.dispatch :as dispatch]
            [relay.export :as export]
            [relay.harness.agentloop :as agentloop]
            [relay.inbound :as inbound]
            [relay.files :as files]
            [relay.ingest-run :as ingest-run]
            [relay.log :as log]
            [relay.messages :as messages]
            [relay.moves :as moves]
            [relay.pause :as pause]
            [relay.ready :as ready]
            [relay.render :as render]
            [relay.roster :as roster]
            [relay.serve :as serve]
            [relay.setup :as setup]
            [relay.shell :as shell]
            [relay.text :as text]
            [relay.tools :as tools]
            [relay.traces :as traces]
            [relay.websocket :as ws]
            [relay.week :as week]))

(def short-usage
  "What relay help prints: the everyday commands, each line at most 60
  characters, so it reads on a phone's terminal."
  "relay: your assistant, and what it has ready for you.

  relay                    open the dashboard
  relay ready              what is ready for you
  relay reply <n> -m <text>
                           answer message <n>
  relay memo steward -s <subject> -m <text>
                           ask for something
  relay revert <n>         undo a change the note flags
  relay dismiss <n>        drop a suggestion from the note
  relay undo <n>           put back files that were moved
  relay status             what is running, and what it cost

Every command takes --data <dir>, unless the file
~/.config/relay/config.edn names it.
relay help all lists every command.")

(def usage
  "relay: a relay of agent roles, with an append-only log as the record.

Every command needs the data directory, from --data <dir> or from the file
~/.config/relay/config.edn, which holds a map such as {:data \"~/relay-data\"}.

  relay init                          make the data directory, asking for its settings
  relay status                        roles, open work, harnesses, recent wakes
  relay inbox                         memos waiting for you
  relay ready [--all]                 what is ready for you, the 10 newest: what
                                      you were told, drafts, calendar entries,
                                      findings, file moves
  relay ready <n>                     one message whole, by its number (a cut
                                      item says how to open it whole)
  relay reply <message> -m <text>     answer a message from relay ready; the role
                                      that told you is woken with your answer
  relay memo <to> -s <subject>        send a memo; body from -m or stdin
  relay close <memo> -m <outcome>     close a memo
  relay roles                         the registry, and what each role is doing
  relay add <id> [--title <t>] [--reports-to <id>] [--harness <h>] [--model <m>]
                                      add a role; its instructions on stdin
  relay update <id> [--title <t>] [--harness <h>] [--model <m>] [--instructions]
                                      change a role; --instructions reads stdin
  relay remove <id>                   remove a role
  relay routines [<role>]             list routines
  relay routine <role> <name> --schedule <s>
                                      write a routine; instructions on stdin
  relay routine run [<role>] <name>   run a routine now, as its schedule would, and
                                      wake its role to do it; <role> only when two
                                      roles have a routine of that name
  relay unroutine <role> <name>       remove a routine
  relay retry <role>                  requeue a role's dead-lettered memos
  relay wake <role> [--note <text>]   run a role now, ignoring retry delays
  relay [--open <role>]               open the dashboard in this terminal, through
                                      the client (relay-tui unless relay.edn
                                      names another with :client), with <role>
                                      in focus when given
  relay help                          the everyday commands (also relay --help)
  relay help all                      print this
  relay <command> --help              just that command's lines from this list
  relay acp [<role>] [--trace <file>]
                                      talk with the roles from an ACP client, which
                                      runs this as its agent command: through
                                      relay serve when it runs, else in this
                                      process; <role> is the role a session is
                                      for when the client does not say; --trace
                                      appends the traffic to <file> as JSON lines
  relay serve                         run the service that keeps conversations
                                      going while no client is attached (launchd
                                      runs this; see setup/); its lock and
                                      socket are in ~/.relay/run. With :serve
                                      :websocket in relay.edn it also listens
                                      for phone clients on 127.0.0.1. --socket and
                                      --run-dir, here and for relay acp, are
                                      for relay's own tests only
  relay tick [--pause-file <f>]       one dispatcher pass (launchd runs this); does
                                      nothing while the pause file exists, which
                                      bb deploy makes
  relay facts [<topic>] [--search <s>] [--as-of <t>] [--known-at <t>] [--all]
                                      what relay knows; times as for facts
  relay fact <id>                     every version of a fact
  relay fact add <text> [--topic <t>] [--from <time>] [--to <time>]
                                      record a fact
  relay fact correct <id> --why <w> [--text <t>] [--topic <t>] [--from <time>] [--to <time>]
                                      correct a fact
  relay fact revert <id> <version>    put back an earlier version of a fact
  relay notes <role>                  a role's notes
  relay journal <role> [-n <count>]   a role's latest journal entries
  relay cards [--archived]            your board: the active cards, and
                                      with --archived the archived ones too
  relay card <id>                     one card
  relay card <id> <op> [--item <n>] [--row <n>] [--column <n>] [--state <s>]
                       [--text <t>] [--seen <rev>]
                                      change a card: retitle, archive, restore; add,
                                      tick, untick, edit, remove (a checklist); set,
                                      add_row, add_column, rename_row, rename_column,
                                      remove_row, remove_column (a tracker); write
                                      (a note)
  relay card add <kind> --title <t> [--item <t> ...] [--row <t> ...] [--column <t> ...]
                 [--text <t>]         put a checklist, tracker or note on the board
  relay docs                          the docs
  relay doc <path> [--version <n>] [--history]
                                      read a doc, or list its versions
  relay put <path>                    write a doc from stdin
  relay doc revert <path> <version>   put back an earlier version of a doc
  relay export <dir>                  write a read-only snapshot of the knowledge
  relay log [--tail <n>] [--json]     the events, newest last
  relay week [--since <date>] [--until <date>] [--json]
                                      how often relay needed you and how long it
                                      waited, what each role's wakes did and cost,
                                      wakes that look wrong, and your flags; the
                                      last 7 days unless dates (YYYY-MM-DD) say
  relay flag <wake> good|bad [note]   mark a wake or conversation (its id from relay
                                      status or relay week) to find it later
  relay traces --out <dir> [--since <date>] [--until <date>] [--agentloop <bin>]
                                      write the wakes and conversations as OpenTelemetry
                                      traces to <dir>/traces.otlp.jsonl, offline; they
                                      hold no content
  relay connect <name> <url> [--client-id <id>] [--agentloop <bin>]
                                      sign relay in to a remote MCP server and write
                                      connections/<name>.edn with its tools as relay
                                      proposes them; list <name> in
                                      relay.edn's :connections to load it
  relay mcp-token <name> --url <url>  print an access token for a connection to
                                      that server, renewing it when needed
                                      (agentloop runs this)
  relay moves                         the files roles moved, each call with its number
  relay undo <move> [<file>...]       put back what one call moved, or only the
                                      files named (as relay moves shows them);
                                      a file changed since stays, and is named
  relay house build|up|down|remove|status
                                      the steward's shell, from relay.edn's :house:
                                      build its image (this needs the network),
                                      start it, stop it, stop it and delete its
                                      image, or say how it is and what it sees
                                      (the tick starts it when it is missing)
  relay changes [-n <count>]          the latest changes roles made to how they
                                      work (skills, routines, notes, roles),
                                      with the number a daily note gave each
  relay note [now]                    show the daily note as it would go out now;
                                      with now, make today's and send it
  relay revert <item> [<note>]        undo a change the latest daily note (or note
                                      <note>) flags, by its number there
  relay revert c<k>                   undo any other change, by the handle relay
                                      changes gives it
  relay decline <item> [<note>]       say no to the offer of help in an item of the
                                      latest daily note (or of note <note>); the
                                      item stays, and the offer does not come back
  relay dismiss <item> [<note>]       drop an item of the latest daily note (or
                                      of note <note>) for good
  relay calendar url [--host <name>]  the address to subscribe to the agent's
                                      calendar at, in your calendar app; it opens
                                      the calendar and nothing else
  relay poll [<source>]               check relay.edn's :ingest sources now (the
                                      mailbox, GitHub, calendars), due or not, and
                                      say what came in
  relay tool --data <dir> --role <r> [--wake <w>] <name>
                                      run a role's tool call (harnesses use this)

Whatever you write or paste here is yours, and relay treats it so.

--public on memo, routine, fact, put, add, update and wake --note marks what
you write as public: it may leave this machine, since a public role can put
it in a web search. A public role is shown only public text, so anything you
write for one needs it. On add and update it marks the role's text public: a
public role's own id, title and instructions must be, and it sees only roles
whose text is. update --public with both --title and --instructions marks the
id public too.")

(defn- fail! [& msg]
  (throw (ex-info (str/join " " msg) {:relay/usage true})))

(def ^:dynamic *config-file*
  "The per-user config file. Bound in tests."
  nil)

(def ^:dynamic *pause-file*
  "The pause file (relay.pause) in place of the default. Bound in tests."
  nil)

(defn pause-file
  "The pause file: --pause-file, else the default under the user's home."
  [opts]
  (or (when-not (str/blank? (:pause-file opts)) (:pause-file opts))
      *pause-file*
      (pause/default-file (System/getProperty "user.home"))))

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

(defn- add-starters!
  "Adds the steward, its daily-note skill and routine, and with the web
  the scout, to a log that has no roles yet, from the texts in setup/.
  Returns the lines to say."
  [dir web?]
  (let [sys (inbound/system dir)]
    (cond
      (seq (:roles (inbound/current-state sys))) []
      :else
      (if-let [texts (some-> (setup/repo-root) setup/starter-texts)]
        (do (doseq [c (setup/starter-commands texts web?)]
              (inbound/submit! sys (assoc c :actor core/operator)))
            [(str "Added your steward" (when web? " and a scout that searches the web")
                  ", and the steward's daily-note routine.")])
        ["relay could not find its setup/ texts, so it added no roles. Add your steward with"
         "  relay add steward --title Steward < setup/steward.md"]))))

(defn- init!
  "Makes a data directory, asking for its settings when it has none, and
  adds the roles relay sets up when the log has none. An existing
  relay.edn is checked, never rewritten."
  [dir]
  (let [file (str (fs/path dir "relay.edn"))
        ready (fn [& more]
                (log/init! dir)
                (apply result 0 (str "relay data directory ready at " (fs/absolutize dir)) more))]
    (cond
      (fs/exists? file) (do (config/load-config dir) (ready))
      (not (System/console))
      (fail! "relay init asks for settings, so run it in a terminal. Or write"
             file "yourself, from setup/relay.example.edn.")
      :else
      (if-let [settings (setup/create! {:ask (fn [prompt] (print prompt) (flush) (read-line))
                                        :say println
                                        :webtool (setup/webtool-bin)
                                        ;; Written as an absolute :bin, so no other
                                        ;; agentloop on PATH runs in its place.
                                        :agentloop (some-> (agentloop/agentloop-bin {}) fs/absolutize str)}
                                       file)]
        (do (log/init! dir)
            (let [web (:web settings)
                  added (add-starters! dir (some? web))]
              (apply ready
                     (concat added
                             (setup/next-steps
                              {:key-ok? (setup/command-works? (get-in settings [:harnesses :agentloop :api-key-command]))
                               :provider (get-in settings [:harnesses :agentloop :provider])
                               :web? (some? web)
                               :brave-ok? (and web (setup/command-works? (:key-command web)))
                               :data-dir (str (fs/absolutize dir))})))))
        (result 1)))))

(def ^:dynamic *stdin*
  "Text to read as stdin instead of the process's, or nil. Bound by the
  scenario runner, which has no terminal to pipe from."
  nil)

(def ^:dynamic *clock*
  "A clock (a function returning an Instant) to run commands with instead
  of the real one, or nil. Bound by the scenario runner, which moves time
  by hand."
  nil)

(defn- stdin-text []
  (cond *stdin* *stdin*
        (System/console) ""
        :else (slurp *in*)))

(defn- stdin-bytes
  "stdin as bytes, for relay's strict JSON reader to decode
  (relay.json/text), or *stdin* when a test bound it."
  []
  (cond *stdin* *stdin*
        (System/console) ""
        :else (.readAllBytes System/in)))

(def ^:dynamic *public*
  "Whether what the operator writes is public: --public."
  false)

(def ^:dynamic *reports*
  "An atom holding the sanitizing reports of what the operator wrote in
  this command, or nil outside a command. run-command binds it and tells
  the operator what changed."
  nil)

(defn- sanitized
  "x, a string or data holding strings, cleaned of hidden characters
  (relay.text/sanitize-input). What changed is told to the operator when
  the command ends."
  [x]
  (let [{:keys [value report]} (text/sanitize-input x)]
    (when *reports* (swap! *reports* conj report))
    value))

(defn- submit!
  "Submits the operator's command. What the operator writes at the
  command line is theirs, so it carries the labels of that entry point:
  none (I5). Its text is sanitized first (I6)."
  [sys type args]
  (inbound/submit! sys {:type type :actor core/operator :args (sanitized args)
                        :labels (core/entry-labels :command-line) :public *public*}))

(defn- run-tool
  "A harness runs this for each tool call: an envelope on stdin, {input,
  labels, call_id}, and an envelope on stdout, {content, is_error,
  labels}. A refusal is an ordinary result with is_error set, so the model
  can read why. A call that is not an exact envelope exits 1, with the
  reason on stderr only, and the harness tells the model the tool failed."
  [sys role tool-name wake]
  (let [{:keys [problem input labels call-id]} (tools/parse-envelope (stdin-bytes))]
    (cond
      problem
      {:exit 1 :out [] :err [problem]}
      ;; A script harness can claim any labels, so it gets no tools.
      (tools/script-role? (inbound/current-state sys) (:config sys) role)
      {:exit 1 :out [] :err [(str role " runs on a :script harness, which gets no tools.")]}
      :else
      (result 0 (tools/envelope (tools/call! sys role tool-name input labels call-id wake))))))

(defn- knowledge
  "The operator's views of what relay knows. The operator sees everything,
  contaminated or not, each role-written text behind the gutter."
  [sys what args]
  (let [state (inbound/current-state sys)
        config (:config sys)
        zone (config/zone config)]
    {:exit 0
     :out (case what
            :facts (let [{:keys [as-of known-at topic search all]} args
                         at #(core/world-time config % %2)]
                     (render/facts state zone (core/facts state {:as-of (or (at as-of "as_of") ((:clock sys)))
                                                                 :known-at (at known-at "known_at")
                                                                 :topic topic :search search :all all})))
            :fact (let [f (core/fact state (:fact args))]
                    (when-not f (fail! (str "There is no fact " (:fact args) ".")))
                    (render/fact-history state zone f))
            :journal (render/journal zone (core/journal state (:owner args) (or (:limit args) 10)))
            :docs (render/docs state zone (core/docs state nil))
            :doc (let [{:keys [path version]} args
                       v (core/doc-version state path {:version version})]
                   (when-not v (fail! (str "There is no doc at " path (when version (str " with version " version)) ".")))
                   (render/doc zone v))
            :doc-history (let [vs (get-in state [:docs (:path args) :versions])]
                           (when (empty? vs) (fail! (str "There is no doc at " (:path args) ".")))
                           (render/doc-history zone (:path args) vs)))}))

;; --- the board

(def ^:dynamic *terminal*
  "How relay connect talks with the operator: {:say (fn [text]) :ask (fn []
  line)}, stdout and stdin by default. Bound in tests."
  (fn [] {:say (fn [text] (print text) (flush))
          :ask (fn [] (read-line))}))

(defn- operator-card! [sys type args]
  ;; Whatever the operator writes on a card is the operator's: no labels,
  ;; never public, whatever --public says.
  (inbound/submit! sys {:type type :actor core/operator :args (sanitized args)
                        :labels (core/entry-labels :command-line) :public false}))

(def ^:private card-options
  "What relay card reads, and the options every command may be given."
  #{:item :row :column :state :text :seen :title :data :public})

(defn- card!
  "relay card: show one card, change it with an op, or add one."
  [sys [a b] opts]
  (let [one (fn [k]
              (let [vs (get opts k)]
                (when (> (count vs) 1) (fail! (str "--" (name k) " takes one number here.")))
                (when-let [v (first vs)]
                  (or (parse-long v) (fail! (str "--" (name k) " takes a number, not " v "."))))))
        ;; An option relay would ignore is refused, so a mistyped --seen
        ;; cannot quietly turn off the stale-edit check.
        unknown (sort (map name (remove card-options (keys opts))))]
    (when (seq unknown)
      (fail! (str "relay card has no option " (str/join ", " (map #(str "--" %) unknown))
                  ". It takes --item, --row, --column, --state, --text, --seen and, to add, --title.")))
    (cond
      (nil? a)
      (fail! "usage: relay card <id> [<op> ...], or relay card add <kind> --title <t> ...")
      (and (= "add" a) (nil? b))
      (fail! "usage: relay card add <kind> --title <t> [--item <t> ...] [--row <t> ...] [--column <t> ...] [--text <t>]")
      (= "add" a)
      (let [e (first (operator-card! sys :card/add (cond-> {:kind b :title (:title opts)}
                                                       (:item opts) (assoc :items (:item opts))
                                                       (:row opts) (assoc :rows (:row opts))
                                                       (:column opts) (assoc :columns (:column opts))
                                                       (some? (:text opts)) (assoc :text (:text opts)))))]
        (result 0 (str "Added card " (:seq e) ".")))
      :else
      (let [id (or (parse-long a) (fail! (str "A card's id is a number, not " a ".")))]
        (if (nil? b)
          (if-let [c (core/card (inbound/current-state sys) id)]
            {:exit 0 :out (render/card c)}
            (fail! (str "There is no card " id ".")))
          (let [op (cond-> {:op b}
                     (one :item) (assoc :item (one :item))
                     (one :row) (assoc :row (one :row))
                     (one :column) (assoc :column (one :column))
                     (:state opts) (assoc :state (:state opts))
                     (some? (:text opts)) (assoc :text (:text opts))
                     (:seen opts) (assoc :seen (:seen opts)))]
            (if-let [e (first (operator-card! sys :card/op (assoc op :card id)))]
              (result 0 (cards/confirmation id (get-in e [:data :op])))
              (result 0 (str "That changes nothing: "
                             (:unchanged (cards/decide (:cards (inbound/current-state sys)) id op)))))))))))

(defn- date-option [opts k]
  (when-let [v (get opts k)]
    (try (java.time.LocalDate/parse v)
         (catch Exception _ (fail! (str "--" (name k) " takes a date such as 2026-10-18, not " v "."))))))

(defn- week-rows
  "The window --since and --until name, the log, and the wakes and
  conversations that started in it with what their run files say."
  [sys opts]
  (let [zone (config/zone (:config sys))
        win (week/window zone ((:clock sys)) (date-option opts :since) (date-option opts :until))
        events (log/read-events (:data-dir sys))
        walked (week/walk-log events)
        wakes (filter #(week/in-window? win (:started-at %)) (vals (:wakes (:state walked))))
        facts (traces/read-facts (:data-dir sys) wakes)]
    {:win win :events events :facts facts :rows (week/wake-rows walked win facts)}))

(defn- week-report [sys opts]
  (let [{:keys [win events facts]} (week-rows sys opts)]
    (week/report events (config/zone (:config sys)) ((:clock sys)) win facts)))

(def ^:private spec
  {:alias {:s :subject :m :message :n :tail}
   :coerce {:subject :string :message :string :title :string :reports-to :string
            :harness :string :model :string :schedule :string :note :string
            :tail :long :data :string :search :string :as-of :string :known-at :string
            :version :long :wake :long :role :string :trace :string :public :boolean
            :topic :string :from :string :to :string :why :string :text :string
            :pause-file :string :socket :string :run-dir :string :since :string :until :string :out :string :agentloop :string
            :client-id :string :url :string :open :string :host :string
            :item [:string] :row [:string] :column [:string] :state :string :seen :long :archived :boolean}})

(defn- house!
  "relay house build|up|down|status."
  [sys [sub]]
  (let [ctx #(shell/context (:data-dir sys) (:config sys))]
    (case sub
      "build" (let [{:keys [restarted]} (shell/build! (ctx))]
                (result 0 (if restarted "Built the shell's image, and restarted the shell with it." "Built the shell's image.")))
      "up" (result 0 (case (shell/up! (ctx)) :running "The shell is already running." :started "Started the shell."))
      "down" (result 0 (case (shell/down! (ctx)) :stopped "Stopped the shell. Its scratch files are gone." :absent "The shell was not running."))
      "remove" (let [{:keys [container image]} (shell/remove! (ctx))]
                 (result 0 (str (case container :stopped "Stopped the shell" :absent "The shell was not running")
                                (case image
                                  :removed ", and removed its image. relay house build makes it again."
                                  :absent ", and it has no image."
                                  :kept (str ". Its image " (get-in (ctx) [:house :image])
                                             " is named in relay.edn, so it is kept.")))))
      "status" (let [c (ctx)
                     {:keys [running current image-present]} (shell/status c)
                     mounts (get-in c [:house :mounts])]
                 (apply result 0
                        (cond
                          (and running current) "The shell is running."
                          running "The shell is running with older settings. relay house up restarts it."
                          (not image-present) "The shell is not running, and its image is not built. Run relay house build."
                          :else "The shell is not running. relay house up starts it.")
                        (if (seq mounts)
                          (cons "It sees, read-only:"
                                (map #(str "  " (shell/mount-path %) (when (shell/mount-trusted? %) " (trusted)")) mounts))
                          ["It sees none of your folders: :house has no :mounts."])))
      (fail! "usage: relay house build|up|down|remove|status"))))

(defn- run-routine!
  "relay routine run [<role>] <name>: sends the routine's run now, as the
  tick would when it came due, then wakes its role to do it, as relay
  wake does."
  [sys args]
  (let [[owner rname] (if (next args) args [nil (first args)])]
    (when (str/blank? rname) (fail! "usage: relay routine run [<role>] <name>"))
    (let [events (submit! sys :routine/run {:owner owner :name rname})
          fired (first (filter #(= "routine/fired" (:type %)) events))
          role (get-in fired [:data :owner])
          woke (dispatch/wake! sys role {})]
      {:exit (if (#{:ran :idle} (:result woke)) 0 1)
       :out (cons (str "Sent " role " its " rname " routine now (memo " (get-in fired [:data :memo]) ").")
                  (render/wake-result woke))})))

(defn- note!
  "relay note shows the daily note as it would go out now; relay note now
  makes today's and sends it, as the tick does at :daily-note :at. It is
  made from what the steward wrote for it, so relay routine run
  daily-note first has the steward write its parts."
  [sys [sub]]
  (let [config (:config sys)
        zone (config/zone config)
        now ((:clock sys))
        today (.toLocalDate (.atZone ^java.time.Instant now zone))
        state (inbound/current-state sys)]
    (when-not (:daily-note config)
      (fail! "relay.edn has no :daily-note, so there is no daily note. Set :daily-note {:at \"07:30\" :role \"steward\"}."))
    (case sub
      nil (let [{:keys [subject sections]} (core/compose-note state config today now)]
            (if-not sections
              (result 0 "The daily note has nothing to say yet, so none would go out now."
                      (str "The steward writes its parts from its routine: relay routine run "
                           (digest/role-of config) " daily-note."))
              (apply result 0 subject ""
                     (str/split-lines (channels/body-text {:subject subject :sections sections :labels []})))))
      "now" (cond
              (and (= today (core/daily-date state)) (not (false? (get-in state [:daily :sent]))))
              (result 0 (str "Today's note was made already. The next one is tomorrow at "
                             (get-in config [:daily-note :at]) "."))
              ;; Nothing to say yet: nothing is recorded, so the day's note
              ;; can still go out once the steward has written its parts.
              (nil? (:sections (core/compose-note state config today now)))
              (result 0 "Today's note has nothing to say yet, so nothing was sent, and it can still go out today."
                      (str "The steward writes its parts from its routine: relay routine run "
                           (digest/role-of config) " daily-note."))
              :else
              (let [events (inbound/submit! sys {:type :digest/compose :actor core/dispatcher
                                                 :args {:date (str today)}})
                    sent? (some #(= "message/created" (:type %)) events)
                    st (inbound/current-state sys)]
                (doseq [{:keys [channel messages]} (core/messages-due st now (.minusSeconds ^java.time.Instant now 120))]
                  (inbound/deliver! sys channel messages))
                (result 0 (if sent?
                            "Sent today's daily note. It counts as today's, so the next one is tomorrow."
                            "Today's note had nothing to say, so nothing was sent."))))
      (fail! "usage: relay note [now]"))))

(defn- tailnet-name
  "This Mac's name on the tailnet, from `tailscale status --json`, or nil
  when tailscale is not on PATH or does not say within five seconds."
  []
  (when-let [bin (fs/which "tailscale")]
    (try
      (let [proc (p/process [(str bin) "status" "--json"] {:out :string :err :string})
            r (deref proc 5000 nil)]
        (if (and r (zero? (:exit r)))
          (some-> (json/parse-string (:out r)) (get-in ["Self" "DNSName"]) (str/replace #"\.$" "") not-empty)
          (do (p/destroy-tree proc) nil)))
      (catch Exception _ nil))))

(defn- calendar!
  "relay calendar url: the address a calendar app subscribes to the
  agent's calendar at, with the feed's own token (relay.websocket/
  feed-token), which opens the calendar and nothing else."
  [sys [sub] opts]
  (when-not (= "url" sub) (fail! "usage: relay calendar url [--host <name>]"))
  (let [{:keys [port token-command]} (get-in (:config sys) [:serve :websocket])]
    (when-not port
      (fail! "The calendar is served by relay serve's listener, and relay.edn has none yet. See \"Reaching"
             "relay from your phone\" in the README for :serve {:websocket ...}."))
    (let [{:keys [token error]} (serve/read-token token-command)
          _ (when error (fail! (str "relay could not read the listener's token: " error ".")))
          host (or (:host opts) (tailnet-name))]
      (if host
        (result 0 (ws/calendar-url host port token)
                "Add it in your calendar app as a calendar subscription. It opens the calendar and nothing else.")
        (result 0 (ws/calendar-url "<your Mac's tailnet name>" port token)
                "relay could not find this Mac's tailnet name: put it in, or run relay calendar url --host <name>.")))))

(def ^:private ready-shown
  "How many of the newest items relay ready shows without --all."
  10)

(defn- command
  "Runs one command with the data directory's system. Returns {:exit :out},
  :out being the lines to print."
  [sys dir cmd more opts]
  (let [zone (config/zone (:config sys))
        state #(inbound/current-state sys)
        lines (fn [ls] {:exit 0 :out ls})]
    (case cmd
      "status" (lines (render/status (state) zone ((:clock sys)) (:config sys) (pause/recovery-lines (pause-file opts))))
      "inbox" (lines (let [st (state)] (render/inbox st zone (core/unread-memos st core/operator))))
      "ready" (let [all (ready/ready (state) {:limit Integer/MAX_VALUE})
                    shown (if (:all opts) all (take ready-shown all))
                    [ref] more]
                (if ref
                  (let [id (if (re-matches #"\d+" ref) (str "message:" ref) ref)
                        it (first (filter #(= id (:id %)) all))]
                    (when-not it
                      (fail! (str "Nothing ready is numbered " ref ". relay ready lists what is ready.")))
                    (lines (render/ready-one (ready/wire it) zone ((:clock sys)))))
                  (lines (render/ready (mapv ready/wire shown) zone ((:clock sys)) (- (count all) (count shown))))))
      "reply" (let [[id] more
                    _ (when-not (some-> id parse-long) (fail! "usage: relay reply <message> -m <text>"))
                    text (or (:message opts)
                             *stdin*
                             ;; Read from the terminal or a pipe, saying
                             ;; so on stderr, so it never waits unseen.
                             (do (binding [*out* *err*]
                                   (println "Type your reply, then press Ctrl-D on a new line. (Or pass -m \"...\".)")
                                   (flush))
                                 (slurp *in*)))]
                (submit! sys :message/reply {:in-reply-to (parse-long id) :text text})
                (result 0 (str "Sent your reply to message " id ".")))
      "memo" (let [[to] more
                   body (or (:message opts) (stdin-text))]
               (when-not to (fail! "usage: relay memo <to> -s <subject> [-m <body>]"))
               (let [events (submit! sys :memo/send {:to to :subject (:subject opts) :body body})]
                 (result 0 (str "Sent memo " (:seq (first events))))))
      "close" (let [[id] more]
                (submit! sys :memo/close {:memo (some-> id parse-long) :outcome (:message opts)})
                (result 0 (str "Closed memo " id)))
      "roles" (lines (let [s (state)] (render/roles s (roster/roster s (:config sys) ((:clock sys))))))
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
      "routine" (if (and (= "run" (first more)) (nil? (:schedule opts)))
                  (run-routine! sys (rest more))
                  (let [[owner rname] more]
                  (submit! sys :routine/write {:owner owner :name rname :schedule (:schedule opts)
                                               :instructions (stdin-text)})
                  (result 0 (str "Routine " rname " is set for " owner))))
      "unroutine" (let [[owner rname] more]
                    (submit! sys :routine/remove {:owner owner :name rname})
                    (result 0 (str "Removed routine " rname)))
      "retry" (let [[r] more]
                (result 0 (str (count (submit! sys :memo/retry {:role r})) " memo(s) requeued")))
      "wake" (let [[r] more
                   woke (dispatch/wake! sys r {:note (sanitized (:note opts)) :dispatched (:dispatched opts)
                                               :public (:public opts)})]
               (if (or (= :idle (:result woke)) (and (= :ran (:result woke)) (= "ran" (:outcome woke))))
                 {:exit 0 :out (render/wake-result r woke)}
                 {:exit 1 :out [] :err (render/wake-result r woke)}))
      :dashboard (result (dispatch/dashboard! sys (:open opts) {:data (when (:data opts) (str (fs/absolutize dir)))}))
      "acp" (let [[r] more]
              (result (serve/acp! sys r {:trace (:trace opts) :socket (:socket opts) :run-dir (:run-dir opts)})))
      "serve" (result (serve/serve! sys {:socket (:socket opts) :run-dir (:run-dir opts)}))
      "tick" (do (dispatch/tick! sys) (result 0))
      "facts" (knowledge sys :facts {:topic (first more) :search (:search opts)
                                     :as-of (:as-of opts) :known-at (:known-at opts) :all (:all opts)})
      "fact" (let [[sub a b] more]
               (case sub
                 "add" (let [events (submit! sys :fact/add {:text a :topic (:topic opts)
                                                            :valid-from (:from opts) :valid-to (:to opts)})]
                         (result 0 (str "Recorded fact " (:seq (first events)))))
                 "correct" (do (submit! sys :fact/correct
                                        (cond-> {:fact (some-> a parse-long) :why (:why opts)}
                                          (:text opts) (assoc :text (:text opts))
                                          (:topic opts) (assoc :topic (:topic opts))
                                          (:from opts) (assoc :valid-from (:from opts))
                                          (:to opts) (assoc :valid-to (:to opts))))
                               (result 0 (str "Corrected fact " a)))
                 "revert" (let [id (some-> a parse-long)
                                version (some-> b parse-long)
                                v (first (filter #(= version (:seq %)) (:versions (core/fact (state) id))))]
                            (when-not v (fail! "usage: relay fact revert <fact> <version>, a version from relay fact <fact>"))
                            (submit! sys :fact/revert {:fact id :version version})
                            (result 0 (str "Reverted fact " id " to version " version ".")))
                 (knowledge sys :fact {:fact (some-> sub parse-long)})))
      "notes" (let [[r] more]
                (when-not r (fail! "usage: relay notes <role>"))
                (lines (render/notes zone (core/notes (state) r))))
      "journal" (let [[r] more]
                  (when-not r (fail! "usage: relay journal <role> [-n <count>]"))
                  (knowledge sys :journal {:owner r :limit (:tail opts)}))
      "cards" (lines (render/board (core/board (state) (:archived opts))))
      "card" (card! sys more opts)
      "docs" (knowledge sys :docs {})
      "doc" (let [[path a b] more]
              (when-not path (fail! "usage: relay doc <path> [--version <n>] [--history]"))
              (when (= "revert" path)
                (let [version (some-> b parse-long)
                      v (core/doc-version (state) a {:version version})]
                  (when-not v (fail! "usage: relay doc revert <path> <version>, a version from relay doc <path> --history"))
                  (submit! sys :doc/revert {:path a :version version})))
              (cond
                (= "revert" path) (result 0 (str "Reverted " a " to version " b "."))
                (:history opts)
                (knowledge sys :doc-history {:path path})
                :else (knowledge sys :doc {:path path :version (:version opts)})))
      "put" (let [[path] more]
              (lines (render/doc-put path (submit! sys :doc/put {:path path :content (stdin-text)}))))
      "export" (let [[dir] more]
                 (when-not dir (fail! "usage: relay export <dir>"))
                 (result 0 (str "Wrote " (export/export! sys dir) " files to " dir)))
      "log" (lines (render/log (log/read-events (:data-dir sys)) opts))
      "week" (let [r (week-report sys opts)]
               (if (:json opts)
                 (result 0 (-> (json/generate-string (week/json-ready r) {:escape-non-ascii true})
                               (str/replace "\u007f" "\\u007f")))
                 (lines (render/week-report r zone))))
      "flag" (let [[id value & note] more
                   wake-id (some-> id parse-long)]
               (when-not (and wake-id value)
                 (fail! "usage: relay flag <wake> good|bad [note]"))
               (submit! sys :wake/flag {:wake wake-id :value value
                                        :note (or (:message opts) (str/join " " note))})
               (let [w (get-in (state) [:wakes wake-id])]
                 (result 0 (str "Flagged " (if (:chat w) "conversation " "wake ") wake-id " (" (:role w) ") " value "."))))
      "traces" (let [out (:out opts)
                     _ (when (str/blank? out) (fail! "usage: relay traces --out <dir> [--since <date>] [--until <date>]"))
                     config (:config sys)
                     bin (or (:agentloop opts)
                             (agentloop/agentloop-bin (get-in config [:harnesses (:default-harness config)])))
                     _ (when-not bin (fail! "relay traces needs agentloop: pass --agentloop <bin>."))
                     {:keys [rows]} (week-rows sys opts)
                     {:keys [file lines wakes conversations problems]} (traces/export! dir bin rows out)]
                 (apply result 0 (str "Wrote " lines " lines for " wakes " wakes and " conversations
                                      " conversations to " file ".")
                        problems))
      "tool" (run-tool sys (:role opts) (first more) (:wake opts))
      "house" (house! sys more)
      "calendar" (calendar! sys more opts)
      "moves" (lines (files/calls-lines (core/move-calls (state)) zone))
      "undo" (let [[n & names] more
                   id (some-> n parse-long)
                   usage "usage: relay undo <move> [<file>...], a number from relay moves, and the files of it to put back"]
               (when-not id (fail! usage))
               (when-let [flag (or (some->> (keys (dissoc opts :data :pause-file)) sort first name (str "--"))
                                   (first (filter #(str/starts-with? % "-") names)))]
                 (fail! (str "relay undo has no option " flag ". " usage)))
               (let [only (when (seq names)
                            (let [c (or (core/move-call (state) id) (fail! (str "There is no move " id ". relay moves lists them.")))
                                  {:keys [only error]} (moves/steps-named c names)]
                              (or only (fail! error))))
                     {:keys [ok refused]} (moves/undo! sys id only)]
                 (if ok (lines ok) (apply result 1 refused))))
      "poll" (let [said (ingest-run/poll-now! sys (first more))]
               (dispatch/announce-github! sys)
               ;; A source it could not check fails the command, so a
               ;; script can tell.
               (apply result (if (some #(str/includes? % ": could not check it: ") said) 1 0) said))
      "note" (note! sys more)
      "changes" (let [st (state)
                      notes (filter #(= "daily-note" (:origin %)) (vals (:messages st)))]
                  (lines (digest/changes-lines (core/recipe-changes st) (or (:reverted st) #{})
                                               (digest/note-numbers notes) (:id (core/latest-note st)) zone
                                               (or (:tail opts) 30))))
      ;; An item's number in the daily note, the one a reply and the client
      ;; use: the latest note's, or the note named after it.
      ("dismiss" "decline")
      (let [[n note] more
            k (some-> n parse-long)
            m (some-> note parse-long)]
        (when-not k (fail! (str "usage: relay " cmd " <item> [<note>], an item's number in the daily note")))
        (submit! sys :digest/act (cond-> {:n k :op cmd} m (assoc :message m)))
        (let [st (state)
              msg (if m (core/message st m) (core/latest-note st))]
          (result 0 (text/visible-line (core/acted-line st msg k)))))
      ;; The same, or the handle relay changes gives a change no note
      ;; numbered (digest/revert-target).
      "revert"
      (let [{:keys [error change n message]} (digest/revert-target (core/recipe-changes (state)) more)]
        (when error (fail! error))
        (if change
          (do (submit! sys :change/revert {:change change})
              (result 0 (text/visible-line (digest/undone-line (core/recipe-change-by-seq (state) change)))))
          (do (submit! sys :digest/act (cond-> {:n n :op "revert"} message (assoc :message message)))
              (let [st (state)
                    msg (if message (core/message st message) (core/latest-note st))]
                (result 0 (text/visible-line (core/acted-line st msg n)))))))
      "connect" (let [[n url] more
                      config (:config sys)]
                  (when-not (and n url)
                    (fail! "usage: relay connect <name> <url> [--client-id <id>] [--agentloop <bin>]"))
                  (let [{:keys [say ask]} (*terminal*)
                        ;; The server writes the tool names and descriptions shown.
                        say (fn [text] (say (str/join "\n" (map text/visible-line (str/split text #"\n" -1)))))]
                    (apply result 0
                           (connect/connect!
                            {:name n :url url :data-dir (str (fs/absolutize dir)) :relay-bin (dispatch/relay-bin)
                             :agentloop (or (:agentloop opts)
                                            (agentloop/agentloop-bin (get-in config [:harnesses (:default-harness config)])))
                             :client-id (:client-id opts)
                             :taken (set (keep (fn [[t spec]] (when-not (= n (:connection spec)) t)) (:tools config)))
                             :say say :ask ask
                             :pin! (fn [pins] (submit! sys :tools/pin {:pins pins}))}))))
      (apply result 1 (str "relay has no command " cmd ".") "" (str/split-lines short-usage)))))

(def ^:private last-messages-file
  "Where the tick keeps the :messages of the last relay.edn that loaded,
  so it can still say relay has stopped when relay.edn cannot be read."
  ".messages-last-loaded.edn")

(defn- keep-loaded-messages!
  "Keeps the :messages of a relay.edn that loaded, when they changed.
  Best effort: it never throws."
  [dir config]
  (try
    (let [f (str (fs/path dir last-messages-file))
          text (pr-str (:messages config))]
      (when-not (and (fs/exists? f) (= text (slurp f)))
        (spit f text)))
    (catch Exception e
      (binding [*out* *err*] (println (str "relay tick: could not keep the message settings: " (ex-message e)))))))

(defn- usable-messages
  "The :messages to tell the operator through when relay.edn cannot be
  loaded: its own when that section alone is sound, else those of the
  last relay.edn that loaded, else the client alone."
  [dir]
  (let [sound (fn [read]
                (try (let [m (read)] (when (map? m) (config/check-messages m) m))
                     (catch Exception _ nil)))]
    (or (sound #(let [raw (edn/read-string (slurp (str (fs/path dir "relay.edn"))))]
                  (when (map? raw) (or (:messages raw) messages/default-settings))))
        (sound #(edn/read-string (slurp (str (fs/path dir last-messages-file)))))
        messages/default-settings)))

(defn- report-broken-settings!
  "When the tick cannot load relay.edn, nothing else runs, so it says so:
  on stderr, which launchd keeps, and once per distinct problem as an
  alert through the channels :messages routes one to: relay.edn's own
  when that section alone is sound, else those of the last relay.edn
  that loaded, so a file relay cannot read at all is told too. The log
  may be what is wrong, so the alert is not recorded there. Best effort:
  it never throws."
  [dir problem]
  (binding [*out* *err*] (println (str "relay tick: relay.edn cannot be used: " problem)))
  (try
    (let [marker (fs/path dir ".settings-problem")
          settings (usable-messages dir)]
      (when-not (and (fs/exists? marker) (= problem (slurp (str marker))))
        (let [now (java.time.Instant/now)
              msg (assoc (messages/message {:kind "alert" :urgency "high"
                                            :subject "relay has stopped: relay.edn cannot be used"
                                            :sections [{:items [problem "Nothing runs until it is fixed."]}]})
                         :id 0 :created-at now :labels [])]
          ;; High urgency: quiet hours do not hold it, and no zone is needed.
          (doseq [{:keys [channel]} (messages/route settings nil msg now)]
            (inbound/send! (get-in settings [:channels channel]) [msg] (constantly nil))))
        (spit (str marker) problem)))
    (catch Exception e
      (binding [*out* *err*] (println (str "relay tick: could not tell the operator: " (ex-message e)))))))

(defn- parse-line [args]
  ;; Options may come before the command (--data) and after it, and
  ;; babashka.cli stops at the first argument, so parse in two passes.
  (let [{global :opts rest-args :args} (cli/parse-args args spec)
        {[cmd & more] :args local-opts :opts} (cli/parse-args rest-args spec)]
    ;; Bare `relay` opens the dashboard (relay#75). No command and a help
    ;; flag stays nil, which `execute` answers with the usage; so does
    ;; `relay help`. The dashboard's command is a keyword, so no word typed
    ;; names it, and the scenario runner, which allows only named
    ;; commands, refuses it.
    (let [opts (merge global local-opts)]
      {:cmd (cond cmd cmd
                  (or (:help opts) (:h opts)) nil
                  :else :dashboard)
       :more more :opts opts})))

(defn parsed-options
  "The options relay would read from these arguments, as a map. Whatever
  spelling picked them (--x v, -x v, :x v, --x=v, --no-x), they are here."
  [args]
  (:opts (parse-line args)))

(defn command-of
  "The command name in a command line, as `execute` finds it, or nil."
  [args]
  (:cmd (parse-line args)))

(defn effective-data-dir
  "The data directory `execute` would use for these arguments, exactly as it
  would choose it, including a --data after the command, which wins over
  one before it. Throws when there is none."
  [args]
  (data-dir (:opts (parse-line args))))

(defn- run-command
  "Runs a command with its data directory's system, and returns what it
  says, made safe for a terminal."
  [cmd more opts]
  (do (when (and (= "tool" cmd) (or (str/blank? (:role opts)) (str/blank? (:data opts))))
        (fail! "usage: relay tool --data <dir> --role <role> <tool name>"))
      ;; babashka.cli takes any flag, so one relay no longer has would
      ;; pass unnoticed: what the operator writes is theirs.
      (when (contains? opts :untrusted)
        (fail! "relay has no --untrusted: what you write or paste is yours, and relay treats it so."))
      (let [dir (data-dir opts)
            reports (atom [])
            result (case cmd
                     "init" (init! dir)
                     ;; Needs only the connection file and the keychain, so a
                     ;; relay.edn broken elsewhere does not stop a server's token.
                     "mcp-token" (result 0 (connect/token! dir (first more) (:url opts)))
                     (let [sys (try (inbound/system dir (cond-> {} *clock* (assoc :clock *clock*)))
                                    (catch clojure.lang.ExceptionInfo e
                                      (when (= "tick" cmd) (report-broken-settings! dir (ex-message e)))
                                      (throw e)))]
                       (when (= "tick" cmd)
                         (fs/delete-if-exists (fs/path dir ".settings-problem"))
                         (keep-loaded-messages! dir (:config sys)))
                       (binding [*public* (true? (:public opts))
                                 *reports* reports]
                         (command sys dir cmd more opts))))
            told (text/report-lines (apply text/merge-reports text/empty-report @reports))
            result (cond-> result (seq told) (update :err (fnil into []) told))]
        ;; Everything printed may hold text a role wrote, so nothing
        ;; that could drive the terminal is printed raw. relay tool's
        ;; output is a JSON envelope for the harness, not a terminal.
        (if (or (= "tool" cmd) (and (#{"log" "week"} cmd) (:json opts)))
          ;; JSON escaped by its encoder, not by visible, which would
          ;; break it.
          result
          (cond-> result
            (:out result) (update :out #(mapv text/visible-line %))
            (:err result) (update :err #(mapv text/visible-line %)))))))

(defn command-help
  "The lines of `relay help all` about one command: each entry whose
  line starts `relay <cmd>`, with the lines that continue it. Empty when
  relay has no such command. Pure."
  [cmd]
  (let [entries (reduce (fn [acc line]
                          (cond (re-find #"^  relay " line) (conj acc [line])
                                (and (seq acc) (re-find #"^\s{3,}\S" line)) (conj (pop acc) (conj (peek acc) line))
                                :else (conj acc nil)))
                        [] (str/split-lines usage))
        head (str "  relay " cmd)]
    (vec (mapcat identity (filter #(and % (let [l (first %)]
                                            (and (str/starts-with? l head)
                                                 (or (= (count l) (count head))
                                                     (#{\space \tab} (nth l (count head)))))))
                                  entries)))))

(defn execute
  "Runs one command line. Returns {:exit :out}, :out being the lines to
  print. Prints nothing itself, except what a command that talks with the
  operator directly (init, chat, acp) prints along the way."
  [args]
  (let [{:keys [cmd more opts]} (parse-line args)]
    (cond
      (and (= "help" cmd) (= "all" (first more)))
      (apply result 0 (str/split-lines usage))
      (or (nil? cmd) (#{"help" "-h" "--help"} cmd))
      (apply result 0 (str/split-lines short-usage))
      ;; A command's own help is printed before anything is loaded: no
      ;; config file, no data directory, so asking never touches them.
      (and (string? cmd) (or (:help opts) (:h opts)))
      (let [lines (command-help cmd)]
        (if (seq lines)
          (apply result 0 lines)
          (apply result 1 (str "relay has no command " cmd ".") "" (str/split-lines short-usage))))
      ;; A tick marks itself as running before it looks for the pause file,
      ;; and a paused tick touches nothing else (see relay.pause).
      (= "tick" cmd)
      (pause/while-ticking
       (pause-file opts)
       #(if-let [why (pause/reason (pause-file opts))]
          (result 0 (text/visible-line (str "relay tick: paused (" (pause-file opts) "): " why)))
          (run-command cmd more opts)))
      :else
      (run-command cmd more opts))))

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
                    (binding [*out* *err*] (println (text/visible-line (ex-message e))))
                    (if (or (core/rejection e) (:relay/usage (ex-data e))) 1 2)))]
    (System/exit code)))
