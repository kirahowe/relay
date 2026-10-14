(ns relay.pi-test
  "The pi harness: reading its output back, and whole wakes with the real pi
  against a stand-in for the Anthropic API. The stand-in plays the model: it
  reads the inbox through relay's tool, closes every memo it finds, and
  says it is done."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.harness.pi :as pi]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.mock-anthropic :as mock])
  (:import (java.time Instant)))

(def now (Instant/parse "2026-10-14T12:00:00Z"))

(defn- run-of [stop error & {:as more}]
  (merge {:events [{:type "session" :id "s-1"}
                   {:type "agent_end" :messages [{:role "user" :content []}
                                                 (cond-> {:role "assistant" :stopReason stop
                                                          :content [{:type "text" :text "All done."}]}
                                                   error (assoc :errorMessage error))]}]
          :exit 0 :stderr "" :now now}
         more))

(deftest reading-how-a-run-ended
  (testing "a finished run, whatever the exit code says"
    (is (= ["ran" "s-1" "All done." false]
           ((juxt :outcome :session :said :crashed) (pi/classify (run-of "stop" nil))))))
  (testing "the errors pi reports, verbatim from pi 0.73.1"
    (is (= "limited" (:outcome (pi/classify (run-of "error" "429 {\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"This request would exceed your organization's rate limit.\"}}")))))
    (is (= "unreachable" (:outcome (pi/classify (run-of "error" "529 {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}")))))
    (is (= "needs-attention" (:outcome (pi/classify (run-of "error" "401 {\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}")))))
    (is (= "needs-attention" (:outcome (pi/classify (run-of "error" "400 {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}")))))
    (is (= "unreachable" (:outcome (pi/classify (run-of "error" "Connection error: fetch failed"))))))
  (testing "a bad request is the run's own failure"
    (is (= ["ran" true] ((juxt :outcome :crashed) (pi/classify (run-of "error" "400 {\"error\":{\"message\":\"prompt is too long\"}}"))))))
  (testing "a rate limit's reset comes from the headers relay's extension logged"
    (let [log (str (json/generate-string {:status 529 :headers {}}) "\n"
                   (json/generate-string {:status 429 :headers {"anthropic-ratelimit-requests-reset" "2026-10-14T12:01:00Z"
                                                                "anthropic-ratelimit-tokens-reset" "2026-10-14T12:30:00Z"
                                                                "retry-after" "60"}}))]
      (is (= "2026-10-14T12:30:00Z" (:resets-at (pi/classify (run-of "error" "429 x" :provider-log log))))))
    (is (= "2026-10-14T12:02:00Z"
           (:resets-at (pi/classify (run-of "error" "429 x"
                                            :provider-log (json/generate-string {:status 429 :headers {"Retry-After" "120"}}))))))
    (is (nil? (:resets-at (pi/classify (run-of "error" "429 x"))))))
  (testing "a run that never got going"
    (is (= "needs-attention" (:outcome (pi/classify {:events [] :exit 1 :now now
                                                     :stderr "No API key found for anthropic."}))))
    (is (= "needs-attention" (:outcome (pi/classify {:events [] :exit 1 :now now :stderr "node: not found"}))))
    (is (= "unreachable" (:outcome (pi/classify {:events [{:type "session" :id "s"}] :exit 1 :now now :stderr ""})))))
  (testing "the deadline"
    (is (= ["ran" true] ((juxt :outcome :crashed) (pi/classify {:events [] :exit "timeout" :now now :stderr ""}))))))

;; --- whole wakes against the stand-in

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
                 :default-harness :pi
                 :harnesses {:pi (merge {:kind :pi :base-url (:url *mock*)
                                         :api-key-command ["echo" "sk-test-key"]
                                         :effort "high"}
                                        harness)}
                 :tools {"printenv" {:class :read :description "Print the environment." :command ["env"]}}
                 :grants {"clerk" {:tools ["printenv"]}}
                 :notify {:kind :none}})))

(defn- with-mock [responder f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-pi-test"}))
        m (mock/start! responder)
        before (System/getProperty "relay.bin")]
    (System/setProperty "relay.bin" relay-bin)
    (log/init! (str dir "/data"))
    (try
      (binding [*dir* dir *mock* m]
        (write-config! {})
        (binding [*sys* (inbound/system (str dir "/data"))]
          (inbound/submit! *sys* {:type :role/add :actor "operator"
                                  :args {:id "clerk" :title "Clerk" :config "File things. Be brief."}})
          (f)))
      (finally
        ((:stop m))
        (if before (System/setProperty "relay.bin" before) (System/clearProperty "relay.bin"))
        (fs/delete-tree dir)))))

(defn- memo! [subject]
  (:seq (first (inbound/submit! *sys* {:type :memo/send :actor "operator"
                                       :args {:to "clerk" :subject subject :body "Body."}}))))

(defn- state [] (inbound/current-state *sys*))

(def pi-installed? (fs/exists? "node_modules/.bin/pi"))

(deftest a-real-pi-wake-closes-memos-through-relays-tools
  (if-not pi-installed?
    (println "SKIPPED: pi is not installed; run npm ci")
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
                     "routine_write" "routine_remove" "printenv"}
                   (set (map :name (:tools first-req)))))
            ;; pi drops additionalProperties on the way out. relay reads only
            ;; the arguments it knows and ignores the rest.
            (is (= {:type "object" :properties {:memo {:type "integer" :description "The memo's number, as shown in the inbox."}
                                                :outcome {:type "string"}}
                    :required ["memo" "outcome"]}
                   (select-keys (:input_schema (first (filter #(= "memo_close" (:name %)) (:tools first-req))))
                                [:type :properties :required]))))
          (testing "the system prompt is relay's: the shared instructions and the role's config"
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
  (when pi-installed?
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
  (when pi-installed?
    (with-mock (fn [body]
                 (if (empty? (mock/last-tool-results body))
                   {:tool-uses [{:name "role_add" :input {:id "x" :title "X" :config "c"}}]}
                   {:text "Oh well."}))
      (fn []
        (memo! "Grow")
        (dispatch/wake! *sys* "clerk" {})
        (let [[result] (mock/last-tool-results (:body (last @(:requests *mock*))))]
          (is (:error result))
          (is (nil? (core/role (state) "x"))))))))

(deftest a-rate-limit-pauses-the-harness-until-the-reported-reset
  (when pi-installed?
    (let [reset (str (.plusSeconds (Instant/now) 5400))]
      (with-mock (fn [_] {:status 429 :type "rate_limit_error" :message "Rate limited."
                          :headers {"anthropic-ratelimit-tokens-reset" reset "retry-after" "5"}})
        (fn []
          (let [id (memo! "File this")
                result (dispatch/wake! *sys* "clerk" {})]
            (is (= "limited" (:outcome result)))
            (is (= (Instant/parse reset) (core/paused-until (state) "pi" (Instant/now))))
            (is (zero? (:attempts (core/memo (state) id))))))))))

(deftest an-interrupted-wake-resumes-its-pi-session
  (when pi-installed?
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
                (is (>= (count (filter #(= "user" (:role %)) (:messages resumed-req))) 2))))))))))

(deftest no-key-means-no-run
  (when pi-installed?
    (with-mock closes-everything
      (fn []
        (write-config! {:api-key-command ["false"]})
        (binding [*sys* (inbound/system (str *dir* "/data"))]
          (memo! "x")
          (is (= "needs-attention" (:outcome (dispatch/wake! *sys* "clerk" {}))))
          (is (empty? @(:requests *mock*))))))))
