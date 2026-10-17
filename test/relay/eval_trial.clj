(ns relay.eval-trial
  "One trial of an eval case (see relay.eval): reading a case, playing it
  once in a fresh staging directory, scoring it, and checking a case's
  oracle and foils against the mock model. relay.eval-runner is the
  caller that runs many trials and records them."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [relay.eval :as eval]
            [relay.scenario :as scenario]
            [relay.scenario-runner :as runner]
            [relay.staging :as staging]
            [relay.test-support :as support]))

(defn usage-error [msg] (ex-info msg {:relay/usage true}))

(def defaults-file (str runner/scenario-dir "/eval-defaults.edn"))

(def trial-limits
  "agentloop's limits for every wake in a trial: far above what a case
  needs, so a wake that loops stops long before it costs much."
  {:max-turns 30 :max-tool-calls 40 :max-tokens 600000})

(defn read-defaults []
  (let [d (edn/read-string (slurp defaults-file))
        problems (eval/defaults-problems d)]
    (when (seq problems) (throw (usage-error (str "eval-defaults.edn: " (str/join " " problems)))))
    d))

(defn eval-case-files
  "The scenario files that are eval cases."
  []
  (filter #(:eval (edn/read-string (slurp %))) (runner/scenario-files)))

(defn read-case
  "An eval case, checked as a scenario and as a case."
  [file defaults]
  (let [c (runner/read-scenario file)
        problems (eval/case-problems c defaults)]
    (when (seq problems)
      (throw (usage-error (str (runner/scenario-name file) ": " (str/join " " problems)))))
    c))

;; --- one trial

(def staged-harness
  (get-in (staging/settings {:data-dir "/staging"}) [:harnesses :agentloop]))

(defn staging-model [] (:model staged-harness))

(defn run-trial!
  "Plays a case once in `dir` (a fresh directory) and scores it. `script`
  replaces the case's oracle (a foil's), in mock mode. Returns {:errors
  [...]} when it produced no score, else {:scores :passed :totals}; both
  with :dir and :world."
  [file kase {:keys [dir live? model script repo-root]}]
  (let [asked (or model (staging-model))
        run (runner/run-scenario! file (cond-> {:data dir :fresh? true :keep? true :live? live?
                                                :harness (cond-> {:limits trial-limits} support/agentloop-bin (assoc :bin support/agentloop-bin))
                                                :repo-root (or repo-root ".")}
                                         live? (assoc :model asked)
                                         script (assoc :script script)))
        world (:world run)
        errors (eval/trial-errors run asked)
        totals (eval/trial-usage world)]
    (if (seq errors)
      {:errors errors :dir (:dir run) :world world :totals totals}
      (let [scores (eval/grade world (eval/graders kase (read-defaults)))]
        {:scores scores :passed (eval/passed? scores) :totals totals :dir (:dir run) :world world}))))

(defn mock-verdicts
  "Plays a case's oracle and every foil against the mock model, each in a
  temporary directory removed afterwards. Returns {:oracle problems :foils
  {name problems}}: the oracle must pass every check, and each foil must
  fail exactly its :fails."
  [file]
  (let [defaults (read-defaults)
        kase (read-case file defaults)
        play (fn [script]
               (let [dir (str (fs/create-temp-dir {:prefix (str "relay-eval-" (runner/scenario-name file) "-")}))]
                 (try (run-trial! file kase (cond-> {:dir dir} script (assoc :script script)))
                      (finally (fs/delete-tree dir)))))
        problems (fn [{:keys [errors scores]} fails]
                   (if (seq errors)
                     (mapv #(str (name (:class %)) " error: " (:message %)) errors)
                     (eval/foil-problems scores fails)))]
    {:oracle (problems (play nil) #{})
     :foils (into (sorted-map) (for [[nm {:keys [model fails]}] (:foils kase)]
                                 [nm (problems (play model) fails)]))}))
