(ns relay.serve
  "The shell around relay.hub: `relay serve`, the service that keeps
  conversations going while no client is attached, and `relay acp`,
  which is a bridge to that service when it runs and the hub in-process
  when it does not.

  One loop thread owns the hub. Everything else (a connection's reader,
  a conversation's proxy, the log watcher) puts events on its queue, and
  the loop steps the hub and carries out the effects in order. A submit
  is carried out on the loop and its result stepped at once, so a turn
  relay accepts is passed on before anything else happens. Writes go to
  a queue per connection and per conversation, so a client that stops
  reading holds up nobody else.

  A conversation is relay.dispatch/run-conversation!: the role's chat
  wake, its agent behind relay's proxy, with the hub as the proxy's
  client side.

  The socket for a data directory is under a fixed directory in the
  user's home (*run-dir*), named by a hash of the absolute data
  directory, so its path stays short (macOS takes about 104 bytes). The
  directory is the user's alone and so is the socket. `--socket` names
  another. A lock beside the socket keeps a second service off it."
  (:require [babashka.fs :as fs]
            [relay.acp :as acp]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.hub :as hub]
            [relay.inbound :as inbound]
            [relay.roster :as roster]
            [relay.workspace :as workspace])
  (:import (java.net UnixDomainSocketAddress StandardProtocolFamily)
           (java.nio.channels Channels FileChannel ServerSocketChannel SocketChannel)
           (java.nio.file OpenOption StandardOpenOption)
           (java.util.concurrent LinkedBlockingQueue)))

;; --- where the socket is

(def ^:dynamic *run-dir*
  "The directory the service's socket and lock go in. Bound in tests."
  nil)

(defn run-dir []
  (or *run-dir* (str (fs/path (System/getProperty "user.home") ".relay" "run"))))

(defn socket-path
  "The socket the service for a data directory listens on: `socket` when
  given, else one named by a hash of the absolute data directory."
  [data-dir socket]
  (str (fs/absolutize
        (or socket (fs/path (run-dir) (str (subs (core/sha256 (str (fs/normalize (fs/absolutize data-dir)))) 0 16)
                                           ".sock"))))))

;; --- streams

(defn- daemon [name f]
  (doto (Thread. ^Runnable f ^String name) (.setDaemon true) (.start)))

(defn- queue-input-stream
  "An input stream of the byte arrays put on q, ending at ::eof."
  [^LinkedBlockingQueue q]
  (let [cur (atom nil) pos (atom 0) done (atom false)
        fill! (fn [] (loop []
                       (cond @done false
                             (and @cur (< @pos (alength ^bytes @cur))) true
                             :else (let [x (.take q)]
                                     (if (= ::eof x)
                                       (do (reset! done true) false)
                                       (do (reset! cur x) (reset! pos 0) (recur)))))))]
    (proxy [java.io.InputStream] []
      (read
        ([] (if (fill!)
              (let [b (aget ^bytes @cur @pos)] (swap! pos inc) (bit-and b 0xff))
              -1))
        ([^bytes b] (if (fill!)
                      (let [n (min (alength b) (- (alength ^bytes @cur) @pos))]
                        (System/arraycopy @cur @pos b 0 n) (swap! pos + n) n)
                      -1))
        ([^bytes b off len]
         (if (fill!)
           (let [n (min len (- (alength ^bytes @cur) @pos))]
             (System/arraycopy @cur @pos b off n) (swap! pos + n) n)
           -1))))))

(defn- line-output-stream
  "An output stream that calls (on-line text) for each complete line
  written to it."
  [on-line]
  (let [pending (atom acp/no-rest)
        take! (fn [^bytes chunk]
                (let [[rest lines] (acp/split @pending chunk)]
                  (reset! pending rest)
                  (doseq [^bytes l lines]
                    (on-line (String. l java.nio.charset.StandardCharsets/UTF_8)))))]
    (proxy [java.io.OutputStream] []
      (write
        ([b] (if (bytes? b) (take! b) (take! (byte-array [(unchecked-byte b)]))))
        ([^bytes b off len] (take! (java.util.Arrays/copyOfRange b (int off) (int (+ off len))))))
      (flush []))))

(defn- line-bytes ^bytes [line] (.getBytes (str line "\n") java.nio.charset.StandardCharsets/UTF_8))

;; --- the engine: the hub and its loop

(defn- engine [sys mode approvals]
  {:sys sys :mode mode :approvals approvals :q (LinkedBlockingQueue.)
   :hub (atom (hub/hub {:mode mode})) :conns (atom {}) :convs (atom {}) :next-conn (atom 0)
   :closed-any (atom false)})

(defn- enqueue! [eng e] (.put ^LinkedBlockingQueue (:q eng) e))

(defn- roster-event [{:keys [sys]}]
  {:event :log-changed
   :roster (roster/roster (inbound/current-state sys) (:config sys) ((:clock sys)))})

(defn- submit-result
  "Carries out a submit: the result as the hub takes it."
  [sys command]
  (try (inbound/submit! sys command)
       {:ok true}
       (catch clojure.lang.ExceptionInfo e
         {:rejection (or (core/rejection e) :error) :message (ex-message e)})
       (catch Exception e
         {:rejection :error :message (or (ex-message e) (str (class e)))})))

(declare start-conversation!)

(defn- carry-out!
  "Carries out one effect. Returns the event it leads to at once, if any."
  [eng {:keys [effect] :as f}]
  (case effect
    :write (do (when-let [c (get @(:conns eng) (:conn f))] ((:send! c) (:line f))) nil)
    :send (do (when-let [c (get @(:convs eng) (:role f))] ((:send! c) (:line f))) nil)
    :submit (merge {:event :submit-result :tag (:tag f)} (submit-result (:sys eng) (:command f)))
    :start (do (start-conversation! eng (:role f) nil) nil)
    :stop (do (when-let [c (get @(:convs eng) (:role f))] ((:stop! c))) nil)))

(defn- process!
  "Steps the hub with an event and carries out what it calls for, an
  effect's own result first."
  [eng e]
  (let [[h fx] (hub/step @(:hub eng) e)]
    (reset! (:hub eng) h)
    (when (= :connection-closed (:event e)) (reset! (:closed-any eng) true))
    (doseq [f fx]
      (when-let [follow (try (carry-out! eng f)
                             (catch Exception ex
                               (binding [*out* *err*] (println "relay could not carry out" (:effect f) ":" (ex-message ex)))
                               nil))]
        (process! eng follow)))))

(defn- run-loop!
  "Takes events until `done?` says the hub is done, or ::quit."
  [eng done?]
  (loop []
    (let [e (.take ^LinkedBlockingQueue (:q eng))]
      (when-not (= ::quit e)
        (try (process! eng e)
             (catch Exception ex
               (binding [*out* *err*] (println "relay's hub failed on an event:" (ex-message ex)))))
        (when-not (done?) (recur))))))

(defn- watch-log!
  "Feeds the hub the roster whenever the log changes, about four times a
  second, until `stop` is delivered."
  [eng stop]
  (daemon "relay-log-watcher"
          (fn []
            (loop [stamp (dispatch/log-stamp (:data-dir (:sys eng)))]
              (when (= ::waiting (deref stop 250 ::waiting))
                (recur (try (let [now (dispatch/log-stamp (:data-dir (:sys eng)))]
                              (when (not= now stamp) (enqueue! eng (roster-event eng)))
                              now)
                            (catch Exception e
                              (binding [*out* *err*] (println "relay could not read the log:" (ex-message e)))
                              stamp))))))))

;; --- conversations

(defn- start-conversation!
  "Starts a role's conversation on a thread of its own. Its proxy's lines
  come to the hub as :agent-line, and it ends as :conversation-gone, or
  :conversation-failed when it never started. `started` is delivered
  {:wake} once it runs, or {:error} when it could not start; the
  conversation's result once it has ended is in the returned promise."
  [{:keys [sys mode approvals] :as eng} role started]
  (let [q (LinkedBlockingQueue.)
        result (promise)
        ran (atom false)
        out (line-output-stream #(enqueue! eng {:event :agent-line :role role :line %}))]
    (swap! (:convs eng) assoc role {:send! #(.put q (line-bytes %)) :stop! #(.put q ::eof)})
    (daemon (str "relay-conversation-" role)
            (fn []
              (let [r (try (dispatch/run-conversation!
                            sys role {:in (queue-input-stream q) :out out
                                      ;; A service lets the hub decide who may answer.
                                      :approvals (or (= :service mode) (boolean approvals))
                                      :on-started (fn [wake]
                                                    (reset! ran true)
                                                    (enqueue! eng {:event :conversation-started :role role :wake wake})
                                                    (some-> started (deliver {:wake wake})))})
                           (catch Throwable t {:error (or (ex-message t) (str (class t)))}))]
                (.put q ::eof)
                (swap! (:convs eng) dissoc role)
                (enqueue! eng (if @ran
                                {:event :conversation-gone :role role}
                                {:event :conversation-failed :role role :message (:error r)}))
                (some-> started (deliver r))
                (deliver result r))))
    result))

;; --- connections

(def write-backlog
  "How many characters may wait to be written to one connection before
  relay gives up on it as a client that has stopped reading."
  (* 64 1024 1024))

(defn- add-connection!
  "Starts a connection's reader and writer. Lines it sends come to the hub
  as :connection-line, and its end as :connection-closed, once."
  [eng {:keys [^java.io.InputStream in ^java.io.OutputStream out origin approvals role trace on-close]}]
  (let [cid (swap! (:next-conn eng) inc)
        q (LinkedBlockingQueue.)
        backlog (atom 0)
        closed (atom false)
        close! (fn []
                 (when (compare-and-set! closed false true)
                   (.put q ::eof)
                   (enqueue! eng {:event :connection-closed :conn cid})
                   (when on-close (on-close))))
        send! (fn [line]
                (when-not @closed
                  (if (> (swap! backlog + (count line)) write-backlog)
                    (do (binding [*out* *err*] (println "relay closed a connection that stopped reading."))
                        (close!))
                    (.put q line))))]
    (swap! (:conns eng) assoc cid {:send! send! :close! close!})
    (enqueue! eng {:event :connection-opened :conn cid :origin origin :approvals approvals :role role})
    (daemon (str "relay-connection-writer-" cid)
            (fn []
              (loop []
                (let [x (.take q)]
                  (when-not (= ::eof x)
                    (swap! backlog - (count x))
                    (when trace (trace :agent x))
                    (if (try (.write out (line-bytes x)) (.flush out) true
                             (catch java.io.IOException _ false))
                      (recur)
                      (close!)))))
              (swap! (:conns eng) dissoc cid)))
    (daemon (str "relay-connection-reader-" cid)
            (fn []
              (dispatch/pump! in (java.io.OutputStream/nullOutputStream) (fn [_]) (fn [])
                              {:screen (fn [line]
                                         (when trace (trace :client line))
                                         (enqueue! eng {:event :connection-line :conn cid :line (:text line)})
                                         nil)
                               ;; A line too long to read whole goes nowhere; a
                               ;; request whose id relay can find is told why.
                               :on-oversized (fn [{:keys [prefix]}]
                                               (when-let [id (acp/line-id prefix)]
                                                 (send! (workspace/error-line id -32600 "relay does not pass on a message over 8 MB."))))})
              (close!)))
    cid))

;; --- relay serve

(defn- lock!
  "Takes the lock that keeps a second service off this socket, or nil
  when another holds it."
  [path]
  (let [ch (FileChannel/open (fs/path path) (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
    ;; The channel holds the lock until it is closed.
    (if (try (.tryLock ch) (catch Exception _ nil))
      ch
      (do (.close ch) nil))))

(defn serve!
  "Runs the relay service for the system's data directory until SIGTERM
  or SIGINT: listens on the socket, runs a conversation per role for its
  clients, and keeps conversations going while none is attached. On the
  way out every conversation ends and is recorded. Conversations a
  service before it left running (it crashed, say) are ended first, as
  lost. Returns an exit code."
  [sys {:keys [socket ready]}]
  (binding [*out* *err*]
    (let [path (socket-path (:data-dir sys) socket)
          dir (fs/parent path)]
      (fs/create-dirs dir)
      (fs/set-posix-file-permissions dir "rwx------")
      (if-let [lock (lock! (str path ".lock"))]
        (let [_ (fs/delete-if-exists path)
              server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                       (.bind (UnixDomainSocketAddress/of ^String path)))
              _ (fs/set-posix-file-permissions path "rw-------")
              eng (engine sys :service false)
              stop (promise)
              quit (promise)]
          (dispatch/reap-lost-wakes! sys)
          (process! eng (roster-event eng))
          (watch-log! eng quit)
          (daemon "relay-hub" (fn [] (run-loop! eng (constantly false)) (deliver quit true)))
          (daemon "relay-accept"
                  (fn []
                    (loop []
                      (when-let [ch (try (.accept server) (catch Exception _ nil))]
                        (add-connection! eng {:in (Channels/newInputStream ^SocketChannel ch)
                                              :out (Channels/newOutputStream ^SocketChannel ch)
                                              :origin :socket :approvals false
                                              :on-close #(try (.close ^SocketChannel ch) (catch Exception _ nil))})
                        (recur)))))
          (println (str "relay serve: listening on " path))
          (some-> ready (deliver path))
          (dispatch/with-signals {"TERM" #(deliver stop :term) "INT" #(deliver stop :int) "HUP" (fn [])}
            (fn []
              @stop
              (.close server)
              ;; Every conversation ends, and its wake records the end.
              (doseq [[_ c] @(:convs eng)] ((:stop! c)))
              (let [deadline (+ (System/currentTimeMillis) dispatch/stop-grace-ms 10000)]
                (while (and (seq @(:convs eng)) (< (System/currentTimeMillis) deadline))
                  (Thread/sleep 50)))
              (doseq [[_ c] @(:conns eng)] ((:close! c)))
              (enqueue! eng ::quit)
              (deref quit 5000 nil)
              (fs/delete-if-exists path)
              (.close ^FileChannel lock)
              0)))
        (do (println (str "relay serve is already running for " (:data-dir sys) " (" path ")."))
            1)))))

;; --- relay acp

(defn- bridge!
  "Copies bytes between the client on in/out and a listening service,
  having told the service first what this connection may do."
  [^SocketChannel ch {:keys [in out approvals role trace]}]
  (let [sock-in (Channels/newInputStream ch)
        sock-out (Channels/newOutputStream ch)
        tracer (dispatch/tracer trace)
        done (promise)]
    (.write sock-out (line-bytes (workspace/notification-line "_relay/connect" {"approvals" (boolean approvals) "role" role})))
    (.flush sock-out)
    (daemon "relay-bridge-in"
            (fn []
              (dispatch/pump! in sock-out (fn [l] (when tracer (tracer :client l))) #(deliver done :service-gone))
              (try (.shutdownOutput ch) (catch Exception _ nil))))
    (daemon "relay-bridge-out"
            (fn []
              (dispatch/pump! sock-in out (fn [l] (when tracer (tracer :agent l))) #(deliver done :client-gone))
              (deliver done :service-closed)))
    (dispatch/with-signals (assoc dispatch/terminal-signals "TERM" #(deliver done :terminated))
      (fn [] @done (try (.close ch) (catch Exception _ nil)) 0))))

(defn- embedded!
  "Runs the hub in this process with in/out as its one connection, until
  the connection ends and every conversation it opened has ended. With a
  role, its conversation opens first: when it cannot, relay says why on
  stderr and returns 1 before any traffic."
  [sys role {:keys [in out approvals trace]}]
  (let [eng (engine sys :embedded approvals)
        tracer (dispatch/tracer trace)
        started (promise)
        result (when role (start-conversation! eng role started))]
    (process! eng (roster-event eng))
    (if-let [error (and role (:error @started))]
      (do (println error) 1)
      (let [quit (promise)
            cid (add-connection! eng {:in in :out out :origin :stdio :approvals (boolean approvals) :role role
                                      :trace tracer})]
        (watch-log! eng quit)
        (dispatch/with-signals (assoc dispatch/terminal-signals
                                      "TERM" #(when-let [c (get @(:conns eng) cid)] ((:close! c))))
          (fn [] (run-loop! eng #(and @(:closed-any eng) (hub/idle? @(:hub eng)) (empty? @(:convs eng))))))
        (deliver quit true)
        (if result
          (let [r @result]
            (when (:error r) (println (:error r)))
            (if (integer? (:exit r)) (:exit r) 1))
          0)))))

(defn acp!
  "relay acp: speaks ACP on in/out (stdin and stdout by default). When a
  service listens for the data directory, a bridge to it; else the hub
  in this process, whose conversations end with it. relay itself writes
  to stdout only ACP. SIGINT and SIGHUP are ignored, as for chat; SIGTERM
  ends the connection, and with it, in this process, its conversations.
  Returns an exit code.

  Options: :approvals lets the client answer permission requests; :trace
  a file to append both directions of the traffic to; :socket the
  service's socket."
  [sys role {:keys [trace in out socket] :or {in System/in out System/out} :as opts}]
  (binding [*out* *err*]
    (try
      ;; Before anything opens, so a refused file costs nothing.
      (when trace (dispatch/private-trace-file! trace))
      (let [opts (assoc opts :in in :out out)
            path (socket-path (:data-dir sys) socket)
            ch (when (fs/exists? path)
                 (try (SocketChannel/open (UnixDomainSocketAddress/of ^String path)) (catch Exception _ nil)))]
        (if ch
          (bridge! ch (assoc opts :role role))
          (embedded! sys role opts)))
      (catch Exception e
        (println (or (ex-message e) (str (class e))))
        1))))
