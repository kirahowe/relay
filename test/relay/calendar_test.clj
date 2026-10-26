(ns relay.calendar-test
  "The agent's calendar: events in the log, versioned like facts, rendered
  as a feed that a calendar app can subscribe to. Only roles write it
  (I9), and the feed says only what they wrote."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.core :as core]
            [relay.core-test :as ct :refer [advance run world]]
            [relay.ics :as ics]
            [relay.ics-test :as ics-test]
            [relay.tools :as tools])
  (:import (java.time Instant)))

(defn- seed []
  (-> (world)
      (run "operator" :role/add {:id "hub" :title "Hub" :instructions "Run relay."})
      (run "operator" :role/add {:id "clerk" :title "Clerk" :instructions "File things."})))

(defn- created [w] (:seq (last (:last w))))
(defn- at [s] (Instant/parse s))

(defn- feed [w]
  (ics-test/read-events (ics/render {:now (:now w) :events (core/calendar-feed (:state w))})))

(def dentist {:title "Dentist" :start "2026-10-20 09:00" :end "2026-10-20 10:00"
              :location "Elm Street" :description "Bring the card."})

(deftest an-event-is-added-changed-and-removed-with-its-history
  (let [w (-> (seed) (run "clerk" :calendar/add dentist))
        id (created w)
        w (-> w (advance 60) (run "clerk" :calendar/update {:event id :start "2026-10-21 09:00"
                                                           :end "2026-10-21 10:00" :why "They moved it."}))
        w (-> w (advance 60) (run "clerk" :calendar/remove {:event id :why "Cancelled by the office."}))
        versions (get-in (:state w) [:calendar id :versions])]
    (is (nil? (:rejected w)))
    (testing "every change is a version; removal keeps them all"
      (is (= [:added :changed :removed] (mapv :change versions)))
      (is (= ["Dentist" "Dentist" "Dentist"] (mapv :title versions)))
      (is (= "They moved it." (:why (second versions))))
      (is (= "Cancelled by the office." (:why (last versions)))))
    (testing "times are in the operator's zone, kept as instants"
      (is (= (at "2026-10-20T13:00:00Z") (:start (first versions)))))
    (testing "the feed keeps the removed event as cancelled, with the sequence that cancelled it"
      (let [[e & more] (feed w)]
        (is (empty? more))
        (is (= ["CANCELLED" 2 (ics/uid id)] ((juxt :status :sequence :uid) e)))
        (is (= (at "2026-10-21T13:00:00Z") (:start e)) "it shows where it last was")))
    (testing "a removed event is not changed or removed again"
      (is (= :invalid (:rejected (run w "clerk" :calendar/update {:event id :title "x"}))))
      (is (= :invalid (:rejected (run w "clerk" :calendar/remove {:event id})))))
    (testing "the live calendar no longer lists it; all does"
      (is (empty? (core/calendar-events (:state w) {:as-of (:now w)})))
      (is (= 1 (count (core/calendar-events (:state w) {:all true})))))))

(deftest an-all-day-event-is-a-whole-day-end-to-end
  ;; Regression (U1): calendar_add had no all-day option, so "an all-day
  ;; event for Wed 11 Nov" was an hour at midnight.
  (let [w (-> (seed) (run "clerk" :calendar/add {:title "Remembrance Day" :start "2026-11-11" :all_day true}))
        id (created w)
        [e] (feed w)
        read (fn [w] (:content ((:read (get tools/builtin "calendar"))
                                {:state (:state w) :config ct/cfg :now (:now w)} "clerk" {})))]
    (is (nil? (:rejected w)) (:why w))
    (is (= [(java.time.LocalDate/parse "2026-11-11") (java.time.LocalDate/parse "2026-11-12")]
           ((juxt :start-date :end-date) e)) "DTSTART and DTEND are DATEs: the day, and the day after")
    (is (nil? (:start e)))
    (is (str/includes? (str (read w)) "2026-11-11, all day"))
    (testing "several days, and moved"
      (let [w (run w "clerk" :calendar/update {:event id :start "2026-11-12" :end "2026-11-13"})
            [e] (feed w)]
        (is (nil? (:rejected w)) (:why w))
        (is (= [(java.time.LocalDate/parse "2026-11-12") (java.time.LocalDate/parse "2026-11-14")]
               ((juxt :start-date :end-date) e)))
        (is (str/includes? (str (read w)) "2026-11-12 to 2026-11-13, all day"))
        (testing "a last day before the first is refused"
          (is (= :invalid (:rejected (run w "clerk" :calendar/update {:event id :end "2026-11-01"})))))
        (testing "and made a timed event again"
          (let [w (run w "clerk" :calendar/update {:event id :all_day false :start "2026-11-12 09:00"})
                [e] (feed w)]
            (is (nil? (:rejected w)) (:why w))
            (is (nil? (:start-date e)))
            (is (= 3600 (.getSeconds (java.time.Duration/between (:start e) (:end e)))))))
        (testing "removed, it is still a whole day, cancelled"
          (let [w (run w "clerk" :calendar/remove {:event id})
                [e] (feed w)]
            (is (= ["CANCELLED" (java.time.LocalDate/parse "2026-11-12")] ((juxt :status :start-date) e)))))))
    (testing "an all-day event needs a date"
      (is (= :invalid (:rejected (run (seed) "clerk" :calendar/add {:title "x" :start "soon" :all_day true})))))))

(deftest the-sequence-rises-and-the-uid-stays-with-each-change
  (let [w (-> (seed) (run "clerk" :calendar/add dentist))
        id (created w)
        steps [{:title "Dentist (checkup)"} {:location "Oak Street"} {:description ""} {:end "2026-10-20 11:00"}]
        [w seqs] (reduce (fn [[w seqs] args]
                           (let [w (-> w (advance 60) (run "clerk" :calendar/update (assoc args :event id)))]
                             [w (conj seqs (:sequence (first (feed w))))]))
                         [w [(:sequence (first (feed w)))]] steps)]
    (is (= [0 1 2 3 4] seqs))
    (is (= [(ics/uid id)] (mapv :uid (feed w))))
    (is (= [nil "Oak Street"] [(:description (first (feed w))) (:location (first (feed w)))]) "empty clears")))

(deftest the-feed-has-only-what-roles-wrote
  (let [w (-> (seed) (run "clerk" :calendar/add dentist))]
    (testing "the operator and the dispatcher cannot write the calendar"
      (is (= :forbidden (:rejected (run w "operator" :calendar/add dentist))))
      (is (= :forbidden (:rejected (run w "dispatcher" :calendar/add dentist))))
      (is (= :forbidden (:rejected (run w "operator" :calendar/remove {:event (created w)})))))
    (testing "a public role has no calendar"
      (let [w (run w "operator" :role/add {:id "courier" :title "Courier" :instructions "Carry."} true)]
        (is (= :forbidden (:rejected (run w "courier" :calendar/add dentist))))
        (is (not (contains? (tools/offered ct/cfg "courier") "calendar_add")))
        (is (not (contains? (tools/offered ct/cfg "courier") "calendar")))))
    (testing "nothing but an event written by a role is in the feed"
      (is (= 1 (count (feed w)))))))

(deftest events-are-checked
  (let [w (seed)]
    (doseq [[args why] [[(dissoc dentist :title) "a title"]
                        [(assoc dentist :title "  ") "a title"]
                        [(dissoc dentist :start) "a start"]
                        [(assoc dentist :start "next tuesday") "a start relay understands"]
                        [(assoc dentist :end "2026-10-20 09:00") "an end after the start"]
                        [(assoc dentist :end "2026-10-19 09:00") "an end after the start"]
                        [(assoc dentist :start "1960-01-01") "a year from 1970"]
                        [(assoc dentist :start "10000-01-01T00:00:00Z") "a year to 9999"]
                        [(assoc dentist :title (apply str (repeat 201 "x"))) "a short title"]
                        [(assoc dentist :description (apply str (repeat 2001 "x"))) "a short description"]]]
      (is (#{:invalid} (:rejected (run w "clerk" :calendar/add args))) why))
    (testing "no end means an hour"
      (let [w (run w "clerk" :calendar/add (dissoc dentist :end))
            e (core/calendar-event (:state w) (created w))]
        (is (= 3600 (- (.getEpochSecond (:end e)) (.getEpochSecond (:start e)))))))
    (testing "an update must change something, and name an event that is there"
      (let [w (run w "clerk" :calendar/add dentist)
            id (created w)]
        (is (= :invalid (:rejected (run w "clerk" :calendar/update {:event id :title "Dentist"}))))
        (is (= :invalid (:rejected (run w "clerk" :calendar/update {:event id}))))
        (is (= :not-found (:rejected (run w "clerk" :calendar/update {:event 999 :title "x"}))))
        (is (= :not-found (:rejected (run w "clerk" :calendar/remove {:event 999}))))
        (testing "an update cannot leave the end before the start"
          (is (= :invalid (:rejected (run w "clerk" :calendar/update {:event id :end "2026-10-20 08:00"}))))
          (is (= :invalid (:rejected (run w "clerk" :calendar/update {:event id :end "2026-10-20 09:00"}))))
          (is (= :invalid (:rejected (run w "clerk" :calendar/update {:event id :start "2026-10-20 12:00"
                                                                      :end "2026-10-20 11:00"})))))
        (testing "a start at or after the end needs an end too"
          (is (= :invalid (:rejected (run w "clerk" :calendar/update {:event id :start "2026-10-20 11:00"}))))
          (is (nil? (:rejected (run w "clerk" :calendar/update {:event id :start "2026-10-20 11:00"
                                                                :end "2026-10-20 12:00"})))))))
    (testing "the calendar holds a limited number of events"
      (let [full (reduce (fn [w i] (run w "clerk" :calendar/add (assoc dentist :title (str "e" i))))
                         w (range core/calendar-limit))]
        (is (nil? (:rejected full)))
        (is (= :invalid (:rejected (run full "clerk" :calendar/add dentist))))
        (testing "a removed one still counts while the feed lists it, so churn cannot grow the feed"
          (is (= :invalid (:rejected (run (run full "clerk" :calendar/remove {:event (created full)})
                                          "clerk" :calendar/add dentist)))))
        (testing "once it has dropped off the feed, it makes room"
          (let [gone (-> full (run "clerk" :calendar/remove {:event (created full)})
                         (advance (* (inc ics/cancelled-days) 86400)))
                gone (assoc gone :state (:state gone))]
            ;; the others ended by then too, so they no longer count
            (is (nil? (:rejected (run gone "clerk" :calendar/add (assoc dentist :start "2026-12-20 09:00"
                                                                           :end "2026-12-20 10:00")))))))))))

(deftest adding-and-removing-in-a-loop-cannot-grow-the-feed
  (let [w (seed)
        w (loop [w w i 0]
            (if (>= i (+ core/calendar-limit 500))
              w
              (let [w (advance (run w "clerk" :calendar/add (assoc dentist :title (str "e" i))) 1)]
                (recur (if (:rejected w) w (run w "clerk" :calendar/remove {:event (created w)}))
                       (inc i)))))
        text (ics/render {:now (:now w) :events (core/calendar-feed (:state w))})]
    (is (= :invalid (:rejected w)) "the loop ran into the limit")
    (is (<= (count (ics-test/read-events text)) core/calendar-limit))))

(deftest events-that-ended-long-ago-leave-the-feed-and-the-count
  (let [past (assoc dentist :start "2020-01-01 09:00" :end "2020-01-01 10:00")
        w (reduce (fn [w i] (run w "clerk" :calendar/add (assoc past :title (str "old" i))))
                  (seed) (range (+ core/calendar-limit 100)))]
    (is (nil? (:rejected w)))
    (is (empty? (feed w)) "none of them is in the feed")
    (is (nil? (:rejected (run w "clerk" :calendar/add dentist))) "a new one is not blocked")
    (testing "one that ended recently is still in the feed, and drops off after ended-days"
      (let [w (run (seed) "clerk" :calendar/add dentist)
            over (at "2026-10-20T14:00:00Z")]
        (is (= 1 (count (feed (assoc w :now (.plusSeconds over (* (dec ics/ended-days) 86400)))))))
        (is (empty? (feed (assoc w :now (.plusSeconds over (* (inc ics/ended-days) 86400))))))))
    (testing "moving an old event back onto a full feed is refused like adding one"
      (let [w (run (seed) "clerk" :calendar/add past)
            old (created w)
            w (reduce (fn [w i] (run w "clerk" :calendar/add (assoc dentist :title (str "e" i))))
                      w (range core/calendar-limit))]
        (is (nil? (:rejected w)))
        (is (= :invalid (:rejected (run w "clerk" :calendar/update {:event old :start "2026-12-01 09:00"
                                                                    :end "2026-12-01 10:00"}))))))))

(deftest the-cancel-window-starts-when-the-event-was-removed
  (let [w (-> (seed) (run "clerk" :calendar/add dentist))
        id (created w)
        w (-> w (advance (* 5 86400)) (run "clerk" :calendar/remove {:event id}))
        removed (:now w)
        at-removal #(assoc w :now (.plusSeconds ^Instant removed %))]
    (is (nil? (:rejected w)))
    (let [[e & more] (feed (at-removal 60))]
      (is (empty? more))
      (is (= "CANCELLED" (:status e)))
      (is (= removed (:dtstamp e)) "stamped with the removal, not the creation"))
    (is (= 1 (count (feed (at-removal (dec (* ics/cancelled-days 86400)))))))
    (is (empty? (feed (at-removal (* ics/cancelled-days 86400)))))))

(deftest a-public-session-keeps-the-calendar-closed-even-after-config-drops-it
  ;; courier is public in ct/cfg. Its wake began public; then relay.edn stops
  ;; listing it as public. The wake is still public (I3, I9).
  (let [w (-> (seed)
              (run "operator" :role/add {:id "courier" :title "Courier" :instructions "Carry."} true)
              (run "operator" :wake/start {:role "courier" :note "Go."} true))
        wake (relay.test-support/calling-wake (:state w) "courier")
        later (update ct/cfg :grants dissoc "courier")
        call (fn [type args]
               (try (core/run-command (:state w) {:type type :actor "courier" :args args :session-wake (:id wake)}
                                      {:now (:now w) :config later})
                    nil
                    (catch clojure.lang.ExceptionInfo e (core/rejection e))))
        w (run w "clerk" :calendar/add dentist)
        id (created w)]
    (is (:public-session wake) "the wake began public")
    (is (not (core/public-role? later "courier")) "and the config no longer says so")
    (is (= :forbidden (call :calendar/add dentist)))
    (is (= :forbidden (call :calendar/update {:event id :title "x"})))
    (is (= :forbidden (call :calendar/remove {:event id})))
    (testing "the calendar tool is not there for it either"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"You have no tool called calendar"
                            (binding [tools/*wake* (:id wake)]
                              ((:read (get tools/builtin "calendar"))
                               {:state (:state w) :config later :now (:now w)} "courier" {})))))))

(deftest the-tools-add-update-and-remove
  (let [sys-run (fn [w name actor args]
                  (let [state (:state w)
                        tool (get tools/builtin name)
                        wake (relay.test-support/calling-wake state actor)
                        [state' events] (core/run-command state {:type (case name
                                                                         "calendar_add" :calendar/add
                                                                         "calendar_update" :calendar/update
                                                                         "calendar_remove" :calendar/remove)
                                                                 :actor actor :args args :session-wake (:id wake)}
                                                          {:now (:now w) :config ct/cfg})]
                    (assert tool)
                    (assoc w :state state' :last events)))
        read (fn [w args] (let [r ((:read (get tools/builtin "calendar"))
                                   {:state (:state w) :config ct/cfg :now (:now w)} "clerk" args)]
                            (if (map? r) (:content r) r)))
        w (-> (seed) (sys-run "calendar_add" "clerk" dentist))
        id (created w)]
    (testing "the tools are of class edit, for houses only, and the read tool reads"
      (is (= #{:edit} (set (map #(:class (get tools/builtin %)) ["calendar_add" "calendar_update" "calendar_remove"]))))
      (is (= :read (:class (get tools/builtin "calendar"))))
      (is (= (str "Event " id ", 2026-10-20 09:00 to 10:00\n  Dentist\n  at Elm Street\n  Bring the card.")
             (read w {}))))
    (testing "past events and removed ones show only with all"
      (let [w (-> w (advance (* 30 86400)))]
        (is (= "Your calendar has no events." (read w {})))
        (is (str/includes? (read w {:all true}) "Dentist")))
      (let [w (-> w (sys-run "calendar_remove" "clerk" {:event id}))]
        (is (= "Your calendar has no events." (read w {})))
        (is (str/includes? (read w {:all true}) (str "Event " id ", removed")))))
    (testing "what the read shows carries its labels"
      (let [labelled (:labels ((:read (get tools/builtin "calendar"))
                               {:state (:state w) :config ct/cfg :now (:now w)} "clerk" {}))]
        (is (some #{"private"} labelled))))))

(deftest labels-stay-with-the-version-that-carried-them
  (let [w (-> (seed) (run "clerk" :calendar/add dentist))
        id (created w)
        w (advance w 60)
        [state events] (core/run-command (:state w) {:type :calendar/update :actor "clerk" :labels ["contaminated"]
                                                     :args {:event id :title "Dentist, as a mail said"}
                                                     :session-wake (:id (relay.test-support/calling-wake (:state w) "clerk"))}
                                         {:now (:now w) :config ct/cfg})
        [v1 v2] (get-in state [:calendar id :versions])]
    (is (= 1 (count events)))
    (is (= [] (:labels v1)))
    (is (= ["contaminated"] (:labels v2)))
    (testing "removing keeps what the removed version said, labels too"
      (let [[state _] (core/run-command state {:type :calendar/remove :actor "clerk" :args {:event id}
                                               :session-wake (:id (relay.test-support/calling-wake state "clerk"))}
                                        {:now (.plusSeconds ^Instant (:now w) 60) :config ct/cfg})
            v3 (peek (get-in state [:calendar id :versions]))]
        (is (= "Dentist, as a mail said" (:title v3)))
        (is (= ["contaminated"] (:labels v3)))))))

(deftest a-new-version-keeps-the-labels-of-the-version-it-changes
  (let [w (advance (seed) 60)
        call (fn [w type labels args]
               (first (core/run-command (:state w) {:type type :actor "clerk" :labels labels :args args
                                                    :session-wake (:id (relay.test-support/calling-wake (:state w) "clerk"))}
                                        {:now (:now w) :config ct/cfg})))
        state (call w :calendar/add ["contaminated"] dentist)
        id (first (keys (:calendar state)))
        w (advance (assoc w :state state) 60)
        ;; a clean session changes only the location
        state' (call w :calendar/update [] {:event id :location "Oak Street"})
        v2 (peek (get-in state' [:calendar id :versions]))]
    (is (= "Oak Street" (:location v2)))
    (is (some #{"contaminated"} (:labels v2)))
    (is (some #{"contaminated"} (core/item-labels (core/calendar-event state' id))))))

(deftest an-old-log-has-no-calendar
  (is (= {} (:calendar core/empty-state)))
  (is (= [] (core/calendar-feed core/empty-state))))

;; --- the property: any history of commands gives a feed that reads back

(def gen-title (gen/fmap #(apply str %) (gen/vector (gen/elements ["Dentist" ", " ";" "\\" "\n" "é" "日本" "🎹" " " "x"]) 1 12)))
(def gen-when (gen/fmap (fn [[d h]] (format "2026-11-%02d %02d:00" (inc d) h)) (gen/tuple (gen/choose 0 27) (gen/choose 0 22))))

(def gen-op
  (gen/one-of
   [(gen/fmap (fn [[t s]] [:add {:title t :start s}]) (gen/tuple gen-title gen-when))
    (gen/fmap (fn [[i t]] [:update i {:title t}]) (gen/tuple (gen/choose 0 5) gen-title))
    (gen/fmap (fn [[i l]] [:update i {:location l}]) (gen/tuple (gen/choose 0 5) gen-title))
    (gen/fmap (fn [[i s]] [:update i {:start s}]) (gen/tuple (gen/choose 0 5) gen-when))
    (gen/fmap (fn [[i s e]] [:update i {:start s :end e}]) (gen/tuple (gen/choose 0 5) gen-when gen-when))
    (gen/fmap (fn [[i e]] [:update i {:end e}]) (gen/tuple (gen/choose 0 5) gen-when))
    (gen/fmap (fn [d] [:wait (* d 86400)]) (gen/choose 1 45))
    (gen/fmap (fn [i] [:remove i]) (gen/choose 0 5))]))

(defspec any-history-gives-a-feed-that-reads-back 150
  (prop/for-all [ops (gen/vector gen-op 1 14)]
    (let [step (fn [[w ids sequences] [op i args]]
                 (let [w (advance w (if (= op :wait) i 60))
                       id (when (and (number? i) (seq ids)) (nth ids (mod i (count ids))))
                       before (count (:events w))
                       w (case op
                           :wait w
                           :add (run w "clerk" :calendar/add i)
                           :update (if id (run w "clerk" :calendar/update (assoc args :event id)) w)
                           :remove (if id (run w "clerk" :calendar/remove {:event id}) w))
                       ids (if (and (= op :add) (nil? (:rejected w)) (> (count (:events w)) before))
                             (conj ids (created w)) ids)
                       now-seqs (into {} (map (juxt :uid :sequence) (feed w)))]
                   ;; No sequence ever falls, and a change raises it.
                   (assert (every? (fn [[uid n]] (>= n (get sequences uid 0))) now-seqs))
                   [w ids (merge sequences now-seqs)]))
          [w ids _] (reduce step [(seed) [] {}] (map (fn [[op a b]] (if (= op :add) [op a nil] [op a b])) ops))
          back (feed w)
          live (vec (ics/listed (:now w) (core/calendar-feed (:state w))))
          removals (into {} (for [e (:events w) :when (= "calendar/removed" (:type e))]
                              [(get-in e [:data :event]) (Instant/parse (str (:at e)))]))]
      (and (= (count back) (count live))
           (every? #(= (:removed-at %) (get removals (:id %))) (filter :removed-at (core/calendar-feed (:state w))))
           (every? #(nil? (get removals (:id %))) (remove :removed-at (core/calendar-feed (:state w))))
           (every? true?
                   (map (fn [b e] (and (= (ics/clean-text (:title e)) (:title b))
                                       (= (:sequence e) (:sequence b))
                                       (= (:start e) (:start b))
                                       (= (if (:removed-at e) "CANCELLED" "CONFIRMED") (:status b))))
                        back live))))))

(deftest removing-events-that-ended-long-ago-cannot-grow-the-feed
  (let [past (assoc dentist :start "2020-01-01 09:00" :end "2020-01-01 10:00")
        n (+ core/calendar-limit 500)
        w (reduce (fn [w i] (run w "clerk" :calendar/add (assoc past :title (str "old" i))))
                  (seed) (range n))
        ids (map :id (core/calendar-events (:state w) {:all true}))
        w (reduce (fn [w id] (run w "clerk" :calendar/remove {:event id})) w ids)]
    (is (= n (count ids)) "all of them were added")
    (is (nil? (:rejected w)) "and all removed")
    (is (<= (core/calendar-listed-count (:state w) (:now w)) core/calendar-limit))
    (is (<= (count (feed w)) core/calendar-limit))
    (is (empty? (feed w)) "they had left the feed already, so removing them lists nothing")))

(deftest an-event-shaped-by-outside-text-has-its-links-broken-in-the-feed
  ;; Codex R1: a mail could have the steward put a live link holding the
  ;; operator's data in the calendar she subscribes to.
  (let [w (advance (seed) 60)
        link "https://evil.example/collect?data=SECRET"
        add (fn [w labels title]
              (first (core/run-command (:state w) {:type :calendar/add :actor "clerk" :labels labels
                                                   :args (assoc dentist :title title :description (str "See " link))
                                                   :session-wake (:id (relay.test-support/calling-wake (:state w) "clerk"))}
                                       {:now (:now w) :config ct/cfg})))
        state (add w ["contaminated" "mail:dana@riverbend.test" "private"] "From a mail")
        state (add (assoc w :state state) ["private"] "From the operator")
        by-title (into {} (map (juxt :title identity)
                               (ics-test/read-events (ics/render {:now (:now w) :events (core/calendar-feed state)}))))]
    (is (= ["contaminated" "mail:dana@riverbend.test" "private"]
           (:labels (first (filter #(= "From a mail" (:title %)) (core/calendar-feed state)))))
        "the feed carries each event's labels")
    (is (= "See hxxps://evil[.]example/collect?data=SECRET" (:description (by-title "From a mail"))))
    (is (= (str "See " link) (:description (by-title "From the operator"))))))
