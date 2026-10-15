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

(def notify-kinds #{:ntfy :command :macos :none})

(defn- problem! [msg] (throw (ex-info msg {:relay/usage true})))

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
      (problem! "The command :notify in relay.edn needs a :command."))))

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
    (when-not (or (nil? always-hold) (boolean? always-hold))
      (throw (ex-info (str "Tool " tool-name " has :always-hold " (pr-str always-hold)
                           "; it must be true or false.")
                      {:tool tool-name :relay/usage true}))))
  ;; A :script harness runs a program that no policy watches, so its
  ;; roles may not be granted tools that reach outside relay.
  (when (and (= :script (get-in config [:harnesses (:default-harness config) :kind]))
             (some (comp seq :tools val) grants))
    (problem! (str "The default harness in relay.edn is a :script harness, which enforces no"
                   " policy, so no role may be granted :tools.")))
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

(defn retry-delay-seconds
  "The wait after the nth failure: the base, doubled for each failure after
  the first, never more than the ceiling."
  [{:keys [retry-base-seconds retry-ceiling-seconds]} n]
  (long (min retry-ceiling-seconds
             (* retry-base-seconds (Math/pow 2 (min 40 (dec (max 1 n))))))))
