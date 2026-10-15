(ns relay.notify-test
  "Pushing to an ntfy server, here a stand-in on loopback."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as http]
            [relay.config :as config]
            [relay.notify :as notify]))

(defn- with-ntfy [f]
  (let [seen (atom [])
        server (http/run-server (fn [req]
                                  (swap! seen conj {:headers (:headers req)
                                                    :body (json/parse-string (slurp (:body req)) true)})
                                  {:status 200 :body "{}"})
                                {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (try (f (str "http://127.0.0.1:" (http/server-port server)) seen)
         (finally (http/server-stop! server)))))

(deftest ntfy-sends-a-bearer-token-from-a-command-or-a-file
  (with-ntfy
    (fn [url seen]
      (let [dir (str (fs/create-temp-dir {:prefix "relay-notify"}))
            file (str dir "/token")]
        (spit file "tk_from_file\n")
        (notify/push! {:kind :ntfy :url url :topic "t1"} {:title "a" :body "b"})
        (notify/push! {:kind :ntfy :url url :topic "t1" :token-command ["printf" "tk_from_command"]}
                      {:title "a" :body "b"})
        (notify/push! {:kind :ntfy :url url :topic "t1" :token-file file} {:title "a" :body "b"})
        (is (= [nil "Bearer tk_from_command" "Bearer tk_from_file"]
               (map #(get-in % [:headers "authorization"]) @seen)))
        (is (= "t1" (get-in (first @seen) [:body :topic])))
        (testing "a token command that fails, or prints nothing, sends nothing"
          (is (thrown? Exception (notify/push! {:kind :ntfy :url url :topic "t" :token-command ["false"]}
                                               {:title "a" :body "b"})))
          (is (thrown? Exception (notify/push! {:kind :ntfy :url url :topic "t" :token-command ["true"]}
                                               {:title "a" :body "b"})))
          (is (= 3 (count @seen))))
        (fs/delete-tree dir)))))

(deftest the-token-settings-are-checked
  (let [base {:timezone "UTC" :default-harness :a
              :harnesses {:a {:kind :agentloop :api-key-command ["true"]}}}
        problem (fn [notify] (try (config/resolve-config (assoc base :notify notify)) nil
                                  (catch clojure.lang.ExceptionInfo e (ex-message e))))
        ntfy {:kind :ntfy :url "http://x" :topic "t"}]
    (is (nil? (problem (assoc ntfy :token-command ["cat" "/x"]))))
    (is (nil? (problem (assoc ntfy :token-file "/x"))))
    (is (str/includes? (problem (assoc ntfy :token-command "cat /x")) ":token-command"))
    (is (str/includes? (problem (assoc ntfy :token-file "")) ":token-file"))
    (is (str/includes? (problem (assoc ntfy :token-file "/x" :token-command ["cat"])) "not both"))))
