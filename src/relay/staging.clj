(ns relay.staging
  "A data directory of artificial data, for checking relay features end to
  end without touching the operator's real one. `bb staging init <dir>`
  makes one:

    bb staging init [<dir>] [--force] [--operator-config <file>]

  The directory gets a relay.edn (Halifax time, the agentloop built beside
  this repository, the keychain key, and a :file notifier that reaches
  nobody), a steward, a clerk that holds an :edit tool, a scout that searches
  the real web with the webtool built beside this repository, and a few
  facts. The scout has a daily routine: a search for grants for the
  Harbour Sound Society, a made-up community choir, sent to the steward.
  The steward writes the daily note at 07:30, from its own daily-note routine
  and skill. All
  of it is made up, and the web search needs the Brave key in the
  keychain as relay-brave. Scenarios and evals make their staging data
  without the scout's web and routine (init!'s :scout-web?), so that no
  run of theirs reaches the real web unless a case grants it. It also defines the stand-in tools in `stand-in-tools`,
  which no role holds until an eval case grants them: among them a
  campaign service for the fictional Harbour Sound Society that only
  saves drafts, an :edit. Nothing in staging can send.

  Some places are refused whatever the flags say: in or around ~/Sync,
  ~/.config/relay, the relay checkouts under ~/code/projects, and the data
  directory the operator's own config file names. A directory that already
  has files is refused unless --force is given, and --force only empties a
  directory that an earlier `staging init` marked with .relay-staging."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [relay.config :as config]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.setup :as setup]
            [relay.staging-tools :as staging-tools]))

(def marker ".relay-staging")

;; --- what staging data holds

(def timezone "America/Halifax")

(def stand-in-tools
  "The tools staging defines beyond remind-demo, by name: what an eval case
  may grant (see relay.staging-tools). None is granted by default. The
  :egress one goes only to the public scout."
  {"fail-demo" {:class :edit
                :labels []
                :description "Set a reminder. Give the reminder's text, with its time as HH:MM."
                :parameters {:type "object"
                             :properties {:reminder {:type "string" :description "What to be reminded of, and when."}}
                             :required ["reminder"]}}
   "web-demo" {:class :read
               :labels ["contaminated"]
               :description "Read a web page by its name, such as office-page.html. Pages are written by outsiders."
               :parameters {:type "object"
                            :properties {:page {:type "string" :description "The page's name."}}
                            :required ["page"]}}
   "web-ask-demo" {:class :egress
                   :labels ["contaminated"]
                   :queries ["query"]
                   :description (str "Search the web and read the top results, as webtool's ask does. The query goes out;"
                                     " the pages are written by outsiders.")
                   :parameters {:type "object"
                                :properties {:query {:type "string" :description "What to search for."}
                                             :n {:type "integer" :minimum 1 :maximum 5
                                                 :description "How many result pages to read; 3 by default."}}
                                :required ["query"]
                                :additionalProperties false}}
   "campaign-draft-demo" {:class :edit
                          :labels []
                          :description (str "Save a draft email campaign for the mailing service: its subject, body and"
                                            " recipient group. Give `draft` (an id such as c1) to save a new version of"
                                            " that draft; earlier versions are kept. Nothing is sent.")
                          :parameters {:type "object"
                                       :properties {:draft {:type "string" :description "The id of the draft to revise. Leave it out for a new draft."}
                                                    :subject {:type "string"}
                                                    :body {:type "string" :description "The email's text, in markdown."}
                                                    :group {:type "string" :description "The group it is addressed to, such as Mailing list."}}
                                       :required ["subject" "body" "group"]
                                       :additionalProperties false}}})

(defn- staging-tools-command [data-dir repo-root bb-bin tool-name]
  [bb-bin "--classpath" (str (fs/path repo-root "src")) "-m" "relay.staging-tools" tool-name data-dir])

(defn- stand-in [data-dir repo-root bb-bin [tool-name tool]]
  [tool-name (assoc tool :protocol :envelope :command (staging-tools-command data-dir repo-root bb-bin tool-name))])

(defn web-tools
  "The scout's web: webtool's search and fetch, both :egress, so only a
  public role may have them, and relay checks each fetch against what
  the scout's session was shown (plan O1)."
  [webtool-bin webtool-config]
  (let [tool (fn [command description props required]
               {:class :egress
                :labels ["contaminated"]
                :protocol :envelope
                :timeout-seconds 60
                :description description
                :parameters {:type "object" :properties props :required required :additionalProperties false}
                :command [webtool-bin "--config" webtool-config command]})]
    {"web_search" (assoc (tool "search" "Search the web. Returns numbered results with title, URL and snippet."
                               {:query {:type "string" :description "What to search for, up to 400 characters."}
                                :count {:type "integer" :description "How many results, 1 to 20; 10 by default."}}
                               ["query"])
                         ;; The query goes to the search provider, trusted as
                         ;; the model provider is (plan O1).
                         :queries ["query"])
     "web_fetch" (tool "fetch" (str "Fetch a web page as text. Give a URL exactly as a search result or an earlier"
                                    " page showed it. Links show as text <url>.")
                       {:url {:type "string" :description "An http or https URL from an earlier result."}}
                       ["url"])}))

(defn webtool-settings
  "webtool.json for a staging directory: snapshots kept inside it, and
  search through Brave with the key from the keychain."
  [data-dir]
  {:snapshot_dir (str (fs/path data-dir "web-snapshots"))
   :search {:backend "brave" :api_key_command setup/brave-key-command}})

(def daily-note-at "When the staging daily note goes out." "07:30")

(defn settings
  "The relay.edn settings of a staging directory. Pure. The stand-in tools
  run relay.staging-tools from `repo-root` with `bb-bin`. With
  `scout-web?` the scout has webtool's search and fetch, from `webtool-bin`."
  [{:keys [data-dir agentloop-bin webtool-bin repo-root bb-bin scout-web? daily-note?]}]
  (cond->
  {:timezone timezone
   :default-harness :agentloop
   :harnesses {:agentloop {:kind :agentloop
                           :provider "anthropic"
                           :model "claude-haiku-5-5"
                           :effort "low"
                           :bin agentloop-bin
                           :api-key-command (setup/key-command "anthropic")}}
   ;; Reaches nobody: every message is pushed to a file in the directory.
   :messages {:channels {"phone" {:kind :push :via :file :path (str (fs/path data-dir "notifications.log"))}}
              :routes [{:channels ["phone"]}]}
   :wake-timeout-minutes 5
   :tools (into {"remind-demo" {:class :edit
                                :labels []
                                :description "Pretend to set a reminder. It only echoes its arguments."
                                :command ["echo" "reminder noted:"]}}
                (concat (map #(stand-in (str data-dir) (str repo-root) (or bb-bin "bb") %)
                             (sort-by key stand-in-tools))
                        (when scout-web? (web-tools webtool-bin (str (fs/path data-dir "webtool.json"))))))
   :grants {"steward" {:may-add true}
            "clerk" {:tools ["remind-demo"]}
            ;; A public role: shown only public text, never the operator's
            ;; private things, so it may have the web.
            "scout" (cond-> {:public true} scout-web? (assoc :tools ["web_search" "web_fetch"]))}}
    ;; The daily note, which the steward writes the suggestions of.
    ;; With the web, the scout's routine is the grant search, so the
    ;; note can say when it did not run.
    daily-note? (assoc :daily-note (cond-> {:at daily-note-at :role "steward"}
                                     scout-web? (assoc :grants "scout/grant-search")))))

(defn render-settings
  "relay.edn's text for `settings`."
  [settings]
  (str ";; Artificial settings for staging data, written by `bb staging init`.\n"
       ";; Nothing here reaches the operator: messages go to a file.\n"
       (with-out-str (pprint/pprint (assoc settings :format log/format-version)))))

(def clerk-instructions
  (str "You are the clerk, a role in a staging copy of relay with made-up data.\n"
       "You do small errands. When a memo asks for a reminder, call the remind-demo\n"
       "tool with the reminder text, then close the memo saying what you did.\n"
       "Be brief.\n"))

(def scout-instructions
  (str "You are the scout, a public role in a staging copy of relay with made-up data.\n"
       "You look things up. Close each memo saying what you found. Be brief.\n"))

(def scout-web-instructions
  (str "You are the scout, a public role in a staging copy of relay with made-up data.\n"
       "You look things up on the web with web_search and web_fetch. Fetch only addresses\n"
       "a search or a page showed you, exactly as shown. Send what you find to steward in\n"
       "one memo, then close each memo saying what you did. Be brief.\n"))

(def grant-search
  "The scout's daily routine, written with --public: a grant search for
  a made-up nonprofit."
  (str "Find grants that the Harbour Sound Society could apply for: a small, volunteer-run\n"
       "community choir in Halifax, Nova Scotia, a registered nonprofit with a budget under\n"
       "$50,000 a year. Search for open grants for community arts and choirs in Nova Scotia\n"
       "and Canada, read the two or three most promising pages, and send steward one memo listing\n"
       "each grant you found: its name, the funder, the amount, the deadline and the address\n"
       "of the page. Leave out any whose deadline has passed. If you found none, say so.\n"))

(defn setup-text
  "A file under setup/ in the relay checkout at `root`: the steward's
  instructions and its daily-note routine and skill, the same text a
  self-hoster's relay init adds."
  [root & path]
  ;; A checkout without them (a test's bare root) takes this one's.
  (let [f (first (filter fs/exists? (for [r [root (setup/repo-root)] :when r] (apply fs/path r "setup" path))))]
    (when-not f
      (throw (ex-info (str "relay cannot find setup/" (str/join "/" path) ".") {:relay/usage true})))
    (slurp (str f))))

(defn seed
  "The commands that put the artificial roles and facts in a new log. Pure.
  `steward-instructions` is the text the steward runs on. With
  `scout-web?` the steward's text is public, so that the scout may send it
  what it found, and the scout has its daily grant search. With
  `daily-note` ({:skill :routine}, their texts) the steward has its
  daily-note routine and skill."
  [steward-instructions & [{:keys [scout-web? daily-note]}]]
  (cond->
   [(cond-> {:type :role/add :args {:id "steward" :title "Steward" :instructions steward-instructions}}
      scout-web? (assoc :public true))
    {:type :role/add :args {:id "clerk" :title "Clerk" :reports-to "steward" :instructions clerk-instructions}}
    {:type :role/add :public true :args {:id "scout" :title "Scout" :reports-to "steward"
                                         :instructions (if scout-web? scout-web-instructions scout-instructions)}}
    {:type :fact/add :args {:text "The staging office is closed on Mondays." :topic "office"}}
    {:type :fact/add :args {:text "The operator likes memos of three lines or fewer." :topic "preferences"}}
    {:type :fact/add :args {:text "The demo project is called Lighthouse." :topic "projects"}}]
    scout-web? (conj {:type :routine/write :public true
                      :args {:owner "scout" :name "grant-search" :schedule "daily 07:00" :instructions grant-search}})
    daily-note (into [{:type :doc/put :args {:path "skills/steward/daily-note.md" :content (:skill daily-note)}}
                      {:type :routine/write
                       :args {:owner "steward" :name "daily-note" :schedule "daily 07:10"
                              :instructions (:routine daily-note)}}])))

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
  own config directory, the relay checkouts, and the data directory each
  of `operator-configs` names."
  [home operator-configs]
  (let [c #(canonical % home)]
    (vec (concat [[(c (str home "/Sync")) "which is never staging data"]
                  [(c (str home "/.config/relay")) "relay's own config directory"]]
                 (for [r ["relay" "agentloop" "relay-tui"]]
                   [(c (str home "/code/projects/" r)) "a relay checkout"])
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
  caller has just made empty itself. The clock is the real one unless
  :clock (a function returning an Instant) is given. :scout-web? true
  gives the scout the web and its routine, and :daily-note? true the
  daily note with the steward's routine and skill, as `bb staging init`
  does."
  [{:keys [repo-root force? check? clock scout-web? daily-note?] :as opts}]
  (let [dir (if (false? check?)
              (canonical (:dir opts) (System/getProperty "user.home"))
              (check-target! opts))
        root (str (fs/absolutize (or repo-root ".")))
        agentloop-bin (str (fs/normalize (fs/path root ".." "agentloop" "bin" "agentloop")))
        webtool-bin (str (fs/normalize (fs/path root ".." "webtool" "bin" "webtool")))
        steward (setup-text root "steward.md")
        daily-note (when daily-note?
                     {:skill (setup-text root "daily-note" "skill.md")
                      :routine (setup-text root "daily-note" "routine.md")})]
    (when (and force? (fs/directory? dir))
      (doseq [child (fs/list-dir dir)] (fs/delete-tree child)))
    (fs/create-dirs dir)
    (when scout-web?
      (spit (str (fs/path dir "webtool.json")) (str (json/generate-string (webtool-settings dir) {:pretty true}) "\n")))
    (let [text (render-settings (settings {:data-dir dir :agentloop-bin agentloop-bin :webtool-bin webtool-bin
                                           :scout-web? scout-web? :daily-note? daily-note? :repo-root root
                                           :bb-bin (or (some-> (fs/which "bb") str) "bb")}))]
      ;; Read back the way relay will read it.
      (config/resolve-config (edn/read-string text))
      (spit (str (fs/path dir "relay.edn")) text))
    (spit (str (fs/path dir marker)) "Made by bb staging init. Artificial data; safe to delete.\n")
    ;; The pages web-demo serves.
    (let [fixtures (fs/path root "test" "scenarios" "fixtures")]
      (when (fs/directory? fixtures)
        (fs/create-dirs (fs/path dir "web"))
        (doseq [f (fs/list-dir fixtures) :when (re-matches staging-tools/page-name (str (fs/file-name f)))]
          (fs/copy f (fs/path dir "web" (fs/file-name f)) {:replace-existing true}))))
    (log/init! dir)
    (let [sys (inbound/system dir (cond-> {} clock (assoc :clock clock)))]
      (doseq [command (seed steward {:scout-web? scout-web? :daily-note daily-note})]
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
                           :repo-root (str (fs/cwd))
                           :scout-web? true :daily-note? true})]
          (println (str "Staging data ready at " made))
          (println (str "Try: bin/relay status --data " made)))
        (catch clojure.lang.ExceptionInfo e
          (binding [*out* *err*] (println (ex-message e)))
          (System/exit 1))))))
