(ns relay.public-roles-e2e-test
  "A public role's wake with the real agentloop binary against the stand-in
  for the Anthropic API: what the model is sent never holds anything
  private, its egress runs, and a result carrying private is
  refused, by relay's tool command and by agentloop itself."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.dispatch :as dispatch]
            [relay.harness :as harness]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.mock-anthropic :as mock]
            [relay.test-support :as support]))

(def bin support/agentloop-bin)
(def relay-bin (str (fs/absolutize "bin/relay")))
(def fixture (str (fs/absolutize "test/fixtures/envelope-tool")))

(defn- settings [mock]
  {:timezone "UTC"
   :default-harness :agentloop
   :harnesses {:agentloop {:kind :agentloop :bin bin :base-url (:url mock)
                           :api-key-command ["echo" "sk-test-key"] :retry {:retries 0}}}
   :tools {"search" {:class :egress :labels ["contaminated"] :protocol :envelope
                     :description "Search the web." :command [fixture "echo"]
                     :parameters {:type "object" :properties {:question {:type "string"}}}}
           "lookup" {:class :read :labels [] :protocol :envelope :description "Look something up."
                     :command [fixture "private"] :parameters {:type "object"}}}
   :grants {"scout" {:public true :tools ["search" "lookup"]}}
   :notify {:kind :none}})

(defn- with-world [responder f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-public-e2e"}))
        data (str dir "/data")
        m (mock/start! responder)
        before (System/getProperty "relay.bin")]
    (System/setProperty "relay.bin" relay-bin)
    (log/init! data)
    (spit (str data "/relay.edn") (pr-str (settings m)))
    (spit (str data "/AGENTS.md") "The operator's bank is SECRET-BANK.")
    (try
      (let [sys (inbound/system data)]
        (doseq [id ["hub" "scout"]]
          (inbound/submit! sys {:type :role/add :actor "operator" :public (= "scout" id)
                                :args {:id id :title id :instructions "Search for arts grants. Be brief."}}))
        (f sys m dir))
      (finally
        ((:stop m))
        (if before (System/setProperty "relay.bin" before) (System/clearProperty "relay.bin"))
        (fs/delete-tree dir)))))

(defn- browses
  "Reads the inbox, searches the web twice, looks something up, closes the
  memo and stops."
  [body]
  (let [called (set (for [msg (:messages body) :when (= "assistant" (:role msg))
                          c (:content msg) :when (and (map? c) (= "tool_use" (:type c)))]
                      (:name c)))
        memo (some-> (re-find #"Memo (\d+)" (json/generate-string (:messages body))) second parse-long)]
    (cond
      (not (called "inbox")) {:tool-uses [{:name "inbox" :input {}}]}
      (not (called "search")) {:tool-uses [{:name "search" :input {:question "arts grants"}}
                                           {:name "search" :input {:question "deadlines"}}]}
      (not (called "lookup")) {:tool-uses [{:name "lookup" :input {}}]}
      (not (called "note_write")) {:tool-uses [{:name "note_write" :input {:name "found" :text "Two grants."}}]}
      (not (called "memo_close")) {:tool-uses [{:name "memo_close" :input {:memo memo :outcome "Found two."}}]}
      :else {:text "Done."})))

(deftest a-public-role-browses-and-never-holds-private
  (if-not bin
    (println "SKIPPED: agentloop is not built; run make -C ../agentloop build")
    (with-world browses
      (fn [sys mock _]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"was not sent"
                              (inbound/submit! sys {:type :memo/send :actor "hub" :labels ["private"]
                                                    :args {:to "scout" :subject "Kira's thesis" :body "SECRET-THESIS topic."}})))
        (inbound/submit! sys {:type :memo/send :actor "operator" :public true
                              :args {:to "scout" :subject "Find arts grants" :body "Open ones, this spring."}})
        (let [result (dispatch/wake! sys "scout" {})
              state (inbound/current-state sys)
              wake (get-in state [:wakes (:wake result)])
              bodies (map (comp json/generate-string :body) @(:requests mock))
              request (json/parse-string (slurp (str (fs/path (fs/parent (:transcript wake)) "request.json"))) true)]
          (is (= "ran" (:outcome result)))
          (is (= 0 (:left-open result)))
          (testing "agentloop is told the session may not hold private"
            (is (= ["private"] (get-in request [:policy :forbid_labels])))
            (is (= [] (get-in request [:policy :labels]))))
          (testing "its egress ran, contaminated as it became"
            (is (= 2 (count (filter #(= "search" (:name %))
                                    (mapcat mock/last-tool-results (map :body @(:requests mock))))))))
          (testing "what it wrote through relay's tool command is public"
            (is (:public (get-in state [:notes "scout" "found"]))))
          (testing "the session ended without private"
            (is (= ["contaminated"] (:labels wake))))
          (testing "the model was sent nothing private"
            (is (not-any? #(str/includes? % "SECRET-THESIS") bodies) "the hub's memo was never sent")
            (is (not-any? #(str/includes? % "SECRET-BANK") bodies) "nor the operator's AGENTS.md")
            (is (not-any? #(str/includes? % "SECRET-FROM-THE-TOOL") bodies) "nor a result carrying private")
            (is (some #(str/includes? % "This result was refused") bodies)))
          (testing "the operator is told of the refused result"
            (let [kinds (set (map :kind (vals (:notifications state))))]
              (is (contains? kinds "result-refused")))))))))

(def private-tool
  "A tool command that answers every call with a result carrying private,
  standing in for relay's own, so that only agentloop is left to refuse it."
  "#!/bin/sh\ncat >/dev/null\nprintf '{\"content\":\"SECRET-FROM-AGENTLOOP\",\"is_error\":false,\"labels\":[\"private\"]}'\n")

(deftest agentloop-refuses-what-relay-would-have-let-through
  (if-not bin
    (println "SKIPPED: agentloop is not built; run make -C ../agentloop build")
    (with-world (fn [body]
                  (if (empty? (mock/last-tool-results body))
                    {:tool-uses [{:name "lookup" :input {}}]}
                    {:text "Done."}))
      (fn [sys mock dir]
        (inbound/submit! sys {:type :memo/send :actor "operator" :public true
                              :args {:to "scout" :subject "Look" :body "Look it up."}})
        (let [started (inbound/submit! sys {:type :wake/start :actor "dispatcher" :args {:role "scout" :dispatched true}})
              wake-id (:seq (first started))
              state (inbound/current-state sys)
              request (dispatch/wake-request sys state (get-in state [:wakes wake-id]) (dispatch/wake-inputs sys))
              command (harness/wake-command request)
              tool (str dir "/private-tool.sh")
              file (str (fs/path (:run-dir request) "request.json"))]
          ;; The request relay wrote, with its tool command swapped for one
          ;; that answers with private.
          (spit tool private-tool)
          (fs/set-posix-file-permissions tool "rwx------")
          (spit file (json/generate-string (assoc (json/parse-string (slurp file) true) :tool_command [tool])))
          (let [ran (assoc (dispatch/run-command! command) :now (java.time.Instant/now))
                report (harness/wake-report request command ran)]
            (is (= [{:call_id "toolu_1" :tool "lookup" :labels ["private"]}]
                   (map #(update % :call_id (constantly "toolu_1")) (:refused report))))
            (is (= [] (:labels report)) "the session never took the label")
            (is (not-any? #(str/includes? (json/generate-string (:body %)) "SECRET-FROM-AGENTLOOP") @(:requests mock)))
            (inbound/submit! sys {:type :wake/end :actor "dispatcher" :args (dispatch/wake-end-args wake-id report)})
            (is (some #(and (= "result-refused" (:kind %)) (str/includes? (:body %) "lookup returned your private things"))
                      (vals (:notifications (inbound/current-state sys)))))))))))
