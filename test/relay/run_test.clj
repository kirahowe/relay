(ns relay.run-test
  "relay run: the schedule's rules as properties, and the shell against a
  stand-in relay command that records what it was asked to run."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.dispatch :as dispatch]
            [relay.run :as run])
  (:import (java.time Instant)))

(def t0 (Instant/parse "2026-10-26T12:00:00Z"))

(defn- simulate
  "Runs the schedule over `steps`, each [seconds-later exits], where
  exits is the set of children that ended since the step before. Returns
  every start as [child instant]."
  [steps]
  (loop [schedule (run/initial t0) running #{} t t0 [[dt exits] & more] steps starts []]
    (let [running (apply disj running exits)
          todo (run/due schedule running t)
          starts (into starts (map (fn [c] [c t]) todo))
          schedule (reduce #(run/started %1 %2 t) schedule todo)
          running (into running todo)]
      (if dt
        (recur schedule running (.plusSeconds t dt) more starts)
        starts))))

(def steps
  (gen/vector (gen/tuple (gen/choose 0 200) (gen/set (gen/elements [:serve :tick])))
              0 60))

(deftest the-schedule-starts-each-child-at-most-once-a-minute
  (let [r (tc/quick-check
           300
           (prop/for-all [s steps]
             (let [starts (simulate s)]
               (every? (fn [child]
                         (let [ts (map second (filter #(= child (first %)) starts))]
                           (every? (fn [[a b]] (>= (.getSeconds (java.time.Duration/between a b)) 60))
                                   (partition 2 1 ts))))
                       [:serve :tick]))))]
    (is (:pass? r) (pr-str (select-keys r [:seed :shrunk])))))

(deftest the-schedule-never-starts-a-child-that-runs
  (let [r (tc/quick-check
           300
           (prop/for-all [s steps]
             (loop [schedule (run/initial t0) running #{} t t0 [[dt exits] & more] s]
               (let [running (apply disj running exits)
                     todo (run/due schedule running t)]
                 (cond
                   (some running todo) false
                   (nil? dt) true
                   :else (recur (reduce #(run/started %1 %2 t) schedule todo)
                                (into running todo) (.plusSeconds t dt) more))))))]
    (is (:pass? r) (pr-str (select-keys r [:seed :shrunk])))))

(deftest a-child-that-is-due-and-stopped-is-started
  (is (= [:serve :tick] (run/due (run/initial t0) #{} t0)))
  (is (= [] (run/due (run/initial t0) #{:serve :tick} t0)))
  (let [s (run/started (run/initial t0) :tick t0)]
    (is (= [] (run/due s #{:serve} (.plusSeconds t0 59))))
    (is (= [:tick] (run/due s #{:serve} (.plusSeconds t0 60))))))

(defn- stand-in!
  "A relay command that appends its arguments to `record`. serve stays up
  until SIGTERM, then records that it stopped."
  [dir record]
  (let [bin (str (fs/path dir "relay"))]
    (spit bin (str "#!/bin/sh\n"
                   "echo \"$*\" >> '" record "'\n"
                   "if [ \"$1\" = serve ]; then\n"
                   "  trap 'echo stopped >> \"" record "\"; exit 0' TERM\n"
                   "  while :; do sleep 0.05; done\n"
                   "fi\n"))
    (fs/set-posix-file-permissions bin "rwx------")
    bin))

(deftest relay-run-runs-serve-and-ticks-and-stops-serve-on-the-way-out
  (let [dir (str (fs/create-temp-dir {:prefix "relay-run"}))
        record (str (fs/path dir "record"))
        bin (stand-in! dir record)
        ;; Each look at the clock is 30 seconds on, so a tick is due every
        ;; second look.
        now (atom t0)
        clock #(swap! now (fn [t] (.plusSeconds ^Instant t 30)))
        stop (promise)
        lines #(if (fs/exists? record) (str/split-lines (slurp record)) [])]
    (try
      (let [done (future (run/run! bin "/data" {:clock clock :poll-ms 20 :stop stop :socket "/s.sock"}))]
        (loop [n 0]
          (when (and (< n 250) (< (count (filter #(str/starts-with? % "tick") (lines))) 3))
            (Thread/sleep 20)
            (recur (inc n))))
        (deliver stop true)
        (is (= 0 (deref done 10000 ::timeout)))
        (let [ls (lines)]
          (is (= 1 (count (filter #(= "serve --data /data --socket /s.sock" %) ls))) (pr-str ls))
          (is (<= 3 (count (filter #(= "tick --data /data" %) ls))) (pr-str ls))
          (is (some #{"stopped"} ls) "serve was asked to stop, and relay run waited for it")))
      (finally (fs/delete-tree dir)))))

(deftest a-dispatched-wake-leads-a-process-group-of-its-own
  ;; launchd ends the service by killing its process group; a wake in it
  ;; would die with every restart and count as a crash.
  (let [dir (str (fs/create-temp-dir {:prefix "relay-wake-group"}))
        out (str (fs/path dir "group"))
        bin (str (fs/path dir "relay"))]
    (try
      (spit bin (str "#!/bin/sh\necho \"$$ $(ps -o pgid= -p $$)\" > '" out "'\n"))
      (fs/set-posix-file-permissions bin "rwx------")
      @(p/process (dispatch/detached (dispatch/wake-argv bin "hub" dir)) {:in "" :out :discard :err :discard})
      (loop [n 0] (when (and (< n 100) (not (fs/exists? out))) (Thread/sleep 20) (recur (inc n))))
      (Thread/sleep 50)
      (let [[pid pgid] (str/split (str/trim (slurp out)) #"\s+")]
        (is (= pid pgid))
        (is (not= pgid (str/trim (:out (p/sh "ps" "-o" "pgid=" "-p" (str (.pid (java.lang.ProcessHandle/current)))))))))
      (finally (fs/delete-tree dir)))))

(deftest a-wake-that-cannot-start-is-still-told
  (with-redefs [dispatch/relay-bin (constantly "/nonexistent/relay")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not start a wake for hub"
                          (dispatch/spawn-wake! {:data-dir "/tmp"} "hub")))))

(deftest a-child-that-cannot-start-does-not-stop-relay-run
  (let [stop (promise)
        done (future (run/run! "/nonexistent/relay" "/data" {:poll-ms 20 :stop stop}))]
    (Thread/sleep 200)
    (is (not (realized? done)) "it carries on, to try again a minute later")
    (deliver stop true)
    (is (= 0 (deref done 5000 ::timeout)))))
