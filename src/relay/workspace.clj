(ns relay.workspace
  "What `relay acp` tells a client's workspace pane, through ACP
  extension methods (their names start with _, as ACP requires):

    _relay/items     what the operator was shown in a session, newest first
    _relay/resolve   look a [[kind:id]] ref up
    _relay/cards     the operator's board, as wire forms (relay.cards/wire);
                     the first call subscribes the client to
                     _relay/cards/changed notifications
    _relay/cards/op  change a card, as the operator
    _relay/messages  what relay told the operator, newest first (relay.
                     channels/wire); the first call subscribes the client
                     to _relay/messages/changed notifications
    _relay/ready     everything ready for the operator (relay.ready); the
                     first call subscribes the client to
                     _relay/ready/changed notifications
    _relay/reply     answer a message, as the operator
    _relay/undo      undo a file move, as the operator (relay undo)

  relay answers them itself, and agentloop never sees them: the board's in
  relay.hub, which knows what each client was sent, the rest in each
  conversation's proxy (relay.dispatch). A conversation keeps what it
  learns from the traffic in a value, `conversation`: the displays it
  kept. Everything here is pure; relay.dispatch is the shell."
  (:require [clojure.string :as str]
            [relay.acp :as acp]
            [relay.cards :as cards]
            [relay.channels :as channels]
            [relay.core :as core]
            [relay.json :as strict]
            [relay.ready :as ready]))

;; --- what a conversation has learned

(def conversation
  "Nothing learned yet: the displays kept, in the order they came."
  {:displays []})

(defn shown
  "The conversation once a session's displays are kept: each {:session
  :call :ref :path}, once."
  [conv session displays]
  (reduce (fn [c {:keys [call ref path]}]
            (if (some #(= path (:path %)) (:displays c))
              c
              (update c :displays conj {:session session :call call :ref ref :path path})))
          conv displays))

;; --- the requests

(def methods
  "The pane's methods a conversation's proxy answers. The board's
  (_relay/cards, _relay/cards/op) are relay.hub's, which knows what each
  client was sent."
  #{"_relay/items" "_relay/resolve"})

(defn extension-request
  "A client's line as one of relay's extension methods: {:id :method
  :params}, with :id nil for a notification, when its exact method starts
  with _relay/, whether or not it is one of `methods`. A line the strict
  reader refuses (relay.acp/message) is {:id :refused why}, with the id
  relay can find in it (relay.acp/line-id) or nil: it never reaches the
  agent. Nil for any other line, which goes on to the agent as it is.
  Takes a line as relay.acp/line reads it, or its text."
  [x]
  (let [{text :text msg :message} (acp/line x)]
    (when (some? text)
      (cond
        (nil? msg)
        {:id (acp/line-id text) :refused "relay does not pass on a line that is not one strictly valid JSON object."}

        (acp/relay-method? msg)
        {:id (get msg "id") :method (get msg "method") :params (let [p (get msg "params")] (if (map? p) p {}))}))))

(defn drop-agent-line?
  "True when relay acp must not pass an agent's line to the client: the
  strict reader refuses it (relay.acp/message), or its exact method starts
  with _relay/. Only relay speaks relay's methods to the client, and every
  component reads a line that passes by the same rule, so none of them
  can read it as one of relay's."
  [line]
  (let [msg (acp/message line)]
    (or (nil? msg) (acp/relay-method? msg))))

;; --- responses

(defn client-request-key
  "The id key of a client's line when it is a request, one the agent owes
  an answer to once relay passes it on, or nil. A request is a message
  with an id and a method that is a non-empty string."
  [line]
  (let [msg (acp/message line)]
    (when (= :request (acp/kind msg))
      (acp/id-key (get msg "id")))))

(defn agent-response
  "{:key k} when the client would read an agent's line as a response: a
  message with an id and no method that is a non-empty string (the ACP
  SDK reads a missing, null or empty method alike). k is the id's key,
  nil when it can be no request's. Nil for any other line."
  [line]
  (let [msg (acp/message line)]
    (when (= :response (acp/kind msg))
      {:key (acp/id-key (get msg "id"))})))

(defn owe
  "The answers owed by the agent, once relay passed it the client's
  request with id key k. A count, since an agent may answer twice to an id
  the client reused while it was open (one of them an error)."
  [owed k]
  (update owed k (fnil inc 0)))

(defn forget
  "The answers owed once the client sent relay one of its own methods as a
  request with id key k, before relay answers it. The client has given up on any
  earlier request under k, or reused its id, so a late answer from the
  agent under k could only be taken for the answer to this one; it is
  dropped. A line the strict reader refuses cancels nothing: it may be a
  malformed answer to the agent, whose ids can equal the client's."
  [owed k]
  (dissoc owed k))

(defn settle
  "[owed' pass?]: whether an agent's response with id key k answers a
  request the client sent it and that it has not answered yet, and what
  is owed after it. Only such a response reaches the client, so an agent
  cannot answer a request the client sent relay (_relay/cards, say), one
  it never got, or one it has answered already."
  [owed k]
  (let [n (get owed k 0)]
    (if (and (some? k) (pos? n))
      [(if (= 1 n) (dissoc owed k) (assoc owed k (dec n))) true]
      [owed false])))

(defn result-line [id result]
  (strict/generate {"jsonrpc" "2.0" "id" id "result" result}))

(defn error-line [id code message]
  (strict/generate {"jsonrpc" "2.0" "id" id "error" {"code" code "message" message}}))

;; --- turns
;;
;; A conversation holds its role only while a turn runs (relay#63), so
;; each prompt a client sends is a turn relay records: started before the
;; prompt reaches the agent, and ended when the agent answers it.
;; relay.hub does the gating; these make its commands.

(defn turn-start-command
  "The command that starts a turn of conversation `wake` in an ACP
  session: the operator's, since every client is theirs."
  [wake session]
  {:type :conversation/turn-start :actor core/operator :args {:wake wake :session session}})

(defn stop-reason
  "Why an agent's answer to a prompt says its turn stopped: the result's
  stopReason, or \"error\" for an error. relay.core keeps only the
  reasons it knows."
  [line]
  (let [msg (acp/message line)
        r (get-in msg ["result" "stopReason"])]
    (cond (contains? msg "error") "error"
          (string? r) r
          :else "other")))

(defn turn-end-command [wake stop-reason]
  {:type :conversation/turn-end :actor core/dispatcher :args {:wake wake :stop-reason stop-reason}})

;; --- items

(defn- shown-meta
  "The title and sources relay kept with a shown doc, from the version
  that stored the display."
  [state path]
  (:shown (first (filter :shown (get-in state [:docs path :versions])))))

(defn shown-item
  "A shown doc as the pane lists it, or nil when it is gone. `labels` say
  where what it shows came from."
  [state {:keys [call ref path]}]
  (let [v (core/doc-version state path {})]
    (when (and v (not (:removed v)))
      (let [{:keys [title sources]} (shown-meta state path)]
        {"kind" "shown" "ref" ref "path" path "callId" call
         "at" (str (:at v))
         "title" (or title path) "text" (:content v)
         "sources" (vec (for [{t :title r :ref} sources] {"title" t "ref" r}))
         "labels" (core/union (:labels v) (core/path-labels state path))
         "version" (:seq v)}))))

(defn items
  "The _relay/items answer for a session (all of the conversation's when
  `session` is nil): its shown docs, newest first."
  [state conv session]
  (let [mine? #(or (nil? session) (= session (:session %)))
        shown (keep #(when (mine? %) (shown-item state %)) (:displays conv))]
    {"items" (vec (reverse (sort-by #(core/inst (get % "at")) shown)))}))

;; --- resolve

(defn resolve-answer
  "The _relay/resolve answer: what `resolve` (relay.refs/resolve) says of
  the ref, with keywords as strings. relay.refs does no public-role
  filtering, which is right here: relay acp's client is the operator's."
  [resolve state {:strs [ref]}]
  (if-not (string? ref)
    {:error "_relay/resolve needs a ref."}
    (let [r (resolve state ref)]
      {:result (cond-> {"ref" (:ref r) "kind" (some-> (:kind r) name) "status" (some-> (:status r) name)}
                 (:title r) (assoc "title" (:title r))
                 (contains? r :text) (assoc "text" (:text r))
                 (:labels r) (assoc "labels" (vec (:labels r)))
                 (:target r) (assoc "target" (:target r)))})))

;; --- the board
;;
;; A client never holds the truth about a card: it sends an op, relay
;; decides it, and the client draws whatever relay sends back. Every op
;; from the client is the operator's, with no labels (I5): the pane is
;; the operator's as the command line is. A card change never wakes a
;; role.

(defn card-wire
  "A card as a client is sent it (relay.cards/wire)."
  [c]
  (cards/wire c (core/card-labels c)))

(defn log-rev
  "The seq of the log's last event, which a client can compare revs with."
  [state]
  (dec (:next-seq state)))

(defn subscribed
  "A subscriber's record ({:cards revs}) once it has been sent every card
  as it is in `state`, so it is told of each change from here on."
  [conv state]
  (assoc conv :cards (cards/revs (:cards state))))

(defn cards-params-error
  "Why relay refuses a _relay/cards request's params, or nil. The board
  is the connection's, so the method takes no sessionId; like every
  parameter it does not know, one is refused rather than ignored."
  [params]
  (when-let [unknown (seq (sort (remove #{"archived"} (keys params))))]
    (str "_relay/cards has no parameter " (str/join ", " unknown) ". It takes archived.")))

(defn cards-answer
  "The _relay/cards answer: the board's cards as wire forms, the
  archived ones too when asked."
  [state {:strs [archived]}]
  {"rev" (log-rev state)
   "cards" (mapv card-wire (core/board state (true? archived)))})

(defn cards-changed
  "The _relay/cards/changed notification's params for a subscriber's
  record: every card whose rev is not the one the client was last
  sent, archived ones included, so the client can drop them. Nil when
  nothing changed or the client has not subscribed."
  [state conv]
  (when-let [sent (:cards conv)]
    (when-let [ids (seq (cards/changed-since sent (:cards state)))]
      {"rev" (log-rev state) "cards" (mapv #(card-wire (core/card state %)) ids)})))

(defn notification-line [method params]
  (strict/generate {"jsonrpc" "2.0" "method" method "params" params}))

(defn card-op-command
  "What a _relay/cards/op asks for, as the :card/op command relay
  submits, or {:error why}. It is the operator's, with no labels."
  [params]
  (let [{:strs [card]} params
        ;; A typo must not pass as success: a misspelled seen would turn
        ;; off the stale-edit check. The board is the connection's, so a
        ;; sessionId is refused like any other unknown parameter.
        known (into #{"card"} (map name) cards/op-fields)
        unknown (sort (remove known (keys params)))]
    (cond
      (seq unknown)
      {:error (str "_relay/cards/op has no parameter " (str/join ", " unknown) ". It takes card, "
                   (str/join ", " (map name cards/op-fields)) ".")}
      (not (integer? card))
      {:error "_relay/cards/op needs a card, by number."}
      :else
      {:command {:type :card/op :actor core/operator :labels [] :public false
                 :args (into {:card card}
                             (keep (fn [k] (when (contains? params (name k)) [k (get params (name k))])))
                             cards/op-fields)}})))

(defn card-op-answer
  "The _relay/cards/op answer once the op is decided: the card as it is
  now, and whether the op changed it."
  [state card-id changed?]
  {"card" (card-wire (core/card state card-id)) "changed" (boolean changed?)})

;; --- messages: the client channel (relay.messages)
;;
;; Every message relay tells the operator is the client's to show, as it
;; was sent and whatever became of its other deliveries. A subscriber's
;; record is the log's rev when it was last sent messages; a message
;; whose rev is past it is new or changed.

(def default-messages-limit 100)
(def max-messages-limit 500)

(defn messages-params-error
  "Why relay refuses a _relay/messages request's params, or nil. It
  takes `limit`, how many of the newest to send, and nothing else."
  [params]
  (let [unknown (sort (remove #{"limit"} (keys params)))
        limit (get params "limit")]
    (cond
      (seq unknown) (str "_relay/messages has no parameter " (str/join ", " unknown) ". It takes limit.")
      (and (contains? params "limit") (not (and (integer? limit) (<= 1 limit max-messages-limit))))
      (str "_relay/messages takes a limit from 1 to " max-messages-limit "."))))

(defn messages-answer
  "The _relay/messages answer: the newest messages as wire forms, newest
  first, and the log's rev."
  [state params]
  {"rev" (log-rev state)
   "messages" (->> (vals (:messages state)) (sort-by :id >)
                   (take (or (get params "limit") default-messages-limit))
                   (mapv channels/wire))})

(defn messages-changed
  "The _relay/messages/changed params for a subscriber last sent the
  messages as of `rev`: every message made or changed since, oldest
  first. Nil when there is none."
  [state rev]
  (when-let [ms (seq (filter #(> (:rev %) rev) (sort-by :id (vals (:messages state)))))]
    {"rev" (log-rev state) "messages" (mapv channels/wire ms)}))

;; --- ready: what relay prepared and told the operator (relay.ready)
;;
;; A subscriber's record is the wire items it was last sent, by id. What
;; differs from it is new, changed or gone.

(defn ready-params-error
  "Why relay refuses a _relay/ready request's params, or nil. It takes
  limit, how many of the newest items to send, and nothing else."
  [params]
  (let [unknown (sort (remove #{"limit"} (keys params)))
        limit (get params "limit")]
    (cond
      (seq unknown) (str "_relay/ready has no parameter " (str/join ", " unknown) ". It takes limit.")
      (and (contains? params "limit") (not (and (integer? limit) (<= 1 limit 500))))
      "_relay/ready takes a limit from 1 to 500.")))

(defn ready-items
  "The wire items a _relay/ready answer holds, for params."
  [state params]
  (get (ready/answer state (log-rev state) {:limit (or (get params "limit") ready/default-limit)}) "items"))

(defn ready-answer
  "The _relay/ready answer."
  [state params]
  {"rev" (log-rev state) "items" (ready-items state params)})

(defn ready-changed
  "The _relay/ready/changed params for a subscriber last sent `sent` (wire
  items by id), or nil when the view is as it was sent."
  [state sent limit]
  (ready/changed (log-rev state) sent (ready-items state {"limit" limit})))

(defn reply-command
  "What a _relay/reply asks for, as the :message/reply command relay
  submits, or {:error why}. It is the operator's, with no labels."
  [params]
  (let [unknown (sort (remove #{"inReplyTo" "text"} (keys params)))
        {:strs [inReplyTo text]} params]
    (cond
      (seq unknown) {:error (str "_relay/reply has no parameter " (str/join ", " unknown) ". It takes inReplyTo and text.")}
      (not (integer? inReplyTo)) {:error "_relay/reply needs inReplyTo, the number of the message."}
      (not (string? text)) {:error "_relay/reply needs text."}
      :else {:command {:type :message/reply :actor core/operator :labels [] :public false
                       :args {:in-reply-to inReplyTo :text text}}})))

(defn undo-params-error
  "Why relay refuses a _relay/undo request's params, or nil. It takes
  move, the number of a files_move call (a move item's undo)."
  [params]
  (let [unknown (sort (remove #{"move"} (keys params)))]
    (cond
      (seq unknown) (str "_relay/undo has no parameter " (str/join ", " unknown) ". It takes move.")
      (not (and (integer? (get params "move")) (pos? (get params "move"))))
      "_relay/undo needs move, the number of the move to undo.")))
