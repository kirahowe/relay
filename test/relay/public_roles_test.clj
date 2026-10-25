(ns relay.public-roles-test
  "Public roles: roles whose sessions never hold private, so they may send
  data out unheld. Everything that would put private into one is refused
  where it would happen; the property in labels-test checks the same over
  random histories."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cli :as cli]
            [relay.config :as config]
            [relay.core :as core]
            [relay.test-support :as support]
            [relay.dispatch :as dispatch]
            [relay.harness.agentloop :as agentloop]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.render :as render]
            [relay.text :as text]
            [relay.tools :as tools])
  (:import (java.time Instant)))

(def t0 (Instant/parse "2026-10-14T12:00:00Z"))

(def fixture (str (fs/absolutize "test/fixtures/envelope-tool")))

(def settings
  {:timezone "UTC"
   :default-harness :agentloop
   :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}}
   :notify {:kind :none}
   :tools {"search" {:class :egress :labels ["contaminated"] :description "Search the web." :command ["true"]}
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
  (is (str/includes? (problem {"hub" {:public true}}) "dead-letter-handler")
      "the default handler, hub, counts too")
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

(deftest a-memo-from-a-private-session-waits-to-be-declassified
  (let [w (-> (world) (run "hub" :memo/send {:to "scout" :subject "Find grants for my thesis"
                                              :body "My supervisor is Dr Secret."}
                           ["contaminated" "private"]))
        id (:id (first (core/unread-memos (:state w) "scout")))
        m (core/memo (:state w) id)
        note (last (vals (:notifications (:state w))))]
    (is (core/awaiting-declassification? cfg m))
    (is (not (core/quarantined? cfg m)) "a memo to a public role is never quarantined")
    (testing "the operator is told who sent it to whom, and nothing of what it says"
      (is (= "memo-awaiting-declassification" (:kind note)))
      (is (str/includes? (:title note) "from hub to scout"))
      (is (not (str/includes? (str (:title note) (:body note)) "thesis")))
      (is (not (str/includes? (str (:title note) (:body note)) "Secret"))))
    (testing "the role is not shown it, told of it, or woken for it"
      (is (= "Nothing is waiting for you." (:content (read-as w "scout" "inbox" {}))))
      (is (empty? (core/roles-to-wake (:state w) cfg (:now w))))
      (is (empty? (:last (run w "operator" :wake/start {:role "scout"})))))
    (testing "and cannot close or block it, since for it there is no such memo"
      (is (re-find #"There is no memo" (refusal #(run w "scout" :memo/close {:memo id :outcome "x"}))))
      (is (re-find #"There is no memo" (refusal #(run w "scout" :memo/block {:memo id :explanation "x"})))))
    (testing "only the operator declassifies"
      (is (some? (refusal #(run w "hub" :memo/declassify {:memo id :sha256 (core/declassify-digest (:state w) id)}))))
      (is (some? (refusal #(run w "scout" :memo/declassify {:memo id :sha256 (core/declassify-digest (:state w) id)})))))
    (testing "only the memo the operator was shown"
      (is (re-find #"not what you were shown" (refusal #(run w "operator" :memo/declassify {:memo id}))))
      (is (re-find #"not what you were shown" (refusal #(run w "operator" :memo/declassify {:memo id :sha256 (core/sha256 "x")})))))
    (let [w (run w "operator" :memo/declassify {:memo id :sha256 (core/declassify-digest (:state w) id)})
          m (core/memo (:state w) id)]
      (is (:public m))
      (is (= ["contaminated"] (:labels m)) "private goes, contaminated stays")
      (is (= ["scout"] (core/roles-to-wake (:state w) cfg (:now w))))
      (is (str/includes? (:content (read-as w "scout" "inbox" {})) "Dr Secret"))
      (let [request (wake-request w "scout")]
        (is (str/includes? (:prompt request) "Dr Secret"))
        (is (= ["contaminated"] (get-in request [:policy :labels])))
        (is (= ["private"] (get-in request [:policy :forbid_labels]))))
      (testing "a memo that is not waiting cannot be declassified"
        (is (some? (refusal #(run w "operator" :memo/declassify {:memo id}))))))))

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
      (let [w (run* (world) {:type :memo/send :actor "hub" :args {:to "scout" :subject "s" :body "b"}
                             :labels [] :public true})]
        (is (not (:public (first (core/unread-memos (:state w) "scout")))))
        (is (core/awaiting-declassification? cfg (first (core/unread-memos (:state w) "scout"))))))
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
    (testing "--public goes with --untrusted"
      (let [w (run-public w :memo/send {:to "scout" :subject "Pasted" :body "From a page."} ["contaminated"])
            m (first (core/unread-memos (:state w) "scout"))]
        (is (and (:public m) (= ["contaminated"] (:labels m))))
        (is (not (core/awaiting-declassification? cfg m)))))
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
    (testing "the last three entries, newest last, a long one cut, a contaminated one withheld"
      (is (not (str/includes? (:prompt hub) "First")))
      (is (< (str/index-of (:prompt hub) "Second.") (str/index-of (:prompt hub) "Long. ")))
      (is (str/includes? (:prompt hub) "is withheld because it is contaminated"))
      (is (not (str/includes? (:prompt hub) "after a page")))
      (is (str/includes? (:prompt hub) "[cut; read it whole with `journal`]"))
      (is (not (str/includes? (:prompt hub) (apply str (repeat 650 "x"))))))
    (testing "topics with counts, leaving out contaminated facts"
      (is (str/includes? (:prompt hub) "grants (1), household/car (2), salary (1)\n"))
      (is (not (str/includes? (:prompt hub) "outside"))))
    (testing "the hub's wake carries private, and nothing contaminated it did not show"
      (is (= ["private"] (get-in hub [:policy :labels]))))
    (testing "a public role is shown only public entries and public facts"
      (is (str/includes? (:prompt scout) "Scout found two grants."))
      (is (str/includes? (:prompt scout) "grants (1)\n"))
      (is (not (str/includes? (:prompt scout) "salary")))
      (is (not (str/includes? (:prompt scout) "household")))
      (is (not (core/private? (get-in scout [:policy :labels])))))
    (testing "a contaminated public item does not make a clean wake contaminated: it is left out"
      (let [w (run-public w :fact/add {:topic "leads" :text "A page said apply."} ["contaminated"])
            scout (wake-request w "scout")]
        (is (str/includes? (:prompt scout) "grants (1)\n\nSome facts carry data this wake has not seen"))
        (is (not (str/includes? (:prompt scout) "leads")))
        (is (= [] (get-in scout [:policy :labels])))))))

(deftest a-wake-s-prompt-never-raises-its-labels
  ;; A clerk woken by a public memo starts clean. The private fact and
  ;; journal entry stay out of its prompt rather than making it private,
  ;; so its egress calls are not held for something it never chose to read.
  (let [w (-> (world)
              (run-public :role/add {:id "clerk" :title "Clerk" :instructions "File things."})
              (run "operator" :fact/add {:topic "salary" :text "Kira earns a lot."})
              (run-public :fact/add {:topic "office" :text "The office opens at nine."})
              (run "operator" :journal/write {:owner "clerk" :text "Filed the payslip."})
              (run-public :journal/write {:owner "clerk" :text "Checked the office hours."})
              (run-public :memo/send {:to "clerk" :subject "Go" :body "Look."}))
        clerk (wake-request w "clerk")
        prompt (:prompt clerk)]
    (is (= [] (get-in clerk [:policy :labels])))
    (is (str/includes? prompt "Checked the office hours."))
    (is (not (str/includes? prompt "payslip")))
    (is (re-find #"Entry \d+ carries data this wake has not seen. Read it with `journal` if you need it." prompt))
    (is (str/includes? prompt "office (1)\n\nSome facts carry data this wake has not seen, so they are not counted here."))
    (is (not (str/includes? prompt "salary")))
    (testing "woken by a private memo, the same clerk sees them"
      (let [clerk (wake-request (run w "operator" :memo/send {:to "clerk" :subject "Pay" :body "File it."}) "clerk")]
        (is (= ["private"] (get-in clerk [:policy :labels])))
        (is (str/includes? (:prompt clerk) "Filed the payslip."))
        (is (str/includes? (:prompt clerk) "office (1), salary (1)\n"))
        (is (not (str/includes? (:prompt clerk) "not counted here")))))))

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
        told (filter #(= "public-role-cannot-run" (:kind %)) (vals (:notifications (:state w))))]
    (is (= 1 (count told)))
    (is (str/includes? (:body (first told)) "relay update scout --public"))
    (is (some #(str/includes? % "Public role scout cannot run") (render/status (:state w) java.time.ZoneOffset/UTC t0 cfg)))))

(deftest a-deny-reason-for-a-public-role-is-public
  (let [w (run-public (world) :memo/send {:to "scout" :subject "Go" :body "Look."})
        w (run w "dispatcher" :wake/start {:role "scout" :dispatched true})
        wake (support/calling-wake (:state w) "scout")
        w (run w "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :session "s1" :said "" :labels ["contaminated"]
                                         :held [{:id "c1" :name "search" :input {:args ["x"]} :input-sha256 "d"}]})
        hold (first (core/open-holds (:state w) "scout"))
        w (run w "operator" :hold/resolve {:hold (:id hold) :verdict "deny" :reason "Not that site."})
        w (run w "dispatcher" :wake/start {:role "scout" :dispatched true})
        wake (support/calling-wake (:state w) "scout")]
    (is (= [(:id hold)] (:resolves wake)))
    (is (not (core/private? (core/wake-labels (:state w) cfg wake))))))

;; --- an event marked public never carries private

(deftest a-public-event-carrying-private-is-never-read
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"public and carries private"
                        (core/replay [{:seq 1 :at "2026-10-14T12:00:00Z" :type "journal/written" :actor "scout"
                                       :labels ["private"] :public true :data {:owner "scout" :text "x"}}])))
  (testing "only an item is ever public"
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/replay [{:seq 1 :at "2026-10-14T12:00:00Z" :type "notification/created" :actor "scout"
                                :labels [] :public true :data {:kind "x" :title "t" :body "b"}}]))))
  (testing "an event from before labels is not public, whatever it says"
    (let [s (core/replay [{:seq 1 :at "2026-10-14T12:00:00Z" :type "role/added" :actor "operator" :labels []
                           :data {:id "scout" :title "S" :instructions "x"}}
                          {:seq 2 :at "2026-10-14T12:00:00Z" :type "memo/sent" :actor "scout" :public true
                           :data {:from "scout" :to "scout" :subject "s" :body "b"}}
                          {:seq 3 :at "2026-10-14T12:00:00Z" :type "labels/enabled" :actor "dispatcher" :data {}}])]
      (is (not (:public (core/memo s 2)))))))

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
            notes (vals (:notifications (inbound/current-state sys)))]
        (is (nil? ok))
        (is (= tools/refused-result error))
        (is (= [] labels))
        (is (some #(and (= "result-refused" (:kind %)) (str/includes? (:body %) "lookup")) notes))
        (is (not-any? #(str/includes? (str (:body %)) "SECRET") notes))))))

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
      (is (some #(and (= "result-refused" (:kind %)) (str/includes? (:body %) "lookup"))
                (vals (:notifications (:state ended)))))
      (is (some #(= "session-forbidden" (:kind %)) (vals (:notifications (:state ended-forbidden)))))
      (is (nil? (:interrupted-wake (core/role (:state ended-forbidden) "scout")))
          "its next wake starts a fresh session"))))

;; --- relay declassify

(defn- tty [shown answer]
  (fn [] {:say (fn [text] (swap! shown str text)) :ask (fn [] (answer @shown))}))

(defn- typed-code [shown] (second (re-find #"Type (\w{4}) to" shown)))

(deftest declassify-shows-the-memo-and-wants-the-code
  (with-data
    (fn [data sys]
      (doseq [id ["hub" "scout"]]
        (inbound/submit! sys {:type :role/add :actor "operator" :public (= "scout" id) :args {:id id :title id :instructions "x"}}))
      (inbound/submit! sys {:type :memo/send :actor "hub" :labels ["contaminated" "private"]
                            :args {:to "scout" :subject "Grants for the thesis" :body "See https://example.com/x"}})
      (let [id (:id (first (core/unread-memos (inbound/current-state sys) "scout")))
            relay #(cli/execute (concat ["--data" data] %&))
            public? #(:public (core/memo (inbound/current-state sys) id))]
        (testing "the status says it is waiting"
          (is (some #(str/includes? % (str "declassified: " id)) (:out (relay "status")))))
        (testing "with no terminal, nothing changes"
          (binding [cli/*tty* (fn [] (throw (java.io.IOException. "Device not configured")))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"terminal" (relay "declassify" (str id)))))
          (is (not (public?))))
        (testing "a wrong code changes nothing, after showing it in full"
          (let [shown (atom "")]
            (binding [cli/*tty* (tty shown (constantly "ZZZZ"))]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not the code" (relay "declassify" (str id)))))
            (is (str/includes? @shown "Grants for the thesis"))
            (is (str/includes? @shown "from role \"hub\""))
            (is (str/starts-with? @shown (str (core/declassify-text (core/memo (inbound/current-state sys) id)) "\n\n"))
                "the terminal shows the memo's review itself")
            (is (str/includes? @shown "[contaminated]"))
            (is (str/includes? @shown "hxxps://example[.]com") "contaminated text is defanged")
            (is (str/includes? @shown "web search"))
            (is (not (public?)))))
        (testing "the code typed back declassifies it"
          (binding [cli/*tty* (tty (atom "") typed-code)]
            (is (= 0 (:exit (relay "declassify" (str id))))))
          (is (public?)))
        (testing "a memo not waiting is refused before anything is asked"
          (let [asked (atom false)]
            (binding [cli/*tty* (fn [] (reset! asked true) {:say identity :ask (constantly "x")})]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not waiting" (relay "declassify" (str id)))))
            (is (not @asked))))))))

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
        (testing "a deny for a public role says the reason is public"
          (inbound/submit! sys {:type :wake/start :actor "dispatcher" :args {:role "scout" :dispatched true}})
          (inbound/submit! sys {:type :wake/end :actor "dispatcher"
                                :args {:wake (:id (support/calling-wake (state) "scout")) :outcome "ran" :session "s1"
                                       :said "" :labels []
                                       :held [{:id "c1" :name "search" :input {} :input-sha256 "d"}]}})
          (let [shown (atom "")
                hold (:id (first (core/open-holds (state) "scout")))]
            (binding [cli/*tty* (tty shown typed-code)]
              (is (= 0 (:exit (relay "deny" (str hold) "not" "that")))))
            (is (str/includes? @shown "scout is a public role: your reason is shown to it as public"))))))))

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

(deftest a-held-session-from-before-a-role-was-public-is-ended
  (let [w (-> (world)
              (run-before "operator" :memo/send {:to "scout" :subject "Post it" :body "Secret."})
              (run-before "dispatcher" :wake/start {:role "scout" :dispatched true}))
        wake (support/calling-wake (:state w) "scout")
        w (run-before w "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :session "old" :said "" :labels ["private"]
                                                 :held [{:id "c1" :name "search" :input {} :input-sha256 "d"}
                                                        {:id "c2" :name "search" :input {:x 1} :input-sha256 "e"}]})
        [h1] (core/open-holds (:state w) "scout")
        ;; Denied with a reason while the role was not public.
        w (run-before w "operator" :hold/resolve {:hold (:id h1) :verdict "deny" :reason "Not with my bank details."})]
    (is (not (:reason-public (core/hold (:state w) (:id h1)))))
    (let [w (run w "operator" :wake/start {:role "scout"})
          s (:state w)]
      (is (get-in s [:ended-sessions "old"]))
      (is (empty? (core/open-holds s "scout")) "its other call is denied")
      (is (nil? (support/calling-wake s "scout")) "and its memo, not public, waits")
      (is (not-any? (comp seq :resolves) (vals (:wakes s)))))
    (testing "a reason given before the role was public stays private wherever it goes"
      (is (core/private? (core/wake-labels (:state w) cfg {:role "scout" :resolves [(:id h1)]}))))
    (testing "one given after is public"
      (let [w (run w "operator" :hold/resolve {:hold (:id (first (core/open-holds (:state w) "scout"))) :verdict "deny"
                                               :reason "Not that one."})]
        (is (:reason-public (last (sort-by :id (vals (:holds (:state w)))))))))))

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
    (is (core/awaiting-declassification? cfg (core/memo (:state w) hidden)))
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

(deftest a-session-left-from-before-public-is-ended-even-if-nobody-decides-it
  ;; Its call is still open, so nothing would resume it, and the memo it
  ;; keeps is declassified: only waking the role to end it frees the memo.
  (with-data
    (fn [_ sys]
      (let [before (assoc sys :config before-public)
            submit #(inbound/submit! %1 %2)
            state #(inbound/current-state sys)]
        (doseq [id ["hub" "scout"]]
          (submit before {:type :role/add :actor "operator" :public (= "scout" id) :args {:id id :title id :instructions "x"}}))
        (submit before {:type :memo/send :actor "operator" :args {:to "scout" :subject "Post it" :body "Secret."}})
        (submit before {:type :wake/start :actor "dispatcher" :args {:role "scout" :dispatched true}})
        (submit before {:type :wake/end :actor "dispatcher"
                        :args {:wake (:id (support/calling-wake (state) "scout")) :outcome "ran" :session "old" :said ""
                               :labels ["private"] :held [{:id "c1" :name "search" :input {} :input-sha256 "d"}]}})
        (let [memo (:id (first (core/unread-memos (state) "scout")))]
          (submit sys {:type :memo/declassify :actor "operator" :args {:memo memo :sha256 (core/declassify-digest (state) memo)}})
          (is (= ["scout"] (core/roles-to-wake (state) cfg (Instant/now))) "woken to end the old session")
          (testing "a wake ends the old session and carries the memo it kept"
            (let [ran (atom nil)]
              (with-redefs [dispatch/run-harness! (fn [& _] (reset! ran true) {:outcome "ran"})]
                (is (= :ran (:result (dispatch/wake! sys "scout" {:dispatched true})))))
              (is @ran)
              (is (get-in (state) [:ended-sessions "old"]))
              (is (= [memo] (:memos (last (sort-by :id (vals (:wakes (state)))))))))))))))

(deftest a-wake-that-only-ends-old-sessions-runs-nothing
  ;; Regression: dispatch took the first event of the start as the wake.
  (with-data
    (fn [_ sys]
      (let [before (assoc sys :config before-public)
            state #(inbound/current-state sys)]
        (doseq [id ["hub" "scout"]]
          (inbound/submit! before {:type :role/add :actor "operator" :public (= "scout" id) :args {:id id :title id :instructions "x"}}))
        (inbound/submit! before {:type :memo/send :actor "operator" :args {:to "scout" :subject "Post it" :body "Secret."}})
        (inbound/submit! before {:type :wake/start :actor "dispatcher" :args {:role "scout" :dispatched true}})
        (inbound/submit! before {:type :wake/end :actor "dispatcher"
                                 :args {:wake (:id (support/calling-wake (state) "scout")) :outcome "ran" :session "old"
                                        :said "" :labels ["private"]
                                        :held [{:id "c1" :name "search" :input {} :input-sha256 "d"}]}})
        (with-redefs [dispatch/run-harness! (fn [& _] (throw (ex-info "no harness should run" {})))]
          (is (= {:result :idle} (dispatch/wake! sys "scout" {:dispatched true}))))
        (is (get-in (state) [:ended-sessions "old"]))
        (is (nil? (support/calling-wake (state) "scout")))
        (is (empty? (core/roles-to-wake (state) cfg (Instant/now))) "and it is not woken again for them")))))

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

(deftest a-declassified-routine-run-carries-only-its-own-labels
  (let [w (-> (world)
              (run-before "operator" :memo/send {:to "scout" :subject "Go" :body "Look."})
              (run-before "dispatcher" :wake/start {:role "scout"})
              (as-> w (let [wake (support/calling-wake (:state w) "scout")]
                        (-> w
                            (run-before "scout" :routine/write {:name "daily" :schedule "every 10m" :instructions "Look."}
                                        ["private"])
                            (run-before "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :said "" :session "s"}))))
              (update :now #(.plusSeconds ^Instant % 700))
              (run-before "dispatcher" :tick {:lost-wakes []}))
        m (:last-memo (core/routine (:state w) "scout" "daily"))
        w (run w "operator" :memo/declassify {:memo m :sha256 (core/declassify-digest (:state w) m)})]
    (is (core/private? (core/routine-labels (:state w) "scout" "daily")))
    (is (not (core/private? (core/wake-labels (:state w) cfg {:role "scout" :memos [m]}))))
    (is (= ["scout"] (core/roles-to-wake (:state w) cfg (:now w))))
    (is (= [m] (:memos (support/calling-wake (:state (run w "dispatcher" :wake/start {:role "scout" :dispatched true})) "scout"))))))

(deftest a-public-session-is-ended-rather-than-given-a-private-reason
  ;; Held while public, denied with a reason while the role was briefly
  ;; not public, then carried on once it is again.
  (let [w (-> (world) (run-public :memo/send {:to "scout" :subject "Go" :body "Look."})
              (run "dispatcher" :wake/start {:role "scout" :dispatched true}))
        wake (support/calling-wake (:state w) "scout")
        w (run w "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :session "pub" :said "" :labels []
                                         :held [{:id "c1" :name "search" :input {} :input-sha256 "d"}]})
        h (:id (first (core/open-holds (:state w) "scout")))
        w (run-before w "operator" :hold/resolve {:hold h :verdict "deny" :reason "My bank says no."})]
    (is (= ["pub"] (core/sessions-from-before-public (:state w) cfg "scout")))
    (let [w (run w "dispatcher" :wake/start {:role "scout" :dispatched true})
          s (:state w)]
      (is (get-in s [:ended-sessions "pub"]))
      (is (not (core/private? (core/wake-labels s cfg (support/calling-wake s "scout")))))
      (is (empty? (:resolves (support/calling-wake s "scout")))))))

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

(deftest declassify-shows-what-would-hide-in-the-memo
  ;; A secret's bytes as variation selectors, which draw as nothing.
  (with-data
    (fn [data sys]
      (doseq [id ["hub" "scout"]]
        (inbound/submit! sys {:type :role/add :actor "operator" :public (= "scout" id) :args {:id id :title id :instructions "x"}}))
      (let [hide (fn [b] (String. (Character/toChars (if (< b 16) (+ 0xFE00 b) (+ 0xE0100 (- b 16))))))
            body (str "Search for arts grants" (apply str (map #(hide (int %)) "PIN 0912")) ".")]
        (inbound/submit! sys {:type :memo/send :actor "hub" :labels ["private"]
                              :args {:to "scout" :subject "Grants" :body body}})
        (let [id (:id (first (core/unread-memos (inbound/current-state sys) "scout")))
              shown (atom "")]
          (binding [cli/*tty* (tty shown (constantly "NOPE"))]
            (is (thrown? clojure.lang.ExceptionInfo (cli/execute ["--data" data "declassify" (str id)]))))
          ;; Declassifying takes them for blanks, so the role is given
          ;; none of them, and the operator is shown what it is given.
          (is (str/includes? @shown "Search for arts grants ."))
          (is (not (re-find #"\\u\{" @shown)))
          (is (not-any? #(<= 0xFE00 % 0xFE0F) (iterator-seq (.iterator (.codePoints ^String @shown)))))
          (binding [cli/*tty* (tty (atom "") typed-code)]
            (cli/execute ["--data" data "declassify" (str id)]))
          (is (= "Search for arts grants ." (:body (core/memo (inbound/current-state sys) id)))))))))

;; --- from the round 5 review

(defn- bits-in-spacing
  "\"PIN 0912\" as trailing whitespace, one byte a line: space 0, tab 1."
  []
  (str/join "\n" (for [c "PIN 0912"]
                   (str "Please look for arts grants in Ontario."
                        (apply str (map #(if (bit-test (int c) %) "\t" " ") (range 7 -1 -1)))))))

(deftest declassify-gives-the-role-the-text-the-operator-saw
  ;; Regression: trailing spaces and tabs carried a secret through.
  (with-data
    (fn [data sys]
      (doseq [id ["hub" "scout"]]
        (inbound/submit! sys {:type :role/add :actor "operator" :public (= "scout" id) :args {:id id :title id :instructions "x"}}))
      (inbound/submit! sys {:type :memo/send :actor "hub" :labels ["private"]
                            :args {:to "scout" :subject "Grants" :body (bits-in-spacing)}})
      (let [id (:id (first (core/unread-memos (inbound/current-state sys) "scout")))
            shown (atom "")]
        (binding [cli/*tty* (tty shown typed-code)]
          (is (= 0 (:exit (cli/execute ["--data" data "declassify" (str id)])))))
        (let [body (:body (core/memo (inbound/current-state sys) id))]
          (is (not (re-find #"[ \t]\n|[ \t]$|\t" body)) "no spacing left to carry anything")
          (is (= 8 (count (re-seq #"(?m)^Please look for arts grants in Ontario\.$" body))))
          (testing "what the role is given is what the operator was shown"
            (is (str/includes? @shown (str/replace body #"(?m)^" text/gutter)))))))))

(def gen-perturbed
  "Two texts that read the same, spaced differently: the same words in the
  same lines and paragraphs, with any blanks between and around them."
  (let [blank (gen/fmap #(apply str %) (gen/vector (gen/elements [" " "\t" " " " " "\f"]) 1 4))
        maybe-blank (gen/one-of [(gen/return "") blank])
        para (gen/vector (gen/vector (gen/elements ["grant" "arts" "Ontario" "May" "2026" "a.b"]) 1 5) 1 3)
        doc (gen/vector para 1 3)
        render (fn [doc]
                 (gen/fmap (fn [[gaps edges]]
                             (let [pick (fn [a] (let [x (first @a)] (swap! a rest) x))
                                   gaps (atom gaps) edges (atom edges)
                                   ;; Between words, some blank; around them
                                   ;; and on the lines between paragraphs, any.
                                   gap #(or (pick gaps) " ")
                                   edge #(or (pick edges) "")]
                               (str/join (str "\n" (edge) "\n" (edge) "\n")
                                         (for [p doc]
                                           (str/join "\n" (for [line p]
                                                            (str (edge) (str/join (gap) line) (edge))))))))
                           (gen/tuple (gen/vector blank 100) (gen/vector maybe-blank 100))))]
    (gen/bind doc (fn [d] (gen/tuple (render d) (render d))))))

(defspec text-spaced-differently-declassifies-to-the-same 300
  (prop/for-all [[a b] gen-perturbed]
    (let [pa (core/plain-spacing a)]
      (and (= pa (core/plain-spacing b))
           (= pa (core/plain-spacing pa))
           (not (re-find #"[\t  \f]|  | \n|\n |\n\n\n" pa))))))

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
        kinds (map :kind (sort-by :id (vals (:notifications (:state w)))))]
    (is (= ["public-role-cannot-run" "public-role-can-run" "public-role-cannot-run"]
           (filter #{"public-role-cannot-run" "public-role-can-run"} kinds)))))



;; --- from the round 6 review

(deftest a-declassified-subject-is-one-line
  (is (= "Hi there" (:subject (core/declassified {:subject "Hi\rthere" :body "b"}))))
  (is (= "Hi there" (:subject (core/declassified {:subject "Hi\n\n there " :body "b"}))))
  (testing "and what draws as an empty space is a space"
    (doseq [cp [0x2800 0x1D159 0x16FE4 0x115F 0x1160 0x3164 0xFFA0 0x200B 0xFE0F]]
      (is (= "a b" (core/plain-spacing (str "a" (String. (Character/toChars cp)) "b")))
          (format "U+%04X" cp)))))

(deftest declassify-shows-each-escape-once
  (with-data
    (fn [data sys]
      (doseq [id ["hub" "scout"]]
        (inbound/submit! sys {:type :role/add :actor "operator" :public (= "scout" id) :args {:id id :title id :instructions "x"}}))
      (inbound/submit! sys {:type :memo/send :actor "hub" :labels ["private"]
                            :args {:to "scout" :subject "Grants" :body "a\\b and zw:\u0007. end"}})
      (let [id (:id (first (core/unread-memos (inbound/current-state sys) "scout")))
            shown (atom "")]
        (binding [cli/*tty* (tty shown (constantly "NOPE"))]
          (is (thrown? clojure.lang.ExceptionInfo (cli/execute ["--data" data "declassify" (str id)]))))
        (is (str/includes? @shown "a\\\\b and zw:\\u{0007}. end"))
        (is (not (str/includes? @shown "\\\\u{")))))))

;; --- from the round 8 review: what the role gets is what was shown

(defn- declassify-view
  "relay declassify's view of a memo, as the terminal shows it, and what the
  role is then given."
  [m]
  (let [d (core/declassified m)]
    [(str/join "\n" (map text/visible (render/declassify java.time.ZoneOffset/UTC d)))
     (select-keys d [:subject :body])]))

(def gen-urlish-text
  (gen/fmap #(apply str %)
            (gen/vector (gen/elements ["http://" "HtTp://" "hxxp://" "HXXP://" "FOO://" "foo[:]//" "evil" "a.b"
                                       "a[.]b" "a．b" "a。b" " " "\t" "é" "é" "x" "/" "www." "WWW."])
                        1 8)))

(def gen-folded-variant
  "A text, and another that defanging, spacing or NFC may fold into the
  same view."
  (gen/bind gen-urlish-text
            (fn [s]
              (gen/fmap (fn [fs] [s (reduce (fn [t f] (f t)) s fs)])
                        (gen/vector (gen/elements [#(str/replace % "http" "HtTp") #(str/replace % "hxxp" "http")
                                                   #(str/replace % "." "．") #(str/replace % "[.]" ".")
                                                   #(str/replace % "é" "é") #(str/replace % " " "\t")
                                                   identity])
                                    0 3)))))

(defspec equal-declassify-views-give-the-role-equal-text 500
  (prop/for-all [[a b] gen-folded-variant]
    (let [memo #(hash-map :id 7 :from "hub" :subject "Grants" :body % :sent-at t0 :labels ["contaminated" "private"])
          [va ra] (declassify-view (memo a))
          [vb rb] (declassify-view (memo b))]
      (or (not= va vb) (= ra rb)))))

(deftest a-defanged-url-reaches-the-role-as-it-was-shown
  ;; Regression: http and hxxp, and HtTp and http, showed the same, while
  ;; the role was given each one as written.
  (doseq [[a b] [["B: http://evil.example/x" "B: hxxp://evil.example/x"]
                 ["B: HtTp://evil.example/x FOO://y" "B: http://evil.example/x foo[:]//y"]]]
    (let [memo #(hash-map :id 7 :from "hub" :subject "s" :body % :sent-at t0 :labels ["contaminated" "private"])
          [va ra] (declassify-view (memo a))
          [vb rb] (declassify-view (memo b))]
      (is (= (= va vb) (= ra rb)) "two texts look alike exactly when the role gets them alike")
      (is (str/includes? va (:body ra)) "and what it gets is the text shown")
      (is (str/includes? vb (:body rb)))))
  (testing "through relay declassify and the role's inbox"
    (with-data
      (fn [data sys]
        (doseq [id ["hub" "scout"]]
          (inbound/submit! sys {:type :role/add :actor "operator" :public true :args {:id id :title id :instructions "x"}}))
        (inbound/submit! sys {:type :memo/send :actor "hub" :labels ["contaminated" "private"]
                              :args {:to "scout" :subject "s" :body "B: HtTp://evil.example/x café"}})
        (let [id (:id (first (core/unread-memos (inbound/current-state sys) "scout")))
              shown (atom "")]
          (binding [cli/*tty* (tty shown typed-code)]
            (is (= 0 (:exit (cli/execute ["--data" data "declassify" (str id)])))))
          (inbound/submit! sys {:type :wake/start :actor "dispatcher" :args {:role "scout" :dispatched true}})
          (let [wake (:id (support/calling-wake (inbound/current-state sys) "scout"))
                got (:ok (tools/call! sys "scout" "inbox" {} [] "c" wake))
                line "B: hxxp://evil[.]example/x café"]
            (is (str/includes? @shown line))
            (is (str/includes? got line))
            (is (str/includes? @shown "Subject: \"s\" [contaminated]") "the marker is all the review adds")))))))
