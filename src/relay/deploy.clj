(ns relay.deploy
  "The decisions behind `bb deploy` and `bb rollback`, as pure functions of
  what was observed. relay.deploy-flow runs a deploy or rollback on them,
  and relay.deploy-run does what it asks.

  A checkout is described by an observation:

    {:path \"/path\"   :exists? true   :jj? true
     :wc      {:commit c :change x :empty? true :conflict? false}   ; @
     :parents [{:commit c :change x} ...]                           ; @-
     :target  {:commit c :change x}}                                ; the bookmark, or nil

  Deploying moves each checkout's working copy onto the bookmark's commit
  (main), so the next tick runs the new code. Commits are named by id from
  the first observation to the move, so what is tested and confirmed is what
  is installed. A checkout whose working copy holds changes, or sits on a
  merge, is never touched."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def repo-keys
  "The checkouts, in the order a deploy moves them: what relay runs on
  first, relay last. A rollback goes the other way."
  [:agentloop :relay-tui :relay])

(def builds
  "How each checkout is built after it moves. relay is a script and needs
  none."
  {:agentloop ["make" "build"]
   :relay-tui ["make" "build"]})

;; --- reading what jj says

(def template
  "The jj template that makes a commit's line, read by parse-commit."
  "commit_id ++ \" \" ++ change_id ++ \" \" ++ if(empty, \"empty\", \"changed\") ++ \" \" ++ if(conflict, \"conflict\", \"clean\") ++ \"\\n\"")

(defn parse-commit
  "A commit from the line `template` makes, or nil."
  [line]
  (when-let [[_ commit change empty conflict] (re-matches #"([0-9a-f]+) ([a-z]+) (empty|changed) (conflict|clean)\s*" (str line))]
    {:commit commit :change change :empty? (= "empty" empty) :conflict? (= "conflict" conflict)}))

(defn parse-commits
  "Every commit in jj's output, in order."
  [text]
  (vec (keep parse-commit (str/split-lines (str text)))))

(defn ids [commit] (select-keys commit [:commit :change]))

(defn same-commit? [a b]
  (and (some? (:commit a)) (= (:commit a) (:commit b))))

(defn short-id [commit] (subs (str (:commit commit)) 0 (min 12 (count (str (:commit commit))))))

(defn sole-parent
  "The one parent of the working copy, or nil when it has none or several."
  [{:keys [parents]}]
  (when (= 1 (count parents)) (first parents)))

;; --- deciding

(defn- checkout-problems
  "Why a checkout cannot be moved, as lines."
  [repo {:keys [path exists? jj? wc parents error]}]
  (cond
    (not exists?) [(str (name repo) ": " path " does not exist.")]
    (not jj?) [(str (name repo) ": " path " is not a jj repository.")]
    error [(str (name repo) ": jj could not read it (" error "). If it says the working copy is stale, run "
                "`jj workspace update-stale` in " path ".")]
    (:conflict? wc) [(str (name repo) ": its working copy is in conflict.")]
    (not (:empty? wc))
    [(str (name repo) ": its working copy has changes (" (:change wc) "). Commit or abandon them first; "
          "deploy only moves an empty working copy.")]
    (> (count parents) 1)
    [(str (name repo) ": its working copy is a merge of " (str/join ", " (map short-id parents))
          ". Move it onto one commit first; a merge cannot be put back by a rollback.")]
    (empty? parents) [(str (name repo) ": its working copy has no parent commit.")]
    :else []))

(defn plan-deploy
  "What to do to deploy the bookmark to every checkout. Returns
  {:problems [...]} when something stands in the way, else {:steps [{:repo
  :path :observed :from :to :moves?}]}, in repo-keys order. :observed is
  what the plan was made from, and :to is the commit to move to, by id.
  Pure."
  [{:keys [observed bookmark]}]
  (let [problems (vec (mapcat (fn [r] (let [o (get observed r)]
                                        (or (seq (checkout-problems r o))
                                            (when-not (:target o)
                                              [(str (name r) ": it has no " bookmark " bookmark.")]))))
                              repo-keys))]
    (if (seq problems)
      {:problems problems}
      {:steps (vec (for [r repo-keys
                         :let [{:keys [path target] :as o} (get observed r)
                               parent (sole-parent o)]]
                     {:repo r :path path :observed o :from (ids parent) :to (ids target)
                      :moves? (not (same-commit? parent target))
                      :build (builds r)}))})))

(defn observation-changes
  "How a checkout now differs from the one a plan was made from, as lines.
  Empty when it is the same: the same working copy commit (so no edit
  since), the same parents and the same bookmark target. An observation
  that could not be read is a difference."
  [repo planned now]
  (let [commit-ids #(mapv :commit %)]
    (vec (concat
          (when-not (and (:exists? now) (:jj? now))
            [(str (name repo) " is no longer a jj checkout.")])
          (when (:error now)
            [(str (name repo) " could not be read: " (:error now) ".")])
          (when (and (:jj? now) (not (:error now)))
            (concat
             (when (not= (:commit (:wc planned)) (:commit (:wc now)))
               [(str (name repo) "'s working copy changed from " (short-id (:wc planned)) " to " (short-id (:wc now)) ".")])
             (when (not= (commit-ids (:parents planned)) (commit-ids (:parents now)))
               [(str (name repo) " is now on " (str/join ", " (map short-id (:parents now)))
                     ", not " (str/join ", " (map short-id (:parents planned))) ".")])
             (when (and (:target planned) (not (same-commit? (:target planned) (:target now))))
               [(str (name repo) "'s bookmark moved from " (short-id (:target planned)) " to "
                     (short-id (:target now)) " after it was tested.")])))))))

;; --- rollback

(defn latest-per-id
  "The records with only the last line of each id, in the order those lines
  were written. A deploy writes its record before it starts and again as
  each checkout moves, so the last line is the whole story."
  [records]
  (let [last-at (reduce (fn [m [i r]] (assoc m (:id r) i)) {} (map-indexed vector records))]
    (vec (keep-indexed (fn [i r] (when (= i (last-at (:id r))) r)) records))))

(defn touched?
  "Whether a record shows a checkout moving, moved, being built or built."
  [record]
  (boolean (some #{:moving :moved :building :built} (map :state (vals (:repos record))))))

(defn moved?
  "Whether a record's run moved, or tried to move, any checkout."
  [record]
  (boolean (some (fn [[_ m]]
                   (or (= :moving (:state m))
                       (and (:new m) (:previous m) (not (same-commit? (:new m) (:previous m))))))
                 (:repos record))))

(defn undoable?
  "Whether there is something to undo in a deploy's record: it moved a
  checkout, or it touched one and did not finish (it failed, or the process
  was killed during a rebuild that moved nothing, which left relay paused).
  A deploy that was refused at its first look, or rebuilt in place and
  finished, is not."
  [record]
  (boolean (or (moved? record)
               (and (touched? record) (not= :ok (:status record))))))

(defn rollback-source
  "What a rollback does, as {:record deploy} to undo, {:lift-only run} or
  {:problem text}.

  Every run writes its record before it pauses relay, so the newest record
  tells whether a pause can be stale. When the newest run began and never
  touched anything (it was killed while waiting for wakes, say), there is
  nothing to move, and a rollback only lifts the pause: undoing an older,
  good deploy would be wrong. A rollback that found relay already paused
  says so in its record, since a pause it did not make is not its to lift.
  If relay is paused (`paused?`), the newest such run counts whatever its
  status: the stop that closed it as failed may have come just before the
  one that lifts the pause.

  Otherwise it is the latest deploy with something to undo that no finished
  rollback has undone. A deploy that moved nothing but failed is undone by
  rebuilding in place."
  ([records] (rollback-source records false))
  ([records paused?]
  (let [records (latest-per-id records)
        newest (last records)
        undone? (fn [d] (some #(and (= :rollback (:kind %)) (= (:id d) (:of %)) (= :ok (:status %))) records))
        candidates (filter #(and (= :deploy (:kind %)) (undoable? %)) records)
        open (last (remove undone? candidates))]
    (cond
      (and newest (not (touched? newest)) (not (:inherited newest))
           (or (= :started (:status newest)) paused?))
      {:lift-only newest}
      open {:record open}
      (seq candidates)
      {:problem (str "The deploy of " (:at (last candidates)) " was already rolled back. There is nothing to roll back; deploy first.")}
      :else
      {:problem "There is no deploy record that moved a checkout, or failed after touching one, to roll back from."}))))

(defn plan-rollback
  "What to do to put each checkout back where the deploy in `record` found
  it. Each checkout must be the one the record names and must be where the
  record says the deploy left it (or still where it found it, for a deploy
  or rollback that stopped partway). Pure; the result is that of
  plan-deploy."
  [{:keys [observed record paths]}]
  (let [recorded (fn [r k] (get-in record [:repos r k]))
        problems
        (vec (mapcat
              (fn [r]
                (let [o (get observed r)
                      parent (sole-parent o)
                      known (remove nil? [(recorded r :previous) (recorded r :new) (recorded r :target)])]
                  (or (seq (checkout-problems r o))
                      (cond
                        (not (recorded r :previous))
                        [(str (name r) ": the deploy record has no previous commit for it.")]
                        (not= (recorded r :path) (get paths r))
                        [(str (name r) ": the record is for " (recorded r :path) ", not " (get paths r) ".")]
                        (not-any? #(same-commit? parent %) known)
                        [(str (name r) " is on " (short-id parent) ", which the deploy of " (:at record)
                              " did not leave it on (it found it on " (short-id (recorded r :previous))
                              (when (recorded r :new) (str " and moved it to " (short-id (recorded r :new))))
                              "). Something else moved it; not rolling back over that.")]))))
              repo-keys))]
    (if (seq problems)
      {:problems problems}
      {:steps (vec (for [r repo-keys
                         :let [{:keys [path] :as o} (get observed r)
                               previous (recorded r :previous)]]
                     {:repo r :path path :observed o :from (ids (sole-parent o)) :to previous
                      :moves? (not (same-commit? (sole-parent o) previous))
                      :build (builds r)}))})))

(defn describe-plan
  "The plan as lines for the operator to read before confirming."
  [{:keys [steps]}]
  (vec (for [{:keys [repo path from to moves?]} steps]
         (str "  " (name repo) " (" path "): "
              (if moves?
                (str (short-id from) " -> " (short-id to))
                (str "already on " (short-id to)))
              (when (builds repo) (str ", then " (str/join " " (builds repo))))))))

;; --- running wakes

(defn busy-wakes
  "The wakes in `state` that are running, as {:id :role :chat}. `alive?`
  says whether a wake's process is still there: a wake whose process has
  gone is for the next tick to end, and does not hold a deploy up. An
  open conversation holds a deploy up even between turns: its relay acp
  runs the code from before the deploy, which could not read an event the
  new code writes."
  [state alive?]
  (vec (for [w (sort-by :id (vals (:wakes state)))
             :when (and (= :running (:status w)) (alive? w))]
         (select-keys w [:id :role :chat]))))

;; --- where the record goes

(defn record-path-problem
  "Why the deploy record may not be written at `path`, or nil. The record
  stays outside every checkout and outside ~/Sync, so it is neither
  committed nor synced. Paths arrive absolute with links resolved."
  [{:keys [path sync repo-paths]}]
  (let [under? (fn [p root] (or (= p root) (str/starts-with? p (str root "/"))))]
    (cond
      (under? path sync) (str path " is inside " sync ".")
      (some #(under? path %) repo-paths) (str path " is inside a checkout.")
      :else nil)))

;; --- the record

(defn make-record
  "A deploy or rollback record. `repos` maps each repo to {:path :wc
  :previous :target :new :state}, commits as {:commit :change}. :id is
  the time it began, and every line written for the same run keeps it;
  a rollback names the deploy it undoes in :of."
  [{:keys [id kind of at status failure repos inherited lift-only]}]
  (cond-> {:id id :kind kind :at at :status status
           :repos (into {} (for [[r m] repos]
                             [r (cond-> (select-keys m [:path :state])
                                  (:wc m) (assoc :wc (ids (:wc m)))
                                  (:previous m) (assoc :previous (ids (:previous m)))
                                  (:target m) (assoc :target (ids (:target m)))
                                  (:new m) (assoc :new (ids (:new m))))]))}
    of (assoc :of of)
    inherited (assoc :inherited true)
    lift-only (assoc :lift-only true)
    failure (assoc :failure failure)))

(defn record->line [record] (str (pr-str record) "\n"))

(defn- read-record-line
  "The record on a line, or nil. The line must hold exactly one map with an
  :id: a line with something after the map, or a map that is cut off, is
  not a record."
  [line]
  (try (let [rdr (java.io.PushbackReader. (java.io.StringReader. line))
             r (edn/read {:eof ::eof} rdr)
             more (edn/read {:eof ::eof} rdr)]
         (when (and (map? r) (:id r) (= ::eof more)) r))
       (catch Exception _ nil)))

(defn read-records
  "Every record in the file's text, oldest first. A line that is not a
  whole record is skipped, so a damaged tail does not hide the records
  before it or the ones written after it."
  [text]
  (->> (str/split-lines (str text))
       (remove str/blank?)
       (keep read-record-line)
       vec))

(defn repair-before-append
  "The text to write before a new record so that it starts on its own
  line: a newline when the file's text does not end in one."
  [existing-text]
  (if (or (str/blank? existing-text) (str/ends-with? existing-text "\n")) "" "\n"))

;; --- the pause

(defn pause-problem
  "Why the pause file cannot be relied on, or nil. It only works for a relay
  that knows it, and both the relay that is running while the checkouts move
  and the one left in place at the end must: the first does the ticking
  during the move, and the second is what a failure leaves paused.
  `external?` is the operator saying ticks are stopped some other way."
  [{:keys [current-supports? target-supports? external?]}]
  (let [how " Stop the tick job first (launchctl bootout gui/<uid>/relay.tick), run this again with --paused-externally, and start the job again afterwards."]
    (cond
      external? nil
      (not current-supports?)
      (str "The relay that is running now does not know the pause file, so a tick would not stop for it." how)
      (not target-supports?)
      (str "The relay this would leave in place does not know the pause file, so a failure partway could not keep ticks stopped." how))))
