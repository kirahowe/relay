(ns relay.acp
  "The pure half of `relay acp`. relay sits between an ACP client and the
  role's ACP agent and copies bytes both ways unchanged. On the side, it
  reads the traffic line by line to learn what the conversation's wake
  should record: the sessions it used, and how its last turn ended.

  Nothing here does I/O. relay.dispatch/acp! is the shell.

  A direction is :client (client to agent) or :agent (agent to client)."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
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
  "A line's bytes as text. Bytes that are not UTF-8 become U+FFFD, which
  can only make a line fail to parse, never change what a valid one says."
  [^bytes line]
  (String. line java.nio.charset.StandardCharsets/UTF_8))

;; --- what the traffic says

(def observer
  "What has been learned so far: the client's requests still waiting for an
  answer (id to method, and the session a load or resume names), the
  sessions the conversation used, in the order it first used them, and
  how the last answered request ended."
  {:requests {} :sessions [] :last nil})

(defn- parse [line]
  (try (json/parse-string line) (catch Exception _ nil)))

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

(defn- agent-said [obs msg]
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

(defn observe
  "The observer after one complete line went by in a direction. Total: a
  line that is not JSON, or JSON that is not a message relay understands,
  changes nothing.

  Sessions come from the answers: the id a successful session/new returns,
  and the id a successful session/load or session/resume named. A load the
  agent refused used no session. Notifications that mention a session
  (session/update, say) add nothing."
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

(defn report
  "The report relay.dispatch/conversation-end-args takes, from what was
  observed and the agent's exit code."
  [obs exit]
  {:exit exit :sessions (:sessions obs) :said (said obs)})

(defn trace-entry
  "One line of a --trace file: who sent the line, and the message, or the
  raw text when the line is not JSON."
  [direction line]
  (let [msg (parse line)]
    (cond-> {:from (name direction)}
      (some? msg) (assoc :message msg)
      (nil? msg) (assoc :text line))))

;; --- tool kinds

(def class-kinds
  "The ACP tool kind for each of relay's tool classes."
  {"read" "read" "write" "edit" "egress" "fetch" "act" "execute"})

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

(defn permission-request
  "An agent's session/request_permission, as relay keeps track of it, or
  nil for any other line: its id, session, tool call, the tool's name and
  input, the options offered, and the session's labels if the agent sent
  them."
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
         :options (vec (filter map? (when (sequential? options) options)))}))))

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
