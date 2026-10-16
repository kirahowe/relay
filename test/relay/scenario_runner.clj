(ns relay.scenario-runner
  "Runs scenarios (see relay.scenario) end to end on staging data:

    bb scenario <name> [--live] [--data <dir>] [--force]
    bb scenarios

  A scenario is a file test/scenarios/<name>.edn. Each run makes a fresh
  staging data directory (relay.staging), then plays the steps against it.
  Every relay command gets an explicit --data. Ticks run in this process,
  one dispatcher pass at a time with each wake run to its end, and never
  through launchd. A wake is the real thing: relay starts agentloop, and
  agentloop starts `relay tool` for each tool call.

  Ticks run in this process unless a step says {:tick 1 :spawn :process},
  which starts each wake as relay tick does, as a subprocess.

  Without --live the model is a mock Anthropic server that answers from the
  scenario's :model script. With --live it is the real model, using the
  harness and keychain key in the staging relay.edn. A live run spends a
  little money, so scenarios that cannot work against a real model say
  :live? false.

  The operator's terminal confirmations (release, approve, trust) are
  answered by the runner, since a scenario is the operator."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [relay.cli :as relay]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.mock-anthropic :as mock]
            [relay.scenario :as scenario]
            [relay.staging :as staging])
  (:import (java.time Duration Instant)))

(def scenario-dir "test/scenarios")

(defn scenario-files [] (sort (map str (fs/glob scenario-dir "*.edn"))))

(defn scenario-name [file] (str/replace (str (fs/file-name file)) #"\.edn$" ""))

(defn path-options
  "The path options relay would read from these arguments, as keywords. An
  argument list relay cannot parse counts as setting :unparsable."
  [args]
  (try (vec (filter scenario/path-option-keys (keys (relay/parsed-options (mapv str args)))))
       (catch clojure.lang.ExceptionInfo _ [:unparsable])))

(defn- parsed-command
  "The command in a line, or nil when relay cannot parse it."
  [line]
  (try (relay/command-of line) (catch clojure.lang.ExceptionInfo _ nil)))

(defn read-scenario [file]
  (let [s (edn/read-string (slurp (str file)))
        problems (concat (scenario/problems s)
                         (for [{:keys [run] :as step} (:steps s)
                               :when (and (vector? run) (seq (path-options run)))]
                           (str "Step " (pr-str step) " sets " (pr-str (path-options run))
                                ", a path option. Scenarios run only against their own directory.")))]
    (when (seq problems)
      (throw (ex-info (str (scenario-name file) ": " (str/join " " problems)) {:relay/usage true})))
    s))

;; --- the operator at the terminal

(defn- operator-tty
  "A terminal that types back whatever code it is asked for."
  []
  (let [shown (atom "")]
    {:say (fn [text] (swap! shown str text))
     :ask (fn [] (second (re-find #"Type ([A-Z0-9]{4}) to " @shown)))}))

;; --- a run

(defn- world [dir]
  (let [sent-file (fs/path dir "notifications.log")]
    {:state (core/replay (log/read-events dir))
     :events (log/read-events dir)
     :sent (if (fs/exists? sent-file)
             (mapv #(json/parse-string % true) (str/split-lines (slurp (str sent-file))))
             [])}))

(defn- same-dir? [a b]
  (let [home (System/getProperty "user.home")]
    (= (staging/canonical a home) (staging/canonical b home))))

(defn run-command!
  "A relay command line as the operator, in this process, with an explicit
  --data. Returns {:exit :out :err}. The operator's terminal is answered
  automatically, so before that is bound the data directory the command
  would really use is worked out the way relay works it out, and the
  command is refused unless it is this scenario's own."
  [{:keys [dir clock]} args stdin]
  (let [line (into ["--data" dir] args)
        ;; No config file to fall back on, whatever the arguments say.
        no-config (str (fs/path dir "no-operator-config.edn"))
        effective (binding [relay/*config-file* no-config]
                    (try (relay/effective-data-dir line)
                         (catch clojure.lang.ExceptionInfo _ nil)))]
    (cond
      ;; Arguments relay cannot parse are not run.
      (nil? (parsed-command line))
      {:exit 1 :out [] :err [(str "Refused: " (pr-str args) " cannot be parsed.")]}

      (not (scenario/allowed-commands (parsed-command line)))
      {:exit 1 :out [] :err [(str "Refused: " (pr-str args) " is not a command a scenario may run.")]}

      (or (some scenario/path-option? args) (seq (path-options args)))
      {:exit 1 :out [] :err [(str "Refused: " (pr-str args) " sets a path option.")]}

      (not (and effective (same-dir? effective dir)))
      {:exit 1 :out [] :err [(str "Refused: " (pr-str args) " would run against "
                                  (pr-str effective) ", not the scenario's data directory " dir ".")]}

      :else
      (binding [relay/*tty* operator-tty
                relay/*stdin* stdin
                relay/*clock* clock
                relay/*config-file* no-config]
        (try (relay/execute line)
             (catch clojure.lang.ExceptionInfo e
               {:exit 1 :out [] :err [(ex-message e)]}))))))

(defn- tick!
  "One dispatcher pass, with each wake run to its end in this process.
  Returns a line per wake."
  [{:keys [dir clock]}]
  (let [woke (atom [])
        sys (inbound/system dir {:clock clock})]
    (dispatch/tick! sys {:spawn! (fn [sys role]
                                   (let [r (dispatch/wake! sys role {:dispatched true})]
                                     (swap! woke conj (str role ": " (or (:outcome r) (name (:result r)))
                                                           (when (:left-open r) (str ", " (:left-open r) " memo(s) left open"))))))})
    @woke))

(def process-wait-ms 180000)

(defn- tick-with-processes!
  "One dispatcher pass exactly as relay tick does it: each wake is a `relay
  wake` subprocess, started with the real spawn and the real bin/relay.
  Waits for them. A wake that cannot start, or exits badly, is a failure."
  [{:keys [dir clock]}]
  (let [sys (inbound/system dir {:clock clock})
        started (atom [])
        spawn (fn [sys role]
                (let [process (dispatch/spawn-wake! sys role)]
                  (swap! started conj [role process])
                  process))
        roles (dispatch/tick! sys {:spawn! spawn})
        results (doall (for [[role process] @started]
                         [role (deref process process-wait-ms ::timeout)]))]
    {:lines (vec (for [[role r] results]
                   (str role ": relay wake " (if (= ::timeout r) "timed out" (str "exited " (:exit r))))))
     :failures (vec (for [[role r] results
                          :when (or (= ::timeout r) (not (zero? (:exit r))))]
                      (str "The relay wake process for " role " "
                           (if (= ::timeout r) "did not finish" (str "exited " (:exit r))) ".")))
     :roles roles}))

(defn- step!
  "Runs one step. Returns {:log [lines] :failures [lines]}."
  [{:keys [dir offset] :as ctx} step]
  (cond
    (:run step)
    (let [state (:state (world dir))
          args (mapv str (scenario/resolve-refs state (:run step)))
          {:keys [exit out err]} (run-command! ctx args (:stdin step))
          expected (:exit step 0)]
      {:log [(str "relay " (str/join " " args) " -> exit " exit)]
       :failures (when (not= expected exit)
                   [(str "relay " (str/join " " args) " exited " exit ", expected " expected ": "
                         (str/join " " (concat out err)))])})

    (and (:tick step) (= :process (:spawn step)))
    (let [ticks (doall (for [_ (range (:tick step))] (tick-with-processes! ctx)))]
      {:log (vec (for [[i {:keys [lines roles]}] (map-indexed vector ticks)
                       line (cons (str "tick " (inc i) " (subprocesses)" (when (empty? roles) ": nobody to wake"))
                                  (map #(str "  " %) lines))]
                   line))
       :failures (vec (mapcat :failures ticks))})

    (:tick step)
    {:log (vec (for [i (range (:tick step))
                     line (let [woke (tick! ctx)]
                            (cons (str "tick " (inc i) (when (empty? woke) ": nobody to wake"))
                                  (map #(str "  " %) woke)))]
                 line))}

    (:advance-minutes step)
    (do (swap! offset + (:advance-minutes step))
        {:log [(str "advance " (:advance-minutes step) " minute(s)")]})

    (:assert step)
    {:failures (scenario/check-all (world dir) (:assert step))}))

;; --- the harness: mock or real

(defn- use-mock!
  "Points the staging harness at a mock server and a made-up key."
  [dir mock-url]
  (let [file (str (fs/path dir "relay.edn"))
        settings (edn/read-string (slurp file))]
    (spit file (pr-str (update-in settings [:harnesses :agentloop] assoc
                                  :base-url mock-url
                                  :api-key-command ["echo" "sk-mock-key"]
                                  :retry {:retries 0})))))

(defn- scripted-model
  "A mock-anthropic responder playing the scenario's script. Returns it with
  an atom of the problems met on the way."
  [script dir]
  (let [used (atom {})
        problems (atom [])]
    {:problems problems
     :responder (fn [body]
                  ;; Wakes run as separate processes, so requests can arrive
                  ;; together: take this request's place in the script
                  ;; atomically, then choose the reply for that place.
                  (let [role (scenario/role-of body)
                        [before _] (swap-vals! used (fn [u] (second (scenario/next-reply script u role))))
                        [reply _] (scenario/next-reply script before role)]
                    (try
                      (scenario/resolve-refs (:state (world dir)) reply)
                      (catch clojure.lang.ExceptionInfo e
                        (swap! problems conj (str role "'s reply: " (ex-message e)))
                        {:status 400 :type "invalid_request_error" :message (ex-message e)}))))}))

(defn run-scenario!
  "Plays a scenario. Options: :live?, :data (a directory to make the staging
  data in, else a temporary one), :force?, :operator-config, :repo-root.
  Returns {:pass? :dir :log :failures}."
  [file {:keys [live? data force? operator-config] :as opts}]
  (let [{:keys [steps model] :as s} (read-scenario file)
        _ (when (and live? (false? (:live? s)))
            (throw (ex-info (str (scenario-name file) " cannot run live: it needs a scripted model.")
                            {:relay/usage true})))
        temp? (nil? data)
        dir (if temp?
              (str (fs/create-temp-dir {:prefix (str "relay-scenario-" (scenario-name file) "-")}))
              data)
        _ (staging/init! (cond-> {:dir dir :force? force? :operator-config operator-config
                                  :repo-root (:repo-root opts ".")}
                           temp? (assoc :check? false)))
        dir (staging/canonical dir (System/getProperty "user.home"))
        started (Instant/now)
        offset (atom 0)
        clock #(.plus started (Duration/ofMinutes @offset))
        ctx {:dir dir :clock clock :offset offset}
        mock-model (when-not live? (scripted-model model dir))
        server (when mock-model (mock/start! (:responder mock-model)))
        before (System/getProperty "relay.bin")
        log-lines (atom [])
        failures (atom [])]
    (when server (use-mock! dir (:url server)))
    (System/setProperty "relay.bin" (str (fs/absolutize (str (:repo-root opts ".") "/bin/relay"))))
    (try
      ;; A step that throws (a wake that cannot start, say) fails the
      ;; scenario and ends the run; later steps would only pile up noise.
      (reduce (fn [_ step]
                (try (let [{l :log f :failures} (step! ctx step)]
                       (swap! log-lines into l)
                       (swap! failures into f))
                     (catch Exception e
                       (swap! failures conj (str "Step " (pr-str step) " threw: " (ex-message e)))
                       (reduced nil))))
              nil steps)
      (swap! failures into (scenario/check-all (world dir) (:assert s)))
      (when mock-model (swap! failures into @(:problems mock-model)))
      (finally
        (when server ((:stop server)))
        (if before (System/setProperty "relay.bin" before) (System/clearProperty "relay.bin"))))
    (let [pass? (empty? @failures)]
      (when (and temp? pass?) (fs/delete-tree dir))
      {:pass? pass? :dir (when-not (and temp? pass?) dir) :log @log-lines :failures @failures
       :requests (when server (count @(:requests server)))})))

;; --- bb scenario, bb scenarios

(defn- report! [name {:keys [pass? dir log failures requests]}]
  (doseq [l log] (println (str "  " l)))
  (when requests (println (str "  model requests: " requests)))
  (doseq [f failures] (println (str "  FAIL " f)))
  (println (str (if pass? "PASS " "FAIL ") name (when dir (str "   (data kept in " dir ")")))))

(def ^:private spec {:coerce {:live :boolean :data :string :force :boolean :operator-config :string}})

(defn- find-file [name]
  (let [f (str scenario-dir "/" name ".edn")]
    (when-not (fs/exists? f)
      (throw (ex-info (str "No scenario " name ". There are: "
                           (str/join ", " (map scenario-name (scenario-files))) ".")
                      {:relay/usage true})))
    f))

(defn- guarded [f]
  (try (f)
       (catch clojure.lang.ExceptionInfo e
         (binding [*out* *err*] (println (ex-message e)))
         (System/exit 1))))

(defn scenario-main [& args]
  (let [{[name] :args o :opts} (cli/parse-args args spec)]
    (guarded
     #(do (when-not name (throw (ex-info "usage: bb scenario <name> [--live] [--data <dir>] [--force]" {:relay/usage true})))
          (let [r (run-scenario! (find-file name) {:live? (:live o) :data (:data o) :force? (:force o)
                                                   :operator-config (:operator-config o)})]
            (report! name r)
            (System/exit (if (:pass? r) 0 1)))))))

(defn scenarios-main [& _]
  (let [results (for [f (scenario-files)]
                  (let [r (try (run-scenario! f {})
                               (catch Exception e {:pass? false :failures [(ex-message e)]}))]
                    (report! (scenario-name f) r)
                    r))
        results (doall results)]
    (println (str (count (filter :pass? results)) " of " (count results) " scenarios passed"))
    (System/exit (if (every? :pass? results) 0 1))))
