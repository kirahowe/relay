(ns relay.harness.pi
  "Runs a role with pi (github.com/badlogic/pi-mono) on the Anthropic API.

  Settings in relay.edn, under :harnesses:

    {:pi {:kind :pi
          :model \"claude-opus-5-5\"     ; the default
          :effort \"high\"               ; low, medium, high, xhigh or max
          :api-key-command [\"security\" \"find-generic-password\" \"-s\" \"relay-anthropic\" \"-w\"]
          ;; or :api-key-env \"ANTHROPIC_API_KEY\", read from relay's environment
          :bin \"/path/to/pi\"}}         ; default: the copy pinned in this repo

  pi runs with none of its own tools, extensions, context files, skills or
  settings: an empty agent directory per wake, and only relay's extension,
  which presents the tools relay offers the role. Each wake's transcript is
  pi's JSON event stream, kept in the wake's run directory.

  How a run ended is read from the events, never from the exit code: in JSON
  mode pi exits 0 even when every request failed."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :as p]
            [relay.harness :as harness])
  (:import (java.time Instant)))

(def adaptive-models
  "Models this pi version does not know, registered with it here. They take
  adaptive thinking only, which relay's extension asks for itself (see
  resources/pi/relay-tools.ts), so pi is told they do not reason and sends
  no thinking settings of its own."
  (into {}
        (for [[id input output] [["claude-opus-5-5" 4 20] ["claude-sonnet-5-5" 2 10]
                                 ["claude-haiku-5-5" 0.1 0.5] ["claude-fable-5-1" 10 50]]]
          [id {:id id :reasoning false :input ["text" "image"]
               :contextWindow 1000000 :maxTokens 128000
               :cost {:input input :output output :cacheRead (/ input 10.0) :cacheWrite (* input 1.25)}}])))

(def default-model "claude-opus-5-5")

(defn- repo-root []
  (some-> (System/getProperty "relay.bin") fs/real-path fs/parent fs/parent str))

(defn pi-bin [{:keys [bin]}]
  (or bin
      (let [pinned (some-> (repo-root) (fs/path "node_modules" ".bin" "pi"))]
        (when (and pinned (fs/executable? pinned)) (str pinned)))
      "pi"))

(defn- extension-path []
  (let [url (io/resource "pi/relay-tools.ts")]
    (when-not (= "file" (.getProtocol url))
      (throw (ex-info "relay's pi extension must be on disk, not inside a jar." {:url (str url)})))
    (str (fs/path (.toURI url)))))

(defn api-key
  "The API key, from a command (such as a keychain lookup) or an environment
  variable. Nil when there is none."
  [{:keys [api-key-command api-key-env] :or {api-key-env "ANTHROPIC_API_KEY"}}]
  (if api-key-command
    (let [{:keys [exit out]} (apply p/shell {:out :string :err :string :continue true :in ""}
                                    api-key-command)]
      (when (zero? exit) (not-empty (str/trim out))))
    (not-empty (System/getenv api-key-env))))

(defn- write-agent-dir!
  "pi's configuration for one wake: only what relay sets."
  [dir {:keys [model base-url]}]
  (fs/create-dirs dir)
  (spit (str (fs/path dir "models.json"))
        (json/generate-string
         {:providers {:anthropic (cond-> {}
                                   base-url (assoc :baseUrl base-url)
                                   (adaptive-models model) (assoc :models [(adaptive-models model)]))}}))
  (spit (str (fs/path dir "settings.json"))
        (json/generate-string {:quietStartup true})))

(defn command
  "The command line for one run."
  [{:keys [harness model system-prompt prompt tools session-dir resume]}]
  (let [model (or model (:model harness) default-model)]
    (vec (concat [(pi-bin harness) "--mode" "json" "-p"
                  "--provider" (or (:provider harness) "anthropic")
                  "--model" model
                  "--no-builtin-tools" "-ne" "-e" (extension-path)
                  "--tools" (str/join "," (map :name tools))
                  "-nc" "-ns" "-np" "--no-themes" "--offline"
                  "--system-prompt" system-prompt
                  "--session-dir" (str session-dir)]
                 (when resume ["--session" resume])
                 [prompt]))))

;; --- reading a run back

(defn parse-events
  "The JSON events in pi's output, skipping anything else it printed."
  [text]
  (vec (keep (fn [line]
               (when (str/starts-with? (str/triml line) "{")
                 (try (let [e (json/parse-string line true)] (when (map? e) e))
                      (catch Exception _ nil))))
             (str/split-lines (str text)))))

(defn- final-message
  "The last assistant message: from agent_end when the run got that far,
  otherwise from the last message_end."
  [events]
  (let [from-end (some->> events (filter #(= "agent_end" (:type %))) last :messages
                          (filter #(= "assistant" (:role %))) last)]
    (or from-end
        (->> events
             (filter #(and (= "message_end" (:type %)) (= "assistant" (get-in % [:message :role]))))
             last :message))))

(defn- text-of [message]
  (->> (:content message) (filter #(= "text" (:type %))) (map :text) (str/join "\n")))

(defn- reset-time
  "When a rate limit lifts, from the headers of the last 429: the latest of
  Anthropic's reset headers that is still ahead, or retry-after."
  [provider-log ^Instant now]
  (let [last-429 (->> (str/split-lines (str provider-log))
                      (keep #(try (json/parse-string % true) (catch Exception _ nil)))
                      (filter #(= 429 (:status %)))
                      last)
        headers (into {} (map (fn [[k v]] [(str/lower-case (name k)) (str v)]) (:headers last-429)))
        resets (keep (fn [[k v]]
                       (when (and (str/starts-with? k "anthropic-ratelimit-") (str/ends-with? k "-reset"))
                         (try (Instant/parse v) (catch Exception _ nil))))
                     headers)
        ahead (filter #(.isAfter ^Instant % now) resets)
        retry-after (some-> (get headers "retry-after") parse-long)]
    (cond
      (seq ahead) (str (reduce #(if (.isAfter ^Instant %1 %2) %1 %2) ahead))
      retry-after (str (.plusSeconds now retry-after))
      :else nil)))

(def ^:private unreachable-words
  #"(?i)fetch failed|econnrefused|econnreset|enotfound|etimedout|socket hang up|network|terminated|timed? ?out|connection")

(defn classify
  "How a run ended, from what pi printed. Pure: takes the parsed events, the
  exit code, stderr, the provider log and the time."
  [{:keys [events exit stderr provider-log now]}]
  (let [session (some #(when (= "session" (:type %)) (:id %)) events)
        final (final-message events)
        stop (:stopReason final)
        error (str (:errorMessage final))
        status (some-> (re-find #"^(\d{3})\b" error) second parse-long)
        said (harness/tail (if (= "error" stop) error (text-of final)) 8)
        report (fn [outcome & {:as more}]
                 (merge {:outcome outcome :exit exit :session session :said said
                         :crashed (or (= "timeout" exit) (and (number? exit) (not (zero? exit))))}
                        more))]
    (cond
      (= "timeout" exit) (report "ran" :crashed true :said "Stopped at the wake deadline.")
      (#{"stop" "length" "toolUse"} stop) (report "ran")
      (= "aborted" stop) (report "ran" :crashed true)
      (= "error" stop)
      (cond
        (= 429 status) (report "limited" :resets-at (reset-time provider-log now))
        (#{401 403 404} status) (report "needs-attention")
        (re-find #"(?i)credit balance|billing" error) (report "needs-attention")
        (and status (>= status 500)) (report "unreachable")
        (and (nil? status) (re-find unreachable-words error)) (report "unreachable")
        :else (report "ran" :crashed true))
      ;; No final message at all.
      (re-find #"(?i)no api key|api key not found|login" (str stderr))
      (report "needs-attention" :said (harness/tail stderr 5))
      (nil? session) (report "needs-attention" :said (str "pi did not start. " (harness/tail stderr 5)))
      :else (report "unreachable" :said (harness/tail (str stderr) 5)))))

(defmethod harness/run! :pi
  [{:keys [harness run-dir tools env timeout-ms] :as request}]
  (let [file #(str (fs/path run-dir %))
        key (api-key harness)
        model (or (:model request) (:model harness) default-model)]
    (if-not key
      {:outcome "needs-attention" :exit nil :crashed false
       :said "No API key: set :api-key-command or :api-key-env for the pi harness in relay.edn."}
      (let [agent-dir (file "pi-agent")
            _ (write-agent-dir! agent-dir {:model model :base-url (:base-url harness)})
            _ (spit (file "tools.json") (json/generate-string tools))
            _ (fs/create-dirs (:session-dir request))
            work (file "work")
            _ (fs/create-dirs work)
            out (file "transcript.jsonl")
            provider-log (file "provider.log")
            exit (harness/run-process!
                  (command (assoc request :model model))
                  {:dir work :out-file out :timeout-ms timeout-ms
                   :env (merge env
                               {"PI_CODING_AGENT_DIR" agent-dir
                                "PI_OFFLINE" "1"
                                "PI_TELEMETRY" "0"
                                "ANTHROPIC_API_KEY" key
                                "RELAY_TOOLS_FILE" (file "tools.json")
                                "RELAY_PROVIDER_LOG" provider-log}
                               (when (adaptive-models model)
                                 (cond-> {"RELAY_PI_THINKING" "adaptive"}
                                   (:effort harness) (assoc "RELAY_PI_EFFORT" (:effort harness)))))})]
        (assoc (classify {:events (parse-events (slurp out))
                          :exit exit
                          :stderr (slurp (str out ".err"))
                          :provider-log (when (fs/exists? provider-log) (slurp provider-log))
                          :now (Instant/now)})
               :transcript out)))))
