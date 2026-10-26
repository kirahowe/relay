(ns relay.setup
  "The questions `relay init` asks to write a new relay.edn. Each has a
  suggestion that Enter accepts, and nothing is written until the operator
  has seen the whole file and said yes."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [relay.config :as config])
  (:import (java.time ZoneId)))

(def efforts ["low" "medium" "high" "xhigh" "max"])

(def default-models {"anthropic" "claude-opus-5-5"})

(defn key-command [provider]
  ["security" "find-generic-password" "-s" (str "relay-" provider) "-w"])

(def brave-key-command
  "Where relay init suggests the Brave Search API key is kept."
  ["security" "find-generic-password" "-s" "relay-brave" "-w"])

(defn webtool-bin
  "The webtool binary to suggest: `webtool` on PATH, else the sibling
  checkout's build. Nil when there is none."
  []
  (or (some-> (fs/which "webtool") str)
      (let [root (some-> (System/getProperty "relay.bin") fs/real-path fs/parent fs/parent)
            sibling (some-> root (fs/path ".." "webtool" "bin" "webtool"))]
        (when (and sibling (fs/executable? sibling)) (str (fs/normalize sibling))))))

(defn- webtool-problem [s]
  (when-not (or (= "none" s) (fs/absolute? s))
    "Give the full path to the webtool binary, or none."))

(defn- one-of [choices]
  (fn [s] (when-not (some #{s} choices) (str "Answer one of: " (str/join ", " choices) "."))))

(defn- zone-problem [s]
  (when-not (try (ZoneId/of s) (catch Exception _ nil))
    (str s " is not a time zone. Use a name such as America/Halifax.")))

(defn- url-problem [s]
  (when-not (re-matches #"https?://\S+" s) "Give a URL starting with http:// or https://."))

(defn- ask!
  "Asks until the answer is acceptable. `ask` returns the operator's line,
  or nil when input has ended; a blank line takes the suggestion."
  [{:keys [ask say]} prompt suggestion problem]
  (loop []
    (let [line (ask (str prompt (when suggestion (str " [" suggestion "]")) ": "))
          _ (when (nil? line) (throw (ex-info "Stopped. Nothing was written." {:relay/usage true})))
          answer (if (str/blank? line) suggestion (str/trim line))]
      (if-let [p (cond (str/blank? answer) "This one needs an answer."
                       problem (problem answer))]
        (do (say p) (recur))
        answer))))

(defn interview
  "Asks for every setting relay needs and returns them as a settings map."
  [io]
  (let [zone (ask! io "Time zone, for routine schedules" (str (ZoneId/systemDefault)) zone-problem)
        provider (ask! io "Model provider (anthropic or openai)" "anthropic" (one-of ["anthropic" "openai"]))
        model (ask! io "Model" (default-models provider) nil)
        effort (ask! io (str "Effort (" (str/join ", " efforts) ")") "medium" (one-of efforts))
        api-key (ask! io "Command that prints the API key" (str/join " " (key-command provider)) nil)
        kind (ask! io "Pushes: macos (this Mac), ntfy (a phone, via an ntfy server) or none"
                   "macos" (one-of ["macos" "ntfy" "none"]))
        messages (case kind
                   "none" {:channels {} :routes []}
                   "macos" {:channels {"push" {:kind :push :via :macos}} :routes [{:channels ["push"]}]}
                   "ntfy" {:channels {"push" {:kind :push :via :ntfy
                                              :url (ask! io "ntfy server URL" "http://127.0.0.1:2586" url-problem)
                                              :topic (ask! io "ntfy topic" "relay" nil)}}
                           :routes [{:channels ["push"]}]})
        _ ((:say io) (str "\nrelay can look things up on the web for you. It searches with Brave Search"
                          " and reads the top pages with webtool. Brave needs an API key"
                          " (https://brave.com/search/api/), used only to send your searches to Brave."
                          " Keep it in the keychain with"
                          "\n  security add-generic-password -a \"$USER\" -s relay-brave -w\n"))
        webtool (ask! io "Path to webtool (none to leave the web out)" (or (:webtool io) "none") webtool-problem)
        web (when-not (= "none" webtool)
              {:bin webtool
               :key-command (str/split (ask! io "Command that prints the Brave Search API key"
                                             (str/join " " brave-key-command) nil)
                                       #"\s+")})]
    {:timezone zone
     :default-harness :agentloop
     :harnesses {:agentloop (cond-> {:kind :agentloop :provider provider :model model :effort effort
                                     :api-key-command (str/split api-key #"\s+")}
                              (:agentloop io) (assoc :bin (:agentloop io)))}
     :messages messages
     :web web}))

(defn webtool-config
  "webtool.json's text for the web settings `interview` returns: page
  snapshots kept inside the data directory `data-dir`, so two data
  directories never mix, and search through Brave with the key from the
  command given."
  [{:keys [key-command]} data-dir]
  (str (json/generate-string
        {:snapshot_dir (str (fs/path data-dir "webtool" "snapshots"))
         :search {:backend "brave" :api_key_command key-command}}
        {:pretty true})
       "\n"))

(def web-tool-parameters
  {:type "object"
   :properties {:query {:type "string" :description "What to search for, up to 400 characters."}
                :n {:type "integer" :minimum 1 :maximum 5 :description "How many result pages to read; 3 by default."}}
   :required ["query"]
   :additionalProperties false})

(def steward
  "The id of the main house role relay init sets up: the one you talk to,
  which reads the mailbox, writes the daily note and gets dead letters."
  "steward")

(def daily-note-at "When the daily note relay init sets up goes out." "07:30")

(defn- web-tool
  "The scout's web tool, webtool's ask, as relay.edn's text."
  [{:keys [bin]} webtool-file]
  (str " ;; The web: webtool's ask searches with Brave Search and reads the top\n"
       " ;; results. It is :egress because the query goes out to Brave, so only\n"
       " ;; a public role may have it: the scout, which sees nothing of yours.\n"
       " :tools {\"web\" {:class :egress\n"
       "                :labels [\"contaminated\"]\n"
       "                :protocol :envelope\n"
       "                :timeout-seconds 150\n"
       "                :queries [\"query\"]\n"
       "                :description \"Search the web and read the top results.\"\n"
       "                :parameters " (pr-str web-tool-parameters) "\n"
       "                :command " (pr-str [bin "--config" webtool-file "ask"]) "}}\n\n"))

(def optional-blocks
  "The parts of relay.edn relay init writes turned off: mail to the
  operator, each source in :ingest, the shell (its folders and its
  grant), and the folders the steward may tidy. Each is a set of lines
  that start with ;; inside a section that is on, so removing the ;; from
  a block's lines turns it on and nothing else."
  [:mail :mailbox :repos :calendar :shell :files])

(defn- switch-blocks
  "The template's lines marked `@block ` after their indentation, as
  they are when the block is in `on`, else with ;; after the indentation.
  Pure."
  [template on]
  (str/join "\n"
            (for [line (str/split template #"\n" -1)]
              (if-let [[_ indent block text] (re-matches #"( *)@([a-z]+) (.*)" line)]
                (if (contains? on (keyword block)) (str indent text) (str indent ";; " text))
                line))))

(defn- messages-text
  "The :messages section: the channels and routes init chose, then mail
  to the operator and its route, off."
  [{:keys [channels routes]}]
  (let [lines (fn [indent xs] (apply str (map #(str indent % "\n") xs)))]
    (str " :messages\n"
         " {:channels\n"
         (lines "  {" (take 1 (for [[k v] (sort-by key channels)] (str (pr-str k) " " (pr-str v)))))
         (lines "   " (drop 1 (for [[k v] (sort-by key channels)] (str (pr-str k) " " (pr-str v)))))
         (if (seq channels) "   " "  {\n   ") "@mail \"mail\" {:kind :email :url \"smtps://smtp.example.org:465\"\n"
         "   @mail         :from \"agent@example.org\" :to \"you@example.org\" :user \"agent@example.org\"\n"
         "   @mail         :credentials-command [\"security\" \"find-generic-password\" \"-s\" \"relay-mail\" \"-w\"]}\n"
         "   }\n"
         "  :routes\n"
         (lines "  [" (take 1 (map pr-str routes)))
         (lines "   " (drop 1 (map pr-str routes)))
         (if (seq routes) "   " "  [\n   ") "@mail {:channels [\"mail\"]}\n"
         "   ]}\n\n")))

(defn render
  "relay.edn's text for the settings `interview` returns. `webtool-file`
  is where webtool.json goes, when the settings have the web. Everything
  relay can do but cannot guess (mail to you, the mailbox, GitHub, your
  calendar, the steward's shell, folders to tidy) is there, each a block
  of lines turned off with ;; and placeholders to fill in. `on` names the
  blocks (optional-blocks) to write turned on instead, for tests."
  ([settings webtool-file] (render settings webtool-file #{}))
  ([{:keys [timezone harnesses messages web]} webtool-file on]
   (let [{:keys [provider model effort api-key-command bin]} (:agentloop harnesses)]
     (switch-blocks
      (str ";; relay's settings, written by `relay init`. Only you edit this file; no\n"
           ";; role can read it. A line that starts with ;; inside a section is off:\n"
           ";; fill in its placeholders and remove the ;; from each line of it to turn\n"
           ";; it on, then run `relay status`, which checks the file.\n"
           ";; setup/relay.example.edn in the relay repository shows every setting.\n"
           "{:format " config/settings-format "\n"
           " :timezone " (pr-str timezone) "\n\n"
           " :default-harness :agentloop\n"
           " :harnesses\n"
           " {:agentloop {:kind :agentloop\n"
           "              :provider " (pr-str provider) "\n"
           "              :model " (pr-str model) "\n"
           "              :effort " (pr-str effort) "\n"
           (when bin (str "              :bin " (pr-str bin) "\n"))
           "              :api-key-command " (pr-str api-key-command) "}}\n\n"
           " ;; The steward: the role you talk to, which reads the mailbox, writes\n"
           " ;; the daily note and hears about work another role could not finish.\n"
           " :dead-letter-handler \"" steward "\"\n\n"
           " ;; What relay tells you goes to these channels; every message also\n"
           " ;; shows in the client. Mail to you comes from the agent's own address\n"
           " ;; and goes to no one else. To turn it on, fill in the addresses and the\n"
           " ;; agent's mail server, store its password with\n"
           " ;;   security add-generic-password -a \"$USER\" -s relay-mail -w\n"
           " ;; and remove the ;; from the mail lines below, its route included.\n"
           " ;; setup/relay.example.edn shows quiet hours and the evening digest.\n"
           (messages-text messages)
           " ;; What comes in from outside, as memos to the steward: the agent's own\n"
           " ;; mailbox, the GitHub repositories to watch, and your calendar's\n"
           " ;; private ICS address, which it only reads. Turn each on by itself.\n"
           " :ingest\n"
           " {\n  @mailbox \"mailbox\"  {:kind :pop3 :url \"pop3s://pop.example.org:995\" :user \"agent@example.org\"\n"
           "  @mailbox             :credentials-command [\"security\" \"find-generic-password\" \"-s\" \"relay-pop\" \"-w\"]\n"
           "  @mailbox             :authserv-id \"mx.example.org\" :to \"" steward "\"}\n"
           "  @repos \"repos\"    {:kind :github :repos [\"you/your-repo\"] :to \"" steward "\"}\n"
           "  @calendar \"calendar\" {:kind :ics :url \"https://calendar.example.org/you.ics\" :to \"" steward "\"}\n"
           "  }\n\n"
           " ;; The steward's shell: a Linux container with no network, where the\n"
           " ;; steward reads the folders you list here, read-only. A plain path is\n"
           " ;; read as outside text, since it may hold what others wrote (a\n"
           " ;; download); mark a folder of only your own writing as trusted. To\n"
           " ;; turn it on, list them, remove the ;; here and before :shell in\n"
           " ;; :grants, then run relay house build once (it needs the network).\n"
           " @shell :house {:mounts [\"~/Downloads\" {:path \"~/Documents\" :trusted true}]}\n\n"
           " ;; The daily note, at this time each day: what is coming up, and any\n"
           " ;; change to how relay works that outside text shaped. The steward\n"
           " ;; writes its parts from a routine relay init added. Remove it for no\n"
           " ;; note.\n"
           " :daily-note {:at " (pr-str daily-note-at) " :role \"" steward "\"}\n\n"
           " ;; Tools beyond relay's own. Each needs :class and :labels; see\n"
           " ;; setup/relay.example.edn, which also shows Apple Reminders and\n"
           " ;; services that speak MCP.\n"
           (if web (web-tool web webtool-file) " :tools {}\n\n")
           " ;; What each role is granted beyond relay's own tools: :tools,\n"
           " ;; :may-add, :public, :shell and :files. The steward may add roles.\n"
           " ;; :files lists folders it may tidy, with moves you can undo.\n"
           " :grants {\"" steward "\" {:may-add true\n"
           "                      @shell :shell true\n"
           "                      @files :files [\"~/Downloads\"]\n"
           "                      }"
           (when web "\n          \"scout\" {:public true :tools [\"web\"]}")
           "}}\n")
      on))))

(defn starter-commands
  "The commands that add the roles relay init sets up, as the operator,
  from the texts in setup/: the steward, with its daily-note skill and
  routine, and with the web a scout. Pure. `texts` is {:steward :scout
  :skill :routine}."
  [{:keys [steward scout skill routine]} web?]
  ;; With a scout, the steward's own text is public, so the scout, which
  ;; sees only public text, can find it and answer it. The text is
  ;; setup/steward.md, public already.
  (cond-> [(cond-> {:type :role/add :args {:id "steward" :title "Steward" :instructions steward}}
             web? (assoc :public true))
           {:type :doc/put :args {:path "skills/steward/daily-note.md" :content skill}}
           {:type :routine/write :args {:owner "steward" :name "daily-note" :schedule "daily 07:10"
                                        :instructions routine}}]
    web? (conj {:type :role/add :public true
                :args {:id "scout" :title "Scout" :reports-to "steward" :instructions scout}})))

(defn starter-lines
  "What relay init says over an existing relay.edn when the log has no
  roles: the commands that add what starter-commands adds, from the texts
  under `root`'s setup/, run against `data` when it was given (else the
  default data directory). With the web, a scout too. Pure."
  [root web? data]
  (let [relay (str "relay" (when data (str " --data " data)))
        file #(str root "/setup/" %)]
    (cond-> ["Your log has no roles yet. To add the steward relay sets up, with its daily note:"
             (str "  " relay " add steward --title Steward < " (file "steward.md"))
             (str "  " relay " put skills/steward/daily-note.md < " (file "daily-note/skill.md"))
             (str "  " relay " routine steward daily-note --schedule \"daily 07:10\" < " (file "daily-note/routine.md"))]
      web? (conj (str "  " relay " add scout --public --title Scout --reports-to steward < " (file "scout.md"))))))

(defn repo-root
  "The relay checkout this code runs from, or nil."
  []
  (some-> (io/resource "relay/setup.clj") io/file fs/parent fs/parent fs/parent str))

(defn starter-texts
  "The texts in setup/ under `root` that starter-commands needs, or nil
  when one is missing."
  [root]
  (let [read #(let [f (apply fs/path root "setup" %&)] (when (fs/exists? f) (slurp (str f))))
        texts {:steward (read "steward.md") :scout (read "scout.md")
               :skill (read "daily-note" "skill.md") :routine (read "daily-note" "routine.md")}]
    (when (every? some? (vals texts)) texts)))

(defn command-works?
  "Whether `argv` prints something and exits 0 within 30 seconds. What it
  prints, a secret, is never kept or shown."
  [argv]
  (try
    (let [proc (p/process (vec argv) {:out :string :err :string :in ""})
          r (deref proc 30000 ::timeout)]
      (if (= ::timeout r)
        (do (p/destroy-tree proc) false)
        (and (zero? (:exit r)) (not (str/blank? (:out r))))))
    (catch Exception _ false)))

(def anthropic-key-store
  "How to store the model key where relay init suggests looking."
  "security add-generic-password -a \"$USER\" -s relay-anthropic -w")

(defn next-steps
  "What relay init says after it has written the files: three steps, the
  first saying whether the key commands work now. Pure. `checks` is
  {:key-ok? :web? :brave-ok? :provider :data-dir}."
  [{:keys [key-ok? web? brave-ok? provider data-dir]}]
  (let [store (fn [service] (str "security add-generic-password -a \"$USER\" -s " service " -w"))]
    (concat
     ["" "Next:"]
     (if key-ok?
       ["  1. Your model API key command works."]
       [(str "  1. Store your " (or provider "model") " API key, which the key command could not print:")
        (str "       " (store (str "relay-" (or provider "anthropic"))))])
     (when web?
       (if brave-ok?
         ["     Web search's key command works too."]
         ["     Web search needs a Brave Search key (https://brave.com/search/api/):"
          (str "       " (store "relay-brave"))]))
     [(str "  2. Turn on what you want in " (fs/path data-dir "relay.edn") ": mail to you, the folders")
      "     the steward may read, your calendar. Each is a commented section there,"
      "     and relay status checks the file."
      "  3. Have launchd run relay tick every minute (README: Setup), then run relay"
      "     to talk to your steward."])))

(defn create!
  "Interviews the operator, shows the file and writes it to `file` only on
  a yes. Returns the settings when it was written, else false. With the web, webtool.json is
  shown and written beside it, unless one is there already, which is
  kept. `io` may give :webtool, the webtool binary to suggest, and
  :agentloop, the agentloop binary found, which is written as the
  harness's absolute :bin."
  [{:keys [ask say] :as io} file]
  (say "relay needs a few settings. Press Enter to take the suggestion in brackets.\n")
  (let [settings (interview io)
        webtool-file (str (fs/path (fs/parent (fs/absolutize file)) "webtool.json"))
        text (render settings webtool-file)
        web-text (when (and (:web settings) (not (fs/exists? webtool-file)))
                   (webtool-config (:web settings) (str (fs/parent (fs/absolutize file)))))]
    ;; The file is read back the way relay will read it, so a setting it
    ;; would refuse is caught here rather than at the first wake.
    (config/resolve-config (edn/read-string text))
    (say (str "\nThis will be " file ":\n\n" text))
    (cond
      web-text (say (str "\nand " webtool-file ":\n\n" web-text))
      (:web settings) (say (str "\n" webtool-file " is there already and is kept.")))
    (let [answer (ask "Write it? [y/N] ")]
      (if (#{"y" "yes"} (some-> answer str/trim str/lower-case))
        (do (fs/create-dirs (fs/parent file))
            (spit file text)
            (when web-text (spit webtool-file web-text))
            settings)
        (do (say "Nothing was written.") false)))))
