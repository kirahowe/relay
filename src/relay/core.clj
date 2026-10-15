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
  (:import (java.time Duration Instant LocalDate LocalDateTime)))

(def operator "operator")
(def dispatcher "dispatcher")
(def reserved-ids #{operator dispatcher "relay" "all" "none"})

(def private-label
  "Seen the operator's private data. Reading the text of anything relay
  keeps (a memo, fact, doc, note or journal entry) adds it."
  "private")
(def contaminated-label
  "Seen something an outsider could have written."
  "contaminated")
(def label-names #{private-label contaminated-label})

(defn union
  "Labels as relay keeps them: the distinct names of all the given
  collections, sorted, in a vector, so a state survives a JSON round trip."
  [& colls]
  (vec (sort (distinct (apply concat colls)))))

(defn content-labels
  "The labels of relay content: its writers' labels and private."
  [& colls]
  (apply union [private-label] colls))

(def harness-outcomes
  "How a wake's harness run ended. Only \"ran\" means the role got to work;
  the others are the harness's own failures and count against no memo."
  #{"ran" "limited" "unreachable" "needs-attention"})

(def empty-state
  {:roles {} :memos {} :routines {} :wakes {} :dead-letters {} :harnesses {}
   :notifications {} :guards {} :facts {} :notes {} :journals {} :docs {} :holds {}
   ;; Every label any event has carried, which bounds what a session that
   ;; could read anything in relay may have seen.
   :seen-labels []
   :next-seq 1 :last-at nil})

(defn- blank? [s] (or (not (string? s)) (str/blank? s)))

(defn inst ^Instant [s] (some-> s str Instant/parse))

(defn- after? [^Instant a ^Instant b] (.isAfter a b))

;; --- rejections

(defn reject!
  "Refuses a command. `reason` is a keyword a caller can act on; the message
  is for whoever sent the command. A message that quotes something relay
  keeps carries that thing's labels, which go back with it to a role."
  ([message] (reject! :invalid message))
  ([reason message] (reject! reason message []))
  ([reason message labels]
   (throw (ex-info message (cond-> {:relay/rejected reason}
                             (seq labels) (assoc :relay/labels (union labels)))))))

(defn rejection [e] (:relay/rejected (ex-data e)))

(defn rejection-labels
  "The labels of what a refusal's message quotes."
  [e]
  (or (:relay/labels (ex-data e)) []))

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

(declare quarantined?)

(defn waiting-memos
  "Open memos a role may be shown that are not waiting out a retry delay."
  [state config to now]
  (vec (remove #(or (backing-off? % now) (quarantined? config %)) (unread-memos state to))))

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
    (filter #(and (= role-id (:role %)) (after? (:started-at %) since) (not (:chat %))
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

(defn hold [state id] (get-in state [:holds id]))

(defn open-holds
  "A role's tool calls waiting for the operator, oldest first, or every
  role's with no role given."
  ([state] (->> (vals (:holds state)) (filter #(= :open (:status %))) (sort-by :id) vec))
  ([state role-id] (filterv #(= role-id (:role %)) (open-holds state))))

(defn held? [state role-id] (boolean (seq (open-holds state role-id))))

(defn pending-resolutions
  "A role's holds the operator has decided that no wake has carried back to
  the held session yet, oldest first."
  [state role-id]
  (->> (vals (:holds state))
       (filter #(and (= role-id (:role %)) (#{:allowed :denied} (:status %)) (nil? (:delivered %))))
       (sort-by :id)
       vec))

(defn roles-to-wake
  "The roles the dispatcher should wake now: with a memo waiting or a
  decided hold to carry back, not already running, with no hold still
  waiting for the operator, their harness not paused, and not stopped by
  the runaway guard."
  [state config now]
  (vec (for [id (active-role-ids state)
             :when (and (or (seq (waiting-memos state config id now)) (seq (pending-resolutions state id)))
                        (not (held? state id))
                        (not (running? state id))
                        (not (paused-until state (harness-of state config id) now))
                        (not (running-away? state id config now)))]
         id)))

;; knowledge
;;
;; A fact has two kinds of time. Its validity, valid-from and valid-to, says
;; when it holds in the world; either end may be open. Each version of it
;; also has the time it was recorded, the `at` of the event that wrote it.
;; A change in the world supersedes a fact: the old one gets a new version
;; that ends where the change begins, and the new state of affairs is a new
;; fact. A correction says the record was wrong: it writes a new version of
;; the same fact, so asking what was known before the correction still
;; gives the old answer. No version is ever dropped.

(defn fact [state id] (get-in state [:facts id]))

(defn- not-after? [^Instant a ^Instant b] (not (after? a b)))

(defn fact-as-known
  "The fact as it was recorded at `known-at`, or nil if it had not been
  recorded yet. With no `known-at`, as it is recorded now."
  [{:keys [versions] :as f} known-at]
  (when-let [v (if known-at
                 (last (take-while #(not-after? (:recorded-at %) known-at) versions))
                 (peek versions))]
    (merge (select-keys f [:id :supersedes]) v)))

(defn holds-at?
  "Whether a version of a fact says it holds at world time `as-of`."
  [{:keys [valid-from valid-to retracted]} as-of]
  (and (not retracted)
       (or (nil? valid-from) (not-after? valid-from as-of))
       (or (nil? valid-to) (after? valid-to as-of))))

(defn under-topic? [topic t]
  (or (nil? topic) (= topic t) (and t (str/starts-with? t (str topic "/")))))

(defn facts
  "The facts holding at world time :as-of, as recorded at :known-at, by id.
  With :all, every fact recorded by then and not retracted, whatever its
  validity. :topic keeps that topic and the topics under it; :search keeps
  facts whose text contains it, ignoring case."
  [state {:keys [as-of known-at topic search all]}]
  (let [as-of (or as-of known-at (:last-at state))
        needle (some-> search str/lower-case)]
    (->> (vals (:facts state))
         (keep #(fact-as-known % known-at))
         (filter #(and (if all (not (:retracted %)) (holds-at? % as-of))
                       (under-topic? topic (:topic %))
                       (or (nil? needle) (str/includes? (str/lower-case (:text %)) needle))))
         (sort-by :id)
         vec)))

(defn notes [state owner] (vec (sort-by :name (vals (get-in state [:notes owner])))))

(defn journal
  "A role's journal entries, oldest first, the last `limit` of them if given."
  [state owner limit]
  (let [entries (get-in state [:journals owner] [])]
    (if limit (vec (take-last limit entries)) entries)))

(defn doc-version
  "A doc's version: the one written by event `version` if given, else the
  latest recorded at `known-at`, else the latest. Nil when there is none,
  and a removal is a version too."
  [state path {:keys [version known-at]}]
  (let [versions (get-in state [:docs path :versions])]
    (cond
      version (first (filter #(= version (:seq %)) versions))
      known-at (last (take-while #(not-after? (:at %) known-at) versions))
      :else (peek versions))))

(defn docs
  "The docs that exist, as recorded at `known-at` if given, by path."
  [state known-at]
  (->> (keys (:docs state))
       (keep #(doc-version state % {:known-at known-at}))
       (remove :removed)
       (sort-by :path)
       vec))

;; labels
;;
;; Every event carries the labels of the text it holds: a role's call
;; carries its session's labels, and an event that copies or quotes text
;; (a routine into a memo, a memo into a dead letter) carries the labels of
;; what it copied too. Each version of an object keeps the labels of the
;; event that wrote it, and what a reader is shown carries the labels of
;; the versions whose text it shows, never of versions it does not show.
;; Reading the text of relay content (a memo, fact, doc, note, journal
;; entry or routine's instructions) adds private as well; role titles and
;; instructions, routine names and doc paths carry their writers' labels
;; but are not private. A role id is text its creator chose, so wherever
;; one is shown, the labels of whoever chose it go with it.
;;
;; Contaminated text is withheld by default. A reader is shown a
;; placeholder that gives only what is clean (a number, a kind, a clean
;; author) and can ask for the text, taking on its labels. A contaminated
;; memo to a role granted an external act or egress tool is quarantined:
;; it is not shown to the role, carried in a wake or counted as waiting
;; until the operator releases it.

(defn contaminated? [labels] (boolean (some #{contaminated-label} labels)))

(defn id-labels
  "The labels of a role id: those of whoever added the role. The operator
  and the dispatcher are not chosen by anyone."
  [state id]
  (or (:id-labels (role state id)) []))

(defn ids-labels [state ids]
  (apply union (map #(id-labels state %) (distinct (remove nil? ids)))))

(defn role-labels
  "What a role's id, title and instructions carry."
  [state id]
  (let [r (role state id)]
    (union (:id-labels r) (:title-labels r) (:instructions-labels r))))

(defn shown-id
  "A role id as a reader is shown it, with its labels: the id when whoever
  chose it was clean, else words that do not name it."
  [state id]
  (let [ls (id-labels state id)]
    (if (contaminated? ls) ["a role whose id is withheld" []] [id ls])))

(defn memo-labels
  "What reading a memo addressed to you adds: its writers' labels, private,
  and the labels of its sender's id as shown (shown-id)."
  [state m]
  (content-labels (:labels m) (second (shown-id state (:from m)))))

(defn shown-memo
  "A memo as its recipient is shown it, its sender per shown-id."
  [state m]
  (assoc m :from (first (shown-id state (:from m)))))

(defn quarantines?
  "Whether a role holds an external tool that acts in the operator's name
  or sends data out. Such a role may not take in a contaminated memo by
  itself: the operator releases it. relay's own act tools do not count,
  since agentloop holds them anyway once the role is contaminated. A role
  without such tools reads a contaminated memo like any other and
  becomes contaminated."
  [config role-id]
  (boolean (some #(#{:act :egress} (get-in config [:tools % :class]))
                 (:tools (config/grants config role-id)))))

(defn quarantined?
  "A contaminated memo to a role that quarantines, not yet released."
  [config m]
  (and (not= operator (:to m)) (contaminated? (:labels m)) (not (:released m))
       (quarantines? config (:to m))))

(defn deliverable-memos
  "Open memos to a role that it may be shown, oldest first."
  [state config to]
  (filterv #(not (quarantined? config %)) (unread-memos state to)))

(defn version-labels
  "What showing one version of a fact or doc adds, with who wrote it."
  [state v]
  (union (:labels v) (id-labels state (:actor v))))

(defn path-labels
  "What showing a doc's path adds: the labels of whoever first chose it."
  [state path]
  (or (get-in state [:docs path :path-labels]) []))

(defn notes-labels
  "What showing these notes adds."
  [notes]
  (if (seq notes) (content-labels (mapcat :labels notes)) []))

(defn journal-labels [entries]
  (if (seq entries) (content-labels (mapcat :labels entries)) []))

(defn routine-labels [state owner name] (or (:labels (routine state owner name)) []))

(defn tool-labels
  "The static labels relay.edn gives an external tool: what any call to it
  adds to the session."
  [config tool-name]
  (or (get-in config [:tools tool-name :labels]) []))

(defn session-labels-bound
  "Every label a role's session could hold by now: anything relay keeps
  could have been read, and any tool it is granted called. Text a session
  wrote that relay was not told the labels of (what a wake said when the
  harness gave no labels) carries this."
  [state config role-id]
  (content-labels (:seen-labels state)
                  (mapcat #(tool-labels config %) (:tools (config/grants config role-id)))))

(defn prompt-notes
  "A role's notes as its system prompt shows them: the clean ones, and how
  many are withheld as contaminated."
  [state owner]
  (let [{withheld true shown false} (group-by #(contaminated? (:labels %)) (notes state owner))]
    {:shown (vec shown) :withheld (count withheld)}))

(defn wake-labels
  "The labels of everything a wake's prompts are made of: the role's id,
  title and instructions, the notes shown, the memos it carries and the
  ids of their senders, the routines they are runs of, the note from
  whoever woke it, and the operator's reasons on the holds it carries back.
  A conversation has no prompt, only the instructions and notes."
  [state {:keys [role memos note chat resolves]}]
  (let [ms (keep #(memo state %) memos)]
    (union (role-labels state role)
           (notes-labels (:shown (prompt-notes state role)))
           (mapcat #(memo-labels state %) ms)
           (mapcat #(when (:routine %) (routine-labels state (:to %) (:routine %))) ms)
           (when (and (not chat) (not (blank? note))) [private-label])
           (when (some #(not (blank? (:reason (hold state %)))) resolves) [private-label]))))

;; --- evolve

(defmulti evolve* (fn [_state event] (:type event)))

(defmethod evolve* :default [_ event]
  (throw (ex-info (str "This version of relay does not know event type " (:type event)
                       " (event " (:seq event) "). Upgrade relay before reading this log.")
                  {:event event})))

(defn evolve [state {:keys [seq at labels] :as event}]
  (when-not (= seq (:next-seq state))
    (throw (ex-info (str "Event " seq " is out of order; expected " (:next-seq state) ".")
                    {:event event})))
  (-> (evolve* state event)
      (update :seen-labels union labels)
      (assoc :next-seq (inc seq) :last-at (inst at))))

(defn replay [events] (reduce evolve empty-state events))

(defmethod evolve* "role/added" [state {:keys [seq at data labels]}]
  (let [{:keys [id title instructions reports-to harness model]} data]
    (assoc-in state [:roles id] {:id id :title title :instructions instructions :reports-to reports-to
                                 :harness harness :model model :status :active
                                 :added-at (inst at) :version seq
                                 ;; Each piece of text carries the labels of
                                 ;; whoever wrote what it says now.
                                 :id-labels (union labels) :title-labels (union labels)
                                 :instructions-labels (union labels)})))

(defmethod evolve* "role/updated" [state {:keys [seq data labels]}]
  (update-in state [:roles (:id data)]
             #(cond-> (merge % (select-keys data [:title :instructions :reports-to :harness :model]))
                (or (contains? data :title) (contains? data :instructions)) (assoc :version seq)
                (contains? data :title) (assoc :title-labels (union labels))
                (contains? data :instructions) (assoc :instructions-labels (union labels)))))

(defmethod evolve* "role/removed" [state {:keys [at data]}]
  (update-in state [:roles (:id data)] assoc :status :removed :removed-at (inst at)))

(defmethod evolve* "memo/sent" [state {:keys [seq at data labels]}]
  (let [{:keys [from to subject body routine]} data]
    (assoc-in state [:memos seq] {:id seq :from from :to to :subject subject :body body
                                  :routine routine :sent-at (inst at) :status :unread
                                  :attempts 0 :retry-at nil :labels (union labels)})))

(defmethod evolve* "memo/closed" [state {:keys [at actor data labels]}]
  (update-in state [:memos (:memo data)] assoc
             :status :read :outcome (:outcome data) :closed-at (inst at) :closed-by actor
             :retry-at nil :outcome-labels (union labels)))

(defmethod evolve* "memo/released" [state {:keys [data]}]
  (assoc-in state [:memos (:memo data) :released] true))

(defmethod evolve* "memo/attempt-failed" [state {:keys [data]}]
  (update-in state [:memos (:memo data)] assoc
             :attempts (:attempt data) :retry-at (inst (:retry-at data))))

(defmethod evolve* "memo/dead-lettered" [state {:keys [seq at data labels]}]
  (let [{:keys [memo reason explanation attempts transcript-tail wake]} data]
    (-> state
        (update-in [:memos memo] assoc :status :dead :attempts attempts :retry-at nil)
        (assoc-in [:dead-letters seq] {:id seq :memo memo :reason reason
                                       :explanation explanation :attempts attempts
                                       :transcript-tail transcript-tail :wake wake
                                       :created-at (inst at) :status :open
                                       :labels (union labels)}))))

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

(defmethod evolve* "routine/written" [state {:keys [seq at data labels]}]
  (let [{:keys [owner name schedule instructions]} data]
    (update-in state [:routines owner name]
               (fn [r] (assoc r :owner owner :name name :schedule schedule
                              :instructions instructions :baseline (inst at)
                              :status :active :labels (union labels) :version seq)))))

(defmethod evolve* "routine/removed" [state {:keys [data]}]
  (assoc-in state [:routines (:owner data) (:name data) :status] :removed))

(defmethod evolve* "routine/fired" [state {:keys [at data]}]
  (let [{:keys [owner name memo one-off]} data]
    (update-in state [:routines owner name] assoc
               :baseline (inst at) :last-memo memo
               :status (if one-off :retired :active))))

(defmethod evolve* "wake/started" [state {:keys [seq at data]}]
  (let [{:keys [role memos note resumes harness pid pid-start chat resolves]} data]
    (cond-> (assoc-in state [:wakes seq] {:id seq :role role :memos (vec memos) :note note
                                          :resumes resumes :harness harness :pid pid
                                          :pid-start pid-start :started-at (inst at)
                                          :status :running :chat (boolean chat)
                                          :resolves (vec resolves)})
      ;; A conversation leaves an interrupted wake to be resumed later.
      (not chat) (assoc-in [:roles role :interrupted-wake] nil)
      ;; The holds whose decisions this wake carries back to the session.
      true (update :holds (fn [hs] (reduce #(assoc-in %1 [%2 :delivered] seq) hs resolves))))))

(defmethod evolve* "wake/ended" [state {:keys [at data labels]}]
  (let [{:keys [wake outcome exit crashed cut-short session sessions said transcript held]} data
        role-id (get-in state [:wakes wake :role])]
    (cond-> (update-in state [:wakes wake] assoc
                       :status :ended :ended-at (inst at) :outcome outcome :exit exit
                       :crashed (boolean crashed) :cut-short (boolean cut-short)
                       :session session :said said :transcript transcript :labels (union labels))
      sessions (assoc-in [:wakes wake :sessions] (vec sessions))
      (seq held) (assoc-in [:wakes wake :held] (vec held))
      ;; A wake the harness failed, one cut short at its deadline, or one
      ;; stopped for the operator to decide on a call, is kept to be
      ;; resumed. A conversation is not.
      (and (or (not= "ran" outcome) cut-short (seq held)) (not (get-in state [:wakes wake :chat])))
      (assoc-in [:roles role-id :interrupted-wake] wake))))

(defn- clean [labels] (vec (remove #{contaminated-label} labels)))

(defn- clean-version [versions version]
  (mapv #(cond-> % (= version (:seq %)) (update :labels clean)) versions))

(defmethod evolve* "label/trusted" [state {:keys [data]}]
  (let [{:keys [kind ref version]} data
        num (parse-long ref)
        [owner nm] (str/split ref #"/" 2)]
    (case kind
      "memo" (update-in state [:memos num :labels] clean)
      ;; The exact version the operator read, never a later one.
      "fact" (update-in state [:facts num :versions] clean-version version)
      "doc" (-> state
                (update-in [:docs ref :versions] clean-version version)
                (update-in [:docs ref :path-labels] clean))
      "note" (update-in state [:notes owner nm :labels] clean)
      "routine" (update-in state [:routines owner nm :labels] clean)
      "role" (update-in state [:roles ref] #(-> % (update :id-labels clean) (update :title-labels clean)
                                                (update :instructions-labels clean)))
      "journal" (update state :journals
                        (fn [js] (update-vals js (fn [es] (mapv #(cond-> % (= num (:id %)) (update :labels clean)) es))))))))

(defmethod evolve* "hold/opened" [state {:keys [seq at data labels]}]
  (let [{:keys [role wake session call tool input input-sha256]} data]
    (assoc-in state [:holds seq] {:id seq :role role :wake wake :session session :call call
                                  :tool tool :input input :input-sha256 input-sha256
                                  :status :open :opened-at (inst at) :labels (union labels)})))

(defmethod evolve* "hold/resolved" [state {:keys [at actor data]}]
  (let [{:keys [hold verdict reason]} data]
    (update-in state [:holds hold] assoc
               :status (if (= "allow" verdict) :allowed :denied) :reason reason
               :resolved-at (inst at) :resolved-by actor)))

(defmethod evolve* "permission/asked" [state {:keys [seq data]}]
  (assoc-in state [:permissions seq] (assoc data :id seq)))

(defmethod evolve* "permission/answered" [state {:keys [data]}]
  (let [asked (->> (vals (:permissions state))
                   (filter #(and (= (:wake data) (:wake %)) (= (:call data) (:call %)) (nil? (:outcome %))))
                   (sort-by :id) last)]
    (cond-> state
      asked (update-in [:permissions (:id asked)] assoc :outcome (:outcome data) :by (:by data)))))

(defmethod evolve* "hold/requeued" [state {:keys [data]}]
  (update-in state [:holds (:hold data)] dissoc :delivered))

(defmethod evolve* "harness/paused" [state {:keys [data]}]
  (let [{:keys [harness reason until failed-tries down-since]} data]
    (update-in state [:harnesses harness] assoc
               :condition reason :paused-until (inst until) :failed-tries failed-tries
               :down-since (inst down-since))))

(defmethod evolve* "harness/recovered" [state {:keys [data]}]
  (assoc-in state [:harnesses (:harness data)] {:condition "available" :failed-tries 0}))

(defmethod evolve* "notification/created" [state {:keys [seq at data labels]}]
  (let [{:keys [kind harness role]} data]
    (cond-> (assoc-in state [:notifications seq] (assoc data :id seq :created-at (inst at)
                                                        :delivery :pending :labels (union labels)))
      (#{"harness-needs-attention" "harness-unreachable"} kind)
      (assoc-in [:harnesses harness :operator-told] true)
      (= "runaway-guard-tripped" kind)
      (assoc-in [:guards role] (inst at)))))

(defmethod evolve* "notification/delivered" [state {:keys [data]}]
  (assoc-in state [:notifications (:notification data) :delivery] :delivered))

(defmethod evolve* "notification/failed" [state {:keys [data]}]
  (update-in state [:notifications (:notification data)] assoc
             :delivery :failed :error (:error data)))

(defn- fact-version [{:keys [seq at actor labels]} change {:keys [topic text valid-from valid-to]}]
  {:seq seq :recorded-at (inst at) :actor actor :change change :labels (union labels)
   :topic topic :text text :valid-from (inst valid-from) :valid-to (inst valid-to)})

(defn- add-version [state id version]
  (update-in state [:facts id :versions] (fnil conj []) version))

(defmethod evolve* "fact/added" [state {:keys [seq data] :as event}]
  (-> state
      (assoc-in [:facts seq] {:id seq :supersedes nil})
      (add-version seq (fact-version event :added data))))

(defmethod evolve* "fact/superseded" [state {:keys [seq data] :as event}]
  (let [old (:fact data)
        ended (assoc (dissoc (peek (get-in state [:facts old :versions])) :why)
                     :seq seq :recorded-at (inst (:at event)) :actor (:actor event)
                     ;; It still says what the old version said.
                     :change :superseded :labels (union (:labels (peek (get-in state [:facts old :versions])))
                                                        (:labels event))
                     :valid-to (inst (:valid-from data)) :superseded-by seq)]
    (-> state
        (add-version old ended)
        (assoc-in [:facts seq] {:id seq :supersedes old})
        (add-version seq (fact-version event :added (dissoc data :fact))))))

(defmethod evolve* "fact/corrected" [state {:keys [data] :as event}]
  (let [{:keys [fact retracted why superseded-by]} data]
    (add-version state fact (cond-> (assoc (fact-version event (if retracted :retracted :corrected) data)
                                           :why why :superseded-by superseded-by)
                              retracted (assoc :retracted true)))))

(defmethod evolve* "note/written" [state {:keys [seq at data labels]}]
  (let [{:keys [owner name text]} data]
    (assoc-in state [:notes owner name] {:owner owner :name name :text text :written-at (inst at)
                                         :labels (union labels) :version seq})))

(defmethod evolve* "note/removed" [state {:keys [data]}]
  (update-in state [:notes (:owner data)] dissoc (:name data)))

(defmethod evolve* "journal/written" [state {:keys [seq at data labels]}]
  (update-in state [:journals (:owner data)] (fnil conj [])
             {:id seq :owner (:owner data) :text (:text data) :at (inst at) :labels (union labels)}))

(defn- add-doc-version [state {:keys [seq at actor labels]} path more]
  (update-in state [:docs path]
             (fn [d] (-> (assoc d :path path)
                         ;; The path is text too, chosen by whoever first wrote it.
                         (update :path-labels #(or % (union labels)))
                         (update :versions (fnil conj [])
                                 (merge {:seq seq :path path :at (inst at) :actor actor :labels (union labels)}
                                        more))))))

(defmethod evolve* "doc/put" [state {:keys [data] :as event}]
  (add-doc-version state event (:path data) {:content (:content data)}))

(defmethod evolve* "doc/removed" [state {:keys [data] :as event}]
  (add-doc-version state event (:path data) {:removed true}))

;; --- decide

(defn- emit
  "Adds an event to a transaction being built, folding it into the
  transaction's state so later steps see it. The event carries the
  caller's labels, and `copied`, the labels of any text it copies from
  elsewhere."
  ([tx type data] (emit tx type data []))
  ([{:keys [state now actor] :as tx} type data copied]
   (let [event {:seq (:next-seq state) :at (str now) :type type :actor actor
                :labels (union (:labels tx) copied) :data data}]
     (-> tx
         (assoc :state (evolve state event))
         (update :events conj event)))))

(defn- last-id [tx] (:seq (peek (:events tx))))


(defn- require-text [value what]
  (when (blank? value) (reject! (str what " must not be empty.")))
  (str/trim value))

(def ^:private id-pattern #"[a-z][a-z0-9-]{0,39}")

(defn usable-id? [id]
  (boolean (and (string? id) (re-matches id-pattern id) (not (reserved-ids id)))))

(defn- notify [tx kind title body & {:as about}]
  (emit tx "notification/created" (merge {:kind kind :title title :body body} (dissoc about :labels))
        (:labels about)))

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
        report (dead-letter-report state letter)
        ;; The report quotes the memo, and what the role said about it.
        m (memo state (:memo letter))
        copied (union (memo-labels state m) (ids-labels state [(:from m) to]) (:labels letter))]
    (if (and handler (active-role? state handler) (not= handler to))
      (emit tx "memo/sent" {:from dispatcher :to handler
                            :subject (str "Dead letter: " title) :body report}
            copied)
      (notify tx "work-abandoned" title report :role to :dead-letter letter-id :labels copied))))

(defn- dead-letter
  "Dead-letters a memo. `copied` is the labels of the text in `more`, such
  as the end of a transcript."
  [tx memo-id reason copied & {:as more}]
  (let [tx (emit tx "memo/dead-lettered"
                 (merge {:memo memo-id :reason reason
                         :attempts (:attempts (memo (:state tx) memo-id))}
                        more)
                 copied)]
    (escalate tx (last-id tx))))

(defn- send-memo
  "Sends a memo. `copied` is the labels of text it copies, such as a
  routine's instructions."
  ([tx m] (send-memo tx m []))
  ([tx {:keys [from to subject body routine]} copied]
   (let [tx (emit tx "memo/sent" (cond-> {:from from :to to :subject subject :body body}
                                   routine (assoc :routine routine))
                  copied)]
     (cond
       (= operator to)
       (notify tx "memo-for-operator" subject body :memo (last-id tx) :labels copied)
       ;; Nothing of a quarantined memo's text goes in the notification.
       (quarantined? (:config tx) (memo (:state tx) (last-id tx)))
       (notify tx "memo-quarantined" (str "Memo " (last-id tx) " to " to " is quarantined")
               (str "It is contaminated, so " to " is not shown it until you release it. relay release "
                    (last-id tx) " shows it to you first.")
               :role to :memo (last-id tx) :labels (ids-labels (:state tx) [to]))
       :else tx))))

(defn- authorize-registry! [{:keys [config actor]}]
  (when-not (or (= operator actor) (config/may-add? config actor))
    (reject! :forbidden (str actor " may not add, change or remove roles."))))

(defn- active-role! [state id]
  (when-not (active-role? state id)
    ;; A removed role still exists, and its id is text its creator chose.
    (reject! :not-found (str "There is no role called " id ".") (id-labels state id))))

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
  [state {:keys [actor labels] :as command} {:keys [now config]}]
  (when (and (:last-at state) (.isBefore ^Instant now (:last-at state)))
    (throw (ex-info "decide was given a time before the log's last event." {:now now})))
  (when-not (or (#{operator dispatcher} actor) (active-role? state actor))
    (reject! :forbidden (str "Unknown actor " (pr-str actor) ".")))
  ;; A label relay does not know could only be a mistake, and dropping it
  ;; would lose what it stands for, so the command is refused.
  (when-not (and (or (nil? labels) (sequential? labels)) (every? label-names labels))
    (reject! (str "Unknown labels " (pr-str labels) "; relay knows only "
                  (str/join " and " (sort label-names)) ".")))
  (:events (decide* {:state state :events [] :now now :config config :actor actor
                     :labels (union labels)}
                    command)))

(defn run-command
  "decide, then fold the events into the state. Returns [state events]."
  [state command ctx]
  (let [events (decide state command ctx)]
    [(reduce evolve state events) events]))

;; roles

(defn- script-without-grants!
  "A :script harness gets no tools, so a role granted any may not run on one."
  [config id harness]
  (let [g (config/grants config id)]
    (when (and (= :script (get-in config [:harnesses (keyword harness) :kind]))
               (or (seq (:tools g)) (:may-add g)))
      (reject! (str id " is granted tools in relay.edn, and a :script harness gets none."
                    " Choose another harness or take the grants away.")))))

(defmethod decide* :role/add [{:keys [state config actor] :as tx}
                              {{:keys [id title reports-to harness model] :as args} :args}]
  (authorize-registry! tx)
  (when-not (usable-id? id)
    (reject! (str "A role id is lower case letters, digits and hyphens, starting with "
                  "a letter, and not one of " (str/join ", " (sort reserved-ids)) ".")))
  (when (role state id)
    (reject! :conflict (str "The id " id " is already taken; ids are never reused.") (id-labels state id)))
  (let [reports-to (or reports-to (when (not= operator actor) actor))]
    (when (and reports-to (not (active-role? state reports-to)))
      (reject! :not-found (str "There is no role called " reports-to " to report to.")
               (id-labels state reports-to)))
    (when (and harness (not (contains? (:harnesses config) (keyword harness))))
      (reject! (str "No harness called " harness " is configured.")))
    (script-without-grants! config id (or harness (some-> (:default-harness config) name)))
    (emit tx "role/added" {:id id
                           :title (if (blank? title) id (str/trim title))
                           :instructions (require-text (:instructions args) "A role's instructions")
                           :reports-to reports-to :harness harness :model model})))

(defmethod decide* :role/update [{:keys [state config actor] :as tx} {{:keys [id] :as args} :args}]
  (authorize-registry! tx)
  (active-role! state id)
  (when (= id actor) (reject! :forbidden "A role may not change its own instructions."))
  (let [changes (select-keys args [:title :instructions :reports-to :harness :model])]
    (when (empty? changes) (reject! "Nothing to change."))
    (when (contains? changes :instructions) (require-text (:instructions changes) "A role's instructions"))
    (when-let [h (:harness changes)]
      (when-not (contains? (:harnesses config) (keyword h))
        (reject! (str "No harness called " h " is configured.")))
      (script-without-grants! config id h))
    (when-let [r (:reports-to changes)] (active-role! state r))
    (emit tx "role/updated" (assoc changes :id id))))

(defmethod decide* :role/remove [{:keys [state actor] :as tx} {{:keys [id]} :args}]
  (authorize-registry! tx)
  (active-role! state id)
  (when (= id actor) (reject! :forbidden "A role may not remove itself."))
  (when (running? state id) (reject! :busy (str id " is running now; remove it once it stops.") (id-labels state id)))
  (as-> tx tx
    (reduce (fn [tx {:keys [name]}] (emit tx "routine/removed" {:owner id :name name}))
            tx (active-routines state id))
    (emit tx "role/removed" {:id id})
    (reduce (fn [tx m] (dead-letter tx (:id m) "recipient-removed" []))
            tx (unread-memos state id))
    ;; Nothing will ever carry a decision back to a removed role.
    (reduce (fn [tx {hold-id :id}]
              (emit tx "hold/resolved" {:hold hold-id :verdict "deny" :reason "The role was removed."}))
            tx (open-holds state id))))

;; memos

(defmethod decide* :memo/send [{:keys [state actor] :as tx} {{:keys [to subject body]} :args}]
  (when-not (or (= operator to) (active-role? state to))
    (reject! :not-found (str "There is no role called " to ".") (id-labels state to)))
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
    (dead-letter tx memo "blocked" [] :explanation (require-text explanation "The explanation"))))

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

(defn- owner-for
  "Whose routines, notes or journal a command is about: the calling role's
  own, or for the operator, the role named."
  [{:keys [state actor]} owner what]
  (let [owner (if (= operator actor) owner actor)]
    (when-not (and owner (active-role? state owner))
      (reject! :forbidden (if (= operator actor)
                            (str "Name the role the " what " belongs to.")
                            (str "Only a role has " what "s."))))
    owner))

(defn- routine-owner [tx owner] (owner-for tx owner "routine"))

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
                                {{role-id :role :keys [note dispatched chat pid pid-start]} :args}]
  (when-not (#{dispatcher operator} actor)
    (reject! :forbidden "Only the operator or the dispatcher wakes a role."))
  (active-role! state role-id)
  (when (running? state role-id) (reject! :busy (str role-id " is already running.") (id-labels state role-id)))
  ;; Resuming a session with a call still held would only stop at it again.
  ;; A conversation uses a session of its own, so it may go ahead.
  (when (and (not chat) (held? state role-id))
    (reject! :held (str role-id " has a tool call waiting for the operator; see relay holds.")
             (id-labels state role-id)))
  (let [harness (harness-of state config role-id)]
    (when dispatched
      (when (paused-until state harness now) (reject! :paused (str harness " is paused.")))
      (when (running-away? state role-id config now)
        (reject! :runaway (str role-id " has been stopped by the runaway guard."))))
    ;; A conversation with the operator holds the role like a wake, so no
    ;; wake runs alongside it, but it carries no memos: what the role does
    ;; with them there is up to the conversation, and nothing counts against
    ;; them when it ends.
    (when (and chat (not= operator actor))
      (reject! :forbidden "Only the operator can open a conversation."))
    (let [memos (cond chat [] dispatched (waiting-memos state config role-id now) :else (deliverable-memos state config role-id))
          resolves (when-not chat (mapv :id (pending-resolutions state role-id)))]
      (if (and (empty? memos) (blank? note) (not chat) (empty? resolves))
        tx
        (emit tx "wake/started" (cond-> {:role role-id :memos (mapv :id memos) :note note
                                         :resumes (when-not chat (:interrupted-wake (role state role-id)))
                                         :harness harness :pid pid :pid-start pid-start}
                                  chat (assoc :chat true)
                                  (seq resolves) (assoc :resolves resolves)))))))

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
                    (dead-letter memo-id "never-closed" (:said-labels tx)
                                 :transcript-tail said :wake (:id wake)))
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
                  :harness harness :labels (:said-labels tx))
          :else tx)))))

(defn- held-call?
  "A held call relay can record: with an id, a tool name, and the digest
  of its input that the decision is bound to. Without the digest it is
  not recorded, since a decision could not be tied to it."
  [c]
  (and (map? c) (string? (:id c)) (not (blank? (:id c))) (string? (:name c)) (not (blank? (:name c)))
       (string? (:input-sha256 c)) (not (blank? (:input-sha256 c)))))

(defn- open-hold
  "Records a call held in a wake's session, unless it is already known: a
  hold still open stays as it is, and one decided and carried back that is
  held again was never received, so it is carried back again."
  [{:keys [state] :as tx} wake session {:keys [id name input input-sha256]}]
  (let [known (first (filter #(and (= session (:session %)) (= id (:call %))) (vals (:holds state))))]
    (cond
      (nil? known)
      (let [tx (emit tx "hold/opened" {:role (:role wake) :wake (:id wake) :session session
                                       :call id :tool name :input input :input-sha256 input-sha256}
                     (:said-labels tx))
            hold-id (last-id tx)]
        (notify tx "hold-opened" (str (:role wake) " wants to use " name)
                (str "It is waiting for you to decide.\n\n" (pr-str input)
                     "\n\nrelay approve " hold-id ", or relay deny " hold-id " [why].")
                :role (:role wake) :hold hold-id
                :labels (union (:said-labels tx) (id-labels state (:role wake)))))
      ;; Carried back again only if it is the same call, by its digest.
      (and (not= :open (:status known)) (:delivered known) (= input-sha256 (:input-sha256 known)))
      (emit tx "hold/requeued" {:hold (:id known)})
      :else tx)))

(defn- end-wake [{:keys [state config] :as tx} wake-id {:keys [outcome exit crashed cut-short session sessions said
                                                              transcript resets-at held labels]}]
  (let [wake (get-in state [:wakes wake-id])
        ;; What the session said, and the input of any call it left held,
        ;; are its own text, with the labels it had gathered by the end:
        ;; as its harness reports them, or when it does not, every label
        ;; it could have gathered.
        tx (assoc tx :said-labels (if (and (sequential? labels) (every? label-names labels))
                                    (union labels)
                                    (session-labels-bound state config (:role wake))))]
    (when-not (harness-outcomes outcome)
      (reject! (str "Unknown harness outcome " (pr-str outcome) ".")))
    ;; Whatever the harness said is held, the wake ends: only a wake that
    ;; ran and has a session to resume can leave calls held, and anything
    ;; else in the list is not a call.
    (let [held (when (and (sequential? held) (= "ran" outcome) (not (:chat wake)) (not (blank? session)))
                 (filterv held-call? held))]
    ;; A conversation records every session it used, in order, instead of
    ;; the one session a wake resumes from.
    (when (some? sessions)
      (when-not (and (sequential? sessions) (every? #(and (string? %) (not (blank? %))) sessions))
        (reject! (str "Sessions must be a list of session ids, not " (pr-str sessions) ".")))
      (when-not (:chat wake)
        (reject! "Only a conversation records a list of sessions.")))
    (as-> tx tx
      (emit tx "wake/ended" (cond-> {:wake wake-id :outcome outcome :exit exit :crashed (boolean crashed)
                                     :cut-short (boolean cut-short) :session session :said said
                                     :transcript transcript}
                              (some? sessions) (assoc :sessions (vec sessions))
                              (seq held) (assoc :held (mapv :id held)))
            (:said-labels tx))
      (note-harness tx (:harness wake) outcome resets-at said)
      (reduce #(open-hold %1 wake session %2) tx held)
      ;; A wake stopped for the operator has not failed its memos: it is
      ;; resumed once they decide.
      (if (and (= "ran" outcome) (empty? held)) (settle-memos tx wake said) tx)
      (dissoc tx :said-labels)))))

;; the operator's say over contaminated text

(defn- operator-only! [{:keys [actor]} what]
  (when-not (= operator actor)
    (reject! :forbidden (str "Only the operator " what "."))))

(defmethod decide* :memo/release [{:keys [config] :as tx} {{id :memo} :args}]
  (operator-only! tx "releases a quarantined memo")
  (let [m (open-memo! tx id)]
    (when-not (quarantined? config m)
      (reject! :invalid (str "Memo " id " is not quarantined.")))
    (emit tx "memo/released" {:memo id})))

(def trust-kinds #{"memo" "fact" "doc" "note" "routine" "role" "journal"})

(defn trusted-version
  "What `relay trust <kind> <ref>` is about, and its labels: a memo, a
  fact's current version, a doc's latest version, a note or routine named
  owner/name, a role, or a journal entry by number. Nil when there is none."
  [state kind ref]
  (let [num (some-> ref str parse-long)
        [owner nm] (str/split (str ref) #"/" 2)]
    (case kind
      "memo" (when-let [m (memo state num)]
               {:version (:id m) :labels (:labels m) :text (str (:subject m) "\n\n" (:body m))})
      "fact" (when-let [v (some-> (fact state num) (fact-as-known nil))]
               {:version (:seq v) :labels (:labels v) :text (str (when (:topic v) (str "[" (:topic v) "] ")) (:text v)
                                               (when (:why v) (str "\n\nWhy it changed: " (:why v))))})
      "doc" (when-let [v (doc-version state ref {})]
              (when-not (:removed v)
                {:version (:seq v) :labels (union (:labels v) (path-labels state ref)) :text (:content v)}))
      "note" (when-let [n (get-in state [:notes owner nm])]
               {:version (:version n) :labels (:labels n) :text (:text n)})
      "routine" (when-let [r (routine state owner nm)]
                  {:version (:version r) :labels (:labels r) :text (str (:schedule r) "\n" (:instructions r))})
      "role" (when-let [r (role state ref)]
               {:version (:version r) :labels (role-labels state ref)
                :text (str ref "\n" (:title r) "\n\n" (:instructions r))})
      "journal" (when-let [e (first (filter #(= num (:id %)) (mapcat val (:journals state))))]
                  {:version (:id e) :labels (:labels e) :text (:text e)})
      nil)))

(defn sha256
  "The hex sha256 of a text's UTF-8 bytes."
  [s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes (str s) java.nio.charset.StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defmethod decide* :label/trust [{:keys [state] :as tx} {{:keys [kind ref version] digest :sha256} :args}]
  ;; The operator has read it and vouches for it. That clears
  ;; contaminated from what it says now, and never private.
  (operator-only! tx "vouches for contaminated text")
  (when-not (trust-kinds kind)
    (reject! (str "Trust what? One of " (str/join ", " (sort trust-kinds)) ".")))
  (let [v (trusted-version state kind ref)]
    (when-not v (reject! :not-found (str "There is no " kind " " ref ".")))
    (when-not (contaminated? (:labels v))
      (reject! :invalid (str "The " kind " " ref " is not contaminated.")))
    ;; The operator vouches for the text they were shown, and only that:
    ;; if it changed since, nothing is trusted.
    (when-not (and (= version (:version v)) (= digest (sha256 (:text v))))
      (reject! :conflict (str "The " kind " " ref " changed since you read it. Nothing was trusted; look again.")))
    (emit tx "label/trusted" {:kind kind :ref (str ref) :version version})))

;; permission requests in a conversation
;;
;; agentloop asks the ACP client about a held call itself. relay acp
;; records each request and its answer, so the log says who allowed what.

(defn- permission-labels
  "The labels of a permission request's text: the session's as agentloop
  sent them, or with none valid, every label the session could hold."
  [{:keys [state config]} role labels]
  (if (and (sequential? labels) (seq labels) (every? label-names labels))
    (union labels)
    (session-labels-bound state config role)))

(defmethod decide* :permission/asked [{:keys [state] :as tx} {{:keys [wake session call tool input-sha256 labels]} :args}]
  (dispatcher-only! tx)
  (let [role (get-in state [:wakes wake :role])]
    (when-not (get-in state [:wakes wake :chat]) (reject! "Only a conversation asks its client."))
    (emit tx "permission/asked" {:wake wake :role role :session session :call call :tool tool
                                 :input-sha256 input-sha256}
          (permission-labels tx role labels))))

(defmethod decide* :permission/answered [{:keys [state] :as tx} {{:keys [wake session call tool outcome by labels]} :args}]
  (dispatcher-only! tx)
  (let [role (get-in state [:wakes wake :role])]
    (when-not (get-in state [:wakes wake :chat]) (reject! "Only a conversation asks its client."))
    (cond-> (emit tx "permission/answered" {:wake wake :role role :session session :call call
                                            :outcome outcome :by by}
                  (permission-labels tx role labels))
      ;; relay refused it for a client that cannot ask the operator.
      (= "relay" by)
      (notify "permission-refused" (str role " was refused a call to " tool " in a conversation")
              (str "Its ACP client was not started with --approvals, so relay refused the call"
                   " for it. To decide such calls yourself, talk with " role " through relay chat.")
              :role role :labels (union (id-labels state role) (permission-labels tx role labels))))))

;; holds

(defmethod decide* :hold/resolve [{:keys [state actor] :as tx} {{hold-id :hold :keys [verdict reason]} :args}]
  ;; A person decides, never a model or a tool: only the operator, at the
  ;; command line, can resolve a hold.
  (when-not (= operator actor)
    (reject! :forbidden "Only the operator decides on a held tool call."))
  (let [h (hold state hold-id)]
    (when-not h (reject! :not-found (str "There is no hold " hold-id ".")))
    (when-not (= :open (:status h))
      (reject! :invalid (str "Hold " hold-id " was already decided: " (name (:status h)) ".")))
    (when-not (#{"allow" "deny"} verdict)
      (reject! (str "A hold is resolved with allow or deny, not " (pr-str verdict) ".")))
    (emit tx "hold/resolved" (cond-> {:hold hold-id :verdict verdict}
                               (not (blank? reason)) (assoc :reason (str/trim reason))))))

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
                                      :routine name}
                                  (:labels r))
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
              (if (and (seq (waiting-memos state config id now))
                       (not (running? state id))
                       (running-away? state id config now)
                       (or (nil? reported) (not (after? (.plus ^Instant reported window) now))))
                (notify tx "runaway-guard-tripped" (str id " was stopped by the runaway guard")
                        (str "It has been woken more often in the last "
                             (:runaway-window-minutes config) " minutes than ordinary use "
                             "explains. Its work is waiting and nothing was dropped.")
                        :role id :labels (id-labels state id))
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

(def conversation-gone
  "What a conversation the tick found lost says: its client stopped relay
  before relay could record the end."
  "The conversation's client went away without letting relay record the end.")

(defmethod decide* :tick [{:keys [state] :as tx} {{:keys [lost-wakes]} :args}]
  (dispatcher-only! tx)
  (as-> tx tx
    ;; A wake whose runner process is gone ended without saying how. It
    ;; counts against its memos, like a crash, so a memo that kills its
    ;; runner every time is still dead-lettered in the end. A conversation
    ;; carries no memos, and its process belongs to its client, which may
    ;; close it by killing it outright (Zed kills the whole process group),
    ;; so a lost one is the client going away, not a crash.
    (reduce (fn [tx id]
              (let [wake (get-in (:state tx) [:wakes id])]
                (cond
                  (not= :running (:status wake)) tx
                  (:chat wake) (end-wake tx id {:outcome "ran" :crashed false :said conversation-gone})
                  :else (end-wake tx id {:outcome "ran" :crashed true
                                         :said "The process running this wake disappeared."}))))
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

;; knowledge

(defn world-time
  "A time in the world, as an Instant: an ISO instant, or a date, or a date
  and time, in the operator's zone. Nil for nothing."
  ^Instant [config s what]
  (when-not (blank? s)
    (let [s (str/trim s)
          zone (config/zone config)
          attempt (fn [f] (try (f) (catch Exception _ nil)))]
      (or (attempt #(Instant/parse s))
          (attempt #(.toInstant (.atZone (LocalDateTime/parse (str/replace s " " "T")) zone)))
          (attempt #(.toInstant (.atStartOfDay (LocalDate/parse s) zone)))
          (reject! (str what " is not a time relay understands: " (pr-str s) ". Use 2026-10-14, "
                        "2026-10-14 09:00 or 2026-10-14T13:00:00Z."))))))

(def ^:private topic-pattern #"[a-z0-9][a-z0-9-]*(/[a-z0-9][a-z0-9-]*)*")

(defn- fact-topic [topic]
  (when-not (blank? topic)
    (let [t (str/trim topic)]
      (when-not (and (<= (count t) 100) (re-matches topic-pattern t))
        (reject! (str "A topic is lower case words joined by hyphens, with / between levels,"
                      " such as household/car. " (pr-str t) " is not one.")))
      t)))

(defn- fact-text [s]
  (let [t (require-text s "A fact")]
    (when (> (count t) 2000) (reject! "A fact is at most 2000 characters. Put longer content in a doc."))
    t))

(defn- validity!
  "Checks a fact's validity and returns it as strings for an event."
  [from to]
  (when (and from to (not (after? to from)))
    (reject! "A fact's valid-to must come after its valid-from."))
  {:valid-from (some-> from str) :valid-to (some-> to str)})

(defn- known-fact! [state id]
  (let [f (fact state id)]
    (when-not f (reject! :not-found (str "There is no fact " id ".")))
    (let [v (fact-as-known f nil)]
      (when (:retracted v) (reject! :invalid (str "Fact " id " was retracted.")))
      v)))

(defmethod decide* :fact/add [{:keys [config] :as tx} {{:keys [topic text valid-from valid-to]} :args}]
  (emit tx "fact/added" (merge {:topic (fact-topic topic) :text (fact-text text)}
                               (validity! (world-time config valid-from "valid-from")
                                          (world-time config valid-to "valid-to")))))

(defmethod decide* :fact/supersede [{:keys [state config now] :as tx}
                                    {{:keys [fact topic text valid-from valid-to]} :args}]
  (let [old (known-fact! state fact)
        from (or (world-time config valid-from "valid-from") now)]
    (when-let [by (:superseded-by old)]
      (reject! :conflict (str "Fact " fact " was already superseded by fact " by "; supersede that one.")))
    (when (and (:valid-from old) (not (after? from (:valid-from old))))
      (reject! :invalid (str "The change must come after fact " fact " began to hold, at " (:valid-from old)
                             ". If the fact was wrong from the start, correct it instead.")
               (content-labels (:labels old))))
    (when (and (:valid-to old) (after? from (:valid-to old)))
      (reject! :invalid (str "Fact " fact " had already stopped holding at " (:valid-to old)
                             "; add a new fact instead.")
               (content-labels (:labels old))))
    (emit tx "fact/superseded"
          (merge {:fact fact
                  :topic (if (blank? topic) (:topic old) (fact-topic topic))
                  :text (fact-text text)}
                 (validity! from (world-time config valid-to "valid-to")))
          ;; The old version's text is carried into the version that ends
          ;; it, and its topic into the new fact when none is given.
          (:labels old))))

(defn- bound
  "A validity bound for a correction: unchanged when not given, open for
  \"open\", and otherwise the time given."
  [config given current what]
  (cond (blank? given) current
        (= "open" (str/trim given)) nil
        :else (world-time config given what)))

(defn- reopen-superseded
  "When a fact that superseded another is retracted, the change never
  happened, so the fact it replaced is put back as it was before."
  [{:keys [state] :as tx} retracted-id old-id]
  (let [versions (get-in state [:facts old-id :versions])
        current (peek versions)
        before (last (take-while #(not= retracted-id (:superseded-by %)) versions))]
    (if (and before (= retracted-id (:superseded-by current)))
      (emit tx "fact/corrected"
            (merge (select-keys current [:topic :text])
                   {:fact old-id :superseded-by nil
                    :valid-from (some-> (:valid-from current) str)
                    :valid-to (some-> (:valid-to before) str)
                    :why (str "Fact " retracted-id ", which superseded it, was retracted.")})
            (:labels current))
      tx)))

(defmethod decide* :fact/correct [{:keys [state config] :as tx}
                                  {{:keys [fact topic text valid-from valid-to retract why]} :args}]
  (let [cur (known-fact! state fact)
        why (require-text why "Saying why the record was wrong")]
    (when (and retract (:supersedes cur) (:superseded-by cur))
      ;; In the middle of a chain of changes, putting back the fact it
      ;; replaced would leave that fact and its successor's both holding.
      (reject! (str "Fact " fact " replaced fact " (:supersedes cur) " and was itself superseded by fact "
                    (:superseded-by cur) ", so retracting it would leave two facts holding at once."
                    " Correct the facts' text or times instead.")))
    (if retract
      (as-> tx tx
        (emit tx "fact/corrected" (merge (select-keys cur [:topic :text])
                                         {:fact fact :retracted true :why why
                                          :superseded-by (:superseded-by cur)}
                                         (validity! (:valid-from cur) (:valid-to cur)))
              (:labels cur))
        (if-let [old (:supersedes cur)] (reopen-superseded tx fact old) tx))
      (let [corrected (merge {:topic (if (blank? topic) (:topic cur) (fact-topic topic))
                              :text (if (blank? text) (:text cur) (fact-text text))}
                             (validity! (bound config valid-from (:valid-from cur) "valid-from")
                                        (bound config valid-to (:valid-to cur) "valid-to")))]
        (when (= corrected (merge (select-keys cur [:topic :text])
                                  (validity! (:valid-from cur) (:valid-to cur))))
          (reject! :invalid "That correction changes nothing." (content-labels (:labels cur))))
        ;; A correction keeps whatever it does not change (the times, the
        ;; topic, the text, the link to its successor), so it carries the
        ;; labels of the version it corrects.
        (emit tx "fact/corrected" (assoc corrected :fact fact :why why
                                         :superseded-by (:superseded-by cur))
              (:labels cur))))))

(def ^:private note-name-pattern #"[a-z0-9][a-z0-9-]{0,47}")

(defmethod decide* :note/write [tx {{:keys [owner name text]} :args}]
  (let [owner (owner-for tx owner "note")
        text (require-text text "A note")]
    (when-not (and (string? name) (re-matches note-name-pattern name))
      (reject! "A note's name is lower case letters, digits and hyphens."))
    (when (> (count text) 4000)
      (reject! "A note is at most 4000 characters, since notes go into every wake. Put more in a doc."))
    (if (= text (get-in (:state tx) [:notes owner name :text]))
      tx
      (emit tx "note/written" {:owner owner :name name :text text}))))

(defmethod decide* :note/remove [{:keys [state] :as tx} {{:keys [owner name]} :args}]
  (let [owner (owner-for tx owner "note")]
    (when-not (get-in state [:notes owner name])
      (reject! :not-found (str owner " has no note called " name ".")))
    (emit tx "note/removed" {:owner owner :name name})))

(defmethod decide* :journal/write [tx {{:keys [owner text]} :args}]
  (let [owner (owner-for tx owner "journal")
        text (require-text text "A journal entry")]
    (when (> (count text) 20000) (reject! "A journal entry is at most 20000 characters."))
    (emit tx "journal/written" {:owner owner :text text})))

(def ^:private doc-path-pattern #"[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)*")

(defn- doc-path [path]
  (let [p (some-> path str/trim)]
    (when-not (and p (<= (count p) 200) (re-matches doc-path-pattern p))
      (reject! (str "A doc's path is lower case names of letters, digits, dots, hyphens and"
                    " underscores, joined by /, such as household/insurance.md.")))
    p))

(defmethod decide* :doc/put [{:keys [state] :as tx} {{:keys [path content]} :args}]
  (let [path (doc-path path)]
    (when (blank? content) (reject! "A doc's content must not be empty. To remove a doc, remove it."))
    (when (> (count content) 500000) (reject! "A doc is at most 500000 characters."))
    (when-let [clash (first (filter #(or (str/starts-with? (:path %) (str path "/"))
                                         (str/starts-with? path (str (:path %) "/")))
                                    (docs state nil)))]
      (reject! :conflict (str path " would sit where " (:path clash) " is, as a file and a"
                              " folder at once. Choose another path.")
               (union (version-labels state clash) (path-labels state (:path clash)))))
    (if (= content (:content (doc-version state path {})))
      tx
      (emit tx "doc/put" {:path path :content content}))))

(defmethod decide* :doc/remove [{:keys [state] :as tx} {{:keys [path]} :args}]
  (let [path (doc-path path)
        v (doc-version state path {})]
    (when (or (nil? v) (:removed v)) (reject! :not-found (str "There is no doc at " path ".")))
    (emit tx "doc/removed" {:path path})))
