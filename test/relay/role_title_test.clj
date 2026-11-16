(ns relay.role-title-test
  "A role's title as clients are sent it (relay#120): a role an outsider
  steered may set one, so it arrives ready to show, its links broken."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.core :as core]
            [relay.core-test :as ct]
            [relay.hub-test :as ht]
            [relay.roster :as roster]))

(def canary "https://evil.test/?d=CANARY")

(defn- state-with-helper
  "The hub role, granted :may-add, adds a helper from a session that
  holds outside text, with an address as its title: the reviewer's case.
  `title` is the helper's title."
  [title]
  (let [[s] (core/run-command core/empty-state
                              {:type :role/add :actor "operator" :labels [] :args {:id "hub" :title "Hub" :instructions "Run relay."}}
                              {:now ht/t0 :config ct/cfg})
        [s] (core/run-command s {:type :role/add :actor "hub" :labels ["contaminated" "private"]
                                 :args {:id "helper" :title title :instructions "Help."}}
                              {:now ht/t0 :config ct/cfg})]
    s))

(defn- with-session
  "The roster with the helper's conversation open as session s1."
  [rs]
  (mapv #(cond-> % (= "helper" (:id %))
           (assoc :conversation {:wake 7 :session "s1" :opened-at ht/t0 :last-activity ht/t0 :turn :idle :turns 1 :unread? false}))
        rs))

(defn- hub-with-helper
  "A hub whose roster holds the helper, its conversation live as s1 on
  connection 1, and that connection's _relay/roles answer: [h answer]."
  [state]
  (let [rs (roster/roster state ct/cfg ht/t0)
        h (-> (ht/fresh) (ht/opened 1))
        [h] (ht/run h {:event :log-changed :roster rs :state state})
        [h fx] (ht/say h 1 (ht/req 1 "_relay/roles" {}))
        roles (first (ht/writes fx 1))
        [h] (ht/say h 1 (ht/req 2 "initialize" {:protocolVersion 1 :clientCapabilities {}}))
        [h] (ht/say h 1 (ht/req 3 "session/new" {:cwd "/w" :mcpServers [] :_meta {"relay/role" "helper"}}))
        [h] (ht/run h {:event :conversation-started :role "helper" :wake 7 :labels ["contaminated"]})
        [h] (ht/agent h "helper" (ht/res 1 {:protocolVersion 1}))
        [h] (ht/agent h "helper" (ht/res 2 {:sessionId "s1"}))
        [h] (ht/run h {:event :log-changed :roster (with-session rs) :state state})]
    [h roles]))

(defn- titles
  "Every title the client was sent for the helper: in _relay/roles, in
  _relay/roles/changed and in session/list."
  [state]
  (let [[h roles] (hub-with-helper state)
        helper #(first (filter (fn [r] (= "helper" (get r "id"))) %))
        rs (roster/roster state ct/cfg ht/t0)
        [h fx] (ht/run h {:event :log-changed :roster (mapv #(cond-> % (= "helper" (:id %)) (assoc :working? true)) (with-session rs))
                          :state state})
        changed (first (filter #(= "_relay/roles/changed" (get % "method")) (ht/writes fx 1)))
        [_ fx] (ht/say h 1 (ht/req 9 "session/list" {}))
        listed (get-in (first (filter #(= 9 (get % "id")) (ht/writes fx 1))) ["result" "sessions"])]
    {:roles (get (helper (get-in roles ["result" "roles"])) "title")
     :changed (get (helper (get-in changed ["params" "roles"])) "title")
     :list (get (first listed) "title")}))

(deftest a-title-an-outsider-shaped-reaches-no-client-as-a-link
  (let [ts (titles (state-with-helper canary))]
    (is (= #{:roles :changed :list} (set (keys (filter (comp string? val) ts)))) "every path was sent a title")
    (doseq [[path t] ts]
      (testing (name path)
        (is (= "hxxps://evil[.]test/?d=CANARY" t))
        (is (not (str/includes? t "evil.test")))))))

(deftest a-title-is-ready-to-show
  (testing "hidden characters and newlines are shown, once"
    (let [t (str "Help" (char 0x200b) "er\nnext \\ ")]
      (is (= #{"Help\\u{200b}er\\u{000a}next \\\\"} (set (vals (titles (state-with-helper t)))))))))

(deftest a-clean-title-is-sent-as-written
  (let [[s] (core/run-command core/empty-state
                              {:type :role/add :actor "operator" :labels [] :args {:id "helper" :title "Notes at notes.example" :instructions "Help."}}
                              {:now ht/t0 :config ct/cfg})]
    (is (= "Notes at notes.example" (get (roster/wire-role (first (roster/roster s ct/cfg ht/t0))) "title")))))
