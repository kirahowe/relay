(ns relay.public-roles-test
  "Public roles, the scouts: roles whose sessions never hold private, so
  they may send what they write out. Everything that would put private
  into one is refused where it would happen; the property in labels-test
  checks the same over random histories, and house-test I3."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.cli :as cli]
            [relay.config :as config]
            [relay.core :as core]
            [relay.test-support :as support]
            [relay.dispatch :as dispatch]
            [relay.harness.agentloop :as agentloop]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.render :as render]
            [relay.tools :as tools])
  (:import (java.time Instant)))

(def t0 (Instant/parse "2026-10-14T12:00:00Z"))

(def fixture (str (fs/absolutize "test/fixtures/envelope-tool")))

(def settings
  {:timezone "UTC"
   :default-harness :agentloop
   :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}}
   :tools {"search" {:class :egress :labels ["contaminated"] :description "Search the web." :command ["true"]
                     :protocol :envelope :queries ["query"] :parameters {:type "object" :properties {:query {:type "string"}}}}
           "lookup" {:class :read :labels [] :protocol :envelope :description "Look something up."
                     :command [fixture "private"] :parameters {:type "object"}}
           "rem-read" {:class :read :labels ["private"] :description "Read reminders." :command ["true"]}}
   :grants {"hub" {:may-add true :tools ["rem-read"]}
            "scout" {:public true :tools ["search" "lookup"]}}})

(def cfg (config/resolve-config settings))

(defn- as-harness
  "A role's command as a harness sends it: naming the wake whose session
  made it, as relay's tool command does."
  [state {:keys [actor] :as command}]
  (if-let [w (and (not (contains? command :session-wake)) (support/calling-wake state actor))]
    (assoc command :session-wake (:id w))
    command))

(defn- run* [{:keys [state now] :as w} command]
  (let [[state' events] (core/run-command state (as-harness state command) {:now now :config cfg})]
    (-> w (assoc :state state' :last events) (update :events (fnil into []) events))))

(defn- run [w actor type args & [labels]]
  (run* w {:type type :actor actor :args args :labels labels}))

(defn- run-public [w type args & [labels]]
  (run* w {:type type :actor "operator" :args args :labels labels :public true}))

(defn- refusal [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (when-not (core/rejection e) (throw e))
         (ex-message e))))

(defn- world []
  (-> {:state core/empty-state :now t0}
      ;; The hub's text is public, so a public role may see and reach it.
      (run-public :role/add {:id "hub" :title "Hub" :instructions "Run relay."})
      (run-public :role/add {:id "scout" :title "Scout" :instructions "Search for grants."})))

(defn- read-as [w role tool args]
  (let [r ((:read (get tools/builtin tool)) {:state (:state w) :config cfg :now (:now w)} role args)]
    (if (map? r) r {:content r :labels []})))

(defn- wake-request [w role]
  (let [w (run w "dispatcher" :wake/start {:role role :dispatched true})
        wake (support/calling-wake (:state w) role)]
    (when wake
      (dispatch/wake-request {:data-dir "/nowhere" :config cfg} (:state w) wake
                             {:shared "Shared." :operator-additions "SECRET-ADDITIONS" :now t0
                              :relay-bin "relay" :data-path "/nowhere"}))))

(def before-public (config/resolve-config (update settings :grants dissoc "scout")))

(defn- run-before [w actor type args & [labels]]
  (let [[state' events] (core/run-command (:state w) {:type type :actor actor :args args :labels labels}
                                          {:now (:now w) :config before-public})]
    (assoc w :state state' :last events)))

(defn- in-wake
  "Runs (f w) while a wake of `role` is running, as a role's calls are made,
  and ends it after."
  [w role f]
  (let [w (-> w (run-public :memo/send {:to role :subject "Go" :body "Look."})
              (run "dispatcher" :wake/start {:role role :dispatched true}))
        w (f w)
        wake (support/calling-wake (:state w) role)]
    (run w "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :session (str "s" (:id wake)) :said "" :labels []})))

;; --- settings

(defn- problem [grants & {:as more}]
  (try (config/resolve-config (merge settings {:grants grants} more)) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest settings-refuse-what-a-public-role-may-not-have
  (is (nil? (problem {"scout" {:public true :tools ["search"]}})))
  (is (str/includes? (problem {"scout" {:public true :may-add true}}) ":may-add"))
  (is (str/includes? (problem {"scout" {:public true :tools ["rem-read"]}}) "whose output is private"))
  (is (str/includes? (problem {"scout" {:public true}} :dead-letter-handler "scout") "dead-letter-handler"))
  (is (str/includes? (problem {"steward" {:public true}}) "dead-letter-handler")
      "the default handler, the steward, counts too")
  (is (str/includes? (problem {"scout" {:public "yes"}}) "true or false"))
  (is (str/includes? (problem {"scout" {:public true}}
                              :harnesses {:s {:kind :script :command ["true"]}} :default-harness :s)
                     ":script"))
  (testing "a grant relay does not know is refused, so a typo cannot leave a role private-capable"
    (is (str/includes? (problem {"scout" {:pubic true}}) ":pubic"))
    (is (str/includes? (problem {"scout" {:tools ["search"] :may-ad true}}) ":may-ad")))
  (testing "a public role is not put on a :script harness either"
    (let [cfg (config/resolve-config (assoc-in settings [:harnesses :s] {:kind :script :command ["true"]}))]
      (is (some? (try (core/decide core/empty-state {:type :role/add :actor "operator"
                                                     :args {:id "scout" :instructions "x" :harness "s"}}
                                   {:now t0 :config cfg})
                      nil
                      (catch clojure.lang.ExceptionInfo e (core/rejection e))))))))

;; --- memos to a public role

(deftest a-memo-from-the-house-to-a-public-role-is-public-as-written
  (let [w (run (world) "hub" :memo/send {:to "scout" :subject "Find arts grants"
                                         :body "Open ones for a small choir, this spring."}
               ["contaminated" "private"])
        m (first (core/unread-memos (:state w) "scout"))]
    (testing "it is public, and keeps the rest of its provenance"
      (is (:public m))
      (is (= ["contaminated"] (:labels m)))
      (is (= ["contaminated"] (:labels (first (:last w))))))
    (testing "the scout is shown it and woken for it"
      (is (= [m] (core/deliverable-memos (:state w) cfg "scout")))
      (is (str/includes? (:content (read-as w "scout" "inbox" {})) "small choir"))
      (is (some #{"scout"} (core/roles-to-wake (:state w) cfg (:now w)))))
    (testing "the wake's prompt holds nothing private"
      (let [request (wake-request w "scout")]
        (is (str/includes? (:prompt request) "small choir"))
        (is (not (core/private? (get-in request [:policy :labels])))))))
  (testing "the sender sees that it went out as written"
    (let [dir (str (fs/create-temp-dir {:prefix "relay-scout-memo"}))]
      (try
        (log/init! dir)
        (let [sys {:data-dir dir :config cfg :clock (constantly t0)}]
          (doseq [c [{:type :role/add :actor "operator" :public true :args {:id "hub" :title "Hub" :instructions "Run relay."}}
                     {:type :role/add :actor "operator" :public true :args {:id "scout" :title "Scout" :instructions "Look."}}]]
            (inbound/submit! sys c))
          (let [{:keys [ok]} (tools/call! sys "hub" "memo_send" {:to "scout" :subject "Grants" :body "Look."} [])]
            (is (re-find #"public as you wrote it" ok))
            (is (not (re-find #"(?i)label|contaminat|private" ok)))))
        (finally (fs/delete-tree dir)))))
  (testing "a role whose id, title and instructions were written in private may still write to a scout"
    (let [w (-> (world)
                (run "hub" :role/add {:id "clerk" :title "SECRET-TITLE" :instructions "SECRET-INSTRUCTIONS"} ["private"])
                (run "clerk" :memo/send {:to "scout" :subject "Choir grants" :body "Find three."} ["private"]))
          m (first (core/unread-memos (:state w) "scout"))]
      (is (nil? (:rejected w)))
      (is (:public m) "the memo is public as written")
      (testing "the scout is shown the memo and the sender's id, so it can answer"
        (is (= [m] (core/deliverable-memos (:state w) cfg "scout")))
        (let [inbox (:content (read-as w "scout" "inbox" {}))]
          (is (str/includes? inbox "Find three."))
          (is (str/includes? inbox "clerk")))
        (is (nil? (refusal #(in-wake w "scout" (fn [w] (run w "scout" :memo/send {:to "clerk" :subject "Found" :body "Three."} [])))))))
      (testing "and nothing else of that role"
        (let [request (wake-request w "scout")
              roles (:content (read-as w "scout" "roles" {}))]
          (is (not (core/private? (get-in request [:policy :labels]))))
          (doseq [seen [(:prompt request) roles (:content (read-as w "scout" "inbox" {}))]]
            (is (not (str/includes? seen "SECRET-TITLE")))
            (is (not (str/includes? seen "SECRET-INSTRUCTIONS"))))
          (is (not (str/includes? roles "clerk")) "it is not listed among the roles the scout knows")))))
  (testing "a memo an older relay left waiting is never shown, and the operator may close it"
    (let [w (run-before (world) "hub" :memo/send {:to "scout" :subject "Old" :body "Secret."} ["private"])
          id (:id (first (core/unread-memos (:state w) "scout")))]
      (is (some? id))
      (is (empty? (core/deliverable-memos (:state w) cfg "scout")))
      (is (= "Nothing is waiting for you." (:content (read-as w "scout" "inbox" {}))))
      (is (re-find #"There is no memo" (refusal #(run w "scout" :memo/close {:memo id :outcome "x"}))))
      (is (nil? (refusal #(run w "operator" :memo/close {:memo id :outcome "Not for a scout."})))))))

(deftest a-scout-s-memo-back-comes-from-the-web
  (let [w (-> (world)
              (run "operator" :role/add {:id "steward" :title "Steward" :instructions "Keep house."})
              (run "steward" :memo/send {:to "scout" :subject "Grants" :body "Find three."} ["private"]))]
    (testing "a scout knows a role whose text is private only once it has written to the scout"
      (is (nil? (refusal #(in-wake w "scout" (fn [w] (run w "scout" :memo/send {:to "steward" :subject "Found" :body "Three."} []))))))
      (is (re-find #"no role called" (refusal #(in-wake (-> (world) (run "operator" :role/add {:id "steward" :title "S" :instructions "x"}))
                                                       "scout" (fn [w] (run w "scout" :memo/send {:to "steward" :subject "s" :body "b"} [])))))))
    (testing "a memo it was never shown, one an older relay left, does not tell it the role exists"
      (let [w (-> (world)
                  (run "operator" :role/add {:id "steward" :title "Steward" :instructions "Keep house."})
                  (run-before "steward" :memo/send {:to "scout" :subject "Old" :body "Secret."} ["private"]))]
        (is (seq (core/unread-memos (:state w) "scout")) "the old memo is there")
        (is (re-find #"no role called" (str (refusal #(in-wake w "scout" (fn [w] (run w "scout" :memo/send {:to "steward" :subject "s" :body "b"} [])))))))))
    (testing "its memo is labelled as from outside, and from the web, even from a session that read nothing"
      (let [w (in-wake w "scout" #(run % "scout" :memo/send {:to "steward" :subject "Found" :body "Three."} []))
            back (first (core/unread-memos (:state w) "steward"))]
        (is (= ["contaminated"] (:labels back)))
        (is (:public back))
        (is (= ["search:web"] (core/sources-of (:state w) (:id back) (:labels back))))))))

(deftest a-public-role-s-memos-and-its-own-writes-are-public
  (let [w (in-wake (world) "scout"
                   #(-> %
                        (run "scout" :memo/send {:to "hub" :subject "Found three" :body "See the list."} ["contaminated"])
                        (run "scout" :note/write {:name "plan" :text "Look at the arts council."} ["contaminated"])
                        (run "scout" :fact/add {:text "The deadline is in May."} ["contaminated"])))
        s (:state w)]
    (is (every? :public (filter #(and (core/public-item-events (:type %)) (= "scout" (:actor %))) (:events w))))
    (is (:public (first (core/unread-memos s "hub"))))
    (is (:public (get-in s [:notes "scout" "plan"])))
    (testing "a role's input never makes anything public"
      (is (not (:public (first (core/unread-memos (:state (run* (world) {:type :memo/send :actor "hub" :labels [] :public true
                                                                          :args {:to "hub" :subject "s" :body "b"}}))
                                                  "hub"))))))
    (testing "a call from a session that began before the role was public is not public"
      (let [w (-> (world)
                  (run-before "operator" :memo/send {:to "scout" :subject "s" :body "b"})
                  (run-before "dispatcher" :wake/start {:role "scout" :dispatched true})
                  (run "scout" :note/write {:name "n" :text "Copied from AGENTS.md."} []))]
        (is (not (:public (get-in (:state w) [:notes "scout" "n"]))))))
    (testing "a public role's call from a session holding private is refused outright"
      (is (re-find #"public role" (refusal #(run (world) "scout" :journal/write {:text "x"} ["private"])))))))

(deftest what-the-operator-writes-for-a-public-role-needs-public
  (let [w (world)]
    (doseq [[type args] [[:memo/send {:to "scout" :subject "s" :body "b"}]
                         [:routine/write {:owner "scout" :name "daily" :schedule "daily 07:00" :instructions "Look."}]
                         [:note/write {:owner "scout" :name "n" :text "t"}]
                         [:journal/write {:owner "scout" :text "t"}]
                         [:wake/start {:role "scout" :note "Look at this."}]]]
      (is (re-find #"--public says that what you write may leave this machine" (str (refusal #(run w "operator" type args))))
          (str type))
      (is (seq (:last (run-public w type args))) (str type " with --public")))
    (testing "a public memo with labels keeps them"
      (let [w (run-public w :memo/send {:to "scout" :subject "Pasted" :body "From a page."} ["contaminated"])
            m (first (core/unread-memos (:state w) "scout"))]
        (is (and (:public m) (= ["contaminated"] (:labels m))))
        (is (= [m] (core/deliverable-memos (:state w) cfg "scout")))))
    (testing "a public note on a wake reaches the role's prompt without private"
      (let [w (run-public w :wake/start {:role "scout" :note "Look at the arts council."})
            wake (support/calling-wake (:state w) "scout")
            request (dispatch/wake-request {:data-dir "/nowhere" :config cfg} (:state w) wake
                                           {:shared "" :now t0 :relay-bin "relay" :data-path "/nowhere"})]
        (is (str/includes? (:prompt request) "arts council"))
        (is (= [] (get-in request [:policy :labels])))))
    (testing "a role that is not public takes the operator's text as ever"
      (is (seq (:last (run w "operator" :memo/send {:to "hub" :subject "s" :body "b"})))))))

(deftest a-routine-run-is-public-when-the-routine-is
  (let [w (-> (world)
              (run-public :routine/write {:owner "scout" :name "daily" :schedule "every 10m" :instructions "Look."})
              (update :now #(.plusSeconds ^Instant % 700))
              (run "dispatcher" :tick {:lost-wakes []}))
        m (first (core/unread-memos (:state w) "scout"))]
    (is (:public m))
    (is (= ["scout"] (core/roles-to-wake (:state w) cfg (:now w))))))

;; --- what a public role is shown

(deftest a-public-role-is-shown-public-items-only
  (let [w (-> (world)
              (run "hub" :fact/add {:text "Kira's salary is secret." :topic "money"} [])
              (run-public :fact/add {:text "Grants open in March." :topic "grants"})
              (run "hub" :doc/put {:path "private/cv.md" :content "Home address."} [])
              (run-public :doc/put {:path "public/brief.md" :content "Look for arts grants."})
              (run "operator" :note/write {:owner "hub" :name "n" :text "Hub's note."})
              (run "hub" :role/add {:id "diary" :title "Kira's diary" :instructions "x"} ["private"])
              ;; A clean session may still carry the operator's AGENTS.md.
              (run "hub" :role/add {:id "banker" :title "Kira's bank" :instructions "x" :reports-to "hub"} []))]
    (testing "facts: the others are not shown, counted or searched"
      (let [r (read-as w "scout" "facts" {:all true})]
        (is (str/includes? (:content r) "Grants open in March."))
        (is (not (str/includes? (:content r) "salary")))
        (is (not (core/private? (:labels r))))
        (is (str/includes? (:content (read-as w "scout" "facts" {:search "salary"})) "No facts match."))
        (is (not (str/includes? (:content r) "left out")) "nothing says anything was left out")))
    (testing "a fact it cannot see does not exist for it"
      (let [secret (:id (first (filter #(= "money" (:topic (core/fact-as-known % nil))) (vals (:facts (:state w))))))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"There is no fact"
                              (read-as w "scout" "fact_history" {:fact secret})))
        (is (re-find #"There is no fact" (refusal #(run w "scout" :fact/correct {:fact secret :text "x" :why "y"}))))))
    (testing "docs"
      (let [r (read-as w "scout" "docs" {})]
        (is (str/includes? (:content r) "public/brief.md"))
        (is (not (str/includes? (:content r) "cv.md"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"There is no doc at private/cv.md"
                            (read-as w "scout" "doc_get" {:path "private/cv.md"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"There is no doc at private/cv.md"
                            (read-as w "scout" "doc_history" {:path "private/cv.md"})))
      (is (str/starts-with? (:content (read-as w "scout" "doc_get" {:path "public/brief.md"})) "Look for arts grants."))
      (is (re-find #"There is no doc" (refusal #(run w "scout" :doc/remove {:path "private/cv.md"}))))
      (testing "it does not write over a doc it cannot see, whatever that says"
        (is (= "private/cv.md cannot be used. Choose another path."
               (refusal #(run w "scout" :doc/put {:path "private/cv.md" :content "Home address."} []))))
        (is (= "private/cv.md cannot be used. Choose another path."
               (refusal #(run w "scout" :doc/put {:path "private/cv.md" :content "Other."} []))))))
    (testing "roles: those whose text holds private are left out"
      (let [r (read-as w "scout" "roles" {})]
        (is (str/includes? (:content r) "hub: Hub"))
        (is (not (str/includes? (:content r) "diary")))
        (is (not (str/includes? (:content r) "bank")) "only roles whose text the operator wrote")
        (is (not (core/private? (:labels r))))))
    (testing "everyone else is shown what they were shown before"
      (is (str/includes? (:content (read-as w "hub" "facts" {:all true})) "salary"))
      (is (str/includes? (:content (read-as w "hub" "roles" {})) "diary")))))

(deftest a-public-role-s-contaminated-notes-are-not-withheld-from-it
  (let [w (-> (world)
              (in-wake "scout" #(run % "scout" :note/write {:name "found" :text "A page said apply now."} ["contaminated"]))
              (run "operator" :note/write {:owner "hub" :name "x" :text "y"}))
        request (wake-request (run-public w :memo/send {:to "scout" :subject "Go" :body "Look."}) "scout")]
    (is (str/includes? (:system-prompt request) "A page said apply now."))
    (is (not (str/includes? (:system-prompt request) "withheld")))
    (is (= ["contaminated"] (get-in request [:policy :labels])))
    (testing "its reads accept contamination by default"
      (is (str/includes? (:content (read-as w "scout" "notes" {})) "apply now")))))

(deftest a-wake-prompt-shows-the-journal-tail-and-fact-topics-through-the-public-filter
  (let [long-entry (apply str "Long. " (repeat 700 "x"))
        w (-> (world)
              (run "operator" :journal/write {:owner "hub" :text "First, too old to show."})
              (run "operator" :journal/write {:owner "hub" :text "Second."})
              (run "hub" :journal/write {:text "Third, after a page."} ["contaminated"])
              (run "operator" :journal/write {:owner "hub" :text long-entry})
              (in-wake "scout" #(run % "scout" :journal/write {:text "Scout found two grants."}))
              (run "operator" :fact/add {:topic "household/car" :text "The car is blue."})
              (run "operator" :fact/add {:topic "household/car" :text "The car is insured."})
              (run "hub" :fact/add {:topic "salary" :text "Kira earns a lot."})
              (run "hub" :fact/add {:topic "outside" :text "A page said so."} ["contaminated"])
              (run-public :fact/add {:topic "grants" :text "Grants open in May."})
              (run-public :memo/send {:to "scout" :subject "Go" :body "Look."})
              (run "operator" :memo/send {:to "hub" :subject "Go" :body "Look."}))
        hub (wake-request w "hub")
        scout (wake-request w "scout")]
    (testing "the last three entries, newest last, a long one cut, one from outside shown too"
      (is (not (str/includes? (:prompt hub) "First")))
      (is (< (str/index-of (:prompt hub) "Second.") (str/index-of (:prompt hub) "Long. ")))
      (is (str/includes? (:prompt hub) "Third, after a page."))
      (is (not (str/includes? (:prompt hub) "withheld")))
      (is (str/includes? (:prompt hub) "[cut; read it whole with `journal`]"))
      (is (not (str/includes? (:prompt hub) (apply str (repeat 650 "x"))))))
    (testing "topics with counts, every fact counted"
      (is (str/includes? (:prompt hub) "grants (1), household/car (2), outside (1), salary (1)\n")))
    (testing "the hub's wake carries the labels of what its prompt shows"
      (is (= ["contaminated" "private"] (get-in hub [:policy :labels]))))
    (testing "a public role is shown only public entries and public facts"
      (is (str/includes? (:prompt scout) "Scout found two grants."))
      (is (str/includes? (:prompt scout) "grants (1)\n"))
      (is (not (str/includes? (:prompt scout) "salary")))
      (is (not (str/includes? (:prompt scout) "household")))
      (is (not (core/private? (get-in scout [:policy :labels])))))
    (testing "a public item from outside is shown, with where it came from"
      (let [w (run-public w :fact/add {:topic "leads" :text "A page said apply."} ["contaminated"])
            scout (wake-request w "scout")]
        (is (str/includes? (:prompt scout) "grants (1), leads (1)\n"))
        (is (= ["contaminated"] (get-in scout [:policy :labels])))))))

(deftest a-wake-s-prompt-carries-the-labels-of-what-it-shows
  (let [w (-> (world)
              (run-public :role/add {:id "clerk" :title "Clerk" :instructions "File things."})
              (run "operator" :fact/add {:topic "salary" :text "Kira earns a lot."})
              (run "operator" :journal/write {:owner "clerk" :text "Filed the payslip."})
              (run-public :memo/send {:to "clerk" :subject "Go" :body "Look."}))
        clerk (wake-request w "clerk")]
    (is (str/includes? (:prompt clerk) "Filed the payslip."))
    (is (str/includes? (:prompt clerk) "salary (1)"))
    (is (= ["private"] (get-in clerk [:policy :labels])) "a house role reads everything, and knows it")))

(deftest a-public-role-is-not-given-the-operator-s-additions
  (let [w (run-public (world) :memo/send {:to "scout" :subject "Go" :body "Look."})
        w (run w "operator" :memo/send {:to "hub" :subject "Go" :body "Look."})
        scout (wake-request w "scout")
        hub (wake-request w "hub")]
    (is (not (str/includes? (:system-prompt scout) "SECRET-ADDITIONS")))
    (is (str/includes? (:system-prompt hub) "SECRET-ADDITIONS"))
    (is (str/includes? (:system-prompt scout) "You are a public role"))
    (is (not (contains? (:policy hub) :forbid_labels)))
    (testing "nor is a conversation with it"
      (let [w (run w "operator" :wake/start {:role "scout" :chat true :note dispatch/conversation-note})
            wake (support/calling-wake (:state w) "scout")
            request (dispatch/conversation-request {:data-dir "/nowhere" :config cfg} (:state w) wake
                                                   {:shared "Shared." :operator-additions "SECRET-ADDITIONS"
                                                    :now t0 :relay-bin "relay" :data-path "/nowhere"})]
        (is (not (str/includes? (:system-prompt request) "SECRET-ADDITIONS")))
        (is (= ["private"] (get-in request [:policy :forbid_labels])))))))

(deftest a-public-role-whose-own-text-holds-private-is-not-woken
  ;; Made public after a session holding private wrote its instructions.
  (let [before (config/resolve-config (update settings :grants dissoc "scout"))
        [state] (core/run-command (:state (run (world) "operator" :role/add {:id "clerk" :title "C" :instructions "x"}))
                                  {:type :role/update :actor "hub" :args {:id "scout" :instructions "Find grants for Kira's thesis."}
                                   :labels ["private"]}
                                  {:now t0 :config before})
        w (run-public {:state state :now t0} :memo/send {:to "scout" :subject "Go" :body "Look."})]
    (is (core/unwakeable-public-role? (:state w) cfg "scout"))
    (is (empty? (core/roles-to-wake (:state w) cfg t0)))
    (is (re-find #"relay update scout --public" (refusal #(run w "operator" :wake/start {:role "scout"}))))
    (testing "and only the operator, with --public, can write them now"
      (is (re-find #"only the operator" (refusal #(run w "hub" :role/update {:id "scout" :instructions "x"} ["private"]))))
      (is (re-find #"only the operator" (refusal #(run w "hub" :role/update {:id "scout" :title "x"} []))))
      (is (re-find #"with --public" (refusal #(run w "operator" :role/update {:id "scout" :instructions "Find arts grants."}))))
      (let [w (run-public w :role/update {:id "scout" :instructions "Find arts grants."})]
        (is (= ["scout"] (core/roles-to-wake (:state w) cfg t0)) "the fix the refusal names works")))))

(deftest roles-a-public-role-sees-are-those-written-with-public
  (let [w (-> (world)
              (run "operator" :role/add {:id "clerk" :title "Clerk" :instructions "File things."}))
        roles #(:content (read-as % "scout" "roles" {}))]
    (is (not (str/includes? (roles w) "clerk")) "the operator's role, written without --public")
    (is (str/includes? (roles w) "hub: Hub"))
    (let [w (run-public w :role/update {:id "clerk" :title "Clerk" :instructions "File things."})]
      (is (str/includes? (roles w) "clerk: Clerk") "rewritten with --public, id included"))
    (testing "an id a role chose holding private can never be made public"
      (let [w (run w "hub" :role/add {:id "zz-secret" :title "t" :instructions "i"} ["private"])
            w (run-public w :role/update {:id "zz-secret" :title "t" :instructions "i"})]
        (is (not (str/includes? (roles w) "zz-secret")))))))

(deftest a-public-role-that-cannot-run-is-reported-once
  (let [w (-> (world)
              ;; Rewritten without --public while it was not yet public.
              (run-before "operator" :role/update {:id "scout" :instructions "Unmarked."})
              (run "dispatcher" :tick {:lost-wakes []})
              (run "dispatcher" :tick {:lost-wakes []}))
        told (filter #(= "public-role-cannot-run" (:origin %)) (vals (:messages (:state w))))]
    (is (= 1 (count told)))
    (is (str/includes? (support/message-text (first told)) "relay update scout --public"))
    (is (some #(str/includes? % "Public role scout cannot run") (render/status (:state w) java.time.ZoneOffset/UTC t0 cfg)))))

;; --- an event marked public never carries private

(deftest a-public-event-carrying-private-is-never-read
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"public and carries private"
                        (core/replay [{:seq 1 :at "2026-10-14T12:00:00Z" :type "journal/written" :actor "scout"
                                       :labels ["private"] :public true :data {:owner "scout" :text "x"}}])))
  (testing "only an item is ever public"
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/replay [{:seq 1 :at "2026-10-14T12:00:00Z" :type "message/created" :actor "scout"
                                :labels [] :public true :data {:kind "x" :title "t" :body "b"}}])))))


;; --- through relay's tool command and agentloop's outcome

(defn- with-data [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-public-test"}))
        data (str dir "/data")]
    (log/init! data)
    (spit (str data "/relay.edn") (pr-str settings))
    (try (f data (inbound/system data)) (finally (fs/delete-tree dir)))))

(deftest a-result-carrying-private-is-refused-and-the-operator-told
  (with-data
    (fn [_ sys]
      (doseq [id ["hub" "scout"]]
        (inbound/submit! sys {:type :role/add :actor "operator" :public (= "scout" id) :args {:id id :title id :instructions "x"}}))
      (let [{:keys [ok error labels]} (tools/call! sys "scout" "lookup" {} ["contaminated"] "c1")
            notes (vals (:messages (inbound/current-state sys)))]
        (is (nil? ok))
        (is (= tools/refused-result error))
        (is (= [] labels))
        (is (some #(and (= "result-refused" (:origin %)) (str/includes? (support/message-text %) "lookup")) notes))
        (is (not-any? #(str/includes? (support/message-text %) "SECRET") notes))))))

(deftest agentloop-s-refusals-reach-the-operator
  (let [report (agentloop/classify {:outcome {:type "outcome" :reason "completed" :labels ["contaminated"]
                                              :refused [{:call_id "c1" :tool "lookup" :labels ["private"]}]}
                                    :exit 0 :session "s" :now t0})
        forbidden (agentloop/classify {:outcome {:type "outcome" :reason "forbidden" :forbidden ["private"]}
                                       :exit 0 :session "s" :now t0})]
    (is (= [{:call_id "c1" :tool "lookup" :labels ["private"]}] (:refused report)))
    (is (= ["ran" true ["private"]] ((juxt :outcome :crashed :forbidden) forbidden)))
    (let [w (run-public (world) :memo/send {:to "scout" :subject "Go" :body "Look."})
          w (run w "dispatcher" :wake/start {:role "scout" :dispatched true})
          wake (support/calling-wake (:state w) "scout")
          ended (run w "dispatcher" :wake/end (dispatch/wake-end-args (:id wake) report))
          ended-forbidden (run w "dispatcher" :wake/end (dispatch/wake-end-args (:id wake) forbidden))]
      (is (some #(and (= "result-refused" (:origin %)) (str/includes? (support/message-text %) "lookup"))
                (vals (:messages (:state ended)))))
      (is (some #(= "session-forbidden" (:origin %)) (vals (:messages (:state ended-forbidden)))))
      (is (nil? (:interrupted-wake (core/role (:state ended-forbidden) "scout")))
          "its next wake starts a fresh session"))))

(deftest the-operator-writes-public-with-the-flag
  (with-data
    (fn [data sys]
      (inbound/submit! sys {:type :role/add :actor "operator" :public true :args {:id "scout" :title "S" :instructions "x"}})
      (let [relay #(cli/execute (concat ["--data" data] %&))
            state #(inbound/current-state sys)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"may leave this machine"
                              (relay "memo" "scout" "-s" "Look" "-m" "for grants")))
        (is (= 0 (:exit (relay "memo" "scout" "-s" "Look" "-m" "for grants" "--public"))))
        (is (:public (first (core/unread-memos (state) "scout"))))
        (relay "fact" "add" "Grants open in March." "--public")
        (is (some #(:public (core/fact-as-known % nil)) (vals (:facts (state)))))
))))

;; --- a role made public later

(deftest a-session-from-before-a-role-was-public-is-never-carried-on
  (let [w (-> (world)
              (run-before "operator" :memo/send {:to "scout" :subject "Kira's thesis" :body "Secret topic."})
              (run-before "dispatcher" :wake/start {:role "scout" :dispatched true}))
        wake (support/calling-wake (:state w) "scout")
        ;; Cut short, so its next wake would carry the session on.
        w (run-before w "dispatcher" :wake/end {:wake (:id wake) :outcome "limited" :session "old" :said ""
                                                 :labels ["private"]})]
    (is (= (:id wake) (:interrupted-wake (core/role (:state w) "scout"))))
    (let [w (-> w (run-public :memo/send {:to "scout" :subject "Go" :body "Look."})
                (run "dispatcher" :wake/start {:role "scout"}))
          now (support/calling-wake (:state w) "scout")]
      (is (nil? (:resumes now)) "the session from before is not resumed")
      (is (:public-session now))
      (testing "one from after is"
        (let [w (run w "dispatcher" :wake/end {:wake (:id now) :outcome "limited" :session "new" :said "" :labels []})
              w (run w "dispatcher" :wake/start {:role "scout"})]
          (is (= (:id now) (:resumes (support/calling-wake (:state w) "scout")))))))))

(deftest a-public-role-is-never-run-on-a-script-harness
  (let [script-settings (assoc-in settings [:harnesses :s] {:kind :script :command ["true"]})
        before (config/resolve-config (update script-settings :grants dissoc "scout"))
        after (config/resolve-config script-settings)
        [state] (core/run-command (:state (world)) {:type :role/update :actor "operator" :args {:id "scout" :harness "s"}}
                                  {:now t0 :config before})
        [state] (core/run-command state {:type :memo/send :actor "operator" :public true
                                         :args {:to "scout" :subject "Go" :body "Look."}}
                                  {:now t0 :config after})]
    (is (core/unwakeable-public-role? state after "scout"))
    (is (empty? (core/roles-to-wake state after t0)))
    (is (some? (try (core/decide state {:type :wake/start :actor "operator" :args {:role "scout"}} {:now t0 :config after})
                    nil
                    (catch clojure.lang.ExceptionInfo e (core/rejection e)))))))

(deftest rewriting-a-routine-replaces-a-run-the-role-cannot-see
  (let [w (-> (world)
              (run-before "operator" :routine/write {:owner "scout" :name "daily" :schedule "every 10m" :instructions "Secret."})
              (update :now #(.plusSeconds ^Instant % 700))
              (run-before "dispatcher" :tick {:lost-wakes []}))
        hidden (:last-memo (core/routine (:state w) "scout" "daily"))]
    (is (not (:public (core/memo (:state w) hidden))) "a run the public role cannot see")
    (let [w (-> w
                (run-public :routine/write {:owner "scout" :name "daily" :schedule "every 10m" :instructions "Look."})
                (update :now #(.plusSeconds ^Instant % 700))
                (run "dispatcher" :tick {:lost-wakes []}))
          s (:state w)]
      (is (= :read (:status (core/memo s hidden))))
      (is (:public (core/memo s (:last-memo (core/routine s "scout" "daily")))) "the rewritten routine fires")
      (is (= ["scout"] (core/roles-to-wake s cfg (:now w)))))))

(deftest a-public-role-s-conversations-are-kept-apart
  (let [w (run (world) "operator" :wake/start {:role "scout" :chat true :note dispatch/conversation-note})
        w (run w "operator" :wake/start {:role "hub" :chat true :note dispatch/conversation-note})
        req #(dispatch/conversation-request {:data-dir "/d" :config cfg} (:state w) (support/calling-wake (:state w) %)
                                            {:shared "" :now t0 :relay-bin "relay" :data-path "/d"})]
    (is (str/ends-with? (:session-dir (req "scout")) "conversations-public"))
    (is (str/ends-with? (:session-dir (req "hub")) "conversations-labelled"))))

(deftest a-call-is-public-only-from-the-public-wake-that-made-it
  (let [w (-> (world)
              (run-before "operator" :memo/send {:to "scout" :subject "s" :body "b"})
              (run-before "dispatcher" :wake/start {:role "scout" :dispatched true}))
        old (:id (support/calling-wake (:state w) "scout"))
        ;; The old wake's relay went away; the tick ends it, and a public
        ;; wake starts while the old agentloop may still be calling.
        w (-> w (run "dispatcher" :tick {:lost-wakes [old]})
              (run-public :memo/send {:to "scout" :subject "Go" :body "Look."})
              (run "dispatcher" :wake/start {:role "scout" :dispatched true}))
        new (:id (support/calling-wake (:state w) "scout"))
        hub-wake (:id (support/calling-wake (:state (-> w (run "operator" :memo/send {:to "hub" :subject "s" :body "b"})
                                                     (run "dispatcher" :wake/start {:role "hub" :dispatched true})))
                                         "hub"))
        note (fn [wake]
               (let [w (run* w {:type :note/write :actor "scout" :args {:name "n" :text "t"} :labels []
                                :session-wake wake})]
                 (:public (get-in (:state w) [:notes "scout" "n"]))))]
    (is (:public-session (get-in (:state w) [:wakes new])))
    (is (true? (note new)))
    (is (false? (note old)) "the old session, from before the role was public")
    (is (false? (note nil)) "no wake named")
    (is (false? (note hub-wake)) "another role's wake")))

(deftest a-public-role-cannot-reach-a-role-it-cannot-see
  (let [w (-> (world) (run "hub" :role/add {:id "banker" :title "Bank" :instructions "x"} []))
        w (in-wake w "scout" identity)
        send #(refusal (fn [] (in-wake w "scout" (fn [w] (run w "scout" :memo/send {:to % :subject "s" :body "b"} [])))))]
    (is (= "There is no role called banker." (send "banker")))
    (is (= "There is no role called nobody." (send "nobody")) "the same as for no role at all")
    (is (nil? (send "hub")))))

(deftest grants-name-roles-by-string
  (is (str/includes? (problem {:scout {:public true}}) "not a role id"))
  (is (str/includes? (problem {"Scout" {:public true}}) "not a role id"))
  (is (str/includes? (problem {"scout " {:public true}}) "not a role id"))
  (is (str/includes? (problem {"operator" {:tools ["search"]}}) "not a role id")))

;; --- from the round 4 review

(deftest a-session-that-began-public-stays-public-when-relay-edn-changes
  ;; The operator takes :public away while a public wake (or conversation)
  ;; runs: that session is still read for as public, and refused private.
  (let [w (-> (world)
              (run "hub" :role/add {:id "acct-watch" :title "Watch HSBC account 4417" :instructions "x"} [])
              (run "hub" :doc/put {:path "finance/hsbc.md" :content "Overdraft."} []))
        w (in-wake w "scout" identity)
        w (-> w (run-public :memo/send {:to "scout" :subject "Go" :body "Look."})
              (run "dispatcher" :wake/start {:role "scout" :dispatched true}))
        wake (:id (support/calling-wake (:state w) "scout"))
        read (fn [tool] (binding [tools/*wake* wake]
                          (let [r ((:read (get tools/builtin tool)) {:state (:state w) :config before-public :now t0}
                                   "scout" {})]
                            (if (map? r) (:content r) r))))]
    (is (not (str/includes? (read "roles") "acct-watch")))
    (is (not (str/includes? (read "docs") "hsbc")))
    (testing "without the wake named, as any role it is not public"
      (is (str/includes? (:content ((:read (get tools/builtin "roles")) {:state (:state w) :config before-public :now t0}
                                    "scout" {}))
                         "acct-watch")))
    (testing "and a call from it claiming private is refused, as for any public session"
      (is (some? (try (core/decide (:state w) {:type :journal/write :actor "scout" :args {:text "x"} :labels ["private"]
                                               :session-wake wake}
                                   {:now t0 :config before-public})
                      nil
                      (catch clojure.lang.ExceptionInfo e (core/rejection e))))))))

(deftest an-earlier-known-at-says-nothing-a-public-role-cannot-see-now
  (let [w (run-public (world) :fact/add {:text "The grant office is in Leeds."})
        id (:seq (first (:last w)))
        w (-> w (update :now #(.plusSeconds ^Instant % 3600))
              (run "hub" :fact/correct {:fact id :text "The office is at Kira's flat." :why "moved"} ["private"]))
        facts #(:content (read-as w "scout" "facts" %))]
    (is (= "No facts match." (facts {})))
    (is (= "No facts match." (facts {:known_at "2026-10-14T12:30:00Z"})) "as fact_history says: no such fact")))

(deftest a-public-doc-cannot-go-where-a-path-was-chosen-in-private
  (let [w (run (world) "operator" :doc/put {:path "notes/plan.md" :content "Mine."})]
    (is (re-find #"first written without --public.*new path with --public"
                 (refusal #(run-public w :doc/put {:path "notes/plan.md" :content "For the scout."}))))
    (is (seq (:last (run-public w :doc/put {:path "public/plan.md" :content "For the scout."}))))))

(deftest a-public-session-never-touches-the-registry
  ;; Its grant was changed to :may-add while it ran.
  (let [w (-> (world) (run "operator" :role/add {:id "tax" :title "Tax" :instructions "My taxes."}))
        w (-> w (run-public :memo/send {:to "scout" :subject "Go" :body "Look."})
              (run "dispatcher" :wake/start {:role "scout" :dispatched true}))
        wake (:id (support/calling-wake (:state w) "scout"))
        demoted (config/resolve-config (assoc-in settings [:grants "scout"] {:may-add true}))
        refused (fn [type args]
                  (try (core/decide (:state w) {:type type :actor "scout" :args args :labels [] :session-wake wake}
                                    {:now t0 :config demoted})
                       nil
                       (catch clojure.lang.ExceptionInfo e (ex-message e))))
        msg "scout is a public role, and a public role may not add, change or remove roles."]
    (is (= msg (refused :role/add {:id "newbie" :title "N" :instructions "From the web."})))
    (is (= msg (refused :role/update {:id "tax" :title "Tax" :instructions "Search for tax forms."})))
    (is (= msg (refused :role/update {:id "nosuch" :title "x"})) "the same whether or not the role exists")
    (is (= msg (refused :role/remove {:id "tax"})))
    (is (re-find #"may not requeue" (refused :memo/retry {:role "tax"})))))

(deftest a-public-role-put-right-is-told-again-when-it-breaks-again
  (let [tick #(run % "dispatcher" :tick {:lost-wakes []})
        w (-> (world)
              (run-before "operator" :role/update {:id "scout" :instructions "Unmarked."})
              tick
              (run-public :role/update {:id "scout" :instructions "Marked."})
              tick
              (run-before "operator" :role/update {:id "scout" :instructions "Unmarked again."})
              tick)
        kinds (map :origin (sort-by :id (vals (:messages (:state w)))))]
    (is (= ["public-role-cannot-run" "public-role-can-run" "public-role-cannot-run"]
           (filter #{"public-role-cannot-run" "public-role-can-run"} kinds)))))



;; --- from the round 6 review
