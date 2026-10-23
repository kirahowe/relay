(ns relay.service
  "bb service: stop, start or restart the launchd job that runs relay serve,
  or say whether it runs. Stopping it ends every live conversation, which a
  deploy needs (it waits for every running wake). The decisions are pure;
  -main runs launchctl."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def label "relay.serve")

(defn steps
  "What `action` does when the job is running or not: a vector of :stop,
  :wait-stopped and :start, or nil for an action there is none of. Stopping
  a stopped job and starting a running one do nothing."
  [action running?]
  (case action
    "stop" (if running? [:stop :wait-stopped] [])
    "start" (if running? [] [:start])
    "restart" (if running? [:stop :wait-stopped :start] [:start])
    "status" []
    nil))

(defn parse-status
  "The job's state from `launchctl print`'s output: {:running? :pid}."
  [out]
  (let [pid (some->> (re-find #"(?m)^\s*pid = (\d+)" out) second parse-long)
        state (some->> (re-find #"(?m)^\s*state = (\S+)" out) second)]
    {:running? (and (some? pid) (= "running" state)) :pid pid}))

(defn status-line
  "How `bb service status` puts it."
  [{:keys [loaded? running? pid]}]
  (cond
    (not loaded?) "relay serve is stopped (its launchd job is not loaded)."
    running? (str "relay serve is running (pid " pid ").")
    :else "relay serve's launchd job is loaded but not running."))

;; --- the shell

(defn- uid [] (str/trim (:out (p/sh "id" "-u"))))

(defn- target [] (str "gui/" (uid) "/" label))

(defn- plist [] (str (System/getProperty "user.home") "/Library/LaunchAgents/" label ".plist"))

(defn- current
  "Whether the job is loaded, and, from launchctl, whether it runs."
  []
  (let [{:keys [exit out]} (p/sh "launchctl" "print" (target))]
    (if (zero? exit)
      (assoc (parse-status out) :loaded? true)
      {:loaded? false :running? false})))

(defn- fail! [msg]
  (binding [*out* *err*] (println msg))
  (System/exit 1))

(defn- sh! [& argv]
  (let [{:keys [exit out err]} (apply p/sh argv)]
    (when-not (zero? exit)
      (fail! (str (str/join " " argv) " failed: " (str/trim (str err out)))))))

(defn- wait-stopped!
  "Waits up to 15 seconds for the job to be gone, so a start that follows
  does not find the old one still loaded."
  []
  (loop [n 0]
    (cond
      (not (:loaded? (current))) nil
      (>= n 30) (fail! "relay serve did not stop within 15 seconds.")
      :else (do (Thread/sleep 500) (recur (inc n))))))

(defn -main [& [action]]
  (let [now (current)
        todo (steps action (:loaded? now))]
    (when (nil? todo)
      (fail! "usage: bb service stop|start|restart|status"))
    (when (and (some #{:start} todo) (not (fs/exists? (plist))))
      (fail! (str "There is no " (plist) ". README.md says how to install the relay serve job.")))
    (doseq [s todo]
      (case s
        :stop (do (sh! "launchctl" "bootout" (target))
                  (println "Stopped relay serve. Every live conversation has ended."))
        :wait-stopped (wait-stopped!)
        :start (do (sh! "launchctl" "bootstrap" (str "gui/" (uid)) (plist))
                   (println "Started relay serve."))))
    (when (and (empty? todo) (not= "status" action))
      (println (str "Nothing to do: " (status-line now))))
    (when (or (= "status" action) (some #{:start} todo))
      (Thread/sleep (if (= "status" action) 0 1000))
      (println (status-line (current))))))
