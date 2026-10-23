(ns relay.verify-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.verify :as verify]))

(def root "/w/relay")

(defn- step-ids [opts] (mapv :id (verify/plan opts root)))

(deftest the-plan-runs-every-suite-in-order-and-evals-only-when-live
  (is (= ["agentloop" "relay-tui" "webtool" "relay" "scenarios" "pentest"] (step-ids {})))
  (is (= "eval" (peek (step-ids {:live true}))))
  (testing "--only can name the live step without --live"
    (is (= ["eval"] (step-ids {:only "eval"}))))
  (is (= ["relay" "pentest"] (step-ids {:only "relay,pentest"})))
  (is (= ["agentloop" "relay-tui" "webtool" "scenarios"] (step-ids {:skip "relay,pentest"}))))

(deftest every-relay-step-uses-the-agentloop-the-plan-built
  (let [steps (verify/plan {:agentloop-dir "/x/al"} root)
        bin "/x/al/bin/agentloop"]
    (is (= "/x/al" (:dir (first steps))))
    (doseq [id ["relay" "scenarios" "pentest"]]
      (is (some #{bin} (:cmd (first (filter #(= id (:id %)) steps)))) id))))

(deftest siblings-default-next-to-the-checkout
  (is (str/ends-with? (:dir (first (verify/plan {} root))) "agentloop"))
  (is (some #{"--cleanroom"} (:cmd (last (verify/plan {:cleanroom "/c"} root))))))

(deftest the-arguments-parse-or-say-what-is-wrong
  (is (= {:live true :only "a,b"} (verify/parse-args ["--live" "--only" "a,b"])))
  (is (:error (verify/parse-args ["--only"])))
  (is (:error (verify/parse-args ["--frobnicate"]))))

(deftest the-summary-passes-only-when-every-step-ran-and-exited-zero
  (let [s (fn [id] {:id id})
        ok {:step (s "a") :exit 0 :secs 1.0}
        bad {:step (s "b") :exit 3 :secs 2.0}
        skipped {:step (s "c") :skipped "no checkout at /c"}]
    (is (verify/passed? [ok]))
    (is (not (verify/passed? [ok bad])))
    (is (not (verify/passed? [ok skipped])) "a step that could not run is not a pass")
    (let [lines (verify/summary-lines [ok bad skipped])]
      (is (= "Failed: b, c" (last lines)))
      (is (some #(str/includes? % "FAIL  exit 3") lines))
      (is (some #(str/includes? % "SKIP  no checkout") lines)))
    (is (= "All passed." (last (verify/summary-lines [ok]))))))
