(ns relay.tools
  "The tools a role works with. A role has no file or shell access of its
  own: everything it does is a call to one of these, by name, and every call
  that changes anything goes through the inbound path as that role.

  Each tool has a class (see relay.config/tool-classes). relay's own tools
  are defined here. The operator can grant further tools in relay.edn, each
  one an external command given the call's arguments on its command line.
  Which tools a role is offered depends on its grants, and a call to a tool
  the role was not offered is refused, whatever the harness let through."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [relay.config :as config]
            [relay.core :as core]
            [relay.inbound :as inbound]))

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
   {:class :write
    :description "Remove one of your routines."
    :parameters (obj {:name text} "name")
    :run (fn [sys actor args]
           (submit sys actor :routine/remove args)
           (str "Removed routine " (:name args) "."))}

   "role_add"
   {:class :write
    :grant :may-add
    :description "Add a role. The config is its standing instructions: what it is for, what it may decide alone, and when to ask."
    :parameters (obj {:id text :title text :config text} "id" "title" "config")
    :run (fn [sys actor args]
           (submit sys actor :role/add args)
           (str "Added " (:id args) "."))}

   "role_update"
   {:class :write
    :grant :may-add
    :description "Change another role's title or config."
    :parameters (obj {:id text :title text :config text} "id")
    :run (fn [sys actor args]
           (submit sys actor :role/update args)
           (str "Updated " (:id args) "."))}

   "role_remove"
   {:class :write
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
                                                        :env (select-keys (into {} (System/getenv))
                                                                          ["PATH" "HOME" "USER" "LANG" "TMPDIR"])}
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

(defn manifest
  "What a harness needs to present the tools: names, descriptions and
  parameter schemas."
  [config role-id]
  (vec (for [[n {:keys [class description parameters]}] (sort-by key (offered config role-id))]
         {:name n :class (name class) :description description :parameters parameters})))

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
      (let [args (cond-> (or args {})
                   ;; A model sometimes quotes a number.
                   (string? (:memo args)) (update :memo #(or (parse-long (str/trim %)) %)))]
        (try {:ok ((:run tool) sys role-id args)}
             (catch Exception e
               {:error (or (ex-message e) (str (class e)))}))))))
