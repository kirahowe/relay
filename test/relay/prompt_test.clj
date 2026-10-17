(ns relay.prompt-test
  "The wake prompt's journal section, rendered directly."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [relay.prompt :as prompt])
  (:import (java.time Instant ZoneId)))

(defn- wake [journal public?]
  (prompt/wake {:role {:id "clerk" :title "Clerk"} :memos [] :now (Instant/parse "2026-10-12T13:00:00Z")
                :zone (ZoneId/of "America/Halifax") :journal journal :public? public?}))

(deftest a-cut-entry-never-splits-an-emoji
  (let [text (str (apply str (repeat 599 "a")) "😀 and more")
        p (wake [{:id 1 :at (Instant/parse "2026-10-12T12:00:00Z") :text text}] false)
        kept (second (re-find #"(a+\S*) \[cut;" p))]
    (is (str/includes? p "[cut; read it whole with `journal`]"))
    (is (not (Character/isHighSurrogate (.charAt kept (dec (count kept))))))))

(deftest a-withheld-contaminated-entry-says-what-reading-it-does
  (let [entry [{:id 4 :at (Instant/parse "2026-10-12T12:00:00Z") :text "x" :withheld :contaminated}]]
    (is (str/includes? (wake entry false) "accept_contamination only if you need it"))
    (is (str/includes? (wake entry true) "makes this session contaminated"))
    (is (not (str/includes? (wake entry true) "accept_contamination")))))
