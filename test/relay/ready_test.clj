(ns relay.ready-test
  "What is ready for the operator (relay.ready): the fold over the state,
  where each thing came from in plain words, the wire and its changes,
  what an edit call prepared, the operator's reply, `relay ready` and the
  client channel's _relay/ready and _relay/reply."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [cheshire.core :as json]
            [relay.core :as core]
            [relay.core-test :as ct]
            [relay.dispatch :as dispatch]
            [relay.harness.agentloop :as agentloop]
            [relay.hub-test :as ht]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.move-view :as move-view]
            [relay.ready :as ready]
            [relay.render :as render]
            [relay.test-support :as support]
            [relay.text :as text]
            [relay.tools :as tools]
            [relay.workspace :as workspace])
  (:import (java.time Instant ZoneId)))

(defn- woken [w role]
  (-> w (ct/run "operator" :memo/send {:to role :subject "s" :body "b"})
      (ct/run "dispatcher" :wake/start {:role role})))

(defn- seed []
  (-> (ct/world)
      (ct/run "operator" :role/add {:id "clerk" :title "Clerk" :instructions "x"})
      (woken "clerk")))

(defn- as-role
  "Runs a command as a role in its wake, with these labels."
  [w role type args labels]
  (let [wake (support/calling-wake (:state w) role)
        [state events] (core/run-command (:state w)
                                         {:type type :actor role :labels labels :args args
                                          :session-wake (:id wake)}
                                         {:now (:now w) :config ct/cfg})]
    (-> w (assoc :state state :rejected nil :last events) (update :events into events))))

(defn- items [w] (ready/ready (:state w)))
(defn- kinds [w] (mapv :kind (items w)))

;; --- the fold

(deftest everything-prepared-for-the-operator-is-one-list-newest-first
  (let [w (-> (seed)
              (ct/run "clerk" :message/send {:subject "Concert email is drafted"
                                             :sections [{:items ["It is in your drafts."]}]})
              (ct/advance 60)
              (ct/run "clerk" :prepared/record {:call "c1" :tool "campaign-draft-demo" :title "Concert in May"
                                                :where "Saved draft c1, version 1, to the group Subscribers."
                                                :preview "Hello, the choir sings on May 9."})
              (ct/advance 60)
              (ct/run "clerk" :calendar/add {:title "Choir concert" :start "2026-11-20 19:00"})
              (ct/advance 60)
              (ct/run "clerk" :card/add {:kind "checklist" :title "Venues to call" :items ["Town hall" "Library"]})
              (ct/advance 60)
              (ct/run "operator" :card/add {:kind "note" :title "My own card" :text "mine"}))]
    (is (nil? (:rejected w)))
    (is (= [:finding :event :draft :message] (kinds w))
        "newest first; a card the operator made is not a finding")
    (let [by (group-by :kind (items w))
          draft (first (:draft by))]
      (is (= "Concert in May" (:title draft)))
      (is (= ["Saved draft c1, version 1, to the group Subscribers." "Hello, the choir sings on May 9."]
             (:lines draft)))
      (is (= ["0 of 2 done"] (:lines (first (:finding by)))))
      (is (= (:message (first (:message by))) (:reply-to (first (:message by)))) "a role's message can be answered"))))

(deftest removed-and-ended-events-are-not-ready
  (let [w (-> (seed)
              (ct/run "clerk" :calendar/add {:title "Old" :start "2026-10-01 09:00" :end "2026-10-01 10:00"})
              (ct/run "clerk" :calendar/add {:title "Gone" :start "2026-12-02 09:00"}))
        gone (:seq (last (:last w)))
        w (-> w
              (ct/run "clerk" :calendar/add {:title "Soon" :start "2026-12-01 09:00"})
              (ct/run "clerk" :calendar/remove {:event gone}))]
    (is (nil? (:rejected w)))
    (is (= ["Soon"] (mapv :title (items w))))))

(deftest relays-own-messages-have-nobody-to-answer-them
  (let [w (-> (seed) (ct/run "operator" :memo/send {:to "operator" :subject "Hi" :body "x"}))
        msgs (filter #(= :message (:kind %)) (items w))]
    (is (seq msgs))
    (is (every? #(nil? (:reply-to %)) msgs))))

(defspec the-view-is-newest-first-and-bounded 30
  (prop/for-all [n (gen/choose 0 12) limit (gen/choose 1 15)]
    (let [w (reduce (fn [w i] (-> w (ct/advance 5)
                                  (ct/run "clerk" :message/send {:subject (str "m" i) :sections [{:items ["x"]}]})))
                    (seed) (range n))
          xs (ready/ready (:state w) {:limit limit})
          ats (map #(.toEpochMilli ^Instant (:at %)) xs)]
      (and (= (min n limit) (count (filter #(= :message (:kind %)) xs)))
           (<= (count xs) limit)
           (= ats (reverse (sort ats)))))))

(defspec items-at-the-same-time-are-newest-first-by-the-logs-order 50
  ;; Several things made in one tick share a time; the log's order says
  ;; which came last.
  (prop/for-all [kinds (gen/vector (gen/elements [:message :draft :card]) 1 8)]
    (let [w (reduce (fn [w [i k]]
                      (case k
                        :message (ct/run w "clerk" :message/send {:subject (str "m" i) :sections [{:items ["x"]}]})
                        :draft (ct/run w "clerk" :prepared/record {:call (str "c" i) :tool "t" :title (str "d" i) :where "w"})
                        :card (ct/run w "clerk" :card/add {:kind "note" :title (str "k" i) :text "t"})))
                    (seed) (map-indexed vector kinds))
          xs (ready/ready (:state w))
          seqs (map :seq xs)
          wire (mapv ready/wire xs)]
      (and (= (count kinds) (count xs))
           (apply = (map :at xs))
           (= seqs (reverse (sort seqs)))
           (= seqs (map #(get % "seq") wire))))))

(deftest the-order-is-total
  (let [at (Instant/parse "2026-10-26T12:00:00Z")
        a {:id "card:3" :at at :seq 3} b {:id "message:5" :at at :seq 5}
        c {:id "event:1" :at (.plusSeconds at 1) :seq 1}]
    (is (= [c b a] (sort ready/newer-first [a b c])))
    (is (= [c b a] (sort ready/newer-first [b c a])))))

(def gen-move-call
  (gen/let [n (gen/choose 1 4)
            status (gen/elements [:moving :moved])
            done (gen/fmap #(vec (sort (distinct %))) (gen/vector (gen/choose 0 (dec n)) 0 n))
            undone (gen/fmap #(vec (sort (distinct %))) (gen/vector (gen/choose 0 (dec n)) 0 n))
            stopped (gen/elements [nil "it could not read it"])]
    (let [done (if (= :moving status) [] done)]
      {:id 7 :actor "steward" :at (Instant/parse "2026-10-26T12:00:00Z") :labels ["private"] :status status
       :moves (vec (for [i (range n)] {:from (str "/f/a" i) :to (str "/f/b" i)}))
       :done (mapv (fn [i] {:index i}) done)
       :undone (vec (filter (set done) undone)) :stopped (when (= :moved status) stopped)})))

(defspec a-move-offers-undo-exactly-while-there-is-something-to-undo 200
  (prop/for-all [c gen-move-call]
    (let [it (first (filter #(= :move (:kind %)) (ready/ready (assoc-in core/empty-state [:moves 7] c))))
          left (core/move-steps-left c)
          moving? (= :moving (:status c))]
      (and (= (boolean (:undo it)) (boolean (or moving? (seq left))))
           (= (:undone it) (boolean (and (not moving?) (seq (:done c)) (empty? left))))
           (nil? (ready/provenance (:labels it)))
           (every? #(str/includes? (str/join "\n" (:lines it)) (subs (:from ((:moves c) (:index %))) 3)) (:done c))
           (not (str/includes? (str/join "\n" (:lines it)) "/f/"))
           (not (str/includes? (str/join "\n" (:lines it)) "By steward"))))))

;; --- file moves read relative to the granted folder

(deftest a-move-reads-relative-to-the-granted-folder
  (let [roots ["/Users/k/notes"]
        shown [[0 {:from "/Users/k/notes/board mtg.md" :to "/Users/k/notes/meetings/2026-09-01-board-meeting.md"}]
               [1 {:from "/Users/k/notes/rehearsal.txt" :to "/Users/k/notes/choir/rehearsal.txt"}]
               [2 {:from "/Users/k/notes/a.txt" :to "/Users/k/notes/choir/a.txt"}]]]
    (is (= {:title "3 files"
            :lines ["3 files in notes, into 2 folders"
                    "board mtg.md → meetings/2026-09-01-board-meeting.md"
                    "rehearsal.txt → choir/rehearsal.txt"
                    "a.txt → choir/a.txt (undone)"]}
           (move-view/view roots shown #{2})))
    (is (= {:title "board mtg.md"
            :lines ["1 file in notes" "board mtg.md → meetings/2026-09-01-board-meeting.md"]}
           (move-view/view roots (take 1 shown) #{})))
    (testing "several granted folders: each line names its folder"
      (is (= ["2 files in notes, docs, into 2 folders" "notes: a.txt → b/a.txt" "docs: c.txt → d/c.txt"]
             (:lines (move-view/view ["/h/notes" "/h/docs"]
                                      [[0 {:from "/h/notes/a.txt" :to "/h/notes/b/a.txt"}]
                                       [1 {:from "/h/docs/c.txt" :to "/h/docs/d/c.txt"}]]
                                      #{})))))
    (testing "a call recorded without its folders uses the folder its files share"
      (is (= ["2 files in notes, into 2 folders" "x/a.txt → y/a.txt" "x/b.txt → z/b.txt"]
             (:lines (move-view/view nil
                                      [[0 {:from "/h/notes/x/a.txt" :to "/h/notes/y/a.txt"}]
                                       [1 {:from "/h/notes/x/b.txt" :to "/h/notes/z/b.txt"}]]
                                      #{})))))))

(def gen-segment (gen/elements ["a" "b c" "x.md" "2026-09-01" "notes"]))

(defspec move-lines-are-one-per-file-and-never-hold-the-granted-path 200
  (prop/for-all [n (gen/choose 1 30)
                 dirs (gen/vector (gen/vector gen-segment 0 2) 1 30)
                 names (gen/vector gen-segment 30)]
    (let [root "/private/tmp/claude-501/-Users-k-code/granted/notes"
          shown (vec (for [i (range n)
                           :let [d (str/join "/" (nth dirs (mod i (count dirs))))]]
                       [i {:from (str root "/" (nth names i) i) :to (str root "/" (when (seq d) (str d "/")) (nth names i) i)}]))
          {:keys [title lines]} (move-view/view [root] shown #{})]
      (and (= (inc n) (count lines))
           (= (if (= 1 n) (str (nth names 0) 0) (str n " files")) title)
           (str/starts-with? (first lines) (str n (if (= 1 n) " file" " files") " in notes"))
           (not-any? #(or (str/includes? % "/private") (str/includes? % "\n")) lines)
           (every? #(str/includes? % " → ") (rest lines))))))

;; --- a daily note's numbered items

(deftest a-notes-numbered-items-carry-their-own-numbers-and-whether-they-were-done
  (let [m {:id 5 :kind "digest" :subject "Daily note" :created-at "2026-10-26T12:00:00Z" :labels []
           :origin "daily-note" :acted {3 "dismissed"}
           :sections [{:heading "Changes to check"
                       :items [{:text "1. A change" :action {:op "revert" :n 1}}
                               {:text "And 2 more changes"}]}
                      {:heading "Ahead"
                       :items [{:text "3. Dentist" :action {:op "dismiss" :n 3}}
                               {:text "4. Taxes" :action {:op "dismiss" :n 4}}]}]}
        it (first (filter #(= :message (:kind %)) (ready/ready (assoc-in core/empty-state [:messages 5] m))))]
    (is (= [{:n 1 :op "revert" :line 0 :done nil}
            {:n 3 :op "dismiss" :line 2 :done "dismissed"}
            {:n 4 :op "dismiss" :line 3 :done nil}]
           (:acts it)))
    (is (= [{"n" 1 "op" "revert" "line" 0 "done" nil}
            {"n" 3 "op" "dismiss" "line" 2 "done" "dismissed"}
            {"n" 4 "op" "dismiss" "line" 3 "done" nil}]
           (get (ready/wire it) "acts")))
    (is (every? #(str/starts-with? (nth (:lines it) (:line %)) (str (:n %) ". ")) (:acts it))
        "a line's own number is the one the act uses")))

;; --- where a thing came from

(deftest a-source-reads-as-where-it-came-from
  (is (nil? (ready/provenance [])))
  (is (nil? (ready/provenance ["private"])) "her own things say nothing")
  (is (= "Drew on: a mail from x@y.org" (ready/provenance ["private" "mail:x@y.org"])))
  (is (= "Drew on: the web page office-holidays.html" (ready/provenance ["contaminated" "web:office-holidays.html"]))
      "a source we know wins over the bare mark")
  (is (= "Drew on: something from outside" (ready/provenance ["contaminated"])))
  (is (= "Drew on: a mail from a@b; the web page a.html" (ready/provenance ["web:a.html" "mail:a@b"]))))

(def gen-label
  (gen/one-of [(gen/elements ["private" "contaminated" "web" "mail" "github" "mail:a@b.org" "web:x.org" "github:o/r"])
               gen/string
               (gen/fmap #(str "mail:" %) gen/string)]))

(defspec provenance-never-shows-a-label-name 200
  (prop/for-all [labels (gen/vector gen-label 0 5)]
    (let [p (ready/provenance labels)]
      (or (nil? p)
          (and (str/starts-with? p "Drew on: ")
               (not (re-find #"contaminated|private" p))
               (not (re-find #"[\u0000-\u0008\u000a-\u001f\u007f‮​]" p)))))))

(deftest a-mail-label-is-shown-where-the-operator-reads-it
  (let [w (-> (seed)
              (ct/run "clerk" :prepared/record {:call "c1" :tool "campaign-draft-demo" :title "Reply to the booker"
                                           :where "Saved."}))
        ;; labels beyond contaminated and private arrive with the mailbox (B8)
        it (assoc (first (items w)) :labels ["contaminated" "mail:booker@example.org" "private"]
                  :sources ["mail:booker@example.org"])
        wire (ready/wire it)]
    (is (= "Drew on: a mail from booker@example.org" (get wire "from")))
    (is (not (str/includes? (json/generate-string wire) "mail:booker")))
    (is (= true (get wire "outsider")))))

(deftest a-contaminated-call-shows-where-it-came-from
  (let [w (as-role (seed) "clerk" :prepared/record {:call "c1" :tool "campaign-draft-demo" :title "T" :where "W"}
                   ["contaminated" "private"])]
    (is (= "Drew on: something from outside" (get (ready/wire (first (items w))) "from")))))

(deftest the-wire-never-says-contaminated
  (let [w (-> (seed)
              (as-role "clerk" :message/send {:subject "Look https://evil.example" :sections [{:items ["go https://evil.example"]}]}
                       ["contaminated" "private"]))
        wire (ready/wire (first (items w)))
        s (json/generate-string wire)]
    (is (not (re-find #"contaminated|private" s)))
    (is (= "Drew on: something from outside" (get wire "from")))
    (is (true? (get wire "outsider")))
    (is (= "Look hxxps://evil[.]example" (get wire "title")) "links are broken, no mark of its own")
    (is (= ["go hxxps://evil[.]example"] (get wire "lines")) "and so are the links in its lines")))

(def hostile-chars ["a" "b" " " "\n" "\u001b" "\u202e" "\u200b" "\u0007" "\u2066" "\u007f" "é"
                    "http://evil.example/?d=1" "https://x.org/a" " https://q.io "])

(def gen-hostile
  (gen/fmap str/join (gen/vector (gen/elements hostile-chars) 0 12)))

(def gen-outside-labels
  (gen/fmap (fn [[l more]] (vec (cons l more)))
            (gen/tuple (gen/elements ["contaminated" "web:a.html" "mail:a@b.org" "github:o/r"])
                       (gen/vector (gen/elements ["private" "web:a.html"]) 0 2))))

(def unsafe-chars #"[\u0000-\u0008\u000b-\u001f\u007f\u200b-\u200f\u202a-\u202e\u2066-\u2069]")

(defn- hostile-items
  "One item of every kind, built from these texts and labels."
  [title line labels]
  [{:id "message:1" :kind :message :at (Instant/parse "2026-10-26T12:00:00Z") :message 1 :message-kind :note
    :title title :lines [line] :labels labels :replies [] :reply-to 1}
   {:id "prepared:2" :kind :draft :at (Instant/parse "2026-10-26T12:00:00Z") :title title :lines [line line] :labels labels}
   {:id "event:3" :kind :event :at (Instant/parse "2026-10-26T12:00:00Z") :title title :lines [line]
    :labels labels :start (Instant/parse "2026-11-20T19:00:00Z") :end nil}
   {:id "card:4" :kind :finding :at (Instant/parse "2026-10-26T12:00:00Z") :title title :lines [line] :labels labels :card 4}])

(defspec what-the-operator-is-sent-is-safe-and-from-outside-has-no-live-links 300
  (prop/for-all [title gen-hostile line gen-hostile labels gen-outside-labels]
    (let [wires (mapv ready/wire (hostile-items title line labels))
          shown (render/ready wires (ZoneId/of "UTC") (Instant/parse "2026-10-27T12:00:00Z"))
          texts (fn [w] (cons (get w "title") (get w "lines")))]
      (and (every? true? (map #(get % "outsider") wires))
           (not-any? #(re-find unsafe-chars %) shown)
           (every? (fn [w] (not-any? #(or (re-find unsafe-chars %) (re-find #"https?://" %)) (texts w))) wires)
           (not-any? #(re-find #"https?://" %) shown)))))

(def gen-long-sources
  ;; Several named sources make the longest "Drew on:" lines.
  (gen/vector (gen/elements ["web:https://example.org/a/very/long/path/to/office-holidays-2026.html"
                             "mail:someone.with.a.long.name@lanternhall.example" "github:kira/shelf"
                             "ics:work-calendar" "search:web" "contaminated"])
              1 5))

(defspec every-line-of-relay-ready-fits-a-phone 200
  ;; Regression (U1b review): the "Drew on:" footer and preview lines ran
  ;; past 60 columns.
  (prop/for-all [title gen-hostile line gen-hostile srcs gen-long-sources]
    (let [wires (mapv #(ready/wire (assoc % :sources srcs)) (hostile-items title line (vec (cons "contaminated" srcs))))
          now (Instant/parse "2026-10-27T12:00:00Z")
          lines (concat (render/ready wires (ZoneId/of "UTC") now)
                        (mapcat #(render/ready-one % (ZoneId/of "UTC") now) wires))]
      (every? #(<= (count %) render/ready-width) lines))))

(defspec her-own-text-is-only-made-visible 200
  (prop/for-all [title gen-hostile line gen-hostile]
    (let [wires (mapv ready/wire (hostile-items title line ["private"]))]
      (and (every? #(false? (get % "outsider")) wires)
           (every? #(nil? (get % "from")) wires)
           (every? (fn [w] (not-any? #(re-find unsafe-chars %) (cons (get w "title") (get w "lines")))) wires)
           ;; links are left alone
           (every? (fn [w] (= (boolean (re-find #"https?://" (str title " " line)))
                              (boolean (re-find #"https?://" (str/join " " (cons (get w "title") (get w "lines")))))))
                   (take 1 wires))))))

;; --- changes

(def gen-wire-item
  (gen/fmap (fn [[id t]] {"id" (str "message:" id) "kind" "message" "title" t "lines" []})
            (gen/tuple (gen/choose 1 6) (gen/elements ["a" "b" "c"]))))

(defn- uniq [xs] (vec (vals (ready/by-id xs))))

(defspec a-client-that-applies-changes-holds-the-view 300
  (prop/for-all [sent (gen/fmap uniq (gen/vector gen-wire-item 0 6))
                 cur (gen/fmap uniq (gen/vector gen-wire-item 0 6))]
    (let [held (ready/by-id sent)
          params (ready/changed 9 held cur)]
      (and (= (ready/by-id cur) (ready/apply-changed held (or params {"items" [] "gone" []})))
           (= (nil? params) (= held (ready/by-id cur)))))))

;; --- what an edit call prepared

(def transcript
  "A session as agentloop writes it: a decision holds only the labels the
  policy adds for that call (a tool's static labels), a result the labels it
  added, and the session's starting labels an entry of their own."
  (str/join "\n"
            (map json/generate-string
                 [{:type "labels" :labels {:labels ["private"] :source "seed"}}
                  {:type "message" :message {:message {:role "assistant"
                                                       :parts [{:type "tool_call" :tool_call {:id "w" :name "web-demo" :input {:url "http://x"}}}
                                                               {:type "tool_call" :tool_call {:id "a" :name "campaign-draft-demo"
                                                                                              :input {:subject "Concert" :body "Hello" :group "All"}}}
                                                               {:type "tool_call" :tool_call {:id "b" :name "campaign-draft-demo" :input {:subject "x"}}}
                                                               {:type "tool_call" :tool_call {:id "c" :name "campaign-draft-demo" :input {:subject "y"}}}
                                                               {:type "tool_call" :tool_call {:id "d" :name "remind-demo" :input {}}}]}}}
                  {:type "decision" :decision {:call_id "w" :tool "web-demo" :verdict "allow"}}
                  {:type "tool_result" :labels ["contaminated"]
                   :tool_result {:result {:call_id "w" :content "A page."} :labels ["contaminated"]}}
                  {:type "decision" :decision {:call_id "a" :tool "campaign-draft-demo" :verdict "allow"}}
                  {:type "decision" :decision {:call_id "b" :tool "campaign-draft-demo" :verdict "deny"}}
                  {:type "decision" :decision {:call_id "c" :tool "campaign-draft-demo" :verdict "allow"}}
                  {:type "decision" :decision {:call_id "d" :tool "remind-demo" :verdict "allow"}}
                  {:type "tool_result" :tool_result {:result {:call_id "a" :content "Saved draft c1, version 1.\nEarlier versions are kept."}}}
                  {:type "tool_result" :tool_result {:result {:call_id "c" :content "A draft needs a body." :is_error true}}}
                  {:type "tool_result" :tool_result {:result {:call_id "d" :content "Set."}}}])))

(deftest calls-are-read-out-of-a-session
  (is (= [{:call "w" :tool "web-demo" :input {"url" "http://x"} :result "A page." :labels ["contaminated" "private"]}
          {:call "a" :tool "campaign-draft-demo" :input {"subject" "Concert" "body" "Hello" "group" "All"}
           :result "Saved draft c1, version 1.\nEarlier versions are kept." :labels ["contaminated" "private"]}
          {:call "d" :tool "remind-demo" :input {} :result "Set." :labels ["contaminated" "private"]}]
         (agentloop/parse-calls transcript))
      "allowed calls that ended without an error; a refused call and a failed one prepared nothing; the labels are what the session held, not what the decision added"))

(deftest a-draft-after-a-contaminated-read-is-from-outside
  (let [only-private (str/join "\n" (take 1 (str/split-lines transcript)))
        calls (agentloop/parse-calls transcript)
        draft (first (filter #(= "a" (:call %)) calls))]
    (testing "nothing outside was read: the draft is hers"
      (is (empty? (agentloop/parse-calls only-private))))
    (testing "the draft's labels make it from outside, with links broken"
      (let [w (as-role (seed) "clerk" :prepared/record
                       (ready/prepared-args (assoc draft :input {"subject" "S" "body" "http://evil.example/?d=secret"}))
                       (:labels draft))
            wire (ready/wire (first (items w)))]
        (is (true? (get wire "outsider")))
        (is (= "Drew on: something from outside" (get wire "from")))
        (is (= ["Saved draft c1, version 1." "hxxp://evil[.]example/?d=secret"] (get wire "lines")))))))

(deftest a-call-becomes-a-title-a-place-and-a-preview
  (is (= {:call "a" :tool "campaign-draft-demo" :title "Concert" :where "Saved draft c1, version 1." :preview "Hello"}
         (ready/prepared-args (second (agentloop/parse-calls transcript)))))
  (is (= "campaign draft demo" (:title (ready/prepared-args {:call "z" :tool "campaign-draft-demo" :input {} :result ""})))
      "no title in the input: the tool's name in words"))

(defspec prepared-args-is-total 200
  (prop/for-all [tool gen/string input (gen/map gen/string (gen/one-of [gen/string gen/int]))
                 result gen/string]
    (let [a (ready/prepared-args {:call "c" :tool tool :input input :result result})]
      (and (string? (:title a)) (string? (:where a))))))

(deftest a-prepared-call-is-recorded-once
  (let [args {:call "c1" :tool "campaign-draft-demo" :title "Concert" :where "Saved."}
        w (-> (seed) (ct/run "clerk" :prepared/record args))
        again (ct/run w "clerk" :prepared/record args)]
    (is (= 1 (count (:prepared (:state w)))))
    (is (= 1 (count (:prepared (:state again)))) "read twice, recorded once")
    (is (empty? (:last again)))
    (is (= :forbidden (:rejected (ct/run w "operator" :prepared/record (assoc args :call "c2")))))
    (is (= :invalid (:rejected (ct/run w "clerk" :prepared/record (assoc args :call "c3" :title " ")))))
    (testing "long text is cut and kept on one line"
      (let [w (ct/run w "clerk" :prepared/record (assoc args :call "c4" :title (str "a\n" (apply str (repeat 500 "b")))
                                                        :where (apply str (repeat 900 "w"))))
            p (last (sort-by :id (vals (:prepared (:state w)))))]
        (is (= 200 (count (:title p))))
        (is (not (str/includes? (:title p) "\n")))
        (is (= 400 (count (:where p))))))))

(deftest a-public-role-prepares-nothing
  (let [w (-> (ct/world)
              (ct/run "operator" :role/add {:id "courier" :title "Courier" :instructions "x"} true)
              (woken "courier")
              (ct/run "courier" :prepared/record {:call "c" :tool "t" :title "x" :where "y"}))]
    (is (= :forbidden (:rejected w)))))

;; --- replying

(defn- told-w []
  (-> (seed)
      (as-role "clerk" :message/send {:subject "Concert email is drafted" :sections [{:items ["See your drafts."]}]}
               ["contaminated" "private"])))

(defn- msg-id [w] (:id (first (filter #(= "clerk" (:role %)) (vals (:messages (:state w)))))))

(deftest a-reply-reaches-the-role-that-told-the-operator
  (let [w (told-w)
        id (msg-id w)
        w (ct/run w "operator" :message/reply {:in-reply-to id :text "Make it shorter, please."})
        memo (last (sort-by :id (vals (:memos (:state w)))))
        m (get-in w [:state :messages id])]
    (is (nil? (:rejected w)))
    (testing "recorded against the message"
      (is (= ["Make it shorter, please."] (mapv :text (:replies m))))
      (is (= "client" (:channel (first (:replies m)))))
      (is (= (:seq (first (filter #(= "message/replied" (:type %)) (:events w)))) (:rev m))
          "the client is told it changed")
      (is (= {:message id :memo (:id memo) :channel "client" :text "Make it shorter, please."}
             (:data (first (filter #(= "message/replied" (:type %)) (:events w)))))
          "the same event a verified mail reply makes"))
    (testing "a memo from the operator to the role, saying what it answers"
      (is (= ["operator" "clerk" "Re: Concert email is drafted"] ((juxt :from :to :subject) memo)))
      (is (str/includes? (:body memo) "Make it shorter, please."))
      (is (str/includes? (:body memo) (str "in_reply_to " id))))
    (testing "the memo carries the labels of the subject it quotes"
      (is (some #{"contaminated"} (core/memo-labels (:state w) memo))))
    (testing "the view shows it answered"
      (let [wire (ready/wire (first (filter #(= :message (:kind %)) (items w))))]
        (is (= ["Make it shorter, please."] (map #(get % "text") (get wire "replies"))))))))

(deftest what-a-reply-refuses
  (let [w (told-w)
        id (msg-id w)
        reply (fn [w actor args] (ct/run w actor :message/reply args))]
    (is (= :not-found (:rejected (reply w "operator" {:in-reply-to 9999 :text "x"}))))
    (is (= :forbidden (:rejected (reply w "clerk" {:in-reply-to id :text "x"}))) "only the operator answers")
    (is (= :invalid (:rejected (reply w "operator" {:in-reply-to id :text "  "}))))
    (is (= :invalid (:rejected (reply w "operator" {:in-reply-to id :text (apply str (repeat 4001 "x"))}))))
    (testing "a message from relay itself has nobody to answer it"
      (let [w (ct/run w "operator" :memo/send {:to "operator" :subject "Hi" :body "x"})
            own (:id (first (filter #(nil? (:role %)) (vals (:messages (:state w))))))]
        (is (= :invalid (:rejected (reply w "operator" {:in-reply-to own :text "x"}))))))
    (testing "a role that is gone cannot be answered"
      (let [w (told-w)
            wake (support/calling-wake (:state w) "clerk")
            w (-> w (ct/run "clerk" :memo/close {:memo 1 :outcome "Done."})
                  (ct/run "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :exit 0 :said "x"})
                  (ct/run "operator" :role/remove {:id "clerk"}))]
        (is (nil? (:rejected w)))
        (is (= :not-found (:rejected (reply w "operator" {:in-reply-to (msg-id w) :text "x"}))))))))

(deftest a-memo-a-role-sent-the-operator-can-be-answered-too
  (let [w (-> (seed) (ct/run "clerk" :memo/send {:to "operator" :subject "Draft: concert email" :body "It is saved."}))
        m (first (filter #(= "memo-for-operator" (:origin %)) (vals (:messages (:state w)))))
        item (first (filter #(= (:id m) (:message %)) (items w)))
        _ (is (= [(:memo m)] (map :id (core/unread-memos (:state w) core/operator))) "it waits for her")
        w (ct/run w "operator" :message/reply {:in-reply-to (:id m) :text "Thanks."})
        memo (last (sort-by :id (vals (:memos (:state w)))))]
    (is (= (:id m) (:reply-to item)) "the memo's sender is who answers")
    (is (nil? (:rejected w)))
    (is (= ["operator" "clerk"] ((juxt :from :to) memo)))
    (testing "her answer closes the memo, so it no longer waits in her inbox"
      (is (empty? (core/unread-memos (:state w) core/operator)))
      (let [closed (core/memo (:state w) (:memo m))]
        (is (= [:read "Answered: Thanks." "operator"] ((juxt :status :outcome :closed-by) closed))))
      (is (some #{"Your inbox: 0 open"} (render/status (:state w) (ZoneId/of "UTC") (:now w) ct/cfg []))
          "relay status says nothing is open for her"))
    (testing "a second answer is still threaded, and closes nothing again"
      (let [w (ct/run w "operator" :message/reply {:in-reply-to (:id m) :text "One more thing."})]
        (is (nil? (:rejected w)))
        (is (= 2 (count (:replies (core/message (:state w) (:id m))))))
        (is (empty? (filter #(= "memo/closed" (:type %)) (:last w))))))))

;; --- relay ready

(defn- lines-of [items now]
  (render/ready (mapv ready/wire items) (ZoneId/of "America/Toronto") now))

(deftest relay-ready-is-calm-and-short
  (let [w (-> (seed)
              (ct/run "clerk" :message/send {:subject "Concert email is drafted"
                                             :sections [{:items ["It is in your drafts, ready to read."]}]})
              (ct/advance 60)
              (as-role "clerk" :prepared/record {:call "c1" :tool "campaign-draft-demo" :title "Concert in May"
                                                 :where "Saved draft c1, version 1, to the group Subscribers."}
                       ["contaminated" "private"])
              (ct/advance 60)
              (ct/run "clerk" :calendar/add {:title "Choir concert" :start "2026-11-20 19:00" :location "Town hall"}))
        out (lines-of (items w) (.plusSeconds ^Instant (:now w) 3600))
        mid (msg-id w)]
    (is (= ["Calendar: Choir concert (1h ago)"
            "  Fri 20 Nov 19:00"
            "  Town hall"
            ""
            "Draft: Concert in May (1h ago)"
            "  Saved draft c1, version 1, to the group Subscribers."
            "  Drew on: something from outside"
            ""
            (str "Message " mid ": Concert email is drafted (1h ago)")
            "  It is in your drafts, ready to read."
            (str "  Reply: relay reply " mid " -m \"...\"")]
           out))
    (is (every? #(<= (count %) 60) out))
    (is (not (re-find #"(?i)contaminated|private|label|role|tool" (str/join "\n" out)))))
  (is (= ["Nothing is ready."] (lines-of [] (Instant/now)))))

(deftest relay-ready-says-how-long-ago-briefly
  (let [now (Instant/parse "2026-10-26T12:00:00Z")
        ago #(render/ready-age (str (.minusSeconds now %)) now (ZoneId/of "UTC"))]
    (is (= ["just now" "just now" "5m ago" "3h ago" "2d ago" "16 Oct"]
           (map ago [-30 59 300 (* 3 3600) (* 2 86400) (* 10 86400)])))))

(defn- all-day-wire
  "The wire item of an all-day event added from `first-day` to `last-day`,
  as _relay/ready sends it."
  [first-day last-day]
  (let [w (ct/run (seed) "clerk" :calendar/add (cond-> {:title "Away" :start first-day :all_day true}
                                                  last-day (assoc :end last-day)))]
    (is (nil? (:rejected w)) (:why w))
    (first (filter #(= "event" (get % "kind")) (workspace/ready-items (:state w) {})))))

(deftest an-all-day-event-reaches-the-wire-and-relay-ready-as-days
  ;; The wire says which days, the last one included, and relay ready
  ;; says them with no time of day.
  (let [two (all-day-wire "2026-11-11" "2026-11-12")
        one (all-day-wire "2026-11-11" nil)
        now (Instant/parse "2026-10-26T12:00:00Z")]
    (is (= {"allDay" true "startDate" "2026-11-11" "lastDate" "2026-11-12"}
           (select-keys two ["allDay" "startDate" "lastDate"])))
    (is (= {"allDay" true "startDate" "2026-11-11" "lastDate" "2026-11-11"}
           (select-keys one ["allDay" "startDate" "lastDate"]))
        "a single day ends on the day it starts")
    (let [out-two (render/ready [two] (ZoneId/of "America/Toronto") now)
          out-one (render/ready [one] (ZoneId/of "America/Toronto") now)]
      (is (= "  Wed 11 Nov to Thu 12 Nov, all day" (second out-two)))
      (is (= "  Wed 11 Nov, all day" (second out-one)))
      (is (not-any? #(re-find #"\d\d:\d\d" %) (concat out-two out-one)) "no time of day"))
    (testing "relay ready <n> shows it as days too"
      (let [out (render/ready-one two (ZoneId/of "America/Toronto") now)]
        (is (= "  Wed 11 Nov to Thu 12 Nov, all day" (second out)))
        (is (not-any? #(re-find #"\d\d:\d\d" %) out) "no time of day")))))

(defspec an-all-day-events-last-day-is-its-span-less-one 30
  (prop/for-all [offset (gen/choose 30 400)
                 span (gen/choose 1 5)]
    (let [first-day (.plusDays (java.time.LocalDate/parse "2026-10-26") offset)
          last-day (.plusDays first-day (dec span))
          it (all-day-wire (str first-day) (str last-day))]
      (and (= (str first-day) (get it "startDate"))
           (= (dec span) (.between java.time.temporal.ChronoUnit/DAYS
                                   (java.time.LocalDate/parse (get it "startDate"))
                                   (java.time.LocalDate/parse (get it "lastDate"))))))))

(deftest relay-ready-marks-text-it-cut
  (let [now (Instant/parse "2026-10-26T12:00:00Z")
        out (render/ready [{"id" "prepared:1" "kind" "draft" "title" "Long" "at" (str now)
                            "lines" ["one" "two" "three" "four" "five"]}] (ZoneId/of "UTC") now)]
    (is (= ["Draft: Long (just now)" "  one" "  two" "  three" "  four …" "  All of it: relay ready prepared:1"] out)))
  (testing "a full last line keeps the width"
    (let [now (Instant/parse "2026-10-26T12:00:00Z")
          long (apply str (repeat 58 "x"))
          out (render/ready [{"id" "prepared:1" "kind" "draft" "title" "T" "at" (str now)
                              "lines" [long long long long long]}] (ZoneId/of "UTC") now)]
      (is (str/ends-with? (last (butlast out)) " …"))
      (is (= "  All of it: relay ready prepared:1" (last out)))
      (is (every? #(<= (count %) 60) out))))
  (testing "nothing is marked when nothing was cut"
    (let [now (Instant/parse "2026-10-26T12:00:00Z")]
      (is (not-any? #(str/includes? % "…")
                    (render/ready [{"id" "prepared:1" "kind" "draft" "title" "T" "at" (str now) "lines" ["a" "b"]}]
                                  (ZoneId/of "UTC") now))))))

(def gen-text
  (gen/fmap str/join (gen/vector (gen/elements ["a" "bb" " " "\n" "\n\n" "Draft: " "Message 1: " "verylongwordverylongword" "é" "‮" "x@y.org"]) 0 40)))

(defspec relay-ready-fits-a-phone-and-hostile-text-never-starts-a-line 300
  (prop/for-all [title gen-text body gen-text]
    (let [wire [{"id" "message:1" "kind" "message" "message" 1 "title" title "lines" [body body]
                 "from" "from the web" "at" "2026-10-26T12:00:00Z" "replyTo" 1 "replies" []}
                {"id" "event:2" "kind" "event" "title" title "lines" [body]
                 "start" "2026-11-20T19:00:00Z" "at" "2026-10-26T12:00:00Z"}]
          out (render/ready wire (ZoneId/of "UTC") (Instant/parse "2026-10-26T13:00:00Z"))
          headings (remove #(or (str/blank? %) (str/starts-with? % " ")) out)]
      (and (every? #(<= (count %) 60) out)
           ;; the only lines at the left edge are relay's own headings
           (every? #(re-find #"^(Message 1|Calendar):" %) headings)
           (= 2 (count headings))))))

(deftest relay-ready-one-shows-a-whole-item
  (let [now (Instant/parse "2026-10-26T12:00:00Z")
        lines (for [i (range 12)] (str "Line " i " " (apply str (repeat 80 "w ")) "end"))
        it {"id" "message:28" "kind" "message" "message" 28 "title" "Prep checklist" "at" (str now)
            "lines" (vec lines) "from" "from the web" "replyTo" 28
            "replies" [{"at" (str now) "text" (apply str (repeat 70 "r "))}]}
        out (render/ready-one it (ZoneId/of "UTC") now)
        text (str/join " " (map str/trim out))]
    (is (= "Message 28: Prep checklist (just now)" (first out)))
    (is (every? #(<= (count %) 60) out))
    (is (every? #(str/includes? text (str "Line " % " ")) (range 12)) "nothing is cut")
    (is (str/includes? text "end") )
    (is (not-any? #(str/includes? % "…") out))
    (is (some #{"  from the web"} out))
    (is (some #(str/starts-with? % "  You replied: r r") out) "the whole reply")
    (testing "a cut item in the list says how to open it"
      (let [out (render/ready [it] (ZoneId/of "UTC") now)]
        (is (some #{"  All of it: relay ready 28"} out))))))

(deftest relay-ready-with-a-number-prints-one-item-whole
  (let [dir (str (fs/create-temp-dir {:prefix "relay-ready-one"}))
        data (str dir "/data")
        run (fn [& args] ((requiring-resolve 'relay.cli/execute) (into ["--data" data] args)))]
    (try
      (fs/create-dirs data)
      (spit (str data "/relay.edn") (pr-str {:timezone "UTC" :default-harness :x
                                             :harnesses {:x {:kind :script :command ["true"]}}}))
      (log/init! data)
      (let [sys (inbound/system data {})
            body (vec (for [i (range 9)] (str "Step " i " of the checklist, written out in full.")))]
        (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title "Clerk" :instructions "x"}})
        (inbound/submit! sys {:type :memo/send :actor "operator" :args {:to "clerk" :subject "s" :body "b"}})
        (inbound/submit! sys {:type :wake/start :actor "dispatcher" :args {:role "clerk"}})
        (let [m (inbound/submit! sys {:type :message/send :actor "clerk"
                                      :args {:subject "Prep checklist" :sections [{:items body}]}})
              _ m]
          (is (some #(str/includes? % "All of it: relay ready") (:out (run "ready"))) "the list says how")
          (let [n (second (re-find #"Message (\d+)" (first (filter #(str/starts-with? % "Message ") (:out (run "ready"))))))
                out (:out (run "ready" n))]
            (is (some? n))
            (is (not-any? #(str/includes? % "…") out))
            (is (= 9 (count (filter #(str/includes? % "of the checklist") out))))
            (is (every? #(<= (count %) 60) out)))))
      (testing "an unknown number is refused plainly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nothing ready is numbered 9999" (run "ready" "9999"))))
      (finally (fs/delete-tree dir)))))

(defspec relay-ready-marks-a-cut-exactly-when-it-cut 200
  (prop/for-all [lines (gen/vector (gen/fmap str/join (gen/vector (gen/elements ["word " "a" "longerword " "x"]) 0 30)) 0 7)]
    (let [now (Instant/parse "2026-10-26T12:00:00Z")
          out (render/ready [{"id" "prepared:1" "kind" "draft" "title" "T" "at" (str now) "lines" lines}]
                            (ZoneId/of "UTC") now)
          wrapped (mapcat #(render/wrap % 58) (remove str/blank? lines))]
      (and (every? #(<= (count %) 60) out)
           (= (> (count wrapped) 4) (boolean (some #(str/ends-with? % " …") out)))
           (= (> (count wrapped) 4) (boolean (some #{"  All of it: relay ready prepared:1"} out)))))))

(deftest relay-ready-and-relay-reply-through-the-cli
  (let [dir (str (fs/create-temp-dir {:prefix "relay-ready-cli"}))
        data (str dir "/data")
        run (fn [& args] ((requiring-resolve 'relay.cli/execute) (into ["--data" data] args)))]
    (try
      (fs/create-dirs data)
      (spit (str data "/relay.edn") (pr-str {:timezone "UTC" :default-harness :x
                                             :harnesses {:x {:kind :script :command ["true"]}}}))
      (log/init! data)
      (is (= ["Nothing is ready."] (:out (run "ready"))))
      (let [sys (inbound/system data {})]
        (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title "Clerk" :instructions "x"}})
        (inbound/submit! sys {:type :card/add :actor "clerk" :args {:kind "note" :title "A finding" :text "t"}}))
      (is (str/includes? (str/join "\n" (:out (run "ready"))) "A finding"))
      (testing "the 10 newest, unless --all"
        (let [sys (inbound/system data {})]
          (doseq [i (range 12)]
            (inbound/submit! sys {:type :card/add :actor "clerk" :args {:kind "note" :title (str "Finding " i) :text "t"}})))
        (let [out (:out (run "ready"))
              heads (filter #(str/starts-with? % "Board") out)]
          (is (= 10 (count heads)))
          (is (str/starts-with? (first heads) "Board, card ") )
          (is (str/includes? (first heads) "Finding 11"))
          (is (= "… and 3 older. relay ready --all shows every one." (last out))))
        (let [out (:out (run "ready" "--all"))]
          (is (= 13 (count (filter #(str/starts-with? % "Board") out))))
          (is (not-any? #(str/includes? % "older") out))))
      (finally (fs/delete-tree dir)))))

;; --- the client channel

(defn- hub-state [w] (:state w))

(deftest the-hub-answers-ready-and-sends-what-changes
  (let [w0 (told-w)
        w1 (-> w0 (ct/advance 60)
               (ct/run "clerk" :prepared/record {:call "c1" :tool "campaign-draft-demo" :title "Concert" :where "Saved."}))
        h (-> (ht/live) (assoc :state (hub-state w0)) (ht/opened 2))
        [h fx] (ht/say h 1 (ht/req 5 "_relay/ready" {}))
        answer (get (first (ht/writes fx 1)) "result")]
    (is (= ["message"] (map #(get % "kind") (get answer "items"))))
    (is (= #{"id" "kind" "at" "seq" "title" "lines" "from" "outsider" "message" "messageKind" "urgency" "thread" "replyTo" "replies"}
           (set (keys (first (get answer "items")))))
        "a message's item carries what a client needs to answer it")
    (testing "a new item reaches the subscriber, and only it"
      (let [[h fx] (ht/run h {:event :log-changed :roster ht/roster :state (hub-state w1)})
            [n] (ht/writes fx 1)]
        (is (= "_relay/ready/changed" (get n "method")))
        (is (= ["draft"] (map #(get % "kind") (get-in n ["params" "items"]))))
        (is (empty? (ht/writes fx 2)))
        (testing "and is not sent again"
          (let [[_ fx] (ht/run h {:event :log-changed :roster ht/roster :state (hub-state w1)})]
            (is (empty? (filter #(= "_relay/ready/changed" (get % "method")) (ht/writes fx 1))))))))
    (testing "a parameter it does not take is refused"
      (let [[_ fx] (ht/say h 1 (ht/req 6 "_relay/ready" {:sessionId "s1"}))]
        (is (= -32602 (get-in (first (ht/writes fx 1)) ["error" "code"])))))))

(deftest the-hub-takes-a-reply-as-the-operators
  (let [w (told-w)
        h (-> (ht/live) (assoc :state (hub-state w)) (ht/opened 2))
        id (msg-id w)
        [h2 fx] (ht/say h 1 (ht/req 7 "_relay/reply" {:inReplyTo id :text "Thanks."}))
        [submit] (ht/submits fx)]
    (is (= {:type :message/reply :actor "operator" :labels [] :public false :args {:in-reply-to id :text "Thanks."}}
           (:command submit)))
    (testing "the answer comes once relay has taken it"
      (let [[_ fx] (ht/run h2 {:event :submit-result :ok true})]
        (is (= {} (get (first (ht/writes fx 1)) "result")))))
    (testing "what is wrong with the params is refused at once"
      (doseq [params [{:text "x"} {:inReplyTo "1" :text "x"} {:inReplyTo 1} {:inReplyTo 1 :text "x" :extra 1}]]
        (let [[_ fx] (ht/say h 1 (ht/req 8 "_relay/reply" params))]
          (is (= -32602 (get-in (first (ht/writes fx 1)) ["error" "code"])) (pr-str params)))))))

(deftest the-hub-cleans-a-reply-like-a-paste
  (let [w (told-w)
        h (-> (ht/live) (assoc :state (hub-state w)) (ht/opened 2))
        id (msg-id w)
        [h2 fx] (ht/say h 1 (ht/req 7 "_relay/reply" {:inReplyTo id :text "Ok\u200b, send it \u202eto everyone"}))
        [submit] (ht/submits fx)]
    (is (= "Ok, send it to everyone" (get-in submit [:command :args :text])))
    (let [[_ fx] (ht/run h2 {:event :submit-result :ok true})
          notes (get-in (first (ht/writes fx 1)) ["result" "notes"])]
      (is (= ["Removed 1 invisible character from what you pasted."
              "Removed 1 text-direction character from what you pasted."] notes)
          "the answer says what changed"))))

(defspec a-reply-from-the-client-is-submitted-cleaned 200
  (prop/for-all [t (gen/fmap str/join (gen/vector (gen/elements ["a" " " "\u200b" "\u202e" "\u2028" "e\u0301" "\uDB40\uDC41" "Ok"]) 1 20))]
    (let [h (-> (ht/live) (assoc :state (hub-state (told-w))) (ht/opened 2))
          [_ fx] (ht/say h 1 (ht/req 7 "_relay/reply" {:inReplyTo 1 :text t}))
          sent (get-in (first (ht/submits fx)) [:command :args :text])]
      (= sent (:text (text/sanitize t)) (:text (text/sanitize sent))))))

;; --- from a session to the view, through the shell

(deftest a-sessions-edit-calls-are-recorded-once-and-appear-in-the-view
  (let [dir (str (fs/create-temp-dir {:prefix "relay-ready-prepared"}))
        data (str dir "/data")]
    (try
      (fs/create-dirs data)
      (spit (str data "/relay.edn") (pr-str {:timezone "UTC" :default-harness :x
                                             :harnesses {:x {:kind :script :command ["true"]}}}))
      (log/init! data)
      (let [config (-> ct/cfg
                       (assoc-in [:tools "campaign-draft-demo"] {:class :edit})
                       (assoc-in [:tools "remind-demo"] {:class :read}))
            sys (inbound/system data {:config config})]
        (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title "Clerk" :instructions "x"}})
        (inbound/submit! sys {:type :memo/send :actor "operator" :args {:to "clerk" :subject "s" :body "b"}})
        (let [started (inbound/submit! sys {:type :wake/start :actor "dispatcher" :args {:role "clerk"}})
              wake-id (:seq (first (filter #(= "wake/started" (:type %)) started)))]
          ;; What relay's tool command handed the session in this wake.
          (tools/record-read! data "clerk" wake-id ["contaminated"])
          (dispatch/store-prepared! sys "clerk" wake-id transcript [])
          (dispatch/store-prepared! sys "clerk" wake-id transcript [])
          (let [state (inbound/current-state sys)
                [d & more] (filter #(= :draft (:kind %)) (ready/ready state))]
            (is (empty? more) "read twice, recorded once; the read-class call is not a draft")
            (is (= ["Concert" ["Saved draft c1, version 1." "Hello"]]
                   [(:title d) (:lines d)]))
            (is (= "Drew on: something from outside" (get (ready/wire d) "from")) "the call carries what the session read"))
          (testing "what the session read anywhere in the wake counts, as a resumed transcript may lack it; labels it only held do not"
            (let [t (str/join "\n"
                              (map json/generate-string
                                   [{:type "message" :message {:message {:role "assistant"
                                                                         :parts [{:type "tool_call" :tool_call {:id "z" :name "campaign-draft-demo" :input {:subject "Later"}}}]}}}
                                    {:type "decision" :decision {:call_id "z" :tool "campaign-draft-demo" :verdict "allow"}}
                                    {:type "tool_result" :tool_result {:result {:call_id "z" :content "Saved."}}}]))]
              (dispatch/store-prepared! sys "clerk" wake-id t ["mail:x@y.org" "private"])
              (let [later (first (filter #(= "Later" (:title %)) (ready/ready (inbound/current-state sys))))]
                (is (= "Drew on: something from outside" (get (ready/wire later) "from")))
                (is (true? (get (ready/wire later) "outsider")))
                (is (some #{"contaminated"} (:labels later)) "its labels still say everything it held"))))))
      (finally (fs/delete-tree dir)))))
