(ns relay.setup
  "The questions `relay init` asks to write a new relay.edn. Each has a
  suggestion that Enter accepts, and nothing is written until the operator
  has seen the whole file and said yes."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [relay.config :as config]
            [relay.log :as log])
  (:import (java.time ZoneId)))

(def efforts ["low" "medium" "high" "xhigh" "max"])

(def default-models {"anthropic" "claude-opus-5-5"})

(defn- key-command [provider]
  ["security" "find-generic-password" "-s" (str "relay-" provider) "-w"])

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
                 {:kind (keyword kind)})]
    {:timezone zone
     :default-harness :agentloop
     :harnesses {:agentloop {:kind :agentloop :provider provider :model model :effort effort
                             :api-key-command (str/split api-key #"\s+")}}
     :notify notify}))

(defn render
  "relay.edn's text for the settings `interview` returns."
  [{:keys [timezone harnesses notify]}]
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
         " ;; setup/relay.example.edn.\n"
         " :tools {}\n"
         " :grants {}}\n")))

(defn create!
  "Interviews the operator, shows the file and writes it to `file` only on
  a yes. Returns true when it was written."
  [{:keys [ask say] :as io} file]
  (say "relay needs a few settings. Press Enter to take the suggestion in brackets.\n")
  (let [text (render (interview io))]
    ;; The file is read back the way relay will read it, so a setting it
    ;; would refuse is caught here rather than at the first wake.
    (config/resolve-config (edn/read-string text))
    (say (str "\nThis will be " file ":\n\n" text))
    (let [answer (ask "Write it? [y/N] ")]
      (if (#{"y" "yes"} (some-> answer str/trim str/lower-case))
        (do (fs/create-dirs (fs/parent file)) (spit file text) true)
        (do (say "Nothing was written.") false)))))
