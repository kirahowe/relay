(ns relay.tools
  "The tools a role works with. A role has no file or shell access of its
  own: everything it does is a call to one of these, by name, and every call
  that changes anything goes through the inbound path as that role.

  Each tool has a class (see relay.config/tool-classes). relay's own tools
  are defined here; the ones that remove something also say so as their
  :acp-kind, which an ACP client shows. The operator can grant further
  tools in relay.edn, each one an external command given the call's
  arguments on its command line.
  Which tools a role is offered depends on its grants, and a call to a tool
  the role was not offered is refused, whatever the harness let through."
  (:require [babashka.process :as p]
            [clojure.set :as set]
            [clojure.string :as str]
            [relay.config :as config]
            [relay.core :as core]
            [relay.harness :as harness]
            [relay.inbound :as inbound])
  (:import (java.time Instant LocalTime)
           (java.time.format DateTimeFormatter)))

(defn- obj [props & required]
  {:type "object" :properties props :required (vec required) :additionalProperties false})

(def ^:private text {:type "string"})
(def ^:private memo-id {:type "integer" :description "The memo's number, as shown in the inbox."})

(defn- fmt-memo [{:keys [id from subject body sent-at attempts routine]}]
  (str "Memo " id ": " subject "\n"
       (if routine
         (str "  Your routine `" routine "` coming due, " sent-at ".")
         (str "  From " from ", sent " sent-at "."))
       (when (pos? attempts) (str " Left open by " attempts " earlier wake(s)."))
       "\n\n" (str/replace body #"(?m)^" "  ") "\n"))

(defn- submit [sys actor type args]
  (inbound/submit! sys {:type type :actor actor :args args}))

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
  [{:keys [config clock]} {:keys [as_of known_at]}]
  {:as-of (or (core/world-time config as_of "as_of") (clock))
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

(def builtin
  "relay's own tools, by name."
  {"inbox"
   {:class :read
    :description "List the memos addressed to you that are still open."
    :parameters (obj {})
    :run (fn [sys actor _]
           (let [memos (core/unread-memos (inbound/current-state sys) actor)]
             (if (empty? memos)
               "Nothing is waiting for you."
               (str/join "\n" (map fmt-memo memos)))))}

   "roles"
   {:class :read
    :description "List the roles in relay: who exists, what each is for, and who it reports to. Send a memo to `operator` to reach the operator."
    :parameters (obj {})
    :run (fn [sys _ _]
           (let [state (inbound/current-state sys)]
             (str/join "\n" (for [id (core/active-role-ids state)
                                  :let [{:keys [title reports-to]} (core/role state id)]]
                              (str id ": " title (when reports-to (str ", reports to " reports-to)))))))}

   "memo_send"
   {:class :write
    :description "Send a memo to a role, or to `operator`. The subject is one line; put the ask in it. The body must stand on its own for a reader with no memory of your work."
    :parameters (obj {:to text :subject text :body text} "to" "subject" "body")
    :run (fn [sys actor args]
           (str "Sent memo " (created-memo (submit sys actor :memo/send args)) " to " (:to args) "."))}

   "memo_close"
   {:class :write
    :description "Close a memo you have dealt with, saying in a sentence or two what you did. Close only what you have done."
    :parameters (obj {:memo memo-id :outcome text} "memo" "outcome")
    :run (fn [sys actor args]
           (submit sys actor :memo/close args)
           (str "Closed memo " (:memo args) "."))}

   "memo_block"
   {:class :write
    :description "Say you cannot do what a memo asks, why, and what would unblock you. It goes to whoever handles dead letters. Trying again would not help."
    :parameters (obj {:memo memo-id :explanation text} "memo" "explanation")
    :run (fn [sys actor args]
           (submit sys actor :memo/block args)
           (str "Blocked memo " (:memo args) "; it has been passed on."))}

   "routines"
   {:class :read
    :description "List your routines and their schedules."
    :parameters (obj {})
    :run (fn [sys actor _]
           (let [rs (core/active-routines (inbound/current-state sys) actor)]
             (if (empty? rs)
               "You have no routines."
               (str/join "\n\n" (for [{:keys [name schedule instructions]} rs]
                                  (str name " (" schedule ")\n  " instructions))))))}

   "routine_write"
   {:class :write
    :description "Create or replace one of your routines: a memo you send yourself on a schedule. Schedules: `every 30m` (or 2h, 1d), `daily 07:30`, `weekly mon 09:00`, `once 2026-10-08 09:00`. Writing a routine again restarts its clock. Every wake costs money, so keep routines few."
    :parameters (obj {:name {:type "string" :description "Lower case letters, digits and hyphens."}
                      :schedule text :instructions text}
                     "name" "schedule" "instructions")
    :run (fn [sys actor args]
           (submit sys actor :routine/write args)
           (str "Routine " (:name args) " is set."))}

   "routine_remove"
   {:class :write :acp-kind "delete"
    :description "Remove one of your routines."
    :parameters (obj {:name text} "name")
    :run (fn [sys actor args]
           (submit sys actor :routine/remove args)
           (str "Removed routine " (:name args) "."))}

   "facts"
   {:class :read
    :description (str "Look up facts: what relay knows about the world. By default, the facts that"
                      " hold now. Narrow by `topic` (a topic includes those under it) or `search`"
                      " (words in the text). `as_of` asks what held at another time; `known_at`"
                      " asks what relay had recorded by then; `all` lists every fact, past and"
                      " future too.")
    :parameters (obj {:topic text :search text :as_of world-time :known_at world-time :all flag})
    :run (fn [{:keys [config] :as sys} _ args]
           (let [found (core/facts (inbound/current-state sys)
                                   (merge (times sys args) (select-keys args [:topic :search :all])))]
             (if (empty? found)
               "No facts match."
               (str/join "\n" (map #(fmt-fact config %) found)))))}

   "fact_history"
   {:class :read
    :description "Every recorded version of a fact: when it was recorded, changed or corrected, by whom and why."
    :parameters (obj {:fact fact-id} "fact")
    :run (fn [{:keys [config] :as sys} _ {:keys [fact]}]
           (let [f (core/fact (inbound/current-state sys) fact)]
             (if-not f
               (throw (ex-info (str "There is no fact " fact ".") {}))
               (str "Fact " fact (when (:supersedes f) (str ", which replaced fact " (:supersedes f))) "\n"
                    (str/join "\n" (map #(str "- " (fmt-version config %)) (:versions f)))))))}

   "fact_add"
   {:class :write
    :description (str "Record a fact: one thing that is true in the world, in a sentence or two that"
                      " stand on their own. Give `valid_from` when you know when it started to hold,"
                      " and `valid_to` when you know it will stop. A topic groups facts, such as"
                      " household/car.")
    :parameters (obj {:text text :topic text :valid_from world-time :valid_to world-time} "text")
    :run (fn [sys actor args]
           (str "Recorded fact " (new-fact (submit sys actor :fact/add (fact-args args))) "."))}

   "fact_supersede"
   {:class :write
    :description (str "The world changed: fact `fact` stopped holding at `valid_from` (default now)"
                      " and `text` holds instead. The old fact is kept, ending there. Use this for"
                      " a change, and fact_correct for a mistake.")
    :parameters (obj {:fact fact-id :text text :valid_from world-time :valid_to world-time :topic text}
                     "fact" "text")
    :run (fn [sys actor args]
           (str "Fact " (:fact args) " is superseded by fact "
                (new-fact (submit sys actor :fact/supersede (fact-args args))) "."))}

   "fact_correct"
   {:class :write
    :description (str "The record was wrong: fix a fact's text, topic or times, saying why. Give only"
                      " what changes; `open` for a time removes that bound. With `retract`, the fact"
                      " was never true, and a change it recorded is undone. The earlier record is kept"
                      " and still answers for what relay knew before.")
    :parameters (obj {:fact fact-id :why text :text text :topic text :valid_from world-time
                      :valid_to world-time :retract flag}
                     "fact" "why")
    :run (fn [sys actor args]
           (submit sys actor :fact/correct (fact-args args))
           (str (if (:retract args) "Retracted" "Corrected") " fact " (:fact args) "."))}

   "note_write"
   {:class :write
    :description (str "Write one of your notes: your memory between wakes. Your notes are shown to you"
                      " at the start of every wake, so keep them short and current. Writing a note"
                      " replaces it.")
    :parameters (obj {:name {:type "string" :description "Lower case letters, digits and hyphens."}
                      :text text}
                     "name" "text")
    :run (fn [sys actor args]
           (submit sys actor :note/write (select-keys args [:owner :name :text]))
           (str "Note " (:name args) " is written."))}

   "note_remove"
   {:class :write :acp-kind "delete"
    :description "Remove one of your notes once it no longer helps."
    :parameters (obj {:name text} "name")
    :run (fn [sys actor args]
           (submit sys actor :note/remove (select-keys args [:owner :name]))
           (str "Removed note " (:name args) "."))}

   "journal"
   {:class :read
    :description "Read your latest journal entries, oldest first."
    :parameters (obj {:limit {:type "integer" :description "How many, 10 by default."}})
    :run (fn [{:keys [config] :as sys} actor args]
           (let [entries (core/journal (inbound/current-state sys) (own actor args) (or (:limit args) 10))]
             (if (empty? entries)
               "The journal is empty."
               (str/join "\n\n" (for [{:keys [at text]} entries]
                                   (str (local-time config at) "\n" text))))))}

   "journal_write"
   {:class :write
    :description "Add an entry to your journal: what you did and learned, for later. Entries are never changed."
    :parameters (obj {:text text} "text")
    :run (fn [sys actor args]
           (submit sys actor :journal/write (select-keys args [:owner :text]))
           "Written.")}

   "docs"
   {:class :read
    :description "List the docs: longer shared content that is really a file, by path."
    :parameters (obj {})
    :run (fn [{:keys [config] :as sys} _ _]
           (let [ds (core/docs (inbound/current-state sys) nil)]
             (if (empty? ds)
               "There are no docs."
               (str/join "\n" (for [{:keys [path at actor content]} ds]
                                (str path " (" (count content) " characters, last written "
                                     (local-time config at) " by " actor ")"))))))}

   "doc_get"
   {:class :read
    :description "Read a doc, the latest version unless you give one from its history."
    :parameters (obj {:path text :version {:type "integer"}} "path")
    :run (fn [sys _ {:keys [path version]}]
           (let [v (core/doc-version (inbound/current-state sys) path {:version version})]
             (cond (nil? v) (throw (ex-info (str "There is no doc at " path
                                                 (when version (str " with version " version)) ".") {}))
                   (:removed v) (throw (ex-info (str path " was removed in version " (:seq v) ".") {}))
                   :else (:content v))))}

   "doc_history"
   {:class :read
    :description "The versions of a doc: when each was written and by whom."
    :parameters (obj {:path text} "path")
    :run (fn [{:keys [config] :as sys} _ {:keys [path]}]
           (let [vs (get-in (inbound/current-state sys) [:docs path :versions])]
             (if (empty? vs)
               (throw (ex-info (str "There is no doc at " path ".") {}))
               (str/join "\n" (for [{:keys [seq at actor content removed]} vs]
                                (str "version " seq ", " (local-time config at) " by " actor
                                     (if removed ": removed" (str ", " (count content) " characters"))))))))}

   "doc_put"
   {:class :write
    :description "Write a doc, replacing its content. Every version is kept. Paths are like household/insurance.md."
    :parameters (obj {:path text :content text} "path" "content")
    :run (fn [sys actor args]
           (if-let [v (:seq (first (submit sys actor :doc/put (select-keys args [:path :content]))))]
             (str "Wrote " (:path args) ", version " v ".")
             (str (:path args) " already says that.")))}

   "doc_remove"
   {:class :write :acp-kind "delete"
    :description "Remove a doc. Its history is kept."
    :parameters (obj {:path text} "path")
    :run (fn [sys actor args]
           (submit sys actor :doc/remove (select-keys args [:path]))
           (str "Removed " (:path args) "."))}

   "role_add"
   {:class :write
    :grant :may-add
    :description "Add a role. Its instructions say what it is for, what it may decide alone, and when to ask. What it may use is granted by the operator, not written here."
    :parameters (obj {:id text :title text :instructions text} "id" "title" "instructions")
    :run (fn [sys actor args]
           (submit sys actor :role/add args)
           (str "Added " (:id args) "."))}

   "role_update"
   {:class :write
    :grant :may-add
    :description "Change another role's title or instructions."
    :parameters (obj {:id text :title text :instructions text} "id")
    :run (fn [sys actor args]
           (submit sys actor :role/update args)
           (str "Updated " (:id args) "."))}

   "role_remove"
   {:class :write :acp-kind "delete"
    :grant :may-add
    :description "Remove a role. Its open memos are dead-lettered and its routines stop."
    :parameters (obj {:id text} "id")
    :run (fn [sys actor args]
           (submit sys actor :role/remove args)
           (str "Removed " (:id args) "."))}

   "dead_letters_retry"
   {:class :write
    :grant :dead-letter-handler
    :description "Put a role's dead-lettered memos back in its inbox with a clean slate, once whatever stopped it is fixed."
    :parameters (obj {:role text} "role")
    :run (fn [sys actor args]
           (str "Requeued " (count (submit sys actor :memo/retry args)) " memo(s)."))}})

(defn- external-tool
  "A tool the operator defined in relay.edn: an external command, given the
  call's `args` after its own."
  [[tool-name {:keys [class description command]}]]
  [tool-name
   {:class class
    :description description
    :parameters (obj {:args {:type "array" :items {:type "string"}
                             :description "Command line arguments."}})
    :run (fn [_ _ {:keys [args]}]
           ;; A clean environment: nothing the harness or relay was started
           ;; with, such as credentials, reaches an external command.
           (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true :in ""
                                                        :env (harness/base-env {})}
                                               (into (vec command) (map str args)))]
             (if (zero? exit)
               out
               (throw (ex-info (str tool-name " failed (exit " exit "): " err out) {})))))}])

(defn offered
  "The tools a role is offered, by name: relay's own, less those that need a
  grant it lacks, plus the external tools it is granted."
  [config role-id]
  (let [{:keys [tools]} (config/grants config role-id)
        holds? (fn [grant]
                 (case grant
                   nil true
                   :may-add (config/may-add? config role-id)
                   :dead-letter-handler (or (= role-id (:dead-letter-handler config))
                                            (config/may-add? config role-id))))]
    (merge (into {} (filter #(holds? (:grant (val %))) builtin))
           (into {} (map external-tool (select-keys (:tools config) tools))))))

(defn grants-summary
  "What a role is granted, in a few sentences for its prompt, so it knows
  what it can and cannot do without trying."
  [config role-id]
  (let [offered (offered config role-id)
        external (sort (keys (select-keys (:tools config) (:tools (config/grants config role-id)))))]
    (str/join "\n"
              (cond-> []
                (contains? offered "role_add")
                (conj "You may add, change and remove other roles, with `role_add`, `role_update` and `role_remove`.")
                (= role-id (:dead-letter-handler config))
                (conj "Work that other roles could not finish comes to you as dead letters.")
                (contains? offered "dead_letters_retry")
                (conj "You can put a role's dead letters back in its inbox with `dead_letters_retry`.")
                :always
                (conj (if (seq external)
                        (str "Beyond relay's own tools, the operator has granted you: "
                             (str/join ", " (map #(str "`" % "`") external)) ".")
                        "You have relay's own tools and nothing beyond them."))
                :always
                (conj (str "Only the operator grants tools and powers. If your work needs one you lack,"
                           " ask whoever you report to, or the operator if you report to nobody."))))))

(defn manifest
  "What a harness needs to present the tools: names, descriptions and
  parameter schemas, each tool's class, and for some of relay's own tools
  an :acp-kind more exact than the class gives (relay.acp/tool-kinds)."
  [config role-id]
  (vec (for [[n {:keys [class description parameters acp-kind]}] (sort-by key (offered config role-id))]
         (cond-> {:name n :class (name class) :description description :parameters parameters}
           acp-kind (assoc :acp-kind acp-kind)))))

(defn call!
  "Runs one tool call for a role and returns the text to give back to it.
  Refusals come back as text too, so the role can read why and adjust."
  [sys role-id tool-name args]
  (let [tool (get (offered (:config sys) role-id) tool-name)]
    (cond
      (not (core/active-role? (inbound/current-state sys) role-id))
      {:error (str role-id " is not an active role.")}
      (nil? tool)
      {:error (str "You have no tool called " tool-name ".")}
      :else
      (let [args (reduce (fn [args k]
                           ;; A model sometimes quotes a number.
                           (cond-> args
                             (string? (get args k)) (update k #(or (parse-long (str/trim %)) %))))
                         (or args {}) [:memo :fact :version :limit])]
        (try {:ok ((:run tool) sys role-id args)}
             (catch Exception e
               {:error (or (ex-message e) (str (class e)))}))))))
