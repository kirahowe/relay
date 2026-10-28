(ns relay.eventkit-contract
  "Checks that the real EventKit binary reads the arguments relay builds
  for it. relay's tests run against a fake binary, which takes whatever
  relay sends, so only this shows the two agree. It runs reads only, never
  a write: with access granted, a write would change the operator's own
  reminders. Without access, a read the binary understood answers
  no-access, and one it did not answers usage.

    bb -cp src:resources:test -m relay.eventkit-contract <binary>"
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [relay.eventkit :as eventkit])
  (:import (java.time Instant)))

(defn- answer [bin argv]
  (let [{:keys [out exit]} @(p/process (into [bin] argv) {:in "" :out :string :err :string})]
    {:exit exit :json (try (json/parse-string out true) (catch Exception _ nil))}))

(defn- cases []
  ;; A time with a fraction of a second, as Instant/now nearly always has.
  (let [now (.plusNanos (Instant/now) 123456789)
        {:keys [from to]} (eventkit/window now)]
    [["events in every calendar" (eventkit/events-argv from to nil)]
     ["events in named calendars" (eventkit/events-argv from to ["Home" "Work -- shared"])]
     ["reminders in named lists" (eventkit/reminders-argv ["Personal" "--list"])]]))

(defn -main [bin]
  (let [bad (for [[what argv] (cases)
                  :let [{:keys [json] :as a} (answer bin argv)]
                  :when (or (nil? json) (= "usage" (:code json)))]
              (str what ": " (pr-str argv) " -> " (pr-str a)))]
    (if (seq bad)
      (do (doseq [b bad] (println "FAIL" b)) (System/exit 1))
      (println "ok: the binary reads every argument list relay builds for a read"))))
