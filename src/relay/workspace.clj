(ns relay.workspace
  "What `relay acp` tells a chat client's workspace pane, through three
  ACP extension methods (their names start with _, as ACP requires):

    _relay/items    what the operator was shown in a session, and the
                    calls it proposed, newest first
    _relay/resolve  look a [[kind:id]] ref up
    _relay/trust    trust a shown doc, as `relay trust doc <path>` does

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
            [relay.core :as core]))

;; --- what a conversation has learned

(def conversation
  "Nothing learned yet: the displays kept, in the order they came, and the
  permission requests passed on, by [session call]."
  {:displays [] :proposals {}})

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
  "The conversation once a permission request went to the client."
  [conv {:keys [session call tool input labels preview]} ^java.time.Instant at]
  (assoc-in conv [:proposals [session call]]
            {:session session :call call :tool tool :input input :labels (vec labels)
             :preview preview :status "waiting" :at at}))

(defn answered
  "The conversation once a permission request was answered, by the client
  or by relay."
  [conv {:keys [session call]} outcome]
  (if (get-in conv [:proposals [session call]])
    (assoc-in conv [:proposals [session call] :status] (status-of outcome))
    conv))

;; --- the requests

(def methods #{"_relay/items" "_relay/resolve" "_relay/trust"})

(defn extension-request
  "A client's line as one of relay's extension methods: {:id :method
  :params}, with :id nil for a notification. Nil for any other line, which
  goes on to the agent as it is. A method that starts with _relay/ is
  relay's whether or not it is one of `methods`."
  [line]
  (let [msg (when (and (string? line) (str/includes? line "_relay/"))
              (try (json/parse-string line) (catch Exception _ nil)))
        method (when (map? msg) (get msg "method"))]
    (when (and (string? method) (str/starts-with? method "_relay/"))
      {:id (get msg "id") :method method :params (let [p (get msg "params")] (if (map? p) p {}))})))

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

(defn- proposal-item [{:keys [call tool input labels preview status at]}]
  {"kind" "proposal" "callId" call "tool" tool "input" input "preview" preview
   "labels" labels "status" status "at" (str at)})

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
