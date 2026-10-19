(ns relay.workbench-test
  "The workbench grant: relay.edn gives a role agentloop's sandboxed
  workbench, relay checks the grant, and the agentloop request carries a
  workbench block and a policy entry for it only when the role has one."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.config :as config]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.harness.agentloop :as agentloop]
            [relay.tools :as tools])
  (:import (java.time Instant)))

(def settings
  {:timezone "UTC"
   :default-harness :agentloop
   :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}}
   :notify {:kind :none}
   :tools {"web_fetch" {:class :read :labels ["contaminated"] :description "Fetch a page." :command ["true"]}}
   :grants {"hub" {:tools ["web_fetch"] :workbench true}
            "clerk" {:tools []}
            "scout" {:public true :tools ["web_fetch"]}}})

(defn- problem [f]
  (try (config/resolve-config (f settings)) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

;; --- relay.edn

(deftest the-operator-grants-the-workbench
  (is (nil? (problem identity)))
  (is (= {:deny-read []} (config/workbench (config/resolve-config settings) "hub")))
  (is (nil? (config/workbench (config/resolve-config settings) "clerk")))
  (testing "the grant may add paths the commands may not read"
    (is (nil? (problem #(assoc-in % [:grants "hub" :workbench] {:deny-read ["/Users/you/private"]}))))
    (is (= {:deny-read ["/Users/you/private"]}
           (config/workbench (config/resolve-config (assoc-in settings [:grants "hub" :workbench]
                                                              {:deny-read ["/Users/you/private"]}))
                             "hub"))))
  (testing "a grant that is not true or {:deny-read [...]}"
    (doseq [w [false "yes" {} {:deny-read []} {:deny-read "/x"} {:deny-read ["x"]}
               {:deny-read ["/a" "/a"]} {:deny-read ["/a"] :scratch "/b"} nil]]
      (is (str/includes? (str (problem #(assoc-in % [:grants "hub" :workbench] w))) ":workbench grant")
          (pr-str w))))
  (testing "a path that could end a rule early in the sandbox profile"
    (doseq [p ["/a\"b" "/a\\b"]]
      (is (str/includes? (str (problem #(assoc-in % [:grants "hub" :workbench] {:deny-read [p]}))) ":workbench grant")
          (pr-str p))))
  (testing "a public role may not have it"
    (is (str/includes? (problem #(assoc-in % [:grants "scout" :workbench] true))
                       "may not have :workbench")))
  (testing "a script harness has no workbench"
    (is (str/includes? (problem #(-> % (assoc :default-harness :s)
                                     (assoc-in [:harnesses :s] {:kind :script :command ["true"]})
                                     (assoc :grants {"hub" {:workbench true}})))
                       "needs agentloop")))
  (testing "an unknown grant key is still refused"
    (is (str/includes? (problem #(assoc-in % [:grants "hub" :workbenches] true)) "does not know"))))

;; --- the request

(def gen-harness
  (gen/fmap (fn [[reader wb base-url]]
              (cond-> {:kind :agentloop :api-key-command ["key"]}
                reader (assoc :reader reader)
                wb (assoc :workbench wb)
                base-url (assoc :base-url base-url)))
            (gen/tuple (gen/elements [nil {} {:model "small"} {:model "small" :effort "medium"}])
                       (gen/elements [nil {} {:command-timeout-seconds 30}
                                      {:deny-read ["/etc/secrets"]}
                                      {:command-timeout-seconds 30 :deny-read ["/etc/secrets"]}])
                       (gen/elements [nil "https://example.test"]))))

(def tool-list
  [{:name "web_fetch" :description "d" :parameters {} :class "read" :labels ["contaminated"]}])

(deftest the-harness-settings-are-checked
  (is (nil? (problem #(assoc-in % [:harnesses :agentloop :workbench] {:command-timeout-seconds 60}))))
  (is (nil? (problem #(assoc-in % [:harnesses :agentloop :workbench] {:deny-read ["/Users/you/private"]}))))
  (testing "a misspelt or unusable setting is refused, never dropped"
    (doseq [w [{:deny_read ["/Users/you/private"]} {:deny-read ["relative"]} {:deny-read "/x"}
               {:command-timeout-seconds 0} {:command-timeout-seconds "60"} {:scratch-root "/tmp/x"}
               {:deny-read ["/a\""]} true]]
      (is (str/includes? (str (problem #(assoc-in % [:harnesses :agentloop :workbench] w))) ":workbench")
          (pr-str w))))
  (is (str/includes? (str (problem #(assoc % :harnesses {:agentloop (get-in % [:harnesses :agentloop])
                                                         :s {:kind :script :command ["true"]
                                                             :workbench {:deny-read ["/x"]}}})))
                     ":workbench")
      "only an agentloop harness has a workbench")
  (testing "the role's grant refuses the same paths"
    (is (str/includes? (str (problem #(assoc-in % [:grants "hub" :workbench] {:deny-read ["/a\""]})))
                       ":workbench grant"))))

(def the-workbench-is-on-exactly-when-granted
  (prop/for-all [harness gen-harness
                 grant (gen/elements [nil {:deny-read []} {:deny-read ["/Users/you/private"]}])]
    (let [policy (tools/policy tool-list ["private"] {})
          request {:harness harness :system-prompt "s" :prompt "p" :tools tool-list :policy policy
                   :tool-command ["relay" "tool"] :session-dir "/s" :workbench grant
                   :data-dir "/data/relay"}
          run (agentloop/request-json request "id" "/w")
          without (agentloop/request-json (dissoc request :workbench) "id" "/w")
          acp (agentloop/acp-request-json request "/w")
          reader (merge agentloop/default-reader (:reader harness))
          wb (merge agentloop/default-workbench (:workbench harness))]
      (and (= (:workbench run) (:workbench acp))
           (= (cond-> {"web_fetch" "read"} grant (assoc "workbench" "read")) (:tool_kinds acp))
           (= (:policy run) (:policy acp))
           (if grant
             (let [block (:workbench run)]
               (and
                ;; The child runs on the reader's model and effort.
                (= (:model reader) (:model block))
                (= (:effort reader) (:effort block))
                (= (:command-timeout-seconds wb) (:command_timeout_seconds block))
                ;; relay's own data is never readable, and the grant's
                ;; and the harness's paths are both there, each once.
                (some #{"/data/relay"} (:deny_read block))
                (every? (set (:deny_read block)) (concat (:deny-read wb) (:deny-read grant)))
                (apply distinct? (:deny_read block))
                (= {:class "read"} (get-in run [:policy :tools "workbench"]))
                ;; Nothing else changes: the tools list never names it.
                (= (dissoc without :policy) (dissoc run :policy :workbench))
                (= (:policy without) (update (:policy run) :tools dissoc "workbench"))
                (not-any? #(= "workbench" (:name %)) (:tools run))))
             (and (= without run)
                  (not (contains? run :workbench))
                  (not (contains? (get-in run [:policy :tools]) "workbench"))))))))

(deftest a-request-has-the-workbench-exactly-when-the-role-is-granted-it
  (let [result (tc/quick-check 300 the-workbench-is-on-exactly-when-granted)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

(deftest a-wake-request-carries-the-grant-and-the-role-is-told
  (let [cfg (config/resolve-config (assoc-in settings [:grants "hub" :workbench]
                                             {:deny-read ["/Users/you/private"]}))
        t0 (Instant/parse "2026-10-18T12:00:00Z")
        cmd (fn [state actor type args]
              (first (core/run-command state {:type type :actor actor :args args} {:now t0 :config cfg})))
        state (-> core/empty-state
                  (cmd "operator" :role/add {:id "hub" :title "Hub" :instructions "Route."})
                  (cmd "operator" :role/add {:id "clerk" :title "Clerk" :instructions "File."})
                  (cmd "operator" :memo/send {:to "hub" :subject "Look" :body "Look."})
                  (cmd "operator" :memo/send {:to "clerk" :subject "File" :body "File."})
                  (cmd "operator" :wake/start {:role "hub"})
                  (cmd "operator" :wake/start {:role "clerk"}))
        data-dir (str (fs/path (fs/temp-dir) (str "relay-never-made-" (random-uuid))))
        sys {:data-dir data-dir :config cfg}
        inputs {:shared "" :now t0 :relay-bin "relay" :data-path "/d"}
        request (fn [role] (dispatch/wake-request sys state (core/running-wake state role) inputs))]
    (is (= {:deny-read ["/Users/you/private"]} (:workbench (request "hub"))))
    (is (str/includes? (:system-prompt (request "hub")) "`workbench`"))
    (is (nil? (:workbench (request "clerk"))))
    (is (not (str/includes? (:system-prompt (request "clerk")) "workbench")))
    ;; The request names relay's data directory, which the sandbox denies.
    (is (= (str (fs/absolutize data-dir)) (:data-dir (request "hub"))))
    (is (some #{(str (fs/absolutize data-dir))}
              (:deny_read (:workbench (agentloop/request-json (request "hub") "id" "/w")))))))

(deftest a-role-with-the-workbench-may-hold-private-and-contaminated
  ;; Text such a session wrote without labels is taken to carry both: the
  ;; workbench reads the operator's files, which anyone could have written.
  (let [cfg (config/resolve-config settings)]
    (is (= #{"private" "contaminated"} (set (core/session-labels-bound core/empty-state cfg "hub"))))
    (is (= ["private"] (core/session-labels-bound core/empty-state cfg "clerk"))
        "relay's own content is private; only the workbench adds contaminated here")))
