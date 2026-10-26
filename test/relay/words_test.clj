(ns relay.words-test
  "The words the operator reads at the command line: plain sentences, no
  raw data, errors on stderr with a failing exit, and nothing that waits
  without saying so."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [relay.cli :as cli]
            [relay.log :as log]
            [relay.render :as render]
            [relay.tools :as tools])
  (:import (java.time ZoneId)))

(defn- with-data [settings f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-words"}))
        data (str dir "/data")]
    (try
      (log/init! data)
      (spit (str data "/relay.edn")
            (pr-str (merge {:timezone "UTC" :default-harness :x
                            :harnesses {:x {:kind :script :command ["true"]}}}
                           settings)))
      (f data)
      (finally (fs/delete-tree dir)))))

(deftest an-empty-relay-says-what-to-do-next
  (with-data {:default-harness :a :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}
              :grants {"scout" {:public true}}}
    (fn [data]
      (let [out (:out (cli/execute ["--data" data "status"]))]
        (is (some #{"  No roles yet. Add the hub, which takes your requests: relay add hub < setup/hub.md"} out))
        (is (some #{"  relay.edn grants scout, but there is no scout role. Add it: relay add scout --public < its instructions"} out))
        (is (not-any? #(str/includes? % "Operator") out))))))

(deftest a-refused-wake-says-why-on-stderr-and-fails
  ;; Regression: it printed invalid {:message "..."} and exited 0.
  (with-data {}
    (fn [data]
      (let [{:keys [exit out err]} (cli/execute ["--data" data "wake" "nobody"])]
        (is (= 1 exit))
        (is (empty? out))
        (is (= ["There is no role called nobody."] err))))))

(deftest reply-without-text-says-it-is-waiting
  ;; Regression: relay reply 16 waited on stdin with no word.
  (with-data {}
    (fn [data]
      (let [err (java.io.StringWriter.)
            r (binding [*in* (java.io.BufferedReader. (java.io.StringReader. ""))
                        *err* err]
                (try (cli/execute ["--data" data "reply" "16"])
                     (catch clojure.lang.ExceptionInfo e {:refused (ex-message e)})))]
        (is (str/includes? (str err) "Type your reply, then press Ctrl-D"))
        (is (str/includes? (str (:refused r)) "There is no message 16."))))))

(deftest a-removed-doc-says-when-not-a-log-number
  (is (= ["a.md was removed on 2026-10-26 09:00."]
         (render/doc (ZoneId/of "UTC") {:path "a.md" :seq 44 :removed true
                                        :at (java.time.Instant/parse "2026-10-26T09:00:00Z")}))))

(deftest a-known-tool-failure-reads-as-what-to-do
  (is (str/starts-with? (tools/plain-failure "api_key_command failed: exit status 44")
                        "The web search key is not working"))
  (is (= "Rate limited." (tools/plain-failure "Rate limited."))))
