(ns relay.quarantine-test
  "The quarantined reader grant: relay.edn names the tools a role may read
  through agentloop's read_quarantined, relay checks the grant, and the
  agentloop request carries a quarantine block and a policy entry for the
  reader only when the role has one."
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
   :tools {"web_fetch" {:class :read :labels ["contaminated"] :description "Fetch a page." :command ["true"]}
           "mail_read" {:class :read :labels ["contaminated" "private"] :description "Read mail." :command ["true"]}
           "web_post" {:class :egress :labels [] :description "Post." :command ["true"]}}
   :grants {"hub" {:tools ["web_fetch" "mail_read" "web_post"] :quarantine ["web_fetch" "mail_read"]}
            "scout" {:public true :tools ["web_fetch"] :quarantine ["web_fetch"]}}})

(defn- problem [f]
  (try (config/resolve-config (f settings)) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

;; --- relay.edn

(deftest the-operator-grants-the-reader-over-read-tools-the-role-holds
  (is (nil? (problem identity)) "a private and a public role may both have it")
  (is (= ["web_fetch" "mail_read"] (config/quarantine-tools (config/resolve-config settings) "hub")))
  (is (nil? (config/quarantine-tools (config/resolve-config settings) "clerk")))
  (testing "a tool the role is not granted"
    (is (str/includes? (problem #(assoc-in % [:grants "hub" :tools] ["web_fetch"])) "not granted in :tools")))
  (testing "a tool that is not :read"
    (is (str/includes? (problem #(assoc-in % [:grants "hub" :quarantine] ["web_post"])) "not a :read tool")))
  (testing "a tool that always holds"
    (is (str/includes? (str (problem #(assoc-in % [:tools "web_fetch" :always-hold] true))) ":always-hold"))
    (is (nil? (problem #(assoc-in % [:tools "web_fetch" :always-hold] false)))))
  (testing "a tool :tools does not define"
    (is (str/includes? (problem #(-> % (assoc-in [:grants "hub" :tools] ["nope"])
                                     (assoc-in [:grants "hub" :quarantine] ["nope"])))
                       "not a :read tool")))
  (testing "a grant that is not a non-empty vector of distinct names"
    (doseq [q [[] "web_fetch" ["web_fetch" "web_fetch"] [:web_fetch] nil]]
      (is (str/includes? (str (problem #(assoc-in % [:grants "hub" :quarantine] q))) "vector of distinct tool names")
          (pr-str q))))
  (testing "a public role still may not read private output through it"
    (is (str/includes? (problem #(assoc-in % [:grants "scout"] {:public true :tools ["mail_read"] :quarantine ["mail_read"]}))
                       "whose output is private"))))

(deftest the-reader-model-is-a-harness-setting
  (is (nil? (problem #(assoc-in % [:harnesses :agentloop :reader] {:model "claude-haiku-5-5" :effort "low"}))))
  (is (nil? (problem #(assoc-in % [:harnesses :agentloop :reader] {:effort "medium"}))))
  (doseq [r [{:model ""} {:effort "lots"} {:model "m" :retries 3} "claude-haiku-5-5"]]
    (is (str/includes? (str (problem #(assoc-in % [:harnesses :agentloop :reader] r))) ":reader") (pr-str r)))
  (is (str/includes? (str (problem #(assoc % :harnesses {:agentloop (get-in % [:harnesses :agentloop])
                                                        :s {:kind :script :command ["true"] :reader {}}})))
                     ":reader")
      "only an agentloop harness has a reader"))

;; --- the request

(def gen-harness
  (gen/fmap (fn [[effort reader base-url]]
              (cond-> {:kind :agentloop :api-key-command ["key"]}
                effort (assoc :effort effort)
                reader (assoc :reader reader)
                base-url (assoc :base-url base-url)))
            (gen/tuple (gen/elements [nil "low" "max"])
                       (gen/elements [nil {} {:model "small"} {:effort "high"} {:model "small" :effort "medium"}])
                       (gen/elements [nil "https://example.test"]))))

(def tool-list
  [{:name "web_fetch" :description "d" :parameters {} :class "read" :labels ["contaminated"]}
   {:name "inbox" :description "d" :parameters {} :class "read" :labels []}])

(def the-reader-is-on-exactly-when-granted
  (prop/for-all [harness gen-harness
                 inner (gen/elements [nil [] ["web_fetch"]])
                 public? gen/boolean]
    (let [policy (tools/policy tool-list ["private"] {:public? public?})
          request {:harness harness :system-prompt "s" :prompt "p" :tools tool-list :policy policy
                   :tool-command ["relay" "tool"] :session-dir "/s" :quarantine inner}
          run (agentloop/request-json request "id" "/w")
          without (agentloop/request-json (dissoc request :quarantine) "id" "/w")
          acp (agentloop/acp-request-json request "/w")
          reader (merge agentloop/default-reader (:reader harness))]
      (and (= (:quarantine run) (:quarantine acp))
           (= (cond-> {"inbox" "read" "web_fetch" "read"} (seq inner) (assoc "read_quarantined" "read"))
              (:tool_kinds acp))
           (= (:policy run) (:policy acp))
           (if (seq inner)
             (and (= {:model (:model reader) :effort (:effort reader) :tools inner} (:quarantine run))
                  (= {:class "read"} (get-in run [:policy :tools "read_quarantined"]))
                  ;; Nothing else changes: the tools list never names the
                  ;; reader, and forbid_labels and the labels stay.
                  (= (dissoc without :policy) (dissoc run :policy :quarantine))
                  (= (:policy without) (update (:policy run) :tools dissoc "read_quarantined"))
                  (not-any? #(= "read_quarantined" (:name %)) (:tools run)))
             (and (= without run)
                  (not (contains? run :quarantine))
                  (not (contains? (get-in run [:policy :tools]) "read_quarantined"))))))))

(deftest a-request-has-the-reader-exactly-when-the-role-is-granted-it
  (let [result (tc/quick-check 300 the-reader-is-on-exactly-when-granted)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

(deftest a-wake-request-names-the-granted-tools-and-the-role-is-told
  (let [cfg (config/resolve-config settings)
        t0 (Instant/parse "2026-10-15T12:00:00Z")
        cmd (fn [state actor type args]
              (first (core/run-command state {:type type :actor actor :args args} {:now t0 :config cfg})))
        state (-> core/empty-state
                  (cmd "operator" :role/add {:id "hub" :title "Hub" :instructions "Route."})
                  (cmd "operator" :role/add {:id "clerk" :title "Clerk" :instructions "File."})
                  (cmd "operator" :memo/send {:to "hub" :subject "Look" :body "Look."})
                  (cmd "operator" :memo/send {:to "clerk" :subject "File" :body "File."})
                  (cmd "operator" :wake/start {:role "hub"})
                  (cmd "operator" :wake/start {:role "clerk"}))
        sys {:data-dir (str (fs/path (fs/temp-dir) (str "relay-never-made-" (random-uuid)))) :config cfg}
        inputs {:shared "" :now t0 :relay-bin "relay" :data-path "/d"}
        request (fn [role] (dispatch/wake-request sys state (core/running-wake state role) inputs))]
    (is (= ["web_fetch" "mail_read"] (:quarantine (request "hub"))))
    (is (str/includes? (:system-prompt (request "hub")) "`web_fetch`, `mail_read` through `read_quarantined`"))
    (is (nil? (:quarantine (request "clerk"))))
    (is (not (str/includes? (:system-prompt (request "clerk")) "read_quarantined")))))
