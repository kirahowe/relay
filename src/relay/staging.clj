(ns relay.staging
  "A data directory of artificial data, for checking relay features end to
  end without touching the operator's real one. `bb staging init <dir>`
  makes one:

    bb staging init [<dir>] [--force] [--operator-config <file>]

  The directory gets a relay.edn (Halifax time, the agentloop built beside
  this repository, the keychain key, and a :file notifier that reaches
  nobody), a hub, a clerk that holds an :act tool, and a few facts. All of
  it is made up.

  Some places are refused whatever the flags say: in or around ~/Sync,
  ~/.config/relay, ~/Library/Application Support/relay, the live checkouts under ~/code/projects, and the data
  directory the operator's own config file names. A directory that already has files is refused unless
  --force is given, and --force only empties a directory that an earlier
  `staging init` marked with .relay-staging."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [relay.config :as config]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.setup :as setup]))

(def marker ".relay-staging")

;; --- what staging data holds

(defn settings
  "The relay.edn settings of a staging directory. Pure."
  [{:keys [data-dir agentloop-bin]}]
  {:timezone "America/Halifax"
   :default-harness :agentloop
   :harnesses {:agentloop {:kind :agentloop
                           :provider "anthropic"
                           :model "claude-haiku-5-5"
                           :effort "low"
                           :bin agentloop-bin
                           :api-key-command (setup/key-command "anthropic")}}
   ;; Reaches nobody: notifications are appended to a file in the directory.
   :notify {:kind :file :path (str (fs/path data-dir "notifications.log"))}
   :wake-timeout-minutes 5
   :tools {"remind-demo" {:class :act
                          :labels []
                          :description "Pretend to set a reminder. It only echoes its arguments."
                          :command ["echo" "reminder noted:"]}}
   :grants {"hub" {:may-add true}
            "clerk" {:tools ["remind-demo"]}
            ;; A public role: shown only public text, never the operator's
            ;; private data, so it may send data out unheld.
            "scout" {:public true}}})

(defn render-settings
  "relay.edn's text for `settings`."
  [settings]
  (str ";; Artificial settings for staging data, written by `bb staging init`.\n"
       ";; Nothing here reaches the operator: notifications go to a file.\n"
       (with-out-str (pprint/pprint (assoc settings :format log/format-version)))))

(def clerk-instructions
  (str "You are the clerk, a role in a staging copy of relay with made-up data.\n"
       "You do small errands. When a memo asks for a reminder, call the remind-demo\n"
       "tool with the reminder text, then close the memo saying what you did.\n"
       "Be brief.\n"))

(def scout-instructions
  (str "You are the scout, a public role in a staging copy of relay with made-up data.\n"
       "You look things up. Close each memo saying what you found. Be brief.\n"))

(defn seed
  "The commands that put the artificial roles and facts in a new log. Pure.
  `hub-instructions` is the text the hub runs on."
  [hub-instructions]
  [{:type :role/add :args {:id "hub" :title "Hub" :instructions hub-instructions}}
   {:type :role/add :args {:id "clerk" :title "Clerk" :reports-to "hub" :instructions clerk-instructions}}
   {:type :role/add :public true :args {:id "scout" :title "Scout" :reports-to "hub" :instructions scout-instructions}}
   {:type :fact/add :args {:text "The staging office is closed on Mondays." :topic "office"}}
   {:type :fact/add :args {:text "The operator likes memos of three lines or fewer." :topic "preferences"}}
   {:type :fact/add :args {:text "The demo project is called Lighthouse." :topic "projects"}}])

;; --- where it may go

(defn- under? [path root]
  (or (= path root) (str/starts-with? path (str root "/"))))

(defn target-problem
  "Why `dir` may not be initialized, or nil when it may. Pure: every path
  arrives canonical. `protected` is a vector of [path why] that `dir` may
  not be in, equal to or around. `entries` is what the directory holds now
  (nil when it does not exist) and `marked?` whether it carries the staging
  marker."
  [{:keys [dir protected entries marked? force?]}]
  (or
   (when (= "/" dir) "The root directory is not a staging directory.")
   (some (fn [[path why]]
           (when (or (under? dir path) (under? path dir))
             (str dir " is in or around " path ", " why ".")))
         protected)
   (cond
     (empty? entries) nil

     (not force?)
     (str dir " is not empty. Pass --force to start it afresh; that only works on a directory an earlier `staging init` made.")

     (not marked?)
     (str dir " is not empty and was not made by `staging init` (it has no " marker "), so --force will not touch it."))))

;; --- the edge

(defn- usage-error [msg] (ex-info msg {:relay/usage true}))

(defn- expand-home [path home]
  (cond (= "~" path) home
        (str/starts-with? path "~/") (str home "/" (subs path 2))
        :else path))

(defn canonical
  "An absolute path with symbolic links resolved as far as the path exists,
  so a link into ~/Sync is seen for what it is."
  [path home]
  (let [p (-> (expand-home path home) fs/absolutize fs/normalize)
        existing (first (filter fs/exists? (take-while some? (iterate fs/parent p))))]
    (if existing
      (str (fs/normalize (fs/path (fs/real-path existing) (fs/relativize existing p))))
      (str p))))

(defn operator-data-dir
  "The data directory named in the operator's config file, or nil. Reads
  :data and nothing else."
  [config-file home]
  (when (fs/exists? config-file)
    (let [c (try (edn/read-string (slurp (str config-file))) (catch Exception _ nil))]
      (when (and (map? c) (string? (:data c)) (not (str/blank? (:data c))))
        (canonical (:data c) home)))))

(defn default-operator-config [home] (str home "/.config/relay/config.edn"))

(defn default-dir [home] (str home "/code/projects/.relay-staging"))

(defn protected-roots
  "What staging data may never be in or around, canonical: ~/Sync, relay's
  own config and deploy-control directories, the live checkouts, and the
  data directory each of `operator-configs` names."
  [home operator-configs]
  (let [c #(canonical % home)]
    (vec (concat [[(c (str home "/Sync")) "which is never staging data"]
                  [(c (str home "/.config/relay")) "relay's own config directory"]
                  [(c (str home "/Library/Application Support/relay")) "where deploys keep their records and the pause file"]]
                 (for [r ["relay" "agentloop" "acp-tui"]]
                   [(c (str home "/code/projects/" r)) "a live checkout"])
                 (for [config operator-configs
                       :let [d (operator-data-dir config home)]
                       :when d]
                   [d "the data directory named by the operator's config"])))))

(defn check-target!
  "Throws when `dir` may not be used for staging data. Returns its
  canonical path."
  [{:keys [dir home operator-config force?]}]
  (let [home (or home (System/getProperty "user.home"))
        dir (canonical dir home)
        problem (target-problem
                 {:dir dir
                  ;; The operator's own config is always read; a file given
                  ;; with --operator-config adds to it and never replaces it.
                  :protected (protected-roots home (distinct (remove nil? [(default-operator-config home) operator-config])))
                  :entries (when (fs/directory? dir) (mapv str (fs/list-dir dir)))
                  :marked? (fs/exists? (fs/path dir marker))
                  :force? force?})]
    (when problem (throw (usage-error problem)))
    dir))

(defn init!
  "Makes a staging data directory and returns its path. `repo-root` is the
  relay checkout whose sibling agentloop the harness runs. Options as for
  check-target!, and :check? false skips the checks for a directory the
  caller has just made empty itself. The clock is the real one."
  [{:keys [repo-root force? check?] :as opts}]
  (let [dir (if (false? check?)
              (canonical (:dir opts) (System/getProperty "user.home"))
              (check-target! opts))
        root (str (fs/absolutize (or repo-root ".")))
        agentloop-bin (str (fs/normalize (fs/path root ".." "agentloop" "bin" "agentloop")))
        hub-file (fs/path root "setup" "hub.md")
        hub (if (fs/exists? hub-file) (slurp (str hub-file)) "You run relay for the operator. Be brief.")]
    (when (and force? (fs/directory? dir))
      (doseq [child (fs/list-dir dir)] (fs/delete-tree child)))
    (fs/create-dirs dir)
    (let [text (render-settings (settings {:data-dir dir :agentloop-bin agentloop-bin}))]
      ;; Read back the way relay will read it.
      (config/resolve-config (edn/read-string text))
      (spit (str (fs/path dir "relay.edn")) text))
    (spit (str (fs/path dir marker)) "Made by bb staging init. Artificial data; safe to delete.\n")
    (log/init! dir)
    (let [sys (inbound/system dir)]
      (doseq [command (seed hub)]
        (inbound/submit! sys (assoc command :actor "operator"))))
    dir))

(def ^:private cli-spec
  {:coerce {:force :boolean :operator-config :string}})

(defn -main [& args]
  (let [{[sub dir] :args opts :opts} (cli/parse-args args cli-spec)
        home (System/getProperty "user.home")]
    (if-not (= "init" sub)
      (do (println "usage: bb staging init [<dir>] [--force] [--operator-config <file>]") (System/exit 1))
      (try
        (let [made (init! {:dir (or dir (default-dir home)) :force? (:force opts)
                           :operator-config (:operator-config opts)
                           :repo-root (str (fs/cwd))})]
          (println (str "Staging data ready at " made))
          (println (str "Try: bin/relay status --data " made)))
        (catch clojure.lang.ExceptionInfo e
          (binding [*out* *err*] (println (ex-message e)))
          (System/exit 1))))))
