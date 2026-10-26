(ns relay.house-test
  "The house: inside, nothing is gated; the walls are what keep the
  operator safe. What relay.edn may say about tools and grants, what a
  scout can see (I3), where trust comes from (I5), and the operator's own
  commands, which ask for no codes."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cli :as cli]
            [relay.config :as config]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.harness.agentloop :as agentloop]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.test-support :as support]
            [relay.tools :as tools])
  (:import (java.time Instant)))

(def t0 (Instant/parse "2026-10-14T12:00:00Z"))

(def settings {:timezone "UTC"
               :default-harness :agentloop
               :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}}
               :tools {"draft" {:class :edit :labels [] :description "Save a draft." :command ["true"]}
                       "web" {:class :egress :labels ["contaminated"] :description "Search." :command ["true"]
                              :protocol :envelope :queries ["query"]
                              :parameters {:type "object" :properties {:query {:type "string"}} :required ["query"]}}}
               :grants {"hub" {:may-add true :tools ["draft"]} "scout" {:public true :tools ["web"]}}})

(def cfg (config/resolve-config settings))

(defn- problem [settings]
  (try (config/resolve-config settings) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

;; --- relay.edn

(deftest the-example-settings-are-valid
  (let [c (config/resolve-config (edn/read-string (slurp "setup/relay.example.edn")))]
    (is (= ["private"] (get-in c [:tools "rem-read" :labels])) "reminders are the operator's own text")))

(deftest every-external-tool-declares-its-labels
  (let [with-tool #(assoc-in settings [:tools "draft"] (merge {:class :edit :description "d" :command ["true"]} %))]
    (is (nil? (problem (with-tool {:labels []}))))
    (is (nil? (problem (with-tool {:labels ["contaminated" "private"]}))))
    (is (str/includes? (problem (with-tool {})) "needs :labels"))
    (is (str/includes? (problem (with-tool {:labels nil})) "needs :labels"))
    (is (problem (with-tool {:labels [:contaminated]})) "labels are strings, never keywords")
    (is (problem (with-tool {:labels ["secret"]})) "an unknown label is refused")
    (is (problem (with-tool {:labels ["Contaminated"]})))
    (is (problem (with-tool {:labels "contaminated"})))
    (is (problem (with-tool {:labels [] :class :sends})))
    (testing "labels are kept sorted and distinct"
      (is (= ["contaminated" "private"]
             (get-in (config/resolve-config (with-tool {:labels ["private" "contaminated" "private"]}))
                     [:tools "draft" :labels]))))))

(deftest there-is-no-class-that-acts-as-the-operator
  (is (= #{:read :write :edit :egress} config/tool-classes))
  (is (str/includes? (problem (assoc-in settings [:tools "draft" :class] :act)) "needs a :class"))
  (testing "a connection file's tool may not be :act either"
    (is (not (contains? config/tool-classes :act)))))

(deftest settings-for-holds-are-refused-not-ignored
  (doseq [k [:always-hold :consent :preview]]
    (testing (str k)
      (is (str/includes? (str (problem (assoc-in settings [:tools "draft" k] true))) "no longer has")))))

(deftest grants-and-harness-settings-that-are-gone-are-refused
  (is (str/includes? (problem (assoc-in settings [:grants "hub" :quarantine] ["web"])) "does not know"))
  (is (str/includes? (problem (assoc-in settings [:grants "hub" :workbench] true)) "does not know"))
  (is (str/includes? (problem (assoc-in settings [:harnesses :agentloop :reader] {:model "m"})) "no longer has"))
  (is (str/includes? (problem (assoc-in settings [:harnesses :agentloop :workbench] {})) "no longer has")))

(deftest only-a-public-role-may-have-an-egress-tool
  (is (nil? (problem settings)))
  (let [p (problem (assoc-in settings [:grants "hub" :tools] ["draft" "web"]))]
    (is (str/includes? p "only a public role may have"))
    (is (not (re-find #"(?i)label|contaminat" p)) "the operator is told why without relay's words"))
  (testing "nor a role whose grant leaves :public out"
    (is (problem (assoc settings :grants {"clerk" {:tools ["web"]}})))))

(deftest a-script-harness-gets-no-tools
  (is (str/includes? (problem (assoc settings :default-harness :fake
                                     :harnesses {:fake {:kind :script :command ["true"]}}))
                     "no role may be granted :tools or :may-add"))
  (is (nil? (problem (-> settings
                         (assoc :default-harness :fake :harnesses {:fake {:kind :script :command ["true"]}})
                         (assoc :grants {}))))))

;; --- what a session is given

(defn- run [{:keys [state now] :as w} actor type args & [{:keys [labels public]}]]
  (try
    (let [wake (support/calling-wake state actor)
          [state' events] (core/run-command state (cond-> {:type type :actor actor :args args :labels labels
                                                           :public public}
                                                    wake (assoc :session-wake (:id wake)))
                                            {:now now :config cfg})]
      (-> w (assoc :state state' :last events :rejected nil) (update :events into events)))
    (catch clojure.lang.ExceptionInfo e
      (if (core/rejection e) (assoc w :rejected (core/rejection e) :message (ex-message e) :last []) (throw e)))))

(defn- world []
  (-> {:state core/empty-state :now t0 :events []}
      (run "operator" :role/add {:id "hub" :title "Hub" :instructions "Run relay."})
      (run "operator" :role/add {:id "scout" :title "Scout" :instructions "Look." :reports-to "hub"} {:public true})))

(deftest a-house-session-reads-everything-and-is-held-for-nothing
  (let [w (-> (world)
              (run "hub" :fact/add {:topic "home" :text "From a page: the hall seats 200."} {:labels ["contaminated"]})
              (run "hub" :note/write {:name "venues" :text "Outside text."} {:labels ["contaminated"]})
              (run "operator" :memo/send {:to "hub" :subject "Seats?" :body "How many?"}))
        ctx {:state (:state w) :config cfg :now (:now w)}
        read (fn [tool args] ((:read (get tools/builtin tool)) ctx "hub" args))]
    (testing "a fact from outside is shown with no placeholder and no switch to ask for it"
      (is (str/includes? (:content (read "facts" {})) "the hall seats 200"))
      (is (not (contains? (get-in tools/builtin ["facts" :parameters :properties]) :accept_contamination))))
    (testing "its labels come with it, as provenance"
      (is (core/contaminated? (:labels (read "notes" {})))))
    (testing "no tool takes accept_contamination"
      (is (not-any? #(contains? (get-in % [:parameters :properties]) :accept_contamination) (vals tools/builtin))))
    (testing "the policy names no class and holds nothing: only labels"
      (let [manifest (tools/manifest cfg "hub")
            p (tools/policy manifest ["contaminated" "private"])]
        (is (= #{:labels :tools} (set (keys p))))
        (is (every? #(= #{:labels} (set (keys %))) (vals (:tools p))))
        (is (nil? (:forbid_labels p)))))))

(deftest a-house-role-s-request-to-a-scout-goes-as-written
  (let [w (run (world) "hub" :memo/send {:to "scout" :subject "Look up the hall" :body "Please."}
               {:labels ["private"]})
        m (first (core/unread-memos (:state w) "scout"))]
    (is (nil? (:rejected w)))
    (is (:public m) "decision 6: no sign-off, and the scout is shown it")
    (is (= [] (:labels m))))
  (testing "the operator's own, written public, goes too"
    (is (nil? (:rejected (run (world) "operator" :memo/send {:to "scout" :subject "Hall" :body "Look it up."}
                              {:public true}))))))

(deftest an-egress-tool-is-one-relay-runs
  (let [mcp (fn [protocol]
              (-> settings
                  (assoc :mcp-servers (if (= :mcp-http protocol)
                                        {"svc" {:url "https://mcp.example.com/mcp"}}
                                        {"svc" {:command ["svc"]}}))
                  (assoc-in [:tools "web"] {:protocol protocol :server "svc" :tool "fetch" :class :egress
                                            :labels ["contaminated"]})))]
    (doseq [protocol [:mcp-stdio :mcp-http]]
      (let [p (problem (mcp protocol))]
        (is (str/includes? (str p) "an :egress tool of an MCP server") (pr-str protocol))))
    (testing "a public role may still have a remote tool that only reads"
      (is (nil? (problem (assoc-in (mcp :mcp-http) [:tools "web" :class] :read)))))))

(deftest an-egress-tool-takes-only-what-relay-can-check
  (let [web (fn [tool] (assoc-in settings [:tools "web"]
                                 (merge {:class :egress :labels ["contaminated"] :description "Fetch." :command ["web"]}
                                        tool)))
        params (fn [props] {:type "object" :properties props})]
    (testing "a url, numbers, yes-or-no values and the listed queries"
      (is (nil? (problem (web {:protocol :envelope :parameters (params {:url {:type "string"}})}))))
      (is (nil? (problem (web {:protocol :envelope :queries ["query"]
                               :parameters (params {:query {:type "string"} :count {:type "integer"}
                                                    :fresh {:type "boolean"} :weight {:type "number"}})})))))
    (testing "a command given the model's arguments is refused, since relay cannot read them"
      (doseq [tool [{} {:protocol :plain}]]
        (is (str/includes? (str (problem (web tool))) "must be an :envelope tool") (pr-str tool))))
    (testing "an envelope tool whose properties relay does not know"
      (is (str/includes? (str (problem (web {:protocol :envelope}))) "must be an :envelope tool"))
      (is (str/includes? (str (problem (web {:protocol :envelope :parameters {:type "object"}})))
                         "must be an :envelope tool")))
    (testing "free text it was not told goes to a trusted service"
      (doseq [props [{:host {:type "string"}}
                     {:url {:type "string"} :query {:type "string"}}
                     {:url {:type "array" :items {:type "string"}}}
                     {:opts {:type "object"}}
                     {:query {}}]]
        (is (str/includes? (str (problem (web {:protocol :envelope :parameters (params props)})))
                           "nor listed in :queries")
            (pr-str props))))
    (testing ":queries names string parameters other than url"
      (doseq [q [["url"] ["count"] ["nope"] "query" [:query]]]
        (is (str/includes? (str (problem (web {:protocol :envelope :queries q
                                               :parameters (params {:url {:type "string"} :query {:type "string"}
                                                                    :count {:type "integer"}})})))
                           "has :queries")
            (pr-str q))))
    (testing "and only an :egress tool has :queries"
      (is (str/includes? (str (problem (assoc-in settings [:tools "draft" :queries] ["x"])))
                         "which only an :egress tool has")))))

(def digest-a (apply str (repeat 64 "a")))
(def digest-b (apply str (repeat 64 "b")))

(def mcp-cfg
  (config/resolve-config
   (assoc settings :mcp-servers {"srv" {:command ["srv"]}}
          :tools {"t1" {:protocol :mcp-stdio :server "srv" :tool "one" :class :read :labels []}}
          :grants {"hub" {:tools ["t1"]}})))

(defn- mcp-run [{:keys [state now] :as w} actor type args]
  (let [[state' events] (core/run-command state {:type type :actor actor :args args} {:now now :config mcp-cfg})]
    (-> w (assoc :state state' :last events) (update :events into events))))

(deftest a-tool-that-changed-is-taken-and-the-operator-is-told
  (let [w (-> {:state core/empty-state :now t0 :events []}
              (mcp-run "operator" :role/add {:id "hub" :title "Hub" :instructions "x"})
              (mcp-run "operator" :memo/send {:to "hub" :subject "s" :body "b"})
              (mcp-run "dispatcher" :wake/start {:role "hub" :dispatched true}))
        wake (:id (core/working-wake (:state w) "hub"))
        w (mcp-run w "dispatcher" :wake/end {:wake wake :outcome "ran" :session "s1" :said "ok" :pins {"t1" digest-a}})
        _ (is (= digest-a (core/tool-pin (:state w) "t1")))
        w (mcp-run w "dispatcher" :wake/start {:role "hub"})
        wake (:id (core/working-wake (:state w) "hub"))
        w (mcp-run w "dispatcher" :wake/end {:wake wake :outcome "needs-attention" :session "s1" :said "changed"
                                             :tools-error {:tool "t1" :pinned digest-a :got digest-b
                                                           :message "The definition changed."}})
        ns (filter #(= "message/created" (:type %)) (:last w))]
    (is (= digest-b (core/tool-pin (:state w) "t1")) "the new definition is pinned")
    (is (nil? (:interrupted-wake (core/role (:state w) "hub"))) "the next wake starts a fresh session")
    (is (not (core/waiting-on-tools? (:state w) "hub" (:now w))) "and nothing waits for the operator")
    (is (= ["tool-changed"] (map #(get-in % [:data :origin]) ns)))
    (is (not (re-find #"(?i)relay fresh|label|hold|role|tool" (support/message-text (:data (first ns))))))
    (testing "a second session stopped by the same change takes it quietly"
      (let [w (mcp-run w "dispatcher" :wake/start {:role "hub"})
            wake (:id (core/working-wake (:state w) "hub"))
            w (mcp-run w "dispatcher" :wake/end {:wake wake :outcome "needs-attention" :session "s2" :said "changed"
                                                 :tools-error {:tool "t1" :pinned digest-a :got digest-b :message "m"}})]
        (is (empty? (filter #(= "message/created" (:type %)) (:last w))))))))

;; --- I3: scouts see nothing private

(def gen-write
  "Something written into relay: by the operator (public or not), by the
  house role or by the scout, from inside or from outside. Docs, notes
  and routines take their names from a small pool, so one path often
  holds both public and private versions."
  (gen/tuple (gen/frequency [[4 (gen/return :doc)] [6 (gen/elements [:memo :fact :note :journal :routine])]])
             (gen/elements ["operator" "hub" "scout"])
             gen/boolean
             (gen/elements [[] ["contaminated"]])
             (gen/elements ["0" "1"])))

(defn- write-step
  "Writes something holding a marker unique to it. Returns [world marker
  public?], public? being whether the scout may see it."
  [w [kind actor public labels slot] n]
  (let [marker (str "M" n "X")
        owner (if (= "operator" actor) (if public "scout" "hub") actor)
        public? (or (= "scout" actor) (and (= "operator" actor) public))
        opts {:labels (when-not (= "operator" actor) labels) :public (and (= "operator" actor) public)}
        w' (case kind
             :memo (run w actor :memo/send {:to (if (= "scout" actor) "hub" owner) :subject marker :body marker} opts)
             :fact (run w actor :fact/add {:topic "t" :text marker} opts)
             :note (run w actor :note/write {:owner owner :name (str "n" slot) :text marker} opts)
             :doc (run w actor :doc/put {:path (str "d" slot ".md") :content marker} opts)
             :journal (run w actor :journal/write {:owner owner :text marker} opts)
             :routine (run w actor :routine/write {:owner owner :name (str "r" slot) :schedule "every 1h"
                                                    :instructions marker} opts))]
    [w' marker (and public? (nil? (:rejected w')))]))

(defn- scout-reads
  "Everything the scout's own read tools show it, as text, with labels."
  [state]
  (let [ctx {:state state :config cfg :now t0}
        offered (tools/offered cfg "scout")
        calls (concat [["inbox" {}] ["roles" {}] ["routines" {}] ["facts" {}] ["facts" {:all true}] ["notes" {}]
                       ["journal" {}] ["docs" {}]]
                      (for [id (keys (:facts state))] ["fact_history" {:fact id}])
                      (for [p (keys (:docs state))] ["doc_get" {:path p}])
                      (for [p (keys (:docs state))] ["doc_history" {:path p}]))]
    (for [[tool args] calls :when (get offered tool)]
      (try (let [r ((:read (get offered tool)) ctx "scout" args)]
             (if (map? r) r {:content r :labels []}))
           (catch clojure.lang.ExceptionInfo e {:content (ex-message e) :labels (core/rejection-labels e)})))))

(deftest a-scout-is-not-shown-the-private-versions-of-a-public-doc
  ;; The operator writes a doc public; the hub rewrites it from the
  ;; house; the operator writes it public again. The scout may read the
  ;; doc, but the hub's version is not shown and not counted.
  (let [w (-> (world)
              (run "operator" :doc/put {:path "hall.md" :content "Seats 200."} {:public true})
              (run "hub" :doc/put {:path "hall.md" :content "Kira's booking ref 4411."} {:labels []})
              (run "operator" :doc/put {:path "hall.md" :content "Seats 250."} {:public true}))
        hub-seq (:seq (second (get-in (:state w) [:docs "hall.md" :versions])))
        history (:content ((:read (get (tools/offered cfg "scout") "doc_history"))
                           {:state (:state w) :config cfg :now t0} "scout" {:path "hall.md"}))]
    (is (= 3 (count (get-in (:state w) [:docs "hall.md" :versions]))))
    (is (str/includes? (str history) "version "))
    (is (not (str/includes? (str history) (str "version " hub-seq ","))))
    (is (= 2 (count (re-seq #"version \d+," (str history)))))))

(defspec scouts-see-only-public-items 150
  (prop/for-all [writes (gen/vector gen-write 1 25)]
    (let [[w markers] (reduce (fn [[w ms] [i step]]
                                (let [[w m public?] (write-step w step i)] [w (assoc ms m public?)]))
                              [(world) {}] (map-indexed vector writes))
          private (set (keep (fn [[m public?]] (when-not public? m)) markers))
          reads (scout-reads (:state w))
          ;; doc_history never prints content, so the versions it must not
          ;; list are checked by their seqs: not shown and not counted.
          unseen-versions (for [[path d] (:docs (:state w)), v (:versions d)
                                :when (not (core/doc-shown-to-public? (:state w) path v))]
                            (str "version " (:seq v) ","))
          w (run w "operator" :wake/start {:role "scout" :note "Look things up." :dispatched false} {:public true})
          wake (core/working-wake (:state w) "scout")
          request (when wake
                    (dispatch/wake-request {:data-dir "/tmp/relay-house" :config cfg} (:state w) wake
                                           {:shared "Shared." :operator-additions "SECRET additions" :now t0
                                            :relay-bin "relay" :data-path "/tmp/relay-house"}))
          prompts (str (:system-prompt request) (:prompt request))]
      (and (every? (fn [{:keys [content labels]}]
                     (and (not (core/private? labels))
                          (not-any? #(str/includes? (str content) %) private)
                          (not-any? #(str/includes? (str content) %) unseen-versions)))
                   reads)
           (some? request)
           (not-any? #(str/includes? prompts %) private)
           (not (str/includes? prompts "SECRET"))
           (= ["private"] (get-in request [:policy :forbid_labels]))
           (not (core/private? (get-in request [:policy :labels])))))))

(def gen-roles
  (gen/vector (gen/tuple (gen/elements ["alpha" "beta" "gamma" "delta"]) gen/boolean) 1 4))

(defspec forbid-private-reaches-agentloop-for-every-public-role 100
  (prop/for-all [roles gen-roles]
    (let [roles (into {} roles)
          c (config/resolve-config (assoc settings :tools {} :grants (into {} (for [[r p] roles] [r {:public p}]))))
          state (reduce (fn [s [r p]]
                          (first (core/run-command s {:type :role/add :actor "operator" :public p
                                                      :args {:id r :title r :instructions "Work."}}
                                                   {:now t0 :config c})))
                        core/empty-state roles)]
      (every? (fn [[r p]]
                (let [request (dispatch/wake-request {:data-dir "/tmp/relay-house" :config c} state
                                                     {:id 1 :role r :memos [] :harness "agentloop"}
                                                     {:shared "S" :operator-additions nil :now t0
                                                      :relay-bin "relay" :data-path "/tmp/relay-house"})
                      sent (agentloop/request-json request "s1" "/tmp/relay-house/work")]
                  (if p
                    (= ["private"] (get-in sent [:policy :forbid_labels]))
                    (nil? (get-in sent [:policy :forbid_labels])))))
              roles))))

;; --- I5: trust follows the hand that brought it in

(deftest the-operator-s-entry-points-are-trusted
  (doseq [e [:command-line :conversation :operator-mail]]
    (is (= [] (core/entry-labels e)) (str e)))
  (doseq [e [:ingestor :scout]]
    (is (= ["contaminated"] (core/entry-labels e)) (str e)))
  (is (= ["contaminated" "private"] (core/entry-labels {:tool ["private" "contaminated" "private"]})))
  (is (thrown? clojure.lang.ExceptionInfo (core/entry-labels :somewhere-new))))

(def gen-entry
  (gen/one-of [(gen/elements (keys core/entry-points))
               (gen/fmap (fn [ls] {:tool ls}) (gen/vector (gen/elements ["private" "contaminated"]) 0 3))]))

(defspec provenance-is-a-function-of-the-entry-point 200
  (prop/for-all [entry gen-entry]
    (let [ls (core/entry-labels entry)]
      (and (= ls (core/entry-labels entry))
           (= ls (vec (sort (distinct ls))))
           (cond
             (#{:command-line :conversation :operator-mail} entry) (empty? ls)
             (#{:ingestor :scout} entry) (core/contaminated? ls)
             :else (= (set (:tool entry)) (set ls)))))))

(def gen-role-write
  (gen/tuple (gen/elements [:memo :fact :note :doc :journal])
             (gen/elements [[] ["contaminated"] ["private"] ["contaminated" "private"]])))

(defspec what-a-session-writes-carries-what-it-read 200
  (prop/for-all [[kind labels] gen-role-write
                 n gen/nat]
    (let [w (run (world) "hub" (case kind :memo :memo/send :fact :fact/add :note :note/write :doc :doc/put
                                 :journal :journal/write)
                 (case kind
                   :memo {:to "operator" :subject (str "s" n) :body "b"}
                   :fact {:text (str "f" n)}
                   :note {:name (str "n" n) :text "t"}
                   :doc {:path (str "d" n ".md") :content "c"}
                   :journal {:text "j"})
                 {:labels labels})]
      (and (seq (:last w))
           (every? #(every? (set (:labels %)) labels) (:last w))))))

;; --- the operator at the command line

(defn- with-data [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-house-test"}))
        data (str dir "/data")]
    (log/init! data)
    (spit (str data "/relay.edn") (pr-str settings))
    (try (f data) (finally (fs/delete-tree dir)))))

(defn- relay [data & args] (cli/execute (concat ["--data" data] args)))

(deftest what-the-operator-writes-is-theirs
  (with-data
    (fn [data]
      (binding [cli/*stdin* "Pasted from a mail I trust."]
        (is (= 0 (:exit (relay data "put" "notes/pasted.md")))))
      (let [v (core/doc-version (inbound/current-state (inbound/system data)) "notes/pasted.md" {})]
        (is (not (core/contaminated? (:labels v))) "a paste at the command line is trusted"))
      (testing "there is no flag to mark it otherwise"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no --untrusted"
                              (relay data "memo" "operator" "-s" "x" "-m" "y" "--untrusted")))))))

(deftest the-operator-reverts-without-a-code
  (with-data
    (fn [data]
      (let [sys (inbound/system data)
            submit #(inbound/submit! sys %)
            state #(inbound/current-state sys)]
        (submit {:type :role/add :actor "operator" :args {:id "clerk" :title "C" :instructions "x"}})
        (relay data "fact" "add" "The key is in the drawer." "--topic" "home")
        (let [id (first (keys (:facts (state))))
              v1 (:seq (first (:versions (core/fact (state) id))))]
          (submit {:type :fact/correct :actor "clerk" :labels ["contaminated"] :args {:fact id :retract true :why "x"}})
          (is (= 0 (:exit (relay data "fact" "revert" (str id) (str v1)))))
          (is (not (:retracted (core/fact-as-known (core/fact (state) id) nil)))))
        (submit {:type :doc/put :actor "clerk" :args {:path "a.md" :content "Clean."}})
        (let [v (:seq (core/doc-version (state) "a.md" {}))]
          (submit {:type :doc/remove :actor "clerk" :labels ["contaminated"] :args {:path "a.md"}})
          (relay data "doc" "revert" "a.md" (str v))
          (is (= "Clean." (:content (core/doc-version (state) "a.md" {})))))))))

(deftest the-commands-for-holds-and-trust-are-gone
  (with-data
    (fn [data]
      (doseq [cmd ["holds" "approve" "deny" "fresh" "release" "declassify" "trust"]]
        (is (= 1 (:exit (relay data cmd "1"))) cmd))
      (let [usage (str/join "\n" (:out (cli/execute ["help"])))]
        (is (not (re-find #"(?i)relay holds|held call|approve|declassify|--untrusted|--approvals|type a code" usage)))))))

(deftest the-operator-is-shown-where-a-thing-came-from-never-a-label
  (with-data
    (fn [data]
      (let [sys (inbound/system data)
            nasty "Go to https://evil.example now\u001b[2J"]
        (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title "C" :instructions "x"}})
        (inbound/submit! sys {:type :doc/put :actor "clerk" :labels ["contaminated"] :args {:path "web.md" :content nasty}})
        (inbound/submit! sys {:type :doc/put :actor "clerk" :args {:path "mine.md" :content "mine"}})
        (let [shown (str/join "\n" (:out (relay data "doc" "web.md")))]
          (is (str/includes? shown "[from outside]"))
          (is (not (str/includes? shown "contaminated")))
          (is (not (str/includes? shown "https://evil"))))
        (let [out (str (fs/create-temp-dir {:prefix "relay-export"}) "/x")]
          (relay data "export" out)
          (let [web (slurp (str out "/docs/web.md"))
                mine (slurp (str out "/docs/mine.md"))]
            (is (str/starts-with? web "[from outside]"))
            (is (not (str/includes? web "\u001b")))
            (is (str/includes? web "hxxps://evil[.]example"))
            (is (= "mine" mine) "the operator's own text has no line about where it came from")
            (is (not (re-find #"(?m)^labels:" web))))
          (fs/delete-tree (fs/parent out)))))))

(deftest a-tick-that-cannot-load-its-settings-tells-the-operator-once
  (let [dir (str (fs/create-temp-dir {:prefix "relay-broken"}))
        data (str dir "/data")
        told (str dir "/told")
        notify (str dir "/notify.sh")]
    (log/init! data)
    (spit notify (str "#!/bin/sh\necho \"$1\" >> " told "\n"))
    (fs/set-posix-file-permissions notify "rwxr-xr-x")
    ;; Broken everywhere but :messages.
    (spit (str data "/relay.edn") (pr-str {:timezone "Nowhere/At_All" :messages {:channels {"phone" {:kind :push :via :command :command [notify]}} :routes [{:channels ["phone"]}]}}))
    (dotimes [_ 2]
      (is (thrown? clojure.lang.ExceptionInfo (relay data "tick"))))
    (is (= ["relay has stopped: relay.edn cannot be used"] (str/split-lines (slurp told))) "told once, not every tick")
    (fs/delete-tree dir)))

(deftest an-outcome-relay-cannot-read-is-a-crash-not-a-pause
  (let [out (str "{\"type\":\"entry\"}\n{\"type\":\"outcome\",\"reason\":\"completed\",\"text\":\"" (apply str (repeat 3 "x")) "\n")
        report (agentloop/classify {:outcome (agentloop/parse-outcome out) :exit 0 :session "s" :now t0})]
    (is (= ["ran" true] ((juxt :outcome :crashed) report)))
    (is (nil? (:labels report)) "no labels, so relay assumes every label the session could hold")))
