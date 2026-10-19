(ns relay.setup
  "The questions `relay init` asks to write a new relay.edn. Each has a
  suggestion that Enter accepts, and nothing is written until the operator
  has seen the whole file and said yes."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [relay.config :as config]
            [relay.log :as log])
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
        kind (ask! io "Notifications: macos (this Mac), ntfy (a phone, via an ntfy server) or none"
                   "macos" (one-of ["macos" "ntfy" "none"]))
        notify (if (= "ntfy" kind)
                 {:kind :ntfy
                  :url (ask! io "ntfy server URL" "http://127.0.0.1:2586" url-problem)
                  :topic (ask! io "ntfy topic" "relay" nil)}
                 {:kind (keyword kind)})
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
     :harnesses {:agentloop {:kind :agentloop :provider provider :model model :effort effort
                             :api-key-command (str/split api-key #"\s+")}}
     :notify notify
     :web web}))

(defn webtool-config
  "webtool.json's text for the web settings `interview` returns: page
  snapshots kept under the operator's Library, and search through Brave
  with the key from the command given."
  [{:keys [key-command]} home]
  (str (json/generate-string
        {:snapshot_dir (str (fs/path home "Library" "Application Support" "webtool" "snapshots"))
         :search {:backend "brave" :api_key_command key-command}}
        {:pretty true})
       "\n"))

(def web-tool-parameters
  {:type "object"
   :properties {:query {:type "string" :description "What to search for, up to 400 characters."}
                :n {:type "integer" :minimum 1 :maximum 5 :description "How many result pages to read; 3 by default."}}
   :required ["query"]
   :additionalProperties false})

(defn- web-tool
  "The hub's web tool, webtool's ask, as relay.edn's text."
  [{:keys [bin]} webtool-file]
  (str " ;; The web: webtool's ask searches with Brave Search and reads the top\n"
       " ;; results. The hub reads it only through read_quarantined (its\n"
       " ;; :quarantine grant), so it gets typed answers and stays clean. It is\n"
       " ;; :egress because the query goes out to Brave.\n"
       " :tools {\"web\" {:class :egress\n"
       "                :labels [\"contaminated\"]\n"
       "                :protocol :envelope\n"
       "                :timeout-seconds 150\n"
       "                :description \"Search the web and read the top results. Read it through read_quarantined with a closed schema.\"\n"
       "                :parameters " (pr-str web-tool-parameters) "\n"
       "                :command " (pr-str [bin "--config" webtool-file "ask"]) "}}\n"
       " :grants {\"hub\" {:quarantine [\"web\"]}}}\n"))

(defn render
  "relay.edn's text for the settings `interview` returns. `webtool-file`
  is where webtool.json goes, when the settings have the web."
  [{:keys [timezone harnesses notify web]} webtool-file]
  (let [{:keys [provider model effort api-key-command]} (:agentloop harnesses)]
    (str ";; relay's settings, written by `relay init`. Only the operator edits this\n"
         ";; file; no role can read it. setup/relay.example.edn in the relay\n"
         ";; repository shows every setting, including tools and grants.\n"
         "{:format " log/format-version "\n"
         " :timezone " (pr-str timezone) "\n\n"
         " :default-harness :agentloop\n"
         " :harnesses\n"
         " {:agentloop {:kind :agentloop\n"
         "              :provider " (pr-str provider) "\n"
         "              :model " (pr-str model) "\n"
         "              :effort " (pr-str effort) "\n"
         "              :api-key-command " (pr-str api-key-command) "}}\n\n"
         " :notify " (pr-str notify) "\n\n"
         " ;; Tools beyond relay's own. Each needs :class and :labels; see\n"
         " ;; setup/relay.example.edn. Apple Reminders, for one, is two tools:\n"
         " ;;   \"rem-read\" {:class :read :labels [\"private\"]\n"
         " ;;               :description \"Read Apple Reminders. Run with [\\\"help\\\"] for the commands.\"\n"
         " ;;               :command [\"/path/to/relay/tools/rem/rem\" \"--mode\" \"read\"]}\n"
         " ;;   \"rem\" {:class :edit :labels [\"private\"]\n"
         " ;;          :description \"Change Apple Reminders. Run with [\\\"help\\\"] for the commands.\"\n"
         " ;;          :command [\"/path/to/relay/tools/rem/rem\" \"--mode\" \"write\"]}\n"
         " ;; Changing your own reminders is :edit: nobody else sees them, and\n"
         " ;; a change can be undone. relay asks you first only once the role has\n"
         " ;; read an outsider's text. If a list is shared with other people, rem\n"
         " ;; stays :act, so every change asks you, and rem-read adds\n"
         " ;; \"contaminated\".\n"
         (if web
           (web-tool web webtool-file)
           (str " :tools {}\n"
                " :grants {}}\n")))))

(defn create!
  "Interviews the operator, shows the file and writes it to `file` only on
  a yes. Returns true when it was written. With the web, webtool.json is
  shown and written beside it, unless one is there already, which is
  kept. `io` may give :home, the operator's home directory, and :webtool,
  the webtool binary to suggest."
  [{:keys [ask say] :as io} file]
  (say "relay needs a few settings. Press Enter to take the suggestion in brackets.\n")
  (let [settings (interview io)
        webtool-file (str (fs/path (fs/parent (fs/absolutize file)) "webtool.json"))
        text (render settings webtool-file)
        web-text (when (and (:web settings) (not (fs/exists? webtool-file)))
                   (webtool-config (:web settings) (or (:home io) (str (fs/home)))))]
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
            true)
        (do (say "Nothing was written.") false)))))
