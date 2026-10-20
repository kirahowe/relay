(ns relay.tools
  "The tools a role works with. A role has no file or shell access of its
  own: everything it does is a call to one of these, by name, and every call
  that changes anything goes through the inbound path as that role.

  Each tool has a class (see relay.config/tool-classes). relay's own tools
  are defined here; the ones that remove something also say so as their
  :acp-kind, which an ACP client shows. The operator can grant further
  tools in relay.edn, each one an external command given the call's
  arguments on its command line or an envelope on stdin, or a tool of an
  MCP server, which agentloop runs.
  Which tools a role is offered depends on its grants, and a call to a tool
  the role was not offered is refused, whatever the harness let through.

  A read tool is a pure :read function of the state, the config, the time,
  the caller and the call's arguments. It returns its text with the labels
  of everything that text shows (relay.core's labels section): a session
  that reads it gains them. Every other tool has a :run function that
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
            [relay.json :as strict]
            [relay.refs :as refs]
            [relay.reports :as reports])
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

(def ^:private newer-withheld
  "A newer change to this is contaminated and withheld; pass accept_contamination to see it.")

(defn- fmt-fact [config {:keys [id topic text supersedes superseded-by newer-withheld?] :as f}]
  (str "Fact " id (when topic (str " [" topic "]")) ", holds " (validity config f)
       (when supersedes (str ", replacing fact " supersedes))
       (when superseded-by (str ", superseded by fact " superseded-by))
       " " (refs/ref :fact id)
       "\n  " text
       (when (:newer-withheld f) (str "\n  (" newer-withheld ")"))))

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

(def ^:private accept
  {:type "boolean"
   :description (str "Include contaminated items: text an outsider may have written, or a role"
                     " wrote after reading such text. They are withheld by default. Reading one"
                     " marks you contaminated, and from then on the operator must approve your"
                     " calls that act outside relay or send data out.")})

(defn- doc-ref
  "The ref to show beside a doc version a read returns, or nil. The path
  is text whoever first wrote the doc chose, so it is shown only when
  `labels`, the labels the read puts on the item the ref goes in, already
  cover its labels."
  [state path labels version]
  (when (every? (set labels) (core/path-labels state path))
    (refs/doc-ref path version)))

(defn- author [state actor] (core/shown-id state actor))

(defn- item
  "One thing a read shows: its text and labels, and what stands in for it
  when it is withheld, with the labels of that."
  [text labels placeholder & [placeholder-labels]]
  {:text text :labels labels :placeholder placeholder :placeholder-labels (or placeholder-labels [])})

(defn- show
  "Joins what a read shows, withholding contaminated items unless the
  caller accepts them. An item with :always-withhold is withheld whatever
  the caller says."
  [items sep accept?]
  (let [shown (for [{:keys [text labels placeholder placeholder-labels always-withhold]} items]
                (if (and (core/contaminated? labels) (or always-withhold (not accept?)))
                  [placeholder placeholder-labels]
                  [text labels]))]
    {:content (str/join sep (map first shown))
     :labels (apply core/union (map second shown))}))

(defn- withheld [what] (str what ": contaminated, withheld. Pass accept_contamination to read it."))

(defn- public-reader?
  "Whether a read is a public role's. It is shown public items only, and
  nothing else of what relay keeps: not shown, not counted, with no
  placeholder, since even a placeholder says that something is there and
  who wrote it. It has no private data to leak, so contaminated items are
  not withheld from it. A role is read for as public when relay.edn says it
  is, or when the wake that made the call (*wake*) began public, so a
  change to relay.edn while a session runs never widens what it sees."
  [state config actor]
  (core/public-caller? state config actor *wake*))

(def builtin
  "relay's own tools, by name."
  {"inbox"
   {:class :read
    :description (str "List the memos addressed to you that are still open. A memo the operator"
                      " has quarantined is listed but not shown until they release it.")
    :parameters (obj {:accept_contamination accept})
    :read (fn [{:keys [state config]} actor _]
            (let [memos (cond->> (core/unread-memos state actor)
                          (public-reader? state config actor)
                          (filter #(core/shown-to-public? % (core/memo-labels state %))))
                  kept (core/kept-memo-ids state actor)]
              (if (empty? memos)
                "Nothing is waiting for you."
                (show (for [m memos
                            :let [[who who-labels] (author state (:from m))
                                  ;; A declassified memo's sender as the
                                  ;; operator vouched for it.
                                  who-labels (or (:from-labels m) who-labels)]]
                        (cond
                          ;; Another session's, waiting with it for the operator.
                          (kept (:id m))
                          (item (str "Memo " (:id m) " from " who ": kept by a session waiting for the"
                                     " operator to decide a held call. It goes on there; leave it.\n")
                                who-labels "")
                          (core/quarantined? config m)
                          (assoc (item nil (:labels m)
                                       (str "Memo " (:id m) " from " who ": quarantined. It is contaminated,"
                                            " and the operator has to release it before you can read it.\n")
                                       who-labels)
                                 :always-withhold true)
                          ;; A released memo was delivered by the operator.
                          :else
                          (item (fmt-memo (core/shown-memo state m)) (core/memo-labels state m)
                                (str "Memo " (:id m) " from " who ": contaminated.\n") who-labels)))
                      ;; A contaminated memo shown here was released by the
                      ;; operator, or is to a role that may take one in.
                      "\n" true))))}

   "roles"
   {:class :read
    :description "List the roles in relay: who exists, what each is for, and who it reports to. Send a memo to `operator` to reach the operator."
    :parameters (obj {:accept_contamination accept})
    :read (fn [{:keys [state config]} actor {:keys [accept_contamination]}]
            (let [public (public-reader? state config actor)]
              (show (for [id (core/active-role-ids state)
                          ;; A public role sees the roles whose text the
                          ;; operator wrote, and whom they report to only
                          ;; when the operator chose that id too.
                          :when (or (not public) (core/role-text-public? state id))
                          :let [{:keys [title reports-to title-labels]} (core/role state id)
                                reports-to (when (or (not public) (core/role-id-public? state reports-to)) reports-to)
                                id-ls (core/id-labels state id)
                                labels (core/union id-ls title-labels (core/id-labels state reports-to))]]
                      (item (str id ": " title (when reports-to (str ", reports to " reports-to)))
                            labels
                            (if (core/contaminated? id-ls)
                              (withheld "A role")
                              (withheld (str id ": its title")))
                            (when-not (core/contaminated? id-ls) id-ls)))
                    "\n" (or public accept_contamination))))}

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
    :parameters (obj {:accept_contamination accept})
    :read (fn [{:keys [state config]} actor {:keys [accept_contamination]}]
            (let [public (public-reader? state config actor)
                  rs (cond->> (core/active-routines state actor)
                       public (filter #(core/shown-to-public? % (core/item-labels %))))]
              (if (empty? rs)
                "You have no routines."
                ;; A routine's instructions become a memo's body when it
                ;; fires, so they are content like a memo's.
                (show (for [{:keys [name schedule instructions] :as r} rs]
                        (item (str name " (" schedule ")\n  " instructions)
                              (core/item-labels r)
                              (withheld "A routine")))
                      "\n\n" (or public accept_contamination)))))}

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
    :parameters (obj {:topic text :search text :as_of world-time :known_at world-time :all flag
                      :accept_contamination accept})
    :read (fn [{:keys [state config] :as ctx} actor args]
            (let [public (public-reader? state config actor)
                  accept_contamination (or public (:accept_contamination args))
                  ;; A contaminated change never hides a clean record, and
                  ;; a contaminated fact's text is not even searched unless
                  ;; it is accepted, since a match, or how it moves the
                  ;; ranking of the others, would say something about it.
                  found (core/facts-shown state (merge (times ctx args) (select-keys args [:all :topic :search]))
                                          {:public public :accept accept_contamination})
                  ;; Said whether or not there are any: how many there are is
                  ;; up to whoever wrote them.
                  note (when-not accept_contamination
                         (str "Contaminated facts, if there are any, were left out and not searched."
                              " Pass accept_contamination to include them."))]
              (if (and (empty? found) (nil? note))
                "No facts match."
                (update (show (for [f found]
                                (item (fmt-fact config f) (core/item-labels f)
                                      (withheld (str "Fact " (:id f)))))
                              "\n" true)
                        :content #(str/join "\n" (remove str/blank? [(if (empty? found) "No facts match." %) note]))))))}

   "fact_history"
   {:class :read
    :description "Every recorded version of a fact: when it was recorded, changed or corrected, by whom and why."
    :parameters (obj {:fact fact-id :accept_contamination accept} "fact")
    :read (fn [{:keys [state config]} actor {:keys [fact accept_contamination]}]
            (let [public (public-reader? state config actor)
                  f (core/fact state fact)]
              (if (or (nil? f) (and public (not (core/fact-shown-to-public? state (core/fact-as-known f nil)))))
                (core/reject! :not-found (str "There is no fact " fact "."))
                (update (show (for [v (:versions f)
                                    :when (or (not public) (core/fact-shown-to-public? state v))
                                    :let [[who who-labels] (author state (:actor v))]]
                                (item (str "- " (fmt-version config v))
                                      (core/item-labels v (core/version-labels state v))
                                      (withheld (str "- " (local-time config (:recorded-at v)) " by " who))
                                      who-labels))
                              "\n" (or public accept_contamination))
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
    :parameters (obj {:limit {:type "integer" :description "How many, 10 by default."}
                      :accept_contamination accept})
    :read (fn [{:keys [state config]} actor {:keys [accept_contamination] :as args}]
            (let [public (public-reader? state config actor)
                  entries (core/journal-shown state (own actor args) (or (:limit args) 10) public)]
              (if (empty? entries)
                "The journal is empty."
                (show (for [{:keys [id at text] :as e} entries]
                        (item (str (local-time config at) " " (refs/ref :journal id) "\n" text) (core/item-labels e)
                              (withheld (str (local-time config at) "\nEntry " id))))
                      "\n\n" (or public accept_contamination)))))}

   "journal_write"
   {:class :write
    :description "Add an entry to your journal: what you did and learned, for later. Entries are never changed."
    :parameters (obj {:text text} "text")
    :run (fn [sys actor labels args]
           (submit sys actor labels :journal/write (select-keys args [:owner :text]))
           "Written.")}

   "notes"
   {:class :read
    :description (str "Read your notes. They are in your instructions at the start of every wake,"
                      " except contaminated ones, which are withheld there and here unless you"
                      " accept them.")
    :parameters (obj {:accept_contamination accept})
    :read (fn [{:keys [state config]} actor {:keys [accept_contamination] :as args}]
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
                              (core/item-labels n) (withheld "A note")))
                      "\n\n" (or public accept_contamination)))))}

   "docs"
   {:class :read
    :description "List the docs: longer shared content that is really a file, by path."
    :parameters (obj {:accept_contamination accept})
    :read (fn [{:keys [state config]} actor {:keys [accept_contamination]}]
            (let [public (public-reader? state config actor)
                  accept_contamination (or public accept_contamination)
                  ds (cond->> (core/docs state nil (not accept_contamination))
                       public (filterv #(core/doc-shown-to-public? state (:path %) %)))]
              (if (empty? ds)
                "There are no docs."
                (show (for [{:keys [path at actor content seq] :as v} ds
                            :let [[who who-labels] (author state actor)]]
                        (item (str path " (" (count content) " characters, last written "
                                   (local-time config at) " by " actor ") " (refs/doc-ref path nil)
                                   (when (:newer-withheld v) (str " (" newer-withheld ")")))
                              (core/union (core/version-labels state v) (core/path-labels state path))
                              (withheld (str "A doc, version " seq " by " who))
                              who-labels))
                      "\n" accept_contamination))))}

   "doc_get"
   {:class :read
    :description "Read a doc, the latest version unless you give one from its history."
    :parameters (obj {:path text :version {:type "integer"} :accept_contamination accept} "path")
    :read (fn [{:keys [state config]} actor {:keys [path version accept_contamination]}]
            (let [public (public-reader? state config actor)
                  accept_contamination (or public accept_contamination)
                  v (if (or version accept_contamination)
                      (core/doc-version state path {:version version})
                      (core/doc-as-shown state path true))
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
                                            (when (:newer-withheld v) (str "\n\n[relay: " newer-withheld "]"))
                                            (some->> (doc-ref state path (core/item-labels v) (:seq v)) (str "\n\n")))
                                       (core/item-labels v)
                                       (withheld (str "Version " (:seq v) " of the doc")))]
                                "" accept_contamination))))}

   "doc_history"
   {:class :read
    :description "The versions of a doc: when each was written and by whom."
    :parameters (obj {:path text :accept_contamination accept} "path")
    :read (fn [{:keys [state config]} actor {:keys [path accept_contamination]}]
            (let [public (public-reader? state config actor)
                  vs (get-in state [:docs path :versions])]
              (if (or (empty? vs) (and public (not (core/doc-shown-to-public? state path (peek vs)))))
                (core/reject! :not-found (str "There is no doc at " path "."))
                (show (for [{:keys [seq at actor content removed] :as v} vs
                            :when (or (not public) (core/doc-shown-to-public? state path v))
                            :let [[who who-labels] (author state actor)]]
                        (item (str "version " seq ", " (local-time config at) " by " actor
                                   (if removed ": removed" (str ", " (count content) " characters"))
                                   (some->> (doc-ref state path (core/version-labels state v) seq) (str " ")))
                              (core/version-labels state v)
                              (withheld (str "version " seq ", " (local-time config at) " by " who))
                              who-labels))
                      "\n" (or public accept_contamination)))))}

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

   "cards"
   {:class :read
    :grant :private
    :description (str "Read the operator's board: cards for work the operator comes back to over days,"
                      " or a status worth a glance. With no arguments, the active cards. `card` shows one"
                      " card, archived or not; `archived` lists the archived cards too. Each card shows the"
                      " numbers of its items, rows and columns, which card_op takes.")
    :parameters (obj {:card {:type "integer" :description "The card's number."}
                      :archived flag
                      :accept_contamination accept})
    :read (fn [{:keys [state config]} actor {:keys [card archived accept_contamination]}]
            ;; A public role has no board: cards are private only.
            (when (public-reader? state config actor)
              (throw (ex-info "You have no tool called cards." {})))
            (let [cs (if card
                       [(or (core/card state card) (core/reject! :not-found (str "There is no card " card ".")))]
                       (core/board state archived))]
              (if (empty? cs)
                "The board is empty."
                (show (for [c cs]
                        (item (cards/text-form c) (core/card-labels c) (withheld (str "Card " (:id c)))))
                      "\n\n" accept_contamination))))}

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

   "ledger"
   {:class :read
    :grant :ledger
    :description (str "List the ledger: the findings of research roles that the operator accepted, with"
                      " their status (new, seen, applied or passed). An entry relay accepted without the"
                      " operator shows only its kind, deadline and amount; its text is contaminated and"
                      " withheld unless you accept contamination.")
    :parameters (obj {:status {:type "string" :enum reports/statuses :description "Only entries with this status."}
                      :accept_contamination accept})
    :read (fn [{:keys [state config]} actor {:keys [status accept_contamination]}]
            ;; A public role has no ledger: it holds the operator's decisions.
            (when (public-reader? state config actor)
              (throw (ex-info "You have no tool called ledger." {})))
            (let [entries (cond->> (core/ledger-entries state) status (filter #(= status (:status %))))]
              (if (empty? entries)
                "The ledger has no entries."
                (show (for [{:keys [title organization url location] :as e} entries
                            :let [typed (reports/typed-line e)
                                  full (str typed " " (refs/ref :report (:report e)) "\n  " title " at " organization
                                            (when location (str ", " location))
                                            "\n  " url)]]
                        (item full (core/item-labels e)
                              (str typed "\n  Its text is contaminated, withheld. Pass accept_contamination to read it.")
                              [core/private-label]))
                      "\n" accept_contamination))))}

   "ledger_status"
   {:class :write
    :grant :ledger
    :description "Set a ledger entry's status, as the operator told you: new, seen, applied or passed."
    :parameters (obj {:entry {:type "integer" :description "The entry's number."}
                      :status {:type "string" :enum reports/statuses}}
                     "entry" "status")
    :run (fn [sys actor labels args]
           (submit sys actor labels :ledger/set-status args)
           (str "Entry " (:entry args) " is " (:status args) "."))}

   "reports"
   {:class :read
    :grant :reports
    :description (str "List the findings you reported before, by title and URL, so you can leave out"
                      " what you have already reported.")
    :parameters (obj {})
    :read (fn [{:keys [state config]} actor _]
            (let [public (public-reader? state config actor)
                  rs (->> (vals (:reports state))
                          (filter #(and (= actor (:role %))
                                        (or (not public) (core/shown-to-public? % (core/item-labels %)))))
                          (sort-by :id))]
              (if (empty? rs)
                "You have not reported anything yet."
                ;; What it wrote, and nothing of what relay or the operator
                ;; made of it.
                (labelled (str/join "\n" (for [r rs f (:findings r)]
                                           (str "- " (:title f) "  " (reports/canonical-url (:url f))
                                                " " (refs/ref :report (:id r)))))
                          (mapcat core/item-labels rs)))))}

   "report_submit"
   {:class :write
    :grant :reports
    :description (str "Submit a report of what you found: a short summary and the findings, each with the"
                      " page it is on and evidence quoted word for word from pages you fetched. relay"
                      " checks every quote against its page before the operator reads the report. You"
                      " are not told how the checks went.")
    ;; The kinds come from the role's grant (offered).
    :parameters (reports/input-schema [])
    :run (fn [sys actor labels args]
           (let [id (:seq (first (filter #(= "report/submitted" (:type %))
                                         (submit sys actor labels :report/submit args))))]
             (str "Report " id " submitted; it will be checked.")))}

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
        result (try (run-limited (vec command) (json/generate-string {:input args :labels labels :call_id (str *call-id*)})
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

(def preview-timeout-ms
  "How long a tool's :preview command may run. A preview reads a service;
  past this the hold opens without one."
  30000)

(defn run-preview!
  "Runs a tool's :preview command on a held call, on the envelope protocol:
  the call's input, the session's labels and the call id on stdin, one
  result envelope on stdout, whose content is markdown saying what the call
  would do. Returns {:content :is-error :labels}. When the command fails,
  the content says so and :is-error is true: the hold opens all the same,
  and what the command printed goes to the operator's log."
  [tool-name command input labels call-id]
  (let [failed (fn [why out err]
                 (binding [*out* *err*] (println (str tool-name " preview: " why)) (print err out) (flush))
                 {:content (str "relay could not get a preview: the preview command " why ".")
                  :is-error true :labels []})
        result (try (run-limited (vec command) (json/generate-string {:input input :labels (vec labels)
                                                                      :call_id (str call-id)})
                                 preview-timeout-ms {:bytes? true})
                    (catch Exception e (ex-message e)))]
    (if-not (map? result)
      (failed (if (= ::timeout result) "timed out" (str "could not start: " result)) "" "")
      (let [{:keys [exit err] raw :out} result
            parsed (when (zero? exit) (parse-result raw))
            out (String. ^bytes raw java.nio.charset.StandardCharsets/UTF_8)]
        (cond
          (not (zero? exit)) (failed (str "exited " exit) out err)
          (nil? parsed) (failed "did not print exactly one result envelope" out err)
          :else parsed)))))

(def verifier-output-limit
  "The most a verifier may print, in bytes. Twenty pages' worth of check
  output is far less; past this the run is stopped and counts as failed,
  so a verifier gone wrong cannot fill memory or the log."
  (* 8 1024 1024))

(defn- read-bounded
  "Everything on `in`, as bytes, or ::too-long once it passes `limit` bytes."
  [^java.io.InputStream in limit]
  (let [out (java.io.ByteArrayOutputStream.)
        buf (byte-array 65536)]
    (loop [total 0]
      (let [n (.read in buf)]
        (cond (neg? n) (.toByteArray out)
              (> (+ total n) limit) ::too-long
              :else (do (.write out buf 0 n) (recur (+ total n))))))))

(defn run-verifier
  "Runs the :verifier on one check request (relay.reports/check-requests),
  on the envelope protocol, as a call from a session holding `labels`.
  Returns {:content the check output parsed as JSON, :labels it reported},
  or throws saying only that it failed. Its output is read only up to
  verifier-output-limit; what it prints on stderr is relay's."
  [{:keys [command timeout-seconds]} labels request call-id]
  (let [failed (fn [why]
                 (binding [*out* *err*] (println (str "verifier: " why)) (flush))
                 (throw (ex-info "The verifier failed." {})))
        proc (p/process (vec command) {:in (json/generate-string {:input request :labels labels :call_id (str call-id)})
                                       :out :stream :err :inherit :env (harness/base-env {})})
        out (future (read-bounded (:out proc) verifier-output-limit))
        timeout-ms (* 1000 (or timeout-seconds config/verifier-timeout-seconds))
        text (deref out timeout-ms ::timeout)]
    (when (#{::timeout ::too-long} text)
      (p/destroy-tree proc)
      (failed (if (= ::timeout text) "timed out" "printed more than relay reads")))
    (let [exit (:exit (deref proc 10000 {:exit ::timeout}))
          parsed (when (= 0 exit) (parse-result text))
          v (when parsed (strict-json-value (:content parsed)))]
      (cond
        (not= 0 exit) (do (p/destroy-tree proc) (failed (str "exited " exit)))
        (nil? parsed) (failed "did not print exactly one result envelope")
        (:is-error parsed) (failed "answered with an error")
        (= ::bad v) (failed "its content is not one JSON value")
        :else {:content v :labels (:labels parsed)}))))

(defn- external-tool
  "A tool the operator defined in relay.edn: an external command, given the
  call's `args` after its own, or with :protocol :envelope, given the call
  as an envelope on stdin and answering with one on stdout. With :protocol
  :mcp-stdio or :mcp-http it is a tool of an MCP server, which agentloop
  runs or reaches: relay only names it to the harness (:mcp) and polices
  it like any other."
  [[tool-name {:keys [class description command labels always-hold protocol parameters timeout-seconds consent] :as spec}]]
  (case protocol
    ;; agentloop runs an MCP tool itself, against its server; relay's tool
    ;; command never does. Its server gives the description and schema.
    (:mcp-stdio :mcp-http)
    [tool-name
     {:class class
      :labels (vec labels)
      :always-hold (boolean always-hold)
      :consent consent
      :mcp (select-keys spec [:protocol :server :tool :digest :pin :allow-content :fixed-args :hidden-args])}]
    :envelope
    [tool-name
     {:class class
      :labels (vec labels)
      :always-hold (boolean always-hold)
      :consent consent
      :description description
      :parameters (or parameters {:type "object"})
      :run (fn [_ _ session-labels args]
             (run-envelope-tool tool-name command (vec labels) session-labels args
                                (* 1000 (or timeout-seconds default-timeout-seconds))))}]
  ;; A command given the call's arguments.
  [tool-name
   {:class class
    :labels (vec labels)
    :always-hold (boolean always-hold)
    :consent consent
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

(defn offered
  "The tools a role is offered, by name: relay's own, less those that need a
  grant it lacks, plus the external tools it is granted. None at all for a
  role on a :script harness."
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
                   :reports (some? (config/report-kinds config role-id))
                   ;; Only once some role writes reports, so there can be a
                   ;; ledger at all.
                   :ledger (and (not (config/public? config role-id)) (seq (config/research-roles config)))
                   ;; Cards are private only.
                   :private (not (config/public? config role-id))))]
    (cond-> (merge (into {} (filter #(holds? (:grant (val %))) builtin))
                   (into {} (map external-tool (select-keys (:tools config) tools))))
      ;; report_submit's schema names the kinds this role may report.
      (holds? :reports) (assoc-in ["report_submit" :parameters]
                                  (reports/input-schema (config/report-kinds config role-id)))))))

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
                (contains? offered "ledger")
                (conj (str "The ledger holds the research findings the operator accepted. `ledger` lists them,"
                           " and `ledger_status` records a status the operator gives you for one."))
                (contains? offered "report_submit")
                (conj (str "You report what you find with `report_submit`, as findings of the kinds "
                           (str/join ", " (config/report-kinds config role-id)) ". `reports` lists what you"
                           " reported before, so you need not report it again."))
                (core/public-role? config role-id)
                (conj (str "You are a public role: you may search and browse the web, so you are shown only what"
                           " is public, and whatever you write is public. The operator's private data never"
                           " reaches you."))
                :always
                (conj (if (seq external)
                        (str "Beyond relay's own tools, the operator has granted you: "
                             (str/join ", " (map #(str "`" % "`") external)) ".")
                        "You have relay's own tools and nothing beyond them."))
                (config/workbench config role-id)
                (conj (str "For questions about files on this Mac, and for calculations over data, you have"
                           " `workbench`: you give it a task and a JSON Schema for the answer, and a separate"
                           " assistant searches and reads the disk and computes in a sandbox with no way to"
                           " send anything out. You see only its answer. Ask for the narrowest schema that"
                           " answers the question."))
                (seq (config/quarantine-tools config role-id))
                (conj (str "You can also read "
                           (str/join ", " (map #(str "`" % "`") (config/quarantine-tools config role-id)))
                           " through `read_quarantined`."))
                :always
                (conj (str "Only the operator grants tools and powers. If your work needs one you lack,"
                           " ask whoever you report to, or the operator if you report to nobody.")))))))

(defn manifest
  "What a harness needs to present the tools: names, descriptions and
  parameter schemas (for an MCP tool, its :mcp server and name instead),
  each tool's class and static labels (none for relay's own), whether
  every call needs the operator, the operator's standing :consent for an
  act tool, and for some of relay's own tools an :acp-kind more exact than
  the class gives (relay.acp/tool-kinds)."
  [config role-id & [opts]]
  (vec (for [[n {:keys [class description parameters acp-kind labels always-hold mcp consent]}]
             (sort-by key (offered config role-id opts))]
         (cond-> {:name n :class (name class) :description description :parameters parameters
                  :labels (vec labels)}
           always-hold (assoc :always-hold true)
           (seq consent) (assoc :consent consent)
           acp-kind (assoc :acp-kind acp-kind)
           ;; An MCP tool: its server and the server's name for it. The
           ;; server gives the description and parameters.
           mcp (-> (dissoc :description :parameters) (assoc :mcp mcp))))))

(defn policy
  "agentloop's policy for a role's tools and a session seeded with
  `labels`: each tool's class, static labels and the operator's standing
  consent, by name. agentloop matches a call against the consent. A public role's
  session may never hold private, which agentloop enforces too:
  forbid_labels."
  ([manifest labels] (policy manifest labels {}))
  ([manifest labels {:keys [public?]}]
   (cond-> {:labels (core/union labels)
            :tools (into (sorted-map)
                         (for [{:keys [name class labels always-hold consent]} manifest]
                           [name (cond-> {:class class}
                                   (seq labels) (assoc :labels (vec labels))
                                   always-hold (assoc :always_hold true)
                                   (seq consent) (assoc :consent (vec consent)))]))}
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
      ;; external tool gets the input as agentloop decided it, so the
      ;; operator's consent and the preview see what the tool runs on.
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
            input [:memo :fact :version :limit :entry :card :item :row :column :seen])
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
      :else {:input (strict/parse (json/generate-string (get env "input")) {:keywords? true})
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
