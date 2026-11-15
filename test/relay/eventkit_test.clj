(ns relay.eventkit-test
  "The pure side of Apple Calendar and Reminders (relay.eventkit): what the
  binary prints becomes events and reminders, the lists a write may touch
  are checked before the binary is called, and the undo of a change is
  planned so that it never overwrites the operator's own edit."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.eventkit :as eventkit]
            [relay.ics-feed :as ics-feed])
  (:import (java.time Instant ZoneId)))

(def utc (ZoneId/of "UTC"))
(def now (Instant/parse "2026-10-28T12:00:00Z"))

;; --- what the binary printed

(deftest the-binarys-answers-are-read
  (is (= {:ok {:events []}} (eventkit/output 0 "{\"events\": []}")))
  (testing "a refusal from the binary is an error with its code"
    (let [r (eventkit/output 1 "{\"error\": \"nope\", \"code\": \"refused\"}")]
      (is (= "refused" (:code r)))
      (is (str/includes? (:error r) "nope"))))
  (testing "lost access says how to give it again"
    (let [r (eventkit/output 1 "{\"error\": \"Calendar access denied\", \"code\": \"no-access\"}")]
      (is (= "no-access" (:code r)))
      (is (str/includes? (:error r) "relay eventkit-access"))))
  (testing "anything else it did is an error that says so"
    (is (:error (eventkit/output 0 "")))
    (is (:error (eventkit/output 0 "[1,2]")))
    (is (:error (eventkit/output 0 "not json")))
    (is (str/includes? (:error (eventkit/output 3 "")) "exit code 3"))))

(deftest an-error-text-is-one-short-line
  (let [r (eventkit/output 1 (json/generate-string {:error (str "first line\n" (apply str (repeat 500 "x"))) :code "failed"}))]
    (is (not (str/includes? (:error r) "\n")))
    (is (< (count (:error r)) 300))))

;; --- events

(def raw-event
  {:key "abc#2026-10-29T14:00:00Z" :id "abc" :calendar "Home" :title "Interview"
   :start "2026-10-29T14:00:00Z" :end "2026-10-29T15:00:00Z" :all_day false
   :location "Room 2" :notes "Bring a CV" :url nil :status "confirmed" :modified "2026-10-20T10:00:00Z"})

(deftest an-event-becomes-the-feeds-event
  (let [e (eventkit/event raw-event)]
    (is (= "abc#2026-10-29T14:00:00Z" (ics-feed/event-key e)))
    (is (= {:instant (Instant/parse "2026-10-29T14:00:00Z")} (:start e)))
    (is (= "Interview" (:summary e)))
    (is (= "Bring a CV" (:description e)))
    (is (= "Home" (:calendar e)))
    (is (= "confirmed" (:status e))))
  (testing "an all-day event keeps dates, the end exclusive"
    (let [e (eventkit/event (assoc raw-event :start "2026-10-29" :end "2026-10-31" :all_day true))]
      (is (= {:date (java.time.LocalDate/parse "2026-10-29")} (:start e)))
      (is (str/includes? (ics-feed/event-text e utc) "Thu 2026-10-29 to Fri 2026-10-30, all day"))))
  (testing "a cancelled event says so, whichever way it is spelled"
    (is (= "cancelled" (:status (eventkit/event (assoc raw-event :status "Canceled")))))
    (is (nil? (:status (eventkit/event (assoc raw-event :status "none"))))))
  (testing "an event with no key or no readable start is left out"
    (is (nil? (eventkit/event (dissoc raw-event :key))))
    (is (nil? (eventkit/event (assoc raw-event :start "yesterday"))))
    (is (nil? (eventkit/event (assoc raw-event :start nil))))))

(deftest events-poll-like-a-feed
  (let [past (assoc raw-event :key "old" :start "2026-10-20T10:00:00Z" :end "2026-10-20T11:00:00Z")
        {:keys [items keys]} (eventkit/poll-events [raw-event past] utc now)]
    (is (= ["abc#2026-10-29T14:00:00Z"] (map :key items)) "one over for more than a day is not news")
    (is (= #{"abc#2026-10-29T14:00:00Z" "old"} (set keys)) "but it is still in the calendar, so not removed")
    (testing "the version changes when anything about the event does"
      (is (not= (:version (first items))
                (:version (first (:items (eventkit/poll-events [(assoc raw-event :location "Room 3")] utc now)))))))
    (testing "and not when only the calendar's modified time does"
      (is (= (:version (first items))
             (:version (first (:items (eventkit/poll-events [(assoc raw-event :modified "2026-10-27T00:00:00Z")] utc now)))))))))

(deftest the-calendar-window
  (let [{:keys [from to]} (eventkit/window now)]
    (is (= "2026-10-20T12:00:00Z" (str from)))
    (is (= "2026-12-27T12:00:00Z" (str to)))
    (is (= ["events" "--from" "2026-10-20T12:00:00Z" "--to" "2026-12-27T12:00:00Z" "--calendar" "Home" "--calendar" "Work"]
           (eventkit/events-argv from to ["Home" "Work"])))
    (is (= ["events" "--from" "2026-10-20T12:00:00Z" "--to" "2026-12-27T12:00:00Z"] (eventkit/events-argv from to nil))))
  (testing "a read at a time with a fraction of a second asks for whole seconds, which is all the binary reads"
    (let [{:keys [from to]} (eventkit/window (.plusNanos now 899512000))]
      (is (= ["events" "--from" "2026-10-20T12:00:00Z" "--to" "2026-12-27T12:00:00Z"] (eventkit/events-argv from to nil))))))

(def gen-text (gen/fmap #(str/trim (apply str %)) (gen/vector (gen/elements (seq "abc XYZ-é,.;:")) 1 30)))

(def gen-when
  (gen/one-of [(gen/fmap (fn [s] {:instant (Instant/ofEpochSecond (+ 1700000000 (* 60 s)))}) (gen/choose 0 100000))
               (gen/fmap (fn [d] {:date (.plusDays (java.time.LocalDate/parse "2026-01-01") d)}) (gen/choose 0 400))]))

(def gen-event
  (gen/fmap (fn [[uid summary start end loc desc]]
              (cond-> {:uid (if (str/blank? uid) "u" uid) :summary (if (str/blank? summary) "t" summary) :start start}
                end (assoc :end end)
                (not (str/blank? loc)) (assoc :location loc)
                (not (str/blank? desc)) (assoc :description desc)))
            (gen/tuple gen-text gen-text gen-when (gen/one-of [(gen/return nil) gen-when]) gen-text gen-text)))

(defspec an-event-survives-the-log-as-data 200
  (prop/for-all [e gen-event]
    (let [through-json (json/parse-string (json/generate-string (ics-feed/event->data e)) true)]
      (= e (ics-feed/data->event through-json)))))

(defspec the-version-ignores-modified-and-follows-everything-else 100
  (prop/for-all [title gen-text other gen-text]
    (let [e (assoc raw-event :title title)
          v (fn [x] (:version (first (:items (eventkit/poll-events [x] utc now)))))]
      (and (= (v e) (v (assoc e :modified (str other))))
           (or (= title other) (not= (v e) (v (assoc e :title other))))))))

;; --- reminders

(def raw-reminder
  {:id "R1" :list "Personal" :title "Buy milk" :notes nil :due "2026-10-29" :completed false
   :completed_at nil :modified "2026-10-27T10:00:00Z"})

(deftest a-reminder-is-read
  (is (= {:id "R1" :list "Personal" :title "Buy milk" :due "2026-10-29" :completed false
          :modified "2026-10-27T10:00:00Z"}
         (eventkit/reminder raw-reminder)))
  (is (= "2026-10-29T09:00" (:due (eventkit/reminder (assoc raw-reminder :due "2026-10-29T09:00")))))
  (is (true? (:completed (eventkit/reminder (assoc raw-reminder :completed true)))))
  (is (nil? (eventkit/reminder (dissoc raw-reminder :id))))
  (is (nil? (eventkit/reminder (dissoc raw-reminder :list)))))

(deftest reminders-poll-by-id
  (let [{:keys [items keys]} (eventkit/poll-reminders [raw-reminder (assoc raw-reminder :id "R2" :title "Call")])]
    (is (= ["R1" "R2"] (map :key items)))
    (is (= ["R1" "R2"] keys))
    (is (not= (:version (first items)) (:version (second items))))
    (testing "a touch that changes nothing is no news, a completion is"
      (let [v (fn [r] (:version (first (:items (eventkit/poll-reminders [r])))))]
        (is (= (v raw-reminder) (v (assoc raw-reminder :modified "2026-10-28T00:00:00Z"))))
        (is (not= (v raw-reminder) (v (assoc raw-reminder :completed true))))))))

(deftest reminders-read-as-text
  (let [rs (map eventkit/reminder [raw-reminder (assoc raw-reminder :id "R2" :title "Call" :completed true :notes "Ask\nabout rent")])
        t (eventkit/reminders-text ["Personal" "Work"] rs)]
    (is (str/includes? t "List Personal: 1 open, 1 done lately"))
    (is (str/includes? t "[open] Buy milk, due Thu 2026-10-29 (list Personal, id R1)"))
    (is (str/includes? t "[done] Call"))
    (is (str/includes? t "\n  Ask\n  about rent"))
    (is (str/includes? t "List Work: 0 open"))
    (is (< (.indexOf t "[open]") (.indexOf t "[done]")) "open ones come first")))

;; --- writes

(def lists ["Personal" "Groceries"])
(def snapshot [(eventkit/reminder raw-reminder)
               (eventkit/reminder (assoc raw-reminder :id "R9" :list "Shared with Dana"))])

(deftest an-add-is-checked-before-the-binary-is-called
  (let [plan (eventkit/plan-write "add" lists snapshot [] {:list "Personal" :title "  Buy   milk " :due "2026-10-30T09:00" :notes "2%"})]
    (is (= ["add" "--list" "Personal" "--title" "Buy milk" "--due" "2026-10-30T09:00" "--notes" "2%"] (:tail plan)))
    (is (= {:list "Personal" :title "Buy milk" :due "2026-10-30T09:00" :notes "2%"} (:args plan))))
  (testing "all the lists the source names go to the binary as --allow, before the command"
    (is (= ["--allow" "Personal" "--allow" "Groceries" "add" "--list" "Personal" "--title" "x"]
           (eventkit/write-argv lists ["add" "--list" "Personal" "--title" "x"]))))
  (testing "a list that is not the source's is refused, naming the ones that are"
    (let [r (eventkit/plan-write "add" lists snapshot [] {:list "Shared with Dana" :title "x"})]
      (is (str/includes? (:error r) "not one relay may write to"))
      (is (str/includes? (:error r) "\"Personal\", \"Groceries\""))))
  (testing "a list is matched exactly"
    (doseq [l ["personal" "Personal " "Personal2" "" nil]]
      (is (:error (eventkit/plan-write "add" lists snapshot [] {:list l :title "x"})) (pr-str l))))
  (testing "a title and a date have to be real"
    (is (= "A reminder needs a title." (:error (eventkit/plan-write "add" lists snapshot [] {:list "Personal" :title " "}))))
    (is (str/includes? (:error (eventkit/plan-write "add" lists snapshot [] {:list "Personal" :title "x" :due "tomorrow"})) "A due date is"))
    (is (str/includes? (:error (eventkit/plan-write "add" lists snapshot [] {:list "Personal" :title "x" :due "2026-02-30"})) "A due date is"))
    (is (:error (eventkit/plan-write "add" lists snapshot [] {:list "Personal" :title (apply str (repeat 501 "x"))})))))

(defspec a-list-outside-the-source-is-never-asked-of-the-binary 300
  (prop/for-all [allowed (gen/vector-distinct gen-text {:min-elements 1 :max-elements 4})
                 other gen-text]
    (let [plan (eventkit/plan-write "add" allowed [] [] {:list other :title "x"})]
      (if (some #{other} allowed)
        (and (nil? (:error plan)) (= other (nth (:tail plan) 2)))
        (and (:error plan) (nil? (:tail plan)))))))

(deftest text-written-into-a-reminder-is-cleaned-like-text-for-the-operator
  (let [zwsp "\u200b" bidi "\u202e"
        plan (eventkit/plan-write "add" lists snapshot [] {:list "Personal" :title (str "Pay" zwsp " rent" bidi) :notes (str "a" zwsp "b")})]
    (is (= "Pay rent" (:title (:args plan))))
    (is (= "ab" (:notes (:args plan)))))
  (testing "links are broken when the session holds outside text, not otherwise"
    (let [args {:list "Personal" :title "See example.org/pay" :notes "visit evil.example.net"}
          plain (:args (eventkit/plan-write "add" lists snapshot ["private"] args))
          outside (:args (eventkit/plan-write "add" lists snapshot ["contaminated" "private"] args))
          from-mail (:args (eventkit/plan-write "add" lists snapshot ["mail:eve@example.net"] args))]
      (is (= "See example.org/pay" (:title plain)))
      (is (= "See example[.]org/pay" (:title outside)))
      (is (= "visit evil[.]example[.]net" (:notes outside)))
      (is (= outside from-mail)))))

(deftest changing-a-reminder-needs-one-relay-knows-in-a-list-it-may-write
  (testing "done and reopen take the id the snapshot has"
    (is (= ["done" "R1"] (:tail (eventkit/plan-write "done" lists snapshot [] {:id "R1"}))))
    (is (= ["reopen" "R1"] (:tail (eventkit/plan-write "reopen" lists snapshot [] {:id "R1"})))))
  (testing "an id has to match exactly, and be one relay has read"
    (doseq [id ["R" "r1" "R1 " "" nil "R10"]]
      (is (:error (eventkit/plan-write "done" lists snapshot [] {:id id})) (pr-str id))))
  (testing "a reminder in a list the source does not name is not touched"
    (is (str/includes? (:error (eventkit/plan-write "done" lists snapshot [] {:id "R9"})) "not one relay may write to")))
  (testing "an edit sends only what changes"
    (is (= ["set" "R1" "--title" "Buy oat milk" "--clear-due" "--clear-notes" "--list" "Groceries"]
           (:tail (eventkit/plan-write "edit" lists snapshot [] {:id "R1" :title "Buy oat milk" :due "" :notes "" :list "Groceries"}))))
    (is (= ["set" "R1" "--due" "2026-11-01"] (:tail (eventkit/plan-write "edit" lists snapshot [] {:id "R1" :due "2026-11-01"}))))
    (testing "no value is magic: none is not a date"
      (is (str/includes? (:error (eventkit/plan-write "edit" lists snapshot [] {:id "R1" :due "none"})) "A due date is"))))
  (testing "an edit that moves it somewhere not allowed is refused"
    (is (str/includes? (:error (eventkit/plan-write "edit" lists snapshot [] {:id "R1" :list "Shared with Dana"})) "not one relay may write to")))
  (testing "an edit that changes nothing is refused"
    (is (str/includes? (:error (eventkit/plan-write "edit" lists snapshot [] {:id "R1"})) "Say what to change")))
  (testing "notes that read none are notes, written as such, and clearing is its own flag"
    (is (= ["set" "R1" "--notes" "None"] (:tail (eventkit/plan-write "edit" lists snapshot [] {:id "R1" :notes "None"}))))
    (is (= ["set" "R1" "--clear-notes"] (:tail (eventkit/plan-write "edit" lists snapshot [] {:id "R1" :notes ""})))))
  (testing "there is no write called delete"
    (is (:error (eventkit/plan-write "delete" lists snapshot [] {:id "R1"})))))

;; --- undoing, field by field

(def before (eventkit/reminder (assoc raw-reminder :title "Buy milk" :due "2026-10-29")))
(def after (eventkit/reminder (assoc raw-reminder :title "Buy oat milk" :due "2026-11-01" :notes "Oatly")))
(def base (eventkit/reminder (assoc raw-reminder :title "Buy milk" :due "2026-10-29")))
(def opts {:outside {} :lists ["Personal" "Groceries"]})

(defn- plan
  ([before after cur] (plan before after cur opts))
  ([before after cur o] (eventkit/revert-plan {:before before :after after} cur o)))

(deftest undoing-an-add-completes-the-reminder-and-never-deletes-it
  (let [added (assoc base :completed false)]
    (is (= [{:op "done" :id "R1"}] (:steps (plan nil added added))))
    (is (:noop (plan nil added (assoc added :completed true))) "already done")
    (testing "only completion is undone: the operator's rewrite of the text does not matter"
      (is (= [{:op "done" :id "R1"}] (:steps (plan nil added (assoc added :title "Rewritten"))))))
    (is (str/includes? (:refuse (plan nil added nil)) "not in its list"))))

(deftest an-undo-touches-only-the-fields-the-change-set
  (let [b (assoc base :completed false)
        retitled (assoc b :title "Buy oat milk")]
    (testing "the title the change set is put back; the notes the operator added since stay"
      (let [cur (assoc retitled :notes "Oatly please")
            p (plan b retitled cur)]
        (is (= [{:op "edit" :id "R1" :set {:title "Buy milk"}}] (:steps p)))
        (is (empty? (:left p)))))
    (testing "a completion undone leaves a title the operator rewrote alone"
      (let [done (assoc b :completed true)
            p (plan b done (assoc done :title "New title"))]
        (is (= [{:op "reopen" :id "R1"}] (:steps p)))))))

(deftest a-field-the-operator-changed-since-is-left-alone-and-said
  (let [b base
        a (assoc b :title "Buy oat milk" :due "2026-11-01")]
    (testing "some fields match: those are undone, the others are named"
      (let [p (plan b a (assoc a :due "2026-12-25"))]
        (is (= [{:op "edit" :id "R1" :set {:title "Buy milk"}}] (:steps p)))
        (is (= ["Left alone because you changed it since: due date."] (:left p)))))
    (testing "none match: refused, naming what changed"
      (let [p (plan b a (assoc a :title "Mine" :due "2026-12-25"))]
        (is (str/includes? (:refuse p) "title and due date"))
        (is (str/includes? (:refuse p) "Fix it by hand"))))
    (testing "every set field already holds its before value: a no-op"
      (is (:noop (plan b a b))))
    (testing "a change that set nothing"
      (is (:noop (plan b b b))))))

(deftest an-undo-never-writes-outside-text
  (let [eve {"renew passport" {:labels ["contaminated" "mail:eve@example.net"] :sources ["mail:eve@example.net"]}}
        o {:outside eve :lists ["Personal"]}
        b (assoc base :title "Renew passport" :completed false)
        a (assoc b :title "Pay the invoice")]
    (let [p (plan b a a o)]
      (is (str/includes? (:refuse p) "came from a mail from eve@example[.]net")
          "refused: it is the only field, and it may not be written"))
    (testing "the other fields are still undone, and the outside one is named"
      (let [a2 (assoc a :completed true)
            p (plan b a2 a2 o)]
        (is (= [{:op "reopen" :id "R1"}] (:steps p)))
        (is (str/includes? (first (:left p)) "so relay won't put it back"))))))

(deftest a-list-relay-may-not-write-to-is-not-restored
  (let [b (assoc base :list "Elsewhere") a (assoc b :list "Personal")
        p (plan b a a)]
    (is (:refuse p))))

(def gen-text (gen/elements ["a" "b" "c" "d"]))

(def gen-rem
  (gen/fmap (fn [[t n due done list]]
              {:id "R1" :title t :completed done :list list
               :notes n :due due})
            (gen/tuple gen-text (gen/elements [nil "x" "y"]) (gen/elements [nil "2026-10-29" "2026-10-30"]) gen/boolean
                       (gen/elements ["Personal" "Groceries"]))))

;; Against an independent model of the two rules.
(defspec an-undo-is-field-by-field-compare-and-swap-and-never-writes-outside-text 1000
  (prop/for-all [b gen-rem a gen-rem cur gen-rem
                 out (gen/set gen-text)
                 add? (gen/frequency [[1 gen/boolean] [3 (gen/return false)]])]
    (let [before (when-not add? b)
          outside (into {} (for [t out] [t {:labels ["contaminated"] :sources []}]))
          p (eventkit/revert-plan {:before before :after a} cur {:outside outside :lists ["Personal" "Groceries"]})
          norm (fn [k v] (if (= :completed k) (boolean v) (str v)))
          ;; the model: which fields the change set, what they were, what they became
          set-fields (if add?
                       {:completed [true (boolean (:completed a))]}
                       (into {} (for [k [:list :title :notes :due :completed]
                                      :when (not= (norm k (get b k)) (norm k (get a k)))]
                                  [k [(norm k (get b k)) (norm k (get a k))]])))
          writable? (fn [k [bv av]]
                      (and (= (norm k (get cur k)) av)
                           (not= (norm k (get cur k)) bv)
                           (not (and (#{:title :notes} k) (contains? out bv)))))
          expected (set (for [[k v] set-fields :when (writable? k v)] k))
          written (into {} (mapcat (fn [{:keys [op set]}]
                                     (case op
                                       "edit" (for [[k v] set] [k (norm k v)])
                                       "done" [[:completed true]]
                                       "reopen" [[:completed false]]))
                                   (:steps p)))]
      (and
       ;; (a) never a field the change did not set; (b) never one whose current value differs from its after
       (every? (fn [k] (and (contains? set-fields k) (= (norm k (get cur k)) (second (get set-fields k))))) (keys written))
       ;; (c) never outside text
       (every? (fn [[k v]] (not (and (#{:title :notes} k) (contains? out v)))) written)
       ;; (d) what it writes is the change's before value
       (every? (fn [[k v]] (= v (first (get set-fields k)))) written)
       ;; and it writes exactly the fields the model says it may
       (= expected (set (keys written)))))))

;; A whole history: relay's changes, the operator's edits and moves, and undos in any
;; order, checked against what each undo may touch.
(def gen-history-step
  (gen/one-of
   [(gen/tuple (gen/return :relay) (gen/vector (gen/tuple (gen/elements [:title :notes :due :list :completed]) gen-text) 1 3))
    (gen/tuple (gen/return :operator) (gen/elements [:title :notes :due :list :completed]) gen-text)
    (gen/tuple (gen/return :undo) gen/nat)]))

(defn- set-field [r k v]
  (assoc r k (case k
               :completed (even? (hash v))
               :list (if (even? (hash v)) "Personal" "Groceries")
               :due (when (even? (hash v)) "2026-10-29")
               :notes (when (even? (hash v)) (str v))
               v)))

(defn- norm [k v] (if (= :completed k) (boolean v) (str v)))

(defn- apply-steps
  "The reminder after the binary has run an undo plan's steps."
  [cur steps]
  (reduce (fn [r {:keys [op set]}]
            (case op
              "edit" (reduce (fn [r [k v]] (assoc r k (if (= :completed k) v (when (seq (str v)) v)))) r set)
              "done" (assoc r :completed true)
              "reopen" (assoc r :completed false)))
          cur steps))

(defn- undo-is-sound?
  "Whether an undo of change `ch` from `cur` to `new` obeyed the rules: it
  changed only fields the change set (a) whose value was the change's after
  value (b), wrote no outside text (c), and wrote the change's before value (d)."
  [ch cur new out]
  (let [uf (eventkit/undo-fields ch)
        changed (filter #(not= (norm % (get cur %)) (norm % (get new %))) [:list :title :notes :due :completed])]
    (every? (fn [k]
              (and (contains? uf k)
                   (= (norm k (get cur k)) (norm k (get-in uf [k :after])))
                   (not (and (#{:title :notes} k) (contains? out (str (get new k)))))
                   (= (norm k (get new k)) (norm k (get-in uf [k :before])))))
            changed)))

(defspec no-undo-ever-touches-more-than-the-fields-its-change-set-and-still-holds 400
  (prop/for-all [out (gen/set gen-text) steps (gen/vector gen-history-step 1 30)]
    (let [outside (into {} (for [t out] [t {:labels ["contaminated"] :sources []}]))
          o {:outside outside :lists ["Personal" "Groceries"]}
          step (fn [{:keys [cur changes] :as st} [kind a b]]
                 (case kind
                   :relay (let [new (reduce (fn [r [k v]] (set-field r k v)) cur a)]
                            (assoc st :cur new :changes (conj changes {:before cur :after new})))
                   :operator (assoc st :cur (set-field cur a b))
                   :undo (if (empty? changes)
                           st
                           (let [ch (nth changes (mod a (count changes)))
                                 new (apply-steps cur (:steps (eventkit/revert-plan ch cur o)))]
                             (-> st (assoc :cur new) (update :ok? #(and % (undo-is-sound? ch cur new out))))))))]
      (:ok? (reduce step {:cur {:id "R1" :list "Personal" :title "a" :completed false} :changes [] :ok? true} steps)))))

;; What a write did is what reads show, and only the fields the call asked for.
(def gen-edit-args
  (gen/let [fields (gen/vector (gen/tuple (gen/elements [:title :notes :due :list]) gen-text) 1 3)]
    (into {} (for [[k v] fields]
               [k (case k :list (if (even? (hash v)) "Personal" "Groceries") :due (if (even? (hash v)) "2026-10-29" "") :notes (if (even? (hash v)) v "") v)]))))

(defspec a-recorded-change-holds-only-requested-fields-with-values-a-read-confirmed 600
  (prop/for-all [pre gen-rem
                 op (gen/elements ["edit" "done" "reopen"])
                 args gen-edit-args
                 saved? gen/boolean
                 operator-edit (gen/tuple (gen/elements [:title :notes :due :list :completed]) gen-text)
                 operator-during-outage? gen/boolean
                 gone? (gen/frequency [[1 (gen/return true)] [9 (gen/return false)]])]
    (let [pre (assoc pre :id "R1")
          requested (eventkit/requested-fields op (if (= "edit" op) args {}))
          norm (fn [k v] (if (= :completed k) (boolean v) (str v)))
          applied (if saved? (reduce (fn [r [k v]] (if (and (#{:notes :due} k) (str/blank? (str v))) (dissoc r k) (assoc r k v))) pre requested) pre)
          ;; the operator edits something during the outage
          post (if operator-during-outage?
                 (let [[k v] operator-edit] (assoc applied k (if (= :completed k) (even? (hash v)) v)))
                 applied)
          post (when-not gone? post)
          o (eventkit/attribute-fields pre requested post)
          ok-fields (when post (filter (fn [[k v]] (= (norm k (get post k)) (norm k v))) requested))]
      (case (:status o)
        :gone (nil? post)
        :failed (and post (empty? ok-fields))
        :changed (and (seq ok-fields)
                      (= (set (:confirmed o)) (set (map first ok-fields)))
                      ;; never a field the call did not ask for, never a value the read did not hold
                      (every? (fn [k]
                                (if (contains? (set (:confirmed o)) k)
                                  (= (norm k (get (:after o) k)) (norm k (get requested k)))
                                  (= (norm k (get (:after o) k)) (norm k (get pre k)))))
                              [:list :title :notes :due :completed])
                      (= pre (:before o)))))))

(deftest a-successful-call-records-only-what-it-asked-for-from-the-binarys-images
  (let [pre (eventkit/reminder (assoc raw-reminder :title "Buy milk" :notes "old" :due "2026-10-29"))
        ;; the binary's after-image also differs in notes (say she typed as it saved)
        post (assoc pre :title "Buy oat milk" :notes "hers")
        o (eventkit/attribute {:op "edit" :args {:id "R1" :title "Buy oat milk"}} pre post)]
    (is (= :changed (:status o)))
    (is (= "Buy oat milk" (get-in o [:after :title])))
    (is (= "old" (get-in o [:after :notes])) "the notes she changed are not part of the record")
    (is (= pre (:before o)))
  (testing "an add is by the reported id, and an answer with none is not a change"
    (is (= "NEW" (get-in (eventkit/attribute {:op "add" :args {:list "Personal" :title "x"}} nil
                                             {:id "NEW" :list "Personal" :title "x" :completed false})
                         [:after :id])))
    (is (= :failed (:status (eventkit/attribute {:op "add" :args {:list "Personal" :title "x"}} nil nil))))
    (testing "and the requested values must be in the answer"
      (is (= :failed (:status (eventkit/attribute {:op "done" :args {:id "R1"}} pre pre))))))))

(deftest a-snapshot-takes-a-written-reminder
  (is (= [after] (eventkit/snapshot-with [] after)))
  (is (= [after] (eventkit/snapshot-with [before] after)))
  (is (= [before (eventkit/reminder (assoc raw-reminder :id "R2"))]
         (eventkit/snapshot-with [before] (eventkit/reminder (assoc raw-reminder :id "R2"))))))


;; --- where reminder text came from

(def eve {:labels ["contaminated" "mail:eve@example.net"] :sources ["mail:eve@example.net"]})

(defn- outside-labels? [{:keys [labels]}] (boolean (some #(not= "private" %) labels)))

(defn- note [m session args] (eventkit/note-outside m (:labels session) (:sources session) args))
(def clean {:labels ["private"] :sources []})

(deftest every-text-an-outside-session-asked-to-write-is-remembered-and-none-is-forgotten
  (let [m (-> {}
              (note eve {:list "Personal" :title "  Wire the deposit " :notes "Account 1234"})
              (note clean {:title "Call the bank"})
              (note eve {:id "R1" :due "2027-01-01"}))]
    (is (= #{"wire the deposit" "account 1234"} (set (keys m))) "folded text only; a clean write adds none")
    (is (= {:labels ["contaminated" "mail:eve@example.net"] :sources ["mail:eve@example.net"]} (get m "wire the deposit")))
    (testing "the labels of two outside writes of one text are joined"
      (let [m2 (note m {:labels ["mail:dana@example.org"] :sources ["mail:dana@example.org"]} {:title "Wire the deposit"})]
        (is (= ["contaminated" "mail:dana@example.org" "mail:eve@example.net"] (:labels (get m2 "wire the deposit"))))))
    (testing "text stored a little differently from what relay sent still matches"
      (let [m3 (note {} eve {:title "Caf\u00e9 at noon" :notes "Line one\nLine two"})]
        (doseq [shown [{:title "Cafe\u0301 at noon"} {:title "CAF\u00c9  AT NOON"} {:title "caf\u00e9 at noon" :notes "Line one\r\nLine two"}]]
          (is (outside-labels? (eventkit/shown-provenance m3 [shown])) (pr-str shown)))))
    (testing "shown text is outside exactly when it is in the set"
      (is (outside-labels? (eventkit/shown-provenance m [{:title "Wire the deposit" :notes nil}])))
      (is (outside-labels? (eventkit/shown-provenance m [{:title "Mine" :notes "Account 1234 "}])))
      (is (not (outside-labels? (eventkit/shown-provenance m [{:title "Call the bank" :notes nil}]))))
      (is (= {:labels ["private"] :sources []} (eventkit/shown-provenance m []))))))

(def gen-text (gen/elements ["a" "b" "c" "d" "e"]))

(def gen-step
  (gen/one-of
   [(gen/tuple (gen/return :write) gen/boolean (gen/elements [:add :title :notes :done :reopen]) gen-text gen/nat (gen/elements ["rem" "work"]))
    (gen/tuple (gen/return :operator) (gen/elements [:title :notes]) gen-text gen/nat)
    (gen/tuple (gen/return :move) gen/nat (gen/elements ["Personal" "Work"]))
    (gen/tuple (gen/return :revert) gen/nat)
    (gen/tuple (gen/return :poll) (gen/elements ["rem" "work"]))]))

(defspec shown-text-is-outside-iff-an-outside-session-ever-asked-to-write-it 300
  (prop/for-all [steps (gen/vector gen-step 0 40)]
    ;; A small independent model: the lists as the operator and the binary leave
    ;; them, each source's last snapshot, and the set of outside texts, kept as a
    ;; plain set. The state under test is the fold of reminders/changing.
    (let [lists {"rem" "Personal" "work" "Work"}
          final
          (reduce
           (fn [{:keys [list snaps outside state] :as m} step]
             (case (first step)
               :write
               (let [[_ out? kind text n src] step
                     ids (vec (sort (keys list)))
                     id (when (seq ids) (nth ids (mod n (count ids))))
                     add? (or (= kind :add) (nil? id))
                     args (cond add? {:list (lists src) :title text :notes text}
                                (= :title kind) {:id id :title text}
                                (= :notes kind) {:id id :notes text}
                                :else {:id id})
                     session (if out? eve clean)
                     state' (note state session args)
                     outside' (if out? (into outside (filter #(some? %) (if add? [text text] [(:title args) (:notes args)]))) outside)
                     ;; The binary saves, or relay stops, or it fails after saving: the list holds
                     ;; the text either way, and nothing is ever removed from the set.
                     list' (cond add? (assoc list (str "R" (inc (count list))) {:title text :notes text :list (lists src)})
                                 (= :title kind) (assoc-in list [id :title] text)
                                 (= :notes kind) (assoc-in list [id :notes] text)
                                 :else list)]
                 (assoc m :list list' :state state' :outside outside'))
               :operator
               (let [[_ k text n] step ids (vec (sort (keys list)))]
                 (if (seq ids) (assoc-in m [:list (nth ids (mod n (count ids))) k] text) m))
               :move
               (let [[_ n to] step ids (vec (sort (keys list)))]
                 (if (seq ids) (assoc-in m [:list (nth ids (mod n (count ids))) :list] to) m))
               :revert  ; an undo writes old text back; it asks as the operator, adding nothing
               (let [[_ n] step ids (vec (sort (keys list)))]
                 (if (seq ids) (assoc-in m [:list (nth ids (mod n (count ids))) :title] "a") m))
               :poll
               (let [[_ src] step]
                 (assoc-in m [:snaps src] (vec (for [[id r] list :when (= (lists src) (:list r))] (assoc r :id id)))))))
           {:list {} :snaps {} :outside #{} :state {}}
           steps)]
      (every? (fn [[_ snap]]
                (every? (fn [r]
                          (every? (fn [k]
                                    (let [t (get r k)
                                          shown (eventkit/shown-provenance (:state final) [(select-keys r [k])])]
                                      (= (outside-labels? shown)
                                         (boolean (and (some? t) (contains? (:outside final) t))))))
                                  [:title :notes]))
                        snap))
              (:snaps final)))))
