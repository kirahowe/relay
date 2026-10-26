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
            [relay.cards :as cards]
            [relay.config :as config]
            [relay.schedule :as schedule]
            [relay.search :as search]
            [relay.skills :as skills]
            [relay.spend :as spend])
  (:import (java.time Duration Instant LocalDate LocalDateTime)
           (java.time.format DateTimeFormatter)))

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

(def entry-points
  "Where something enters relay, and the labels it enters with (I5).
  Trust follows the hand that brought it in: what the operator types,
  pastes or attaches, at the command line, in a conversation (a prompt or
  a resource in it) or in a mail from their own verified address, is
  theirs and enters trusted. What an ingestor or a scout brings in by
  itself enters contaminated. A tool's result enters with the labels
  relay.edn gives the tool, which the operator chose for it; anything a
  session writes carries the union of what it read."
  {:command-line [] :conversation [] :operator-mail []
   :ingestor [contaminated-label] :scout [contaminated-label]})

(defn entry-labels
  "The labels of what enters relay at `entry` (entry-points), or for a
  tool's result, {:tool labels}, the labels relay.edn gives the tool.
  Throws for anything else, so a new way in must be given its labels."
  [entry]
  (cond
    (contains? entry-points entry) (get entry-points entry)
    (and (map? entry) (sequential? (:tool entry))) (vec (sort (distinct (:tool entry))))
    :else (throw (ex-info (str "relay does not know the entry point " (pr-str entry) ".") {:entry entry}))))

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
  operator with --public or by a public role's session, and its labels
  never hold private."
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
   :notifications {} :guards {} :facts {} :notes {} :journals {} :docs {} :cards {}
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
  keeps carries that thing's labels, which go back with it to a role.

  A refusal may also quote the call it refuses: an id, a path, a date.
  That text is the caller's own, so whatever ran the call gives every
  refusal the labels of the session that sent it (relay.tools/run-tool).
  No refusal has to be marked as quoting the call, so a new one cannot
  hand a session's text back unlabelled. A refusal that quotes nothing
  gets labels the session already holds, which costs nothing. Text that
  must carry no labels, such as the clock's, is not thrown with reject!."
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

;; A role runs at most one wake that works through its memos (dispatched,
;; or asked for by hand) and keeps at most one conversation open with the
;; operator. A conversation holds the role only while a turn of it runs,
;; so the two may be open at once, but the role still takes one turn at a
;; time: no turn starts while a wake works, and no wake starts while a
;; turn runs.

(defn- running-wakes [state role-id]
  (->> (vals (:wakes state))
       (filter #(and (= role-id (:role %)) (= :running (:status %))))
       (sort-by :id)))

(defn working-wake
  "The role's running wake that is not a conversation, or nil."
  [state role-id]
  (first (remove :chat (running-wakes state role-id))))

(defn open-conversation
  "The role's open conversation, a running wake with :chat, or nil."
  [state role-id]
  (first (filter :chat (running-wakes state role-id))))

(defn in-turn?
  "Whether a turn of a conversation runs now."
  [wake]
  (= :running (:turn wake)))

(defn busy?
  "Whether no other turn of the role's may start: a wake of it works, or
  a turn of its conversation runs. The dispatcher wakes only a role that
  is not busy."
  [state role-id]
  (boolean (or (working-wake state role-id) (in-turn? (open-conversation state role-id)))))

(defn open?
  "Whether the role has any running wake, an idle conversation included."
  [state role-id]
  (boolean (seq (running-wakes state role-id))))


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

(declare public-role? shown-to-public? memo-labels)

(defn deliverable-memos
  "Open memos to a role that it may be shown, oldest first. A public role
  is shown only public memos with nothing private in them (I3): any other
  waits, unseen, until the operator closes it. relay refuses to send one
  now, so only an older log holds such a memo."
  [state config to]
  (if (and (not= operator to) (public-role? config to))
    (filterv #(shown-to-public? % (memo-labels state %)) (unread-memos state to))
    (unread-memos state to)))

(defn waiting-memos
  "Open memos a role may be shown that are not waiting out a retry delay."
  [state config to now]
  (vec (remove #(backing-off? % now) (deliverable-memos state config to))))

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

(defn waiting-on-tools?
  "Whether the dispatcher leaves a role alone because its MCP tools could
  not be set up: until its retry time. A block from an older relay that
  waited for relay fresh has no retry time, and waits no longer."
  [state role-id now]
  (when-let [{:keys [retry-at]} (get-in state [:roles role-id :tools-unavailable])]
    (boolean (and retry-at (after? retry-at now)))))

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

(declare unwakeable-public-role?)

(defn roles-to-wake
  "The roles the dispatcher should wake now: with a memo waiting, not busy
  (busy?), their harness not paused, their tools not waiting out a retry,
  and not stopped by the runaway guard. A public role whose own text holds
  private is never woken (unwakeable-public-role?)."
  [state config now]
  (vec (for [id (active-role-ids state)
             :when (and (seq (waiting-memos state config id now))
                        (not (busy? state id))
                        (not (paused-until state (harness-of state config id) now))
                        (not (waiting-on-tools? state id now))
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

(declare fact-shown-to-public?)

(defn facts
  "The facts holding at world time :as-of, as recorded at :known-at, by id.
  With :all, every fact recorded by then and not retracted, whatever its
  validity. :topic keeps that topic and the topics under it. :search ranks
  what is left by how well its text matches (relay.search, BM25), best
  first and ties by id, keeping only facts that share a word with it; a
  search with no words in it keeps facts whose text contains it, ignoring
  case. Every filter applies before the ranking, so the ranking says
  nothing about the facts left out. With :public-only, only facts a
  public role can see now (fact-shown-to-public? of the current version,
  as fact_history asks) whose version shown is public too, before any
  search, so a search or an earlier known_at says nothing about the
  others."
  [state {:keys [as-of known-at topic search all public-only]}]
  (let [as-of (or as-of known-at (:last-at state))
        found (->> (vals (:facts state))
                   (filter #(or (not public-only) (fact-shown-to-public? state (fact-as-known % nil))))
                   (keep #(fact-as-known % known-at))
                   (filter #(or (not public-only) (and (:public %) (not (private? (item-labels %))))))
                   (filter #(and (if all (not (:retracted %)) (holds-at? % as-of))
                                 (under-topic? topic (:topic %))))
                   (sort-by :id))]
    (vec (if (blank? search)
           found
           (or (search/rank found search {:text-fn :text :tie-fn :id})
               (let [needle (str/lower-case search)]
                 (filter #(str/includes? (str/lower-case (:text %)) needle) found)))))))

(defn facts-shown
  "The facts a reader is shown for a query (as for facts, without
  :public-only, which this sets): a public reader only public ones, any
  other every one."
  [state q {:keys [public]}]
  (facts state (assoc q :public-only (boolean public))))

(defn notes [state owner] (vec (sort-by :name (vals (get-in state [:notes owner])))))

(defn journal
  "A role's journal entries, oldest first, the last `limit` of them if given."
  [state owner limit]
  (let [entries (get-in state [:journals owner] [])]
    (if limit (vec (take-last limit entries)) entries)))


(defn journal-shown
  "The last `limit` entries of a role's journal that a reader may be shown,
  oldest first. A public reader is shown only public entries, and the
  others are not counted."
  [state owner limit public]
  (if public
    (vec (take-last limit (filter #(shown-to-public? % (item-labels %)) (journal state owner nil))))
    (journal state owner limit)))

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
;; Labels are provenance: they say where a thing came from, and drive two
;; things only. A public role is shown only public items and its sessions
;; may never hold private (agentloop's forbid_labels), and the operator is
;; shown where a thing came from. Inside the house nothing is withheld,
;; quarantined or held because of a label.

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

(defn memo-labels
  "What reading a memo addressed to you adds: its writers' labels, private
  unless it is public, and the labels of its sender's id."
  [state m]
  (item-labels m (id-labels state (:from m))))

(defn public-role?
  "Whether a role is public (relay.config/public?): a scout. Its sessions
  never hold private, so it is shown only public items and has nothing
  private to leak."
  [config role-id]
  (config/public? config role-id))

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
                  (mapcat #(tool-labels config %) (config/role-tools config role-id))))

(defn prompt-notes
  "A role's notes as its system prompt shows them: all of them, or for a
  public role only its public ones."
  [state config owner]
  (if (public-role? config owner)
    (filterv #(shown-to-public? % (item-labels %)) (notes state owner))
    (notes state owner)))

(def wake-journal-entries
  "How many of its latest journal entries a wake's prompt shows a role."
  3)

(defn prompt-journal
  "The tail of a role's journal as a wake's prompt shows it: the entries
  the journal tool would show for the last wake-journal-entries, oldest
  first."
  [state config owner]
  (journal-shown state owner wake-journal-entries (public-role? config owner)))

(defn prompt-skills
  "A role's skills as its system prompt lists them: [{:path :title
  :use-when :labels}]. The skills are the docs under skills/<role>/ whose
  latest version exists; a public role is shown only its public ones."
  [state config role]
  (let [prefix (str "skills/" role "/")
        public (public-role? config role)]
    (vec (for [path (sort (filter #(str/starts-with? % prefix) (keys (:docs state))))
               :let [v (doc-version state path {})]
               :when (and v (not (:removed v)) (or (not public) (doc-shown-to-public? state path v)))]
           (assoc (skills/parse (:content v)) :path path
                  :labels (union (item-labels v (version-labels state v)) (path-labels state path)))))))

(defn prompt-fact-topics
  "The fact topics a wake's prompt lists for a role: {:topics [{:topic
  :count :labels}], sorted}, counting the facts that hold now under
  exactly each topic (nil for facts with none). The facts are the ones the
  facts tool shows with no arguments: only public ones to a public role."
  [state config role]
  {:topics (->> (group-by :topic (facts-shown state {} {:public (public-role? config role)}))
                (map (fn [[topic group]] {:topic topic :count (count group)
                                          :labels (apply union (map item-labels group))}))
                (sort-by #(or (:topic %) "~"))
                vec)})

(defn card [state id] (get-in state [:cards id]))

(defn card-labels
  "What reading a card adds: private, and the labels of the text it shows
  now (relay.cards/text-labels). Ticks and cell states add none."
  [c]
  (content-labels (cards/text-labels c)))

(defn board
  "The cards on the board, oldest first: the active ones, and with
  `archived` the archived ones too."
  [state archived]
  (->> (vals (:cards state))
       (filter #(or archived (cards/active? %)))
       (sort-by :id)
       vec))

(defn prompt-cards
  "The cards a wake's or a conversation's prompt lists for a role:
  {:cards [{:id :title :kind :progress :labels}]}, oldest first: the active
  cards. Counts are numbers and add no labels. Nil for a public role,
  which has no board."
  [state config role]
  (when-not (public-role? config role)
    {:cards (mapv (fn [c] {:id (:id c) :title (get-in c [:title :text]) :kind (:kind c)
                           :progress (cards/progress c)
                           :labels (content-labels (get-in c [:title :labels]))})
                  (board state false))}))

(defn carried-memo-labels
  "What a memo carried in a wake adds wherever its text is shown again:
  memo-labels, and for a run of a routine that is not public, the
  routine's labels, since its subject is the routine's name. A public memo
  shows only its own text, which its labels already cover."
  [state m]
  (union (memo-labels state m)
         (when (and (:routine m) (not (:public m))) (routine-labels state (:to m) (:routine m)))))

(defn wake-labels
  "The labels of everything a wake's prompts are made of: the role's id,
  title and instructions, the notes and skills shown, the memos it
  carries and the ids of their senders, the routines they are runs of,
  the note from whoever woke it unless it was public, and the journal
  tail, fact topics and cards a wake's prompt lists. A conversation has no
  prompt, only the instructions, notes, skills and cards."
  [state config {:keys [role memos note note-public chat]}]
  (let [ms (keep #(memo state %) memos)]
    (union (role-labels state role)
           (notes-labels (prompt-notes state config role))
           (mapcat :labels (prompt-skills state config role))
           (mapcat #(carried-memo-labels state %) ms)
           (when (and (not chat) (not (blank? note)) (not note-public)) [private-label])
           (when-not chat (journal-labels (prompt-journal state config role)))
           (when-not chat (mapcat :labels (:topics (prompt-fact-topics state config role))))
           (mapcat :labels (:cards (prompt-cards state config role))))))

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

(def removed-event-kinds
  "Events older versions of relay wrote for machinery that no longer
  exists: research reports and their verification, the ledger and trust
  levels; held calls and the operator's decisions on them, a
  conversation's permission requests, trusting contaminated text,
  releasing a quarantined memo, declassifying one, and the marker where
  labels began. The log is append-only, so they stay in old logs; they
  fold to nothing (I8), with two exceptions that keep old items as they
  were: the labels marker still says where labels began
  (with-legacy-labels), and a declassified memo keeps the text the
  operator vetted. Their labels still count as seen."
  #{"report/submitted" "report/verify-started" "report/verify-failed" "report/verified"
    "finding/reviewed" "ledger/entry-added" "ledger/entry-withdrawn" "ledger/status-set"
    "role/trust-level"
    "hold/opened" "hold/previewed" "hold/resolved" "hold/requeued" "hold/session-ended"
    "permission/asked" "permission/answered"
    "label/trusted" "memo/released" "memo/declassified" "labels/enabled"})

(defmethod evolve* :default [state event]
  (when-not (removed-event-kinds (:type event))
    (throw (ex-info (str "This version of relay does not know event type " (:type event)
                       " (event " (:seq event) "). Upgrade relay before reading this log.")
                  {:event event})))
  state)

(def public-item-events
  "The events that write an item a public role may be shown: these carry
  :public when their writer was public (decide sets it, never a role's
  input), and the item keeps it."
  #{"memo/sent" "routine/written" "fact/added" "fact/superseded" "fact/corrected"
    "note/written" "journal/written" "doc/put" "doc/removed" "role/added" "role/updated"})

(defn evolve [state {:keys [seq at] :as event}]
  (when-not (= seq (:next-seq state))
    (throw (ex-info (str "Event " seq " is out of order; expected " (:next-seq state) ".")
                    {:event event})))
  (let [{:keys [labels]} event]
    ;; A public item never carries private: decide refuses what would make
    ;; one, so this can only be a bug, and nothing is folded past it.
    (when (and (:public event) (not (removed-event-kinds (:type event)))
               (or (private? labels) (not (public-item-events (:type event)))))
      (throw (ex-info (str "Event " seq " is public and carries private, or is not an item a public role"
                           " may read. relay refuses to read it.")
                      {:event event})))
    (-> (evolve* state event)
        (update :seen-labels union labels)
        (assoc :next-seq (inc seq) :last-at (inst at)))))

(def legacy-labels
  "What anything a role or the dispatcher wrote before relay kept labels
  is taken to have seen: no record says otherwise, so everything."
  [contaminated-label private-label])

(defn with-legacy-labels
  "The events as replay folds them. Every event before the log's first
  labels/enabled marker, by anyone but the operator, carries
  legacy-labels and is not public: relay did not record what its writer
  had seen. The operator's own writing is hers. The marker itself is a
  removed kind and folds to nothing; only its place in the log counts. A
  log with no marker was written by a relay that kept labels from the
  start (this one never writes the marker)."
  [events]
  (if-let [marker (some #(when (= "labels/enabled" (:type %)) (:seq %)) events)]
    (map (fn [{:keys [seq actor] :as event}]
           (if (and (< seq marker) (not= operator actor))
             (-> event (update :labels union legacy-labels) (dissoc :public))
             event))
         events)
    events))

(defn replay
  "The state the events fold to (with-legacy-labels)."
  [events]
  (reduce evolve empty-state (with-legacy-labels events)))

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

(defmethod evolve* "memo/declassified" [state {:keys [data]}]
  ;; Declassify is gone, and so is what it did to who may read the memo:
  ;; it stays as private as it was written. Its text stays the one the
  ;; operator vetted, as it always read after this event.
  (if (get-in state [:memos (:memo data)])
    (update-in state [:memos (:memo data)]
               #(cond-> %
                  (string? (:subject data)) (assoc :subject (:subject data))
                  (string? (:body data)) (assoc :body (:body data))))
    state))

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

(defn definition-changed?
  "Whether a wake's tools-error says an MCP tool's definition changed
  under its session: agentloop gives the digest the session pinned and the
  one the server lists now. Such a session cannot go on."
  [tools-error]
  (boolean (and (map? tools-error) (:pinned tools-error) (:got tools-error))))

(defmethod evolve* "wake/started" [state {:keys [seq at data]}]
  (let [{:keys [role memos note note-public public-session resumes harness pid pid-start chat]} data]
    (cond-> (assoc-in state [:wakes seq] {:id seq :role role :memos (vec memos) :note note
                                          :note-public (true? note-public)
                                          :public-session (true? public-session)
                                          :resumes resumes :harness harness :pid pid
                                          :pid-start pid-start :started-at (inst at)
                                          :status :running :chat (boolean chat)})
      ;; A conversation's turns: none yet, none ended and none seen.
      chat (update-in [:wakes seq] assoc :turn :idle :turns 0 :turns-ended 0 :seen 0
                      :last-activity (inst at))
      ;; A conversation leaves an interrupted wake to be resumed later.
      (not chat) (assoc-in [:roles role :interrupted-wake] nil))))

(defmethod evolve* "wake/ended" [state {:keys [at data labels]}]
  (let [{:keys [wake outcome exit crashed cut-short session sessions said transcript usage cost-usd tools-error]} data
        role-id (get-in state [:wakes wake :role])
        chat (get-in state [:wakes wake :chat])]
    (cond-> (update-in state [:wakes wake] assoc
                       :status :ended :ended-at (inst at) :outcome outcome :exit exit
                       :crashed (boolean crashed) :cut-short (boolean cut-short)
                       :session session :said said :transcript transcript :labels (union labels))
      sessions (assoc-in [:wakes wake :sessions] (vec sessions))
      ;; What its model calls used and cost. A wake from before relay kept
      ;; them, or whose cost is unknown, has none: never 0.
      (map? usage) (assoc-in [:wakes wake :usage] usage)
      (number? cost-usd) (assoc-in [:wakes wake :cost-usd] cost-usd)
      ;; A wake the harness failed, or one cut short at its deadline, is
      ;; kept to be resumed. A conversation is not, and nor is a session
      ;; whose tool changed under it: the next wake starts afresh.
      (and (or (not= "ran" outcome) cut-short) (not chat) (not (definition-changed? tools-error)))
      (assoc-in [:roles role-id :interrupted-wake] wake)
      ;; A wake that ran had its tools; a conversation clears no block.
      (and (= "ran" outcome) (not chat) (get-in state [:roles role-id :tools-unavailable]))
      (update-in [:roles role-id] dissoc :tools-unavailable)
      ;; It also ends a streak of changed definitions (tools-unavailable).
      (and (= "ran" outcome) (not chat) (get-in state [:roles role-id :tool-changed]))
      (update-in [:roles role-id] dissoc :tool-changed))))

(defmethod evolve* "tool/pinned" [state {:keys [data]}]
  (cond-> (assoc-in state [:tool-pins (:tool data)] (:digest data))
    ;; Taken because the definition changed under that role's session.
    (and (:role data) (get-in state [:roles (:role data)]))
    (assoc-in [:roles (:role data) :tool-changed] (:tool data))))

(defn tool-pin
  "The definition digest relay accepted for an MCP tool, by its name in
  relay.edn, or nil before its first use."
  [state tool]
  (get-in state [:tool-pins tool]))

(defmethod evolve* "role/tools-unavailable" [state {:keys [at data]}]
  (let [{:keys [role wake session server tool retry-at tries]} data]
    (assoc-in state [:roles role :tools-unavailable]
              {:wake wake :session session :server server :tool tool :since (inst at)
               :retry-at (inst retry-at) :tries tries})))

(defmethod evolve* "conversation/line-dropped" [state _] state)

(defmethod evolve* "conversation/turn-started" [state {:keys [at data]}]
  (let [{:keys [wake session turn]} data]
    (update-in state [:wakes wake] assoc :turn :running :turns turn :turn-session session
               :last-activity (inst at))))

(defmethod evolve* "conversation/turn-ended" [state {:keys [at data]}]
  (let [{:keys [wake turn stop-reason]} data]
    (update-in state [:wakes wake] assoc :turn :idle :turns-ended turn :stop-reason stop-reason
               :last-activity (inst at))))

(defmethod evolve* "conversation/seen" [state {:keys [data]}]
  (assoc-in state [:wakes (:wake data) :seen] (:turn data)))

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
      (update :unwakeable-told dissoc role)
      ;; The highest share of the weekly budget told for each week.
      (= "budget-reached" kind)
      (update-in [:budget-told (:week data)] (fnil max 0) (:percent data)))))

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

(defmethod evolve* "journal/written" [state {:keys [seq at actor data labels public]}]
  (update-in state [:journals (:owner data)] (fnil conj [])
             {:id seq :owner (:owner data) :actor actor :text (:text data) :at (inst at) :labels (union labels)
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
  (add-doc-version state event (:path data) (cond-> {:content (:content data)}
                                              (:shown data) (assoc :shown (:shown data)))))

(defmethod evolve* "doc/removed" [state {:keys [data] :as event}]
  (add-doc-version state event (:path data) {:removed true}))

(defmethod evolve* "card/added" [state event]
  (update state :cards cards/add event))

(defmethod evolve* "card/changed" [state event]
  (update state :cards cards/change event))

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
     (if (= operator to)
       (notify tx "memo-for-operator" subject body :memo (last-id tx) :labels copied)
       tx))))

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
      ;; The dispatcher writes it, so it is never public, whoever's call
      ;; set it off.
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

(declare public-actor? shown-path?)

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
  ;; An idle conversation still runs in its own process, so it counts.
  (when (open? state id) (reject! :busy (str id " is running now; remove it once it stops.") (id-labels state id)))
  (as-> tx tx
    (reduce (fn [tx {:keys [name]}] (emit tx "routine/removed" {:owner id :name name}))
            tx (active-routines state id))
    (emit tx "role/removed" {:id id})
    (reduce (fn [tx m] (dead-letter tx (:id m) "recipient-removed" []))
            tx (unread-memos state id))))

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
    ;; A public role is shown only public text. A memo from inside the
    ;; house would carry the operator's things out to it, so until scouts
    ;; take requests (B5) none is sent.
    (when (and (not= operator actor) (public-role? (:config tx) to) (not (:public tx)))
      (reject! :forbidden (str to " works on the open web, and a memo from " actor " could carry your"
                               " private things to it, so it was not sent.")))
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
      (reject! :invalid (str "Not a schedule relay understands: " (pr-str schedule) ". Use every 30m, "
                    "every 2h, every 1d, daily 07:30, weekly mon 09:00 or once 2026-10-08 09:00.")))
    (let [last-run (memo (:state tx) (:last-memo (routine (:state tx) owner name)))
          tx (emit tx "routine/written" {:owner owner :name name
                                         :schedule (schedule/render (schedule/parse schedule))
                                         :instructions (require-text instructions "A routine's instructions")})]
      ;; A public role cannot see or close an open run that is not public,
      ;; which would keep the rewritten routine from firing. The rewrite
      ;; replaces it.
      (if (and last-run (= :unread (:status last-run)) (public-role? (:config tx) owner)
               (not (shown-to-public? last-run (memo-labels (:state tx) last-run))))
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

(defn- start-wake
  "What :wake/start does once the role is known to be free."
  [{:keys [state config now actor] :as tx} {{role-id :role :keys [note dispatched chat pid pid-start]} :args}]
  (let [harness (harness-of state config role-id)]
    (when dispatched
      (when (paused-until state harness now) (reject! :paused (str harness " is paused.")))
      (when (running-away? state role-id config now)
        (reject! :runaway (str role-id " has been stopped by the runaway guard.")))
      (when (waiting-on-tools? state role-id now)
        (reject! :paused (str role-id "'s tools could not be set up."))))
    ;; A conversation with the operator holds the role only while a turn
    ;; of it runs, and carries no memos: what the role does with them
    ;; there is up to the conversation, and nothing counts against them
    ;; when it ends.
    (when (and chat (not= operator actor))
      (reject! :forbidden "Only the operator can open a conversation."))
    (let [;; A wake starts with the memos the role may be shown, in a fresh
          ;; session unless the last one was cut short.
          memos (cond chat []
                      dispatched (waiting-memos state config role-id now)
                      :else (deliverable-memos state config role-id))
          public (public-role? config role-id)
          ;; A session from before a public role was public is not carried
          ;; on as one: it may hold private text that no label records.
          before-public? #(and public % (not (get-in state [:wakes % :public-session])))
          interrupted (let [w (:interrupted-wake (role state role-id))]
                        (when-not (before-public? w) w))
          ;; A note the operator wrote with --public may go where the role
          ;; sends things; the conversation's own note is relay's.
          note-public (boolean (and (not chat) (not (blank? note)) (:public tx)))]
      (when (and public (not chat) (not (blank? note)) (not note-public))
        (reject! :invalid (str role-id " is a public role, so a note for it needs --public. " public-means)))
      (when-let [why (unwakeable-reason state config role-id)]
        (reject! :invalid (str role-id " is a public role and cannot run as one: " why)))
      ;; The last line: nothing private goes into a public role's prompts.
      (when (and public (private? (wake-labels state config {:role role-id :memos (mapv :id memos) :note note
                                                             :note-public note-public :chat chat})))
        (reject! :invalid (str role-id " is a public role, and its prompts would hold private data.")))
      (if (and (empty? memos) (blank? note) (not chat))
        tx
        (emit tx "wake/started" (cond-> {:role role-id :memos (mapv :id memos) :note note
                                         :resumes (when-not chat interrupted)
                                         :harness harness :pid pid :pid-start pid-start}
                                  note-public (assoc :note-public true)
                                  public (assoc :public-session true)
                                  chat (assoc :chat true)))))))

(defmethod decide* :wake/start [{:keys [actor] :as tx} {{role-id :role :keys [chat]} :args :as command}]
  (when-not (#{dispatcher operator} actor)
    (reject! :forbidden "Only the operator or the dispatcher wakes a role."))
  (active-role! (:state tx) role-id)
  (when (and chat (not= operator actor))
    (reject! :forbidden "Only the operator can open a conversation."))
  ;; A role keeps one conversation open at a time. It may open while a
  ;; wake works: its first turn waits for the wake to end. A wake starts
  ;; while a conversation is open, but not while a turn of it runs.
  (if chat
    (when (open-conversation (:state tx) role-id)
      (reject! :busy (str role-id " already has a conversation open.") (id-labels (:state tx) role-id)))
    (when (busy? (:state tx) role-id)
      (reject! :busy (str role-id " is already running.") (id-labels (:state tx) role-id))))
  (start-wake tx command))

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
                  (str "It said:\n\n" said "\n\nEvery role on it waits, and nothing has been "
                       "dropped. Work carries on by itself once it runs again.")
                  :harness harness :labels (:said-labels tx))
          :else tx)))))

(defn- tell-refusals
  "Tells the operator about what agentloop kept from a public role's
  session for carrying their private things: a session that would not
  start, and tool results it refused. Names the tools, never what they
  returned."
  [{:keys [state] :as tx} wake {:keys [refused forbidden]}]
  (let [role (:role wake)
        tools (sort (distinct (keep #(when (and (map? %) (string? (:tool %))) (:tool %)) (when (sequential? refused) refused))))
        forbidden (when (sequential? forbidden) (filterv label-names forbidden))]
    (cond-> tx
      (seq forbidden)
      (notify "session-forbidden" (str role "'s session did not start")
              (str "It had seen your private things, which " role " works without, so it did not run."
                   " Its next wake starts a fresh session.")
              :role role :labels (id-labels state role))
      (seq tools)
      (notify "result-refused" (str "Something was kept from " role)
              (str (str/join ", " tools) " returned your private things, which " role
                   " works without, so it was not shown " (if (next refused) "them." "it."))
              :role role :labels (id-labels state role)))))

(defn wake-usage
  "Token counts as a wake records them, {:input :output :cache-read
  :cache-write}, each a whole number of zero or more, or nil when `u` is
  not that."
  [u]
  (when (and (map? u) (= #{:input :output :cache-read :cache-write} (set (keys u)))
             (every? #(and (integer? %) (>= % 0)) (vals u)))
    (into {} (map (fn [[k v]] [k (long v)])) u)))

(defn wake-cost
  "A cost in US dollars as a wake records it, or nil when `x` is not a
  finite amount of zero or more."
  [x]
  (when (and (number? x) (Double/isFinite (double x)) (>= (double x) 0.0))
    (double x)))

(defn- warn-of-spend
  "Tells the operator when the week's spend, all roles together, reaches
  80% of the weekly budget and when it reaches 100%, once each a week. A
  wake that passes both at once tells only 100%. A warning only: relay
  never stops or slows a role for what it costs."
  [{:keys [state config now] :as tx}]
  (if-let [budget (get-in config [:budget :weekly-usd])]
    (let [{:keys [week cost-usd]} (spend/this-week (vals (:wakes state)) (config/zone config) now)
          percent (spend/threshold-reached budget cost-usd (get-in state [:budget-told (str week)]))
          usd #(format "$%.2f" (double %))]
      (if-not percent
        tx
        (notify tx "budget-reached"
                (if (= 100 percent)
                  "relay has spent its weekly budget"
                  (str "relay has spent " percent "% of its weekly budget"))
                (str "The roles have spent " (usd cost-usd) " this week, from Monday " week
                     ", against a weekly budget of " (usd budget) ". Nothing is stopped or slowed;"
                     " relay status shows spend per role.")
                :week (str week) :percent percent)))
    tx))

(defn role-wrote-in-wake?
  "True when `role` itself wrote one of `entries` after `wake` started. An
  entry the operator or relay wrote in the role's journal does not count."
  [entries wake role]
  (boolean (some #(and (> (:id %) (:id wake)) (= role (:actor %))) entries)))

(defn- journal-cut-short
  "Writes a journal entry for a role whose wake was cut short at its
  deadline before it wrote one, so its next wake knows: when, and which
  memos the wake had. It quotes their subjects, so it carries their
  labels, and it is public only when the wake was a public session and
  every memo it quotes is public."
  [{:keys [state config now] :as tx} wake]
  (let [role (:role wake)
        ms (keep #(memo state %) (:memos wake))
        wrote? (role-wrote-in-wake? (journal state role nil) wake role)]
    (if (or (:chat wake) wrote?)
      tx
      (let [copied (apply union (map #(carried-memo-labels state %) ms))
            public (boolean (and (:public-session wake) (not (private? (union (:labels tx) copied)))
                                 (every? :public ms)))
            at (.format (.atZone ^Instant now (config/zone config)) (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))
            text (str "Written by relay: this wake was cut short at its deadline, at " at
                      ", before you wrote a journal entry. "
                      (if (seq ms)
                        (str "It had " (if (next ms) "memos " "memo ")
                             (str/join ", " (for [m ms] (str (:id m) " (" (:subject m) ")")))
                             ". ")
                        "It had no memos. ")
                      "Check what was done before carrying on.")]
        (with-public tx public #(emit % "journal/written" {:owner role :text text} copied))))))

(defn wake-tools-error
  "Why a role's MCP tools could not be set up, as a wake records it:
  {:message} with any of :server, :tool, :pinned and :got, all strings, or
  nil when `e` is not that."
  [e]
  (when (and (map? e) (string? (:message e)) (not (blank? (:message e))))
    (into {} (filter (fn [[k v]] (and (#{:message :server :tool :pinned :got} k) (string? v)))) e)))

(defn- record-pins
  "Remembers the definition digest of each MCP tool a wake's session
  pinned that relay has not seen before (trust on first use, as SSH's
  known_hosts). Every later request names it, so any session, of any
  role, that is offered another definition stops, and relay takes the new
  one for the next (tools-unavailable)."
  [{:keys [state config] :as tx} wake pins]
  (if (or (:chat wake) (not (map? pins)))
    tx
    (reduce (fn [tx [tool digest]]
              (let [tool (if (keyword? tool) (name tool) tool)]
                (if (and (string? tool) (string? digest) (re-matches #"[0-9a-f]{64}" digest)
                         (config/mcp-tool? (get-in config [:tools tool]))
                         (nil? (tool-pin (:state tx) tool)))
                  (emit tx "tool/pinned" {:tool tool :digest digest})
                  tx)))
            tx (sort-by (comp str key) pins))))

(defn- tools-unavailable
  "Records that a wake's MCP tools could not be set up. When a tool's
  definition changed under the session (the session pinned another),
  relay takes the new definition, tells the operator, and the role's next
  wake starts a fresh session with it. When it changes again before the
  role has run since, as a server whose description carries a timestamp
  does, relay still takes it but tries the role again only after a wait
  that doubles, and says nothing more: one notification per streak, and
  no agentloop started on every tick. When relay.edn's :digest pins the
  old one, the operator must change :digest: until then the role is tried
  again after a wait that doubles, as for any other failure."
  [{:keys [state config ^Instant now] :as tx} wake session {:keys [message server tool pinned got]}]
  (let [role (:role wake)
        before (get-in state [:roles role :tools-unavailable])
        changed (boolean (and pinned got))
        mcp (and tool (config/mcp-tool? (get-in config [:tools tool])))
        config-pin (get-in config [:tools tool :digest])
        ;; relay.edn's :digest is the operator's own pin, which relay never
        ;; moves.
        takes-new (and changed mcp (nil? config-pin) (string? got) (re-matches #"[0-9a-f]{64}" got))
        ;; It changed under this role's last session too.
        again (and takes-new (= tool (get-in state [:roles role :tool-changed])))
        back-off (fn [tx]
                   (let [tries (inc (or (:tries before) 0))
                         retry-at (.plusSeconds now (config/retry-delay-seconds config tries))]
                     (emit tx "role/tools-unavailable" (cond-> {:role role :wake (:id wake) :session session
                                                                :tries tries :retry-at (str retry-at)}
                                                         server (assoc :server server)
                                                         tool (assoc :tool tool)))))]
    (cond
      (and takes-new (= got (tool-pin state tool)))
      ;; Taken already, when another session stopped on the same change.
      tx

      again
      (-> tx (emit "tool/pinned" {:tool tool :digest got :role role}) back-off)

      takes-new
      (-> tx
          (emit "tool/pinned" {:tool tool :digest got :role role})
          (notify "tool-changed" (str tool " changed")
                  (str "The server behind " tool " now describes it differently, and relay uses the new"
                       " version from now on. " role " starts a fresh session with it at its next wake.")
                  :role role :labels (:said-labels tx)))

      :else
      (let [tx (back-off tx)]
        (if before
          tx
          (notify tx "tools-unavailable" (str role "'s tools could not be set up")
                  (str "agentloop said:\n\n" message "\n\n"
                       (if (and changed config-pin)
                         (str "The definition no longer matches the :digest relay.edn gives " tool "."
                              " Check the change. To take it, set :digest to " got ".")
                         (str "Nothing ran and nothing is dropped. relay tries " role " again after a wait"
                              " that doubles each time; `relay wake " role "` tries at once.")))
                  :role role :labels (:said-labels tx)))))))

(defn- tell-shown
  "Tells the operator what a wake showed them, by path only: a display's
  title and body are its tool's text. A conversation's operator saw them
  as they came, so they are not told again."
  [tx wake shown]
  (let [paths (when (sequential? shown) (vec (distinct (filter #(and (string? %) (shown-path? %)) shown))))]
    (if (or (empty? paths) (:chat wake))
      tx
      (notify tx "shown" (str (:role wake) " showed you " (count paths) (if (= 1 (count paths)) " thing" " things"))
              (str "While it worked, " (:role wake) " showed you what it found. Read "
                   (if (= 1 (count paths)) "it" "them") " with relay doc:\n\n"
                   (str/join "\n" (map #(str "relay doc " %) paths)))
              :role (:role wake)))))

(def stop-reasons
  "How a conversation's turn can end: ACP's stop reasons, \"error\" when
  the agent answered the prompt with an error, \"ended\" when the
  conversation ended during the turn, and \"other\", which anything
  else is kept as, so the log holds none of an agent's own text here."
  #{"end_turn" "max_tokens" "max_turn_requests" "refusal" "cancelled" "error" "ended" "other"})

(defn- end-turn
  "Ends the turn running in a conversation."
  [{:keys [state] :as tx} wake-id stop-reason]
  (let [{:keys [role turns]} (get-in state [:wakes wake-id])]
    (emit tx "conversation/turn-ended" {:wake wake-id :role role :turn turns
                                        :stop-reason (if (stop-reasons stop-reason) stop-reason "other")})))

(defn- end-wake [{:keys [state config] :as tx} wake-id {:keys [outcome exit crashed cut-short session sessions said
                                                              transcript resets-at labels usage cost-usd
                                                              tools-error pins shown]
                                                       :as report}]
  (let [wake (get-in state [:wakes wake-id])
        tools-error (when (and (= "needs-attention" outcome) (not (:chat wake)))
                      (wake-tools-error tools-error))
        ;; What the session said is its own text, with the labels it had
        ;; gathered by the end: as its harness reports them, or when it
        ;; does not, every label it could have gathered.
        tx (assoc tx :said-labels (if (and (sequential? labels) (every? label-names labels))
                                    (union labels)
                                    (session-labels-bound state config (:role wake))))]
    (when-not (harness-outcomes outcome)
      (reject! (str "Unknown harness outcome " (pr-str outcome) ".")))
    ;; A conversation records every session it used, in order, instead of
    ;; the one session a wake resumes from.
    (when (some? sessions)
      (when-not (and (sequential? sessions) (every? #(and (string? %) (not (blank? %))) sessions))
        (reject! (str "Sessions must be a list of session ids, not " (pr-str sessions) ".")))
      (when-not (:chat wake)
        (reject! "Only a conversation records a list of sessions.")))
    (as-> tx tx
      ;; A conversation that ends during a turn ends the turn first.
      (if (in-turn? wake) (end-turn tx wake-id "ended") tx)
      (emit tx "wake/ended" (cond-> {:wake wake-id :outcome outcome :exit exit :crashed (boolean crashed)
                                     :cut-short (boolean cut-short) :session session :said said
                                     :transcript transcript}
                              (some? sessions) (assoc :sessions (vec sessions))
                              (wake-usage usage) (assoc :usage (wake-usage usage))
                              (wake-cost cost-usd) (assoc :cost-usd (wake-cost cost-usd))
                              tools-error (assoc :tools-error tools-error))
            (:said-labels tx))
      (record-pins tx wake pins)
      ;; Tools that could not be set up are the role's trouble, not its
      ;; harness's: no model was called.
      (if tools-error
        (tools-unavailable tx wake session tools-error)
        (note-harness tx (:harness wake) outcome resets-at said))
      (if cut-short (journal-cut-short tx wake) tx)
      (if (= "ran" outcome) (settle-memos tx wake said) tx)
      (tell-refusals tx wake report)
      (tell-shown tx wake shown)
      ;; Only a cost can move the week's total.
      (if (wake-cost cost-usd) (warn-of-spend tx) tx)
      (dissoc tx :said-labels))))

;; the operator's own

(defn- operator-only! [{:keys [actor]} what]
  (when-not (= operator actor)
    (reject! :forbidden (str "Only the operator " what "."))))

(defmethod decide* :result/refused [{:keys [state actor] :as tx} {{:keys [tool]} :args}]
  ;; relay kept a tool's result from a public role's session for carrying
  ;; private. The role's own call reports it; the operator is told which
  ;; tool, never what it returned.
  (caller-role! tx)
  (when-not (public-actor? tx) (reject! :forbidden "Only a public role's results are refused."))
  (notify tx "result-refused" (str "Something was kept from " actor)
          (str tool " returned your private things, which " actor " works without, so it was not shown it.")
          :role actor :labels (id-labels state actor)))

(defmethod decide* :conversation/turn-unrecorded [{:keys [state] :as tx} {{:keys [wake]} :args}]
  ;; relay serve could not record the end of a conversation's turn, so the
  ;; role stays busy until it can; it keeps trying and asks for this once.
  (dispatcher-only! tx)
  (let [{:keys [role chat]} (get-in state [:wakes wake])]
    (if chat
      (notify tx "turn-unrecorded" (str "relay could not record the end of " role "'s turn")
              (str "relay keeps trying. Until it can, " role " stays busy: no wake of it and no other turn"
                   " starts. A full disk or a log relay cannot write to would do this.")
              :role role :wake wake :labels (id-labels state role))
      tx)))

(defmethod decide* :conversation/line-dropped [{:keys [state] :as tx} {{:keys [wake size]} :args}]
  (dispatcher-only! tx)
  (let [role (get-in state [:wakes wake :role])]
    (-> tx
        (emit "conversation/line-dropped" {:wake wake :role role :size size})
        (notify "conversation-line-dropped" (str "relay dropped a message from " role "'s agent")
                (str "A line of " size " bytes was too long to check, so its client never saw it.")
                :role role :labels (id-labels state role)))))

(def ^:private session-pattern
  "An ACP session id as relay keeps it: agentloop's are UUIDs."
  #"[A-Za-z0-9._:-]{1,128}")

(defn- conversation!
  "The conversation with this id, or a refusal."
  [state wake-id]
  (let [w (get-in state [:wakes wake-id])]
    (when-not (:chat w) (reject! :not-found (str "There is no conversation " (pr-str wake-id) ".")))
    w))

(defmethod decide* :conversation/turn-start [{:keys [state] :as tx} {{:keys [wake session]} :args}]
  ;; The operator sends each turn, from whichever client.
  (operator-only! tx "starts a turn of a conversation")
  (let [{:keys [role turns] :as w} (conversation! state wake)]
    (when-not (= :running (:status w)) (reject! :invalid (str "Conversation " wake " has ended.")))
    (when (in-turn? w) (reject! :invalid (str "A turn of conversation " wake " is running already.")))
    (when-not (and (string? session) (re-matches session-pattern session))
      (reject! :invalid "A turn needs the ACP session it runs in."))
    ;; The role takes one turn at a time: this one waits for the wake.
    (when (working-wake state role)
      (reject! :busy (str role " is busy with scheduled work. Send this again once it is done.")
               (id-labels state role)))
    (emit tx "conversation/turn-started" {:wake wake :role role :session session :turn (inc turns)})))

(defmethod decide* :conversation/turn-end [{:keys [state] :as tx} {{:keys [wake stop-reason]} :args}]
  ;; Total: whatever ends a turn may be told twice, or after the wake.
  (dispatcher-only! tx)
  (let [w (get-in state [:wakes wake])]
    (if (and (:chat w) (= :running (:status w)) (in-turn? w))
      (end-turn tx wake stop-reason)
      tx)))

(defmethod decide* :conversation/seen [{:keys [state] :as tx} {{:keys [wake turn]} :args}]
  (operator-only! tx "says what they have seen of a conversation")
  (let [{:keys [role turns seen]} (conversation! state wake)]
    (when-not (and (integer? turn) (<= 1 turn (or turns 0)))
      (reject! :invalid (str "Conversation " wake " has no turn " (pr-str turn) ".")))
    (if (> turn (or seen 0))
      (emit tx "conversation/seen" {:wake wake :role role :turn turn})
      tx)))

;; flags: the operator marks a wake or a conversation to find it later

(def flag-values #{"good" "bad"})

(defmethod evolve* "wake/flagged" [state {:keys [seq at data]}]
  (update-in state [:wakes (:wake data) :flags] (fnil conj [])
             {:id seq :value (:value data) :note (:note data) :at (inst at)}))

(defmethod decide* :wake/flag [{:keys [state] :as tx} {{:keys [wake value note]} :args}]
  ;; A flag grants nothing and changes nothing a role sees, so it needs
  ;; no code; it is still the operator's alone.
  (operator-only! tx "flags a wake or a conversation")
  (when-not (and (integer? wake) (get-in state [:wakes wake]))
    (reject! :not-found (str "There is no wake or conversation " wake ".")))
  (when-not (flag-values value)
    (reject! :invalid (str "A flag is good or bad, not " (pr-str value) ".")))
  (emit tx "wake/flagged" (cond-> {:wake wake :value value}
                            (not (blank? note)) (assoc :note (str/trim note)))))

;; relay connect: the operator looked at a remote server's tools and
;; pinned the definition of each one relay proposed. Every later session,
;; of any role, is held to it, so connecting again replaces what an
;; earlier connect or first use pinned.
(defmethod decide* :tools/pin [{:keys [state] :as tx} {{:keys [pins]} :args}]
  (operator-only! tx "pins tool definitions")
  (when-not (and (map? pins) (seq pins)
                 (every? (fn [[t d]] (and (string? t) (re-matches #"[A-Za-z0-9_-]{1,64}" t)
                                          (string? d) (re-matches #"[0-9a-f]{64}" d)))
                         pins))
    (reject! :invalid "Pins are tool names and SHA-256 digests in lowercase hex."))
  (reduce (fn [tx [tool digest]]
            (if (= digest (tool-pin state tool)) tx (emit tx "tool/pinned" {:tool tool :digest digest})))
          tx (sort-by key pins)))

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
                       (not (busy? state id))
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
                      "Every role on it waits, and nothing has been dropped. Work carries on by itself once it is back."
                      :harness harness)
              tx))
          tx (sort-by key (:harnesses state))))

(def conversation-gone
  "What a conversation the tick found lost says: its client stopped relay
  before relay could record the end."
  "The conversation's client went away without letting relay record the end.")

(defmethod decide* :tick [tx {{:keys [lost-wakes]} :args}]
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

;; the morning brief (relay.brief composes it)

(defn brief-date
  "The date of the last morning brief relay composed, sent or not, or nil."
  ^LocalDate [state]
  (some-> (:brief-date state) LocalDate/parse))

(defmethod evolve* "brief/composed" [state {:keys [data]}]
  (assoc state :brief-date (:date data)))

(defmethod decide* :brief/send [{:keys [state] :as tx} {{:keys [date title body labels sections]} :args}]
  ;; Recorded once a day under the log's lock, so two ticks cannot both
  ;; send one, and a restart sends none twice. A brief with nothing to say
  ;; is recorded with no title, and nothing is pushed.
  (dispatcher-only! tx)
  (let [d (or (try (LocalDate/parse (str date)) (catch Exception _ nil))
              (reject! (str "A brief needs its date, not " (pr-str date) ".")))]
    (when (some-> (brief-date state) (.isBefore d) not)
      (reject! :invalid (str "The brief for " d " was already composed.")))
    (cond-> (emit tx "brief/composed" {:date (str d) :sent (some? title) :sections sections})
      title (notify "brief" title body :date (str d) :labels labels))))

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
          (reject! :invalid (str what " is not a time relay understands: " (pr-str s) ". Use 2026-10-14, "
                        "2026-10-14 09:00 or 2026-10-14T13:00:00Z."))))))

(def ^:private topic-pattern #"[a-z0-9][a-z0-9-]*(/[a-z0-9][a-z0-9-]*)*")

(defn- fact-topic [topic]
  (when-not (blank? topic)
    (let [t (str/trim topic)]
      (when-not (and (<= (count t) 100) (re-matches topic-pattern t))
        (reject! :invalid (str "A topic is lower case words joined by hyphens, with / between levels,"
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

(defn- own-skills-only!
  "A role writes skills only under skills/<its own id>/. The operator may
  write anywhere."
  [{:keys [state actor]} path]
  (when (and (role state actor) (not (skills/writable-by? actor path)))
    (reject! :forbidden (str "A role writes only its own skills, under skills/" actor "/. " path " is not there."))))

(defmethod decide* :doc/put [{:keys [state] :as tx} {{:keys [path content]} :args}]
  (let [path (doc-path path)
        _ (own-skills-only! tx path)]
    ;; Only what the operator was shown goes under shown/, so a doc there
    ;; is always what relay stored, never a role's own text passed off as
    ;; something the operator saw.
    (when (shown-path? path)
      (reject! :invalid (str "Docs under shown/ are what you were shown. Nobody writes there; choose"
                             " another path.")))
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

(defmethod decide* :doc/remove [{:keys [state actor] :as tx} {{:keys [path]} :args}]
  (let [path (doc-path path)
        _ (own-skills-only! tx path)
        v (doc-version state path {})]
    ;; What the operator was shown stays where the workspace pane and the
    ;; notification said it is.
    (when (and (shown-path? path) (not= operator actor))
      (reject! :invalid "Docs under shown/ are what the operator was shown, and stay there."))
    (when (or (nil? v) (:removed v) (and (public-actor? tx) (not (doc-shown-to-public? state path v))))
      (reject! :not-found (str "There is no doc at " path ".")))
    (emit tx "doc/removed" {:path path})))

;; cards
;;
;; Cards on the operator's board (relay.cards). They are private only: a
;; public role is not offered the card tools, and a card command from a
;; public role's session is refused, so a card is never on the public
;; side. A card change never wakes a role and never notifies anyone; a
;; role sees the new state when it next reads. The operator's own changes
;; (the command line, a client's pane) carry no labels.

(defn- card-keeper!
  "Refuses a card command from anyone but the operator or a role that is
  not public."
  [{:keys [actor] :as tx}]
  (when (public-actor? tx)
    (reject! :forbidden (str actor " is a public role, and a public role has no board.")))
  (when (= dispatcher actor)
    (reject! :forbidden "Only the operator or a role keeps cards.")))

(defmethod decide* :card/add [{:keys [state] :as tx} {:keys [args]}]
  (card-keeper! tx)
  (emit tx "card/added" (cards/added (:cards state) (select-keys args [:kind :title :items :rows :columns :text]))))

(defmethod decide* :card/op [{:keys [state] :as tx} {{id :card :as args} :args}]
  (card-keeper! tx)
  ;; An op that changes nothing writes nothing; relay.cards says why.
  (let [{:keys [op]} (cards/decide (:cards state) id (select-keys args cards/op-fields))]
    (if op
      (emit tx "card/changed" {:card id :op op})
      tx)))

;; what the operator was shown
;;
;; A tool result may carry a display: a title, a body and sources that the
;; operator is shown and the model never reads (agentloop keeps it apart).
;; relay keeps each one as a doc at shown/<date>/<call>.md, written as the
;; role whose session made it, with the display's labels, after a wake or
;; as a conversation goes. The model is told the ref, [[shown:<date>/<call>]],
;; and nothing else.

(def ^:private shown-ref-pattern #"shown:(\d{4}-\d{2}-\d{2})/([a-z0-9][a-z0-9_-]{0,99})")

(defn shown-path?
  "Whether a doc path is under shown/."
  [path]
  (str/starts-with? (str path) "shown/"))

(defn shown-ref-path
  "The doc path for a display's ref, shown:<yyyy-mm-dd>/<id>, or nil when
  the ref is not one."
  [ref]
  (when-let [[_ date id] (and (string? ref) (re-matches shown-ref-pattern ref))]
    (str "shown/" date "/" id ".md")))

(defn shown-ref-prefix
  "The display_ref_prefix for a wake that starts at `now`: shown: and the
  date in the operator's zone, so a wake's displays sit under its day."
  [^Instant now ^java.time.ZoneId zone]
  (str "shown:" (java.time.LocalDate/ofInstant now zone) "/"))

(defn- ref-id
  "A ref's id written so it can sit between [[kind: and ]]: [, ] and a
  line break become %5B, %5D and %0A, and a % that would read as one of
  those escapes becomes %25, as relay.refs writes them."
  [s]
  (-> (str s)
      (str/replace #"%(?=25|5B|5D|0A)" "%25")
      (str/replace "[" "%5B")
      (str/replace "]" "%5D")
      (str/replace "\n" "%0A")))

(defn- source-ref
  "A display source's ref, kind:id, as [[kind:id]] with its id escaped."
  [r]
  (let [[_ kind id] (re-matches #"(?s)([a-z]+):(.*)" (str r))]
    (if kind (str "[[" kind ":" (ref-id id) "]]") (str "[[" (ref-id r) "]]"))))

(defn shown-doc-text
  "The text of a shown doc: the title as a heading, the body, and the
  sources as a list of refs. This is what trusting it vouches for."
  [{:keys [title body sources]}]
  (str "# " (str/replace (str/trim (str title)) #"\s+" " ") "\n\n"
       (str/trim (str body))
       (when (seq sources)
         (str "\n\n## Sources\n\n"
              (str/join "\n" (for [{t :title r :ref} sources]
                               (str "- " (str/replace (str/trim (str t)) #"\s+" " ") " " (source-ref r))))))
       "\n"))

(defn- display-source? [x]
  (and (map? x) (string? (:title x)) (not (blank? (:title x)))
       (string? (:ref x)) (not (blank? (:ref x))) (not (re-find #"[\n\r]" (:ref x)))))

(defmethod decide* :display/show [{:keys [state] :as tx} {{:keys [ref title body sources call]} :args}]
  ;; Written as the role whose session made it, carrying the display's
  ;; labels, like anything the role writes.
  (caller-role! tx)
  (let [path (or (shown-ref-path ref) (reject! :invalid (str "A display's ref is shown:<date>/<id>, not " (pr-str ref) ".")))]
    (when (or (blank? title) (not (string? body)))
      (reject! :invalid "A display has a title and a body."))
    (when-not (and (sequential? sources) (every? display-source? sources))
      (reject! :invalid "A display's sources are a list of titles and refs."))
    (let [content (shown-doc-text {:title title :body body :sources sources})
          latest (doc-version state path {})]
      (cond
        ;; The same display stored again changes nothing.
        (and latest (not (:removed latest)) (= content (:content latest))) tx
        ;; Another display at its path is never overwritten.
        latest (reject! :conflict (str path " holds another display."))
        (> (count content) 500000) (reject! :invalid "A display is at most 500000 characters.")
        :else (emit tx "doc/put" {:path path :content content
                                  :shown {:call (str call) :title (str/trim title)
                                          :sources (mapv #(select-keys % [:title :ref]) sources)}})))))
