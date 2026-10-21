(ns relay.cli
  "The relay command."
  (:require [babashka.cli :as cli]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [relay.cards :as cards]
            [relay.config :as config]
            [relay.connect :as connect]
            [relay.core :as core]
            [cheshire.core :as json]
            [relay.dispatch :as dispatch]
            [relay.export :as export]
            [relay.harness.agentloop :as agentloop]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.notify :as notify]
            [relay.pause :as pause]
            [relay.render :as render]
            [relay.reports :as reports]
            [relay.roster :as roster]
            [relay.serve :as serve]
            [relay.setup :as setup]
            [relay.text :as text]
            [relay.tools :as tools]
            [relay.traces :as traces]
            [relay.week :as week]))

(def usage
  "relay: a relay of agent roles, with an append-only log as the record.

Every command needs the data directory, from --data <dir> or from the file
~/.config/relay/config.edn, which holds a map such as {:data \"~/relay-data\"}.

  relay init                          make the data directory, asking for its settings
  relay status                        roles, open work, harnesses, recent wakes
  relay inbox                         memos waiting for the operator
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
  relay unroutine <role> <name>       remove a routine
  relay retry <role>                  requeue a role's dead-lettered memos
  relay wake <role> [--note <text>]   run a role now, ignoring retry delays
  relay chat [<role>]                 talk with the roles in this terminal, through
                                      the chat client (acp-tui unless relay.edn
                                      names another with :chat-client), with
                                      <role> in focus
  relay acp [<role>] [--trace <file>] [--approvals] [--socket <path>]
                                      talk with the roles from an ACP client, which
                                      runs this as its agent command: through
                                      relay serve when it runs, else in this
                                      process; <role> is the role a session is
                                      for when the client does not say; --trace
                                      appends the traffic to <file> as JSON lines;
                                      --approvals lets the client answer held tool
                                      calls, which relay otherwise refuses
  relay serve [--socket <path>]       run the service that keeps conversations
                                      going while no client is attached (launchd
                                      runs this; see setup/)
  relay tick [--pause-file <f>]       one dispatcher pass (launchd runs this); does
                                      nothing while the pause file exists, which
                                      bb deploy makes
  relay verify <report>               check a research report now (the tick does this
                                      by itself)
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
  relay cards [--archived]            the operator's board: the active cards, and
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
  relay holds                         tool calls waiting for you to decide, in full
  relay approve <hold>                let a held tool call run
  relay deny <hold> [why] [--end]     refuse a held tool call; --end also starts the
                                      role's next wake afresh instead of in that session
  relay fresh <role>                  end the session a role's next wake would carry on,
                                      so it starts afresh; this is how you accept an MCP
                                      tool whose definition changed under the session
  relay release <memo>                deliver a quarantined memo to its role
  relay declassify <memo>             make a memo to a public role public, so the
                                      role is shown it
  relay trust <kind> <ref>            vouch for contaminated text you have read:
                                      memo <n>, fact <n>, doc <path>, note <role>/<name>,
                                      routine <role>/<name>, role <id> or journal <n>
  relay review [<report>]             the research reports waiting for you, or go
                                      through one report's findings: accept each,
                                      reject it as fake, or set it aside
  relay trust-level <role> <0|1>      how far to trust a research role's reports: at 1,
                                      findings nothing flags go to the ledger unread
  relay ledger                        the findings accepted from research reports
  relay ledger set <entry> <status>   mark an entry new, seen, applied or passed
  relay week [--since <date>] [--until <date>] [--json]
                                      how often relay asked you for something and how
                                      long it waited, what each role's wakes did and
                                      cost, wakes that look wrong, and your flags;
                                      the last 7 days unless dates (YYYY-MM-DD) say
  relay flag <wake> good|bad [note]   mark a wake or conversation (its id from relay
                                      status or relay week) to find it later
  relay traces --out <dir> [--since <date>] [--until <date>] [--agentloop <bin>]
                                      write the wakes and conversations as OpenTelemetry
                                      traces to <dir>/traces.otlp.jsonl, offline; they
                                      hold no content
  relay connect <name> <url> [--client-id <id>] [--agentloop <bin>]
                                      sign relay in to a remote MCP server, choose
                                      what each of its tools may do, and write
                                      connections/<name>.edn; list <name> in
                                      relay.edn's :connections to load it
  relay mcp-token <name> --url <url>  print an access token for a connection to
                                      that server, renewing it when needed
                                      (agentloop runs this)
  relay tool --data <dir> --role <r> [--wake <w>] <name>
                                      run a role's tool call (harnesses use this)

approve, deny, fresh, release, declassify, trust, trust-level, review,
connect and revert show you what they are about on the terminal and ask you to type a code
back there, so only a person at the terminal can make those decisions.

--untrusted on memo, put, add, update and routine marks what you write as
contaminated, for text you copied from somewhere you do not trust.

--public on memo, routine, fact, put, add, update and wake --note marks what
you write as public: it may leave this machine, since a public role can put
it in a web search. A public role is shown only public text, so anything you
write for one needs it. It goes with --untrusted. On add and update it marks
the role's text public: a public role's own id, title and instructions must
be, and it sees only roles whose text is. update --public with both --title
and --instructions marks the id public too.")

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
                      :say println
                      :webtool (setup/webtool-bin)}
                     file)
      (ready)
      :else (result 1))))

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

(def ^:dynamic *untrusted*
  "Labels for what the operator writes: contaminated with --untrusted."
  [])

(def ^:dynamic *public*
  "Whether what the operator writes is public: --public."
  false)

(defn- submit! [sys type args]
  (inbound/submit! sys {:type type :actor core/operator :args args :labels *untrusted* :public *public*}))

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
                   (render/doc v))
            :doc-history (let [vs (get-in state [:docs (:path args) :versions])]
                           (when (empty? vs) (fail! (str "There is no doc at " (:path args) ".")))
                           (render/doc-history zone (:path args) vs)))}))

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

(defn- tty!
  "The operator's terminal (*tty*), or a refusal saying `verb` needs one."
  [verb]
  (try (*tty*)
       (catch Exception _
         (fail! (str "relay " verb " asks you to confirm on a terminal, and there is none.")))))

(defn- confirm!
  "Shows `lines` on the terminal and asks for a fresh code to be typed back.
  Throws, changing nothing, when there is no terminal or the code is wrong.
  `tty` is a terminal already open for the same decision."
  [verb lines & [tty]]
  (let [{:keys [say ask]} (or tty (tty! verb))
        code (confirmation-code)]
    (say (str (str/join "\n" (map text/visible-line lines)) "\n\nType " code " to " verb ": "))
    (when-not (= code (some-> (ask) str/trim str/upper-case))
      (fail! "That is not the code. Nothing was changed."))))

(defn- labels-line [labels]
  (str "Labels: " (if (seq labels) (str/join ", " labels) "none")))

(defn- decide-hold! [sys verb verdict hold-id reason & [end]]
  (let [st (inbound/current-state sys)
        h (core/hold st hold-id)]
    (when-not h (fail! (str "There is no hold " hold-id ".")))
    ;; Asking for a code to do nothing would only teach typing codes.
    (when-not (= :open (:status h))
      (fail! (str "Hold " hold-id " was already decided: " (name (:status h)) ".")))
    (confirm! verb (concat (render/hold h (:config sys) (core/approved-hosts st))
                           (when end ["" "The session will not be carried on: the role's next wake starts afresh."])
                           ;; The role is shown the reason, and may send it out.
                           (when (and (= "deny" verdict) (core/public-role? (:config sys) (:role h)))
                             ["" (str (:role h) " is a public role: your reason is shown to it as public, and"
                                      " may leave this machine in a web search.")])))
    (submit! sys :hold/resolve {:hold hold-id :verdict verdict :reason reason :end (boolean end)})
    (result 0 (str (if (= "allow" verdict) "Approved" "Denied") " hold " hold-id ". "
                   (:role h) " carries on at the next tick."))))

;; --- the board

(defn- operator-card! [sys type args]
  ;; Whatever the operator writes on a card is the operator's: no labels,
  ;; never public, whatever --untrusted or --public say.
  (inbound/submit! sys {:type type :actor core/operator :args args :labels [] :public false}))

(def ^:private card-options
  "What relay card reads, and the options every command may be given."
  #{:item :row :column :state :text :seen :title :data :untrusted :public})

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

(def ^:private review-answers
  {"a" "accept" "r" "fake" "n" "not-relevant" "s" nil})

(defn- review!
  "Goes through a report's waiting findings with the operator on the
  terminal, one at a time, then asks for the code to record what they
  decided. Each decision is bound to the digest of the finding as shown."
  [sys id]
  (let [state (inbound/current-state sys)
        zone (config/zone (:config sys))
        r (core/report state id)
        pending (when r (core/pending-findings r))]
    (when-not (seq pending)
      (fail! (str "Report " id " has nothing waiting for you. relay review lists those that do.")))
    (let [{:keys [say ask] :as tty} (tty! "review")
          show (fn [lines] (say (str (str/join "\n" (map text/visible-line lines)) "\n")))
          _ (show (concat (render/report-header zone r) [""]))
          decisions (vec (for [i pending
                               :let [_ (show (concat (render/finding r i) [""]))
                                     answer (loop []
                                              (say "[a]ccept / [r]eject as fake / [n]ot relevant / [s]kip: ")
                                              (let [a (some-> (ask) str/trim str/lower-case)]
                                                (cond (nil? a) (fail! "The terminal closed. Nothing was decided.")
                                                      (contains? review-answers a) (review-answers a)
                                                      :else (recur))))]
                               :when answer]
                           {:finding i :decision answer :sha256 (core/finding-digest r i)}))
          by (group-by :decision decisions)
          listed (fn [d] (if-let [ds (seq (get by d))] (str/join ", " (map #(inc (:finding %)) ds)) "none"))]
      (if (empty? decisions)
        (result 0 "Nothing was decided.")
        (do (confirm! "record these decisions"
                      [(str "Report " id ":")
                       (str "  accept: " (listed "accept"))
                       (str "  reject as fake: " (listed "fake"))
                       (str "  not relevant: " (listed "not-relevant"))
                       (str "  skipped: " (let [s (remove (set (map :finding decisions)) pending)]
                                            (if (seq s) (str/join ", " (map inc s)) "none")))
                       ""
                       "Accepting puts a finding in the ledger as yours: its text no longer counts as an outsider's."]
                      tty)
            (let [before (core/waiting-findings (inbound/current-state sys))
                  _ (submit! sys :report/review {:report id :decisions decisions})
                  again (core/shown-again before (inbound/current-state sys))]
              (apply result 0 (str "Recorded " (count decisions) " decision(s) on report " id ".")
                     (when (seq again)
                       [(str "Shown again, as they match what you rejected as fake: "
                             (str/join ", " (for [[r i] again] (str "finding " (inc i) " of report " r)))
                             ". relay review lists them.")]))))))))

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
            :version :long :wake :long :role :string :trace :string :untrusted :boolean :public :boolean :approvals :boolean
            :topic :string :from :string :to :string :why :string :text :string :end :boolean
            :pause-file :string :socket :string :since :string :until :string :out :string :agentloop :string
            :client-id :string :url :string
            :item [:string] :row [:string] :column [:string] :state :string :seen :long :archived :boolean}})

(defn- command
  "Runs one command with the data directory's system. Returns {:exit :out},
  :out being the lines to print."
  [sys dir cmd more opts]
  (let [zone (config/zone (:config sys))
        state #(inbound/current-state sys)
        lines (fn [ls] {:exit 0 :out ls})]
    (case cmd
      "status" (lines (render/status (state) zone ((:clock sys)) (:config sys) (pause/recovery-lines (pause-file opts))))
      "inbox" (lines (render/inbox zone (core/unread-memos (state) core/operator)))
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
                   woke (dispatch/wake! sys r {:note (:note opts) :dispatched (:dispatched opts)
                                               :public (:public opts)})]
               {:exit (if (#{:ran :idle} (:result woke)) 0 1) :out (render/wake-result woke)})
      "chat" (let [[r] more]
               (result (dispatch/chat! sys r {:data (when (:data opts) (str (fs/absolutize dir)))})))
      "acp" (let [[r] more]
              (result (serve/acp! sys r {:trace (:trace opts) :approvals (:approvals opts) :socket (:socket opts)})))
      "serve" (result (serve/serve! sys {:socket (:socket opts)}))
      "tick" (do (dispatch/tick! sys) (result 0))
      "verify" (let [id (some-> (first more) parse-long)]
                 (when-not id (fail! "usage: relay verify <report>"))
                 (let [{r :result :keys [message]} (dispatch/verify! sys id {:by-hand (not (:dispatched opts))})]
                   (if (= :verified r)
                     (result 0 (str "Checked report " id "."))
                     (result 1 (str "Report " id " was not checked: " message)))))
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
                            (confirm! "revert" (concat [(str "Fact " id " back to version " version ":" (when (:topic v) (str " [" (:topic v) "]")))]
                                                       (text/block (:labels v) (:text v))
                                                       [(str "holds from " (or (:valid-from v) "the start") " until "
                                                             (or (:valid-to v) "further notice"))
                                                        "" (labels-line (:labels v))]))
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
                  (confirm! "revert" (concat [(str a " back to version " version ":")]
                                             (if (:removed v) ["(removed)"] (text/block (:labels v) (:content v)))
                                             ["" (labels-line (:labels v))]))
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
      "holds" (let [st (state)] (lines (render/holds (core/open-holds st) (:config sys) (core/approved-hosts st))))
      "approve" (let [[h] more]
                  (decide-hold! sys "approve" "allow" (some-> h parse-long) nil))
      "deny" (let [[h & why] more]
               (decide-hold! sys "deny" "deny" (some-> h parse-long)
                             (or (:message opts) (when (seq why) (str/join " " why)))
                             (:end opts)))
      "fresh" (let [[r] more
                    st (state)]
                (when-not r (fail! "usage: relay fresh <role>"))
                (when-not (core/active-role? st r) (fail! (str r " is not an active role.")))
                (when-not (core/session-to-carry-on st r)
                  (fail! (str r " has no session to end: its next wake starts afresh.")))
                ;; What is shown is what is sent: core refuses if it has
                ;; changed by the time the code is typed.
                (let [{:keys [tool got] :as expect} (core/fresh-expectation st r)
                      others (core/roles-released-by-fresh st r)
                      dropped (fn [role]
                                (for [h (core/holds-dropped-by-fresh st role)]
                                  (str "  " role " hold " (:id h) ": " (:tool h) " ("
                                       (if (= :open (:status h)) "open, will be denied" "approved, not yet run")
                                       ")")))]
                  (confirm! "start afresh"
                            (concat
                             [(str "End the session " r "'s next wake would carry on. The next wake starts a"
                                   " new session, with " r "'s open memos.")
                              (if tool
                                (str "This accepts the definition of the MCP tool " tool " that agentloop saw"
                                     " when the wake was stopped (sha256 " got "), for every role granted it.")
                                "No tool definition changes.")]
                             (when (seq others)
                               [(str "The same change stopped " (str/join ", " others) ": their sessions end"
                                     " too, and their next wakes start afresh.")])
                             (when-let [ls (seq (mapcat dropped (cons r others)))]
                               (cons "These held calls will never run:" ls))))
                  (submit! sys :role/fresh {:role r :expect expect})
                  (result 0 (str r "'s next wake starts afresh."))))
      "release" (let [id (some-> (first more) parse-long)
                      m (core/memo (state) id)]
                  (when-not (and m (core/quarantined? (:config sys) m)) (fail! (str "Memo " (first more) " is not quarantined.")))
                  (confirm! "release" (concat (render/memo zone m) ["" (labels-line (:labels m))
                                                                    (str "Releasing it shows it to " (:to m)
                                                                         ", which then counts as contaminated.")]))
                  (submit! sys :memo/release {:memo id})
                  (result 0 (str "Released memo " id " to " (:to m) ".")))
      "declassify" (let [id (some-> (first more) parse-long)
                         m (core/memo (state) id)]
                     (when-not (and m (= :unread (:status m)) (core/awaiting-declassification? (:config sys) m))
                       (fail! (str "Memo " (first more) " is not waiting to be declassified.")))
                     ;; The role gets exactly what the operator is shown: the
                     ;; memo as declassifying makes it (defanged if
                     ;; contaminated, spacing plain, NFC), with only the
                     ;; [contaminated] marker added here.
                     (confirm! "declassify"
                               (concat (render/declassify zone (core/declassified m))
                                       ;; As for a held call: what could pass
                                       ;; for something else, or hide.
                                       (when-let [odd (seq (let [d (core/declassified m)] (text/lookalikes (str (:subject d) " " (:body d)))))]
                                         (cons "" (cons "Look closely, it has characters that can pass for others or hide text:"
                                                        (map #(str "  " %) odd))))
                                       ["" (labels-line (:labels m))
                                        (str "Declassifying it shows it to " (:to m) ", a public role, exactly as it"
                                             " is shown here. Say so only if nothing in it, its sender"
                                             " included, is private, not even in its choice of words: it may leave"
                                             " this machine in a web search.")]))
                     (binding [*public* false]
                       (submit! sys :memo/declassify {:memo id}))
                     (result 0 (str "Declassified memo " id "; " (:to m) " is shown it at its next wake.")))
      "trust" (let [[kind ref] more
                    v (core/trusted-version (state) kind ref)]
                (when-not v (fail! "usage: relay trust <kind> <ref>, naming something relay keeps"))
                ;; Shown with its URLs broken, like anything contaminated;
                ;; only what is shown is changed, not what is trusted.
                (confirm! "trust" (concat [(str kind " " ref ":")]
                                          (text/block ["contaminated"] (:text v))
                                          ["" (labels-line (:labels v))
                                           "Trusting it clears contaminated from what it says now."]))
                ;; What was shown, so a change while the operator read it
                ;; trusts nothing.
                (submit! sys :label/trust {:kind kind :ref ref :version (:version v)
                                           :sha256 (core/sha256 (:text v))})
                (result 0 (str "Trusted " kind " " ref ".")))
      "trust-level" (let [[role level] more
                          level (some-> level parse-long)
                          st (state)]
                      (when-not (and role (#{0 1} level))
                        (fail! "usage: relay trust-level <role> <0|1>"))
                      ;; Refused before the code is asked for: asking for a
                      ;; code to do nothing would only teach typing codes.
                      (when-not (core/active-role? st role) (fail! (str "There is no role called " role ".")))
                      (when-not (config/report-kinds (:config sys) role)
                        (fail! (str role " writes no reports, so it has no trust level.")))
                      (when (= level (core/trust-level st role))
                        (fail! (str role " is at trust level " level " already.")))
                      (let [{:keys [reports findings accepted fake latest-report]} (core/research-record st role)
                            digest (core/record-digest st role)]
                        (confirm! "set the trust level"
                                  [(str "Set " role "'s trust level to " level ", from " (core/trust-level st role) ".")
                                   (str "So far: " reports " reports (the latest " latest-report "), " findings
                                        " findings, " accepted " accepted, " fake " rejected as fake.")
                                   ""
                                   (if (= 1 level)
                                     (str "At 1, a finding nothing flags, and not drawn for the one-in-"
                                          reports/sample-one-in " sample, goes into the ledger without you reading it."
                                          " Its kind, deadline and amount show as clean; its text stays contaminated."
                                          " relay puts it back to 0 by itself if a quote is not on its page, the"
                                          " verifier refuses a page, or you reject a finding as fake.")
                                     "At 0, you review every finding not seen before.")])
                        (submit! sys :role/trust-level {:role role :level level :sha256 digest}))
                      (result 0 (str role " is at trust level " level ".")))
      "ledger" (let [[sub entry status] more]
                 (case sub
                   nil (lines (render/ledger zone (core/ledger-entries (state))))
                   "set" (do (submit! sys :ledger/set-status {:entry (some-> entry parse-long) :status status})
                             (result 0 (str "Entry " entry " is " status ".")))
                   (fail! "usage: relay ledger [set <entry> <new|seen|applied|passed>]")))
      "review" (if-let [id (some-> (first more) parse-long)]
                 (review! sys id)
                 (lines (render/reports-waiting zone (core/reports-waiting (state)))))
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
      "connect" (let [[n url] more
                      config (:config sys)]
                  (when-not (and n url)
                    (fail! "usage: relay connect <name> <url> [--client-id <id>] [--agentloop <bin>]"))
                  (let [{:keys [say ask] :as tty} (tty! "connect")
                        ;; The server writes the tool names and descriptions shown.
                        say (fn [text] (say (str/join "\n" (map text/visible-line (str/split text #"\n" -1)))))]
                    (apply result 0
                           (connect/connect!
                            {:name n :url url :data-dir (str (fs/absolutize dir)) :relay-bin (dispatch/relay-bin)
                             :agentloop (or (:agentloop opts)
                                            (agentloop/agentloop-bin (get-in config [:harnesses (:default-harness config)])))
                             :classes config/tool-classes :client-id (:client-id opts)
                             :taken (set (keep (fn [[t spec]] (when-not (= n (:connection spec)) t)) (:tools config)))
                             :others (into (set (keys tools/builtin))
                                           (keep (fn [[t spec]] (when-not (= n (:connection spec)) t)) (:tools config)))
                             :say say :ask ask
                             :confirm! (fn [lines] (confirm! "connect" lines tty))
                             :pin! (fn [pins] (submit! sys :tools/pin {:pins pins}))}))))
      (apply result 1 (str/split-lines usage)))))

(defn- report-broken-settings!
  "When the tick cannot load relay.edn, nothing else runs, so it says so:
  on stderr, which launchd keeps, and once per distinct problem through
  :notify, if that section alone is sound. Best effort: it never throws."
  [dir problem]
  (binding [*out* *err*] (println (str "relay tick: relay.edn cannot be used: " problem)))
  (try
    (let [marker (fs/path dir ".settings-problem")
          notify-settings (:notify (edn/read-string (slurp (str (fs/path dir "relay.edn")))))]
      (when-not (and (fs/exists? marker) (= problem (slurp (str marker))))
        (config/check-notify notify-settings)
        (notify/push! notify-settings {:title "relay has stopped: relay.edn cannot be used"
                                       :body (str problem "\n\nNo role is woken until it is fixed.")
                                       :labels []})
        (spit (str marker) problem)))
    (catch Exception e
      (binding [*out* *err*] (println (str "relay tick: could not tell the operator: " (ex-message e)))))))

(defn- parse-line [args]
  ;; Options may come before the command (--data) and after it, and
  ;; babashka.cli stops at the first argument, so parse in two passes.
  (let [{global :opts rest-args :args} (cli/parse-args args spec)
        {[cmd & more] :args local-opts :opts} (cli/parse-args rest-args spec)]
    {:cmd cmd :more more :opts (merge global local-opts)}))

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
      (let [dir (data-dir opts)
            result (case cmd
                     "init" (init! dir)
                     ;; Needs only the connection file and the keychain, so a
                     ;; relay.edn broken elsewhere does not stop a server's token.
                     "mcp-token" (result 0 (connect/token! dir (first more) (:url opts)))
                     (let [sys (try (inbound/system dir (cond-> {} *clock* (assoc :clock *clock*)))
                                    (catch clojure.lang.ExceptionInfo e
                                      (when (= "tick" cmd) (report-broken-settings! dir (ex-message e)))
                                      (throw e)))]
                       (when (= "tick" cmd) (fs/delete-if-exists (fs/path dir ".settings-problem")))
                       (binding [*untrusted* (if (:untrusted opts) [core/contaminated-label] [])
                                 *public* (true? (:public opts))]
                         (command sys dir cmd more opts))))]
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

(defn execute
  "Runs one command line. Returns {:exit :out}, :out being the lines to
  print. Prints nothing itself, except what a command that talks with the
  operator directly (init, chat, acp) prints along the way."
  [args]
  (let [{:keys [cmd more opts]} (parse-line args)]
    (cond
      (or (nil? cmd) (#{"help" "-h" "--help"} cmd))
      (apply result 0 (str/split-lines usage))
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
