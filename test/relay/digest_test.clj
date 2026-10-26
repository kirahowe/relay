(ns relay.digest-test
  "The daily note's pure parts: when it is due, which changes it flags,
  what it says of each, which suggestions it leaves out, and what a reply
  asks for."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.digest :as digest])
  (:import (java.time Instant LocalDate LocalTime ZoneId ZoneOffset)))

(defn- quick [n property]
  (let [result (tc/quick-check n property)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

(def toronto (ZoneId/of "America/Toronto"))
(def seven-thirty (LocalTime/of 7 30))
(defn- at [s] (.toInstant (.atZone (java.time.LocalDateTime/parse s) toronto)))
(defn- day [s] (LocalDate/parse s))

;; --- when

(deftest a-note-is-due-once-a-day-from-its-time
  (is (nil? (digest/due-date seven-thirty toronto (at "2026-10-17T07:29") nil)))
  (is (= (day "2026-10-17") (digest/due-date seven-thirty toronto (at "2026-10-17T07:30") nil)))
  (is (= (day "2026-10-17") (digest/due-date seven-thirty toronto (at "2026-10-17T23:59") (day "2026-10-16"))))
  (is (nil? (digest/due-date seven-thirty toronto (at "2026-10-17T09:00") (day "2026-10-17"))))
  (is (= (day "2026-10-17") (digest/due-date seven-thirty toronto (at "2026-10-17T15:10") (day "2026-10-15")))
      "a missed day is not made up: today's note says it")
  (is (nil? (digest/due-date nil toronto (at "2026-10-17T09:00") nil)) "no :daily-note, no note")
  (testing "a time a clock change skips moves forward by the gap"
    (let [t (LocalTime/of 2 30)]
      (is (= (Instant/parse "2026-03-08T07:30:00Z") (digest/slot (day "2026-03-08") t toronto))))))

(def ^:private tick-times
  ;; Ticks over three days, some minutes apart, some hours.
  (gen/fmap (fn [gaps] (reductions + 0 gaps)) (gen/vector (gen/elements [1 7 60 180 600]) 1 40)))

(deftest notes-are-never-repeated-and-never-skipped-on-a-day-with-a-tick-past-their-time
  (quick 200
         (prop/for-all [minutes tick-times]
           (let [start (at "2026-10-16T00:00")
                 ticks (map #(.plusSeconds ^Instant start (* 60 %)) minutes)
                 sent (reduce (fn [sent t]
                                (if-let [d (digest/due-date seven-thirty toronto t (last sent))]
                                  (conj sent d) sent))
                              [] ticks)
                 local #(.toLocalDate (.atZone ^Instant % toronto))]
             (and (= sent (distinct sent))
                  (every? (fn [d] (some #(and (= d (local %)) (not (.isBefore ^Instant % (digest/slot d seven-thirty toronto)))) ticks))
                          sent)
                  (every? (fn [d] (some #{d} sent))
                          (distinct (for [t ticks :when (not (.isBefore ^Instant t (digest/slot (local t) seven-thirty toronto)))]
                                      (local t)))))))))

(deftest the-settings-are-checked
  (let [problem #(digest/settings-problem % #{"scout"})]
    (is (nil? (problem nil)))
    (is (nil? (problem {:at "07:30" :role "steward"})))
    (is (problem {:at "7:30" :role "steward"}))
    (is (problem {:at "07:30"}))
    (is (problem {:at "07:30" :role "steward" :tools ["cal"]}))
    (is (str/includes? (problem {:at "07:30" :role "scout"}) "public"))))

;; --- what counts as from outside

(deftest outside-means-contaminated-or-a-named-source
  (is (digest/outside? ["contaminated"]))
  (is (digest/outside? ["mail:eve@example.net"]))
  (is (digest/outside? ["github:a/b" "private"]))
  (is (not (digest/outside? ["private"])))
  (is (not (digest/outside? []))))

(deftest the-source-is-named-in-plain-words
  (is (= "a mail that says it is from eve@example[.]net"
         (digest/source-phrase ["contaminated" "mail:eve@example.net"])))
  (is (= "GitHub, owner/repo" (digest/source-phrase ["contaminated" "github:owner/repo" "private"])))
  (is (= "the calendar work" (digest/source-phrase ["ics:work"])))
  (is (= "a mail that says it is from you, not verified" (digest/source-phrase ["mail:unverified-operator"])))
  (is (= "something from outside" (digest/source-phrase ["contaminated"])) "as from the web")
  (is (= "GitHub, a/b and the calendar c" (digest/source-phrase ["ics:c" "github:a/b"])))
  (is (str/ends-with? (digest/source-phrase ["ics:c" "github:a/b" "mail:x@y.org" "ics:d"]) "and 2 more"))
  (testing "no label's name is shown"
    (is (not-any? #(str/includes? (digest/source-phrase ["contaminated" %]) "contaminated")
                  ["mail:x@y.org" "github:a/b"]))))

(deftest line-deltas-count-what-was-added-and-removed
  (is (= {:added 2 :removed 0} (digest/line-delta nil "a\nb")))
  (is (= {:added 1 :removed 1} (digest/line-delta "a\nb" "a\nc")))
  (is (= {:added 0 :removed 0} (digest/line-delta "a" "a")))
  (quick 200
         (prop/for-all [before (gen/vector (gen/elements ["a" "b" "c" ""]) 0 6)
                        after (gen/vector (gen/elements ["a" "b" "c" ""]) 0 6)]
           (let [b (str/join "\n" before) a (str/join "\n" after)
                 lines #(if (str/blank? %) 0 (count (str/split-lines %)))
                 {:keys [added removed]} (digest/line-delta b a)]
             (= (- added removed) (- (lines a) (lines b)))))))

(deftest a-change-is-one-plain-line
  (is (= "New how-to \"grants\" (3 lines added)"
         (digest/change-line {:what "skill" :name "grants" :change "added" :delta {:added 3 :removed 0}})))
  (is (= "Changed the routine \"morning\" (1 line added, 2 removed), now daily 07:00"
         (digest/change-line {:what "routine" :name "morning" :change "changed" :delta {:added 1 :removed 2}
                              :schedule "daily 07:00"})))
  (is (= "Removed the note \"vendors\"" (digest/change-line {:what "note" :name "vendors" :change "removed"
                                                            :delta {:added 0 :removed 4}})))
  (is (= "Changed the role \"clerk\"" (digest/change-line {:what "role" :name "clerk" :change "changed"
                                                            :delta {:added 0 :removed 0}})))
  (testing "a name chosen in a session that read outside text is defanged"
    (is (= "New how-to \"evil[.]example/pin\""
           (digest/change-line {:what "skill" :name "evil.example/pin" :change "added" :labels ["contaminated"]})))
    (is (= "New how-to \"notes.v2\""
           (digest/change-line {:what "skill" :name "notes.v2" :change "added" :labels ["private"]})))))

;; --- the note

(def ^:private label-sets
  [[] ["private"] ["contaminated"] ["contaminated" "mail:eve@example.net"] ["github:a/b"] ["private" "ics:cal"]])

(def ^:private gen-change
  (gen/let [what (gen/elements ["skill" "routine" "note" "role"])
            change (gen/elements ["added" "changed" "removed"])
            actor (gen/elements ["operator" "dispatcher" "steward" "clerk"])
            labels (gen/elements label-sets)
            name (gen/elements ["grants" "morning" "vendors" "clerk"])]
    {:what what :change change :actor actor :labels labels :name name :owner "steward"
     :delta {:added 1 :removed 0} :at "2026-10-26T10:00:00Z"}))

(def ^:private gen-item
  (gen/let [text (gen/elements ["Interview with Maple Co, Thu 29 Oct 10:00" "Dentist, Fri 10:00"
                                "Arts grant, due 1 Nov" "revert 1" "1. Changed the how-to \"x\""])
            offer (gen/elements [nil "Want prep notes?" "Shall I book it?"])
            key (gen/elements [nil "maple" "dentist" "arts"])]
    (cond-> {:text text} offer (assoc :offer offer) key (assoc :key key))))

(def ^:private gen-written
  (gen/let [ps (gen/vector (gen/tuple (gen/elements digest/parts) (gen/vector gen-item 0 4)
                                      (gen/elements label-sets))
                           0 4)]
    (into {} (for [[p items labels] ps] [p {:items items :labels labels}]))))

(defn- oracle-flagged
  "The changes the note must flag, worked out apart from relay.digest: a
  role's, since the last note, with a label saying it read outside text."
  [changes since]
  (filter #(and (> (:seq %) since)
                (not (#{"operator" "dispatcher"} (:actor %)))
                (some (fn [l] (or (= "contaminated" l) (str/includes? l ":"))) (:labels %)))
          changes))

(deftest the-note-flags-every-outside-change-and-the-writer-cannot-remove-one
  ;; I-flag: whatever a role wrote for its parts, the note lists every
  ;; change shaped by outside text since the last note, each with a way
  ;; to undo it, and nothing else as a change.
  (quick 300
         (prop/for-all [changes (gen/fmap #(vec (map-indexed (fn [i c] (assoc c :seq (inc i))) %))
                                          (gen/vector gen-change 0 12))
                        since (gen/choose 0 6)
                        written gen-written
                        dismissed (gen/set (gen/elements ["ahead/maple" "grants/arts" "ahead/dentist-fri-10-00"]))]
           (let [note (digest/compose {:date (LocalDate/parse "2026-10-26") :changes changes :since since
                                       :written written :dismissed dismissed})
                 flagged (oracle-flagged changes since)
                 want (map :seq flagged)
                 ;; Adding or removing a role cannot be undone, so it
                 ;; is listed with the reason and no number.
                 undoable (map :seq (remove #(and (= "role" (:what %)) (#{"added" "removed"} (:change %))) flagged))
                 reverts (filter #(= "revert" (:op %)) (:numbered note))
                 texts (mapcat :items (:sections note))]
             (and (= want (:flagged note))
                  (= undoable (map :change reverts))
                  ;; Each is in the text, with its number and only that.
                  (every? (fn [{:keys [n]}]
                            (some #(and (str/starts-with? (:text %) (str n ". "))
                                        (str/includes? (:text %) (str "Reply \"revert " n "\" to undo it."))
                                        (= {:op "revert" :n n} (:action %)))
                                  texts))
                          reverts)
                  (not-any? #(re-find #"relay revert" (:text %)) texts)
                  ;; Every flagged change is in the text, numbered or not.
                  (= (count want) (count (filter #(re-find #", shaped by " (:text %)) texts)))
                  ;; The flagged section is first.
                  (or (empty? want) (= "Changes to check" (:heading (first (:sections note)))))
                  ;; Numbers run 1, 2, 3 ... in order.
                  (= (range 1 (inc (count (:numbered note)))) (map :n (:numbered note)))
                  ;; Nothing to say, nothing sent.
                  (= (nil? (:sections note)) (and (empty? want) (empty? (mapcat :items (:sections note)))))
                  ;; No label's name reaches the text.
                  (not-any? #(re-find #"contaminated|private|mail:|github:|ics:" (:text %)) texts))))))

(deftest a-dismissed-suggestion-is-never-in-the-note
  (quick 300
         (prop/for-all [written gen-written
                        dismissed (gen/set (gen/elements ["ahead/maple" "grants/arts" "ahead/dentist-fri-10-00"
                                                          "ahead/interview-with-maple-co-thu-29-oct-10-00"]))]
           (let [note (digest/compose {:date (LocalDate/parse "2026-10-26") :changes [] :since 0
                                       :written written :dismissed dismissed})]
             (every? (fn [{:keys [op keys]}]
                       (or (not= "dismiss" op) (not-any? dismissed keys)))
                     (:numbered note))))))

(deftest a-full-note
  (let [note (digest/compose
              {:date (LocalDate/parse "2026-10-26")
               :since 0
               :changes [{:seq 4 :what "skill" :name "grants" :change "changed" :actor "steward"
                          :labels ["contaminated" "mail:eve@example.net"] :delta {:added 2 :removed 0}}
                         {:seq 5 :what "note" :name "venues" :change "added" :actor "steward" :labels ["private"]
                          :delta {:added 1 :removed 0}}]
               :written {"ahead" {:items [{:text "Interview with Maple Co, Thu 29 Oct 10:00" :offer "Want prep notes?"
                                           :key "maple"}]
                                  :labels ["contaminated" "private"]}
                         "learned" {:items [{:text "I keep the venues you like in a note now."}] :labels ["private"]}}
               :dismissed #{}})]
    (is (= "Daily note for Monday 26 October: 1 change to check" (:subject note)))
    (is (= ["Changes to check" "Coming up" "What I learned" nil] (map :heading (:sections note))))
    (is (= (str "1. Changed the how-to \"grants\" (2 lines added), shaped by a mail that says it is from"
                " eve@example[.]net. Reply \"revert 1\" to undo it.")
           (:text (first (:items (first (:sections note)))))))
    (is (= "2. Interview with Maple Co, Thu 29 Oct 10:00 Want prep notes?"
           (:text (first (:items (second (:sections note)))))))
    (is (= ["contaminated" "private"] (:labels (first (:items (second (:sections note))))))
        "a role's text keeps its provenance, which each channel shows")
    (is (= [{:n 1 :op "revert" :change 4}
            {:n 2 :op "dismiss" :part "ahead"}]
           (map #(select-keys % [:n :op :change :part]) (:numbered note))))
    (is (= ["ahead/maple" "ahead/interview-with-maple-co-thu-29-oct-10-00"] (:keys (second (:numbered note)))))
    (is (str/includes? (:text (first (:items (last (:sections note))))) "\"not N\""))))

(deftest an-empty-note-says-nothing
  (is (nil? (:sections (digest/compose {:date (LocalDate/parse "2026-10-26") :changes [] :since 0
                                        :written {} :dismissed #{}}))))
  (testing "nor does one whose suggestions were all turned down"
    (is (nil? (:sections (digest/compose {:date (LocalDate/parse "2026-10-26") :changes [] :since 0
                                          :written {"ahead" {:items [{:text "X" :key "x"}] :labels []}}
                                          :dismissed #{"ahead/x"}}))))))

(deftest parts-are-checked
  (is (nil? (digest/part-problem "ahead" [{:text "a" :offer "b" :key "c"}])))
  (is (digest/part-problem "flagged" [{:text "a"}]) "relay writes the flagged section, never a role")
  (is (digest/part-problem "ahead" [{:text ""}]))
  (is (digest/part-problem "ahead" [{:text "a" :action {:op "revert" :n 1}}]))
  (is (digest/part-problem "ahead" (repeat 21 {:text "a"})))
  (is (digest/part-problem "ahead" [{:text (apply str (repeat 501 "a"))}])))

;; --- a reply

(def ^:private numbered
  [{:n 1 :op "revert" :change 4} {:n 2 :op "dismiss"} {:n 3 :op "dismiss"}])

(deftest a-reply-says-what-to-do
  (is (= [{:op "revert" :n 1}] (digest/reply-acts "revert 1" numbered)))
  (is (= [{:op "revert" :n 1} {:op "dismiss" :n 3}] (digest/reply-acts "Undo 1.\nnot 3\n\nThanks!" numbered)))
  (is (= [{:op "dismiss" :n 2} {:op "dismiss" :n 3}] (digest/reply-acts "dismiss 2 and 3" numbered)))
  (is (= [{:op "dismiss" :n 2} {:op "dismiss" :n 3}] (digest/reply-acts "not #2, 3" numbered)))
  (is (= [] (digest/reply-acts "Please revert the change about grants" numbered)) "left to the role to read")
  (is (= [] (digest/reply-acts "not this one" numbered)) "two suggestions: which one is not clear")
  (is (= [{:op "dismiss" :n 2}] (digest/reply-acts "Not this one." [{:n 1 :op "revert"} {:n 2 :op "dismiss"}])))
  (quick 200
         (prop/for-all [n (gen/choose 1 999)
                        w (gen/elements ["revert" "Revert" "undo" "not" "dismiss" "drop" "skip"])
                        pad (gen/elements ["" " " "  "])]
           (= [{:op (if (#{"revert" "Revert" "undo"} w) "revert" "dismiss") :n n}]
              (digest/reply-acts (str pad w " " n pad) numbered)))))

(deftest a-reply-of-only-acts-needs-nobody-to-read-it
  (is (digest/only-acts? "revert 1" numbered))
  (is (digest/only-acts? "Undo 1.\n\nnot 3" numbered))
  (is (digest/only-acts? "not this one" [{:n 1 :op "revert"} {:n 2 :op "dismiss"}]))
  (is (not (digest/only-acts? "revert 1\nThanks, and book the hall too." numbered)))
  (is (not (digest/only-acts? "Please revert the change about grants" numbered)))
  (is (not (digest/only-acts? "" numbered))))

(deftest what-an-undo-did-is-said-plainly
  ;; Regression (U1): undoing a new how-to said it "is back as it was".
  (is (= "Removed the new how-to \"office-hours-questions\"."
         (digest/undone-line {:what "skill" :name "office-hours-questions" :change "added"})))
  (is (= "Put the routine \"morning\" back as it was." (digest/undone-line {:what "routine" :name "morning" :change "changed"})))
  (is (= "Brought back the note \"vendors\"." (digest/undone-line {:what "note" :name "vendors" :change "removed"})))
  (is (= "Dropped \"Prep notes for the Riverbend interview\". It will not be suggested again."
         (digest/dropped-line "Prep notes for the Riverbend interview")))
  (testing "adding or removing a role is never undone, and the reason is plain"
    (is (digest/revert-refusal {:what "role" :name "scout" :change "added"}))
    (is (digest/revert-refusal {:what "role" :name "scout" :change "removed"}))
    (is (nil? (digest/revert-refusal {:what "role" :name "scout" :change "changed"})))
    (is (nil? (digest/revert-refusal {:what "skill" :name "x" :change "added"})))))

(def ^:private gen-note
  (gen/let [changes (gen/fmap #(vec (map-indexed (fn [i c] (assoc c :seq (inc i))) %)) (gen/vector gen-change 0 8))
            written gen-written
            id (gen/choose 1 50)]
    (assoc (digest/compose {:date (LocalDate/parse "2026-10-26") :changes changes :since 0 :written written
                            :dismissed #{}})
           :id id :changes changes)))

(deftest a-number-in-a-note-names-one-change-that-can-be-undone
  ;; For any note, each number names exactly one item; a revert number
  ;; names exactly one change, which can be undone; and relay changes
  ;; shows that same number for it and never the log's.
  (quick 300
         (prop/for-all [notes (gen/vector gen-note 1 3)]
           (let [notes (map-indexed (fn [i m] (assoc m :id (inc i))) notes)
                 latest (last notes)
                 numbers (digest/note-numbers notes)
                 by-seq (into {} (for [c (:changes latest)] [(:seq c) c]))
                 lines (digest/changes-lines (:changes latest) #{} numbers (:id latest) toronto 100)]
             (and (= (count (:numbered latest)) (count (distinct (map :n (:numbered latest)))))
                  (every? (fn [{:keys [op change]}]
                            (or (not= "revert" op)
                                (and (some? (by-seq change)) (digest/revertable? (by-seq change)))))
                          (:numbered latest))
                  (every? (fn [{:keys [op n change]}]
                            (or (not= "revert" op) (= [n (:id latest)] (get numbers change))))
                          (:numbered latest))
                  (every? (fn [{:keys [op n change]}]
                            (or (not= "revert" op)
                                (some #(str/starts-with? % (str n ".  ")) lines)))
                          (:numbered latest)))))))

(deftest relay-revert-takes-a-notes-number-or-a-changes-handle
  (let [changes [{:seq 4} {:seq 9} {:seq 12}]]
    (is (= {:n 3} (digest/revert-target changes ["3"])))
    (is (= {:n 3 :message 12} (digest/revert-target changes ["3" "12"])))
    (is (= {:change 9} (digest/revert-target changes ["c2"])) "a handle is a place among the changes, not a seq")
    (is (= {:change 12} (digest/revert-target changes ["C3"])))
    (is (= "relay changes lists no change c4." (:error (digest/revert-target changes ["c4"]))))
    (doseq [bad [[] ["x"] ["c"] ["c0"] ["c2" "5"] ["3" "x"] ["3" "4" "5"] ["-1"]]]
      (is (:error (digest/revert-target changes bad)) (pr-str bad)))))

(deftest relay-changes-names-how-to-undo-each
  (let [at "2026-10-26T13:00:00Z"
        changes [{:seq 4 :at at :what "note" :name "a" :change "added" :actor "steward" :labels []}
                 {:seq 9 :at at :what "role" :name "helper" :change "added" :actor "steward" :labels []}
                 {:seq 12 :at at :what "note" :name "b" :change "added" :actor "steward" :labels ["contaminated"]}
                 {:seq 15 :at at :what "note" :name "c" :change "added" :actor "steward" :labels []}]
        lines (digest/changes-lines changes #{15} {12 [1 30]} 30 ZoneOffset/UTC 10)]
    (is (str/starts-with? (nth lines 0) "c1  "))
    (is (str/starts-with? (nth lines 1) "    "))
    (is (str/ends-with? (nth lines 1) ", which cannot be undone"))
    (is (str/starts-with? (nth lines 2) "1.  "))
    (is (str/starts-with? (nth lines 3) "    "))
    (is (str/ends-with? (nth lines 3) ", undone"))
    (is (= "To undo one: relay revert <n> for item n of the latest daily note; relay revert c<k> for any other."
           (last lines)))
    (is (not-any? #(re-find #"\b(9|15)\b" %) lines) "the log's numbers are never shown")))

(deftest a-long-list-of-changes-is-counted-past-what-fits
  (let [changes (vec (for [i (range 1 61)]
                       {:seq i :what "note" :name (str "n" i) :change "added" :actor "steward"
                        :labels ["contaminated"] :delta {:added 1 :removed 0}}))
        note (digest/compose {:date (LocalDate/parse "2026-10-26") :changes changes :since 0 :written {} :dismissed #{}})
        items (:items (first (:sections note)))]
    (is (= 60 (count (:flagged note))) "the note's record keeps every one")
    (is (= (inc digest/flags-shown) (count items)))
    (is (= "And 20 more changes like these. relay changes lists them all." (:text (last items))))
    (is (str/starts-with? (:subject note) "Daily note for Monday 26 October: 60 changes"))))
