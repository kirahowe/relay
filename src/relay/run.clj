(ns relay.run
  "`relay run`, what the relay service runs (brew services start relay):
  `relay serve`, and `relay tick` once a minute, each as a child process.
  A child of its own keeps each as it is when run by hand, and lets a
  relay.edn that will not load stop serve without stopping the tick,
  which is what tells the operator about it.

  At most one tick runs at a time, and a tick starts at least a minute
  after the one before. serve is started again when it exits, at least a
  minute after it last started, so a serve that cannot start does not
  fill the log. On SIGTERM or SIGINT no new tick starts, serve is asked
  to stop (it ends every conversation and records it), and relay run
  waits for both children: launchd kills whatever is left in the
  service's process group once relay run exits, and a tick cut off there
  could deliver a message without recording it. The wakes a tick starts
  lead process groups of their own (relay.dispatch/detached), so they
  run on.

  The decisions are pure (due, started); run! is the shell."
  (:refer-clojure :exclude [run!])
  (:require [babashka.process :as p]
            [relay.dispatch :as dispatch])
  (:import (java.time Duration Instant)))

(def interval
  "How long after a tick starts the next may start, and after serve
  starts it may be started again."
  (Duration/ofMinutes 1))

(def stop-wait-ms
  "How long relay run waits for its children on the way out, both at
  once. serve takes up to dispatch/stop-grace-ms, ten seconds for its
  conversations to end and five for its loop to quit; this leaves five
  more. The formula's stop_timeout (40 seconds) is launchd's grace above
  this."
  (+ dispatch/stop-grace-ms 20000))

(defn initial
  "Both due at once."
  [^Instant now]
  {:serve-at now :tick-at now})

(defn due
  "What to start at `now`, a vector of :serve and :tick: each when it is
  not running and its time has come. `running` is the set of those
  running."
  [{:keys [serve-at tick-at]} running ^Instant now]
  (cond-> []
    (and (not (running :serve)) (not (.isBefore now serve-at))) (conj :serve)
    (and (not (running :tick)) (not (.isBefore now tick-at))) (conj :tick)))

(defn started
  "The schedule after `child` started at `now`."
  [schedule child ^Instant now]
  (assoc schedule (case child :serve :serve-at :tick :tick-at) (.plus now ^Duration interval)))

;; --- the shell

(defn- argv [relay-bin data child {:keys [socket run-dir]}]
  (case child
    :tick [relay-bin "tick" "--data" data]
    :serve (cond-> [relay-bin "serve" "--data" data]
             socket (into ["--socket" socket])
             run-dir (into ["--run-dir" run-dir]))))

(defn- alive? [proc] (.isAlive ^Process (:proc proc)))

(defn- start!
  "Starts one child, or says why it could not (the relay command gone in
  the middle of an upgrade, say) and returns nil: the schedule tries again
  a minute later."
  [argv]
  (try (p/process argv {:in "" :out :inherit :err :inherit})
       (catch Exception e
         (binding [*out* *err*]
           (println (str "relay run: could not start " (second argv) ": " (or (ex-message e) (class e)))))
         nil)))

(defn- wait-for! [procs ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (doseq [[_ proc] procs]
      (.waitFor ^Process (:proc proc) (max 0 (- deadline (System/currentTimeMillis)))
                java.util.concurrent.TimeUnit/MILLISECONDS))))

(defn run!
  "Runs serve and the tick for the data directory `data` (absolute) with
  the relay command `relay-bin`, until SIGTERM or SIGINT. Their output
  goes where relay run's does. `opts`: :socket and :run-dir for serve,
  :clock, :poll-ms, how often it looks at its children, and :stop, a
  promise that stops it as a signal does. Returns an exit code."
  [relay-bin data {:keys [clock poll-ms stop] :or {clock #(Instant/now) poll-ms 1000} :as opts}]
  (let [stop (or stop (promise))]
    (dispatch/with-signals {"TERM" #(deliver stop true) "INT" #(deliver stop true) "HUP" (fn [])}
      (fn []
        (loop [schedule (initial (clock))
               procs {}]
          (let [procs (into {} (filter (fn [[_ proc]] (alive? proc))) procs)]
            (if (realized? stop)
              (do (some-> ^Process (:proc (:serve procs)) .destroy)
                  (wait-for! procs stop-wait-ms)
                  0)
              (let [now (clock)
                    todo (due schedule (set (keys procs)) now)
                    procs (into procs (for [child todo
                                            :let [proc (start! (argv relay-bin data child opts))]
                                            :when proc]
                                        [child proc]))
                    schedule (reduce #(started %1 %2 now) schedule todo)]
                (deref stop poll-ms nil)
                (recur schedule procs)))))))))
