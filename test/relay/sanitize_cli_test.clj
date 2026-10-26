(ns relay.sanitize-cli-test
  "I6 at the command line: what the operator writes is cleaned before the
  log, and what changed is told in plain words."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.cli :as cli]
            [relay.core :as core]
            [relay.inbound :as inbound]
            [relay.log :as log]))

;; --- the command line

(defn- with-data [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-sanitize-test"}))
        data (str dir "/data")]
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :agentloop
                   :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}}))
    (try (f data) (finally (fs/delete-tree dir)))))

(deftest the-command-line-cleans-what-it-writes-and-says-so
  (with-data
    (fn [data]
      (let [run (fn [stdin & args]
                  (binding [cli/*stdin* stdin]
                    (cli/execute (concat ["--data" data] args))))
            events #(log/read-events data)
            has? (fn [s] (some #(str/includes? (pr-str %) s) (events)))]
        (run "be helpful" "add" "clerk" "--title" "Clerk")
        (testing "memo -m"
          (let [r (run "" "memo" "clerk" "-s" "hi\u200b" "-m" "ign\u202eore\u200b")]
            (is (= 0 (:exit r)))
            (is (= ["Removed 2 invisible characters from what you pasted."
                    "Removed 1 text-direction character from what you pasted."]
                   (filter #(str/starts-with? % "Removed") (:err r))))
            (is (not (has? "\u200b")))
            (is (not (has? "\u202e")))))
        (testing "put, from stdin"
          (let [r (run "line\u200b one\u2060\n" "put" "notes.md")]
            (is (= ["Removed 2 invisible characters from what you pasted."] (:err r)))
            (is (= "line one\n" (-> (core/doc-version (inbound/current-state (inbound/system data)) "notes.md" {}) :content)))))
        (testing "fact add"
          (let [r (run "" "fact" "add" "cafe\u0301 opens at nine")]
            (is (= ["Put some combined characters into their standard form."] (:err r)))
            (is (has? "café opens at nine"))))
        (testing "role add: instructions on stdin"
          (let [r (run "be kind\u200d" "add" "poet" "--title" "Poet")]
            (is (= ["Removed 1 invisible character from what you pasted."] (:err r)))))
        (testing "card add"
          (let [r (run "" "card" "add" "note" "--title" "t\u200bitle" "--text" "body\u202e")]
            (is (= ["Removed 1 invisible character from what you pasted."
                    "Removed 1 text-direction character from what you pasted."]
                   (filter #(str/starts-with? % "Removed") (:err r))))
            (is (not (has? "\u202e")))))
        (testing "wake --note"
          (let [r (try (run "" "wake" "clerk" "--note" "x\u200by\u202e")
                       (catch Exception _ nil))
                started (filter #(= "wake/started" (:type %)) (events))]
            (is (seq started) "the wake reached the log")
            (is (some #(= "xy" (get-in % [:data :note])) started)
                (pr-str started))
            (is (not (has? "\u200b")))
            (is (not (has? "\u202e")))
            (when r
              (is (= ["Removed 1 invisible character from what you pasted."
                      "Removed 1 text-direction character from what you pasted."]
                     (filter #(str/starts-with? % "Removed") (:err r)))))))
        (testing "clean text says nothing"
          (let [r (run "" "fact" "add" "plain text")]
            (is (empty? (:err r)))))))))

(deftest a-failed-command-writes-nothing-raw
  (with-data
    (fn [data]
      (is (thrown? clojure.lang.ExceptionInfo
                   (cli/execute ["--data" data "memo" "nobody" "-s" "a\u200b" "-m" "b"])))
      (is (not-any? #(str/includes? (pr-str %) "\u200b") (log/read-events data))))))
