(ns relay.acp
  "The pure half of `relay acp`. relay sits between an ACP client and the
  role's ACP agent and copies bytes both ways unchanged. On the side, it
  reads the traffic line by line to learn what the conversation's wake
  should record: the sessions it used, and how its last turn ended.

  Nothing here does I/O. relay.dispatch/proxy! and relay.serve are the shell.

  A direction is :client (client to agent) or :agent (agent to client).

  The shell reads each complete line once (`line`) and passes that value,
  its text and its message together, to every decision about it. Each
  function here that takes a line also takes plain text, which it reads
  itself."
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

(defrecord Line [text message])

(defn line
  "A line read once: {:text :message}, its text (`text` for bytes) and the
  message relay's strict reader (relay.json) makes of it, the JSON object
  it is or nil. relay acp asks many things of one line (the screen,
  displays, the observer, the trace), and the strict reader
  is slow enough on a large line that it must read it only once: the
  shell makes this value and every decision takes it. A line already read
  comes back as it is; anything but text or bytes has neither."
  [x]
  (cond
    (instance? Line x) x
    (string? x) (->Line x (strict/object x))
    (bytes? x) (line (text x))
    :else (->Line nil nil)))

(defn message
  "A line as a message: the JSON object it is, read by relay's strict
  reader (relay.json), or nil when it is not one. Every reader of ACP in
  relay, agentloop and relay-tui reads by the same rule, so a line that
  passes means the same to all of them. A line already read (`line`) is
  not read again."
  [x]
  (:message (line x)))

(defn kind
  "What a message is, as every reader of ACP in relay takes it: :request
  (an id and a method), :notification (a method and no id), :response
  (an id and no method), or nil. A method is a non-empty string: the ACP
  SDK reads a missing, null or empty method alike, so a message with an
  id and an empty method is a response. The proxy and the hub both
  classify by this, so they never disagree about a line."
  [msg]
  (when (map? msg)
    (let [m (get msg "method")
          method? (and (string? m) (not= "" m))]
      (cond method? (if (contains? msg "id") :request :notification)
            (contains? msg "id") :response))))

(defn- text-of
  "A line's text, without reading it as JSON: for a Line its text, else
  the string."
  [x]
  (if (instance? Line x) (:text x) (when (some? x) (str x))))

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
  (some-> (re-find id-pattern (str (text-of line))) second (strict/parse-or nil)))

(defn- session-id [x]
  (when (and (string? x) (not (str/blank? x))) x))

(defn- used [obs id]
  (if (or (nil? id) (some #{id} (:sessions obs)))
    obs
    (update obs :sessions conj id)))

(defn- client-said [obs msg]
  (let [{id "id" method "method" params "params"} msg]
    (if (= :request (kind msg))
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
        request (when (= :response (kind msg))
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
  (let [msg (message line)]
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
  [direction x]
  (let [{:keys [text message]} (line x)]
    (cond-> {:from (name direction)}
      (some? message) (assoc :message message)
      (nil? message) (assoc :text (strict/shown (str text))))))

;; --- displays

(defn display-session
  "The session of an agent's session/update that carries a display (a
  tool call content block with _meta relay/display), or nil for any other
  line."
  [line]
  (when (some-> (text-of line) (str/includes? "relay/display"))
    (let [msg (message line)
          params (when (map? msg) (get msg "params"))
          u (when (map? params) (get params "update"))
          content (when (map? u) (get u "content"))]
      (when (and (= "session/update" (get msg "method")) (sequential? content)
                 (some #(and (map? %) (map? (get-in % ["content" "_meta" "relay/display"]))) content))
        (session-id (get params "sessionId"))))))

;; --- tool kinds

(def class-kinds
  "The ACP tool kind for each of relay's tool classes."
  {"read" "read" "write" "edit" "edit" "edit" "egress" "fetch"})

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

(defn id-key
  "A JSON-RPC id as a key that two ids share exactly when a client
  matching responses to its requests takes them for the same id: numbers
  by value (9, 9.0 and 9e0 are one id to the ACP SDK and to JavaScript),
  strings by their text, and null. Nil for what cannot be an id."
  [id]
  (cond (nil? id) [:null]
        (string? id) [:string id]
        (number? id) [:number (str (.stripTrailingZeros (bigdec id)))]
        :else nil))

;; --- outside text, made inert

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
  (when (some-> (text-of line) (str/includes? "relay/display"))
    (let [msg (message line)
          content (when (map? msg) (get-in msg ["params" "update" "content"]))]
      (when (and (= "session/update" (get msg "method")) (sequential? content)
                 (some #(and (map? %) (contains? (get-in % ["content" "_meta"]) "relay/display")) content))
        (strict/generate
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
