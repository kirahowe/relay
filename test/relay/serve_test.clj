(ns relay.serve-test
  "relay serve end to end over its Unix socket: a service of its own for
  each test, clients through relay acp's bridge, and a fake agent behind
  each conversation (relay.serve-support). Each test's socket, run
  directory and data directory are its own, so the tests run in parallel
  with the rest of the suite."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.acp-client :as client]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.hub :as hub]
            [relay.hub-test :as ht]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.serve :as serve]
            [relay.test-support :as support]
            [relay.serve-support :refer [deaf-agent-script *dir* *run-dir* *sys* *socket* data state events wait-for start-service! stop-service! with-service connect! new-session! prompt texts methods-of done!]]))

(deftest two-clients-share-one-conversation
  (with-service
    (fn [_]
      (let [a (connect! :role "clerk")
            b (connect!)]
        (try
          (is (= "s1" (get-in (new-session! a "clerk") ["result" "sessionId"])))
          (let [r (new-session! b "clerk")]
            (is (= {"sessionId" "s1" "_meta" {"relay/attached" true}} (get r "result")) "the second attaches")
            (is (client/await! b #(= "_relay/replayed" (get % "method")) 5000)))
          (testing "one prompts; the other sees the prompt and the reply"
            (is (= "end_turn" (get-in (client/request! a "session/prompt" (prompt "Hello")) ["result" "stopReason"])))
            (is (client/await! b #(= "REPLY" (get-in % ["params" "update" "content" "text"])) 5000))
            (is (some #{"Hello"} (texts b)))
            (is (not (some #{"Hello"} (texts a))) "the sender is not sent its own prompt"))
          (testing "the agent cannot pass for relay"
            (is (not-any? #(str/includes? % "FORGED") (concat @(:lines a) @(:lines b)))))
          (testing "one conversation, one wake"
            (is (= 1 (count (filter :chat (vals (:wakes (state))))))))
          (finally (done! a b)))))))

(deftest a-turn-outlives-its-client-and-a-new-client-gets-the-history
  (with-service
    (fn [_]
      (let [a (connect! :role "clerk")]
        (new-session! a "clerk")
        (client/request-async! a "session/prompt" (prompt "SLOW job"))
        (Thread/sleep 300)
        (done! a)
        (is (wait-for #(some (fn [e] (= "conversation/turn-ended" (:type e))) (events)) 15000)
            "the turn finished with no client attached")
        (is (core/open-conversation (state) "clerk") "and the conversation is still open")
        (let [c (connect!)]
          (try
            (client/request! c "initialize" {:protocolVersion 1})
            (let [r (client/request! c "session/load" {:sessionId "s1" :cwd *dir* :mcpServers []})]
              (is (= {} (get r "result")) (pr-str r)))
            (testing "the replay: the prompt, everything the agent said, then _relay/replayed, then the answer"
              ;; The agent's two chunks come back merged into one.
              (is (= ["SLOW job" "GOTREPLY"] (texts c)))
              (let [ms (vec @(:messages c))
                    replayed (.indexOf ^java.util.List (mapv #(get % "method") ms) "_relay/replayed")
                    answered (first (keep-indexed #(when (= 2 (get %2 "id")) %1) ms))]
                (is (< -1 replayed answered))))
            (finally (done! c))))))))

(deftest no-client-is-ever-asked-to-approve-anything
  (with-service
    (fn [_]
      (let [a (connect! :role "clerk")
            b (connect!)]
        (try
          (new-session! a "clerk")
          (new-session! b "clerk")
          (let [id (client/request-async! a "session/prompt" (prompt "PERM please"))]
            (is (= "end_turn" (get-in (client/response a id 10000) ["result" "stopReason"])))
            (testing "the agent was told the request was cancelled"
              (is (some #{"PERMITTED cancelled"} (texts a))))
            (testing "and no client saw it"
              (is (not (some #{"session/request_permission"} (methods-of a))))
              (is (not (some #{"session/request_permission"} (methods-of b)))))
            (testing "nor was the operator told to answer one"
              (is (not-any? #(re-find #"permission" (str (:origin %))) (vals (:messages (state)))))))
          (finally (done! a b)))))))

(deftest a-prompt-queues-behind-a-dispatched-wake
  (with-service
    (fn [_]
      (let [a (connect! :role "clerk")]
        (try
          (new-session! a "clerk")
          (client/request! a "_relay/roles" {})
          (let [started (inbound/submit! *sys* {:type :wake/start :actor "operator"
                                                :args (merge {:role "clerk" :note "Busy."} (dispatch/current-process))})
                wake (:seq (first started))
                id (client/request-async! a "session/prompt" (prompt "Queued"))]
            (is (client/await! a (fn [m] (and (= "_relay/roles/changed" (get m "method"))
                                              (some #(= "queued" (get-in % ["conversation" "turn"])) (get-in m ["params" "roles"]))))
                               5000)
                "the roster says it is queued")
            (is (nil? (client/response a id 500)))
            (inbound/submit! *sys* {:type :wake/end :actor "dispatcher" :args {:wake wake :outcome "ran" :said ""}})
            (is (= "end_turn" (get-in (client/response a id 10000) ["result" "stopReason"])) "it runs once the wake ends"))
          (finally (done! a)))))))

(deftest the-operator-ends-a-conversation
  (with-service
    (fn [_]
      (let [a (connect! :role "clerk")]
        (try
          (new-session! a "clerk")
          (let [r (client/request! a "_relay/conversations/end" {:sessionId "s1"} 20000)]
            (is (= {} (get r "result")) (pr-str r))
            (is (nil? (core/open-conversation (state) "clerk")) "answered once the wake has ended"))
          (testing "a new session opens a new conversation"
            (let [r (client/request! a "session/new" {:cwd *dir* :mcpServers [] :_meta {"relay/role" "clerk"}})]
              (is (= "s1" (get-in r ["result" "sessionId"])))
              (is (= 2 (count (filter :chat (vals (:wakes (state)))))))))
          (finally (done! a)))))))

(deftest a-restart-ends-what-the-old-service-held
  (with-service
    (fn [service]
      (testing "SIGTERM ends each conversation, a turn in progress included, and records it"
        (let [a (connect! :role "clerk")]
          (new-session! a "clerk")
          (client/request-async! a "session/prompt" (prompt "STUCK"))
          (is (wait-for #(core/busy? (state) "clerk") 5000))
          (stop-service! service)
          (let [wake (first (filter :chat (vals (:wakes (state)))))]
            (is (= :ended (:status wake)))
            (is (some #(and (= "conversation/turn-ended" (:type %)) (= "ended" (get-in % [:data :stop-reason]))) (events))))
          (done! a)))
      (testing "a service that died leaves its conversations to the next, which ends them"
        (let [service (start-service!)
              a (connect! :role "clerk")]
          (new-session! a "clerk")
          (is (= :running (:status (core/open-conversation (state) "clerk"))))
          (.destroyForcibly ^Process (:proc service))
          (deref service 10000 nil)
          (done! a)
          (let [service (start-service!)
                b (connect! :role "clerk")]
            (try
              (is (nil? (core/open-conversation (state) "clerk")) "the lost conversation was ended")
              (is (= "s1" (get-in (new-session! b "clerk") ["result" "sessionId"])) "and a new one opens")
              (is (= 3 (count (filter :chat (vals (:wakes (state)))))))
              (finally (done! b) (stop-service! service)))))))))

(deftest one-service-per-data-directory
  (with-service
    (fn [_]
      (doseq [socket [*socket* (str (fs/parent *socket*) "/other.sock")]]
        (testing (str "on " socket)
          (let [proc (p/process [support/relay-bin "serve" "--data" (data) "--socket" socket "--run-dir" *run-dir*]
                                {:out :string :err :string})
                r (deref proc 15000 :timeout)]
            (when (= :timeout r) (.destroy ^Process (:proc proc)) (deref proc 15000 nil))
            (is (not= :timeout r) "it refuses at once")
            (is (= 1 (:exit r)))
            (is (str/includes? (str (:err r)) "already running"))))))))

(deftest ending-a-conversation-stops-an-agent-that-stopped-reading
  ;; Codex round 1: stopping only queued an end of input behind a prompt
  ;; the agent would never read, so the end hung and the role stayed taken.
  (with-service
    (fn [_]
      (let [a (connect! :role "clerk")]
        (try
          (new-session! a "clerk")
          (client/request-async! a "session/prompt" (prompt (apply str (repeat (* 1024 1024) "x"))))
          (Thread/sleep 500)
          (let [t0 (System/currentTimeMillis)
                r (client/request! a "_relay/conversations/end" {:sessionId "s1"} 30000)]
            (is (= {} (get r "result")) (pr-str r))
            (is (< (- (System/currentTimeMillis) t0) (+ dispatch/stop-grace-ms 10000)) "within the grace and a margin")
            (is (nil? (core/open-conversation (state) "clerk")) "the wake has ended"))
          (finally (done! a)))))
    deaf-agent-script))

(deftest the-socket-path-is-short-and-the-data-directory-s-own
  (binding [serve/*run-dir* "/Users/someone/.relay/run"]
    (let [a (serve/socket-path "/Users/someone/Sync/relay" nil)]
      (is (= a (serve/socket-path "/Users/someone/Sync/relay/." nil)))
      (is (not= a (serve/socket-path "/Users/someone/other" nil)))
      (is (< (count a) 104))
      (is (= "/tmp/x.sock" (serve/socket-path "/d" "/tmp/x.sock"))))))

;; --- bounds (Codex round 1)

(deftest a-sender-gives-up-past-its-backlog
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        over (atom 0)
        {:keys [send! taken!]} (#'serve/bounded-sender q 100 #(swap! over inc))]
    (send! (apply str (repeat 60 "a")))
    (is (zero? @over))
    (taken! 61)
    (send! (apply str (repeat 60 "b")))
    (is (zero? @over) "what was taken no longer counts")
    (send! (apply str (repeat 60 "c")))
    (is (= 1 @over) "past the backlog it gives up")
    (is (= 2 (.size q)) "and queues nothing more")))

(deftest the-event-queue-holds-producers-back
  (let [eng (#'serve/engine nil :service {:capacity 2})
        q (:q eng)]
    (#'serve/enqueue! eng 1)
    (#'serve/enqueue! eng 2)
    (let [third (future (#'serve/enqueue! eng 3) :put)]
      (is (= :waiting (deref third 300 :waiting)) "a full queue makes the producer wait")
      (.take ^java.util.concurrent.BlockingQueue q)
      (is (= :put (deref third 2000 :waiting)) "until there is room")
      (is (= 2 (.size ^java.util.concurrent.BlockingQueue q))))))

;; --- Codex round 2

(deftest serve-never-removes-what-is-not-its-socket
  (with-service
    (fn [service]
      (stop-service! service)
      (doseq [[what make!] [["a file" #(spit % "Kira's notes")] ["a directory" #(fs/create-dirs %)]]]
        (testing what
          (let [path (str (fs/parent *socket*) "/taken")
                _ (make! path)
                r (deref (p/process [support/relay-bin "serve" "--data" (data) "--socket" path "--run-dir" *run-dir*]
                                    {:out :string :err :string})
                         15000 :timeout)]
            (is (= 1 (:exit r)) (pr-str r))
            (is (str/includes? (str (:err r)) "not a socket"))
            (if (= "a file" what)
              (is (= "Kira's notes" (slurp path)) "left as it was")
              (is (fs/directory? path)))
            (fs/delete-tree path)))))))

(deftest a-data-directory-is-known-by-its-real-path
  (let [base (str (fs/create-temp-dir {:dir "/tmp" :prefix "rsreal"}))
        data (str base "/data")
        link (str base "/link")]
    (try
      (fs/create-dirs data)
      (fs/create-sym-link link data)
      (binding [serve/*run-dir* "/r"]
        (is (= (serve/lock-path data) (serve/lock-path link)) "a link to it")
        (is (= (serve/socket-path data nil) (serve/socket-path link nil)))
        (is (= (serve/lock-path data) (serve/lock-path (str "/private" data))) "/tmp and /private/tmp on macOS"))
      (finally (fs/delete-tree base)))))

(deftest the-service-takes-only-so-many-connections
  (let [eng (#'serve/engine nil :service {:max-conns 2})
        conn #(#'serve/add-connection! eng {:in (java.io.PipedInputStream. (java.io.PipedOutputStream.))
                                            :out (java.io.ByteArrayOutputStream.) :origin :socket})]
    (is (some? (conn)))
    (is (some? (conn)))
    (is (nil? (conn)) "a third is refused")))

;; --- Codex round 3

(deftest a-turn-end-the-log-refused-is-recorded-once-it-can-be
  ;; The shell carries out the hub's submits: one that throws (a full disk)
  ;; comes back as a failure, the hub keeps the role busy and says so, and
  ;; a later tick records it.
  (let [[h fx] (ht/say (ht/live) 1 (ht/req 30 "session/prompt" {:sessionId "s1" :prompt []}))
        h (ht/submit-ok h fx)
        up (:up (second (hub/running h "clerk")))
        eng (#'serve/engine nil :service)
        replies (atom [])
        submitted (atom [])
        err (java.io.StringWriter.)]
    (reset! (:hub eng) h)
    (reset! (:conns eng) {1 {:send! #(swap! replies conj %)}})
    (binding [*err* err]
      (with-redefs [inbound/submit! (fn [_ cmd] (swap! submitted conj (:type cmd))
                                      (throw (java.io.IOException. "No space left on device")))]
        (#'serve/process! eng {:event :agent-line :role "clerk" :line (ht/res up {:stopReason "end_turn"})})))
    (is (str/includes? (str err) "could not record the end of clerk's turn") (str err))
    (is (= [:conversation/turn-end :conversation/turn-unrecorded] @submitted))
    (is (get-in @(:hub eng) [:convs "clerk" :turn-end-due]) "still busy")
    (reset! submitted [])
    (with-redefs [inbound/submit! (fn [_ cmd] (swap! submitted conj (:type cmd)) [])]
      (#'serve/process! eng {:event :tick}))
    (is (= [:conversation/turn-end] @submitted) "tried again")
    (is (nil? (get-in @(:hub eng) [:convs "clerk" :turn-end-due])) "and recorded")))

;; The flake behind the gate failure: stop-service! sent SIGTERM as soon as
;; the socket accepted, before the service had its handler, so the default
;; action killed it (exit 143) instead of a clean stop.
(deftest ^:volley/serial the-service-handles-sigterm-before-its-socket-exists
  (let [dir (str (fs/create-temp-dir {:prefix "relay-serve-sig"}))
        sockdir (str (fs/create-temp-dir {:dir "/tmp" :prefix "rs"}))
        path (str sockdir "/s.sock")
        existed (atom ::never)]
    (try
      (let [data (str dir "/data")
            _ (log/init! data)
            _ (spit (str data "/relay.edn") (pr-str {:timezone "UTC" :default-harness :a
                                                   :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin "/bin/true"}}}))
            sys (inbound/system data)]
        (do
          (let [lock (java.nio.channels.FileChannel/open (fs/path (str sockdir "/lock"))
                                                         (into-array java.nio.file.OpenOption
                                                                     [java.nio.file.StandardOpenOption/CREATE
                                                                      java.nio.file.StandardOpenOption/WRITE]))
                real dispatch/with-signals]
            (with-redefs [dispatch/with-signals
                          (fn [handlers f]
                            (reset! existed (fs/exists? path {:nofollow-links true}))
                            (future (Thread/sleep 200) ((get handlers "TERM")))
                            (real {} f))]
              (is (= 0 (#'serve/run-service! sys path lock nil)))))))
      (is (false? @existed) "the handlers are in place before anything can connect")
      (finally (fs/delete-tree sockdir) (fs/delete-tree dir)))))

(deftest ^:volley/serial serve-handles-sigterm-before-anyone-can-connect
  ;; Found in the gate: a SIGTERM that came as the socket first listened
  ;; killed the service with 143, its handler not yet installed. In this
  ;; process, so the handler can be looked at: once the socket is bound,
  ;; SIGTERM is the service's.
  (let [dir (str (fs/create-temp-dir {:prefix "relay-serve-term"}))
        sockdir (str (fs/create-temp-dir {:dir "/tmp" :prefix "rst"}))
        data (str dir "/data")
        term (sun.misc.Signal. "TERM")
        probe (reify sun.misc.SignalHandler (handle [_ _]))
        ;; The handler now, put back at once. Signal/handle wraps what it
        ;; returns afresh each time, so handlers are compared by name.
        current (fn [] (let [h (sun.misc.Signal/handle term probe)] (sun.misc.Signal/handle term h) h))
        before (current)]
    (try
      (relay.log/init! data)
      (spit (str data "/relay.edn") (pr-str {:timezone "UTC" :default-harness :a
                                              :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin "/bin/false"}}}))
      (let [sys (inbound/system data)
            listening (promise)
            go-on (promise)
            reap dispatch/reap-lost-wakes!
            relays? (fn [h] (not= (str before) (str h)))]
        ;; The service stops just after it binds its socket, where a
        ;; client could already connect, until the handler is looked at.
        (with-redefs [dispatch/reap-lost-wakes! (fn [s] (deliver listening true) @go-on (reap s))]
          (let [run (future (serve/serve! sys {:socket (str sockdir "/s.sock") :run-dir (str sockdir "/run")}))]
            (is (not= :timeout (deref listening 30000 :timeout)) "the service binds its socket")
            (is (relays? (current)) "SIGTERM is the service's once its socket is bound")
            (deliver go-on true)
            ;; Stop it as SIGTERM would, once its handler is there: never
            ;; the JVM's own, which would end this process.
            (let [h (some #(when (relays? %) %) (repeatedly 200 #(do (Thread/sleep 50) (current))))]
              (when h (.handle ^sun.misc.SignalHandler h term)))
            (is (= 0 (deref run 30000 :timeout)))
            (is (= (str before) (str (current))) "and gives it back when it stops"))))
      (finally
        (sun.misc.Signal/handle term before)
        (fs/delete-tree dir)
        (fs/delete-tree sockdir)))))
