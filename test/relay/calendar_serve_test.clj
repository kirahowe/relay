(ns relay.calendar-serve-test
  "The agent's calendar feed, on relay serve's WebSocket listener and
  nowhere else (I9). The routing is pure and checked as a property; the
  service is checked end to end, with an HTTP client that is not relay's."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [relay.cli]
            [relay.log :as log]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.ics :as ics]
            [relay.ics-test :as ics-test]
            [relay.inbound :as inbound]
            [relay.websocket :as ws]
            [relay.serve-support :refer [*sys* wait-for with-service]]))

;; --- the decision

(def listener-token
  "The listener's token, which opens every conversation."
  "tok-for-tests-123")
(def token
  "The feed's own token, made from the listener's."
  (ws/feed-token listener-token))
(def settings {:token listener-token :allowed-origins ["https://phone.example"]})

(defn- head
  ([target] (head target {}))
  ([target headers] (head "GET" target headers))
  ([method target headers]
   (ws/parse-head (str method " " target " HTTP/1.1\r\nHost: x"
                       (apply str (for [[k vs] headers v (if (string? vs) [vs] vs)] (str "\r\n" k ": " v)))))))

(defn- bearer [t] {"Authorization" (str "Bearer " t)})

(deftest the-feed-needs-the-token-from-a-header-or-the-url
  (testing "the token in a header, or in the URL"
    (is (= {:calendar true} (ws/route (head "/calendar.ics" (bearer token)) settings)))
    (is (= {:calendar true} (ws/route (head (str "/calendar.ics?token=" token)) settings))))
  (testing "no token, a wrong one, two of them, or a badly escaped one"
    (is (= {:status 401} (ws/route (head "/calendar.ics") settings)))
    (is (= {:status 401} (ws/route (head "/calendar.ics" (bearer "nope")) settings)))
    (is (= {:status 401} (ws/route (head "/calendar.ics?token=nope") settings)))
    (is (= {:status 401} (ws/route (head (str "/calendar.ics?token=" token "&token=" token)) settings)))
    (is (= {:status 401} (ws/route (head "/calendar.ics?token=%zz") settings)))
    (is (= {:status 401} (ws/route (head "/calendar.ics" {"Authorization" [(str "Bearer " token) (str "Bearer " token)]}) settings)))
    (testing "a header that is wrong is not rescued by a URL that is right"
      (is (= {:status 401} (ws/route (head (str "/calendar.ics?token=" token) (bearer "nope")) settings)))))
  (testing "an origin the listener does not allow is refused, as for the upgrade"
    (is (= {:status 403} (ws/route (head "/calendar.ics" (assoc (bearer token) "Origin" "https://evil.example")) settings)))
    (is (= {:calendar true} (ws/route (head "/calendar.ics" (assoc (bearer token) "Origin" "https://phone.example")) settings))))
  (testing "the listener's own token does not open the feed, in a header or a URL"
    ;; Regression (U1): the feed took the token that opens every
    ;; conversation, and a calendar app syncs its URL to its provider.
    (is (= {:status 401} (ws/route (head "/calendar.ics" (bearer listener-token)) settings)))
    (is (= {:status 401} (ws/route (head (str "/calendar.ics?token=" listener-token)) settings))))
  (testing "and the feed's token does not open a conversation"
    (is (= {:status 401} (ws/route (head "/" (merge (bearer token) {"Upgrade" "websocket" "Connection" "Upgrade"
                                                                     "Sec-WebSocket-Version" "13"
                                                                     "Sec-WebSocket-Key" "dGhlIHNhbXBsZSBub25jZQ=="}))
                                   settings))))
  (testing "the URL relay calendar url prints is the one the feed takes"
    (let [url (ws/calendar-url "mac.tail1234.ts.net" 8765 listener-token)]
      (is (= (str "https://mac.tail1234.ts.net:8765/calendar.ics?token=" token) url))
      (is (= {:calendar true} (ws/route (head (subs url (count "https://mac.tail1234.ts.net:8765"))) settings)))
      (is (not (str/includes? url listener-token)))))
  (testing "only a GET of exactly that path is the feed; the rest is as before"
    (is (= {:status 404} (ws/route (head "POST" "/calendar.ics" (bearer token)) settings)))
    (is (= {:status 404} (ws/route (head "/calendar.ics/" (bearer token)) settings)))
    (is (= {:status 404} (ws/route (head "/calendar.ics.bak" (bearer token)) settings)))
    (is (= {:status 404} (ws/route (head "/" (bearer token)) settings)))
    (is (= {:status 400} (ws/route nil settings)))))

(defspec the-feed-is-served-to-the-token-and-to-nothing-else 300
  (prop/for-all [path (gen/elements ["/calendar.ics" "/calendar.ics?token=T" "/calendar.ics?x=1&token=T" "/" "/x?token=T"
                                     "/calendar.ics?token=nope" "/calendar.ics?token="])
                 method (gen/elements ["GET" "POST" "HEAD" "PUT"])
                 header (gen/elements [nil token "wrong"])
                 origin (gen/elements [nil "https://phone.example" "https://evil.example"])]
    (let [target (str/replace path "T" token)
          h (head method target (cond-> {} header (merge (bearer header)) origin (assoc "Origin" origin)))
          d (ws/route h settings)
          authorised (and (= "GET" method)
                          (str/starts-with? target "/calendar.ics")
                          (or (= "/calendar.ics" target) (str/starts-with? target "/calendar.ics?"))
                          (or (nil? origin) (= origin "https://phone.example"))
                          (if header (= header token) (str/includes? target (str "token=" token))))]
      (and (map? d)
           (= (boolean (:calendar d)) (boolean authorised))
           ;; The feed is never an upgrade, and an upgrade never the feed.
           (not (and (:calendar d) (:upgrade d)))))))

(defspec the-two-tokens-never-open-each-others-door 200
  ;; For any listener token: the feed's token differs from it, opens the
  ;; feed, and never upgrades to a conversation; the listener's token
  ;; never opens the feed.
  (prop/for-all [t (gen/fmap #(apply str %) (gen/not-empty (gen/vector (gen/elements (map char (range 0x21 0x7f))))))]
    (let [ft (ws/feed-token t)
          s {:token t :allowed-origins []}
          upgrade (fn [tok] (ws/route (head "/" (merge (bearer tok) {"Upgrade" "websocket" "Connection" "Upgrade"
                                                                    "Sec-WebSocket-Version" "13"
                                                                    "Sec-WebSocket-Key" "dGhlIHNhbXBsZSBub25jZQ=="}))
                                      s))]
      (and (not= ft t)
           (re-matches #"[0-9a-f]{64}" ft)
           (= {:calendar true} (ws/route (head (str "/calendar.ics?token=" ft)) s))
           (= {:status 401} (ws/route (head "/calendar.ics" (bearer t)) s))
           (= {:status 401} (upgrade ft))
           (contains? (upgrade t) :upgrade)))))

(deftest the-answer-is-a-calendar-that-is-never-cached
  (let [bs (ws/calendar-response-bytes "BEGIN:VCALENDAR\r\n日\r\n")
        text (String. bs "UTF-8")
        [head body] (str/split text #"\r\n\r\n" 2)]
    (is (str/starts-with? head "HTTP/1.1 200 OK\r\n"))
    (is (str/includes? head "Content-Type: text/calendar; charset=utf-8"))
    (is (str/includes? head "Cache-Control: no-store"))
    (is (str/includes? head (str "Content-Length: " (alength (.getBytes ^String body "UTF-8")))))))

;; --- relay calendar url

(deftest the-command-line-prints-the-address-to-subscribe-to
  ;; Regression (U1): nothing printed the feed's address, and the README
  ;; had it built by hand around the listener's own token.
  (let [dir (str (fs/create-temp-dir {:prefix "relay-calendar-url"}))
        data (str dir "/data")
        write! (fn [settings]
                 (spit (str data "/relay.edn")
                       (pr-str (merge {:timezone "UTC" :default-harness :x
                                       :harnesses {:x {:kind :agentloop :api-key-command ["true"]}}}
                                      settings))))
        relay #(relay.cli/execute (concat ["--data" data] %&))]
    (try
      (log/init! data)
      (write! {:serve {:websocket {:port 8765 :token-command ["/bin/echo" listener-token]}}})
      (binding [relay.cli/*probe* (constantly :ok)]
        (let [{:keys [exit out]} (relay "calendar" "url" "--host" "mac.tail1234.ts.net")]
          (is (= 0 exit))
          (is (= (str "https://mac.tail1234.ts.net:8765/calendar.ics?token=" token) (first out)))
          (is (not (some #(str/includes? % listener-token) out)) "never the listener's own token")))
      (testing "on this machine the listener speaks plain http"
        (binding [relay.cli/*probe* (constantly :ok)]
          (is (= (str "http://127.0.0.1:8765/calendar.ics?token=" token)
                 (first (:out (relay "calendar" "url" "--host" "127.0.0.1")))))))
      (testing "it says when the feed cannot be read"
        (binding [relay.cli/*probe* (constantly :refused)]
          (is (str/includes? (second (:out (relay "calendar" "url" "--host" "mac.tail1234.ts.net")))
                             "relay serve is not running")))
        (binding [relay.cli/*probe* #(if (str/includes? % "127.0.0.1") :ok :refused)]
          (is (= "  tailscale serve --bg --https=8765 http://127.0.0.1:8765"
                 (last (:out (relay "calendar" "url" "--host" "mac.tail1234.ts.net"))))))
        (binding [relay.cli/*probe* (constantly :other)]
          (is (str/includes? (second (:out (relay "calendar" "url" "--host" "h"))) "but not with the calendar"))))
      (testing "without a listener it says what to set up"
        (write! {})
        (is (thrown-with-msg? Exception #"has none yet" (relay "calendar" "url" "--host" "h"))))
      (finally (fs/delete-tree dir)))))

;; --- the service

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort s)))

(defn- port-open? [port]
  (try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] true)
       (catch Exception _ false)))

(defn- get-feed [port & [{:keys [headers query]}]]
  (http/get (str "http://127.0.0.1:" port "/calendar.ics" query)
            {:headers (or headers {}) :throw false}))

(defn- agent-add! [args]
  (inbound/submit! *sys* {:type :calendar/add :actor "clerk" :args args}))

(deftest ^:volley/serial the-service-serves-the-calendar-on-its-listener
  (let [port (free-port)]
    (with-service
      (fn [_]
        (is (wait-for #(port-open? port) 15000) "the listener is up")
        (testing "an empty calendar is a valid feed"
          (let [r (get-feed port {:headers (bearer token)})]
            (is (= 200 (:status r)))
            (is (= "text/calendar; charset=utf-8" (get-in r [:headers "content-type"])))
            (is (= "no-store" (get-in r [:headers "cache-control"])))
            (is (= [] (ics-test/read-events (:body r))))))
        (let [[e] (agent-add! {:title "Dentist, Elm St; bring card" :start "2026-10-20 09:00"
                               :end "2026-10-20 10:00" :description "Line one\nLine two"})
              id (:seq e)]
          (testing "the listener's own token gets nothing"
            (is (= 401 (:status (get-feed port {:query (str "?token=" listener-token)})))))
          (testing "an event the agent wrote is in the feed, by header or by URL"
            (doseq [r [(get-feed port {:headers (bearer token)})
                       (get-feed port {:query (str "?token=" token)})]]
              (is (= 200 (:status r)))
              (let [[ev & more] (ics-test/read-events (:body r))]
                (is (empty? more))
                (is (= [(ics/uid id) "Dentist, Elm St; bring card" "Line one\nLine two" 0 "CONFIRMED"]
                       ((juxt :uid :title :description :sequence :status) ev))))))
          (testing "a change reaches the next request with a higher sequence, and a removal cancels"
            (inbound/submit! *sys* {:type :calendar/update :actor "clerk" :args {:event id :location "Oak St"}})
            (let [[ev] (ics-test/read-events (:body (get-feed port {:headers (bearer token)})))]
              (is (= [1 "Oak St"] ((juxt :sequence :location) ev))))
            (inbound/submit! *sys* {:type :calendar/remove :actor "clerk" :args {:event id}})
            (let [[ev] (ics-test/read-events (:body (get-feed port {:headers (bearer token)})))]
              (is (= [2 "CANCELLED"] ((juxt :sequence :status) ev))))))
        (testing "no token, a wrong one, or a foreign origin is refused with no body"
          (doseq [[headers status] [[{} 401] [(bearer "nope") 401]
                                    [(assoc (bearer token) "Origin" "https://evil.example") 403]]]
            (let [r (get-feed port {:headers headers})]
              (is (= status (:status r)))
              (is (= "" (:body r))))))
        (testing "nothing else is served"
          (is (= 404 (:status (http/get (str "http://127.0.0.1:" port "/") {:headers (bearer token) :throw false}))))
          (is (= 404 (:status (http/post (str "http://127.0.0.1:" port "/calendar.ics") {:headers (bearer token) :throw false})))))
        (testing "the listener is on 127.0.0.1 alone: no other address of this machine answers"
          (let [^java.net.InetAddress a (java.net.InetAddress/getLocalHost)]
            (when-not (.isLoopbackAddress a)
              (is (not (try (with-open [_ (doto (java.net.Socket.) (.connect (java.net.InetSocketAddress. a (int port)) 500))] true)
                            (catch Exception _ false)))
                  "refused on this machine's own address")))))
      nil {:serve {:websocket {:port port :token-command ["/bin/echo" listener-token]}}})))
