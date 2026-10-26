(ns relay.moves
  "Carrying out a files_move call and undoing one: relay.files decides,
  this does it, under the data directory's files lock, and records each
  step in the log through relay.inbound."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [relay.config :as config]
            [relay.core :as core]
            [relay.files :as files]
            [relay.inbound :as inbound])
  (:import (java.nio.channels FileChannel)
           (java.nio.file OpenOption StandardOpenOption)))

(def ^:private jvm-lock (Object.))

(defn- lock!
  "Runs f with the data directory's files lock held, so that two moves or
  undos never run at once."
  [data-dir f]
  (locking jvm-lock
    (let [file (fs/path data-dir "files" ".lock")]
      (fs/create-dirs (fs/parent file))
      (with-open [ch (FileChannel/open file (into-array OpenOption [StandardOpenOption/CREATE
                                                                    StandardOpenOption/WRITE]))
                  _lock (.lock ch)]
        (f)))))

(defn- usage! [msg] (throw (ex-info msg {:relay/usage true})))

(defn move-files!
  "Carries out a files_move call for `actor`, a session holding `labels`.
  Returns {:content :labels}. Refuses the whole call, moving nothing, when
  any move may not be made. The call is recorded before anything moves
  (files/moving) and what it did after (files/moved), so a relay stopped
  between the two leaves a call that says to check by hand."
  [sys actor labels moves & [{:keys [session-wake]}]]
  (lock! (:data-dir sys)
    (fn []
      (let [roots (files/real-roots (:files (config/grants (:config sys) actor)) (:data-dir sys))
            _ (when (empty? roots) (core/reject! :forbidden "You have no folders to organize." [core/private-label]))
            steps (files/plan moves roots)
            submit (fn [type args]
                     (inbound/submit! sys (cond-> {:type type :actor actor :args args :labels labels}
                                            session-wake (assoc :session-wake session-wake))))
            asked (mapv #(select-keys % [:from :to]) steps)
            call (:seq (first (submit :files/start {:moves asked})))
            [done stopped]
            (reduce (fn [[done _] [i {:keys [from to create]}]]
                      (let [made (atom [])]
                        (try (let [fp (files/fingerprint from)]
                               (files/make-dirs! made create)
                               (files/move! from to)
                               [(conj done {:index i :fingerprint fp :created @made}) nil])
                             (catch Exception e
                               (files/remove-empty! @made)
                               (reduced [done (str from ": " (files/why-failed e))])))))
                    [[] nil] (map-indexed vector steps))]
        (submit :files/finish (cond-> {:call call :done done} stopped (assoc :stopped stopped)))
        {:content (files/moved-text call asked done stopped)
         :labels (core/union labels [core/private-label])}))))

(defn undo!
  "Puts back what files_move call `id` moved, for the operator. Returns
  {:ok lines} when it did, or {:refused lines} when anything the call moved
  is gone or changed, or something has taken a moved thing's old place; a
  refused undo touches nothing."
  [sys id]
  (lock! (:data-dir sys)
    (fn []
      (let [c (core/move-call (inbound/current-state sys) id)
            left (core/move-steps-left c)
            paths (fn [{:keys [index]}] ((:moves c) index))
            problems (delay (vec (keep (fn [s] (files/undo-problem (paths s) (:fingerprint s)
                                                                    (files/world-now (paths s))))
                                       left)))]
        (cond
          (nil? c) (usage! (str "There is no move " id ". relay moves lists them."))
          (= :moving (:status c))
          {:refused (into [(str "relay stopped while it made move " id ", so the log does not say which of"
                                " these moved. Check them by hand:")]
                          (map files/move-line (:moves c)))}
          (empty? (:done c)) (usage! (str "Move " id " moved nothing."))
          (empty? left) (usage! (str "Move " id " is already undone."))
          (seq @problems)
          {:refused (into [(str "Move " id " was not undone, and nothing changed:")]
                          (map #(str "  " %) @problems))}
          :else
          (let [[undone stopped]
                (reduce (fn [[undone _] s]
                          (let [{:keys [from to]} (paths s)]
                            (try (files/move! to from) [(conj undone (:index s)) nil]
                                 (catch Exception e (reduced [undone (str to ": " (files/why-failed e))])))))
                        [[] nil] (reverse left))
                undone-set (set undone)
                [removed kept] (files/remove-empty! (mapcat :created (filter #(undone-set (:index %)) left)))]
            (inbound/submit! sys {:type :files/undo :actor core/operator
                                  :args (cond-> {:call id :steps (vec (sort undone)) :removed removed}
                                          stopped (assoc :stopped stopped))
                                  :labels []})
            {:ok (cond-> [(str "Undid move " id ": put back " (count undone) " of " (count left) "."
                               (when (seq removed) (str " Removed " (count removed) " empty folder(s) it had made.")))]
                   (seq kept) (conj (str "Kept " (str/join ", " kept) ": something new is in it."))
                   stopped (conj (str "Stopped at " stopped ". relay undo " id " tries the rest again.")))}))))))

(defn undo-outcome
  "undo! for a client (_relay/undo): {:ok lines}, or {:refused lines}
  with B9's reason when undo is impossible, including no such move or
  nothing left to undo."
  [sys id]
  (try (undo! sys id)
       (catch clojure.lang.ExceptionInfo e
         (if (:relay/usage (ex-data e)) {:refused [(ex-message e)]} (throw e)))))
