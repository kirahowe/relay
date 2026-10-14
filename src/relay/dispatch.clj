(ns relay.dispatch
  "The dispatcher. Nothing runs between ticks: something outside relay
  (launchd, once a minute) runs `relay tick`, which brings the log up to date
  with the clock and starts a wake for each role with work waiting. Each wake
  runs in its own process, so a slow role holds up neither the others nor
  the next tick."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [relay.config :as config]
            [relay.core :as core]
            [relay.harness :as harness]
            [relay.harness.agentloop]
            [relay.inbound :as inbound]
            [relay.prompt :as prompt]
            [relay.tools :as tools])
  (:import (java.time Duration Instant)))

;; --- whether a wake's process is still there

(defn current-process
  "This process's pid and start time. The start time tells a live process
  apart from a later one that was given the same pid."
  []
  (let [me (java.lang.ProcessHandle/current)]
    {:pid (.pid me)
     :pid-start (some-> (.startInstant (.info me)) (.orElse nil) str)}))

(defn alive? [pid pid-start]
  (boolean
   (when pid
     (let [handle (java.lang.ProcessHandle/of (long pid))]
       (when (.isPresent handle)
         (let [h (.get handle)
               started (some-> (.startInstant (.info h)) (.orElse nil) str)]
           (and (.isAlive h)
                (or (nil? pid-start) (nil? started) (= pid-start started)))))))))

(defn lost-wakes
  "Running wakes whose process has gone, so nobody will ever end them."
  [state]
  (vec (for [{:keys [id pid pid-start status]} (vals (:wakes state))
             :when (and (= :running status) (not (alive? pid pid-start)))]
         id)))

;; --- one wake

(defn relay-bin
  "The relay command, for harnesses to run tool calls with."
  []
  (or (System/getProperty "relay.bin") "relay"))

(defn shared-instructions []
  (slurp (io/resource "AGENTS.md")))

(defn- operator-additions [data-dir]
  (let [f (fs/path data-dir "AGENTS.md")]
    (when (fs/exists? f) (slurp (str f)))))

(defn- now ^Instant [{:keys [clock]} state]
  (let [t ^Instant (clock)]
    (if (and (:last-at state) (.isBefore t (:last-at state))) (:last-at state) t)))

(defn wake-request
  "Everything a harness needs for one wake."
  [{:keys [data-dir config] :as sys} state {:keys [id role memos note resumes harness]}]
  (let [role-row (core/role state role)
        run-dir (fs/path data-dir "runs" role (str id))
        resumed (when resumes (get-in state [:wakes resumes]))]
    (fs/create-dirs run-dir)
    {:harness (get-in config [:harnesses (keyword harness)])
     :harness-name harness
     :role role-row
     :model (or (:model role-row) (get-in config [:harnesses (keyword harness) :model]))
     :system-prompt (prompt/instructions {:shared (shared-instructions)
                                          :operator-additions (operator-additions data-dir)
                                          :notes (core/notes state role)
                                          :granted (tools/grants-summary config role)}
                                         role-row)
     :prompt (prompt/wake {:role role-row
                           :memos (map #(core/memo state %) memos)
                           :note note
                           :now (now sys state)
                           :zone (config/zone config)
                           :resumed? (some? resumes)})
     :tools (tools/manifest config role)
     :env (harness/base-env {"RELAY_DATA" (str (fs/absolutize data-dir))
                             "RELAY_ROLE" role
                             "RELAY_WAKE" (str id)
                             "RELAY_BIN" (relay-bin)})
     :run-dir (str run-dir)
     :session-dir (str (fs/path data-dir "runs" role "sessions"))
     :resume (:session resumed)
     :timeout-ms (long (* 60 1000 (:wake-timeout-minutes config)))}))

(defn- run-harness
  "Runs the harness, turning anything it throws into a report, so that a
  wake that started always ends."
  [request]
  (try
    (if (:harness request)
      (harness/run! request)
      {:outcome "needs-attention" :exit nil :crashed false
       :said (str "relay.edn has no harness called " (:harness-name request) ".")})
    (catch Exception e
      {:outcome "ran" :exit nil :crashed true
       :said (str "The harness adapter failed: " (or (ex-message e) (class e)))})))

(defn wake!
  "Starts a wake of a role, runs it and ends it. Returns {:result ...}:
  :idle when there was nothing to do, :busy, :paused or :runaway when it was
  not started, and otherwise the wake's id and outcome."
  [sys role-id {:keys [note dispatched]}]
  (let [started (try
                  (inbound/submit! sys {:type :wake/start
                                        :actor (if dispatched core/dispatcher core/operator)
                                        :args (merge {:role role-id :note note
                                                      :dispatched (boolean dispatched)}
                                                     (current-process))})
                  (catch clojure.lang.ExceptionInfo e
                    (if-let [reason (core/rejection e)]
                      {:result reason :message (ex-message e)}
                      (throw e))))]
    (cond
      (map? started) started
      (empty? started) {:result :idle}
      :else
      (let [wake-id (:seq (first started))
            state (inbound/current-state sys)
            report (run-harness (wake-request sys state (get-in state [:wakes wake-id])))]
        (inbound/submit! sys {:type :wake/end :actor core/dispatcher
                              :args (-> (select-keys report [:outcome :exit :crashed :session
                                                             :said :resets-at])
                                        (update :exit #(if (number? %) % (some-> % str)))
                                        (update :resets-at #(some-> % str))
                                        (assoc :wake wake-id
                                               :transcript (some-> (:transcript report) str)))})
        (let [state (inbound/current-state sys)]
          {:result :ran
           :wake wake-id
           :outcome (:outcome report)
           :left-open (count (filter #(= :unread (:status (core/memo state %)))
                                     (get-in state [:wakes wake-id :memos])))})))))

(def ^:private conversation-note
  "The operator has opened a conversation with you. Talk with them directly;
  use your tools as you would in a wake.")

(defn chat!
  "Opens an interactive session as a role on this terminal. It holds the role
  like a wake, so no wake runs alongside it, and carries no memos. Returns
  the harness's exit code."
  [sys role-id]
  (let [started (inbound/submit! sys {:type :wake/start :actor core/operator
                                      :args (merge {:role role-id :chat true
                                                    :note conversation-note}
                                                   (current-process))})
        wake-id (:seq (first started))
        state (inbound/current-state sys)
        request (wake-request sys state (get-in state [:wakes wake-id]))
        exit (try
               (if (:harness request)
                 (harness/chat! (update request :system-prompt str "\n\n" conversation-note "\n"))
                 (throw (ex-info (str "relay.edn has no harness called " (:harness-name request) ".") {})))
               (finally
                 (inbound/submit! sys {:type :wake/end :actor core/dispatcher
                                       :args {:wake wake-id :outcome "ran" :exit nil :crashed false
                                              :said "Conversation ended."}})))]
    exit))

;; --- the tick

(def ^:private stale-after (Duration/ofMinutes 2))

(defn- stranded-notifications
  "Notifications recorded but never pushed, because whoever recorded them
  stopped first. Fresh ones are left to their own process."
  [state ^Instant now]
  (filter #(and (= :pending (:delivery %))
                (.isBefore (.plus ^Instant (:created-at %) stale-after) now))
          (vals (:notifications state))))

(defn spawn-wake!
  "Starts a dispatched wake in its own process and does not wait for it."
  [{:keys [data-dir]} role-id]
  (p/process [(relay-bin) "wake" role-id "--dispatched"]
             {:in "" :out :discard :err :discard
              :extra-env {"RELAY_DATA" (str (fs/absolutize data-dir))}}))

(defn tick!
  "One pass: end wakes whose process has gone, fire due routines, report what
  needs reporting, push notifications left stranded, then start a wake for
  every role with work waiting. Returns the roles woken."
  ([sys] (tick! sys {}))
  ([{:keys [config] :as sys} {:keys [spawn!] :or {spawn! spawn-wake!}}]
   (let [state (inbound/current-state sys)]
     (inbound/submit! sys {:type :tick :actor core/dispatcher
                           :args {:lost-wakes (lost-wakes state)}}))
   (let [state (inbound/current-state sys)
         t (now sys state)]
     (doseq [n (sort-by :id (stranded-notifications state t))]
       (inbound/deliver! sys {:seq (:id n) :data n}))
     (let [roles (core/roles-to-wake state config t)]
       (doseq [r roles] (spawn! sys r))
       roles))))
