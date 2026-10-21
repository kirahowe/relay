(ns relay.hub-test
  "The hub, as data: an example for each protocol message, and properties
  over random runs against a simulated agent and relay (relay.hub-sim)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
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
    (is (= {"relay/hub" 1} (get-in a ["result" "_meta"])))
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
            {"jsonrpc" "2.0" "method" "_relay/replayed" "params" {"sessionId" "s1"}}]
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

(deftest a-second-session-new-attaches-with-the-replay
  (let [h (-> (live) (opened 2))
        upd (note "session/update" {:sessionId "s1" :update {:sessionUpdate "agent_message_chunk" :content {:type "text" :text "hi"}}})
        [h fx] (agent h "clerk" upd)
        _ (is (= [(parse upd)] (writes fx 1)) "the attached client sees it")
        [h fx] (say h 2 (req 5 "session/new" {:cwd "/x" :_meta {"relay/role" "clerk"}}))]
    (is (= [{"jsonrpc" "2.0" "id" 5 "result" {"sessionId" "s1" "_meta" {"relay/attached" true}}}
            (parse upd)
            {"jsonrpc" "2.0" "method" "_relay/replayed" "params" {"sessionId" "s1"}}]
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
    (is (= [(parse upd) {"jsonrpc" "2.0" "method" "_relay/replayed" "params" {"sessionId" "s1"}}
            {"jsonrpc" "2.0" "id" 4 "result" {}}]
           (writes fx 2)))
    (is (contains? (get-in h [:conns 2 :attached]) "clerk"))
    (testing "nothing that is not live is reopened"
      (let [[_ fx] (say h 2 (req 5 "session/load" {:sessionId "old"}))]
        (is (= -32602 (get-in (first (writes fx 2)) ["error" "code"])))))))

(deftest a-replay-past-its-cap-starts-with-a-notice
  (let [h (assoc (live) :replay-cap 300)
        upd #(note "session/update" {:sessionId "s1" :update {:sessionUpdate "agent_message_chunk"
                                                              :content {:type "text" :text (str "chunk " %)}}})
        h (reduce (fn [h i] (first (agent h "clerk" (upd i)))) h (range 10))
        [_ fx] (say (opened h 2) 2 (req 1 "session/load" {:sessionId "s1"}))
        got (writes fx 2)
        texts (keep #(get-in % ["params" "update" "content" "text"]) got)]
    (is (= hub/truncated-notice (first texts)))
    (is (= "chunk 9" (last texts)) "the newest are kept")
    (is (< (count texts) 11))))

(deftest session-list-gives-live-conversations
  (let [h (assoc (live) :roster [(roster-entry "clerk" {:wake 7 :session "s1" :opened-at t0 :last-activity t0 :turn :idle :turns 1
                                                          :unread? false :approvals 0}) (roster-entry "hub")])
        [_ fx] (say h 1 (req 9 "session/list" {}))]
    (is (= {"sessions" [{"sessionId" "s1" "cwd" "/w" "title" "Clerk" "updatedAt" (str t0) "_meta" {"relay/role" "clerk"}}]}
           (get (first (writes fx 1)) "result")))))

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
    (is (empty? (get-in h [:convs "clerk" :queue]))))
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
          [sent] (sends fx "clerk")]
      (is (nil? (try (json/parse-string sent) (catch Exception _ nil))) "unreadable, so the proxy refuses it")
      (is (str/includes? sent "\"id\":900"))))
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
      (let [h (opened h 2 :approvals true)
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
        [h fx] (say h 2 (req 31 "session/prompt" {:sessionId "s1" :prompt []}))
        [h] (agent h "clerk" (permission 900))
        [h fx] (say h 2 (req 40 "_relay/conversations/end" {:sessionId "s1"}))]
    (is (= [:send :stop] (map :effect fx)) "the turn is cancelled, then the conversation stopped")
    (let [[h fx] (run h {:event :conversation-gone :role "clerk"})]
      (is (= [{"jsonrpc" "2.0" "id" 30 "error" {"code" -32000 "message" "The conversation ended."}}]
             (filter #(= 30 (get % "id")) (writes fx 1))))
      (is (= #{31 40} (set (keep #(get % "id") (writes fx 2)))))
      (is (= {"stopReason" "cancelled"} (get (first (filter #(= 31 (get % "id")) (writes fx 2))) "result")))
      (is (= 2 (count (filter #(= "_relay/permission/settled" (get % "method")) (concat (writes fx 1) (writes fx 2))))))
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
    (testing "a board change goes only to clients that asked for the board through it"
      (let [[h] (say h 1 (req 52 "_relay/cards" {:sessionId "s1"}))
            [_ fx] (agent h "clerk" (note "_relay/cards/changed" {:rev 3 :cards []}))]
        (is (= 1 (count (writes fx 1))))
        (is (empty? (writes fx 2)))))
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

(defspec the-hub-keeps-its-promises-embedded 200
  (prop/for-all [actions (gen/vector sim/gen-action 1 60)]
    (let [problems (run-world :embedded (cons [:open true 0] actions))]
      (or (empty? problems) (do (println "problems:" (take 5 problems)) false)))))
