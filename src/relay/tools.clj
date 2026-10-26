(ns relay.tools
  "The tools a role works with. A role has no file or shell access of its
  own: everything it does is a call to one of these, by name, and every call
  that changes anything goes through the inbound path as that role.

  Each tool has a class (see relay.config/tool-classes). relay's own tools
  are defined here; the ones that remove something also say so as their
  :acp-kind, which an ACP client shows. No call is held for the operator:
  what a role can reach is decided by the tools it is given. The operator can grant further
  tools in relay.edn, each one an external command given the call's
  arguments on its command line or an envelope on stdin, or a tool of an
  MCP server, which agentloop runs.
  Which tools a role is offered depends on its grants, and a call to a tool
  the role was not offered is refused, whatever the harness let through.

  A read tool is a pure :read function of the state, the config, the time,
  the caller and the call's arguments. It returns its text with the labels
  of everything that text shows (relay.core's labels section): a session
  that reads it gains them. Inside the house a read shows everything; a
  public role's read shows only public items. Every other tool has a :run function that
  makes the change as the caller, carrying the calling session's labels.
  Either may return plain text, which carries no labels, or {:content
  :labels}."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [relay.cards :as cards]
            [relay.clock :as clock]
            [relay.config :as config]
            [relay.core :as core]
            [relay.harness :as harness]
            [relay.inbound :as inbound]
            [relay.files :as files]
            [relay.json :as strict]
            [relay.messages :as messages]
            [relay.moves :as moves]
            [relay.refs :as refs]
            [relay.shell :as shell])
  (:import (java.time Instant LocalTime)
           (java.time.format DateTimeFormatter)))

(defn- obj [props & required]
  {:type "object" :properties props :required (vec required) :additionalProperties false})

(def ^:private text {:type "string"})
(def ^:private memo-id {:type "integer" :description "The memo's number, as shown in the inbox."})

(defn- labelled [content & label-colls]
  {:content content :labels (apply core/union label-colls)})

(defn- fmt-memo [{:keys [id from subject body sent-at attempts routine]}]
  (str "Memo " id ": " subject " " (refs/ref :memo id) "\n"
       (if routine
         (str "  Your routine `" routine "` coming due, " sent-at ".")
         (str "  From " from ", sent " sent-at "."))
       (when (pos? attempts) (str " Left open by " attempts " earlier wake(s)."))
       "\n\n" (str/replace body #"(?m)^" "  ") "\n"))

(declare ^:dynamic *wake*)

(defn- submit [sys actor labels type args]
  (inbound/submit! sys (cond-> {:type type :actor actor :args args :labels labels}
                         *wake* (assoc :session-wake *wake*))))

(defn- created-memo [events]
  (:seq (first (filter #(= "memo/sent" (:type %)) events))))

(def ^:private flag {:type "boolean"})
(def ^:private fact-id {:type "integer" :description "The fact's number."})
(def ^:private world-time
  {:type "string" :description "A date (2026-10-14), a local date and time (2026-10-14 09:00), or an ISO instant."})

(defn- local-time
  "A time in the operator's zone, as a bare date when it is midnight."
  [config ^Instant t]
  (when t
    (let [z (.atZone t (config/zone config))]
      (if (= LocalTime/MIDNIGHT (.toLocalTime z))
        (str (.toLocalDate z))
        (.format z (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))))))

(defn- validity [config {:keys [valid-from valid-to]}]
  (let [t #(local-time config %)]
    (cond (and valid-from valid-to) (str "from " (t valid-from) " until " (t valid-to))
          valid-from (str "from " (t valid-from))
          valid-to (str "until " (t valid-to))
          :else "with no known start or end")))

(defn- fmt-fact [config {:keys [id topic text supersedes superseded-by] :as f}]
  (str "Fact " id (when topic (str " [" topic "]")) ", holds " (validity config f)
       (when supersedes (str ", replacing fact " supersedes))
       (when superseded-by (str ", superseded by fact " superseded-by))
       " " (refs/ref :fact id)
       "\n  " text))

(defn- fmt-version [config {:keys [change recorded-at actor why superseded-by] :as v}]
  (str (local-time config recorded-at) " by " actor ": "
       (case change
         :added "recorded"
         :superseded (str "superseded by fact " superseded-by)
         :corrected (str "corrected, because " why)
         :retracted (str "retracted, because " why))
       (when-not (#{:superseded :retracted} change)
         (str "\n  holds " (validity config v) (when (:topic v) (str " [" (:topic v) "]"))
              "\n  " (:text v)))))

(defn- times
  "A query's world and recording times from a call's arguments."
  [{:keys [config now]} {:keys [as_of known_at]}]
  {:as-of (or (core/world-time config as_of "as_of") now)
   :known-at (core/world-time config known_at "known_at")})

(defn- own
  "Whose data a read is about: the caller's, or for the operator, the role named."
  [actor {:keys [owner]}]
  (if (= core/operator actor) owner actor))

(defn- fact-args [args]
  (-> args
      (select-keys [:fact :topic :text :why :retract :valid_from :valid_to])
      (set/rename-keys {:valid_from :valid-from :valid_to :valid-to})))

(defn- new-fact [events]
  (:seq (first (filter #(#{"fact/added" "fact/superseded"} (:type %)) events))))

(defn- event-when
  "When an event is, in the operator's zone: the date and times, with the
  end's date only when it is another day."
  [config {:keys [start end]}]
  (let [z #(.atZone ^Instant % (config/zone config))
        fmt #(.format (z %1) (DateTimeFormatter/ofPattern %2))]
    (str (fmt start "yyyy-MM-dd HH:mm") " to "
         (if (= (.toLocalDate (z start)) (.toLocalDate (z end)))
           (fmt end "HH:mm")
           (fmt end "yyyy-MM-dd HH:mm")))))

(defn- fmt-event [config {:keys [id title location description removed] :as e}]
  (str "Event " id (when removed ", removed") ", " (event-when config e) "\n  " title
       (when location (str "\n  at " location))
       (when description (str "\n  " (str/replace description #"\n" "\n  ")))))

(defn- event-args [args]
  (select-keys args [:event :title :start :end :location :description :why]))

(defn- new-event [events]
  (:seq (first (filter #(= "calendar/added" (:type %)) events))))

(defn- doc-ref
  "The ref to show beside a doc version a read returns, or nil. The path
  is text whoever first wrote the doc chose, so it is shown only when
  `labels`, the labels the read puts on the item the ref goes in, already
  cover its labels."
  [state path labels version]
  (when (every? (set labels) (core/path-labels state path))
    (refs/doc-ref path version)))

(defn- show
  "Joins what a read shows, each piece {:text :labels}: the texts, and the
  union of their labels."
  [items sep]
  {:content (str/join sep (map :text items))
   :labels (apply core/union (map :labels items))})

(defn- item [text labels] {:text text :labels labels})

(defn- public-reader?
  "Whether a read is a public role's. It is shown public items only, and
  nothing else of what relay keeps: not shown and not counted, since even
  a count says that something is there. A role is read for as public when
  relay.edn says it is, or when the wake that made the call (*wake*) began
  public, so a change to relay.edn while a session runs never widens what
  it sees (I3)."
  [state config actor]
  (core/public-caller? state config actor *wake*))

(defn- no-calendar!
  "A public role has no calendar: for it there is no such tool."
  [state config actor]
  (when (public-reader? state config actor)
    (throw (ex-info "You have no tool called calendar." {}))))

(def builtin
  "relay's own tools, by name."
  {"inbox"
   {:class :read
    :description "List the memos addressed to you that are still open."
    :parameters (obj {})
    :read (fn [{:keys [state config]} actor _]
            (let [memos (cond->> (core/unread-memos state actor)
                          (public-reader? state config actor)
                          (filter #(core/shown-to-public? % (core/memo-labels state %))))]
              (if (empty? memos)
                "Nothing is waiting for you."
                (show (for [m memos] (item (fmt-memo m) (core/memo-labels state m))) "\n"))))}

   "roles"
   {:class :read
    :description "List the roles in relay: who exists, what each is for, and who it reports to. Send a memo to `operator` to reach the operator."
    :parameters (obj {})
    :read (fn [{:keys [state config]} actor _]
            (let [public (public-reader? state config actor)]
              (show (for [id (core/active-role-ids state)
                          ;; A public role sees the roles whose text the
                          ;; operator wrote, and whom they report to only
                          ;; when the operator chose that id too.
                          :when (or (not public) (core/role-text-public? state id))
                          :let [{:keys [title reports-to title-labels]} (core/role state id)
                                reports-to (when (or (not public) (core/role-id-public? state reports-to)) reports-to)]]
                      (item (str id ": " title (when reports-to (str ", reports to " reports-to)))
                            (core/union (core/id-labels state id) title-labels (core/id-labels state reports-to))))
                    "\n")))}

   "clock"
   {:class :read
    :description (str "The time and date arithmetic, which you should not do in your head. op `now` gives"
                      " the current date and time. `add` adds a span (3w, 2d, 90m, 1mo, 1y, negative allowed)"
                      " to a date, or to now. `between` gives the days, hours and minutes from one date to"
                      " another. `next` gives the next mon..sun on or after a date, or the nth one. A date is"
                      " 2026-10-14, 2026-10-14 09:00 or an ISO date-time with an offset; without an offset it"
                      " is in the operator's time zone. now and today also work as dates. Every answer names"
                      " the weekday.")
    :parameters (obj {:op {:type "string" :enum ["now" "add" "between" "next"]}
                      :date {:type "string" :description "add, next: the date to start from. Defaults to now for add and today for next."}
                      :span {:type "string" :description "add: what to add, such as 3w, 2d, 90m, 1mo, 1y or -1d."}
                      :from {:type "string" :description "between: the first date."}
                      :to {:type "string" :description "between: the second date."}
                      :weekday {:type "string" :description "next: mon, tue, wed, thu, fri, sat or sun."}
                      :nth {:type "integer" :description "next: 2 for the second such day on or after the date. Defaults to 1."}}
                     "op")
    ;; It shows nothing relay keeps, so it carries no labels: the plain
    ;; text a read returns is unlabelled.
    :read (fn [{:keys [config now]} _ args]
            (clock/answer now (config/zone config) args))}

   "memo_send"
   {:class :write
    :description "Send a memo to a role, or to `operator`. The subject is one line; put the ask in it. The body must stand on its own for a reader with no memory of your work."
    :parameters (obj {:to text :subject text :body text} "to" "subject" "body")
    :run (fn [sys actor labels args]
           (str "Sent memo " (created-memo (submit sys actor labels :memo/send args)) " to " (:to args) "."))}

   "memo_close"
   {:class :write
    :description "Close a memo you have dealt with, saying in a sentence or two what you did. Close only what you have done."
    :parameters (obj {:memo memo-id :outcome text} "memo" "outcome")
    :run (fn [sys actor labels args]
           (submit sys actor labels :memo/close args)
           (str "Closed memo " (:memo args) "."))}

   "memo_block"
   {:class :write
    :description "Say you cannot do what a memo asks, why, and what would unblock you. It goes to whoever handles dead letters. Trying again would not help."
    :parameters (obj {:memo memo-id :explanation text} "memo" "explanation")
    :run (fn [sys actor labels args]
           (submit sys actor labels :memo/block args)
           (str "Blocked memo " (:memo args) "; it has been passed on."))}

   "routines"
   {:class :read
    :description "List your routines and their schedules."
    :parameters (obj {})
    :read (fn [{:keys [state config]} actor _]
            (let [public (public-reader? state config actor)
                  rs (cond->> (core/active-routines state actor)
                       public (filter #(core/shown-to-public? % (core/item-labels %))))]
              (if (empty? rs)
                "You have no routines."
                ;; A routine's instructions become a memo's body when it
                ;; fires, so they are content like a memo's.
                (show (for [{:keys [name schedule instructions] :as r} rs]
                        (item (str name " (" schedule ")\n  " instructions) (core/item-labels r)))
                      "\n\n"))))}

   "routine_write"
   {:class :write
    :description "Create or replace one of your routines: a memo you send yourself on a schedule. Schedules: `every 30m` (or 2h, 1d), `daily 07:30`, `weekly mon 09:00`, `once 2026-10-08 09:00`. Writing a routine again restarts its clock. Every wake costs money, so keep routines few."
    :parameters (obj {:name {:type "string" :description "Lower case letters, digits and hyphens."}
                      :schedule text :instructions text}
                     "name" "schedule" "instructions")
    :run (fn [sys actor labels args]
           (submit sys actor labels :routine/write args)
           (str "Routine " (:name args) " is set."))}

   "routine_remove"
   {:class :write :acp-kind "delete"
    :description "Remove one of your routines."
    :parameters (obj {:name text} "name")
    :run (fn [sys actor labels args]
           (submit sys actor labels :routine/remove args)
           (str "Removed routine " (:name args) "."))}

   "facts"
   {:class :read
    :description (str "Look up facts: what relay knows about the world. By default, the facts that"
                      " hold now. Narrow by `topic` (a topic includes those under it) or `search`"
                      " (words in the text, best matches first). `as_of` asks what held at another time; `known_at`"
                      " asks what relay had recorded by then; `all` lists every fact, past and"
                      " future too.")
    :parameters (obj {:topic text :search text :as_of world-time :known_at world-time :all flag})
    :read (fn [{:keys [state config] :as ctx} actor args]
            (let [found (core/facts-shown state (merge (times ctx args) (select-keys args [:all :topic :search]))
                                          {:public (public-reader? state config actor)})]
              (if (empty? found)
                "No facts match."
                (show (for [f found] (item (fmt-fact config f) (core/item-labels f))) "\n"))))}

   "fact_history"
   {:class :read
    :description "Every recorded version of a fact: when it was recorded, changed or corrected, by whom and why."
    :parameters (obj {:fact fact-id} "fact")
    :read (fn [{:keys [state config]} actor {:keys [fact]}]
            (let [public (public-reader? state config actor)
                  f (core/fact state fact)]
              (if (or (nil? f) (and public (not (core/fact-shown-to-public? state (core/fact-as-known f nil)))))
                (core/reject! :not-found (str "There is no fact " fact "."))
                (update (show (for [v (:versions f)
                                    :when (or (not public) (core/fact-shown-to-public? state v))]
                                (item (str "- " (fmt-version config v))
                                      (core/item-labels v (core/version-labels state v))))
                              "\n")
                        :content #(str "Fact " fact " " (refs/ref :fact fact) (when (:supersedes f) (str ", which replaced fact " (:supersedes f)))
                                       "\n" %)))))}

   "fact_add"
   {:class :write
    :description (str "Record a fact: one thing that is true in the world, in a sentence or two that"
                      " stand on their own. Give `valid_from` when you know when it started to hold,"
                      " and `valid_to` when you know it will stop. A topic groups facts, such as"
                      " household/car.")
    :parameters (obj {:text text :topic text :valid_from world-time :valid_to world-time} "text")
    :run (fn [sys actor labels args]
           (str "Recorded fact " (new-fact (submit sys actor labels :fact/add (fact-args args))) "."))}

   "fact_supersede"
   {:class :write
    :description (str "The world changed: fact `fact` stopped holding at `valid_from` (default now)"
                      " and `text` holds instead. `text` is the new state, written as a fact that"
                      " holds from then on (\"The operator drives a 2022 Corolla.\"), not an account"
                      " of the change, and it replaces the old fact, so do not also add it with"
                      " fact_add. The old fact is kept, ending there. Use this for a change, and"
                      " fact_correct for a mistake.")
    :parameters (obj {:fact fact-id :text text :valid_from world-time :valid_to world-time :topic text}
                     "fact" "text")
    :run (fn [sys actor labels args]
           (str "Fact " (:fact args) " is superseded by fact "
                (new-fact (submit sys actor labels :fact/supersede (fact-args args))) "."))}

   "fact_correct"
   {:class :write
    :description (str "The record was wrong: fix a fact's text, topic or times, saying why. Give only"
                      " what changes; `open` for a time removes that bound. With `retract`, the fact"
                      " was never true, and a change it recorded is undone. The earlier record is kept"
                      " and still answers for what relay knew before.")
    :parameters (obj {:fact fact-id :why text :text text :topic text :valid_from world-time
                      :valid_to world-time :retract flag}
                     "fact" "why")
    :run (fn [sys actor labels args]
           (submit sys actor labels :fact/correct (fact-args args))
           (str (if (:retract args) "Retracted" "Corrected") " fact " (:fact args) "."))}

   "calendar"
   {:class :read
    :grant :private
    :description (str "Read your calendar: the events you have put on it, which the operator subscribes to."
                      " With no arguments, the events that have not ended yet. `as_of` asks from another"
                      " time; `all` lists every event, past and removed ones too. Each event shows its"
                      " number, which calendar_update and calendar_remove take.")
    :parameters (obj {:as_of world-time :all flag})
    :read (fn [{:keys [state config now]} actor {:keys [as_of all]}]
            (no-calendar! state config actor)
            (let [found (core/calendar-events state {:all all :as-of (or (core/world-time config as_of "as_of") now)})]
              (if (empty? found)
                "Your calendar has no events."
                (show (for [e found] (item (fmt-event config e) (core/item-labels e))) "\n"))))}

   "calendar_add"
   {:class :edit
    :grant :private
    :description (str "Put an event on your own calendar, which the operator subscribes to. It is never"
                      " the operator's calendar: nothing here invites anyone or changes their appointments."
                      " `start` and `end` are local times (2026-10-14 09:00) or ISO instants; with no"
                      " `end` the event lasts an hour. Anything you change later is kept as a version.")
    :parameters (obj {:title text :start world-time :end world-time :location text :description text}
                     "title" "start")
    :run (fn [sys actor labels args]
           (str "Added event " (new-event (submit sys actor labels :calendar/add (event-args args))) "."))}

   "calendar_update"
   {:class :edit
    :grant :private
    :description (str "Change an event on your own calendar. Give only what changes; an empty `location`"
                      " or `description` clears it. Say `why` when it is not obvious. The calendar app"
                      " sees the new version, and the earlier ones are kept.")
    :parameters (obj {:event {:type "integer" :description "The event's number."}
                      :title text :start world-time :end world-time :location text :description text :why text}
                     "event")
    :run (fn [sys actor labels args]
           (submit sys actor labels :calendar/update (event-args args))
           (str "Changed event " (:event args) "."))}

   "calendar_remove"
   {:class :edit
    :grant :private
    :acp-kind "delete"
    :description (str "Take an event off your own calendar. The calendar app is told it is cancelled, and"
                      " the record is kept.")
    :parameters (obj {:event {:type "integer" :description "The event's number."} :why text} "event")
    :run (fn [sys actor labels args]
           (submit sys actor labels :calendar/remove (event-args args))
           (str "Removed event " (:event args) "."))}

   "note_write"
   {:class :write
    :description (str "Write one of your notes: your memory between wakes. Your notes are shown to you"
                      " at the start of every wake, so keep them short and current. Writing a note"
                      " replaces it.")
    :parameters (obj {:name {:type "string" :description "Lower case letters, digits and hyphens."}
                      :text text}
                     "name" "text")
    :run (fn [sys actor labels args]
           (submit sys actor labels :note/write (select-keys args [:owner :name :text]))
           (str "Note " (:name args) " is written."))}

   "note_remove"
   {:class :write :acp-kind "delete"
    :description "Remove one of your notes once it no longer helps."
    :parameters (obj {:name text} "name")
    :run (fn [sys actor labels args]
           (submit sys actor labels :note/remove (select-keys args [:owner :name]))
           (str "Removed note " (:name args) "."))}

   "journal"
   {:class :read
    :description "Read your latest journal entries, oldest first."
    :parameters (obj {:limit {:type "integer" :description "How many, 10 by default."}})
    :read (fn [{:keys [state config]} actor args]
            (let [public (public-reader? state config actor)
                  entries (core/journal-shown state (own actor args) (or (:limit args) 10) public)]
              (if (empty? entries)
                "The journal is empty."
                (show (for [{:keys [id at text] :as e} entries]
                        (item (str (local-time config at) " " (refs/ref :journal id) "\n" text) (core/item-labels e)))
                      "\n\n"))))}

   "journal_write"
   {:class :write
    :description "Add an entry to your journal: what you did and learned, for later. Entries are never changed."
    :parameters (obj {:text text} "text")
    :run (fn [sys actor labels args]
           (submit sys actor labels :journal/write (select-keys args [:owner :text]))
           "Written.")}

   "notes"
   {:class :read
    :description "Read your notes. They are in your instructions at the start of every wake."
    :parameters (obj {})
    :read (fn [{:keys [state config]} actor args]
            (let [public (public-reader? state config actor)
                  ns (cond->> (core/notes state (own actor args))
                       public (filter #(core/shown-to-public? % (core/item-labels %))))]
              (if (empty? ns)
                "You have no notes."
                (show (for [{:keys [name text] :as n} ns]
                        (item (str "## " name
                                   ;; The owner's id is text someone chose, so it is
                                   ;; shown only when the note's labels cover it.
                                   (when (every? (set (core/item-labels n)) (core/id-labels state (own actor args)))
                                     (str " " (refs/ref :note (str (own actor args) "/" name))))
                                   "\n\n" text)
                              (core/item-labels n)))
                      "\n\n"))))}

   "docs"
   {:class :read
    :description "List the docs: longer shared content that is really a file, by path."
    :parameters (obj {})
    :read (fn [{:keys [state config]} actor _]
            (let [public (public-reader? state config actor)
                  ds (cond->> (core/docs state nil)
                       public (filterv #(core/doc-shown-to-public? state (:path %) %)))]
              (if (empty? ds)
                "There are no docs."
                (show (for [{:keys [path at actor content] :as v} ds]
                        (item (str path " (" (count content) " characters, last written "
                                   (local-time config at) " by " actor ") " (refs/doc-ref path nil))
                              (core/union (core/version-labels state v) (core/path-labels state path))))
                      "\n"))))}

   "doc_get"
   {:class :read
    :description "Read a doc, the latest version unless you give one from its history."
    :parameters (obj {:path text :version {:type "integer"}} "path")
    :read (fn [{:keys [state config]} actor {:keys [path version]}]
            (let [public (public-reader? state config actor)
                  v (core/doc-version state path {:version version})
                  ;; For a public role a doc it cannot see does not exist,
                  ;; nor does a version it cannot see.
                  v (if (and public (not (and (core/doc-shown-to-public? state path v)
                                              (core/doc-shown-to-public? state path (core/doc-version state path {})))))
                      nil
                      v)]
              (cond (nil? v) (core/reject! :not-found (str "There is no doc at " path
                                                             (when version (str " with version " version)) "."))
                    (:removed v) (core/reject! :not-found (str path " was removed in version " (:seq v) "."))
                    :else (show [(item (str (:content v)
                                            (some->> (doc-ref state path (core/item-labels v) (:seq v)) (str "\n\n")))
                                       (core/item-labels v))]
                                ""))))}

   "doc_history"
   {:class :read
    :description "The versions of a doc: when each was written and by whom."
    :parameters (obj {:path text} "path")
    :read (fn [{:keys [state config]} actor {:keys [path]}]
            (let [public (public-reader? state config actor)
                  vs (get-in state [:docs path :versions])]
              (if (or (empty? vs) (and public (not (core/doc-shown-to-public? state path (peek vs)))))
                (core/reject! :not-found (str "There is no doc at " path "."))
                (show (for [{:keys [seq at actor content removed] :as v} vs
                            :when (or (not public) (core/doc-shown-to-public? state path v))]
                        (item (str "version " seq ", " (local-time config at) " by " actor
                                   (if removed ": removed" (str ", " (count content) " characters"))
                                   (some->> (doc-ref state path (core/version-labels state v) seq) (str " ")))
                              (core/version-labels state v)))
                      "\n"))))}

   "doc_put"
   {:class :write
    :description "Write a doc, replacing its content. Every version is kept. Paths are like household/insurance.md."
    :parameters (obj {:path text :content text} "path" "content")
    :run (fn [sys actor labels args]
           (if-let [v (:seq (first (submit sys actor labels :doc/put (select-keys args [:path :content]))))]
             (str "Wrote " (:path args) ", version " v ".")
             ;; Saying the doc already holds this tells the caller what it holds.
             (labelled (str (:path args) " already says that.")
                       (let [state (inbound/current-state sys)
                             path (str/trim (:path args))]
                         (core/item-labels (core/doc-version state path {})
                                           (core/path-labels state path))))))}

   "doc_remove"
   {:class :write :acp-kind "delete"
    :description "Remove a doc. Its history is kept."
    :parameters (obj {:path text} "path")
    :run (fn [sys actor labels args]
           (submit sys actor labels :doc/remove (select-keys args [:path]))
           (str "Removed " (:path args) "."))}

   "tell_operator"
   {:class :edit
    :grant :private
    :description (str "Tell the operator something: that a draft is ready and where it is, a question only"
                      " they can answer, or something they need to know now. relay decides how it reaches"
                      " them. The subject is one line and carries the point; the body stands on its own."
                      " Put refs after what you drew on. `items` are listed after the body, one each, such"
                      " as the placeholders a draft still needs. `urgency` is low (can wait for the day's"
                      " digest), normal (the default) or high (they should see it now). To answer a message"
                      " of yours they replied to, give its number as `in_reply_to`.")
    :parameters (obj {:subject text
                      :body text
                      :items {:type "array" :items text}
                      :urgency {:type "string" :enum messages/urgencies}
                      :in_reply_to {:type "integer" :description "The number of the message this answers."}}
                     "subject" "body")
    :run (fn [sys actor labels {:keys [subject body items urgency in_reply_to]}]
           (let [with-refs (fn [t] {:text t :refs (mapv :ref (refs/parse t))})
                 sections (mapv (fn [sec] (update sec :items #(mapv with-refs %)))
                                (messages/body-sections body items))
                 events (submit sys actor labels :message/send
                                (cond-> {:subject subject :sections sections}
                                  urgency (assoc :urgency urgency)
                                  (some? in_reply_to) (assoc :in-reply-to in_reply_to)))]
             (str "Told the operator (message " (:seq (first (filter #(= "message/created" (:type %)) events))) ").")))}

   "cards"
   {:class :read
    :grant :private
    :description (str "Read the operator's board: cards for work the operator comes back to over days,"
                      " or a status worth a glance. With no arguments, the active cards. `card` shows one"
                      " card, archived or not; `archived` lists the archived cards too. Each card shows the"
                      " numbers of its items, rows and columns, which card_op takes.")
    :parameters (obj {:card {:type "integer" :description "The card's number."}
                      :archived flag})
    :read (fn [{:keys [state config]} actor {:keys [card archived]}]
            ;; A public role has no board: cards are private only.
            (when (public-reader? state config actor)
              (throw (ex-info "You have no tool called cards." {})))
            (let [cs (if card
                       [(or (core/card state card) (core/reject! :not-found (str "There is no card " card ".")))]
                       (core/board state archived))]
              (if (empty? cs)
                "The board is empty."
                (show (for [c cs] (item (cards/text-form c) (core/card-labels c))) "\n\n"))))}

   "card_add"
   {:class :write
    :grant :private
    :description (str "Put a card on the operator's board, for work they come back to over days or a status"
                      " worth a glance: a checklist (`items`), a tracker of rows by columns, such as accounts"
                      " by month (`rows`, `columns`), or a note (`text`). Keep it current with card_op and"
                      " archive it when it is finished.")
    :parameters (obj {:kind {:type "string" :enum cards/kinds}
                      :title {:type "string" :description "One line."}
                      :items {:type "array" :items text :description "A checklist's items, one line each."}
                      :rows {:type "array" :items text :description "A tracker's rows, one line each."}
                      :columns {:type "array" :items text :description "A tracker's columns, one line each."}
                      :text {:type "string" :description "A note's text."}}
                     "kind" "title")
    :run (fn [sys actor labels args]
           (let [e (first (submit sys actor labels :card/add args))]
             ;; The card as its event added it holds only the caller's own
             ;; text, so it carries the session's labels, as a refusal would.
             (labelled (str "Added card " (:seq e) ".\n\n" (cards/text-form (get (cards/add {} e) (:seq e))))
                       labels)))}

   "card_op"
   {:class :write
    :grant :private
    :description (str "Change a card on the operator's board. op is one of retitle, archive, restore (any"
                      " card); add, tick, untick, edit, remove (a checklist, by `item`); set (a tracker cell,"
                      " by `row` and `column`, to open, done or skip), add_row, add_column, rename_row,"
                      " rename_column, remove_row, remove_column (a tracker); write (a note). `text` is the"
                      " new text. Give `seen`, the card's rev when you last read it (the header shows"
                      " it as \"rev N\"), with an edit so that you do not overwrite a newer one.")
    :parameters (obj {:card {:type "integer" :description "The card's number."}
                      :op {:type "string" :enum (vec (sort (keys cards/ops)))}
                      :item {:type "integer"} :row {:type "integer"} :column {:type "integer"}
                      :state {:type "string" :enum cards/cell-states}
                      :text text
                      :seen {:type "integer" :description "The rev in the card's header when you last read it."}}
                     "card" "op")
    :run (fn [sys actor labels args]
           (let [events (submit sys actor labels :card/op args)]
             (if-let [e (first events)]
               (cards/confirmation (:card args) (get-in e [:data :op]))
               ;; Saying it changes nothing says something of what the
               ;; card holds, which is private.
               (let [{:keys [unchanged labels]} (try (cards/decide (:cards (inbound/current-state sys)) (:card args)
                                                                   (select-keys args cards/op-fields))
                                                     (catch clojure.lang.ExceptionInfo _ nil))]
                 (if unchanged
                   (labelled (str "That changes nothing: " unchanged) (core/content-labels labels))
                   "That changes nothing.")))))}

   "role_add"
   {:class :write
    :grant :may-add
    :description "Add a role. Its instructions say what it is for, what it may decide alone, and when to ask. What it may use is granted by the operator, not written here."
    :parameters (obj {:id text :title text :instructions text} "id" "title" "instructions")
    :run (fn [sys actor labels args]
           (submit sys actor labels :role/add args)
           (str "Added " (:id args) "."))}

   "role_update"
   {:class :write
    :grant :may-add
    :description "Change another role's title or instructions."
    :parameters (obj {:id text :title text :instructions text} "id")
    :run (fn [sys actor labels args]
           (submit sys actor labels :role/update args)
           (str "Updated " (:id args) "."))}

   "role_remove"
   {:class :write :acp-kind "delete"
    :grant :may-add
    :description "Remove a role. Its open memos are dead-lettered and its routines stop."
    :parameters (obj {:id text} "id")
    :run (fn [sys actor labels args]
           (submit sys actor labels :role/remove args)
           (str "Removed " (:id args) "."))}

   "dead_letters_retry"
   {:class :write
    :grant :dead-letter-handler
    :description "Put a role's dead-lettered memos back in its inbox with a clean slate, once whatever stopped it is fixed."
    :parameters (obj {:role text} "role")
    :run (fn [sys actor labels args]
           (str "Requeued " (count (submit sys actor labels :memo/retry args)) " memo(s)."))}})

(def ^:dynamic *call-id*
  "The id of the call being run, which an envelope tool is given."
  nil)

(def ^:dynamic *wake*
  "The wake whose session made the call being run, as relay named it in the
  harness's tool command. A public role's call writes public items only
  when that wake is running and began public."
  nil)

(defn strict-json-value
  "The one JSON value in `s`, a string or bytes, read by relay's strict
  reader (relay.json), or ::bad when it breaks the reader's rule: more
  than one value, a repeated key, text that is not UTF-8. Those are how
  two readers can be made to read one text differently."
  [s]
  (strict/parse-or s ::bad))

(defn parse-display
  "A display as an envelope tool gives it: exactly {title, body, labels}
  and optionally sources, a list of exactly {title, ref}, with labels
  relay knows and refs of one line. Returns {:title :body :labels
  :sources}, or ::bad. agentloop checks it again, with its bounds."
  [d]
  (let [source? (fn [x] (and (map? x) (= #{"title" "ref"} (set (keys x)))
                             (string? (get x "title")) (not (str/blank? (get x "title")))
                             (string? (get x "ref")) (not (str/blank? (get x "ref")))
                             (not (re-find #"[\n\r]" (get x "ref")))))]
    (if (and (map? d)
             (every? #{"title" "body" "labels" "sources"} (keys d))
             (string? (get d "title")) (not (str/blank? (get d "title")))
             (string? (get d "body"))
             (sequential? (get d "labels")) (every? core/label-names (get d "labels"))
             (or (not (contains? d "sources")) (and (sequential? (get d "sources")) (every? source? (get d "sources")))))
      {:title (get d "title") :body (get d "body") :labels (core/union (get d "labels"))
       :sources (mapv (fn [x] {:title (get x "title") :ref (get x "ref")}) (get d "sources"))}
      ::bad)))

(defn parse-result
  "An envelope tool's stdout: exactly {content, labels} and optionally
  is_error and display (parse-display), keys in exactly that case, labels
  relay knows. Returns {:content :is-error :labels} with :display when it
  gave one, or nil."
  [out]
  (let [v (strict-json-value out)
        display (when (and (map? v) (contains? v "display")) (parse-display (get v "display")))]
    (when (and (map? v)
               (every? #{"content" "is_error" "labels" "display"} (keys v))
               (string? (get v "content"))
               (or (not (contains? v "is_error")) (boolean? (get v "is_error")))
               (sequential? (get v "labels"))
               (every? core/label-names (get v "labels"))
               (not= ::bad display))
      (cond-> {:content (get v "content") :is-error (true? (get v "is_error")) :labels (core/union (get v "labels"))}
        display (assoc :display display)))))

(def default-timeout-seconds
  "How long an external tool may run when relay.edn gives it no
  :timeout-seconds. agentloop's own tool timeout is the outer bound on a
  role's call; this one makes sure relay ends the tool's processes and
  tells the model only what it may know."
  600)

(defn timeout-seconds
  "How long a tool spec may run: its :timeout-seconds, or `default`."
  ([spec] (timeout-seconds spec default-timeout-seconds))
  ([{:keys [timeout-seconds]} default] (or timeout-seconds default)))

;; --- running a tool's command for a limited time

(def ^:private in-own-group
  ;; perl starts the command in a process group of its own, so a child
  ;; it leaves behind can be found and ended once the command has exited.
  ;; The block form of exec never goes through a shell. A LANG naming a
  ;; locale that is not installed makes perl warn on stderr as it starts;
  ;; the wrapper's own PERL_BADLANG=0 (group-env) silences that, and the
  ;; script drops it again so the command never sees it. The command keeps
  ;; the LANG it was given.
  ["/usr/bin/perl" "-e" "delete $ENV{PERL_BADLANG}; setpgrp(0, 0) or die \"setpgrp: $!\\n\"; exec { $ARGV[0] } @ARGV or die \"$!\\n\"" "--"])

(defn group-env
  "The environment for in-own-group: `env` plus the wrapper's own switch
  against locale warnings, which the wrapper removes before it runs the command."
  [env]
  (assoc env "PERL_BADLANG" "0"))

(def ^:private kill-grace-ms 2000)

(defn- signal-group!
  "Sends `sig` (\"0\" only asks whether any process is left) to the process
  group `pgid`. True when the group exists."
  [pgid sig]
  (zero? (:exit @(p/process ["/bin/kill" (str "-" sig) "--" (str "-" pgid)] {:out :string :err :string}))))

(defn- stop-group!
  "Ends a command started in its own group, and whatever it started:
  SIGTERM, then SIGKILL to anything still there after kill-grace-ms."
  [proc]
  (let [pgid (.pid ^Process (:proc proc))]
    (p/destroy-tree proc)
    (signal-group! pgid "TERM")
    (loop [waited 0]
      (when (signal-group! pgid "0")
        (if (< waited kill-grace-ms)
          (do (Thread/sleep 50) (recur (+ waited 50)))
          (signal-group! pgid "KILL"))))))

(defn- run-limited
  "Runs `command` with `in` on stdin and a clean environment (nothing the
  harness or relay was started with, such as credentials). Returns {:exit
  :out :err} once it has exited and closed its output, or ::timeout when
  that takes longer than `ms`. With :bytes? :out is the bytes the command
  printed, for relay's strict JSON reader to decode (relay.json/text);
  else it is text. A child left holding its output keeps it
  open past its exit, so the output counts against the same limit. On a
  timeout every process in its group is ended. Throws when it cannot
  start."
  [command in ms & [{:keys [bytes?]}]]
  (let [program (first command)
        ;; A bare name is found on the clean PATH; a path with a slash is
        ;; taken as written, relative to relay's directory, as Java runs it.
        prog (or (if (str/includes? program "/")
                   (let [f (fs/absolutize program)] (when (fs/executable? f) f))
                   (fs/which program {:paths (str/split (get (harness/base-env {}) "PATH") #":")}))
                 (throw (ex-info (str "Cannot run program " program) {})))
        proc (p/process (into in-own-group (cons (str prog) (rest command)))
                        {:in in :out :stream :err :stream :env (group-env (harness/base-env {}))})
        out (future (if bytes?
                      (let [b (java.io.ByteArrayOutputStream.)] (io/copy (:out proc) b) (.toByteArray b))
                      (slurp (:out proc))))
        err (future (slurp (:err proc)))
        deadline (+ (System/currentTimeMillis) ms)
        within #(deref % (max 0 (- deadline (System/currentTimeMillis))) ::timeout)
        exited (within proc)
        o (within out)
        e (within err)]
    (if (some #{::timeout} [exited o e])
      (do (stop-group! proc) ::timeout)
      {:exit (:exit exited) :out o :err e})))

(defn run-external!
  "Runs an external tool on the plain protocol: its configured command with
  `args` after it, a clean environment and nothing on stdin, for at most
  its :timeout-seconds, or `default-seconds` when it has none. Returns
  {:out :labels}, the labels being the tool's own, or {:error why} with
  :timed-out true when it ran out of time, in which case its whole process
  tree is ended. What a failed tool printed goes to stderr, the
  operator's log, and nowhere else."
  ([tool-name spec args] (run-external! tool-name spec args default-timeout-seconds))
  ([tool-name {:keys [command labels] :as spec} args default-seconds]
   (let [seconds (timeout-seconds spec default-seconds)
         failed (fn [why out err & [extra]]
                  (binding [*out* *err*]
                    (println (str "the tool " tool-name " " why))
                    (print (str err) (str out)) (flush))
                  (merge {:error why} extra))]
     (try
       (let [result (run-limited (into (vec command) (map str args)) "" (* 1000 seconds))]
         (cond
           (= ::timeout result) (failed (str "timed out after " seconds " seconds") "" "" {:timed-out true})
           (not (zero? (:exit result))) (failed (str "exited " (:exit result)) (:out result) (:err result))
           :else {:out (:out result) :labels (vec labels)}))
       (catch Exception e (failed (str "could not start: " (ex-message e)) "" ""))))))

(defn- run-envelope-tool
  "Runs an envelope tool: the call on stdin, one result envelope on stdout.
  Anything else (a failure, a timeout, output that is not exactly one
  envelope) tells the model only that it failed, and what it printed goes
  to the operator's log."
  [tool-name command static labels args timeout-ms]
  ;; What a failed tool printed goes to stderr whatever labels it claimed,
  ;; even for a public role, whose harness keeps it in the session's
  ;; operator-only log (agentloop's ToolResult.Log). That log never reaches
  ;; the model or a client, and sits in the operator's data directory with
  ;; everything private relay keeps; hiding it would cost the operator the
  ;; only record of why a tool failed.
  (let [failed (fn [why out err]
                 (binding [*out* *err*] (println (str tool-name ": " why)) (print err out) (flush))
                 (throw (ex-info (str "The tool " tool-name " failed.") {:relay/labels static})))
        result (try (run-limited (vec command) (strict/generate {:input args :labels labels :call_id (str *call-id*)})
                                 timeout-ms {:bytes? true})
                    (catch Exception e (failed (str "could not start: " (ex-message e)) "" "")))]
    (if (= ::timeout result)
      (failed "timed out" "" "")
      (let [{:keys [exit err] raw :out} result
            parsed (when (zero? exit) (parse-result raw))
            out (String. ^bytes raw java.nio.charset.StandardCharsets/UTF_8)]
        (cond
          (not (zero? exit)) (failed (str "exited " exit) out err)
          (nil? parsed) (failed "did not print exactly one result envelope" out err)
          (:is-error parsed) (throw (ex-info (:content parsed) {:relay/labels (core/union static (:labels parsed))}))
          ;; A display carries at least the tool's own labels and the
          ;; result's, as anything from the tool does.
          :else (cond-> (labelled (:content parsed) static (:labels parsed))
                  (:display parsed) (assoc :display (update (:display parsed) :labels core/union
                                                            static (:labels parsed)))))))))

(defn- external-tool
  "A tool the operator defined in relay.edn: an external command, given the
  call's `args` after its own, or with :protocol :envelope, given the call
  as an envelope on stdin and answering with one on stdout. With :protocol
  :mcp-stdio or :mcp-http it is a tool of an MCP server, which agentloop
  runs or reaches: relay only names it to the harness (:mcp) and polices
  it like any other."
  [[tool-name {:keys [class description command labels protocol parameters timeout-seconds] :as spec}]]
  (case protocol
    ;; agentloop runs an MCP tool itself, against its server; relay's tool
    ;; command never does. Its server gives the description and schema.
    (:mcp-stdio :mcp-http)
    [tool-name
     {:class class
      :labels (vec labels)
      :mcp (select-keys spec [:protocol :server :tool :digest :pin :allow-content :fixed-args :hidden-args])}]
    :envelope
    [tool-name
     {:class class
      :labels (vec labels)
      :description description
      :parameters (or parameters {:type "object"})
      :run (fn [_ _ session-labels args]
             (run-envelope-tool tool-name command (vec labels) session-labels args
                                (* 1000 (or timeout-seconds default-timeout-seconds))))}]
  ;; A command given the call's arguments.
  [tool-name
   {:class class
    :labels (vec labels)
    :description description
    :parameters (obj {:args {:type "array" :items {:type "string"}
                             :description "Command line arguments."}})
    ;; Everything it prints, on success or failure, carries its labels.
    :run (fn [_ _ _ {:keys [args]}]
           (let [{:keys [out error timed-out]} (run-external! tool-name spec args)]
             (if error
               ;; What it printed went to the operator's log only; the model
               ;; is told just that it failed, or that it ran out of time.
               (throw (ex-info (str "The tool " tool-name (if timed-out (str " " error) " failed") ".")
                               {:relay/labels (vec labels)}))
               (labelled out labels))))}]))

(defn script-role?
  "Whether a role runs on a :script harness. A script is any program: no
  policy watches its calls and it can claim any labels, so it is offered
  no tools at all, and relay tool refuses its calls."
  [state config role-id]
  (= :script (get-in config [:harnesses (keyword (core/harness-of state config role-id)) :kind])))

(defn shell-tool
  "The `sh` tool, for a role granted :shell: a command run in the steward's
  shell (relay.shell), its output read back by the role itself. It changes
  nothing outside the container, whose own files are all in memory."
  [config]
  {:class :read
   :acp-kind "execute"
   :description (shell/tool-description (:house config))
   :parameters (obj {:command {:type "string" :description "The command, run with sh -c in /scratch."}}
                    "command")
   :run (fn [sys _ session-labels {:keys [command]}]
          (shell/sh! (shell/context (:data-dir sys) (:config sys)) session-labels command))})

(defn files-tool
  "`files_move`, for a role granted :files: moves and renames inside the
  folders it may organize, carried out by relay on the host (relay.moves).
  Class edit: only the operator sees what changed, and undoes it with
  relay undo."
  [roots]
  {:class :edit
   :acp-kind "move"
   :description (files/tool-description roots)
   :parameters (obj {:moves {:type "array"
                             :description "The moves, in order: each {\"from\": full path, \"to\": full path}."
                             :items {:type "object"
                                     :properties {:from text :to text}
                                     :required ["from" "to"]
                                     :additionalProperties false}}}
                    "moves")
   :run (fn [sys actor labels {:keys [moves]}]
          (when-not (every? #(and (map? %) (= #{:from :to} (set (keys %)))) moves)
            (throw (ex-info "Each move is {\"from\": ..., \"to\": ...}, and nothing else." {})))
          (moves/move-files! sys actor labels (vec moves) {:session-wake *wake*}))})

(defn offered
  "The tools a role is offered, by name: relay's own, less those that need a
  grant it lacks, plus `sh` for a role granted :shell and `files_move` for
  one granted :files, plus the external
  tools it is granted. None at all for a role on a :script harness."
  [config role-id & [{:keys [script?]}]]
  (if script?
    {}
  (let [tools (config/role-tools config role-id)
        holds? (fn [grant]
                 (case grant
                   nil true
                   :may-add (config/may-add? config role-id)
                   :dead-letter-handler (or (= role-id (:dead-letter-handler config))
                                            (config/may-add? config role-id))
                   ;; Cards are private only.
                   :private (not (config/public? config role-id))))]
    (merge (into {} (filter #(holds? (:grant (val %))) builtin))
           (when (and (:shell (config/grants config role-id)) (:house config)
                      (not (config/public? config role-id)))
             {"sh" (shell-tool config)})
           (when-let [roots (and (not (config/public? config role-id))
                                 (seq (:files (config/grants config role-id))))]
             {"files_move" (files-tool (vec roots))})
           (into {} (map external-tool (select-keys (:tools config) tools)))))))

(defn grants-summary
  "What a role is granted, in a few sentences for its prompt, so it knows
  what it can and cannot do without trying."
  [config role-id & [{:keys [script?] :as opts}]]
  (let [offered (offered config role-id opts)
        external (sort (keys (select-keys (:tools config) (:tools (config/grants config role-id)))))]
    (if script?
      "You run on a script harness, which has no tools."
    (str/join "\n"
              (cond-> []
                (contains? offered "role_add")
                (conj "You may add, change and remove other roles, with `role_add`, `role_update` and `role_remove`.")
                (= role-id (:dead-letter-handler config))
                (conj "Work that other roles could not finish comes to you as dead letters.")
                (contains? offered "dead_letters_retry")
                (conj "You can put a role's dead letters back in its inbox with `dead_letters_retry`.")
                (core/public-role? config role-id)
                (conj (str "You are a public role: you may search and browse the web, so you are shown only what"
                           " is public, and whatever you write is public. The operator's private things never"
                           " reach you."))
                :always
                (conj (if (seq external)
                        (str "Beyond relay's own tools, the operator has granted you: "
                             (str/join ", " (map #(str "`" % "`") external)) ".")
                        "You have relay's own tools and nothing beyond them."))
                :always
                (conj (str "Only the operator grants tools and powers. If your work needs one you lack,"
                           " ask whoever you report to, or the operator if you report to nobody.")))))))

(defn class-of
  "The class relay gives a tool: the operator's setting for a configured
  tool, else relay's own tool's, as a string. Nil for a tool relay does
  not know."
  [config tool]
  (some-> (or (get-in config [:tools tool :class]) (get-in builtin [tool :class])) name))

(defn manifest
  "What a harness needs to present the tools: names, descriptions and
  parameter schemas (for an MCP tool, its :mcp server and name instead),
  each tool's class and static labels (none for relay's own), and for some
  of relay's own tools an :acp-kind more exact than the class gives
  (relay.acp/tool-kinds)."
  [config role-id & [opts]]
  (let [offered (offered config role-id opts)]
    ;; relay.edn refuses these; this is the second line, at the one place
    ;; every session's tools come from (plan O3, I1).
    (when-not (config/public? config role-id)
      (doseq [[n {:keys [class mcp]}] offered]
        (when (or (= :egress class) (and (= :mcp-http (:protocol mcp)) (not (#{:read :edit} class))))
          (throw (ex-info (str "relay will not start a session for " role-id " with " n
                               ", which could send your things out of the house.")
                          {:relay/usage true})))))
  (vec (for [[n {:keys [class description parameters acp-kind labels mcp]}]
             (sort-by key offered)]
         (cond-> {:name n :class (name class) :description description :parameters parameters
                  :labels (vec labels)}
           acp-kind (assoc :acp-kind acp-kind)
           ;; An MCP tool: its server and the server's name for it. The
           ;; server gives the description and parameters.
           mcp (-> (dissoc :description :parameters) (assoc :mcp mcp)))))))

(defn policy
  "agentloop's policy for a role's tools and a session seeded with
  `labels`: the session's starting labels, and the static labels each
  tool's calls add, for the tools that have any. Labels are provenance
  only; agentloop holds no call. A public role's session may never hold
  private, which agentloop enforces: forbid_labels (I3)."
  ([manifest labels] (policy manifest labels {}))
  ([manifest labels {:keys [public?]}]
   (cond-> {:labels (core/union labels)
            :tools (into (sorted-map)
                         (for [{:keys [name labels]} manifest :when (seq labels)]
                           [name {:labels (vec labels)}]))}
     public? (assoc :forbid_labels [core/private-label]))))

(defn- result
  "A tool's text and labels from what its function returned."
  [r]
  (if (map? r)
    (cond-> {:ok (:content r) :labels (core/union (:labels r))}
      (:display r) (assoc :display (:display r)))
    {:ok r :labels []}))

(defn read-ctx
  "What a read tool reads: the state now, the config and the time."
  [sys]
  {:state (inbound/current-state sys) :config (:config sys) :now ((:clock sys))})

(defn run-tool
  "Runs a tool as `actor`, carrying the caller's `labels` into anything it
  writes. Returns {:ok text :labels} or {:error text :labels}, the labels
  being those of what the text shows. Throws nothing."
  [sys actor tool args labels]
  (try (result (if-let [read (:read tool)]
                 (read (read-ctx sys) actor args)
                 ((:run tool) sys actor labels args)))
       (catch clojure.lang.ExceptionInfo e
         ;; Any refusal may quote the call and show the session its own
         ;; text again, so every refusal carries that session's labels
         ;; (core/reject!). Other errors, such as the clock's fixed
         ;; messages, carry only the labels they were thrown with.
         {:error (ex-message e)
          :labels (core/union (core/rejection-labels e) (:relay/labels (ex-data e))
                              (when (core/rejection e) labels))})
       (catch Exception e
         {:error (or (ex-message e) (str (class e))) :labels []})))

(defn operator-text
  "Runs one of relay's own read tools as the operator, for its text."
  [sys tool-name args]
  (let [{:keys [ok error]} (run-tool sys core/operator (get builtin tool-name) args [])]
    (if error (throw (ex-info error {:relay/usage true})) ok)))

(declare coerce-input input-problem)

(def refused-result
  "What a public role is told in place of a result that carried private."
  "This result was refused: it carries private data, which a public role may not see.")

(defn- refuse-private
  "A public role's result as it may have it: one that carries private (an
  envelope tool can say any labels, and a refusal can quote anything) is
  replaced by a refusal, and the operator is told which tool it was.
  agentloop refuses it too, with forbid_labels."
  [sys role-id tool-name result]
  (if (and (core/private? (:labels result))
           (core/public-caller? (inbound/current-state sys) (:config sys) role-id *wake*))
    (do (try (inbound/submit! sys (cond-> {:type :result/refused :actor role-id :args {:tool tool-name} :labels []}
                                    *wake* (assoc :session-wake *wake*)))
             (catch Exception e
               (binding [*out* *err*] (println "relay could not record a refused result:" (ex-message e)))))
        {:error refused-result :labels []})
    ;; agentloop drops a display a public role's session may not hold;
    ;; relay does not pass one on either.
    (if (and (:display result) (core/private? (get-in result [:display :labels]))
             (core/public-caller? (inbound/current-state sys) (:config sys) role-id *wake*))
      (dissoc result :display)
      result)))

(defn call!
  "Runs one tool call for a role, made by a session holding `labels`, and
  returns {:ok text :labels} or {:error text :labels}: the text to give
  back to it and the labels of what that text shows. Refusals come back as
  text too, so the role can read why and adjust. `wake` is the wake whose
  session made the call, from the tool command relay gave the harness."
  [sys role-id tool-name args labels & [call-id wake]]
  (let [state (inbound/current-state sys)
        config (:config sys)
        tool (get (offered config role-id) tool-name)
        ;; A harness that cannot attest its session's labels is taken to
        ;; have seen everything it could have. relay tool refuses a script
        ;; role's calls outright; this is the second line.
        labels (if (script-role? state config role-id)
                 (core/union labels (core/session-labels-bound state config role-id))
                 labels)]
    (cond
      (not (core/active-role? state role-id))
      {:error (str role-id " is not an active role.") :labels []}
      ;; The name is the session's own text, so the refusal carries the
      ;; session's labels, as any refusal that quotes the call does.
      (nil? tool)
      {:error (str "You have no tool called " tool-name ".") :labels (core/union labels)}
      ;; agentloop calls an MCP tool's server itself; a call that reaches
      ;; relay's tool command did not come through it, and is not run.
      (:mcp tool)
      {:error (str "The tool " tool-name " comes from an MCP server, which agentloop runs.") :labels []}
      :else
      ;; Only relay's own tools take a quoted number as a number. An
      ;; external tool gets the input as agentloop gave it.
      (let [args (cond-> (or args {}) (not (contains? (:tools config) tool-name)) coerce-input)]
        (if-let [problem (input-problem (:parameters tool) args)]
          {:error problem :labels []}
          (binding [*call-id* call-id *wake* wake]
            (refuse-private sys role-id tool-name (run-tool sys role-id tool args labels))))))))

;; --- the envelope a harness sends for each call

(defn- type-ok? [{:keys [type items]} v]
  (case type
    "string" (string? v)
    "integer" (integer? v)
    ;; The strict reader keeps a decimal as a BigDecimal, exactly.
    "number" (number? v)
    "boolean" (boolean? v)
    "array" (and (sequential? v) (every? #(type-ok? items %) v))
    "object" (map? v)
    false))

(defn input-problem
  "Why a call's input does not fit its tool's parameter schema, or nil.
  relay's schemas are flat objects, so this checks what they say: the
  required properties, no others, and each one's type."
  [{:keys [properties required] :as schema} input]
  (let [props (into {} (map (fn [[k v]] [(name k) v]) properties))]
    (cond
      (not (map? input)) "The input must be a JSON object."
      ;; A schema that names no properties leaves checking to the tool.
      (not (contains? schema :properties)) nil
      :else
      (or (first (for [r required :when (not (contains? input (keyword r)))]
                   (str "The input needs " r ".")))
          (first (for [[k v] input
                       ;; The whole key: "card/seen" is not "seen", though
                       ;; (name :card/seen) is.
                       :let [schema (get props (if (keyword? k) (subs (str k) 1) (str k)))]
                       :when (or (nil? schema) (not (type-ok? schema v)))]
                   (if schema
                     (str (name k) " must be " (case (:type schema)
                                                 "array" (if (= "string" (get-in schema [:items :type])) "a list of strings" "a list")
                                                 (str "a " (:type schema))) ".")
                     ;; Fixed text: a tool's refusal carries no labels, so it
                     ;; must not quote the call's own key.
                     (str "The input has an argument this tool does not take. It takes "
                          (if (seq props) (str/join ", " (sort (keys props))) "none") "."))))))))

(defn coerce-input
  "A model sometimes quotes a number."
  [input]
  (if (map? input)
    (reduce (fn [args k]
              (cond-> args
                (string? (get args k)) (update k #(or (parse-long (str/trim %)) %))))
            input [:memo :fact :event :version :limit :entry :card :item :row :column :seen :in_reply_to])
    input))

(defn parse-envelope
  "A tool call as a harness sends it: exactly {input, labels, call_id}, with
  labels relay knows. Returns {:input :labels :call-id}, or {:problem text}.
  Anything else is refused, so a harness that has not sent the session's
  labels never has its calls run as a clean session's."
  [raw]
  (let [env (if (or (nil? raw) (and (string? raw) (str/blank? raw))) nil (strict-json-value raw))]
    (cond
      (or (nil? env) (= ::bad env)) {:problem "The call is not a JSON envelope."}
      (not (map? env)) {:problem "The call is not a JSON object."}
      (not= #{"input" "labels" "call_id"} (set (keys env)))
      {:problem "The call must have exactly input, labels and call_id."}
      (not (and (sequential? (get env "labels")) (every? core/label-names (get env "labels"))))
      {:problem (str "Unknown labels " (pr-str (get env "labels")) ".")}
      (not (and (string? (get env "call_id")) (not (str/blank? (get env "call_id")))))
      {:problem "The call has no call_id."}
      (not (map? (get env "input"))) {:problem "The call's input must be a JSON object."}
      :else {:input (strict/parse (strict/generate (get env "input")) {:keywords? true})
             :labels (core/union (get env "labels"))
             :call-id (get env "call_id")})))

(defn envelope
  "The result as a harness reads it, with the tool's display, if it gave
  one, for agentloop to keep apart from the model."
  [{:keys [ok error labels display]}]
  (json/generate-string (cond-> {:content (or ok error) :is_error (some? error) :labels (core/union labels)}
                          (and display (nil? error))
                          (assoc :display {:title (:title display) :body (:body display)
                                           :labels (core/union (:labels display))
                                           :sources (vec (:sources display))}))))
