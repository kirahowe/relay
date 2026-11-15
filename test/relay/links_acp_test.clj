(ns relay.links-acp-test
  "Live links over relay acp (relay#95, relay#118): _relay/links and its
  changed notification, and the operator keeping and dropping a link
  from a client. A card's text stays broken whatever is live, and the
  card lists the live links it holds beside it. The hub runs in relay
  acp's own process and no conversation is opened: these methods are the
  connection's."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.acp-client :as client]
            [relay.core :as core]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.test-support :as support]))

(def fund "https://grants.example.org/fund?year=2027")
(def fund-broken "hxxps://grants[.]example[.]org/fund?year=2027")
(def evil-broken "hxxps://evil[.]example/x?d=1")

(def note
  "The note's text as a client is sent it, whatever is live."
  (str "See " fund-broken " and " evil-broken))

(def fund-listed [{"address" fund "shown" fund-broken}])

(defn- with-relay [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-links-acp"}))
        data (str dir "/data")]
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :a
                   :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}}))
    (try
      (let [sys (inbound/system data)]
        (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "scout" :title "Scout" :instructions "x"}})
        (inbound/submit! sys {:type :card/add :actor "scout" :labels ["contaminated"]
                              :args {:kind "note" :title "Grants"
                                     :text (str "See " fund " and https://evil.example/x?d=1")}})
        (f dir sys))
      (finally (fs/delete-tree dir)))))

(defn- card
  "The board's card in the newest message that carries the cards: the
  _relay/cards answer or a _relay/cards/changed."
  [c]
  (some #(or (get-in % ["params" "cards" 0]) (get-in % ["result" "cards" 0]))
        (reverse @(:messages c))))

(defn- index-of
  "Where the first message satisfying pred is among those the client has."
  [c pred]
  (first (keep-indexed (fn [i m] (when (and (map? m) (pred m)) i)) @(:messages c))))

(defn- links-changed? [k link]
  (fn [m] (and (= "_relay/links/changed" (get m "method")) (some #{link} (get-in m ["params" k])))))

(defn- cards-with?
  "A _relay/cards/changed whose card lists exactly these links, beside
  text that is as broken as it ever was."
  [links]
  (fn [m] (and (= "_relay/cards/changed" (get m "method"))
               (= links (get-in m ["params" "cards" 0 "links"]))
               (= note (get-in m ["params" "cards" 0 "note" "text"])))))

(deftest the-operator-keeps-and-drops-a-link-over-relay-acp
  (with-relay
    (fn [dir sys]
      (let [data (:data-dir sys)
            live #(core/live-links (inbound/current-state sys))
            c (client/start! [support/relay-bin "acp" "--data" data] {:dir dir})]
        (try
          (is (contains? (get-in (client/request! c "initialize" {:protocolVersion 1 :clientCapabilities {}})
                                 ["result" "_meta"])
                         "relay/hub"))
          (testing "at first no link is live: the card's links are both broken and it lists none"
            (is (= [] (get-in (client/request! c "_relay/links" {}) ["result" "live"])))
            (client/request! c "_relay/cards" {})
            (is (= note (get-in (card c) ["note" "text"])))
            (is (= [[] 0] [(get (card c) "links") (get (card c) "linksLeftOut")])))
          (testing "kept, as the client showed it: relay answers, names the link, and sends the card again with it listed"
            (is (= {"changed" true} (get (client/request! c "_relay/links/keep" {:address fund-broken}) "result")))
            (is (client/await! c (links-changed? "live" fund) 10000))
            (is (client/await! c (cards-with? fund-listed) 10000))
            (is (< (index-of c (links-changed? "live" fund))
                   (index-of c (cards-with? fund-listed)))
                "the set comes before the card that reads differently for it")
            (is (not (str/includes? (pr-str (dissoc (card c) "links")) "https://"))
                "and the address is nowhere in the card but its list")
            (is (= #{fund} (live)))
            (let [e (last (log/read-events data))]
              (is (= ["link/kept" "operator" fund] [(:type e) (:actor e) (get-in e [:data :link])]))))
          (testing "kept again, nothing changes"
            (is (= {"changed" false} (get (client/request! c "_relay/links/keep" {:address fund}) "result"))))
          (testing "what is not one plain address is refused with relay's reason, and nothing is kept"
            (doseq [bad ["https://user@evil.example/" "https://evil.example/a(b)" "see https://evil.example/x" "javascript:alert(1)"]]
              (let [r (client/request! c "_relay/links/keep" {:address bad})]
                (is (= -32000 (get-in r ["error" "code"])) bad)
                (is (str/includes? (str (get-in r ["error" "message"])) "one http or https address") bad)))
            (is (= #{fund} (live))))
          (testing "a link kept on the command line reaches the client too"
            (inbound/submit! sys {:type :link/keep :actor "operator" :args {:url "http://arts.example.net/apply"}})
            (is (client/await! c (links-changed? "live" "http://arts.example.net/apply") 10000)))
          (testing "dropped: the link is named gone and the card comes again listing none"
            (is (= {"changed" true} (get (client/request! c "_relay/links/drop" {:address fund}) "result")))
            (is (client/await! c (links-changed? "gone" fund) 10000))
            (is (client/await! c (cards-with? []) 10000))
            (is (< (index-of c (links-changed? "gone" fund)) (index-of c (cards-with? [])))
                "the set comes first again")
            (is (= #{"http://arts.example.net/apply"} (live))))
          (testing "a parameter relay does not know is refused"
            (is (= -32602 (get-in (client/request! c "_relay/links/keep" {:address fund :sessionId "s"}) ["error" "code"])))
            (is (= -32602 (get-in (client/request! c "_relay/links" {:all true}) ["error" "code"]))))
          (client/close-input! c)
          (is (= 0 (client/wait! c 20000)))
          (finally (client/sever! c)))))))
