(ns relay.prompt-test
  "The wake prompt's journal section, rendered directly."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [relay.prompt :as prompt])
  (:import (java.time Instant ZoneId)))

(defn- wake [journal]
  (prompt/wake {:role {:id "clerk" :title "Clerk"} :memos [] :now (Instant/parse "2026-10-12T13:00:00Z")
                :zone (ZoneId/of "America/Halifax") :journal journal}))

(deftest a-cut-entry-never-splits-an-emoji
  (let [text (str (apply str (repeat 599 "a")) "😀 and more")
        p (wake [{:id 1 :at (Instant/parse "2026-10-12T12:00:00Z") :text text}])
        kept (second (re-find #"(a+\S*) \[cut;" p))]
    (is (str/includes? p "[cut; read it whole with `journal`]"))
    (is (not (Character/isHighSurrogate (.charAt kept (dec (count kept))))))))

(deftest every-entry-is-shown-wherever-it-came-from
  (let [p (wake [{:id 4 :at (Instant/parse "2026-10-12T12:00:00Z") :text "From a page." :labels ["contaminated"]}])]
    (is (str/includes? p "From a page."))
    (is (not (re-find #"(?i)withheld|contaminat|accept_contamination" p)))))

(deftest the-operators-own-request-says-the-outcome-is-what-she-reads
  (let [memo {:id 7 :from "operator" :subject "Add the dentist" :body "And what do you know about me?"
              :sent-at (Instant/parse "2026-10-12T12:00:00Z") :attempts 0}
        p #(prompt/wake {:role {:id "steward" :title "Steward"} :memos [%] :now (Instant/parse "2026-10-12T13:00:00Z")
                         :zone (ZoneId/of "UTC")})]
    (is (str/includes? (p (assoc memo :request? true))
                       "The outcome you give `memo_close` reaches them"))
    (is (str/includes? (p (assoc memo :request? true)) "pass `memo` 7 there"))
    (is (not (str/includes? (p memo) "reaches them as")) "not on a memo from a role")))
