(ns relay.review-test
  "Reviews (relay#79): the text relay writes for what the operator
  consents to, its digest, and the hub's check that a client which
  declares relay/review allows only what it was sent."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.data.json :as data-json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.acp :as acp]
            [relay.acp-client :as client]
            [relay.cards :as cards]
            [relay.core :as core]
            [relay.core-test :as ct]
            [relay.dispatch :as dispatch]
            [relay.hub :as hub]
            [relay.hub-test :as ht]
            [relay.inbound :as inbound]
            [relay.json :as strict]
            [relay.log :as log]
            [relay.review :as review]
            [relay.serve-test :as st]
            [relay.test-support :as support]
            [relay.text :as text]
            [relay.workspace :as workspace]))

;; --- generators

(def odd-code-points
  "Code points that hide text, rewrite a terminal, pass for others or
  draw as a space that is not one."
  [0 7 9 10 13 27 127 0x85 0x9b 0xa0 0xad 0x34f 0x200b 0x200d 0x200e 0x202a 0x202e 0x2066 0x2069
   0x2028 0x2029 0x2800 0x3000 0x3164 0xfe0f 0xfeff 0xe000 0xd800 0xdfff 0x10ffff 0xe0041 0x1f600
   0x430 0x435 0x3bf 0xe9 0x301 92])

(defn- cp-str [cp] (String. (Character/toChars (int cp))))

(def gen-nasty
  "Text with anything in it: any code point, lone surrogates included,
  the odd ones above, and spelled-out escapes."
  (gen/fmap (fn [parts] (apply str parts))
            (gen/vector (gen/one-of [(gen/fmap cp-str (gen/choose 0 0x10FFFF))
                                     (gen/fmap cp-str (gen/elements odd-code-points))
                                     gen/string-alphanumeric
                                     (gen/elements ["\\u{200b}" "\\" "\\\\" "\n" "\t" " " "  │ " "[contaminated]"
                                                    "Options:" "\"" "https://evil.example/x" "www.a.b" "1.5"])])
                        0 10)))

(def gen-json
  "JSON values as relay.json reads them, with nasty strings and keys."
  (gen/recursive-gen
   (fn [inner] (gen/one-of [(gen/vector inner 0 4)
                            (gen/fmap #(into {} %) (gen/vector (gen/tuple gen-nasty inner) 0 4))]))
   (gen/one-of [gen-nasty gen/large-integer gen/boolean (gen/return nil)
                (gen/fmap #(bigdec (/ % 100.0)) (gen/choose -100000 100000))])))

(def gen-labels (gen/elements [nil [] ["private"] ["contaminated"] ["contaminated" "private"]]))

(def gen-option
  (gen/let [name gen-nasty kind (gen/one-of [(gen/elements ["allow_once" "allow_always" "reject_once" "reject_always"]) gen-nasty])
            id gen-nasty]
    {"name" name "kind" kind "optionId" id}))

(def gen-request
  (gen/let [role gen-nasty tool gen-nasty class (gen/one-of [(gen/return nil) (gen/elements ["read" "egress" "act"])])
            preview (gen/one-of [(gen/return nil)
                                 (gen/let [t gen-nasty ls gen-labels e gen/boolean] {:text t :labels ls :error e})])
            input gen-json labels gen-labels
            fixed (gen/one-of [(gen/return nil) (gen/fmap #(into {} %) (gen/vector (gen/tuple gen-nasty gen/small-integer) 1 3))])
            options (gen/vector gen-option 0 3)]
    {:role role :tool tool :class class :preview preview :input input :labels labels
     :fixed-args fixed :options options}))

(defn- review-ok? [t]
  (every? #(or (= 10 %) (text/printable? %)) (iterator-seq (.iterator (.codePoints ^String t)))))

;; --- the text

(defspec a-review-holds-only-printable-characters-spaces-and-newlines 300
  (prop/for-all [r gen-request]
    (let [{t "text"} (review/request-review r)]
      (review-ok? t))))

(defspec a-review-s-digest-is-the-sha256-of-its-text 100
  (prop/for-all [r gen-request]
    (let [{t "text" h "sha256"} (review/request-review r)]
      (and (= h (core/sha256 t))
           (= 64 (count h))))))

(def slots
  "Where one string goes in a clean request, for telling two apart."
  {:role #(assoc %1 :role %2)
   :tool #(assoc %1 :tool %2)
   :preview #(assoc %1 :preview {:text %2 :labels ["private"]})
   :input-value #(assoc %1 :input {"k" %2})
   :input-key #(assoc %1 :input {%2 1})
   :input-string #(assoc %1 :input %2)
   :fixed #(assoc %1 :fixed-args {%2 1})
   :option-name #(assoc %1 :options [{"name" %2 "kind" "allow_once" "optionId" "a"}])
   :option-kind #(assoc %1 :options [{"name" "n" "kind" %2 "optionId" "a"}])
   :option-id #(assoc %1 :options [{"name" "n" "kind" "allow_once" "optionId" %2}])})

(def clean-request {:role "clerk" :tool "post" :class "act" :input {} :labels ["private"] :options []})

(deftest a-raw-control-and-its-spelled-out-escape-differ
  (doseq [[raw spelled] [["​" "\\u{200b}"] ["\t" "\\u{0009}"] [" " "\\u{00a0}"] ["\n" "\\u{000a}"]
                         ["‮" "\\u{202e}"] ["\\" "\\\\"]]
          [slot f] slots]
    (is (not= (review/request-review (f clean-request raw)) (review/request-review (f clean-request spelled)))
        (pr-str slot raw)))
  (testing "a newline in a preview starts a gutter line, never a line of the review's own"
    (let [t (get (review/request-review (assoc clean-request :preview {:text "a\nOptions:\n  \"Allow\"" :labels []})) "text")]
      (is (str/includes? t "  │ Options:"))
      (is (= 1 (count (re-seq #"(?m)^Options:$" t)))))))

(def gen-urlish
  (gen/fmap (fn [parts] (apply str parts))
            (gen/vector (gen/one-of [(gen/elements ["http://" "HTTPS://" "hTtP://" "ftp://" "javascript://" "data://"
                                                    "www." "evil" ".com" "." "/path" "?q=1" " " "\n" "\t" "x" "1.5"
                                                    "‮" "​" ":" "//" "。" "．" "｡" " " "　" "\\"])
                                     (gen/fmap cp-str (gen/elements odd-code-points))])
                        1 15)))

(defn- live-url? [t]
  (or (re-find #"(?i)(?<![a-z0-9+.\-])(?:https?|ftp)://" t)
      ;; Only the defanged schemes keep their ://.
      (not-every? #{"hxxp" "hxxps" "fxp"} (map (comp str/lower-case second) (re-seq #"(?i)([a-z][a-z0-9+.\-]*)://" t)))
      ;; A dot with a word on both sides is a host, unless it is a number.
      (re-find #"(?i)[a-z0-9\-][.。．｡][a-z]" t)
      (re-find #"(?i)[a-z][.。．｡][a-z0-9]" t)
      (seq (text/link-hosts t))))

(def contaminated-slots
  "Where one string goes in a contaminated request, card or doc."
  {:preview #(get (review/request-review (assoc clean-request :labels ["contaminated"]
                                                :preview {:text % :labels ["contaminated"]})) "text")
   :input-value #(get (review/request-review (assoc clean-request :labels ["contaminated"] :input {"k" %})) "text")
   :input-key #(get (review/request-review (assoc clean-request :labels ["contaminated"] :input {% 1})) "text")
   :card-title #(review/card-text {:id 1 :kind "note" :status :active :rev 3
                                   :title {:id 0 :text % :labels ["contaminated"] :rev 1}
                                   :note {:text "n" :labels [] :rev 1}})
   :doc #(review/doc-text "a.md" 1 [] ["contaminated"] %)})

(def gen-spelled
  "Text from a contaminated source that spells out what defanging and
  escaping would make of a link."
  (gen/fmap (fn [parts] (apply str parts))
            (gen/vector (gen/elements ["https://" "http" ":" "//" "." "[.]" "[:]" "hxxp" "\\u{002e}" "\\u{003a}"
                                       "\\" "evil" "com" "12.50" "\"" " " "\n" "．" "\\u{ff0e}" "1"])
                        0 8)))

(deftest a-link-and-its-defanged-spelling-differ
  (doseq [[a b] [["x.com" "x[.]com"] ["https://e.x" "hxxps://e[.]x"] ["a.b" "a\\u{002e}b"] ["a:b//" "a\\u{003a}//"]]
          [slot f] contaminated-slots]
    (is (not= (f a) (f b)) (pr-str slot a))))

(deftest amounts-read-as-written-even-from-a-contaminated-source
  (let [t (get (review/request-review (assoc clean-request :labels ["contaminated"]
                                             :input {"amount" "12.50" "n" 12.50M "v" "1.5.2" "p" "$3.20)"}))
               "text")]
    (is (str/includes? t "\"amount\": \"12.50\","))
    (is (str/includes? t "\"n\": 12.50,"))
    (is (str/includes? t "\"1.5.2\""))
    (is (str/includes? t "\"$3.20)\""))
    (testing "a dotted quad is an address, not an amount"
      (is (not (str/includes? (review/doc-text "a" 1 [] ["contaminated"] "10.0.0.1") "10.0.0.1"))))
    (testing "a link in it does not read as written"
      (is (str/includes? (review/doc-text "a" 1 [] ["contaminated"] "go to https://evil.example now")
                         "go to https\\u{003a}//evil\\u{002e}example now")))))

(defspec a-contaminated-source-has-no-live-url-in-its-review 500
  (prop/for-all [p gen-urlish i gen-urlish k gen-urlish]
    (let [r (assoc clean-request :labels ["contaminated"] :preview {:text p :labels ["contaminated"]}
                   :input {k i})
          t (get (review/request-review r) "text")
          card {:id 1 :kind "checklist" :status :active :rev 3 :added-at "2026-10-24T00:00:00Z"
                :title {:id 0 :text p :labels ["contaminated"] :rev 1}
                :items [{:id 1 :text i :labels ["contaminated"] :done false :rev 2}]}
          ct (review/card-text card)
          dt (review/doc-text "notes/a" 4 [] ["contaminated"] (str p "\n" i))]
      (and (not (live-url? t)) (not (live-url? ct)) (not (live-url? dt))
           (review-ok? t) (review-ok? ct) (review-ok? dt)))))

(defn- leaves
  "Every key and leaf value in a JSON value."
  [x]
  (cond (map? x) (mapcat (fn [[k v]] (cons k (leaves v))) x)
        (sequential? x) (mapcat leaves x)
        :else [x]))

(defspec nothing-of-the-input-is-missing-from-the-review 300
  (prop/for-all [input gen-json]
    (let [t (get (review/request-review (assoc clean-request :input input)) "text")]
      (every? #(str/includes? t (text/visible-strict (strict/generate %))) (leaves input)))))

(deftest a-huge-deeply-nested-input-is-shown-whole
  (let [big (apply str (repeat 300000 "abc "))
        deep (reduce (fn [v i] {(str "k" i) [v]}) {"end" big} (range 100))
        t (get (review/request-review (assoc clean-request :input deep)) "text")]
    (is (str/includes? t (strict/generate big)))
    (is (str/includes? t "\"k99\""))
    (is (str/includes? t "\"end\""))
    (is (review-ok? t))))

(deftest a-request-s-review-reads-in-order
  (let [t (get (review/request-review
                {:role "clerk" :tool "send" :class "act"
                 :preview {:text "Send to Everyone" :labels ["private"]}
                 :input {"group" "Everyone" "body" "hi"} :labels ["private"] :fixed-args {"group" "Everyone"}
                 :options [{"name" "Allow once" "kind" "allow_once" "optionId" "allow_once"}
                           {"name" "Reject" "kind" "reject_once" "optionId" "reject_once"}]})
               "text")]
    (is (= (str/join "\n" ["Permission request from role \"clerk\""
                           "Tool \"send\", class \"act\""
                           ""
                           "What it does, by the tool's preview (its labels [\"private\"]):"
                           "  │ Send to Everyone"
                           ""
                           "Input, all of it, as JSON:"
                           "  │ {"
                           "  │   \"body\": \"hi\","
                           "  │   \"group\": \"Everyone\""
                           "  │ }"
                           "Set by your settings, not by the role: [\"group\"]"
                           ""
                           "Labels of the conversation: [\"private\"]"
                           ""
                           "Options:"
                           "  \"Allow once\", kind \"allow_once\", id \"allow_once\""
                           "  \"Reject\", kind \"reject_once\", id \"reject_once\""
                           ""
                           "Characters to check: none"])
           t))))

;; --- reading a review back: injectivity, by construction

(defn- unescape
  "One line of a review as it was before escaping."
  [line]
  (str/replace line #"\\\\|\\u\{([0-9a-f]+)\}"
               (fn [[m hex]] (if hex (cp-str (Long/parseLong hex 16)) "\\"))))

(def ^:private json-string-re #"\"(?:[^\"\\]|\\.)*\"")

(defn- read-json [s] (data-json/read-str s :bigdec true))

(defn- labelled
  "[value contaminated?] of a labelled line `prefix` JSON-string [contaminated], or nil."
  [prefix line]
  (let [l (unescape line)]
    (when (str/starts-with? l prefix)
      (when-let [[_ js c] (re-matches (re-pattern (str "(?s)(" json-string-re ")( \\[contaminated\\])?")) (subs l (count prefix)))]
        [(read-json js) (some? c)]))))

(defn- take-block
  "[text contaminated? rest] of a gutter block at the head of lines."
  [lines]
  (let [[c lines] (if (= "  [contaminated]" (first lines)) [true (rest lines)] [false lines])
        [bl more] (split-with #(str/starts-with? % text/gutter) lines)]
    (assert (seq bl) "a block has a line")
    [(str/join "\n" (map #(unescape (subs % (count text/gutter))) bl)) c more]))

(defn- labels-of [s] (if (= "none sent" s) nil (read-json s)))

(defn parse-request
  "A request's review text read back into what it says."
  [t]
  (let [[l0 l1 l2 l3 & more] (str/split t #"\n" -1)
        [_ heading role] (re-matches (re-pattern (str "(?s)(.*) from role (" json-string-re ")")) (unescape l0))
        [_ tool class] (re-matches (re-pattern (str "(?s)Tool (" json-string-re "), class (" json-string-re "|not known to relay)")) (unescape l1))
        class (when-not (= "not known to relay" class) (read-json class))
        _ (assert (= "" l2))
        [preview more] (if (= "What it does: this tool has no preview." l3)
                         [nil more]
                         (let [[_ ls] (re-matches #"(?s)What it does, by the tool's preview \(its labels (.*)\):" (unescape l3))
                               [err more] (if (= "  The preview command could not say what this call would do." (first more))
                                            [true (rest more)] [false more])
                               [text _ more] (take-block more)]
                           [{:text text :labels (labels-of ls) :error err} more]))
        [blank head & more] more
        _ (assert (and (= "" blank) (= "Input, all of it, as JSON:" head)))
        [input-text _ more] (take-block more)
        [fixed more] (if-let [[_ ks] (re-matches #"(?s)Set by your settings, not by the role: (.*)" (unescape (first more)))]
                       [(read-json ks) (rest more)] [nil more])
        [blank ls & more] more
        _ (assert (= "" blank))
        labels (labels-of (second (re-matches #"(?s)Labels of the conversation: (.*)" (unescape ls))))
        [options more] (if (= ["" "Options:"] (take 2 more))
                         (let [[os more] (split-with #(str/starts-with? % "  ") (drop 2 more))]
                           [(if (= ["  none"] os)
                              []
                              (vec (for [o os
                                         :let [[_ n k i] (re-matches (re-pattern (str "(?s)  (" json-string-re "), kind (" json-string-re "), id (" json-string-re ")")) (unescape o))]]
                                     {"name" (read-json n) "kind" (read-json k) "optionId" (read-json i)})))
                            more])
                         [nil more])]
    (assert (= "" (first more)))
    (assert (str/starts-with? (second more) "Characters to check:"))
    {:heading heading :role (read-json role) :tool (read-json tool)
     :class class
     :preview preview :input (read-json input-text) :fixed fixed :labels labels :options options}))

(defn- request-subject [{:keys [heading role tool reader inner class preview input labels fixed-args options]}]
  {:heading (or heading "Permission request") :role (str role) :tool (str tool) :class class

   :preview (when preview {:text (str (:text preview)) :labels (some-> (:labels preview) vec) :error (boolean (:error preview))})
   :input input :fixed (when (seq fixed-args) (vec (sort (map str (keys fixed-args)))))
   :labels (some-> labels vec)
   :options (some->> options (mapv #(into {} (map (fn [k] [k (str (get % k))])) ["name" "kind" "optionId"])))})

(defn parse-card
  "A card's review text read back into what it says."
  [t]
  (let [[l0 l1 & more] (str/split t #"\n" -1)
        [_ id kind rev archived] (re-matches #"Card (\d+) \((\w+), rev (\d+)(?:, \d+ of \d+ done)?(?:, complete)?(, archived)?\)" l0)
        el (fn [re line] (let [l (unescape line)
                               [_ a b js c] (re-matches (re-pattern (str "(?s)" re "(" json-string-re ")( \\[contaminated\\])?")) l)]
                           (when js [a b (read-json js) (some? c)])))
        [title-text title-c] (labelled "Title: " l1)
        [body more] (split-with #(not= "" %) more)
        base {:id (parse-long id) :kind kind :rev (parse-long rev) :archived (some? archived) :title [title-text title-c]}]
    (assert (str/starts-with? (second more) "Characters to check:"))
    (case kind
      "checklist" (assoc base :items (if (= ["No items yet."] body) []
                                         (vec (for [l body :let [[i st text c] (el "Item (\\d+), (done|open): " l)]]
                                                [(parse-long i) (= "done" st) text c]))))
      "tracker" (let [cols (if (= "No columns yet." (first body)) [] (vec (for [l body :let [[i _ text c] (el "Column (\\d+): ()" l)] :when i] [(parse-long i) text c])))
                      rows (vec (for [l body :let [[i _ text c] (el "Row (\\d+): ()" l)] :when i] [(parse-long i) text c]))
                      cells (vec (for [l body :let [[_ r c st] (re-matches #"Row (\d+), column (\d+): (\w+)" l)] :when r] [(parse-long r) (parse-long c) st]))]
                  (assoc base :columns cols :rows rows :cells cells))
      "note" (let [[head & bl] body
                   [text _ _] (take-block bl)]
               (assoc base :note [text (= "Note, from a contaminated source:" head)])))))

(defn- card-subject [{:keys [id kind rev title items rows columns cells note] :as card}]
  (let [c? #(text/contaminated? (:labels %))
        base {:id id :kind kind :rev rev :archived (not (cards/active? card)) :title [(:text title) (c? title)]}]
    (case kind
      "checklist" (assoc base :items (vec (for [i items] [(:id i) (boolean (:done i)) (:text i) (c? i)])))
      "tracker" (assoc base :columns (vec (for [x columns] [(:id x) (:text x) (c? x)]))
                       :rows (vec (for [x rows] [(:id x) (:text x) (c? x)]))
                       :cells (vec (for [[[r c] st] (sort-by key cells) :when (not= "open" st)] [r c st])))
      "note" (assoc base :note [(:text note) (c? note)]))))

(defn parse-record
  "A record's review text (relay.review/record-lines) read back: its kind,
  version and labels, and each field as [label value contaminated? as]."
  [t]
  (let [[l0 & more] (str/split t #"\n" -1)
        [_ kind version] (re-matches #"(\w+), version (.*)" l0)]
    (loop [lines more fields []]
      (let [l (first lines)]
        (if (str/starts-with? l "Labels: ")
          (do (assert (= "" (second lines)))
              (assert (str/starts-with? (nth lines 2) "Characters to check"))
              {:kind (str/lower-case kind) :version version :labels (labels-of (subs (unescape l) 8)) :fields fields})
          (let [[_ label c rest] (re-matches #"(?s)([A-Z][A-Za-z ]*?)(, from a contaminated source)?:(.*)" (unescape l))]
            (cond
              (= " none" rest) (recur (next lines) (conj fields [label nil false nil]))
              (= "" rest) (let [[text bc more] (take-block (next lines))]
                            (assert (= (some? c) bc))
                            (recur more (conj fields [label text bc :block])))
              (str/starts-with? rest " \"") (let [[v cc] (labelled (str label ": ") l)]
                                              (recur (next lines) (conj fields [label v cc :line])))
              :else (recur (next lines) (conj fields [label (subs rest 1) false :plain])))))))))

(defn- record-subject [{:keys [kind version labels fields]}]
  {:kind kind :version (str version) :labels (vec labels)
   :fields (vec (for [{:keys [label value as] ls :labels} fields]
                  (if (nil? value) [label nil false nil]
                      [label (str value) (and (not= :plain as) (text/contaminated? ls)) as])))})

(def gen-el (gen/let [t gen-nasty ls gen-labels] {:text t :labels (vec ls)}))

(def gen-card
  (gen/let [kind (gen/elements ["checklist" "tracker" "note"])
            id (gen/choose 1 5000) rev (gen/choose 1 9000) status (gen/elements [:active :archived])
            title gen-el
            items (gen/vector (gen/let [e gen-el d gen/boolean] (assoc e :done d)) 0 4)
            cols (gen/vector gen-el 0 3) rows (gen/vector gen-el 0 3)
            states (gen/vector (gen/elements ["open" "done" "skip"]) 9)
            note gen-el]
    (let [ids (fn [els] (vec (map-indexed #(assoc %2 :id (inc %1) :rev 1) els)))
          cols (ids cols) rows (ids rows)]
      (cond-> {:id id :kind kind :status status :rev rev :title (assoc title :id 0 :rev 1)}
        (= "checklist" kind) (assoc :items (ids items))
        (= "tracker" kind) (assoc :columns cols :rows rows
                                  :cells (into {} (for [r rows c cols] [[(:id r) (:id c)] (nth states (mod (* (:id r) (:id c)) 9))])))
        (= "note" kind) (assoc :note (assoc note :rev 1))))))

(defspec a-request-s-review-reads-back-as-the-request 500
  (prop/for-all [r gen-request heading (gen/elements [nil "Held call"]) holds gen/boolean]
    (let [r (cond-> (assoc r :heading heading) holds (assoc :options nil))]
      (= (request-subject r) (parse-request (get (review/request-review r) "text"))))))

(defspec a-card-s-review-reads-back-as-the-card 500
  (prop/for-all [c gen-card]
    (let [t (review/card-text c)]
      (and (review-ok? t) (= (card-subject c) (parse-card t))))))

(defspec a-doc-s-review-reads-back-as-the-doc 300
  (prop/for-all [path gen-nasty v (gen/choose 1 9999) pls gen-labels ls gen-labels content gen-nasty]
    (let [r (review/doc-record path v pls ls content)
          t (review/record-text r)]
      (and (review-ok? t) (= (record-subject r) (parse-record t))))))

(def gen-fact-op
  (gen/one-of
   [(gen/tuple (gen/return :add) (gen/elements [nil "payment" "household/car"]) gen-nasty
               (gen/elements [nil "2026-01-01" "2026-11-01"]) (gen/elements [nil "2026-12-01" "2036-11-01"]) gen-labels)
    (gen/tuple (gen/return :correct) gen/nat gen-nasty gen-nasty (gen/elements [nil "2036-11-01" "open"]) gen-labels)
    (gen/tuple (gen/return :retract) gen/nat gen-nasty)]))

(defn- run-fact-ops [ops]
  (reduce (fn [state [op a b c d e]]
            (let [ids (vec (sort (keys (:facts state))))
                  pick (fn [n] (when (seq ids) (nth ids (mod n (count ids)))))
                  cmd (case op
                        :add {:type :fact/add :actor "hub" :labels (vec e) :args {:topic a :text b :valid-from c :valid-to d}}
                        :correct {:type :fact/correct :actor "hub" :labels (vec e)
                                  :args {:fact (pick a) :text b :why c :valid-to d}}
                        :retract {:type :fact/correct :actor "hub" :labels [] :args {:fact (pick a) :retract true :why b}})]
              (try (first (core/run-command state cmd {:now ct/t0 :config ct/cfg}))
                   (catch clojure.lang.ExceptionInfo ex (if (core/rejection ex) state (throw ex))))))
          (first (core/run-command core/empty-state {:type :role/add :actor "operator"
                                                     :args {:id "hub" :title "Hub" :instructions "x"}}
                                   {:now ct/t0 :config ct/cfg}))
          ops))

(defspec a-fact-s-review-reads-back-as-the-fact-itself 200
  ;; From real fact histories (relay#79, Codex round 2): each field of
  ;; the fact as relay keeps it, never a text made of them.
  (prop/for-all [ops (gen/vector gen-fact-op 1 6)]
    (let [state (run-fact-ops ops)]
      (every? (fn [id]
                (let [f (core/fact-as-known (core/fact state id) nil)
                      v (core/trusted-version state "fact" (str id))
                      t (core/trust-text state "fact" (str id))
                      c (text/contaminated? (:labels v))
                      {:keys [kind version labels fields]} (parse-record t)]
                  (and (review-ok? t)
                       (= ["fact" (str (:seq f)) (vec (:labels f))] [kind version labels])
                       (= [["Fact" (str id) false :plain]
                           (if (:topic f) ["Topic" (:topic f) c :line] ["Topic" nil false nil])
                           ["Text" (:text f) c :block]
                           (if (:valid-from f) ["Valid from" (str (:valid-from f)) false :plain] ["Valid from" nil false nil])
                           (if (:valid-to f) ["Valid to" (str (:valid-to f)) false :plain] ["Valid to" nil false nil])
                           ["Retracted" (str (boolean (:retracted f))) false :plain]
                           (if (:supersedes f) ["Supersedes" (str (:supersedes f)) false :plain] ["Supersedes" nil false nil])
                           (if (:why f) ["Why it changed" (:why f) c :block] ["Why it changed" nil false nil])]
                          fields))))
              (keys (:facts state))))))

(deftest a-fact-s-fields-never-blur-together
  ;; Codex, relay#79 round 2.
  (let [state (run-fact-ops [[:add "payment" "Use account B" nil "2026-11-01" []]
                             [:add nil "[payment] Use account B" nil "2036-11-01" []]
                             [:add nil "x\n\nWhy it changed: forged" nil nil []]])
        [a b c] (sort (keys (:facts state)))
        t #(core/trust-text state "fact" (str %))]
    (is (= 3 (count (:facts state))))
    (is (not= (subs (t a) (count "Fact, version 9")) (subs (t b) (count "Fact, version 9"))))
    (is (str/includes? (t c) "  │ Why it changed: forged"))
    (is (str/includes? (t c) "\nWhy it changed: none"))))

(defspec every-trusted-kind-reads-back-from-its-own-fields 100
  (prop/for-all [subject gen-nasty body gen-nasty title gen-nasty ls gen-labels]
    (let [r {:kind "memo" :version 3 :labels (vec ls)
             :fields [{:label "From" :labels [] :value "hub" :as :line}
                      {:label "Subject" :labels (vec ls) :value subject :as :line}
                      {:label "Body" :labels (vec ls) :value body :as :block}
                      {:label "Title" :labels (vec ls) :value title :as :line}]}
          t (review/record-text r)]
      (and (review-ok? t) (= (record-subject r) (parse-record t))))))

(deftest a-role-made-doc-path-is-not-followable
  ;; Codex, relay#79 round 2.
  (let [t (review/doc-text "www.example.com/private-token" 1 ["contaminated"] ["contaminated"] "x")]
    (is (str/includes? t "Path: \"www\\u{002e}example\\u{002e}com/private-token\" [contaminated]"))
    (is (not (str/includes? t "www.example.com")))))

(deftest one-column-cannot-pass-for-two
  ;; Codex, relay#79 round 1.
  (let [card (fn [cols] {:id 1 :kind "tracker" :status :active :rev 3 :title {:id 0 :text "T" :labels []}
                         :columns (vec (map-indexed (fn [i [t ls]] {:id (inc i) :text t :labels ls}) cols)) :rows [] :cells {}})]
    (is (not= (review/card-text (card [["Jan, 2 [contaminated] Feb" ["contaminated"]]]))
              (review/card-text (card [["Jan" []] ["Feb" ["contaminated"]]]))))))

;; --- characters that pass for others

(def confusables [0x430 0x435 0x43e 0x440 0x441 0x3bf 0x3b1 0x456 0xff41 0x217c])

(def gen-with-confusable
  (gen/let [a gen/string-alphanumeric cp (gen/elements confusables) b gen/string-alphanumeric]
    (str a (cp-str cp) b)))

(defspec a-confusable-in-the-input-is-always-named 300
  (prop/for-all [s gen-with-confusable k gen/string-alphanumeric labels gen-labels
                 nest (gen/elements [:value :key :deep])]
    (let [[input path] (case nest :value [{k s} [k]] :key [{s 1} [s]] :deep [{k [{"x" s}]} [k 0 "x"]])
          t (get (review/request-review (assoc clean-request :labels labels :input input)) "text")
          section (second (str/split t #"\nCharacters to check:\n" 2))
          cp (first (filter #(> % 127) (iterator-seq (.iterator (.codePoints ^String s)))))]
      (and section
           (str/includes? section (format "U+%04X %s" cp (Character/getName (int cp))))
           (str/includes? section (str "  input " (if (text/contaminated? labels)
                                                     (text/visible-defanged (strict/generate path))
                                                     (text/visible-strict (strict/generate path)))))))))

(deftest a-lookalike-recipient-is-named-where-it-is
  ;; Codex, relay#79 round 1: pаypal with a Cyrillic а.
  (let [t (get (review/request-review (assoc clean-request :labels ["contaminated"]
                                             :preview {:text "To: pаypal" :labels ["contaminated"]}
                                             :input {"to" "pаypal"}))
               "text")]
    (is (str/includes? t "  preview: word \"pаypal\" mixes Cyrillic and Latin"))
    (is (str/includes? t "  input [\"to\"]: word \"pаypal\" mixes Cyrillic and Latin"))
    (is (str/includes? t "  input [\"to\"]: U+0430 CYRILLIC SMALL LETTER A")))
  (testing "a card's and a doc's too"
    (is (str/includes? (review/card-text {:id 1 :kind "checklist" :status :active :rev 3 :title {:id 0 :text "pаy" :labels []} :items []})
                       "  title: U+0430 CYRILLIC SMALL LETTER A"))
    (is (str/includes? (review/doc-text "a.md" 1 [] [] "pаy") "  content: U+0430 CYRILLIC SMALL LETTER A"))))

(deftest pretty-json-reads-back-as-the-value
  (doseq [v [{} [] {"b" [1 2.50M {}] "a" "x\né"} nil "s" [[[]]]]]
    (is (= v (strict/parse (strict/pretty v))) (pr-str v))))

;; --- the proxy attaches it

(deftest the-proxy-attaches-relay-s-review-and-drops-the-agent-s
  (let [request (json/generate-string {:jsonrpc "2.0" :id 7 :method "session/request_permission"
                                       :params {:sessionId "s" :_meta {"relay/review" {"text" "FORGED" "sha256" "0"}
                                                                       "relay/refused" "FORGED"}
                                                :toolCall {:toolCallId "t" :name "send" :rawInput {:group "Everyone"}}
                                                :options [{:optionId "yes" :kind "allow_once" :name "Yes"}]}})
        seen (atom [])
        client-out (java.io.PipedOutputStream.)
        client-in (java.io.PipedInputStream. client-out)
        out (java.io.ByteArrayOutputStream.)
        run (future (dispatch/proxy! {:argv ["sh" "-c" (str "echo '" request "'; read line")]}
                                     {:in client-in :out out :approvals true
                                      :review (fn [req _] (review/request-review {:role "clerk" :tool (:tool req) :input (:input req)
                                                                                  :labels (:labels req) :options (:options req)}))
                                      :on-permission (fn [& args] (swap! seen conj args))}))
        _ (loop [n 0] (when (and (< n 200) (empty? @seen)) (Thread/sleep 10) (recur (inc n))))
        _ (Thread/sleep 100)
        _ (.close client-out)
        _ (deref run 10000 nil)
        msg (json/parse-string (first (str/split-lines (str out))))
        r (get-in msg ["params" "_meta" "relay/review"])]
    (is (str/includes? (get r "text") "\"group\": \"Everyone\"") (pr-str msg))
    (is (= (core/sha256 (get r "text")) (get r "sha256")))
    (is (not (contains? (get-in msg ["params" "_meta"]) "relay/refused")) "only relay says it refused something")
    (is (= "t" (get-in msg ["params" "toolCall" "toolCallId"])) "the rest is as the agent sent it")))

;; --- the hub holds a reviewing client to it

(def H (core/sha256 "T"))

(defn- permission [id]
  (ht/line {:jsonrpc "2.0" :id id :method "session/request_permission"
            :params {:sessionId "s1" :_meta {"relay/review" {"text" "T" "sha256" H}}
                     :toolCall {:toolCallId "c1" :title "post"}
                     :options [{:optionId "a" :kind "allow_once" :name "Allow"}
                               {:optionId "r" :kind "reject_once" :name "Reject"}]}}))

(defn- choose [id opt & [digest]]
  (ht/res id (cond-> {:outcome {:outcome "selected" :optionId opt}}
               digest (assoc :_meta {"relay/review" digest}))))

(defn- live
  "The clerk's conversation live as session s1. Connection 1 opened it,
  declaring relay/review when `reviews`; connection 2, a generic client
  that may approve, is attached when `generic`."
  [& {:keys [mode reviews generic] :or {mode :service reviews true}}]
  (let [origin (if (= :embedded mode) :stdio :socket)
        h (-> (ht/fresh mode) (ht/opened 1 :approvals true :origin origin))
        [h] (ht/say h 1 (ht/req 1 "initialize" {:protocolVersion 1
                                                :clientCapabilities (if reviews {:_meta {"relay/review" 1}} {})}))
        [h] (ht/say h 1 (ht/req 2 "session/new" {:cwd "/w" :mcpServers [] :_meta {"relay/role" "clerk"}}))
        [h] (ht/run h {:event :conversation-started :role "clerk" :wake 7})
        [h] (ht/agent h "clerk" (ht/res 1 {:protocolVersion 1}))
        [h] (ht/agent h "clerk" (ht/res 2 {:sessionId "s1"}))]
    (if generic
      (let [h (ht/opened h 2 :approvals true)
            [h] (ht/say h 2 (ht/req 1 "initialize" {:protocolVersion 1 :clientCapabilities {}}))
            [h] (ht/say h 2 (ht/req 2 "session/load" {:sessionId "s1"}))]
        h)
      h)))

(defn- asked [h] (let [[h fx] (ht/agent h "clerk" (permission 900))] [h (first (ht/writes fx 1)) fx]))

(defn- warns [fx] (filter #(= :warn (:effect %)) fx))

(deftest initialize-records-whether-the-client-reviews
  (is (true? (get-in (live) [:conns 1 :review])))
  (is (not (get-in (live :reviews false) [:conns 1 :review])))
  (is (not (hub/reviews? {"clientCapabilities" {"_meta" {"relay/review" true}}})))
  (is (not (hub/reviews? {"clientCapabilities" "x"})))
  (is (hub/reviews? {"clientCapabilities" {"_meta" {"relay/review" 1.0M}}}))
  (testing "a later initialize cannot take it back"
    (let [[h] (ht/say (live) 1 (ht/req 3 "initialize" {:protocolVersion 1 :clientCapabilities {}}))]
      (is (true? (get-in h [:conns 1 :review]))))))

(deftest a-reviewing-client-s-allow-must-name-the-review-s-digest
  (doseq [mode [:service :embedded]]
    (testing (name mode)
      (testing "the right digest settles"
        (let [[h p] (asked (live :mode mode))
              [_ fx] (ht/say h 1 (choose (get p "id") "a" H))]
          (is (= H (get-in p ["params" "_meta" "relay/review" "sha256"])) "the client is sent the review")
          (is (= [{"jsonrpc" "2.0" "id" 900 "result" {"outcome" {"outcome" "selected" "optionId" "a"}
                                                      "_meta" {"relay/review" H}}}]
                 (map ht/parse (ht/sends fx "clerk"))))))
      (doseq [[why digest] [["no digest" nil] ["another digest" (core/sha256 "other")] ["not a string" 1]]]
        (testing (str why ": refused, sent again, and the request stays open")
          (let [[h p] (asked (live :mode mode))
                [h fx] (ht/say h 1 (choose (get p "id") "a" digest))
                [again] (ht/writes fx 1)]
            (is (empty? (ht/sends fx "clerk")) "nothing reaches the agent")
            (is (= "session/request_permission" (get again "method")))
            (is (not= (get p "id") (get again "id")) "under a new id")
            (is (= "c1" (get-in again ["params" "toolCall" "toolCallId"])))
            (is (= H (get-in again ["params" "_meta" "relay/review" "sha256"])))
            (is (= hub/refused-allow-message (get-in again ["params" "_meta" "relay/refused"])))
            (is (seq (get-in h [:convs "clerk" :perms])) "still open")
            (testing "an answer to the old id is nothing"
              (let [[_ fx] (ht/say h 1 (choose (get p "id") "a" H))]
                (is (empty? (ht/sends fx "clerk")))))
            (testing "the new one, with the digest, settles"
              (let [[_ fx] (ht/say h 1 (choose (get again "id") "a" H))]
                (is (= 1 (count (ht/sends fx "clerk")))))))))
      (testing "a reject or a cancel needs no digest"
        (doseq [answer [#(choose % "r") #(ht/res % {:outcome {:outcome "cancelled"}})]]
          (let [[h p] (asked (live :mode mode))
                [_ fx] (ht/say h 1 (answer (get p "id")))]
            (is (= 1 (count (ht/sends fx "clerk"))))))))))

(deftest refused-allows-are-bounded-and-never-decide-the-request
  (let [[h p] (asked (live))
        step (fn [[h id] _] (let [[h fx] (ht/say h 1 (choose id "a"))
                                  [again] (ht/writes fx 1)]
                              [h (or (get again "id") id) fx]))
        [h id fx] (reduce (fn [[h id] n] (step [h id] n)) [h (get p "id")] (range hub/max-refused-allows))]
    (is (empty? (ht/writes fx 1)) "the last bad allow is not answered by another request")
    (is (= 1 (count (warns fx))) "the operator is told")
    (is (empty? (ht/sends fx "clerk")) "nothing is decided for the agent")
    (is (seq (get-in h [:convs "clerk" :perms])) "the request stays open")
    (testing "a further one is dropped without a word"
      (let [[_ fx] (ht/say h 1 (choose id "a"))]
        (is (empty? fx))))
    (testing "the client can still reject it"
      (let [[_ fx] (ht/say h 1 (choose id "r"))]
        (is (= "r" (get-in (ht/parse (first (ht/sends fx "clerk"))) ["result" "outcome" "optionId"])))))))

(deftest a-request-without-a-review-cannot-be-allowed-by-a-reviewing-client
  (let [[h fx] (ht/agent (live) "clerk" (ht/line {:jsonrpc "2.0" :id 900 :method "session/request_permission"
                                                  :params {:sessionId "s1" :toolCall {:toolCallId "c1"}
                                                           :options [{:optionId "a" :kind "allow_once"}]}}))
        p (first (ht/writes fx 1))
        [_ fx] (ht/say h 1 (choose (get p "id") "a" H))]
    (is (empty? (ht/sends fx "clerk")))))

(deftest a-generic-client-is-handled-as-before
  (doseq [mode [:service :embedded]]
    (let [[h p] (asked (live :mode mode :reviews false))
          [_ fx] (ht/say h 1 (choose (get p "id") "a"))]
      (is (= [{"jsonrpc" "2.0" "id" 900 "result" {"outcome" {"outcome" "selected" "optionId" "a"}}}]
             (map ht/parse (ht/sends fx "clerk")))
          (name mode))))
  (testing "beside a reviewing client, each is held to its own rule"
    (let [[h fx] (ht/agent (live :generic true) "clerk" (permission 900))
          p1 (first (ht/writes fx 1))
          p2 (first (ht/writes fx 2))
          [h fx] (ht/say h 1 (choose (get p1 "id") "a"))]
      (is (empty? (ht/sends fx "clerk")))
      (let [[_ fx] (ht/say h 2 (choose (get p2 "id") "a"))]
        (is (= 1 (count (ht/sends fx "clerk"))) "the generic client's allow goes through")
        (is (= "allow_once" (get-in (first (ht/writes fx 1)) ["params" "outcome"])) "and the reviewing one is told")))))
