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
            [relay.reports :as reports]
            [relay.schedule :as schedule]
            [relay.text :as text])
  (:import (java.time Duration Instant LocalDate LocalDateTime)))

(def operator "operator")
(def dispatcher "dispatcher")
(def reserved-ids config/reserved-role-ids)

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

(defn private? [labels] (boolean (some #{private-label} labels)))

(defn item-labels
  "What reading one item of relay content (a memo, a version of a fact or
  doc, a note, a journal entry, a routine) adds: its writers' labels, and
  private unless the item is public. A public item was written by the
  operator with --public, by a public role's session, or declassified by
  the operator, and its labels never hold private."
  [item & colls]
  (if (:public item)
    (apply union (:labels item) colls)
    (apply content-labels (:labels item) colls)))

(def harness-outcomes
  "How a wake's harness run ended. Only \"ran\" means the role got to work;
  the others are the harness's own failures and count against no memo."
  #{"ran" "limited" "unreachable" "needs-attention"})

(def empty-state
  {:roles {} :memos {} :routines {} :wakes {} :dead-letters {} :harnesses {}
   :notifications {} :guards {} :facts {} :notes {} :journals {} :docs {} :holds {}
   :reports {} :ledger {} :trust-levels {}
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

(declare quarantined? awaiting-declassification?)

(declare held-memo-ids)

(defn waiting-memos
  "Open memos a role may be shown that are not waiting out a retry delay,
  and are not kept by a session with a call held for the operator."
  [state config to now]
  (let [held (held-memo-ids state to)]
    (vec (remove #(or (backing-off? % now) (quarantined? config %) (awaiting-declassification? config %)
                      (held (:id %)))
                 (unread-memos state to)))))

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
       (filter #(and (= role-id (:role %)) (#{:allowed :denied} (:status %)) (nil? (:delivered %))
                     (not (:abandoned %))))
       (sort-by :id)
       vec))

(defn- unsettled-hold?
  "A hold whose session is not done with it: still open, or decided and
  not yet carried back."
  [h]
  (or (= :open (:status h))
      (and (#{:allowed :denied} (:status h)) (nil? (:delivered h)) (not (:abandoned h)))))

(defn held-wakes
  "The wakes whose sessions wait on a hold of a role's."
  [state role-id]
  (set (keep #(when (and (= role-id (:role %)) (unsettled-hold? %)
                         (not (get-in state [:ended-sessions (:session %)])))
                (:wake %))
             (vals (:holds state)))))

(defn held-memo-ids
  "The memos kept by a role's held sessions. Each held session keeps the
  memos it carried; the role's other memos go on without it."
  [state role-id]
  (set (mapcat #(get-in state [:wakes % :memos]) (held-wakes state role-id))))

(defn kept-memo-ids
  "The memos a role's running wake may not touch: those kept by a held
  session other than the one it carries on. All of them when it is not
  running."
  [state role-id]
  (let [running (running-wake state role-id)]
    (set (mapcat #(get-in state [:wakes % :memos])
                 (disj (held-wakes state role-id) (:resumes running))))))

(defn resumable-held-session
  "A held session of a role's whose every hold is decided, ready to be
  carried on with the decisions: {:wake :holds}, the oldest such, or nil.
  An ended session is never carried on."
  [state role-id]
  (let [hs (filter #(and (= role-id (:role %)) (unsettled-hold? %)
                         (not (get-in state [:ended-sessions (:session %)])))
                   (vals (:holds state)))
        by-session (group-by :session hs)]
    (->> by-session
         (keep (fn [[_ hs]] (when (every? #(not= :open (:status %)) hs)
                              {:wake (apply max (map :wake hs)) :holds (vec (sort-by :id hs))})))
         (sort-by :wake)
         first)))

(declare unwakeable-public-role? sessions-from-before-public)

(defn roles-to-wake
  "The roles the dispatcher should wake now: with a memo waiting or a held
  session whose calls are all decided, not already running, their harness
  not paused, and not stopped by the runaway guard. A call held for the
  operator keeps only its own session's memos waiting. A public role whose
  own text holds private is never woken (unwakeable-public-role?)."
  [state config now]
  (vec (for [id (active-role-ids state)
             :when (and (or (seq (waiting-memos state config id now)) (resumable-held-session state id)
                            ;; Woken to end them, so their memos go on.
                            (seq (sessions-from-before-public state config id)))
                        (not (running? state id))
                        (not (paused-until state (harness-of state config id) now))
                        (not (running-away? state id config now))
                        (not (unwakeable-public-role? state config id)))]
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

(declare contaminated?)

(defn fact-as-shown
  "The fact as a reader is shown it. With `clean`, a contaminated change
  never hides a clean record: the latest version not written by a
  contaminated session, marked :newer-withheld when a contaminated one
  came after it. A fact no clean session ever wrote is shown as it is,
  for the reader to withhold whole."
  [{:keys [versions] :as f} known-at clean]
  (let [latest (fact-as-known f known-at)]
    (if (or (not clean) (nil? latest) (not (contaminated? (:labels latest))))
      latest
      (let [vs (if known-at (take-while #(not-after? (:recorded-at %) known-at) versions) versions)]
        (if-let [v (last (remove #(contaminated? (:labels %)) vs))]
          (assoc (merge (select-keys f [:id :supersedes]) v) :newer-withheld true)
          latest)))))

(declare fact-shown-to-public?)

(defn facts
  "The facts holding at world time :as-of, as recorded at :known-at, by id.
  With :all, every fact recorded by then and not retracted, whatever its
  validity. :topic keeps that topic and the topics under it; :search keeps
  facts whose text contains it, ignoring case. With :clean, each fact as
  fact-as-shown gives it, so a contaminated change does not hide it. With
  :public-only, only facts a public role can see now (fact-shown-to-public?
  of the current version, as fact_history asks) whose version shown is
  public too, before any search, so a search or an earlier known_at says
  nothing about the others."
  [state {:keys [as-of known-at topic search all clean public-only]}]
  (let [as-of (or as-of known-at (:last-at state))
        needle (some-> search str/lower-case)]
    (->> (vals (:facts state))
         (filter #(or (not public-only) (fact-shown-to-public? state (fact-as-known % nil))))
         (keep #(fact-as-shown % known-at clean))
         (filter #(or (not public-only) (and (:public %) (not (private? (item-labels %))))))
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

(defn doc-as-shown
  "A doc's latest version as a reader is shown it. With `clean`, a
  contaminated change (a new version or a removal) never hides a clean
  one: the latest version not written by a contaminated session, marked
  :newer-withheld. A doc no clean session wrote is shown as it is."
  [state path clean]
  (let [latest (doc-version state path {})]
    (if (or (not clean) (nil? latest) (not (contaminated? (:labels latest))))
      latest
      (if-let [v (last (remove #(contaminated? (:labels %)) (get-in state [:docs path :versions])))]
        (assoc v :newer-withheld true)
        latest))))

(defn docs
  "The docs that exist, as recorded at `known-at` if given, by path. With
  `clean`, as doc-as-shown gives them, so a contaminated removal or
  overwrite does not hide a clean doc."
  ([state known-at] (docs state known-at false))
  ([state known-at clean]
   (->> (keys (:docs state))
        (keep #(if clean (doc-as-shown state % true) (doc-version state % {:known-at known-at})))
        (remove :removed)
        (sort-by :path)
        vec)))

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
  "What reading a memo addressed to you adds: its writers' labels, private
  unless it is public, and the labels of its sender's id as shown
  (shown-id), or for a declassified memo, those the operator left on it."
  [state m]
  (item-labels m (or (:from-labels m) (second (shown-id state (:from m))))))

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

(defn public-role?
  "Whether a role is public (relay.config/public?). Its sessions never hold
  private, so it is shown only public items and has nothing private to
  leak: no memo to it is quarantined, and its contaminated notes are not
  withheld from it."
  [config role-id]
  (config/public? config role-id))

(defn quarantined?
  "A contaminated memo to a role that quarantines, not yet released. A
  memo to a public role is never quarantined: that role holds no private
  data for the memo's writer to make it send out."
  [config m]
  (and (not= operator (:to m)) (contaminated? (:labels m)) (not (:released m))
       (not (public-role? config (:to m)))
       (quarantines? config (:to m))))

(defn awaiting-declassification?
  "A memo to a public role that is not public: it waits, unseen by the
  role, until the operator declassifies it (relay declassify) or closes
  it."
  [config m]
  (and (not= operator (:to m)) (not (:public m)) (public-role? config (:to m))))

(defn public-caller?
  "Whether a role's call is to be treated as a public role's: shown only
  public items and refused anything private. It is when relay.edn makes
  the role public now, or when the wake that made the call (named in the
  tool command relay gave the harness) began public, so that changing
  relay.edn while a session runs never widens what that session sees."
  [state config actor wake-id]
  (boolean
   (and (not= operator actor) (not= dispatcher actor)
        (or (public-role? config actor)
            (let [w (get-in state [:wakes wake-id])]
              (and (= actor (:role w)) (:public-session w)))))))

(defn shown-to-public?
  "Whether a public role may be shown an item: public, and nothing it
  shows carries private (`labels`, its labels as read)."
  [item labels]
  (and (:public item) (not (private? labels))))

(defn deliverable-memos
  "Open memos to a role that it may be shown, oldest first."
  [state config to]
  (filterv #(not (or (quarantined? config %) (awaiting-declassification? config %))) (unread-memos state to)))

(defn version-labels
  "What showing one version of a fact or doc adds, with who wrote it."
  [state v]
  (union (:labels v) (id-labels state (:actor v))))

(defn path-labels
  "What showing a doc's path adds: the labels of whoever first chose it."
  [state path]
  (or (get-in state [:docs path :path-labels]) []))

(defn doc-shown-to-public?
  "Whether a public role may be shown a version of a doc: the version is
  public, and neither it, its writer's id nor the doc's path carries
  private."
  [state path v]
  (boolean (and v (get-in state [:docs path :path-public])
                (shown-to-public? v (union (item-labels v) (version-labels state v) (path-labels state path))))))

(defn fact-shown-to-public?
  "Whether a public role may be shown a version of a fact."
  [state v]
  (boolean (and v (shown-to-public? v (union (item-labels v) (version-labels state v))))))

(defn notes-labels
  "What showing these notes adds."
  [notes]
  (apply union (map item-labels notes)))

(defn journal-labels [entries]
  (apply union (map item-labels entries)))

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
  many are withheld as contaminated. A public role is shown its public
  notes, contaminated or not, since it has nothing private to leak, and
  its other notes are not even counted."
  [state config owner]
  (if (public-role? config owner)
    {:shown (filterv #(shown-to-public? % (item-labels %)) (notes state owner)) :withheld 0}
    (let [{withheld true shown false} (group-by #(contaminated? (:labels %)) (notes state owner))]
      {:shown (vec shown) :withheld (count withheld)})))

(defn wake-labels
  "The labels of everything a wake's prompts are made of: the role's id,
  title and instructions, the notes shown, the memos it carries and the
  ids of their senders, the routines they are runs of, the note from
  whoever woke it unless it was public, and the operator's reasons on the
  holds it carries back, unless the operator gave them knowing the role
  was public. A conversation has no prompt, only the instructions and
  notes."
  [state config {:keys [role memos note note-public chat resolves]}]
  (let [ms (keep #(memo state %) memos)]
    (union (role-labels state role)
           (notes-labels (:shown (prompt-notes state config role)))
           (mapcat #(memo-labels state %) ms)
           ;; A public memo shows only its own text (a run's subject is the
           ;; routine's name), which its labels already cover.
           (mapcat #(when (and (:routine %) (not (:public %))) (routine-labels state (:to %) (:routine %))) ms)
           (when (and (not chat) (not (blank? note)) (not note-public)) [private-label])
           (when (some #(let [h (hold state %)]
                          ;; A reason the operator gave knowing the role was
                          ;; public is public; any other may be private.
                          (and (not (blank? (:reason h))) (not (:reason-public h))))
                       resolves)
             [private-label]))))

(defn role-id-public?
  "Whether a role's id may be shown to a public role: the operator wrote
  it with --public, or vouched for it so by rewriting the role's title and
  instructions with --public. Role text is not content and carries no
  private floor, and any role's session may have taken in the operator's
  own unlabelled AGENTS.md, so only what the operator marked public is."
  [state id]
  (let [r (role state id)]
    (boolean (and r (contains? (:public-text r) :id) (not (private? (:id-labels r)))))))

(defn role-text-public?
  "Whether a role's id, title and instructions may be shown to a public
  role: all three are public (role-id-public?), and none carries private."
  [state id]
  (let [r (role state id)]
    (boolean (and r (= #{:id :title :instructions} (:public-text r)) (not (private? (role-labels state id)))))))

(defn unwakeable-reason
  "Why a public role cannot run as one, saying what fixes it, or nil when it
  can. Its own id, title and instructions are in every prompt, so all must
  be public; and a :script harness is any program, which could claim any
  labels with no agentloop to refuse private for it."
  [state config role-id]
  (when (public-role? config role-id)
    (let [r (role state role-id)]
      (cond
        (= :script (get-in config [:harnesses (keyword (harness-of state config role-id)) :kind]))
        (str role-id " runs on a :script harness. Give it an agentloop harness with relay update "
             role-id " --harness <name>.")
        (private? (:id-labels r))
        (str "the id " role-id " was chosen by a session holding private data, so it can never be"
             " public. Add the role again under a new id with relay add <id> --public, and remove this one.")
        (not (role-text-public? state role-id))
        (str "its id, title and instructions were not all written with --public. Run relay update "
             role-id " --public --title <title> --instructions, with its instructions on stdin.")))))

(defn unwakeable-public-role?
  "A public role that cannot be run as one (unwakeable-reason). It is not
  woken until that is put right, and the tick tells the operator once."
  [state config role-id]
  (some? (unwakeable-reason state config role-id)))

;; --- evolve

(defmulti evolve* (fn [_state event] (:type event)))

(defmethod evolve* :default [_ event]
  (throw (ex-info (str "This version of relay does not know event type " (:type event)
                       " (event " (:seq event) "). Upgrade relay before reading this log.")
                  {:event event})))

(def legacy-labels
  "What anything a role or the dispatcher wrote before relay kept labels
  is taken to have seen: no record says otherwise, so everything."
  [contaminated-label private-label])

(defn- with-legacy-labels
  "An event written before the log's labels/enabled marker, by anyone but
  the operator, carries legacy-labels: relay did not record what it had
  seen. The operator can trust what they have read."
  [{:keys [legacy-before]} {:keys [seq actor] :as event}]
  (if (and legacy-before (< seq legacy-before) (not= operator actor))
    (-> event (update :labels union legacy-labels) (dissoc :public))
    event))

(def public-item-events
  "The events that write an item a public role may be shown: these carry
  :public when their writer was public (decide sets it, never a role's
  input), and the item keeps it."
  #{"memo/sent" "routine/written" "fact/added" "fact/superseded" "fact/corrected"
    "note/written" "journal/written" "doc/put" "doc/removed" "role/added" "role/updated"
    "report/submitted"})

(defn evolve [state {:keys [seq at] :as event}]
  (when-not (= seq (:next-seq state))
    (throw (ex-info (str "Event " seq " is out of order; expected " (:next-seq state) ".")
                    {:event event})))
  (let [{:keys [labels] :as event} (with-legacy-labels state event)]
  ;; A public item never carries private: decide refuses what would make
  ;; one, so this can only be a bug, and nothing is folded past it.
  (when (and (:public event) (or (private? labels) (not (public-item-events (:type event)))))
    (throw (ex-info (str "Event " seq " is public and carries private, or is not an item a public role"
                         " may read. relay refuses to read it.")
                    {:event event})))
  (-> (evolve* state event)
      (update :seen-labels union labels)
      (assoc :next-seq (inc seq) :last-at (inst at)))))

(defmethod evolve* "labels/enabled" [state {:keys [seq]}]
  (update state :labels-enabled-at #(or % seq)))

(defn replay
  "The state the events fold to. The events before the log's first
  labels/enabled marker, if it has one, were written before relay kept
  labels (with-legacy-labels)."
  [events]
  (let [marker (some #(when (= "labels/enabled" (:type %)) (:seq %)) events)]
    (reduce evolve (cond-> empty-state marker (assoc :legacy-before marker)) events)))

(defn needs-labels-marker?
  "Whether a log has no labels/enabled marker yet. This relay writes one
  into every log it opens: first thing in a new log, and after the old
  events in a log from before relay kept labels."
  [state]
  (nil? (:labels-enabled-at state)))

(defmethod evolve* "role/added" [state {:keys [seq at data labels public]}]
  (let [{:keys [id title instructions reports-to harness model]} data]
    (assoc-in state [:roles id] {:id id :title title :instructions instructions :reports-to reports-to
                                 :harness harness :model model :status :active
                                 :added-at (inst at) :version seq
                                 ;; Each piece of text carries the labels of
                                 ;; whoever wrote what it says now.
                                 :id-labels (union labels) :title-labels (union labels)
                                 :instructions-labels (union labels)
                                 ;; Which of its texts the operator wrote
                                 ;; with --public.
                                 :public-text (if public #{:id :title :instructions} #{})})))

(defmethod evolve* "role/updated" [state {:keys [seq data labels public]}]
  (let [mark (fn [r k] (update r :public-text (fnil (if public conj disj) #{}) k))]
    (update-in state [:roles (:id data)]
               #(cond-> (merge % (select-keys data [:title :instructions :reports-to :harness :model]))
                  (or (contains? data :title) (contains? data :instructions)) (assoc :version seq)
                  (contains? data :title) (-> (assoc :title-labels (union labels)) (mark :title))
                  (contains? data :instructions) (-> (assoc :instructions-labels (union labels)) (mark :instructions))
                  ;; Rewriting both with --public, the operator has the id in
                  ;; front of them too, and vouches for it.
                  (and public (contains? data :title) (contains? data :instructions)) (mark :id)))))

(defmethod evolve* "role/removed" [state {:keys [at data]}]
  (update-in state [:roles (:id data)] assoc :status :removed :removed-at (inst at)))

(defmethod evolve* "memo/sent" [state {:keys [seq at data labels public]}]
  (let [{:keys [from to subject body routine]} data]
    (assoc-in state [:memos seq] {:id seq :from from :to to :subject subject :body body
                                  :routine routine :sent-at (inst at) :status :unread
                                  :attempts 0 :retry-at nil :labels (union labels)
                                  :public (true? public)})))

(defmethod evolve* "memo/closed" [state {:keys [at actor data labels]}]
  (update-in state [:memos (:memo data)] assoc
             :status :read :outcome (:outcome data) :closed-at (inst at) :closed-by actor
             :retry-at nil :outcome-labels (union labels)))

(defmethod evolve* "memo/released" [state {:keys [data]}]
  (assoc-in state [:memos (:memo data) :released] true))

(defmethod evolve* "memo/declassified" [state {:keys [data]}]
  ;; The operator read it and vouches that it holds nothing private, its
  ;; sender's id included. Contaminated stays.
  (update-in state [:memos (:memo data)]
             #(assoc % :public true :declassified true
                     ;; The text as the operator was shown it.
                     :subject (or (:subject data) (:subject %)) :body (or (:body data) (:body %))
                     :labels (vec (remove #{private-label} (:labels %)))
                     :from-labels (vec (remove #{private-label} (:from-labels data))))))

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

(defmethod evolve* "routine/written" [state {:keys [seq at data labels public]}]
  (let [{:keys [owner name schedule instructions]} data]
    (update-in state [:routines owner name]
               (fn [r] (assoc r :owner owner :name name :schedule schedule
                              :instructions instructions :baseline (inst at)
                              :status :active :labels (union labels) :version seq
                              :public (true? public))))))

(defmethod evolve* "routine/removed" [state {:keys [data]}]
  (assoc-in state [:routines (:owner data) (:name data) :status] :removed))

(defmethod evolve* "routine/fired" [state {:keys [at data]}]
  (let [{:keys [owner name memo one-off]} data]
    (update-in state [:routines owner name] assoc
               :baseline (inst at) :last-memo memo
               :status (if one-off :retired :active))))

(defmethod evolve* "wake/started" [state {:keys [seq at data]}]
  (let [{:keys [role memos note note-public public-session resumes harness pid pid-start chat resolves]} data]
    (cond-> (assoc-in state [:wakes seq] {:id seq :role role :memos (vec memos) :note note
                                          :note-public (true? note-public)
                                          :public-session (true? public-session)
                                          :resumes resumes :harness harness :pid pid
                                          :pid-start pid-start :started-at (inst at)
                                          :status :running :chat (boolean chat)
                                          :resolves (vec resolves)})
      ;; A conversation leaves an interrupted wake to be resumed later, and
      ;; so does carrying on a held session: only the wake that carries
      ;; it on, or a wake that carries on no held session, clears it.
      (and (not chat) (or (empty? resolves) (= resumes (get-in state [:roles role :interrupted-wake]))))
      (assoc-in [:roles role :interrupted-wake] nil)
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
  (let [{:keys [role wake session call tool input input-sha256 label-sources]} data]
    (assoc-in state [:holds seq] {:id seq :role role :wake wake :session session :call call
                                  :tool tool :input input :input-sha256 input-sha256
                                  :label-sources label-sources
                                  :status :open :opened-at (inst at) :labels (union labels)})))

(defmethod evolve* "hold/resolved" [state {:keys [at actor data]}]
  (let [{:keys [hold verdict reason reason-public]} data]
    (update-in state [:holds hold] assoc
               :status (if (= "allow" verdict) :allowed :denied) :reason reason :reason-public (true? reason-public)
               :resolved-at (inst at) :resolved-by actor)))

(defmethod evolve* "permission/asked" [state {:keys [seq data]}]
  (assoc-in state [:permissions seq] (assoc data :id seq)))

(defmethod evolve* "permission/answered" [state {:keys [data]}]
  (let [asked (->> (vals (:permissions state))
                   (filter #(and (= (:wake data) (:wake %)) (= (:call data) (:call %)) (nil? (:outcome %))))
                   (sort-by :id) last)]
    (cond-> state
      asked (update-in [:permissions (:id asked)] assoc :outcome (:outcome data) :by (:by data)))))

(defmethod evolve* "conversation/line-dropped" [state _] state)

(defmethod evolve* "hold/session-ended" [state {:keys [data]}]
  (let [{:keys [role session]} data
        interrupted (get-in state [:roles role :interrupted-wake])]
    (cond-> (-> state
                (assoc-in [:ended-sessions session] true)
                (update :holds
                    (fn [hs] (update-vals hs #(cond-> % (and (= session (:session %)) (not= :open (:status %))
                                                              (nil? (:delivered %)))
                                                     (assoc :abandoned true))))))
      ;; The next wake starts afresh, carrying the role's open memos.
      (= session (get-in state [:wakes interrupted :session]))
      (assoc-in [:roles role :interrupted-wake] nil))))

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
      (assoc-in [:guards role] (inst at))
      (= "public-role-cannot-run" kind)
      (assoc-in [:unwakeable-told role] (:reason data))
      (= "public-role-can-run" kind)
      (update :unwakeable-told dissoc role))))

(defmethod evolve* "notification/delivered" [state {:keys [data]}]
  (assoc-in state [:notifications (:notification data) :delivery] :delivered))

(defmethod evolve* "notification/failed" [state {:keys [data]}]
  (update-in state [:notifications (:notification data)] assoc
             :delivery :failed :error (:error data)
             :attempts (:attempt data) :retry-at (inst (:retry-at data))))

(defn- fact-version [{:keys [seq at actor labels public]} change {:keys [topic text valid-from valid-to]}]
  {:seq seq :recorded-at (inst at) :actor actor :change change :labels (union labels) :public (true? public)
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
                     ;; decide marks it public only when the old version was.
                     :public (true? (:public event))
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

(defmethod evolve* "note/written" [state {:keys [seq at data labels public]}]
  (let [{:keys [owner name text]} data]
    (assoc-in state [:notes owner name] {:owner owner :name name :text text :written-at (inst at)
                                         :labels (union labels) :version seq :public (true? public)})))

(defmethod evolve* "note/removed" [state {:keys [data]}]
  (update-in state [:notes (:owner data)] dissoc (:name data)))

(defmethod evolve* "journal/written" [state {:keys [seq at data labels public]}]
  (update-in state [:journals (:owner data)] (fnil conj [])
             {:id seq :owner (:owner data) :text (:text data) :at (inst at) :labels (union labels)
              :public (true? public)}))

(defn- add-doc-version [state {:keys [seq at actor labels public]} path more]
  (update-in state [:docs path]
             (fn [d] (-> (assoc d :path path)
                         ;; The path is text too, chosen by whoever first wrote it.
                         (update :path-labels #(or % (union labels)))
                         (update :path-public #(if (some? %) % (true? public)))
                         (update :versions (fnil conj [])
                                 (merge {:seq seq :path path :at (inst at) :actor actor :labels (union labels)
                                         :public (true? public)}
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
   (let [event (cond-> {:seq (:next-seq state) :at (str now) :type type :actor actor
                        :labels (union (:labels tx) copied) :data data}
                 ;; Only an item, and only from a public writer (decide).
                 (and (:public tx) (public-item-events type)) (assoc :public true))]
     (-> tx
         (assoc :state (evolve state event))
         (update :events conj event)))))

(defn- last-id [tx] (:seq (peek (:events tx))))

(defn- with-public
  "Runs (f tx) with the transaction's writer taken as public or not, as
  for text the dispatcher copies from elsewhere, and puts the writer back
  after."
  [tx public f]
  (assoc (f (assoc tx :public public)) :public (:public tx)))

(defn- copying!
  "The transaction for an item that copies text from `source`, an item:
  public only when both its writer and the source are. A writer who asked
  for public, such as the operator with --public, is refused rather than
  given a private item."
  [tx source what]
  (when (and (:public tx) (not (:public source)))
    (reject! :invalid (str what " would copy text that is not public, so it cannot be public."
                           " Write it again in full with --public instead.")))
  (assoc tx :public (boolean (and (:public tx) (:public source)))))

(def ^:private public-means
  "--public says that what you write may leave this machine: a public role's session can put it in a web search.")


(defn- require-text [value what]
  (when (blank? value) (reject! (str what " must not be empty.")))
  (str/trim value))

(defn usable-id? [id] (config/role-id? id))

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
       ;; Nor of one waiting to be declassified: it may be private.
       (awaiting-declassification? (:config tx) (memo (:state tx) (last-id tx)))
       (notify tx "memo-awaiting-declassification"
               (str "Memo " (last-id tx) " from " from " to " to " waits for you")
               (str to " is a public role and the memo is not public, so " to " is not shown it until"
                    " you declassify it. relay declassify " (last-id tx) " shows it to you first.")
               :role to :memo (last-id tx) :labels (ids-labels (:state tx) [from to]))
       :else tx))))

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
      ;; Through send-memo, so a contaminated dead letter is quarantined
      ;; and the operator told, as for any memo. The dispatcher writes it,
      ;; so it is never public, whoever's call set it off.
      (with-public tx false
        #(send-memo % {:from dispatcher :to handler :subject (str "Dead letter: " title) :body report}
                    copied))
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

(declare public-actor?)

(defn- authorize-registry!
  "Refuses a registry change from anyone but the operator or a role granted
  :may-add. A public role's session never changes the registry, whatever
  relay.edn says now, and is told so before anything is looked up, so the
  refusal says nothing about which roles exist."
  [{:keys [config actor] :as tx}]
  (when (public-actor? tx)
    (reject! :forbidden (str actor " is a public role, and a public role may not add, change or remove roles.")))
  (when-not (or (= operator actor) (config/may-add? config actor))
    (reject! :forbidden (str actor " may not add, change or remove roles."))))

(defn- active-role! [state id]
  (when-not (active-role? state id)
    ;; A removed role still exists, and its id is text its creator chose.
    (reject! :not-found (str "There is no role called " id ".") (id-labels state id))))

(defn- caller-role! [{:keys [state actor]}]
  (when-not (active-role? state actor)
    (reject! :forbidden (str "Only a role can do that, and " actor " is not one."))))

(defn- public-actor? [{:keys [state config actor session-wake]}]
  (public-caller? state config actor session-wake))

(defn- open-memo! [{:keys [state] :as tx} id]
  (let [m (memo state id)]
    ;; A public role cannot see a memo that is not public, so for it there
    ;; is no such memo.
    (when (or (nil? m) (and (public-actor? tx) (not (shown-to-public? m (memo-labels state m)))))
      (reject! :not-found (str "There is no memo " id ".")))
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
  (let [public-role (and (active-role? state actor) (public-caller? state config actor (:session-wake command)))]
    ;; A public role's session never holds private (agentloop forbids it),
    ;; so a call claiming it is refused before it can write anything.
    (when (and public-role (private? labels))
      (reject! :forbidden (str actor " is a public role, and this call came from a session holding private."
                               " relay refused it.")))
    (:events (decide* {:state state :events [] :now now :config config :actor actor
                       :labels (union labels) :session-wake (:session-wake command)
                       ;; Whether what this command writes is public: the
                       ;; operator says so with --public, a public role's
                       ;; session is when it started public, and nobody
                       ;; else's ever is. A session that began before the
                       ;; role was made public may hold private text no
                       ;; label records, so its writes are not public.
                       ;; The wake is the one relay named in the tool
                       ;; command it gave the harness, so an old process
                       ;; cannot borrow a newer public wake's standing.
                       :public (boolean (cond (= operator actor) (true? (:public command))
                                              public-role (let [w (get-in state [:wakes (:session-wake command)])]
                                                            (and (= actor (:role w)) (= :running (:status w))
                                                                 (:public-session w)))
                                              :else false))}
                      command))))

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
               (or (seq (:tools g)) (:may-add g) (:public g)))
      (reject! (str id " is granted tools or made public in relay.edn, and a :script harness gets no"
                    " tools and can claim any labels. Choose another harness or take the grants away.")))))

(defn- public-role-text!
  "Refuses a public role's id, title or instructions unless the operator
  writes them with --public: the role is shown them in every wake, and a
  role's session may carry private details it was never labelled for (the
  operator's AGENTS.md)."
  [{:keys [config actor public]} id]
  (when (and (public-role? config id) (not (and (= operator actor) public)))
    (reject! :forbidden (str id " is a public role, so only the operator writes its id, title and instructions,"
                             " with --public. " public-means))))

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
    (public-role-text! tx id)
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
    (when (or (contains? changes :title) (contains? changes :instructions))
      (public-role-text! tx id))
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

(defn- operator-public!
  "Refuses what the operator writes for a public role without --public:
  the role may send it out, so the operator has to say it may."
  [{:keys [actor config] :as tx} role what]
  (when (and (= operator actor) (public-role? config role) (not (:public tx)))
    (reject! :invalid (str role " is a public role, so " (str/lower-case what) " for it needs --public. "
                           public-means))))

(defmethod decide* :memo/send [{:keys [state actor] :as tx} {{:keys [to subject body]} :args}]
  ;; A public role knows only the roles whose text the operator wrote.
  (when (and (public-actor? tx) (not= operator to) (not (role-text-public? state to)))
    (reject! :not-found (str "There is no role called " to ".")))
  (when-not (or (= operator to) (active-role? state to))
    (reject! :not-found (str "There is no role called " to ".") (id-labels state to)))
  (let [subject (require-text subject "A memo's subject")
        body (require-text body "A memo's body")]
    (when (str/includes? subject "\n") (reject! "A memo's subject is one line."))
    (when (> (count subject) 200) (reject! "A memo's subject is at most 200 characters."))
    (operator-public! tx to "A memo")
    (send-memo tx {:from actor :to to :subject subject :body body})))

(defn- not-kept!
  "Refuses a role's change to a memo a held session keeps, unless the role
  is carrying that session on."
  [{:keys [state actor]} id]
  (when (and (not= operator actor) ((kept-memo-ids state actor) id))
    (reject! :held (str "Memo " id " is kept by a session waiting for the operator;"
                        " it goes on once the held call is decided."))))

(defmethod decide* :memo/close [{:keys [actor] :as tx} {{:keys [memo outcome]} :args}]
  (let [m (open-memo! tx memo)]
    (when-not (or (= actor (:to m)) (= operator actor))
      (reject! :forbidden (str "Memo " memo " is not addressed to " actor ".")))
    (not-kept! tx memo)
    (emit tx "memo/closed" {:memo memo :outcome (require-text outcome "The outcome")})))

(defmethod decide* :memo/block [{:keys [actor] :as tx} {{:keys [memo explanation]} :args}]
  (caller-role! tx)
  (let [m (open-memo! tx memo)]
    (when-not (= actor (:to m))
      (reject! :forbidden (str "Memo " memo " is not addressed to " actor ".")))
    (not-kept! tx memo)
    (dead-letter tx memo "blocked" [] :explanation (require-text explanation "The explanation"))))

(defmethod decide* :memo/retry [{:keys [state config actor] :as tx} {{:keys [role]} :args}]
  ;; Dead letters quote other roles' memos, so a public session never
  ;; handles them, whatever relay.edn says now.
  (when (public-actor? tx)
    (reject! :forbidden (str actor " is a public role, and a public role may not requeue dead letters.")))
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
    (operator-public! tx owner "A routine")
    (when-not (and (string? name) (re-matches routine-name-pattern name))
      (reject! "A routine name is lower case letters, digits and hyphens."))
    (when-not (schedule/parse schedule)
      (reject! (str "Not a schedule relay understands: " (pr-str schedule) ". Use every 30m, "
                    "every 2h, every 1d, daily 07:30, weekly mon 09:00 or once 2026-10-08 09:00.")))
    (let [last-run (memo (:state tx) (:last-memo (routine (:state tx) owner name)))
          tx (emit tx "routine/written" {:owner owner :name name
                                         :schedule (schedule/render (schedule/parse schedule))
                                         :instructions (require-text instructions "A routine's instructions")})]
      ;; A public role cannot see or close an open run that is not public,
      ;; which would keep the rewritten routine from firing. The rewrite
      ;; replaces it.
      (if (and last-run (= :unread (:status last-run)) (awaiting-declassification? (:config tx) last-run))
        (emit tx "memo/closed" {:memo (:id last-run)
                                :outcome (str "Replaced when the routine " name " was rewritten: this run was"
                                              " not public, so " owner " could not see it.")})
        tx))))

(defmethod decide* :routine/remove [{:keys [state] :as tx} {{:keys [owner name]} :args}]
  (let [owner (routine-owner tx owner)
        r (routine state owner name)]
    (when-not (and (= :active (:status r))
                   ;; A public role knows only its public routines.
                   (or (not (public-actor? tx)) (shown-to-public? r (item-labels r))))
      (reject! :not-found (str owner " has no routine called " name ".")))
    (emit tx "routine/removed" {:owner owner :name name})))

;; waking

(defn- dispatcher-only! [{:keys [actor]}]
  (when-not (= dispatcher actor)
    (reject! :forbidden "Only the dispatcher does that.")))

(defn sessions-from-before-public
  "A public role's held sessions that cannot be carried on as a public
  role's, not yet ended: those from before it was public, and those whose
  decided calls carry a reason the operator gave while it was not."
  [state config role-id]
  (if-not (public-role? config role-id)
    []
    (let [hs (filter #(and (= role-id (:role %)) (unsettled-hold? %)
                           (not (get-in state [:ended-sessions (:session %)])))
                     (vals (:holds state)))]
      (->> (group-by :session hs)
           (keep (fn [[session hs]]
                   (when (some #(or (not (get-in state [:wakes (:wake %) :public-session]))
                                    ;; A reason given while the role was not
                                    ;; public could not be carried back to it.
                                    (and (not (blank? (:reason %))) (not (:reason-public %))))
                               hs)
                     session)))
           sort vec))))

(defn- end-sessions-from-before-public
  "Ends a public role's held sessions from before it was public: they may
  hold private text no label records (the operator's AGENTS.md, what was
  said in them), so none is carried on. Its open calls are denied, and the
  memos they kept go on in fresh sessions. roles-to-wake wakes the role for
  this, so a session whose calls nobody decides cannot strand them."
  [{:keys [state config] :as tx} role-id]
  (let [sessions (sessions-from-before-public state config role-id)]
    (reduce (fn [tx session]
              (as-> tx tx
                (reduce (fn [tx {h :id}]
                          (emit tx "hold/resolved" {:hold h :verdict "deny"
                                                    :reason "The role was made public, so this session was ended."}))
                        tx (sort-by :id (filter #(and (= session (:session %)) (= :open (:status %)))
                                                (vals (:holds (:state tx))))))
                (emit tx "hold/session-ended" {:role role-id :session session :why "the role was made public"})))
            tx sessions)))

(defn- start-wake
  "What :wake/start does once the role is known to be free."
  [{:keys [state config now actor] :as tx} {{role-id :role :keys [note dispatched chat pid pid-start]} :args}]
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
    (let [;; A held session whose calls are all decided is carried on
          ;; first, with the decisions and the memos it kept. Otherwise a
          ;; wake starts with the memos no held session keeps, in a fresh
          ;; session unless the last one was cut short.
          ready (when-not chat (resumable-held-session state role-id))
          kept (held-memo-ids state role-id)
          memos (cond chat []
                      ready (filterv #(and (= :unread (:status %)) (not (awaiting-declassification? config %)))
                                     (map #(memo state %) (get-in state [:wakes (:wake ready) :memos])))
                      dispatched (waiting-memos state config role-id now)
                      :else (filterv #(not (kept (:id %))) (deliverable-memos state config role-id)))
          held (held-wakes state role-id)
          ;; A session from before relay kept labels is never carried on:
          ;; the policy could not know what it had seen.
          before-labels? #(and % (:labels-enabled-at state) (< % (:labels-enabled-at state)))
          public (public-role? config role-id)
          ;; Nor is a session from before a public role was public carried
          ;; on as one: it may hold private text that no label records.
          before-public? #(and public % (not (get-in state [:wakes % :public-session])))
          interrupted (cond ready (when-not (before-labels? (:wake ready)) (:wake ready))
                            :else (let [w (:interrupted-wake (role state role-id))]
                                    (when-not (or (before-labels? w) (held w) (before-public? w)) w)))
          resolves (when ready (mapv :id (:holds ready)))
          ;; A note the operator wrote with --public may go where the role
          ;; sends things; the conversation's own note is relay's.
          note-public (boolean (and (not chat) (not (blank? note)) (:public tx)))]
      (when (and public (not chat) (not (blank? note)) (not note-public))
        (reject! :invalid (str role-id " is a public role, so a note for it needs --public. " public-means)))
      (when-let [why (unwakeable-reason state config role-id)]
        (reject! :invalid (str role-id " is a public role and cannot run as one: " why)))
      ;; The last line: nothing private goes into a public role's prompts.
      (when (and public (private? (wake-labels state config {:role role-id :memos (mapv :id memos) :note note
                                                             :note-public note-public :chat chat
                                                             :resolves resolves})))
        (reject! :invalid (str role-id " is a public role, and its prompts would hold private data.")))
      (if (and (empty? memos) (blank? note) (not chat) (empty? resolves))
        tx
        (emit tx "wake/started" (cond-> {:role role-id :memos (mapv :id memos) :note note
                                         :resumes (when-not chat interrupted)
                                         :harness harness :pid pid :pid-start pid-start}
                                  note-public (assoc :note-public true)
                                  public (assoc :public-session true)
                                  chat (assoc :chat true)
                                  (seq resolves) (assoc :resolves resolves)))))))

(defmethod decide* :wake/start [{:keys [config actor] :as tx} {{role-id :role :keys [chat]} :args :as command}]
  (when-not (#{dispatcher operator} actor)
    (reject! :forbidden "Only the operator or the dispatcher wakes a role."))
  (active-role! (:state tx) role-id)
  (when (running? (:state tx) role-id)
    (reject! :busy (str role-id " is already running.") (id-labels (:state tx) role-id)))
  (when (and chat (not= operator actor))
    (reject! :forbidden "Only the operator can open a conversation."))
  (start-wake (cond-> tx (public-role? config role-id) (end-sessions-from-before-public role-id))
              command))

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
  [{:keys [state] :as tx} wake session {:keys [id name input input-sha256 label-sources]}]
  (let [known (first (filter #(and (= session (:session %)) (= id (:call %))) (vals (:holds state))))]
    (cond
      (nil? known)
      (let [tx (emit tx "hold/opened" (cond-> {:role (:role wake) :wake (:id wake) :session session
                                               :call id :tool name :input input :input-sha256 input-sha256}
                                        (map? label-sources) (assoc :label-sources label-sources))
                     (:said-labels tx))
            hold-id (last-id tx)]
        ;; The input stays in relay holds: it may hold private data, and a
        ;; notification goes to a phone.
        (notify tx "hold-opened" (str (:role wake) " wants to use " name)
                (str "Hold " hold-id ": " (:role wake) " wants to use " name ", "
                     (if-let [c (get-in (:config tx) [:tools name :class])]
                       (str (if (#{:act :egress} c) "an " "a ") (clojure.core/name c) " tool")
                             "one of relay's own tools")
                     ". It is waiting for you: relay holds shows it in full.")
                :role (:role wake) :hold hold-id
                :labels (union (:said-labels tx) (id-labels state (:role wake)))))
      ;; Carried back again only if it is the same call, by its digest.
      (and (not= :open (:status known)) (:delivered known) (= input-sha256 (:input-sha256 known)))
      (emit tx "hold/requeued" {:hold (:id known)})
      :else tx)))

(defn- tell-refusals
  "Tells the operator about what agentloop kept from a wake's session for
  carrying a label it may not hold: a session that would not start, and
  tool results it refused. Names the tools, never what they returned."
  [{:keys [state] :as tx} wake {:keys [refused forbidden]}]
  (let [role (:role wake)
        tools (sort (distinct (keep #(when (and (map? %) (string? (:tool %))) (:tool %)) (when (sequential? refused) refused))))
        forbidden (when (sequential? forbidden) (filterv label-names forbidden))]
    (cond-> tx
      (seq forbidden)
      (notify "session-forbidden" (str role "'s session did not start")
              (str "It holds " (str/join " and " forbidden) ", which a public role's session may not hold, so"
                   " agentloop did not run it. Its next wake starts a fresh session.")
              :role role :labels (id-labels state role))
      (seq tools)
      (notify "result-refused" (str "A result was kept from " role)
              (str "agentloop refused " (count refused) " tool result(s) from " (str/join ", " tools)
                   " because they carried private data, which " role " may not see.")
              :role role :labels (id-labels state role)))))

(defn- end-wake [{:keys [state config] :as tx} wake-id {:keys [outcome exit crashed cut-short session sessions said
                                                              transcript resets-at held labels] :as report}]
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
      (tell-refusals tx wake report)
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

(defn plain-spacing
  "Text with its spacing made plain: every run of spaces, tabs and other
  blanks within a line becomes one space, lines lose the blanks at their
  start and end, and runs of blank lines become one, none at the start or
  end. What is left is what a reader sees, so nothing can ride in the
  spacing (trailing spaces and tabs as bits, say) past someone who read
  it. Anything that draws as nothing or as an empty space
  (text/blank-glyph?) counts as a blank, and every kind of line break as
  one."
  [s]
  (->> (str/split (-> (apply str (map #(if (text/blank-glyph? %) " " (String. (Character/toChars (int %))))
                                      (iterator-seq (.iterator (.codePoints (str s))))))
                      (str/replace #"\r\n?|[\u0085\u2028\u2029]" "\n"))
                  #"\n")
       (map #(str/trim (str/replace % #"[\p{Zs}\t\x0B\f\u001C-\u001F]+" " ")))
       (partition-by str/blank?)
       (mapcat #(if (str/blank? (first %)) [""] %))
       (drop-while str/blank?)
       reverse (drop-while str/blank?) reverse
       (str/join "\n")))

(defn declassified
  "A memo as declassifying makes it, and the role is given: its subject and
  body defanged if it is contaminated (text/defang), with plain spacing
  (plain-spacing), in Unicode NFC. relay declassify shows exactly this
  text, adding only the [contaminated] marker, so what the operator
  vouches for and what the role gets are one text. Defanging folds
  several texts into one (http and HtTp, a dot and [.]), so the role
  must get the folded text, or the difference could carry bits the
  operator never saw."
  [m]
  (let [fix (fn [s] (-> (str s)
                        (cond-> (contaminated? (:labels m)) text/defang)
                        plain-spacing
                        (java.text.Normalizer/normalize java.text.Normalizer$Form/NFC)))]
    (-> m
        ;; A subject is one line, however it was written.
        (update :subject #(fix (str/replace (plain-spacing %) "\n" " ")))
        (update :body fix))))

(defmethod decide* :memo/declassify [{:keys [state config] :as tx} {{id :memo} :args}]
  ;; The operator has read the memo, its sender's id included, and says it
  ;; may leave this machine. Only a person at the terminal does that.
  (operator-only! tx "declassifies a memo")
  (let [m (open-memo! tx id)]
    (when-not (awaiting-declassification? config m)
      (reject! :invalid (str "Memo " id " is not waiting to be declassified.")))
    (let [{:keys [subject body]} (declassified m)]
      (emit tx "memo/declassified" {:memo id :subject subject :body body
                                    :from-labels (vec (remove #{private-label} (second (shown-id state (:from m)))))}
            ;; It holds the memo's text, without the private it vouches away.
            (remove #{private-label} (:labels m))))))

(defmethod decide* :result/refused [{:keys [state actor] :as tx} {{:keys [tool]} :args}]
  ;; relay kept a tool's result from a public role's session for carrying
  ;; private. The role's own call reports it; the operator is told which
  ;; tool, never what it returned.
  (caller-role! tx)
  (when-not (public-actor? tx) (reject! :forbidden "Only a public role's results are refused."))
  (notify tx "result-refused" (str "A result was kept from " actor)
          (str "relay refused a result from " tool " because it carried private data, which " actor
               " may not see.")
          :role actor :labels (id-labels state actor)))

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

(def ^:private refusal-reasons
  {"no-approvals" "Its ACP client was not started with --approvals, so relay refused the call for it. To decide such calls yourself, talk with it through relay chat."
   "client-gone" "Its client went away before answering, so relay refused the call."
   "client-error" "Its client answered a request that was not open, so relay refused what was open rather than let the turn hang."
   "unreadable" "relay could not read the request, so it refused it rather than pass it on."
   "agent-gone" "Its agent stopped before the client answered."})

(defmethod decide* :permission/answered [{:keys [state] :as tx} {{:keys [wake session call tool outcome by labels why]} :args}]
  (dispatcher-only! tx)
  (let [role (get-in state [:wakes wake :role])]
    (when-not (get-in state [:wakes wake :chat]) (reject! "Only a conversation asks its client."))
    (cond-> (emit tx "permission/answered" (cond-> {:wake wake :role role :session session :call call
                                                    :outcome outcome :by by}
                                             why (assoc :why why))
                  (permission-labels tx role labels))
      ;; relay answered it, not the operator: say why.
      (= "relay" by)
      (notify "permission-refused" (str role " was refused a call to " tool " in a conversation")
              (get refusal-reasons why "relay refused the call.")
              :role role :labels (union (id-labels state role) (permission-labels tx role labels))))))

(defmethod decide* :conversation/line-dropped [{:keys [state] :as tx} {{:keys [wake size permission refused]} :args}]
  (dispatcher-only! tx)
  (let [role (get-in state [:wakes wake :role])]
    (-> tx
        (emit "conversation/line-dropped" {:wake wake :role role :size size
                                           :permission (boolean permission) :refused (boolean refused)})
        (notify "conversation-line-dropped" (str "relay dropped a message from " role "'s agent")
                (str "A line of " size " bytes was too long to check, so its client never saw it."
                     (when permission " It looked like a permission request, ")
                     (when (and permission refused) "and relay refused it.")
                     (when (and permission (not refused)) "and relay could not tell which, so it went unanswered."))
                :role role :labels (id-labels state role)))))

(defmethod decide* :labels/enable [{:keys [state] :as tx} _]
  ;; Marks where labels begin, the first time this relay opens a log
  ;; written before it kept them.
  (dispatcher-only! tx)
  (if (needs-labels-marker? state)
    (emit tx "labels/enabled" {})
    tx))

;; holds

(def denials-per-session
  "After this many denied calls a session is not carried on: a model
  that keeps asking for what it is refused starts afresh."
  3)

(defmethod decide* :hold/resolve [{:keys [state actor] :as tx} {{hold-id :hold :keys [verdict reason end]} :args}]
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
    (let [tx (emit tx "hold/resolved" (cond-> {:hold hold-id :verdict verdict}
                                        (not (blank? reason)) (assoc :reason (str/trim reason))
                                        ;; The operator is told, as they type it,
                                        ;; that a public role's reason is public.
                                        (and (not (blank? reason)) (public-role? (:config tx) (:role h)))
                                        (assoc :reason-public true)))
          denied (count (filter #(and (= (:session h) (:session %)) (= :denied (:status %)))
                                (vals (:holds (:state tx)))))
          ended (cond (and (= "deny" verdict) end) "the operator ended it"
                      (and (= "deny" verdict) (>= denied denials-per-session))
                      (str denied " of its calls were denied"))]
      (if-not ended
        tx
        (cond-> (as-> tx tx
                  ;; Every other call still open in it is denied: the
                  ;; session will not be carried on to run it.
                  (reduce (fn [tx {other :id}]
                            (emit tx "hold/resolved" {:hold other :verdict "deny" :reason "The session was ended."}))
                          tx (filter #(and (= (:session h) (:session %)) (= :open (:status %)))
                                     (vals (:holds (:state tx)))))
                  (emit tx "hold/session-ended" {:role (:role h) :session (:session h) :why ended}))
          (not end)
          (notify "session-ended" (str (:role h) "'s session will not be carried on")
                  (str "Its calls were denied " denied " times, so its next wake starts afresh,"
                       " with its open memos.")
                  :role (:role h) :labels (id-labels state (:role h))))))))

(defmethod decide* :wake/end [{:keys [state] :as tx} {{:keys [wake] :as args} :args}]
  (dispatcher-only! tx)
  (when-not (= :running (get-in state [:wakes wake :status]))
    (reject! :invalid (str "Wake " wake " is not running.")))
  (end-wake tx wake args))

;; the tick

(defn- fire-routines [{:keys [state config now] :as tx}]
  (reduce (fn [tx {:keys [owner name instructions schedule] :as r}]
            (if (routine-due? (:state tx) config r now)
              (let [;; A run copies the routine, so it is public when the
                    ;; routine is.
                    tx (with-public tx (:public r)
                         #(send-memo % {:from dispatcher :to owner :subject name :body instructions
                                        :routine name}
                                     (:labels r)))
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

(defn- report-unwakeable
  "Tells the operator, once for each reason, about a public role that cannot
  run as one, and how to fix it."
  [{:keys [state config] :as tx}]
  (reduce (fn [tx id]
            (let [why (unwakeable-reason (:state tx) config id)
                  told (get-in (:state tx) [:unwakeable-told id])]
              (cond
                (and why (not= why told))
                (notify tx "public-role-cannot-run" (str id " cannot run as a public role")
                        (str "It is not woken until this is put right: " why)
                        :role id :reason why :labels (id-labels state id))
                ;; Put right, so a later breakage is told again.
                (and told (not why))
                (notify tx "public-role-can-run" (str id " can run as a public role again")
                        "It is woken as usual from now on." :role id :labels (id-labels state id))
                :else tx)))
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

(declare verify-failed)

(defmethod decide* :tick [{:keys [state] :as tx} {{:keys [lost-wakes lost-verifications]} :args}]
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
    ;; A verify process that is gone failed, and is tried again later:
    ;; only the very process the tick found gone, by pid and start time.
    (reduce (fn [tx {id :report :keys [pid pid-start]}]
              (let [v (get-in (:state tx) [:reports id :verifying])]
                (if (and v (= pid (:pid v)) (= pid-start (:pid-start v)))
                  (verify-failed tx id "The process checking it disappeared.")
                  tx)))
            tx (sort-by :report (filter map? lost-verifications)))
    (fire-routines tx)
    (trip-guards tx)
    (report-unwakeable tx)
    (report-outages tx)))

;; notifications

(defmethod decide* :notification/delivered [{:keys [state] :as tx} {{:keys [notification]} :args}]
  (dispatcher-only! tx)
  (when-not (get-in state [:notifications notification]) (reject! :not-found "No such notification."))
  (emit tx "notification/delivered" {:notification notification}))

(def notification-attempts
  "How many times a notification is pushed before relay gives up on it."
  8)

(defmethod decide* :notification/failed [{:keys [state config now] :as tx} {{:keys [notification error]} :args}]
  (dispatcher-only! tx)
  (let [n (get-in state [:notifications notification])]
    (when-not n (reject! :not-found "No such notification."))
    ;; A failed push is tried again after a wait that doubles, a few times.
    (let [attempt (inc (or (:attempts n) 0))]
      (emit tx "notification/failed"
            {:notification notification :error (str error) :attempt attempt
             :retry-at (when (< attempt notification-attempts)
                         (str (.plusSeconds ^Instant now (config/retry-delay-seconds config attempt))))}))))

(defn notifications-to-push
  "Notifications the tick pushes: pending ones whose own process stopped
  before pushing them, and failed ones whose retry is due."
  [state ^Instant now ^Instant stale-before]
  (->> (vals (:notifications state))
       (filter #(case (:delivery %)
                  :pending (.isBefore ^Instant (:created-at %) stale-before)
                  :failed (some-> ^Instant (:retry-at %) (.isAfter now) not)
                  false))
       (sort-by :id)))

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

(defn- core-fact! [state id]
  (or (fact state id) (reject! :not-found (str "There is no fact " id "."))))

(defn- known-fact!
  "A fact's current version. For a public role, a fact whose current version
  it cannot be shown does not exist."
  [{:keys [state] :as tx} id]
  (let [f (fact state id)]
    (when (or (nil? f) (and (public-actor? tx) (not (fact-shown-to-public? state (fact-as-known f nil)))))
      (reject! :not-found (str "There is no fact " id ".")))
    (let [v (fact-as-known f nil)]
      (when (:retracted v) (reject! :invalid (str "Fact " id " was retracted.")))
      v)))

(defmethod decide* :fact/add [{:keys [config] :as tx} {{:keys [topic text valid-from valid-to]} :args}]
  (emit tx "fact/added" (merge {:topic (fact-topic topic) :text (fact-text text)}
                               (validity! (world-time config valid-from "valid-from")
                                          (world-time config valid-to "valid-to")))))

(defmethod decide* :fact/supersede [{:keys [config now] :as tx}
                                    {{:keys [fact topic text valid-from valid-to]} :args}]
  (let [old (known-fact! tx fact)
        tx (copying! tx old (str "Superseding fact " fact))
        from (or (world-time config valid-from "valid-from") now)]
    (when-let [by (:superseded-by old)]
      (reject! :conflict (str "Fact " fact " was already superseded by fact " by "; supersede that one.")))
    (when (and (:valid-from old) (not (after? from (:valid-from old))))
      (reject! :invalid (str "The change must come after fact " fact " began to hold, at " (:valid-from old)
                             ". If the fact was wrong from the start, correct it instead.")
               (item-labels old)))
    (when (and (:valid-to old) (after? from (:valid-to old)))
      (reject! :invalid (str "Fact " fact " had already stopped holding at " (:valid-to old)
                             "; add a new fact instead.")
               (item-labels old)))
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
      ;; It puts back the old fact's own text, public only if that was.
      (with-public tx (and (:public tx) (:public current)) #(emit % "fact/corrected"
            (merge (select-keys current [:topic :text])
                   {:fact old-id :superseded-by nil
                    :valid-from (some-> (:valid-from current) str)
                    :valid-to (some-> (:valid-to before) str)
                    :why (str "Fact " retracted-id ", which superseded it, was retracted.")})
            (:labels current)))
      tx)))

(defmethod decide* :fact/correct [{:keys [config] :as tx}
                                  {{:keys [fact topic text valid-from valid-to retract why]} :args}]
  (let [cur (known-fact! tx fact)
        tx (copying! tx cur (str "Correcting fact " fact))
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
          (reject! :invalid "That correction changes nothing." (item-labels cur)))
        ;; A correction keeps whatever it does not change (the times, the
        ;; topic, the text, the link to its successor), so it carries the
        ;; labels of the version it corrects.
        (emit tx "fact/corrected" (assoc corrected :fact fact :why why
                                         :superseded-by (:superseded-by cur))
              (:labels cur))))))

(defmethod decide* :fact/revert [{:keys [state] :as tx} {{:keys [fact version]} :args}]
  ;; The operator puts back a version they have read, after a change they
  ;; do not want, such as a contaminated session's. The restored version
  ;; carries the labels of the text it restores.
  (operator-only! tx "reverts a fact")
  (let [f (core-fact! state fact)
        v (first (filter #(= version (:seq %)) (:versions f)))]
    (when-not v (reject! :not-found (str "Fact " fact " has no version " version ".")))
    ;; The restored version is public when the one it restores was.
    (emit (assoc (copying! tx v (str "Version " version " of fact " fact)) :public (true? (:public v)))
          "fact/corrected"
          (cond-> (merge (select-keys v [:topic :text])
                         {:fact fact :why (str "The operator reverted it to version " version ".")
                          :superseded-by (:superseded-by v)}
                         (validity! (:valid-from v) (:valid-to v)))
            (:retracted v) (assoc :retracted true))
          (:labels v))))

(defmethod decide* :doc/revert [{:keys [state] :as tx} {{:keys [path version]} :args}]
  (operator-only! tx "reverts a doc")
  (let [v (doc-version state path {:version version})]
    (when-not v (reject! :not-found (str "There is no version " version " of " path ".")))
    (let [tx (assoc (copying! tx v (str "Version " version " of " path)) :public (true? (:public v)))]
      (if (:removed v)
        (emit tx "doc/removed" {:path path} (:labels v))
        (emit tx "doc/put" {:path path :content (:content v)} (:labels v))))))

(def ^:private note-name-pattern #"[a-z0-9][a-z0-9-]{0,47}")

(defmethod decide* :note/write [tx {{:keys [owner name text]} :args}]
  (let [owner (owner-for tx owner "note")
        text (require-text text "A note")
        _ (operator-public! tx owner "A note")
        old (get-in (:state tx) [:notes owner name])]
    (when-not (and (string? name) (re-matches note-name-pattern name))
      (reject! "A note's name is lower case letters, digits and hyphens."))
    (when (> (count text) 4000)
      (reject! "A note is at most 4000 characters, since notes go into every wake. Put more in a doc."))
    ;; Unchanged text writes nothing, unless that would tell a public role
    ;; what a note it cannot see says, or leave a note it wrote private.
    (if (and (= text (:text old)) (= (true? (:public old)) (:public tx)))
      tx
      (emit tx "note/written" {:owner owner :name name :text text}))))

(defmethod decide* :note/remove [{:keys [state] :as tx} {{:keys [owner name]} :args}]
  (let [owner (owner-for tx owner "note")
        n (get-in state [:notes owner name])]
    (when-not (and n (or (not (public-actor? tx)) (shown-to-public? n (item-labels n))))
      (reject! :not-found (str owner " has no note called " name ".")))
    (emit tx "note/removed" {:owner owner :name name})))

(defmethod decide* :journal/write [tx {{:keys [owner text]} :args}]
  (let [owner (owner-for tx owner "journal")
        text (require-text text "A journal entry")]
    (operator-public! tx owner "A journal entry")
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
      (if (and (public-actor? tx) (not (doc-shown-to-public? state (:path clash) clash)))
        ;; A public role is not told the path of a doc it cannot see, only
        ;; that the path cannot be used.
        (reject! :conflict (str path " cannot be used: it would sit where another doc is, as a file and"
                                " a folder at once. Choose another path."))
        (reject! :conflict (str path " would sit where " (:path clash) " is, as a file and a"
                                " folder at once. Choose another path.")
                 (union (version-labels state clash) (path-labels state (:path clash))))))
    (let [latest (doc-version state path {})]
      ;; A path is public only when it was first chosen in public, so a
      ;; public write there could never be seen by a public role.
      (when (and (:public tx) (not (public-actor? tx)) (get-in state [:docs path]) (not (get-in state [:docs path :path-public])))
        (reject! :invalid (str path " was first written without --public, so its path is not public and no public"
                               " role can see a doc there. Put it at a new path with --public.")))
      ;; A public role does not write over a doc it cannot see: it could not
      ;; see what it wrote, and its path was not chosen in public. That this
      ;; and the clash above are refused tells it a path is taken, never
      ;; what is there or who wrote it. Docs share one tree, so the only
      ;; alternative is a namespace of their own for public roles, which
      ;; would cut them off from public docs the operator writes.
      (when (and latest (public-actor? tx) (not (doc-shown-to-public? state path latest)))
        (reject! :conflict (str path " cannot be used. Choose another path.")))
      ;; Unchanged content writes nothing, unless saying so would tell a
      ;; public role what a doc it cannot see says, or leave its own write
      ;; private.
      (if (and (= content (:content latest)) (= (true? (:public latest)) (:public tx))
               (or (not (public-actor? tx)) (doc-shown-to-public? state path latest)))
        tx
        (emit tx "doc/put" {:path path :content content})))))

(defmethod decide* :doc/remove [{:keys [state] :as tx} {{:keys [path]} :args}]
  (let [path (doc-path path)
        v (doc-version state path {})]
    (when (or (nil? v) (:removed v) (and (public-actor? tx) (not (doc-shown-to-public? state path v))))
      (reject! :not-found (str "There is no doc at " path ".")))
    (emit tx "doc/removed" {:path path})))

;; research reports
;;
;; A public role granted :reports writes what it found as a report: a
;; summary and findings in a strict schema (relay.reports/parse-report).
;; The tick has each new report checked by the verifier in a process of
;; its own (relay verify), which records what came back; flags are worked
;; out from it here. The role is never told how its report fared, so it
;; cannot learn to pass the checks. The operator reviews each finding not
;; seen before and accepts it into the ledger or rejects it.

(defn report [state id] (get-in state [:reports id]))

(defmethod evolve* "report/submitted" [state {:keys [seq at data labels public]}]
  (assoc-in state [:reports seq] {:id seq :role (:role data) :summary (:summary data)
                                  :findings (vec (:findings data)) :submitted-at (inst at)
                                  :labels (union labels) :public (true? public)
                                  :status :submitted :attempts 0 :retry-at nil}))

(defmethod decide* :report/submit [{:keys [config actor] :as tx} {:keys [args]}]
  (caller-role! tx)
  (let [kinds (config/report-kinds config actor)]
    (when-not (and kinds (public-actor? tx))
      (reject! :forbidden (str actor " may not submit reports.")))
    ;; Only from a session that began public, so the report is public: it
    ;; holds an outsider's text and nothing of the operator's.
    (when-not (:public tx)
      (reject! :forbidden (str "This session began before " actor " was public, so it cannot submit a report."
                               " The next wake can.")))
    (let [{:keys [report problem]} (reports/parse-report kinds args)]
      (when problem (reject! :invalid problem))
      (emit tx "report/submitted" (assoc report :role actor)))))

(defn verifying?
  "Whether a report's verify process has started and not finished."
  [r]
  (some? (:verifying r)))

(defn reports-to-verify
  "The reports the tick has checked now: submitted, not being checked,
  and not waiting out a retry delay. None without a :verifier."
  [state config ^Instant now]
  (if-not (:verifier config)
    []
    (->> (vals (:reports state))
         (filter #(and (= :submitted (:status %)) (not (verifying? %))
                       (not (some-> ^Instant (:retry-at %) (.isAfter now)))))
         (map :id) sort vec)))

(defn ledger-entries [state] (vec (sort-by :id (vals (:ledger state)))))

(defn known-findings
  "What relay already knows of findings, for flags (relay.reports/verify):
  the registrable domains of ledger entries; the canonical URLs that hide
  a finding as seen before, each with what it matched: a ledger entry, a
  finding waiting for review, or one the operator judged not relevant;
  and those of findings rejected as fake, which are shown again, flagged.
  Report `except`'s own findings are left out."
  [state except]
  (let [entries (sort-by :id (vals (:ledger state)))
        findings (for [r (sort-by :id (vals (:reports state)))
                       :when (and (not= except (:id r)) (= :verified (:status r)))
                       [i f] (map-indexed vector (get-in r [:verification :findings]))]
                   [(:id r) i f])
        by (fn [kind pred] (into {} (for [[r i f] (reverse findings) :when (pred f)]
                                      [(:canonical-url f) {:kind kind :report r :finding i}])))]
    {:domains (set (keep :domain entries))
     ;; A ledger entry wins over a waiting finding, and that over one set
     ;; aside, when one URL matches several.
     :seen (merge (by "not-relevant" #(= "not-relevant" (:decision %)))
                  (by "pending" #(and (not (:seen-before %)) (nil? (:decision %))))
                  (into {} (for [e (reverse entries)] [(:url e) {:kind "ledger" :entry (:id e)}])))
     :fake (set (for [[_ _ f] findings :when (= "fake" (:decision f))] (:canonical-url f)))}))

(defn verification-labels
  "What a report's verification carries: the report's labels, and
  contaminated, since what the verifier says comes from pages."
  [r]
  (union (:labels r) [contaminated-label]))

(defn pending-findings
  "The findings of a verified report that wait for the operator: not seen
  before and not yet decided, as their indexes."
  [r]
  (if-not (= :verified (:status r))
    []
    (vec (keep-indexed (fn [i f] (when-not (or (:seen-before f) (:decision f)) i))
                       (get-in r [:verification :findings])))))

(defmethod evolve* "report/verify-started" [state {:keys [at data]}]
  (assoc-in state [:reports (:report data) :verifying] {:pid (:pid data) :pid-start (:pid-start data)
                                                        :started-at (inst at)}))

(defmethod evolve* "report/verify-failed" [state {:keys [data]}]
  (update-in state [:reports (:report data)]
             #(-> (dissoc % :verifying)
                  (assoc :attempts (:attempt data) :retry-at (inst (:retry-at data)) :error (:error data)
                         :status (if (:retry-at data) :submitted :failed)))))

(defmethod evolve* "report/verified" [state {:keys [at data labels]}]
  (update-in state [:reports (:report data)]
             #(-> (dissoc % :verifying :retry-at)
                  (assoc :status :verified :verified-at (inst at)
                         :verification {:flags (vec (:flags data)) :findings (vec (:findings data))
                                        :urls (vec (:urls data)) :sampled (vec (:sampled data))
                                        :labels (union labels)}))))

(defn- report! [state id]
  (or (report state id) (reject! :not-found (str "There is no report " id "."))))

(defmethod decide* :report/verify-start [{:keys [state config now] :as tx} {{id :report :keys [pid pid-start by-hand]} :args}]
  (dispatcher-only! tx)
  (let [r (report! state id)]
    (when-not (:verifier config) (reject! :invalid "relay.edn has no :verifier to check reports with."))
    (when (verifying? r) (reject! :busy (str "Report " id " is being checked now.")))
    (when-not (or (= :submitted (:status r)) (and by-hand (= :failed (:status r))))
      (reject! :invalid (str "Report " id " is " (name (:status r)) ", so there is nothing to check.")))
    (when (and (not by-hand) (some-> ^Instant (:retry-at r) (.isAfter now)))
      (reject! :invalid (str "Report " id " is next checked at " (:retry-at r) ".")))
    (emit tx "report/verify-started" {:report id :pid pid :pid-start pid-start})))

(defn- verification-notice
  "The one notification for a verified report: counts and what to run,
  never any of its text."
  [state id]
  (let [r (report state id)
        fs (get-in r [:verification :findings])
        pending (pending-findings r)]
    (str "Report " id " from " (:role r) ": " (count fs) " findings, "
         (count (filter #(seq (:flags %)) fs)) " flagged, " (count (filter :seen-before fs)) " seen before."
         (when (seq pending) (str " relay review " id)))))

(defn- checking!
  "Refuses news of a check from any process but the one checking the
  report now, by pid and start time, so a process from an earlier try, or
  a tick that saw an earlier one gone, cannot speak for this one."
  [r pid pid-start]
  (let [v (:verifying r)]
    (when-not (and v (= pid (:pid v)) (= pid-start (:pid-start v)))
      (reject! :invalid (str "Report " (:id r) " is not being checked by that process.")))))

(defmethod decide* :report/verify [{:keys [state config now] :as tx} {{id :report :keys [results sampled labels pid pid-start]} :args}]
  (dispatcher-only! tx)
  (let [r (report! state id)
        asked (mapcat :items (reports/check-requests r))]
    (checking! r pid pid-start)
    (when-not (and (or (nil? labels) (sequential? labels)) (every? label-names labels))
      (reject! :invalid "The verifier's labels must be labels relay knows."))
    (when-not (and (sequential? results) (= (count asked) (count results)) (every? map? results))
      (reject! :invalid (str "The verifier's results do not match report " id "'s checks.")))
    (when-not (and (sequential? sampled) (= (count (:findings r)) (count sampled)) (every? boolean? sampled))
      (reject! :invalid "The sample must say for every finding whether it was drawn."))
    (let [checked (reports/checked-urls r results)
          today (LocalDate/ofInstant now (config/zone config))
          v (reports/verify r checked (known-findings state id) today)
          tx (emit tx "report/verified" {:report id :flags (:flags v) :findings (:findings v)
                                         :urls (mapv #(dissoc % :quotes) checked) :sampled (vec sampled)}
                   ;; With whatever the verifier said its answers carry.
                   (union (verification-labels r) labels))]
      (notify tx "report-verified" (str "Report " id " from " (:role r) " was checked")
              (verification-notice (:state tx) id)
              :role (:role r) :report id :labels (id-labels state (:role r))))))

(defn- verify-failed
  "Records that checking a report failed. It is tried again after a wait
  that grows, like a memo's, until :max-attempts; the operator is told the
  first time and when relay gives up."
  [{:keys [state config now] :as tx} id error]
  (let [r (report state id)
        attempt (inc (or (:attempts r) 0))
        retry-at (when (< attempt (:max-attempts config))
                   (str (.plusSeconds ^Instant now (config/retry-delay-seconds config attempt))))
        tx (emit tx "report/verify-failed" {:report id :error (str error) :attempt attempt :retry-at retry-at}
                 ;; The verifier's own words may come from a page.
                 (verification-labels r))]
    (cond
      (nil? retry-at)
      (notify tx "report-verify-failed" (str "relay gave up checking report " id)
              (str "Checking report " id " from " (:role r) " failed " attempt " times. relay verify " id
                   " tries again; relay log shows why it failed.")
              :role (:role r) :report id :labels (id-labels state (:role r)))
      (= 1 attempt)
      (notify tx "report-verify-failed" (str "relay could not check report " id)
              (str "Checking report " id " from " (:role r) " failed. relay tries again later by itself;"
                   " relay log shows why it failed.")
              :role (:role r) :report id :labels (id-labels state (:role r)))
      :else tx)))

(defmethod decide* :report/verify-fail [{:keys [state] :as tx} {{id :report :keys [error pid pid-start]} :args}]
  (dispatcher-only! tx)
  (let [r (report! state id)]
    (checking! r pid pid-start)
    (verify-failed tx id error)))
