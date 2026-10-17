(ns relay.eval-runner-test
  "bb eval's shell: where records may go, and when the budget stops a run."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.eval-runner :as er]))

(defn- holds? [r] (:pass? r))

(deftest records-never-go-in-the-repository-or-a-protected-place
  (let [home (System/getProperty "user.home")]
    (is (some? (er/out-problem "." "." home)))
    (is (some? (er/out-problem "test/out" "." home)))
    (is (some? (er/out-problem (str home "/Sync/evals") "." home)))
    (is (some? (er/out-problem (str home "/.config/relay/evals") "." home)))
    (is (nil? (er/out-problem (str (fs/create-temp-dir {:prefix "relay-eval-out"})) "." home)))))

(deftest the-budget-stops-the-next-trial-before-it-would-pass-the-limit
  (is (not (er/over-budget? {:spent 0.0 :dearest 0.0} 0.01)) "the first trial starts")
  (is (not (er/over-budget? {:spent 5.0 :dearest 1.0} nil)) "no limit")
  (is (not (er/over-budget? {:spent 0.04 :dearest 0.01} 0.05)) "exactly on the limit is allowed")
  (is (er/over-budget? {:spent 0.045 :dearest 0.01} 0.05))
  (is (holds? (tc/quick-check 300 (prop/for-all [spent (gen/double* {:min 0 :max 10 :NaN? false :infinite? false})
                                                 extra (gen/double* {:min 0 :max 10 :NaN? false :infinite? false})
                                                 dearest (gen/double* {:min 0 :max 10 :NaN? false :infinite? false})
                                                 limit (gen/double* {:min 0 :max 20 :NaN? false :infinite? false})]
                                    ;; More spend never lets a trial start that a smaller spend stopped.
                                    (or (not (er/over-budget? {:spent spent :dearest dearest} limit))
                                        (er/over-budget? {:spent (+ spent extra) :dearest dearest} limit)))))))
