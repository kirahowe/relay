(ns relay.daily-note-tick-test
  "The tick makes the daily note once a day at its time, and sends new
  GitHub items from people as one push, in a data directory of its own."
  (:require [babashka.fs :as fs]
            [clojure.edn]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [relay.cli]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [org.httpkit.server :as srv])
  (:import (java.time Instant)))

(def ^:dynamic *dir* nil)

(use-fixtures :each
  (fn [run-test]
    (let [dir (str (fs/create-temp-dir {:prefix "relay-daily-note-test"}))]
      (try (binding [*dir* dir] (run-test))
           (finally (fs/delete-tree dir))))))

(defn- sys-at [t]
  (let [data (str *dir* "/data")]
    (when-not (fs/exists? data) (log/init! data))
    (spit (str data "/relay.edn")
          (pr-str {:timezone "America/Toronto"
                   :default-harness :x :harnesses {:x {:kind :agentloop :api-key-command ["true"]}}
                   :messages {:channels {"phone" {:kind :push :via :file :path (str *dir* "/pushed.log")}}
                              :routes [{:channels ["phone"]}]}
                   :daily-note {:at "07:30" :role "steward"}}))
    (inbound/system data {:clock (constantly (Instant/parse t))})))

(defn- pushed []
  (let [f (str *dir* "/pushed.log")]
    (if (fs/exists? f) (mapv #(json/parse-string % true) (str/split-lines (slurp f))) [])))

(defn- tick! [sys] (dispatch/tick! sys {:spawn! (fn [& _])}))

(defn- submit! [sys actor type args & [labels]]
  (inbound/submit! sys {:type type :actor actor :args args :labels (or labels [])}))

(deftest the-tick-makes-the-note-once-a-day
  (let [sys (sys-at "2026-10-26T11:00:00Z")]
    (submit! sys "operator" :role/add {:id "steward" :title "Steward" :instructions "Read."})
    (submit! sys "steward" :note/write {:name "venues" :text "Eve's hall"} ["contaminated" "mail:eve@example.net"])
    (submit! sys "steward" :digest/write-part {:part "ahead" :items [{:text "Interview, Thu 10:00" :offer "Prep notes?"}]})
    (tick! (sys-at "2026-10-26T11:29:00Z"))
    (is (= [] (pushed)) "not before 07:30 in Toronto")
    (tick! (sys-at "2026-10-26T11:31:00Z"))
    (let [[p & more] (pushed)]
      (is (nil? more))
      (is (= "Daily note for Monday 26 October: 1 change to check" (:title p)))
      (is (str/starts-with? (:body p) "1. New note \"venues\" (1 line added), shaped by a mail")))
    (testing "later that day, nothing more"
      (tick! (sys-at "2026-10-26T16:00:00Z"))
      (is (= 1 (count (pushed)))))
    (testing "the next morning, with nothing new, nothing is sent but the day is recorded"
      (tick! (sys-at "2026-10-27T11:31:00Z"))
      (is (= 1 (count (pushed))))
      (is (= [true false] (map #(get-in % [:data :sent])
                               (filter #(= "digest/composed" (:type %)) (log/read-events (str *dir* "/data")))))))))

(def ^:private issues-json
  (json/generate-string
   (for [[id n user type] [[101 5 "alice" "User"] [102 6 "bob" "User"] [103 7 "dependabot[bot]" "Bot"]]]
     {"id" id "number" n "title" (str "Title " n) "state" "open" "user" {"login" user "type" type}
      "body" "" "html_url" (str "https://github.com/owner/repo/issues/" n) "comments" 0
      "updated_at" "2026-10-26T12:10:00Z"})))

(deftest the-tick-keeps-new-github-items-from-people-for-the-daily-note
  (let [s (srv/run-server (fn [_] {:status 200 :headers {"Content-Type" "application/json"} :body issues-json})
                          {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        base (str "http://127.0.0.1:" (srv/server-port s))
        at (fn [t]
             (let [sys (sys-at t)
                   edn (clojure.edn/read-string (slurp (str *dir* "/data/relay.edn")))]
               (spit (str *dir* "/data/relay.edn")
                     (pr-str (assoc edn :ingest {"repos" {:kind :github :repos ["owner/repo"] :api-url base :to "steward"}})))
               (inbound/system (:data-dir sys) {:clock (constantly (Instant/parse t))})))]
    (try
      (submit! (at "2026-10-26T11:00:00Z") "operator" :role/add {:id "steward" :title "Steward" :instructions "Read."})
      (tick! (at "2026-10-26T12:00:00Z")) ;; the first poll marks where to start
      (is (= [] (pushed)))
      (tick! (at "2026-10-26T12:15:00Z"))
      (is (= [] (pushed)) "nothing about them is urgent")
      (is (= [5 6] (map :number (:github-news (inbound/current-state (at "2026-10-26T12:16:00Z")))))
          "a bot's is not news")
      (testing "the steward still gets the memo, the bot's item too"
        (let [memos (filter #(= "steward" (:to %)) (vals (:memos (inbound/current-state (at "2026-10-26T12:16:00Z")))))]
          (is (= 1 (count memos)))
          (is (str/includes? (:body (first memos)) "dependabot"))))
      (testing "the same items again are no news"
        (tick! (at "2026-10-26T12:30:00Z"))
        (is (= [5 6] (map :number (:github-news (inbound/current-state (at "2026-10-26T12:31:00Z")))))))
      (finally (srv/server-stop! s)))))

(deftest the-command-line-lists-undoes-and-drops
  (let [sys (sys-at "2026-10-26T11:00:00Z")
        data (:data-dir sys)
        relay #(relay.cli/execute (concat ["--data" data] %&))]
    (submit! sys "operator" :role/add {:id "steward" :title "Steward" :instructions "Read."})
    (submit! sys "steward" :note/write {:name "venues" :text "Old Hall"})
    (submit! sys "steward" :note/write {:name "venues" :text "Eve's hall"} ["contaminated" "mail:eve@example.net"])
    (submit! sys "steward" :digest/write-part {:part "ahead" :items [{:text "Dentist, Fri" :key "dentist"}]})
    (submit! sys "dispatcher" :digest/compose {:date "2026-10-26"})
    (let [c (:seq (last (core/recipe-changes (inbound/current-state sys))))
          {:keys [exit out]} (relay "changes")
          venues (first (filter #(str/includes? % "Changed the note") out))]
      (is (= 0 exit))
      (is (str/starts-with? venues "1.  ") "the note's number, the same one a reply and the client use")
      (is (not (some #(str/includes? % (str c)) (butlast out))) "never the log's own number")
      (is (str/includes? venues "Changed the note \"venues\" (1 line added, 1 removed), shaped by a mail from eve@example.net"))
      (is (str/starts-with? (last out) "To undo one: relay revert <n> for item n of the latest daily note"))
      ;; Regression (U1): `relay revert 1` took 1 as the log's number, and
      ;; removed the first role ever added.
      (is (= {:exit 0 :out ["Put the note \"venues\" back as it was."]} (relay "revert" "1")))
      (is (= "Old Hall" (get-in (inbound/current-state sys) [:notes "steward" "venues" :text])))
      (is (= :active (:status (core/role (inbound/current-state sys) "steward"))))
      (let [out (:out (relay "changes"))
            changes (remove #(or (str/blank? %) (str/starts-with? % "To undo one")) out)]
        (is (str/ends-with? (last (butlast changes)) ", undone"))
        (testing "an undo says it cannot itself be undone, and has no handle"
          (is (str/ends-with? (last changes) ", by you, which cannot be undone"))
          (is (not (re-find #"^c\d+ " (last changes))))))
      (is (thrown? Exception (relay "revert" "1")) "once")
      (is (thrown? Exception (relay "revert" "2")) "item 2 is a suggestion, not a change")
      (is (= {:exit 0 :out ["Dropped \"Dentist, Fri\". It will not be suggested again."]} (relay "dismiss" "2")))
      (is (contains? (:dismissed (inbound/current-state sys)) "ahead/dentist"))
      (is (thrown? Exception (relay "revert" "change:3")))
      (testing "a change no note numbered is undone by its handle"
        (let [line (first (filter #(str/includes? % "New note \"venues\"") (:out (relay "changes"))))
              handle (second (re-find #"^(c\d+) " line))]
          (is (some? handle) line)
          (is (= {:exit 0 :out ["Removed the new note \"venues\"."]} (relay "revert" handle)))
          (is (nil? (get-in (inbound/current-state sys) [:notes "steward" "venues"])))
          (is (thrown? Exception (relay "revert" handle)) "once")
          (is (thrown? Exception (relay "revert" "c999")) "a handle relay changes does not list"))))))
