(ns relay.reminders
  "Writing the operator's reminders: the actuator behind reminder_add,
  reminder_edit, reminder_done and reminder_reopen, and the undo of a
  change to one. relay.eventkit decides what to ask and relay.eventkit-run
  runs the binary; this orders the steps and records each in the log
  through relay.inbound, under a lock in the data directory so two calls
  never run at once.

  A write is two records around the binary call, as files_move's are:
  reminders/changing before it, and after it reminders/changed with the
  reminder as it was and as it is, or reminders/failed. A relay stopped
  between them leaves a call that says to check by hand. The reminder lists
  a source names are the only ones a write can touch, in relay first and in
  the binary again (every one is passed as --allow).

  The undo of a change is asked for by a decision (relay.core), which
  cannot call the binary; settle-reverts! does it, from the tick and after
  `relay revert`."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [relay.core :as core]
            [relay.digest :as digest]
            [relay.eventkit :as eventkit]
            [relay.eventkit-run :as eventkit-run]
            [relay.inbound :as inbound])
  (:import (java.nio.channels FileChannel)
           (java.nio.file OpenOption StandardOpenOption)))

(def ^:private jvm-lock (Object.))

(defn- lock!
  "Runs f with the data directory's reminders lock held."
  [data-dir f]
  (locking jvm-lock
    (let [file (fs/path data-dir "reminders" ".lock")]
      (fs/create-dirs (fs/parent file))
      (with-open [ch (FileChannel/open file (into-array OpenOption [StandardOpenOption/CREATE
                                                                    StandardOpenOption/WRITE]))
                  _lock (.lock ch)]
        (f)))))

(defn- done-text [op {:keys [id title list due]}]
  (str (case op "add" "Added" "edit" "Changed" "done" "Marked done" "reopen" "Reopened")
       " the reminder \"" title "\" in the list " (pr-str list)
       (when (seq due) (str ", due " (eventkit/due-text due)))
       " (id " id ")."))

(defn write!
  "Carries out a reminder tool call for `actor`, a session holding
  `labels`, on source `source`. Returns {:content :labels}. Refuses the
  call before the binary is called when it names a list the source does
  not list, or text that will not do (eventkit/plan-write). The call is
  recorded before the binary runs and its outcome after. Throws what the
  binary said when it fails, after recording the failure."
  [sys actor labels source op args & [{:keys [session-wake sources]}]]
  (lock! (:data-dir sys)
    (fn []
      (let [src (get-in (:config sys) [:ingest source])
            _ (when-not (and (= :apple-reminders (:kind src)) (= actor (:to src)))
                (core/reject! :forbidden (str actor " may not change reminders from " (pr-str source) ".")))
            snapshot (:snapshot (core/ingest-record (inbound/current-state sys) source))
            plan (eventkit/plan-write op (:lists src) snapshot labels args)
            _ (when (:error plan) (core/reject! :invalid (:error plan) [core/private-label]))
            submit (fn [type args]
                     (inbound/submit! sys (cond-> {:type type :actor actor :args args :labels labels}
                                            session-wake (assoc :session-wake session-wake)
                                            sources (assoc :sources sources))))
            call (:seq (first (submit :reminders/start {:source source :op op :args (:args plan)})))
            {:keys [before after]} (try (eventkit-run/write! src (:tail plan))
                                        (catch Exception e
                                          (submit :reminders/fail {:call call :error (or (ex-message e) (str (class e)))})
                                          (throw (ex-info (ex-message e) {:relay/labels (core/union labels [core/private-label])}))))]
        (submit :reminders/finish {:call call :before before :after after})
        {:content (done-text op after)
         :labels (core/union labels [core/private-label])}))))

;; --- undoing a change

(defn- settle-one!
  "Settles one asked undo: puts the reminder back, finds it already as the
  undo leaves it, or refuses. Returns the line to say."
  [sys change]
  (let [state (inbound/current-state sys)
        c (core/recipe-change-by-seq state change)
        src (get-in (:config sys) [:ingest (:owner c)])
        after (:after c)
        submit (fn [type args] (inbound/submit! sys {:type type :actor core/dispatcher :args args :labels []}))
        refuse (fn [why] (submit :reminders/revert-refuse {:change change :why why}) why)]
    (cond
      (not= :apple-reminders (:kind src))
      (refuse (str "relay.edn no longer has the reminders source " (:owner c) ", so relay cannot put the reminder back."))
      (not (eventkit/allowed? (:lists src) (:list after)))
      (refuse (str "The list " (pr-str (:list after)) " is no longer one relay may write to, so it leaves the reminder alone."))
      :else
      (let [current (try (eventkit-run/find-reminder src (:list after) (:id after))
                         (catch Exception e ::unreadable))]
        (if (= ::unreadable current)
          (refuse "relay could not read your reminders just now, so nothing was changed. Ask again in a few minutes.")
          (let [plan (eventkit/revert-plan c current)]
            (cond
              (:refuse plan) (refuse (:refuse plan))
              (:noop plan) (do (submit :reminders/revert-done {:change change}) (:noop plan))
              :else
              (let [done (try (eventkit-run/write! src (eventkit/revert-tail plan))
                              (catch Exception e {:error (or (ex-message e) (str (class e)))}))]
                (if (:error done)
                  (refuse (str "relay could not put the reminder back: " (:error done) " Nothing was changed."))
                  (do (submit :reminders/revert-done {:change change :op (:op plan)
                                                      :before (:before done) :after (:after done)})
                      (digest/undone-line c)))))))))))

(defn settle-reverts!
  "Carries out every undo of a reminder change that was asked for and
  not settled yet. Returns the lines to say, one for each. A failure of
  one is its own line and stops no other."
  [sys]
  (let [asked (sort (keys (:reminder-reverts (inbound/current-state sys))))]
    (if (empty? asked)
      []
      (lock! (:data-dir sys)
        (fn []
          (vec (for [change asked
                     ;; Settled meanwhile by another process.
                     :when (contains? (:reminder-reverts (inbound/current-state sys)) change)]
                 (try (settle-one! sys change)
                      (catch Exception e
                        (str "relay could not settle the undo of change " change ": " (or (ex-message e) (str (class e))))))))))))) 
