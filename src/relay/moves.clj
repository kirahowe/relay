(ns relay.moves
  "Carrying out a files_move call and undoing one: relay.files decides,
  this does it, under the data directory's files lock, and records each
  step in the log through relay.inbound."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [relay.config :as config]
            [relay.core :as core]
            [relay.files :as files]
            [relay.inbound :as inbound]
            [relay.move-view :as move-view]
            [relay.shell :as shell])
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
  (files/moving) and what it did after (files/moved), with the folders it
  left empty, so a relay stopped between the two leaves a call that says
  to check by hand."
  [sys actor labels moves & [{:keys [session-wake sources]}]]
  (lock! (:data-dir sys)
    (fn []
      (let [roots (files/real-roots (:files (config/grants (:config sys) actor)) (:data-dir sys))
            _ (when (empty? roots) (core/reject! :forbidden "You have no folders to organize." [core/private-label]))
            steps (files/plan moves roots (files/mount-places (shell/settings (:config sys))))
            submit (fn [type args]
                     (inbound/submit! sys (cond-> {:type type :actor actor :args args :labels labels}
                                            session-wake (assoc :session-wake session-wake)
                                            sources (assoc :sources sources))))
            asked (mapv #(select-keys % [:from :to]) steps)
            call (:seq (first (submit :files/start {:moves asked :roots roots})))
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
                    [[] nil] (map-indexed vector steps))
            emptied (files/emptied (map #(:from (asked (:index %))) done) roots)]
        (submit :files/finish (cond-> {:call call :done done}
                                stopped (assoc :stopped stopped)
                                (seq emptied) (assoc :emptied emptied)))
        {:content (files/moved-text call asked done stopped emptied)
         :labels (core/union labels [core/private-label])}))))

(defn shower
  "How the paths of move call `c` read to the operator (move-view/shower)."
  [c]
  (move-view/shower (:roots c) (mapcat (juxt :from :to) (:moves c))))

(defn- plural [n w] (str n " " w (when-not (= 1 n) "s")))

(defn- folders-to-sweep
  "The folders undo of call `id` may remove once the steps `back` (indexes
  of `id`, by this undo) are back, each as [call folder], the call being
  the one that made it: those `id` made for its moves now back, and those
  another call made for moves now back when one of `tos`, the targets put
  back by this undo, is inside it. Never one already removed or kept for
  something new by an earlier undo, or one a later call not undone took
  something out of (its undo needs it)."
  [calls id back tos]
  (let [back-of (fn [k] (cond-> (set (:undone k)) (= id (:id k)) (into back)))
        left-of (fn [k] (remove #((back-of k) (:index %)) (:done k)))]
    (distinct
     (for [k calls
           :let [back (back-of k)
                 gone (into (set (:removed k)) (:kept k))
                 needed (for [l calls :when (> (:id l) (:id k)) s (left-of l)]
                          (:from ((:moves l) (:index s))))]
           s (:done k) :when (back (:index s))
           d (:created s)
           :when (or (= id (:id k)) (some #(files/under? % d) tos))
           :when (not (gone d))
           :when (not (some #(and (not= % d) (files/under? % d)) needed))]
       [(:id k) d]))))

(defn undo!
  "Puts back what files_move call `id` moved, for the operator: every
  move of it that is left, or only those whose indexes are in `only`.
  Each move is checked on its own, and one that cannot go back (it is
  gone or changed, or something has taken its old place) is left where
  it is and named, with why and what to do; the rest go back. One the
  operator moved back by hand counts as put back. Returns {:ok lines
  :summary} when it put back any, or {:refused lines} when it put back
  none, which touches nothing. Paths read relative to the folder the
  operator granted."
  ([sys id] (undo! sys id nil))
  ([sys id only]
   (lock! (:data-dir sys)
     (fn []
       (let [state (inbound/current-state sys)
             c (core/move-call state id)
             calls (core/move-calls state)
             all-left (core/move-steps-left c)
             left (cond->> all-left only (filterv #(contains? only (:index %))))
             paths (fn [{:keys [index]}] ((:moves c) index))
             show (when c (shower c))
             again (str "relay undo " id)
             pending (for [k calls s (core/move-steps-left k)] (:to ((:moves k) (:index s))))
             ;; A later call, not undone, that moved from or to the place
             ;; this one put something, or the place it came from: what
             ;; is there, or what goes back there, is that call's to put
             ;; back first, even when it looks the same. [call path], the
             ;; latest call, since its undo comes first.
             later (fn [{:keys [from to]}]
                     (last (for [k calls :when (> (:id k) id)
                                 st (core/move-steps-left k)
                                 :let [m ((:moves k) (:index st))]
                                 p [to from]
                                 :when (some #(or (files/under? % p) (files/under? p %)) [(:from m) (:to m)])]
                             [(:id k) p])))
             checked (delay (mapv (fn [s]
                                    (if-let [[k p] (later (paths s))]
                                      {:step s
                                       :why (str "Move " k " has moved "
                                                 (if (= p (:to (paths s))) (show p) (str "something at " (show p)))
                                                 " since. relay undo " k " first, then " again " puts "
                                                 (show (:to (paths s))) " back.")}
                                      (let [w (files/world-now (paths s))
                                            why (files/undo-problem (paths s) (:fingerprint s) w show again)]
                                        {:step s
                                         :why why
                                         :by-hand (boolean (and why (files/back-by-hand? (paths s) (:fingerprint s) w pending)))})))
                                  left))]
         (cond
           (nil? c) (usage! (str "There is no move " id ". relay moves lists them."))
           (= :moving (:status c))
           {:refused (into [(str "relay stopped while it made move " id ", so the log does not say which of"
                                 " these moved. Check them by hand:")]
                           (map files/move-line (:moves c)))}
           (empty? (:done c)) (usage! (str "Move " id " moved nothing."))
           (empty? all-left) (usage! (str "Move " id " is already undone."))
           (empty? left) (usage! (str "Those files of move " id " are already back."))
           (every? #(and (:why %) (not (:by-hand %))) @checked)
           {:refused (concat [(str "Move " id " was not undone, and nothing changed:")]
                             (map #(str "  " (:why %)) @checked))}
           :else
           (let [doable (map :step (remove :why @checked))
                 by-hand (map :step (filter :by-hand @checked))
                 skipped (keep #(when-not (:by-hand %) (:why %)) @checked)
                 [moved stopped]
                 (reduce (fn [[moved _] s]
                           (let [{:keys [from to]} (paths s)]
                             (try (files/move! to from) [(conj moved (:index s)) nil]
                                  (catch Exception e (reduced [moved (str (show to) ": " (files/why-failed e))])))))
                         [[] nil] (reverse doable))
                 back (into (set moved) (map :index by-hand))
                 tos (map #(:to (paths {:index %})) back)
                 sweep (folders-to-sweep calls id back tos)
                 [removed kept] (files/remove-empty! (distinct (map second sweep)))
                 ;; Kept for something new, unless a move not undone put
                 ;; what is in it there: then a later undo may sweep it.
                 pending-after (for [k calls s (core/move-steps-left k)
                                     :when (not (and (= id (:id k)) (back (:index s))))]
                                 (:to ((:moves k) (:index s))))
                 kept (vec (remove (fn [d] (some #(files/under? % d) pending-after)) kept))
                 by-folder (fn [ds] (vec (for [[k d] sweep :when (some #{d} ds)] {:call k :path d})))
                 n (count back)]
             (inbound/submit! sys {:type :files/undo :actor core/operator
                                   :args (cond-> {:call id :steps (vec (sort back))
                                                  :removed (by-folder removed)}
                                           (seq kept) (assoc :kept (by-folder kept))
                                           (seq by-hand) (assoc :by-hand (vec (sort (map :index by-hand))))
                                           stopped (assoc :stopped stopped))
                                   :labels []})
             {:summary (str "Put back " n (when (not= n (count left)) (str " of " (count left)))
                            (if (= 1 (count left)) " file." " files."))
              :ok (cond-> [(str "Undid move " id ": put back " n " of " (count left) "."
                                (when (seq removed) (str " Removed " (plural (count removed) "empty folder") " moves had made.")))]
                    (seq by-hand) (into (map #(str "  " (show (:from (paths %))) ": you put it back yourself.") by-hand))
                    (seq skipped) (into (concat [(str "Not put back:")] (map #(str "  " %) skipped)))
                    (seq kept) (conj (str "Kept " (str/join ", " (map show kept)) ": something new is in it."))
                    stopped (conj (str "Stopped at " stopped ". relay undo " id " tries the rest again.")))})))))))

(defn steps-named
  "The indexes of the moves of call `c` that `names` name, each by where
  it went or where it came from, as relay moves shows it (relative to the
  folder) or in full: {:only #{indexes}}, or {:error why} naming the first
  name no move of it has."
  [c names]
  (let [show (shower c)
        done (map :index (:done c))
        index-of (fn [n] (first (for [i done
                                      :let [{:keys [from to]} ((:moves c) i)]
                                      :when (some #{n} [from to (show from) (show to)])]
                                  i)))]
    (if-let [bad (first (remove index-of names))]
      {:error (str "Move " (:id c) " did not move " bad ". relay moves lists what it moved.")}
      {:only (set (map index-of names))})))

(defn moves-text
  "files_moves: the role's own move calls, the newest `n`, each with what
  it moved in full paths and what the operator put back. A role reads it
  to know what it did, as it is now."
  [calls n]
  (if (empty? calls)
    "You have moved no files."
    (str/join "\n\n"
              (for [{:keys [id at moves done undone status stopped emptied]} (take-last n calls)
                    :let [undone (set undone)]]
                (str/join "\n"
                          (concat [(str "Move " id ", " at ": "
                                        (case status
                                          :moving "relay stopped before it recorded what moved; the operator checks by hand"
                                          :moved (str (count done) " of " (count moves) " moved"
                                                      (cond (and (seq done) (= (count undone) (count done))) ", all put back by the operator"
                                                            (seq undone) (str ", " (count undone) " put back by the operator"))
                                                      (when stopped (str ", stopped: " stopped))))
                                        ".")]
                                  (for [{:keys [index]} done :let [{:keys [from to]} (moves index)]]
                                    (str "  " from " → " to (when (undone index) " (put back)")))
                                  (when (seq emptied) [(str "  Left empty: " (str/join ", " emptied))])))))))

(defn undo-outcome
  "undo! for a client (_relay/undo): {:ok lines}, or {:refused lines}
  with the reason when undo is impossible, including no such move or
  nothing left to undo."
  [sys id]
  (try (undo! sys id)
       (catch clojure.lang.ExceptionInfo e
         (if (:relay/usage (ex-data e)) {:refused [(ex-message e)]} (throw e)))))
