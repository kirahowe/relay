(ns relay.config
  "The operator's settings, from relay.edn at the root of the data directory.
  Settings are not data: they say how relay runs and what each role is
  granted, and only the operator edits them. No role has a tool that can
  read or write the file."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [relay.connections :as connections]
            [relay.files :as files]
            [relay.ingest :as ingest]
            [relay.messages :as messages]
            [relay.shell :as shell])
  (:import (java.time ZoneId)))

(def tool-classes
  "Every tool a role can be granted is named in config and given one of
  these classes, which say what the tool can do:

    :read    returns content, changes nothing, sends nothing out that an
             outsider could see
    :write   changes relay data only, which keeps its labels
    :edit    changes something outside relay that only the operator sees
             and that they can undo: a draft in their own account, mail
             to them, the agent's own calendar
    :egress  can send what the model chose where an outsider could see it,
             such as a web search; only a public role may have one

  Nothing relay holds can send, post, publish or pay as the operator, so
  there is no class for that. No call is ever held for the operator."
  #{:read :write :edit :egress})

(def label-names
  "The labels a tool's output can carry (relay.core/label-names). An
  external tool declares the ones its output always carries in :labels, as
  strings: \"contaminated\" for anything an outsider could have written,
  \"private\" for the operator's private data."
  #{"private" "contaminated"})

(def defaults
  "Settings relay can sensibly choose for itself. Everything relay needs
  and cannot guess, :timezone, :default-harness and :harnesses, is
  required in relay.edn; `relay init` asks for it. Without :messages,
  every message is the client's to show and goes nowhere else."
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
   ;; The ACP client bare `relay` runs, given `[--open <role>] -- relay
   ;; acp` after these arguments.
   :client ["relay-tui"]
   :tools {}
   :mcp-servers {}
   ;; The remote MCP servers relay connect set up, by name (relay.connections).
   :connections []
   :grants {}
   :messages messages/default-settings})

(defn- problem! [msg] (throw (ex-info msg {:relay/usage true})))

(defn check-messages
  "Checks the :messages settings alone, throwing on the first problem, so
  a relay.edn broken elsewhere can still say so to the operator."
  [settings]
  (when-let [why (messages/settings-problem settings)] (problem! why)))

(def removed-keys
  "Settings relay no longer has, and what took their place."
  {:notify "relay.edn has :notify, which relay no longer has. Say where messages go with :messages; setup/relay.example.edn shows how."})

(def efforts #{"low" "medium" "high" "xhigh" "max"})

(def removed-harness-keys
  "Harness settings relay no longer has: the quarantined reader's model
  and the workbench."
  #{:reader :workbench})

(defn- check-required
  [{:keys [timezone default-harness harnesses]}]
  (when-not (and (string? timezone)
                 (try (ZoneId/of timezone) (catch Exception _ nil)))
    (problem! (str "relay.edn needs :timezone, a zone name such as \"America/Halifax\""
                   (when timezone (str "; " (pr-str timezone) " is not one")) ".")))
  (when-not (and (map? harnesses) (seq harnesses))
    (problem! "relay.edn needs :harnesses, saying how to run a model."))
  (doseq [[h {:keys [kind api-key-command] :as harness}] harnesses]
    (when-not kind
      (problem! (str "Harness " (name h) " in relay.edn needs a :kind.")))
    (when-let [gone (seq (filter #(contains? harness %) (sort removed-harness-keys)))]
      (problem! (str "Harness " (name h) " in relay.edn has " (str/join " and " (map pr-str gone))
                     ", which relay no longer has. Remove " (if (next gone) "them." "it."))))
    (when (and (= :agentloop kind)
               (not (and (sequential? api-key-command) (seq api-key-command))))
      (problem! (str "Harness " (name h) " in relay.edn needs :api-key-command, "
                     "the command that prints the API key."))))
  (when-not (contains? harnesses default-harness)
    (problem! (str "relay.edn needs :default-harness, naming one of :harnesses"
                   (when default-harness (str "; there is no harness " (pr-str default-harness))) "."))))

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
  #{:tools :may-add :public :shell :files})

(defn- check-grants
  "Checks each role's grants, and what a public role may not have. A public
  role's session never holds private, so it is granted nothing whose
  output is private, nothing that adds or changes roles, and no dead
  letters, which quote other roles' memos. A role that is not public, a
  house role, holds the operator's things, so it is granted no :egress
  tool: nothing it reads can leave the house by one."
  [{:keys [tools grants dead-letter-handler harnesses default-harness] :as config}]
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
      (doseq [t (distinct (:tools g))
              :when (some #{"private"} (get-in tools [t :labels]))]
        (problem! (str "Role " role " is :public, so it may not be granted " t
                       ", whose output is private.")))
      (when (= role dead-letter-handler)
        (problem! (str "Role " role " is :public, so it may not be the :dead-letter-handler:"
                       " dead letters quote other roles' private memos.")))
      (when (= :script (get-in harnesses [default-harness :kind]))
        (problem! (str "Role " role " is :public, and the default harness is a :script harness,"
                       " whose program can claim any labels. A public role needs agentloop."))))
    (when-not (or (nil? (:shell g)) (boolean? (:shell g)))
      (problem! (str "Role " role " has :shell " (pr-str (:shell g)) " in relay.edn; it must be true or false.")))
    (when (true? (:shell g))
      (when (:public g)
        (problem! (str "Role " role " is :public, so it may not have :shell: the shell shows your files.")))
      (when-not (contains? config :house)
        (problem! (str "Role " role " has :shell, so relay.edn needs a :house section saying how the shell"
                       " runs, such as {:mounts [\"/Users/you/notes\"]}.")))
      (when (= :script (get-in harnesses [default-harness :kind]))
        (problem! (str "Role " role " has :shell, and the default harness is a :script harness, which gets"
                       " no tools. A role with the shell needs agentloop."))))
    ;; :files names the folders the role may move files within
    ;; (relay.files), apart from the shell's read-only mounts.
    (when (contains? g :files)
      (when-let [why (files/grant-problem role (:files g))] (problem! why))
      (when (:public g)
        (problem! (str "Role " role " is :public, so it may not have :files: file names are your private data.")))
      (when (= :script (get-in harnesses [default-harness :kind]))
        (problem! (str "Role " role " has :files, and the default harness is a :script harness, which gets"
                       " no tools. A role that organizes files needs agentloop."))))
    (when-not (:public g)
      (doseq [t (distinct (:tools g))
              :let [{:keys [class protocol]} (get tools t)]]
        (when (= :egress class)
          (problem! (str "Role " role " is granted " t ", an :egress tool, and only a public role may have"
                         " one: " role " holds your things, which " t " could send out of the house.")))
        ;; What a house role gives a remote server goes into the
        ;; operator's own account there, and nowhere else (I1).
        (when (and (= :mcp-http protocol) (not (#{:read :edit} class)))
          (problem! (str "Role " role " is granted " t ", a tool of a remote server, which a house role"
                         " may have only as :read or :edit.")))))))

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

(def websocket-defaults
  "What `:serve {:websocket ...}` takes when it leaves them out: the role a
  session/new without _meta relay/role goes to, and the browser origins
  allowed in (none)."
  {:default-role "hub" :allowed-origins []})

(defn websocket-problem
  "What is wrong with `:serve`, the relay service's settings, as a
  sentence for the operator, or nil. Its one setting is :websocket, the
  listener a phone reaches the service through:
  {:port 8765 :token-command [...] :default-role \"hub\" :allowed-origins []}."
  [serve]
  (let [example (str "{:websocket {:port 8765 :token-command [\"security\" \"find-generic-password\" \"-s\""
                     " \"relay-websocket\" \"-w\"]}}")
        {:keys [port token-command default-role allowed-origins] :as ws} (when (map? serve) (:websocket serve))]
    (cond
      (not (and (map? serve) (every? #{:websocket} (keys serve))))
      (str "relay.edn's :serve is " example ", and nothing else, not " (pr-str serve) ".")
      (not (contains? serve :websocket)) nil
      (not (and (map? ws) (every? #{:port :token-command :default-role :allowed-origins} (keys ws))))
      (str "relay.edn's :serve :websocket takes :port, :token-command, :default-role and :allowed-origins,"
           " not " (pr-str ws) ".")
      (not (and (int? port) (<= 1 port 65535)))
      (str "relay.edn's :serve :websocket needs :port, the port on 127.0.0.1 to listen on, from 1 to 65535"
           (when (some? port) (str ", not " (pr-str port))) ".")
      (not (and (vector? token-command) (seq token-command)
                (every? #(and (string? %) (not (str/blank? %))) token-command)))
      (str "relay.edn's :serve :websocket needs :token-command, the command that prints the token a client"
           " must send, such as [\"security\" \"find-generic-password\" \"-s\" \"relay-websocket\" \"-w\"]"
           (when (some? token-command) (str ", not " (pr-str token-command))) ".")
      (and (contains? ws :default-role) (not (role-id? default-role)))
      (str "relay.edn's :serve :websocket has :default-role " (pr-str default-role)
           ", which is not a role id such as \"hub\".")
      (and (contains? ws :allowed-origins)
           (not (and (vector? allowed-origins) (every? #(and (string? %) (not (str/blank? %))) allowed-origins))))
      (str "relay.edn's :serve :websocket has :allowed-origins " (pr-str allowed-origins)
           "; it is a vector of origins, such as [\"https://example.com\"], or [] for none."))))

(defn- check-serve
  "The optional :serve. Returns the config with :websocket's defaults
  filled in."
  [config]
  (if-not (contains? config :serve)
    config
    (do (when-let [why (websocket-problem (:serve config))] (problem! why))
        (cond-> config
          (get-in config [:serve :websocket])
          (update-in [:serve :websocket] #(merge websocket-defaults %))))))

(defn- check-brief
  "The optional :brief: {:at \"07:30\"}, the time each day, and the tools
  it reads, :calendar and :reminders, when they are not cal and rem-read.
  A tool named here must be a :read tool in :tools: the brief runs it by
  itself, and must change nothing."
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
  #{:class :labels :protocol :server :tool :digest :allow-content
    :fixed-args :hidden-args :pin :connection})

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

(defn egress-problem
  "Why relay.edn's `tool` cannot be an :egress tool, or nil. relay holds a
  scout to the addresses it was shown (plan O1, I4) by checking each call
  before it runs, and it can do that only for a call it runs and can
  read: an :envelope tool whose parameters it knows. A `url` parameter is
  checked whole. Free text in any other parameter could carry an address
  with no scheme, or a host name, which relay cannot tell from words, so
  an :egress tool may take free text only in the parameters the operator
  lists in :queries, text that goes to a service trusted as the model
  provider is, such as a web search. Its other parameters are numbers or
  yes-or-no values. Pure."
  [tool-name {:keys [class protocol parameters queries] :as tool}]
  (let [props (into {} (map (fn [[k v]] [(name k) v]) (:properties parameters)))
        why (fn [& parts] (apply str "Tool " tool-name " in relay.edn " parts))]
    (cond
      (and (contains? tool :queries) (not= :egress class))
      (why "has :queries, which only an :egress tool has.")

      (not= :egress class) nil

      (mcp-tool? tool)
      (why "is an :egress tool of an MCP server. relay checks every :egress call before it runs, and"
           " agentloop calls an MCP server itself, so an :egress tool must be an :envelope tool relay runs.")

      (or (not= :envelope protocol) (not (map? (:properties parameters))))
      (why "is an :egress tool, which must be an :envelope tool with :parameters that list its"
           " properties. relay can hold a scout to the addresses it was shown only in a `url` argument"
           " it can read, so it does not run a command given the model's arguments as an :egress tool.")

      (not (or (nil? queries)
               (and (vector? queries) (every? string? queries)
                    (every? #(and (not= "url" %) (= "string" (get-in props [% :type]))) queries))))
      (why "has :queries " (pr-str queries) ". It lists the names of string parameters, other than url,"
           " whose text goes to a service you trust as you trust the model provider, such as [\"query\"].")

      :else
      (when-let [[p] (first (for [[p schema] (sort-by key props)
                                  :when (not (if (or (= "url" p) (some #{p} queries))
                                               (= "string" (:type schema))
                                               (#{"integer" "number" "boolean"} (:type schema))))]
                              [p]))]
        (why "is an :egress tool, and its parameter " p " is neither a string url, nor a number or"
             " yes-or-no value, nor listed in :queries. relay can hold a scout to the addresses it was"
             " shown only in a url argument. List a parameter in :queries only when its text goes to a"
             " service you trust as you trust the model provider, such as a web search, never to one"
             " that publishes it.")))))

(def removed-tool-keys
  "Tool settings relay no longer has: no call is held for the operator,
  so there is nothing to hold, consent to or preview."
  #{:always-hold :consent :preview})

(defn validate
  "Returns the config, or throws describing the first problem found."
  [{:keys [tools grants max-attempts] client :client :as config}]
  (doseq [[k why] (sort-by key removed-keys) :when (contains? config k)] (problem! why))
  (check-required config)
  (check-messages (:messages config))
  (when-let [why (ingest/settings-problem (:ingest config) #(true? (get-in config [:grants % :public])))]
    (problem! why))
  (when-not (and (sequential? client) (seq client)
                 (every? #(and (string? %) (not (str/blank? %))) client))
    (problem! (str "relay.edn has :client " (pr-str client)
                   "; it must be a command, such as [\"relay-tui\"].")))
  (doseq [[tool-name {:keys [class labels] :as tool}] tools]
    (when-let [gone (seq (filter #(contains? tool %) (sort removed-tool-keys)))]
      (problem! (str "Tool " tool-name " in relay.edn has " (str/join " and " (map pr-str gone))
                     ", which relay no longer has: no call waits for you. Remove "
                     (if (next gone) "them." "it."))))
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
    (when-let [why (egress-problem tool-name tool)] (problem! why))
    (when-not (or (nil? (:timeout-seconds tool)) (pos-int? (:timeout-seconds tool)))
      (throw (ex-info (str "Tool " tool-name " has :timeout-seconds " (pr-str (:timeout-seconds tool))
                           "; it must be a positive whole number.")
                      {:tool tool-name :relay/usage true}))))
  ;; A :script harness runs a program that no policy watches, so its
  ;; roles may not be granted tools that reach outside relay.
  (when (and (= :script (get-in config [:harnesses (:default-harness config) :kind]))
             (some (fn [[_ g]] (or (seq (:tools g)) (:may-add g))) grants))
    (problem! (str "The default harness in relay.edn is a :script harness, which gets no tools,"
                   " so no role may be granted :tools or :may-add.")))
  (when (contains? config :house)
    (when-let [why (shell/settings-problem (:house config))] (problem! why)))
  (check-mcp config)
  (check-grants config)
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
  (update (check-serve config) :tools update-vals
          (fn [t] (update t :labels #(vec (sort (distinct %)))))))

(defn resolve-config
  "Defaults overlaid with the operator's settings."
  [settings]
  (validate (merge defaults (dissoc settings :format))))

(defn load-config [data-dir]
  (let [file (fs/path data-dir "relay.edn")]
    (when-not (fs/exists? file)
      (problem! (str data-dir " has no relay.edn. Run `relay init` to make one.")))
    (let [config (resolve-config (connections/expand data-dir (edn/read-string (slurp (str file)))
                                                     {:classes tool-classes :labels label-names}))]
      ;; Where the files to mount, and the folders to organize, may not be
      ;; depends on this machine: the home directory and the data
      ;; directory itself.
      (let [where {:home (str (fs/real-path (System/getProperty "user.home")))
                   :data-dir (str (fs/real-path data-dir))}]
        (when-let [why (or (shell/mounts-problem (:house config) where)
                           (files/roots-problem config where))]
          (problem! why)))
      config)))

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

(defn role-tools
  "Every external tool a role's sessions can run: its :tools."
  [config role]
  (vec (distinct (:tools (grants config role)))))

(defn mcp-servers-for
  "The :mcp-servers that the named tools come from, by name: those a
  request for a role granted them must start, and no others."
  [config tool-names]
  (select-keys (:mcp-servers config)
               (keep #(let [t (get-in config [:tools %])] (when (mcp-tool? t) (:server t))) tool-names)))

(defn retry-delay-seconds
  "The wait after the nth failure: the base, doubled for each failure after
  the first, never more than the ceiling."
  [{:keys [retry-base-seconds retry-ceiling-seconds]} n]
  (long (min retry-ceiling-seconds
             (* retry-base-seconds (Math/pow 2 (min 40 (dec (max 1 n))))))))
