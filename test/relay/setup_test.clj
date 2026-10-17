(ns relay.setup-test
  (:require [babashka.fs :as fs]
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

(defn- create [answers]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-setup-test"}))
        file (str dir "/relay.edn")
        io (scripted answers)
        written (setup/create! io file)]
    {:written written
     :io io
     :settings (when (fs/exists? file) (edn/read-string (slurp file)))
     :dir dir}))

(deftest the-suggestions-make-a-config-relay-accepts
  (let [{:keys [written settings dir]} (create ["" "" "" "" "" "" "y"])]
    (is written)
    (is (= {:kind :macos} (:notify settings)))
    (is (= "anthropic" (get-in settings [:harnesses :agentloop :provider])))
    (is (= "claude-opus-5-5" (get-in settings [:harnesses :agentloop :model])))
    (is (= ["security" "find-generic-password" "-s" "relay-anthropic" "-w"]
           (get-in settings [:harnesses :agentloop :api-key-command])))
    (is (string? (:timezone settings)))
    (is (map? (config/load-config dir)))))

(deftest nothing-is-written-without-a-yes
  (doseq [answer ["" "n" "no" "sure" nil]]
    (let [{:keys [written settings io]} (create ["" "" "" "" "" "" answer])]
      (is (not written) (pr-str answer))
      (is (nil? settings) (pr-str answer))
      (is (some #(str/includes? % "Nothing was written") @(:said io)) (pr-str answer)))))

(deftest a-bad-answer-is-asked-again
  (let [{:keys [settings io]} (create ["Mars/Olympus" "Europe/Paris" "" "" "extreme" "high" "" "" "y"])]
    (is (= "Europe/Paris" (:timezone settings)))
    (is (= "high" (get-in settings [:harnesses :agentloop :effort])))
    (is (some #(str/includes? % "not a time zone") @(:said io)))
    (is (some #(str/includes? % "Answer one of") @(:said io)))))

(deftest openai-has-no-model-to-suggest
  (testing "a blank model is refused rather than left empty"
    (let [{:keys [settings io]} (create ["" "openai" "" "gpt-x" "" "" "none" "y"])]
      (is (= "gpt-x" (get-in settings [:harnesses :agentloop :model])))
      (is (= ["security" "find-generic-password" "-s" "relay-openai" "-w"]
             (get-in settings [:harnesses :agentloop :api-key-command])))
      (is (= {:kind :none} (:notify settings)))
      (is (some #(str/includes? % "needs an answer") @(:said io))))))

(deftest ntfy-asks-where-the-server-is
  (let [{:keys [settings]} (create ["" "" "" "" "" "ntfy" "localhost" "http://nas:2586" "alerts" "y"])]
    (is (= {:kind :ntfy :url "http://nas:2586" :topic "alerts"} (:notify settings)))))

(deftest input-ending-early-writes-nothing
  (let [dir (str (fs/create-temp-dir {:prefix "relay-setup-test"}))
        file (str dir "/relay.edn")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nothing was written"
                          (setup/create! (scripted ["" ""]) file)))
    (is (not (fs/exists? file)))))

(deftest relay-refuses-a-config-missing-what-it-needs
  (let [complete {:timezone "UTC" :default-harness :agentloop
                  :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"]}}
                  :notify {:kind :none}}]
    (is (map? (config/resolve-config complete)))
    (doseq [[what broken] [["no timezone" (dissoc complete :timezone)]
                           ["a bad timezone" (assoc complete :timezone "Nowhere")]
                           ["no notify" (dissoc complete :notify)]
                           ["an ntfy without a topic" (assoc complete :notify {:kind :ntfy :url "http://x"})]
                           ["no harnesses" (dissoc complete :harnesses)]
                           ["a default harness that is not defined" (assoc complete :default-harness :other)]
                           ["an agentloop with no key command"
                            (update-in complete [:harnesses :agentloop] dissoc :api-key-command)]]]
      (is (thrown? clojure.lang.ExceptionInfo (config/resolve-config broken)) what))))

(deftest a-budget-is-optional-and-checked
  (let [complete {:timezone "UTC" :default-harness :agentloop
                  :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"]}}
                  :notify {:kind :none}}]
    (is (nil? (:budget (config/resolve-config complete))) "no budget unless one is set")
    (doseq [ok [15 15.5 0.01]]
      (is (= {:weekly-usd ok} (:budget (config/resolve-config (assoc complete :budget {:weekly-usd ok})))) (pr-str ok)))
    (doseq [bad [nil 15 {} {:weekly-usd 0} {:weekly-usd -5} {:weekly-usd "15"} {:weekly-usd 1/2}
                 {:weekly-usd ##Inf} {:weekly 15} {:weekly-usd 15 :hard true}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":budget is \{:weekly-usd 15\}"
                            (config/resolve-config (assoc complete :budget bad)))
          (pr-str bad)))))
