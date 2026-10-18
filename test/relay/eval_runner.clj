(ns relay.eval-runner
  "Runs eval cases (see relay.eval) and records the results:

    bb eval [<case> ...] [--out <dir>] [--live] [--trials n] [--model m] [--effort e]
            [--max-cost usd] [--agentloop <path>]

  Each trial is one play of a case in its own fresh staging directory,
  <out>/<run-id>/trials/<case>/<n>/, kept afterwards (it is artificial
  data). Without --live the model is the mock server playing the case's
  oracle script. With --live it is the real model: claude-haiku-5-5 unless
  --model names another, at the staging harness's effort (low) unless
  --effort names another, the key coming from the keychain item
  relay-anthropic through the staging harness's :api-key-command. Nothing
  here reads the operator's relay.edn, config or ~/Sync.

  A trial is an error, not a fail, when a precondition (the case's
  :assert) did not hold, a step failed, a wake ended needs-attention,
  limited or unreachable, a wake hit its token or turn limit, or a model
  other than the one asked for served it. An error is run once more and
  then left out of pass rates; errors are counted beside them.

  --max-cost stops starting trials once the spend so far plus the dearest
  trial so far would pass it. Cost is computed from the usage agentloop
  recorded for each request, at relay.transcript's prices.

  The run directory holds run.json (what was run, on what), results.jsonl
  (one row per scored trial, in Inspect AI's sample shape), errors.jsonl,
  traces.otlp.jsonl (see relay.eval-otel), summary.json and prompt-surface.edn (what every staging role sees before
  any memo, whose digest is in run.json)."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [relay.config :as config]
            [relay.dispatch :as dispatch]
            [relay.eval :as eval]
            [relay.eval-otel :as eval-otel]
            [relay.eval-trial :as trial]
            [relay.inbound :as inbound]
            [relay.render :as render]
            [relay.scenario :as scenario]
            [relay.scenario-runner :as runner]
            [relay.staging :as staging]
            [relay.test-support :as support])
  (:import (java.security MessageDigest)
           (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def default-live-model "claude-haiku-5-5")

(def default-out
  "Where run records go when --out is not given."
  "~/code/projects/.relay-evals")

;; --- what the run ran on

(defn- sha256 [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn prompt-surface
  "What each staging role sees before any memo: its system prompt and the
  tools it is offered, as sent to agentloop. Made in a scratch staging
  directory under `scratch`."
  [scratch repo-root]
  (let [dir (staging/init! {:dir (str (fs/path scratch "prompt-surface")) :check? false :repo-root repo-root})
        sys (inbound/system dir)
        state (inbound/current-state sys)
        inputs (assoc (dispatch/wake-inputs sys) :now (Instant/parse "2026-01-01T00:00:00Z"))]
    (try
      (into (sorted-map)
            (for [role (sort (keys (:roles state)))
                  :let [req (dispatch/wake-request sys state {:id 0 :role role :memos [] :harness "agentloop"} inputs)]]
              [role {:system-prompt (:system-prompt req)
                     :tools (mapv #(select-keys % [:name :description :parameters]) (:tools req))}]))
      (finally (fs/delete-tree dir)))))

(defn surface-digests [surface repo-root]
  {:prompt-digest (sha256 (pr-str surface))
   :agents-md (sha256 (slurp (str (fs/path repo-root "resources" "AGENTS.md"))))
   :hub-md (sha256 (slurp (str (fs/path repo-root "setup" "hub.md"))))
   :tools (sha256 (pr-str (into (sorted-map) (for [[r {t :tools}] surface] [r t]))))})

(defn- commit-of
  "A checkout's jj commit id, read without snapshotting anything."
  [dir]
  (try
    (let [{:keys [exit out]} (p/shell {:dir (str dir) :out :string :err :string :continue true}
                                      "jj" "--ignore-working-copy" "--no-pager" "log" "-r" "@" "--no-graph"
                                      "-T" "change_id.short(8) ++ \" \" ++ commit_id.short(12)")]
      (when (zero? exit) (str/trim out)))
    (catch Exception _ nil)))

;; --- where records go

(defn out-problem
  "Why run records may not go in `out`, or nil. Never in or around the
  places staging data may not go, and never inside the repository."
  [out repo-root home]
  (let [out (staging/canonical out home)
        root (staging/canonical repo-root home)
        under? (fn [a b] (or (= a b) (str/starts-with? a (str b "/"))))]
    (or (when (under? out root) (str out " is inside the relay repository; put run records outside it."))
        (staging/target-problem {:dir out :protected (staging/protected-roots home []) :entries nil}))))

(defn- run-id []
  (str (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'") (.atOffset (Instant/now) ZoneOffset/UTC))
       "-" (subs (str (random-uuid)) 0 4)))

(defn- write-json [file x] (spit (str file) (json/generate-string x {:pretty true})))
(defn- append-json-line [file x] (spit (str file) (str (json/generate-string x) "\n") :append true))

(defn- printable [x]
  (walk/postwalk #(cond (keyword? %) (name %) (instance? Instant %) (str %) :else %) x))

;; --- a run

(defn over-budget?
  "Whether starting another trial could pass `max-cost`: the spend recorded
  so far plus the dearest trial so far, as the estimate of the next one.
  Pure. Nil means no limit. Before any trial has run both are zero, so the
  first trial always starts."
  [{:keys [spent dearest]} max-cost]
  (boolean (and max-cost (> (+ spent dearest) max-cost))))

(defn- record-errors! [dir cid t {:keys [errors] :as r} rerun?]
  (doseq [e errors]
    (append-json-line (fs/path dir "errors.jsonl")
                      (printable (assoc e :id cid :epoch t :data_dir (:dir r) :rerun rerun?)))))

(defn- trial-line [cid t r]
  (if (:errors r)
    (str cid " #" t " ERROR " (str/join "; " (map :message (:errors r))))
    (str cid " #" t " " (if (:passed r) "PASS" "FAIL")
         (format "  $%.4f  %d wake(s), %d request(s)"
                 (double (or (get-in r [:totals :cost-usd]) 0.0))
                 (get-in r [:totals :wakes]) (get-in r [:totals :requests]))
         (let [failed (for [[g s] (:scores r) :when (= :fail (:value s))] (name g))]
           (when (seq failed) (str "  failed: " (str/join ", " failed)))))))

(defn- play-trial!
  "One trial of a case, run once more if it errored and the budget allows.
  Updates `budget` ({:spent :dearest}) and returns the last attempt."
  [dir file kase t {:keys [live? model effort repo-root max-cost]} budget]
  (let [cid (get-in kase [:eval :id])
        attempt (fn [suffix]
                  (let [tdir (str (fs/path dir "trials" cid (str t suffix)))]
                    (fs/create-dirs tdir)
                    (let [r (try (trial/run-trial! file kase {:dir tdir :live? live? :model model :effort effort
                                                                         :repo-root repo-root})
                                 (catch Exception e
                                   {:errors [{:class :step :message (str "The trial threw: " (ex-message e))}]
                                    :dir tdir}))
                          c (or (get-in r [:totals :cost-usd]) 0.0)]
                      (swap! budget #(-> % (update :spent + c) (update :dearest max c)))
                      r)))
        first-try (attempt "")]
    (if-not (:errors first-try)
      first-try
      (do (record-errors! dir cid t first-try false)
          (if (over-budget? @budget max-cost)
            first-try
            (let [again (attempt "-rerun")]
              (record-errors! dir cid t again true)
              again))))))

(defn- agentloop-for-traces
  "The agentloop binary the run used, for `agentloop otel`."
  []
  (let [bin (or support/agentloop-bin (:bin trial/staged-harness))]
    (when (and bin (fs/executable? bin)) (str bin))))

(defn- record-traces!
  "Appends a trial's lines to traces.otlp.jsonl. A wake whose export fails
  is reported on stderr and left out; the run goes on."
  [dir bin run-id kase t r started ended]
  (let [{:keys [lines problems]}
        (eval-otel/trial-lines bin {:run-id run-id :case-id (get-in kase [:eval :id]) :tier (get-in kase [:eval :tier])
                                    :version (get-in kase [:eval :version]) :trial t :scores (:scores r)
                                    :errors (:errors r) :started started :ended ended :data-dir (:dir r)})]
    (spit (str (fs/path dir "traces.otlp.jsonl")) (apply str (map #(str % "\n") lines)) :append true)
    (doseq [pr problems] (binding [*out* *err*] (println pr)))))

(defn run!
  "Runs `files` (eval cases) and writes the records. Returns {:run :summary
  :dir}."
  [files {:keys [out live? model effort trials max-cost repo-root args]}]
  (let [repo-root (or repo-root ".")
        defaults (trial/read-defaults)
        cases (mapv (fn [f] [f (trial/read-case f defaults)]) files)
        id (run-id)
        dir (str (fs/path (staging/canonical out (System/getProperty "user.home")) id))
        _ (fs/create-dirs dir)
        _ (spit (str (fs/path dir "errors.jsonl")) "")
        _ (spit (str (fs/path dir "traces.otlp.jsonl")) "")
        trace-bin (agentloop-for-traces)
        surface (prompt-surface dir repo-root)
        _ (spit (str (fs/path dir "prompt-surface.edn")) (with-out-str (pprint/pprint surface)))
        asked (if live? (or model default-live-model) (trial/staging-model))
        effort (or (when live? effort) (:effort trial/staged-harness))
        opts {:live? live? :model (when live? asked) :effort effort :repo-root repo-root :max-cost max-cost}
        run-info (merge {:run-id id :started (str (Instant/now)) :command (into ["bb" "eval"] args)
                         :live (boolean live?) :model asked :effort effort
                         :trials trials :max-cost-usd max-cost
                         :relay-commit (commit-of repo-root)
                         :agentloop-bin support/agentloop-bin
                         :agentloop-commit (commit-of (if support/agentloop-bin
                                                        (fs/parent (fs/parent support/agentloop-bin))
                                                        (fs/path repo-root ".." "agentloop")))
                         :cases (into (sorted-map)
                                      (for [[f c] cases]
                                        [(get-in c [:eval :id]) {:file (str f) :version (get-in c [:eval :version])
                                                                 :digest (sha256 (slurp (str f)))}]))}
                        (surface-digests surface repo-root))
        budget (atom {:spent 0.0 :dearest 0.0})
        stopped (atom nil)
        per-case (vec
                  (for [[file kase] cases
                        :let [cid (get-in kase [:eval :id])
                              n (or trials (if live? (get-in kase [:eval :trials] 5) 1))
                              results (doall
                                       (for [t (range 1 (inc n))
                                             :while (not @stopped)
                                             :let [r (if (over-budget? @budget max-cost)
                                                       (let [why (format "Stopped before %s trial %d: $%.5f spent; another trial could pass --max-cost $%.5f."
                                                                         cid t (:spent @budget) max-cost)]
                                                         (reset! stopped why)
                                                         (append-json-line (fs/path dir "errors.jsonl")
                                                                           {:class "budget" :id cid :epoch t :message why})
                                                         nil)
                                                       (let [started (Instant/now)
                                                             r (play-trial! dir file kase t opts budget)]
                                                         (record-traces! dir trace-bin id kase t r started (Instant/now))
                                                         r))]
                                             :when r]
                                         (do (println (trial-line cid t r))
                                             (when-not (:errors r)
                                               (append-json-line (fs/path dir "results.jsonl")
                                                                 (printable (eval/result-row kase t (:scores r) (:totals r) (:dir r)))))
                                             [t r])))]]
                    {:id cid :tier (get-in kase [:eval :tier])
                     :rows (vec (for [[t r] results :when (not (:errors r))]
                                  (eval/result-row kase t (:scores r) (:totals r) (:dir r))))
                     :errors (count (filter (comp :errors second) results))}))
        summary (eval/summary per-case)
        run-info (assoc run-info :ended (str (Instant/now)) :stopped @stopped :spent-usd (:spent @budget))]
    (write-json (fs/path dir "summary.json") (printable summary))
    (write-json (fs/path dir "run.json") (printable run-info))
    {:run run-info :summary summary :dir dir}))


;; --- bb eval

(def ^:private spec
  {:coerce {:live :boolean :trials :long :model :string :effort :string :out :string :max-cost :double :agentloop :string}})

(def usage-line "usage: bb eval [<case> ...] [--out <dir>] [--live] [--trials n] [--model m] [--effort e] [--max-cost usd] [--agentloop <path>]")

(defn eval-main [& args]
  (let [{names :args o :opts} (cli/parse-args args spec)
        home (System/getProperty "user.home")
        out (str/replace-first (or (:out o) default-out) #"^~(?=/|$)" home)]
    (try
      (when (and (or (:model o) (:effort o)) (not (:live o)))
        (throw (trial/usage-error "--model and --effort only mean something with --live.")))
      (when (and (:effort o) (not (config/efforts (:effort o))))
        (throw (trial/usage-error (str "--effort is one of " (str/join ", " (sort config/efforts)) "."))))
      (when-let [path (:agentloop o)] (support/use-agentloop! path))
      (when-let [why (out-problem out "." home)] (throw (trial/usage-error why)))
      (let [all (trial/eval-case-files)
            by-name (into {} (map (juxt runner/scenario-name identity) all))
            files (if (seq names)
                    (mapv #(or (by-name %)
                               (throw (trial/usage-error (str "No eval case " % ". There are: "
                                                        (str/join ", " (sort (keys by-name))) "."))))
                          names)
                    all)
            {:keys [run summary dir]} (run! files {:out out :live? (:live o) :model (:model o) :effort (:effort o)
                                                   :trials (:trials o) :max-cost (:max-cost o)
                                                   :args (vec args)})]
        (println)
        (doseq [l (render/eval-report run summary)] (println l))
        (when (:stopped run) (println (:stopped run)))
        (println (str "records in " dir))
        (System/exit (if (and (zero? (:errors summary)) (nil? (:stopped run))
                              (every? #(= (:passed %) (:scored %)) (vals (:cases summary))))
                       0 1)))
      (catch clojure.lang.ExceptionInfo e
        (binding [*out* *err*] (println (ex-message e)))
        (System/exit 1)))))
