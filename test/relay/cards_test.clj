(ns relay.cards-test
  "Cards on the board: the pure rules in relay.cards, and the card
  commands as relay.core decides and folds them."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.walk :as walk]
            [relay.cards :as cards]
            [relay.core :as core]
            [relay.core-test :refer [cfg t0]]))

(defn- check [n property]
  (let [result (tc/quick-check n property)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

(defn- refusal
  "The reason a call was refused, or nil when it was not."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (or (:relay/rejected (ex-data e)) (throw e)))))

;; --- a pure driver: cards folded from events made here

(defn- added-event [seq data labels]
  {:seq seq :at "2026-10-20T15:03:00Z" :type "card/added" :actor "hub" :labels labels :data data})

(defn- new-card
  "A world holding one card, added by event 1."
  [args & [labels]]
  (let [e (added-event 1 (cards/added {} args) (or labels []))]
    {:cards (cards/add {} e) :seq 2 :events [e]}))

(defn- op!
  "Applies an op to card 1 if it is decided, as event :seq. A refusal or
  an op that changes nothing leaves the world as it was."
  [{:keys [cards seq] :as w} op & [labels]]
  (try
    (if-let [decided (:op (cards/decide cards 1 op))]
      (let [e {:seq seq :at "2026-10-20T15:04:00Z" :type "card/changed" :actor "hub" :labels (or labels [])
               :data {:card 1 :op decided}}]
        (-> w (update :cards cards/change e) (update :events conj e) (update :seq inc)))
      w)
    (catch clojure.lang.ExceptionInfo e
      (if (:relay/rejected (ex-data e)) w (throw e)))))

(defn- card [w] (get-in w [:cards 1]))

;; --- generators

(def gen-line
  (gen/frequency [[8 (gen/fmap #(str "t" (str/join %)) (gen/vector (gen/elements ["a" "b" " " "é" "‮" "."]) 0 6))]
                  [1 (gen/return "")]
                  [1 (gen/return "two\nlines")]
                  [1 (gen/return (apply str (repeat 201 "x")))]]))

(def gen-labels (gen/elements [[] ["private"] ["contaminated"] ["contaminated" "private"]]))

(def gen-card-args
  (gen/one-of [(gen/fmap (fn [[t items]] {:kind "checklist" :title t :items items})
                         (gen/tuple (gen/return "List") (gen/vector (gen/return "item") 0 4)))
               (gen/fmap (fn [[rows cols]] {:kind "tracker" :title "Grid" :rows rows :columns cols})
                         (gen/tuple (gen/vector (gen/return "row") 0 3) (gen/vector (gen/return "col") 0 3)))
               (gen/return {:kind "note" :title "Note" :text "first\nsecond"})]))

(defn- gen-op-for
  "An op, mostly one of `kind`'s, with small element ids and mostly sound
  text, so that most of them are decided."
  [kind]
  (gen/let [op (gen/frequency [[16 (gen/elements (sort (keep (fn [[o s]] (when ((:kinds s) kind) o)) cards/ops)))]
                               [3 (gen/elements (sort (keys cards/ops)))]
                               [1 (gen/return "bogus")]])
            n (gen/frequency [[9 (gen/choose 1 4)] [1 (gen/choose 0 9)]])
            m (gen/frequency [[9 (gen/choose 1 4)] [1 (gen/choose 0 9)]])
            state (gen/frequency [[9 (gen/elements cards/cell-states)] [1 (gen/return "maybe")]])
            text gen-line
            seen (gen/frequency [[3 (gen/return nil)] [1 (gen/choose 0 30)]])]
    (cond-> {:op op :item n :row n :column m :state state :text text}
      seen (assoc :seen seen))))

(def gen-op (gen/bind (gen/elements cards/kinds) gen-op-for))

(def gen-world
  "A card and the ops applied to it, each with the labels of the session
  that sent it."
  (gen/let [args gen-card-args
            labels gen-labels
            steps (gen/vector (gen/tuple (gen-op-for (:kind args)) gen-labels) 0 25)]
    (reduce (fn [w [op ls]] (op! w op ls)) (new-card args labels) steps)))

;; --- properties

(def text-and-wire-are-total
  (prop/for-all [w gen-world]
    (let [c (card w)
          wire (cards/wire c (core/card-labels c))]
      (and (string? (cards/text-form c))
           (str/starts-with? (cards/text-form c) "Card 1: ")
           ;; The wire form survives a JSON round trip unchanged.
           (= wire (json/parse-string (json/generate-string wire)))
           (= (get wire "rev") (:rev c))))))

(deftest text-and-wire-forms-are-total-and-the-wire-form-round-trips
  (check 300 text-and-wire-are-total))

(defn- refold
  "The cards folded again from the events alone, after a JSON round trip,
  as replay reads them from the log."
  [events]
  (reduce (fn [cs e]
            (let [e (json/parse-string (json/generate-string e) true)]
              (case (:type e) "card/added" (cards/add cs e) "card/changed" (cards/change cs e))))
          {} events))

(def the-projection-is-a-fold-of-the-events
  (prop/for-all [w gen-world]
    (= (:cards w) (refold (:events w)))))

(deftest the-projection-is-a-fold-of-the-decided-events
  (check 300 the-projection-is-a-fold-of-the-events))

(defn- without-revs [x]
  (walk/postwalk #(if (map? %) (dissoc % :rev) %) x))

(def gen-two-ops-on-different-elements
  (gen/one-of
   [(gen/let [a (gen/elements [{:op "tick"} {:op "untick"} {:op "edit" :text "A"} {:op "remove"}])
              b (gen/elements [{:op "tick"} {:op "untick"} {:op "edit" :text "B"} {:op "remove"}])
              [i j] (gen/elements [[1 2] [2 3] [1 3]])]
      [{:kind "checklist" :title "L" :items ["one" "two" "three"]}
       (assoc a :item i) (assoc b :item j)])
    (gen/let [[r1 c1 r2 c2] (gen/elements [[1 1 1 2] [1 1 2 1] [2 2 1 1]])
              s1 (gen/elements cards/cell-states)
              s2 (gen/elements cards/cell-states)]
      [{:kind "tracker" :title "G" :rows ["a" "b"] :columns ["x" "y"]}
       {:op "set" :row r1 :column c1 :state s1} {:op "set" :row r2 :column c2 :state s2}])
    (gen/return [{:kind "tracker" :title "G" :rows ["a" "b"] :columns ["x" "y"]}
                 {:op "rename_row" :row 1 :text "A"} {:op "remove_row" :row 2}])]))

(def ops-on-different-elements-commute
  (prop/for-all [[args a b] gen-two-ops-on-different-elements]
    (let [w (new-card args)]
      (= (without-revs (card (-> w (op! a) (op! b))))
         (without-revs (card (-> w (op! b) (op! a))))))))

(deftest ops-on-different-elements-commute-test
  (check 200 ops-on-different-elements-commute))

(def seen-refuses-exactly-the-stale-edits
  (prop/for-all [w gen-world
                 seen (gen/choose 0 30)]
    (let [c (card w)
          [op el] (case (:kind c)
                    "checklist" (let [i (first (:items c))] [(when i {:op "edit" :item (:id i)}) i])
                    "tracker" (let [r (first (:rows c))] [(when r {:op "rename_row" :row (:id r)}) r])
                    "note" [{:op "write"} (:note c)])
          ;; The title too, for every kind.
          checks (cond-> [[{:op "retitle"} (:title c)]] op (conj [op el]))]
      (every? (fn [[op el]]
                (let [r (refusal #(cards/decide (:cards w) 1 (assoc op :text "a new text" :seen seen)))]
                  (cond
                    (not (cards/active? c)) (= :invalid r)
                    (> (:rev el) seen) (= :conflict r)
                    :else (nil? r))))
              checks))))

(deftest seen-refuses-exactly-the-stale-edits-test
  (check 300 seen-refuses-exactly-the-stale-edits))

(def unknown-ops-and-elements-are-refused
  (prop/for-all [w gen-world
                 op (gen/such-that #(not (contains? cards/ops %)) gen/string-alphanumeric)
                 n (gen/choose 7 1000)]
    (let [c (card w)
          on-element (case (:kind c)
                       "checklist" {:op "tick" :item n}
                       "tracker" {:op "set" :row n :column 1 :state "done"}
                       "note" nil)]
      (and (= :invalid (refusal #(cards/decide (:cards w) 1 {:op op})))
           (= :not-found (refusal #(cards/decide (:cards w) 99 {:op "archive"})))
           (or (nil? on-element) (not (cards/active? c))
               (= :not-found (refusal #(cards/decide (:cards w) 1 on-element))))))))

(deftest unknown-ops-cards-and-elements-are-refused
  (check 200 unknown-ops-and-elements-are-refused)
  (is (= :invalid (refusal #(cards/added {} {:kind "kanban" :title "x"}))))
  (is (= :invalid (refusal #(cards/added {} {:kind nil :title "x"}))))
  (testing "an op of another kind is refused"
    (is (= :invalid (refusal #(cards/decide (:cards (new-card {:kind "note" :title "N"})) 1 {:op "tick" :item 1}))))))

;; --- examples

(deftest limits-are-enforced-and-say-what-they-are
  (let [msg (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (testing "one line of 1 to 200 characters"
      (is (re-find #"at most 200" (msg #(cards/added {} {:kind "note" :title (apply str (repeat 201 "x"))}))))
      (is (some? (cards/added {} {:kind "note" :title (str "  " (apply str (repeat 200 "x")) "  ")})))
      (is (re-find #"one line" (msg #(cards/added {} {:kind "note" :title "a\nb"}))))
      (is (re-find #"one line" (msg #(cards/added {} {:kind "note" :title "a b"}))))
      (is (re-find #"not be empty" (msg #(cards/added {} {:kind "checklist" :title "T" :items ["  "]})))))
    (testing "a note is up to 8000 characters and may be empty"
      (is (re-find #"8000" (msg #(cards/added {} {:kind "note" :title "T" :text (apply str (repeat 8001 "x"))}))))
      (is (= "" (:text (cards/added {} {:kind "note" :title "T"})))))
    (testing "counts"
      (is (re-find #"at most 100 items" (msg #(cards/added {} {:kind "checklist" :title "T" :items (repeat 101 "i")}))))
      (is (re-find #"at most 40 rows" (msg #(cards/added {} {:kind "tracker" :title "T" :rows (repeat 41 "r")}))))
      (is (re-find #"at most 24 columns" (msg #(cards/added {} {:kind "tracker" :title "T" :columns (repeat 25 "c")}))))
      (let [w (new-card {:kind "checklist" :title "T" :items (repeat 100 "i")})]
        (is (re-find #"at most 100 items" (msg #(cards/decide (:cards w) 1 {:op "add" :text "more"}))))))
    (testing "the 31st active card is refused, saying to archive one"
      (let [thirty (into {} (for [i (range 1 31)] [i {:id i :status :active}]))]
        (is (re-find #"at most 30 active cards. Archive one" (msg #(cards/added thirty {:kind "note" :title "T"}))))
        (is (some? (cards/added (assoc-in thirty [1 :status] :archived) {:kind "note" :title "T"})))))
    (testing "a kind takes only its own contents"
      (is (re-find #"checklist takes items" (msg #(cards/added {} {:kind "checklist" :title "T" :text "x"})))))))

(deftest ops-do-what-they-say
  (let [w (-> (new-card {:kind "checklist" :title "How to work through it"
                         :items ["Make a folder for the statements" "Download one account's months in one login"
                                 "Import into the app, then the spreadsheet"]})
              (op! {:op "tick" :item 1}))]
    (is (= (str "Card 1: How to work through it (checklist, 1 of 3 done) [[card:1]]\n"
                "[x] 1 Make a folder for the statements\n"
                "[ ] 2 Download one account's months in one login\n"
                "[ ] 3 Import into the app, then the spreadsheet")
           (cards/text-form (card w))))
    (testing "an op that changes nothing says so"
      (is (= "Item 1 is already ticked." (:unchanged (cards/decide (:cards w) 1 {:op "tick" :item 1})))))
    (testing "a new item takes the next id, never one used before"
      (let [w (-> w (op! {:op "remove" :item 3}) (op! {:op "add" :text "Check the totals"}))]
        (is (= [1 2 4] (map :id (:items (card w)))))))
    (testing "complete when every item is done"
      (let [w (-> w (op! {:op "tick" :item 2}) (op! {:op "tick" :item 3}))]
        (is (cards/complete? (card w)))
        (is (str/includes? (cards/text-form (card w)) "(checklist, 3 of 3 done, complete)"))))
    (testing "an archived card takes only restore"
      (let [w (op! w {:op "archive"})]
        (is (str/includes? (cards/text-form (card w)) ", archived)"))
        (is (= :invalid (refusal #(cards/decide (:cards w) 1 {:op "tick" :item 2}))))
        (is (cards/active? (card (op! w {:op "restore"})))))))
  (let [w (-> (new-card {:kind "tracker" :title "Catch up monthly financials"
                         :rows ["BMO" "RBC" "x" "y" "Canadian Tire"] :columns ["Jun" "Jul" "Aug" "Sep"]})
              (op! {:op "remove_row" :row 3}) (op! {:op "remove_row" :row 4})
              (op! {:op "set" :row 1 :column 1 :state "done"}) (op! {:op "set" :row 1 :column 2 :state "done"})
              (op! {:op "set" :row 5 :column 1 :state "skip"}) (op! {:op "set" :row 5 :column 2 :state "done"}))]
    (is (= (str "Card 1: Catch up monthly financials (tracker, 4 of 12 done) [[card:1]]\n"
                "Columns: 1 Jun, 2 Jul, 3 Aug, 4 Sep\n"
                "Row 1 BMO: Jun done, Jul done, Aug open, Sep open\n"
                "Row 2 RBC: all open\n"
                "Row 5 Canadian Tire: Jun skip, Jul done, Aug open, Sep open")
           (cards/text-form (card w))))
    (testing "removing a column drops its cells"
      (let [c (card (op! w {:op "remove_column" :column 1}))]
        (is (= {:done 2 :total 9} (cards/progress c)))))
    (testing "setting a cell open stores nothing"
      (is (= {[1 2] "done" [5 1] "skip" [5 2] "done"}
             (:cells (card (op! w {:op "set" :row 1 :column 1 :state "open"})))))))
  (let [w (new-card {:kind "note" :title "Plan" :text "one\ntwo"})]
    (is (= "Card 1: Plan (note) [[card:1]]\n  │ one\n  │ two" (cards/text-form (card w))))
    (is (not (cards/complete? (card w))))))

(deftest the-wire-form-is-safe-to-show
  (let [w (-> (new-card {:kind "checklist" :title "T" :items ["see https://evil.example/x"]})
              (op! {:op "add" :text "zero​width https://evil.example/y"} ["contaminated"]))
        wire (cards/wire (card w) (core/card-labels (card w)))
        [clean dirty] (get wire "items")]
    (is (= ["contaminated" "private"] (get wire "labels")))
    (testing "clean text is made visible, not defanged"
      (is (= ["see https://evil.example/x" false] [(get clean "text") (get clean "outsider")])))
    (testing "contaminated text is defanged and visible, and marked outsider"
      (is (= ["zero\\u{200b}width hxxps://evil[.]example/y" true] [(get dirty "text") (get dirty "outsider")])))
    (testing "the text form is marked too"
      (is (str/includes? (get wire "text") "[ ] 2 [contaminated] zero\\u{200b}width hxxps://evil[.]example/y")))))

;; --- through relay.core

(defn- run
  "Runs a command through relay.core: [state events], or {:rejected
  reason :message m}."
  [state actor type args & [{:keys [labels public]}]]
  (try (core/run-command state {:type type :actor actor :args args :labels labels :public public}
                         {:now t0 :config cfg})
       (catch clojure.lang.ExceptionInfo e
         (if (core/rejection e)
           {:rejected (core/rejection e) :message (ex-message e)}
           (throw e)))))

(defn- seeded []
  (-> core/empty-state
      (run "operator" :role/add {:id "hub" :title "Hub" :instructions "Run relay."})
      first))

(def gen-command
  (gen/let [actor (gen/elements ["hub" "operator"])
            labels gen-labels
            add? (gen/frequency [[1 (gen/return true)] [4 (gen/return false)]])
            args gen-card-args
            card (gen/choose 2 8)
            op gen-op]
    (if add?
      [actor labels :card/add args]
      [actor labels :card/op (assoc op :card card)])))

(def core-folds-cards-like-replay
  (prop/for-all [commands (gen/vector gen-command 0 30)]
    (let [[state events] (reduce (fn [[state events] [actor labels type args]]
                                   (let [r (run state actor type args {:labels labels})]
                                     (if (map? r) [state events] [(first r) (into events (second r))])))
                                 [(seeded) []] commands)
          all (into (vec (second (run core/empty-state "operator" :role/add
                                      {:id "hub" :title "Hub" :instructions "Run relay."})))
                    events)]
      (and (= (:cards state) (:cards (core/replay all)))
           (= (:cards state) (:cards (core/replay (mapv #(json/parse-string (json/generate-string %) true) all))))
           (= (:cards state) (refold (filter #(str/starts-with? (:type %) "card/") events)))))))

(deftest relay-core-folds-cards-as-replay-does
  (check 150 core-folds-cards-like-replay))

(deftest a-card-change-wakes-nobody-and-notifies-nobody
  (let [[state events] (run (seeded) "operator" :card/add {:kind "checklist" :title "T" :items ["a"]})
        [state more] (run state "operator" :card/op {:card (:seq (first events)) :op "tick" :item 1})]
    (is (= ["card/added" "card/changed"] (map :type (concat events more))))
    (is (empty? (core/roles-to-wake state cfg t0)))
    (testing "an op that changes nothing appends nothing"
      (is (empty? (second (run state "operator" :card/op {:card (:seq (first events)) :op "tick" :item 1})))))
    (testing "the dispatcher keeps no cards"
      (is (= :forbidden (:rejected (run state "dispatcher" :card/add {:kind "note" :title "T"})))))))
