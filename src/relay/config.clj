(ns relay.config
  "The operator's settings, from relay.edn at the root of the data directory.
  Settings are not data: they say how relay runs and what each role is
  granted, and only the operator edits them. No role has a tool that can
  read or write the file."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.time ZoneId)))

(def tool-classes
  "Every tool a role can be granted is named in config and given one of
  these classes, which agentloop's policy decides on:

    :read    returns content, changes nothing, sends nothing out that an
             outsider could see
    :write   changes relay data only
    :egress  can send data the model chose where an outsider could see it
    :act     does something in the operator's name outside relay, such as
             a reminder

  A session that has seen both private data and something contaminated
  needs the operator's approval for an egress call, and one that has seen
  anything contaminated needs it for an act."
  #{:read :write :egress :act})

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

(defn- check-required
  [{:keys [timezone default-harness harnesses notify]}]
  (when-not (and (string? timezone)
                 (try (ZoneId/of timezone) (catch Exception _ nil)))
    (problem! (str "relay.edn needs :timezone, a zone name such as \"America/Halifax\""
                   (when timezone (str "; " (pr-str timezone) " is not one")) ".")))
  (when-not (and (map? harnesses) (seq harnesses))
    (problem! "relay.edn needs :harnesses, saying how to run a model."))
  (doseq [[h {:keys [kind api-key-command]}] harnesses]
    (when-not kind
      (problem! (str "Harness " (name h) " in relay.edn needs a :kind.")))
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
  #{:tools :may-add :public :reports})

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
      (check-reports-grant role g))))

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
    (when-not (contains? #{nil :plain :envelope} (:protocol tool))
      (throw (ex-info (str "Tool " tool-name " has :protocol " (pr-str (:protocol tool))
                           "; it is :envelope, or left out for a command given its arguments.")
                      {:tool tool-name :relay/usage true})))
    (when (and (some? (:parameters tool))
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
                      {:tool tool-name :relay/usage true}))))
  ;; A :script harness runs a program that no policy watches, so its
  ;; roles may not be granted tools that reach outside relay.
  (when (and (= :script (get-in config [:harnesses (:default-harness config) :kind]))
             (some (fn [[_ g]] (or (seq (:tools g)) (:may-add g))) grants))
    (problem! (str "The default harness in relay.edn is a :script harness, which gets no tools,"
                   " so no role may be granted :tools or :may-add.")))
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
    (resolve-config (edn/read-string (slurp (str file))))))

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

(defn research-roles
  "The roles granted :reports, sorted."
  [config]
  (sort (keep (fn [[role g]] (when (contains? g :reports) role)) (:grants config))))

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
