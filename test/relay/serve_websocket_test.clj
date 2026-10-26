(ns relay.serve-websocket-test
  "relay serve's WebSocket listener (relay#66). Its tests take a free TCP
  port and then let the service bind it, so they run one at a time."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.acp-client :as client]
            [relay.core :as core]
            [relay.serve :as serve]
            [babashka.http-client.websocket :as ws]
            [relay.websocket]
            [relay.serve-support :refer [*dir* state wait-for said stop-service! with-service connect! new-session! prompt texts done!]]))

;; --- the WebSocket listener (relay#66)

(def ws-token "tok-for-tests-123")

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort s)))

(defn- ws-settings [port & [extra]]
  {:serve {:websocket (merge {:port port :token-command ["/bin/echo" ws-token]} extra)}})

(defn- port-open? [port]
  (try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] true)
       (catch Exception _ false)))

(defn- ws-connect!
  "A client over WebSocket (java.net.http's, an implementation that is not
  relay's), with the API of relay.acp-client."
  [port & {:keys [token] :or {token ws-token}}]
  (let [messages (atom []) lines (atom []) buf (StringBuilder.) closed (promise)
        sock (ws/websocket {:uri (str "ws://127.0.0.1:" port "/")
                            :headers {"Authorization" (str "Bearer " token)}
                            :on-message (fn [_ data last?]
                                          (locking buf
                                            (.append buf (str data))
                                            (when last?
                                              (let [l (str buf)]
                                                (.setLength buf 0)
                                                (swap! lines conj l)
                                                (swap! messages conj (json/parse-string l))))))
                            :on-close (fn [_ code _] (deliver closed code))
                            :on-error (fn [_ _] (deliver closed :error))})]
    {:messages messages :lines lines :next-id (atom 0) :ws sock :closed closed
     :send-fn (fn [text] (ws/send! sock text))}))

(defn- ws-close! [c] (try (ws/close! (:ws c)) (catch Exception _ nil)))

(defn- with-websocket-service
  "with-service with the WebSocket listener on a free port: (f service port)."
  [f & [extra]]
  (let [port (free-port)]
    (with-service
      (fn [service]
        (is (wait-for #(port-open? port) 15000) "the WebSocket listener is up")
        (f service port))
      nil (ws-settings port extra))))

(deftest ^:volley/serial a-phone-holds-a-conversation-over-websocket
  (with-websocket-service
    (fn [_ port]
      (let [c (ws-connect! port)]
        (try
          (let [init (client/request! c "initialize" {:protocolVersion 1 :clientCapabilities {}} 5000)]
            (is (= {"relay/hub" 1 "relay/keeps" true} (get-in init ["result" "_meta"]))))
          (is (= ["clerk" "hub"] (map #(get % "id") (get-in (client/request! c "_relay/roles" {} 5000) ["result" "roles"]))))
          (is (= "s1" (get-in (client/request! c "session/new" {:cwd *dir* :mcpServers [] :_meta {"relay/role" "clerk"}} 10000)
                              ["result" "sessionId"])))
          (is (= "end_turn" (get-in (client/request! c "session/prompt" (prompt "Hello") 10000) ["result" "stopReason"])))
          (is (= ["GOT" "REPLY"] (texts c)) "the reply, live, as the agent sent it")
          (is (client/await! c #(= "_relay/roles/changed" (get % "method")) 5000) "and the roster as it changes")
          (testing "every message is one JSON-RPC line"
            (is (every? #(and (map? (json/parse-string %)) (not (str/includes? % "\n"))) @(:lines c))))
          (finally (ws-close! c)))))))

(deftest ^:volley/serial a-phone-and-a-terminal-share-one-conversation
  (with-websocket-service
    (fn [_ port]
      (let [a (connect! :role "clerk")
            b (ws-connect! port)]
        (try
          (new-session! a "clerk")
          (client/request! b "initialize" {:protocolVersion 1} 5000)
          (is (= {"sessionId" "s1" "_meta" {"relay/attached" true}}
                 (get (client/request! b "session/new" {:cwd *dir* :mcpServers [] :_meta {"relay/role" "clerk"}} 5000) "result")))
          (let [id (client/request-async! a "session/prompt" (prompt "Hello from the terminal"))]
            (is (= "end_turn" (get-in (client/response a id 5000) ["result" "stopReason"])))
            (is (some #{"Hello from the terminal"} (texts b)) "the phone sees the terminal's prompt")
            (is (client/await! b #(= "REPLY" (get-in % ["params" "update" "content" "text"])) 5000)
                "and the reply"))
          (testing "and the other way: the phone prompts, the terminal sees it"
            (is (= "end_turn" (get-in (client/request! b "session/prompt" (prompt "From the phone") 10000) ["result" "stopReason"])))
            (is (client/await! a #(= "From the phone" (get-in % ["params" "update" "content" "text"])) 5000)))
          (finally (ws-close! b) (done! a)))))))

(deftest ^:volley/serial a-phone-s-session-new-without-a-role-goes-to-the-default-role
  (with-websocket-service
    (fn [_ port]
      (let [c (ws-connect! port)]
        (try
          (client/request! c "initialize" {:protocolVersion 1} 5000)
          (is (= "s1" (get-in (client/request! c "session/new" {:cwd *dir* :mcpServers []} 10000) ["result" "sessionId"])))
          (is (core/open-conversation (state) "clerk") "the configured default role")
          (is (not (core/open-conversation (state) "hub")))
          (finally (ws-close! c)))))
    {:default-role "clerk"}))

(defn- raw-request
  "Sends a request's head to the listener and returns everything it
  answers until it closes."
  [port head]
  (with-open [s (java.net.Socket. "127.0.0.1" (int port))]
    (.setSoTimeout s 5000)
    (.write (.getOutputStream s) (.getBytes (str head "\r\n\r\n") "ISO-8859-1"))
    (.flush (.getOutputStream s))
    (String. (.readAllBytes (.getInputStream s)) "ISO-8859-1")))

(defn- upgrade-head [& extra]
  (str/join "\r\n" (concat ["GET / HTTP/1.1" "Host: 127.0.0.1" "Upgrade: websocket" "Connection: Upgrade"
                            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" "Sec-WebSocket-Version: 13"]
                           extra)))

(defn- upgraded
  "A raw socket past the upgrade, and its streams."
  [port]
  (let [s (java.net.Socket. "127.0.0.1" (int port))
        in (java.io.DataInputStream. (.getInputStream s))]
    (.setSoTimeout s 5000)
    (.write (.getOutputStream s) (.getBytes (str (upgrade-head (str "Authorization: Bearer " ws-token)) "\r\n\r\n") "ISO-8859-1"))
    (is (str/starts-with? (relay.websocket/read-head in) "HTTP/1.1 101 "))
    {:sock s :in in :out (.getOutputStream s)}))

(defn- close-code
  "The status of the close frame the listener sends next, and whether
  the connection then ends."
  [{:keys [^java.io.DataInputStream in]}]
  (let [b0 (.readUnsignedByte in) n (.readUnsignedByte in) code (.readUnsignedShort in)]
    [(= 0x88 b0) (= 2 n) code (try (= -1 (.read in)) (catch java.net.SocketException _ true))]))

(deftest ^:volley/serial the-listener-refuses-before-the-upgrade-and-closes-on-what-it-does-not-take
  (with-websocket-service
    (fn [_ port]
      (testing "refusals carry their status and nothing else"
        (doseq [[what head status] [["no token" (upgrade-head) 401]
                                    ["a wrong token" (upgrade-head "Authorization: Bearer tok-for-tests-12") 401]
                                    ["an Origin, even with the token" (upgrade-head (str "Authorization: Bearer " ws-token)
                                                                                    "Origin: http://localhost:3000") 403]
                                    ["a plain request" "GET / HTTP/1.1\r\nHost: 127.0.0.1" 404]
                                    ["a plain request with the token" (str "GET /x HTTP/1.1\r\nAuthorization: Bearer " ws-token) 404]]]
          (let [answer (raw-request port head)]
            (is (str/starts-with? answer (str "HTTP/1.1 " status " ")) (str what ": " answer))
            (is (str/ends-with? answer "Content-Length: 0\r\nConnection: close\r\n\r\n") what)
            (is (not (str/includes? (str/lower-case answer) "relay")) what))))
      (testing "java.net.http's client is refused without the token"
        (is (thrown? Exception (ws-connect! port :token "wrong"))))
      (testing "a binary frame closes the socket"
        (let [c (upgraded port)]
          (try (.write ^java.io.OutputStream (:out c) (relay.websocket/frame-bytes :binary (.getBytes "{}") (byte-array [1 2 3 4]) true))
               (is (= [true true 1003 true] (close-code c)))
               (finally (.close ^java.net.Socket (:sock c))))))
      (testing "a message over 8 MB closes the socket, before relay reads it"
        (let [c (upgraded port)
              ;; Only the frame's head: its length says 8 MB and one byte.
              head (java.util.Arrays/copyOfRange (relay.websocket/frame-bytes :text (byte-array (inc (* 8 1024 1024))) (byte-array [1 2 3 4]) true) 0 14)]
          (try (.write ^java.io.OutputStream (:out c) head)
               (is (= [true true 1009 true] (close-code c)))
               (finally (.close ^java.net.Socket (:sock c))))))
      (testing "the listener still serves after all that"
        (let [c (ws-connect! port)]
          (try (is (some? (client/request! c "initialize" {:protocolVersion 1} 5000)))
               (finally (ws-close! c))))))))

(defn- listening
  "The TCP addresses a process listens on, as lsof gives them."
  [proc]
  (let [{:keys [out]} (p/shell {:out :string :err :string :continue true}
                               "lsof" "-nP" "-a" "-p" (str (.pid ^Process (:proc proc))) "-iTCP" "-sTCP:LISTEN" "-Fn")]
    (vec (keep #(when (str/starts-with? % "n") (subs % 1)) (str/split-lines out)))))

(deftest ^:volley/serial the-listener-binds-only-the-loopback-address
  (with-websocket-service
    (fn [service port]
      (is (= [(str "127.0.0.1:" port)] (listening service)))
      (testing "another address of this Mac does not reach it"
        (doseq [addr (->> (re-seq #"inet (\d+\.\d+\.\d+\.\d+)" (:out (p/shell {:out :string} "ifconfig")))
                          (map second)
                          (remove #(str/starts-with? % "127."))
                          (take 2))]
          (is (thrown? java.io.IOException (with-open [_ (java.net.Socket. ^String addr (int port))]))
              addr))))))

(deftest ^:volley/serial no-listener-without-settings-or-a-token
  (testing "no :websocket, no listener"
    (with-service
      (fn [service]
        (is (= [] (listening service)))
        (is (not (str/includes? (:err (stop-service! service)) "WebSocket"))))))
  (doseq [[what command why] [["a token command that fails" ["/bin/sh" "-c" "echo nope >&2; exit 3"] "exited 3: nope"]
                              ["an empty token" ["/usr/bin/true"] "printed no token"]]]
    (testing what
      (let [port (free-port)]
        (with-service
          (fn [service]
            (Thread/sleep 300)
            (is (not (port-open? port)))
            (is (= [] (listening service)))
            (testing "and the service still runs for the socket"
              (let [a (connect! :role "clerk")]
                (try (is (= "s1" (get-in (new-session! a "clerk") ["result" "sessionId"])))
                     (finally (done! a)))))
            (let [err (:err (stop-service! service))]
              (is (str/includes? err (str "no WebSocket listener: its :token-command " why)) err)))
          nil (ws-settings port {:token-command command}))))))

(deftest the-websocket-and-the-socket-share-the-connection-cap
  (let [eng (#'serve/engine nil :service {:max-conns 2})
        stream #(#'serve/add-connection! eng {:in (java.io.PipedInputStream. (java.io.PipedOutputStream.))
                                              :out (java.io.ByteArrayOutputStream.) :origin :socket})
        other #(#'serve/open-connection! eng {:write! (constantly true) :origin :websocket})]
    (is (some? (stream)))
    (is (some? (other)))
    (is (nil? (stream)) "the socket's third is refused")
    (is (nil? (other)) "and so is the listener's")))

(deftest a-connection-whose-client-stops-reading-is-closed-past-the-backlog
  ;; The WebSocket's writer blocks on a client that does not read, as the
  ;; socket's does, so what waits for it is bounded the same way.
  (let [eng (#'serve/engine nil :service)
        closed (promise)
        stuck (promise)]
    (with-redefs [serve/write-backlog 1000]
      (let [{:keys [send!]} (#'serve/open-connection! eng {:write! (fn [_] @stuck true) :origin :websocket
                                                           :close-transport! #(deliver closed true)})]
        (dotimes [_ 20] (send! (apply str (repeat 100 "x"))))
        (is (deref closed 2000 false) "relay gave up on it")
        (deliver stuck true)))))

;; --- Codex round 1 (relay#66)

(defn- in-process-listener
  "The WebSocket listener of an engine with no system, on a port the OS
  picks: [engine port server]."
  []
  (let [eng (#'serve/engine nil :service)
        server (#'serve/websocket-listener! eng {:port 0 :token ws-token :allowed-origins [] :default-role "hub"})]
    [eng (.getLocalPort ^java.net.ServerSocket server) server]))

(deftest ^:volley/serial whatever-ends-a-websocket-reader-frees-its-place
  (testing "a reader that throws"
    (let [[eng port server] (in-process-listener)]
      (try
        (with-redefs [relay.websocket/read-message (fn [& _] (throw (RuntimeException. "boom")))]
          (let [c (upgraded port)]
            (try
              (is (= -1 (try (.read ^java.io.InputStream (:in c)) (catch java.io.IOException _ -1))) "it is closed")
              (is (wait-for #(empty? @(:conns eng)) 5000) "and its place is free")
              (finally (.close ^java.net.Socket (:sock c))))))
        (finally (.close ^java.net.ServerSocket server)))))
  (testing "a length that would overflow, after a fragment"
    (let [[eng port server] (in-process-listener)]
      (try
        (let [c (upgraded port)
              out ^java.io.OutputStream (:out c)
              m (byte-array [1 2 3 4])
              header (doto (java.nio.ByteBuffer/allocate 14)
                       (.put (unchecked-byte 0x80)) (.put (unchecked-byte (bit-or 0x80 127)))
                       (.putLong Long/MAX_VALUE) (.put m))]
          (try
            (.write out (relay.websocket/frame-bytes :text (.getBytes "x") m false))
            (.write out (.array header))
            (.flush out)
            (is (= [true true 1009 true] (close-code c)))
            (is (wait-for #(empty? @(:conns eng)) 5000) "its place is free")
            (finally (.close ^java.net.Socket (:sock c)))))
        (finally (.close ^java.net.ServerSocket server))))))

(deftest ^:volley/serial a-message-that-stalls-anywhere-is-stopped-at-its-deadline
  (let [m (byte-array [1 2 3 4])
        frame (relay.websocket/frame-bytes :text (.getBytes "{\"jsonrpc\":\"2.0\",\"method\":\"x\"}") m true)
        long-frame (relay.websocket/frame-bytes :text (byte-array 300) m true)
        fragment (relay.websocket/frame-bytes :text (.getBytes "x") m false)]
    (doseq [[what ^bytes sent] [["partway through the first frame's header" (java.util.Arrays/copyOfRange long-frame 0 3)]
                                ["partway through its mask" (java.util.Arrays/copyOfRange frame 0 4)]
                                ["partway through a single-frame message's payload" (java.util.Arrays/copyOfRange long-frame 0 100)]
                                ["after a whole fragment, before the next" fragment]]]
      (testing what
        (let [[eng port server] (in-process-listener)]
          (try
            (with-redefs [relay.websocket/max-message-ms 400]
              (let [c (upgraded port)
                    out ^java.io.OutputStream (:out c)
                    t0 (System/currentTimeMillis)]
                (try
                  (.write out sent)
                  (.flush out)
                  (is (= -1 (try (.read ^java.io.InputStream (:in c)) (catch java.io.IOException _ -1))) "closed")
                  (let [took (- (System/currentTimeMillis) t0)]
                    (is (<= 350 took 3000) (str "at the deadline, after " took " ms")))
                  (is (wait-for #(empty? @(:conns eng)) 5000) "and its place is free")
                  (finally (.close ^java.net.Socket (:sock c))))))
            (finally (.close ^java.net.ServerSocket server))))))))

(deftest ^:volley/serial an-idle-connection-between-messages-is-never-closed
  (let [[eng port server] (in-process-listener)
        m (byte-array [1 2 3 4])]
    (try
      (with-redefs [relay.websocket/max-message-ms 200]
        (let [c (upgraded port)
              out ^java.io.OutputStream (:out c)]
          (try
            (.write out (relay.websocket/frame-bytes :text (.getBytes "{\"jsonrpc\":\"2.0\",\"method\":\"x\"}") m true))
            (.write out (relay.websocket/frame-bytes :ping (.getBytes "p") m true))
            (.flush out)
            (Thread/sleep 1500)
            (is (= 1 (count @(:conns eng))) "still connected after many deadlines")
            (testing "and it still takes a message"
              (.write out (relay.websocket/frame-bytes :ping (.getBytes "q") m true))
              (.flush out)
              (let [in ^java.io.DataInputStream (:in c)]
                ;; The two pongs, in order.
                (is (= [0x8a 1 (int \p)] [(.readUnsignedByte in) (.readUnsignedByte in) (.readUnsignedByte in)]))
                (is (= [0x8a 1 (int \q)] [(.readUnsignedByte in) (.readUnsignedByte in) (.readUnsignedByte in)]))))
            (finally (.close ^java.net.Socket (:sock c))))))
      (finally (.close ^java.net.ServerSocket server)))))

(deftest ^:volley/serial a-token-command-that-hangs-or-says-too-much-gives-no-listener
  (let [tmp (str (fs/create-temp-dir {:prefix "relay-token"}))
        pidf (str tmp "/sleeper.pid")]
    (try
      (doseq [[what command why] [["one that hangs" ["/bin/sh" "-c" (str "sleep 600 & echo $! > " pidf "; wait")]
                                   "did not finish within 10 seconds"]
                                  ["one that prints a lot" ["/usr/bin/yes"] "printed more than 4096 bytes"]]]
        (testing what
          (let [port (free-port)]
            (with-service
              (fn [service]
                (testing "the socket works while the token command runs"
                  (let [a (connect! :role "clerk")]
                    (try (is (= "s1" (get-in (new-session! a "clerk") ["result" "sessionId"])))
                         (finally (done! a)))))
                (let [line (str "no WebSocket listener: its :token-command " why)]
                  (is (wait-for #(str/includes? (said service) line) 20000) (said service))
                  (is (not (port-open? port)))
                  (is (= [] (listening service)))
                  (let [err (:err (stop-service! service))]
                    (is (str/includes? err line) err))))
              nil (ws-settings port {:token-command command})))))
      (testing "what the hanging command started was killed too"
        (let [pid (parse-long (str/trim (slurp pidf)))]
          (is (wait-for #(not (.isPresent (java.lang.ProcessHandle/of pid))) 5000))))
      (finally (fs/delete-tree tmp)))))
