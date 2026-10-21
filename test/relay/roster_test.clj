(ns relay.roster-test
  "The roster and its wire form: an example, and properties over states
  made by core-test's model of relay, conversations and turns included."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.core :as core]
            [relay.core-test :as ct]
            [relay.roster :as roster]))

(defn- seeded []
  (-> (ct/world)
      (ct/run "operator" :role/add {:id "hub" :title "Hub" :instructions "c"})
      (ct/run "operator" :role/add {:id "clerk" :title "Clerk" :instructions "c"})))

(deftest the-roster-says-what-each-role-is-doing
  (let [w (-> (seeded)
              (ct/run "operator" :memo/send {:to "hub" :subject "s" :body "b"})
              (ct/run "dispatcher" :wake/start {:role "hub" :dispatched true})
              (ct/run "operator" :wake/start {:role "clerk" :chat true}))
        chat (:id (core/open-conversation (:state w) "clerk"))
        opened (:now w)
        w (-> w (ct/advance 60)
              (ct/run "operator" :conversation/turn-start {:wake chat :session "sess-1"})
              (ct/run "dispatcher" :permission/asked {:wake chat :session "sess-1" :call "c1" :tool "post"
                                                      :input-sha256 "x"})
              (ct/advance 60))
        later (:now w)
        w (ct/run w "dispatcher" :conversation/turn-end {:wake chat :stop-reason "end_turn"})
        [clerk hub :as r] (roster/roster (:state w) ct/cfg (:now w))]
    (is (= ["clerk" "hub"] (map :id r)) "in relay roles order")
    (is (= {:id "hub" :title "Hub" :labels [] :working? true :holds 0 :conversation nil} hub))
    (is (= {:id "clerk" :title "Clerk" :labels [] :working? false :holds 0
            :conversation {:wake chat :session "sess-1" :opened-at opened :last-activity later
                           :turn :idle :turns 1 :unread? true :approvals 1}}
           clerk))
    (testing "seen through the last turn, it is read"
      (let [w (ct/run w "operator" :conversation/seen {:wake chat :turn 1})]
        (is (false? (get-in (roster/roster (:state w) ct/cfg (:now w)) [0 :conversation :unread?])))))
    (testing "the wire form is what _relay/roles sends"
      (is (= {"roles" [{"id" "clerk" "title" "Clerk" "labels" [] "working" false "holds" 0
                        "conversation" {"sessionId" "sess-1" "openedAt" (str opened)
                                        "lastActivity" (str later) "turn" "idle" "turns" 1
                                        "unread" true "approvals" 1}}
                       {"id" "hub" "title" "Hub" "labels" [] "working" true "holds" 0 "conversation" nil}]}
             (roster/wire r))))
    (testing "before its first turn a conversation has no session"
      (let [w (ct/run (seeded) "operator" :wake/start {:role "hub" :chat true})]
        (is (= {"sessionId" nil "turn" "idle" "turns" 0 "unread" false "approvals" 0}
               (select-keys (get-in (roster/wire (roster/roster (:state w) ct/cfg (:now w))) ["roles" 1 "conversation"])
                            ["sessionId" "turn" "turns" "unread" "approvals"])))))))

(deftest a-role-s-labels-are-those-of-its-id-and-title
  (let [as (fn [state actor labels type args]
             (first (core/run-command state {:type type :actor actor :labels labels :args args}
                                      {:now ct/t0 :config ct/cfg})))
        s (-> (:state (seeded))
              (as "hub" ["private"] :role/add {:id "scout" :title "Scout" :instructions "c"})
              (as "operator" [] :role/update {:id "scout" :title "Scout of the web"}))
        s' (as s "hub" ["contaminated"] :role/update {:id "scout" :instructions "Read pages."})
        scout #(first (filter (fn [e] (= "scout" (:id e))) (roster/roster % ct/cfg ct/t0)))]
    (is (= ["private"] (:labels (scout s))) "hub's session wrote its id")
    (is (= (:labels (scout s)) (:labels (scout s'))) "its instructions are not shown")))

;; --- properties over reachable states

(def gen-world
  (gen/fmap (fn [steps]
              (reduce (fn [w s] (#'ct/step (dissoc w :problem) s)) (seeded) steps))
            (gen/vector ct/gen-step 1 60)))

(defn- event-max
  "The highest :turn of the events of this type for the wake, or 0."
  [events type wake]
  (reduce max 0 (keep #(when (and (= type (:type %)) (= wake (get-in % [:data :wake])))
                         (get-in % [:data :turn]))
                      events)))

(defspec the-roster-is-total-and-follows-the-log 300
  (prop/for-all [w gen-world]
    (let [s (:state w)
          r (roster/roster s ct/cfg (:now w))]
      (and
       ;; Every active role once, in relay roles order.
       (= (core/active-role-ids s) (mapv :id r))
       (every? (fn [{:keys [id working? holds conversation] :as e}]
                 (let [chat (core/open-conversation s id)]
                   (and (= #{:id :title :labels :working? :holds :conversation} (set (keys e)))
                        ;; Busy iff a wake works or a turn runs.
                        (= (core/busy? s id) (or working? (= :running (:turn conversation))))
                        (= working? (some? (core/working-wake s id)))
                        (= holds (count (core/open-holds s id)))
                        ;; A conversation is shown iff one is open.
                        (= (some? conversation) (some? chat))
                        (or (nil? conversation)
                            (let [{:keys [wake turns unread? approvals turn opened-at last-activity]} conversation
                                  events (:events w)]
                              (and (= wake (:id chat))
                                   (#{:idle :running} turn)
                                   (= turns (event-max events "conversation/turn-started" wake))
                                   ;; Unread iff a turn ended after the last one seen.
                                   (= unread? (> (event-max events "conversation/turn-ended" wake)
                                                 (event-max events "conversation/seen" wake)))
                                   (= approvals (count (filter #(and (= wake (:wake %)) (nil? (:outcome %)))
                                                               (vals (:permissions s)))))
                                   (not (.isBefore ^java.time.Instant last-activity opened-at))))))))
               r)
       ;; The wire form survives JSON as it is.
       (let [wire (roster/wire r)]
         (= wire (json/parse-string (json/generate-string wire))))))))
