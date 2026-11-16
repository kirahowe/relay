(ns relay.surfaces-test
  "Surfaces: the pure rules in relay.surfaces, and a surface as a card
  through relay.cards, relay.core, the tools and the command line."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cards :as cards]
            [relay.core :as core]
            [relay.core-test :refer [cfg t0]]
            [relay.hub-test :as ht]
            [relay.inbound :as inbound]
            [relay.json :as strict]
            [relay.log :as log]
            [relay.prompt :as prompt]
            [relay.refs :as refs]
            [relay.surfaces :as surfaces]
            [relay.text :as text]
            [relay.tools :as tools]
            [relay.workspace :as workspace]
            [relay.cli]))

(defn- check [n property]
  (let [result (tc/quick-check n property)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

(defn- refused
  "{:reason :message} when (f) is refused, or nil."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (if-let [reason (:relay/rejected (ex-data e))]
           {:reason reason :message (ex-message e)}
           (throw e)))))

(defn- message [f] (:message (refused f)))

;; --- a pure driver: one surface card, folded from events made here

(defn- event [seq type labels data]
  {:seq seq :at "2026-11-14T15:03:00Z" :type type :actor "steward" :labels labels :data data})

(defn- world
  "A world holding one surface, card `id`, added by the event of that seq."
  ([args] (world args []))
  ([args labels] (world args labels 1))
  ([args labels id]
   (let [e (event id "card/added" labels (cards/added {} (assoc args :kind "surface")))]
     {:id id :cards (cards/add {} e) :seq (inc id) :events [e]})))

(defn- card [w] (get-in w [:cards (:id w)]))

(defn- op!
  "Applies an op to the world's card when it is decided. A :seen of :now
  is the card's rev. A refusal or an op that changes nothing leaves the
  world as it was."
  [{:keys [id cards seq] :as w} op & [labels]]
  (let [op (cond-> op (= :now (:seen op)) (assoc :seen (:rev (card w))))]
    (try
      (if-let [decided (:op (cards/decide cards id op))]
        (let [e (event seq "card/changed" (or labels []) {:card id :op decided})]
          (-> w (update :cards cards/change e) (update :events conj e) (update :seq inc)))
        w)
      (catch clojure.lang.ExceptionInfo e
        (if (:relay/rejected (ex-data e)) w (throw e))))))

(defn- refold [events]
  (reduce (fn [cs e] (case (:type e) "card/added" (cards/add cs e) "card/changed" (cards/change cs e)))
          {} events))

(defn- wire
  ([c] (wire c #{}))
  ([c live] (cards/wire (assoc c :live live) (core/card-labels c))))

(defn- surface-message [w] (get-in w ["surface" "a2ui" 0 "createSurface"]))

(def a-list
  "A small surface: a caption, a list of boxes drawn from the data, and a
  box of its own."
  {:title "Trip"
   :components [{"id" "root" "component" "Column" "children" ["note" "items" "paid"]}
                {"id" "note" "component" "Text" "variant" "caption"
                 "text" {"@call" "formatString" "args" {"value" "For ${/when}"}}}
                {"id" "items" "component" "List" "children" {"componentId" "item" "path" "/items"}}
                {"id" "item" "component" "CheckBox" "label" {"@path" "text"} "value" {"@path" "done"}}
                {"id" "paid" "component" "CheckBox" "label" "Site paid for" "value" {"@path" "/site/paid"}}]
   :data {"when" "Saturday"
          "items" [{"text" "Tent" "done" true} {"text" "Bags" "done" false}]
          "site" {"paid" false}}})

;; --- the fixtures relay-tui draws

(def fixtures ["checklist" "note" "hostile"])

(defn- fixture-card
  "The card a fixture's surface makes, as a client is sent it."
  [name]
  (let [{:strs [title labels live components data]} (strict/parse (slurp (str "testdata/surfaces/" name ".json")))
        w (world {:title title :components components :data data} labels 1840)]
    (wire (card w) (set live))))

(defn write-files!
  "Writes the files made from relay.surfaces again: the catalog document
  and each fixture's card. `bb surfaces` runs it, after a change to the
  catalog, to a surface in testdata/surfaces or to what relay sends for
  one."
  []
  (spit "resources/a2ui/relay_catalog.json" (str (strict/pretty (surfaces/catalog-document)) "\n"))
  (doseq [name fixtures]
    (spit (str "testdata/surfaces/" name ".card.json") (str (strict/pretty (fixture-card name)) "\n"))))

(deftest the-fixture-cards-are-what-relay-sends
  (doseq [name fixtures]
    (is (= (strict/parse (slurp (str "testdata/surfaces/" name ".card.json"))) (fixture-card name)) name)))

(deftest a-surface-a-session-with-outside-text-wrote-shows-nothing-live
  (let [w (fixture-card "hostile")
        {:strs [components dataModel]} (surface-message w)
        by-id (into {} (map (juxt #(get % "id") identity)) components)
        all (pr-str w)]
    (is (true? (get-in w ["surface" "outsider"])))
    (testing "an address formatString joins from two pieces is broken as one"
      (is (= "Apply at hxxps://evil[.]example/apply?who=kira today" (get-in by-id ["joined" "text"]))))
    (testing "the same pieces side by side are two strings, each made safe"
      (is (= ["hxxps://ev" "il[.]example/apply?who=kira"] (map #(get-in by-id [% "text"]) ["left" "right"]))))
    (testing "its text is inert, the live link broken with the rest, and that link travels beside it"
      (is (= "The fund's page is hxxps://kept[.]example/fund and its mirror is hxxps://kept[.]example/fund/x"
             (get-in by-id ["kept" "text"])))
      (is (= [[{"address" "https://kept.example/fund" "shown" "hxxps://kept[.]example/fund"}] 0]
             [(get w "links") (get w "linksLeftOut")])
          "the address that runs on from it is another address, which nobody kept"))
    (testing "an escape sequence, a zero-width character and an override are shown, not sent"
      (is (not (re-find #"[\u001b\u0007\u200b\u202e]" all)))
      (is (str/includes? (get-in by-id ["item__0" "label"]) "\\u{001b}]8;;hxxps://evil[.]example"))
      (is (= "zero\\u{200b}width and a line break" (get-in by-id ["item__1" "label"]))))
    (testing "an override is shown and a host in a label is broken too"
      (is (= "Q1 \\u{202e}etad at the arts council, evil[.]example" (get-in by-id ["item__2" "label"]))))
    (testing "the data model holds what the inputs read and none of the text"
      (is (= {"items" [{"done" false} {"done" true} {"done" false}]} dataModel)))))

;; --- what relay refuses

(defn- adding [f]
  (message #(cards/added {} (f (assoc a-list :kind "surface")))))

(defn- with-component [c]
  (fn [args] (-> args (update :components conj c) (update-in [:components 0 "children"] conj (get c "id")))))

(deftest relay-refuses-what-its-catalog-does-not-have
  (testing "a component the catalog leaves out"
    (doseq [type ["TextField" "ChoicePicker" "Image" "Video" "AudioPlayer" "Modal" "Tracker" "Surface"]]
      (is (= (str "Component 6 (x): relay's catalog has no such component. It has Text, Row, Column, List, Card,"
                  " Divider, CheckBox, Button.")
             (adding (with-component {"id" "x" "component" type "child" "note"})))
          type)))
  (testing "a property the component does not take"
    (is (= "Component 6 (x): a Row takes children and nothing else."
           (adding (with-component {"id" "x" "component" "Row" "children" [] "justify" "center"}))))
    (is (= (str "Component 6 (x): a Text takes text, variant and nothing else."
                " relay's catalog has no @checks, @accessibility, @metadata or @catalogId.")
           (adding (with-component {"id" "x" "component" "Text" "text" "t" "@catalogId" "https://elsewhere.example/c.json"}))))
    (is (= "Component 6 (x): a Text's variant is body or caption."
           (adding (with-component {"id" "x" "component" "Text" "text" "t" "variant" "h1"})))))
  (testing "a property it needs"
    (is (= "Component 6 (x): a CheckBox needs value."
           (adding (with-component {"id" "x" "component" "CheckBox" "label" "l"})))))
  (testing "a function relay does not have"
    (is (= "Component 6 (x): relay's catalog has two functions, formatString and @index."
           (adding (with-component {"id" "x" "component" "Text"
                                    "text" {"@call" "openUrl" "args" {"url" "https://evil.example"}}}))))
    (is (str/starts-with?
         (adding (with-component {"id" "x" "component" "Text"
                                  "text" {"@call" "formatString" "args" {"value" "at ${now()}"}}}))
         "Component 6 (x): in formatString, ${...} holds one path and nothing else"))
    (is (str/starts-with?
         (adding (with-component {"id" "x" "component" "Text"
                                  "text" {"@call" "formatString" "args" {"value" "open ${/a"}}}))
         "Component 6 (x): in formatString, ${...} holds one path and nothing else"))
    (testing "and a call that is not written as one is refused, not an error"
      (doseq [args [1 "oops" nil [] {} {"value" 7} {"value" "v" "more" 1}]]
        (is (str/starts-with? (adding (with-component {"id" "x" "component" "Text"
                                                       "text" {"@call" "formatString" "args" args}}))
                              "Component 6 (x): formatString is written")
            (pr-str args)))
      (doseq [call [{"@call" "@index" "args" 1} {"@call" "@index" "args" {"offset" "1"}} {"@call" "@index" "more" 1}]]
        (is (str/includes? (str (adding (with-component {"id" "x" "component" "Text" "text" call})))
                           "@index is written")
            (pr-str call)))))
  (testing "a path that is not one"
    (doseq [p ["/a b" "/a//b" "/a/" "/a~1b" "/ключ" "a/../b"]]
      (is (str/starts-with? (adding (with-component {"id" "x" "component" "Text" "text" {"@path" p}}))
                            "Component 6 (x): that is not a path.")
          p))
    (is (= "Component 6 (x): a path is written {\"@path\": \"/a/path\"}, with nothing else beside it."
           (adding (with-component {"id" "x" "component" "Text" "text" {"@path" "/a" "also" 1}})))))
  (testing "an input bound to the whole data model"
    (is (= "Component 6 (x): an input cannot be bound to /, the whole data model."
           (adding (with-component {"id" "x" "component" "CheckBox" "label" "l" "value" {"@path" "/"}}))))))

(deftest the-components-are-one-tree-under-root
  (let [set-components (fn [cs] (fn [args] (assoc args :components cs)))
        text (fn [id] {"id" id "component" "Text" "text" "t"})]
    (is (= "A surface's components are a list of objects, at least one." (adding (set-components []))))
    (is (= "A surface's components are a list of objects, at least one." (adding (set-components nil))))
    (is (= "A surface needs one component with the id root, which holds the others."
           (adding (set-components [(text "top")]))))
    (is (= "Two components have the same id."
           (adding (set-components [{"id" "root" "component" "Column" "children" ["a"]} (text "a") (text "a")]))))
    (is (= "Component 1 (root): it names a child that is not in the list."
           (adding (set-components [{"id" "root" "component" "Column" "children" ["gone"]}]))))
    (is (= "The component a is a child twice. A component has one parent."
           (adding (set-components [{"id" "root" "component" "Column" "children" ["a" "a"]} (text "a")]))))
    (is (= "Component 2 (a): root cannot be a child."
           (adding (set-components [{"id" "root" "component" "Column" "children" ["a"]}
                                    {"id" "a" "component" "Card" "child" "root"}]))))
    (is (= "Component 2 (a): root does not reach it. Make it a child of a component root reaches, or leave it out."
           (adding (set-components [(text "root") (text "a")]))))
    (testing "a ring that root does not reach"
      (is (str/includes? (adding (set-components [(text "root")
                                                  {"id" "a" "component" "Card" "child" "b"}
                                                  {"id" "b" "component" "Card" "child" "a"}]))
                         "root does not reach it")))
    (testing "an id that could pass for one relay makes"
      (doseq [id ["item__0" "_x" "x_" "0x" "a-b" "a.b" "" (apply str (repeat 65 "a"))]]
        (is (str/includes? (adding (set-components [{"id" "root" "component" "Column" "children" []} (text id)]))
                           ": an id is letters and digits")
            id)))
    (testing "a path from a list's item, or @index, outside a template"
      (is (str/includes? (adding (set-components [{"id" "root" "component" "Text" "text" {"@path" "text"}}]))
                         "mean something only where a list's template draws the component"))
      (is (str/includes? (adding (set-components [{"id" "root" "component" "Text" "text" {"@call" "@index"}}]))
                         "mean something only where a list's template draws the component")))
    (testing "nesting"
      (let [deep (fn [n] (conj (vec (for [i (range n)]
                                      {"id" (if (zero? i) "root" (str "c" i)) "component" "Card" "child" (str "c" (inc i))}))
                               (text (str "c" n))))]
        (is (nil? (adding (set-components (deep (dec surfaces/max-depth))))))
        (is (= (str "Components nest at most " surfaces/max-depth " deep.")
               (adding (set-components (deep surfaces/max-depth)))))))))

(deftest a-data-model-holds-plain-json-within-limits
  (let [data (fn [d] (adding #(assoc % :data d)))]
    (is (= "A number in a data model is a whole number. Write any other as text, such as \"12.5\"."
           (data {"when" 12.5M})))
    (is (= "A number in a data model is a whole number no larger than 2^53. Write any other as text."
           (data {"when" (inc surfaces/max-whole)})))
    (is (nil? (data (assoc (:data a-list) "big" surfaces/max-whole "small" (- surfaces/max-whole)))))
    (is (= "A data model holds no null. Leave the key out." (data {"when" nil})))
    (is (= "A key in a data model is 1 to 64 letters, digits, underscores or hyphens, and not a - by itself." (data {"a b" 1})))
    (is (= "A key in a data model is 1 to 64 letters, digits, underscores or hyphens, and not a - by itself." (data {"a/b" 1})))
    (is (= "A surface's data is a JSON object." (data [1 2])))
    (is (= (str "A list in a data model holds at most " surfaces/max-list " items.")
           (data {"items" (vec (repeat (inc surfaces/max-list) 1))})))
    (is (= (str "A text in a data model is at most " surfaces/max-text " characters.")
           (data {"when" (apply str (repeat (inc surfaces/max-text) "x"))})))
    (is (= (str "A data model nests at most " surfaces/max-data-depth " deep.")
           (data (reduce (fn [d _] {"d" d}) {} (range (inc surfaces/max-data-depth))))))
    (is (= (str "A data model holds at most " surfaces/max-values " values.")
           (data (into {} (for [i (range 30)] [(str "k" i) (vec (repeat 200 1))])))))
    (testing "no data is an empty model"
      (is (nil? (adding #(-> % (dissoc :data) (assoc :components [{"id" "root" "component" "Text" "text" "t"}]))))))))

(deftest a-surface-is-drawn-before-it-is-recorded
  (testing "too much to draw"
    (let [many (assoc a-list
                      :components [{"id" "root" "component" "List" "children" {"componentId" "row" "path" "/items"}}
                                   {"id" "row" "component" "Row" "children" ["a" "b" "c" "d" "e"]}
                                   {"id" "a" "component" "Text" "text" "a"} {"id" "b" "component" "Text" "text" "b"}
                                   {"id" "c" "component" "Text" "text" "c"} {"id" "d" "component" "Text" "text" "d"}
                                   {"id" "e" "component" "Text" "text" "e"}]
                      :data {"items" (vec (repeat 200 1))})]
      (is (= (str "The surface would draw more than " surfaces/max-drawn " components. Shorten a list.")
             (message #(cards/added {} (assoc many :kind "surface")))))
      (testing "and a write to the data that would do it is refused too"
        (let [w (world (assoc-in many [:data "items"] [1 2 3]))]
          (is (= (str "The surface would draw more than " surfaces/max-drawn " components. Shorten a list.")
                 (message #(cards/decide (:cards w) 1 {:op "data" :path "/items" :value (vec (repeat 200 1)) :seen 1}))))))))
  (testing "a text that is too long once its data is read"
    (let [long (apply str (repeat 5000 "x"))]
      (is (= (str "A text of the surface would be over " surfaces/max-text " characters once its data is read.")
             (adding #(assoc % :components [{"id" "root" "component" "Text"
                                            "text" {"@call" "formatString" "args" {"value" "${/a}${/a}"}}}]
                             :data {"a" long})))))
    (testing "of 1,600 spots, each read as 8,000 characters, is refused for its length at once"
      ;; 1,600 spots of 8,000 characters each would be 12.8 million.
      (let [spots (apply str (repeat 1600 "${/a}"))
            data {"a" (apply str (repeat surfaces/max-text "x"))}
            t0 (System/nanoTime)]
        (is (= (str "A text of the surface would be over " surfaces/max-text " characters once its data is read.")
               (adding #(assoc % :data data
                               :components [{"id" "root" "component" "Text"
                                             "text" {"@call" "formatString" "args" {"value" spots}}}]))))
        (is (< (/ (- (System/nanoTime) t0) 1e9) 2.0)))))
  (testing "a path to a list or an object reads as nothing, so a text never holds a data model's JSON"
    (let [w (world {:title "T"
                    :components [{"id" "root" "component" "Text"
                                  "text" {"@call" "formatString" "args" {"value" "[${/}|${/items}|${/items/0}|${/n}|${/yes}|${/gone}]"}}}]
                    :data {"items" [{"text" "Tent"}] "n" 7 "yes" true}})]
      (is (= "[|||7|true|]" (get (first (get (surface-message (wire (card w))) "components")) "text")))))
  (testing "more than a surface may keep, shown or not"
    ;; Data no component reads was once taken up to thirty megabytes,
    ;; and a role reading the card was handed all of it.
    (let [parked (vec (repeat 9 (apply str (repeat 8000 "x"))))
          why (adding #(assoc-in % [:data "parked"] parked))]
      (is (re-matches #"The surface's components and data would be \d+ characters as JSON, and the most is 64000\. Keep less in its data\." why)
          why)
      (let [w (world a-list)]
        (is (str/starts-with? (message #(cards/decide (:cards w) 1 {:op "data" :path "/parked" :value parked :seen 1}))
                              "The surface's components and data would be ")))))
  (testing "what a surface keeps is counted with its boxes unticked, so unticking one never takes it over"
    ;; A surface of exactly 64,000 with a ticked box was taken, and came
    ;; to 64,001 when the operator unticked it: the role's next write
    ;; was refused for a size it had not changed.
    (let [ticked (fn [n] {:title "T"
                          :components [{"id" "root" "component" "CheckBox" "label" "l" "value" {"@path" "/done"}}]
                          :data {"done" true "parked" (vec (repeat 7 (apply str (repeat 8000 "x")))) "pad" (apply str (repeat n "x"))}})
          size (fn [n] (surfaces/written-size (select-keys (ticked n) [:components :data])))
          n (- surfaces/max-written (size 0))]
      (is (= surfaces/max-written (size n)))
      (is (str/starts-with? (str (message #(cards/added {} (assoc (ticked n) :kind "surface"))))
                            (str "The surface's components and data would be " (inc surfaces/max-written) " characters")))
      (let [w (-> (world (ticked (dec n)))
                  (op! {:op "input" :path "/done" :value false :seen :now}))]
        (is (false? (get-in (card w) [:surface :data "done"])))
        (is (= surfaces/max-written (surfaces/written-size (:surface (card w)))))
        (is (nil? (refused #(cards/decide (:cards w) 1 {:op "data" :path "/pad" :value (apply str (repeat (dec n) "y"))
                                                        :seen (:rev (card w))})))
            "a write of the same size is taken after the untick"))))
  (testing "more reading of the data than a draw may do"
    ;; Three texts of 1,500 empty spots each, drawn for every item of a
    ;; list, showed nothing and took seconds for every client and tick.
    (let [spots {"@call" "formatString" "args" {"value" (apply str (repeat 1500 "${e}"))}}
          t0 (System/nanoTime)
          why (adding #(assoc % :data {"items" (vec (repeat 100 {"e" ""}))}
                              :components [{"id" "root" "component" "List" "children" {"componentId" "row" "path" "/items"}}
                                           {"id" "row" "component" "Column" "children" ["a" "b" "c"]}
                                           {"id" "a" "component" "Text" "text" spots}
                                           {"id" "b" "component" "Text" "text" spots}
                                           {"id" "c" "component" "Text" "text" spots}]))]
      (is (= (str "The surface would read its data more than " surfaces/max-reads " times to be drawn."
                  " Shorten a list, or put fewer ${...} spots in a text.")
             why))
      (is (< (/ (- (System/nanoTime) t0) 1e9) 2.0))))
  (testing "a surface too large to send"
    ;; Each hidden character is shown as ten, in the A2UI message and in
    ;; the text form: 16,000 of them are within what a surface may show
    ;; and far over what one may weigh.
    (let [hidden (apply str (repeat 4000 (String. (Character/toChars 0xE0001))))
          texts (vec (for [i (range 4)] {"id" (str "t" i) "component" "Text" "text" hidden}))
          why (adding #(assoc % :components (into [{"id" "root" "component" "Column" "children" (mapv (fn [t] (get t "id")) texts)}]
                                                  texts)))]
      (is (re-matches #"The surface would be \d+ bytes as a client is sent it, and the most is 128000\. Shorten its text or a list\." why)
          why)))
  (testing "more text than a surface may show in all"
    (let [long (apply str (repeat surfaces/max-text "x"))
          texts (vec (for [i (range 6)] {"id" (str "t" i) "component" "Text" "text" long}))]
      (is (= (str "The surface would show more than " surfaces/max-shown " characters in all. Shorten its text or a list.")
             (adding #(assoc % :components (into [{"id" "root" "component" "Column" "children" (mapv (fn [t] (get t "id")) texts)}]
                                                 texts)))))))
  (testing "a box is bound only where the data holds true or false already"
    (let [box (fn [path] (with-component {"id" "x" "component" "CheckBox" "label" "l" "value" {"@path" path}}))
          why "A CheckBox is bound to a path where the data does not hold true or false. Write false there first, so a tick has somewhere to go."]
      (testing "not where it holds a list, an object, a text or a number, which a tick would replace"
        (doseq [held [[true] {"done" true} "true" 1]]
          (is (= why (adding (comp #(assoc-in % [:data "held"] held) (box "/held")))) (pr-str held)))
        (is (= why (adding (box "/items"))) "the list another box reads from")
        (is (= why (adding (box "/site"))) "the object another box is in"))
      (testing "not where it holds nothing, which a tick would have to make"
        (is (= why (adding (box "/nothing"))))
        (is (= why (adding (box "/items/7/done"))) "an item the list does not have")
        (is (str/starts-with? (adding (box "/items/-")) "Component 6 (x): that is not a path.")
            "the place a write adds to a list is nowhere a component reads")
        (is (= why (adding (box "/when/done"))) "through a text")
        ;; Each of these once made a box that could never be ticked.
        (is (= why (adding (box "/a/a/a/a/a/a/a/a/a/a/a/a"))) "deeper than a data model goes")
        (is (= why (adding #(assoc % :data {"items" [{}]}
                                   :components [{"id" "root" "component" "List"
                                                 "children" {"componentId" "box" "path" "/items"}}
                                                {"id" "box" "component" "CheckBox" "label" "l"
                                                 "value" {"@path" "a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a"}}])))
            "longer than a path may be"))
      (testing "and a write that would take the true or false away is refused"
        (let [w (world a-list)]
          (doseq [op [{:op "data" :path "/site" :value {"paid" "yes"}}
                      {:op "data" :path "/site/paid" :value nil}
                      {:op "data" :path "/" :value {"items" []}}]]
            (is (= why (message #(cards/decide (:cards w) 1 (assoc op :seen 1)))) (pr-str op)))))
      (testing "two boxes on one path are both that box"
        (is (nil? (adding (box "/site/paid")))))
      (testing "every box relay accepts can be ticked and unticked"
        (let [w (world a-list)]
          (doseq [path (keys (:inputs (surfaces/shown (:surface (card w)))))
                  value [true false]]
            (is (nil? (refused #(cards/decide (:cards w) 1 {:op "input" :path path :value value :seen 1})))
                (str path " " value)))))
      (testing "even on a surface at a limit, since every limit is checked with the boxes unticked"
        ;; Each of these once refused the tick: with the text to shorten,
        ;; which the operator cannot do.
        (let [boxes (fn [n] (vec (for [i (range n)] {"id" (str "b" i) "component" "CheckBox" "label" "x"
                                                     "value" {"@path" (str "/b" i)}})))
              surface (fn [filler n]
                        {:title "T"
                         :components (into [{"id" "root" "component" "Column"
                                             "children" (into ["t0" "t1"] (map #(str "b" %) (range n)))}
                                            {"id" "t0" "component" "Text" "text" {"@path" "/pad"}}
                                            {"id" "t1" "component" "Text" "text" {"@path" "/pad"}}]
                                           (boxes n))
                         :data (into {"pad" filler} (for [i (range n)] [(str "b" i) false]))})
              ticks-all (fn [w n]
                          (reduce (fn [w i]
                                    (let [op {:op "input" :path (str "/b" i) :value true :seen (:rev (card w))}]
                                      (is (nil? (refused #(cards/decide (:cards w) 1 op))) (str "box " i))
                                      (op! w op)))
                                  w (range n)))]
          (testing "of characters"
            ;; Two texts and twelve one-letter labels: 16,000 characters.
            (let [w (world (surface (apply str (repeat (/ (- surfaces/max-shown 12) 2) "x")) 12))]
              (is (= surfaces/max-shown (reduce + (map count (surfaces/strings (:surface (card w)))))))
              (let [w (ticks-all w 12)]
                (is (every? true? (map #(get-in (card w) [:surface :data (str "b" %)]) (range 12))))
                (is (not= surfaces/undrawable (surfaces/shown (:surface (card w))))))))
          (testing "of bytes"
            ;; The largest filler relay takes, to the byte, then every box
            ;; ticked. A hidden character is nine bytes as it is sent.
            (let [filler (fn [n] (apply str (repeat n "\u200b")))
                  fits? (fn [n] (nil? (refused #(cards/added {} (assoc (surface (filler n) 12) :kind "surface")))))
                  most (loop [lo 0 hi 7000]
                         (if (= lo hi) lo (let [mid (quot (+ lo hi 1) 2)] (if (fits? mid) (recur mid hi) (recur lo (dec mid))))))]
              (is (< 1000 most 7000))
              (is (str/starts-with? (message #(cards/added {} (assoc (surface (filler (inc most)) 12) :kind "surface")))
                                    "The surface would be "))
              (ticks-all (world (surface (filler most) 12)) 12)))))
      (testing "even when a text that reads the box is at its limit: true is held to the length of false"
        (let [reads-box (fn [n] {:title "T"
                                 :components [{"id" "root" "component" "Column" "children" ["t" "b"]}
                                              {"id" "t" "component" "Text"
                                               "text" {"@call" "formatString" "args" {"value" "${/pad}${/done}"}}}
                                              {"id" "b" "component" "CheckBox" "label" "l" "value" {"@path" "/done"}}]
                                 :data {"done" true "pad" (apply str (repeat n "x"))}})]
          ;; 7,996 and "true" is 8,000 characters, and 8,001 with "false".
          (is (= (str "A text of the surface would be over " surfaces/max-text " characters once its data is read.")
                 (message #(cards/added {} (assoc (reads-box (- surfaces/max-text 4)) :kind "surface")))))
          (let [w (world (reads-box (- surfaces/max-text 5)))]
            (is (nil? (refused #(cards/decide (:cards w) 1 {:op "input" :path "/done" :value false :seen 1}))))))))))

;; --- what a client is sent

(defn- display-strings
  "Every string of a createSurface message that a client draws."
  [{:strs [components]}]
  (mapcat (fn [c]
            (case (get c "component")
              "Text" [(get c "text")]
              "CheckBox" [(get c "label")]
              []))
          components))

(defn- resolved?
  "Whether a createSurface message is one relay resolved: no call and no
  template, children as ids, and a binding only where an input writes.
  Its components are one tree: no id twice, and every component but root
  the child of exactly one."
  [{:strs [components]}]
  (and
   (apply distinct? (map #(get % "id") components))
   (= (frequencies (remove #{"root"} (map #(get % "id") components)))
      (frequencies (mapcat #(concat (get % "children") (some-> (get % "child") vector)) components)))
   (every? (fn [c]
            (let [type (get c "component")
                  bound? #(and (map? %) (= ["@path"] (keys %)) (str/starts-with? (get % "@path") "/"))
                  plain? (fn plain? [v] (cond (map? v) (and (not-any? #{"@path" "@call" "componentId"} (keys v))
                                                            (every? plain? (vals v)))
                                              (sequential? v) (every? plain? v)
                                              :else true))]
              (case type
                ("Row" "Column" "List") (every? string? (get c "children"))
                "CheckBox" (and (string? (get c "label")) (or (boolean? (get c "value")) (bound? (get c "value"))))
                (plain? c))))
           components)))

(defn- strings-in [x]
  (cond (string? x) [x] (map? x) (mapcat strings-in (vals x)) (sequential? x) (mapcat strings-in x) :else []))

(defn- no-hidden? [^String s]
  (not-any? #(text/hidden? %) (iterator-seq (.iterator (.codePoints s)))))

(defn- inert?
  "Whether `out` holds nothing a reader could follow: no http or https
  scheme, and no host of the hostile texts left with its dot."
  [out]
  (and (not (re-find #"(?i)https?://" out))
       (not (re-find #"(?i)(evil|il|kept)\.example" out))))

(def gen-labels (gen/elements [[] ["private"] ["contaminated"] ["contaminated" "private"]]))

(def hostile
  ["plain words" "https://evil.example/x?d=1" "https://ev" "il.example/steal" "evil.example" "evil.example/path"
   "\u001b]8;;https://evil.example\u0007click\u001b]8;;\u0007" "\u202etxt.exe" "zero\u200bwidth" "a\\u{41}b"
   "two\nlines" "<https://kept.example/a>" "https://kept.example/a" "https://kept.example/a." "\r" "\u2028"
   "see https://kept.example/a/more" "tab\there" "🏕️ camp" "x.y/z" "ht" "tps://evil.example" "**bold**"])

(def gen-text (gen/fmap #(str/join " " %) (gen/vector (gen/elements hostile) 0 3)))

(def gen-format
  "A formatString value that is well formed: paths, an escaped ${, and
  pieces an address could be joined from."
  (gen/fmap str/join (gen/vector (gen/elements ["${/a}" "${/b}" "${/items/0/text}" "${/nothing}" "\\${" "see "
                                                "https://ev" "il.example/x " "https://kept.example/a" " " "."])
                                 0 6)))

(def gen-surface
  "A surface holding hostile text everywhere text can go: a literal, a
  path, a formatString, a row, and the labels of a templated list."
  (gen/let [t1 gen-text
            a gen-text
            b gen-text
            fmt gen-format
            items (gen/vector (gen/tuple gen-text gen/boolean) 0 4)
            title (gen/elements ["T" "see https://evil.example/t" "zero\u200bwidth"])]
    {:title title
     :components [{"id" "root" "component" "Column" "children" ["lit" "bound" "fmt" "row" "items"]}
                  {"id" "lit" "component" "Text" "text" t1}
                  {"id" "bound" "component" "Text" "variant" "caption" "text" {"@path" "/a"}}
                  {"id" "fmt" "component" "Text" "text" {"@call" "formatString" "args" {"value" fmt}}}
                  {"id" "row" "component" "Row" "children" ["left" "right"]}
                  {"id" "left" "component" "Text" "text" {"@path" "/a"}}
                  {"id" "right" "component" "Text" "text" {"@path" "/b"}}
                  {"id" "items" "component" "List" "children" {"componentId" "item" "path" "/items"}}
                  {"id" "item" "component" "Card" "child" "box"}
                  {"id" "box" "component" "CheckBox" "label" {"@path" "text"} "value" {"@path" "done"}}]
     :data {"a" a "b" b
            "items" (vec (for [[t done] items] {"text" t "done" done}))}}))

(def a-surface-is-safe-to-show
  (prop/for-all [args gen-surface
                 labels gen-labels
                 live (gen/elements [#{} #{"https://kept.example/a"}])]
    (let [c (card (world args labels))
          w (wire c live)
          msg (surface-message w)
          shown (concat (display-strings msg) (str/split-lines (get w "text")))
          dirty? (text/contaminated? labels)]
      (and (seq shown)
           (every? no-hidden? shown)
           ;; In a surface an outsider may have shaped, every link is
           ;; broken, a live one too.
           (or (not dirty?) (every? inert? shown))
           (= dirty? (get-in w ["surface" "outsider"]))
           ;; What a client draws is each of the surface's strings, made
           ;; safe with the surface's labels, in order.
           (= (display-strings msg)
              (map #(text/safe-text (cards/surface-labels (:surface c)) %) (surfaces/strings (:surface c))))
           ;; The live links travel beside the text: only ones relay
           ;; keeps live, only for outside text, each in the form some
           ;; string the client holds shows it in, and each of them when
           ;; a string holds it as a word of its own.
           (let [listed (get w "links")
                 held (cons (get-in w ["title" "text"]) (display-strings msg))]
             (and (every? #(contains? live (get % "address")) listed)
                  (every? (fn [{:strs [shown]}] (some #(str/includes? % shown) held)) listed)
                  (or dirty? (empty? listed))
                  (or (not dirty?) (empty? live)
                      (not (some #(some #{"https://kept.example/a"} (str/split % #"\s+")) (surfaces/strings (:surface c))))
                      (= ["https://kept.example/a"] (map #(get % "address") listed)))))
           (resolved? msg)
           ;; The data model holds what the inputs read, true and false,
           ;; and none of the role's text.
           (empty? (strings-in (get msg "dataModel")))
           (= w (json/parse-string (json/generate-string w)))))))

(deftest every-string-of-a-surface-is-made-safe-whole
  (check 300 a-surface-is-safe-to-show))

(deftest a-surfaces-strings-are-the-ones-it-shows
  (is (= ["For Saturday" "Tent" "Bags" "Site paid for"] (surfaces/strings (:surface (card (world a-list))))))
  (testing "a text that formatString joins is one string, which the role wrote nowhere"
    (let [w (world {:title "T"
                    :components [{"id" "root" "component" "Card" "child" "row"}
                                 {"id" "row" "component" "Row" "children" ["joined" "plain"]}
                                 {"id" "joined" "component" "Text"
                                  "text" {"@call" "formatString" "args" {"value" "${/a}${/b}"}}}
                                 {"id" "plain" "component" "Text" "text" "beside it"}]
                    :data {"a" "https://ev" "b" "il.example/x"}})]
      (is (= ["https://evil.example/x" "beside it"] (surfaces/strings (:surface (card w)))))))
  (testing "a surface that cannot be drawn has the one line that says so"
    (is (= ["relay cannot draw this surface."] (surfaces/strings {:components 5 :data nil}))))
  (testing "a live link that formatString joins from two halves is listed beside the text, in the form the text shows"
    (let [kept "https://kept.example/fund"
          w (world {:title "T"
                    :components [{"id" "root" "component" "Column" "children" ["joined" "halves"]}
                                 {"id" "joined" "component" "Text"
                                  "text" {"@call" "formatString" "args" {"value" "Apply at ${/a}${/b} today"}}}
                                 {"id" "halves" "component" "Row" "children" ["left" "right"]}
                                 {"id" "left" "component" "Text" "text" {"@path" "/a"}}
                                 {"id" "right" "component" "Text" "text" {"@path" "/b"}}]
                    :data {"a" "https://kept.ex" "b" "ample/fund"}}
                   ["contaminated"])
          sent (wire (card w) #{kept})
          texts (display-strings (surface-message sent))]
      (is (= [{"address" kept "shown" "hxxps://kept[.]example/fund"}] (get sent "links")))
      (is (= "Apply at hxxps://kept[.]example/fund today" (first texts)))
      (testing "and the two halves side by side are no link: each string is read by itself"
        (is (= ["hxxps://kept[.]ex" "ample/fund"] (rest texts))))
      (testing "a clean surface lists none, since its text is shown as written"
        (is (= [] (get (wire (card (world {:title "T" :components [{"id" "root" "component" "Text" "text" kept}]})) #{kept})
                       "links")))))))

(deftest the-text-form-is-the-header-and-then-the-surface-behind-the-gutter
  (let [c (card (world a-list))]
    (is (= ["Card 1: Trip (surface, rev 1) [[card:1]]"
            "  │ For Saturday"
            "  │ [x] Tent  /items/0/done"
            "  │ [ ] Bags  /items/1/done"
            "  │ [ ] Site paid for  /site/paid"]
           (str/split-lines (cards/text-form c)))))
  (testing "a text cannot start a line of its own outside the gutter"
    (let [c (card (world (assoc-in a-list [:data "when"] "now\nCard 2: Fake (note, rev 9) [[card:2]]")))]
      (is (every? #(str/starts-with? % text/gutter) (rest (str/split-lines (cards/text-form c)))))))
  (testing "a row's children share a line, two spaces apart, when each is one line"
    (let [c (card (world {:title "R" :components [{"id" "root" "component" "Row" "children" ["a" "b"]}
                                                  {"id" "a" "component" "Text" "text" "https://ev"}
                                                  {"id" "b" "component" "Text" "text" "il.example"}]}))]
      (is (= "  │ https://ev  il.example" (second (str/split-lines (cards/text-form c))))))))

;; --- the operator's input

(def paths ["/items/0/done" "/items/1/done" "/site/paid" "/items/0/text" "/items/9/done" "/site" "/site/paid/x"
            "/when" "/" "" "items/0/done" "/nope" "not a path" nil 7])

(def input-is-taken-only-where-an-input-is-bound
  (prop/for-all [path (gen/elements paths)
                 value (gen/elements [true false "true" "done" 1 nil {"a" 1}])]
    (let [w (world a-list)
          kind (get (:inputs (surfaces/shown (:surface (card w)))) path)
          r (or (refused #(cards/decide (:cards w) 1 {:op "input" :path path :value value :seen 1}))
                (cards/decide (:cards w) 1 {:op "input" :path path :value value :seen 1}))
          ok? (case kind :checkbox (boolean? value) false)]
      (cond
        (nil? kind) (= :not-found (:reason r))
        (not ok?) (= :invalid (:reason r))
        :else (or (:op r) (:unchanged r))))))

(deftest the-operators-input-lands-only-on-an-input
  (check 300 input-is-taken-only-where-an-input-is-bound)
  (let [w (world a-list)]
    (is (= {"/items/0/done" :checkbox "/items/1/done" :checkbox "/site/paid" :checkbox}
           (:inputs (surfaces/shown (:surface (card w))))))
    (testing "a box"
      (let [w (op! w {:op "input" :path "/items/1/done" :value true :seen :now})]
        (is (= [true true] (map #(get % "done") (get-in (card w) [:surface :data "items"]))))
        (is (= 2 (:rev (card w))))))
    (testing "a client is sent the value at every box's path, and a tick changes that one alone"
      (is (= {"items" [{"done" true} {"done" false}] "site" {"paid" false}}
             (get (surface-message (wire (card w))) "dataModel")))
      (let [w (op! w {:op "input" :path "/site/paid" :value true :seen :now})]
        (is (= (assoc-in (:data a-list) ["site" "paid"] true) (get-in (card w) [:surface :data])))))
    (testing "the same value again changes nothing"
      (is (= {:unchanged "That box is already ticked." :labels []}
             (cards/decide (:cards w) 1 {:op "input" :path "/items/0/done" :value true :seen 1})))
      (is (= {:unchanged "That box is already unticked." :labels []}
             (cards/decide (:cards w) 1 {:op "input" :path "/site/paid" :value false :seen 1}))))
    (testing "wrong values are named"
      (is (= "A checkbox takes true or false."
             (message #(cards/decide (:cards w) 1 {:op "input" :path "/items/0/done" :value "yes" :seen 1}))))
      (is (= "Nothing on card 1 takes input at that path."
             (message #(cards/decide (:cards w) 1 {:op "input" :path "/when" :value true :seen 1})))))
    (testing "an input with a literal value is not the operator's to change"
      (let [w (world {:title "L" :components [{"id" "root" "component" "CheckBox" "label" "done" "value" true}]})]
        (is (= {} (:inputs (surfaces/shown (:surface (card w))))))
        (is (true? (get (first (get (surface-message (wire (card w))) "components")) "value")))))))

(deftest a-surfaces-ops-need-the-rev-the-caller-read
  (let [w (-> (world a-list) (op! {:op "input" :path "/items/1/done" :value true :seen :now}))
        decide (fn [op] (message #(cards/decide (:cards w) 1 op)))]
    (is (= 2 (:rev (card w))))
    (doseq [op [{:op "input" :path "/items/0/done" :value false}
                {:op "data" :path "/when" :value "Sunday"}
                {:op "components" :components (:components a-list)}]]
      (is (= (str (:op op) " needs seen: the card's rev when you read it.") (decide op)))
      (is (= "Card 1 has changed since rev 1. Read the card again before you change it."
             (decide (assoc op :seen 1))))
      (is (= "seen must be a rev you read. Read the card again for it." (decide (assoc op :seen 3)))))
    (testing "a refusal for seen never says which rev the card is at"
      ;; Told the rev, a role sent the same write again without reading,
      ;; and it landed on the item that had moved into that place.
      (let [w (reduce (fn [w _] (op! w {:op "data" :path "/when" :value (str "day " (:rev (card w))) :seen :now}))
                      (world a-list) (range 40))
            rev (:rev (card w))]
        (is (= 41 rev))
        (doseq [op ["input" "data" "components"]
                seen [1 7 40 42 99]
                :let [said (message #(cards/decide (:cards w) 1 {:op op :path "/items/0/done" :value true :seen seen
                                                                  :components (:components a-list)}))]]
          (is (some? said) (str op " " seen))
          (is (not (str/includes? said (str rev))) said))))
    (testing "a write against the list as it was would land on another item, so it is refused"
      (let [w (-> (world a-list)
                  (op! {:op "data" :path "/items/0" :value nil :seen :now}))]
        (is (= [{"text" "Bags" "done" false}] (get-in (card w) [:surface :data "items"])))
        (is (some? (refused #(cards/decide (:cards w) 1 {:op "input" :path "/items/0/done" :value true :seen 1}))))))
    (testing "the other kinds' ops are not a surface's, and a surface's are not theirs"
      (is (= "tick is not an op on a surface. A surface's ops are archive, components, data, input, press, restore, retitle."
             (decide {:op "tick" :item 1})))
      (let [e (event 1 "card/added" [] (cards/added {} {:kind "checklist" :title "L" :items ["a"]}))]
        (is (str/starts-with? (message #(cards/decide (cards/add {} e) 1 {:op "input" :path "/a" :value true :seen 1}))
                              "input is not an op on a checklist."))))))

;; --- what a role writes

(deftest a-role-writes-data-by-path-and-replaces-components-whole
  (let [w (world a-list)
        data (fn [w] (get-in (card w) [:surface :data]))]
    (testing "a write, an addition to a list, and a removal"
      (let [w (-> w (op! {:op "data" :path "/when" :value "Sunday" :seen :now})
                  (op! {:op "data" :path "/items/-" :value {:text "Stove" :done false} :seen :now})
                  (op! {:op "data" :path "/items/0" :value nil :seen :now})
                  (op! {:op "data" :path "/extra/deep" :value [1 2] :seen :now}))]
        (is (= {"when" "Sunday"
                "items" [{"text" "Bags" "done" false} {"text" "Stove" "done" false}]
                "site" {"paid" false}
                "extra" {"deep" [1 2]}}
               (data w)))
        (is (= 5 (:rev (card w))))))
    (testing "a write at / replaces the model"
      (is (= {"items" [] "site" {"paid" true}}
             (data (op! w {:op "data" :path "/" :value {"items" [] "site" {"paid" true}} :seen :now})))))
    (testing "a list is never made by a write, so nothing is written under the name - or a number by mistake"
      ;; /list/- on data with no list once made {"list" {"-" ...}}, and
      ;; the next write there replaced the first.
      (let [decide (fn [op] (message #(cards/decide (:cards w) 1 (assoc op :seen 1))))]
        (is (= "Nothing can be written at that path: there is no list there to add to. Write the list first."
               (decide {:op "data" :path "/list/-" :value "first"})))
        (is (= "Nothing can be written at that path: there is no list there to add to. Write the list first."
               (decide {:op "data" :path "/site/-" :value "x"}))
            "nor to an object")
        (is (= "Nothing can be written at that path: there is no list there to hold that position. Write the list first."
               (decide {:op "data" :path "/other/0" :value "zero"})))
        (is (= "A key in a data model is 1 to 64 letters, digits, underscores or hyphens, and not a - by itself."
               (decide {:op "data" :path "/site" :value {"-" 1}})))
        (is (= "data needs a path from the top of the data model, such as /items/0/done, or / for all of it."
               (decide {:op "data" :path "/-/x" :value 1})))
        (testing "a list that is there is added to, and an object takes a key that looks like a number"
          (let [w (-> w (op! {:op "data" :path "/list" :value [] :seen :now})
                      (op! {:op "data" :path "/list/-" :value "first" :seen :now})
                      (op! {:op "data" :path "/list/-" :value "second" :seen :now})
                      (op! {:op "data" :path "/site/2026" :value "booked" :seen :now}))]
            (is (= ["first" "second"] (get (data w) "list")))
            (is (= {"paid" false "2026" "booked"} (get (data w) "site")))))))
    (testing "what cannot be written says why"
      (let [decide (fn [op] (message #(cards/decide (:cards w) 1 (assoc op :seen 1))))]
        (is (= "data needs a value. A null value removes what is at the path." (decide {:op "data" :path "/when"})))
        (is (= "data needs a path from the top of the data model, such as /items/0/done, or / for all of it."
               (decide {:op "data" :path "when" :value 1})))
        (is (= "data needs a path from the top of the data model, such as /items/0/done, or / for all of it."
               (decide {:op "data" :path "/a b" :value 1})))
        (is (= "Nothing can be written at that path: a list on the way has no such item."
               (decide {:op "data" :path "/items/5/done" :value true})))
        (is (= "Nothing can be written at that path: it runs through a value that is neither a list nor an object."
               (decide {:op "data" :path "/when/x" :value true})))
        (is (= "The whole data model cannot be removed. Write {} at / to empty it."
               (decide {:op "data" :path "/" :value nil})))
        (is (= "A surface's data is a JSON object." (decide {:op "data" :path "/" :value [1]})))
        (is (= "A number in a data model is a whole number. Write any other as text, such as \"12.5\"."
               (decide {:op "data" :path "/when" :value 1.5M})))))
    (testing "writing what is there, or removing what is not, changes nothing"
      (is (= {:unchanged "Card 1's data already holds that." :labels []}
             (cards/decide (:cards w) 1 {:op "data" :path "/when" :value "Saturday" :seen 1})))
      (is (= {:unchanged "Card 1's data already holds that." :labels []}
             (cards/decide (:cards w) 1 {:op "data" :path "/gone" :value nil :seen 1}))))
    (testing "components are replaced whole and checked like new ones"
      (let [cs [{"id" "root" "component" "Text" "text" {"@path" "/when"}}]
            w2 (op! w {:op "components" :components cs :seen :now})]
        (is (= cs (get-in (card w2) [:surface :components])))
        (is (= ["Card 1: Trip (surface, rev 2) [[card:1]]" "  │ Saturday"] (str/split-lines (cards/text-form (card w2)))))
        (is (= {:unchanged "Card 1 already has those components." :labels []}
               (cards/decide (:cards w) 1 {:op "components" :components (:components a-list) :seen 1})))
        (is (str/includes? (message #(cards/decide (:cards w) 1 {:op "components" :seen 1
                                                                 :components [{"id" "root" "component" "TextField"}]}))
                           "relay's catalog has no such component"))))))

(def gen-key (gen/elements ["a" "b" "items" "2026-01" "x_y" "0"]))

(def gen-value
  (gen/recursive-gen
   (fn [inner] (gen/one-of [(gen/vector inner 0 3) (gen/map gen-key inner {:max-elements 3})]))
   (gen/one-of [gen/boolean (gen/choose -5 5) (gen/elements ["s" "" "t.ex"])])))

(def a-write-reads-back
  (prop/for-all [data (gen/map gen-key gen-value {:max-elements 4})
                 parts (gen/vector (gen/one-of [gen-key (gen/fmap str (gen/choose 0 3))]) 1 4)
                 v gen-value]
    (let [p (surfaces/path (str "/" (str/join "/" parts)))
          r (or (refused #(surfaces/written data p v)) (surfaces/written data p v))]
      (or (:reason r)
          (and (= v (surfaces/at r parts))
               ;; The fold applies a recorded write as decide did.
               (= r (surfaces/folded data p v))
               ;; Removing it again leaves nothing there when it was a
               ;; key, and is the same in the fold.
               (= (surfaces/removed r p) (surfaces/folded r p nil))
               (or (vector? (surfaces/at r (butlast parts)))
                   (nil? (surfaces/at (surfaces/removed r p) parts))))))))

(deftest a-data-write-reads-back
  (check 500 a-write-reads-back))

;; --- labels

(def link "https://evil.example/x")

(defn- tainted-surface [n]
  {:title "T"
   :components [{"id" "root" "component" "Column" "children" ["lit" "bound" "box"]}
                {"id" "lit" "component" "Text" "text" (str "literal " n " " link)}
                {"id" "bound" "component" "Text" "text" {"@path" "/label"}}
                {"id" "box" "component" "CheckBox" "label" "done" "value" {"@path" "/done"}}]
   :data {"label" (str "data " n " " link) "done" false}})

(def gen-write
  (gen/tuple (gen/elements [:components :data-whole :data-path :input :retitle]) gen-labels))

(def a-surfaces-taint-is-that-of-every-write-still-in-it
  (prop/for-all [first-labels gen-labels
                 writes (gen/vector gen-write 0 12)]
    (let [[w model] (reduce
                     (fn [[w m] [i [kind labels]]]
                       (let [n (inc i)]
                         (case kind
                           :components [(op! w {:op "components" :components (:components (tainted-surface n)) :seen :now} labels)
                                        (assoc m :components labels)]
                           :data-whole [(op! w {:op "data" :path "/" :value (:data (tainted-surface n)) :seen :now} labels)
                                        (assoc m :data [labels])]
                           :data-path [(op! w {:op "data" :path "/label" :value (str "path " n " " link) :seen :now} labels)
                                       (update m :data conj labels)]
                           :input [(op! w {:op "input" :path "/done" :seen :now
                                           :value (not (get-in (card w) [:surface :data "done"]))} labels)
                                   m]
                           :retitle [(op! w {:op "retitle" :text (str "T" n) :seen :now} labels)
                                     (assoc m :title labels)])))
                     [(world (tainted-surface 0) first-labels)
                      {:title first-labels :components first-labels :data [first-labels]}]
                     (map-indexed vector writes))
          c (card w)
          held (apply core/union (:components model) (:data model))
          dirty? (text/contaminated? held)
          wr (wire c)
          shown (display-strings (surface-message wr))]
      (and (= (inc (count writes)) (count (:events w)))
           (= (core/content-labels held (:title model)) (core/card-labels c))
           (= dirty? (get-in wr ["surface" "outsider"]))
           ;; No string is shown whole unless every write still in the
           ;; surface was clean.
           (= (if dirty? 0 2) (count (filter #(str/includes? % link) shown)))
           (= (if dirty? 2 0) (count (filter #(str/includes? % "hxxps://evil[.]example/x") shown)))))))

(deftest a-surface-is-as-tainted-as-every-write-still-in-it
  (check 300 a-surfaces-taint-is-that-of-every-write-still-in-it)
  (testing "replacing the components clean does not clean data an outsider shaped"
    (let [w (-> (world (tainted-surface 0) ["contaminated"])
                (op! {:op "components" :components (:components (tainted-surface 1)) :seen :now} []))]
      (is (= ["contaminated" "private"] (core/card-labels (card w))))
      (is (true? (get-in (wire (card w)) ["surface" "outsider"])))
      (testing "replacing the data too does, and the title keeps its own"
        (let [w (op! w {:op "data" :path "/" :value (:data (tainted-surface 2)) :seen :now} [])]
          (is (false? (get-in (wire (card w)) ["surface" "outsider"])))
          (is (true? (get-in (wire (card w)) ["title" "outsider"])))
          (is (= ["private"] (core/card-labels (card (op! w {:op "retitle" :text "Clean" :seen :now} [])))))))))
  (testing "an input adds no labels, whoever sends it"
    (let [w (-> (world (tainted-surface 0) [])
                (op! {:op "input" :path "/done" :value true :seen :now} ["contaminated"]))]
      (is (true? (get-in (card w) [:surface :data "done"])))
      (is (= ["private"] (core/card-labels (card w)))))))

;; --- the fold

(def gen-surface-op
  "An op on a-list's surface, mostly one that is decided."
  (gen/let [op (gen/frequency [[5 (gen/return "input")] [5 (gen/return "data")] [2 (gen/return "components")]
                               [1 (gen/elements ["retitle" "archive" "restore" "tick" "bogus"])]])
            path (gen/elements paths)
            value (gen/one-of [(gen/elements [true false "done" nil 1.5M {"text" "New" "done" false}
                                              [{"text" "Only" "done" true}] {"items" []} "text https://evil.example"])
                               gen-value])
            components (gen/elements [(:components a-list)
                                      [{"id" "root" "component" "Text" "text" {"@path" "/when"}}]
                                      [{"id" "root" "component" "Button"}]
                                      (:components (tainted-surface 3))])
            seen (gen/frequency [[8 (gen/return :now)] [1 (gen/return nil)] [1 (gen/choose 0 30)]])
            labels gen-labels]
    [(cond-> {:op op :path path :value value :components components :text "Title" :item 1}
       seen (assoc :seen seen))
     labels]))

(def gen-surface-world
  (gen/let [steps (gen/vector gen-surface-op 0 25)
            labels gen-labels]
    (reduce (fn [w [op ls]] (op! w op ls)) (world a-list labels) steps)))

(def the-cards-are-a-fold-of-the-events
  (prop/for-all [w gen-surface-world]
    (let [c (card w)
          wr (wire c)]
      (and (= (:cards w) (refold (:events w)))
           ;; As the log gives them back: member names as keywords.
           (= (:cards w) (refold (mapv #(json/parse-string (json/generate-string %) true) (:events w))))
           ;; Whatever was recorded can be drawn, shown and sent.
           (not= surfaces/undrawable (surfaces/shown (:surface c)))
           ;; And every box on it can be ticked and unticked.
           (or (not (cards/active? c))
               (every? (fn [path]
                         (every? #(nil? (refused (fn [] (cards/decide (:cards w) 1 {:op "input" :path path :value %
                                                                                    :seen (:rev c)}))))
                                 [true false]))
                       (keys (:inputs (surfaces/shown (:surface c))))))
           (every? #(str/starts-with? % text/gutter) (rest (str/split-lines (cards/text-form c))))
           (str/includes? (first (str/split-lines (cards/text-form c))) (str ", rev " (:rev c) ")"))
           (resolved? (surface-message wr))
           (= wr (json/parse-string (json/generate-string wr)))))))

(deftest a-surface-is-a-fold-of-its-events
  (check 300 the-cards-are-a-fold-of-the-events))

(def gen-junk
  "Any JSON at all, as an event of a log someone edited might hold."
  (gen/recursive-gen
   (fn [inner] (gen/one-of [(gen/vector inner 0 3)
                            (gen/map (gen/elements [:id :component :children :child :text :label :value :path :op
                                                    (keyword "@path") (keyword "@call") :args :componentId])
                                     inner {:max-elements 4})]))
   (gen/one-of [gen/boolean (gen/choose -3 3) (gen/return nil) (gen/return 1.5)
                (gen/elements ["root" "Text" "Column" "List" "CheckBox" "/a" "a" "" "formatString" "@index"
                               "components" "data" "input" "x\u001b]8;;https://evil.example\u0007"])])))

(defn- json-strings
  "Every string in a JSON value, member names included."
  [x]
  (cond (string? x) [x]
        (map? x) (concat (keys x) (mapcat json-strings (vals x)))
        (sequential? x) (mapcat json-strings x)
        :else []))

(def nothing-recorded-stops-the-fold-or-the-forms
  ;; A surface's three ops, holding anything. An op this relay does not
  ;; know is another matter: like an event type it does not know, it
  ;; stops the fold, since only a newer relay can have written it.
  (prop/for-all [components gen-junk
                 data gen-junk
                 ops (gen/vector (gen/tuple (gen/elements ["data" "input" "components"]) gen-junk gen-junk gen-junk) 0 4)]
    (let [e (event 1 "card/added" ["contaminated"] {:card-kind "surface" :title "Old" :next {}
                                                   :components components :data data})
          cs (reduce (fn [cs [i [op path value components]]]
                       (cards/change cs (event (+ 2 i) "card/changed" []
                                               {:card 1 :op {:op op :path path :value value :components components}})))
                     (cards/add {} e)
                     (map-indexed vector ops))
          c (get cs 1)
          w (wire c)]
      (and (string? (cards/text-form c))
           (every? #(str/starts-with? % text/gutter) (rest (str/split-lines (cards/text-form c))))
           (= w (json/parse-string (json/generate-string w)))
           (resolved? (surface-message w))
           ;; Nothing hidden reaches a client in any string of the
           ;; message, whatever the event held: not in an id, a word or
           ;; a path either.
           (every? no-hidden? (json-strings (surface-message w)))
           (vector? (surfaces/source-lines (:surface c)))
           ;; and an op on it is decided or refused, never an error
           (some? (or (refused #(cards/decide cs 1 {:op "input" :path "/a" :value true :seen (:rev c)}))
                      (cards/decide cs 1 {:op "input" :path "/a" :value true :seen (:rev c)})))
           (some? (or (refused #(cards/decide cs 1 {:op "data" :path "/a" :value true :seen (:rev c)}))
                      (cards/decide cs 1 {:op "data" :path "/a" :value true :seen (:rev c)})))))))

(deftest whatever-a-log-holds-the-fold-and-the-forms-go-on
  (check 300 nothing-recorded-stops-the-fold-or-the-forms))

(def gen-mistaken-surface
  "a-list with a few of its properties replaced by anything at all, or a
  property it does not have added: what a model's mistake looks like."
  (gen/let [changes (gen/vector (gen/tuple (gen/choose 0 4)
                                           (gen/elements ["id" "component" "children" "child" "text" "label" "value"
                                                          "variant" "direction" "axis" "args" "@call" "@path"])
                                           gen-junk)
                                1 3)
            data (gen/frequency [[3 (gen/return (:data a-list))] [1 gen-junk]])]
    {:components (reduce (fn [cs [i k v]] (assoc-in cs [i k] (surfaces/plain v))) (:components a-list) changes)
     :data data}))

(def a-check-accepts-or-refuses
  (prop/for-all [{:keys [components data]} gen-mistaken-surface
                 path gen-junk
                 value gen-junk
                 op (gen/elements ["components" "data" "input" "retitle" "archive"])]
    (let [w (world a-list)]
      ;; `refused` rethrows whatever is not a refusal, which fails this.
      (refused #(cards/added {} {:kind "surface" :title "T" :components components :data data}))
      (refused #(cards/decide (:cards w) 1 {:op op :components components :path path :value value :text "T" :seen 1}))
      (refused #(cards/decide (:cards w) 1 {:op "data" :path "/items" :value data :seen 1}))
      true)))

(deftest a-mistaken-surface-is-refused-not-an-error
  (check 500 a-check-accepts-or-refuses))

(deftest a-surface-another-relay-recorded-never-stops-the-fold
  ;; Nothing is checked when an event is folded, so an event that no
  ;; longer fits the limits is shown as a card that cannot be drawn.
  (let [e (event 1 "card/added" [] {:card-kind "surface" :title "Old" :next {}
                                    :components [{:id "root" :component "Hologram" :depth 3}]
                                    :data {:a 1}})
        cs (-> (cards/add {} e)
               (cards/change (event 2 "card/changed" [] {:card 1 :op {:op "data" :path "/a/b/c" :value 1}}))
               (cards/change (event 3 "card/changed" [] {:card 1 :op {:op "input" :path "not a path" :value true}}))
               (cards/change (event 4 "card/changed" [] {:card 1 :op {:op "data" :path "/" :value [1]}})))
        c (get cs 1)]
    (is (= {"a" 1} (get-in c [:surface :data])))
    (testing "a replacement of the data that could not be applied leaves the data's labels as they were"
      ;; Clean components show /url, which a session with outside text
      ;; wrote. A recorded write of a list at / changes nothing, so it
      ;; must not make the surface clean.
      (let [added (event 1 "card/added" [] {:card-kind "surface" :title "T" :next {}
                                            :components [{:id "root" :component "Text" :text {(keyword "@path") "/url"}}]
                                            :data {:url "x"}})
            cs (-> (cards/add {} added)
                   (cards/change (event 2 "card/changed" ["contaminated"]
                                        {:card 1 :op {:op "data" :path "/url" :value "https://evil.example/private"}})))
            shown (fn [cs] (let [w (wire (get cs 1))]
                             [(get-in w ["surface" "outsider"])
                              (get (first (get (surface-message w) "components")) "text")]))]
        (is (= [true "hxxps://evil[.]example/private"] (shown cs)))
        (doseq [value [[] nil "x" 7]]
          (is (= [true "hxxps://evil[.]example/private"]
                 (shown (cards/change cs (event 3 "card/changed" [] {:card 1 :op {:op "data" :path "/" :value value}}))))
              (pr-str value)))
        (testing "and one that was applied does replace them"
          (is (= [false "https://ok.example"]
                 (shown (cards/change cs (event 3 "card/changed" [] {:card 1 :op {:op "data" :path "/"
                                                                                  :value {:url "https://ok.example"}}}))))))))
    (is (= ["Card 1: Old (surface, rev 4) [[card:1]]" "  │ relay cannot draw this surface."]
           (str/split-lines (cards/text-form c))))
    (is (= [{"id" "root" "component" "Text" "text" "relay cannot draw this surface."}]
           (get (surface-message (wire c)) "components")))
    (testing "a word or an id the catalog does not have is never sent on from a recorded surface"
      (let [recorded (fn [components]
                       (surface-message (wire (get (cards/add {} (event 1 "card/added" [] {:card-kind "surface" :title "T" :next {}
                                                                                           :components components :data {}}))
                                                   1))))]
        (is (= [{"id" "root" "component" "Text" "text" "t"}]
               (get (recorded [{:id "root" :component "Text" :text "t" :variant "\u001b[31mRED https://evil.example"
                                :extra "\u001b]8;;x"}])
                    "components")))
        (is (= [{"id" "root" "component" "Text" "text" "relay cannot draw this surface."}]
               (get (recorded [{:id "root" :component "Column" :children ["a b\u001b"]}
                               {:id "a b\u001b" :component "Text" :text "t"}])
                    "components")))
        (testing "nor a component twice, which would be one id twice"
          (doseq [components [[{:id "root" :component "Column" :children ["a" "a"]}
                               {:id "a" :component "Text" :text "t"}]
                              [{:id "root" :component "Column" :children ["a" "b"]}
                               {:id "a" :component "Card" :child "c"}
                               {:id "b" :component "Card" :child "c"}
                               {:id "c" :component "Text" :text "t"}]]]
            (is (= [{"id" "root" "component" "Text" "text" "relay cannot draw this surface."}]
                   (get (recorded components) "components")))))))))

;; --- the catalog

(deftest the-catalog-file-is-the-catalog
  (is (= (str (strict/pretty (surfaces/catalog-document)) "\n") (slurp "resources/a2ui/relay_catalog.json"))
      "resources/a2ui/relay_catalog.json is not what relay.surfaces/catalog-document gives. Write it again."))

(deftest the-catalog-says-what-relay-checks
  (let [told (surfaces/catalog-text)
        doc (surfaces/catalog-document)]
    (doseq [[type {:keys [props]}] surfaces/catalog]
      (is (str/includes? told (str "  " type ": ")) type)
      (is (= (set (cons "component" (map first props))) (set (keys (get-in doc ["components" type "properties"])))) type)
      (doseq [[k] props] (is (str/includes? told k) (str type " " k))))
    (doseq [f surfaces/functions] (is (str/includes? told f) f))
    (is (= surfaces/catalog-id (get doc "catalogId")))
    (testing "every component of the catalog can be written, drawn, shown and sent"
      (doseq [[type c] {"Text" {"text" "t"}
                        "Row" {"children" []} "Column" {"children" []} "List" {"children" []}
                        "Card" {"child" "leaf"} "Divider" {}
                        "CheckBox" {"label" "l" "value" false}
                        "Button" {"child" "leaf" "action" {"event" {"name" "go"}}}}]
        (let [w (world {:title type
                        :components (cond-> [(merge {"id" "root" "component" type} c)]
                                      (#{"Card" "Button"} type) (conj {"id" "leaf" "component" "Text" "text" "t"}))})]
          (is (= type (get (first (get (surface-message (wire (card w))) "components")) "component")) type)
          (is (string? (cards/text-form (card w))) type)))
      (is (= (set (map first surfaces/catalog)) #{"Text" "Row" "Column" "List" "Card" "Divider" "CheckBox" "Button"})
          "A component new to the catalog needs a case above."))))

(def what-relay-accepts-weighs-no-more-than-it-measured
  ;; Text of one kind repeated: plain, doubled by JSON, tripled or more
  ;; by being shown escaped, wide in UTF-8, lengthened by breaking its
  ;; links, or kept whole as a live link.
  (prop/for-all [unit (gen/elements ["x" "\"" "\\" "é" "\u200b" (String. (Character/toChars 0xE0001)) "🏕" "a.bc "
                                     "https://x.example/a " "https://localhost/a " "\n"])
                 n (gen/choose 1 8000)
                 copies (gen/choose 1 6)
                 labels gen-labels]
    (let [text (apply str (repeat n unit))
          texts (vec (for [i (range copies)] {"id" (str "t" i) "component" "Text" "text" text}))
          args {:title "W" :components (into [{"id" "root" "component" "Column" "children" (mapv #(get % "id") texts)}]
                                             texts)}]
      (or (some? (message #(cards/added {} (assoc args :kind "surface"))))
          (let [sent (wire (card (world args labels)) #{"https://x.example/a" "https://localhost/a"})]
            (<= (alength (.getBytes ^String (strict/generate sent) "UTF-8"))
                (+ (* 5/4 cards/max-sent) 4000)))))))

(deftest a-surface-relay-accepts-fits-what-a-client-reads
  (check 40 what-relay-accepts-weighs-no-more-than-it-measured))

(def every-box-of-an-accepted-surface-ticks
  ;; Surfaces up to and at the limits: text of every weight, texts that
  ;; read the boxes, and boxes ticked and unticked to begin with.
  (prop/for-all [unit (gen/elements ["x" "\"" "é" "\u200b" "a.bc " "https://x.example/a "])
                 n (gen/choose 1 8000)
                 copies (gen/choose 1 4)
                 states (gen/vector gen/boolean 1 8)
                 reads? gen/boolean
                 order (gen/shuffle (range 8))]
    (let [k (count states)
          text (apply str (repeat n unit))
          args {:title "B"
                :components (vec (concat
                                  [{"id" "root" "component" "Column"
                                    "children" (vec (concat (map #(str "t" %) (range copies)) ["said"] (map #(str "b" %) (range k))))}
                                   {"id" "said" "component" "Text"
                                    "text" (if reads?
                                             {"@call" "formatString"
                                              "args" {"value" (apply str (for [i (range k)] (str "${/b" i "} ")))}}
                                             "boxes")}]
                                  (for [i (range copies)] {"id" (str "t" i) "component" "Text" "text" {"@path" "/pad"}})
                                  (for [i (range k)] {"id" (str "b" i) "component" "CheckBox" "label" "box"
                                                      "value" {"@path" (str "/b" i)}})))
                :data (into {"pad" text} (map-indexed (fn [i v] [(str "b" i) v]) states))}]
      (or (some? (message #(cards/added {} (assoc args :kind "surface"))))
          ;; Flip every box, in some order, then flip each back.
          (let [flips (concat (filter #(< % k) order) (filter #(< % k) (reverse order)))
                [w ok?] (reduce (fn [[w ok?] i]
                                  (let [op {:op "input" :path (str "/b" i) :seen (:rev (card w))
                                            :value (not (get-in (card w) [:surface :data (str "b" i)]))}]
                                    [(op! w op)
                                     (and ok?
                                          (nil? (refused #(cards/decide (:cards w) 1 op)))
                                          (not= surfaces/undrawable (surfaces/shown (:surface (card w)))))]))
                                [(world args) true] flips)]
            (and ok?
                 (= (inc (count flips)) (count (:events w)))
                 (not= surfaces/undrawable (surfaces/shown (:surface (card w))))
                 (= (get-in (card w) [:surface :data]) (:data args))))))))

(deftest every-box-relay-accepts-can-be-ticked-and-unticked
  (check 30 every-box-of-an-accepted-surface-ticks))

(deftest relays-limits-are-within-what-relay-tui-takes
  ;; relay-tui's renderer folds 2,000 components and draws 1,500 nodes,
  ;; 16 deep, 500 children to a container. A surface relay accepts is
  ;; one it draws whole.
  (is (<= surfaces/max-drawn 1500))
  (is (<= surfaces/max-depth 16))
  (is (<= surfaces/max-list 500))
  (testing "a board of the largest surfaces fits in one line a client reads"
    ;; relay-tui reads lines of up to 10,485,760 bytes. A card may be a
    ;; quarter over max-sent, for live links, and has its title and a few
    ;; fields beside the surface.
    (is (< (* cards/max-active (+ (* 5/4 cards/max-sent) 4000)) (/ 10485760 2))))
  (let [surface (fn [unit n copies]
                  (let [texts (vec (for [i (range copies)]
                                     {"id" (str "t" i) "component" "Text" "text" (apply str (repeat n unit))}))]
                    {:title "W" :components (into [{"id" "root" "component" "Column" "children" (mapv #(get % "id") texts)}]
                                                  texts)}))
        refusal (fn [args] (message #(cards/added {} (assoc args :kind "surface"))))
        sent (fn [args] (alength (.getBytes ^String (strict/generate (wire (card (world args ["contaminated"])))) "UTF-8")))
        too-heavy #"The surface would be \d+ bytes as a client is sent it, and the most is 128000\. Shorten its text or a list\."]
    (testing "backslashes double when shown and again in JSON, and the measure counts both"
      ;; 16,000 backslashes are 64,000 bytes shown and 128,000 as JSON in
      ;; each of the two places they are sent. A measure that left JSON's
      ;; doubling out would take them.
      (is (re-matches too-heavy (str (refusal (surface "\\" 8000 2))))))
    (testing "the measure is in bytes: a character of four bytes weighs four"
      ;; 16,000 of them are within what a surface may show, 32,000 chars
      ;; of UTF-16, and 128,000 bytes sent twice over.
      (let [tent "\uD83C\uDFD5"]
        (is (re-matches too-heavy (str (refusal (surface tent 4000 4)))))
        (testing "and the largest surface relay takes weighs what relay says it may"
          (let [most (loop [lo 0 hi 4000]
                       (if (= lo hi)
                         lo
                         (let [mid (quot (+ lo hi 1) 2)]
                           (if (refusal (surface tent mid 4)) (recur lo (dec mid)) (recur mid hi)))))
                weight (sent (surface tent most 4))]
            (is (< 3000 most 4000))
            ;; The card's own fields come on top of the surface's 128,000.
            (is (< (- cards/max-sent 200) weight (+ cards/max-sent 600)) (str weight))))))))

;; --- through relay.core, the tools and the command line

(defn- run
  [state actor type args & [{:keys [labels]}]]
  (try (core/run-command state {:type type :actor actor :args args :labels labels} {:now t0 :config cfg})
       (catch clojure.lang.ExceptionInfo e
         (if (core/rejection e)
           {:rejected (core/rejection e) :message (ex-message e)}
           (throw e)))))

(defn- seeded []
  (first (run core/empty-state "operator" :role/add {:id "hub" :title "Hub" :instructions "Run relay."})))

(deftest a-surface-is-a-card-in-relay-core
  (let [[state events] (run (seeded) "hub" :card/add (assoc a-list :kind "surface") {:labels ["private"]})
        id (:seq (first events))
        [state more] (run state "operator" :card/op {:card id :op "input" :path "/items/1/done" :value true :seen id})]
    (is (= ["card/added" "card/changed"] (map :type (concat events more))))
    (testing "an input wakes nobody"
      (is (empty? (core/roles-to-wake state cfg t0))))
    (testing "the log's JSON gives the same card back"
      (let [all (into (vec (second (run core/empty-state "operator" :role/add
                                        {:id "hub" :title "Hub" :instructions "Run relay."})))
                      (concat events more))]
        (is (= (:cards state) (:cards (core/replay (mapv #(json/parse-string (json/generate-string %) true) all)))))))
    (testing "the board, the prompt and a ref take it as any card"
      (is (= [id] (map :id (core/board state false))))
      (is (str/includes? (prompt/board (core/prompt-cards state cfg "hub")) (str "card " id ": Trip (surface)")))
      (let [r (refs/resolve state (str "[[card:" id "]]"))]
        (is (= [:ok (str "Card " id ": Trip") ["private"]] [(:status r) (:title r) (:labels r)]))
        (is (str/includes? (:text r) "[x] Bags  /items/1/done"))))
    (testing "archive, restore and retitle are any card's"
      (let [rev (:rev (core/card state id))
            [state _] (run state "operator" :card/op {:card id :op "retitle" :text "Lake trip" :seen rev})
            [state _] (run state "operator" :card/op {:card id :op "archive"})]
        (is (= ["Lake trip" :archived] ((juxt #(get-in % [:title :text]) :status) (core/card state id))))
        (is (= (str "Card " id " is archived. Restore it first.")
               (:message (run state "operator" :card/op {:card id :op "input" :path "/items/0/done" :value false
                                                         :seen (:rev (core/card state id))}))))))
    (testing "a client's op is the operator's, with the path and the value as sent"
      (is (= {:command {:type :card/op :actor "operator" :labels [] :public false
                        :args {:card id :op "input" :path "/site/paid" :value true :seen 9}}}
             (workspace/card-op-command {"card" id "op" "input" "path" "/site/paid" "value" true "seen" 9})))
      (is (= "surface" (get (workspace/card-wire state (core/card state id)) "kind"))))))

(deftest a-clients-input-reaches-relay-and-then-every-client
  (let [cmd (fn [state type args]
              (first (core/run-command state {:type type :actor "operator" :labels [] :args args} {:now t0 :config cfg})))
        s0 (-> core/empty-state
               (cmd :role/add {:id "clerk" :instructions "x"})
               (cmd :card/add (assoc a-list :kind "surface")))
        id (first (keys (:cards s0)))
        input {:card id :op "input" :path "/items/1/done" :value true :seen id}
        s1 (cmd s0 :card/op input)
        boxes (fn [card] (get-in card ["surface" "a2ui" 0 "createSurface" "dataModel" "items"]))
        h (-> (ht/live) (assoc :state s0) (ht/opened 2))
        [h fx1] (ht/say h 1 (ht/req 5 "_relay/cards" {}))
        [h] (ht/say h 2 (ht/req 6 "_relay/cards" {}))]
    (testing "the board holds the surface as A2UI"
      (let [card (get-in (first (ht/writes fx1 1)) ["result" "cards" 0])]
        (is (= ["surface" (str "card_" id)] [(get card "kind") (get-in card ["surface" "a2ui" 0 "createSurface" "surfaceId"])]))
        (is (= [{"done" true} {"done" false}] (boxes card)))))
    (testing "an input is the operator's, with no labels, and relay applies it"
      (let [[h fx] (ht/say h 1 (ht/req 7 "_relay/cards/op" input))
            [{:keys [tag command]}] (ht/submits fx)]
        (is (empty? (ht/writes fx 1)) "the client is told nothing until relay has decided")
        (is (= ["operator" [] input] [(:actor command) (:labels command) (:args command)]))
        (let [[_ fx] (ht/run h {:event :submit-result :tag tag :ok true :changed true :state s1})
              [answer] (ht/writes fx 1)
              [told] (ht/writes fx 2)]
          (testing "the client that sent it gets the card in its answer"
            (is (= [7 true [{"done" true} {"done" true}]]
                   [(get answer "id") (get-in answer ["result" "changed"]) (boxes (get-in answer ["result" "card"]))])))
          (testing "and the other is sent it"
            (is (= ["_relay/cards/changed" [{"done" true} {"done" true}]]
                   [(get told "method") (boxes (get-in told ["params" "cards" 0]))]))))))
    (testing "relay's refusal reaches the client in relay's words"
      (let [[h fx] (ht/say h 1 (ht/req 8 "_relay/cards/op" (assoc input :seen 1)))
            [{:keys [tag]}] (ht/submits fx)
            why (str "Card " id " has changed since rev 1. Read the card again before you change it.")
            [_ fx] (ht/run h {:event :submit-result :tag tag :ok false :rejection :conflict :message why})]
        (is (= [-32000 why] ((juxt #(get % "code") #(get % "message")) (get (first (ht/writes fx 1)) "error"))))))
    (testing "a parameter the op does not take is refused, not dropped"
      (let [[_ fx] (ht/say h 1 (ht/req 9 "_relay/cards/op" {:card id :op "input" :pth "/items/1/done" :value true :seen id}))]
        (is (empty? (ht/submits fx)))
        (is (= -32602 (get-in (first (ht/writes fx 1)) ["error" "code"])))))))

(defn- with-relay
  "Calls (f sys data) with a relay on artificial data and two roles, hub
  and clerk."
  [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-surfaces"}))
        data (str dir "/data")]
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :a
                   :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}}))
    (try
      (let [sys (inbound/system data)]
        (doseq [id ["hub" "clerk"]]
          (inbound/submit! sys {:type :role/add :actor "operator" :args {:id id :title id :instructions "x"}}))
        (f sys data dir))
      (finally (fs/delete-tree dir)))))

(defn- keyworded
  "JSON data as a harness's call gives it to a tool: member names as
  keywords."
  [x]
  (strict/parse (strict/generate x) {:keywords? true}))

(deftest a-role-keeps-a-surface-with-the-card-tools
  (with-relay
    (fn [sys _ _]
      (let [call (fn [role tool args labels] (tools/call! sys role tool args labels))
            the-card #(core/card (inbound/current-state sys) %)
            added (call "hub" "card_add" (keyworded (assoc a-list :kind "surface")) ["private"])
            id (parse-long (second (re-find #"Added card (\d+)" (str (:ok added)))))]
        (testing "card_add answers with the card as it is drawn, and its rev"
          (is (str/includes? (:ok added) (str "Card " id ": Trip (surface, rev " id ")")) (pr-str added))
          (is (str/includes? (:ok added) "[ ] Bags  /items/1/done"))
          (is (= ["private"] (:labels added))))
        (testing "the call's input fits the tool's schema, nested as it is"
          (let [offered (tools/offered (:config sys) "hub")]
            (is (nil? (tools/input-problem (:parameters (get offered "card_add")) (keyworded (assoc a-list :kind "surface")))))
            (doseq [v [true "done" nil {:a 1} [1 2]]]
              (is (nil? (tools/input-problem (:parameters (get offered "card_op"))
                                             {:card id :op "data" :path "/when" :value v :seen id}))))
            (is (some? (tools/input-problem (:parameters (get offered "card_op"))
                                            {:card id :op "data" :path 7 :value 1 :seen id})))))
        (testing "read alone, a surface comes with what its role wrote and what the operator set"
          (inbound/submit! sys {:type :card/op :actor "operator" :labels []
                                :args {:card id :op "input" :path "/items/1/done" :value true :seen id}})
          (let [r (call "clerk" "cards" {:card id} [])]
            (is (str/includes? (:ok r) "[x] Bags  /items/1/done"))
            (is (str/includes? (:ok r) "\n\ncomponents:\n{\"children\":[\"note\",\"items\",\"paid\"],\"component\":\"Column\",\"id\":\"root\"}\n"))
            (is (str/includes? (:ok r) "\ndata:\n{\"items\":[{\"done\":true,\"text\":\"Tent\"},{\"done\":true,\"text\":\"Bags\"}],\"site\":{\"paid\":false},\"when\":\"Saturday\"}"))
            (is (= ["private"] (:labels r))))
          (testing "and the whole board shows it without"
            (is (not (str/includes? (:ok (call "clerk" "cards" {} [])) "components:")))))
        (testing "cards with catalog says what a surface may hold, in relay's words"
          (is (= {:ok (surfaces/catalog-text) :labels []} (call "clerk" "cards" {:catalog true} ["contaminated"]))))
        (testing "a write needs the rev, and its answer gives the next"
          (let [rev (:rev (the-card id))
                r (call "clerk" "card_op" {:card id :op "data" :path "/when" :value "Sunday https://evil.example" :seen rev}
                        ["contaminated"])
                now (:rev (the-card id))]
            (is (= {:ok (str "Wrote the data of card " id ". The card is at rev " now " now.") :labels []} r))
            (is (= ["contaminated" "private"] (core/card-labels (the-card id))))
            (is (str/starts-with? (:error (call "clerk" "card_op" {:card id :op "data" :path "/when" :value "x" :seen rev} []))
                                  (str "Card " id " has changed since rev " rev ".")))
            (is (= "data needs seen: the card's rev when you read it."
                   (:error (call "clerk" "card_op" {:card id :op "data" :path "/when" :value "x"} []))))
            (testing "null removes, and leaving the value out does not"
              (is (= "data needs a value. A null value removes what is at the path."
                     (:error (call "clerk" "card_op" {:card id :op "data" :path "/when" :seen now} []))))
              (call "clerk" "card_op" {:card id :op "data" :path "/when" :value nil :seen now} [])
              (is (not (contains? (get-in (the-card id) [:surface :data]) "when"))))))
        (testing "what changes nothing says so, with the labels of what it tells"
          (is (= {:ok (str "That changes nothing: Card " id " already has those components.")
                  :labels ["contaminated" "private"]}
                 (call "hub" "card_op" (keyworded {:card id :op "components" :components (:components a-list)
                                                   :seen (:rev (the-card id))})
                       []))))
        (testing "components are replaced through the tool"
          (let [r (call "hub" "card_op" (keyworded {:card id :op "components" :seen (:rev (the-card id))
                                                    :components [{"id" "root" "component" "Text" "text" "Done."}]})
                        [])]
            (is (str/starts-with? (:ok r) (str "Replaced the components of card " id ". The card is at rev ")))
            (is (= [{"id" "root" "component" "Text" "text" "Done."}] (get-in (the-card id) [:surface :components])))))))))

(deftest the-operator-keeps-a-surface-from-the-command-line
  (with-relay
    (fn [sys data dir]
      (let [relay (fn [& args]
                    (update ((requiring-resolve 'relay.cli/execute) (into ["--data" data] args))
                            :out #(if (string? %) % (str/join "\n" %))))
            file (str dir "/trip.json")
            the-card #(core/card (inbound/current-state sys) %)]
        (spit file (strict/generate {"components" (:components a-list) "data" (:data a-list)}))
        (is (str/starts-with? (:out (relay "card" "add" "surface" "--title" "Trip" "--surface" file)) "Added card "))
        (let [id (:id (first (core/board (inbound/current-state sys) false)))
              sid (str id)]
          (testing "relay card shows it as it is drawn, with each input's path"
            (is (str/includes? (:out (relay "card" sid)) "[ ] Bags  /items/1/done")))
          (testing "input ticks a box, and says the rev"
            (let [out (:out (relay "card" sid "input" "--path" "/items/1/done" "--value" "true" "--seen" sid))
                  rev (:rev (the-card id))]
              (is (= (str "Set that input on card " id ". The card is at rev " rev " now.") (str/trim out)))
              (is (true? (get-in (the-card id) [:surface :data "items" 1 "done"])))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has changed since rev"
                                    (relay "card" sid "input" "--path" "/items/0/done" "--value" "false" "--seen" sid)))))
          (testing "data writes JSON at a path, and null removes it"
            (relay "card" sid "data" "--path" "/when" "--value" "Sunday" "--seen" (str (:rev (the-card id))))
            (is (= "Sunday" (get-in (the-card id) [:surface :data "when"])))
            (relay "card" sid "data" "--path" "/items/-" "--value" "{\"text\":\"Stove\",\"done\":false}"
                   "--seen" (str (:rev (the-card id))))
            (is (= 3 (count (get-in (the-card id) [:surface :data "items"]))))
            (relay "card" sid "data" "--path" "/when" "--value" "null" "--seen" (str (:rev (the-card id))))
            (is (not (contains? (get-in (the-card id) [:surface :data]) "when"))))
          (testing "components replaces them from a file"
            (spit file (strict/generate {"components" [{"id" "root" "component" "Text" "text" "Packed."}]}))
            (relay "card" sid "components" "--surface" file "--seen" (str (:rev (the-card id))))
            (is (str/includes? (:out (relay "card" sid)) "  │ Packed.")))
          (testing "--surface reads a file on this machine and nothing else"
            ;; slurp would have fetched an address and recorded what came
            ;; back as the operator's own text.
            (doseq [address ["http://127.0.0.1:9/trip.json" "https://evil.example/s.json" (str "file://" file)]]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--surface takes a file on this machine"
                                    (relay "card" "add" "surface" "--title" "X" "--surface" address))
                  address))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--surface takes a file on this machine"
                                  (relay "card" "add" "surface" "--title" "X" "--surface" dir))
                "a directory"))
          (testing "components leaves the data alone, and says so when the file holds data too"
            (spit file (strict/generate {"components" [{"id" "root" "component" "Text" "text" "x"}] "data" {"a" 1}}))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"replaces the components and leaves the data as it is"
                                  (relay "card" sid "components" "--surface" file "--seen" (str (:rev (the-card id))))))
            (is (str/includes? (:out (relay "card" sid)) "  │ Packed.")))
          (testing "a file that is missing or is not a surface says so"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"relay could not read .*nope\.json: --surface takes a file"
                                  (relay "card" "add" "surface" "--title" "X" "--surface" (str dir "/nope.json"))))
            (spit file "[1, 2]")
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not hold one JSON object"
                                  (relay "card" "add" "surface" "--title" "X" "--surface" file)))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"A surface's components are a list of objects"
                                  (relay "card" "add" "surface" "--title" "X")))))))))
