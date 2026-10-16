(ns relay.conversation-prompt-test
  "What a conversation's system prompt says about time."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.config :as config]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.prompt :as prompt])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(def now (Instant/parse "2026-10-16T17:05:12Z")) ; Friday 14:05 in Halifax

(def cfg (config/resolve-config {:timezone "America/Halifax"
                                 :default-harness :agentloop
                                 :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"]}}
                                 :notify {:kind :none}}))

(defn- conversation-prompt [config now]
  (let [[state _] (core/run-command core/empty-state
                                    {:type :role/add :actor "operator" :labels []
                                     :args {:id "clerk" :title "Clerk" :instructions "File things."}}
                                    {:now now :config config})
        sys {:data-dir "/nowhere" :config config}
        inputs {:shared "Shared." :operator-additions nil :now now :relay-bin "relay" :data-path "/nowhere"}
        wake {:id 1 :role "clerk" :harness "agentloop" :chat true :note dispatch/conversation-note
              :memos [] :resolves []}]
    (:system-prompt (dispatch/conversation-request sys state wake inputs))))

(deftest a-conversation-is-told-when-it-started-in-the-operators-zone
  (let [text (conversation-prompt cfg now)]
    (is (str/includes? text "This conversation started on Friday 16 October 2026 at 14:05, America/Halifax time (UTC-03:00)."))
    (is (str/includes? text "`clock` tool"))
    (is (str/includes? text "any date arithmetic")))
  (testing "the zone and the weekday follow the configuration and the date"
    (let [tokyo (config/resolve-config (assoc cfg :timezone "Asia/Tokyo"))
          text (conversation-prompt tokyo now)]
      (is (str/includes? text "on Saturday 17 October 2026 at 02:05, Asia/Tokyo time (UTC+09:00)."))
      (is (not (str/includes? text "Halifax")))))
  (testing "a wake's system prompt does not carry it: its prompt names the time"
    (is (not (str/includes? (prompt/instructions {:shared "Shared."} {:id "a" :title "A" :instructions "x"})
                            "This conversation started")))))

(defspec the-start-line-is-the-start-in-the-zone 200
  (prop/for-all [secs (gen/choose 1577836800 2209075200)
                 zone (gen/elements ["UTC" "America/Halifax" "Asia/Kolkata" "Australia/Lord_Howe" "Pacific/Chatham"])]
    (let [at (Instant/ofEpochSecond secs)
          z (ZoneId/of zone)
          line (prompt/conversation-start at z)
          local (.atZone at z)]
      (and (str/includes? line (str zone " time"))
           (str/includes? line (format "%02d:%02d, " (.getHour local) (.getMinute local)))
           (str/includes? line (str (.getDayOfMonth local) " "))
           (str/includes? line (.format local (DateTimeFormatter/ofPattern "EEEE d MMMM yyyy" Locale/ENGLISH)))))))
