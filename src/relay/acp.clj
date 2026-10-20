(ns relay.acp
  "The pure half of `relay acp`. relay sits between an ACP client and the
  role's ACP agent and copies bytes both ways unchanged. On the side, it
  reads the traffic line by line to learn what the conversation's wake
  should record: the sessions it used, and how its last turn ended.

  Nothing here does I/O. relay.dispatch/acp! is the shell.

  A direction is :client (client to agent) or :agent (agent to client)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [relay.json :as strict]
            [relay.text :as text])
  (:import (java.util Arrays)))

;; --- lines out of chunks

(def no-rest
  "Nothing left over: where splitting starts."
  [])

(defn- join-bytes ^bytes [parts]
  (let [out (byte-array (reduce + 0 (map #(alength ^bytes %) parts)))]
    (reduce (fn [at ^bytes p]
              (System/arraycopy p 0 out at (alength p))
              (+ at (alength p)))
            0 parts)
    out))

(defn left-over
  "The bytes a split has kept back, waiting for their newline."
  ^bytes [rest]
  (join-bytes rest))

(defn split
  "Adds a chunk of bytes to what is left over from earlier chunks and
  returns [left-over lines]: the complete lines, as bytes without their
  newlines, and what came after the last newline, kept for the next chunk.
  ACP frames each message as one line, but a chunk can end anywhere.

  What is left over is a vector of pieces, joined only when its line
  completes, so a long line arriving in many chunks is copied once."
  [rest ^bytes chunk]
  (let [n (alength chunk)
        ;; Latin-1 maps each byte to one char, so indexes agree, and
        ;; indexOf finds a newline far faster than a loop over the bytes.
        s (String. chunk java.nio.charset.StandardCharsets/ISO_8859_1)]
    (loop [start 0 rest rest lines (transient [])]
      (let [i (.indexOf s 10 (int start))]
        (if (neg? i)
          [(if (< start n) (conj rest (Arrays/copyOfRange chunk start n)) rest)
           (persistent! lines)]
          (recur (inc i) no-rest
                 (conj! lines (join-bytes (conj rest (Arrays/copyOfRange chunk start i))))))))))

(defn text
  "A line's bytes as text (relay.json/text): a byte that is not part of
  valid UTF-8 becomes a lone surrogate, so the line never parses."
  [^bytes line]
  (strict/text line))

;; --- what the traffic says

(def observer
  "What has been learned so far: the client's requests still waiting for an
  answer (id to method, and the session a load or resume names), the
  sessions the conversation used, in the order it first used them, and
  how the last answered request ended. Also what each session has cost
  so far, from the agent's usage_update, and whether any usage_update came
  without a cost."
  {:requests {} :sessions [] :last nil :costs {} :unpriced false})

(def ^:private last-message
  "The last string line this thread read as a message, and what it read:
  relay acp asks about one line many times (the screen, permissions,
  displays, the observer, the trace), and the strict reader is slow
  enough on a large line that reading it once matters. Kept per thread,
  since each direction is copied on its own thread; it holds at most one
  line per thread."
  (ThreadLocal.))

(defn message
  "A line as a message: the JSON object it is, read by relay's strict
  reader (relay.json), or nil when it is not one. Every reader of ACP in
  relay, agentloop and acp-tui reads by the same rule, so a line that
  passes means the same to all of them.

  The same string asked about again on the same thread is not read again.
  Strings cannot change, so the answer cannot go stale; bytes, which can,
  are read every time."
  [line]
  (cond
    (string? line)
    (let [[seen msg :as hit] (.get ^ThreadLocal last-message)]
      (if (and hit (identical? seen line))
        msg
        (let [msg (strict/object line)]
          (.set ^ThreadLocal last-message [line msg])
          msg)))

    (bytes? line) (strict/object line)))

(defn relay-method?
  "Whether a message's method is one of relay's: its exact method member
  starts with _relay/. Only relay sends these to the client."
  [msg]
  (let [m (when (map? msg) (get msg "method"))]
    (and (string? m) (str/starts-with? m "_relay/"))))

;; Unrolled and possessive, so a long string cannot overflow the regex
;; engine's stack.
(def ^:private id-pattern #"\"id\"\s*:\s*(-?\d+|\"[^\"\\]*+(?:\\.[^\"\\]*+)*+\")")

(defn line-id
  "The id a line that cannot be read whole seems to carry: the first
  \"id\" member with a number or a string, or nil. Only for answering
  or refusing such a line, never for passing it on."
  [line]
  (some-> (re-find id-pattern (str line)) second (strict/parse-or nil)))

(defn- parse [line] (message line))

(defn- session-id [x]
  (when (and (string? x) (not (str/blank? x))) x))

(defn- used [obs id]
  (if (or (nil? id) (some #{id} (:sessions obs)))
    obs
    (update obs :sessions conj id)))

(defn- client-said [obs msg]
  (let [{id "id" method "method" params "params"} msg]
    (if (and (string? method) (contains? msg "id"))
      (assoc-in obs [:requests id]
                (cond-> {:method method}
                  (#{"session/load" "session/resume"} method)
                  (assoc :session (when (map? params) (session-id (get params "sessionId"))))))
      obs)))

(defn- usage-update
  "What an agent's usage_update says a session has cost so far: the
  session and an amount in US dollars, or the session and nil when it
  carries no cost in dollars. Nil for any other message."
  [msg]
  (let [params (get msg "params")
        u (when (map? params) (get params "update"))]
    (when (and (= "session/update" (get msg "method")) (map? u)
               (= "usage_update" (get u "sessionUpdate")))
      (let [{amount "amount" currency "currency"} (let [c (get u "cost")] (when (map? c) c))]
        [(session-id (get params "sessionId"))
         (when (and (= "USD" currency) (number? amount) (Double/isFinite (double amount)) (>= amount 0))
           (double amount))]))))

(defn- agent-answered [obs msg]
  (let [{id "id" result "result" error "error"} msg
        request (when (and (contains? msg "id") (not (contains? msg "method")))
                  (get-in obs [:requests id]))]
    (if-not request
      obs
      (let [obs (update obs :requests dissoc id)]
        (cond
          (map? error)
          (assoc obs :last {:error (let [m (get error "message")]
                                     (if (and (string? m) (not (str/blank? m)))
                                       m
                                       (str "error " (pr-str (get error "code")))))})
          (contains? msg "result")
          (let [obs (case (:method request)
                      "session/new" (used obs (when (map? result) (session-id (get result "sessionId"))))
                      ("session/load" "session/resume") (used obs (:session request))
                      obs)
                stop (when (map? result) (get result "stopReason"))]
            (if (string? stop) (assoc obs :last {:stop stop}) obs))
          :else obs)))))

(defn- agent-said [obs msg]
  (if-let [[session cost] (usage-update msg)]
    ;; Each cost is the session's total so far, so the latest one stands.
    (if (and session cost)
      (assoc-in obs [:costs session] cost)
      (assoc obs :unpriced true))
    (agent-answered obs msg)))

(defn observe
  "The observer after one complete line went by in a direction. Total: a
  line that is not JSON, or JSON that is not a message relay understands,
  changes nothing.

  Sessions come from the answers: the id a successful session/new returns,
  and the id a successful session/load or session/resume named. A load the
  agent refused used no session. Notifications that mention a session
  (session/update, say) add no session, though a usage_update records
  what its session has cost so far."
  [obs direction line]
  (let [msg (when (string? line) (parse line))]
    (if-not (map? msg)
      obs
      (case direction
        :client (client-said obs msg)
        :agent (agent-said obs msg)
        obs))))

(defn said
  "How the conversation's last answered request ended, for a person to
  read, or nil when nothing was answered."
  [{:keys [last]}]
  (cond
    (:stop last) (str "Conversation ended. Its last turn ended with " (:stop last) ".")
    (:error last) (str "Conversation ended. Its last request failed: " (:error last))))

(defn cost-usd
  "What the conversation cost in US dollars: the sum of each session's
  last reported cost. Nil when no session reported one, or when any
  usage_update came without a cost, since then the sum would be short."
  [{:keys [costs unpriced]}]
  (when (and (seq costs) (not unpriced))
    (reduce + 0.0 (vals costs))))

(defn report
  "The report relay.dispatch/conversation-end-args takes, from what was
  observed and the agent's exit code."
  [obs exit]
  (cond-> {:exit exit :sessions (:sessions obs) :said (said obs)}
    (cost-usd obs) (assoc :cost-usd (cost-usd obs))))

(defn trace-entry
  "One line of a --trace file: who sent the line, and the message, or the
  raw text when the line is not JSON."
  [direction line]
  (let [msg (parse line)]
    (cond-> {:from (name direction)}
      (some? msg) (assoc :message msg)
      (nil? msg) (assoc :text (strict/shown (str line))))))

;; --- displays

(defn display-session
  "The session of an agent's session/update that carries a display (a
  tool call content block with _meta relay/display), or nil for any other
  line."
  [line]
  (when (and (string? line) (str/includes? line "relay/display"))
    (let [msg (parse line)
          params (when (map? msg) (get msg "params"))
          u (when (map? params) (get params "update"))
          content (when (map? u) (get u "content"))]
      (when (and (= "session/update" (get msg "method")) (sequential? content)
                 (some #(and (map? %) (map? (get-in % ["content" "_meta" "relay/display"]))) content))
        (session-id (get params "sessionId"))))))

;; --- tool kinds

(def class-kinds
  "The ACP tool kind for each of relay's tool classes."
  {"read" "read" "write" "edit" "edit" "edit" "egress" "fetch" "act" "execute"})

(defn tool-kinds
  "The ACP kind of each tool in a manifest (relay.tools/manifest), by name:
  a tool's own :acp-kind when it has one, else its class's kind. A client
  uses the kind to show a call."
  [manifest]
  (into (sorted-map)
        (keep (fn [{tool-name :name :keys [class acp-kind]}]
                (when-let [kind (or acp-kind (class-kinds (some-> class name)))]
                  [tool-name kind])))
        manifest))

;; --- permission requests

(defn- preview-block
  "The text block of the preview in a tool call's content, if it has one:
  one whose _meta has relay/preview true."
  [content]
  (when (sequential? content)
    (some (fn [c]
            (let [b (when (map? c) (get c "content"))]
              (when (and (map? b) (= "text" (get b "type")) (string? (get b "text"))
                         (true? (get-in b ["_meta" "relay/preview"])))
                b)))
          content)))

(defn preview
  "The text of the preview in a tool call's content, if it has one."
  [content]
  (get (preview-block content) "text"))

(defn preview-labels
  "The labels relay gave the preview in a tool call's content: what its
  text carries, which is not what the session holds."
  [content]
  (let [ls (get-in (preview-block content) ["_meta" "relay/labels"])]
    (when (sequential? ls) (vec (filter string? ls)))))

(defn permission-request
  "An agent's session/request_permission, as relay keeps track of it, or
  nil for any other line: its id, session, tool call, the tool's name and
  input, the options offered, the session's labels if the agent sent
  them, and the preview, if the call's content has one."
  [line]
  (let [msg (when (string? line) (parse line))]
    (when (and (map? msg) (= "session/request_permission" (get msg "method")) (contains? msg "id"))
      (let [params (get msg "params")
            call (when (map? params) (get params "toolCall"))
            options (when (map? params) (get params "options"))]
        {:id (get msg "id")
         :session (when (map? params) (get params "sessionId"))
         :call (when (map? call) (get call "toolCallId"))
         :tool (when (map? call) (or (get call "name") (get call "title")))
         :input (when (map? call) (get call "rawInput"))
         :labels (let [ls (when (map? call) (get-in call ["_meta" "agentloop/labels"]))]
                   (when (sequential? ls) (vec ls)))
         :preview (when (map? call) (preview (get call "content")))
         :preview-labels (when (map? call) (preview-labels (get call "content")))
         ;; The arguments the operator fixed, which the input has in it.
         :fixed-args (let [f (when (map? call) (get-in call ["_meta" "agentloop/fixed_args"]))]
                       (when (and (map? f) (seq f)) f))
         :options (vec (filter map? (when (sequential? options) options)))}))))

(defn unreadable-permission-request
  "A line that may be a permission request but is not one relay can read,
  as much of a request as can be made of it: its id if one can be found,
  and :unreadable. That is a line the strict reader refuses that mentions
  request_permission, or a message whose method is
  session/request_permission that permission-request cannot read (it has
  no id). Nil for any other line. Such a line is refused, never passed
  on."
  [line]
  (when (and (string? line) (nil? (permission-request line)))
    (let [msg (message line)]
      (when (if msg
              (= "session/request_permission" (get msg "method"))
              (str/includes? line "request_permission"))
        {:id (when-not msg (line-id line)) :unreadable true :options []}))))

(defn stray-answer?
  "Whether a client's line is an answer (a result or an error, with an
  id, and no method), whichever request it claims to answer."
  [line]
  (let [msg (when (string? line) (parse line))]
    (and (map? msg) (contains? msg "id") (not (contains? msg "method"))
         (or (contains? msg "result") (contains? msg "error")))))

(defn- option-of-kind [{:keys [options]} kind]
  (some #(when (= kind (get % "kind")) (get % "optionId")) options))

(defn refusal
  "The answer that refuses a permission request: its reject_once option,
  or cancelled when it offers none."
  [{:keys [id] :as req}]
  {"jsonrpc" "2.0" "id" id
   "result" {"outcome" (if-let [opt (option-of-kind req "reject_once")]
                         {"outcome" "selected" "optionId" opt}
                         {"outcome" "cancelled"})}})

(defn answer-of
  "What a client's line says about an outstanding permission request: nil
  when the line is not an answer to it, else {:outcome kind :valid bool}.
  An answer is valid only when it selects an option the agent offered or
  cancels; anything else counts as a refusal."
  [req line]
  (let [msg (when (string? line) (parse line))]
    (when (and (map? msg) (not (contains? msg "method")) (contains? msg "id") (= (:id req) (get msg "id")))
      (let [outcome (get-in msg ["result" "outcome"])
            chosen (when (and (map? outcome) (= "selected" (get outcome "outcome")))
                     (some #(when (= (get outcome "optionId") (get % "optionId")) %) (:options req)))]
        (cond
          chosen {:outcome (get chosen "kind") :valid true}
          (and (map? outcome) (= "cancelled" (get outcome "outcome"))) {:outcome "cancelled" :valid true}
          :else {:outcome "reject_once" :valid false})))))

;; --- previews

(def preview-meta
  "The _meta that marks the preview in a permission request's
  toolCall.content, on the content item and on its text block, so a
  client can tell it from anything else there and show it as relay's.
  with-preview adds relay/labels, the labels of the preview's text."
  {"relay/preview" true})

(defn- fence
  "A code fence longer than any run of backticks in s, so s cannot end it."
  [s]
  (apply str (repeat (max 3 (inc (reduce max 0 (map count (re-seq #"`+" (str s)))))) "`")))

(defn- escape-hidden
  "s with every character that draws as nothing shown as \\u{XXXX}."
  [s]
  (apply str (map (fn [cp] (if (text/hidden? cp) (format "\\u{%04x}" cp) (String. (Character/toChars cp))))
                  (iterator-seq (.iterator (.codePoints (str s)))))))

(defn inert
  "Text an outsider may have written, made safe for a client that renders
  markdown and knows nothing of relay's _meta: hidden characters shown as
  \\u{XXXX}, links defanged, and the whole in a code fence nothing in it
  can close. No remote image in it is fetched and no link is live."
  [s]
  (let [t (text/defang (escape-hidden s))
        f (fence t)]
    (str f "text\n" t "\n" f)))

(defn with-inert-displays
  "An agent's session/update line with the text of every display block
  (_meta relay/display) made inert from the display it carries, or nil
  when the line has none. The display under _meta stays as it is, for a
  client that shows displays apart; any other client sees only the inert
  text. A display that cannot be read is made inert from the block's own
  text."
  [line]
  (when (and (string? line) (str/includes? line "relay/display"))
    (let [msg (parse line)
          content (when (map? msg) (get-in msg ["params" "update" "content"]))]
      (when (and (= "session/update" (get msg "method")) (sequential? content)
                 (some #(and (map? %) (contains? (get-in % ["content" "_meta"]) "relay/display")) content))
        (json/generate-string
         (assoc-in msg ["params" "update" "content"]
                   (mapv (fn [c]
                           (let [d (when (map? c) (get-in c ["content" "_meta" "relay/display"]))]
                             (if (and (map? c) (contains? (get-in c ["content" "_meta"]) "relay/display"))
                               (assoc-in c ["content" "text"]
                                         (inert (if (and (map? d) (string? (get d "title")) (string? (get d "body")))
                                                  (str (get d "title") "\n\n" (get d "body"))
                                                  (str (get-in c ["content" "text"])))))
                               c)))
                         content)))))))

(defn preview-markdown
  "The markdown a client is shown for a held call's preview (relay.core
  preview-of): what the tool's preview command read from the service, a
  note when the command failed, and the hosts of links to places the
  operator has not approved before (relay.core/approved-hosts), and the
  placeholders such as [Venue] still left in it (relay.text/placeholders).

  The preview's own text goes inside a code fence, and so do the notes
  after it, which quote it. It can hold text an outsider wrote (a
  campaign's body, say), and as live markdown it could show a remote
  image, which a client fetches before anyone approves anything, or a link
  whose text names another place than it goes."
  [{:keys [content is-error]} approved]
  (let [new-hosts (remove (set approved) (text/link-hosts content))
        odd (text/lookalikes (str content))
        ;; Characters that draw as nothing are shown as \u{XXXX}, so the
        ;; preview cannot hide text from the operator. Line breaks and
        ;; tabs stay, since the preview is markdown.
        shown (escape-hidden content)
        gaps (text/placeholders content)
        notes (str/join "\n" (concat (when (seq gaps)
                                       [(str "Still to fill in, and would go out as written: "
                                             (str/join ", " gaps))])
                                     (when (seq new-hosts)
                                       [(str "Links to places you have not approved before: "
                                             (str/join ", " new-hosts))])
                                     (when (seq odd)
                                       [(str "Characters that can pass for others: " (str/join "; " odd))])))
        notes (text/visible notes)
        fenced (fn [s] (str (fence s) "text\n" s "\n" (fence s)))]
    (str (when is-error "The preview command could not say what this call would do.\n\n")
         (fenced shown)
         (when-not (str/blank? notes) (str "\n\n---\n" (fenced notes))))))

(defn with-preview
  "A session/request_permission line with `markdown` added to the end of
  its toolCall.content as a text content block marked with preview-meta,
  and with `labels`, what the preview's text carries, as relay/labels.
  They are the preview's own, kept apart from agentloop/labels, which are
  the session's: a client renders the preview by them, and the session is
  no more contaminated for it. Everything else in the request is kept.
  Nil when the line is not such a request."
  [line markdown labels]
  (let [msg (when (string? line) (parse line))
        call (get-in msg ["params" "toolCall"])]
    (when (and (map? msg) (= "session/request_permission" (get msg "method")) (map? call))
      (let [content (get call "content")
            meta (assoc preview-meta "relay/labels" (vec labels))
            item {"type" "content" "_meta" meta
                  "content" {"type" "text" "text" (str markdown) "_meta" meta}}]
        (json/generate-string
         (assoc-in msg ["params" "toolCall" "content"]
                   (conj (if (sequential? content) (vec content) []) item)))))))
