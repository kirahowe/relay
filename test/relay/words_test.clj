(ns relay.words-test
  "The words the operator reads at the command line: plain sentences, no
  raw data, errors on stderr with a failing exit, and nothing that waits
  without saying so."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [relay.cli :as cli]
            [relay.log :as log]
            [relay.render :as render])
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
        ;; Regression (U1b review): two hints that overlapped, and a
        ;; setup/ path that worked only from the repository.
        (is (= ["Roles" "  No roles yet. relay init adds your steward."] (take 2 out)) "one hint")
        (is (not-any? #(str/includes? % "grants scout") out) "not a second one about the same thing")
        (is (not-any? #(str/includes? % "setup/") out))
        (is (every? #(<= (count %) 60) out) (pr-str out))
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
