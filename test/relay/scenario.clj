(ns relay.scenario
  "The pure half of the scenario runner (see relay.scenario-runner): reading
  a scenario, matching assertions against what happened, and choosing the
  scripted model's next reply. Nothing here touches a file or a process.

  A scenario is an EDN map:

    {:doc    \"what it shows\"
     :live?  true                    ; may also run against the real model (default false)
     :model  {\"hub\" [reply ...]}     ; scripted replies, mock mode only
     :steps  [step ...]
     :assert [assertion ...]}        ; checked after the last step

  Steps, run in order:

    {:run [\"memo\" \"hub\" \"-s\" \"Subject\" \"-m\" \"Body\"]  ; a relay command, as the operator
     :stdin \"text\"                                         ; optional
     :exit 0}                                               ; expected exit code, default 0
    {:tick 3}                  ; relay's dispatcher pass, three times; wakes run to the end
    {:tick 1 :spawn :process}  ; the same, but each wake is started the way relay tick
                               ; starts it, as a `relay wake` subprocess, and waited for
    {:advance-minutes 2}       ; move relay's clock forward
    {:assert [assertion ...]}  ; check the state so far

  A :run step may not name a data directory, pause file or trace file
  (--data, --pause-file, --trace in any form), and may
  only use the commands in `allowed-commands`: every command is run against
  the scenario's own staging directory, and none that writes to a path it is
  given (export, acp --trace) or starts a process of its own (init, chat) is
  among them.

  A run argument may be {:memo-id pattern}, replaced by the id of the first
  memo that matches.

  Each reply in :model is the model's answer to one request from that role:

    {:text \"...\"}
    {:tool-uses [{:name \"memo_close\" :input {:memo {:memo-id {:subject \"X\"}} :outcome \"Done\"}}]}

  A role's replies are used in order, and once they run out it answers
  {:text \"Done.\"}. {:memo-id pattern} works in tool inputs too.

  An assertion is [:has kind pattern], [:none kind pattern] or
  [:count kind pattern n]. Kind is one of :memo :hold :wake :role :routine
  :dead-letter :notification (relay's records of one), :event (the log's
  events, as JSON read back) or :sent (what the file notifier received). A
  pattern is a map that must be contained in the item, recursively. Its
  values are compared with =, or are one of

    [:includes \"text\"]   a string containing text
    [:regex \"re\"]        a string matching re
    [:not p] [:any p ...] [:all p ...]"
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

;; --- matching

(defn match?
  "Whether `value` fits `pattern`."
  [pattern value]
  (cond
    (map? pattern)
    (and (map? value) (every? (fn [[k p]] (match? p (get value k))) pattern))

    (and (vector? pattern) (#{:includes :regex :not :any :all} (first pattern)))
    (let [[op & args] pattern]
      (case op
        :includes (and (string? value) (str/includes? value (first args)))
        :regex (and (string? value) (boolean (re-find (re-pattern (first args)) value)))
        :not (not (match? (first args) value))
        :any (boolean (some #(match? % value) args))
        :all (every? #(match? % value) args)))

    :else (= pattern value)))

(def kinds #{:memo :hold :wake :role :routine :dead-letter :notification :event :sent})

(defn items
  "The things of one kind in a world: {:state :events :sent}. Sorted so that
  failures read the same every time."
  [{:keys [state events sent]} kind]
  (case kind
    :memo (sort-by :id (vals (:memos state)))
    :hold (sort-by :id (vals (:holds state)))
    :wake (sort-by :id (vals (:wakes state)))
    :role (sort-by :id (vals (:roles state)))
    :routine (sort-by (juxt :owner :name) (mapcat vals (vals (:routines state))))
    :dead-letter (sort-by :id (vals (:dead-letters state)))
    :notification (sort-by :id (vals (:notifications state)))
    :event events
    :sent sent))

(defn- brief [x]
  (let [s (pr-str x)]
    (if (> (count s) 240) (str (subs s 0 240) "...") s)))

(defn check
  "Checks one assertion against a world. Returns nil when it holds, else a
  line saying what was expected and what there was."
  [world [op kind pattern n :as assertion]]
  (if-not (and (#{:has :none :count} op) (kinds kind))
    (str "Unknown assertion " (pr-str assertion) ".")
    (let [all (items world kind)
          hits (filter #(match? pattern %) all)
          shown (fn [] (str (count all) " " (name kind) "(s)"
                            (when (seq all) (str ": " (str/join "; " (map brief (take 5 all)))))))]
      (case op
        :has (when (empty? hits)
               (str "Expected a " (name kind) " matching " (pr-str pattern) ", found " (shown) "."))
        :none (when (seq hits)
                (str "Expected no " (name kind) " matching " (pr-str pattern) ", found " (brief (first hits)) "."))
        :count (when (not= n (count hits))
                 (str "Expected " n " " (name kind) "(s) matching " (pr-str pattern) ", found " (count hits) "."))))))

(defn check-all [world assertions]
  (vec (keep #(check world %) assertions)))

;; --- references to things whose ids are not known until the run

(defn memo-id
  "The id of the first memo matching `pattern`, or nil."
  [state pattern]
  (:id (first (filter #(match? pattern %) (sort-by :id (vals (:memos state)))))))

(defn resolve-refs
  "`x` with each {:memo-id pattern} replaced by that memo's id. Throws when
  no memo matches."
  [state x]
  (walk/postwalk
   (fn [form]
     (if (and (map? form) (= [:memo-id] (keys form)))
       (or (memo-id state (:memo-id form))
           (throw (ex-info (str "No memo matches " (pr-str (:memo-id form)) ".") {:pattern (:memo-id form)})))
       form))
   x))

;; --- the scripted model

(def done {:text "Done."})

(defn role-of
  "The role a model request is for, from the system prompt relay sends."
  [body]
  (let [system (if (string? (:system body))
                 (:system body)
                 (str/join "\n" (keep :text (:system body))))]
    (second (re-find #"Your id is `([^`]+)`" system))))

(defn next-reply
  "The reply for the next request from `role`, and the counts after it.
  Pure: `used` is how many replies each role has been given."
  [script used role]
  (let [n (get used role 0)
        replies (get script role [])]
    [(if (< n (count replies)) (nth replies n) done)
     (assoc used role (inc n))]))

(def allowed-commands
  "The relay commands a scenario may run. Everything here changes or reads
  only the data directory."
  #{"status" "inbox" "memo" "close" "roles" "add" "update" "remove" "routines" "routine" "unroutine"
    "retry" "wake" "facts" "fact" "notes" "journal" "docs" "doc" "put" "log" "holds"
    "approve" "deny" "release" "trust"})

(defn path-option?
  "Whether a command-line argument picks a path: the data directory, the
  pause file or a trace file. A scenario may not, since each of them would
  point a command at something outside its staging directory."
  [arg]
  ;; The name alone, whatever follows "=": a value may hold a newline. This
  ;; is a first screen; relay.scenario-runner also asks relay which options
  ;; the arguments really set, since babashka.cli reads more spellings
  ;; (":name value") than any pattern here.
  (boolean (re-find #"^[-:]{1,2}(no-)?(data|pause-file|trace)(=|$)" (str arg))))

(def path-option-keys #{:data :pause-file :trace})

(defn problems
  "What is wrong with a scenario's shape, as a vector of messages."
  [{:keys [steps assert model] :as scenario}]
  (let [step-problem (fn [step]
                       (let [ks (set (when (map? step) (keys step)))]
                         (cond
                           (not (map? step)) (str "Step " (pr-str step) " is not a map.")
                           (not= 1 (count (filter ks [:run :tick :advance-minutes :assert])))
                           (str "Step " (brief step) " needs exactly one of :run, :tick, :advance-minutes, :assert.")
                           (and (:run step) (not (and (vector? (:run step)) (seq (:run step)))))
                           (str "Step " (brief step) " needs :run to be a vector of arguments.")
                           (and (:run step) (some path-option? (:run step)))
                           (str "Step " (brief step) " names a data directory or another path option. Scenarios run only against their own.")
                           (and (:run step) (not (allowed-commands (first (:run step)))))
                           (str "Step " (brief step) " must start with one of the commands "
                                (str/join ", " (sort allowed-commands)) ".")
                           (and (:tick step) (not (pos-int? (:tick step)))) (str "Step " (brief step) " needs a positive :tick.")
                           (and (:spawn step) (not (and (:tick step) (= :process (:spawn step)))))
                           (str "Step " (brief step) " may give :spawn :process only with :tick.")
                           (and (:advance-minutes step) (not (pos-int? (:advance-minutes step))))
                           (str "Step " (brief step) " needs a positive :advance-minutes."))))
        assertion-problem (fn [[op kind :as a]]
                            (when-not (and (vector? a) (#{:has :none :count} op) (kinds kind))
                              (str "Assertion " (brief a) " is not [:has|:none|:count kind pattern ...].")))
        all-assertions (concat assert (mapcat :assert steps))]
    (vec (concat (when-not (and (vector? steps) (seq steps)) ["A scenario needs :steps."])
                 (when-not (seq assert) ["A scenario needs :assert."])
                 (when-not (or (nil? model) (and (map? model) (every? vector? (vals model))))
                   [":model maps a role id to a vector of replies."])
                 (keep step-problem steps)
                 (keep assertion-problem all-assertions)))))
