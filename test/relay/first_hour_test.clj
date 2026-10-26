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
            [relay.dispatch]
            [relay.inbound :as inbound]
            [relay.ingest-run :as ingest-run]
            [relay.log :as log]
            [relay.ready :as ready]
            [relay.render :as render]
            [relay.setup]
            [relay.shell :as shell]
            [relay.text :as text]
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

(deftest relay-note-names-the-note-s-writer
  (let [data (data-dir!)
        _ (spit (str data "/relay.edn") (pr-str (assoc (read-string (slurp (str data "/relay.edn")))
                                                       :dead-letter-handler "hub" :daily-note {:at "07:30" :role "hub"})))
        sys (inbound/system data {:clock (constantly t0)})]
    (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "hub" :title "Hub" :instructions "r"}})
    (doseq [args [["note"] ["note" "now"]]]
      (let [out (str/join "\n" (:out (apply relay! data args)))]
        (is (str/includes? out "Hub writes its parts from its routine:") (pr-str args))
        (is (str/includes? out "relay routine run hub daily-note") (pr-str args))
        (is (not (str/includes? (str/lower-case out) "steward")) (pr-str args))))
    (testing "without :daily-note, the setting to add names the main role"
      (spit (str data "/relay.edn") (pr-str (dissoc (read-string (slurp (str data "/relay.edn"))) :daily-note)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":daily-note \{:at \"07:30\" :role \"hub\"\}"
                            (relay! data "note"))))))

(deftest relay-note-fits-a-phone-and-says-where-today-s-went
  (let [data (data-dir!)
        sys (inbound/system data {:clock (constantly t0)})
        long-item (str "Interview with the Tidewater Community Foundation about the spring grant, Thursday"
                       " at 10:00 in the Old Hall")]
    (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "steward" :title "Steward" :instructions "r"}})
    (inbound/submit! sys {:type :digest/write-part :actor "steward" :labels []
                          :args {:part "ahead" :items [{:text long-item :offer "Prep notes?"}]}})
    (let [{:keys [out]} (relay! data "note")]
      (is (every? #(<= (count %) 60) out) (pr-str out))
      (is (str/includes? (str/join " " (map str/trim out)) long-item) "wrapped, not cut")
      (is (some #(re-find #"^ {3}\S" %) out) "an item's later lines sit under its text"))
    (relay! data "note" "now")
    (let [id (:id (core/latest-note (inbound/current-state sys)))
          {:keys [exit out]} (relay! data "note")
          text (str/join " " (map str/trim out))]
      (is (= 0 exit))
      (is (every? #(<= (count %) 60) out) (pr-str out))
      (is (str/starts-with? (first out) "Today's note went out at "))
      (is (str/includes? text (str "relay ready " id " shows it")))
      (testing "and the next one has nothing yet"
        (is (str/includes? text "tomorrow at 07:30"))
        (is (not (str/includes? text "relay routine run")) "the routine has run today already")))
    (is (str/includes? (str/join " " (:out (relay! data "note" "now")))
                       (str "relay ready " (:id (core/latest-note (inbound/current-state sys))) " shows it")))))

;; --- the first GitHub poll

(deftest the-first-poll-of-a-repository-says-it-has-started-watching
  (let [src {:kind :github :repos ["kira/tidy" "kira/shelf"] :to "steward"}
        recorded [{:type "ingest/polled"}]]
    (is (= "repos: now watching kira/tidy and kira/shelf; new issues and pull requests show up from the next check."
           (ingest-run/poll-line "repos" src recorded ["kira/tidy" "kira/shelf"])))
    (is (= "repos: nothing new." (ingest-run/poll-line "repos" src recorded [])))
    (is (= "repos: 1 new in kira/shelf (#44 by lee-m)."
           (ingest-run/poll-line "repos" src [{:type "memo/sent"
                                              :data {:subject "GitHub kira/shelf: 1 new"
                                                     :body "What changed.\n\nNew issue #44 by lee-m: Shelf breaks\nhttps://github.com/kira/shelf/issues/44"}}]
                                 [])))
    (is (= "calendar: 3 new (Interview with Tidewater Foundation, Th…, Dentist, Fri 30 Oct 09:00, Board meeting, Mon 2 Nov 18:00)."
           (ingest-run/poll-line "calendar" {:kind :ics :to "steward"}
                                 [{:type "memo/sent"
                                   :data {:subject "Your calendar: 3 new"
                                          :body (str "What changed.\n\nNew: Interview with Tidewater Foundation, Thu 29 Oct 11:00\n  At: x"
                                                     "\n\nNew: Dentist, Fri 30 Oct 09:00\n\nNew: Board meeting, Mon 2 Nov 18:00")}}]
                                 nil)))
    (let [line (ingest-run/poll-line "mailbox" {:kind :pop3 :to "steward"}
                                     [{:type "memo/sent" :data {:subject "See evil.example/x now" :body "x"}}] nil)]
      (is (str/starts-with? line "mailbox: 1 new mail (See "))
      (is (not (str/includes? line "evil.example/x")) "an outsider's words never make a link"))))

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
    (let [lines (render/status st (ZoneId/of "UTC") t0 (config/resolve-config base))]
      (is (str/includes? (str/join " " lines) "set :dead-letter-handler \"hub\"") "relay status says it too")
      (is (every? #(<= (count %) 60) lines) "in lines that fit a phone"))
    (testing "once relay.edn names hub, the alert is resolved and status is quiet"
      (let [fixed (assoc base :dead-letter-handler "hub")
            st (tick st fixed)]
        (is (every? :resolved (alerts st)))
        (is (= 1 (count (alerts st))))
        (is (not-any? #(str/includes? % "dead-letter-handler")
                      (render/status st (ZoneId/of "UTC") t0 (config/resolve-config fixed))))))))

;; --- running a routine by hand

(deftest running-a-routine-by-hand-says-what-ran
  (let [data (data-dir!)
        sys (inbound/system data {:clock (constantly t0)})]
    (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "steward" :title "Steward" :instructions "r"}})
    (inbound/submit! sys {:type :routine/write :actor "operator"
                          :args {:owner "steward" :name "daily-note" :schedule "daily 07:10" :instructions "Write it."}})
    (with-redefs [relay.dispatch/wake! (fn [_ role _] {:result :ran :wake 7 :outcome "ran" :left-open 0 :role role})]
      (let [{:keys [exit out]} (relay! data "routine" "run" "daily-note")]
        (is (= 0 exit))
        (is (re-find #"^Sent steward its daily-note routine now \(memo \d+\)\.$" (first out)))
        (is (= "Steward ran (wake 7) and finished its memos." (second out)))))))

;; --- old habits after an upgrade

(deftest a-removed-command-says-what-replaced-it
  (doseq [cmd ["holds" "approve" "deny" "review" "verify" "ledger" "trust-level" "release" "brief"]]
    (let [{:keys [exit out]} (cli/execute [cmd "7"])]
      (is (= 1 exit) cmd)
      (is (= 1 (count out)) (str cmd ": one line, not the whole help"))
      (is (str/starts-with? (first out) (str "relay " cmd " is gone in this version")) cmd))))

(deftest a-memo-to-the-default-steward-names-the-main-role
  (let [data (data-dir!)
        _ (spit (str data "/relay.edn") (pr-str (assoc (read-string (slurp (str data "/relay.edn")))
                                                       :dead-letter-handler "hub" :daily-note {:at "07:30" :role "hub"})))
        sys (inbound/system data {:clock (constantly t0)})]
    (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "hub" :title "Hub" :instructions "r"}})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"There is no role called steward\. Your main role is hub: relay memo hub"
                          (relay! data "memo" "steward" "-s" "x" "-m" "y")))))

(deftest an-ingest-source-for-no-role-is-named
  (let [config (config/resolve-config {:timezone "UTC" :default-harness :x
                                       :harnesses {:x {:kind :agentloop :api-key-command ["true"]}}
                                       :ingest {"calendar" {:kind :ics :url "https://cal.example.org/a.ics" :to "stewart"}}})]
    (is (some #(= "The :ingest source calendar goes to stewart, which is not a role, so it is not checked. Did you mean steward?" %)
              (config/role-name-problems config ["steward"])))))

;; --- what came of the operator's own request

(deftest what-came-of-the-operators-request-reaches-her-as-a-quiet-reply
  (let [w (-> (seed) (run "operator" :memo/send {:to "steward" :subject "Tidy my notes" :body "Then tell me what you moved."} true))
        id (:id (last-memo w))
        w (-> w (run "dispatcher" :wake/start {:role "steward"})
              (run "steward" :memo/close {:memo id :outcome "Sorted the five notes into folders."}))
        [m & more] (messages w)]
    (is (nil? more))
    (is (= ["reply" "low" "Re: Tidy my notes"] [(:kind m) (:urgency m) (:subject m)]))
    (is (= "Sorted the five notes into folders." (text-of m)))
    (is (= "steward" (core/message-author (:state w) m)) "a reply to it goes to the steward")
    (is (some #(= (str "message:" (:id m)) (:id %)) (ready/ready (:state w) {:limit 100})) "shown in Ready"))
  (testing "not when the role told her about that request itself"
    (let [w (-> (seed) (run "operator" :memo/send {:to "steward" :subject "Tidy" :body "x"} true))
          id (:id (last-memo w))
          w (-> w (run "dispatcher" :wake/start {:role "steward"})
                (run "steward" :message/send {:subject "Tidied" :sections [{:items ["Done."]}] :memo id})
                (run "steward" :memo/close {:memo id :outcome "Done."}))]
      (is (= ["Tidied"] (map :subject (messages w))))))
  (testing "a message about one request does not stand for another"
    (let [w (-> (seed)
                (run "operator" :memo/send {:to "steward" :subject "Dentist" :body "Book it."} true)
                (run "operator" :memo/send {:to "steward" :subject "Gym" :body "Cancel it."} true))
          [dentist gym] (map :id (take-last 2 (sort-by :id (vals (:memos (:state w))))))
          w (-> w (run "dispatcher" :wake/start {:role "steward"})
                (run "steward" :message/send {:subject "Dentist booked" :sections [{:items ["Tue 10:00."]}] :memo dentist})
                (run "steward" :memo/close {:memo dentist :outcome "Booked."})
                (run "steward" :memo/close {:memo gym :outcome "Gym cancelled; refund in 5 days."}))]
      (is (= ["Dentist booked" "Re: Gym"] (map :subject (messages w))))
      (is (= "Gym cancelled; refund in 5 days." (text-of (last (messages w)))))))
  (testing "a message about something else does not stand for it"
    (let [w (-> (seed) (run "operator" :memo/send {:to "steward" :subject "Tidy" :body "x"} true))
          id (:id (last-memo w))
          w (-> w (run "dispatcher" :wake/start {:role "steward"})
                (run "steward" :message/send {:subject "Printer ink is low" :sections [{:items ["FYI."]}]})
                (run "steward" :memo/close {:memo id :outcome "Done."}))]
      (is (= ["Printer ink is low" "Re: Tidy"] (map :subject (messages w))))))
  (testing "a message may only be about a memo the role was sent"
    (let [w (-> (seed) (run "operator" :memo/send {:to "scout" :subject "Grants" :body "x"} true))
          id (:id (last-memo w))
          w (run w "dispatcher" :wake/start {:role "steward"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Memo \d+ is not addressed to steward"
                            (run w "steward" :message/send {:subject "s" :sections [{:items ["x"]}] :memo id})))))
  (testing "not for a memo from a role or relay, nor when she closes it herself"
    (let [w (-> (seed) (run "dispatcher" :wake/start {:role "steward"})
                (run "steward" :memo/send {:to "steward" :subject "Self" :body "x"}))
          w (run w "steward" :memo/close {:memo (:id (last-memo w)) :outcome "Done."})
          w (-> w (run "operator" :memo/send {:to "steward" :subject "Mine" :body "x"} true))
          w (run w "operator" :memo/close {:memo (:id (last-memo w)) :outcome "Never mind."})]
      (is (empty? (messages w))))))

;; --- how Ready shows a message

(deftest ready-shows-list-items-behind-a-dash-and-a-hidden-character-once
  (let [w (-> (seed) (run "dispatcher" :wake/start {:role "steward"})
              (run "steward" :message/send {:subject "For the interview"
                                            :sections [{:items ["Two things to bring."]}
                                                       {:heading "Still needed" :list true
                                                        :items ["Figures for the notes" "Pull request by sam​wise"]}]}))
        it (ready/wire (first (filter #(= :message (:kind %)) (ready/ready (:state w) {:limit 10}))))
        out (render/ready-one it (ZoneId/of "UTC") t0)]
    (is (some #{"  - Figures for the notes"} out))
    (is (some #{"  - Pull request by samwise (a hidden character removed)"} out))
    (is (not-any? #(str/includes? % "\\") (map text/visible-line out)) "never escaped twice")))

(defn- run-labelled [{:keys [state now] :as w} actor type args labels]
  (let [[state' events] (core/run-command state {:type type :actor actor :args args :public false :labels labels}
                                          {:now now :config cfg})]
    (-> w (assoc :state state') (update :events into events))))

(deftest the-answer-to-a-request-carries-where-the-request-came-from
  ;; A forwarded mail becomes the operator's memo with an outsider's
  ;; subject; the answer quotes that subject.
  (let [w (-> (seed) (run-labelled "operator" :memo/send {:to "steward" :subject "Fwd: Click evil.example/x" :body "Deal with it."}
                                   ["contaminated" "mail:dana@example.org"]))
        id (:id (last-memo w))
        w (-> w (run "dispatcher" :wake/start {:role "steward"})
              (run "steward" :memo/close {:memo id :outcome "Filed it."}))
        [m] (messages w)]
    (is (= "Re: Fwd: Click evil.example/x" (:subject m)))
    (is (some #{"contaminated"} (:labels m)))
    (is (= ["mail:dana@example.org"] (:sources m)) "the operator is shown where it came from")))

(deftest a-request-that-failed-is-told-unless-the-role-told-about-it
  (testing "an unrelated message does not stand for the alert"
    (let [w (-> (seed) (run "operator" :memo/send {:to "steward" :subject "File taxes" :body "x"} true))
          id (:id (last-memo w))
          w (-> w (run "dispatcher" :wake/start {:role "steward"})
                (run "steward" :message/send {:subject "Printer ink is low" :sections [{:items ["FYI."]}]})
                (run "steward" :memo/block {:memo id :explanation "No tax forms."}))]
      (is (= ["Printer ink is low" "Could not finish \"File taxes\""] (map :subject (messages w))))))
  (testing "the role's own word about it does"
    (let [w (-> (seed) (run "operator" :memo/send {:to "steward" :subject "File taxes" :body "x"} true))
          id (:id (last-memo w))
          w (-> w (run "dispatcher" :wake/start {:role "steward"})
                (run "steward" :message/send {:subject "I can't file your taxes yet" :sections [{:items ["No forms."]}] :memo id})
                (run "steward" :memo/block {:memo id :explanation "No tax forms."}))]
      (is (= ["I can't file your taxes yet"] (map :subject (messages w))))))
  (testing "the handler is told who told the operator"
    (let [w (-> (seed) (run "operator" :role/add {:id "clerk" :title "Clerk" :instructions "c"} true)
                (run "operator" :memo/send {:to "clerk" :subject "File taxes" :body "x"} true))
          id (:id (last-memo w))
          go (fn [w told?]
               (let [w (run w "dispatcher" :wake/start {:role "clerk"})
                     w (cond-> w told? (run "clerk" :message/send {:subject "Can't file" :sections [{:items ["x"]}] :memo id}))]
                 (run w "clerk" :memo/block {:memo id :explanation "No forms."})))
          relay-told (go w false)
          clerk-told (go w true)]
      (is (str/includes? (:body (last-memo relay-told)) "relay has told the operator"))
      (is (= ["Could not finish \"File taxes\""] (map :subject (messages relay-told))))
      (is (= ["Can't file"] (map :subject (messages clerk-told))))
      (is (not (str/includes? (:body (last-memo clerk-told)) "relay has told")))
      (is (str/includes? (:body (last-memo clerk-told)) "clerk has told the operator")))))

(deftest a-role-that-told-why-it-stopped-is-not-said-again
  (let [w (-> (seed) (run "operator" :routine/write {:owner "steward" :name "weekly-review" :schedule "daily 07:10" :instructions "Review."})
              (run "operator" :routine/run {:name "weekly-review"}))
        id (:id (last-memo w))
        w (-> w (run "dispatcher" :wake/start {:role "steward"})
              (run "steward" :message/send {:subject "Weekly review: I can't see your reminders yet" :sections [{:items ["The reminders tool returns nothing."]}] :memo id})
              (run "steward" :memo/block {:memo id :explanation "No reminders."}))]
    (is (= ["Weekly review: I can't see your reminders yet"] (map :subject (messages w)))))
  (testing "without its own word, relay says it"
    (let [w (-> (seed) (run "operator" :routine/write {:owner "steward" :name "weekly-review" :schedule "daily 07:10" :instructions "Review."})
                (run "operator" :routine/run {:name "weekly-review"}))
          id (:id (last-memo w))
          w (-> w (run "dispatcher" :wake/start {:role "steward"})
                (run "steward" :memo/block {:memo id :explanation "No reminders."}))]
      (is (= ["Could not finish \"weekly-review\""] (map :subject (messages w)))))))

;; --- the day's first note

(deftest the-note-waits-for-its-writer-a-while
  (let [at (java.time.LocalTime/parse "07:30") zone (ZoneId/of "UTC")
        date (java.time.LocalDate/parse "2026-10-26")
        t #(Instant/parse (str "2026-10-26T" % ":00Z"))]
    (is (relay.digest/waits? at zone date (t "07:31") true))
    (is (not (relay.digest/waits? at zone date (t "07:31") false)) "nothing to wait for")
    (is (not (relay.digest/waits? at zone date (t "08:30") true)) "an hour at most")))

(deftest a-note-made-before-its-writer-wrote-says-so-and-can-be-made-again
  (let [data (data-dir!)
        sys (inbound/system data {:clock (constantly t0)})]
    (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "steward" :title "Steward" :instructions "r"}})
    (inbound/submit! sys {:type :digest/write-part :actor "steward" :labels []
                          :args {:part "learned" :items [{:text "I keep your venues in a note now."}]}})
    (let [events (inbound/submit! sys {:type :digest/compose :actor core/dispatcher :args {:date "2026-10-26"}})
          note (some #(when (= "message/created" (:type %)) (:data %)) events)]
      (is (some? note) "what it learned gives it something to say")
      (when note
        (is (str/includes? (text-of note) "I haven't gone through your calendar yet today."))
        (is (not (str/includes? (text-of note) "Nothing coming up")))))
    (inbound/submit! sys {:type :digest/write-part :actor "steward" :labels []
                          :args {:part "ahead" :items [{:text "Interview, Thu 10:00" :offer "Prep notes?"}]}})
    (is (str/includes? (first (:out (relay! data "note" "now"))) "Sent today's daily note"))
    (is (str/includes? (first (:out (relay! data "note" "now"))) "made already") "once it has its parts, it is the day's")))

(deftest a-wake-marks-the-operators-own-requests
  (let [w (-> (seed) (run "operator" :memo/send {:to "steward" :subject "Mine" :body "x"} true))
        mine (:id (last-memo w))
        w (-> w (run "dispatcher" :wake/start {:role "steward"})
              (run "steward" :memo/send {:to "steward" :subject "Self" :body "x"}))
        self (:id (last-memo w))]
    (is (= [true nil] (map :request? (core/prompt-memos (:state w) [mine self]))))))

(deftest checking-a-new-relay-edn-in-a-scratch-directory-says-only-that
  (let [check (str *dir* "/check")]
    (fs/create-dirs check)
    (spit (str check "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :x :harnesses {:x {:kind :agentloop :api-key-command ["true"]}}
                   :budget {:weekly-usd 20}}))
    (let [{:keys [exit out]} (relay! check "status")
          text (str/join " " (map str/trim out))]
      (is (= 0 exit))
      (is (str/includes? text "relay.edn is valid. This directory has no log"))
      (is (not (str/includes? text "relay init")))
      (is (not (str/includes? text "tick has never run")))
      (is (not (str/includes? text "earlier weeks"))))))
