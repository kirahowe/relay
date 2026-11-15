(ns relay.verify-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.verify :as verify]))

(def root "/w/relay")

(defn- step-ids [opts] (mapv :id (verify/plan opts root)))

(deftest the-plan-runs-every-suite-in-order-and-evals-only-when-live
  (is (= ["agentloop" "relay-tui" "catalog" "webtool" "eventkit" "relay" "fuzz" "pentest"] (step-ids {})))
  (is (= "eval" (peek (step-ids {:live true}))))
  (testing "--only can name the live step without --live"
    (is (= ["eval"] (step-ids {:only "eval"}))))
  (is (= ["relay" "pentest"] (step-ids {:only "relay,pentest"})))
  (is (= ["agentloop" "relay-tui" "catalog" "webtool" "eventkit" "fuzz"] (step-ids {:skip "relay,pentest"}))))

(deftest relay-tuis-catalog-is-compared-with-relays
  (let [{:keys [dir cmd]} (first (filter #(= "catalog" (:id %)) (verify/plan {:relay-tui-dir "/x/tui"} root)))]
    (is (= "/x/tui" dir))
    (is (= ["/w/relay/resources/a2ui/relay_catalog.json" "/x/tui/internal/a2ui/relay_catalog.json"
            "/w/relay/testdata/surfaces" "/x/tui/testdata/surfaces"]
           (subvec cmd 4)))
    (is (= "cmp \"$1\" \"$2\" && diff -r \"$3\" \"$4\"" (nth cmd 2)))))

(deftest every-relay-step-uses-the-agentloop-the-plan-built
  (let [steps (verify/plan {:agentloop-dir "/x/al"} root)
        bin "/x/al/bin/agentloop"]
    (is (= "/x/al" (:dir (first steps))))
    (doseq [id ["relay" "pentest"]]
      (is (some #{bin} (:cmd (first (filter #(= id (:id %)) steps)))) id))))

(deftest siblings-default-next-to-the-checkout
  (is (str/ends-with? (:dir (first (verify/plan {} root))) "agentloop")))

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

(deftest a-stopped-container-service-is-named-for-the-steps-it-fails
  (let [stopped "Apple's container service is not running. Start it with: container system start"]
    (testing "only the steps that hold a check on a real container are named"
      (is (= [stopped "While it is stopped, relay (the shell's live test) and pentest (s18-shell-walls) cannot pass."]
             (verify/container-note stopped ["agentloop" "relay" "pentest"])))
      (is (= [stopped "While it is stopped, pentest (s18-shell-walls) cannot pass."]
             (verify/container-note stopped ["webtool" "pentest"]))))
    (testing "nothing is said while the service runs, or when no such step is spoken of"
      (is (nil? (verify/container-note nil ["relay" "pentest"])))
      (is (nil? (verify/container-note stopped ["agentloop" "relay-tui" "webtool" "eventkit"])))
      (is (nil? (verify/container-note stopped []))))
    (testing "after a run, the steps spoken of are those that ran and failed"
      (is (= ["relay"] (verify/failed-ids [{:step {:id "agentloop"} :exit 0 :secs 1.0}
                                           {:step {:id "relay"} :exit 21 :secs 2.0}
                                           {:step {:id "pentest"} :skipped "no checkout at /p"}]))))
    (testing "every step it can name is one the plan runs"
      (is (every? (set (step-ids {})) (keys verify/needs-container))))))
