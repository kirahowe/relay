(ns relay.agentloop-test
  "The agentloop harness: reading an outcome back, and whole wakes with the
  real agentloop binary against a stand-in for the Anthropic API. The
  stand-in plays the model: it reads the inbox through relay's tool, closes
  every memo it finds, and says it is done."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.harness :as harness]
            [relay.harness.agentloop :as agentloop]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.mock-anthropic :as mock]
            [relay.tools :as tools])
  (:import (java.time Instant)))

(def now (Instant/parse "2026-10-14T12:00:00Z"))

(defn- ended [reason & {:as more}]
  (merge {:type "outcome" :reason reason :text "All done." :turns 2
          :usage {:input_tokens 10 :output_tokens 5}}
         more))

(defn- classify [outcome & {:as more}]
  (agentloop/classify (merge {:outcome outcome :exit 0 :stderr "" :session "s-1" :now now} more)))

(defn- model-error [kind & {:as more}]
  (ended "model_error" :text "" :error (merge {:kind kind :status 500 :message "It failed."} more)))

(deftest reading-how-a-run-ended
  (testing "runs that got to work, whatever stopped them"
    (doseq [reason ["completed" "max_tokens" "limit" "held"]]
      (is (= ["ran" "s-1" "All done." false]
             ((juxt :outcome :session :said :crashed) (classify (ended reason))))
          reason)))
  (testing "a refusal is the run's own failure"
    (is (= ["ran" true] ((juxt :outcome :crashed) (classify (ended "refused"))))))
  (testing "a cancel is the wake deadline"
    (is (= ["ran" true "Stopped at the wake deadline."]
           ((juxt :outcome :crashed :said) (classify (ended "canceled"))))))
  (testing "only a stop at the deadline is cut short"
    (is (true? (:cut-short (classify (ended "canceled")))))
    (is (true? (:cut-short (classify nil :exit "timeout"))))
    (doseq [o [(ended "completed") (ended "refused") (model-error "overloaded")
               (model-error "invalid_request") nil]]
      (is (false? (:cut-short (classify o :exit 0))) (pr-str o))))
  (testing "model errors, by kind"
    (is (= "unreachable" (:outcome (classify (model-error "overloaded" :status 529)))))
    (is (= "unreachable" (:outcome (classify (model-error "unreachable" :status 0)))))
    (doseq [kind ["unauthorized" "billing" "not_found"]]
      (is (= "needs-attention" (:outcome (classify (model-error kind :status 401)))) kind))
    (is (= ["ran" true] ((juxt :outcome :crashed) (classify (model-error "invalid_request" :status 400)))))
    (is (= "It failed." (:said (classify (model-error "overloaded"))))))
  (testing "a rate limit resumes when the provider says, else when to retry"
    (is (= "2026-10-14T12:30:00Z"
           (:resets-at (classify (model-error "rate_limited" :status 429
                                              :resets_at "2026-10-14T12:30:00Z"
                                              :retry_after "2026-10-14T12:01:00Z")))))
    (is (= "2026-10-14T12:01:00Z"
           (:resets-at (classify (model-error "rate_limited" :resets_at "2026-10-14T11:00:00Z"
                                              :retry_after "2026-10-14T12:01:00Z")))))
    (is (= ["limited" nil]
           ((juxt :outcome :resets-at) (classify (model-error "rate_limited" :status 429))))))
  (testing "a run that never got going"
    (is (= "needs-attention" (:outcome (classify nil :exit 1 :stderr "agentloop: bad request"))))
    (is (str/includes? (:said (classify nil :exit 1 :stderr "agentloop: bad request")) "did not start")))
  (testing "the deadline"
    (is (= ["ran" true] ((juxt :outcome :crashed) (classify nil :exit "timeout"))))))

(deftest reading-the-outcome-line
  (let [text (str (json/generate-string {:seq 1 :type "run_started"}) "\n"
                  "not json\n"
                  (json/generate-string (ended "completed" :text "first")) "\n"
                  (json/generate-string (ended "held" :text "last")) "\n")]
    (is (= "last" (:text (agentloop/parse-outcome text))))
    (is (nil? (agentloop/parse-outcome "")))))

;; --- whole wakes against the stand-in

(def bin
  "The agentloop binary the tests run: on PATH, or built in the sibling checkout."
  (or (some-> (fs/which "agentloop") str)
      (let [sibling (fs/absolutize "../agentloop/bin/agentloop")]
        (when (fs/executable? sibling) (str sibling)))))

(def relay-bin (str (fs/absolutize "bin/relay")))
(def ^:dynamic *dir* nil)
(def ^:dynamic *sys* nil)
(def ^:dynamic *mock* nil)

(defn- memo-ids [text] (map parse-long (re-seq #"(?m)(?<=^Memo )\d+" (str text))))

(defn closes-everything
  "A model that reads the inbox, closes every memo in it, and stops."
  [body]
  (let [results (mock/last-tool-results body)]
    (cond
      (empty? results) {:text "Let me look." :tool-uses [{:name "inbox" :input {}}]}
      (= "inbox" (:name (first results)))
      (let [ids (memo-ids (:text (first results)))]
        (if (seq ids)
          {:tool-uses (for [id ids] {:name "memo_close" :input {:memo id :outcome "Filed it."}})}
          {:text "Nothing to do."}))
      :else {:text "Done."})))

(defn- write-config! [harness]
  (spit (str *dir* "/data/relay.edn")
        (pr-str {:timezone "UTC"
                 :default-harness :agentloop
                 :harnesses {:agentloop (merge {:kind :agentloop :bin bin :base-url (:url *mock*)
                                                :api-key-command ["echo" "sk-test-key"]
                                                :effort "high"
                                                :retry {:retries 0}}
                                               harness)}
                 :tools {"printenv" {:class :read :description "Print the environment." :command ["env"]}}
                 :grants {"clerk" {:tools ["printenv"]}}
                 :notify {:kind :none}})))

(defn- with-mock [responder f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-agentloop-test"}))
        m (mock/start! responder)
        before (System/getProperty "relay.bin")]
    (System/setProperty "relay.bin" relay-bin)
    (log/init! (str dir "/data"))
    (try
      (binding [*dir* dir *mock* m]
        (write-config! {})
        (binding [*sys* (inbound/system (str dir "/data"))]
          (inbound/submit! *sys* {:type :role/add :actor "operator"
                                  :args {:id "clerk" :title "Clerk" :instructions "File things. Be brief."}})
          (f)))
      (finally
        ((:stop m))
        (if before (System/setProperty "relay.bin" before) (System/clearProperty "relay.bin"))
        (fs/delete-tree dir)))))

(defn- memo! [subject]
  (:seq (first (inbound/submit! *sys* {:type :memo/send :actor "operator"
                                       :args {:to "clerk" :subject subject :body "Body."}}))))

(defn- state [] (inbound/current-state *sys*))


(deftest a-real-wake-closes-memos-through-relays-tools
  (if-not bin
    (println "SKIPPED: agentloop is not built; run make -C ../agentloop build")
    (with-mock closes-everything
      (fn []
        (let [a (memo! "File the invoices")
              b (memo! "File the receipts")
              result (dispatch/wake! *sys* "clerk" {})
              requests (map :body @(:requests *mock*))
              first-req (first requests)]
          (is (= {:result :ran :outcome "ran" :left-open 0} (dissoc result :wake)))
          (is (= [:read :read] (map #(:status (core/memo (state) %)) [a b])))
          (is (= ["clerk" "clerk"] (map #(:closed-by (core/memo (state) %)) [a b])))
          (testing "the model saw only relay's tools, with their schemas"
            (is (= #{"inbox" "roles" "memo_send" "memo_close" "memo_block" "routines"
                     "routine_write" "routine_remove" "facts" "fact_history" "fact_add"
                     "fact_supersede" "fact_correct" "note_write" "note_remove" "journal"
                     "journal_write" "docs" "doc_get" "doc_history" "doc_put" "doc_remove"
                     "printenv"}
                   (set (map :name (:tools first-req)))))
            (let [json-round-trip #(json/parse-string (json/generate-string %) true)
                  manifest (tools/manifest (:config *sys*) "clerk")]
              (is (= (into {} (map (juxt :name (comp json-round-trip :parameters))) manifest)
                     (into {} (map (juxt :name :input_schema)) (:tools first-req))))
              (is (some #(contains? (:input_schema %) :additionalProperties) (:tools first-req))
                  "additionalProperties reaches the API intact")))
          (testing "the system prompt is relay's: the shared instructions and the role's own"
            (let [system (str/join (map :text (:system first-req)))]
              (is (str/includes? system "File things. Be brief."))
              (is (str/includes? system "Your id is `clerk`"))))
          (testing "the wake prompt lists the memos"
            (is (str/includes? (json/generate-string (:messages first-req)) "File the invoices")))
          (testing "adaptive thinking, summarized, at the configured effort"
            (is (= {:type "adaptive" :display "summarized"} (:thinking first-req)))
            (is (= "high" (get-in first-req [:output_config :effort]))))
          (testing "the key came from the configured command"
            (is (= "sk-test-key" (get-in (first @(:requests *mock*)) [:headers "x-api-key"]))))
          (testing "the model saw the tool results"
            (is (= 3 (count requests)))
            (is (= ["Closed memo 2." "Closed memo 3."]
                   (sort (map :text (mock/last-tool-results (last requests)))))))
          (testing "the transcript is kept with the wake"
            (is (fs/exists? (get-in (state) [:wakes (:wake result) :transcript])))))))))

(deftest credentials-never-reach-a-tool
  (when bin
    (with-mock (fn [body]
                 (if (empty? (mock/last-tool-results body))
                   {:tool-uses [{:name "printenv" :input {:args []}}]}
                   {:text "Seen."}))
      (fn []
        (memo! "Look around")
        (dispatch/wake! *sys* "clerk" {})
        (let [env-text (:text (first (mock/last-tool-results (:body (last @(:requests *mock*))))))]
          (is (str/includes? env-text "PATH="))
          (is (not (str/includes? env-text "sk-test-key")))
          (is (not (str/includes? env-text "ANTHROPIC"))))))))

(deftest a-tool-the-role-was-not-granted-is-refused-to-the-model
  (when bin
    (with-mock (fn [body]
                 (if (empty? (mock/last-tool-results body))
                   {:tool-uses [{:name "role_add" :input {:id "x" :title "X" :instructions "c"}}]}
                   {:text "Oh well."}))
      (fn []
        (memo! "Grow")
        (dispatch/wake! *sys* "clerk" {})
        (let [[result] (mock/last-tool-results (:body (last @(:requests *mock*))))]
          (is (:error result))
          (is (nil? (core/role (state) "x"))))))))

(deftest a-rate-limit-pauses-the-harness-until-the-reported-reset
  (when bin
    (let [reset (str (.plusSeconds (Instant/now) 5400))]
      (with-mock (fn [_] {:status 429 :type "rate_limit_error" :message "Rate limited."
                          :headers {"anthropic-ratelimit-tokens-reset" reset "retry-after" "5"}})
        (fn []
          (let [id (memo! "File this")
                result (dispatch/wake! *sys* "clerk" {})]
            (is (= "limited" (:outcome result)))
            (is (= (Instant/parse reset) (core/paused-until (state) "agentloop" (Instant/now))))
            (is (zero? (:attempts (core/memo (state) id))))))))))

(deftest an-interrupted-wake-resumes-its-session
  (when bin
    (let [calls (atom 0)]
      (with-mock (fn [body]
                   (if (= 1 (swap! calls inc))
                     {:status 401 :type "authentication_error" :message "invalid x-api-key"}
                     (closes-everything body)))
        (fn []
          (let [id (memo! "File this")
                first-wake (dispatch/wake! *sys* "clerk" {})
                session (get-in (state) [:wakes (:wake first-wake) :session])]
            (is (= "needs-attention" (:outcome first-wake)))
            (is (some? session))
            (let [second-wake (dispatch/wake! *sys* "clerk" {})
                  resumed-req (:body (nth @(:requests *mock*) 1))]
              (is (= "ran" (:outcome second-wake)))
              (is (= :read (:status (core/memo (state) id))))
              (testing "the second run carried on the first run's conversation"
                (is (= session (get-in (state) [:wakes (:wake second-wake) :session])))
                (is (str/includes? (json/generate-string (:messages resumed-req)) "cut short"))
                ;; The adapter merges the two user turns into one message.
                (is (>= (count (for [m (:messages resumed-req) :when (= "user" (:role m))
                                     c (:content m) :when (= "text" (:type c))]
                                 c))
                        2))))))))))

(deftest no-key-means-no-run
  (when bin
    (with-mock closes-everything
      (fn []
        (write-config! {:api-key-command ["false"]})
        (binding [*sys* (inbound/system (str *dir* "/data"))]
          (memo! "x")
          (is (= "needs-attention" (:outcome (dispatch/wake! *sys* "clerk" {}))))
          (is (empty? @(:requests *mock*))))))))

(deftest the-request-carries-a-per-call-tool-timeout
  (let [req (fn [harness]
              (agentloop/request-json {:harness harness :system-prompt "s" :prompt "p" :tools []
                                       :tool-command ["relay" "tool"] :session-dir "/tmp/s"}
                                      "id" "/tmp/work"))]
    (is (= 300 (:tool_timeout_seconds (req {}))))
    (is (= 45 (:tool_timeout_seconds (req {:tool-timeout-seconds 45}))))))

;; --- conversations are described, not run

(def gen-harness
  (gen/fmap (fn [[provider effort base-url limits retry timeout model]]
              (cond-> {:kind :agentloop :api-key-command ["key"]}
                provider (assoc :provider provider) effort (assoc :effort effort)
                base-url (assoc :base-url base-url) limits (assoc :limits limits)
                retry (assoc :retry retry) timeout (assoc :tool-timeout-seconds timeout)
                model (assoc :model model)))
            (gen/tuple (gen/elements [nil "anthropic" "openai"])
                       (gen/elements [nil "low" "max"])
                       (gen/elements [nil "https://example.test"])
                       (gen/one-of [(gen/return nil)
                                    (gen/map (gen/elements [:max-turns :max-tool-calls :max-tokens :junk])
                                             gen/nat)])
                       (gen/one-of [(gen/return nil)
                                    (gen/map (gen/elements [:retries :backoff-seconds :max-wait-seconds])
                                             gen/nat)])
                       (gen/elements [nil 5 300])
                       (gen/elements [nil "m"]))))

(def an-acp-request-is-a-run-request-for-a-session-directory
  (prop/for-all [harness gen-harness
                 timeout-ms (gen/elements [nil 60000 3600000])
                 model (gen/elements [nil "role-model"])]
    (let [request {:harness harness :role {:id "clerk"} :model model :system-prompt "s" :prompt "hello"
                   :tools [{:name "inbox" :description "d" :parameters {} :class :read}]
                   :tool-command ["relay" "tool"] :session-dir "/data/runs/clerk/conversations"
                   :timeout-ms timeout-ms}
          run (agentloop/request-json request "id" "/w")
          acp (agentloop/acp-request-json request "/w")]
      (and (= "/data/runs/clerk/conversations" (:session_dir acp))
           (not-any? #(contains? acp %) [:session :prompt :timeout_seconds])
           (= {:inbox "read"} (update-keys (:tool_kinds acp) keyword))
           (= "relay · clerk" (:title acp))
           (= (dissoc run :session :prompt :timeout_seconds)
              (dissoc acp :session_dir :tool_kinds :title))))))

(deftest an-acp-request-differs-from-a-run-request-only-in-its-sessions
  (let [result (tc/quick-check 300 an-acp-request-is-a-run-request-for-a-session-directory)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

(deftest conversations-are-described-as-commands-for-relay-to-start
  (let [dir (str (fs/create-temp-dir {:prefix "relay-agentloop-describe"}))
        ;; A binary that leaves a mark if anything runs it.
        bin (str (fs/path dir "agentloop"))
        request {:harness {:kind :agentloop :bin bin :api-key-command ["key"]}
                 :system-prompt "s" :tools [] :tool-command ["relay" "tool" "--role" "clerk"]
                 :run-dir (str (fs/path dir "run")) :session-dir (str (fs/path dir "conversations"))
                 :timeout-ms 60000}
        read-request #(json/parse-string (slurp (str (fs/path dir "run" "request.json"))) true)]
    (try
      (spit bin "#!/bin/sh\ntouch \"$0.ran\"\nexit 9\n")
      (fs/set-posix-file-permissions bin "rwxr-xr-x")
      (testing "acp: the ACP agent, with a directory of sessions"
        (let [{:keys [argv session-dir] :as cmd} (harness/acp-command request)
              written (read-request)]
          (is (= [bin "acp" (str (fs/path (:run-dir request) "request.json"))] argv) (pr-str cmd))
          (is (not (contains? cmd :session)))
          (is (= session-dir (:session_dir written)))
          (is (not-any? #(contains? written %) [:session :prompt :timeout_seconds]))
          (is (fs/directory? session-dir))))
      (testing "describing it starts no process"
        (is (not (fs/exists? (str bin ".ran")))))
      (testing "what cannot run is a problem, not an exception"
        (is (string? (:problem (harness/acp-command (assoc-in request [:harness :api-key-command] [])))))
        (is (string? (:problem (harness/acp-command (assoc-in request [:harness :kind] :script))))))
      (finally (fs/delete-tree dir)))))
