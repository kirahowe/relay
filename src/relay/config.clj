(ns relay.config
  "The operator's settings, from relay.edn at the root of the data directory.
  Settings are not data: they say how relay runs and what each role is
  granted, and only the operator edits them. No role has a tool that can
  read or write the file."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [relay.connections :as connections])
  (:import (java.time ZoneId)))

(def edit-class
  "The class of a tool that changes the operator's own things outside
  relay: nobody else sees the change, and the earlier state survives, so
  it can be undone (their own reminders, a draft in their mail). A tool
  that overwrites or deletes with no history kept is :act. relay cannot
  follow labels outside itself: injected text a contaminated session wrote
  into the operator's reminders would be read back labelled only private.
  So a call is held once the session is contaminated."
  :edit)

(def tool-classes
  "Every tool a role can be granted is named in config and given one of
  these classes, which agentloop's policy decides on:

    :read    returns content, changes nothing, sends nothing out that an
             outsider could see
    :write   changes relay data only, which keeps its labels
    :edit    changes the operator's own things outside relay (edit-class)
    :egress  can send data the model chose where an outsider could see it
    :act     others see it, it spends money, or it cannot be undone, such
             as sending a campaign or a payment

  A session that has seen something contaminated needs the operator's
  approval for an edit call, and one that has also seen private data for
  an egress call. Every act call needs it, whatever the session has seen,
  unless the tool's :consent covers the call. Read and write calls run."
  #{:read :write edit-class :egress :act})

(def label-names
  "The labels a tool's output can carry (relay.core/label-names). An
  external tool declares the ones its output always carries in :labels, as
  strings: \"contaminated\" for anything an outsider could have written,
  \"private\" for the operator's private data."
  #{"private" "contaminated"})

(def defaults
  "Settings relay can sensibly choose for itself. Everything relay needs
  and cannot guess, :timezone, :default-harness, :harnesses and :notify,
  is required in relay.edn; `relay init` asks for it."
  {:dead-letter-handler "hub"
   :wake-timeout-minutes 30
   ;; A memo a wake carries and leaves open is retried after a wait that
   ;; doubles from the base up to the ceiling, and dead-lettered once it
   ;; has failed max-attempts times.
   :max-attempts 8
   :retry-base-seconds 60
   :retry-ceiling-seconds 3600
   ;; A harness that hit its rate limit without saying when it resets is
   ;; tried again after this long.
   :limit-probe-minutes 15
   ;; How long a harness may stay unreachable before the operator is told.
   :outage-patience-minutes 120
   ;; A last resort against a role stuck in a loop, far above ordinary use.
   :runaway-wakes 30
   :runaway-window-minutes 60
   ;; The ACP client `relay chat` runs, given `-- relay acp <role>` after
   ;; these arguments.
   :chat-client ["acp-tui"]
   :tools {}
   :mcp-servers {}
   ;; The remote MCP servers relay connect set up, by name (relay.connections).
   :connections []
   :grants {}})

(def notify-kinds #{:ntfy :command :macos :file :none})

(defn- problem! [msg] (throw (ex-info msg {:relay/usage true})))

(defn check-notify
  "Checks the :notify settings alone, throwing on the first problem, so a
  relay.edn broken elsewhere can still say so to the operator."
  [notify]
  (let [{:keys [kind url topic command]} notify]
    (when-not (notify-kinds kind)
      (problem! (str "relay.edn needs :notify with a :kind, one of "
                     (sort (map name notify-kinds)) ", saying where notifications go.")))
    (when (and (= :ntfy kind) (not (and (string? url) (string? topic))))
      (problem! "The ntfy :notify in relay.edn needs a :url and a :topic."))
    (let [{:keys [token-command token-file]} notify]
      (when (and (some? token-command)
                 (not (and (sequential? token-command) (seq token-command) (every? string? token-command))))
        (problem! "The ntfy :token-command in relay.edn must be a command, such as [\"cat\" \"/path/to/token\"]."))
      (when (and (some? token-file) (not (and (string? token-file) (not (str/blank? token-file)))))
        (problem! "The ntfy :token-file in relay.edn must be a path."))
      (when (and token-command token-file)
        (problem! "The ntfy :notify in relay.edn takes :token-command or :token-file, not both.")))
    (when (and (= :command kind) (not (and (sequential? command) (seq command))))
      (problem! "The command :notify in relay.edn needs a :command."))
    ;; Absolute, so that where it writes never depends on whoever runs relay.
    (when (and (= :file kind) (not (and (string? (:path notify)) (fs/absolute? (:path notify)))))
      (problem! "The file :notify in relay.edn needs a :path, the absolute path of the file to append to."))))

(def efforts #{"low" "medium" "high" "xhigh" "max"})

(defn- check-reader
  "An agentloop harness's optional :reader: the model its quarantined
  reader runs on, {:model \"claude-haiku-5-5\" :effort \"low\"}, either
  or both."
  [h kind reader]
  (let [{:keys [model effort]} reader]
    (when-not (and (= :agentloop kind) (map? reader) (every? #{:model :effort} (keys reader))
                   (or (nil? model) (and (string? model) (not (str/blank? model))))
                   (or (nil? effort) (efforts effort)))
      (problem! (str "Harness " (name h) " in relay.edn has :reader " (pr-str reader) ". An :agentloop"
                     " harness may give {:model \"claude-haiku-5-5\" :effort \"low\"}, the quarantined"
                     " reader's model and effort, with effort one of " (str/join ", " (sort efforts)) ".")))))

(defn- check-required
  [{:keys [timezone default-harness harnesses notify]}]
  (when-not (and (string? timezone)
                 (try (ZoneId/of timezone) (catch Exception _ nil)))
    (problem! (str "relay.edn needs :timezone, a zone name such as \"America/Halifax\""
                   (when timezone (str "; " (pr-str timezone) " is not one")) ".")))
  (when-not (and (map? harnesses) (seq harnesses))
    (problem! "relay.edn needs :harnesses, saying how to run a model."))
  (doseq [[h {:keys [kind api-key-command] :as harness}] harnesses]
    (when-not kind
      (problem! (str "Harness " (name h) " in relay.edn needs a :kind.")))
    (when (contains? harness :reader)
      (check-reader h kind (:reader harness)))
    (when (and (= :agentloop kind)
               (not (and (sequential? api-key-command) (seq api-key-command))))
      (problem! (str "Harness " (name h) " in relay.edn needs :api-key-command, "
                     "the command that prints the API key."))))
  (when-not (contains? harnesses default-harness)
    (problem! (str "relay.edn needs :default-harness, naming one of :harnesses"
                   (when default-harness (str "; there is no harness " (pr-str default-harness))) ".")))
  (check-notify notify))

(def reserved-role-ids
  "Names no role may have: the operator, the dispatcher, and words relay
  uses for groups."
  #{"operator" "dispatcher" "relay" "all" "none"})

(defn role-id?
  "Whether a string can be a role's id: lower case letters, digits and
  hyphens, starting with a letter, at most 40 characters, and not reserved."
  [id]
  (boolean (and (string? id) (re-matches #"[a-z][a-z0-9-]{0,39}" id) (not (reserved-role-ids id)))))

(def grant-keys
  "What a role can be granted. Anything else is refused, so a typo such as
  :pubic cannot leave a role able to hold private data."
  #{:tools :may-add :public :reports :quarantine})

(def kind-pattern
  "A report kind's name: lower case letters, digits and hyphens."
  #"[a-z][a-z0-9-]{0,39}")

(defn- check-reports-grant
  "A :reports grant lets a role write research reports with report_submit.
  Only a public role may have one: a report is checked and shown to the
  operator as an outsider's text, which a public role's always is."
  [role {:keys [reports public]}]
  (when-not (true? public)
    (problem! (str "Role " role " has :reports, which only a :public role may have.")))
  (when-not (and (map? reports) (= #{:kinds} (set (keys reports))))
    (problem! (str "The :reports grant for " role " in relay.edn is {:kinds [\"job\" \"grant\"]}, naming"
                   " the kinds of finding it may report, and nothing else.")))
  (let [kinds (:kinds reports)]
    (when-not (and (vector? kinds) (seq kinds) (apply distinct? kinds)
                   (every? #(and (string? %) (re-matches kind-pattern %)) kinds))
      (problem! (str "The :reports :kinds for " role " in relay.edn must be a vector of distinct names of lower"
                     " case letters, digits and hyphens, such as [\"job\" \"grant\"].")))))

(defn- check-quarantine-grant
  "A :quarantine grant names tools the role may read through agentloop's
  quarantined reader, read_quarantined. Each must be one of the role's
  :tools, of class :read or :egress, and not :always-hold: the reader runs
  it and shows the role only a checked answer about its output. An
  :egress tool, such as webtool's ask, is decided as the role's own call
  would be, since the role writes its input: a session that has seen both
  private data and an outsider's text is held. :write and :act tools
  change things, which a reader must never do. An :always-hold tool is
  refused because every call would wait on the operator for a read."
  [role {:keys [quarantine] granted :tools} tools]
  (when-not (and (vector? quarantine) (seq quarantine) (apply distinct? quarantine)
                 (every? string? quarantine))
    (problem! (str "The :quarantine grant for " role " in relay.edn is a vector of distinct tool names,"
                   " such as [\"web_fetch\"].")))
  (doseq [t quarantine]
    (when-not (some #{t} granted)
      (problem! (str "Role " role " has " t " in :quarantine, which it is not granted in :tools."
                     " It may read only through its own tools.")))
    (when-not (#{:read :egress} (get-in tools [t :class]))
      (problem! (str "Role " role " has " t " in :quarantine, which is not a :read or :egress tool in :tools."
                     " The reader reads only through tools that change nothing.")))
    (when (true? (get-in tools [t :always-hold]))
      (problem! (str "Role " role " has " t " in :quarantine, which has :always-hold. Every read"
                     " through it would wait for you.")))))

(defn- check-verifier
  "The :verifier relay checks reports with: webtool's check, on the
  envelope protocol. Needed once any role may write reports."
  [{:keys [verifier grants]}]
  (when (some? verifier)
    (when-not (and (map? verifier) (every? #{:command :timeout-seconds} (keys verifier)))
      (problem! "relay.edn's :verifier is {:command [...] :timeout-seconds n}, and nothing else."))
    (let [{:keys [command timeout-seconds]} verifier]
      (when-not (and (vector? command) (seq command) (every? #(and (string? %) (not (str/blank? %))) command))
        (problem! (str "relay.edn's :verifier needs a :command, such as [\"/path/to/webtool\" \"--config\""
                       " \"/path/to/webtool.json\" \"check\"].")))
      (when-not (or (nil? timeout-seconds) (pos-int? timeout-seconds))
        (problem! "relay.edn's :verifier :timeout-seconds must be a positive whole number."))))
  (when (and (nil? verifier) (some #(contains? (val %) :reports) (when (map? grants) grants)))
    (problem! "A role in relay.edn is granted :reports, so relay.edn needs a :verifier to check its reports.")))

(defn- check-grants
  "Checks each role's grants, and what a public role may not have. A public
  role's session never holds private, so it is granted nothing whose
  output is private, nothing that adds or changes roles, and no dead
  letters, which quote other roles' memos."
  [{:keys [tools grants dead-letter-handler harnesses default-harness]}]
  (when-not (map? grants)
    (problem! "relay.edn has :grants that is not a map of role ids to grants."))
  (doseq [[role g] grants]
    ;; Roles are looked up by their id as a string, so a keyword or
    ;; symbol here would grant nothing, :public included.
    (when-not (role-id? role)
      (problem! (str "relay.edn grants to " (pr-str role) ", which is not a role id: a string of lower case"
                     " letters, digits and hyphens, starting with a letter, such as \"hub\", and not one of "
                     (str/join ", " (sort reserved-role-ids)) ".")))
    (when-not (map? g)
      (problem! (str "The grants for " role " in relay.edn are not a map, such as {:tools [\"fetch\"]}.")))
    (when-let [unknown (seq (remove grant-keys (keys g)))]
      (problem! (str "The grants for " role " in relay.edn name " (str/join ", " (map pr-str (sort-by str unknown)))
                     ", which relay does not know. A role can be granted "
                     (str/join ", " (sort (map str grant-keys))) ".")))
    (when-not (or (nil? (:public g)) (boolean? (:public g)))
      (problem! (str "Role " role " has :public " (pr-str (:public g)) " in relay.edn; it must be true or false.")))
    (when (:public g)
      (when (:may-add g)
        (problem! (str "Role " role " is :public, so it may not have :may-add: it could write"
                       " roles' instructions and read what other roles wrote.")))
      (doseq [t (:tools g)
              :when (some #{"private"} (get-in tools [t :labels]))]
        (problem! (str "Role " role " is :public, so it may not be granted " t
                       ", whose output is private.")))
      (when (= role dead-letter-handler)
        (problem! (str "Role " role " is :public, so it may not be the :dead-letter-handler:"
                       " dead letters quote other roles' private memos.")))
      (when (= :script (get-in harnesses [default-harness :kind]))
        (problem! (str "Role " role " is :public, and the default harness is a :script harness,"
                       " whose program can claim any labels. A public role needs agentloop."))))
    (when (contains? g :reports)
      (check-reports-grant role g))
    (when (contains? g :quarantine)
      (check-quarantine-grant role g tools))))

(defn- check-budget
  "The optional :budget, a weekly amount in US dollars. relay only warns
  when spend nears it, so a wrong one stops nothing; it is still checked,
  so a typo is not taken for no budget."
  [config]
  (when (contains? config :budget)
    (let [{:keys [weekly-usd] :as budget} (:budget config)]
      (when-not (and (map? budget) (= #{:weekly-usd} (set (keys budget)))
                     (number? weekly-usd) (not (ratio? weekly-usd))
                     (Double/isFinite (double weekly-usd)) (pos? weekly-usd))
        (problem! (str "relay.edn's :budget is {:weekly-usd 15}, a weekly amount in US dollars"
                       " greater than 0, and nothing else"
                       (when (some? budget) (str ", not " (pr-str budget))) "."))))))

(defn- check-brief
  "The optional :brief: {:at \"07:30\"}, the time each day, and the tools
  it reads, :calendar and :reminders, when they are not cal and rem-read.
  A tool named here must be a :read tool in :tools: the brief runs it with
  nobody there to approve a call."
  [{:keys [brief tools] :as config}]
  (when (contains? config :brief)
    (let [{:keys [at]} brief
          hhmm (when (string? at) (re-matches #"(\d{2}):(\d{2})" at))]
      (when-not (and (map? brief) (every? #{:at :calendar :reminders} (keys brief))
                     hhmm (<= 0 (parse-long (nth hhmm 1)) 23) (<= 0 (parse-long (nth hhmm 2)) 59))
        (problem! (str "relay.edn's :brief is {:at \"07:30\"}, a time of day as HH:MM, and may name"
                       " its :calendar and :reminders tools"
                       (when (some? brief) (str ", not " (pr-str brief))) ".")))
      (doseq [k [:calendar :reminders] :when (contains? brief k)
              :let [n (get brief k)]]
        (when-not (= :read (get-in tools [n :class]))
          (problem! (str "relay.edn's :brief names " (pr-str n) " as its " (name k) " tool, which"
                         " must be a :read tool in :tools.")))))))

;; --- MCP servers and their tools

(def credentials-placeholder
  "In an MCP server's :command, the path of the file that holds what its
  :credentials-command printed. agentloop runs that command, writes the
  file and fills in the path; relay never sees the secret."
  "{credentials_file}")

(def mcp-name
  "An MCP server's name, and an MCP tool's name in :tools: agentloop's
  tool names."
  #"[A-Za-z0-9_-]{1,64}")

(def mcp-content-kinds
  "What an MCP tool's :allow-content may name: the kinds of non-text
  content its results may hold. Any other fails the call."
  #{"image" "audio" "resource_link" "resource"})

(defn mcp-tool?
  "Whether a tool comes from an MCP server: one agentloop runs over stdio
  (:mcp-stdio) or a remote one it reaches over HTTP (:mcp-http)."
  [tool]
  (contains? #{:mcp-stdio :mcp-http} (:protocol tool)))

(defn- argv? [x]
  (and (vector? x) (seq x) (every? #(and (string? %) (not (str/includes? % "\u0000"))) x)
       (not (str/blank? (first x)))))

(defn- check-remote-server
  "A remote server, {:url ... :credentials-command [...]}: what relay
  connect makes. Its credentials command prints the bearer token."
  [where {:keys [url credentials-command send-labels] :as server}]
  (when-not (every? #{:url :credentials-command :send-labels} (keys server))
    (problem! (str where " is {:url ...}, and may give :credentials-command and :send-labels, and nothing else.")))
  (when-let [why (connections/url-problem url)]
    (problem! (str where " has :url " (pr-str url) ", which cannot be used: " why ".")))
  (when-not (or (nil? credentials-command) (argv? credentials-command))
    (problem! (str where " has a :credentials-command that is not a command.")))
  (when-not (or (nil? send-labels) (boolean? send-labels))
    (problem! (str where " has :send-labels " (pr-str send-labels) "; it must be true or false."))))

(defn- check-mcp-server
  "One entry of :mcp-servers, with agentloop's rules for it, so that relay
  refuses a bad one before agentloop would."
  [server-name {:keys [command credentials-command send-labels] :as server}]
  (let [where (str "The MCP server " (pr-str server-name) " in relay.edn")]
    (when-not (and (string? server-name) (re-matches mcp-name server-name))
      (problem! (str "relay.edn's :mcp-servers has " (pr-str server-name) ", which is not a server name:"
                     " a string of 1 to 64 letters, digits, - or _.")))
    (if (and (map? server) (contains? server :url))
      (check-remote-server where server)
      (do
        (when-not (and (map? server) (every? #{:command :credentials-command :send-labels} (keys server)))
          (problem! (str where " is {:command [...]} or {:url ...}, and may give :credentials-command and"
                         " :send-labels, and nothing else.")))
        (when-not (argv? command)
          (problem! (str where " needs a :command, such as [\"/usr/local/bin/files-mcp\"].")))
        (when (str/includes? (first command) credentials-placeholder)
          (problem! (str where " cannot run " credentials-placeholder ".")))
        (when-not (or (nil? credentials-command) (argv? credentials-command))
          (problem! (str where " has a :credentials-command that is not a command, such as"
                         " [\"security\" \"find-generic-password\" \"-s\" \"files\" \"-w\"].")))
        (let [holds (some #(str/includes? % credentials-placeholder) command)]
          (when (and holds (nil? credentials-command))
            (problem! (str where " has " credentials-placeholder " in its :command but no :credentials-command.")))
          (when (and credentials-command (not holds))
            (problem! (str where " has a :credentials-command, but no argument of its :command holds "
                           credentials-placeholder ", the file it is written to."))))
        (when-not (or (nil? send-labels) (boolean? send-labels))
          (problem! (str where " has :send-labels " (pr-str send-labels) "; it must be true or false.")))))))

(def mcp-tool-keys
  "What an MCP tool may give. :pin and :connection come only from a
  connection file (relay.connections)."
  #{:class :labels :always-hold :protocol :server :tool :digest :allow-content
    :fixed-args :hidden-args :pin :connection :consent :preview})

(defn- check-mcp-tool
  "A tool with :protocol :mcp-stdio or :mcp-http. Its server gives its description and
  schema, so it takes none; agentloop runs it, so it has no :command, and
  the harness's :tool-timeout-seconds bounds a call."
  [tool-name {:keys [server tool digest allow-content protocol pin connection] :as spec} servers]
  (let [where (str "Tool " tool-name " in relay.edn")]
    (when-not (and (string? tool-name) (re-matches mcp-name tool-name))
      (problem! (str where " is an MCP tool, so its name must be 1 to 64 letters, digits, - or _.")))
    (when-let [extra (seq (remove mcp-tool-keys (keys spec)))]
      (problem! (str where " is an MCP tool and cannot have " (str/join ", " (map pr-str (sort-by str extra)))
                     ". Its server gives its description and parameters, agentloop runs it, and the"
                     " harness's :tool-timeout-seconds bounds a call.")))
    (when-not (contains? servers server)
      (problem! (str where " needs :server, naming one of :mcp-servers"
                     (when (some? server) (str "; there is no MCP server " (pr-str server))) ".")))
    (when-not (and (string? tool) (not (str/blank? tool)) (not (str/includes? tool "\u0000")))
      (problem! (str where " needs :tool, the server's own name for the tool.")))
    (when-not (or (nil? digest) (and (string? digest) (re-matches #"[0-9a-f]{64}" digest)))
      (problem! (str where " has :digest " (pr-str digest) "; it must be a SHA-256 in lowercase hex.")))
    (when (and (some? pin) (not (and connection (string? pin) (re-matches #"[0-9a-f]{64}" pin))))
      (problem! (str where " has :pin, which only a connection file gives; use :digest to pin it.")))
    (when (and (contains? servers server) (not= (= :mcp-http protocol) (contains? (get servers server) :url)))
      (problem! (str where " has :protocol " protocol ", but the MCP server " server " is "
                     (if (contains? (get servers server) :url) "remote, so it is :mcp-http." "run over stdio, so it is :mcp-stdio."))))
    (connections/check-narrowing where spec)
    (when-not (or (nil? allow-content)
                  (and (vector? allow-content) (apply distinct? nil allow-content)
                       (every? mcp-content-kinds allow-content)))
      (problem! (str where " has :allow-content " (pr-str allow-content) "; it must be a vector of"
                     " distinct kinds from " (str/join ", " (sort mcp-content-kinds)) ".")))))

(defn- check-mcp
  "The :mcp-servers and the tools that come from them. A server no tool
  uses is refused, as agentloop refuses one, and so is a server's tool
  under two names."
  [{:keys [mcp-servers tools]}]
  (when-not (map? mcp-servers)
    (problem! "relay.edn's :mcp-servers is a map of server names to {:command [...]}."))
  (doseq [[n s] mcp-servers] (check-mcp-server n s))
  ;; agentloop adds read_quarantined itself and refuses a tool of that name.
  (when (contains? tools "read_quarantined")
    (problem! "Tool read_quarantined in relay.edn has the name of agentloop's quarantined reader. Name it otherwise."))
  (doseq [[n t] tools]
    (if (mcp-tool? t)
      (check-mcp-tool n t mcp-servers)
      (when-let [extra (seq (filter #(contains? t %) [:server :tool :digest :allow-content :fixed-args :hidden-args :pin]))]
        (problem! (str "Tool " n " in relay.edn has " (str/join ", " (map pr-str extra))
                       ", which only a tool with :protocol :mcp-stdio or :mcp-http takes.")))))
  (let [mcp (filter (comp mcp-tool? val) tools)]
    (doseq [[[server remote] names] (group-by (fn [[_ t]] [(:server t) (:tool t)]) mcp)
            :when (next names)]
      (problem! (str "Tools " (str/join " and " (sort (map key names))) " in relay.edn are both the tool "
                     (pr-str remote) " of the MCP server " server ".")))
    (doseq [n (sort (keys mcp-servers))
            :when (not-any? #(= n (:server (val %))) mcp)]
      (problem! (str "The MCP server " n " in relay.edn gives none of :tools. Name a tool from it, or"
                     " remove it.")))))

;; --- standing consent and previews

(defn- json-value?
  "Whether x is a value that has exactly one JSON form: a string, a whole
  number, a finite decimal, true, false, nil, or a vector or a map with
  string keys of such values."
  [x]
  (cond
    (or (string? x) (boolean? x) (nil? x) (int? x)) true
    (double? x) (Double/isFinite x)
    (vector? x) (every? json-value? x)
    (map? x) (every? (fn [[k v]] (and (string? k) (json-value? v))) x)
    :else false))

(defn- check-consent
  "A tool's :consent: the operator's standing consent for an :act tool, a
  vector of rules. A rule is a map from the call's argument names, as
  strings, to the exact values the call must give them. A call whose input
  has every pair of some rule runs without asking. An empty rule would
  cover every call, so it is refused, and so is :consent on any other
  class or beside :always-hold."
  [tool-name {:keys [class always-hold] :as tool}]
  (when (contains? tool :consent)
    (let [consent (:consent tool)
          where (str "Tool " tool-name " in relay.edn")]
      (when-not (= :act class)
        (problem! (str where " has :consent, which only an :act tool takes. Its class is "
                       (pr-str class) ", and calls to it are not held for consent.")))
      (when (true? always-hold)
        (problem! (str where " has both :consent and :always-hold, and :always-hold asks every time"
                       " whatever the consent. Keep one.")))
      (when-not (and (vector? consent)
                     (every? #(and (map? %) (seq %) (every? string? (keys %)) (json-value? %)) consent))
        (problem! (str where " has :consent " (pr-str consent) "; it must be a vector of rules, each"
                       " a map from argument names (strings) to the exact values a call must give them,"
                       " such as [{\"group\" \"Kira only\"}]. A rule names at least one argument."))))))

(defn- check-preview
  "A tool's :preview: an envelope command relay runs on a held call's input
  to show the operator what the call would do."
  [tool-name tool]
  (when (contains? tool :preview)
    (let [preview (:preview tool)]
      (when-not (and (vector? preview) (seq preview)
                     (every? #(and (string? %) (not (str/blank? %)) (not (str/includes? % "\u0000"))) preview))
        (problem! (str "Tool " tool-name " in relay.edn has :preview " (pr-str preview) "; it must be a"
                       " command, a vector of strings such as [\"mail-preview\"], run on the envelope"
                       " protocol."))))))

(defn validate
  "Returns the config, or throws describing the first problem found."
  [{:keys [tools grants max-attempts chat-client] :as config}]
  (check-required config)
  (when-not (and (sequential? chat-client) (seq chat-client)
                 (every? #(and (string? %) (not (str/blank? %))) chat-client))
    (problem! (str "relay.edn has :chat-client " (pr-str chat-client)
                   "; it must be a command, such as [\"acp-tui\"].")))
  (doseq [[tool-name {:keys [class labels always-hold] :as tool}] tools]
    (when-not (tool-classes class)
      (throw (ex-info (str "Tool " tool-name " needs a :class, one of "
                           (sort (map name tool-classes)) ".")
                      {:tool tool-name :relay/usage true})))
    ;; Required, even when empty, so that leaving it out is never taken to
    ;; mean the output is clean. A name relay does not know is refused, so
    ;; a typo cannot drop a label.
    (when-not (and (contains? tool :labels) (vector? labels)
                   (every? #(and (string? %) (label-names %)) labels))
      (throw (ex-info (str "Tool " tool-name " needs :labels, a vector of the labels its output"
                           " always carries, from " (sort label-names) "; [] for none"
                           (when (contains? tool :labels) (str ", not " (pr-str labels))) ".")
                      {:tool tool-name :relay/usage true})))
    (when-not (contains? #{nil :plain :envelope :mcp-stdio :mcp-http} (:protocol tool))
      (throw (ex-info (str "Tool " tool-name " has :protocol " (pr-str (:protocol tool))
                           "; it is :envelope, :mcp-stdio, :mcp-http, or left out for a command given its arguments.")
                      {:tool tool-name :relay/usage true})))
    (when (and (some? (:parameters tool)) (not (mcp-tool? tool))
               (not (and (= :envelope (:protocol tool)) (map? (:parameters tool))
                         (= "object" (get-in tool [:parameters :type])))))
      (throw (ex-info (str "Tool " tool-name " has :parameters, which an :envelope tool may give as a"
                           " JSON Schema object, {:type \"object\" ...}.")
                      {:tool tool-name :relay/usage true})))
    (when-not (or (nil? (:timeout-seconds tool)) (pos-int? (:timeout-seconds tool)))
      (throw (ex-info (str "Tool " tool-name " has :timeout-seconds " (pr-str (:timeout-seconds tool))
                           "; it must be a positive whole number.")
                      {:tool tool-name :relay/usage true})))
    (when-not (or (nil? always-hold) (boolean? always-hold))
      (throw (ex-info (str "Tool " tool-name " has :always-hold " (pr-str always-hold)
                           "; it must be true or false.")
                      {:tool tool-name :relay/usage true})))
    (check-consent tool-name tool)
    (check-preview tool-name tool))
  ;; A :script harness runs a program that no policy watches, so its
  ;; roles may not be granted tools that reach outside relay.
  (when (and (= :script (get-in config [:harnesses (:default-harness config) :kind]))
             (some (fn [[_ g]] (or (seq (:tools g)) (:may-add g))) grants))
    (problem! (str "The default harness in relay.edn is a :script harness, which gets no tools,"
                   " so no role may be granted :tools or :may-add.")))
  (check-mcp config)
  (check-grants config)
  (check-verifier config)
  (check-budget config)
  (check-brief config)
  (doseq [[role {granted :tools}] grants
          t granted]
    (when-not (contains? tools t)
      (throw (ex-info (str "Role " role " is granted tool " t ", which :tools does not define.")
                      {:role role :tool t}))))
  (when-not (and (integer? max-attempts) (pos? max-attempts))
    (throw (ex-info ":max-attempts must be a positive integer." {})))
  ;; Labels are kept as relay.core keeps them: sorted distinct strings.
  (update config :tools update-vals
          (fn [t] (update t :labels #(vec (sort (distinct %)))))))

(defn resolve-config
  "Defaults overlaid with the operator's settings."
  [settings]
  (validate (merge defaults (dissoc settings :format))))

(defn load-config [data-dir]
  (let [file (fs/path data-dir "relay.edn")]
    (when-not (fs/exists? file)
      (problem! (str data-dir " has no relay.edn. Run `relay init` to make one.")))
    (resolve-config (connections/expand data-dir (edn/read-string (slurp (str file)))
                                        {:classes tool-classes :labels label-names}))))

(defn zone ^ZoneId [config]
  (ZoneId/of (:timezone config)))

(defn grants
  "What a role is granted beyond the defaults every role has."
  [config role]
  (get-in config [:grants role] {}))

(defn may-add? [config actor]
  (boolean (:may-add (grants config actor))))

(defn public?
  "Whether a role is public: its sessions never hold private, so what it
  writes is public, and it sees only what is public."
  [config role]
  (true? (:public (grants config role))))

(defn report-kinds
  "The kinds of finding a role may report (its :reports grant), or nil
  when it writes no reports."
  [config role]
  (get-in (grants config role) [:reports :kinds]))

(defn quarantine-tools
  "The tools a role may read through the quarantined reader (its
  :quarantine grant), or nil."
  [config role]
  (not-empty (:quarantine (grants config role))))

(defn research-roles
  "The roles granted :reports, sorted."
  [config]
  (sort (keep (fn [[role g]] (when (contains? g :reports) role)) (:grants config))))

(defn mcp-servers-for
  "The :mcp-servers that the named tools come from, by name: those a
  request for a role granted them must start, and no others."
  [config tool-names]
  (select-keys (:mcp-servers config)
               (keep #(let [t (get-in config [:tools %])] (when (mcp-tool? t) (:server t))) tool-names)))

(def verifier-timeout-seconds
  "How long one verifier call may run when :verifier does not say. Each
  call fetches up to twenty pages."
  600)

(defn retry-delay-seconds
  "The wait after the nth failure: the base, doubled for each failure after
  the first, never more than the ceiling."
  [{:keys [retry-base-seconds retry-ceiling-seconds]} n]
  (long (min retry-ceiling-seconds
             (* retry-base-seconds (Math/pow 2 (min 40 (dec (max 1 n))))))))
