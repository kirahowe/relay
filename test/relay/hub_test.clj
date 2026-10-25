(ns relay.hub-test
  "The hub, as data: an example for each protocol message, and properties
  over random runs against a simulated agent and relay (relay.hub-sim)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.core :as core]
            [relay.core-test :as ct]
            [relay.hub :as hub]
            [relay.hub-sim :as sim])
  (:import (java.time Instant)))

(def t0 (Instant/parse "2026-10-21T12:00:00Z"))

(defn roster-entry [id & [conversation]]
  {:id id :title (str/capitalize id) :labels [] :working? false :holds 0 :conversation conversation})

(def roster [(roster-entry "clerk") (roster-entry "hub")])

(defn line [m] (json/generate-string m))
(defn req [id method params] (line {:jsonrpc "2.0" :id id :method method :params params}))
(defn note [method params] (line {:jsonrpc "2.0" :method method :params params}))
(defn res [id result] (line {:jsonrpc "2.0" :id id :result result}))
(defn parse [l] (json/parse-string l))

(defn run
  "Steps the hub through the events. Returns [hub effects]."
  [h & events]
  (reduce (fn [[h fx] e] (let [[h f] (hub/step h e)] [h (into fx f)])) [h []] events))

(defn writes [fx conn] (for [{:keys [effect] :as f} fx :when (and (= :write effect) (= conn (:conn f)))] (parse (:line f))))
(defn sends [fx role] (for [{:keys [effect] :as f} fx :when (and (= :send effect) (= role (:role f)))] (:line f)))
(defn submits [fx] (filter #(= :submit (:effect %)) fx))

(defn fresh [& [mode]]
  (first (run (hub/hub {:mode (or mode :service)}) {:event :log-changed :roster roster})))

(defn opened [h conn & {:keys [approvals role origin] :or {origin :socket}}]
  (first (run h {:event :connection-opened :conn conn :origin origin :approvals approvals :role role})))

(defn say [h conn l] (run h {:event :connection-line :conn conn :line l}))
(defn agent [h role l] (run h {:event :agent-line :role role :line l}))

(defn live
  "A hub with the clerk's conversation live as session s1, connection 1
  having opened it."
  [& {:keys [mode approvals] :or {mode :service approvals true}}]
  (let [h (-> (fresh mode) (opened 1 :approvals approvals))
        [h] (say h 1 (req 1 "initialize" {:protocolVersion 1 :clientCapabilities {}}))
        [h] (say h 1 (req 2 "session/new" {:cwd "/w" :mcpServers [] :_meta {"relay/role" "clerk"}}))
        [h] (run h {:event :conversation-started :role "clerk" :wake 7})
        [h] (agent h "clerk" (res 1 {:protocolVersion 1}))
        [h] (agent h "clerk" (res 2 {:sessionId "s1"}))]
    h))

(defn submit-ok [h fx] (reduce (fn [h {:keys [tag]}] (first (run h {:event :submit-result :tag tag :ok true}))) h (submits fx)))

;; --- connection

(deftest initialize-says-it-is-relay-s-hub
  (let [[_ fx] (say (opened (fresh) 1) 1 (req 0 "initialize" {:protocolVersion 1}))
        [a] (writes fx 1)]
    (is (= 0 (get a "id")))
    (is (= {"relay/hub" 1 "relay/keeps" true} (get-in a ["result" "_meta"])) "a service keeps conversations")
    (is (= {"relay/hub" 1 "relay/keeps" false}
           (get-in (first (writes (second (say (opened (fresh :embedded) 1 :origin :stdio) 1 (req 0 "initialize" {}))) 1))
                   ["result" "_meta"]))
        "relay acp on its own does not")
    (is (true? (get-in a ["result" "agentCapabilities" "loadSession"])))
    (is (= {} (get-in a ["result" "agentCapabilities" "sessionCapabilities" "list"])))
    (is (= {"name" "relay" "title" "relay" "version" "1"} (get-in a ["result" "agentInfo"])))))

(deftest relay-connect-counts-only-as-the-first-line-of-a-socket
  (let [h (opened (fresh) 1)
        [h fx] (say h 1 (req 9 "_relay/connect" {:approvals true :role "clerk"}))]
    (is (= {"jsonrpc" "2.0" "id" 9 "result" {}} (first (writes fx 1))))
    (is (= [true "clerk"] ((juxt :approvals :role) (get-in h [:conns 1])))))
  (testing "as a later line it is refused and grants nothing"
    (let [h (opened (fresh) 1)
          [h] (say h 1 (req 1 "initialize" {}))
          [h fx] (say h 1 (req 2 "_relay/connect" {:approvals true}))]
      (is (= -32600 (get-in (first (writes fx 1)) ["error" "code"])))
      (is (false? (get-in h [:conns 1 :approvals])))
      (let [[h fx] (say h 1 (note "_relay/connect" {:approvals true}))]
        (is (empty? fx))
        (is (false? (get-in h [:conns 1 :approvals]))))))
  (testing "on stdio it is never taken: approvals come from relay acp's own flag"
    (let [h (opened (fresh :embedded) 1 :origin :stdio)
          [h fx] (say h 1 (req 1 "_relay/connect" {:approvals true}))]
      (is (= -32600 (get-in (first (writes fx 1)) ["error" "code"])))
      (is (false? (get-in h [:conns 1 :approvals]))))))

;; --- conversations

(deftest session-new-opens-the-role-s-conversation
  (let [h (-> (fresh) (opened 1))
        [h] (say h 1 (req 1 "initialize" {:protocolVersion 1 :clientCapabilities {:fs {}}}))
        [h fx] (say h 1 (req 2 "session/new" {:cwd "/w" :mcpServers [] :_meta {"relay/role" "clerk"}}))
        _ (is (= [{:effect :start :role "clerk"}] fx))
        [h fx] (run h {:event :conversation-started :role "clerk" :wake 7})
        [init] (map parse (sends fx "clerk"))
        _ (is (= ["initialize" {"protocolVersion" 1 "clientCapabilities" {"fs" {}}}] [(get init "method") (get init "params")])
              "the agent is initialized as the first client asked")
        [h fx] (agent h "clerk" (res (get init "id") {:protocolVersion 1}))
        [new] (map parse (sends fx "clerk"))
        _ (is (= ["session/new" {"cwd" "/w" "mcpServers" []}] [(get new "method") (get new "params")]))
        [h fx] (agent h "clerk" (res (get new "id") {:sessionId "s1" :configOptions []}))]
    (is (= [{"jsonrpc" "2.0" "id" 2 "result" {"sessionId" "s1" "configOptions" []}}
            {"jsonrpc" "2.0" "method" "_relay/replayed" "params" {"sessionId" "s1" "truncated" false}}]
           (writes fx 1)))
    (is (= #{"clerk"} (get-in h [:conns 1 :attached])))
    (testing "a role comes from _meta, else the connection, else it is an error"
      (let [[_ fx] (say (opened (fresh) 2) 2 (req 3 "session/new" {:cwd "/"}))]
        (is (= -32602 (get-in (first (writes fx 2)) ["error" "code"]))))
      (let [[_ fx] (say (opened (fresh) 2 :role "hub") 2 (req 3 "session/new" {:cwd "/"}))]
        (is (= [{:effect :start :role "hub"}] fx)))
      (let [[_ fx] (say (opened (fresh) 2) 2 (req 3 "session/new" {:_meta {"relay/role" "nobody"}}))]
        (is (str/includes? (get-in (first (writes fx 2)) ["error" "message"]) "no role called nobody"))))
    (testing "a role whose conversation could not open says why"
      (let [h (-> (fresh) (opened 2))
            [h] (say h 2 (req 3 "session/new" {:_meta {"relay/role" "hub"}}))
            [h fx] (run h {:event :conversation-failed :role "hub" :message "hub is busy"})]
        (is (= "hub is busy" (get-in (first (writes fx 2)) ["error" "message"])))
        (is (nil? (get-in h [:convs "hub"])))))))

(deftest a-default-role-is-only-for-a-session-new-that-names-none
  (let [ws (fn [] (first (run (fresh) {:event :connection-opened :conn 2 :origin :websocket :approvals true
                                       :default-role "hub"})))]
    (testing "a session/new with no role goes to the default role"
      (let [[_ fx] (say (ws) 2 (req 3 "session/new" {:cwd "/"}))]
        (is (= [{:effect :start :role "hub"}] fx))))
    (testing "_meta relay/role still wins"
      (let [[_ fx] (say (ws) 2 (req 3 "session/new" {:cwd "/" :_meta {"relay/role" "clerk"}}))]
        (is (= [{:effect :start :role "clerk"}] fx))))
    (testing "and the connection has no role of its own: a request with no sessionId is not routed to it"
      (let [h (ws)]
        (is (nil? (get-in h [:conns 2 :role])))
        (let [[_ fx] (say h 2 (req 4 "_relay/items" {}))]
          (is (= -32602 (get-in (first (writes fx 2)) ["error" "code"]))))))
    (testing "a default role relay does not have is refused as any unknown role is"
      (let [h (first (run (fresh) {:event :connection-opened :conn 2 :origin :websocket :approvals true
                                   :default-role "nobody"}))
            [_ fx] (say h 2 (req 3 "session/new" {:cwd "/"}))]
        (is (str/includes? (get-in (first (writes fx 2)) ["error" "message"]) "no role called nobody"))))
    (testing "a WebSocket connection cannot claim a role or approvals with _relay/connect"
      (let [h (first (run (fresh) {:event :connection-opened :conn 2 :origin :websocket :approvals false}))
            [h fx] (say h 2 (req 1 "_relay/connect" {:approvals true :role "clerk"}))]
        (is (= -32600 (get-in (first (writes fx 2)) ["error" "code"])))
        (is (= [false nil] ((juxt :approvals :role) (get-in h [:conns 2]))))))))

(deftest a-second-session-new-attaches-with-the-replay
  (let [h (-> (live) (opened 2))
        upd (note "session/update" {:sessionId "s1" :update {:sessionUpdate "agent_message_chunk" :content {:type "text" :text "hi"}}})
        [h fx] (agent h "clerk" upd)
        _ (is (= [(parse upd)] (writes fx 1)) "the attached client sees it")
        [h fx] (say h 2 (req 5 "session/new" {:cwd "/x" :_meta {"relay/role" "clerk"}}))]
    (is (= [{"jsonrpc" "2.0" "id" 5 "result" {"sessionId" "s1" "_meta" {"relay/attached" true}}}
            (parse upd)
            {"jsonrpc" "2.0" "method" "_relay/replayed" "params" {"sessionId" "s1" "truncated" false}}]
           (writes fx 2))
        "the answer, then the replay, then _relay/replayed")
    (is (empty? (sends fx "clerk")) "the agent is not asked again")
    (testing "and an empty replay still ends with _relay/replayed"
      (let [[_ fx] (say (opened (live) 3) 3 (req 1 "session/new" {:_meta {"relay/role" "clerk"}}))]
        (is (= ["_relay/replayed"] (keep #(get % "method") (writes fx 3))))))))

(deftest session-load-replays-then-answers
  (let [upd (note "session/update" {:sessionId "s1" :update {:sessionUpdate "agent_message_chunk" :content {:type "text" :text "hi"}}})
        [h] (agent (live) "clerk" upd)
        h (opened h 2)
        [h fx] (say h 2 (req 4 "session/load" {:sessionId "s1" :cwd "/" :mcpServers []}))]
    (is (= [(parse upd) {"jsonrpc" "2.0" "method" "_relay/replayed" "params" {"sessionId" "s1" "truncated" false}}
            {"jsonrpc" "2.0" "id" 4 "result" {}}]
           (writes fx 2)))
    (is (contains? (get-in h [:conns 2 :attached]) "clerk"))
    (testing "nothing that is not live is reopened"
      (let [[_ fx] (say h 2 (req 5 "session/load" {:sessionId "old"}))]
        (is (= -32602 (get-in (first (writes fx 2)) ["error" "code"])))))))

(deftest a-replay-past-its-cap-says-so
  (let [h (assoc (live) :replay-cap 300)
        upd #(note "session/update" {:sessionId "s1" :update {:sessionUpdate "tool_call_update" :toolCallId (str "t" %)}})
        h (reduce (fn [h i] (first (agent h "clerk" (upd i)))) h (range 10))
        [_ fx] (say (opened h 2) 2 (req 1 "session/load" {:sessionId "s1"}))
        got (writes fx 2)
        ids (keep #(get-in % ["params" "update" "toolCallId"]) got)]
    (is (= "t9" (last ids)) "the newest are kept")
    (is (< (count ids) 10))
    (is (= {"sessionId" "s1" "truncated" true} (get (first (filter #(= "_relay/replayed" (get % "method")) got)) "params"))
        "and _relay/replayed says older ones were dropped; relay words nothing itself")
    (is (not-any? #(get-in % ["params" "update" "content" "text"]) got)))
  (testing "a replay that dropped nothing says that too"
    (let [[_ fx] (say (opened (live) 2) 2 (req 1 "session/load" {:sessionId "s1"}))]
      (is (= {"sessionId" "s1" "truncated" false} (get (first (writes fx 2)) "params"))))))

(deftest session-list-gives-live-conversations
  (let [h (assoc (live) :roster [(roster-entry "clerk" {:wake 7 :session "s1" :opened-at t0 :last-activity t0 :turn :idle :turns 1
                                                          :unread? false :approvals 0}) (roster-entry "hub")])
        [_ fx] (say h 1 (req 9 "session/list" {}))]
    (is (= {"sessions" [{"sessionId" "s1" "cwd" "/w" "title" "Clerk" "updatedAt" (str t0) "_meta" {"relay/role" "clerk"}}]}
           (get (first (writes fx 1)) "result")))
    (testing "whatever directory the client lists by"
      ;; Regression: the list kept only conversations opened from the
      ;; client's own cwd, so a phone listing with "/" never saw the one
      ;; relay-tui had opened from /w.
      (let [ids (fn [cwd] (let [[_ fx] (say h 1 (req 10 "session/list" {:cwd cwd}))]
                            (mapv (juxt #(get % "sessionId") #(get % "cwd"))
                                  (get-in (first (writes fx 1)) ["result" "sessions"]))))]
        (is (= [["s1" "/w"]] (ids "/")) "a directory lists what is under it")
        (is (= [["s1" "/w"]] (ids "/w")) "and what is in it")
        (is (= [["s1" "/w"]] (ids "/w/")))
        (is (= [] (ids "/w/x")) "but not what is above it")
        (is (= [] (ids "/wx")) "and matches by path segment, not by text")))))

(deftest session-close-detaches-without-ending
  (let [[h fx] (say (live) 1 (req 9 "session/close" {:sessionId "s1"}))]
    (is (= {} (get (first (writes fx 1)) "result")))
    (is (empty? (get-in h [:conns 1 :attached])))
    (is (get-in h [:convs "clerk"]) "the conversation goes on")
    (is (not-any? #(= :stop (:effect %)) fx)))
  (testing "as ACP asks, it cancels a turn the closing client is running; another's goes on"
    (let [[h fx] (say (live) 1 (req 30 "session/prompt" {:sessionId "s1" :prompt []}))
          h (submit-ok h fx)
          [_ fx] (say h 1 (req 9 "session/close" {:sessionId "s1"}))]
      (is (= ["session/cancel"] (map #(get (parse %) "method") (sends fx "clerk")))))
    (let [h (opened (live) 2)
          [h] (say h 2 (req 1 "session/load" {:sessionId "s1"}))
          [h fx] (say h 2 (req 30 "session/prompt" {:sessionId "s1" :prompt []}))
          h (submit-ok h fx)
          [_ fx] (say h 1 (req 9 "session/close" {:sessionId "s1"}))]
      (is (empty? (sends fx "clerk"))))))

;; --- turns

(deftest a-prompt-waits-for-its-turn
  (let [h (opened (live) 2)
        [h] (say h 2 (req 1 "session/load" {:sessionId "s1"}))
        prompt (req 30 "session/prompt" {:sessionId "s1" :prompt [{:type "text" :text "Hello"}]})
        [h fx] (say h 1 prompt)
        [{:keys [tag command]}] (submits fx)]
    (is (= {:type :conversation/turn-start :actor "operator" :args {:wake 7 :session "s1"}} command))
    (is (empty? (sends fx "clerk")) "nothing reaches the agent before relay accepts the turn")
    (testing "busy: the prompt is queued, and the roster says so"
      (let [h (assoc h :roster [(roster-entry "clerk" {:wake 7 :session nil :turn :idle :turns 0 :unread? false :approvals 0
                                                       :opened-at t0 :last-activity t0})])
            [h fx] (run h {:event :submit-result :tag tag :rejection :busy :message "clerk is busy with scheduled work."})]
        (is (empty? fx))
        (is (= "queued" (get-in (hub/roles-wire h) [0 "conversation" "turn"])))
        (is (= "s1" (get-in (hub/roles-wire h) [0 "conversation" "sessionId"])) "the session before the first turn")
        (testing "and asked for again when the log changes"
          (let [[h fx] (run h {:event :log-changed :roster roster})
                [{:keys [tag]}] (submits fx)
                [h fx] (run h {:event :submit-result :tag tag :ok true})
                [sent] (map parse (sends fx "clerk"))]
            (is (= "session/prompt" (get sent "method")))
            (is (not= 30 (get sent "id")) "under the hub's own id")
            (testing "the other client sees the prompt; the sender does not"
              (is (= [{"jsonrpc" "2.0" "method" "session/update"
                       "params" {"sessionId" "s1" "update" {"sessionUpdate" "user_message_chunk"
                                                            "content" {"type" "text" "text" "Hello"}}}}]
                     (writes fx 2)))
              (is (empty? (writes fx 1))))
            (testing "the answer ends the turn before the sender reads it"
              (let [[_ fx] (agent h "clerk" (res (get sent "id") {:stopReason "end_turn"}))]
                (is (= [:submit :write] (map :effect fx)))
                (is (= {:type :conversation/turn-end :actor "dispatcher" :args {:wake 7 :stop-reason "end_turn"}}
                       (:command (first fx))))
                (is (= {"jsonrpc" "2.0" "id" 30 "result" {"stopReason" "end_turn"}} (parse (:line (second fx)))))))))))
    (testing "any other refusal is the prompt's answer"
      (let [[_ fx] (run h {:event :submit-result :tag tag :rejection :invalid :message "No."})]
        (is (= "No." (get-in (first (writes fx 1)) ["error" "message"])))))))

(deftest cancel-drops-a-queued-prompt-or-stops-the-turn
  (let [[h fx] (say (live) 1 (req 30 "session/prompt" {:sessionId "s1" :prompt []}))
        [h] (run h {:event :submit-result :tag (:tag (first (submits fx))) :rejection :busy})
        [h fx] (say h 1 (note "session/cancel" {:sessionId "s1"}))]
    (is (= [{"jsonrpc" "2.0" "id" 30 "result" {"stopReason" "cancelled"}}] (writes fx 1)))
    (is (empty? (hub/queued h "clerk"))))
  (let [[h fx] (say (live) 1 (req 30 "session/prompt" {:sessionId "s1" :prompt []}))
        h (submit-ok h fx)
        [_ fx] (say h 1 (note "session/cancel" {:sessionId "s1"}))]
    (is (= ["session/cancel"] (map #(get (parse %) "method") (sends fx "clerk"))))))

;; --- permissions

(defn- permission [id]
  (line {:jsonrpc "2.0" :id id :method "session/request_permission"
         :params {:sessionId "s1" :toolCall {:toolCallId "c1" :title "post"}
                  :options [{:optionId "a" :kind "allow_once" :name "Allow"}
                            {:optionId "r" :kind "reject_once" :name "Reject"}]}}))

(defn- choose [id opt] (res id {:outcome {:outcome "selected" :optionId opt}}))

(defn- two-approvers []
  (let [h (opened (live) 2 :approvals true)
        [h] (say h 2 (req 1 "session/load" {:sessionId "s1"}))
        h (opened h 3 :approvals false)
        [h] (say h 3 (req 1 "session/load" {:sessionId "s1"}))]
    h))

(deftest a-permission-request-goes-to-every-approving-client-and-the-first-answer-wins
  (let [[h fx] (agent (two-approvers) "clerk" (permission 900))
        [p1] (writes fx 1)
        [p2] (writes fx 2)]
    (is (empty? (writes fx 3)) "a client that cannot approve is not asked")
    (is (= "session/request_permission" (get p1 "method") (get p2 "method")))
    (let [[h fx] (say h 2 (choose (get p2 "id") "a"))]
      (is (= [{"jsonrpc" "2.0" "id" 900 "result" {"outcome" {"outcome" "selected" "optionId" "a"}}}]
             (map parse (sends fx "clerk")))
          "the answer reaches the agent under its own id")
      (is (= [{"jsonrpc" "2.0" "method" "_relay/permission/settled"
               "params" {"sessionId" "s1" "toolCallId" "c1" "outcome" "allow_once"}}]
             (writes fx 1)))
      (testing "a late answer from the other is dropped without an error, and never reaches the agent"
        (let [[h fx] (say h 1 (res (get p1 "id") {:outcome {:outcome "cancelled"}}))]
          (is (empty? fx))
          (testing "and only once: a second is a stray"
            (is (empty? (second (say h 1 (res (get p1 "id") {:outcome {:outcome "cancelled"}})))))))))))

(deftest an-answer-relay-cannot-take-is-a-refusal
  (testing "an option the agent did not offer goes to the proxy, which refuses it"
    (let [[h fx] (agent (two-approvers) "clerk" (permission 900))
          [p1] (writes fx 1)
          [_ fx] (say h 1 (choose (get p1 "id") "made-up"))]
      (is (= [{"jsonrpc" "2.0" "id" 900 "result" {"outcome" {"outcome" "selected" "optionId" "made-up"}}}]
             (map parse (sends fx "clerk"))))
      (is (= "reject_once" (get-in (first (writes fx 2)) ["params" "outcome"])))))
  (testing "a line the strict reader refuses, with the request's id, is refused through the proxy"
    (let [[h fx] (agent (two-approvers) "clerk" (permission 900))
          [p1] (writes fx 1)
          [_ fx] (say h 1 (str "{\"jsonrpc\":\"2.0\",\"id\":" (json/generate-string (get p1 "id")) ",\"result\":{},\"result\":{}}"))
          [sent] (map parse (sends fx "clerk"))]
      (is (= {"jsonrpc" "2.0" "id" 900 "result" {"outcome" {"outcome" "selected" "optionId" "r"}}} sent)
          "refused through the proxy, which records it")))
  (testing "a client that is not approving cannot answer"
    (let [[h fx] (agent (two-approvers) "clerk" (permission 900))
          id (get (first (writes fx 1)) "id")
          [_ fx] (say h 3 (choose id "a"))]
      (is (empty? (sends fx "clerk"))))))

(deftest a-permission-with-nobody-to-answer-it
  (testing "service: it waits, relay is asked once to tell the operator, and an approver attaching gets it"
    (let [h (live :approvals false)
          [h fx] (agent h "clerk" (permission 900))]
      (is (= [{:type :permission/unattended :actor "dispatcher" :args {:wake 7 :call "c1"}}] (map :command (submits fx))))
      (is (empty? (sends fx "clerk")))
      (let [h (-> (submit-ok h fx) (opened 2 :approvals true))
            [_ fx] (say h 2 (req 1 "session/load" {:sessionId "s1"}))]
        (is (= ["_relay/replayed" nil "session/request_permission"] (map #(get % "method") (writes fx 2)))
            "after the replay and the answer"))))
  (testing "embedded: it is refused"
    (let [[_ fx] (agent (live :mode :embedded :approvals false) "clerk" (permission 900))]
      (is (= [{"jsonrpc" "2.0" "id" 900 "result" {"outcome" {"outcome" "selected" "optionId" "r"}}}]
             (map parse (sends fx "clerk")))))))

;; --- the roster, seen, ending

(deftest the-roster-is-sent-whole-and-only-when-it-changes
  (let [h (opened (fresh) 1)
        [h fx] (say h 1 (req 1 "_relay/roles" {}))]
    (is (= ["clerk" "hub"] (map #(get % "id") (get-in (first (writes fx 1)) ["result" "roles"]))))
    (let [[h fx] (run h {:event :log-changed :roster roster})]
      (is (empty? fx) "nothing changed")
      (let [[_ fx] (run h {:event :log-changed :roster [(assoc (roster-entry "clerk") :working? true) (roster-entry "hub")]})
            [n] (writes fx 1)]
        (is (= "_relay/roles/changed" (get n "method")))
        (is (= 2 (count (get-in n ["params" "roles"]))))
        (is (true? (get-in n ["params" "roles" 0 "working"])))))))

(deftest seen-is-a-request-relay-answers
  (let [[h fx] (say (live) 1 (req 8 "_relay/seen" {:sessionId "s1" :turn 2}))
        [{:keys [tag command]}] (submits fx)]
    (is (= {:type :conversation/seen :actor "operator" :args {:wake 7 :turn 2}} command))
    (is (= [{"jsonrpc" "2.0" "id" 8 "result" {}}] (writes (second (run h {:event :submit-result :tag tag :ok true})) 1)))))

(deftest ending-a-conversation-answers-everything-owed
  (let [h (opened (live) 2 :approvals true)
        [h] (say h 2 (req 1 "session/load" {:sessionId "s1"}))
        [h fx] (say h 1 (req 30 "session/prompt" {:sessionId "s1" :prompt []}))
        h (submit-ok h fx)
        [h fx] (say h 2 (req 31 "_relay/items" {:sessionId "s1"}))
        [h] (agent h "clerk" (permission 900))
        [h fx] (say h 2 (req 40 "_relay/conversations/end" {:sessionId "s1"}))]
    (is (= :send (:effect (first fx))) "the turn is cancelled first")
    (is (= :stop (:effect (last fx))) "then the conversation stopped")
    (is (= 2 (count (filter #(= "_relay/permission/settled" (get % "method")) (concat (writes fx 1) (writes fx 2)))))
        "its permission request is settled with the turn")
    (let [[h fx] (run h {:event :conversation-gone :role "clerk"})]
      (is (= [{"jsonrpc" "2.0" "id" 30 "error" {"code" -32000 "message" "The conversation ended."}}]
             (filter #(= 30 (get % "id")) (writes fx 1))))
      (is (= #{31 40} (set (keep #(get % "id") (writes fx 2)))))
      (is (= -32000 (get-in (first (filter #(= 31 (get % "id")) (writes fx 2))) ["error" "code"]))
          "a request the agent never answered fails")
      (is (nil? (get-in h [:convs "clerk"])))
      (is (every? empty? (map :attached (vals (:conns h))))))))

(deftest embedded-ends-every-conversation-with-its-connection
  (let [[_ fx] (run (live :mode :embedded) {:event :connection-closed :conn 1})]
    (is (= [{:effect :stop :role "clerk"}] fx)))
  (let [[_ fx] (run (live) {:event :connection-closed :conn 1})]
    (is (empty? fx) "service keeps it")))

;; --- relay's own methods, routed

(deftest the-pane-s-methods-go-to-the-conversation-they-name
  (let [h (opened (live) 2 :approvals false)
        [h] (say h 2 (req 1 "session/load" {:sessionId "s1"}))
        [h fx] (say h 2 (req 50 "_relay/items" {:sessionId "s1"}))
        [sent] (map parse (sends fx "clerk"))
        items {:items [{:kind "shown" :canTrust true} {:kind "proposal"}]}
        [_ fx] (agent h "clerk" (res (get sent "id") items))]
    (is (= ["_relay/items" {"sessionId" "s1"}] [(get sent "method") (get sent "params")]))
    (is (= [{"kind" "shown" "canTrust" false} {"kind" "proposal"}] (get-in (first (writes fx 2)) ["result" "items"]))
        "only a client that may approve is offered trust")
    (testing "trust from a client that cannot approve is refused before the conversation sees it"
      (let [[_ fx] (say h 2 (req 51 "_relay/trust" {:sessionId "s1" :path "a.md"}))]
        (is (empty? (sends fx "clerk")))
        (is (= -32000 (get-in (first (writes fx 2)) ["error" "code"])))))
    (testing "the board is the hub's: a proxy's word about it goes nowhere"
      (let [[_ fx] (agent h "clerk" (note "_relay/cards/changed" {:rev 3 :cards []}))]
        (is (empty? fx))))
    (testing "an unknown method of relay's is refused"
      (is (= -32601 (get-in (first (writes (second (say h 2 (req 53 "_relay/nope" {}))) 2)) ["error" "code"]))))))

(deftest the-agent-gets-no-answer-it-is-not-owed
  (let [[_ fx] (agent (live) "clerk" (res 999 {:x 1}))]
    (is (empty? fx)))
  (testing "a request of the agent's other than for permission is refused"
    (let [[_ fx] (agent (live) "clerk" (req 77 "fs/read_text_file" {:path "/etc/passwd"}))]
      (is (= -32601 (get-in (parse (first (sends fx "clerk"))) ["error" "code"])))
      (is (empty? (writes fx 1))))))

;; --- properties: random runs in a simulated world (relay.hub-sim)

(defn- run-world [mode actions]
  (let [w (reduce sim/act (sim/start mode) actions)
        w (sim/drain w)]
    (sim/final-problems w)))

(defspec the-hub-keeps-its-promises-as-a-service 300
  ;; Every client request gets exactly one response; ids never collide;
  ;; the first answer to a permission request wins and the others are
  ;; told; a client attaching sees the history then every later update,
  ;; with no gap and no duplicate; a queued prompt starts once its role
  ;; is free; ending a conversation answers everything owed.
  (prop/for-all [actions (gen/vector sim/gen-action 1 80)]
    (let [problems (run-world :service actions)]
      (or (empty? problems) (do (println "problems:" (take 5 problems)) false)))))

(defspec a-late-attacher-starts-with-the-latest-labels 300
  ;; With a cap small enough to drop the update that carried them.
  (prop/for-all [actions (gen/vector sim/gen-action 1 80)]
    (let [w (sim/drain (reduce sim/act (sim/start :service 400) actions))
          problems (filter #(= :replay-labels (first %)) (sim/final-problems w))]
      (or (empty? problems) (do (println "problems:" (take 5 problems)) false)))))

(defspec requests-stay-bounded-however-clients-come-and-go 300
  ;; A small bound, agents that hold answers back, and clients that leave
  ;; requests behind.
  (prop/for-all [actions (gen/vector sim/gen-action 1 100)]
    (let [problems (sim/final-problems (sim/drain (reduce sim/act (sim/start :service nil 4) actions)))]
      (or (empty? problems) (do (println "problems:" (take 5 problems)) false)))))

(defspec the-hub-keeps-its-promises-embedded 200
  (prop/for-all [actions (gen/vector sim/gen-action 1 60)]
    (let [problems (run-world :embedded (cons [:open true 0] actions))]
      (or (empty? problems) (do (println "problems:" (take 5 problems)) false)))))

;; --- Codex round 1 (relay#64)

(deftest the-replay-keeps-only-what-it-shows
  ;; A subvec kept its whole backing vector, so every dropped update
  ;; stayed in memory. The buffer must hold only the entries it keeps.
  (let [h (assoc (live) :replay-cap 200)
        upd #(note "session/update" {:sessionId "s1" :update {:sessionUpdate "agent_message_chunk"
                                                              :content {:type "text" :text (str "chunk " %)}}})
        h (reduce (fn [h i] (first (agent h "clerk" (upd i)))) h (range 1000))
        {:keys [entries size]} (get-in h [:convs "clerk" :replay])]
    (is (instance? clojure.lang.PersistentQueue entries) "a queue releases what it drops; a subvec does not")
    (is (<= size 200))
    (is (= size (reduce + (map count entries))) "the count covers every retained entry")
    (is (= 1 (count entries)))))

(deftest a-session-new-waiting-for-creation-is-answered-once-whoever-leaves
  (testing "the creator leaves while another waits: the other is answered and attached"
    (let [h (-> (fresh) (opened 1) (opened 2))
          [h] (say h 1 (req 1 "session/new" {:cwd "/a" :_meta {"relay/role" "clerk"}}))
          [h] (run h {:event :conversation-started :role "clerk" :wake 7})
          [h fx] (agent h "clerk" (res 1 {:protocolVersion 1}))
          [new] (map parse (sends fx "clerk"))
          _ (is (= "session/new" (get new "method")) "the agent is asked for the session")
          [h] (say h 2 (req 7 "session/new" {:cwd "/b" :_meta {"relay/role" "clerk"}}))
          [h] (run h {:event :connection-closed :conn 1})
          [h fx] (agent h "clerk" (res (get new "id") {:sessionId "s1"}))]
      (is (= [7] (keep #(get % "id") (writes fx 2))) "the waiter is answered, once")
      (is (contains? (get-in h [:conns 2 :attached]) "clerk"))))
  (testing "the agent exits while creating: the creator is answered once"
    (let [h (opened (fresh) 1)
          [h] (say h 1 (req 1 "session/new" {:_meta {"relay/role" "clerk"}}))
          [h] (run h {:event :conversation-started :role "clerk" :wake 7})
          [h] (agent h "clerk" (res 1 {:protocolVersion 1}))
          [_ fx] (run h {:event :conversation-gone :role "clerk"})]
      (is (= [1] (keep #(get % "id") (writes fx 1)))))))

(deftest one-prompt-at-a-time-per-session
  (let [[h fx] (say (live) 1 (req 30 "session/prompt" {:sessionId "s1" :prompt []}))
        h (submit-ok h fx)]
    (testing "a second prompt while one runs is refused"
      (let [[h fx] (say h 1 (req 31 "session/prompt" {:sessionId "s1" :prompt []}))]
        (is (= -32000 (get-in (first (writes fx 1)) ["error" "code"])))
        (is (empty? (hub/queued h "clerk")))
        (testing "and cancel stops the running turn"
          (let [[_ fx] (say h 1 (note "session/cancel" {:sessionId "s1"}))]
            (is (= ["session/cancel"] (map #(get (parse %) "method") (sends fx "clerk"))))))))
    (testing "a thousand retries keep nothing"
      (let [h (reduce (fn [h i] (first (say h 1 (req (+ 100 i) "session/prompt" {:sessionId "s1" :prompt []})))) h (range 1000))]
        (is (empty? (hub/queued h "clerk"))))))
  (testing "a queued prompt holds the session too"
    (let [[h fx] (say (live) 1 (req 30 "session/prompt" {:sessionId "s1" :prompt []}))
          [h] (run h {:event :submit-result :tag (:tag (first (submits fx))) :rejection :busy})
          [h fx] (say (opened h 2) 2 (req 1 "session/load" {:sessionId "s1"}))
          [h fx] (say h 2 (req 2 "session/prompt" {:sessionId "s1" :prompt []}))]
      (is (= -32000 (get-in (first (filter #(= 2 (get % "id")) (writes fx 2))) ["error" "code"])))
      (is (= 1 (count (hub/queued h "clerk")))))))

(deftest cancel-from-any-attached-client-stops-the-running-turn
  ;; One person on several devices: ctrl-c on one stops a turn the other
  ;; started.
  (let [h (opened (live) 2)
        [h] (say h 2 (req 1 "session/load" {:sessionId "s1"}))
        [h fx] (say h 1 (req 30 "session/prompt" {:sessionId "s1" :prompt []}))
        h (submit-ok h fx)
        [_ fx] (say h 2 (note "session/cancel" {:sessionId "s1"}))]
    (is (= ["session/cancel"] (map #(get (parse %) "method") (sends fx "clerk"))))))

;; --- Codex round 1 (relay-tui finding 4)

(deftest a-replay-starts-with-the-latest-labels-even-when-the-cap-dropped-them
  ;; The update that carried the session's labels was dropped with the
  ;; oldest history, so a client attaching rendered later text as clean
  ;; and left its links live.
  (let [h (assoc (live) :replay-cap 300)
        [h] (agent h "clerk" (note "session/update" {:sessionId "s1" :update {:sessionUpdate "tool_call_update" :toolCallId "t1"
                                                                              :_meta {"agentloop/labels" ["contaminated"]}}}))
        [h] (agent h "clerk" (note "session/update" {:sessionId "s1" :update {:sessionUpdate "usage_update" :used 5 :size 100}}))
        upd #(note "session/update" {:sessionId "s1" :update {:sessionUpdate "agent_message_chunk"
                                                              :content {:type "text" :text (str "https://example.com/" %)}}})
        h (reduce (fn [h i] (first (agent h "clerk" (upd i)))) h (range 20))
        [_ fx] (say (opened h 2) 2 (req 1 "session/load" {:sessionId "s1"}))
        [labels usage & more] (writes fx 2)]
    (is (true? (get-in (first (filter #(= "_relay/replayed" (get % "method")) more)) ["params" "truncated"])))
    (is (= {"sessionUpdate" "session_info_update" "_meta" {"agentloop/labels" ["contaminated"]}}
           (get-in labels ["params" "update"]))
        "the labels come first, before any text they cover")
    (is (= "usage_update" (get-in usage ["params" "update" "sessionUpdate"])) "and the latest usage")
    (is (not-any? #(= "tool_call_update" (get-in % ["params" "update" "sessionUpdate"])) more) "the old update itself was dropped"))
  (testing "a permission request's labels count too"
    (let [[h] (agent (live) "clerk" (line {:jsonrpc "2.0" :id 900 :method "session/request_permission"
                                          :params {:sessionId "s1" :toolCall {:toolCallId "c1" :_meta {"agentloop/labels" ["private"]}}
                                                   :options []}}))
          [_ fx] (say (opened h 2) 2 (req 1 "session/load" {:sessionId "s1"}))]
      (is (= ["private"] (get-in (first (writes fx 2)) ["params" "update" "_meta" "agentloop/labels"]))))))

;; --- Codex round 2

(deftest a-request-id-still-outstanding-cannot-be-reused
  ;; The agent's late answer to the first went out as the answer to the
  ;; second, here one relay answers itself.
  (let [[h fx] (say (live) 1 (req 77 "slow" {:sessionId "s1"}))
        [sent] (map parse (sends fx "clerk"))
        [h fx] (say h 1 (req 77 "_relay/cards" {}))]
    (is (= [{"jsonrpc" "2.0" "id" 77 "error" {"code" -32600 "message" "Request id 77 is already in use on this connection."}}]
           (writes fx 1)))
    (is (empty? (sends fx "clerk")) "nothing goes on")
    (let [[h fx] (agent h "clerk" (res (get sent "id") {:late true}))]
      (is (= [{"jsonrpc" "2.0" "id" 77 "result" {"late" true}}] (writes fx 1)) "the first is answered, once")
      (testing "and once answered, the id is free again"
        (let [[_ fx] (say h 1 (req 77 "slow" {:sessionId "s1"}))]
          (is (= ["slow"] (map #(get (parse %) "method") (sends fx "clerk")))))))))

(deftest a-turn-that-ends-retires-its-permission-requests
  (let [h (opened (live) 2 :approvals true)
        [h] (say h 2 (req 1 "session/load" {:sessionId "s1"}))
        [h fx] (say h 1 (req 30 "session/prompt" {:sessionId "s1" :prompt []}))
        h (submit-ok h fx)
        prompt-id (:up (second (hub/running h "clerk")))
        [h fx] (agent h "clerk" (permission 900))
        p2 (first (writes fx 2))]
    (testing "the agent cancelling its request: each client is told under its own id, and it is settled"
      (let [[h' fx] (agent h "clerk" (note "$/cancel_request" {:requestId 900}))]
        (is (= [{"jsonrpc" "2.0" "method" "$/cancel_request" "params" {"requestId" (get p2 "id")}}
                {"jsonrpc" "2.0" "method" "_relay/permission/settled" "params" {"sessionId" "s1" "toolCallId" "c1" "outcome" "cancelled"}}]
               (writes fx 2)))
        (is (= [{"jsonrpc" "2.0" "id" 900 "result" {"outcome" {"outcome" "cancelled"}}}] (map parse (sends fx "clerk")))
            "and the proxy's record of it is closed")
        (is (empty? (get-in h' [:convs "clerk" :perms])))))
    (testing "the turn ending, however: no client is asked about it again"
      (let [[h fx] (agent h "clerk" (res prompt-id {:stopReason "cancelled"}))
            h (submit-ok h fx)]
        (is (some #(= "_relay/permission/settled" (get % "method")) (writes fx 2)))
        (is (empty? (get-in h [:convs "clerk" :perms])))
        (let [[_ fx] (say (opened h 3 :approvals true) 3 (req 1 "session/load" {:sessionId "s1"}))]
          (is (not-any? #(= "session/request_permission" (get % "method")) (writes fx 3))))))
    (testing "cancelling the turn answers its requests cancelled, as ACP asks of a client"
      (let [[_ fx] (say h 1 (note "session/cancel" {:sessionId "s1"}))]
        (is (= ["session/cancel" nil] (map #(get (parse %) "method") (sends fx "clerk"))))
        (is (= {"outcome" {"outcome" "cancelled"}} (get (parse (second (sends fx "clerk"))) "result")))))))

(deftest a-client-cancelling-its-own-request-is-passed-on-under-relay-s-id
  (let [[h fx] (say (live) 1 (req 77 "slow" {:sessionId "s1"}))
        [sent] (map parse (sends fx "clerk"))
        [_ fx] (say h 1 (note "$/cancel_request" {:requestId 77}))]
    (is (= [{"jsonrpc" "2.0" "method" "$/cancel_request" "params" {"requestId" (get sent "id")}}]
           (map parse (sends fx "clerk"))))))

(deftest waiting-for-a-session-is-bounded
  (let [h (-> (fresh) (opened 1))
        [h] (say h 1 (req 1 "session/new" {:_meta {"relay/role" "clerk"}}))
        [h fx] (say h 1 (req 2 "session/new" {:_meta {"relay/role" "clerk"}}))]
    (is (= -32600 (get-in (first (writes fx 1)) ["error" "code"])) "one waiter per connection and role")
    (is (= 1 (count (filter #(= :waiting (:state %)) (vals (:requests h)))))))
  (testing "and a connection has only so many requests outstanding"
    (let [h (-> (live) (assoc :max-pending 3))
          h (reduce (fn [h i] (first (say h 1 (req (+ 100 i) "slow" {:sessionId "s1"})))) h (range 3))
          [_ fx] (say h 1 (req 200 "slow" {:sessionId "s1"}))]
      (is (= -32000 (get-in (first (writes fx 1)) ["error" "code"])))
      (is (empty? (sends fx "clerk"))))))

(deftest a-response-with-an-empty-method-is-a-response
  ;; The proxy took it for one and the hub for a request, so the turn
  ;; never ended.
  (let [[h fx] (say (live) 1 (req 30 "session/prompt" {:sessionId "s1" :prompt []}))
        h (submit-ok h fx)
        id (:up (second (hub/running h "clerk")))
        [h fx] (agent h "clerk" (line {:jsonrpc "2.0" :id id :method "" :result {:stopReason "end_turn"}}))]
    (is (= :conversation/turn-end (get-in (first (submits fx)) [:command :type])))
    (is (= 30 (get (first (writes fx 1)) "id")))
    (is (nil? (hub/running h "clerk")))))

(defn- board-states
  "A core state with one card on the board, and the state after it is
  ticked."
  []
  (let [cmd (fn [state actor type args]
              (first (core/run-command state {:type type :actor actor :labels [] :args args} {:now t0 :config ct/cfg})))
        s0 (-> core/empty-state
               (cmd "operator" :role/add {:id "clerk" :instructions "x"})
               (cmd "operator" :card/add {:kind "checklist" :title "Taxes" :items ["Find the T4" "File"]}))
        id (first (keys (:cards s0)))]
    [s0 (cmd s0 "operator" :card/op {:card id :op "tick" :item 1}) id]))

(defn- board-items [msg] (mapv #(get % "done") (get-in msg ["params" "cards" 0 "items"])))

(deftest the-board-is-the-hub-s-and-each-subscriber-gets-what-it-lacks
  (let [[s0 s1 card] (board-states)
        subscribed (fn []
                     (let [h (-> (live) (assoc :state s0) (opened 2))
                           [h] (say h 1 (req 5 "_relay/cards" {}))
                           [h] (say h 2 (req 6 "_relay/cards" {}))]
                       h))]
    (testing "a card one client changes reaches the other; the changer gets it in its answer"
      (let [[h fx] (say (subscribed) 1 (req 7 "_relay/cards/op" {:card card :op "tick" :item 1}))
            [{:keys [tag]}] (submits fx)
            [_ fx] (run h {:event :submit-result :tag tag :ok true :changed true :state s1})]
        (is (= [7] (keep #(get % "id") (writes fx 1))))
        (is (empty? (filter #(= "_relay/cards/changed" (get % "method")) (writes fx 1))))
        (is (= [[true false]] (map board-items (writes fx 2))))))
    (testing "the changer leaving at once loses the others nothing: relay decides the op before anything else happens"
      (let [[h fx] (say (subscribed) 1 (req 7 "_relay/cards/op" {:card card :op "tick" :item 1}))
            [h fx2] (run h {:event :submit-result :ok true :changed true :state s1})
            [_ fx3] (run h {:event :connection-closed :conn 1})]
        (is (= [[true false]] (map board-items (writes fx2 2))))
        (is (thrown? clojure.lang.ExceptionInfo (run (first (say (subscribed) 1 (req 7 "_relay/cards/op" {:card card :op "tick" :item 1})))
                                                     {:event :connection-closed :conn 1}))
            "no event can come between a submit and its result")))
    (testing "a second client subscribing takes nothing from the first"
      (let [h (-> (live) (assoc :state s0))
            [h] (say h 1 (req 5 "_relay/cards" {}))
            ;; Another process ticks the card; the second client subscribes
            ;; before the hub has read the change.
            h (opened h 2)
            [h] (say h 2 (req 6 "_relay/cards" {}))
            [_ fx] (run h {:event :log-changed :roster roster :state s1})]
        (is (= [[true false]] (map board-items (writes fx 1))))
        (is (= [[true false]] (map board-items (writes fx 2))))))
    (testing "and what was sent is not sent again"
      (let [h (-> (subscribed) (assoc :state s0))
            [h] (run h {:event :log-changed :roster roster :state s1})
            [_ fx] (run h {:event :log-changed :roster roster :state s1})]
        (is (empty? fx))))))

;; relay#73: the board's methods are the connection's. A client with no
;; conversation open uses them, and a sessionId is refused like any
;; parameter they do not take.
(deftest the-board-needs-no-conversation
  (let [[s0 s1 card] (board-states)
        h (-> (fresh) (assoc :state s0) (opened 1 :approvals true))
        [h] (say h 1 (req 1 "initialize" {:protocolVersion 1 :clientCapabilities {}}))
        [h fx] (say h 1 (req 2 "_relay/cards" {}))]
    (is (empty? (get-in h [:conns 1 :attached])) "no conversation is open")
    (is (= [card] (map #(get % "id") (get-in (first (writes fx 1)) ["result" "cards"]))))
    (testing "an op is taken and answered"
      (let [[h fx] (say h 1 (req 3 "_relay/cards/op" {:card card :op "tick" :item 1}))
            [{:keys [tag command]}] (submits fx)
            [_ fx] (run h {:event :submit-result :tag tag :ok true :changed true :state s1})]
        (is (= ["operator" {:card card :op "tick" :item 1}] [(:actor command) (:args command)]))
        (is (= [true false] (mapv #(get % "done") (get-in (first (writes fx 1)) ["result" "card" "items"]))))))
    (testing "a trust is taken from a client with approvals"
      (let [[_ fx] (say h 1 (req 4 "_relay/cards/trust" {:card card :rev 1 :sha256 (apply str (repeat 64 "a"))}))]
        (is (= [:label/trust] (map #(get-in % [:command :type]) (submits fx))))))
    (testing "and a change elsewhere is sent with no conversation open"
      (let [[_ fx] (run h {:event :log-changed :roster roster :state s1})]
        (is (= [[true false]] (map board-items (filter #(= "_relay/cards/changed" (get % "method")) (writes fx 1)))))))
    (testing "a sessionId is refused on every board method"
      (doseq [[method params] [["_relay/cards" {:sessionId "s1"}]
                               ["_relay/cards/op" {:sessionId "s1" :card card :op "tick" :item 1}]
                               ["_relay/cards/trust" {:sessionId "s1" :card card :rev 1 :sha256 (apply str (repeat 64 "a"))}]]]
        (let [[_ fx] (say h 1 (req 9 method params))
              [w] (writes fx 1)]
          (is (empty? (submits fx)) method)
          (is (= -32602 (get-in w ["error" "code"])) method)
          (is (str/includes? (str (get-in w ["error" "message"])) "no parameter sessionId") method))))))

;; --- Codex round 3

(deftest an-id-stays-reserved-while-its-request-is-outstanding
  ;; A malformed line carrying the id freed it, so a later request could
  ;; take the id and get the agent's late answer to the first.
  (let [[h fx] (say (live) 1 (req 77 "slow" {:sessionId "s1"}))
        [sent] (map parse (sends fx "clerk"))
        [h fx] (say h 1 "{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"x\",")]
    (is (empty? (writes fx 1)) "no answer under an id whose request is outstanding")
    (let [[h fx] (say h 1 (req 77 "_relay/cards" {}))]
      (is (= -32600 (get-in (first (writes fx 1)) ["error" "code"])) "still reserved")
      (let [[_ fx] (agent h "clerk" (res (get sent "id") {:late true}))]
        (is (= [{"jsonrpc" "2.0" "id" 77 "result" {"late" true}}] (writes fx 1)))))))

(deftest orphans-are-bounded-per-conversation-across-reconnects
  ;; Requests a client left behind stay until the agent answers, and count
  ;; against the conversation, so reconnecting cannot get past the bound.
  (let [h (assoc (live) :max-per-conversation 5)
        h (reduce (fn [h cid]
                    (let [h (opened h cid)
                          [h] (say h cid (req 1 "session/load" {:sessionId "s1"}))
                          [h] (say h cid (req 2 "slow" {:sessionId "s1"}))]
                      (first (run h {:event :connection-closed :conn cid}))))
                  h (range 10 20))]
    (is (<= (count (:requests h)) 5))
    (let [h (opened h 30)
          [h] (say h 30 (req 1 "session/load" {:sessionId "s1"}))
          [_ fx] (say h 30 (req 2 "slow" {:sessionId "s1"}))]
      (is (= -32000 (get-in (first (writes fx 30)) ["error" "code"])) "past the bound, refused")
      (is (empty? (sends fx "clerk"))))))

(deftest cancel-drops-every-waiting-prompt-in-the-session
  (let [h (opened (live) 2)
        [h] (say h 2 (req 1 "session/load" {:sessionId "s1"}))
        [h fx] (say h 1 (req 30 "session/prompt" {:sessionId "s1" :prompt []}))
        [h] (run h {:event :submit-result :tag (:tag (first (submits fx))) :rejection :busy})
        [h fx] (say h 2 (note "session/cancel" {:sessionId "s1"}))]
    (is (= [{"jsonrpc" "2.0" "id" 30 "result" {"stopReason" "cancelled"}}] (writes fx 1))
        "the other device's waiting prompt is cancelled, answered to its own client")
    (is (empty? (hub/queued h "clerk")))
    (let [[_ fx] (run h {:event :log-changed :roster roster})]
      (is (empty? (sends fx "clerk")) "and it never runs"))))

(deftest a-turn-end-relay-could-not-record-is-tried-until-it-is
  (let [[h fx] (say (live) 1 (req 30 "session/prompt" {:sessionId "s1" :prompt []}))
        h (submit-ok h fx)
        up (:up (second (hub/running h "clerk")))
        [h fx] (agent h "clerk" (res up {:stopReason "end_turn"}))
        [{:keys [tag]}] (submits fx)
        [h fx] (run h {:event :submit-result :tag tag :rejection :error :message "No space left on device"})]
    (is (= 1 (count (filter #(= :warn (:effect %)) fx))) "the operator is told on stderr")
    (is (= [:conversation/turn-unrecorded] (map #(get-in % [:command :type]) (submits fx))) "and through notify")
    (testing "the role stays busy: no other turn starts"
      (let [h (submit-ok h fx)
            [h fx] (say h 1 (req 31 "session/prompt" {:sessionId "s1" :prompt []}))]
        (is (empty? (filter #(= :conversation/turn-start (get-in % [:command :type])) (submits fx))))
        (testing "it is tried again when time passes, told only once more never"
          (let [[h fx] (run h {:event :tick})
                [{:keys [tag]}] (submits fx)
                _ (is (= :conversation/turn-end (get-in (first (submits fx)) [:command :type])))
                [h fx] (run h {:event :submit-result :tag tag :rejection :error :message "still full"})
                _ (is (empty? (filter #(= :warn (:effect %)) fx)) "not told twice")
                [h fx] (run h {:event :log-changed :roster roster})
                [{:keys [tag]}] (submits fx)
                [_ fx] (run h {:event :submit-result :tag tag :ok true})]
            (is (= [:conversation/turn-start] (map #(get-in % [:command :type]) (submits fx)))
                "once recorded, the waiting prompt gets its turn")))))))

(deftest a-turn-relay-could-not-record-does-not-run
  (let [[h fx] (say (live) 1 (req 30 "session/prompt" {:sessionId "s1" :prompt []}))
        [_ fx] (run h {:event :submit-result :tag (:tag (first (submits fx))) :rejection :error :message "No space left on device"})]
    (is (empty? (sends fx "clerk")))
    (is (= "No space left on device" (get-in (first (writes fx 1)) ["error" "message"])))))

;; --- independent review

(defn- drive
  "The hub through an event and all it leads to, in the shell's order,
  with relay answering each submit ok (or as `decide` says). Returns
  [hub effects]."
  [h e & [decide]]
  (let [fx (volatile! [])
        h (hub/drive h e (fn [f] (vswap! fx conj f)
                           (when (= :submit (:effect f))
                             (merge {:event :submit-result} ((or decide (constantly {:ok true})) f)))))]
    [h @fx]))

(deftest a-prompt-never-shows-queued-while-its-role-is-free
  ;; The shell once carried out a submit's result before the rest of its
  ;; step's effects, so a roster subscriber got idle and then a stale
  ;; queued, and kept queued until the next log change.
  (let [idle {:wake 7 :session "s1" :opened-at t0 :last-activity t0 :turn :idle :turns 0 :unread? false :approvals 0}
        h (assoc (live) :roster [(roster-entry "clerk" idle) (roster-entry "hub")])
        [h] (drive h {:event :connection-line :conn 1 :line (req 5 "_relay/roles" {})})
        turns (fn [fx] (for [m (writes fx 1) :when (= "_relay/roles/changed" (get m "method"))]
                         (get-in m ["params" "roles" 0 "conversation" "turn"])))
        [h fx] (drive h {:event :connection-line :conn 1 :line (req 30 "session/prompt" {:sessionId "s1" :prompt []})})]
    (is (not-any? #{"queued"} (turns fx)) (pr-str (turns fx)))
    (testing "while the role is busy it is queued, and stays so until it runs"
      (let [h (assoc (live) :roster [(roster-entry "clerk" idle) (roster-entry "hub")])
            [h] (drive h {:event :connection-line :conn 1 :line (req 5 "_relay/roles" {})})
            [h fx] (drive h {:event :connection-line :conn 1 :line (req 30 "session/prompt" {:sessionId "s1" :prompt []})}
                          (constantly {:rejection :busy}))]
        (is (= ["queued"] (turns fx)))))))

(defn- canon
  "Updates as their history reads: consecutive text chunks of one kind
  joined."
  [msgs]
  (let [chunk? #(and (#{"agent_message_chunk" "agent_thought_chunk" "user_message_chunk"} (get-in % ["params" "update" "sessionUpdate"]))
                     (string? (get-in % ["params" "update" "content" "text"])))
        bare #(update-in % ["params" "update" "content"] dissoc "text")]
    (reduce (fn [acc m]
              (let [l (peek acc)]
                (if (and l (chunk? l) (chunk? m) (= (bare l) (bare m)))
                  (conj (pop acc) (update-in l ["params" "update" "content" "text"] str (get-in m ["params" "update" "content" "text"])))
                  (conj acc m))))
            [] msgs)))

(def gen-update
  (gen/one-of [(gen/fmap (fn [[k t]] {:sessionId "s1" :update {:sessionUpdate k :content {:type "text" :text t}}})
                         (gen/tuple (gen/elements ["agent_message_chunk" "agent_thought_chunk" "user_message_chunk"])
                                    gen/string-alphanumeric))
               (gen/fmap (fn [n] {:sessionId "s1" :update {:sessionUpdate "tool_call_update" :toolCallId (str "t" n)}})
                         gen/nat)
               (gen/return {:sessionId "s1" :update {:sessionUpdate "agent_message_chunk" :messageId "m2"
                                                     :content {:type "text" :text "x"}}})]))

(defspec a-replay-merges-text-and-keeps-everything-else 200
  (prop/for-all [updates (gen/vector gen-update 0 40)]
    (let [lines (map #(note "session/update" %) updates)
          h (reduce (fn [h l] (first (agent h "clerk" l))) (live) lines)
          [_ fx] (say (opened h 2) 2 (req 1 "session/load" {:sessionId "s1"}))
          replayed (filter #(= "session/update" (get % "method")) (writes fx 2))]
      (and (= (canon (map parse lines)) (canon replayed))
           (<= (count replayed) (count lines))))))

(deftest a-waiting-prompt-keeps-its-place-when-its-client-goes
  (let [h (opened (live) 2)
        [h] (say h 2 (req 1 "session/load" {:sessionId "s1"}))
        [h] (drive h {:event :connection-line :conn 1 :line (req 30 "session/prompt" {:sessionId "s1" :prompt [{:type "text" :text "Later"}]})}
                   (constantly {:rejection :busy}))
        [h] (drive h {:event :connection-closed :conn 1})
        _ (is (= 1 (count (hub/queued h "clerk"))) "it waits, an orphan")
        [h fx] (drive h {:event :log-changed :roster roster})]
    (is (= ["session/prompt"] (map #(get (parse %) "method") (sends fx "clerk"))) "and runs once the role is free")
    (is (= [{"sessionUpdate" "user_message_chunk" "content" {"type" "text" "text" "Later"}}]
           (map #(get-in % ["params" "update"]) (writes fx 2)))
        "the other client sees it start")
    (let [[h fx] (drive h {:event :agent-line :role "clerk" :line (res (:up (second (hub/running h "clerk"))) {:stopReason "end_turn"})})]
      (is (= [:conversation/turn-end] (map #(get-in % [:command :type]) (submits fx))))
      (is (empty? (writes fx 1)) "its answer goes nowhere")
      (is (empty? (:requests h))))))

(deftest the-properties-reach-what-they-are-about
  ;; Each property is only as good as the runs it sees: a change to the
  ;; generator must not quietly stop them reaching these paths. Half the
  ;; runs have a cap small enough to truncate a replay.
  (let [runs (for [i (range 300)]
               (sim/drain (reduce sim/act (sim/start :service (when (odd? i) 400))
                                  (gen/generate (gen/vector sim/gen-action 1 80) (mod i 100) i))))
        share (fn [k] (/ (count (filter #(pos? (get-in % [:seen k] 0)) runs)) (count runs)))]
    (doseq [k [:settled-for-another :unattended :truncated :queued-then-run :orphan-answered]]
      (is (>= (share k) 0.02) (str k " reached in " (double (share k)) " of runs")))))
