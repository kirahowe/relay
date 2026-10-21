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
  another. A lock in the run directory, named the same way, keeps a
  second service off the data directory whatever socket it would use."
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
  "The directory the service's socket and lock go in. Bound in tests;
  `--run-dir`, for tests only, names another for one command."
  nil)

(defn run-dir [& [dir]]
  (or dir *run-dir* (str (fs/path (System/getProperty "user.home") ".relay" "run"))))

(defn- data-key
  "A short name for a data directory: a hash of its real path, so a link
  to it, or /tmp and /private/tmp on macOS, name the same one."
  [data-dir]
  (let [real (try (str (fs/real-path data-dir)) (catch Exception _ (str (fs/normalize (fs/absolutize data-dir)))))]
    (subs (core/sha256 real) 0 16)))

(defn socket-path
  "The socket the service for a data directory listens on: `socket` when
  given, else one named by the data directory's hash in the run
  directory."
  [data-dir socket & [dir]]
  (str (fs/absolutize (or socket (fs/path (run-dir dir) (str (data-key data-dir) ".sock"))))))

(defn lock-path
  "The lock that keeps a second service off a data directory, whatever
  socket either listens on."
  [data-dir & [dir]]
  (str (fs/absolutize (fs/path (run-dir dir) (str (data-key data-dir) ".lock")))))

;; --- streams

(defn- line-bytes ^bytes [line] (.getBytes (str line "\n") java.nio.charset.StandardCharsets/UTF_8))

(defn- daemon [name f]
  (doto (Thread. ^Runnable f ^String name) (.setDaemon true) (.start)))

(defn- queue-input-stream
  "An input stream of the lines put on q, each with its newline, ending at
  ::eof. Calls (on-taken n) with the length of each line it takes, as
  bounded-sender counts it."
  [^LinkedBlockingQueue q on-taken]
  (let [cur (atom nil) pos (atom 0) done (atom false)
        fill! (fn [] (loop []
                       (cond @done false
                             (and @cur (< @pos (alength ^bytes @cur))) true
                             :else (let [x (.take q)]
                                     (if (= ::eof x)
                                       (do (reset! done true) false)
                                       (do (on-taken (inc (count x))) (reset! cur (line-bytes x)) (reset! pos 0) (recur)))))))]
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

;; --- the engine: the hub and its loop

(def event-capacity
  "How many events may wait for the hub. Past it whoever produces one (a
  client's reader, a conversation's proxy, the log watcher) waits, so
  nothing reads faster than the hub keeps up."
  1024)

(def write-backlog
  "How many characters may wait to be written to one connection, or to
  one conversation's agent, before relay gives up on a reader that has
  stopped reading."
  (* 64 1024 1024))

(def max-connections
  "How many clients may be connected at once. Each holds at most
  relay.hub/default-max-pending requests and write-backlog of output, so
  this bounds what the service keeps for its clients."
  64)

(defn- engine [sys mode approvals & [{:keys [capacity max-conns] :or {capacity event-capacity max-conns max-connections}}]]
  {:sys sys :mode mode :approvals approvals :q (LinkedBlockingQueue. (int capacity)) :max-conns max-conns
   ;; What the loop thread itself raises (a connection it gave up on)
   ;; goes here, so the loop never waits on its own full queue.
   :own (LinkedBlockingQueue.) :loop-thread (atom nil)
   :hub (atom (hub/hub {:mode mode})) :conns (atom {}) :convs (atom {}) :next-conn (atom 0)
   :closed-any (atom false)})

(defn- enqueue!
  "Puts an event on the hub's queue, waiting while it is full."
  [eng e]
  (if (= (Thread/currentThread) @(:loop-thread eng))
    (.add ^LinkedBlockingQueue (:own eng) e)
    (.put ^LinkedBlockingQueue (:q eng) e)))

(defn- bounded-sender
  "A sender of lines onto q that gives up, calling (over!) once and
  queueing nothing more, when more than `limit` characters wait unread.
  (taken! n) says n bytes were read."
  [^LinkedBlockingQueue q limit over!]
  (let [backlog (atom 0)
        gave-up (atom false)]
    {:send! (fn [line]
              (when-not @gave-up
                (if (> (swap! backlog + (inc (count line))) limit)
                  (when (compare-and-set! gave-up false true) (over!))
                  (.put q line))))
     :taken! (fn [n] (swap! backlog - n))}))

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
  "Takes events until `done?` says the hub is done, or ::quit, its own
  first."
  [eng done?]
  (reset! (:loop-thread eng) (Thread/currentThread))
  (loop []
    (let [e (or (.poll ^LinkedBlockingQueue (:own eng))
                (.take ^LinkedBlockingQueue (:q eng)))]
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
        ;; The proxy's own stop: it closes the agent's stdin and kills the
        ;; agent after the grace, whatever is still queued for it.
        stop (promise)
        stop! (fn [] (deliver stop :stopped) (.put q ::eof))
        {:keys [send! taken!]} (bounded-sender q write-backlog
                                               #(do (binding [*out* *err*]
                                                      (println (str "relay stopped " role "'s conversation: its agent stopped reading.")))
                                                    (stop!)))
        out (line-output-stream #(enqueue! eng {:event :agent-line :role role :line %}))]
    (swap! (:convs eng) assoc role {:send! send! :stop! stop!})
    (daemon (str "relay-conversation-" role)
            (fn []
              (let [r (try (dispatch/run-conversation!
                            sys role {:in (queue-input-stream q taken!) :out out :stop stop
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

(declare connection!)

(defn- add-connection!
  "Starts a connection's reader and writer, or returns nil when the
  service has as many as it takes. Lines it sends come to the hub as
  :connection-line, and its end as :connection-closed, once."
  [eng opts]
  (when (< (count @(:conns eng)) (:max-conns eng))
    (connection! eng opts)))

(defn- connection!
  [eng {:keys [^java.io.InputStream in ^java.io.OutputStream out origin approvals role trace on-close]}]
  (let [cid (swap! (:next-conn eng) inc)
        q (LinkedBlockingQueue.)
        closed (atom false)
        close! (fn []
                 (when (compare-and-set! closed false true)
                   (.put q ::eof)
                   (enqueue! eng {:event :connection-closed :conn cid})
                   (when on-close (on-close))))
        {:keys [send! taken!]} (bounded-sender q write-backlog
                                               #(do (binding [*out* *err*] (println "relay closed a connection that stopped reading."))
                                                    (close!)))
        send! (fn [line] (when-not @closed (send! line)))]
    (swap! (:conns eng) assoc cid {:send! send! :close! close!})
    (enqueue! eng {:event :connection-opened :conn cid :origin origin :approvals approvals :role role})
    (daemon (str "relay-connection-writer-" cid)
            (fn []
              (loop []
                (let [x (.take q)]
                  (when-not (= ::eof x)
                    (taken! (inc (count x)))
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

(defn- socket-in-the-way
  "Why the socket path cannot be used, or nil. Only a socket that no
  service answers on (one a service left when it died) is taken over;
  anything else there, a file or a directory, is left alone."
  [path]
  (when (fs/exists? path {:nofollow-links true})
    (let [mode (try (java.nio.file.Files/getAttribute (fs/path path) "unix:mode"
                                                      (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
                    (catch Exception _ 0))]
      (cond
        (not= 0140000 (bit-and mode 0170000))
        (str path " is there and is not a socket. relay leaves it alone; name another with --socket.")
        (try (.close (SocketChannel/open (UnixDomainSocketAddress/of ^String path))) true
             (catch Exception _ false))
        (str "another service listens on " path ".")))))

(defn- run-service!
  "Listens on `path`, holding `lock`, until SIGTERM or SIGINT."
  [sys path ^FileChannel lock ready]
  (let [_ (when (fs/exists? path {:nofollow-links true}) (fs/delete path))
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
                  (when-not (add-connection! eng {:in (Channels/newInputStream ^SocketChannel ch)
                                                  :out (Channels/newOutputStream ^SocketChannel ch)
                                                  :origin :socket :approvals false
                                                  :on-close #(try (.close ^SocketChannel ch) (catch Exception _ nil))})
                    (binding [*out* *err*] (println "relay serve turned a client away: it has as many as it takes."))
                    (try (.close ^SocketChannel ch) (catch Exception _ nil)))
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
        0))))

(defn serve!
  "Runs the relay service for the system's data directory until SIGTERM
  or SIGINT: listens on the socket, runs a conversation per role for its
  clients, and keeps conversations going while none is attached. On the
  way out every conversation ends and is recorded. Conversations a
  service before it left running (it crashed, say) are ended first, as
  lost. Returns an exit code."
  [sys {:keys [socket ready run-dir]}]
  (binding [*out* *err*]
    (let [path (socket-path (:data-dir sys) socket run-dir)
          lock-file (lock-path (:data-dir sys) run-dir)
          rdir (fs/parent lock-file)]
      ;; The run directory is relay's own and only the user's. A --socket
      ;; elsewhere goes in a directory that must exist, and is left as it
      ;; is.
      (fs/create-dirs rdir)
      (fs/set-posix-file-permissions rdir "rwx------")
      (if-not (fs/directory? (fs/parent path))
        (do (println (str "relay serve: " (fs/parent path) " is not a directory.")) 1)
        ;; Per data directory, whatever socket either service listens on.
        (if-let [lock (lock! lock-file)]
          (if-let [why (socket-in-the-way path)]
            (do (.close ^FileChannel lock) (println (str "relay serve: " why)) 1)
            (run-service! sys path lock ready))
          (do (println (str "relay serve is already running for " (:data-dir sys) " (" path ")."))
              1))))))

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
  [sys role {:keys [trace in out socket run-dir] :or {in System/in out System/out} :as opts}]
  (binding [*out* *err*]
    (try
      ;; Before anything opens, so a refused file costs nothing.
      (when trace (dispatch/private-trace-file! trace))
      (let [opts (assoc opts :in in :out out)
            path (socket-path (:data-dir sys) socket run-dir)
            ch (when (fs/exists? path)
                 (try (SocketChannel/open (UnixDomainSocketAddress/of ^String path)) (catch Exception _ nil)))]
        (if ch
          (bridge! ch (assoc opts :role role))
          (embedded! sys role opts)))
      (catch Exception e
        (println (or (ex-message e) (str (class e))))
        1))))
