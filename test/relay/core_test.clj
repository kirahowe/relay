(ns relay.core-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.config :as config]
            [relay.core :as core]
            [relay.dispatch :as dispatch])
  (:import (java.time Instant)))

(def t0 (Instant/parse "2026-10-14T12:00:00Z"))

(def cfg (config/resolve-config {:timezone "America/Toronto"
                                 :default-harness :agentloop
                                 :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"]}}
                                 :notify {:kind :none}
                                 :max-attempts 3
                                 :runaway-wakes 6
                                 ;; courier is public: memos to it from anyone
                                 ;; but itself wait for the operator.
                                 :grants {"hub" {:may-add true} "courier" {:public true}}}))

;; --- a small driver: a world is a state, a clock and the events so far

(defn world [] {:state core/empty-state :now t0 :events []})

(defn run
  "Runs a command, returning the world after it. A rejection leaves the world
  as it was and is recorded under :rejected."
  [{:keys [state now] :as w} actor type args & [public]]
  (try
    (let [[state' events] (core/run-command state {:type type :actor actor :args args :public public}
                                            {:now now :config cfg})]
      (-> w (assoc :state state' :rejected nil :last events) (update :events into events)))
    (catch clojure.lang.ExceptionInfo e
      (if (core/rejection e)
        (assoc w :rejected (core/rejection e) :rejected-message (ex-message e) :last [])
        (throw e)))))

(defn advance [w seconds] (update w :now #(.plusSeconds ^Instant % seconds)))

(defn- seed []
  (-> (world)
      (run "operator" :role/add {:id "hub" :title "Hub" :instructions "Run relay."})
      (run "operator" :role/add {:id "clerk" :title "Clerk" :instructions "File things."})))

(defn- memos-to [w to] (core/unread-memos (:state w) to))
(defn- last-memo-id [w] (:id (last (vals (:memos (:state w))))))

(defn- wake
  "Starts a wake of a role and ends it with this outcome, closing the memos
  in `closes` in between."
  [w role-id {:keys [outcome closes dispatched resets-at cut-short] :or {outcome "ran"}}]
  (let [w (run w "dispatcher" :wake/start {:role role-id :dispatched dispatched})
        wake-id (some-> (core/running-wake (:state w) role-id) :id)]
    (if-not wake-id
      w
      (as-> w w
        (reduce (fn [w m] (run w role-id :memo/close {:memo m :outcome "Done."})) w closes)
        (run w "dispatcher" :wake/end {:wake wake-id :outcome outcome :exit 0
                                       :crashed (boolean cut-short) :cut-short cut-short
                                       :resets-at resets-at :said "the end"})))))

(declare json-round-trip)

(defn- notifications [w] (map :kind (sort-by :id (vals (:notifications (:state w))))))

;; --- examples

(deftest a-wake-cut-short-at-its-deadline-is-kept-and-resumed
  (let [w (-> (seed) (run "operator" :memo/send {:to "clerk" :subject "s" :body "b"}))
        id (last-memo-id w)
        w (wake w "clerk" {:outcome "ran" :cut-short true :dispatched true})
        cut (core/role (:state w) "clerk")
        ended (last (sort-by :id (vals (:wakes (:state w)))))]
    (is (true? (:cut-short ended)))
    (is (= (:id ended) (:interrupted-wake cut)))
    (testing "it still counts as having run"
      (is (= 1 (:attempts (core/memo (:state w) id)))))
    (testing "an ordinary run that finishes is not kept"
      (let [w (wake (advance w 7200) "clerk" {:outcome "ran" :dispatched true})]
        (is (nil? (:interrupted-wake (core/role (:state w) "clerk"))))))
    (testing "a conversation cut short is not kept"
      (let [w (-> w (run "operator" :wake/start {:role "clerk" :chat true}))
            chat (core/running-wake (:state w) "clerk")
            w (run w "dispatcher" :wake/end {:wake (:id chat) :outcome "ran" :cut-short true})]
        (is (not= (:id chat) (:interrupted-wake (core/role (:state w) "clerk"))))))))

(deftest a-memo-is-closed-only-by-its-recipient
  (let [w (-> (seed) (run "operator" :memo/send {:to "clerk" :subject "File this" :body "Please."}))
        id (last-memo-id w)]
    (is (= :forbidden (:rejected (run w "hub" :memo/close {:memo id :outcome "x"}))))
    (is (= :invalid (:rejected (run w "clerk" :memo/close {:memo id :outcome "  "}))))
    (let [w (run w "clerk" :memo/close {:memo id :outcome "Filed it."})]
      (is (= :read (:status (core/memo (:state w) id))))
      (is (= "Filed it." (:outcome (core/memo (:state w) id))))
      (testing "and only once"
        (is (= :invalid (:rejected (run w "clerk" :memo/close {:memo id :outcome "again"}))))))))

(deftest only-memos-to-the-operator-notify
  (let [w (-> (seed)
              (run "operator" :memo/send {:to "clerk" :subject "Quiet" :body "b"})
              (run "clerk" :memo/send {:to "operator" :subject "Approve the filing?" :body "Yes or no."}))]
    (is (= ["memo-for-operator"] (notifications w)))
    (is (= "Approve the filing?" (:title (first (vals (:notifications (:state w)))))))))

(deftest a-clean-run-that-closes-nothing-is-a-failed-attempt
  (let [w (-> (seed) (run "operator" :memo/send {:to "clerk" :subject "File" :body "b"}))
        id (last-memo-id w)
        w (wake w "clerk" {:dispatched true})
        m (core/memo (:state w) id)]
    (is (= 1 (:attempts m)))
    (is (= (.plusSeconds t0 60) (:retry-at m)))
    (testing "it waits out its delay, so the dispatcher leaves it alone"
      (is (empty? (core/roles-to-wake (:state w) cfg (:now w))))
      (is (= ["clerk"] (core/roles-to-wake (:state w) cfg (.plusSeconds t0 60)))))
    (testing "a wake asked for by hand ignores the delay"
      (is (= [id] (:memos (core/running-wake (:state (run w "operator" :wake/start {:role "clerk"})) "clerk")))))))

(deftest a-memo-never-closed-is-dead-lettered-to-the-hub
  (let [w (-> (seed) (run "operator" :memo/send {:to "clerk" :subject "File this" :body "b"}))
        id (last-memo-id w)
        w (-> w (wake "clerk" {}) (wake "clerk" {}) (wake "clerk" {}))]
    (is (= :dead (:status (core/memo (:state w) id))))
    (is (= 3 (:attempts (core/memo (:state w) id))))
    (let [[report] (memos-to w "hub")]
      (is (= "dispatcher" (:from report)))
      (is (str/includes? (:subject report) "File this"))
      (is (str/includes? (:body report) "relay retry clerk"))
      (is (str/includes? (:body report) "the end")))
    (is (empty? (notifications w)) "the operator is not told")
    (testing "a retry puts it back with a clean slate"
      (let [w (run w "hub" :memo/retry {:role "clerk"})
            m (core/memo (:state w) id)]
        (is (= [:unread 0 nil] [(:status m) (:attempts m) (:retry-at m)]))
        (is (every? #(= :requeued (:status %)) (vals (:dead-letters (:state w)))))))
    (testing "but not by a role with no say over dead letters"
      (is (= :forbidden (:rejected (run w "clerk" :memo/retry {:role "clerk"})))))))

(deftest blocking-skips-the-retries
  (let [w (-> (seed) (run "operator" :memo/send {:to "clerk" :subject "Impossible" :body "b"}))
        id (last-memo-id w)
        w (run w "clerk" :memo/block {:memo id :explanation "The cabinet is locked."})]
    (is (= :dead (:status (core/memo (:state w) id))))
    (is (str/includes? (:body (first (memos-to w "hub"))) "The cabinet is locked."))
    (testing "the operator cannot block, having nobody to pass it to"
      (let [w (run w "operator" :memo/send {:to "clerk" :subject "Another" :body "b"})]
        (is (= :forbidden (:rejected (run w "operator" :memo/block {:memo (last-memo-id w) :explanation "x"}))))))))

(deftest the-hubs-own-dead-letters-go-to-the-operator
  (let [w (-> (seed) (run "operator" :memo/send {:to "hub" :subject "Do it" :body "b"}))
        w (-> w (wake "hub" {}) (wake "hub" {}) (wake "hub" {}))]
    (is (= 1 (count (core/dead-memos (:state w) "hub"))))
    (is (empty? (memos-to w "hub")))
    (is (= ["work-abandoned"] (notifications w)))))

(deftest a-due-routine-sends-its-owner-a-memo
  (let [w (-> (seed) (run "clerk" :routine/write {:name "sweep" :schedule "every 10m"
                                                  :instructions "Sweep the drafts."}))]
    (is (empty? (:last (run w "dispatcher" :tick {}))) "not yet due")
    (let [w (-> w (advance 600) (run "dispatcher" :tick {}))
          [m] (memos-to w "clerk")]
      (is (= ["sweep" "Sweep the drafts." "sweep"] [(:subject m) (:body m) (:routine m)]))
      (testing "it does not fire again while its run is open"
        (let [w (-> w (advance 3600) (run "dispatcher" :tick {}))]
          (is (= 1 (count (memos-to w "clerk"))))
          (testing "but does once it is closed"
            (let [w (-> w (run "clerk" :memo/close {:memo (:id m) :outcome "Swept."})
                        (run "dispatcher" :tick {}))]
              (is (= 1 (count (memos-to w "clerk"))))
              (is (not= (:id m) (:id (first (memos-to w "clerk"))))))))))))

(deftest editing-a-routine-restarts-its-clock
  (let [w (-> (seed)
              (run "clerk" :routine/write {:name "sweep" :schedule "every 10m" :instructions "a"})
              (advance 300)
              (run "clerk" :routine/write {:name "sweep" :schedule "every 10m" :instructions "b"})
              (advance 400)
              (run "dispatcher" :tick {}))]
    (is (empty? (memos-to w "clerk")))
    (is (= 1 (count (memos-to (-> w (advance 200) (run "dispatcher" :tick {})) "clerk"))))))

(deftest a-one-off-routine-retires-when-it-fires
  (let [w (-> (seed)
              (run "clerk" :routine/write {:name "remind" :schedule "once 2026-10-14 09:00"
                                           :instructions "Remind."})
              (advance 86400)
              (run "dispatcher" :tick {}))]
    (is (= :retired (:status (core/routine (:state w) "clerk" "remind"))))
    (is (= 1 (count (memos-to w "clerk"))))))

(deftest a-dead-routine-run-replaced-by-a-newer-one-is-not-requeued
  ;; Found by the model: requeueing an old run of a routine that has fired
  ;; again since gave the routine two open runs.
  (let [w (-> (seed)
              (run "clerk" :routine/write {:name "sweep" :schedule "every 10m" :instructions "i"})
              (advance 600) (run "dispatcher" :tick {}))
        old (:id (first (memos-to w "clerk")))
        w (-> w (wake "clerk" {}) (advance 600) (wake "clerk" {}) (advance 600) (wake "clerk" {})
              (run "dispatcher" :tick {}))
        newer (:id (first (memos-to w "clerk")))
        w (run w "operator" :memo/retry {:role "clerk"})]
    (is (not= old newer))
    (is (= [newer] (map :id (memos-to w "clerk"))))
    (is (= newer (:superseded-by (core/memo (:state w) old))))
    (is (empty? (core/dead-memos (:state w) "clerk")))
    (is (every? #(not= :open (:status %)) (vals (:dead-letters (:state w)))))))

(deftest a-routine-needs-a-schedule-relay-understands
  (is (= :invalid (:rejected (run (seed) "clerk" :routine/write {:name "x" :schedule "daily 25:00"
                                                                 :instructions "i"})))))

(deftest the-rate-limit-pauses-instead-of-failing
  (let [w (-> (seed) (run "operator" :memo/send {:to "clerk" :subject "File" :body "b"}))
        id (last-memo-id w)
        resets (.plusSeconds t0 7200)
        w (wake w "clerk" {:outcome "limited" :resets-at (str resets)})]
    (testing "the memo has not failed"
      (is (zero? (:attempts (core/memo (:state w) id)))))
    (testing "the harness is paused until the reset the run reported"
      (is (= resets (core/paused-until (:state w) "agentloop" (:now w))))
      (is (empty? (core/roles-to-wake (:state w) cfg (:now w))))
      (is (= :paused (:rejected (run w "dispatcher" :wake/start {:role "clerk" :dispatched true})))))
    (testing "however often it happens, it never becomes a dead letter"
      (let [w (reduce (fn [w _] (wake w "clerk" {:outcome "limited"})) w (range 6))]
        (is (= :unread (:status (core/memo (:state w) id))))
        (is (empty? (notifications w)))))
    (testing "the next wake resumes the one cut short, and a good run lifts the pause"
      (let [interrupted (:interrupted-wake (core/role (:state w) "clerk"))
            w (-> w (advance 7200) (run "dispatcher" :wake/start {:role "clerk" :dispatched true}))
            running (core/running-wake (:state w) "clerk")]
        (is (some? interrupted))
        (is (= interrupted (:resumes running)))
        (let [w (run w "dispatcher" :wake/end {:wake (:id running) :outcome "ran"})]
          (is (nil? (core/paused-until (:state w) "agentloop" (:now w))))
          (is (nil? (:interrupted-wake (core/role (:state w) "clerk")))))))))

(deftest a-limit-with-no-reset-is-probed-again-soon
  (let [w (-> (seed) (run "operator" :memo/send {:to "clerk" :subject "File" :body "b"})
              (wake "clerk" {:outcome "limited"}))]
    (is (= (.plusSeconds t0 900) (core/paused-until (:state w) "agentloop" (:now w))))))

(deftest a-harness-that-needs-a-person-says-so-once
  (let [w (-> (seed) (run "operator" :memo/send {:to "clerk" :subject "File" :body "b"}))
        w (reduce (fn [w _] (-> w (wake "clerk" {:outcome "needs-attention"}) (advance 4000)))
                  w (range 3))]
    (is (= ["harness-needs-attention"] (notifications w)))
    (is (zero? (:attempts (first (memos-to w "clerk")))))))

(deftest an-unreachable-harness-is-waited-out-quietly-then-reported-once
  (let [w (-> (seed) (run "operator" :memo/send {:to "clerk" :subject "File" :body "b"}))
        step (fn [w] (let [until (core/paused-until (:state w) "agentloop" (:now w))
                           w (if until (assoc w :now until) w)]
                       (-> w (wake "clerk" {:outcome "unreachable"}) (run "dispatcher" :tick {}))))
        w (nth (iterate step w) 6)]
    (testing "the waits double"
      (is (= 6 (get-in (:state w) [:harnesses "agentloop" :failed-tries])))
      (is (= (.plusSeconds (:now w) 1920) (core/paused-until (:state w) "agentloop" (:now w)))))
    (testing "failed wakes do not trip the runaway guard"
      (is (empty? (filter #{"runaway-guard-tripped"} (notifications w)))))
    (testing "not told within the patience, told once after it"
      (is (empty? (notifications w)))
      (let [w (-> w (advance 7200) (run "dispatcher" :tick {}) (advance 60) (run "dispatcher" :tick {}))]
        (is (= ["harness-unreachable"] (notifications w)))))))

(deftest a-conversation-records-the-sessions-it-used-in-order
  (let [w (run (seed) "operator" :wake/start {:role "clerk" :chat true})
        chat (core/running-wake (:state w) "clerk")
        end #(run w "dispatcher" :wake/end (merge {:wake (:id chat) :outcome "ran"} %))]
    (is (= ["b" "a"] (get-in (:state (end {:sessions ["b" "a"]})) [:wakes (:id chat) :sessions])))
    (is (= ["b" "a"] (get-in (core/replay (json-round-trip (:events (end {:sessions '("b" "a")}))))
                             [:wakes (:id chat) :sessions]))
        "kept in order through the log")
    (is (nil? (get-in (:state (end {})) [:wakes (:id chat) :sessions])))
    (testing "anything but a list of session ids is refused"
      (doseq [bad ["s1" [nil] [""] [1] {:a 1}]]
        (is (= :invalid (:rejected (end {:sessions bad}))) (pr-str bad)))))
  (testing "a wake has one session, not a list"
    (let [w (-> (seed) (run "operator" :memo/send {:to "clerk" :subject "a" :body "b"})
                (run "dispatcher" :wake/start {:role "clerk"}))
          wake-id (:id (core/running-wake (:state w) "clerk"))]
      (is (= :invalid (:rejected (run w "dispatcher" :wake/end {:wake wake-id :outcome "ran"
                                                                 :sessions ["s"]})))))))

(deftest a-conversation-holds-the-role-but-carries-no-memos
  (let [w (-> (seed) (run "operator" :memo/send {:to "clerk" :subject "a" :body "b"}))
        id (last-memo-id w)
        w (run w "operator" :wake/start {:role "clerk" :chat true})
        chat (core/running-wake (:state w) "clerk")]
    (is (= [] (:memos chat)))
    (is (= :busy (:rejected (run w "dispatcher" :wake/start {:role "clerk" :dispatched true}))))
    (is (= :forbidden (:rejected (run (seed) "dispatcher" :wake/start {:role "clerk" :chat true}))))
    (let [w (run w "dispatcher" :wake/end {:wake (:id chat) :outcome "unreachable"})]
      (is (zero? (:attempts (core/memo (:state w) id))))
      (is (nil? (:interrupted-wake (core/role (:state w) "clerk")))
          "a conversation cut short is not resumed by the next wake"))))

(deftest one-wake-at-a-time
  (let [w (-> (seed) (run "operator" :memo/send {:to "clerk" :subject "a" :body "b"})
              (run "dispatcher" :wake/start {:role "clerk"}))]
    (is (= :busy (:rejected (run w "operator" :wake/start {:role "clerk"}))))
    (is (= :busy (:rejected (run w "operator" :role/remove {:id "clerk"}))))))

(deftest a-lost-wake-is-ended-by-the-tick-and-counts-against-its-memos
  (let [w (-> (seed) (run "operator" :memo/send {:to "clerk" :subject "a" :body "b"})
              (run "dispatcher" :wake/start {:role "clerk"}))
        wake-id (:id (core/running-wake (:state w) "clerk"))
        w (run w "dispatcher" :tick {:lost-wakes [wake-id]})]
    (is (not (core/running? (:state w) "clerk")))
    (is (true? (:crashed (get-in (:state w) [:wakes wake-id]))))
    (is (= 1 (:attempts (first (memos-to w "clerk")))))))

(deftest a-lost-conversation-is-ended-by-the-tick-as-its-client-going-away
  ;; Regression: Zed closes a thread with SIGKILL to relay's process group,
  ;; and the tick recorded every such conversation as a crash.
  (let [w (-> (seed) (run "operator" :wake/start {:role "clerk" :chat true}))
        wake-id (:id (core/running-wake (:state w) "clerk"))
        w (run w "dispatcher" :tick {:lost-wakes [wake-id]})
        wake (get-in (:state w) [:wakes wake-id])]
    (is (not (core/running? (:state w) "clerk")))
    (is (= [:ended "ran" false false core/conversation-gone]
           ((juxt :status :outcome :crashed :cut-short :said) wake)))
    (is (nil? (:interrupted-wake (core/role (:state w) "clerk"))) "nothing is left to resume")))

(deftest the-runaway-guard-stops-a-loop-and-says-so-once-an-hour
  (let [w (seed)
        loop-once (fn [w] (let [w (run w "operator" :memo/send {:to "clerk" :subject "again" :body "b"})]
                            (-> w (wake "clerk" {:closes [(last-memo-id w)]}) (advance 60))))
        w (nth (iterate loop-once w) 6)
        w (-> w (run "operator" :memo/send {:to "clerk" :subject "more" :body "b"}))]
    (is (empty? (core/roles-to-wake (:state w) cfg (:now w))))
    (let [w (-> w (run "dispatcher" :tick {}) (run "dispatcher" :tick {}))]
      (is (= ["runaway-guard-tripped"] (notifications w))))
    (testing "the work waits, and wakes once the window has room"
      (is (= ["clerk"] (core/roles-to-wake (:state w) cfg (.plusSeconds (:now w) 3600)))))))

(deftest the-registry
  (let [w (seed)]
    (testing "only the operator and roles granted :may-add change it"
      (is (= :forbidden (:rejected (run w "clerk" :role/add {:id "x" :instructions "c"}))))
      (is (nil? (:rejected (run w "hub" :role/add {:id "x" :instructions "c"})))))
    (testing "a role added by a role reports to it"
      (is (= "hub" (:reports-to (core/role (:state (run w "hub" :role/add {:id "x" :instructions "c"})) "x")))))
    (testing "ids are checked and never reused"
      (is (= :invalid (:rejected (run w "operator" :role/add {:id "Bad Id" :instructions "c"}))))
      (is (= :invalid (:rejected (run w "operator" :role/add {:id "operator" :instructions "c"}))))
      (let [w (run w "operator" :role/remove {:id "clerk"})]
        (is (= :conflict (:rejected (run w "operator" :role/add {:id "clerk" :instructions "c"}))))))
    (testing "nobody changes or removes themselves"
      (is (= :forbidden (:rejected (run w "hub" :role/update {:id "hub" :instructions "More power."}))))
      (is (= :forbidden (:rejected (run w "hub" :role/remove {:id "hub"})))))
    (testing "removing a role dead-letters its open memos and retires its routines"
      (let [w (-> w
                  (run "operator" :memo/send {:to "clerk" :subject "Open" :body "b"})
                  (run "clerk" :routine/write {:name "r" :schedule "every 1h" :instructions "i"})
                  (run "operator" :role/remove {:id "clerk"}))]
        (is (= 1 (count (core/dead-memos (:state w) "clerk"))))
        (is (= :removed (:status (core/routine (:state w) "clerk" "r"))))
        (is (= 1 (count (memos-to w "hub"))))
        (testing "and a removed role can no longer act"
          (is (= :forbidden (:rejected (run w "clerk" :memo/send {:to "hub" :subject "s" :body "b"})))))))))

;; --- the model: random sequences of everything anyone can do

(def role-pool ["hub" "clerk" "scribe" "courier"])
(def actors (into ["operator" "dispatcher"] role-pool))

(def world-times [nil "2026-09-01" "2026-10-14" "2026-10-15 09:00" "2026-11-01" "2027-01-01"])

(def as-of-samples
  (mapv #(Instant/parse %) ["2026-08-01T00:00:00Z" "2026-09-15T00:00:00Z" "2026-10-14T12:00:00Z"
                            "2026-10-20T00:00:00Z" "2026-12-01T00:00:00Z" "2027-06-01T00:00:00Z"]))

(def gen-step
  (gen/frequency
   [[3 (gen/tuple (gen/return :advance) (gen/elements [1 30 60 600 3600 7200 86400]))]
    [5 (gen/tuple (gen/return :send) (gen/elements actors) (gen/elements (conj role-pool "operator")))]
    [4 (gen/tuple (gen/return :close) (gen/elements actors) gen/nat)]
    [2 (gen/tuple (gen/return :block) (gen/elements role-pool) gen/nat)]
    [3 (gen/tuple (gen/return :retry) (gen/elements (into ["operator" "operator" "hub"] actors))
                  (gen/elements role-pool))]
    ;; Bursts of wakes that close nothing drive memos to the dead letter
    ;; queue and roles into the runaway guard.
    [2 (gen/tuple (gen/return :burst) (gen/elements role-pool) (gen/choose 1 8))]
    [2 (gen/tuple (gen/return :add) (gen/elements actors) (gen/elements role-pool))]
    [1 (gen/tuple (gen/return :remove) (gen/elements actors) (gen/elements role-pool))]
    [2 (gen/tuple (gen/return :routine) (gen/elements role-pool) (gen/elements ["a" "b"])
                  (gen/elements ["every 10m" "every 2h" "daily 07:30" "weekly mon 09:00"
                                 "once 2026-10-15 09:00" "nonsense"]))]
    [1 (gen/tuple (gen/return :unroutine) (gen/elements role-pool) (gen/elements ["a" "b"]))]
    [4 (gen/tuple (gen/return :tick) gen/boolean)]
    [6 (gen/tuple (gen/return :wake) (gen/elements role-pool) gen/boolean
                  (gen/frequency [[6 (gen/return "ran")] [1 (gen/return "limited")]
                                  [1 (gen/return "unreachable")] [1 (gen/return "needs-attention")]])
                  (gen/vector gen/nat 0 3) gen/boolean gen/boolean)]
    [1 (gen/tuple (gen/return :start-only) (gen/elements role-pool))]
    [1 (gen/tuple (gen/return :chat) (gen/elements role-pool)
                  (gen/elements ["ran" "limited" "unreachable" "needs-attention"])
                  (gen/one-of [(gen/return nil) (gen/vector (gen/elements ["s1" "s2" "s3"]) 0 3)]))]
    ;; A conversation opened and, some steps later, ended the way relay acp
    ;; ends one: with whatever its proxy reported, through
    ;; conversation-end-args. Everything else can happen in between.
    [2 (gen/tuple (gen/return :chat-open) (gen/elements role-pool))]
    [3 (gen/tuple (gen/return :chat-close) gen/nat
                  (gen/one-of [(gen/return nil) (gen/elements [0 1 137 "x"])])
                  (gen/one-of [(gen/return nil) (gen/vector (gen/elements ["s1" "s2" "" nil 3]) 0 3)])
                  (gen/elements [nil "" "Conversation ended. Its last turn ended with end_turn."]))]
    ;; Knowledge. World times are dates either side of the start, so that
    ;; changes and corrections land before, between and after each other.
    [3 (gen/tuple (gen/return :fact-add) (gen/elements actors) (gen/elements [nil "home" "home/car" "work"])
                  (gen/elements world-times) (gen/elements world-times))]
    [3 (gen/tuple (gen/return :fact-supersede) (gen/elements actors) gen/nat (gen/elements world-times))]
    [3 (gen/tuple (gen/return :fact-correct) (gen/elements actors) gen/nat
                  (gen/elements [:text :retract :from :to :open]) (gen/elements world-times))]
    [3 (gen/tuple (gen/return :note) (gen/elements actors) (gen/elements role-pool)
                  (gen/elements ["a" "b"]) (gen/elements ["x" "y" nil]))]
    [1 (gen/tuple (gen/return :journal) (gen/elements actors) (gen/elements role-pool))]
    [3 (gen/tuple (gen/return :declassify) (gen/elements ["operator" "operator" "hub" "courier" "dispatcher"]) gen/nat)]
    [2 (gen/tuple (gen/return :send-public) (gen/elements (conj role-pool "operator")))]
    [2 (gen/tuple (gen/return :doc) (gen/elements actors) (gen/elements ["a.md" "dir/b.md"])
                  (gen/elements ["one" "two" nil]))]]))

(defn- nth-or [coll n] (when (seq coll) (nth coll (mod n (count coll)))))

(defn- step [w [kind & args]]
  (let [ids (sort (keys (:memos (:state w))))]
    (case kind
      :advance (advance w (first args))
      :send (let [[from to] args]
              (run w from :memo/send {:to to :subject (str "s" (count ids)) :body "b"}))
      :send-public (run w "operator" :memo/send {:to (first args) :subject (str "s" (count ids)) :body "b"} true)
      :declassify (let [[actor n] args
                        waiting (filter #(core/awaiting-declassification? cfg %) (core/unread-memos (:state w) "courier"))
                        id (if (seq waiting) (:id (nth-or (vec waiting) n)) (nth-or ids n))
                        w' (run w actor :memo/declassify {:memo id})]
                    ;; A declassified memo is deliverable from then on.
                    (if (and (seq (:last w')) (not (some #(= id (:id %)) (core/deliverable-memos (:state w') cfg "courier"))))
                      (assoc w' :problem [:declassified-not-deliverable id])
                      w'))
      :close (let [[actor n] args] (run w actor :memo/close {:memo (nth-or ids n) :outcome "done"}))
      :block (let [[actor n] args] (run w actor :memo/block {:memo (nth-or ids n) :explanation "can't"}))
      :retry (let [[actor r] args] (run w actor :memo/retry {:role r}))
      :add (let [[actor id] args] (run w actor :role/add {:id id :instructions "c"} (= "operator" actor)))
      :remove (let [[actor id] args] (run w actor :role/remove {:id id}))
      :routine (let [[owner n s] args]
                 (run w owner :routine/write {:name n :schedule s :instructions "i"}))
      :unroutine (let [[owner n] args] (run w owner :routine/remove {:name n}))
      :tick (let [[lose?] args
                  running (keep #(when (= :running (:status %)) (:id %)) (vals (:wakes (:state w))))
                  lost (if lose? running [])
                  w' (run w "dispatcher" :tick {:lost-wakes lost})
                  ;; A lost wake is a crash; a lost conversation is its
                  ;; client going away.
                  misrecorded (for [id lost
                                    :let [{:keys [chat crashed status]} (get-in (:state w') [:wakes id])]
                                    :when (or (not= :ended status) (not= crashed (not chat)))]
                                id)]
              (cond-> w' (seq misrecorded) (assoc :problem [:lost-wake-misrecorded misrecorded])))
      :start-only (run w "dispatcher" :wake/start {:role (first args) :dispatched true})
      :chat (let [[r outcome sessions] args
                  w (run w "operator" :wake/start {:role r :chat true})
                  chat (core/running-wake (:state w) r)]
              (if (and chat (:chat chat))
                (run w "dispatcher" :wake/end (cond-> {:wake (:id chat) :outcome outcome}
                                                sessions (assoc :sessions sessions)))
                w))
      :chat-open (run w "operator" :wake/start {:role (first args) :chat true})
      :chat-close (let [[n exit sessions said] args
                        chat (nth-or (sort-by :id (filter #(and (:chat %) (= :running (:status %)))
                                                          (vals (:wakes (:state w)))))
                                     n)]
                    (if chat
                      (let [w' (run w "dispatcher" :wake/end
                                    (dispatch/conversation-end-args
                                     (:id chat) {:exit exit :sessions sessions :said said}))]
                        (cond-> w' (:rejected w') (assoc :problem [:conversation-end-refused (:rejected-message w')])))
                      w))
      :fact-add (let [[actor topic from to] args]
                  (run w actor :fact/add {:topic topic :text (str "f" (count (:events w)))
                                          :valid-from from :valid-to to}))
      :fact-supersede (let [[actor n from] args]
                        (run w actor :fact/supersede {:fact (nth-or (sort (keys (:facts (:state w)))) n)
                                                      :text (str "f" (count (:events w))) :valid-from from}))
      :fact-correct (let [[actor n how t] args
                          id (nth-or (sort (keys (:facts (:state w)))) n)]
                      (run w actor :fact/correct
                           (merge {:fact id :why "wrong"}
                                  (case how
                                    :text {:text (str "c" (count (:events w)))}
                                    :retract {:retract true}
                                    :from {:valid-from t}
                                    :to {:valid-to t}
                                    :open {:valid-from "open" :valid-to "open"}))))
      :note (let [[actor owner n text] args]
              (if text
                (run w actor :note/write {:owner owner :name n :text text})
                (run w actor :note/remove {:owner owner :name n})))
      :journal (let [[actor owner] args]
                 (run w actor :journal/write {:owner owner :text "entry"}))
      :doc (let [[actor path content] args]
             (if content
               (run w actor :doc/put {:path path :content content})
               (run w actor :doc/remove {:path path})))
      :burst (let [[r n] args]
               (reduce (fn [w _]
                         (let [w (-> (run w "operator" :memo/send {:to r :subject "burst" :body "b"})
                                     (run "dispatcher" :wake/start {:role r}))
                               wake-row (core/running-wake (:state w) r)]
                           (cond-> w
                             wake-row (run "dispatcher" :wake/end {:wake (:id wake-row) :outcome "ran"})
                             true (advance 61)
                             true (run "dispatcher" :tick {}))))
                       w (range n)))
      :wake (let [[r dispatched outcome closes resets? cut-short] args
                  w (run w "dispatcher" :wake/start {:role r :dispatched dispatched})
                  wake-row (core/running-wake (:state w) r)]
              (if-not wake-row
                w
                (as-> w w
                  (reduce (fn [w n] (run w r :memo/close {:memo (nth-or (:memos wake-row) n)
                                                          :outcome "done"}))
                          w closes)
                  (run w "dispatcher" :wake/end
                       {:wake (:id wake-row) :outcome outcome :exit 0 :said "said"
                        :crashed cut-short :cut-short cut-short
                        :resets-at (when resets? (str (.plusSeconds ^Instant (:now w) 1800)))})))))))

(def legal-transitions
  #{[:unread :read] [:unread :dead] [:dead :unread]})

(defn- check-step
  "The spec's invariants, checked between one world and the next. Returns a
  vector of the ones broken."
  [before after]
  (let [s (:state after)
        memos (vals (:memos s))
        wakes (vals (:wakes s))
        batch (:last after)
        problems (transient [])
        fail! #(conj! problems %)]
    ;; One wake at a time per role.
    (doseq [[r ws] (group-by :role (filter #(= :running (:status %)) wakes))]
      (when (> (count ws) 1) (fail! [:two-wakes r])))
    ;; A wake carries only its own role's memos.
    (doseq [{:keys [role memos]} wakes, m memos]
      (when (not= role (:to (core/memo s m))) (fail! [:foreign-memo role m])))
    ;; The wake that ended last is kept to be resumed when the harness
    ;; failed it or it was cut short at the deadline, unless it was a
    ;; conversation or another wake started after it.
    (let [ended (last (filter #(= "wake/ended" (:type %)) batch))
          started-after (some #(= "wake/started" (:type %))
                              (rest (drop-while #(not (identical? ended %)) batch)))
          w (when ended (get-in s [:wakes (get-in ended [:data :wake])]))]
      (when (and w (not started-after) (not (:chat w)))
        (let [kept? (boolean (or (not= "ran" (:outcome w)) (:cut-short w)))
              held (:interrupted-wake (core/role s (:role w)))]
          (when-not (= kept? (= (:id w) held)) (fail! [:interrupted-wake (:id w) kept? held])))))
    ;; A routine has at most one open run.
    (doseq [[owner rs] (:routines s), [n _] rs]
      (when (> (count (filter #(and (= owner (:to %)) (= n (:routine %)) (= :unread (:status %))) memos)) 1)
        (fail! [:two-open-runs owner n])))
    ;; Attempts stay within the limit.
    (doseq [m memos]
      (when-not (<= 0 (:attempts m) (:max-attempts cfg)) (fail! [:attempts (:id m)])))
    ;; Memo status moves only along the allowed transitions.
    (doseq [m memos
            :let [was (:status (core/memo (:state before) (:id m)))]
            :when (and was (not= was (:status m)))]
      (when-not (legal-transitions [was (:status m)]) (fail! [:transition (:id m) was (:status m)])))
    ;; A memo once superseded stays dead.
    (doseq [m memos :when (:superseded-by m)]
      (when-not (= :dead (:status m)) (fail! [:superseded-revived (:id m)])))
    ;; An open dead letter's memo is dead.
    (doseq [l (vals (:dead-letters s)) :when (= :open (:status l))]
      (when-not (= :dead (:status (core/memo s (:memo l)))) (fail! [:open-letter-live-memo l])))
    ;; Every dead letter is passed on exactly once, to the handler or the operator.
    (let [letters (count (filter #(= "memo/dead-lettered" (:type %)) batch))
          passed (count (filter #(or (and (= "memo/sent" (:type %)) (= "dispatcher" (get-in % [:data :from]))
                                          (str/starts-with? (get-in % [:data :subject]) "Dead letter"))
                                     (and (= "notification/created" (:type %))
                                          (= "work-abandoned" (get-in % [:data :kind]))))
                                batch))]
      (when (not= letters passed) (fail! [:escalation letters passed])))
    ;; A harness failure counts against no memo.
    (let [failed-wakes (set (keep #(when (and (= "wake/ended" (:type %))
                                              (not= "ran" (get-in % [:data :outcome])))
                                     (get-in % [:data :wake]))
                                  batch))]
      (doseq [e batch :when (= "memo/attempt-failed" (:type e))]
        (when (failed-wakes (get-in e [:data :wake])) (fail! [:harness-failure-counted e]))))
    ;; A conversation carries no memos and no attempt is counted against one.
    (doseq [w wakes :when (:chat w)]
      (when (seq (:memos w)) (fail! [:chat-with-memos (:id w)])))
    ;; A running conversation holds its role, and ending it is never refused.
    (doseq [w wakes :when (and (:chat w) (= :running (:status w)))]
      (when-not (= (:id w) (:id (core/running-wake s (:role w)))) (fail! [:chat-not-holding (:id w)])))
    (when-let [p (:problem after)] (fail! p))
    ;; OnlyTheOperatorDeclassifies, and only a memo that was waiting.
    (doseq [e batch :when (and (= "memo/declassified" (:type e)) (not= (:state before) s))]
      (when-not (and (= "operator" (:actor e))
                     (core/awaiting-declassification? cfg (core/memo (:state before) (get-in e [:data :memo]))))
        (fail! [:declassified-by (:actor e)])))
    (doseq [m memos :when (:declassified m)]
      (when-not (some #(and (= "memo/declassified" (:type %)) (= (:id m) (get-in % [:data :memo]))
                            (= "operator" (:actor %)))
                      (:events after))
        (fail! [:declassified-without-the-operator (:id m)])))
    ;; A memo waiting to be declassified is never carried in a wake.
    (doseq [e batch :when (= "wake/started" (:type e)) m (get-in e [:data :memos])]
      (when (core/awaiting-declassification? cfg (core/memo s m)) (fail! [:pending-memo-carried m])))
    ;; NoSessionOfAPublicRoleHoldsPrivate: a public role's wake starts
    ;; with nothing private.
    (doseq [e batch :when (and (= "wake/started" (:type e)) (core/public-role? cfg (get-in e [:data :role])))]
      (when (core/private? (core/wake-labels s cfg (get-in s [:wakes (:seq e)]))) (fail! [:public-wake-private (:seq e)])))
    ;; APublicItemNeverCarriesPrivate.
    (doseq [e batch :when (:public e)]
      (when (core/private? (:labels e)) (fail! [:public-event-private (:seq e)])))
    (doseq [e batch :when (= "memo/attempt-failed" (:type e))]
      (when (get-in s [:wakes (get-in e [:data :wake]) :chat]) (fail! [:chat-counted e])))
    ;; Every memo to the operator notifies, once.
    (doseq [e batch :when (and (= "memo/sent" (:type e)) (= "operator" (get-in e [:data :to])))]
      (when-not (= 1 (count (filter #(and (= "notification/created" (:type %))
                                          (= (:seq e) (get-in % [:data :memo])))
                                    batch)))
        (fail! [:operator-not-notified e])))
    ;; Only active roles are woken, and a dispatched wake never runs on a paused harness.
    (doseq [e batch :when (= "wake/started" (:type e))]
      (when-not (core/active-role? (:state before) (get-in e [:data :role])) (fail! [:woke-inactive e])))
    ;; Memos go to the operator or a role that exists.
    (doseq [m memos]
      (when-not (or (= "operator" (:to m)) (core/role s (:to m))) (fail! [:memo-to-nobody m])))
    ;; Nothing known is ever lost: facts, docs and journals only gain versions.
    (doseq [[k path] [[:facts :versions] [:docs :versions]]
            [id before-row] (get-in before [:state k])
            :let [old (get before-row path)
                  new (get-in s [k id path])]]
      (when-not (= old (take (count old) new)) (fail! [:lost-version k id])))
    (doseq [[owner entries] (:journals (:state before))]
      (when-not (= entries (take (count entries) (get-in s [:journals owner])))
        (fail! [:lost-journal owner])))
    ;; Every version of a fact has its validity in order, and supersession
    ;; links run both ways.
    (doseq [{:keys [id versions]} (vals (:facts s))
            {:keys [valid-from valid-to superseded-by]} versions]
      (when (and valid-from valid-to (not (.isAfter ^Instant valid-to valid-from)))
        (fail! [:validity-order id]))
      (when (and superseded-by (not= id (:supersedes (core/fact s superseded-by))))
        (fail! [:dangling-supersession id superseded-by])))
    ;; A change in the world leaves every answer about times before it alone.
    (doseq [e batch :when (= "fact/superseded" (:type e))
            :let [change (Instant/parse (get-in e [:data :valid-from]))
                  view (fn [state a] (map (juxt :id :topic :text) (core/facts state {:as-of a})))]
            a as-of-samples :when (.isBefore ^Instant a change)]
      (when (not= (view (:state before) a) (view s a)) (fail! [:change-rewrote-the-past (:seq e) a])))
    ;; seq is gapless and ids are seqs.
    (when (not= (:next-seq s) (inc (count (:events after)))) (fail! [:seq]))
    ;; A rejected command changed nothing.
    (when (and (:rejected after) (not= (:state before) s)) (fail! [:rejected-but-changed]))
    (persistent! problems)))

(defn- json-round-trip [events]
  (mapv #(json/parse-string (json/generate-string %) true) events))

(defspec the-rules-hold-under-any-sequence-of-commands 1000
  (prop/for-all [steps (gen/vector gen-step 1 80)]
    (let [start (-> (world)
                    (run "operator" :role/add {:id "hub" :instructions "c"})
                    (run "operator" :role/add {:id "clerk" :instructions "c"}))
          [final problems] (reduce (fn [[w problems] s]
                                     (let [w (dissoc w :problem)
                                           w' (step w s)]
                                       [w' (into problems (check-step w w'))]))
                                   [start []]
                                   steps)
          ;; Every conversation still open ends, as relay acp ends it.
          closed (reduce (fn [w _] (step (dissoc w :problem) [:chat-close 0 0 ["s1"] nil]))
                         final role-pool)]
      (and (empty? problems)
           (empty? (check-step final closed))
           (not-any? #(and (:chat %) (= :running (:status %))) (vals (:wakes (:state closed))))
           (testing "replaying the log from JSON rebuilds exactly the same state"
             (= (:state final) (core/replay (json-round-trip (:events final)))))
           (testing "asking what was known at T ignores everything recorded after T"
             (every? (fn [e]
                       (let [t (Instant/parse (:at e))
                             then (core/replay (take-while #(not (.isAfter (Instant/parse (:at %)) t))
                                                           (:events final)))]
                         (and (= (core/docs (:state final) t) (core/docs then t))
                              (every? #(= (core/facts (:state final) {:as-of % :known-at t})
                                          (core/facts then {:as-of % :known-at t}))
                                      as-of-samples)
                              (= (core/facts (:state final) {:all true :known-at t})
                                 (core/facts then {:all true :known-at t})))))
                     (take-nth 7 (:events final))))))))
