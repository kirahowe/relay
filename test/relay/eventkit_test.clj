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
    (is (= ["set" "R1" "--title" "Buy oat milk" "--due" "none" "--notes" "none" "--list" "Groceries"]
           (:tail (eventkit/plan-write "edit" lists snapshot [] {:id "R1" :title "Buy oat milk" :due "" :notes "" :list "Groceries"}))))
    (is (= ["set" "R1" "--due" "2026-11-01"] (:tail (eventkit/plan-write "edit" lists snapshot [] {:id "R1" :due "2026-11-01"}))))
    (is (= {:id "R1" :due ""} (:args (eventkit/plan-write "edit" lists snapshot [] {:id "R1" :due "none"})))))
  (testing "an edit that moves it somewhere not allowed is refused"
    (is (str/includes? (:error (eventkit/plan-write "edit" lists snapshot [] {:id "R1" :list "Shared with Dana"})) "not one relay may write to")))
  (testing "an edit that changes nothing is refused"
    (is (str/includes? (:error (eventkit/plan-write "edit" lists snapshot [] {:id "R1"})) "Say what to change")))
  (testing "notes of just none would clear them by accident"
    (is (str/includes? (:error (eventkit/plan-write "edit" lists snapshot [] {:id "R1" :notes "None"})) "clear")))
  (testing "there is no write called delete"
    (is (:error (eventkit/plan-write "delete" lists snapshot [] {:id "R1"})))))

;; --- undoing

(def before (eventkit/reminder (assoc raw-reminder :title "Buy milk" :due "2026-10-29")))
(def after (eventkit/reminder (assoc raw-reminder :title "Buy oat milk" :due "2026-11-01" :notes "Oatly")))

(defn- plan [change cur]
  (eventkit/revert-plan {:change change :before (when (not= "added" change) before) :after after} cur))

(deftest undoing-an-add-finishes-the-reminder-and-never-deletes-it
  (is (= {:op "done" :id "R1"} (plan "added" after)))
  (is (:noop (plan "added" (assoc after :completed true))))
  (is (str/includes? (:refuse (plan "added" nil)) "not in its list")))

(deftest undoing-a-completion-or-a-reopening
  (is (= {:op "reopen" :id "R1"} (plan "completed" (assoc after :completed true))))
  (is (:noop (plan "completed" after)))
  (is (= {:op "done" :id "R1"} (plan "reopened" after)))
  (is (:noop (plan "reopened" (assoc after :completed true)))))

(deftest undoing-an-edit-puts-back-only-what-changed-and-only-while-it-still-reads-as-relay-left-it
  (is (= {:op "edit" :id "R1" :set {:title "Buy milk" :due "2026-10-29" :notes nil}} (plan "changed" after)))
  (testing "the operator changed it since: relay leaves it alone and says to fix it by hand"
    (let [r (plan "changed" (assoc after :title "Buy oat milk and bread"))]
      (is (str/includes? (:refuse r) "has changed since relay edited it"))
      (is (str/includes? (:refuse r) "Fix it by hand"))
      (is (str/includes? (:refuse r) "Buy milk")))
    (is (:refuse (plan "changed" (assoc after :completed true))) "finishing it counts as a change")
    (is (:refuse (plan "changed" (assoc after :list "Groceries")))))
  (testing "a touch that changes nothing the edit set does not count"
    (is (:op (plan "changed" (assoc after :modified "2026-10-28T00:00:00Z")))))
  (testing "an edit that changed nothing has nothing to put back"
    (is (:noop (eventkit/revert-plan {:change "changed" :before after :after after} after)))))

(deftest a-revert-is-carried-out-with-the-binarys-commands
  (is (= ["done" "R1"] (eventkit/revert-tail {:op "done" :id "R1"})))
  (is (= ["reopen" "R1"] (eventkit/revert-tail {:op "reopen" :id "R1"})))
  (is (= ["set" "R1" "--title" "Buy milk" "--due" "none" "--notes" "none" "--list" "Personal"]
         (eventkit/revert-tail {:op "edit" :id "R1" :set {:title "Buy milk" :due "" :notes nil :list "Personal"}}))))

(def gen-reminder
  (gen/fmap (fn [[t n due done list]]
              (cond-> {:id "R1" :list list :title t :completed done}
                (not (str/blank? n)) (assoc :notes n)
                due (assoc :due due)))
            (gen/tuple gen-text gen-text (gen/elements [nil "2026-10-29" "2026-10-30T09:00"]) gen/boolean
                       (gen/elements ["Personal" "Groceries"]))))

(defspec an-edit-is-only-undone-from-the-state-it-left 300
  (prop/for-all [b gen-reminder a gen-reminder cur gen-reminder]
    (let [p (eventkit/revert-plan {:change "changed" :before b :after a} cur)]
      (cond
        ;; Not as relay left it: never touched.
        (not= (eventkit/fields cur) (eventkit/fields a)) (some? (:refuse p))
        ;; As it left it: the fields set bring it to what it was.
        (:op p) (let [applied (merge (select-keys cur [:title :notes :due :list]) (:set p))]
                  (every? (fn [k] (= (str (get applied k)) (str (get b k)))) [:title :notes :due :list]))
        :else (some? (:noop p))))))

(deftest a-snapshot-takes-a-written-reminder
  (is (= [after] (eventkit/snapshot-with [] after)))
  (is (= [after] (eventkit/snapshot-with [before] after)))
  (is (= [before (eventkit/reminder (assoc raw-reminder :id "R2"))]
         (eventkit/snapshot-with [before] (eventkit/reminder (assoc raw-reminder :id "R2"))))))
