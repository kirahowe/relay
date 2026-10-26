(ns relay.messages-test
  "Messages to the operator as data: their shape, routing (a pure
  function of relay.edn's :messages and the time), the deliveries the
  tick makes, the settings, old notifications folding into messages, the
  tell_operator tool, and the client channel (_relay/messages)."
  (:require [babashka.fs]
            [cheshire.core]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.channels :as channels]
            [relay.config :as config]
            [relay.core :as core]
            [relay.core-test :as ct]
            [relay.dispatch]
            [relay.inbound]
            [relay.log]
            [relay.hub :as hub]
            [relay.hub-test :as ht]
            [relay.messages :as messages]
            [relay.test-support :as support]
            [relay.tools :as tools]
            [relay.workspace :as workspace])
  (:import (java.time Duration Instant ZoneId)))

(def toronto (ZoneId/of "America/Toronto"))
(defn- at [s] (Instant/parse s))

;; --- the message

(deftest a-message-is-checked-and-made-whole
  (let [m (messages/message {:kind "note" :subject "  Draft\nready  " :sections [["One" {:text "Two" :refs ["[[fact:1]]"]}]]})]
    (is (= {:kind "note" :urgency "normal" :subject "Draft ready"
            :sections [{:items [{:text "One" :refs []} {:text "Two" :refs ["[[fact:1]]"]}]}]}
           m)))
  (doseq [[bad why] [[{:kind "shout" :subject "s" :sections [["x"]]} "kind"]
                     [{:kind "note" :urgency "now" :subject "s" :sections [["x"]]} "urgency"]
                     [{:kind "note" :subject " " :sections [["x"]]} "subject"]
                     [{:kind "note" :subject "s" :sections []} "something to say"]
                     [{:kind "note" :subject "s" :sections [[" "]]} "empty"]
                     [{:kind "note" :subject (apply str (repeat 201 "x")) :sections [["x"]]} "200"]]]
    (is (str/includes? (try (messages/message bad) nil (catch clojure.lang.ExceptionInfo e (ex-message e))) why) why))
  (testing "a role's body is its paragraphs, and its items a list after them"
    (is (= [{:items ["First." "Second,\nstill second."]} {:list true :items ["[Venue]"]}]
           (messages/body-sections "First.\n\n  \nSecond,\nstill second.\n" ["[Venue]"])))))

;; Relay's own messages are made to fit, whatever text they quote.

(def ^:private gen-long-text
  (gen/one-of [gen/string
               (gen/return "")
               (gen/return "  \n ")
               (gen/fmap #(apply str (repeat % "x")) (gen/choose 3990 4600))
               (gen/fmap #(apply str (repeat % "\uD83D\uDE00")) (gen/choose 1990 4100))]))

(def ^:private gen-item
  (gen/one-of [gen-long-text
               (gen/let [t gen-long-text labels (gen/vector (gen/elements ["private" "contaminated"]) 0 2)]
                 {:text t :refs ["[[memo:1]]"] :labels labels})]))

(def ^:private gen-tell-parts
  (gen/let [kind (gen/elements (sort messages/kinds))
            urgency (gen/elements messages/urgencies)
            subject (gen/one-of [gen-long-text (gen/fmap #(apply str (repeat % "s")) (gen/choose 150 400))])
            sections (gen/one-of
                      [(gen/vector (gen/hash-map :heading gen/string-ascii
                                                 :items (gen/vector gen-item 0 6))
                                   0 20)
                       (gen/fmap (fn [n] [{:list true :items (vec (repeat n "relay doc shown/x"))}])
                                 (gen/choose 40 70))])]
    {:kind kind :urgency urgency :subject subject :sections sections}))

(defspec every-tell-input-makes-a-valid-message 300
  (prop/for-all [parts gen-tell-parts]
    (let [m (messages/message (messages/fit parts))]
      (and (nil? (messages/problem m))
           (= m (messages/message (messages/fit m)))))))

(deftest fitting-keeps-what-fits-and-says-what-it-left-out
  (let [m (messages/fit {:kind "note" :urgency "normal" :subject "s"
                         :sections [{:list true :items (vec (for [i (range 60)] (str "p" i)))}]})
        items (:items (first (:sections m)))]
    (is (= messages/items-limit (count items)))
    (is (= "p0" (:text (first items))))
    (is (= "…and 11 more." (:text (last items)))))
  (let [m (messages/fit {:kind "note" :urgency "normal" :subject "s"
                         :sections (vec (for [i (range 15)] {:heading (str "h" i) :items ["x"]}))})]
    (is (= messages/sections-limit (count (:sections m))))
    (is (= "…and 4 more parts." (:text (first (:items (last (:sections m))))))))
  (testing "an item is cut, with an ellipsis"
    (let [m (messages/fit {:kind "note" :urgency "normal" :subject "s"
                           :sections [[(apply str (repeat 5000 "x"))]]})
          t (:text (first (:items (first (:sections m)))))]
      (is (= messages/item-limit (count t)))
      (is (str/ends-with? t "…"))))
  (testing "nothing to say still says something"
    (is (nil? (messages/problem (messages/fit {:kind "alert" :urgency "high" :subject " " :sections [[" "]]}))))))

;; --- routing

(def ^:private names ["phone" "mail" "desk"])

(def ^:private gen-hhmm
  (gen/fmap (fn [[h m]] (format "%02d:%02d" h m)) (gen/tuple (gen/choose 0 23) (gen/choose 0 59))))

(def ^:private gen-route
  (gen/let [kinds (gen/one-of [(gen/return nil) (gen/fmap vec (gen/set (gen/elements (sort messages/kinds)) {:min-elements 1}))])
            urgencies (gen/one-of [(gen/return nil) (gen/fmap vec (gen/set (gen/elements messages/urgencies) {:min-elements 1}))])
            channels (gen/fmap vec (gen/set (gen/elements names) {:min-elements 1}))
            digest gen/boolean]
    (cond-> {:channels channels}
      kinds (assoc :kinds kinds)
      urgencies (assoc :urgencies urgencies)
      digest (assoc :at :digest))))

(def ^:private gen-settings
  (gen/let [defined (gen/set (gen/elements names))
            routes (gen/vector gen-route 0 4)
            quiet (gen/one-of [(gen/return nil)
                               (gen/such-that #(not= (:from %) (:to %))
                                              (gen/hash-map :from gen-hhmm :to gen-hhmm))])
            digest-at gen-hhmm]
    (cond-> {:channels (into {} (for [n defined] [n {:kind :push :via :file :path (str "/tmp/" n)}]))
             :routes routes :digest-at digest-at}
      quiet (assoc :quiet-hours quiet))))

(def ^:private gen-zone
  (gen/elements (map #(ZoneId/of %) ["America/Toronto" "UTC" "Asia/Kolkata" "Pacific/Chatham" "Europe/London"])))

(def ^:private gen-now
  ;; 2026, by the minute, plus a few seconds, so DST changes are crossed.
  (gen/fmap (fn [[m s]] (.plusSeconds (at "2026-01-01T00:00:00Z") (+ (* 60 m) s)))
            (gen/tuple (gen/choose 0 (* 365 24 60)) (gen/choose 0 59))))

(def ^:private gen-msg
  (gen/hash-map :kind (gen/elements (sort messages/kinds)) :urgency (gen/elements messages/urgencies)))

(defn- matching [settings msg]
  (filter #(and (or (nil? (:kinds %)) (some #{(:kind msg)} (:kinds %)))
                (or (nil? (:urgencies %)) (some #{(:urgency msg)} (:urgencies %))))
          (:routes settings)))

(defspec routing-is-a-pure-function-of-the-settings-and-the-time 500
  (prop/for-all [settings gen-settings zone gen-zone msg gen-msg now gen-now]
    (let [r (messages/route settings zone msg now)
          routed (set (map :channel r))
          named (set (for [rt (matching settings msg) ch (:channels rt) :when (contains? (:channels settings) ch)] ch))
          high (= "high" (:urgency msg))]
      (and (= r (messages/route settings zone msg now)) "the same answer every time"
           ;; Exactly the configured channels a matching route names; the
           ;; client is never listed, being every message's anyway.
           (= routed named)
           (not (contains? routed messages/client-channel))
           (= (count r) (count routed))
           (every? (fn [{:keys [^Instant due]}]
                     (and (not (.isBefore due now)) (.isBefore due (.plus now (Duration/ofDays 3)))))
                   r)
           ;; High urgency waits for nothing.
           (or (not high) (every? #(= now (:due %)) r))
           ;; Nothing else is delivered in the quiet hours.
           (or high (nil? (:quiet-hours settings))
               (not-any? #(messages/quiet? (:quiet-hours settings) zone (:due %)) r))
           ;; A channel some matching route sends to at once gets it at
           ;; once, quiet hours aside.
           (or high (some? (:quiet-hours settings))
               (every? (fn [{:keys [channel due]}]
                         (or (not-any? #(and (nil? (:at %)) (some #{channel} (:channels %))) (matching settings msg))
                             (= now due)))
                       r))
           ;; One only digest routes name waits for the digest time.
           (or high (some? (:quiet-hours settings)) (not (.isFixedOffset (.getRules ^ZoneId zone)))
               (every? (fn [{:keys [channel ^Instant due]}]
                         (or (some #(and (nil? (:at %)) (some #{channel} (:channels %))) (matching settings msg))
                             (= (messages/parse-hhmm (:digest-at settings)) (.toLocalTime (.atZone due zone)))))
                       r))))))

(deftest quiet-hours-hold-a-message-until-they-end
  (let [settings {:channels {"phone" {}} :routes [{:channels ["phone"]}] :quiet-hours {:from "22:00" :to "07:00"}}
        due (fn [urgency t] (:due (first (messages/route settings toronto {:kind "note" :urgency urgency} (at t)))))]
    (is (= (at "2026-10-26T15:00:00Z") (due "normal" "2026-10-26T15:00:00Z")) "11:00 is not quiet")
    (is (= (at "2026-10-27T11:00:00Z") (due "normal" "2026-10-27T03:00:00Z")) "23:00 waits for 07:00")
    (is (= (at "2026-10-27T11:00:00Z") (due "low" "2026-10-27T09:59:00Z")) "05:59 too")
    (is (= (at "2026-10-27T03:00:00Z") (due "high" "2026-10-27T03:00:00Z")) "high urgency goes at once")
    (testing "a window inside one day"
      (let [s (assoc settings :quiet-hours {:from "12:00" :to "13:30"})]
        (is (= (at "2026-10-26T17:30:00Z")
               (:due (first (messages/route s toronto {:kind "note" :urgency "normal"} (at "2026-10-26T16:10:00Z"))))))))
    (testing "the night the clocks go back: 07:00 is still 07:00"
      (is (= (at "2026-11-01T12:00:00Z") (due "normal" "2026-11-01T05:30:00Z"))))))

(deftest a-digest-route-waits-for-the-digest-time
  (let [settings {:channels {"mail" {} "phone" {}}
                  :routes [{:urgencies ["low"] :channels ["mail"] :at :digest}
                           {:kinds ["alert"] :channels ["phone" "mail"]}]
                  :digest-at "18:00"}
        route (fn [msg t] (messages/route settings toronto msg (at t)))]
    (is (= [{:channel "mail" :due (at "2026-10-26T22:00:00Z")}]
           (route {:kind "note" :urgency "low"} "2026-10-26T15:00:00Z")))
    (is (= [{:channel "mail" :due (at "2026-10-27T22:00:00Z")}]
           (route {:kind "note" :urgency "low"} "2026-10-26T22:00:01Z")) "past it, tomorrow's")
    (is (= [{:channel "mail" :due (at "2026-10-26T15:00:00Z")} {:channel "phone" :due (at "2026-10-26T15:00:00Z")}]
           (route {:kind "alert" :urgency "low"} "2026-10-26T15:00:00Z"))
        "a route that sends at once wins")
    (is (= [] (route {:kind "note" :urgency "normal"} "2026-10-26T15:00:00Z")) "no route, the client alone")))

;; --- the deliveries the tick makes

(defn- msg-with [id created deliveries]
  {:id id :created-at (at created) :deliveries deliveries})

(deftest the-tick-delivers-what-is-due-and-what-was-stranded
  (let [now (at "2026-10-26T12:00:00Z")
        stale (.minusSeconds now 120)
        ms [;; Made just now, due at once: its own process delivers it.
            (msg-with 1 "2026-10-26T11:59:30Z" {"phone" {:status :pending :due (at "2026-10-26T11:59:30Z")}})
            ;; Made long ago and never delivered: its process stopped.
            (msg-with 2 "2026-10-26T11:00:00Z" {"phone" {:status :pending :due (at "2026-10-26T11:00:00Z")}})
            ;; Held for quiet hours that ended a minute ago.
            (msg-with 3 "2026-10-26T03:00:00Z" {"phone" {:status :pending :due (at "2026-10-26T11:59:00Z")}
                                                "mail" {:status :pending :due (at "2026-10-26T22:00:00Z")}})
            ;; Failed, retry due; and failed, retry later; and delivered.
            (msg-with 4 "2026-10-26T10:00:00Z" {"mail" {:status :failed :retry-at (at "2026-10-26T11:58:00Z")}})
            (msg-with 5 "2026-10-26T10:00:00Z" {"mail" {:status :failed :retry-at (at "2026-10-26T12:30:00Z")}})
            (msg-with 6 "2026-10-26T10:00:00Z" {"mail" {:status :delivered :due (at "2026-10-26T10:00:00Z")}})
            ;; Failed for good.
            (msg-with 7 "2026-10-26T10:00:00Z" {"mail" {:status :failed :retry-at nil}})]]
    (is (= [{:channel "mail" :messages [4]} {:channel "phone" :messages [2 3]}]
           (messages/due-now (shuffle ms) now stale))
        "what comes due together on a channel goes as one, oldest first")))

;; --- the settings

(defn- problem [m]
  (try (config/resolve-config {:timezone "UTC" :default-harness :a
                               :harnesses {:a {:kind :agentloop :api-key-command ["true"]}}
                               :messages m})
       nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(def mail {:kind :email :url "smtps://smtp.example.org:465" :from "agent@example.org" :to "kira@example.org"})

(deftest the-settings-are-checked
  (is (= messages/default-settings
         (:messages (config/resolve-config {:timezone "UTC" :default-harness :a
                                            :harnesses {:a {:kind :agentloop :api-key-command ["true"]}}})))
      "none: the client alone")
  (is (nil? (problem {:channels {"mail" mail} :routes [{:channels ["mail"]}]})))
  (is (nil? (problem {:channels {"mail" (assoc mail :url "smtp://127.0.0.1:1025")} :routes []})) "plain SMTP to this machine")
  (is (nil? (problem {:channels {"mail" (assoc mail :user "agent@example.org" :credentials-command ["cat" "/k"])}
                      :routes [{:urgencies ["low"] :channels ["mail"] :at :digest}]
                      :digest-at "18:00" :quiet-hours {:from "22:00" :to "07:00"}})))
  (doseq [[m why] [[{:channels {"mail" (assoc mail :url "smtp://smtp.example.org:25")} :routes []} "only for this machine"]
                   [{:channels {"mail" (assoc mail :url "https://smtp.example.org")} :routes []} "cannot be used"]
                   [{:channels {"mail" (assoc mail :to "kira@example.org, eve@example.net")} :routes []} ":to"]
                   [{:channels {"mail" (assoc mail :to "Kira <kira@example.org>")} :routes []} ":to"]
                   [{:channels {"mail" (assoc mail :from "agent@example.org\r\nBcc: eve@example.net")} :routes []} ":from"]
                   [{:channels {"mail" (assoc mail :cc "eve@example.net")} :routes []} ":cc"]
                   [{:channels {"mail" (assoc mail :credentials-command ["cat" "/k"])} :routes []} ":user"]
                   [{:channels {"client" mail} :routes []} "client"]
                   [{:channels {"Mail" mail} :routes []} "lower case"]
                   [{:channels {"mail" mail} :routes [{:channels ["phone"]}]} "does not define"]
                   [{:channels {"mail" mail} :routes [{:channels ["mail"] :kinds ["memo"]}]} ":kinds"]
                   [{:channels {"mail" mail} :routes [{:channels ["mail"] :urgencies ["urgent"]}]} ":urgencies"]
                   [{:channels {"mail" mail} :routes [{:channels ["mail"] :at :digest}]} ":digest-at"]
                   [{:channels {"mail" mail} :routes [{:channels ["mail"] :to "eve@example.net"}]} "does not take"]
                   [{:channels {"mail" mail} :routes [] :quiet-hours {:from "22:00" :to "22:00"}} ":quiet-hours"]
                   [{:channels {"mail" mail} :routes [] :digest-at "6pm"} ":digest-at"]
                   [{:channels {"mail" mail} :routes [] :notify {:kind :none}} "does not take"]]]
    (is (str/includes? (str (problem m)) why) (pr-str m)))
  (testing "the old :notify is refused, saying what replaced it"
    (is (str/includes? (try (config/resolve-config {:timezone "UTC" :default-harness :a
                                                    :harnesses {:a {:kind :agentloop :api-key-command ["true"]}}
                                                    :notify {:kind :none}})
                            nil
                            (catch clojure.lang.ExceptionInfo e (ex-message e)))
                       ":messages"))))

;; --- what older relays recorded

(def ^:private gen-old-kind
  (gen/elements ["memo-for-operator" "work-abandoned" "harness-needs-attention" "session-forbidden"
                 "result-refused" "budget-reached" "tool-changed" "tools-unavailable" "shown"
                 "turn-unrecorded" "conversation-line-dropped" "runaway-guard-tripped"
                 "public-role-cannot-run" "public-role-can-run" "harness-unreachable" "brief"
                 "permission-asked" "report-ready"]))

(defspec every-old-notification-folds-into-a-message 200
  ;; I8: a log an older relay wrote, with its notifications and their
  ;; deliveries, folds; each notification is a message with nothing left
  ;; to deliver, and what relay remembered telling is remembered still.
  (prop/for-all [ns (gen/vector (gen/tuple gen-old-kind gen/string-ascii gen/string (gen/elements [:delivered :failed nil]))
                                1 12)]
    (let [events (second
                  (reduce (fn [[seq evs] [kind title body outcome]]
                            (let [created {:seq seq :at "2026-10-14T12:00:00Z" :type "notification/created"
                                           :actor "dispatcher" :labels ["private"]
                                           :data {:kind kind :title title :body body :harness "agentloop"
                                                  :role "scout" :week "2026-10-12" :percent 80 :reason "r"}}
                                  after (case outcome
                                          :delivered [{:seq (inc seq) :at "2026-10-14T12:00:01Z" :type "notification/delivered"
                                                       :actor "dispatcher" :labels [] :data {:notification seq}}]
                                          :failed [{:seq (inc seq) :at "2026-10-14T12:00:01Z" :type "notification/failed"
                                                    :actor "dispatcher" :labels []
                                                    :data {:notification seq :error "x" :attempt 1 :retry-at "2026-10-14T12:01:00Z"}}]
                                          nil [])]
                              [(+ seq 1 (count after)) (into evs (cons created after))]))
                          [1 []] ns))
          state (core/replay events)
          msgs (sort-by :id (vals (:messages state)))]
      (and (= (count ns) (count msgs))
           (every? #(messages/kinds (:kind %)) msgs)
           (every? #(some #{(:urgency %)} messages/urgencies) msgs)
           (every? #(empty? (:deliveries %)) msgs)
           (empty? (messages/due-now msgs (at "2030-01-01T00:00:00Z") (at "2030-01-01T00:00:00Z")))
           (= (map first ns) (map :origin msgs))
           (every? #(= ["private"] (:labels %)) msgs)
           (= (boolean (some #{"harness-needs-attention" "harness-unreachable"} (map first ns)))
              (boolean (get-in state [:harnesses "agentloop" :operator-told])))
           (= (boolean (some #{"budget-reached"} (map first ns)))
              (= 80 (get-in state [:budget-told "2026-10-12"])))))))

;; --- tell_operator, through relay.core

(defn- told [w] (filter #(= "told" (:origin %)) (sort-by :id (vals (:messages (:state w))))))

(defn- woken [w role]
  (-> w (ct/run "operator" :memo/send {:to role :subject "s" :body "b"})
      (ct/run "dispatcher" :wake/start {:role role})))

(defn- tell [w args] (ct/run w "clerk" :message/send args))

(deftest a-role-tells-the-operator
  (let [w (-> (ct/world)
              (ct/run "operator" :role/add {:id "clerk" :title "Clerk" :instructions "x"})
              (woken "clerk")
              (tell {:subject "Draft ready" :sections [{:items ["It is in your drafts." "[[doc:x]] says so."]}]
                     :urgency "high"}))
        [m] (told w)]
    (is (nil? (:rejected w)))
    (is (= {:kind "note" :urgency "high" :subject "Draft ready" :thread (:id m) :role "clerk"}
           (select-keys m [:kind :urgency :subject :thread :role])))
    (let [wake (support/calling-wake (:state w) "clerk")
          [state] (core/run-command (:state w) {:type :message/send :actor "clerk" :labels ["private" "contaminated"]
                                                :session-wake (:id wake)
                                                :args {:subject "s" :sections [{:items ["x"]}]}}
                                    {:now (:now w) :config ct/cfg})]
      (is (= ["contaminated" "private"] (:labels (last (sort-by :id (vals (:messages state))))))
          "it carries what the session read"))
    (testing "a reply threads to the message it answers"
      (let [w (tell w {:subject "Re: draft" :sections [{:items ["Done."]}] :in-reply-to (:id m)})
            [_ r] (told w)]
        (is (= {:kind "reply" :in-reply-to (:id m) :thread (:id m)} (select-keys r [:kind :in-reply-to :thread])))))
    (testing "what is refused"
      (doseq [[args why] [[{:subject "a\nb" :sections [{:items ["x"]}]} "one line"]
                          [{:subject "s" :sections []} "something to say"]
                          [{:subject "s" :sections [{:items ["x"]}] :urgency "now"} "urgency"]
                          [{:subject "s" :sections [{:items ["x"]}] :in-reply-to 9999} "no message 9999"]
                          [{:subject "s" :sections "x"} "sections of items"]]]
        (let [w (tell w args)]
          (is (:rejected w) why)
          (is (str/includes? (str (:rejected-message w)) why) why))))
    (testing "the operator and the dispatcher do not use it"
      (is (:rejected (ct/run w "operator" :message/send {:subject "s" :sections [{:items ["x"]}]}))))))

(deftest a-public-role-cannot-tell-the-operator-directly
  (let [w (-> (ct/world)
              (ct/run "operator" :role/add {:id "courier" :title "Courier" :instructions "x"} true)
              (woken "courier")
              (ct/run "courier" :message/send {:subject "s" :sections [{:items ["x"]}]}))]
    (is (= :forbidden (:rejected w)))
    (is (empty? (told w))))
  (is (not (contains? (tools/offered ct/cfg "courier") "tell_operator")) "nor is it offered the tool"))

;; --- the client channel

(defn- wire-states []
  (let [w (-> (ct/world)
              (ct/run "operator" :role/add {:id "clerk" :title "Clerk" :instructions "x"})
              (woken "clerk")
              (tell {:subject "First" :sections [{:items ["one"]}]}))
        s0 (:state w)
        w (tell w {:subject "Second https://x.example" :sections [{:items ["two"]}]})]
    [s0 (:state w)]))

(deftest the-client-is-sent-every-message-as-it-is
  (let [[s0 s1] (wire-states)
        answer (workspace/messages-answer s1 {})
        [second first] (get answer "messages")]
    (is (= ["Second https://x.example" "First"] (map #(get-in % ["subject" "text"]) [second first])) "newest first")
    (is (= #{"id" "kind" "urgency" "at" "rev" "thread" "inReplyTo" "subject" "sections" "deliveries" "replies"}
           (set (keys first)))
        "nothing internal: no origin, no role, no labels")
    (is (= [{"items" [{"text" "one" "outsider" false "refs" []}]}] (get first "sections")))
    (is (= 1 (count (get (workspace/messages-answer s1 {"limit" 1}) "messages"))))
    (is (some? (workspace/messages-params-error {"sessionId" "s"})))
    (is (some? (workspace/messages-params-error {"limit" 0})))
    (testing "what changed since a rev"
      (is (= ["Second https://x.example"]
             (map #(get-in % ["subject" "text"]) (get (workspace/messages-changed s1 (workspace/log-rev s0)) "messages"))))
      (is (nil? (workspace/messages-changed s1 (workspace/log-rev s1)))))))

(deftest a-message-from-outside-has-its-links-broken-for-the-client
  (let [msg (assoc (messages/message {:kind "note" :subject "See https://evil.example" :sections [["Go to https://evil.example"]]})
                   :id 1 :rev 1 :thread 1 :created-at (at "2026-10-26T12:00:00Z") :labels ["contaminated"] :deliveries {})
        wire (channels/wire msg)]
    (is (= {"text" "See hxxps://evil[.]example" "outsider" true} (get wire "subject")))
    (is (= "Go to hxxps://evil[.]example" (get-in wire ["sections" 0 "items" 0 "text"])))))

(deftest the-hub-sends-messages-to-its-subscribers
  (let [[s0 s1] (wire-states)
        h (-> (ht/live) (assoc :state s0) (ht/opened 2))
        [h fx] (ht/say h 1 (ht/req 5 "_relay/messages" {}))]
    (is (= ["First"] (map #(get-in % ["subject" "text"]) (get-in (first (ht/writes fx 1)) ["result" "messages"]))))
    (testing "a new message reaches the subscriber, and only it"
      (let [[h fx] (ht/run h {:event :log-changed :roster ht/roster :state s1})
            [n] (ht/writes fx 1)]
        (is (= "_relay/messages/changed" (get n "method")))
        (is (= ["Second https://x.example"] (map #(get-in % ["subject" "text"]) (get-in n ["params" "messages"]))))
        (is (empty? (ht/writes fx 2)) "connection 2 never asked")
        (testing "and is not sent again"
          (let [[_ fx] (ht/run h {:event :log-changed :roster ht/roster :state s1})]
            (is (empty? (ht/writes fx 1)))))))
    (testing "a sessionId is refused like any parameter it does not take"
      (let [[_ fx] (ht/say h 1 (ht/req 6 "_relay/messages" {:sessionId "s1"}))]
        (is (= -32602 (get-in (first (ht/writes fx 1)) ["error" "code"])))))))

;; --- held, then delivered together, through the shell

(deftest held-messages-go-together-when-the-quiet-hours-end
  (let [dir (str (babashka.fs/create-temp-dir {:prefix "relay-messages-test"}))
        data (str dir "/data")
        pushed (str dir "/pushed.log")
        clock (atom (at "2026-10-27T03:00:00Z")) ; 23:00 in Toronto
        sys (fn [] (relay.inbound/system data {:clock #(deref clock)}))
        lines (fn [] (if (babashka.fs/exists? pushed)
                       (mapv #(cheshire.core/parse-string % true) (str/split-lines (slurp pushed)))
                       []))]
    (relay.log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "America/Toronto" :default-harness :a
                   :harnesses {:a {:kind :script :command ["true"]}}
                   ;; Its one role is the steward here.
                   :dead-letter-handler "clerk"
                   :messages {:channels {"phone" {:kind :push :via :file :path pushed}}
                              :routes [{:channels ["phone"]}]
                              :quiet-hours {:from "22:00" :to "07:00"}}}))
    (let [s (sys)
          mk (fn [subject] (relay.inbound/submit! s {:type :role/add :actor core/operator
                                                     :args {:id subject :title subject :instructions "x"}}))]
      (mk "clerk")
      (relay.inbound/submit! s {:type :memo/send :actor core/operator :args {:to "clerk" :subject "s" :body "b"}})
      (relay.inbound/submit! s {:type :wake/start :actor core/dispatcher :args {:role "clerk"}})
      (let [wake (:id (support/calling-wake (relay.inbound/current-state s) "clerk"))
            tell! (fn [subject urgency]
                    (relay.inbound/submit! (sys) {:type :message/send :actor "clerk" :session-wake wake :labels []
                                                  :args {:subject subject :urgency urgency :sections [{:items ["x"]}]}}))]
        (tell! "Draft ready" "normal")
        (tell! "Also this" "low")
        (is (= [] (lines)) "held for the quiet hours")
        (tell! "The house is on fire" "high")
        (is (= ["The house is on fire"] (map :title (lines))) "high urgency goes at once")
        (relay.dispatch/tick! (sys) {:spawn! (fn [& _])})
        (is (= 1 (count (lines))) "the tick holds them too")
        (reset! clock (at "2026-10-27T11:01:00Z"))
        (relay.dispatch/tick! (sys) {:spawn! (fn [& _])})
        (is (= ["The house is on fire" "Draft ready (and 1 more)"] (map :title (lines))) "then one push for both")
        (is (= "Also this" (:body (second (lines)))))
        (is (every? #{:delivered}
                    (for [m (vals (:messages (relay.inbound/current-state (sys)))) [_ d] (:deliveries m)] (:status d))))
        (relay.dispatch/tick! (sys) {:spawn! (fn [& _])})
        (is (= 2 (count (lines))) "and nothing twice")))
    (babashka.fs/delete-tree dir)))

(deftest the-hub-acts-on-a-daily-note-s-item-for-the-client
  (let [h (-> (ht/live) (ht/opened 1))
        [h fx] (ht/say h 1 (ht/req 9 "_relay/messages/act" {:message 12 :item 2}))
        [sub] (ht/submits fx)]
    (is (= {:type :digest/act :actor "operator" :labels [] :public false :args {:message 12 :n 2}} (:command sub)))
    (is (= [{"jsonrpc" "2.0" "id" 9 "result" {}}]
           (ht/writes (second (ht/run h {:event :submit-result :tag (:tag sub) :ok true})) 1)))
    (testing "a refusal is relay's reason"
      (let [[h fx] (ht/say (-> (ht/live) (ht/opened 1)) 1 (ht/req 9 "_relay/messages/act" {:message 12 :item 9}))
            [sub] (ht/submits fx)
            [_ fx] (ht/run h {:event :submit-result :tag (:tag sub) :rejection :not-found :message "The daily note has no item 9."})]
        (is (= "The daily note has no item 9." (get-in (first (ht/writes fx 1)) ["error" "message"])))))
    (testing "the params are checked"
      (let [[_ fx] (ht/say (-> (ht/live) (ht/opened 1)) 1 (ht/req 10 "_relay/messages/act" {:message 12}))]
        (is (= -32602 (get-in (first (ht/writes fx 1)) ["error" "code"])))))))
