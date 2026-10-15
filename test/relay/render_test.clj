(ns relay.render-test
  "What the relay command shows, checked as data: no stdout to capture."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cli :as cli]
            [relay.config :as config]
            [relay.core :as core]
            [relay.log :as log]
            [relay.render :as render])
  (:import (java.time Instant ZoneId)))

(def utc (ZoneId/of "UTC"))
(def t0 (Instant/parse "2026-10-15T12:00:00Z"))
(def cfg (config/resolve-config {:timezone "UTC" :default-harness :x :harnesses {:x {:kind :script}}
                                 :notify {:kind :none}}))

(defn- cmd [state type args]
  (first (core/run-command state {:type type :actor core/operator :args args} {:now t0 :config cfg})))

(defn- quick [n property]
  (let [result (tc/quick-check n property)]
    (is (:pass? result) (pr-str (select-keys result [:seed :shrunk :fail])))))

;; --- the log

(def gen-event
  (gen/fmap (fn [[n type actor data]]
              {:seq n :at (str (.plusSeconds t0 n)) :type type :actor actor :data data})
            (gen/tuple gen/nat (gen/elements ["memo/sent" "wake/started" "tick"])
                       (gen/elements ["operator" "dispatcher" "clerk"])
                       (gen/map gen/keyword gen/string-alphanumeric))))

(def the-log-shows-the-last-events-one-per-line
  (prop/for-all [events (gen/vector gen-event 0 30)
                 tail (gen/one-of [(gen/return nil) gen/nat])
                 json? gen/boolean]
    (let [lines (render/log events {:tail tail :json json?})
          shown (if tail (take-last tail events) events)]
      (and (= (count shown) (count lines))
           (not-any? #(str/includes? % "\n") lines)
           (if json?
             (= (map #(update-keys % keyword) shown)
                (map #(json/parse-string % true) lines))
             (every? (fn [[e line]] (str/starts-with? (str/triml line) (str (:seq e) " ")))
                     (map vector shown lines)))))))

(deftest the-log-is-one-line-per-event
  (quick 300 the-log-shows-the-last-events-one-per-line))

;; --- status

(def gen-role-ids
  (gen/fmap #(vec (distinct %))
            (gen/vector (gen/fmap #(str "r" %) (gen/choose 0 30)) 0 8)))

(def status-lists-every-role-once
  (prop/for-all [ids gen-role-ids
                 memos gen/nat
                 woken gen/nat]
    (let [state (reduce #(cmd %1 :role/add {:id %2 :title (str "T " %2) :instructions "x"})
                        core/empty-state ids)
          state (reduce (fn [s i] (cmd s :memo/send {:to (first ids) :subject (str "s" i) :body "b"}))
                        state (when (seq ids) (range (min memos 5))))
          state (reduce (fn [s id] (cmd s :wake/start {:role id :chat true}))
                        state (take woken ids))
          lines (vec (render/status state utc t0))
          headers (filter #(re-matches #"  r\d+ +T r\d+.*" %) lines)
          recent (rest (drop-while #(not= "\nRecent wakes" %) lines))]
      (and (= "Roles" (first lines))
           (= (sort ids) (sort (map #(second (re-find #"^  (\S+)" %)) headers)))
           (= (min woken (count ids)) (count (filter #(str/includes? % "[running]") headers)))
           (<= (count recent) 5)
           (some #{"\nOperator's inbox: 0 open"} lines)))))

(deftest status-shows-every-role-and-at-most-five-wakes
  (quick 200 status-lists-every-role-once))

(deftest a-paused-harness-says-when-it-is-tried-next
  (let [state (assoc core/empty-state :harnesses {"x" {:condition "limited"
                                                       :paused-until (.plusSeconds t0 3600)}
                                                  "y" {:condition "limited"
                                                       :paused-until (.minusSeconds t0 60)}})
        lines (set (render/status state utc t0))]
    (is (lines "\nHarness x: limited, next try 2026-10-15 13:00"))
    (is (lines "\nHarness y: available"))))

;; --- memos, notes and the rest

(deftest an-inbox-indents-every-line-of-a-memo
  (is (= ["Nothing waiting."] (render/inbox utc [])))
  (is (= ["\n[4] Lunch" "    from clerk, 2026-10-15 12:00" "" "    Noon?\n    \n    Or one."]
         (render/inbox utc [{:id 4 :from "clerk" :subject "Lunch" :body "Noon?\n\nOr one." :sent-at t0}]))))

(deftest routines-are-shown-for-one-role-or-all
  (let [state (-> core/empty-state
                  (cmd :role/add {:id "a" :title "A" :instructions "x"})
                  (cmd :role/add {:id "b" :title "B" :instructions "x"})
                  (cmd :routine/write {:owner "a" :name "daily" :schedule "daily 09:00" :instructions "x"})
                  (cmd :routine/write {:owner "b" :name "weekly" :schedule "weekly mon 09:00" :instructions "x"}))]
    (is (= 2 (count (render/routines state utc nil))))
    (is (= [(format "%-16s %-18s %s (since %s)" "b" "weekly" "weekly mon 09:00" "2026-10-15 12:00")]
           (render/routines state utc ["b"])))
    (is (= ["a: A" "b: B"] (render/roles state)))))

(deftest a-wake-and-a-doc-say-what-happened
  (is (= ["idle {}"] (render/wake-result {:result :idle})))
  (is (= ["ran {:wake 3}"] (render/wake-result {:result :ran :wake 3})))
  (is (= ["Wrote a/b, version 9"] (render/doc-put "a/b" [{:seq 9}])))
  (is (= ["a/b already says that."] (render/doc-put "a/b" []))))

;; --- the command returns what it prints

(deftest a-command-returns-its-lines-instead-of-printing-them
  (let [dir (str (fs/create-temp-dir {:prefix "relay-render-test"}))
        data (str dir "/data")]
    (try
      (fs/create-dirs data)
      (spit (str data "/relay.edn") (pr-str {:timezone "UTC" :default-harness :x
                                             :harnesses {:x {:kind :script :command ["true"]}}
                                             :notify {:kind :none}}))
      (log/init! data)
      (let [printed (with-out-str
                      (is (= {:exit 0 :out ["Nothing waiting."]} (cli/execute ["--data" data "inbox"])))
                      (is (= ["Roles" "\nOperator's inbox: 0 open"]
                             (:out (cli/execute ["--data" data "status"]))))
                      (testing "an unknown command shows the usage and fails"
                        (is (= {:exit 1 :out [cli/usage]} (cli/execute ["--data" data "nonsense"])))))]
        (is (= "" printed)))
      (finally (fs/delete-tree dir)))))
