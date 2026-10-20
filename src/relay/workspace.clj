(ns relay.workspace
  "What `relay acp` tells a chat client's workspace pane, through ACP
  extension methods (their names start with _, as ACP requires):

    _relay/items     what the operator was shown in a session, and the
                     calls it proposed, newest first
    _relay/resolve   look a [[kind:id]] ref up
    _relay/trust     trust a shown doc, as `relay trust doc <path>` does
    _relay/cards     the operator's board, as wire forms (relay.cards/wire);
                     the first call subscribes the client to
                     _relay/cards/changed notifications
    _relay/cards/op  change a card, as the operator

  relay acp answers them itself; agentloop never sees them. A conversation
  keeps what it learns from the traffic in a value, `conversation`: the
  displays it kept and the permission requests it passed on. Everything
  here is pure; relay.dispatch is the shell.

  Trust needs the same presence of the operator as an answer to a
  permission request: it is taken only when relay acp was started with
  --approvals, which relay chat does because its client is the operator's
  terminal. Without it the pane is told to show the command instead. A
  trust names the version and the digest of the text the pane showed, so
  a doc that changed since is not trusted."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [relay.acp :as acp]
            [relay.cards :as cards]
            [relay.core :as core]))

;; --- what a conversation has learned

(def conversation
  "Nothing learned yet: the displays kept, in the order they came, the
  permission requests passed on, by [session call], and the rev of every
  card the client was last sent, nil until it subscribes to the board."
  {:displays [] :proposals {} :cards nil})

(defn shown
  "The conversation once a session's displays are kept: each {:session
  :call :ref :path}, once."
  [conv session displays]
  (reduce (fn [c {:keys [call ref path]}]
            (if (some #(= path (:path %)) (:displays c))
              c
              (update c :displays conj {:session session :call call :ref ref :path path})))
          conv displays))

(defn- status-of [outcome]
  (case outcome
    ("allow_once" "allow_always") "allowed"
    ("reject_once" "reject_always") "rejected"
    "cancelled"))

(defn asked
  "The conversation once a permission request went to the client. A
  proposal is labelled with what it shows: the session's labels, which
  its input carries, and its preview's (relay.acp/preview-labels)."
  [conv {:keys [session call tool input labels preview preview-labels fixed-args]} ^java.time.Instant at]
  (assoc-in conv [:proposals [session call]]
            (cond-> {:session session :call call :tool tool :input input
                     :labels (vec (core/union labels (when preview preview-labels)))
                     :preview preview :status "waiting" :at at}
              (seq fixed-args) (assoc :fixed (vec (sort (keys fixed-args)))))))

(defn answered
  "The conversation once a permission request was answered, by the client
  or by relay."
  [conv {:keys [session call]} outcome]
  (if (get-in conv [:proposals [session call]])
    (assoc-in conv [:proposals [session call] :status] (status-of outcome))
    conv))

;; --- the requests

(def methods #{"_relay/items" "_relay/resolve" "_relay/trust" "_relay/cards" "_relay/cards/op"})

(defn extension-request
  "A client's line as one of relay's extension methods: {:id :method
  :params}, with :id nil for a notification, when its exact method starts
  with _relay/, whether or not it is one of `methods`. A line the strict
  reader refuses (relay.acp/message) is {:id :refused why}, with the id
  relay can find in it (relay.acp/line-id) or nil: it never reaches the
  agent. Nil for any other line, which goes on to the agent as it is."
  [line]
  (when (string? line)
    (let [msg (acp/message line)]
      (cond
        (nil? msg)
        {:id (acp/line-id line) :refused "relay does not pass on a line that is not one strictly valid JSON object."}

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

(defn result-line [id result]
  (json/generate-string {"jsonrpc" "2.0" "id" id "result" result}))

(defn error-line [id code message]
  (json/generate-string {"jsonrpc" "2.0" "id" id "error" {"code" code "message" message}}))

;; --- items

(defn- shown-meta
  "The title and sources relay kept with a shown doc, from the version
  that stored the display."
  [state path]
  (:shown (first (filter :shown (get-in state [:docs path :versions])))))

(defn shown-item
  "A shown doc as the pane lists it, or nil when it is gone. `text` is what
  trusting it vouches for, and `sha256` its digest, which _relay/trust
  must give back."
  [state {:keys [call ref path]} approvals?]
  (let [v (core/doc-version state path {})
        t (core/trusted-version state "doc" path)]
    (when (and v (not (:removed v)) t)
      (let [{:keys [title sources]} (shown-meta state path)
            labels (vec (core/union (:labels t)))]
        {"kind" "shown" "ref" ref "path" path "callId" call
         "at" (str (:at v))
         "title" (or title path) "text" (:text t)
         "sources" (vec (for [{t :title r :ref} sources] {"title" t "ref" r}))
         "labels" labels
         "version" (:version t) "sha256" (core/sha256 (:text t))
         "canTrust" (boolean (and approvals? (core/contaminated? labels)))
         "trustCommand" (str "relay trust doc " path)}))))

(defn- proposal-item [{:keys [call tool input labels preview status at fixed]}]
  (cond-> {"kind" "proposal" "callId" call "tool" tool "input" input "preview" preview
           "labels" labels "status" status "at" (str at)}
    ;; The arguments in the input that the operator set.
    (seq fixed) (assoc "fixedArgs" fixed)))

(defn items
  "The _relay/items answer for a session (all of the conversation's when
  `session` is nil): its shown docs and its proposals, newest first."
  [state conv session approvals?]
  (let [mine? #(or (nil? session) (= session (:session %)))
        shown (keep #(when (mine? %) (shown-item state % approvals?)) (:displays conv))
        proposals (map proposal-item (filter mine? (vals (:proposals conv))))]
    {"items" (vec (reverse (sort-by #(core/inst (get % "at")) (concat shown proposals))))}))

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

;; --- trust

(defn trust-command
  "What a _relay/trust asks for, as the command `relay trust doc` submits,
  or {:error why}. Only a doc the conversation itself showed can be
  trusted this way, and only when relay acp takes approvals from its
  client. The version and digest are what the pane showed; core refuses
  the trust if the doc has changed since."
  [conv approvals? {:strs [sessionId path version sha256]}]
  (cond
    (not approvals?)
    {:error (str "This conversation does not take trust from its client. Run relay trust doc " path
                 " in a terminal.")}
    (not (and (string? path) (some #(and (= path (:path %)) (or (nil? sessionId) (= sessionId (:session %))))
                                   (:displays conv))))
    {:error "Only something this conversation showed you can be trusted here."}
    (not (and (integer? version) (string? sha256)))
    {:error "_relay/trust needs the path, version and sha256 the pane showed."}
    :else
    {:command {:type :label/trust :actor core/operator :labels []
               :args {:kind "doc" :ref path :version version :sha256 sha256 :via "chat"}}}))

;; --- the board
;;
;; A client never holds the truth about a card: it sends an op, relay
;; decides it, and the client draws whatever relay sends back. Every op
;; from the client is the operator's, with no labels, whether or not
;; relay acp takes approvals: the pane is the operator's as the command
;; line is. A card change never wakes a role.

(defn card-wire [c] (cards/wire c (core/card-labels c)))

(defn log-rev
  "The seq of the log's last event, which a client can compare revs with."
  [state]
  (dec (:next-seq state)))

(defn subscribed
  "The conversation once the client has been sent every card as it is in
  `state`, so it is told of each change from here on."
  [conv state]
  (assoc conv :cards (cards/revs (:cards state))))

(defn cards-answer
  "The _relay/cards answer: the board's cards as wire forms, the
  archived ones too when asked."
  [state {:strs [archived]}]
  {"rev" (log-rev state)
   "cards" (mapv card-wire (core/board state (true? archived)))})

(defn cards-changed
  "The _relay/cards/changed notification's params for a subscribed
  conversation: every card whose rev is not the one the client was last
  sent, archived ones included, so the client can drop them. Nil when
  nothing changed or the client has not subscribed."
  [state conv]
  (when-let [sent (:cards conv)]
    (when-let [ids (seq (cards/changed-since sent (:cards state)))]
      {"rev" (log-rev state) "cards" (mapv #(card-wire (core/card state %)) ids)})))

(defn notification-line [method params]
  (json/generate-string {"jsonrpc" "2.0" "method" method "params" params}))

(defn card-op-command
  "What a _relay/cards/op asks for, as the :card/op command relay
  submits, or {:error why}. It is the operator's, with no labels."
  [params]
  (let [{:strs [card]} params
        ;; A typo must not pass as success: a misspelled seen would turn
        ;; off the stale-edit check. sessionId is what acp-tui sends.
        known (into #{"card" "sessionId"} (map name) cards/op-fields)
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
