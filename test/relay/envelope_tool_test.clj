(ns relay.envelope-tool-test
  "External tools that speak the envelope protocol, run against a stand-in
  in test/fixtures/envelope-tool."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.config :as config]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.tools :as tools]))

(def fixture (str (fs/absolutize "test/fixtures/envelope-tool")))

(def schema {:type "object" :properties {:question {:type "string"}} :required ["question"]
             :additionalProperties false})

(defn- tool [mode & {:as more}]
  (merge {:class :read :labels ["private"] :protocol :envelope :description "Ask the mail."
          :command [fixture mode] :parameters schema}
         more))

(defn- with-tools [tools f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-envelope"}))
        data (str dir "/data")]
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :a
                   :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}
                   :notify {:kind :none} :tools tools :grants {"clerk" {:tools (vec (keys tools))}}}))
    (let [sys (inbound/system data)]
      (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title "C" :instructions "x"}})
      (try (f sys) (finally (fs/delete-tree dir))))))

(deftest an-envelope-tool-gets-the-call-and-its-labels-come-back
  (with-tools {"mail" (tool "echo")}
    (fn [sys]
      (let [{:keys [ok labels]} (tools/call! sys "clerk" "mail" {:question "When?"} ["private"] "toolu_7")]
        (is (= "asked When? with [\"private\"] as toolu_7" ok) "the input, the session's labels and the call id")
        (is (= ["contaminated" "private"] labels) "its static labels and the ones it reported"))
      (testing "relay checks the input against the tool's schema first"
        (is (= "The input has an argument this tool does not take. It takes question." (:error (tools/call! sys "clerk" "mail" {:question "q" :q "x"} [] "c"))))
        (is (= "The input needs question." (:error (tools/call! sys "clerk" "mail" {} [] "c")))))
      (testing "the policy and the harness see the configured schema"
        (is (= schema (:parameters (first (filter #(= "mail" (:name %)) (tools/manifest (:config sys) "clerk")))))))))) 

(deftest anything-but-one-exact-envelope-is-a-plain-failure
  (with-tools (into {} (for [m ["exit" "two" "dup" "case" "extra" "badlabel" "noisy"]] [m (tool m)]))
    (fn [sys]
      (doseq [m ["exit" "two" "dup" "case" "extra" "badlabel" "noisy"]]
        (let [{:keys [error labels]} (tools/call! sys "clerk" m {:question "q"} [] "c")]
          (is (= (str "The tool " m " failed.") error) m)
          (is (= ["private"] labels) (str m ": its static labels, nothing of what it printed"))))))
  (testing "a tool's own refusal reaches the model with its labels"
    (with-tools {"mail" (tool "refuse")}
      (fn [sys]
        (is (= {:error "No such source." :labels ["private"]}
               (tools/call! sys "clerk" "mail" {:question "q"} [] "c"))))))
  (testing "and so does one that runs too long"
    (with-tools {"mail" (tool "slow" :timeout-seconds 1)}
      (fn [sys]
        (is (= "The tool mail failed." (:error (tools/call! sys "clerk" "mail" {:question "q"} [] "c"))))))))

(deftest envelope-settings-are-checked
  (let [base {:timezone "UTC" :default-harness :a :harnesses {:a {:kind :agentloop :api-key-command ["true"]}}
              :notify {:kind :none}}
        problem #(try (config/resolve-config (assoc base :tools {"t" %})) nil
                      (catch clojure.lang.ExceptionInfo e (ex-message e)))]
    (is (nil? (problem (tool "echo"))))
    (is (nil? (problem (dissoc (tool "echo") :parameters))))
    (is (str/includes? (problem (assoc (tool "echo") :protocol :json)) ":protocol"))
    (is (str/includes? (problem (assoc (tool "echo") :parameters "x")) ":parameters"))
    (is (str/includes? (problem (assoc (tool "echo") :protocol nil)) ":parameters")
        "a plain tool's arguments are fixed")))
