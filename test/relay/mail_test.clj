(ns relay.mail-test
  "The email channel end to end: tell_operator, a memo to the operator
  and a reply, each delivered to a mailpit this test starts on free
  ports on 127.0.0.1 and reads back through mailpit's API. Each test
  stops the mailpit it started, by its PID."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.core :as core]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.test-support :as support]
            [relay.tools :as tools]))

(def agent-address "agent@example.org")
(def operator-address "kira@example.org")

;; mailpit's login for the agent: bcrypt of "y".
(def auth-line "agent:$2y$05$IuLwiYwjeXWiUPJyVXowXeoLZVGDbayvoIaeUgLT6vyut.5/8UQOe")

(defn- mailpit-bin []
  (or (some-> (fs/which "mailpit") str)
      (when (fs/executable? "/opt/homebrew/bin/mailpit") "/opt/homebrew/bin/mailpit")))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort s)))

(defn- api [base path]
  (let [r (http/get (str base path) {:throw false :timeout 5000})]
    (when (= 200 (:status r)) (json/parse-string (:body r)))))

(defn- with-mailpit
  "Runs (f {:smtp port :api base-url}) with a mailpit of its own, in a
  directory of its own, its database in memory, and stops it by PID."
  [f]
  (let [bin (mailpit-bin)]
    (is bin "mailpit is needed for this test: brew install mailpit")
    (when bin
      (let [dir (str (fs/create-temp-dir {:prefix "relay-mailpit"}))
            auth (str dir "/auth")
            smtp (free-port)
            http-port (free-port)
            base (str "http://127.0.0.1:" http-port)
            _ (spit auth (str auth-line "\n"))
            proc (p/process {:dir dir :out :discard :err :discard}
                            bin "--smtp" (str "127.0.0.1:" smtp) "--listen" (str "127.0.0.1:" http-port)
                            "--database" ":memory:" "--quiet" "--disable-version-check" "--smtp-disable-rdns"
                            "--smtp-auth-file" auth "--smtp-auth-allow-insecure")
            pid (.pid ^Process (:proc proc))]
        (try
          (loop [n 0]
            (when (and (< n 100) (nil? (try (api base "/api/v1/info") (catch Exception _ nil))))
              (Thread/sleep 100)
              (recur (inc n))))
          (is (api base "/api/v1/info") "mailpit answered")
          (f {:smtp smtp :base base})
          (finally
            ;; By the PID this test started, and nothing else.
            (when (.isAlive ^Process (:proc proc))
              (p/shell {:continue true :out :string :err :string} "kill" (str pid))
              (when-not (.waitFor ^Process (:proc proc) 5 java.util.concurrent.TimeUnit/SECONDS)
                (.destroyForcibly ^Process (:proc proc))))
            (fs/delete-tree dir)))))))

(defn- messages-at [base]
  (get (api base "/api/v1/messages") "messages"))

(defn- wait-for [base n]
  (loop [i 0]
    (let [ms (messages-at base)]
      (if (or (>= (count ms) n) (> i 100)) ms (do (Thread/sleep 50) (recur (inc i)))))))

(defn- system [dir smtp password]
  (let [data (str dir "/data")]
    (when-not (fs/exists? data) (log/init! data))
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :a
                   :harnesses {:a {:kind :agentloop :api-key-command ["true"]}}
                   :messages {:channels {"mail" {:kind :email :url (str "smtp://127.0.0.1:" smtp)
                                                 :from agent-address :to operator-address
                                                 :user "agent" :credentials-command ["printf" password]}}
                              :routes [{:channels ["mail"]}]}}))
    (inbound/system data)))

(defn- clerk-at-work! [sys]
  (inbound/submit! sys {:type :role/add :actor core/operator :args {:id "clerk" :title "Clerk" :instructions "x"}})
  (inbound/submit! sys {:type :memo/send :actor core/operator :args {:to "clerk" :subject "Draft it" :body "The concert email."}})
  (inbound/submit! sys {:type :wake/start :actor core/dispatcher :args {:role "clerk"}})
  (:id (support/calling-wake (inbound/current-state sys) "clerk")))

(deftest ^:volley/serial tell-operator-mail-reaches-only-the-operator
  (with-mailpit
    (fn [{:keys [smtp base]}]
      (let [dir (str (fs/create-temp-dir {:prefix "relay-mail-test"}))
            sys (system dir smtp "y")
            wake (clerk-at-work! sys)
            call (fn [args] (tools/call! sys "clerk" "tell_operator" args ["private"] "c1" wake))
            refused (call {:subject "Draft ready\r\nBcc: eve@example.net" :body "x"})
            first-call (call {:subject "Draft ready: spring concert email\rBcc: eve@example.net\u2028"
                              :body "It is in your Hear Here drafts. [[doc:hear-here/style.md]]\n\nCc: eve@example.net"
                              :items ["[Venue]" "[Date of the concert]"]})]
        (is (str/includes? (str (:error refused)) "one line") "a subject with a line break is refused")
        (is (str/starts-with? (str (:ok first-call)) "Told the operator") (pr-str first-call))
        (let [[m] (wait-for base 1)
              full (api base (str "/api/v1/message/" (get m "ID")))
              headers (api base (str "/api/v1/message/" (get m "ID") "/headers"))]
          (testing "one recipient, the configured one, from the agent's own address"
            (is (= [{"Name" "" "Address" operator-address}] (get full "To")))
            (is (= {"Name" "" "Address" agent-address} (get full "From")))
            (is (empty? (get full "Cc")))
            (is (empty? (get full "Bcc")))
            (is (= agent-address (get full "ReturnPath"))))
          (testing "the model's subject is one line, and nothing it wrote became a header"
            (is (= "Draft ready: spring concert email Bcc: eve@example.net" (get full "Subject")))
            (is (= #{"Content-Transfer-Encoding" "Content-Type" "Date" "From" "Message-Id" "Mime-Version"
                     "Received" "Return-Path" "Subject" "To"}
                   (set (keys headers)))))
          (testing "text only, as the operator is shown it"
            (is (= ["text/plain; charset=UTF-8"] (get headers "Content-Type")))
            (is (= "" (get full "HTML")))
            (is (empty? (get full "Attachments")))
            (is (= (str "It is in your Hear Here drafts. [[doc:hear-here/style.md]]\r\n\r\nCc: eve@example.net\r\n\r\n"
                        "- [Venue]\r\n- [Date of the concert]\r\n")
                   (get full "Text"))))
          (testing "the log records it delivered"
            (let [msg (last (sort-by :id (vals (:messages (inbound/current-state sys)))))]
              (is (= :delivered (get-in msg [:deliveries "mail" :status])))
              (is (= (str "relay." (:id msg) ".") (subs (get full "MessageID") 0 (count (str "relay." (:id msg) ".")))))
              (testing "and a reply threads to it"
                (call {:subject "Re: Draft ready" :body "The venue is the Old Hall." :in_reply_to (:id msg)})
                (let [ms (wait-for base 2)
                      reply (first (filter #(= "Re: Draft ready" (get % "Subject")) ms))
                      rh (api base (str "/api/v1/message/" (get reply "ID") "/headers"))]
                  (is (= [(str "<" (get full "MessageID") ">")] (get rh "In-Reply-To")))
                  (is (= [(str "<" (get full "MessageID") ">")] (get rh "References"))))))))
        (testing "a memo to the operator arrives the same way"
          (inbound/submit! sys {:type :memo/send :actor "clerk" :session-wake wake :labels ["private"]
                                :args {:to "operator" :subject "Which venue?" :body "Old Hall or the Annex?"}})
          (let [ms (wait-for base 3)
                memo-mail (first (filter #(= "Which venue?" (get % "Subject")) ms))]
            (is (= [{"Name" "" "Address" operator-address}] (get memo-mail "To")))))
        (testing "a login the server refuses is recorded, and tried again later"
          (let [sys (system dir smtp "wrong")]
            (inbound/submit! sys {:type :memo/send :actor "clerk" :session-wake wake :labels ["private"]
                                  :args {:to "operator" :subject "Never arrives" :body "x"}})
            (let [msg (last (sort-by :id (vals (:messages (inbound/current-state sys)))))]
              (is (= :failed (get-in msg [:deliveries "mail" :status])))
              (is (str/includes? (get-in msg [:deliveries "mail" :error]) "did not take it"))
              (is (some? (get-in msg [:deliveries "mail" :retry-at])))
              (is (= 3 (count (messages-at base))) "nothing more arrived"))))
        (fs/delete-tree dir)))))
