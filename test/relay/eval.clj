(ns relay.eval
  "The pure half of relay's behaviour evals (relay.eval-runner is the
  shell). An eval case is a scenario (relay.scenario) with these keys added:

    :eval     {:id \"fact-validity-window\" :version 1 :tier :regression
               :tags [:knowledge] :trials 5}
    :graders  [{:id :valid-to :check [...] :gating? true :doc \"...\"} ...]
    :foils    {\"no-end-date\" {:model {\"hub\" [reply ...]} :fails #{:valid-to}}}
    :skip-defaults #{:journal-each-wake}

  and usually :clock and :settings, which relay.scenario describes. Its
  :assert holds only preconditions: what must be true for the trial to
  have run at all. A failed precondition makes the trial an error, never a
  fail.

  A check is data:

    [:has kind pattern] [:none kind pattern] [:count kind pattern n]
                         as relay.scenario's assertions
    [:every kind p q]    every item matching p also matches q
    [:before kind p q]   an item matching p comes before any matching q,
                         in the order relay.scenario/items gives (a tool
                         call's :n, otherwise log order). It grades the
                         path, so use it sparingly.
    [:and c ...] [:or c ...] [:not c] [:implies a b]

  A grader is {:id :check} with :gating? (default true) and :doc. A trial
  passes when every gating grader passes. A grader may instead be a judge,
  {:id :judge {:rubric :name :evidence [...]}}: it is never gating, and
  until judges are built it is recorded as skipped, never as a pass or a
  fail. Graders from
  test/scenarios/eval-defaults.edn are added to every case unless it lists
  their ids under :skip-defaults.

  An oracle (the case's :model script) must pass every check, and each foil
  must fail exactly the checks in its :fails, gating or not. That is how a
  grader is shown to tell a good run from a bad one before any money is
  spent on it.

  Results follow Inspect AI's sample shape (id, epoch, scores with value C
  or I), so a later export to its log format is a renaming. pass^k is
  tau-bench's: the chance that k trials of a case all pass, C(c,k)/C(n,k)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [relay.scenario :as scenario]
            [relay.transcript :as transcript]))

;; --- checks

(def check-ops #{:has :none :count :every :before :and :or :not :implies})

(defn check-problems
  "What is wrong with a check's shape, as messages."
  [c]
  (let [bad (fn [why] [(str "Check " (pr-str c) " " why)])]
    (if-not (and (vector? c) (check-ops (first c)))
      (bad (str "is not one of " (str/join ", " (map pr-str (sort check-ops))) "."))
      (let [[op & args] c]
        (case op
          (:has :none) (if (and (= 2 (count args)) (scenario/kinds (first args)) (map? (second args)))
                         []
                         (bad "needs a kind and a pattern map."))
          :count (if (and (= 3 (count args)) (scenario/kinds (first args)) (map? (second args))
                          (nat-int? (nth args 2)))
                   []
                   (bad "needs a kind, a pattern map and a count."))
          (:every :before) (if (and (= 3 (count args)) (scenario/kinds (first args))
                                    (map? (second args)) (map? (nth args 2)))
                             []
                             (bad "needs a kind and two pattern maps."))
          (:and :or) (if (seq args) (vec (mapcat check-problems args)) (bad "needs at least one check."))
          :not (if (= 1 (count args)) (check-problems (first args)) (bad "needs one check."))
          :implies (if (= 2 (count args)) (vec (mapcat check-problems args)) (bad "needs two checks.")))))))

(defn- brief [x]
  (let [s (pr-str x)]
    (if (> (count s) 200) (str (subs s 0 200) "...") s)))

(defn- pass [why] {:value :pass :explanation why})
(defn- fail [why] {:value :fail :explanation why})

(defn check
  "Runs a check against a world. Returns {:value :pass|:fail :explanation}."
  [world c]
  (let [[op & args] c]
    (case op
      (:has :none :count)
      (if-let [why (scenario/check world c)] (fail why) (pass (str "Holds: " (brief c))))

      :every
      (let [[kind p q] args
            hits (filter #(scenario/match? p %) (scenario/items world kind))
            off (first (remove #(scenario/match? q %) hits))]
        (if off
          (fail (str "A " (name kind) " matching " (pr-str p) " does not match " (pr-str q) ": " (brief off) "."))
          (pass (str "Every " (name kind) " matching " (pr-str p) " (" (count hits) ") matches " (pr-str q) "."))))

      :before
      (let [[kind p q] args
            all (vec (scenario/items world kind))
            place (fn [pat] (first (keep-indexed (fn [i x] (when (scenario/match? pat x) i)) all)))
            a (place p)
            b (place q)]
        (cond
          (nil? a) (fail (str "No " (name kind) " matches " (pr-str p) "."))
          (or (nil? b) (< a b)) (pass (str "A " (name kind) " matching " (pr-str p) " comes first."))
          :else (fail (str "A " (name kind) " matching " (pr-str q) " came before any matching " (pr-str p) "."))))

      :and
      (or (first (filter #(= :fail (:value %)) (map #(check world %) args)))
          (pass "Every part holds."))

      :or
      (let [rs (map #(check world %) args)]
        (or (first (filter #(= :pass (:value %)) rs))
            (fail (str "None holds: " (str/join " / " (map :explanation rs))))))

      :not
      (let [r (check world (first args))]
        (if (= :pass (:value r))
          (fail (str "Expected this not to hold: " (:explanation r)))
          (pass (str "Does not hold, as expected: " (:explanation r)))))

      :implies
      (let [a (check world (first args))]
        (if (= :fail (:value a))
          (pass (str "Not applicable: " (:explanation a)))
          (check world (second args)))))))

;; --- cases

(def tiers #{:regression :capability})

(defn graders
  "A case's graders: its own, then the defaults it does not skip."
  [case defaults]
  (let [skip (set (:skip-defaults case))]
    (vec (concat (:graders case) (remove #(skip (:id %)) (:graders defaults))))))

(defn judge? [grader] (contains? grader :judge))

(defn gating?
  "Whether a grader decides the trial: a check unless it says otherwise;
  a judge never does."
  [grader]
  (and (not (judge? grader)) (get grader :gating? true)))

(defn- grader-problems [g]
  (cond
    (not (map? g)) [(str "Grader " (brief g) " is not a map.")]
    (not (keyword? (:id g))) [(str "Grader " (brief g) " needs a keyword :id.")]
    (and (contains? g :judge) (contains? g :check)) [(str "Grader " (:id g) " has both :check and :judge.")]
    (contains? g :judge)
    (let [{:keys [rubric evidence]} (:judge g)]
      (concat (when-not (and (keyword? rubric) (vector? evidence) (every? vector? evidence))
                [(str "Grader " (:id g) ": :judge is {:rubric :name :evidence [[selector ...] ...]}.")])
              (when (true? (:gating? g)) [(str "Grader " (:id g) " is a judge and cannot be gating yet.")])))
    (not (contains? g :check)) [(str "Grader " (:id g) " needs a :check.")]
    (not (or (nil? (:gating? g)) (boolean? (:gating? g)))) [(str "Grader " (:id g) " has a :gating? that is not true or false.")]
    :else (mapv #(str "Grader " (:id g) ": " %) (check-problems (:check g)))))

(defn defaults-problems
  "What is wrong with eval-defaults.edn."
  [defaults]
  (if-not (and (map? defaults) (vector? (:graders defaults)))
    ["eval-defaults.edn is {:graders [...]}."]
    (vec (mapcat grader-problems (:graders defaults)))))

(defn case-problems
  "What is wrong with an eval case, beyond what relay.scenario/problems
  finds, as messages."
  [case defaults]
  (let [{:keys [id version tier tags trials] :as e} (:eval case)
        gs (graders case defaults)
        ids (map :id gs)
        known (set ids)
        default-ids (set (map :id (:graders defaults)))]
    (vec (concat
          (cond
            (not (map? e)) [":eval is a map with :id, :version and :tier."]
            :else (concat (when-not (and (string? id) (re-matches #"[a-z0-9][a-z0-9-]*" id))
                            [":eval :id is a lower-case name such as fact-validity-window."])
                          (when-not (pos-int? version) [":eval :version is a positive integer."])
                          (when-not (tiers tier) [(str ":eval :tier is one of " (sort tiers) ".")])
                          (when-not (or (nil? tags) (and (vector? tags) (every? keyword? tags)))
                            [":eval :tags is a vector of keywords."])
                          (when-not (or (nil? trials) (pos-int? trials)) [":eval :trials is a positive integer."])))
          (when-not (and (vector? (:graders case)) (seq (:graders case))) ["An eval case needs :graders."])
          (mapcat grader-problems (:graders case))
          (for [[i n] (frequencies ids) :when (> n 1)] (str "Grader id " i " is used " n " times."))
          (when-let [bad (seq (remove default-ids (:skip-defaults case)))]
            [(str ":skip-defaults names " (str/join ", " bad) ", which eval-defaults.edn does not define.")])
          (when-not (map? (:model case)) ["An eval case needs its oracle, a :model script."])
          (when-not (or (nil? (:foils case)) (map? (:foils case))) [":foils maps a name to {:model ... :fails #{...}}."])
          (for [[nm {:keys [model fails] :as foil}] (when (map? (:foils case)) (:foils case))
                problem [(when-not (and (map? foil) (map? model) (every? vector? (vals model)))
                           (str "Foil " nm " needs a :model script."))
                         (when-not (and (set? fails) (seq fails))
                           (str "Foil " nm " needs :fails, a set of the grader ids it must fail."))
                         (when-let [bad (and (set? fails) (seq (remove known fails)))]
                           (str "Foil " nm " fails " (str/join ", " bad) ", which are not graders of this case."))]
                :when problem]
            problem)))))

;; --- grading a trial

(defn grade
  "Scores every grader against a world: {id {:value :explanation :gating?}}.
  A judge's :value is :skipped."
  [world graders]
  (into {} (for [g graders]
             [(:id g) (if (judge? g)
                        {:value :skipped :gating? false :kind :judge
                         :explanation "Judge graders are not run yet."}
                        (assoc (check world (:check g)) :gating? (gating? g)))])))

(defn passed? [scores] (every? #(= :pass (:value %)) (filter :gating? (vals scores))))

(def bad-wake-outcomes #{"needs-attention" "limited" "unreachable"})

(defn trial-errors
  "Why a trial produced no score, as [{:class :message}], or empty. `run`
  is what relay.scenario-runner/run-scenario! returned: its :failures,
  with the case's final :assert among them as :assert-failures, which are
  preconditions; the rest are step failures. `model` is the model asked
  for, or nil not to compare."
  [{:keys [failures assert-failures world]} model]
  (let [wakes (scenario/items world :wake)
        pre (count assert-failures)
        steps (drop-last pre failures)]
    (vec (concat
          (for [f steps] {:class :step :message f})
          (for [f assert-failures] {:class :precondition :message f})
          (for [w wakes :when (or (bad-wake-outcomes (:outcome w)) (:crashed w))]
            {:class :wake-outcome
             :message (str (:role w) "'s wake " (:id w) " ended " (:outcome w)
                           (when (:crashed w) ", crashed") ": " (:said w))})
          (for [w wakes :when (#{"limit" "max_tokens"} (:end-reason w))]
            {:class :budget :message (str (:role w) "'s wake " (:id w) " hit a limit (" (:end-reason w) ").")})
          (when model
            (for [w wakes :when (some #(not= (transcript/base-model model) (transcript/base-model %)) (:models w))]
              {:class :model-mismatch
               :message (str (:role w) "'s wake " (:id w) " was served by " (str/join ", " (:models w))
                             ", not " model ".")}))))))

(defn trial-usage
  "A trial's totals over its wakes."
  [world]
  (let [wakes (scenario/items world :wake)
        costs (map :cost-usd wakes)]
    {:wakes (count wakes)
     :requests (reduce + 0 (map :requests wakes))
     :tool-calls (reduce + 0 (map :tool-calls wakes))
     :tool-errors (reduce + 0 (map :tool-errors wakes))
     :usage {:input (reduce + 0 (map :input-tokens wakes))
             :output (reduce + 0 (map :output-tokens wakes))
             :cache-read (reduce + 0 (map :cache-read-tokens wakes))
             :cache-write (reduce + 0 (map :cache-write-tokens wakes))}
     :cost-usd (when (every? some? costs) (reduce + 0.0 costs))
     :models (vec (distinct (mapcat :models wakes)))
     :operator-memos (count (filter #(= "operator" (:to %)) (scenario/items world :memo)))
     :sent (count (scenario/items world :sent))}))

(defn foil-problems
  "How a foil's scores differ from what it says it fails, as messages:
  every check in `fails` must fail and every other must pass. A skipped
  judge can be neither, so it is left out."
  [scores fails]
  (let [skipped (set (for [[id s] scores :when (= :skipped (:value s))] id))
        fails (set/difference (set fails) skipped)
        failed (set (for [[id s] scores :when (= :fail (:value s))] id))]
    (vec (concat
          (for [id (sort (set/difference failed fails))]
            (str "also fails " id ": " (get-in scores [id :explanation])))
          (for [id (sort (set/difference fails failed))]
            (str "passes " id ", which it should fail: " (get-in scores [id :explanation])))))))

;; --- metrics

(defn choose
  "C(n, k) as a double."
  [n k]
  (if (or (neg? k) (> k n))
    0.0
    (reduce (fn [acc i] (/ (* acc (- n i)) (inc i))) 1.0 (range k))))

(defn pass-hat-k
  "pass^k: the chance that k trials drawn from n, of which c passed, all
  pass. Nil when k is more than n or n is 0."
  [n c k]
  (when (and (pos? n) (<= 1 k n)) (/ (choose c k) (choose n k))))

(defn pass-at-k
  "pass@k: the chance that at least one of k trials drawn from n passes."
  [n c k]
  (when (and (pos? n) (<= 1 k n)) (- 1.0 (/ (choose (- n c) k) (choose n k)))))

(defn wilson
  "The Wilson score interval for c successes in n, at 95%. Nil when n is 0."
  [n c]
  (when (pos? n)
    (let [z 1.959964 p (/ (double c) n) z2 (* z z)
          denom (+ 1 (/ z2 n))
          centre (/ (+ p (/ z2 (* 2 n))) denom)
          half (/ (* z (Math/sqrt (+ (/ (* p (- 1 p)) n) (/ z2 (* 4 n n))))) denom)]
      [(max 0.0 (- centre half)) (min 1.0 (+ centre half))])))

(defn- mean [xs] (when (seq xs) (/ (reduce + 0.0 xs) (count xs))))

(defn bootstrap
  "A 95% percentile interval for the mean of `xs`, from `rounds` resamples
  drawn with a fixed seed so the same rows give the same interval."
  [xs & {:keys [rounds seed] :or {rounds 2000 seed 1}}]
  (when (seq xs)
    (let [v (vec xs) n (count v) r (java.util.Random. seed)
          means (sort (repeatedly rounds #(mean (repeatedly n (fn [] (v (.nextInt r n)))))))
          at (fn [q] (nth means (min (dec rounds) (int (* q rounds)))))]
      [(at 0.025) (at 0.975)])))

(defn case-summary
  "One case's figures from its rows (results.jsonl, as maps) and error
  count."
  [rows errors]
  (let [n (count rows)
        c (count (filter :passed rows))
        graders (sort (distinct (mapcat (comp keys :scores) rows)))
        costs (keep :cost_usd rows)]
    {:scored n :passed c :errors errors
     :pass-rate (when (pos? n) (/ (double c) n))
     :interval (wilson n c)
     :pass-hat (into (sorted-map) (for [k [1 3 n]] [k (pass-hat-k n c k)]))
     :grader-passes (into (sorted-map)
                          (for [g graders
                                :let [k (count (filter #(= "C" (get-in % [:scores g :value])) rows))]]
                            [g k]))
     :grader-fails (into (sorted-map)
                         (for [g graders
                               :let [k (count (filter #(= "I" (get-in % [:scores g :value])) rows))]
                               :when (pos? k)]
                           [g k]))
     :mean-wakes (mean (map :wakes rows))
     :mean-requests (mean (map :requests rows))
     :mean-input-tokens (mean (map #(get-in % [:usage :input] 0) rows))
     :mean-output-tokens (mean (map #(get-in % [:usage :output] 0) rows))
     :mean-cost-usd (mean costs)
     :cost-usd (reduce + 0.0 costs)}))

(defn summary
  "The run's figures. `cases` is [{:id :tier :rows :errors}]."
  [cases]
  (let [per-case (into (sorted-map) (for [{:keys [id tier rows errors]} cases]
                                      [id (assoc (case-summary rows errors) :tier tier)]))
        tier-summary (fn [tier]
                       (let [cs (filter #(= tier (:tier %)) (vals per-case))
                             rates (keep :pass-rate cs)
                             hats (keep #(get-in % [:pass-hat 3]) cs)]
                         (when (seq cs)
                           {:cases (count cs)
                            :pass-rate (mean rates)
                            :interval (bootstrap rates)
                            :pass-hat-3 (mean hats)
                            :errors (reduce + 0 (map :errors cs))})))
        rows (mapcat :rows cases)
        wakes (reduce + 0 (map :wakes rows))
        cost (reduce + 0.0 (keep :cost_usd rows))]
    {:cases per-case
     :regression (tier-summary :regression)
     :capability (tier-summary :capability)
     :trials (count rows)
     :errors (reduce + 0 (map :errors cases))
     :wakes wakes
     :requests (reduce + 0 (map :requests rows))
     :cost-usd cost
     :cost-per-wake (when (pos? wakes) (/ cost wakes))}))

;; --- records

(defn result-row
  "One results.jsonl row, in Inspect's sample shape."
  [case trial scores {:keys [usage cost-usd] :as totals} data-dir]
  {:id (get-in case [:eval :id])
   :epoch trial
   :metadata {:tags (mapv name (get-in case [:eval :tags])) :tier (name (get-in case [:eval :tier]))
              :case_version (get-in case [:eval :version])}
   :scores (into (sorted-map)
                 (for [[id {:keys [value explanation gating?]}] scores :when (#{:pass :fail} value)]
                   [id {:value (if (= :pass value) "C" "I") :explanation explanation
                        :metadata {:kind "check" :gating gating?}}]))
   :skipped (vec (for [[id {:keys [value kind explanation]}] (sort-by key scores) :when (= :skipped value)]
                   {:id id :kind (name (or kind :check)) :reason explanation}))
   :passed (passed? scores)
   :usage {:input (:input usage) :output (:output usage)
           :cache_read (:cache-read usage) :cache_write (:cache-write usage)}
   :cost_usd cost-usd
   :served_models (:models totals)
   :wakes (:wakes totals)
   :requests (:requests totals)
   :tool_calls (:tool-calls totals)
   :tool_errors (:tool-errors totals)
   :operator_memos (:operator-memos totals)
   :sent (:sent totals)
   :data_dir data-dir})
