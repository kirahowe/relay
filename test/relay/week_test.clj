(ns relay.week-test
  "relay week's report and relay flag: each kind of interruption arising
  and being decided, the health checks, the window, and properties over
  logs made by core-test's model of relay, with flags added."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.core :as core]
            [relay.test-support :as support]
            [relay.core-test :as ct]
            [relay.render :as render]
            [relay.week :as week])
  (:import (java.time Instant LocalDate ZoneId)))

(def zone (ZoneId/of "America/Toronto"))
(def all {:from nil :until nil})

(defn- report [w & [win facts]]
  (week/report (:events w) zone (:now w) (or win all) (or facts {})))

(defn- nth-or [coll n] (when (seq coll) (nth coll (mod n (count coll)))))

(defn- kind [r k] (first (filter #(= k (:kind %)) (get-in r [:interruptions :kinds]))))

(defn- seeded []
  (-> (ct/world)
      (ct/run "operator" :role/add {:id "hub" :instructions "c"})
      (ct/run "operator" :role/add {:id "clerk" :instructions "c"})
      (ct/run "operator" :role/add {:id "courier" :instructions "c"} true)))

;; --- each kind

(deftest a-harness-that-needs-the-operator-waits-until-it-runs-again
  (let [w (-> (seeded) (ct/run "operator" :memo/send {:to "hub" :subject "s" :body "b"})
              (#'ct/step [:wake "hub" true "needs-attention" [] false false "t1" "a" "a"]))
        _ (is (= 1 (:pending (kind (report w) "harness"))))
        w (-> w (ct/advance 86400) (#'ct/step [:wake "hub" false "ran" [] false false "t1" "a" "a"]))]
    (is (= {"recovered" 1} (:decisions (kind (report w) "harness"))))))

(deftest work-given-up-waits-for-a-retry
  ;; The handler's own memo, blocked, goes to the operator.
  (let [w (-> (seeded) (ct/run "operator" :memo/send {:to "hub" :subject "s" :body "b"}))
        w (ct/run w "dispatcher" :wake/start {:role "hub"})
        id (:id (first (core/unread-memos (:state w) "hub")))
        w (ct/run w "hub" :memo/block {:memo id :explanation "no"})
        _ (is (= 1 (:pending (kind (report w) "dead-letter"))))
        w (ct/run w "operator" :memo/retry {:role "hub"})]
    (is (= {"retried" 1} (:decisions (kind (report w) "dead-letter"))))))

;; --- notifications and wakes

(deftest notifications-count-per-day-kind-and-sender
  (let [w (-> (seeded) (ct/run "operator" :memo/send {:to "clerk" :subject "s" :body "b"})
              (ct/run "dispatcher" :wake/start {:role "clerk"}))
        w (reduce (fn [w i] (ct/run w "clerk" :memo/send {:to "operator" :subject (str "s" i) :body "b"})) w (range 3))
        wake (support/calling-wake (:state w) "clerk")
        w (-> w (ct/advance 86400) (ct/run "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :said "" :cost-usd 0.5}))
        {:keys [notifications roles health]} (report w)]
    (is (= {"clerk" 3} (:memos-by-role notifications)))
    (is (= 3 (get-in notifications [:per-kind "memo-for-operator"])))
    (is (= {"2026-10-14" 3} (:per-day notifications)))
    (testing "the wake left its memo open and wrote no journal entry"
      (is (= [{:id (:id wake) :role "clerk" :role-labels [] :chat false :started-at (:started-at wake)
               :failed ["no-journal-entry" "memos-left-open" "many-operator-memos"]}]
             health)))
    (is (= {:role "clerk" :wakes 1 :memos-left-open 1 :cost-usd 0.5M :cost-per-wake 0.5M :unpriced 0}
           (select-keys (first roles) [:role :wakes :memos-left-open :cost-usd :cost-per-wake :unpriced])))))

(deftest a-conversation-ending-mid-wake-leaves-the-wake-its-work
  ;; relay#63: the walk kept one running wake per role, so a conversation
  ;; that ended while a wake of its role worked made the wake's later
  ;; closes and journal entry count for nothing.
  (let [w (-> (seeded) (ct/run "operator" :memo/send {:to "clerk" :subject "s" :body "b"})
              (ct/run "dispatcher" :wake/start {:role "clerk"})
              (ct/run "operator" :wake/start {:role "clerk" :chat true}))
        wake (core/working-wake (:state w) "clerk")
        chat (core/open-conversation (:state w) "clerk")
        w (-> w (ct/run "dispatcher" :wake/end {:wake (:id chat) :outcome "ran"})
              (ct/run "clerk" :memo/close {:memo (first (:memos wake)) :outcome "done"})
              (ct/run "clerk" :journal/write {:owner "clerk" :text "did it"})
              (ct/run "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :said ""}))
        facts {(:id wake) {:calls 2 :refused 0 :failed 0 :refused-tools [] :failed-tools []}}
        r (report w all facts)]
    (is (empty? (:health r)) (pr-str (:health r)))
    (is (= 1 (:memos-closed (first (filter #(= "clerk" (:role %)) (:roles r))))))))

(deftest a-wake-that-did-its-job-passes-every-check
  (let [w (-> (seeded) (ct/run "operator" :memo/send {:to "clerk" :subject "s" :body "b"})
              (ct/run "dispatcher" :wake/start {:role "clerk"}))
        wake (support/calling-wake (:state w) "clerk")
        id (first (:memos wake))
        w (-> w (ct/run "clerk" :memo/close {:memo id :outcome "done"})
              (ct/run "clerk" :journal/write {:owner "clerk" :text "did it"})
              (ct/run "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :said ""}))
        facts {(:id wake) {:calls 2 :refused 0 :failed 0 :refused-tools [] :failed-tools []}}
        r (report w all facts)
        [row] (week/wake-rows (week/walk-log (:events w)) all facts)]
    (is (empty? (:health r)))
    (is (= 1 (:memos-closed (first (:roles r)))))
    (is (every? :pass (week/checks row)))
    (is (= 6 (count (week/checks row))))
    (testing "a refused call in its transcript fails one"
      (is (= ["tool-calls-refused-or-failed"]
             (week/failed-checks (assoc row :transcript {:calls 2 :refused 1 :failed 0})))))))

(deftest transcript-facts-count-refused-and-failed-calls
  (let [text (str/join "\n" ["{\"type\":\"decision\",\"decision\":{\"call_id\":\"a\",\"tool\":\"post\",\"verdict\":\"deny\"}}"
                             "{\"type\":\"tool_result\",\"tool_result\":{\"result\":{\"call_id\":\"a\",\"is_error\":true}}}"
                             "{\"type\":\"decision\",\"decision\":{\"call_id\":\"b\",\"tool\":\"inbox\",\"verdict\":\"allow\"}}"
                             "{\"type\":\"tool_result\",\"tool_result\":{\"result\":{\"call_id\":\"b\",\"is_error\":true}}}"
                             "{\"type\":\"decision\",\"decision\":{\"call_id\":\"c\",\"tool\":\"post\",\"verdict\":\"deny\",\"resolved\":true}}"
                             "not json" "{\"type\":\"decision\""])]
    (is (= {:calls 3 :refused 1 :failed 1 :refused-tools ["post"] :failed-tools ["inbox"]}
           (week/transcript-facts (week/parse-transcript text))))))

;; --- flags

(deftest flag-marks-a-wake-and-refuses-what-is-not-one
  (let [w (-> (seeded) (ct/run "operator" :memo/send {:to "hub" :subject "s" :body "b"})
              (ct/run "dispatcher" :wake/start {:role "hub"}))
        wake (:id (support/calling-wake (:state w) "hub"))]
    (testing "an unknown id, a memo's id, a bad value, or anyone but the operator is refused"
      (is (= :not-found (:rejected (ct/run w "operator" :wake/flag {:wake 9999 :value "good"}))))
      (is (= :not-found (:rejected (ct/run w "operator" :wake/flag {:wake (dec wake) :value "good"}))))
      (is (= :not-found (:rejected (ct/run w "operator" :wake/flag {:wake "x" :value "good"}))))
      (is (= :invalid (:rejected (ct/run w "operator" :wake/flag {:wake wake :value "meh"}))))
      (is (= :forbidden (:rejected (ct/run w "hub" :wake/flag {:wake wake :value "bad"}))))
      (is (= :forbidden (:rejected (ct/run w "dispatcher" :wake/flag {:wake wake :value "bad"})))))
    (let [w (-> w (ct/run "operator" :wake/flag {:wake wake :value "bad" :note "  looped on inbox  "}) (ct/advance 60)
                (ct/run "operator" :wake/flag {:wake wake :value "good"}))
          r (report w)]
      (is (= [["bad" "looped on inbox"] ["good" nil]] (map (juxt :value :note) (get-in (:state w) [:wakes wake :flags]))))
      (is (= [["bad" "looped on inbox"] ["good" nil]] (map (juxt :value :note) (:flags r))))
      (is (= {"flag" 2} (:actions r)))
      (testing "it survives a replay"
        (is (= (:state w) (core/replay (:events w)))))
      (testing "and shows in relay week"
        (is (some #(str/includes? % (str "wake " wake ", hub")) (render/week-report r zone)))))))

;; --- the window

(deftest the-window-defaults-to-the-last-seven-days
  (let [now (Instant/parse "2026-10-18T15:00:00Z")]
    (is (= {:from (Instant/parse "2026-10-11T15:00:00Z") :until now} (week/window zone now nil nil)))
    (is (= {:from (Instant/parse "2026-10-12T04:00:00Z") :until now}
           (week/window zone now (LocalDate/parse "2026-10-12") nil)) "midnight in Toronto")
    (is (= {:from (Instant/parse "2026-10-06T04:00:00Z") :until (Instant/parse "2026-10-13T04:00:00Z")}
           (week/window zone now nil (LocalDate/parse "2026-10-12"))) "to the end of the day")))

(deftest the-text-report-has-every-section
  (let [lines (render/week-report (report (-> (seeded) (ct/run "operator" :memo/send {:to "hub" :subject "s" :body "b"})
                                               (#'ct/step [:wake "hub" true "needs-attention" [] false false "t1" "a" "a"])))
                                  zone)]
    (doseq [h ["Interruptions: each time relay needed you" "Notifications: 1" "Wakes by role"
               "Health checks: wakes that look wrong" "Flags"]]
      (is (some #{h} lines) h))
    (is (some #(str/starts-with? % "    harness ") lines) "what still waits")
    (is (not-any? #(re-find #"(?i)\bhold|approv|quarantin|declassif|relay fresh" %) lines))))

;; --- properties over generated logs

(def gen-extra
  (gen/frequency
   [[2 (gen/tuple (gen/return :flag) gen/nat (gen/elements ["good" "bad" "meh"]))]]))

(defn- extra-step [w [k & args]]
  (case k
    :flag (let [[n value] args
                ids (sort (keys (:wakes (:state w))))]
            (ct/run w "operator" :wake/flag {:wake (or (nth-or ids n) n) :value value :note "n"}))))

(def gen-log
  (gen/fmap (fn [steps]
              (reduce (fn [w s] (if (#{:flag} (first s))
                                  (extra-step w s)
                                  (#'ct/step (dissoc w :problem) s)))
                      (seeded) steps))
            (gen/vector (gen/frequency [[6 ct/gen-step] [3 gen-extra]]) 1 50)))

(defn- event-time [e] (Instant/parse (:at e)))

(defspec every-interruption-is-decided-or-pending 60
  (prop/for-all [w gen-log]
    (let [r (report w)
          ks (get-in r [:interruptions :kinds])
          pending (frequencies (map :kind (get-in r [:interruptions :pending])))]
      (and (every? #(= (:arose %) (+ (:decided %) (:pending %))) ks)
           (every? #(= (:decided %) (reduce + (vals (:decisions %)))) ks)
           (every? #(= (:pending %) (get pending (:kind %) 0)) ks)
           (= (get-in r [:notifications :total]) (count (filter #(= "notification/created" (:type %)) (:events w))))
           (every? #(or (nil? (:median-seconds %)) (<= 0 (:median-seconds %) (:max-seconds %))) ks)))))

(defspec a-window-that-covers-the-log-gives-the-whole-report 40
  (prop/for-all [w gen-log]
    (let [first-at (event-time (first (:events w)))
          covering {:from (.minusSeconds first-at 86400) :until (.plusSeconds ^Instant (:now w) 86400)}]
      (= (dissoc (report w) :window) (dissoc (report w covering) :window)))))

(defspec windows-side-by-side-add-up 40
  (prop/for-all [w gen-log
                 cut gen/nat]
    (let [times (mapv event-time (:events w))
          mid (nth times (mod cut (count times)))
          until (.plusSeconds ^Instant (:now w) 1)
          whole (report w {:from nil :until until})
          a (report w {:from nil :until mid})
          b (report w {:from mid :until until})
          arose (fn [r] (into {} (map (juxt :kind :arose) (get-in r [:interruptions :kinds]))))]
      (and (= (arose whole) (merge-with + (arose a) (arose b)))
           (= (get-in whole [:notifications :total])
              (+ (get-in a [:notifications :total]) (get-in b [:notifications :total])))
           (= (count (:flags whole)) (+ (count (:flags a)) (count (:flags b))))
           (= (reduce + (map :wakes (:roles whole)))
              (+ (reduce + (map :wakes (:roles a))) (reduce + (map :wakes (:roles b)))))))))
