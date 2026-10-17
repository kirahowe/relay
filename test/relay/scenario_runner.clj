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
  answered by the runner, since a scenario is the operator. Anything else
  the terminal asks, such as review's choice for each finding, is answered
  from the step's :tty.

  A tick checks each new research report with the staging :verifier, in
  this process, or as a `relay verify` subprocess it waits for when the
  step says :spawn :process."
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
            [relay.staging :as staging]
            [relay.test-support :as support])
  (:import (java.time Duration Instant)))

(def scenario-dir "test/scenarios")

(def not-scenarios
  "Files in the scenario directory that are not scenarios."
  #{"eval-defaults.edn"})

(defn scenario-files []
  (sort (map str (remove #(not-scenarios (str (fs/file-name %))) (fs/glob scenario-dir "*.edn")))))

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
  "A terminal that types back whatever code it is asked for, and to any
  other question the next of `answers`, a step's :tty. Once they run out
  it types nothing, as a closed terminal does."
  [answers]
  (let [last-said (atom "")
        left (atom (seq answers))]
    {:say (fn [text] (reset! last-said text))
     :ask (fn []
            (or (second (re-find #"Type ([A-Z0-9]{4}) to [^\n]*$" @last-said))
                (ffirst (swap-vals! left next))))}))

;; --- a run

(defn- json-lines [file]
  (if (fs/exists? file)
    (vec (keep #(try (json/parse-string % true) (catch Exception _ nil))
               (remove str/blank? (str/split-lines (slurp (str file))))))
    []))

(defn world
  "What a scenario's assertions see in a data directory: the state, the
  events, what the notifier sent, each wake's transcript entries by wake id,
  and `now`, relay's clock, when given."
  ([dir] (world dir nil))
  ([dir now]
   (let [events (log/read-events dir)
         state (core/replay events)]
     {:state state
      :events events
      :sent (json-lines (fs/path dir "notifications.log"))
      :transcripts (into {} (for [{:keys [id role]} (vals (:wakes state))
                                  :let [f (fs/path dir "runs" role (str id) "transcript.jsonl")]
                                  :when (fs/exists? f)]
                              [id (json-lines f)]))
      :now now})))

(defn- same-dir? [a b]
  (let [home (System/getProperty "user.home")]
    (= (staging/canonical a home) (staging/canonical b home))))

(defn run-command!
  "A relay command line as the operator, in this process, with an explicit
  --data. Returns {:exit :out :err}. The operator's terminal is answered
  automatically, so before that is bound the data directory the command
  would really use is worked out the way relay works it out, and the
  command is refused unless it is this scenario's own."
  [{:keys [dir clock]} args stdin & [tty-answers]]
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
      (binding [relay/*tty* #(operator-tty tty-answers)
                relay/*stdin* stdin
                relay/*clock* clock
                relay/*config-file* no-config
                ;; status reads the pause file; not the operator's real one.
                relay/*pause-file* (str (fs/path dir "no-pause-file"))]
        (try (relay/execute line)
             (catch clojure.lang.ExceptionInfo e
               {:exit 1 :out [] :err [(ex-message e)]}))))))

(defn- tick!
  "One dispatcher pass, with each wake run to its end in this process.
  Returns a line per wake."
  [{:keys [dir clock]}]
  (let [woke (atom [])
        sys (inbound/system dir {:clock clock})]
    (dispatch/tick! sys {:spawn-verify! (fn [sys report]
                                          (let [r (dispatch/verify! sys report {})]
                                            (swap! woke conj (str "verify report " report ": " (name (:result r))))))
                         :spawn! (fn [sys role]
                                   (let [r (dispatch/wake! sys role {:dispatched true})]
                                     (swap! woke conj (str role ": " (or (:outcome r) (name (:result r)))
                                                           (when (:left-open r) (str ", " (:left-open r) " memo(s) left open"))))))})
    @woke))

(def process-wait-ms 180000)

(defn- tick-with-processes!
  "One dispatcher pass exactly as relay tick does it: each wake is a `relay
  wake` subprocess, and each report check a `relay verify` one, started
  with the real spawn and the real bin/relay. Waits for them. A process
  that cannot start, or exits badly, is a failure."
  [{:keys [dir clock]}]
  (let [sys (inbound/system dir {:clock clock})
        started (atom [])
        spawn (fn [sys role]
                (let [process (dispatch/spawn-wake! sys role)]
                  (swap! started conj [(str "relay wake " role) process])
                  process))
        spawn-verify (fn [sys report]
                       (swap! started conj [(str "relay verify " report) (dispatch/spawn-verify! sys report)]))
        roles (dispatch/tick! sys {:spawn! spawn :spawn-verify! spawn-verify})
        results (doall (for [[what process] @started]
                         [what (deref process process-wait-ms ::timeout)]))]
    {:lines (vec (for [[what r] results]
                   (str what " " (if (= ::timeout r) "timed out" (str "exited " (:exit r))))))
     :failures (vec (for [[what r] results
                          :when (or (= ::timeout r) (not (zero? (:exit r))))]
                      (str "The " what " process "
                           (if (= ::timeout r) "did not finish" (str "exited " (:exit r))) ".")))
     :roles roles}))

(defn- write-clock!
  "Tells test/bin/relay, and so every relay process a wake starts, the time."
  [dir ^Instant at]
  (spit (str (fs/path dir "staging-clock.edn")) (pr-str {:at (str at)})))

(defn- step!
  "Runs one step. Returns {:log [lines] :failures [lines]}."
  [{:keys [dir offset] :as ctx} step]
  (cond
    (:run step)
    (let [state (:state (world dir))
          args (mapv str (scenario/resolve-refs state (:run step)))
          {:keys [exit out err]} (run-command! ctx args (:stdin step) (:tty step))
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
        (when (:stopped-clock? ctx) (write-clock! dir ((:clock ctx))))
        {:log [(str "advance " (:advance-minutes step) " minute(s)")]})

    (:assert step)
    {:failures (scenario/check-all (world dir ((:clock ctx))) (:assert step))}))

;; --- the harness: mock or real

(defn- update-settings! [dir f]
  (let [file (str (fs/path dir "relay.edn"))]
    (spit file (pr-str (f (edn/read-string (slurp file)))))))

(defn- use-mock!
  "Points the staging harness at a mock server and a made-up key, and at
  the agentloop the tests run (test-support, --agentloop) when there is one."
  [dir mock-url]
  (update-settings! dir #(update-in % [:harnesses :agentloop]
                                    (fn [h] (cond-> (assoc h :base-url mock-url
                                                           :api-key-command ["echo" "sk-mock-key"]
                                                           :retry {:retries 0})
                                              support/agentloop-bin (assoc :bin support/agentloop-bin))))))

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
  For evals also :script (replies to play instead of the scenario's
  :model), :model (the model a live run asks for, instead of staging's),
  :harness (more settings for the staging harness, such as :limits),
  :fresh? (the :data directory was just made empty by the caller, so the
  staging checks are skipped) and :keep? (never delete the directory).
  Returns {:pass? :dir :log :failures :assert-failures :requests :world},
  :world being what the assertions saw at the end and :assert-failures the
  last part of :failures, from the scenario's final :assert."
  [file {:keys [live? data force? operator-config script harness fresh? keep?] :as opts}]
  (let [{:keys [steps] :as s} (read-scenario file)
        model (if (contains? opts :script) script (:model s))
        _ (when (and live? (false? (:live? s)))
            (throw (ex-info (str (scenario-name file) " cannot run live: it needs a scripted model.")
                            {:relay/usage true})))
        temp? (nil? data)
        dir (if temp?
              (str (fs/create-temp-dir {:prefix (str "relay-scenario-" (scenario-name file) "-")}))
              data)
        fixed (scenario/clock-instant s)
        started (or fixed (Instant/now))
        offset (atom 0)
        clock #(.plus started (Duration/ofMinutes @offset))
        _ (staging/init! (cond-> {:dir dir :force? force? :operator-config operator-config
                                  :repo-root (:repo-root opts ".")}
                           (or temp? fresh?) (assoc :check? false)
                           fixed (assoc :clock clock)))
        dir (staging/canonical dir (System/getProperty "user.home"))
        ctx {:dir dir :clock clock :offset offset :stopped-clock? (some? fixed)}
        mock-model (when-not live? (scripted-model model dir))
        server (when mock-model (mock/start! (:responder mock-model)))
        before (System/getProperty "relay.bin")
        log-lines (atom [])
        failures (atom [])
        assert-failures (atom [])]
    (when server (use-mock! dir (:url server)))
    (update-settings! dir #(cond-> (scenario/merge-settings % (:settings s))
                             (and live? (:model opts)) (assoc-in [:harnesses :agentloop :model] (:model opts))
                             harness (update-in [:harnesses :agentloop] merge harness)))
    (when fixed (write-clock! dir started))
    ;; With a stopped clock, every relay process a wake starts reads it.
    (System/setProperty "relay.bin" (str (fs/absolutize (str (:repo-root opts ".")
                                                             (if fixed "/test/bin/relay" "/bin/relay")))))
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
      (swap! assert-failures into (scenario/check-all (world dir (clock)) (:assert s)))
      (when mock-model (swap! failures into @(:problems mock-model)))
      (finally
        (when server ((:stop server)))
        (if before (System/setProperty "relay.bin" before) (System/clearProperty "relay.bin"))))
    (let [all-failures (into @failures @assert-failures)
          pass? (empty? all-failures)
          end (world dir (clock))
          delete? (and temp? pass? (not keep?))]
      (when delete? (fs/delete-tree dir))
      {:pass? pass? :dir (when-not delete? dir) :log @log-lines :failures all-failures
       :assert-failures @assert-failures
       :requests (when server (count @(:requests server)))
       :world end})))

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

(defn scenarios-main
  "bb scenarios [--agentloop <path>]: every scenario against the mock model,
  with that agentloop build instead of ../agentloop's."
  [& args]
  (let [[flag path] *command-line-args*
        _ (when (= "--agentloop" flag) (support/use-agentloop! path))
        results (for [f (scenario-files)]
                  (let [r (try (run-scenario! f {})
                               (catch Exception e {:pass? false :failures [(ex-message e)]}))]
                    (report! (scenario-name f) r)
                    r))
        results (doall results)]
    (println (str (count (filter :pass? results)) " of " (count results) " scenarios passed"))
    (System/exit (if (every? :pass? results) 0 1))))
