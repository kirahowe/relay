(ns relay.hub
  "The hub: what sits between relay's ACP clients and its conversations,
  one per role. Pure: (step hub event) gives [hub effects], and a shell
  (relay.serve) carries the effects out and turns what happens into
  events. No I/O, no threads and no clock: time comes in on events.

  A conversation is a role's chat wake running its agent behind relay's
  proxy (relay.dispatch/proxy!), and the hub is that proxy's client side.
  Clients are connections. One connection may attach to several
  conversations and one conversation may have several connections
  attached.

  Two tables hold everything that is owed:

    :requests  every client request the hub has taken and not yet
               answered, one record each, from the moment it is taken to
               the moment it is answered: {:conn :id :method :state :role
               :up :kind ...}. Its state says where it is: :local (being
               answered now, or by relay's decision, which is the next
               event), :waiting (for a session to open), :queued (a
               prompt waiting for its turn), :upstream (passed on to the
               conversation under :up, the hub's id for it) or :ending
               (waiting for the conversation to end). While a record lives its id
               stays the connection's; a record whose connection went
               away stays, an orphan, until its answer comes and is
               dropped. Every lookup (a prompt's turn, who waits for a
               session, which answer is whose) is a query on this table.
               The hub's own request to an agent (initialize) is a record
               with no connection.
    :awaiting  what to do with the result of each submit made and not yet
               answered, in order. A submit's result is the next event the
               hub sees (drive carries effects out so), so nothing else
               happens in between: a prompt cannot be cancelled while
               relay decides its turn.

  The board is the hub's too: it knows the state, which connections
  subscribe, and the rev of every card it sent each one, and on every
  change sends each subscriber what it lacks.

  Events, maps with :event:
    :connection-opened  {:conn :origin (:socket | :stdio | :websocket)
                         :role :default-role}
                                            :role is the connection's own
                                            (relay acp <role>); :default-role
                                            only names the role a session/new
                                            without one goes to
    :connection-line    {:conn :line}       a line the client sent
    :connection-closed  {:conn}
    :conversation-started {:role :wake}     the role's chat wake runs
    :conversation-failed  {:role :message}  it could not start
    :agent-line         {:role :line}       a line from the proxy
    :conversation-gone  {:role}             the wake has ended
    :submit-result      {:ok :changed :state | :rejection :message}
                                            the result of the oldest submit
                                            not yet answered
    :log-changed        {:roster :state}    relay.roster/roster and the
                                            core state, now
    :tick               {}                  time passes: retry what failed

  Effects, maps with :effect:
    :write   {:conn :line}         a line to a connection
    :send    {:role :line}         a line to a conversation's proxy
    :submit  {:command :with-state}
                                   a command for relay.inbound; its result
                                   is the next :submit-result, with the
                                   state after it when :with-state
    :start   {:role}               open the role's conversation
    :stop    {:role}               end it
    :warn    {:text}               tell the operator on stderr
  Asking relay to tell the operator something is a :submit of a command
  whose events make a message, so a message is decided in relay.core like
  every other.

  Modes, an option, never sniffed: :service keeps a conversation when its
  last client goes; :embedded (relay acp with no service, one connection
  on stdio) ends every conversation when its connection goes.

  No client is ever asked to approve anything: an agent's
  session/request_permission is refused like any other request a client
  does not take."
  (:require [clojure.string :as str]
            [relay.acp :as acp]
            [relay.core :as core]
            [relay.json :as strict]
            [relay.ready :as ready]
            [relay.roster :as roster]
            [relay.text :as text]
            [relay.workspace :as workspace]))

(def default-replay-cap
  "How many characters of updates a conversation keeps to replay to a
  client that attaches. Past it the oldest go."
  (* 4 1024 1024))

(def default-max-pending
  "How many requests one connection may have waiting for an answer."
  256)

(def default-max-per-conversation
  "How many requests, orphans included, may wait on one conversation.
  Clients that come and go cannot get past it, so a conversation whose
  agent stops answering holds a bounded amount."
  256)

(defn hub
  "An empty hub. Options: :mode, :service or :embedded; :replay-cap;
  :max-pending; :max-per-conversation."
  [{:keys [mode replay-cap max-pending max-per-conversation]
    :or {replay-cap default-replay-cap max-pending default-max-pending
         max-per-conversation default-max-per-conversation}}]
  (when-not (#{:service :embedded} mode)
    (throw (ex-info "A hub's mode is :service or :embedded." {:mode mode})))
  {:mode mode :replay-cap replay-cap :max-pending max-pending :max-per-conversation max-per-conversation
   :conns {} :convs {} :requests {} :next-rid 0 :roster [] :state nil :awaiting clojure.lang.PersistentQueue/EMPTY})

;; --- effects and lines

(defn- fx [h effect] (update h ::fx conj effect))
(defn- write [h conn line] (fx h {:effect :write :conn conn :line line}))
(defn- send-conv [h role line] (fx h {:effect :send :role role :line line}))

(defn- submit
  "A command for relay, and what to do with its result."
  [h command continuation & {:keys [with-state]}]
  (-> h (update :awaiting conj continuation)
      (fx (cond-> {:effect :submit :command command} with-state (assoc :with-state true)))))

(defn- awaiting?
  "Whether a submit made and not yet answered is one pred picks."
  [h pred]
  (some pred (:awaiting h)))

(defn- with-id [msg id] (strict/generate (assoc msg "id" id)))

(defn- params-of [msg] (let [p (get msg "params")] (if (map? p) p {})))

(defn- session-of [msg] (let [s (get (params-of msg) "sessionId")] (when (string? s) s)))

;; --- the request table

(defn- records
  "The request records that satisfy pred, oldest first, as [rid record]."
  [h pred]
  (sort-by key (filter #(pred (val %)) (:requests h))))

(defn- record [h rid] (get-in h [:requests rid]))

(defn- update-record [h rid & kvs] (apply update-in h [:requests rid] assoc kvs))

(defn queued
  "A conversation's prompts waiting for their turn, oldest first."
  [h role]
  (records h #(and (= role (:role %)) (= :prompt (:kind %)) (= :queued (:state %)))))

(defn running
  "The prompt whose turn runs in a conversation: [rid record], or nil."
  [h role]
  (first (records h #(and (= role (:role %)) (= :prompt (:kind %)) (= :upstream (:state %))))))

(defn- reserved?
  "Whether a connection has a request outstanding under this id key."
  [h cid k]
  (some #(and (= cid (:conn %)) (= k (:key %))) (vals (:requests h))))

(defn- reply
  "Answers a request with a line and forgets it: its id is the
  connection's to use again. An orphan's answer goes nowhere. Every
  answer to a client's request goes through here."
  [h rid line]
  (let [{:keys [conn orphan]} (record h rid)]
    (cond-> (update h :requests dissoc rid)
      (and conn (not orphan)) (write conn line))))

(defn- answer [h rid result] (reply h rid (workspace/result-line (:id (record h rid)) result)))
(defn- refuse [h rid code message] (reply h rid (workspace/error-line (:id (record h rid)) code message)))

(defn- full?
  "Whether a conversation has as many requests waiting as it takes."
  [h role]
  (>= (count (records h #(= role (:role %)))) (:max-per-conversation h)))

(defn- send-upstream
  "Passes a request on to a conversation under an id the hub gives it."
  [h rid role msg kind]
  (let [n (inc (get-in h [:convs role :next-id] 0))]
    (-> h (assoc-in [:convs role :next-id] n)
        (update-record rid :state :upstream :role role :up n :kind kind)
        (send-conv role (with-id msg n)))))

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
                       ;; Only while it waits for the role: a prompt relay is
                       ;; about to start is not shown waiting.
                       (and (= "idle" (get wc "turn")) (:blocked c) (seq (queued h (:id e)))) (assoc "turn" "queued"))))
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

;; --- the board

(defn- board-changes
  "Sends each connection subscribed to the board the cards whose rev is
  not the one it was last sent, from the state, and notes them sent. The
  hub is the one place that knows what each client has."
  [h]
  (let [state (:state h)]
    (reduce (fn [h [cid c]]
              (if-let [params (when (and state (:board c)) (workspace/cards-changed state (:board c)))]
                (-> h (update-in [:conns cid :board] workspace/subscribed state)
                    (write cid (workspace/notification-line "_relay/cards/changed" params)))
                h))
            h (sort-by key (:conns h)))))

(defn- messages-changes
  "Sends each connection subscribed to messages those made or changed
  since it was last sent them."
  [h]
  (let [state (:state h)]
    (reduce (fn [h [cid c]]
              (if-let [params (when (and state (:messages c)) (workspace/messages-changed state (:messages c)))]
                (-> h (assoc-in [:conns cid :messages] (workspace/log-rev state))
                    (write cid (workspace/notification-line "_relay/messages/changed" params)))
                h))
            h (sort-by key (:conns h)))))

(defn- messages-request [h rid msg]
  (let [cid (:conn (record h rid))
        state (:state h)
        why (workspace/messages-params-error (params-of msg))]
    (cond
      why (refuse h rid -32602 why)
      (nil? state) (refuse h rid -32000 "relay has not read its messages yet. Try again.")
      :else (-> h (assoc-in [:conns cid :messages] (workspace/log-rev state))
                (answer rid (workspace/messages-answer state (params-of msg)))))))

(def ^:private ready-limit 60)

(defn- ready-changes
  "Sends each connection subscribed to the ready view what differs from
  what it was last sent."
  [h]
  (let [state (:state h)]
    (reduce (fn [h [cid c]]
              (if-let [params (when (and state (:ready c)) (workspace/ready-changed state (:ready c) (or (:ready-limit c) ready-limit)))]
                (-> h (update-in [:conns cid :ready] #(ready/apply-changed % params))
                    (write cid (workspace/notification-line "_relay/ready/changed" params)))
                h))
            h (sort-by key (:conns h)))))

(defn- ready-request [h rid msg]
  (let [cid (:conn (record h rid))
        state (:state h)
        why (workspace/ready-params-error (params-of msg))]
    (cond
      why (refuse h rid -32602 why)
      (nil? state) (refuse h rid -32000 "relay has not read what is ready yet. Try again.")
      :else (let [answer* (workspace/ready-answer state (params-of msg))]
              (-> h (assoc-in [:conns cid :ready] (ready/by-id (get answer* "items")))
                  (assoc-in [:conns cid :ready-limit] (or (get (params-of msg) "limit") ready-limit))
                  (answer rid answer*))))))

(defn- reply-request [h rid msg]
  (let [{:keys [command error]} (workspace/reply-command (params-of msg))]
    (if error
      (refuse h rid -32602 error)
      (submit h command {:kind :reply :rid rid}))))

(defn- cards-request [h rid msg]
  (let [cid (:conn (record h rid))
        state (:state h)]
    (cond
      (workspace/cards-params-error (params-of msg)) (refuse h rid -32602 (workspace/cards-params-error (params-of msg)))
      (nil? state) (refuse h rid -32000 "relay has not read the board yet. Try again.")
      :else (-> h (assoc-in [:conns cid :board] (workspace/subscribed {} state))
                (answer rid (workspace/cards-answer state (params-of msg)))))))

(defn- card-op [h rid msg]
  (let [{:keys [command error]} (workspace/card-op-command (params-of msg))]
    (if error
      (refuse h rid -32602 error)
      ;; What the operator typed on a card is cleaned like any paste, and
      ;; the answer says what changed.
      (let [{:keys [value notes]} (text/sanitize-input (:args command))]
        (submit h (assoc command :args value)
                {:kind :card-op :rid rid :card (get-in command [:args :card]) :notes notes}
                :with-state true)))))

(defn- card-op-result [h {:keys [rid card notes]} {:keys [ok changed rejection message] :as result}]
  (let [{:keys [conn]} (record h rid)
        state (or (:state result) (:state h))]
    (cond
      (not ok) (refuse h rid -32000 (or message (str "relay refused it" (when rejection (str " (" (name rejection) ")")) ".")))
      :else
      (as-> h h
        (assoc h :state state)
        ;; The client gets this card in the answer, so it is not sent it
        ;; again; everyone else is sent whatever they lack.
        (if (get-in h [:conns conn :board])
          (assoc-in h [:conns conn :board :cards card] (:rev (core/card state card)))
          h)
        (answer h rid (cond-> (workspace/card-op-answer state card changed)
                        (seq notes) (assoc "notes" (vec notes))))
        (board-changes h)))))

;; --- the replay buffer

(def ^:private chunk-kinds #{"agent_message_chunk" "agent_thought_chunk" "user_message_chunk"})

(defn- text-chunk
  "A session/update that is a text chunk of one of chunk-kinds: the
  message with its text taken out, and the text. Nil for anything else."
  [msg]
  (let [u (get-in msg ["params" "update"])
        text (get-in u ["content" "text"])]
    (when (and (chunk-kinds (get u "sessionUpdate")) (= "text" (get-in u ["content" "type"])) (string? text))
      [(update-in msg ["params" "update" "content"] dissoc "text") text])))

(def merge-limit
  "The longest text a merged replay entry grows to before a new one
  starts, so the cap still drops history a piece at a time."
  (* 64 1024))

(defn merge-chunk
  "The replay entry two consecutive updates make, when both are text
  chunks of one kind that differ in nothing but their text: one chunk
  with the texts joined. Nil when they do not merge."
  [a b]
  (let [[ka ta] (text-chunk (acp/message a))
        [kb tb] (text-chunk (acp/message b))]
    (when (and ka kb (= ka kb) (<= (+ (count ta) (count tb)) merge-limit))
      (strict/generate (assoc-in ka ["params" "update" "content" "text"] (str ta tb))))))

(defn- remember
  "Adds a session/update line to a conversation's replay buffer, merged
  into the last entry when both are text chunks of one kind, and drops
  the oldest past the cap. The buffer is a queue, so what it drops is
  gone: a subvec would keep its whole backing vector."
  [h role line]
  (let [cap (:replay-cap h)]
    (update-in h [:convs role :replay]
               (fn [{:keys [entries size dropped] :or {entries clojure.lang.PersistentQueue/EMPTY size 0 dropped false}}]
                 (let [last-entry (last entries)
                       merged (when last-entry (merge-chunk last-entry line))
                       [entries size] (if merged
                                        ;; A queue's last entry is replaced by
                                        ;; rebuilding it; the buffer is bounded.
                                        [(into clojure.lang.PersistentQueue/EMPTY (concat (butlast entries) [merged]))
                                         (+ (- size (count last-entry)) (count merged))]
                                        [(conj entries line) (+ size (count line))])]
                   (loop [entries entries size size dropped dropped]
                     (if (and (> size cap) (seq entries))
                       (recur (pop entries) (- size (count (peek entries))) true)
                       {:entries entries :size size :dropped dropped})))))))

;; The session-level state a client needs to read the rest of a replay,
;; kept outside the capped buffer so dropping old updates cannot lose it:
;; the session's latest labels, which tell a client what the text after
;; them may hold (and so which links to defang), and its latest usage.
;; agentloop sends its labels in the _meta of a tool_call,
;; tool_call_update, usage_update or session_info_update; a replay starts
;; with them as the session_info_update agentloop sends when only the
;; labels change.

(def ^:private labels-key "agentloop/labels")

(defn- labels-in
  "The labels an agent's message carries for its session, or nil."
  [msg]
  (let [meta (or (get-in msg ["params" "update" "_meta"]) (get-in msg ["params" "toolCall" "_meta"]))
        ls (when (map? meta) (get meta labels-key))]
    (when (and (sequential? ls) (every? string? ls)) (vec ls))))

(defn- keep-state
  "Notes the session-level state an agent's message carries."
  [h role msg line]
  (let [ls (labels-in msg)]
    (cond-> h
      ls (assoc-in [:convs role :labels] ls)
      (= "usage_update" (get-in msg ["params" "update" "sessionUpdate"]))
      (assoc-in [:convs role :usage] line))))

(defn- labels-line [session labels]
  (workspace/notification-line "session/update"
                               {"sessionId" session
                                "update" {"sessionUpdate" "session_info_update" "_meta" {labels-key labels}}}))

(defn- replay
  "Writes a conversation's replay to a connection: the session's latest
  labels and usage, every update kept, and _relay/replayed, which says
  whether older updates were dropped. relay words nothing about that
  itself: the client does."
  [h cid role]
  (let [{:keys [session replay labels usage]} (get-in h [:convs role])
        h (if labels (write h cid (labels-line session labels)) h)
        h (if usage (write h cid usage) h)
        h (reduce #(write %1 cid %2) h (:entries replay))]
    (write h cid (workspace/notification-line "_relay/replayed" {"sessionId" session
                                                                 "truncated" (boolean (:dropped replay))}))))

;; --- conversations

(defn- attach
  "Attaches a connection to a role's conversation."
  [h cid role]
  (update-in h [:conns cid :attached] (fnil conj #{}) role))

(defn- waiters [h role] (records h #(and (= role (:role %)) (= :waiting (:state %)))))

(defn- creating? [h role]
  (seq (records h #(and (= role (:role %)) (= :upstream (:state %)) (#{:init :new} (:kind %))))))

(defn- create-session
  "Asks the agent for the conversation's session, once it is initialized
  and a connection waits for one: initialize first (as the first waiting
  client asked), then session/new for the first waiting client."
  [h role]
  (let [{:keys [status init session]} (get-in h [:convs role])
        [rid first-waiter] (first (waiters h role))]
    (cond
      (or (not= :running status) session (creating? h role) (nil? rid)) h
      (= :none init)
      (let [own (:next-rid h)
            cinit (get-in h [:conns (:conn first-waiter) :init])]
        (-> h (update :next-rid inc)
            (assoc-in [:requests own] {:conn nil :role role :state :local})
            (send-upstream own role {"jsonrpc" "2.0" "method" "initialize"
                                     "params" (or cinit {"protocolVersion" 1 "clientCapabilities" {}})}
                           :init)))
      :else
      (send-upstream h rid role {"jsonrpc" "2.0" "method" "session/new"
                                 "params" (let [p (:params first-waiter)
                                                m (let [m (get p "_meta")] (when (map? m) (not-empty (dissoc m "relay/role"))))]
                                            (if m (assoc p "_meta" m) (dissoc p "_meta")))}
                     :new))))

(defn- answer-waiters
  "Answers the connections waiting for the conversation's session: each
  attaches, and gets the answer and then the replay."
  [h role]
  (let [session (get-in h [:convs role :session])]
    (reduce (fn [h [rid {:keys [conn]}]]
              (-> h (answer rid {"sessionId" session "_meta" {"relay/attached" true}})
                  (replay conn role)
                  (attach conn role)))
            h (waiters h role))))

(defn- fail-waiters [h role code message]
  (reduce (fn [h [rid _]] (refuse h rid code message)) h (waiters h role)))

(defn- pump
  "Starts the conversation's next queued prompt when nothing stops it: a
  turn is asked for, and the prompt goes on once relay accepts it. Nothing
  starts while a turn's end is still to be recorded."
  [h role]
  (let [{:keys [status session blocked wake turn-end-due]} (get-in h [:convs role])
        [rid r] (first (queued h role))]
    (if (and (= :running status) session (not blocked) (not turn-end-due) (nil? (running h role)) rid
             (not (awaiting? h #(and (= :turn-start (:kind %)) (= role (:role %))))))
      (submit h (workspace/turn-start-command wake session) {:kind :turn-start :rid rid :role role})
      h)))

(defn- notice-line
  "What relay tells the operator about a prompt it cleaned, as an ordinary
  agent_message_chunk any ACP client shows, marked _meta relay/notice."
  [session notes]
  (workspace/notification-line "session/update"
                               {"sessionId" session
                                "update" {"sessionUpdate" "agent_message_chunk"
                                          "content" {"type" "text" "text" (str (str/join "\n" notes) "\n\n")}
                                          "_meta" {"relay/notice" true}}}))

(defn- tell
  "Tells every connection attached to a conversation what sanitizing
  changed in the operator's prompt, once, and keeps it in the replay."
  [h role notes]
  (if (empty? notes)
    h
    (let [line (notice-line (get-in h [:convs role :session]) notes)]
      (reduce #(write %1 %2 line) (remember h role line) (attached h role)))))

(def ^:private block-strings
  "The text fields of a prompt block, and of the resource inside it. Data
  (an image, a blob) and the mime type are not text."
  ["text" "uri" "name" "title" "description"])

(defn sanitize-prompt
  "A prompt's params with every text the operator wrote cleaned
  (relay.text/sanitize-input): the text of text blocks, embedded
  resources and resource links. Returns [params notes], notes being what
  to tell the operator."
  [params]
  (let [blocks (get params "prompt")]
    (if-not (sequential? blocks)
      [params []]
      (let [pick #(when (map? %) (select-keys % block-strings))
            cleaned (mapv (fn [b]
                            (if-not (map? b)
                              {:block b :report text/empty-report}
                              (let [{:keys [value report]}
                                    (text/sanitize-input {:own (pick b) :resource (pick (get b "resource"))})]
                                {:block (cond-> (merge b (:own value))
                                          (map? (get b "resource")) (update "resource" merge (:resource value)))
                                 :report report})))
                          blocks)
            report (apply text/merge-reports text/empty-report (map :report cleaned))]
        [(assoc params "prompt" (mapv :block cleaned)) (text/report-lines report)]))))

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

(defn- end-turn
  "Records the end of a conversation's turn. Until relay has recorded it,
  no other turn starts there."
  [h role stop-reason]
  (-> h (assoc-in [:convs role :turn-end-due] {:stop-reason stop-reason})
      (submit (workspace/turn-end-command (get-in h [:convs role :wake]) stop-reason)
              {:kind :turn-end :role role})))

(defn- retry-turn-ends
  "Asks again to record a turn's end that could not be recorded."
  [h]
  (reduce (fn [h [role {:keys [turn-end-due]}]]
            (if (and turn-end-due (not (awaiting? h #(and (= :turn-end (:kind %)) (= role (:role %))))))
              (submit h (workspace/turn-end-command (get-in h [:convs role :wake]) (:stop-reason turn-end-due))
                      {:kind :turn-end :role role})
              h))
          h (sort-by key (:convs h))))

(defn- cancel-turn
  "Cancels the running turn: session/cancel to the agent."
  [h role]
  (send-conv h role (workspace/notification-line "session/cancel" {"sessionId" (get-in h [:convs role :session])})))

(defn- end-conversation
  "Forgets a conversation that has ended, answering everything owed about
  it: a queued prompt is cancelled, a request it never answered fails,
  and whoever asked for the end is answered."
  [h role]
  (let [c (get-in h [:convs role])
        gone "The conversation ended."]
    (if-not c
      h
      (as-> h h
        (reduce (fn [h [rid {:keys [kind state]}]]
                  (cond
                    (= :ending state) (answer h rid {})
                    (and (= :prompt kind) (= :queued state)) (answer h rid {"stopReason" "cancelled"})
                    :else (refuse h rid -32000 gone)))
                h (records h #(= role (:role %))))
        (reduce (fn [h cid] (update-in h [:conns cid :attached] disj role)) h (attached h role))
        (update h :convs dissoc role)))))

(defn- stop [h role]
  (if (= :ending (get-in h [:convs role :status]))
    h
    (-> h (assoc-in [:convs role :status] :ending) (fx {:effect :stop :role role}))))

;; --- what a client asks

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

(def ^:private full-message
  "This conversation has as many requests waiting as it takes. Wait for some to be answered.")

(defn- new-session [h rid msg]
  (let [cid (:conn (record h rid))
        params (params-of msg)
        meta-role (let [m (get params "_meta")] (when (map? m) (get m "relay/role")))
        role (or meta-role (get-in h [:conns cid :role]) (get-in h [:conns cid :default-role]))
        c (get-in h [:convs role])]
    (cond
      (not (string? role))
      (refuse h rid -32602 "session/new needs a role: _meta relay/role, or a connection for one role.")
      (not (active-role? h role))
      (refuse h rid -32602 (str "There is no role called " role "."))
      (and c (:session c) (not= :ending (:status c)))
      (-> h (answer rid {"sessionId" (:session c) "_meta" {"relay/attached" true}})
          (replay cid role)
          (attach cid role))
      (and c (= :ending (:status c)))
      (refuse h rid -32000 (str role "'s conversation is ending. Try again once it has."))
      ;; One per connection and role: the first will be answered.
      (seq (records h #(and (= cid (:conn %)) (= role (:role %)) (#{:waiting :upstream} (:state %)) (contains? #{nil :new} (:kind %)))))
      (refuse h rid -32600 (str "A session for " role " is already being opened on this connection."))
      (full? h role) (refuse h rid -32000 full-message)
      c (-> h (update-record rid :state :waiting :role role :params params) (create-session role))
      :else
      (-> h (assoc-in [:convs role] {:role role :status :starting :init :none :next-id 0})
          (update-record rid :state :waiting :role role :params params)
          (fx {:effect :start :role role})))))

(defn- load-session [h rid msg]
  (let [cid (:conn (record h rid))
        s (session-of msg)
        role (conversation-of h s)]
    (if (and role (not= :ending (get-in h [:convs role :status])))
      (-> h (replay cid role) (answer rid {}) (attach cid role))
      (refuse h rid -32602 (str "There is no live conversation " (pr-str s)
                                ". relay does not reopen a conversation that has ended.")))))

(defn- under?
  "Whether directory `dir` is `filter` or inside it, by path segments."
  [filter dir]
  (let [f (str/replace filter #"/+$" "")]
    (or (= f dir) (str/starts-with? dir (str f "/")))))

(defn- list-sessions
  "The live conversations, filtered as ACP asks by working directory. A
  conversation is a role's, opened from whatever directory its first
  client ran in, so the filter takes a directory and everything under
  it: a phone that lists with \"/\" sees the conversation relay-tui opened
  in ~/code, and a directory no conversation is in or under lists none."
  [h rid msg]
  (let [cwd (get (params-of msg) "cwd")]
    (answer h rid
            {"sessions" (vec (for [{:keys [id title conversation]} (:roster h)
                                   :let [c (get-in h [:convs id])
                                         dir (or (:cwd c) "/")]
                                   :when (and (:session c) (not= :ending (:status c))
                                              (or (not (string? cwd)) (under? cwd dir)))]
                               (cond-> {"sessionId" (:session c) "cwd" dir "title" title
                                        "_meta" {"relay/role" id}}
                                 (:last-activity conversation) (assoc "updatedAt" (str (:last-activity conversation))))))})))

(defn- drop-prompts
  "Answers waiting prompts cancelled, each to its own connection."
  [h prompts]
  (reduce (fn [h [rid _]] (answer h rid {"stopReason" "cancelled"})) h prompts))

(defn- detach
  "Detaches a connection from a conversation. Its waiting prompt is
  cancelled; with `cancel?` (session/close, which in ACP cancels the
  session's work), so is a turn it started, while one another connection
  started goes on."
  [h cid role & [cancel?]]
  (as-> h h
    (drop-prompts h (filter #(= cid (:conn (val %))) (queued h role)))
    (if (and cancel? (= cid (:conn (second (running h role))))) (cancel-turn h role) h)
    (update-in h [:conns cid :attached] disj role)))

(defn- prompt
  "A prompt waits for its turn, one per session: as ACP has it, a session
  takes one prompt at a time, so another while one runs or waits is
  refused, and nothing piles up."
  [h rid msg]
  (let [[role why] (attached-conv h (:conn (record h rid)) msg)]
    (cond
      (not role) (refuse h rid -32602 why)
      (or (running h role) (seq (queued h role)))
      (refuse h rid -32000 "A prompt is already running or waiting in this conversation. Send this once it is done, or cancel it.")
      (full? h role) (refuse h rid -32000 full-message)
      :else (let [[params notes] (sanitize-prompt (params-of msg))]
              (-> h (update-record rid :state :queued :role role :kind :prompt :params params :notes notes)
                  (pump role))))))

(defn- cancel
  "session/cancel is for the session, from whichever attached connection:
  it is one person on several devices. Every waiting prompt is answered
  cancelled, each to its own connection, and a running turn is
  cancelled."
  [h cid msg]
  (let [[role] (attached-conv h cid msg)]
    (if-not role
      h
      (as-> h h
        (drop-prompts h (queued h role))
        (if (running h role) (cancel-turn h role) h)))))

(defn- seen [h rid msg]
  (let [s (session-of msg)
        role (conversation-of h s)]
    (if-not role
      (refuse h rid -32602 (str "There is no live conversation " (pr-str s) "."))
      (-> h
          (submit {:type :conversation/seen :actor core/operator
                   :args {:wake (get-in h [:convs role :wake]) :turn (get (params-of msg) "turn")}}
                  {:kind :reply :rid rid})))))

(defn- end-request [h rid msg]
  (let [s (session-of msg)
        role (conversation-of h s)]
    (if-not role
      (refuse h rid -32602 (str "There is no live conversation " (pr-str s) "."))
      (as-> h h
        (update-record h rid :state :ending :role role)
        ;; A running turn is cancelled first.
        (if (running h role) (cancel-turn h role) h)
        (stop h role)))))

(defn- forward
  "Passes a client's request on to the conversation it is about."
  [h rid msg]
  (let [cid (:conn (record h rid))
        [role why] (attached-conv h cid msg)
        method (get msg "method")]
    (cond
      (not role) (refuse h rid -32602 why)
      (full? h role) (refuse h rid -32000 full-message)
      :else (-> (update-record h rid :method method) (send-upstream rid role msg :forward)))))

(defn- connect [h cid rid msg first?]
  (let [{:strs [role]} (params-of msg)]
    (if (and first? (= :socket (get-in h [:conns cid :origin])))
      (cond-> (assoc-in h [:conns cid :role] (when (string? role) role))
        rid (answer rid {}))
      (cond-> h
        rid (refuse rid -32600 "relay takes _relay/connect only as the first line of a connection to its socket.")))))

(defn- initialize-answer [h]
  {"protocolVersion" 1
   "agentCapabilities" {"loadSession" true
                        "promptCapabilities" {"embeddedContext" true}
                        "sessionCapabilities" {"list" {} "close" {}}}
   "agentInfo" {"name" "relay" "title" "relay" "version" "1"}
   "authMethods" []
   ;; relay/keeps: whether a conversation outlives this client.
   "_meta" {"relay/hub" 1 "relay/keeps" (= :service (:mode h))}})

(defn- dispatch-request [h rid msg first?]
  (let [cid (:conn (record h rid))
        method (get msg "method")]
    (case method
      "_relay/connect" (connect h cid rid msg first?)
      "initialize" (-> h (assoc-in [:conns cid :init] (params-of msg))
                       (answer rid (initialize-answer h)))
      "authenticate" (answer h rid {})
      "session/new" (new-session h rid msg)
      "session/load" (load-session h rid msg)
      "session/list" (list-sessions h rid msg)
      "session/close" (let [[role why] (attached-conv h cid msg)]
                        (if role (-> h (detach cid role true) (answer rid {})) (refuse h rid -32602 why)))
      "session/prompt" (prompt h rid msg)
      "_relay/roles" (let [now (roles-wire h)]
                       (-> h (assoc-in [:conns cid :roles] now) (answer rid {"roles" now})))
      "_relay/seen" (seen h rid msg)
      "_relay/conversations/end" (end-request h rid msg)
      "_relay/cards" (cards-request h rid msg)
      "_relay/cards/op" (card-op h rid msg)
      "_relay/messages" (messages-request h rid msg)
      "_relay/ready" (ready-request h rid msg)
      "_relay/reply" (reply-request h rid msg)
      (cond
        (workspace/methods method) (forward h rid msg)
        (str/starts-with? method "_relay/") (refuse h rid -32601 (str "relay has no method " method "."))
        ;; Anything else goes to the conversation it is about, as a
        ;; plain ACP client would expect of relay acp <role>.
        (first (attached-conv h cid msg)) (forward h rid msg)
        :else (refuse h rid -32601 (str "relay has no method " method "."))))))

(defn- client-request
  "A client's request becomes a record in the request table, unless its
  id is one the connection already has a request outstanding under (two
  answers could meet), or the connection has as many outstanding as it
  may."
  [h cid id msg first?]
  (let [k (acp/id-key id)]
    (cond
      (nil? k) (write h cid (workspace/error-line nil -32600 "A request id is a number, a string or null."))
      (reserved? h cid k)
      (write h cid (workspace/error-line id -32600 (str "Request id " (strict/generate id) " is already in use on this connection.")))
      (>= (count (records h #(= cid (:conn %)))) (:max-pending h))
      (write h cid (workspace/error-line id -32000 "Too many requests are waiting for an answer on this connection."))
      :else
      (let [rid (:next-rid h)]
        (-> h (update :next-rid inc)
            (assoc-in [:requests rid] {:conn cid :id id :key k :method (get msg "method") :state :local})
            (dispatch-request rid msg first?))))))

(defn- cancel-request
  "A client's $/cancel_request for a request of its own that relay passed
  on goes to that conversation under relay's id for it."
  [h cid msg]
  (let [k (acp/id-key (get (params-of msg) "requestId"))
        [_ {:keys [role up]}] (first (records h #(and (= cid (:conn %)) (= k (:key %)) (= :upstream (:state %)))))]
    (if role
      (send-conv h role (workspace/notification-line "$/cancel_request" {"requestId" up}))
      h)))

(defn- client-notification [h cid msg first?]
  (let [method (get msg "method")]
    (cond
      (= "_relay/connect" method) (connect h cid nil msg first?)
      (= "session/cancel" method) (cancel h cid msg)
      (= "$/cancel_request" method) (cancel-request h cid msg)
      (str/starts-with? method "_relay/") h
      :else (let [[role] (attached-conv h cid msg)]
              (if (and role (session-of msg)) (send-conv h role (strict/generate msg)) h)))))

(defn- client-line [h cid line]
  (let [first? (zero? (get-in h [:conns cid :lines] 0))
        h (update-in h [:conns cid :lines] (fnil inc 0))
        msg (acp/message line)]
    (if (nil? msg)
      ;; Not strictly one JSON object: never passed on. A request whose
      ;; id can be found is told why, unless that id is one the
      ;; connection has outstanding, whose answer it must not seem to
      ;; give.
      (let [lid (acp/line-id line)
            k (when (some? lid) (acp/id-key lid))]
        (if (and k (not (reserved? h cid k)))
          (write h cid (workspace/error-line lid -32600 "relay does not pass on a line that is not one strictly valid JSON object."))
          h))
      (case (acp/kind msg)
        :request (client-request h cid (get msg "id") msg first?)
        :notification (client-notification h cid msg first?)
        ;; relay asks a client nothing, so an answer from one answers
        ;; nothing.
        h))))

(defn- close-connection
  "A connection has gone. A request of its that was passed on stays, an
  orphan, until its answer comes, and so does a prompt waiting for its
  turn, which keeps its place and runs; the rest are forgotten."
  [h cid]
  (let [h (reduce (fn [h [rid {:keys [state]}]]
                    (if (#{:upstream :queued} state)
                      (update-record h rid :orphan true)
                      (update h :requests dissoc rid)))
                  h (records h #(= cid (:conn %))))
        h (update h :conns dissoc cid)
        ;; A role whose waiters all left has nothing to start a session for.
        h (reduce create-session h (sort (keys (:convs h))))]
    (if (and (= :embedded (:mode h)) (empty? (:conns h)))
      (reduce stop h (sort (keys (:convs h))))
      h)))

;; --- what an agent says

(defn- agent-response [h role line msg]
  (let [n (get msg "id")
        [rid {:keys [kind id conn params orphan] :as r}]
        (first (records h #(and (= role (:role %)) (= :upstream (:state %)) (= (acp/id-key n) (acp/id-key (:up %))))))
        ok? (contains? msg "result")]
    (case kind
      nil h
      :init (let [h (update h :requests dissoc rid)]
              (if ok?
                (-> h (assoc-in [:convs role :init] :done) (create-session role))
                (fail-waiters h role -32000 "The agent could not start a session.")))
      :new (let [session (get-in msg ["result" "sessionId"])]
             (if (and ok? (string? session))
               (let [h (update-in h [:convs role] assoc :session session
                                  :cwd (let [c (get params "cwd")] (when (string? c) c)))]
                 (as-> h h
                   (reply h rid (with-id msg id))
                   (if (and conn (not orphan)) (-> h (replay conn role) (attach conn role)) h)
                   (answer-waiters h role)
                   (pump h role)))
               (-> h (reply rid (with-id msg id)) (create-session role))))
      :prompt (as-> h h
                ;; Recorded before the client reads the answer, so its next
                ;; prompt finds the turn over.
                (end-turn h role (workspace/stop-reason line))
                (reply h rid (with-id msg id))
                (pump h role))
      :forward (reply h rid (with-id msg id)))))

(defn- agent-line [h role line]
  (let [msg (acp/message line)
        method (get msg "method")
        kind (acp/kind msg)]
    (cond
      (or (nil? kind) (not (get-in h [:convs role]))) h
      (= :response kind) (agent-response h role line msg)
      ;; The agent cancelling a request of its own: no client was sent it.
      (= "$/cancel_request" method) h
      ;; A client is asked nothing by an agent, a permission request
      ;; included.
      (= :request kind)
      (send-conv h role (workspace/error-line (get msg "id") -32601 (str "relay's clients do not take " method ".")))
      (= "session/update" method)
      (reduce #(write %1 %2 line) (-> h (keep-state role msg line) (remember role line)) (attached h role))
      ;; The board is the hub's: a proxy has nothing to say about it.
      (str/starts-with? method "_relay/") h
      :else
      (reduce #(write %1 %2 line) h (attached h role)))))

;; --- what relay decided

(defn- submit-result [h {:keys [ok rejection message] :as result}]
  (let [{:keys [kind role rid] :as continuation} (peek (:awaiting h))
        h (update h :awaiting pop)
        r (when rid (record h rid))]
    (case kind
      :turn-start
      (cond
        ok (let [{:keys [conn params notes]} r]
             (-> h (tell role notes) (mirror role conn params)
                 (send-upstream rid role {"jsonrpc" "2.0" "method" "session/prompt" "params" params} :prompt)))
        ;; It waits for the role, and is asked for again when the log
        ;; changes.
        (= :busy rejection) (assoc-in h [:convs role :blocked] true)
        ;; Not recorded, so not run.
        :else (-> h (refuse rid -32000 (or message "relay could not start the turn.")) (pump role)))
      :turn-end
      (cond
        (not (get-in h [:convs role])) h
        ok (-> h (update-in [:convs role] dissoc :turn-end-due :told) (pump role))
        :else
        (as-> h h
          ;; Told once; the role stays busy, as the log says it is, until
          ;; the end is recorded.
          (if (get-in h [:convs role :told])
            h
            (-> h (assoc-in [:convs role :told] true)
                (fx {:effect :warn
                     :text (str "relay could not record the end of " role "'s turn (" message
                                "). It keeps trying; no other turn of " role " starts until it can.")})
                (submit {:type :conversation/turn-unrecorded :actor core/dispatcher
                         :args {:wake (get-in h [:convs role :wake])}}
                        {:kind :ignore})))))
      :reply
      (cond
        ok (answer h rid {})
        :else (refuse h rid -32000 (or message "relay refused it.")))
      :card-op (card-op-result h continuation result)
      :ignore h)))

;; --- step

(defn step
  "The hub after one event, and the effects it calls for, in order. While
  a submit waits for its result, that result must be the next event."
  [h {:keys [event] :as e}]
  (when (and (seq (:awaiting h)) (not= :submit-result event))
    (throw (ex-info "The hub was given an event while a submit's result was owed." {:event event})))
  (when (and (= :submit-result event) (empty? (:awaiting h)))
    (throw (ex-info "The hub was given a submit's result it did not ask for." {})))
  (let [h (assoc h ::fx [])
        h (case event
            :connection-opened
            (assoc-in h [:conns (:conn e)] {:origin (:origin e) :role (:role e) :default-role (:default-role e)
                                            :attached #{} :lines 0})
            :connection-line (if (get-in h [:conns (:conn e)]) (client-line h (:conn e) (:line e)) h)
            :connection-closed (if (get-in h [:conns (:conn e)]) (close-connection h (:conn e)) h)
            :conversation-started
            (let [role (:role e)]
              (-> (if (get-in h [:convs role])
                    h
                    ;; One opened before the hub asked (relay acp <role>).
                    (assoc-in h [:convs role] {:role role :init :none :next-id 0}))
                  (update-in [:convs role] assoc :status :running :wake (:wake e))
                  (create-session role)))
            :conversation-failed
            (-> h (fail-waiters (:role e) -32000 (or (:message e) "relay could not open the conversation."))
                (update :convs dissoc (:role e)))
            :agent-line (agent-line h (:role e) (:line e))
            :conversation-gone (end-conversation h (:role e))
            :submit-result (submit-result h e)
            :log-changed
            (as-> h h
              (assoc h :roster (vec (:roster e)) :state (or (:state e) (:state h)))
              (board-changes h)
              (messages-changes h)
              (ready-changes h)
              (retry-turn-ends h)
              (reduce (fn [h role] (-> h (assoc-in [:convs role :blocked] false) (pump role)))
                      h (sort (keys (:convs h)))))
            :tick (retry-turn-ends h))
        ;; The roster is sent once relay has decided what this event led
        ;; to, never in between.
        h (if (empty? (:awaiting h)) (roster-changes h) h)]
    [(dissoc h ::fx) (::fx h)]))

(defn drive
  "Steps the hub through an event and everything it leads to, carrying
  out each effect with (perform effect), which returns the event it
  leads to (a submit's result) or nil. This is the one order relay uses,
  in its shell and in its tests: each step's effects in order, then the
  events they led to, oldest first, before anything else. Returns the
  hub."
  [h event perform]
  (loop [h h pending (conj clojure.lang.PersistentQueue/EMPTY event)]
    (if-let [e (peek pending)]
      (let [[h fx] (step h e)
            follow (keep perform fx)]
        (recur h (into (pop pending) follow)))
      h)))

(defn idle?
  "Whether an embedded hub is done: no connection and no conversation."
  [h]
  (and (empty? (:conns h)) (empty? (:convs h))))
