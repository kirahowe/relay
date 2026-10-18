(ns relay.week-test
  "relay week's report and relay flag: each kind of interruption arising
  and being decided, the health checks, the window, and properties over
  logs made by core-test's model of relay, with held calls, flags and
  conversation permissions added."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.config :as config]
            [relay.core :as core]
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

(defn- held
  "A wake of `role` the operator starts with a note, which ends with one
  call held."
  [w role]
  (let [w (ct/run w "operator" :wake/start {:role role :note "go"})
        wake (core/running-wake (:state w) role)]
    (if-not wake
      w
      (ct/run w "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :session (str "h" (:id wake)) :said ""
                                        :held [{:id (str "call" (:id wake)) :name "post" :input {}
                                                :input-sha256 "abc"}]}))))

(defn- open-hold [w n] (nth-or (sort-by :id (core/open-holds (:state w))) n))

;; --- each kind

(deftest a-held-call-arises-and-is-decided
  (let [w (-> (seeded) (held "clerk"))
        h (open-hold w 0)]
    (testing "pending until the operator decides"
      (let [r (report w)]
        (is (= {:arose 1 :decided 0 :pending 1} (select-keys (kind r "hold") [:arose :decided :pending])))
        (is (= [{:kind "hold" :ref (:id h) :role "clerk" :arose-at (:opened-at h)}] (get-in r [:interruptions :pending])))))
    (let [w (-> w (ct/advance 3600) (ct/run "operator" :hold/resolve {:hold (:id h) :verdict "allow"}))
          k (kind (report w) "hold")]
      (is (= {:arose 1 :decided 1 :pending 0 :decisions {"approved" 1} :median-seconds 3600 :max-seconds 3600}
             (select-keys k [:arose :decided :pending :decisions :median-seconds :max-seconds]))))))

(deftest a-decision-after-the-window-is-pending-in-it
  (let [w (-> (seeded) (held "clerk"))
        h (open-hold w 0)
        w (-> w (ct/advance 7200) (ct/run "operator" :hold/resolve {:hold (:id h) :verdict "deny"}))
        win {:from nil :until (.plusSeconds ct/t0 3600)}
        k (kind (report w win) "hold")]
    (is (= {:arose 1 :decided 0 :pending 1 :oldest-pending-seconds 3600}
           (select-keys k [:arose :decided :pending :oldest-pending-seconds])))
    (is (= {"denied" 1} (:decisions (kind (report w) "hold"))))))

(deftest a-memo-to-a-public-role-waits-to-be-declassified
  (let [w (-> (seeded) (ct/run "hub" :memo/send {:to "courier" :subject "s" :body "b"}))
        _ (is (= 1 (:pending (kind (report w) "declassify"))))
        id (:id (first (core/unread-memos (:state w) "courier")))
        w (-> w (ct/advance 60) (ct/run "operator" :memo/declassify {:memo id}))]
    (is (= {"declassified" 1} (:decisions (kind (report w) "declassify"))))))

(def qcfg (config/resolve-config {:timezone "UTC" :default-harness :agentloop
                                  :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"]}}
                                  :notify {:kind :none}
                                  :tools {"post" {:class :egress :labels [] :description "Post." :command ["true"]}}
                                  :grants {"clerk" {:tools ["post"]}}}))

(defn- qrun [w actor type args & [labels]]
  (let [[s events] (core/run-command (:state w) {:type type :actor actor :args args :labels labels}
                                     {:now (:now w) :config qcfg})]
    (-> w (assoc :state s) (update :events into events))))

(deftest a-quarantined-memo-waits-for-release
  (let [w (-> {:state core/empty-state :now ct/t0 :events []}
              (qrun "operator" :role/add {:id "clerk" :instructions "c"})
              (qrun "operator" :memo/send {:to "clerk" :subject "s" :body "b"} ["contaminated"]))
        id (:id (first (vals (:memos (:state w)))))
        _ (is (= 1 (:pending (kind (report w) "quarantine"))))
        w (-> w (ct/advance 120) (qrun "operator" :memo/release {:memo id}))]
    (is (= {:decided 1 :decisions {"released" 1} :median-seconds 120}
           (select-keys (kind (report w) "quarantine") [:decided :decisions :median-seconds])))))

(deftest a-changed-tool-waits-for-relay-fresh
  (let [w (-> (seeded)
              (#'ct/step [:wake "hub" false "ran" [] false false "t1" (apply str (repeat 64 "a")) (apply str (repeat 64 "a"))])
              (ct/run "operator" :memo/send {:to "hub" :subject "s" :body "b"})
              (#'ct/step [:wake "hub" true "tools-changed" [] false false "t1" (apply str (repeat 64 "a")) (apply str (repeat 64 "b"))]))]
    (is (= 1 (:pending (kind (report w) "fresh"))) (pr-str (:interruptions (report w))))
    (let [w (ct/run w "operator" :role/fresh {:role "hub" :expect (core/fresh-expectation (:state w) "hub")})]
      (is (= {"accepted" 1} (:decisions (kind (report w) "fresh")))))))

(deftest report-findings-wait-for-review-one-by-one
  (let [w (-> (seeded)
              (#'ct/step [:report "courier" "x" true])
              (#'ct/step [:verify 0 :found false]))
        k (kind (report w) "finding")]
    (is (= 2 (:arose k)) (pr-str (:interruptions (report w))))
    (let [w (#'ct/step w [:review "operator" 0 "fake" false])]
      (is (= {:arose 2 :decided 1 :pending 1 :decisions {"rejected as fake" 1}}
             (select-keys (kind (report w) "finding") [:arose :decided :pending :decisions]))))))

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

(deftest a-conversation-asks-its-client
  (let [w (-> (seeded) (ct/run "operator" :wake/start {:role "hub" :chat true}))
        wake (:id (core/running-wake (:state w) "hub"))
        w (ct/run w "dispatcher" :permission/asked {:wake wake :session "s" :call "c1" :tool "post" :input-sha256 "x"})
        _ (is (= 1 (:pending (kind (report w) "permission"))))
        w (ct/run w "dispatcher" :permission/answered {:wake wake :session "s" :call "c1" :outcome "allow_once" :by "client"})]
    (is (= {"allow_once" 1} (:decisions (kind (report w) "permission"))))))

;; --- notifications and wakes

(deftest notifications-count-per-day-kind-and-sender
  (let [w (-> (seeded) (ct/run "operator" :memo/send {:to "clerk" :subject "s" :body "b"})
              (ct/run "dispatcher" :wake/start {:role "clerk"}))
        w (reduce (fn [w i] (ct/run w "clerk" :memo/send {:to "operator" :subject (str "s" i) :body "b"})) w (range 3))
        wake (core/running-wake (:state w) "clerk")
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

(deftest a-wake-that-did-its-job-passes-every-check
  (let [w (-> (seeded) (ct/run "operator" :memo/send {:to "clerk" :subject "s" :body "b"})
              (ct/run "dispatcher" :wake/start {:role "clerk"}))
        wake (core/running-wake (:state w) "clerk")
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
        wake (:id (core/running-wake (:state w) "hub"))]
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
  (let [lines (render/week-report (report (-> (seeded) (held "clerk"))) zone)]
    (doseq [h ["Interruptions: each time relay asked you for something" "Notifications: 1" "Wakes by role"
               "Health checks: wakes that look wrong" "Flags"]]
      (is (some #{h} lines) h))
    (is (some #(str/starts-with? % "    hold ") lines) "what still waits")))

;; --- properties over generated logs

(def gen-extra
  (gen/frequency
   [[3 (gen/tuple (gen/return :held) (gen/elements ["hub" "clerk"]))]
    [3 (gen/tuple (gen/return :resolve) gen/nat (gen/elements ["allow" "deny"]) gen/boolean)]
    [2 (gen/tuple (gen/return :flag) gen/nat (gen/elements ["good" "bad" "meh"]))]
    [1 (gen/tuple (gen/return :permission) (gen/elements ["hub" "clerk"]) gen/boolean)]]))

(defn- extra-step [w [k & args]]
  (case k
    :held (held w (first args))
    :resolve (let [[n verdict end] args]
               (if-let [h (open-hold w n)]
                 (ct/run w "operator" :hold/resolve {:hold (:id h) :verdict verdict :end end})
                 w))
    :flag (let [[n value] args
                ids (sort (keys (:wakes (:state w))))]
            (ct/run w "operator" :wake/flag {:wake (or (nth-or ids n) n) :value value :note "n"}))
    :permission (let [[r answer?] args
                      w (ct/run w "operator" :wake/start {:role r :chat true})
                      chat (core/running-wake (:state w) r)]
                  (if-not (:chat chat)
                    w
                    (cond-> (ct/run w "dispatcher" :permission/asked {:wake (:id chat) :session "s" :call "c"
                                                                      :tool "post" :input-sha256 "x"})
                      answer? (ct/run "dispatcher" :permission/answered {:wake (:id chat) :session "s" :call "c"
                                                                         :outcome "reject_once" :by "client"})
                      true (ct/run "dispatcher" :wake/end {:wake (:id chat) :outcome "ran"}))))))

(def gen-log
  (gen/fmap (fn [steps]
              (reduce (fn [w s] (if (#{:held :resolve :flag :permission} (first s))
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
           (= (:arose (kind r "hold")) (count (filter #(= "hold/opened" (:type %)) (:events w))))
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
