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
            [relay.inbound :as inbound]
            [relay.text :as text])
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

(def not-through
  "What a role is told when a write did not go through."
  (str "That did not go through. The action is cancelled and may be retried later. Read your reminders again first:"
       " a failed call may still have partly happened, and you should not add the same reminder twice."))

(defn write!
  "Carries out a reminder tool call for `actor`, a session holding
  `labels`, on source `source`. Returns {:content :labels}. Refuses the
  call before the binary is called when it names a list the source does
  not list, or text that will not do (eventkit/plan-write). The call is
  recorded before the binary runs (its words join the outside set at once).
  relay records a change only from a call the binary reports as done, from the
  binary's own before and after images and only the fields the call asked
  for (relay.eventkit/attribute). Any other outcome (an error, a timeout, a
  crash, no JSON) means the action is cancelled: reminders/failed and nothing
  else, and the role is told it did not go through."
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
            fail! (fn [reason]
                    (submit :reminders/fail {:call call :error reason})
                    (throw (ex-info not-through {:relay/labels (core/union labels [core/private-label])})))
            done (try {:ok (eventkit-run/write! src (:tail plan))}
                      (catch Exception e {:error (or (ex-message e) (str (class e)))}))]
        (when (:error done) (fail! (:error done)))
        (let [o (eventkit/attribute {:op op :args (:args plan)} (get-in done [:ok :before]) (get-in done [:ok :after]))]
          (if (= :changed (:status o))
            (do (submit :reminders/finish {:call call :before (:before o) :after (:after o)})
                (let [shown (core/shown-provenance (inbound/current-state sys) [(:after o)])]
                  {:content (done-text op (:after o))
                   :labels (core/union labels [core/private-label] (:labels shown))
                   :sources (:sources shown)}))
            (fail! "the program's answer did not hold what was asked for")))))))

;; --- undoing a change

(defn- find-anywhere
  "The reminder `id` as the lists relay may use hold it now, and the source
  that reads its list: [source-name source reminder], nil if there is none,
  or ::unreadable. Whichever source holds it now: an undo does not depend on
  where the reminder was when relay changed it."
  [config id first-source]
  (let [sources (sort-by (fn [[n _]] (if (= n first-source) "" n))
                         (filter #(= :apple-reminders (:kind (val %))) (:ingest config)))]
    (try (some (fn [[n src]]
                 (when-let [r (some #(when (= id (:key %)) (:reminder %)) (:items (eventkit-run/fetch-reminders src)))]
                   [n src r]))
               sources)
         (catch Exception _ ::unreadable))))

(def undo-cancelled "relay could not undo that just now; ask again later.")

(defn- settle-one!
  "Settles one asked undo, field by field (relay.eventkit/revert-plan):
  reads the reminder fresh and compares, then writes back the fields the
  change set that still hold what it left and whose earlier value may be
  written. It records the undo only from writes the binary reports as done,
  from its own images and only the fields restored; any other outcome cancels
  the undo, which is refused so that the operator hears it and the change stays
  not undone. Returns the line to say."
  [sys change]
  (let [state (inbound/current-state sys)
        c (core/recipe-change-by-seq state change)
        shown #(:labels (core/shown-provenance state (remove nil? [(:before c) (:after c)])))
        submit (fn [type args] (inbound/submit! sys {:type type :actor core/dispatcher :args args :labels []}))
        ;; What the line quotes of the reminder's words is shown as the
        ;; set says, not as the change record's labels do.
        said (fn [line] (text/for-operator (shown) line))
        refuse (fn [why] (submit :reminders/revert-refuse {:change change :why why}) (said why))
        found (find-anywhere (:config sys) (get-in c [:after :id]) (:owner c))]
    (cond
      (= ::unreadable found)
      (refuse "relay could not read your reminders just now, so nothing was changed. Ask again in a few minutes.")
      :else
      (let [[_ src current] found
            plan (eventkit/revert-plan c current {:outside (core/reminder-outside state) :lists (:lists src)})]
        (cond
          (:refuse plan) (refuse (:refuse plan))
          (:noop plan) (let [lines (vec (cons (:noop plan) (:left plan)))]
                         (submit :reminders/revert-done {:change change :lines lines :partial (boolean (seq (:left plan)))})
                         (said (str/join " " lines)))
          :else
          (let [restores (into {} (mapcat (fn [{:keys [op set]}]
                                            (case op "edit" set "done" {:completed true} "reopen" {:completed false}))
                                          (:steps plan)))
                op (if (some #(= "edit" (:op %)) (:steps plan)) "edit" (:op (first (:steps plan))))
                done (try (reduce (fn [acc step]
                                    (let [r (eventkit-run/write! src (eventkit/revert-tail step))]
                                      (-> acc (update :before #(or % (:before r))) (assoc :after (:after r)))))
                                  {} (:steps plan))
                          (catch Exception e {:error (or (ex-message e) (str (class e)))}))
                o (when-not (:error done) (eventkit/attribute-fields (:before done) restores (:after done)))]
            (if (= :changed (:status o))
              (let [short (remove (set (:confirmed o)) (keys restores))
                    lines (cond-> (vec (cons (:summary plan) (:left plan)))
                            (seq short) (conj (str "The program did not report the " (str/join ", " (map name short)) " put back.")))
                    partial (boolean (or (seq (:left plan)) (seq short)))]
                (submit :reminders/revert-done {:change change :op op :before (:before o) :after (:after o)
                                                :lines lines :partial partial})
                (said (str/join " " lines)))
              (refuse undo-cancelled))))))))

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
