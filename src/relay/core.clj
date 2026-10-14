(ns relay.core
  "relay's rules, with no I/O. Two functions carry them:

    evolve  folds one event into the state. The state is nothing more than
            the events folded in order, so it can always be rebuilt from
            the log, and replay must never consult anything but the event:
            not the clock and not the config.
    decide  takes the state and a command and returns the events the command
            produces, or throws a rejection saying why it cannot be done.
            Anything that depends on config or the clock is worked out here
            and written into the events.

  Entity ids are the seq of the event that created them: memo 42 is the
  memo that event 42 sent."
  (:require [clojure.string :as str]
            [relay.config :as config]
            [relay.schedule :as schedule])
  (:import (java.time Duration Instant)))

(def operator "operator")
(def dispatcher "dispatcher")
(def reserved-ids #{operator dispatcher "relay" "all" "none"})

(def harness-outcomes
  "How a wake's harness run ended. Only \"ran\" means the role got to work;
  the others are the harness's own failures and count against no memo."
  #{"ran" "limited" "unreachable" "needs-attention"})

(def empty-state
  {:roles {} :memos {} :routines {} :wakes {} :dead-letters {} :harnesses {}
   :notifications {} :guards {} :next-seq 1 :last-at nil})

(defn inst ^Instant [s] (some-> s str Instant/parse))

(defn- after? [^Instant a ^Instant b] (.isAfter a b))

;; --- rejections

(defn reject!
  "Refuses a command. `reason` is a keyword a caller can act on; the message
  is for whoever sent the command."
  ([message] (reject! :invalid message))
  ([reason message]
   (throw (ex-info message {:relay/rejected reason}))))

(defn rejection [e] (:relay/rejected (ex-data e)))

;; --- queries

(defn role [state id] (get-in state [:roles id]))
(defn active-role? [state id] (= :active (:status (role state id))))
(defn active-role-ids [state]
  (->> (vals (:roles state)) (filter #(= :active (:status %))) (map :id) sort vec))

(defn memo [state id] (get-in state [:memos id]))

(defn running-wake [state role-id]
  (->> (vals (:wakes state))
       (filter #(and (= role-id (:role %)) (= :running (:status %))))
       first))

(defn running? [state role-id] (some? (running-wake state role-id)))

(defn unread-memos
  "Open memos to someone, oldest first."
  [state to]
  (->> (vals (:memos state))
       (filter #(and (= to (:to %)) (= :unread (:status %))))
       (sort-by :id)
       vec))

(defn dead-memos
  "Dead memos still waiting on someone to requeue them."
  [state to]
  (->> (vals (:memos state))
       (filter #(and (= to (:to %)) (= :dead (:status %)) (not (:superseded-by %))))
       (sort-by :id)
       vec))

(defn backing-off? [memo ^Instant now]
  (boolean (some-> (:retry-at memo) (after? now))))

(defn waiting-memos
  "Open memos that are not waiting out a retry delay."
  [state to now]
  (vec (remove #(backing-off? % now) (unread-memos state to))))

(defn harness-of [state config role-id]
  (or (:harness (role state role-id)) (some-> (:default-harness config) name)))

(defn paused-until
  "When a paused harness is next tried, or nil if it is not paused now."
  [state harness ^Instant now]
  (when-let [until (get-in state [:harnesses harness :paused-until])]
    (when (after? until now) until)))

(defn recent-wakes
  "The role's wakes in the runaway window that it got to work in. A wake the
  harness failed is the harness's trouble, which has its own backoff, so it
  does not count towards the guard."
  [state role-id config ^Instant now]
  (let [since (.minus now (Duration/ofMinutes (:runaway-window-minutes config)))]
    (filter #(and (= role-id (:role %)) (after? (:started-at %) since)
                  (not (#{"limited" "unreachable" "needs-attention"} (:outcome %))))
            (vals (:wakes state)))))

(defn running-away? [state role-id config now]
  (>= (count (recent-wakes state role-id config now)) (:runaway-wakes config)))

(defn routine [state owner name] (get-in state [:routines owner name]))

(defn routine-of-memo
  "The routine a memo was a run of: memos carry the routine's name, and a
  routine belongs to the memo's recipient."
  [state owner routine-name]
  (routine state owner routine-name))

(defn active-routines [state owner]
  (->> (vals (get-in state [:routines owner]))
       (filter #(= :active (:status %)))
       (sort-by :name)))

(defn has-open-run? [state {:keys [last-memo]}]
  (= :unread (:status (memo state last-memo))))

(defn routine-due? [state config {:keys [schedule baseline] :as r} now]
  (when-let [sched (schedule/parse schedule)]
    (and (= :active (:status r))
         (schedule/due? sched baseline now (config/zone config))
         (not (has-open-run? state r)))))

(defn roles-to-wake
  "The roles the dispatcher should wake now: with a memo waiting, not already
  running, their harness not paused, and not stopped by the runaway guard."
  [state config now]
  (vec (for [id (active-role-ids state)
             :when (and (seq (waiting-memos state id now))
                        (not (running? state id))
                        (not (paused-until state (harness-of state config id) now))
                        (not (running-away? state id config now)))]
         id)))

;; --- evolve

(defmulti evolve* (fn [_state event] (:type event)))

(defmethod evolve* :default [_ event]
  (throw (ex-info (str "This version of relay does not know event type " (:type event)
                       " (event " (:seq event) "). Upgrade relay before reading this log.")
                  {:event event})))

(defn evolve [state {:keys [seq at] :as event}]
  (when-not (= seq (:next-seq state))
    (throw (ex-info (str "Event " seq " is out of order; expected " (:next-seq state) ".")
                    {:event event})))
  (-> (evolve* state event)
      (assoc :next-seq (inc seq) :last-at (inst at))))

(defn replay [events] (reduce evolve empty-state events))

(defmethod evolve* "role/added" [state {:keys [at data]}]
  (let [{:keys [id title config reports-to harness model]} data]
    (assoc-in state [:roles id] {:id id :title title :config config :reports-to reports-to
                                 :harness harness :model model :status :active
                                 :added-at (inst at)})))

(defmethod evolve* "role/updated" [state {:keys [data]}]
  (update-in state [:roles (:id data)] merge
             (select-keys data [:title :config :reports-to :harness :model])))

(defmethod evolve* "role/removed" [state {:keys [at data]}]
  (update-in state [:roles (:id data)] assoc :status :removed :removed-at (inst at)))

(defmethod evolve* "memo/sent" [state {:keys [seq at data labels]}]
  (let [{:keys [from to subject body routine]} data]
    (assoc-in state [:memos seq] {:id seq :from from :to to :subject subject :body body
                                  :routine routine :sent-at (inst at) :status :unread
                                  :attempts 0 :retry-at nil :labels labels})))

(defmethod evolve* "memo/closed" [state {:keys [at actor data]}]
  (update-in state [:memos (:memo data)] assoc
             :status :read :outcome (:outcome data) :closed-at (inst at) :closed-by actor
             :retry-at nil))

(defmethod evolve* "memo/attempt-failed" [state {:keys [data]}]
  (update-in state [:memos (:memo data)] assoc
             :attempts (:attempt data) :retry-at (inst (:retry-at data))))

(defmethod evolve* "memo/dead-lettered" [state {:keys [seq at data]}]
  (let [{:keys [memo reason explanation attempts transcript-tail wake]} data]
    (-> state
        (update-in [:memos memo] assoc :status :dead :attempts attempts :retry-at nil)
        (assoc-in [:dead-letters seq] {:id seq :memo memo :reason reason
                                       :explanation explanation :attempts attempts
                                       :transcript-tail transcript-tail :wake wake
                                       :created-at (inst at) :status :open}))))

(defmethod evolve* "memo/requeued" [state {:keys [data]}]
  (let [id (:memo data)]
    (-> state
        (update-in [:memos id] assoc :status :unread :attempts 0 :retry-at nil)
        (update :dead-letters
                (fn [letters]
                  (into {} (map (fn [[k l]]
                                  [k (if (and (= id (:memo l)) (= :open (:status l)))
                                       (assoc l :status :requeued)
                                       l)]))
                        letters))))))

(defmethod evolve* "memo/superseded" [state {:keys [data]}]
  (let [id (:memo data)]
    (-> state
        (assoc-in [:memos id :superseded-by] (:by data))
        (update :dead-letters
                (fn [letters]
                  (into {} (map (fn [[k l]]
                                  [k (if (and (= id (:memo l)) (= :open (:status l)))
                                       (assoc l :status :superseded)
                                       l)]))
                        letters))))))

(defmethod evolve* "routine/written" [state {:keys [at data]}]
  (let [{:keys [owner name schedule instructions]} data]
    (update-in state [:routines owner name]
               (fn [r] (assoc r :owner owner :name name :schedule schedule
                              :instructions instructions :baseline (inst at)
                              :status :active)))))

(defmethod evolve* "routine/removed" [state {:keys [data]}]
  (assoc-in state [:routines (:owner data) (:name data) :status] :removed))

(defmethod evolve* "routine/fired" [state {:keys [at data]}]
  (let [{:keys [owner name memo one-off]} data]
    (update-in state [:routines owner name] assoc
               :baseline (inst at) :last-memo memo
               :status (if one-off :retired :active))))

(defmethod evolve* "wake/started" [state {:keys [seq at data]}]
  (let [{:keys [role memos note resumes harness pid pid-start]} data]
    (-> state
        (assoc-in [:wakes seq] {:id seq :role role :memos (vec memos) :note note
                                :resumes resumes :harness harness :pid pid
                                :pid-start pid-start :started-at (inst at)
                                :status :running})
        (assoc-in [:roles role :interrupted-wake] nil))))

(defmethod evolve* "wake/ended" [state {:keys [at data]}]
  (let [{:keys [wake outcome exit crashed session said transcript]} data
        role-id (get-in state [:wakes wake :role])]
    (cond-> (update-in state [:wakes wake] assoc
                       :status :ended :ended-at (inst at) :outcome outcome :exit exit
                       :crashed (boolean crashed) :session session :said said
                       :transcript transcript)
      (not= "ran" outcome) (assoc-in [:roles role-id :interrupted-wake] wake))))

(defmethod evolve* "harness/paused" [state {:keys [data]}]
  (let [{:keys [harness reason until failed-tries down-since]} data]
    (update-in state [:harnesses harness] assoc
               :condition reason :paused-until (inst until) :failed-tries failed-tries
               :down-since (inst down-since))))

(defmethod evolve* "harness/recovered" [state {:keys [data]}]
  (assoc-in state [:harnesses (:harness data)] {:condition "available" :failed-tries 0}))

(defmethod evolve* "notification/created" [state {:keys [seq at data]}]
  (let [{:keys [kind harness role]} data]
    (cond-> (assoc-in state [:notifications seq] (assoc data :id seq :created-at (inst at)
                                                        :delivery :pending))
      (#{"harness-needs-attention" "harness-unreachable"} kind)
      (assoc-in [:harnesses harness :operator-told] true)
      (= "runaway-guard-tripped" kind)
      (assoc-in [:guards role] (inst at)))))

(defmethod evolve* "notification/delivered" [state {:keys [data]}]
  (assoc-in state [:notifications (:notification data) :delivery] :delivered))

(defmethod evolve* "notification/failed" [state {:keys [data]}]
  (update-in state [:notifications (:notification data)] assoc
             :delivery :failed :error (:error data)))

;; --- decide

(defn- emit
  "Adds an event to a transaction being built, folding it into the
  transaction's state so later steps see it."
  ([tx type data] (emit tx type data []))
  ([{:keys [state now actor] :as tx} type data labels]
   (let [event {:seq (:next-seq state) :at (str now) :type type :actor actor
                :labels (vec (sort (distinct labels))) :data data}]
     (-> tx
         (assoc :state (evolve state event))
         (update :events conj event)))))

(defn- last-id [tx] (:seq (peek (:events tx))))

(defn- blank? [s] (or (not (string? s)) (str/blank? s)))

(defn- require-text [value what]
  (when (blank? value) (reject! (str what " must not be empty.")))
  (str/trim value))

(def ^:private id-pattern #"[a-z][a-z0-9-]{0,39}")

(defn usable-id? [id]
  (boolean (and (string? id) (re-matches id-pattern id) (not (reserved-ids id)))))

(defn- notify [tx kind title body & {:as about}]
  (emit tx "notification/created" (merge {:kind kind :title title :body body} about)))

(defn- quote-block [s] (str "> " (str/replace (str s) "\n" "\n> ")))

(defn- dead-letter-report [state {:keys [memo reason explanation attempts transcript-tail]}]
  (let [{:keys [from to subject body]} (get-in state [:memos memo])]
    (str "Memo " memo " to `" to "` from " from " was set aside.\n\n"
         (case reason
           "blocked" (str "They said they could not do it:\n\n" (quote-block explanation))
           "never-closed" (str "They were woken for it " attempts " times and never closed it.")
           "recipient-removed" "Its recipient was removed before it was closed.")
         "\n\nWork out why, then either fix the cause and run `relay retry " to
         "`, or take it to the operator.\n\n## The memo\n\n**" subject "**\n\n" body
         (when-not (blank? transcript-tail)
           (str "\n\n## End of the last transcript\n\n```\n" transcript-tail "\n```")))))

(defn- escalate
  "Passes a fresh dead letter on: as a memo to the dead letter handler, or as
  a notification to the operator when the memo was the handler's own or
  there is no handler."
  [{:keys [state config] :as tx} letter-id]
  (let [letter (get-in state [:dead-letters letter-id])
        {:keys [to subject]} (memo state (:memo letter))
        handler (:dead-letter-handler config)
        title (str to " could not finish \"" subject "\"")
        report (dead-letter-report state letter)]
    (if (and handler (active-role? state handler) (not= handler to))
      (emit tx "memo/sent" {:from dispatcher :to handler
                            :subject (str "Dead letter: " title) :body report})
      (notify tx "work-abandoned" title report :role to :dead-letter letter-id))))

(defn- dead-letter [tx memo-id reason & {:as more}]
  (let [tx (emit tx "memo/dead-lettered"
                 (merge {:memo memo-id :reason reason
                         :attempts (:attempts (memo (:state tx) memo-id))}
                        more))]
    (escalate tx (last-id tx))))

(defn- send-memo [tx {:keys [from to subject body routine]}]
  (let [tx (emit tx "memo/sent" (cond-> {:from from :to to :subject subject :body body}
                                  routine (assoc :routine routine)))]
    (if (= operator to)
      (notify tx "memo-for-operator" subject body :memo (last-id tx))
      tx)))

(defn- authorize-registry! [{:keys [config actor]}]
  (when-not (or (= operator actor) (config/may-add? config actor))
    (reject! :forbidden (str actor " may not add, change or remove roles."))))

(defn- active-role! [state id]
  (when-not (active-role? state id)
    (reject! :not-found (str "There is no role called " id "."))))

(defn- caller-role! [{:keys [state actor]}]
  (when-not (active-role? state actor)
    (reject! :forbidden (str "Only a role can do that, and " actor " is not one."))))

(defn- open-memo! [{:keys [state]} id]
  (let [m (memo state id)]
    (when-not m (reject! :not-found (str "There is no memo " id ".")))
    (when-not (= :unread (:status m))
      (reject! :invalid (str "Memo " id " is not open; it is " (name (:status m)) ".")))
    m))

(defmulti decide* (fn [_tx command] (:type command)))

(defmethod decide* :default [_ command]
  (reject! (str "Unknown command " (pr-str (:type command)) ".")))

(defn decide
  "The events a command produces. `ctx` has :now (an Instant no earlier than
  the state's last event) and :config. Throws a rejection when the command
  cannot be carried out."
  [state {:keys [actor] :as command} {:keys [now config]}]
  (when (and (:last-at state) (.isBefore ^Instant now (:last-at state)))
    (throw (ex-info "decide was given a time before the log's last event." {:now now})))
  (when-not (or (#{operator dispatcher} actor) (active-role? state actor))
    (reject! :forbidden (str "Unknown actor " (pr-str actor) ".")))
  (:events (decide* {:state state :events [] :now now :config config :actor actor}
                    command)))

(defn run-command
  "decide, then fold the events into the state. Returns [state events]."
  [state command ctx]
  (let [events (decide state command ctx)]
    [(reduce evolve state events) events]))

;; roles

(defmethod decide* :role/add [{:keys [state config actor] :as tx}
                              {{:keys [id title reports-to harness model] :as args} :args}]
  (authorize-registry! tx)
  (when-not (usable-id? id)
    (reject! (str "A role id is lower case letters, digits and hyphens, starting with "
                  "a letter, and not one of " (str/join ", " (sort reserved-ids)) ".")))
  (when (role state id)
    (reject! :conflict (str "The id " id " is already taken; ids are never reused.")))
  (let [reports-to (or reports-to (when (not= operator actor) actor))]
    (when (and reports-to (not (active-role? state reports-to)))
      (reject! :not-found (str "There is no role called " reports-to " to report to.")))
    (when (and harness (not (contains? (:harnesses config) (keyword harness))))
      (reject! (str "No harness called " harness " is configured.")))
    (emit tx "role/added" {:id id
                           :title (if (blank? title) id (str/trim title))
                           :config (require-text (:config args) "A role's config")
                           :reports-to reports-to :harness harness :model model})))

(defmethod decide* :role/update [{:keys [state config actor] :as tx} {{:keys [id] :as args} :args}]
  (authorize-registry! tx)
  (active-role! state id)
  (when (= id actor) (reject! :forbidden "A role may not change its own config."))
  (let [changes (select-keys args [:title :config :reports-to :harness :model])]
    (when (empty? changes) (reject! "Nothing to change."))
    (when (contains? changes :config) (require-text (:config changes) "A role's config"))
    (when-let [h (:harness changes)]
      (when-not (contains? (:harnesses config) (keyword h))
        (reject! (str "No harness called " h " is configured."))))
    (when-let [r (:reports-to changes)] (active-role! state r))
    (emit tx "role/updated" (assoc changes :id id))))

(defmethod decide* :role/remove [{:keys [state actor] :as tx} {{:keys [id]} :args}]
  (authorize-registry! tx)
  (active-role! state id)
  (when (= id actor) (reject! :forbidden "A role may not remove itself."))
  (when (running? state id) (reject! :busy (str id " is running now; remove it once it stops.")))
  (as-> tx tx
    (reduce (fn [tx {:keys [name]}] (emit tx "routine/removed" {:owner id :name name}))
            tx (active-routines state id))
    (emit tx "role/removed" {:id id})
    (reduce (fn [tx m] (dead-letter tx (:id m) "recipient-removed"))
            tx (unread-memos state id))))

;; memos

(defmethod decide* :memo/send [{:keys [state actor] :as tx} {{:keys [to subject body]} :args}]
  (when-not (or (= operator to) (active-role? state to))
    (reject! :not-found (str "There is no role called " to ".")))
  (let [subject (require-text subject "A memo's subject")
        body (require-text body "A memo's body")]
    (when (str/includes? subject "\n") (reject! "A memo's subject is one line."))
    (when (> (count subject) 200) (reject! "A memo's subject is at most 200 characters."))
    (send-memo tx {:from actor :to to :subject subject :body body})))

(defmethod decide* :memo/close [{:keys [actor] :as tx} {{:keys [memo outcome]} :args}]
  (let [m (open-memo! tx memo)]
    (when-not (or (= actor (:to m)) (= operator actor))
      (reject! :forbidden (str "Memo " memo " is not addressed to " actor ".")))
    (emit tx "memo/closed" {:memo memo :outcome (require-text outcome "The outcome")})))

(defmethod decide* :memo/block [{:keys [actor] :as tx} {{:keys [memo explanation]} :args}]
  (caller-role! tx)
  (let [m (open-memo! tx memo)]
    (when-not (= actor (:to m))
      (reject! :forbidden (str "Memo " memo " is not addressed to " actor ".")))
    (dead-letter tx memo "blocked" :explanation (require-text explanation "The explanation"))))

(defmethod decide* :memo/retry [{:keys [state config actor] :as tx} {{:keys [role]} :args}]
  (when-not (or (= operator actor) (= actor (:dead-letter-handler config))
                (config/may-add? config actor))
    (reject! :forbidden (str actor " may not requeue dead letters.")))
  (active-role! state role)
  ;; A routine's run that has since been replaced by a newer run of the same
  ;; routine is not brought back, or the routine would have two open runs.
  ;; It is marked superseded instead, which closes its dead letter.
  (reduce (fn [tx {:keys [id routine]}]
            (let [latest (when routine (:last-memo (routine-of-memo state role routine)))]
              (if (and routine latest (not= latest id))
                (emit tx "memo/superseded" {:memo id :by latest})
                (emit tx "memo/requeued" {:memo id}))))
          tx (dead-memos state role)))

;; routines

(def ^:private routine-name-pattern #"[a-z0-9][a-z0-9-]{0,47}")

(defn- routine-owner [{:keys [state actor]} owner]
  (let [owner (if (= operator actor) owner actor)]
    (when-not (and owner (active-role? state owner))
      (reject! :forbidden (if (= operator actor)
                            "Name the role the routine belongs to."
                            "Only a role can have routines.")))
    owner))

(defmethod decide* :routine/write [tx {{:keys [owner name schedule instructions]} :args}]
  (let [owner (routine-owner tx owner)]
    (when-not (and (string? name) (re-matches routine-name-pattern name))
      (reject! "A routine name is lower case letters, digits and hyphens."))
    (when-not (schedule/parse schedule)
      (reject! (str "Not a schedule relay understands: " (pr-str schedule) ". Use every 30m, "
                    "every 2h, every 1d, daily 07:30, weekly mon 09:00 or once 2026-10-08 09:00.")))
    (emit tx "routine/written" {:owner owner :name name
                                :schedule (schedule/render (schedule/parse schedule))
                                :instructions (require-text instructions "A routine's instructions")})))

(defmethod decide* :routine/remove [{:keys [state] :as tx} {{:keys [owner name]} :args}]
  (let [owner (routine-owner tx owner)]
    (when-not (= :active (:status (routine state owner name)))
      (reject! :not-found (str owner " has no routine called " name ".")))
    (emit tx "routine/removed" {:owner owner :name name})))

;; waking

(defn- dispatcher-only! [{:keys [actor]}]
  (when-not (= dispatcher actor)
    (reject! :forbidden "Only the dispatcher does that.")))

(defmethod decide* :wake/start [{:keys [state config now actor] :as tx}
                                {{role-id :role :keys [note dispatched pid pid-start]} :args}]
  (when-not (#{dispatcher operator} actor)
    (reject! :forbidden "Only the operator or the dispatcher wakes a role."))
  (active-role! state role-id)
  (when (running? state role-id) (reject! :busy (str role-id " is already running.")))
  (let [harness (harness-of state config role-id)]
    (when dispatched
      (when (paused-until state harness now) (reject! :paused (str harness " is paused.")))
      (when (running-away? state role-id config now)
        (reject! :runaway (str role-id " has been stopped by the runaway guard."))))
    (let [memos (if dispatched (waiting-memos state role-id now) (unread-memos state role-id))]
      (if (and (empty? memos) (blank? note))
        tx
        (emit tx "wake/started" {:role role-id :memos (mapv :id memos) :note note
                                 :resumes (:interrupted-wake (role state role-id))
                                 :harness harness :pid pid :pid-start pid-start})))))

(defn- settle-memos
  "Deals with each memo a wake carried that is still open. The role never
  closed it, so the attempt failed, however the harness exited."
  [{:keys [config now] :as tx} wake said]
  (reduce (fn [{:keys [state] :as tx} memo-id]
            (let [m (memo state memo-id)
                  attempt (inc (:attempts m))]
              (cond
                (not= :unread (:status m)) tx
                (>= attempt (:max-attempts config))
                (-> tx
                    (emit "memo/attempt-failed" {:memo memo-id :attempt attempt :retry-at nil
                                                 :wake (:id wake)})
                    (dead-letter memo-id "never-closed" :transcript-tail said :wake (:id wake)))
                :else
                (emit tx "memo/attempt-failed"
                      {:memo memo-id :attempt attempt :wake (:id wake)
                       :retry-at (str (.plusSeconds ^Instant now
                                                    (config/retry-delay-seconds config attempt)))}))))
          tx (:memos wake)))

(defn- note-harness
  "Records what a run says about its harness. A run that got to work clears
  any pause. A limit pauses until its reset, or for the probe interval when
  none was given. Being unreachable or needing a person pauses for a wait
  that doubles with each failed try. The operator is told at once when a
  person is needed, and once when an outage outlasts their patience."
  [{:keys [state config ^Instant now] :as tx} harness outcome resets-at said]
  (let [h (get-in state [:harnesses harness])]
    (if (= "ran" outcome)
      (if (and h (or (:paused-until h) (pos? (or (:failed-tries h) 0)) (:down-since h)))
        (emit tx "harness/recovered" {:harness harness})
        tx)
      (let [tries (if (= "limited" outcome) (or (:failed-tries h) 0) (inc (or (:failed-tries h) 0)))
            resets (inst resets-at)
            until (if (= "limited" outcome)
                    (if (and resets (after? resets now))
                      resets
                      (.plusSeconds now (* 60 (:limit-probe-minutes config))))
                    (.plusSeconds now (config/retry-delay-seconds config tries)))
            down-since (or (:down-since h) now)
            tx (emit tx "harness/paused" {:harness harness :reason outcome :until (str until)
                                          :failed-tries tries :down-since (str down-since)})
            told? (:operator-told h)]
        (cond
          (and (= "needs-attention" outcome) (not told?))
          (notify tx "harness-needs-attention" (str harness " needs you before relay can work")
                  (str "It said:\n\n" said "\n\nEvery role on it is on hold and nothing has been "
                       "dropped. Work carries on by itself once it runs again.")
                  :harness harness)
          :else tx)))))

(defn- end-wake [{:keys [state] :as tx} wake-id {:keys [outcome exit crashed session said transcript
                                                       resets-at]}]
  (let [wake (get-in state [:wakes wake-id])]
    (when-not (harness-outcomes outcome)
      (reject! (str "Unknown harness outcome " (pr-str outcome) ".")))
    (as-> tx tx
      (emit tx "wake/ended" {:wake wake-id :outcome outcome :exit exit :crashed (boolean crashed)
                             :session session :said said :transcript transcript})
      (note-harness tx (:harness wake) outcome resets-at said)
      (if (= "ran" outcome) (settle-memos tx wake said) tx))))

(defmethod decide* :wake/end [{:keys [state] :as tx} {{:keys [wake] :as args} :args}]
  (dispatcher-only! tx)
  (when-not (= :running (get-in state [:wakes wake :status]))
    (reject! :invalid (str "Wake " wake " is not running.")))
  (end-wake tx wake args))

;; the tick

(defn- fire-routines [{:keys [state config now] :as tx}]
  (reduce (fn [tx {:keys [owner name instructions schedule] :as r}]
            (if (routine-due? (:state tx) config r now)
              (let [tx (send-memo tx {:from dispatcher :to owner :subject name :body instructions
                                      :routine name})
                    memo-id (:seq (first (filter #(= "memo/sent" (:type %))
                                                 (rseq (:events tx)))))]
                (emit tx "routine/fired" {:owner owner :name name :memo memo-id
                                          :one-off (schedule/one-off? (schedule/parse schedule))}))
              tx))
          tx
          (mapcat #(active-routines state %) (active-role-ids state))))

(defn- trip-guards [{:keys [state config ^Instant now] :as tx}]
  (reduce (fn [tx id]
            (let [reported (get-in (:state tx) [:guards id])
                  window (Duration/ofMinutes (:runaway-window-minutes config))]
              (if (and (seq (waiting-memos state id now))
                       (not (running? state id))
                       (running-away? state id config now)
                       (or (nil? reported) (not (after? (.plus ^Instant reported window) now))))
                (notify tx "runaway-guard-tripped" (str id " was stopped by the runaway guard")
                        (str "It has been woken more often in the last "
                             (:runaway-window-minutes config) " minutes than ordinary use "
                             "explains. Its work is waiting and nothing was dropped.")
                        :role id)
                tx)))
          tx (active-role-ids state)))

(defn- report-outages [{:keys [state config ^Instant now] :as tx}]
  (reduce (fn [tx [harness h]]
            (if (and (= "unreachable" (:condition h))
                     (not (:operator-told h))
                     (:down-since h)
                     (not (after? (.plus ^Instant (:down-since h)
                                         (Duration/ofMinutes (:outage-patience-minutes config)))
                                  now)))
              (notify tx "harness-unreachable"
                      (str harness " has been unreachable since " (:down-since h))
                      "Every role on it is on hold and nothing has been dropped. Work carries on by itself once it is back."
                      :harness harness)
              tx))
          tx (sort-by key (:harnesses state))))

(defmethod decide* :tick [{:keys [state] :as tx} {{:keys [lost-wakes]} :args}]
  (dispatcher-only! tx)
  (as-> tx tx
    ;; A wake whose runner process is gone ended without saying how. It
    ;; counts against its memos, like a crash, so a memo that kills its
    ;; runner every time is still dead-lettered in the end.
    (reduce (fn [tx id]
              (if (= :running (get-in (:state tx) [:wakes id :status]))
                (end-wake tx id {:outcome "ran" :crashed true
                                 :said "The process running this wake disappeared."})
                tx))
            tx (sort lost-wakes))
    (fire-routines tx)
    (trip-guards tx)
    (report-outages tx)))

;; notifications

(defmethod decide* :notification/delivered [{:keys [state] :as tx} {{:keys [notification]} :args}]
  (dispatcher-only! tx)
  (when-not (get-in state [:notifications notification]) (reject! :not-found "No such notification."))
  (emit tx "notification/delivered" {:notification notification}))

(defmethod decide* :notification/failed [{:keys [state] :as tx} {{:keys [notification error]} :args}]
  (dispatcher-only! tx)
  (when-not (get-in state [:notifications notification]) (reject! :not-found "No such notification."))
  (emit tx "notification/failed" {:notification notification :error (str error)}))
