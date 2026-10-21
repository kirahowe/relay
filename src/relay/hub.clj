(ns relay.hub
  "The hub: what sits between relay's ACP clients and its conversations,
  one per role. Pure: (step hub event) gives [hub effects], and a shell
  (relay.serve) carries the effects out and turns what happens into
  events. No I/O, no threads and no clock: time comes in on events.

  A conversation is a role's chat wake running its agent behind relay's
  proxy (relay.dispatch/proxy!), and the hub is that proxy's client side.
  Clients are connections. One connection may attach to several
  conversations and one conversation may have several connections
  attached, so the hub keeps the routing table: which connection is
  attached to which conversation, the JSON-RPC ids it gave each request
  in both directions, the permission requests waiting for an answer and
  who may give it, the prompts waiting for their turn, each
  conversation's replay buffer, and who wants the roster.

  Events, maps with :event:
    :connection-opened  {:conn :origin (:socket | :stdio) :approvals :role}
    :connection-line    {:conn :line}       a line the client sent
    :connection-closed  {:conn}
    :conversation-started {:role :wake}     the role's chat wake runs
    :conversation-failed  {:role :message}  it could not start
    :agent-line         {:role :line}       a line from the proxy
    :conversation-gone  {:role}             the wake has ended
    :submit-result      {:tag :ok | :rejection :message}
    :log-changed        {:roster}           relay.roster/roster, now

  Effects, maps with :effect:
    :write   {:conn :line}         a line to a connection
    :send    {:role :line}         a line to a conversation's proxy
    :submit  {:tag :command}       a command for relay.inbound; its result
                                   comes back as :submit-result with the
                                   tag (nil when nobody waits for it)
    :start   {:role}               open the role's conversation
    :stop    {:role}               end it
  Asking relay to tell the operator something is a :submit of a command
  whose events notify, so a notification is decided in relay.core like
  every other.

  Modes, an option, never sniffed: :service keeps a conversation when its
  last client goes and holds a permission request while no approving
  client is attached, asking relay to notify the operator; :embedded
  (relay acp with no service, one connection on stdio) ends every
  conversation when its connection goes and refuses a permission request
  nobody can answer."
  (:require [clojure.string :as str]
            [relay.acp :as acp]
            [relay.core :as core]
            [relay.json :as strict]
            [relay.roster :as roster]
            [relay.workspace :as workspace]))

(def default-replay-cap
  "How many characters of updates a conversation keeps to replay to a
  client that attaches. Past it the oldest go."
  (* 4 1024 1024))

(defn hub
  "An empty hub. Options: :mode, :service or :embedded; :replay-cap."
  [{:keys [mode replay-cap] :or {replay-cap default-replay-cap}}]
  (when-not (#{:service :embedded} mode)
    (throw (ex-info "A hub's mode is :service or :embedded." {:mode mode})))
  {:mode mode :replay-cap replay-cap :conns {} :convs {} :roster [] :tags {} :next-tag 0})

;; --- effects and lines

(defn- fx [h effect] (update h ::fx conj effect))
(defn- write [h conn line] (fx h {:effect :write :conn conn :line line}))
(defn- send-conv [h role line] (fx h {:effect :send :role role :line line}))

(defn- answer [h conn id result] (write h conn (workspace/result-line id result)))
(defn- refuse [h conn id code message] (write h conn (workspace/error-line id code message)))

(defn- submit
  "A command for relay, with what to do with its result, if anything."
  [h command & [waiting]]
  (if waiting
    (let [tag (:next-tag h)]
      (-> h (assoc-in [:tags tag] waiting) (update :next-tag inc)
          (fx {:effect :submit :tag tag :command command})))
    (fx h {:effect :submit :tag nil :command command})))

(defn- with-id [msg id] (strict/generate (assoc msg "id" id)))

(defn- params-of [msg] (let [p (get msg "params")] (if (map? p) p {})))

(defn- session-of [msg] (let [s (get (params-of msg) "sessionId")] (when (string? s) s)))

;; --- lookups

(defn conversation-of
  "The role whose live conversation has this session, or nil."
  [h session]
  (when (string? session)
    (some (fn [[role c]] (when (= session (:session c)) role)) (:convs h))))

(defn attached
  "The connections attached to a role's conversation, in order."
  [h role]
  (sort (keep (fn [[cid c]] (when (contains? (:attached c) role) cid)) (:conns h))))

(defn- approving [h role] (filter #(get-in h [:conns % :approvals]) (attached h role)))

(defn- active-role? [h role] (some #(= role (:id %)) (:roster h)))

;; --- the roster

(defn roles-wire
  "The roster as _relay/roles sends it, with what only the hub knows laid
  over it: a conversation's session before its first turn, and a turn
  that waits for its role as queued."
  [h]
  (vec (for [e (:roster h)
             :let [w (roster/wire-role e)
                   c (get-in h [:convs (:id e)])]]
         (if (and c (get w "conversation"))
           (update w "conversation"
                   (fn [wc]
                     (cond-> wc
                       (and (nil? (get wc "sessionId")) (:session c)) (assoc "sessionId" (:session c))
                       (and (= "idle" (get wc "turn")) (seq (:queue c))) (assoc "turn" "queued"))))
           w))))

(defn- roster-changes
  "Sends each connection that asked for the roster the whole list when
  any part of it changed since it was last sent."
  [h]
  (let [now (roles-wire h)]
    (reduce (fn [h [cid c]]
              (if (and (:roles c) (not= now (:roles c)))
                (-> h (assoc-in [:conns cid :roles] now)
                    (write cid (workspace/notification-line "_relay/roles/changed" {"roles" now})))
                h))
            h (sort-by key (:conns h)))))

;; --- the replay buffer

(def truncated-notice
  "The text a replay starts with when its oldest updates were dropped."
  "Earlier history of this conversation is not shown.")

(defn- remember
  "Adds a session/update line to a conversation's replay buffer, dropping
  the oldest past the cap. The buffer is a queue, so what it drops is
  gone: a subvec would keep its whole backing vector."
  [h role line]
  (let [cap (:replay-cap h)]
    (update-in h [:convs role :replay]
               (fn [{:keys [entries size dropped] :or {entries clojure.lang.PersistentQueue/EMPTY size 0 dropped false}}]
                 (loop [entries (conj entries line) size (+ size (count line)) dropped dropped]
                   (if (and (> size cap) (seq entries))
                     (recur (pop entries) (- size (count (peek entries))) true)
                     {:entries entries :size size :dropped dropped}))))))

(defn- notice-line [session]
  (workspace/notification-line
   "session/update"
   {"sessionId" session
    "update" {"sessionUpdate" "agent_message_chunk"
              "content" {"type" "text" "text" truncated-notice}
              "_meta" {"relay/notice" "replay-truncated"}}}))

(defn- replay
  "Writes a conversation's replay to a connection: the notice when its
  oldest updates were dropped, every update kept, and _relay/replayed."
  [h cid role]
  (let [{:keys [session replay]} (get-in h [:convs role])
        h (if (:dropped replay) (write h cid (notice-line session)) h)
        h (reduce #(write %1 cid %2) h (:entries replay))]
    (write h cid (workspace/notification-line "_relay/replayed" {"sessionId" session}))))

;; --- permission requests

(defn- perm-line
  "A permission request as one connection is sent it, under its own id."
  [{:keys [msg]} hub-id]
  (with-id msg hub-id))

(defn- send-perm
  "Sends an open permission request to a connection."
  [h role pkey cid]
  (let [perm (get-in h [:convs role :perms pkey])]
    (if (or (nil? perm) (contains? (:sent perm) cid))
      h
      (let [n (inc (get-in h [:conns cid :next-id] 0))
            id (str "relay-" n)]
        (-> h
            (assoc-in [:conns cid :next-id] n)
            (assoc-in [:conns cid :out (acp/id-key id)] {:role role :perm pkey})
            (assoc-in [:convs role :perms pkey :sent cid] id)
            (write cid (perm-line perm id)))))))

(defn- refusal-line [{:keys [msg]}]
  (strict/generate (acp/refusal {:id (get msg "id")
                                 :options (vec (filter map? (let [o (get-in msg ["params" "options"])]
                                                              (when (sequential? o) o))))})))

(defn- unreadable-answer
  "A line relay's proxy cannot read that carries the agent's id for a
  permission request: the proxy refuses the request for it, as it does
  when a client's answer cannot be read."
  [{:keys [msg]}]
  (str "{\"jsonrpc\":\"2.0\",\"id\":" (strict/generate (get msg "id")) ",\"result\":"))

(defn- unattended
  "A permission request no approving connection can see: in service mode
  it waits, and relay is asked once to tell the operator; in embedded
  mode it is refused."
  [h role pkey]
  (let [perm (get-in h [:convs role :perms pkey])]
    (cond
      (nil? perm) h
      (= :embedded (:mode h))
      (-> h (update-in [:convs role :perms] dissoc pkey) (send-conv role (refusal-line perm)))
      (:notified perm) h
      :else
      (-> h (assoc-in [:convs role :perms pkey :notified] true)
          (submit {:type :permission/unattended :actor core/dispatcher
                   :args {:wake (get-in h [:convs role :wake]) :call (:call perm)}})))))

(defn- settle
  "Settles a permission request with a connection's answer: `line` goes
  to the proxy, which checks it against the options the agent offered,
  and every other connection it was sent to is told."
  [h role pkey winner line outcome]
  (let [{:keys [sent call]} (get-in h [:convs role :perms pkey])
        session (get-in h [:convs role :session])]
    (as-> h h
      (update-in h [:convs role :perms] dissoc pkey)
      (reduce (fn [h [cid n]]
                (cond-> (update-in h [:conns cid :out] dissoc (acp/id-key n))
                  ;; A late answer from the others is dropped quietly.
                  (not= cid winner) (-> (write cid (workspace/notification-line
                                                    "_relay/permission/settled"
                                                    {"sessionId" session "toolCallId" call
                                                     "outcome" outcome})))))
              h (sort-by key sent))
      (if line (send-conv h role line) h))))

(defn- drop-from-perms
  "A connection no longer answers the requests it was sent. One left with
  no approving connection is unattended."
  [h cid roles]
  (reduce (fn [h role]
            (reduce (fn [h [pkey perm]]
                      (if-let [n (get-in perm [:sent cid])]
                        (let [h (-> h (update-in [:convs role :perms pkey :sent] dissoc cid)
                                    (update-in [:conns cid :out] dissoc (acp/id-key n)))]
                          (if (empty? (get-in h [:convs role :perms pkey :sent]))
                            (unattended h role pkey)
                            h))
                        h))
                    h (sort-by key (get-in h [:convs role :perms]))))
          h roles))

;; --- conversations

(defn- conv-request
  "Sends a request of the hub's own, or one it passes on for a client, to
  a conversation under an id the hub gives it, and remembers whose it
  is."
  [h role msg origin]
  (let [n (inc (get-in h [:convs role :next-id] 0))]
    (-> h
        (assoc-in [:convs role :next-id] n)
        (assoc-in [:convs role :out (acp/id-key n)] origin)
        (send-conv role (with-id msg n)))))

(defn- attach
  "Attaches a connection to a role's conversation and sends it the
  permission requests still open, when it may answer them."
  [h cid role]
  (let [h (update-in h [:conns cid :attached] (fnil conj #{}) role)]
    (if (get-in h [:conns cid :approvals])
      (reduce #(send-perm %1 role %2 cid) h (sort (keys (get-in h [:convs role :perms]))))
      h)))

(defn- create-session
  "Asks the agent for the conversation's session, once it is initialized
  and a connection waits for one."
  [h role]
  (let [{:keys [status init creating waiting session]} (get-in h [:convs role])
        first-waiter (first waiting)]
    (cond
      (or (not= :running status) session creating (nil? first-waiter)) h
      (= :none init)
      (let [cinit (get-in h [:conns (:conn first-waiter) :init])]
        (-> h (assoc-in [:convs role :init] :sent)
            (conv-request role {"jsonrpc" "2.0" "method" "initialize"
                                "params" (or cinit {"protocolVersion" 1 "clientCapabilities" {}})}
                          {:kind :init})))
      ;; The connection asking is taken off the waiting list: its answer
      ;; is owed under the request's id alone, so nothing else answers it.
      (= :done init)
      (-> h (assoc-in [:convs role :creating] true)
          (update-in [:convs role :waiting] (comp vec rest))
          (conv-request role {"jsonrpc" "2.0" "method" "session/new"
                              "params" (let [p (:params first-waiter)
                                             m (let [m (get p "_meta")] (when (map? m) (not-empty (dissoc m "relay/role"))))]
                                         (if m (assoc p "_meta" m) (dissoc p "_meta")))}
                        {:kind :new :conn (:conn first-waiter) :id (:id first-waiter) :params (:params first-waiter)}))
      :else h)))

(defn- answer-waiters
  "Answers the connections waiting for the conversation's session: each
  attaches, and gets the answer and then the (empty) replay."
  [h role]
  (let [session (get-in h [:convs role :session])
        waiting (get-in h [:convs role :waiting])]
    (reduce (fn [h {:keys [conn id]}]
              (if (get-in h [:conns conn])
                (-> h (answer conn id {"sessionId" session "_meta" {"relay/attached" true}})
                    (replay conn role)
                    (attach conn role))
                h))
            (assoc-in h [:convs role :waiting] [])
            waiting)))

(defn- fail-waiters [h role code message]
  (reduce (fn [h {:keys [conn id]}] (if (get-in h [:conns conn]) (refuse h conn id code message) h))
          (assoc-in h [:convs role :waiting] [])
          (get-in h [:convs role :waiting])))

(defn- pump
  "Starts the conversation's next queued prompt when nothing stops it: a
  turn is asked for, and the prompt goes on once relay accepts it."
  [h role]
  (let [{:keys [status session prompt asking blocked queue wake]} (get-in h [:convs role])]
    (if (and (= :running status) session (nil? prompt) (not asking) (not blocked) (seq queue))
      (-> h (assoc-in [:convs role :asking] true)
          (submit (workspace/turn-start-command wake session) {:kind :turn-start :role role}))
      h)))

(defn- mirror
  "The prompt's text as user_message_chunk updates, which every other
  attached connection gets, and the replay keeps."
  [h role sender params]
  (let [session (get-in h [:convs role :session])
        blocks (let [p (get params "prompt")] (if (sequential? p) (filter map? p) []))
        lines (for [b blocks]
                (workspace/notification-line "session/update"
                                             {"sessionId" session
                                              "update" {"sessionUpdate" "user_message_chunk" "content" b}}))
        others (remove #{sender} (attached h role))]
    (reduce (fn [h line] (reduce #(write %1 %2 line) (remember h role line) others)) h lines)))

(defn- start-prompt [h role]
  (let [{:keys [conn id params]} (first (get-in h [:convs role :queue]))
        h (-> h (update-in [:convs role :queue] (comp vec rest))
              (mirror role conn params))
        n (inc (get-in h [:convs role :next-id] 0))]
    (-> h (assoc-in [:convs role :prompt] {:conn conn :id id :hub-id n})
        (conv-request role {"jsonrpc" "2.0" "method" "session/prompt" "params" params}
                      {:kind :prompt :conn conn :id id}))))

(defn- end-conversation
  "Forgets a conversation that has ended, answering everything owed: a
  queued prompt is cancelled, a request the agent never answered fails,
  every connection holding one of its permission requests is told it is
  settled, and whoever asked for the end is answered."
  [h role]
  (let [{:keys [queue out perms enders] :as c} (get-in h [:convs role])
        gone "The conversation ended."]
    (if-not c
      h
      (as-> h h
        (fail-waiters h role -32000 gone)
        (reduce (fn [h {:keys [conn id]}]
                  (if (get-in h [:conns conn]) (answer h conn id {"stopReason" "cancelled"}) h))
                h queue)
        (reduce (fn [h [_ {:keys [kind conn id]}]]
                  (if (and conn (#{:prompt :forward :new} kind) (get-in h [:conns conn]))
                    (refuse h conn id -32000 gone)
                    h))
                h (sort-by (comp str key) out))
        (reduce (fn [h [pkey _]] (settle h role pkey nil nil "cancelled")) h (sort-by key perms))
        (reduce (fn [h {:keys [conn id]}] (if (get-in h [:conns conn]) (answer h conn id {}) h)) h enders)
        (reduce (fn [h cid] (-> h (update-in [:conns cid :attached] disj role)
                                (update-in [:conns cid :cards] (fnil disj #{}) role)))
                h (attached h role))
        (update h :convs dissoc role)))))

(defn- stop [h role]
  (if (= :ending (get-in h [:convs role :status]))
    h
    (-> h (assoc-in [:convs role :status] :ending) (fx {:effect :stop :role role}))))

;; --- connections

(defn- attached-conv
  "The conversation a connection's request is about: the one its
  sessionId names, when the connection is attached to it; with no
  sessionId the only one it is attached to, or else its own role's (the
  role of relay acp <role>), even before it has a session. [role nil] or
  [nil why]."
  [h cid msg]
  (let [s (session-of msg)
        role (conversation-of h s)
        mine (get-in h [:conns cid :attached] #{})
        own (get-in h [:conns cid :role])]
    (cond
      (and s role (contains? mine role)) [role nil]
      s [nil (str "There is no live conversation " s " on this connection.")]
      (= 1 (count mine)) [(first mine) nil]
      (and (empty? mine) own (get-in h [:convs own :wake]) (not= :ending (get-in h [:convs own :status]))) [own nil]
      :else [nil "Name the conversation with sessionId."])))

(defn- new-session [h cid id msg]
  (let [params (params-of msg)
        meta-role (let [m (get params "_meta")] (when (map? m) (get m "relay/role")))
        role (or meta-role (get-in h [:conns cid :role]))
        c (get-in h [:convs role])]
    (cond
      (not (string? role))
      (refuse h cid id -32602 "session/new needs a role: _meta relay/role, or a connection for one role.")
      (not (active-role? h role))
      (refuse h cid id -32602 (str "There is no role called " role "."))
      (and c (:session c) (not= :ending (:status c)))
      (-> h (answer cid id {"sessionId" (:session c) "_meta" {"relay/attached" true}})
          (replay cid role)
          (attach cid role))
      (and c (= :ending (:status c)))
      (refuse h cid id -32000 (str role "'s conversation is ending. Try again once it has."))
      c
      (-> h (update-in [:convs role :waiting] conj {:conn cid :id id :params params})
          (create-session role))
      :else
      (-> h (assoc-in [:convs role] {:role role :status :starting :init :none :waiting [{:conn cid :id id :params params}]
                                     :queue [] :out {} :perms {} :enders [] :next-id 0})
          (fx {:effect :start :role role})))))

(defn- load-session [h cid id msg]
  (let [s (session-of msg)
        role (conversation-of h s)]
    (if (and role (not= :ending (get-in h [:convs role :status])))
      (-> h (replay cid role) (answer cid id {}) (attach cid role))
      (refuse h cid id -32602 (str "There is no live conversation " (pr-str s)
                                   ". relay does not reopen a conversation that has ended.")))))

(defn- list-sessions [h cid id msg]
  (let [cwd (get (params-of msg) "cwd")]
  (answer h cid id
          {"sessions" (vec (for [{:keys [id title conversation]} (:roster h)
                                 :let [c (get-in h [:convs id])]
                                 :when (and (:session c) (not= :ending (:status c))
                                            ;; ACP's filter by working directory.
                                            (or (not (string? cwd)) (= cwd (:cwd c))))]
                             (cond-> {"sessionId" (:session c) "cwd" (or (:cwd c) "/") "title" title
                                      "_meta" {"relay/role" id}}
                               (:last-activity conversation) (assoc "updatedAt" (str (:last-activity conversation))))))})))

(defn- detach
  "Detaches a connection from a conversation. Its queued prompts are
  cancelled; with `cancel?` (session/close, which in ACP cancels the
  session's work), so is a turn it is running, while turns other
  connections started go on."
  [h cid role & [cancel?]]
  (let [mine (filter #(= cid (:conn %)) (get-in h [:convs role :queue]))
        running (get-in h [:convs role :prompt])]
    (as-> h h
      (reduce (fn [h {:keys [id]}] (answer h cid id {"stopReason" "cancelled"})) h mine)
      (if (and cancel? (= cid (:conn running)))
        (send-conv h role (workspace/notification-line "session/cancel" {"sessionId" (get-in h [:convs role :session])}))
        h)
      (update-in h [:convs role :queue] (fn [q] (vec (remove #(= cid (:conn %)) q))))
      (update-in h [:conns cid :attached] disj role)
      (update-in h [:conns cid :cards] (fnil disj #{}) role)
      (drop-from-perms h cid [role]))))

(defn- prompt
  "A prompt waits for its turn, one per session: as ACP has it, a session
  takes one prompt at a time, so another while one runs or waits is
  refused, and nothing piles up."
  [h cid id msg]
  (let [[role why] (attached-conv h cid msg)
        {:keys [prompt queue asking]} (get-in h [:convs role])]
    (cond
      (not role) (refuse h cid id -32602 why)
      (or prompt asking (seq queue))
      (refuse h cid id -32000 "A prompt is already running or waiting in this conversation. Send this once it is done, or cancel it.")
      :else (-> h (update-in [:convs role :queue] conj {:conn cid :id id :params (params-of msg)})
                (pump role)))))

(defn- cancel
  "session/cancel drops the connection's queued prompt (answered
  cancelled) and cancels a running turn, whichever attached connection
  started it: it is one person on several devices, and ctrl-c on one must
  stop what another started."
  [h cid msg]
  (let [[role] (attached-conv h cid msg)]
    (if-not role
      h
      (let [mine (filter #(= cid (:conn %)) (get-in h [:convs role :queue]))]
        (as-> h h
          (reduce (fn [h {:keys [id]}] (answer h cid id {"stopReason" "cancelled"})) h mine)
          (update-in h [:convs role :queue] (fn [q] (vec (remove #(= cid (:conn %)) q))))
          (if (get-in h [:convs role :prompt])
            (send-conv h role (workspace/notification-line "session/cancel" {"sessionId" (get-in h [:convs role :session])}))
            h))))))

(defn- seen [h cid id msg]
  (let [s (session-of msg)
        role (conversation-of h s)
        turn (get (params-of msg) "turn")]
    (if-not role
      (refuse h cid id -32602 (str "There is no live conversation " (pr-str s) "."))
      (submit h {:type :conversation/seen :actor core/operator :args {:wake (get-in h [:convs role :wake]) :turn turn}}
              {:kind :reply :conn cid :id id}))))

(defn- end-request [h cid id msg]
  (let [s (session-of msg)
        role (conversation-of h s)]
    (if-not role
      (refuse h cid id -32602 (str "There is no live conversation " (pr-str s) "."))
      (as-> h h
        (update-in h [:convs role :enders] conj {:conn cid :id id})
        ;; A running turn is cancelled first.
        (if (get-in h [:convs role :prompt])
          (send-conv h role (workspace/notification-line "session/cancel" {"sessionId" s}))
          h)
        (stop h role)))))

(defn- forward
  "Passes a client's request on to the conversation it names."
  [h cid id msg]
  (let [[role why] (attached-conv h cid msg)
        method (get msg "method")]
    (cond
      (not role) (refuse h cid id -32602 why)
      ;; Trust needs a client that may approve, as an answer to a
      ;; permission request does.
      (and (= "_relay/trust" method) (not (get-in h [:conns cid :approvals])))
      (refuse h cid id -32000 (str "This conversation does not take trust from its client. Run relay trust doc "
                                   (get (params-of msg) "path") " in a terminal."))
      :else
      (cond-> (conv-request h role msg {:kind :forward :conn cid :id id :method method})
        (= "_relay/cards" method) (update-in [:conns cid :cards] (fnil conj #{}) role)))))

(defn- connect [h cid id msg first?]
  (let [{:strs [approvals role]} (params-of msg)]
    (if (and first? (= :socket (get-in h [:conns cid :origin])))
      (cond-> (update-in h [:conns cid] assoc :approvals (true? approvals) :role (when (string? role) role))
        (some? id) (answer cid id {}))
      (cond-> h
        (some? id) (refuse cid id -32600 "relay takes _relay/connect only as the first line of a connection to its socket.")))))

(defn- initialize-answer [msg]
  {"protocolVersion" 1
   "agentCapabilities" {"loadSession" true
                        "promptCapabilities" {"embeddedContext" true}
                        "sessionCapabilities" {"list" {} "close" {}}}
   "agentInfo" {"name" "relay" "title" "relay" "version" "1"}
   "authMethods" []
   "_meta" {"relay/hub" 1}})

(defn- client-request [h cid id msg first?]
  (let [method (get msg "method")]
    (case method
      "_relay/connect" (connect h cid id msg first?)
      "initialize" (-> h (assoc-in [:conns cid :init] (params-of msg)) (answer cid id (initialize-answer msg)))
      "authenticate" (answer h cid id {})
      "session/new" (new-session h cid id msg)
      "session/load" (load-session h cid id msg)
      "session/list" (list-sessions h cid id msg)
      "session/close" (let [[role why] (attached-conv h cid msg)]
                        (if role (-> h (detach cid role true) (answer cid id {})) (refuse h cid id -32602 why)))
      "session/prompt" (prompt h cid id msg)
      "_relay/roles" (let [now (roles-wire h)]
                       (-> h (assoc-in [:conns cid :roles] now) (answer cid id {"roles" now})))
      "_relay/seen" (seen h cid id msg)
      "_relay/conversations/end" (end-request h cid id msg)
      (cond
        (workspace/methods method) (forward h cid id msg)
        (str/starts-with? method "_relay/") (refuse h cid id -32601 (str "relay has no method " method "."))
        ;; Anything else goes to the conversation it is about, as a
        ;; plain ACP client would expect of relay acp <role>.
        (first (attached-conv h cid msg)) (forward h cid id msg)
        :else (refuse h cid id -32601 (str "relay has no method " method "."))))))

(defn- client-notification [h cid msg first?]
  (let [method (get msg "method")]
    (cond
      (= "_relay/connect" method) (connect h cid nil msg first?)
      (= "session/cancel" method) (cancel h cid msg)
      (str/starts-with? method "_relay/") h
      :else (let [[role] (attached-conv h cid msg)]
              (if (and role (session-of msg)) (send-conv h role (strict/generate msg)) h)))))

(defn- open-perms-of
  "The permission requests sent to a connection and not yet settled:
  [role pkey hub-id-key]."
  [h cid]
  (for [[k {:keys [role perm]}] (sort-by (comp str key) (get-in h [:conns cid :out]))
        :when perm]
    [role perm k]))

(defn- hub-id?
  "Whether an id is of the kind the hub gives its own requests to a
  connection."
  [id]
  (and (string? id) (str/starts-with? id "relay-")))

(defn- client-response [h cid line msg]
  (let [k (acp/id-key (get msg "id"))
        {:keys [role perm]} (get-in h [:conns cid :out k])]
    (cond
      perm
      (let [p (get-in h [:convs role :perms perm])
            a (when p (acp/answer-of {:id (get-in p [:sent cid]) :options (vec (filter map? (get-in p [:msg "params" "options"])))}
                                     line))]
        (if-not a
          h
          ;; The answer goes to the proxy under the agent's id; the proxy
          ;; refuses one that picks no option the agent offered.
          (settle h role perm cid (with-id msg (get-in p [:msg "id"])) (:outcome a))))
      ;; An answer to a request already settled for another connection,
      ;; or one the connection stopped holding when it detached.
      (hub-id? (get msg "id"))
      h
      :else
      ;; An answer to nothing it was asked: the client is confused, so
      ;; what it holds open is refused rather than left to hang a turn.
      (reduce (fn [h [role pkey]]
                (let [p (get-in h [:convs role :perms pkey])]
                  (settle h role pkey cid (unreadable-answer p) "reject_once")))
              h (open-perms-of h cid)))))

(defn- client-line [h cid line]
  (let [first? (zero? (get-in h [:conns cid :lines] 0))
        h (update-in h [:conns cid :lines] (fnil inc 0))
        msg (acp/message line)]
    (cond
      (nil? msg)
      ;; Not strictly one JSON object: never passed on. An open
      ;; permission request it seems to answer is refused; a request
      ;; whose id can be found is told why.
      (let [lid (acp/line-id line)
            k (when (some? lid) (acp/id-key lid))
            {:keys [role perm]} (when k (get-in h [:conns cid :out k]))]
        (cond
          perm (settle h role perm cid (unreadable-answer (get-in h [:convs role :perms perm])) "reject_once")
          ;; An id of the hub's own is an answer to a request it no
          ;; longer holds open.
          (hub-id? lid) h
          (some? lid) (refuse h cid lid -32600 "relay does not pass on a line that is not one strictly valid JSON object.")
          :else h))
      (string? (get msg "method"))
      (if (contains? msg "id")
        (client-request h cid (get msg "id") msg first?)
        (client-notification h cid msg first?))
      (contains? msg "id")
      (client-response h cid line msg)
      :else h)))

(defn- close-connection [h cid]
  (let [roles (get-in h [:conns cid :attached] #{})
        h (as-> h h
            (reduce (fn [h role] (update-in h [:convs role :queue] (fn [q] (vec (remove #(= cid (:conn %)) q)))))
                    h roles)
            (reduce (fn [h [role c]]
                      (update-in h [:convs role :waiting] (fn [w] (vec (remove #(= cid (:conn %)) w)))))
                    h (:convs h))
            (drop-from-perms h cid (keys (:convs h)))
            (update h :conns dissoc cid))]
    (if (and (= :embedded (:mode h)) (empty? (:conns h)))
      (reduce stop h (sort (keys (:convs h))))
      h)))

;; --- the agent's side

(defn- permission-request [h role msg]
  (let [pkey (acp/id-key (get msg "id"))
        call (get-in msg ["params" "toolCall" "toolCallId"])
        h (assoc-in h [:convs role :perms pkey] {:msg msg :call call :sent {}})
        approvers (approving h role)]
    (if (seq approvers)
      (reduce #(send-perm %1 role pkey %2) h approvers)
      (unattended h role pkey))))

(defn- agent-response [h role line msg]
  (let [k (acp/id-key (get msg "id"))
        {:keys [kind conn id method] :as origin} (get-in h [:convs role :out k])
        h (update-in h [:convs role :out] dissoc k)
        ok? (contains? msg "result")
        live? #(get-in % [:conns conn])]
    (case kind
      nil h
      :init (if ok?
              (-> h (assoc-in [:convs role :init] :done) (create-session role))
              (-> h (assoc-in [:convs role :init] :none)
                  (fail-waiters role -32000 "The agent could not start a session.")))
      :new (let [session (get-in msg ["result" "sessionId"])
                 h (assoc-in h [:convs role :creating] false)]
             (if (and ok? (string? session))
               (let [h (update-in h [:convs role] assoc :session session
                                  :cwd (let [c (get (:params origin) "cwd")] (when (string? c) c)))]
                 (as-> h h
                   (if (live? h) (-> h (write conn (with-id msg id)) (replay conn role) (attach conn role)) h)
                   (answer-waiters h role)
                   (pump h role)))
               (as-> h h
                 (if (live? h) (write h conn (with-id msg id)) h)
                 (create-session h role))))
      :prompt (as-> h h
                ;; Recorded before the client reads the answer, so its next
                ;; prompt finds the turn over.
                (submit h (workspace/turn-end-command (get-in h [:convs role :wake]) (workspace/stop-reason line)))
                (if (live? h) (write h conn (with-id msg id)) h)
                (assoc-in h [:convs role :prompt] nil)
                (pump h role))
      :forward (if-not (live? h)
                 h
                 (write h conn (with-id (if (and (= "_relay/items" method) ok?
                                                 (not (get-in h [:conns conn :approvals])))
                                          ;; Only a client that may approve is offered trust.
                                          (update-in msg ["result" "items"]
                                                     (fn [items] (mapv #(cond-> % (contains? % "canTrust") (assoc "canTrust" false))
                                                                       (filter map? items))))
                                          msg)
                                        id))))))

(defn- agent-line [h role line]
  (let [msg (acp/message line)
        method (get msg "method")]
    (cond
      (or (nil? msg) (not (get-in h [:convs role]))) h
      (and (string? method) (contains? msg "id"))
      (if (= "session/request_permission" method)
        (permission-request h role msg)
        (send-conv h role (workspace/error-line (get msg "id") -32601 (str "relay's clients do not take " method "."))))
      (= "session/update" method)
      (reduce #(write %1 %2 line) (remember h role line) (attached h role))
      ;; To the connections that asked for the board through it.
      (= "_relay/cards/changed" method)
      (reduce #(write %1 %2 line) h (sort (keep (fn [[cid c]] (when (contains? (:cards c) role) cid)) (:conns h))))
      (string? method)
      (reduce #(write %1 %2 line) h (attached h role))
      (contains? msg "id")
      (agent-response h role line msg)
      :else h)))

;; --- relay's answers

(defn- submit-result [h {:keys [tag ok rejection message]}]
  (let [{:keys [kind role conn id] :as waiting} (get-in h [:tags tag])
        h (update h :tags dissoc tag)]
    (case kind
      nil h
      :turn-start
      (let [h (assoc-in h [:convs role :asking] false)]
        (cond
          (not (get-in h [:convs role])) h
          ;; The prompt was cancelled while relay decided: the turn it
          ;; got ends at once.
          (and ok (empty? (get-in h [:convs role :queue])))
          (submit h (workspace/turn-end-command (get-in h [:convs role :wake]) "cancelled"))
          ok (start-prompt h role)
          ;; It waits for the role, and is asked for again when the log
          ;; changes.
          (= :busy rejection) (assoc-in h [:convs role :blocked] true)
          (empty? (get-in h [:convs role :queue])) h
          :else (let [{:keys [conn id]} (first (get-in h [:convs role :queue]))]
                  (-> h (update-in [:convs role :queue] (comp vec rest))
                      (cond-> (get-in h [:conns conn]) (refuse conn id -32000 (or message "relay refused the turn.")))
                      (pump role)))))
      :reply
      (cond
        (not (get-in h [:conns conn])) h
        ok (answer h conn id {})
        :else (refuse h conn id -32000 (or message "relay refused it."))))))

;; --- step

(defn step
  "The hub after one event, and the effects it calls for, in order."
  [h {:keys [event] :as e}]
  (let [h (assoc h ::fx [])
        h (case event
            :connection-opened
            (assoc-in h [:conns (:conn e)] {:origin (:origin e) :approvals (true? (:approvals e))
                                            :role (:role e) :attached #{} :out {} :lines 0})
            :connection-line (if (get-in h [:conns (:conn e)]) (client-line h (:conn e) (:line e)) h)
            :connection-closed (if (get-in h [:conns (:conn e)]) (close-connection h (:conn e)) h)
            :conversation-started
            (let [role (:role e)]
              (-> (if (get-in h [:convs role])
                    h
                    ;; One opened before the hub asked (relay acp <role>).
                    (assoc-in h [:convs role] {:role role :init :none :waiting [] :queue [] :out {} :perms {}
                                               :enders [] :next-id 0}))
                  (update-in [:convs role] assoc :status :running :wake (:wake e))
                  (create-session role)))
            :conversation-failed
            (-> h (fail-waiters (:role e) -32000 (or (:message e) "relay could not open the conversation."))
                (update :convs dissoc (:role e)))
            :agent-line (agent-line h (:role e) (:line e))
            :conversation-gone (end-conversation h (:role e))
            :submit-result (submit-result h e)
            :log-changed
            (reduce (fn [h role] (-> h (assoc-in [:convs role :blocked] false) (pump role)))
                    (assoc h :roster (vec (:roster e)))
                    (sort (keys (:convs h)))))
        h (roster-changes h)]
    [(dissoc h ::fx) (::fx h)]))

(defn idle?
  "Whether an embedded hub is done: no connection and no conversation."
  [h]
  (and (empty? (:conns h)) (empty? (:convs h))))
