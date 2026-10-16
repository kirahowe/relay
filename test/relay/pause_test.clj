(ns relay.pause-test
  "The pause file: relay tick does nothing while it exists, and relay status
  says so."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.cli :as cli]
            [relay.core :as core]
            [relay.pause :as pause]
            [relay.render :as render]
            [relay.staging :as staging])
  (:import (java.time Instant)))

(defn- temp [] (str (fs/create-temp-dir {:prefix "relay-pause-test"})))

(deftest pausing-and-resuming-round-trip
  (let [dir (temp) file (str dir "/sub/paused")]
    (try
      (is (nil? (pause/reason file)))
      (pause/pause! file "deploy at noon")
      (is (= "deploy at noon" (pause/reason file)))
      (testing "a file with no text still pauses"
        (spit file "")
        (is (= "no reason given" (pause/reason file))))
      (is (true? (pause/resume! file)))
      (is (nil? (pause/reason file)))
      (is (false? (pause/resume! file)))
      (testing "the default is outside the data directory and Sync"
        (is (= "/Users/op/Library/Application Support/relay/paused" (pause/default-file "/Users/op"))))
      (finally (fs/delete-tree dir)))))

(deftest a-paused-tick-touches-nothing
  (let [dir (temp)
        file (str dir "/paused")
        missing (str dir "/no-such-data")]
    (try
      (testing "unpaused, a tick against a directory with no data fails"
        (is (thrown? clojure.lang.ExceptionInfo
                     (cli/execute ["tick" "--data" missing "--pause-file" file]))))
      (pause/pause! file "deploying")
      (testing "paused, the same tick succeeds without reading it"
        (let [r (cli/execute ["tick" "--data" missing "--pause-file" file])]
          (is (= 0 (:exit r)))
          (is (str/includes? (str/join " " (:out r)) "paused"))
          (is (str/includes? (str/join " " (:out r)) "deploying"))
          (is (not (fs/exists? missing)))))
      (testing "only tick is stopped"
        (is (thrown? clojure.lang.ExceptionInfo
                     (cli/execute ["status" "--data" missing "--pause-file" file]))))
      (finally (fs/delete-tree dir)))))

(deftest status-says-when-relay-is-paused
  (let [dir (temp)
        file (str dir "/paused")
        data (staging/init! {:dir (str dir "/data") :check? false})
        status #(str/join "\n" (:out (cli/execute ["status" "--data" data "--pause-file" file])))]
    (try
      (is (not (str/includes? (status) "paused")))
      (pause/pause! file "deploy in progress")
      (is (str/starts-with? (status) "relay is paused:"))
      (is (str/includes? (status) "deploy in progress"))
      (testing "it says where the file is and how to lift it"
        (is (str/includes? (status) (str "Pause file: " file)))
        (is (str/includes? (status) (str "To lift it by hand: rm '" file "'"))))
      (testing "a pause made by bb deploy also names bb rollback"
        (is (not (str/includes? (status) "bb rollback")))
        (pause/pause! file "bb deploy at 2026-10-17")
        (is (str/includes? (status) "run bb rollback")))
      (testing "the rest of the status is unchanged"
        (let [paused (str/split-lines (status))
              header (count (pause/recovery-lines file))]
          (pause/resume! file)
          (is (= (str/split-lines (status)) (drop (inc header) paused)))))
      (finally (fs/delete-tree dir)))))

(deftest render-status-is-unchanged-without-a-pause
  (let [t (Instant/parse "2026-10-15T12:00:00Z")
        zone (java.time.ZoneId/of "UTC")]
    (is (= (vec (render/status core/empty-state zone t))
           (vec (render/status core/empty-state zone t nil nil))))
    (is (= ["why" "second" ""]
           (take 3 (render/status core/empty-state zone t nil ["why" "second"]))))))

(deftest a-running-tick-leaves-a-marker-that-a-deploy-can-see
  (let [dir (temp) file (str dir "/paused")]
    (try
      (is (= [] (pause/ticks-running file)))
      (testing "inside while-ticking, this process is a running tick"
        (pause/while-ticking file
                             (fn []
                               (is (= [{:pid (.pid (java.lang.ProcessHandle/current))}] (pause/ticks-running file))))))
      (testing "the marker is gone afterwards, even when the tick throws"
        (is (= [] (pause/ticks-running file)))
        (is (thrown? Exception (pause/while-ticking file (fn [] (throw (Exception. "boom"))))))
        (is (= [] (pause/ticks-running file))))
      (testing "a marker left by a dead process is swept"
        (spit (str file ".ticking.999999") "999999 2020-01-01T00:00:00Z\n")
        (spit (str file ".ticking.junk") "not a pid")
        (is (= [] (pause/ticks-running file)))
        (is (empty? (fs/list-dir dir))))
      (finally (fs/delete-tree dir)))))

(deftest a-tick-shows-itself-before-it-looks-for-the-pause
  (let [dir (temp) file (str dir "/paused")
        seen (atom nil)]
    (try
      (with-redefs [pause/reason (fn [f] (reset! seen (pause/ticks-running f)) nil)]
        ;; Unpaused, against nothing: it fails, but only after looking.
        (is (thrown? clojure.lang.ExceptionInfo (cli/execute ["tick" "--data" (str dir "/none") "--pause-file" file]))))
      (is (= 1 (count @seen)) "its marker was there when it looked for the pause file")
      (is (= [] (pause/ticks-running file)) "and gone after")
      (finally (fs/delete-tree dir)))))
