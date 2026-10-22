(ns relay.hub-sim
  "A simulated world around the hub, for its properties: connections that
  send what the generated actions say, a fake agent per conversation
  (behind which the proxy's refusals are taken as answers), and a fake
  relay that refuses a turn while a role is busy. The world records what
  each side saw, and `problems` lists every broken invariant from
  section 3 of the design brief."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [relay.acp :as acp]
            [relay.hub :as hub]))

(def roles ["clerk" "hub"])
(def roster (vec (for [r roles] {:id r :title r :labels [] :working? false :holds 0 :conversation nil})))

(def observer
  "In service runs, a connection that attaches to every conversation as
  it opens, never prompts and never leaves: its updates are the
  conversation's whole history."
  0)

(defn- jl [m] (json/generate-string m))
(defn- parse [l] (try (json/parse-string l) (catch Exception _ nil)))

(defn world [mode & [cap max-per-conversation]]
  {:mode mode :cap cap
   :h (first (hub/step (hub/hub (cond-> {:mode mode}
                                  cap (assoc :replay-cap cap)
                                  max-per-conversation (assoc :max-per-conversation max-per-conversation)))
                       {:event :log-changed :roster roster}))
   :conns {} :next-conn 1 :agents {} :busy #{} :running #{} :next-wake 1 :next-text 0
   :problems [] :sent-prompts {}})

(defn- problem [w p] (update w :problems conj p))

;; --- the effects, carried out

(declare run-events carry-out*)

(defn- conn-write [w cid line]
  (let [c (get-in w [:conns cid])
        msg (parse line)]
    (if-not (:open c)
      w
      (as-> w w
        (update-in w [:conns cid :received] conj line)
        ;; A response: to one of its own requests, and only once. A
        ;; request that reused an id still outstanding gets its refusal,
        ;; which is not the first's answer.
        (if (and (contains? msg "id") (not (contains? msg "method")))
          (let [id (get msg "id")]
            (if (and (pos? (get-in w [:conns cid :reuses id] 0))
                     (str/includes? (str (get-in msg ["error" "message"])) "already in use"))
              (update-in w [:conns cid :reuses id] dec)
              (cond-> (update-in w [:conns cid :responses id] (fnil inc 0))
                (not (contains? (get-in w [:conns cid :requests]) id)) (problem [:response-to-nothing cid id])
                (pos? (get-in w [:conns cid :responses id] 0)) (problem [:two-responses cid id]))))
          w)
        ;; A permission request: under an id never used for another.
        (if (= "session/request_permission" (get msg "method"))
          (let [k (acp/id-key (get msg "id"))
                call (get-in msg ["params" "toolCall" "toolCallId"])
                was (get-in w [:conns cid :perm-ids k])]
            (cond-> (assoc-in w [:conns cid :perm-ids k] call)
              (and was (not= was call)) (problem [:perm-id-reused cid k])))
          w)
        (if (and (= "_relay/replayed" (get msg "method")) (true? (get-in msg ["params" "truncated"])))
          (update-in w [:seen :truncated] (fnil inc 0))
          w)
        (if (= "_relay/permission/settled" (get msg "method"))
          (let [call (get-in msg ["params" "toolCallId"])]
            (cond-> (cond-> (update-in w [:conns cid :settled call] (fnil inc 0))
                      ;; Settled by another device's answer.
                      (not= "cancelled" (get-in msg ["params" "outcome"])) (update-in [:seen :settled-for-another] (fnil inc 0)))
              (pos? (get-in w [:conns cid :settled call] 0)) (problem [:settled-twice cid call])))
          w)
        ;; What it sees of each session, in order.
        (if (= "session/update" (get msg "method"))
          (update-in w [:conns cid :updates (get-in msg ["params" "sessionId"])] (fnil conj []) line)
          w)))))

(defn- agent-of [w role] (get-in w [:agents role]))

(defn- agent-says [w role m] (update w :pending conj {:event :agent-line :role role :line (jl m)}))

(defn- agent-gets [w role line]
  (let [msg (parse line)
        a (agent-of w role)]
    (cond
      (nil? a) w
      (nil? msg) (problem w [:unreadable-to-agent role line])
      (and (contains? msg "method") (contains? msg "id"))
      (let [id (get msg "id")
            w (cond-> (update-in w [:agents role :ids] (fnil conj #{}) id)
                (contains? (:ids a) id) (problem [:hub-id-reused role id]))]
        (case (get msg "method")
          ;; A slow agent holds these back until released, so clients
          ;; come and go, and agents die, while a session is created.
          "initialize" (if (contains? (:slow w) role)
                         (update-in w [:agents role :deferred] (fnil conj []) {:jsonrpc "2.0" :id id :result {:protocolVersion 1}})
                         (agent-says w role {:jsonrpc "2.0" :id id :result {:protocolVersion 1}}))
          "session/new" (if (contains? (:slow w) role)
                          (update-in w [:agents role :deferred] (fnil conj []) {:jsonrpc "2.0" :id id :result {:sessionId (:session a)}})
                          (agent-says w role {:jsonrpc "2.0" :id id :result {:sessionId (:session a)}}))
          "session/prompt"
          (if (pos? (get-in w [:turns-ok role] 0))
            (-> w (update-in [:turns-ok role] dec) (update-in [:agents role :prompts] (fnil conj []) id))
            (problem w [:prompt-without-a-turn role id]))
          ;; A slow agent keeps a client's other requests waiting too.
          (if (contains? (:slow w) role)
            (update-in w [:agents role :deferred] (fnil conj []) {:jsonrpc "2.0" :id id :result {}})
            (agent-says w role {:jsonrpc "2.0" :id id :result {}}))))
      (contains? msg "method")
      (if (and (= "session/cancel" (get msg "method")) (seq (:prompts a)))
        (-> w (update-in [:agents role :prompts] (comp vec rest))
            (agent-says role {:jsonrpc "2.0" :id (first (:prompts a)) :result {:stopReason "cancelled"}}))
        w)
      :else
      (let [id (get msg "id")]
        (if (contains? (:perms a) id)
          (cond-> (-> w (update-in [:agents role :perms] disj id) (update-in [:agents role :answers id] (fnil inc 0)))
            (pos? (get-in a [:answers id] 0)) (problem [:perm-answered-twice role id]))
          (problem w [:answer-to-nothing role id]))))))

(defn- relay-decides
  "Relay deciding a submit: the world after it, and the result, which the
  hub sees next."
  [w {:keys [command]}]
  (let [{:keys [type args]} command
        role (some (fn [[r a]] (when (= (:wake args) (:wake a)) r)) (:agents w))
        result (case type
                 :conversation/turn-start (cond (contains? (:busy w) role) {:rejection :busy}
                                                (contains? (:running w) role) {:rejection :invalid :message "in a turn"}
                                                :else {:ok true})
                 {:ok true})
        w (cond-> w
            (and (= type :conversation/turn-start) (:ok result) (contains? (:was-busy w) role))
            (-> (update :was-busy disj role) (update-in [:seen :queued-then-run] (fnil inc 0)))
            (and (= type :conversation/turn-start) (= :busy (:rejection result)))
            (update :was-busy (fnil conj #{}) role)
            (and (= type :conversation/turn-start) (:ok result))
            (-> (update :running conj role) (update-in [:turns-ok role] (fnil inc 0)))
            (= type :conversation/turn-end) (update :running disj role)
            (= type :permission/unattended) (-> (update :unattended (fnil conj []) (:call args))
                                                (update-in [:seen :unattended] (fnil inc 0))))]
    [w (merge {:event :submit-result} result)]))

(defn- carry-out
  "Carries out an effect: the world after it, and the event it leads to
  at once (a submit's result), if any."
  [w {:keys [effect] :as f}]
  (case effect
    :submit (relay-decides w f)
    [(carry-out* w f) nil]))

(defn- carry-out* [w {:keys [effect] :as f}]
  (case effect
    :write (conn-write w (:conn f) (:line f))
    :send (agent-gets w (:role f) (:line f))
    :warn w
    :start (let [role (:role f)
                 wake (:next-wake w)
                 w (cond-> (-> w (update :next-wake inc)
                               (assoc-in [:agents role] {:wake wake :session (str "s-" role "-" wake) :prompts [] :perms #{}}))
                     (= :service (:mode w)) (assoc-in [:conns observer :requests (str "o" wake)] {:method "session/new"}))]
             (update w :pending #(into (vec (concat (when (= :service (:mode w))
                                                      [{:event :connection-line :conn observer
                                                        :line (jl {:jsonrpc "2.0" :id (str "o" wake) :method "session/new"
                                                                   :params {:_meta {"relay/role" role}}})}])
                                                    [{:event :conversation-started :role role :wake wake}]))
                                       %)))
    :stop (update w :pending #(into [{:event :conversation-gone :role (:role f)}] %))))

(defn- gone [w role]
  (-> w (update :agents dissoc role) (update :running disj role) (update :turns-ok dissoc role)))

(defn run-events
  "Steps the hub through the events and everything they lead to, in
  relay.hub/drive's order, the one the shell uses."
  [w events]
  (loop [w (update w :pending (fnil into []) events)]
    (if-let [[e & more] (seq (:pending w))]
      (let [w (assoc w :pending (vec more))
            before (:h w)
            w (cond-> w (= :conversation-gone (:event e)) (gone (:role e)))
            ;; An agent's answer to a request whose client has gone.
            w (let [m (when (= :agent-line (:event e)) (parse (:line e)))]
                (if (and m (contains? m "id") (not (contains? m "method"))
                         (some #(and (:orphan %) (= (:role e) (:role %)) (= (get m "id") (:up %))) (vals (:requests before))))
                  (update-in w [:seen :orphan-answered] (fnil inc 0))
                  w))
            world (volatile! w)
            h (hub/drive (:h w) e (fn [f] (let [[w' follow] (carry-out @world f)] (vreset! world w') follow)))
            w (assoc @world :h h)
            w (if (= :conversation-gone (:event e))
                ;; Ending a conversation answers everything owed about it.
                (let [session (get-in before [:convs (:role e) :session])]
                  (reduce (fn [w [cid c]]
                            (reduce (fn [w [id {:keys [session-id role]}]]
                                      (if (and (:open c) (zero? (get-in w [:conns cid :responses id] 0))
                                               (or (and session session-id (= session session-id)) (= role (:role e))))
                                        (problem w [:owed-after-end cid id])
                                        w))
                                    w (get-in w [:conns cid :requests])))
                          w (:conns w)))
                w)]
        (recur w))
      (dissoc w :pending))))

;; --- connections

(defn- open-conn [w cid opts]
  (-> w (assoc-in [:conns cid] {:open true :next-id 1 :requests {} :responses {} :received [] :updates {} :segments {}})
      (run-events [(merge {:event :connection-opened :conn cid} opts)])))

(defn- conn-sends
  "Connection cid sends a request (when method has :request), recorded so
  its answer can be checked."
  [w cid method params & {:keys [role]}]
  (let [id (get-in w [:conns cid :next-id])
        session (get params :sessionId)]
    (-> w (update-in [:conns cid :next-id] inc)
        (assoc-in [:conns cid :requests id] {:method method :session-id session :role role})
        (run-events [{:event :connection-line :conn cid :line (jl {:jsonrpc "2.0" :id id :method method :params params})}]))))

(defn- conn-notes [w cid method params]
  (run-events w [{:event :connection-line :conn cid :line (jl {:jsonrpc "2.0" :method method :params params})}]))

(defn- live-sessions [w] (vec (sort (keep :session (vals (get-in w [:h :convs]))))))

(defn- attached-sessions [w cid]
  (vec (sort (keep #(get-in w [:h :convs % :session]) (get-in w [:h :conns cid :attached])))))

(defn- nth-or [coll n] (when (seq coll) (nth coll (mod n (count coll)))))

(defn- outstanding
  "A connection's requests not yet answered, by id."
  [w cid]
  (vec (sort (for [[id _] (get-in w [:conns cid :requests]) :when (zero? (get-in w [:conns cid :responses id] 0))] id))))

(defn- text-chunk? [m]
  (and (#{"agent_message_chunk" "agent_thought_chunk" "user_message_chunk"} (get-in m ["params" "update" "sessionUpdate"]))
       (string? (get-in m ["params" "update" "content" "text"]))))

(defn- without-text [m] (update-in m ["params" "update" "content"] dissoc "text"))

(defn- canonical
  "Updates as their history reads, whatever relay merged: the labels a
  replay starts with (relay's, with no title, unlike the agent's) left
  out, the connection's own prompts' text taken out (it is not sent them
  live), and consecutive text chunks of one kind joined."
  [lines own]
  (->> lines
       (map parse)
       (remove #(let [u (get-in % ["params" "update"])]
                  (and (= "session_info_update" (get u "sessionUpdate")) (nil? (get u "title")))))
       (map #(if (= "user_message_chunk" (get-in % ["params" "update" "sessionUpdate"]))
               (update-in % ["params" "update" "content" "text"] (fn [t] (reduce (fn [t o] (str/replace t o "")) (str t) own)))
               %))
       (remove #(and (text-chunk? %) (= "" (get-in % ["params" "update" "content" "text"]))))
       (reduce (fn [acc m]
                 (let [l (peek acc)]
                   (if (and l (text-chunk? l) (text-chunk? m) (= (without-text l) (without-text m)))
                     (conj (pop acc) (update-in l ["params" "update" "content" "text"] str (get-in m ["params" "update" "content" "text"])))
                     (conj acc m))))
               [])))

(defn- end-segment
  "Checks what a connection saw of a session since it attached: with its
  own prompts' text taken out, exactly the observer's history so far,
  with no gap and no duplicate."
  [w cid session]
  (if-let [{:keys [start labels]} (get-in w [:conns cid :segments session])]
    (let [own (get-in w [:own cid] #{})
          seen (subvec (get-in w [:conns cid :updates session] []) start)
          first-labels (some #(get-in (parse %) ["params" "update" "_meta" "agentloop/labels"]) seen)
          seen (canonical seen own)
          history (canonical (get-in w [:conns observer :updates session] []) own)]
      (cond-> (update-in w [:conns cid :segments] dissoc session)
        ;; The labels a late attacher sees first are the latest the agent
        ;; sent, however much of the history the cap dropped.
        (and labels (not= labels first-labels)) (problem [:replay-labels cid session labels first-labels])
        (and (= :service (:mode w)) (nil? (:cap w)) (not= seen history))
        (problem [:replay-gap cid session (count seen) (count history)])))
    w))

(defn- start-segment [w cid session]
  (assoc-in w [:conns cid :segments session] {:start (count (get-in w [:conns cid :updates session] []))
                                              :labels (get-in w [:labels session])}))

(defn- end-segments-of-gone
  "Segments of sessions no longer live end where the conversation did."
  [w]
  (let [live (set (live-sessions w))]
    (reduce (fn [w [cid c]]
              (reduce (fn [w s] (if (live s) w (update-in w [:conns cid :segments] dissoc s)))
                      w (keys (:segments c))))
            w (:conns w))))

(defn- perms-sent [w]
  (into {} (for [[role c] (get-in w [:h :convs]) [pkey p] (:perms c)] [[role pkey] p])))

(defn- told-of-settling
  "Each permission request that was settled by an action: every other
  connection it was sent to, still open, was told, once."
  [before after acting]
  (let [now (perms-sent after)]
    (reduce (fn [w [k {:keys [sent call]}]]
              (if (contains? now k)
                w
                (reduce (fn [w cid]
                          (let [was (get-in before [:conns cid :settled call] 0)
                                is (get-in w [:conns cid :settled call] 0)]
                            (if (and (get-in w [:conns cid :open]) (not= cid acting) (not= (inc was) is)
                                     (contains? (get-in w [:h :conns cid :attached]) (first k)))
                              (problem w [:not-told-settled cid call])
                              w)))
                        w (keys sent))))
            after (perms-sent before))))

(declare act*)

(defn act
  "Carries out one generated action, and checks that settling a
  permission request told everyone it was sent to."
  [w [kind a :as action]]
  (let [cids (vec (sort (keep (fn [[cid c]] (when (and (:open c) (not= observer cid)) cid)) (:conns w))))
        acting (when (#{:answer :stray} kind) (nth-or cids (or a 0)))]
    (let [after (act* (dissoc w :winner) action)
          w' (told-of-settling w after (if (= :race kind) (:winner after) acting))
          bound (get-in w' [:h :max-per-conversation])]
      ;; Requests waiting on a conversation, orphans included, stay within
      ;; its bound however clients come and go.
      (reduce (fn [w role]
                (let [n (count (filter #(= role (:role %)) (vals (get-in w [:h :requests]))))]
                  (cond-> w (> n bound) (problem [:over-the-bound role n]))))
              w' roles))))

(defn- act*
  [w [kind a b]]
  (let [cids (vec (sort (keep (fn [[cid c]] (when (and (:open c) (not= observer cid)) cid)) (:conns w))))
        cid (nth-or cids (if (integer? a) a 0))
        w (case kind
            :open (if (and (= :embedded (:mode w)) (seq (:conns w)))
                    w
                    (let [cid (:next-conn w)]
                      (-> (update w :next-conn inc)
                          (open-conn cid {:origin (if (= :embedded (:mode w)) :stdio :socket)
                                          :approvals (boolean a) :role (nth-or roles (or b 0))}))))
            :close (if cid
                     (-> (reduce #(end-segment %1 cid %2) w (attached-sessions w cid))
                         (assoc-in [:conns cid :open] false)
                         (run-events [{:event :connection-closed :conn cid}]))
                     w)
            :initialize (if cid (conn-sends w cid "initialize" {:protocolVersion 1}) w)
            :new (if cid
                   (let [role (nth-or roles b)
                         s (get-in w [:h :convs role :session])
                         w (if s (-> w (end-segment cid s) (start-segment cid s)) w)
                         w (conn-sends w cid "session/new" {:cwd "/" :_meta {"relay/role" role}} :role role)
                         s' (get-in w [:h :convs role :session])]
                     (if (and s' (not s) (contains? (get-in w [:h :conns cid :attached]) role))
                       (start-segment w cid s')
                       w))
                   w)
            :load (if cid
                    (let [s (or (nth-or (live-sessions w) b) "nope")
                          w (if (some #{s} (attached-sessions w cid)) (end-segment w cid s) w)
                          w (start-segment w cid s)]
                      (conn-sends w cid "session/load" {:sessionId s}))
                    w)
            :prompt (if-let [s (and cid (nth-or (attached-sessions w cid) b))]
                      (let [text (str "<p" (:next-text w) ">")]
                        (-> w (update :next-text inc) (update-in [:own cid] (fnil conj #{}) text)
                            (conn-sends cid "session/prompt" {:sessionId s :prompt [{:type "text" :text text}]})))
                      w)
            :cancel (if-let [s (and cid (nth-or (attached-sessions w cid) b))]
                      (conn-notes w cid "session/cancel" {:sessionId s})
                      w)
            :detach (if-let [s (and cid (nth-or (attached-sessions w cid) b))]
                      (-> w (end-segment cid s) (conn-sends cid "session/close" {:sessionId s}))
                      w)
            :roles (if cid (conn-sends w cid "_relay/roles" {}) w)
            :end (if-let [s (and cid (nth-or (attached-sessions w cid) b))]
                   (conn-sends w cid "_relay/conversations/end" {:sessionId s})
                   w)
            :items (if-let [s (and cid (nth-or (attached-sessions w cid) b))]
                     (conn-sends w cid "_relay/items" {:sessionId s})
                     w)
            :answer (if-let [[k call] (and cid (nth-or (vec (sort-by str (get-in w [:conns cid :perm-ids]))) b))]
                      (let [id (second k)
                            m (case (mod (or b 0) 4)
                                0 {:jsonrpc "2.0" :id id :result {:outcome {:outcome "selected" :optionId "a"}}}
                                1 {:jsonrpc "2.0" :id id :result {:outcome {:outcome "selected" :optionId "zzz"}}}
                                2 {:jsonrpc "2.0" :id id :result {:outcome {:outcome "cancelled"}}}
                                3 nil)]
                        (run-events w [{:event :connection-line :conn cid
                                        :line (if m (jl m) (str "{\"jsonrpc\":\"2.0\",\"id\":" (json/generate-string id) ",\"result\":"))}]))
                      w)
            ;; Reusing an id whose request is outstanding, and a line that
            ;; cannot be read carrying one: neither may answer for it.
            :reuse (if-let [id (and cid (nth-or (outstanding w cid) b))]
                     (-> w (update-in [:conns cid :reuses id] (fnil inc 0))
                         (run-events [{:event :connection-line :conn cid
                                       :line (jl {:jsonrpc "2.0" :id id :method "slow" :params {}})}]))
                     w)
            :garble (if-let [id (and cid (nth-or (outstanding w cid) b))]
                      (run-events w [{:event :connection-line :conn cid
                                      :line (str "{\"jsonrpc\":\"2.0\",\"id\":" (json/generate-string id) ",\"method\":")}])
                      w)
            :stray (if cid (run-events w [{:event :connection-line :conn cid :line (jl {:jsonrpc "2.0" :id 99999 :result {}})}]) w)
            :update (if-let [{:keys [session]} (agent-of w (nth-or roles a))]
                      (let [text (str "u" (:next-text w))]
                        (-> w (update :next-text inc)
                            (agent-says (nth-or roles a) {:jsonrpc "2.0" :method "session/update"
                                                          :params {:sessionId session
                                                                   :update {:sessionUpdate "agent_message_chunk"
                                                                            :content {:type "text" :text text}}}})
                            (run-events [])))
                      w)
            :labels (if-let [{:keys [session]} (agent-of w (nth-or roles a))]
                      (let [ls (nth-or [[] ["private"] ["contaminated"] ["contaminated" "private"]] b)]
                        (-> w (assoc-in [:labels session] ls)
                            (agent-says (nth-or roles a) {:jsonrpc "2.0" :method "session/update"
                                                          :params {:sessionId session
                                                                   :update {:sessionUpdate "session_info_update"
                                                                            :title (str "t" (:next-text w))
                                                                            :_meta {"agentloop/labels" ls}}}})
                            (update :next-text inc)
                            (run-events [])))
                      w)
            :perm (let [role (nth-or roles a)]
                    (if-let [{:keys [session]} (and (get-in w [:h :convs role :session]) (agent-of w role))]
                      (let [id (+ 5000 (:next-text w))]
                        (-> w (update :next-text inc) (update-in [:agents role :perms] conj id)
                            (agent-says role {:jsonrpc "2.0" :id id :method "session/request_permission"
                                              :params {:sessionId session :toolCall {:toolCallId (str "c" id)}
                                                       :options [{:optionId "a" :kind "allow_once"}
                                                                 {:optionId "r" :kind "reject_once"}]}})
                            (run-events [])))
                      w))
            ;; Two devices answering the same request, one after the other.
            :race (let [role (nth-or roles a)
                        w (act* w [:perm a])
                        call (str "c" (+ 5000 (dec (:next-text w))))
                        holders (for [[cid c] (sort-by key (:conns w))
                                      :when (and (:open c) (not= observer cid))
                                      [k pc] (:perm-ids c) :when (= call pc)]
                                  [cid (second k)])]
                    (reduce (fn [w [cid id]]
                              (run-events w [{:event :connection-line :conn cid
                                              :line (jl {:jsonrpc "2.0" :id id :result {:outcome {:outcome "selected" :optionId "a"}}})}]))
                            (assoc w :winner (ffirst holders)) holders))
            :agent-cancel (let [role (nth-or roles a)]
                            (if-let [id (first (sort (:perms (agent-of w role))))]
                              (-> w (agent-says role {:jsonrpc "2.0" :method "$/cancel_request" :params {:requestId id}})
                                  (run-events []))
                              w))
            :reply (let [role (nth-or roles a)]
                     (if-let [id (first (:prompts (agent-of w role)))]
                       (-> w (update-in [:agents role :prompts] (comp vec rest))
                           (agent-says role {:jsonrpc "2.0" :id id :result {:stopReason "end_turn"}})
                           (run-events []))
                       w))
            :slow (update w :slow (fnil (if b conj disj) #{}) (nth-or roles a))
            :release (let [role (nth-or roles a)]
                       (if-let [d (seq (:deferred (agent-of w role)))]
                         (-> (reduce #(agent-says %1 role %2) (assoc-in w [:agents role :deferred] []) d)
                             (run-events []))
                         w))
            :busy (-> w (update :busy (if b conj disj) (nth-or roles a))
                      (run-events [{:event :log-changed :roster roster}]))
            :die (let [role (nth-or roles a)]
                   (if (agent-of w role) (run-events w [{:event :conversation-gone :role role}]) w)))]
    (end-segments-of-gone w)))

(def gen-action
  (gen/frequency
   [[3 (gen/tuple (gen/return :open) (gen/frequency [[3 (gen/return true)] [1 (gen/return false)]]) gen/nat)]
    [1 (gen/tuple (gen/return :close) gen/nat)]
    [2 (gen/tuple (gen/return :initialize) gen/nat)]
    [4 (gen/tuple (gen/return :new) gen/nat gen/nat)]
    [2 (gen/tuple (gen/return :load) gen/nat gen/nat)]
    [5 (gen/tuple (gen/return :prompt) gen/nat gen/nat)]
    [1 (gen/tuple (gen/return :cancel) gen/nat gen/nat)]
    [1 (gen/tuple (gen/return :detach) gen/nat gen/nat)]
    [1 (gen/tuple (gen/return :roles) gen/nat)]
    [1 (gen/tuple (gen/return :end) gen/nat gen/nat)]
    [1 (gen/tuple (gen/return :items) gen/nat gen/nat)]
    [6 (gen/tuple (gen/return :answer) gen/nat gen/nat)]
    [1 (gen/tuple (gen/return :stray) gen/nat)]
    [5 (gen/tuple (gen/return :update) gen/nat)]
    [3 (gen/tuple (gen/return :perm) gen/nat)]
    [5 (gen/tuple (gen/return :reply) gen/nat)]
    [3 (gen/tuple (gen/return :busy) gen/nat gen/boolean)]
    [1 (gen/tuple (gen/return :die) gen/nat)]
    [2 (gen/tuple (gen/return :slow) gen/nat gen/boolean)]
    [3 (gen/tuple (gen/return :labels) gen/nat gen/nat)]
    [2 (gen/tuple (gen/return :agent-cancel) gen/nat)]
    [3 (gen/tuple (gen/return :race) gen/nat)]
    [2 (gen/tuple (gen/return :reuse) gen/nat gen/nat)]
    [1 (gen/tuple (gen/return :garble) gen/nat gen/nat)]
    [3 (gen/tuple (gen/return :release) gen/nat)]]))

(defn start [mode & [cap max-per-conversation]]
  (cond-> (world mode cap max-per-conversation)
    (= :service mode) (open-conn observer {:origin :socket :approvals false})))

(defn drain
  "Lets everything finish: the roles are free, each agent answers its
  prompts, and then every conversation ends."
  [w]
  (let [w (-> (assoc w :busy #{} :slow #{}) (run-events [{:event :log-changed :roster roster}]))
        w (loop [w w n 0]
            (let [held (for [r roles :when (seq (:deferred (agent-of w r)))] r)]
              (if (or (empty? held) (> n 20))
                w
                (recur (reduce #(act* %1 [:release (.indexOf ^java.util.List roles %2)]) w held) (inc n)))))
        w (loop [w w n 0]
            (let [waiting (for [r roles :when (seq (:prompts (agent-of w r)))] r)]
              (if (or (empty? waiting) (> n 200))
                w
                (recur (reduce #(act* %1 [:reply (.indexOf ^java.util.List roles %2)]) w waiting) (inc n)))))
        ;; No prompt waits once its role is free.
        w (reduce (fn [w [role c]]
                    (cond-> w
                      (or (seq (hub/queued (:h w) role)) (hub/running (:h w) role))
                      (problem [:prompt-still-waiting role])))
                  w (get-in w [:h :convs]))
        w (reduce (fn [w [cid c]] (reduce #(end-segment %1 cid %2) w (attached-sessions w cid))) w (:conns w))]
    (reduce (fn [w role] (run-events w [{:event :conversation-gone :role role}]))
            w (sort (keys (get-in w [:h :convs]))))))

(defn final-problems
  "What is wrong once everything has finished: an open connection's
  request left unanswered, or a permission request settled without the
  others being told."
  [w]
  (concat
   (:problems w)
   (for [[cid c] (:conns w) [id n] (:reuses c) :when (pos? n)] [:reuse-not-refused cid id])
   (for [[cid c] (:conns w)
         :when (:open c)
         [id _] (:requests c)
         :when (not= 1 (get-in c [:responses id] 0))]
     [:unanswered cid id])))
