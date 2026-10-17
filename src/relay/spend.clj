(ns relay.spend
  "What the roles' model calls cost, week by week. Pure: the wakes, the
  operator's time zone and the time now come in as arguments.

  A week starts on Monday at midnight in the operator's zone, so a wake
  that ends at Monday 00:00 there belongs to the new week. Amounts are
  summed as BigDecimal, which adds exactly, so a total is always the sum
  of its parts."
  (:import (java.time DayOfWeek Instant LocalDate ZoneId)
           (java.time.temporal TemporalAdjusters)))

(defn week-of
  "The Monday that starts the week holding `at`, in `zone`."
  ^LocalDate [^Instant at ^ZoneId zone]
  (-> (.atZone at zone) .toLocalDate (.with (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY))))

(defn week-start
  "The instant a week starts: the first moment of its Monday in `zone`,
  which is later than midnight when a clock change skips midnight."
  ^Instant [^LocalDate monday ^ZoneId zone]
  (.toInstant (.atStartOfDay monday zone)))

(defn- usd
  "A wake's cost as an exact amount, or nil when it has none."
  [x]
  (when (and (number? x) (Double/isFinite (double x)) (>= (double x) 0.0))
    (bigdec x)))

(def ^:private no-spend {:cost-usd 0M :priced 0 :unpriced 0})

(defn- add-wake
  "Counts one wake towards its role. A priced wake adds its cost. An
  unpriced one adds nothing to the cost and is counted when it ran, since
  only then did it likely call a model."
  [roles {:keys [role cost-usd outcome]}]
  (let [c (usd cost-usd)]
    (cond
      c (update roles role #(-> (or % no-spend) (update :cost-usd + c) (update :priced inc)))
      (= "ran" outcome) (update roles role #(update (or % no-spend) :unpriced inc))
      :else roles)))

(defn weekly
  "Spend for the week holding `now` and the `weeks` - 1 weeks before it,
  newest first. Each week is {:week monday :roles {role {:cost-usd
  :priced :unpriced}} :cost-usd total :unpriced count}, where :priced
  counts the wakes with a cost and :unpriced the wakes that ran without
  one. Only ended wakes count, by when they ended; a role that spent
  nothing in a week is not in it."
  [wakes ^ZoneId zone ^Instant now weeks]
  (let [this (week-of now zone)
        mondays (mapv #(.minusWeeks this (long %)) (range weeks))
        ;; Only the wakes in the window, found cheaply, before any is
        ;; put in its week.
        from (week-start (peek mondays) zone)
        by-week (group-by #(week-of (:ended-at %) zone)
                          (filter #(some-> ^Instant (:ended-at %) (.isBefore from) not) wakes))]
    (vec (for [monday mondays
               :let [roles (reduce add-wake {} (sort-by :id (get by-week monday)))]]
           {:week monday
            :roles roles
            :cost-usd (reduce + 0M (map :cost-usd (vals roles)))
            :unpriced (reduce + 0 (map :unpriced (vals roles)))}))))

(defn this-week
  "Spend for the week holding `now` (weekly, for one week)."
  [wakes zone now]
  (first (weekly wakes zone now 1)))

;; --- the budget

(def thresholds
  "The share of the weekly budget, in percent, at which the operator is
  told, highest first."
  [100 80])

(defn threshold-reached
  "The highest threshold that a week's `total` has reached and that is
  above `told`, the highest already told this week (nil for none), or nil.
  A total that passes both thresholds at once gives only 100: one warning
  that the budget is spent says all that two would."
  [budget total told]
  (let [budget (bigdec budget) total (bigdec total)]
    (some (fn [p] (when (and (>= (* total 100M) (* budget (bigdec p)))
                             (or (nil? told) (> p told)))
                    p))
          thresholds)))
