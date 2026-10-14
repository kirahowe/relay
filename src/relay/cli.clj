(ns relay.cli
  "The relay command."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [relay.config :as config]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.export :as export]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.tools :as tools])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)))

(def usage
  "relay: a relay of agent roles, with an append-only log as the record.

Every command needs the data directory, from --data <dir> or RELAY_DATA.

  relay init                          make the directory a relay data directory
  relay status                        roles, open work, harnesses, recent wakes
  relay inbox                         memos waiting for the operator
  relay memo <to> -s <subject>        send a memo; body from -m or stdin
  relay close <memo> -m <outcome>     close a memo
  relay roles                         the registry
  relay add <id> [--title <t>] [--reports-to <id>] [--harness <h>] [--model <m>]
                                      add a role; its instructions on stdin
  relay update <id> [--title <t>] [--harness <h>] [--model <m>] [--instructions]
                                      change a role; --instructions reads stdin
  relay remove <id>                   remove a role
  relay routines [<role>]             list routines
  relay routine <role> <name> --schedule <s>
                                      write a routine; instructions on stdin
  relay unroutine <role> <name>       remove a routine
  relay retry <role>                  requeue a role's dead-lettered memos
  relay wake <role> [--note <text>]   run a role now, ignoring retry delays
  relay chat <role>                   talk with a role in this terminal
  relay tick                          one dispatcher pass (launchd runs this)
  relay facts [<topic>] [--search <s>] [--as-of <t>] [--known-at <t>] [--all]
                                      what relay knows; times as for facts
  relay fact <id>                     every version of a fact
  relay notes <role>                  a role's notes
  relay journal <role> [-n <count>]   a role's latest journal entries
  relay docs                          the docs
  relay doc <path> [--version <n>] [--history]
                                      read a doc, or list its versions
  relay put <path>                    write a doc from stdin
  relay export <dir>                  write a read-only snapshot of the knowledge
  relay log [--tail <n>] [--json]     the events, newest last
  relay tool <name>                   run a role's tool call (harnesses use this)")

(defn- fail! [& msg]
  (throw (ex-info (str/join " " msg) {:relay/usage true})))

(defn- data-dir [opts]
  (or (:data opts) (System/getenv "RELAY_DATA")
      (fail! "Which data directory? Pass --data <dir> or set RELAY_DATA.")))

(defn- caller
  "Who is running this command. A harness sets RELAY_ROLE for every tool call
  it makes, and a command run with it set acts as that role and nothing
  more. Anything else is the operator."
  []
  (let [r (System/getenv "RELAY_ROLE")]
    (if (str/blank? r) core/operator r)))

(defn- operator-only! [what]
  (when (not= core/operator (caller))
    (fail! (str "Only the operator can " what "."))))

(defn- stdin-text []
  (if (System/console) "" (slurp *in*)))

(defn- local [^Instant at zone]
  (when at (.format (.atZone at ^ZoneId zone) (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))))

(defn- submit! [sys type args]
  (inbound/submit! sys {:type type :actor (caller) :args args}))

(defn- print-memo [zone {:keys [id from subject body sent-at]}]
  (println (str "\n[" id "] " subject))
  (println (str "    from " from ", " (local sent-at zone)))
  (println)
  (println (str/replace (str body) #"(?m)^" "    ")))

(defn- status [{:keys [config] :as sys}]
  (let [state (inbound/current-state sys)
        zone (config/zone config)
        now (Instant/now)]
    (println "Roles")
    (doseq [id (core/active-role-ids state)
            :let [{:keys [title reports-to]} (core/role state id)]]
      (println (format "  %-20s %s%s%s" id title
                       (if reports-to (str ", reports to " reports-to) "")
                       (if (core/running? state id) "  [running]" "")))
      (doseq [{:keys [name schedule]} (core/active-routines state id)]
        (println (format "    routine %-18s %s" name schedule)))
      (doseq [{:keys [id subject attempts retry-at]} (core/unread-memos state id)]
        (println (format "    open  [%d] %s%s" id subject
                         (cond-> ""
                           (pos? attempts) (str " (" attempts " failed attempts")
                           (and (pos? attempts) retry-at) (str ", next " (local retry-at zone))
                           (pos? attempts) (str ")")))))
      (doseq [{memo-id :id :keys [subject]} (core/dead-memos state id)]
        (println (format "    dead  [%d] %s (relay retry %s)" memo-id subject id))))
    (doseq [[h {:keys [condition paused-until]}] (sort-by key (:harnesses state))]
      (println (str "\nHarness " h ": "
                    (if (and paused-until (.isAfter ^Instant paused-until now))
                      (str condition ", next try " (local paused-until zone))
                      "available"))))
    (let [waiting (core/unread-memos state core/operator)]
      (println (format "\nOperator's inbox: %d open" (count waiting))))
    (when-let [recent (seq (take-last 5 (sort-by :id (vals (:wakes state)))))]
      (println "\nRecent wakes")
      (doseq [{:keys [id role started-at status outcome crashed]} recent]
        (println (format "  %-6d %s  %-20s %s" id (local started-at zone) role
                         (if (= :running status) "running"
                             (str outcome (when crashed ", crashed")))))))))

(defn- show-log [sys {:keys [tail json]}]
  (let [events (log/read-events (:data-dir sys))
        events (if tail (take-last tail events) events)]
    (doseq [e events]
      (println (if json
                 (json/generate-string e)
                 (format "%5d %s %-22s %-12s %s" (:seq e) (:at e) (:type e) (:actor e)
                         (let [s (pr-str (:data e))] (subs s 0 (min 120 (count s))))))))))

(defn- run-tool
  "A harness runs this for each tool call: JSON arguments on stdin, the
  result as text on stdout. A refused call exits 1 with the reason, which
  the harness hands back to the model."
  [sys tool-name]
  (let [role (System/getenv "RELAY_ROLE")
        _ (when (str/blank? role) (fail! "relay tool runs only inside a wake, with RELAY_ROLE set."))
        raw (stdin-text)
        args (try (if (str/blank? raw) {} (json/parse-string raw true))
                  (catch Exception _ ::bad))]
    (if (= ::bad args)
      (do (println "The arguments were not valid JSON.") 1)
      (let [{:keys [ok error]} (tools/call! sys role tool-name args)]
        (println (or ok error))
        (if error 1 0)))))

(defn- tool-output
  "Runs one of relay's own read tools as the operator and prints its text."
  [sys tool-name args]
  (println ((get-in tools/builtin [tool-name :run]) sys core/operator args))
  0)

(def ^:private spec
  {:alias {:s :subject :m :message :n :tail}
   :coerce {:subject :string :message :string :title :string :reports-to :string
            :harness :string :model :string :schedule :string :note :string
            :tail :long :data :string :search :string :as-of :string :known-at :string
            :version :long}})

(defn run
  "Runs one command and returns the exit code."
  [args]
  ;; Options may come before the command (--data) and after it, and
  ;; babashka.cli stops at the first argument, so parse in two passes.
  (let [{global :opts rest-args :args} (cli/parse-args args spec)
        {[cmd & more] :args local-opts :opts} (cli/parse-args rest-args spec)
        opts (merge global local-opts)]
    (if (or (nil? cmd) (#{"help" "-h" "--help"} cmd))
      (do (println usage) 0)
      (let [dir (data-dir opts)]
        (if (= "init" cmd)
          (do (operator-only! "initialize a data directory")
              (log/init! dir)
              (println "relay data directory ready at" (str (fs/absolutize dir)))
              0)
          (let [sys (inbound/system dir)
                zone (config/zone (:config sys))]
            (case cmd
              "status" (do (status sys) 0)
              "inbox" (let [memos (core/unread-memos (inbound/current-state sys) (caller))]
                        (if (empty? memos)
                          (println "Nothing waiting.")
                          (doseq [m memos] (print-memo zone m)))
                        0)
              "memo" (let [[to] more
                           body (or (:message opts) (stdin-text))]
                       (when-not to (fail! "usage: relay memo <to> -s <subject> [-m <body>]"))
                       (let [events (submit! sys :memo/send {:to to :subject (:subject opts) :body body})]
                         (println "Sent memo" (:seq (first events)))
                         0))
              "close" (let [[id] more]
                        (submit! sys :memo/close {:memo (some-> id parse-long) :outcome (:message opts)})
                        (println "Closed memo" id)
                        0)
              "roles" (do (doseq [id (core/active-role-ids (inbound/current-state sys))
                                  :let [{:keys [title reports-to]} (core/role (inbound/current-state sys) id)]]
                            (println (str id ": " title (when reports-to (str ", reports to " reports-to)))))
                          0)
              "add" (let [[id] more]
                      (submit! sys :role/add {:id id :title (:title opts) :reports-to (:reports-to opts)
                                              :harness (:harness opts) :model (:model opts)
                                              :instructions (stdin-text)})
                      (println "Added" id)
                      0)
              "update" (let [[id] more]
                         (submit! sys :role/update
                                  (cond-> (select-keys opts [:title :harness :model :reports-to])
                                    true (assoc :id id)
                                    (:instructions opts) (assoc :instructions (stdin-text))))
                         (println "Updated" id)
                         0)
              "remove" (let [[id] more]
                         (submit! sys :role/remove {:id id})
                         (println "Removed" id)
                         0)
              "routines" (let [state (inbound/current-state sys)]
                           (doseq [owner (if (first more) [(first more)] (core/active-role-ids state))
                                   {:keys [name schedule baseline]} (core/active-routines state owner)]
                             (println (format "%-16s %-18s %s (since %s)" owner name schedule
                                              (local baseline zone))))
                           0)
              "routine" (let [[owner rname] more]
                          (submit! sys :routine/write {:owner owner :name rname :schedule (:schedule opts)
                                                       :instructions (stdin-text)})
                          (println "Routine" rname "is set for" owner)
                          0)
              "unroutine" (let [[owner rname] more]
                            (submit! sys :routine/remove {:owner owner :name rname})
                            (println "Removed routine" rname)
                            0)
              "retry" (let [[r] more]
                        (println (count (submit! sys :memo/retry {:role r})) "memo(s) requeued")
                        0)
              "wake" (let [[r] more
                           _ (operator-only! "wake a role")
                           result (dispatch/wake! sys r {:note (:note opts)
                                                         :dispatched (:dispatched opts)})]
                       (println (name (:result result)) (pr-str (dissoc result :result)))
                       (if (#{:ran :idle} (:result result)) 0 1))
              "chat" (let [[r] more]
                       (operator-only! "open a conversation")
                       (when-not r (fail! "usage: relay chat <role>"))
                       (or (dispatch/chat! sys r) 0))
              "tick" (do (operator-only! "run the dispatcher")
                         (dispatch/tick! sys)
                         0)
              "facts" (tool-output sys "facts" {:topic (first more) :search (:search opts)
                                                :as_of (:as-of opts) :known_at (:known-at opts)
                                                :all (:all opts)})
              "fact" (tool-output sys "fact_history" {:fact (some-> (first more) parse-long)})
              "notes" (let [[r] more]
                        (when-not r (fail! "usage: relay notes <role>"))
                        (doseq [{:keys [name text written-at]} (core/notes (inbound/current-state sys) r)]
                          (println (str "## " name "  (" (local written-at zone) ")\n\n" text "\n")))
                        0)
              "journal" (let [[r] more]
                          (when-not r (fail! "usage: relay journal <role> [-n <count>]"))
                          (tool-output sys "journal" {:owner r :limit (:tail opts)}))
              "docs" (tool-output sys "docs" {})
              "doc" (let [[path] more]
                      (when-not path (fail! "usage: relay doc <path> [--version <n>] [--history]"))
                      (if (:history opts)
                        (tool-output sys "doc_history" {:path path})
                        (tool-output sys "doc_get" {:path path :version (:version opts)})))
              "put" (let [[path] more
                          events (submit! sys :doc/put {:path path :content (stdin-text)})]
                      (println (if (seq events) (str "Wrote " path ", version " (:seq (first events)))
                                   (str path " already says that.")))
                      0)
              "export" (let [[dir] more]
                         (operator-only! "export")
                         (when-not dir (fail! "usage: relay export <dir>"))
                         (println "Wrote" (export/export! sys dir) "files to" dir)
                         0)
              "log" (do (show-log sys opts) 0)
              "tool" (run-tool sys (first more))
              (do (println usage) 1))))))))

(defn -main [& args]
  (let [code (try (run args)
                  (catch clojure.lang.ExceptionInfo e
                    (binding [*out* *err*] (println (ex-message e)))
                    (if (or (core/rejection e) (:relay/usage (ex-data e))) 1 2)))]
    (System/exit code)))
