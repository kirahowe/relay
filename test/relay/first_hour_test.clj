(ns relay.first-hour-test
  "What a new self-hoster meets in the first hour: failures told once and
  plainly, with what to do; alerts that end when the trouble does; a
  routine and the daily note run by hand; the first GitHub poll; the
  shell removed with its image."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cli :as cli]
            [relay.config :as config]
            [relay.core :as core]
            [relay.inbound :as inbound]
            [relay.ingest-run :as ingest-run]
            [relay.log :as log]
            [relay.ready :as ready]
            [relay.render :as render]
            [relay.setup]
            [relay.shell :as shell]
            [relay.tools :as tools])
  (:import (java.time Instant ZoneId)))

(def t0 (Instant/parse "2026-10-26T12:00:00Z"))

(def cfg (config/resolve-config {:timezone "UTC" :default-harness :agentloop
                                 :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"]}}
                                 :max-attempts 2
                                 :ingest {"repos" {:kind :github :repos ["kira/tidy"] :to "steward"}}
                                 :grants {"steward" {:may-add true} "scout" {:public true}}}))

(defn- run [{:keys [state now] :as w} actor type args & [public]]
  (let [wake (first (filter #(and (= actor (:role %)) (= :running (:status %))) (vals (:wakes state))))
        [state' events] (core/run-command state (cond-> {:type type :actor actor :args args :public public :labels []}
                                                  wake (assoc :session-wake (:id wake)))
                                          {:now now :config cfg})]
    (-> w (assoc :state state') (update :events into events))))

(defn- seed []
  (-> {:state core/empty-state :now t0 :events []}
      (run "operator" :role/add {:id "steward" :title "Steward" :instructions "Run things."} true)
      (run "operator" :role/add {:id "scout" :title "Scout" :instructions "Look things up."} true)))

(defn- text-of [m] (str/join "\n" (map :text (mapcat :items (:sections m)))))
(defn- messages [w] (sort-by :id (vals (:messages (:state w)))))
(defn- last-memo [w] (last (sort-by :id (vals (:memos (:state w))))))

;; --- dead letters

(defspec a-dead-letter-subject-unwraps-to-the-request 200
  (prop/for-all [subject (gen/such-that #(not (str/includes? % "Dead letter")) gen/string-ascii)
                 roles (gen/vector (gen/elements ["scout" "steward" "clerk"]) 0 4)]
    (let [wrapped (reduce (fn [s r] (str "Dead letter: " r " could not finish \"" s "\"")) subject roles)]
      (and (= subject (core/original-subject wrapped))
           (= (core/original-subject wrapped) (core/original-subject (core/original-subject wrapped)))))))

(deftest the-operators-own-request-comes-back-to-them-once
  (let [w (-> (seed) (run "operator" :memo/send {:to "scout" :subject "Arts grants" :body "Find some."} true))
        id (:id (last-memo w))
        w (-> w (run "dispatcher" :wake/start {:role "scout"})
              (run "scout" :memo/block {:memo id :explanation "Web search is not set up yet."}))
        [m & more] (messages w)]
    (is (nil? more) "one message")
    (is (= "Could not finish \"Arts grants\"" (:subject m)))
    (is (str/includes? (text-of m) "relay retry scout"))
    (testing "the steward still gets the dead letter, knowing the operator was told"
      (let [letter (last-memo w)]
        (is (= "steward" (:to letter)))
        (is (str/includes? (:body letter) "do not tell them again"))
        (testing "and when the steward blocks that too, the operator hears nothing more"
          (let [w (-> w (run "dispatcher" :wake/start {:role "steward"})
                      (run "steward" :memo/block {:memo (:id letter) :explanation "Nothing I can do."}))]
            (is (= 1 (count (messages w))))))))))

(deftest a-role-request-the-steward-cannot-fix-reaches-the-operator-by-its-own-subject
  (let [w (-> (seed) (run "dispatcher" :wake/start {:role "steward"})
              (run "steward" :memo/send {:to "scout" :subject "Lantern Fund" :body "Find it."}))
        id (:id (last-memo w))
        w (-> w (run "dispatcher" :wake/start {:role "scout"})
              (run "scout" :memo/block {:memo id :explanation "No search."}))
        letter (last-memo w)
        _ (is (= "steward" (:to letter)))
        _ (is (empty? (messages w)) "the steward is asked first")
        w (run w "steward" :memo/block {:memo (:id letter) :explanation "Web search needs a key."})
        [m & more] (messages w)]
    (is (nil? more))
    (is (= "Could not finish \"Lantern Fund\"" (:subject m)) "not a dead letter of a dead letter")
    (is (not (str/includes? (text-of m) "Dead letter")))))

;; --- a tool that is not set up

(deftest a-search-key-failure-is-told-once-by-relay-and-ends-when-it-works
  (is (= "search-key" (tools/setup-problem "api_key_command failed: exit status 44")))
  (is (nil? (tools/setup-problem "The tool web failed.")))
  (let [w (-> (seed)
              (run "dispatcher" :tool/broken {:tool "web" :problem "search-key"})
              (run "dispatcher" :tool/broken {:tool "web" :problem "search-key"}))
        [m & more] (messages w)]
    (is (nil? more) "told once")
    (is (= "Web search isn't set up yet" (:subject m)))
    (is (str/includes? (text-of m) "security add-generic-password -a \"$USER\" -s relay-brave -w"))
    (is (empty? (:labels m)) "a local setting, not something from outside")
    (is (some #(str/includes? % "relay-brave") (render/status (:state w) (ZoneId/of "UTC") t0 cfg)))
    (is (= [(str "message:" (:id m))] (map :id (ready/ready (:state w)))))
    (testing "once it works, the alert is resolved and leaves what is ready"
      (let [w (run w "dispatcher" :tool/working {:tool "web"})]
        (is (:resolved (core/message (:state w) (:id m))))
        (is (empty? (ready/ready (:state w))))
        (is (not-any? #(str/includes? % "relay-brave") (render/status (:state w) (ZoneId/of "UTC") t0 cfg)))))))

(deftest the-operators-own-request-that-needed-the-web-is-named-in-the-alert
  (let [w (-> (seed) (run "operator" :memo/send {:to "scout" :subject "Grants for community radio" :body "Find some."} true))
        w (run w "dispatcher" :wake/start {:role "scout"})
        wake (:id (last (sort-by :id (vals (:wakes (:state w))))))
        w (-> w (run "dispatcher" :tool/broken {:tool "web" :problem "search-key" :wake wake})
              (run "dispatcher" :tool/broken {:tool "web" :problem "search-key" :wake wake}))
        [m & more] (messages w)]
    (is (nil? more) "one message for the request, however often the session calls")
    (is (str/includes? (text-of m) "\"Grants for community radio\" couldn't be done"))
    (is (str/includes? (text-of m) "ask again once the key is in"))
    (is (not (str/includes? (text-of m) "carries on by itself")) "it promises nothing that won't happen")
    (testing "a later request of theirs while it is still not set up is named too, once"
      (let [w (-> w (run "scout" :memo/close {:memo (:id (last-memo w)) :outcome "Web search is not set up yet."})
                  (run "dispatcher" :wake/end {:wake wake :outcome "ran" :exit 0 :said "ok"})
                  (run "operator" :memo/send {:to "scout" :subject "Arts grants" :body "Find some."} true)
                  (run "dispatcher" :wake/start {:role "scout"}))
            wake2 (:id (last (sort-by :id (vals (:wakes (:state w))))))
            w (-> w (run "dispatcher" :tool/broken {:tool "web" :problem "search-key" :wake wake2})
                  (run "dispatcher" :tool/broken {:tool "web" :problem "search-key" :wake wake2}))
            [_ m2 & more] (messages w)]
        (is (nil? more))
        (is (str/includes? (text-of m2) "\"Arts grants\" couldn't be done"))
        (is (str/includes? (text-of m2) "relay-brave") "with what to do")))
    (testing "a request from a role is the role's to answer, and not named"
      (let [w (-> (seed) (run "dispatcher" :wake/start {:role "steward"})
                  (run "steward" :memo/send {:to "scout" :subject "Lantern Fund" :body "Find it."})
                  (run "dispatcher" :wake/start {:role "scout"}))
            wake (:id (last (sort-by :id (filter #(= "scout" (:role %)) (vals (:wakes (:state w)))))))
            w (run w "dispatcher" :tool/broken {:tool "web" :problem "search-key" :wake wake})]
        (is (not (str/includes? (text-of (first (messages w))) "Lantern Fund")))))))

;; --- the harness

(deftest a-local-harness-failure-is-not-blamed-on-the-model-service
  (let [[said fix] (core/harness-attention-items "agentloop did not start. version mismatch" true)]
    (is (str/includes? said "could not start its model runner on this machine"))
    (is (not (str/includes? said "model service")))
    (is (str/includes? fix "will not start until this is fixed")))
  (is (str/includes? (first (core/harness-attention-items "invalid x-api-key" false)) "The model service said")))

(deftest a-harness-alert-is-resolved-when-it-recovers
  (let [w (-> (seed) (run "operator" :memo/send {:to "steward" :subject "s" :body "b"}))
        w (run w "dispatcher" :wake/start {:role "steward"})
        wake (:id (first (vals (:wakes (:state w)))))
        w (run w "dispatcher" :wake/end {:wake wake :outcome "needs-attention" :exit 1 :local true
                                         :said "agentloop did not start. old binary"})
        [m] (messages w)]
    (is (= "harness-needs-attention" (:origin m)))
    (is (str/includes? (text-of m) "on this machine"))
    (is (some #(str/includes? % "agentloop did not start") (render/status (:state w) (ZoneId/of "UTC") t0 cfg))
        "relay status says why")
    (is (= 1 (count (ready/ready (:state w)))))
    (let [w (-> w (update :now #(.plusSeconds ^Instant % 3600))
                (run "dispatcher" :wake/start {:role "steward"}))
          wake (:id (last (sort-by :id (vals (:wakes (:state w))))))
          w (run w "dispatcher" :wake/end {:wake wake :outcome "ran" :exit 0 :said "ok"})]
      (is (:resolved (core/message (:state w) (:id m))))
      (is (empty? (filter #(= (:message %) (:id m)) (ready/ready (:state w))))))))

;; --- running things now

(deftest a-routine-runs-by-hand-as-the-tick-would-run-it
  (let [w (-> (seed) (run "operator" :routine/write {:owner "steward" :name "daily-note" :schedule "daily 07:10"
                                                    :instructions "Write the note."}))
        w (run w "operator" :routine/run {:name "daily-note"})
        m (last-memo w)]
    (is (= {:to "steward" :subject "daily-note" :body "Write the note." :routine "daily-note"}
           (select-keys m [:to :subject :body :routine])))
    (is (= (:id m) (:last-memo (core/routine (:state w) "steward" "daily-note"))))
    (testing "not twice while the run is open"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"still open"
                            (run w "operator" :routine/run {:name "daily-note"}))))
    (testing "only the operator, and only a routine that exists"
      (is (thrown? clojure.lang.ExceptionInfo (run w "steward" :routine/run {:name "daily-note"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no routine called nope"
                            (run w "operator" :routine/run {:name "nope"}))))))

(deftest two-routines-of-one-name-need-the-role
  (let [w (-> (seed)
              (run "operator" :routine/write {:owner "steward" :name "check" :schedule "daily 07:10" :instructions "a"})
              (run "operator" :routine/write {:owner "scout" :name "check" :schedule "daily 07:10" :instructions "b"} true))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"relay routine run <role> check"
                          (run w "operator" :routine/run {:name "check"})))
    (is (= "scout" (:to (last-memo (run w "operator" :routine/run {:owner "scout" :name "check"})))))))

(def ^:dynamic *dir* nil)

(use-fixtures :each
  (fn [t]
    (let [dir (str (fs/create-temp-dir {:prefix "relay-first-hour"}))]
      (try (binding [*dir* dir] (t)) (finally (fs/delete-tree dir))))))

(defn- data-dir! []
  (let [data (str *dir* "/data")]
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :x :harnesses {:x {:kind :agentloop :api-key-command ["true"]}}
                   :messages {:channels {"phone" {:kind :push :via :file :path (str *dir* "/pushed.log")}}
                              :routes [{:channels ["phone"]}]}
                   :daily-note {:at "07:30" :role "steward"}}))
    data))

(defn- relay! [data & args]
  (binding [cli/*clock* (constantly t0)]
    (cli/execute (into ["--data" data] args))))

(deftest the-daily-note-can-be-seen-and-sent-now
  (let [data (data-dir!)
        sys (inbound/system data {:clock (constantly t0)})]
    (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "steward" :title "Steward" :instructions "r"}})
    (testing "with nothing written, it says how to have it written"
      (is (str/includes? (str/join "\n" (:out (relay! data "note"))) "relay routine run steward daily-note")))
    (inbound/submit! sys {:type :digest/write-part :actor "steward" :labels []
                          :args {:part "ahead" :items [{:text "Interview, Thu 10:00" :offer "Prep notes?"}]}})
    (let [{:keys [exit out]} (relay! data "note")]
      (is (= 0 exit))
      (is (str/includes? (str/join "\n" out) "Interview, Thu 10:00"))
      (is (not (fs/exists? (str *dir* "/pushed.log"))) "a preview sends nothing"))
    (let [{:keys [out]} (relay! data "note" "now")]
      (is (str/includes? (first out) "Sent today's daily note"))
      (is (= 1 (count (str/split-lines (slurp (str *dir* "/pushed.log")))))))
    (is (str/includes? (first (:out (relay! data "note" "now"))) "made already"))))

(deftest an-empty-note-does-not-use-up-the-day
  (let [data (data-dir!)
        sys (inbound/system data {:clock (constantly t0)})
        pushed #(let [f (str *dir* "/pushed.log")] (if (fs/exists? f) (count (str/split-lines (slurp f))) 0))]
    (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "steward" :title "Steward" :instructions "r"}})
    (testing "note now before the steward wrote anything sends nothing and records nothing"
      (let [[line & more] (:out (relay! data "note" "now"))]
        (is (str/includes? line "nothing to say yet"))
        (is (str/includes? (str/join "\n" more) "relay routine run steward daily-note"))
        (is (nil? (core/daily-date (inbound/current-state sys))))))
    (testing "the tick's own empty note does not stop note now either"
      (inbound/submit! sys {:type :digest/compose :actor core/dispatcher :args {:date "2026-10-26"}})
      (is (some? (core/daily-date (inbound/current-state sys)))))
    (inbound/submit! sys {:type :digest/write-part :actor "steward" :labels []
                          :args {:part "ahead" :items [{:text "Interview, Thu 10:00" :offer "Prep notes?"}]}})
    (is (str/includes? (first (:out (relay! data "note" "now"))) "Sent today's daily note"))
    (is (= 1 (pushed)))
    (is (str/includes? (first (:out (relay! data "note" "now"))) "made already") "a sent note is the day's")
    (is (= 1 (pushed)))))

;; --- the first GitHub poll

(deftest the-first-poll-of-a-repository-says-it-has-started-watching
  (let [src {:kind :github :repos ["kira/tidy" "kira/shelf"] :to "steward"}
        recorded [{:type "ingest/polled"}]]
    (is (= "repos: now watching kira/tidy and kira/shelf; new issues and pull requests show up from the next check."
           (ingest-run/poll-line "repos" src recorded ["kira/tidy" "kira/shelf"])))
    (is (= "repos: nothing new." (ingest-run/poll-line "repos" src recorded [])))
    (is (= "repos: 2 new memos for steward." (ingest-run/poll-line "repos" src [{:type "memo/sent"} {:type "memo/sent"}] [])))))

;; --- the shell

(deftest removing-the-shell-removes-relays-own-image
  (let [names (shell/names {} "/data")]
    (is (= ["container" "image" "rm" (:image names)] (shell/image-remove-argv {} names)))
    (is (= ["docker" "image" "rm" (:image names)] (shell/image-remove-argv {:runner ["docker"]} names)))))

;; --- relay init's roles

(deftest the-roles-init-adds-are-accepted-and-the-scout-can-reach-the-steward
  (let [data (data-dir!)
        sys (inbound/system data {:clock (constantly t0)})
        texts (relay.setup/starter-texts (relay.setup/repo-root))]
    (doseq [c (relay.setup/starter-commands texts true)]
      (inbound/submit! sys (assoc c :actor "operator")))
    (let [st (inbound/current-state sys)]
      (is (= #{"steward" "scout"} (set (core/active-role-ids st))))
      (is (core/role-text-public? st "steward") "the scout sees only public text, the steward's included")
      (is (= "daily 07:10" (:schedule (core/routine st "steward" "daily-note"))))
      (is (some? (core/doc-version st "skills/steward/daily-note.md" {}))))))

(deftest an-ingest-alert-is-resolved-when-the-source-works-again
  (let [w (-> (seed)
              (run "dispatcher" :ingest/failed {:source "repos" :error "GitHub answered 502"})
              (update :now #(.plusSeconds ^Instant % (* 3 3600)))
              (run "dispatcher" :ingest/failed {:source "repos" :error "GitHub answered 502"}))
        alerts (filter #(= "ingest-failing" (:origin %)) (messages w))]
    (is (= 1 (count alerts)))
    (let [w (run w "dispatcher" :ingest/record {:source "repos" :items [] :keys [] :cursor []})]
      (is (every? :resolved (filter #(= "ingest-failing" (:origin %)) (messages w)))))))

(deftest a-tool-whose-key-is-missing-tells-the-operator-and-not-the-model
  (let [data (str *dir* "/data")
        flag (str *dir* "/works")
        script (str *dir* "/search")]
    (spit script (str "#!/bin/bash\ncat >/dev/null\n"
                      "if [ -e " flag " ]; then printf '{\"content\":\"results\",\"is_error\":false,\"labels\":[]}';\n"
                      "else printf '{\"content\":\"api_key_command failed: exit status 44\",\"is_error\":true,\"labels\":[]}'; fi\n"))
    (fs/set-posix-file-permissions script "rwxr-xr-x")
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :a
                   :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}
                   :tools {"search" {:class :egress :labels ["contaminated"] :protocol :envelope :queries ["query"]
                                     :description "Search." :command [script]
                                     :parameters {:type "object" :properties {:query {:type "string"}} :required ["query"]}}}
                   :grants {"scout" {:public true :tools ["search"]}}}))
    (let [sys (inbound/system data)]
      (inbound/submit! sys {:type :role/add :actor "operator" :public true
                            :args {:id "scout" :title "Scout" :instructions "Look."}})
      (let [r (tools/call! sys "scout" "search" {:query "grants"} [] "c1")]
        (is (= tools/not-set-up (:error r)) "the role is told it is not set up, and not to report it")
        (is (= [] (:labels r))))
      (tools/call! sys "scout" "search" {:query "grants"} [] "c2")
      (let [st (inbound/current-state sys)
            alerts (filter #(= "tool-broken" (:origin %)) (vals (:messages st)))]
        (is (= 1 (count alerts)) "told once, however often it fails")
        (spit flag "")
        (is (= "results" (:ok (tools/call! sys "scout" "search" {:query "grants"} [] "c3"))))
        (is (every? :resolved (filter #(= "tool-broken" (:origin %)) (vals (:messages (inbound/current-state sys))))))))))

;; --- a main role that is not called steward

(deftest a-main-role-relay-edn-does-not-name-is-told-once-and-resolved-when-named
  (let [base {:timezone "UTC" :default-harness :agentloop
              :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"]}}
              :grants {"hub" {:may-add true}}}
        tick (fn [state settings]
               (first (core/run-command state {:type :tick :actor "dispatcher" :args {:lost-wakes []} :labels []}
                                        {:now t0 :config (config/resolve-config settings)})))
        add (fn [state id] (first (core/run-command state {:type :role/add :actor "operator" :labels []
                                                           :args {:id id :title id :instructions "x"}}
                                                    {:now t0 :config (config/resolve-config base)})))
        alerts (fn [st] (filter #(= "settings-misnamed" (:origin %)) (vals (:messages st))))
        st (-> core/empty-state (add "hub") (add "clerk"))
        st (-> st (tick base) (tick base))
        [m & more] (alerts st)]
    (is (nil? more) "told once, not every tick")
    (is (str/includes? (text-of m) "set :dead-letter-handler \"hub\""))
    (is (some #(str/includes? % "set :dead-letter-handler \"hub\"")
              (render/status st (ZoneId/of "UTC") t0 (config/resolve-config base)))
        "relay status says it too")
    (testing "once relay.edn names hub, the alert is resolved and status is quiet"
      (let [fixed (assoc base :dead-letter-handler "hub")
            st (tick st fixed)]
        (is (every? :resolved (alerts st)))
        (is (= 1 (count (alerts st))))
        (is (not-any? #(str/includes? % "dead-letter-handler")
                      (render/status st (ZoneId/of "UTC") t0 (config/resolve-config fixed))))))))
