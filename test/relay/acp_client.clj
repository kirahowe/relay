(ns relay.acp-client
  "A small ACP client for tests. It starts an agent command, sends JSON-RPC
  requests on its stdin, and keeps every message the agent writes. It
  answers the agent's own requests: a permission request is cancelled,
  anything else is method-not-found."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(defn- send! [{:keys [^java.io.Writer writer]} msg]
  (locking writer
    (.write writer (str (json/generate-string msg) "\n"))
    (.flush writer)))

(defn- answer [{:strs [id method]}]
  (if (= "session/request_permission" method)
    {:jsonrpc "2.0" :id id :result {:outcome {:outcome "cancelled"}}}
    {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "No method " method)}}))

(defn start!
  "Starts the agent. Returns a client: {:proc :messages :out-bytes}, where
  :messages is an atom of every message the agent wrote, parsed with
  string keys, and :lines every raw stdout line."
  [argv opts]
  (let [proc (p/process (vec argv) (merge {:err :string} opts))
        client {:proc proc
                :writer (io/writer (:in proc))
                :messages (atom [])
                :lines (atom [])
                :next-id (atom 0)}
        reader (Thread.
                ^Runnable
                (fn []
                  (try
                    (doseq [line (line-seq (io/reader (:out proc)))]
                      (swap! (:lines client) conj line)
                      (let [msg (try (json/parse-string line) (catch Exception _ nil))]
                        (swap! (:messages client) conj msg)
                        (when (and (map? msg) (get msg "method") (contains? msg "id"))
                          (try (send! client (answer msg)) (catch java.io.IOException _ nil)))))
                    (catch java.io.IOException _ nil))))]
    (.setDaemon reader true)
    (.start reader)
    (assoc client :reader reader)))

(defn await!
  "The first message satisfying pred, waiting up to timeout-ms. Nil when
  none came."
  [{:keys [messages]} pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (first (filter #(and (map? %) (pred %)) @messages))
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 20)
            (recur))))))

(defn request!
  "Sends a request and returns its response, or nil after timeout-ms."
  ([client method params] (request! client method params 30000))
  ([client method params timeout-ms]
   (let [id (swap! (:next-id client) inc)]
     (send! client {:jsonrpc "2.0" :id id :method method :params params})
     (await! client #(and (= id (get % "id")) (not (contains? % "method"))) timeout-ms))))

(defn request-async!
  "Sends a request without waiting. Returns its id."
  [client method params]
  (let [id (swap! (:next-id client) inc)]
    (send! client {:jsonrpc "2.0" :id id :method method :params params})
    id))

(defn response
  "The response to request id, waiting up to timeout-ms."
  [client id timeout-ms]
  (await! client #(and (= id (get % "id")) (not (contains? % "method"))) timeout-ms))

(defn updates
  "Every session/update the agent has sent, as the update maps."
  [{:keys [messages]}]
  (for [m @messages :when (and (map? m) (= "session/update" (get m "method")))]
    (get-in m ["params" "update"])))

(defn close-input!
  "Closes the agent's stdin, as a client that has finished."
  [{:keys [^java.io.Writer writer]}]
  (try (.close writer) (catch java.io.IOException _ nil)))

(defn sever!
  "Closes both pipes at once, as the OS does when the client dies: the
  agent reads end of input, and its next write fails."
  [{:keys [proc] :as client}]
  (close-input! client)
  (try (.close ^java.io.InputStream (:out proc)) (catch java.io.IOException _ nil)))

(defn pid [{:keys [proc]}] (.pid ^Process (:proc proc)))

(defn wait!
  "The agent's exit code, waiting up to timeout-ms, or :timeout."
  [{:keys [proc]} timeout-ms]
  (if (.waitFor ^Process (:proc proc) timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
    (.exitValue ^Process (:proc proc))
    :timeout))

(defn handshake!
  "initialize then session/new. Returns the session id."
  [client cwd]
  (let [init (request! client "initialize" {:protocolVersion 1
                                            :clientCapabilities {:fs {:readTextFile false
                                                                      :writeTextFile false}}})]
    (when-not (get init "result") (throw (ex-info "initialize failed" {:response init})))
    (let [new (request! client "session/new" {:cwd cwd :mcpServers []})]
      (or (get-in new ["result" "sessionId"])
          (throw (ex-info "session/new failed" {:response new}))))))

(defn prompt-params [session text]
  {:sessionId session :prompt [{:type "text" :text text}]})
