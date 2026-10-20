(ns relay.cards-test
  "Cards on the board: the pure rules in relay.cards, and the card
  commands as relay.core decides and folds them."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.walk :as walk]
            [relay.cards :as cards]
            [relay.core :as core]
            [relay.core-test :refer [cfg t0]]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.prompt :as prompt]
            [relay.refs :as refs]
            [relay.tools :as tools]))

(defn- check [n property]
  (let [result (tc/quick-check n property)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

(defn- refusal
  "The reason a call was refused, or nil when it was not."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (or (:relay/rejected (ex-data e)) (throw e)))))

(defn- refusal-message
  "What a refused call said."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (if (:relay/rejected (ex-data e)) (ex-message e) (throw e)))))

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
           (= (get wire "rev") (:rev c))
           ;; A role or the CLI reads the rev it passes as `seen` from the
           ;; header, and a client from the wire form's text.
           (str/includes? (first (str/split-lines (cards/text-form c))) (str ", rev " (:rev c) ")"))
           (str/includes? (get wire "text") (str ", rev " (:rev c) ")"))))))

(deftest text-and-wire-forms-are-total-and-the-wire-form-round-trips
  (check 300 text-and-wire-are-total))

(deftest the-rev-a-reader-sees-is-the-seen-an-edit-takes
  (let [w (-> (new-card {:kind "checklist" :title "T" :items ["a" "b"]})
              (op! {:op "edit" :item 2 :text "b2"}))
        shown (parse-long (second (re-find #", rev (\d+)\)" (cards/text-form (card w)))))]
    (is (some? (cards/decide (:cards w) 1 {:op "edit" :item 2 :text "b3" :seen shown})))
    (is (= :conflict (refusal #(cards/decide (:cards w) 1 {:op "edit" :item 2 :text "b3" :seen (dec shown)}))))))

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
                 seen (gen/choose -2 40)]
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
                    ;; A rev the client cannot have read is refused, not
                    ;; taken as newer than every edit.
                    (or (neg? seen) (> seen (:rev c))) (= :invalid r)
                    (> (:rev el) seen) (= :conflict r)
                    :else (nil? r))))
              checks))))

(deftest seen-refuses-exactly-the-stale-edits-test
  (check 300 seen-refuses-exactly-the-stale-edits))

(deftest a-seen-past-the-cards-rev-is-refused
  ;; seen=99999 once passed every stale-edit check, so it overwrote newer text.
  (let [w (-> (new-card {:kind "checklist" :title "T" :items ["a" "b"]})
              (op! {:op "edit" :item 1 :text "a2"}))
        rev (:rev (card w))]
    (is (= :invalid (refusal #(cards/decide (:cards w) 1 {:op "edit" :item 1 :text "y" :seen 99999}))))
    (is (str/includes? (refusal-message #(cards/decide (:cards w) 1 {:op "edit" :item 1 :text "y" :seen (inc rev)}))
                       (str "is at rev " rev)))
    (is (some? (cards/decide (:cards w) 1 {:op "edit" :item 1 :text "y" :seen rev})))))

(deftest a-missing-kind-or-op-is-named-in-plain-words
  (is (= "A card needs a kind: checklist, tracker or note."
         (refusal-message #(cards/added {} {:title "T"}))))
  (let [w (new-card {:kind "note" :title "T" :text ""})]
    (doseq [op [{} {:op nil}]]
      (is (str/starts-with? (refusal-message #(cards/decide (:cards w) 1 op)) "A card op needs an op: ")))))

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
    (testing "characters are code points, so an emoji counts as one, not as its surrogate pair's two"
      (let [emoji "\uD83D\uDE00"]
        (is (some? (cards/added {} {:kind "note" :title (apply str (repeat 200 emoji))})))
        (is (re-find #"at most 200" (msg #(cards/added {} {:kind "note" :title (apply str (repeat 201 emoji))}))))
        (is (some? (cards/added {} {:kind "checklist" :title "T" :items [(apply str (repeat 150 emoji))]})))
        (is (some? (cards/added {} {:kind "note" :title "T" :text (apply str (repeat 8000 emoji))})))
        (is (re-find #"8000" (msg #(cards/added {} {:kind "note" :title "T" :text (apply str (repeat 8001 emoji))}))))))
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
    (is (= (str "Card 1: How to work through it (checklist, 1 of 3 done, rev 2) [[card:1]]\n"
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
        (is (str/includes? (cards/text-form (card w)) "(checklist, 3 of 3 done, complete, rev 4)"))))
    (testing "an archived card takes only restore"
      (let [w (op! w {:op "archive"})]
        (is (str/includes? (cards/text-form (card w)) ", archived, rev 3)"))
        (is (= :invalid (refusal #(cards/decide (:cards w) 1 {:op "tick" :item 2}))))
        (is (cards/active? (card (op! w {:op "restore"})))))))
  (let [w (-> (new-card {:kind "tracker" :title "Catch up monthly financials"
                         :rows ["BMO" "RBC" "x" "y" "Canadian Tire"] :columns ["Jun" "Jul" "Aug" "Sep"]})
              (op! {:op "remove_row" :row 3}) (op! {:op "remove_row" :row 4})
              (op! {:op "set" :row 1 :column 1 :state "done"}) (op! {:op "set" :row 1 :column 2 :state "done"})
              (op! {:op "set" :row 5 :column 1 :state "skip"}) (op! {:op "set" :row 5 :column 2 :state "done"}))]
    (is (= (str "Card 1: Catch up monthly financials (tracker, 4 of 12 done, rev 7) [[card:1]]\n"
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
    (is (= "Card 1: Plan (note, rev 1) [[card:1]]\n  │ one\n  │ two" (cards/text-form (card w))))
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

;; --- roles, labels and the board's readers

(defn- with-relay
  "Calls (f sys) with a relay on artificial data: hub and clerk, and scout,
  a public role."
  [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-cards"}))
        data (str dir "/data")]
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :a
                   :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}
                   :notify {:kind :none}
                   :tools {"search" {:class :egress :labels ["contaminated"] :description "Search." :command ["true"]}}
                   :grants {"scout" {:public true :tools ["search"]}}}))
    (try
      (let [sys (inbound/system data)]
        (doseq [id ["hub" "clerk"]]
          (inbound/submit! sys {:type :role/add :actor "operator" :args {:id id :title id :instructions "x"}}))
        (inbound/submit! sys {:type :role/add :actor "operator" :public true
                              :args {:id "scout" :title "Scout" :instructions "Search."}})
        (f sys))
      (finally (fs/delete-tree dir)))))

(defn- call [sys role tool args labels]
  (tools/call! sys role tool args labels))

(defn- the-card [sys id] (core/card (inbound/current-state sys) id))

(deftest a-contaminated-item-taints-the-card-until-it-is-gone
  (with-relay
    (fn [sys]
      (let [added (call sys "hub" "card_add" {:kind "checklist" :title "Taxes" :items ["Find the T4"]} ["private"])
            id (parse-long (second (re-find #"Added card (\d+)" (:ok added))))]
        (testing "card_add answers with the card, carrying the session's labels"
          (is (str/includes? (:ok added) "[ ] 1 Find the T4") (pr-str added))
          (is (= ["private"] (:labels added))))
        (is (= ["private"] (core/card-labels (the-card sys id))))
        (testing "a contaminated session's item makes the card contaminated"
          (is (= {:ok (str "Added item 2 to card " id ".") :labels []}
                 (call sys "clerk" "card_op" {:card id :op "add" :text "Read https://evil.example"} ["contaminated"])))
          (is (= ["contaminated" "private"] (core/card-labels (the-card sys id)))))
        (testing "a role's read withholds it unless the role accepts contamination"
          (let [r (call sys "hub" "cards" {} [])]
            (is (= (str "Card " id ": contaminated, withheld. Pass accept_contamination to read it.") (:ok r)))
            (is (= [] (:labels r))))
          (let [r (call sys "hub" "cards" {:card id :accept_contamination true} [])]
            (is (str/includes? (:ok r) "[ ] 2 Read https://evil.example"))
            (is (= ["contaminated" "private"] (:labels r)))))
        (testing "ticks add nothing"
          (call sys "clerk" "card_op" {:card id :op "tick" :item 1} ["contaminated"])
          (is (= ["contaminated" "private"] (core/card-labels (the-card sys id))))
          (let [c (the-card sys id)]
            (is (= [["private"] ["contaminated"]] (map :labels (:items c))))))
        (testing "the operator rewriting it cleans the card"
          (inbound/submit! sys {:type :card/op :actor "operator" :labels []
                                :args {:card id :op "edit" :item 2 :text "Read the CRA page"}})
          (is (= ["private"] (core/card-labels (the-card sys id)))))
        (testing "so does removing it"
          (call sys "clerk" "card_op" {:card id :op "add" :text "Dirty"} ["contaminated"])
          (is (core/contaminated? (core/card-labels (the-card sys id))))
          (call sys "hub" "card_op" {:card id :op "remove" :item 3} [])
          (is (= ["private"] (core/card-labels (the-card sys id)))))
        (testing "an op that changes nothing says so, with the labels of what it tells"
          (is (= {:ok "That changes nothing: Item 2 already says that." :labels ["private"]}
                 (call sys "hub" "card_op" {:card id :op "edit" :item 2 :text "Read the CRA page"} []))))
        (testing "a refusal comes back as text"
          (is (= (str "Card " id " has no item 9.") (:error (call sys "hub" "card_op" {:card id :op "tick" :item 9} [])))))
        (testing "a ref resolves to the card's text form and labels"
          (let [r (refs/resolve (inbound/current-state sys) (str "[[card:" id "]]"))]
            (is (= [:ok (str "Card " id ": Taxes") ["private"]] [(:status r) (:title r) (:labels r)]))
            (is (str/includes? (:text r) "[x] 1 Find the T4"))))))))

(deftest public-roles-have-no-board
  (with-relay
    (fn [sys]
      (testing "they are not offered the card tools"
        (is (not-any? (set (keys (tools/offered (:config sys) "scout"))) ["cards" "card_add" "card_op"]))
        (is (every? (set (keys (tools/offered (:config sys) "hub"))) ["cards" "card_add" "card_op"])))
      (testing "and decide refuses their card commands"
        (is (thrown-with-msg? Exception #"public role has no board"
                              (inbound/submit! sys {:type :card/add :actor "scout" :labels []
                                                    :args {:kind "note" :title "x"}}))))
      (testing "and the read tool does not exist for them"
        (is (= "You have no tool called cards." (:error (call sys "scout" "cards" {} [])))))
      (testing "and their prompt lists no board"
        (is (nil? (core/prompt-cards (inbound/current-state sys) (:config sys) "scout" [])))))))

(deftest the-prompt-lists-only-cards-its-labels-cover
  (with-relay
    (fn [sys]
      (let [add! (fn [title labels]
                   (:seq (first (inbound/submit! sys {:type :card/add :actor "hub" :labels labels
                                                      :args {:kind "tracker" :title title :rows ["BMO"]
                                                             :columns ["Jun" "Jul"]}}))))
            clean (add! "Catch up monthly financials" [])
            dirty (add! "From a web page" ["contaminated"])
            _ (inbound/submit! sys {:type :card/op :actor "hub" :labels []
                                    :args {:card clean :op "set" :row 1 :column 1 :state "done"}})
            _ (inbound/submit! sys {:type :card/op :actor "hub" :labels ["contaminated"]
                                    :args {:card clean :op "add_row" :text "RBC"}})
            state (inbound/current-state sys)
            for-labels #(core/prompt-cards state (:config sys) "hub" %)]
        (testing "a card is listed by its title's labels: counts and other text add none"
          (is (= {:cards [{:id clean :title "Catch up monthly financials" :kind "tracker" :progress {:done 1 :total 4}}]
                  :left-out true}
                 (for-labels ["private"]))))
        (testing "a session that holds contaminated is shown both"
          (is (= [clean dirty] (map :id (:cards (for-labels ["contaminated" "private"])))))
          (is (false? (:left-out (for-labels ["contaminated" "private"])))))
        (testing "a session without private is shown none, and told some were left out"
          (is (= {:cards [] :left-out true} (for-labels []))))
        (testing "the prompt's lines"
          (is (= (str "\n# The operator's board\n\n"
                      "The active cards, which you read with `cards` and change with `card_op`:\n\n"
                      "card " clean ": Catch up monthly financials (tracker, 1 of 4 done)\n\n"
                      "Some cards carry data this session has not seen, so they are not listed here."
                      " `cards` shows them.\n")
                 (prompt/board (for-labels ["private"]))))
          (is (= "\n# The operator's board\n\nThe board has no cards.\n" (prompt/board {:cards [] :left-out false}))))
        (testing "a wake's prompt carries it after the fact topics"
          (let [p (prompt/wake {:role {:id "hub" :title "Hub"} :memos [] :now t0 :zone (java.time.ZoneId/of "UTC")
                                :fact-topics {:topics [] :left-out false} :cards (for-labels ["private"])})]
            (is (< (str/index-of p "# Fact topics") (str/index-of p "# The operator's board")))))))))

(deftest relay-card-add-without-a-kind-shows-its-usage
  ;; It once printed "A card's kind is checklist, tracker or note, not nil."
  (let [dir (str (fs/create-temp-dir {:prefix "relay-cards-cli"}))
        data (str dir "/data")]
    (try
      (fs/create-dirs data)
      (spit (str data "/relay.edn") (pr-str {:timezone "UTC" :default-harness :x
                                             :harnesses {:x {:kind :script :command ["true"]}}
                                             :notify {:kind :none}}))
      (log/init! data)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^usage: relay card add <kind> --title"
                            ((requiring-resolve 'relay.cli/execute) ["--data" data "card" "add"])))
      (finally (fs/delete-tree dir)))))

(deftest relay-card-refuses-an-option-it-would-ignore
  ;; --sen for --seen was once dropped, and the stale edit went through.
  (let [dir (str (fs/create-temp-dir {:prefix "relay-cards-cli"}))
        data (str dir "/data")
        run (fn [& args] ((requiring-resolve 'relay.cli/execute) (into ["--data" data] args)))]
    (try
      (fs/create-dirs data)
      (spit (str data "/relay.edn") (pr-str {:timezone "UTC" :default-harness :x
                                             :harnesses {:x {:kind :script :command ["true"]}}
                                             :notify {:kind :none}}))
      (log/init! data)
      (run "card" "add" "checklist" "--title" "Errands" "--item" "milk")
      (let [id (:id (first (core/board (inbound/current-state (inbound/system data {})) false)))]
        (run "card" (str id) "edit" "--item" "1" "--text" "oat milk")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"relay card has no option --sen\."
                              (run "card" (str id) "edit" "--item" "1" "--text" "stale" "--sen" (str id))))
        (is (= "oat milk" (-> (core/card (inbound/current-state (inbound/system data {})) id) :items first :text))))
      (finally (fs/delete-tree dir)))))

(deftest a-qualified-seen-is-refused-not-dropped
  ;; "card/seen" once passed the schema as "seen" and was then dropped, so
  ;; a stale write went through.
  (with-relay
    (fn [sys]
      (let [added (call sys "hub" "card_add" {:kind "note" :title "Plan" :text "first"} ["private"])
            id (parse-long (second (re-find #"Added card (\d+)" (:ok added))))]
        (call sys "hub" "card_op" {:card id :op "write" :text "newer"} [])
        (let [r (call sys "hub" "card_op" {:card id :op "write" :text "stale" :card/seen id} [])]
          (is (:error r) (pr-str r))
          (is (= "newer" (get-in (the-card sys id) [:note :text]))))
        (is (some? (tools/input-problem (:parameters (get (tools/offered (:config sys) "hub") "card_op"))
                                        {:card id :op "write" :text "x" (keyword "x" "seen") 1})))))))
