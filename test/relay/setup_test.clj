(ns relay.setup-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.config :as config]
            [relay.setup :as setup]))

(defn- scripted
  "An io that answers the questions with `answers` in order (nil once they
  run out) and keeps what was asked and said."
  [answers]
  (let [left (atom answers) asked (atom []) said (atom [])]
    {:ask (fn [prompt] (swap! asked conj prompt) (let [a (first @left)] (swap! left rest) a))
     :say #(swap! said conj %)
     :asked asked
     :said said}))

(defn- create [answers & [extra]]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-setup-test"}))
        file (str dir "/relay.edn")
        io (merge (scripted answers) {:home (str dir "/home")} extra)
        written (setup/create! io file)]
    {:written written
     :io io
     :settings (when (fs/exists? file) (edn/read-string (slurp file)))
     :dir dir}))

(deftest the-suggestions-make-a-config-relay-accepts
  (let [{:keys [written settings dir]} (create ["" "" "" "" "" "" "" "y"])]
    (is written)
    (is (= {} (:tools settings)) "no webtool found, so no web")
    (is (= {"steward" {:may-add true}} (:grants settings)))
    (is (= {:at "07:30" :role "steward"} (:daily-note settings)) "the daily note is on, written by the steward")
    (is (= {:channels {"push" {:kind :push :via :macos}} :routes [{:channels ["push"]}]} (:messages settings)))
    (is (= "anthropic" (get-in settings [:harnesses :agentloop :provider])))
    (is (= "claude-opus-5-5" (get-in settings [:harnesses :agentloop :model])))
    (is (= ["security" "find-generic-password" "-s" "relay-anthropic" "-w"]
           (get-in settings [:harnesses :agentloop :api-key-command])))
    (is (string? (:timezone settings)))
    (is (map? (config/load-config dir)))))

(deftest nothing-is-written-without-a-yes
  (doseq [answer ["" "n" "no" "sure" nil]]
    (let [{:keys [written settings io]} (create ["" "" "" "" "" "" "" answer])]
      (is (not written) (pr-str answer))
      (is (nil? settings) (pr-str answer))
      (is (some #(str/includes? % "Nothing was written") @(:said io)) (pr-str answer)))))

(deftest a-bad-answer-is-asked-again
  (let [{:keys [settings io]} (create ["Mars/Olympus" "Europe/Paris" "" "" "extreme" "high" "" "" "" "y"])]
    (is (= "Europe/Paris" (:timezone settings)))
    (is (= "high" (get-in settings [:harnesses :agentloop :effort])))
    (is (some #(str/includes? % "not a time zone") @(:said io)))
    (is (some #(str/includes? % "Answer one of") @(:said io)))))

(deftest openai-has-no-model-to-suggest
  (testing "a blank model is refused rather than left empty"
    (let [{:keys [settings io]} (create ["" "openai" "" "gpt-x" "" "" "none" "" "y"])]
      (is (= "gpt-x" (get-in settings [:harnesses :agentloop :model])))
      (is (= ["security" "find-generic-password" "-s" "relay-openai" "-w"]
             (get-in settings [:harnesses :agentloop :api-key-command])))
      (is (= {:channels {} :routes []} (:messages settings)) "none: the client alone")
      (is (some #(str/includes? % "needs an answer") @(:said io))))))

(deftest ntfy-asks-where-the-server-is
  (let [{:keys [settings]} (create ["" "" "" "" "" "ntfy" "localhost" "http://nas:2586" "alerts" "" "y"])]
    (is (= {"push" {:kind :push :via :ntfy :url "http://nas:2586" :topic "alerts"}}
           (get-in settings [:messages :channels])))))

(deftest the-scout-gets-the-web-when-webtool-is-there
  (let [{:keys [written settings dir io]} (create ["" "" "" "" "" "" "" "" "y"] {:webtool "/opt/webtool/bin/webtool"})
        webtool-file (str (fs/path dir "webtool.json"))
        web (get-in settings [:tools "web"])]
    (is written)
    (is (some #(str/includes? % "used only to send your searches to Brave") @(:said io))
        "setup says what the key is for")
    (is (some #(str/includes? % "Brave Search API key") @(:asked io)))
    (is (= {:class :egress :labels ["contaminated"] :protocol :envelope}
           (select-keys web [:class :labels :protocol])))
    (is (= ["/opt/webtool/bin/webtool" "--config" webtool-file "ask"] (:command web)))
    (is (= {"steward" {:may-add true} "scout" {:public true :tools ["web"]}} (:grants settings))
        "the web sends searches out, so only a public role has it")
    (is (= {"snapshot_dir" (str (fs/absolutize dir) "/webtool/snapshots")
            "search" {"backend" "brave" "api_key_command" setup/brave-key-command}}
           (json/parse-string (slurp webtool-file))))
    (is (= ["web"] (config/role-tools (config/load-config dir) "scout"))))
  (testing "another key command, and the web left out"
    (let [{:keys [dir]} (create ["" "" "" "" "" "" "" "pass show brave" "y"] {:webtool "/opt/webtool"})]
      (is (= ["pass" "show" "brave"] (get-in (json/parse-string (slurp (str dir "/webtool.json"))) ["search" "api_key_command"]))))
    (let [{:keys [settings dir]} (create ["" "" "" "" "" "" "none" "y"] {:webtool "/opt/webtool"})]
      (is (= {} (:tools settings)))
      (is (not (fs/exists? (str dir "/webtool.json"))))))
  (testing "a relative path is asked again"
    (let [{:keys [settings io]} (create ["" "" "" "" "" "" "bin/webtool" "/w/webtool" "" "y"])]
      (is (= "/w/webtool" (first (get-in settings [:tools "web" :command]))))
      (is (some #(str/includes? % "full path to the webtool binary") @(:said io))))))

(deftest input-ending-early-writes-nothing
  (let [dir (str (fs/create-temp-dir {:prefix "relay-setup-test"}))
        file (str dir "/relay.edn")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nothing was written"
                          (setup/create! (scripted ["" ""]) file)))
    (is (not (fs/exists? file)))))

(deftest relay-refuses-a-config-missing-what-it-needs
  (let [complete {:timezone "UTC" :default-harness :agentloop
                  :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"]}}}]
    (is (map? (config/resolve-config complete)))
    (doseq [[what broken] [["no timezone" (dissoc complete :timezone)]
                           ["a bad timezone" (assoc complete :timezone "Nowhere")]
                           ["the old :notify" (assoc complete :notify {:kind :none})]
                           ["an ntfy without a topic"
                            (assoc complete :messages {:channels {"p" {:kind :push :via :ntfy :url "http://x"}} :routes []})]
                           ["no harnesses" (dissoc complete :harnesses)]
                           ["a default harness that is not defined" (assoc complete :default-harness :other)]
                           ["an agentloop with no key command"
                            (update-in complete [:harnesses :agentloop] dissoc :api-key-command)]]]
      (is (thrown? clojure.lang.ExceptionInfo (config/resolve-config broken)) what))))

(deftest a-budget-is-optional-and-checked
  (let [complete {:timezone "UTC" :default-harness :agentloop
                  :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"]}}}]
    (is (nil? (:budget (config/resolve-config complete))) "no budget unless one is set")
    (doseq [ok [15 15.5 0.01]]
      (is (= {:weekly-usd ok} (:budget (config/resolve-config (assoc complete :budget {:weekly-usd ok})))) (pr-str ok)))
    (doseq [bad [nil 15 {} {:weekly-usd 0} {:weekly-usd -5} {:weekly-usd "15"} {:weekly-usd 1/2}
                 {:weekly-usd ##Inf} {:weekly 15} {:weekly-usd 15 :hard true}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":budget is \{:weekly-usd 15\}"
                            (config/resolve-config (assoc complete :budget bad)))
          (pr-str bad)))))

(defn- uncommented?
  "Whether `on` is `off` with ;; removed from some lines and nothing else
  changed: what turning a block on by hand does."
  [off on]
  (let [a (str/split-lines off) b (str/split-lines on)]
    (and (= (count a) (count b))
         (every? (fn [[x y]] (or (= x y) (= y (str/replace-first x #"^( *);; " "$1")))) (map vector a b)))))

(deftest init-writes-every-section-commented-out-until-filled-in
  (let [{:keys [dir]} (create ["" "" "" "" "" "" "" "y"])
        text (slurp (str dir "/relay.edn"))]
    (doseq [[what needle] [["mail to you" ";; \"mail\" {:kind :email"]
                           ["the mailbox" ";; \"mailbox\""]
                           ["GitHub" ";; \"repos\""]
                           ["your calendar" ";; \"calendar\" {:kind :ics"]
                           ["the shell" ";; :house {:mounts"]
                           ["the shell's grant" ";; :shell true"]
                           ["folders to tidy" ";; :files"]]]
      (is (str/includes? text needle) what))
    (is (= "steward" (:dead-letter-handler (edn/read-string text)))
        "the steward is named, so a later default cannot move it")))

(deftest each-commented-block-turns-on-by-itself-by-removing-its-semicolons
  (let [settings (fn [messages web]
                   {:timezone "UTC" :messages messages :web web
                    :harnesses {:agentloop {:provider "anthropic" :model "m" :effort "medium" :api-key-command ["true"]}}})
        reads (fn [text] (config/resolve-config (config/expand-paths (edn/read-string text) "/Users/you")))]
    (doseq [messages [{:channels {"push" {:kind :push :via :macos}} :routes [{:channels ["push"]}]}
                       {:channels {} :routes []}]
            web [nil {:bin "/opt/webtool" :key-command ["true"]}]
            on (concat (map hash-set setup/optional-blocks) [(set setup/optional-blocks)])]
      (let [s (settings messages web)
            off (setup/render s "/d/webtool.json")
            text (setup/render s "/d/webtool.json" on)
            where (pr-str on (keys (:channels messages)) (some? web))]
        (is (not= off text) where)
        (is (uncommented? off text) (str where ": only ;; is removed"))
        (is (map? (reads text)) where)
        (when (= #{:mail} on)
          (is (= (cond-> [] (seq (:channels messages)) (conj {:channels ["push"]}) true (conj {:channels ["mail"]}))
                 (get-in (reads text) [:messages :routes]))
              "the mail route names only channels that are there"))))
    (is (map? (reads (setup/render (settings {:channels {} :routes []} nil) "/d/webtool.json"))))))

(deftest the-starter-roles-are-the-steward-and-with-the-web-a-scout
  (let [texts {:steward "s" :scout "c" :skill "k" :routine "r"}
        without (setup/starter-commands texts false)
        with (setup/starter-commands texts true)]
    (is (= [:role/add :doc/put :routine/write] (map :type without)))
    (is (= "steward" (get-in (first without) [:args :id])))
    (is (= "skills/steward/daily-note.md" (get-in (second without) [:args :path])))
    (is (= {:owner "steward" :name "daily-note"} (select-keys (:args (nth without 2)) [:owner :name])))
    (is (= {:id "scout" :public true} {:id (get-in (last with) [:args :id]) :public (:public (last with))}))
    (is (:public (first with)) "the scout sees only public text, so it can find the steward")
    (testing "the texts in setup/ are all there"
      (is (every? string? (vals (setup/starter-texts (setup/repo-root))))))))

(deftest init-ends-with-three-next-steps
  (let [lines (setup/next-steps {:key-ok? false :provider "anthropic" :web? true :brave-ok? false :data-dir "/d"})
        text (str/join "\n" lines)]
    (is (str/includes? text "security add-generic-password -a \"$USER\" -s relay-anthropic -w"))
    (is (str/includes? text "-s relay-brave -w"))
    (is (= 3 (count (filter #(re-find #"^  \d\. " %) lines))))
    (is (str/includes? text "/d/relay.edn")))
  (let [text (str/join "\n" (setup/next-steps {:key-ok? true :web? false :data-dir "/d"}))]
    (is (str/includes? text "key command works"))
    (is (not (str/includes? text "Brave")))))

(deftest a-key-command-is-checked-without-showing-the-key
  (is (setup/command-works? ["echo" "secret"]))
  (is (not (setup/command-works? ["false"])))
  (is (not (setup/command-works? ["true"])) "printing nothing is no key")
  (is (not (setup/command-works? ["/nonexistent/command"]))))
