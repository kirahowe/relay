(ns relay.render
  "What the relay command shows. Pure: each function takes the data to
  show and returns lines of text, which relay.cli prints."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [relay.core :as core])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)))

(defn local
  "An instant as the operator's local time, or nil."
  [^Instant at zone]
  (when at (.format (.atZone at ^ZoneId zone) (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))))

(defn memo
  "One memo, as the operator's inbox shows it."
  [zone {:keys [id from subject body sent-at]}]
  [(str "\n[" id "] " subject)
   (str "    from " from ", " (local sent-at zone))
   ""
   (str/replace (str body) #"(?m)^" "    ")])

(defn inbox [zone memos]
  (if (empty? memos)
    ["Nothing waiting."]
    (mapcat #(memo zone %) memos)))

(defn- open-memo [zone {:keys [id subject attempts retry-at]}]
  (format "    open  [%d] %s%s" id subject
          (cond-> ""
            (pos? attempts) (str " (" attempts " failed attempts")
            (and (pos? attempts) retry-at) (str ", next " (local retry-at zone))
            (pos? attempts) (str ")"))))

(defn- role-status [state zone id]
  (let [{:keys [title reports-to]} (core/role state id)]
    (concat
     [(format "  %-20s %s%s%s" id title
              (if reports-to (str ", reports to " reports-to) "")
              (if (core/running? state id) "  [running]" ""))]
     (for [{:keys [name schedule]} (core/active-routines state id)]
       (format "    routine %-18s %s" name schedule))
     (map #(open-memo zone %) (core/unread-memos state id))
     (for [{memo-id :id :keys [subject]} (core/dead-memos state id)]
       (format "    dead  [%d] %s (relay retry %s)" memo-id subject id)))))

(defn status
  "Roles with their routines and open work, harnesses, the operator's
  inbox and the latest wakes, as of `now`."
  [state zone ^Instant now & [config]]
  (concat
   ["Roles"]
   (mapcat #(role-status state zone %) (core/active-role-ids state))
   (for [[h {:keys [condition paused-until]}] (sort-by key (:harnesses state))]
     (str "\nHarness " h ": "
          (if (and paused-until (.isAfter ^Instant paused-until now))
            (str condition ", next try " (local paused-until zone))
            "available")))
   [(format "\nOperator's inbox: %d open" (count (core/unread-memos state core/operator)))]
   (when-let [hs (seq (core/open-holds state))]
     [(format "Held tool calls: %d (relay holds)" (count hs))])
   (when-let [qs (seq (filter #(core/quarantined? config %) (filter #(= :unread (:status %)) (vals (:memos state)))))]
     [(format "Quarantined memos: %s (relay release <memo>)" (str/join ", " (sort (map :id qs))))])
   (when-let [recent (seq (take-last 5 (sort-by :id (vals (:wakes state)))))]
     (cons "\nRecent wakes"
           (for [{:keys [id role started-at status outcome crashed]} recent]
             (format "  %-6d %s  %-20s %s" id (local started-at zone) role
                     (if (= :running status) "running"
                         (str outcome (when crashed ", crashed")))))))))

(defn roles [state]
  (for [id (core/active-role-ids state)
        :let [{:keys [title reports-to]} (core/role state id)]]
    (str id ": " title (when reports-to (str ", reports to " reports-to)))))

(defn routines
  "The routines of the given owners, or of every active role."
  [state zone owners]
  (for [owner (or (seq owners) (core/active-role-ids state))
        {:keys [name schedule baseline]} (core/active-routines state owner)]
    (format "%-16s %-18s %s (since %s)" owner name schedule (local baseline zone))))

(defn notes [zone notes]
  (for [{:keys [name text written-at]} notes]
    (str "## " name "  (" (local written-at zone) ")\n\n" text "\n")))

(defn log
  "Events one to a line, the last `tail` of them when given: as JSON, or
  as a summary with the event's data cut to 120 characters."
  [events {:keys [tail json]}]
  (for [e (if tail (take-last tail events) events)]
    (if json
      (json/generate-string e)
      (format "%5d %s %-22s %-12s %s" (:seq e) (:at e) (:type e) (:actor e)
              (let [s (pr-str (:data e))] (subs s 0 (min 120 (count s))))))))

(defn wake-result
  "What `relay wake` says it did."
  [result]
  [(str (name (:result result)) " " (pr-str (dissoc result :result)))])

(defn doc-put [path events]
  [(if (seq events)
     (str "Wrote " path ", version " (:seq (first events)))
     (str path " already says that."))])

(defn hold
  "One held tool call, with its whole input."
  [{:keys [id role tool input session call]}]
  [(str "Hold " id ": " role " wants to use " tool)
   (str "    session " session ", call " call)
   "    input:"
   (str/replace (json/generate-string input {:pretty true}) #"(?m)^" "      ")])

(defn holds [hs]
  (if (empty? hs)
    ["No tool calls are waiting for you."]
    (concat (mapcat #(concat (hold %) [""]) hs)
            ["relay approve <hold> lets one run; relay deny <hold> [why] refuses it."])))
