(ns relay.scenario
  "The pure half of the scenario runner (see relay.scenario-runner): reading
  a scenario, matching assertions against what happened, and choosing the
  scripted model's next reply. Nothing here touches a file or a process.

  A scenario is an EDN map:

    {:doc    \"what it shows\"
     :live?  true                    ; may also run against the real model (default false)
     :model  {\"hub\" [reply ...]}     ; scripted replies, mock mode only
     :reader [reply ...]             ; the quarantined reader's, mock mode only
     :steps  [step ...]
     :assert [assertion ...]}        ; checked after the last step

  Steps, run in order:

    {:run [\"memo\" \"hub\" \"-s\" \"Subject\" \"-m\" \"Body\"]  ; a relay command, as the operator
     :stdin \"text\"                                         ; optional
     :exit 0}                                               ; expected exit code, default 0
    {:run [\"review\" \"1\"] :tty [\"a\" \"n\"]}  ; what the operator types at the terminal,
                                         ; in order, beyond the codes the runner types
    {:tick 3}                  ; relay's dispatcher pass, three times; wakes run to the end
    {:tick 1 :spawn :process}  ; the same, but each wake is started the way relay tick
                               ; starts it, as a `relay wake` subprocess, and waited for
    {:tick 1 :script {\"clerk\" [reply ...]}}
                               ; the same, but this tick's wakes get the step's
                               ; replies from a mock model, even in a live run:
                               ; a fixed first attempt that later live wakes
                               ; carry on from
    {:advance-minutes 2}       ; move relay's clock forward
    {:assert [assertion ...]}  ; check the state so far

  A :run step may not name a data directory, pause file or trace file
  (--data, --pause-file, --trace in any form), and may
  only use the commands in `allowed-commands`: every command is run against
  the scenario's own staging directory, and none that writes to a path it is
  given (export, acp --trace) or starts a process of its own (init, chat) is
  among them.

  A run argument may be {:memo-id pattern}, replaced by the id of the first
  memo that matches, or {:report-id pattern}, the same for reports.

  Each reply in :model is the model's answer to one request from that role:

    {:text \"...\"}
    {:tool-uses [{:name \"memo_close\" :input {:memo {:memo-id {:subject \"X\"}} :outcome \"Done\"}}]}

  A role's replies are used in order, and once they run out it answers
  {:text \"Done.\"}. {:memo-id pattern} works in tool inputs too.

  :reader is a vector of replies too, for agentloop's quarantined
  reader, whose requests name no role. Each is the reader's whole reply,
  such as {:text \"{\\\"answer\\\": true}\"}.

  An assertion is [:has kind pattern], [:none kind pattern] or
  [:count kind pattern n]. Kind is one of :memo :hold :wake :role :routine
  :dead-letter :notification (relay's records of one), :event (the log's
  events, as JSON read back) or :sent (what the file notifier received),
  and also:

    :fact        one per fact: its latest version, plus :current, :changes
                 (each version's change, such as [:added :corrected]), :why
                 (of the latest correction or retraction), :first-text (its first version's
                 text) and :supersedes-text (the first text of the fact it
                 replaced)
    :journal     one per entry: :id :owner :text :at :wake
    :note :doc   the current ones
    :tool-call   one per call in any wake's transcript: :role :wake :n (its
                 place in the run) :name :input :verdict (agentloop's: allow,
                 deny or hold) :error :result. Held calls count.
    :report      one per research report, as relay keeps it, with its
                 :verification once checked
    :ledger      one per ledger entry

  A :wake also has :requests, :input-tokens, :output-tokens,
  :cache-read-tokens, :cache-write-tokens, :cost-usd, :models (the models
  that served it), :end-reason (agentloop's), :tool-calls, :tool-errors and
  :journal-at-end (its last journal_write came after its last memo_close
  or memo_block). A :memo also has :resolution (closed, blocked,
  dead-lettered, retry or open) and :recipient-woke (a wake of its
  recipient was given it).

  A pattern is a map that must be contained in the item, recursively. Its
  values are compared with =, or are one of

    [:includes \"text\"]   a string containing text
    [:regex \"re\"]        a string matching re
    [:not p] [:any p ...] [:all p ...]
    [:on-date \"2026-11-03\" ...]  an instant on one of these days, in the
                                  staging time zone
    [:between from to]   a number, or an instant, in a closed range; dates
                         as bounds compare the instant's local date
    [:min-length n]      a string of at least n characters, trimmed
    [:subset s] [:same-set s]  a collection within, or equal to, s as sets
    [:contains p]        a collection with an element matching p

  Eval cases (relay.eval) add keys a scenario run reads too:

    :clock    \"2026-10-12T09:00\", relay's clock at the start, in the staging
              time zone; without it the clock starts at the real time
    :settings {:grants {\"clerk\" {:tools [\"fail-demo\"]}}}, merged into the
              staging relay.edn: only :grants, and in them only :tools,
              naming tools relay.staging defines, :quarantine, naming
              stand-in tools the role reads through read_quarantined
              (relay checks they are :read and in its :tools), and
              :reports {:kinds [...]}, which relay accepts only for a public role such as
              the scout. A role's :tools, :quarantine and :reports replace its staging
              ones."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [relay.config :as config]
            [relay.mock-anthropic :as mock]
            [relay.core :as core]
            [relay.staging :as staging]
            [relay.transcript :as transcript])
  (:import (java.time Instant LocalDate LocalDateTime ZoneId)))

;; --- matching

(def zone
  "The staging time zone, which :on-date and :clock are read in."
  (ZoneId/of staging/timezone))

(defn- instant [v]
  (cond (instance? Instant v) v
        (string? v) (try (Instant/parse v) (catch Exception _ nil))))

(defn- local-date [v] (some-> (instant v) (.atZone ^ZoneId zone) .toLocalDate))

(defn- date-string? [s] (and (string? s) (re-matches #"\d{4}-\d{2}-\d{2}" s)))

(defn- within? [value from to]
  (cond
    (and (number? value) (number? from) (number? to)) (<= from value to)
    (and (date-string? from) (date-string? to))
    (when-let [d (local-date value)]
      (not (or (.isBefore d (LocalDate/parse from)) (.isAfter d (LocalDate/parse to)))))
    :else
    (let [v (instant value) f (instant from) t (instant to)]
      (boolean (and v f t (not (.isBefore v f)) (not (.isAfter v t)))))))

(def ops #{:includes :regex :not :any :all :on-date :between :min-length :subset :same-set :contains})

(defn match?
  "Whether `value` fits `pattern`."
  [pattern value]
  (cond
    (map? pattern)
    (and (map? value) (every? (fn [[k p]] (match? p (get value k))) pattern))

    (and (vector? pattern) (ops (first pattern)))
    (let [[op & args] pattern]
      (case op
        :includes (and (string? value) (str/includes? value (first args)))
        :regex (and (string? value) (boolean (re-find (re-pattern (first args)) value)))
        :not (not (match? (first args) value))
        :any (boolean (some #(match? % value) args))
        :all (every? #(match? % value) args)
        :on-date (let [d (some-> (local-date value) str)] (boolean (and d (some #{d} args))))
        :between (boolean (within? value (first args) (second args)))
        :min-length (and (string? value) (>= (count (str/trim value)) (first args)))
        :subset (and (coll? value) (let [s (set (first args))] (every? #(contains? s %) value)))
        :same-set (and (coll? value) (= (set value) (set (first args))))
        :contains (and (coll? value) (boolean (some #(match? (first args) %) value)))))

    :else (= pattern value)))

(def kinds #{:memo :hold :wake :role :routine :dead-letter :notification :event :sent
             :fact :journal :note :doc :tool-call :report :ledger})

;; --- the newer kinds, from the state and the wakes' transcripts

(defn- now-of [{:keys [now state]}] (or now (:last-at state)))

(defn fact-items
  "One item per fact (see the namespace doc)."
  [{:keys [state] :as world}]
  (let [^Instant now (now-of world)
        first-text (fn [id] (get-in state [:facts id :versions 0 :text]))]
    (vec (for [f (sort-by :id (vals (:facts state)))
               :let [latest (core/fact-as-known f nil)
                     ^Instant to (:valid-to latest)
                     corrections (filter #(#{:corrected :retracted} (:change %)) (:versions f))]]
           (assoc latest
                  :current (and (not (:retracted latest)) (nil? (:superseded-by latest))
                                (or (nil? to) (nil? now) (not (.isBefore to now))))
                  :changes (mapv :change (:versions f))
                  :why (:why (last corrections))
                  :first-text (first-text (:id f))
                  :supersedes-text (some-> (:supersedes f) first-text))))))

(defn- wakes [state] (sort-by :id (vals (:wakes state))))

(defn tool-call-items
  "One item per tool call, over every wake in order."
  [{:keys [state transcripts]}]
  (vec (map-indexed (fn [i call] (assoc call :n (inc i)))
                    (for [w (wakes state)
                          call (transcript/tool-calls (get transcripts (:id w)))]
                      (assoc call :role (:role w) :wake (:id w))))))

(defn- done? [call] (and (= "allow" (:verdict call)) (not (:error call))))

(defn journal-at-end?
  "Whether a wake's last journal_write that ran came after its last
  memo_close or memo_block that ran. Pure, over the wake's calls in order."
  [calls]
  (let [last-index (fn [names] (last (keep-indexed (fn [i c] (when (and (names (:name c)) (done? c)) i)) calls)))
        j (last-index #{"journal_write"})
        c (last-index #{"memo_close" "memo_block"})]
    (boolean (and j (or (nil? c) (> j c))))))

(defn- wake-item [transcripts w]
  (let [entries (get transcripts (:id w))
        {:keys [requests usage cost-usd models]} (transcript/run-usage entries)
        calls (transcript/tool-calls entries)]
    (assoc w
           :requests requests
           :input-tokens (:input usage) :output-tokens (:output usage)
           :cache-read-tokens (:cache-read usage) :cache-write-tokens (:cache-write usage)
           :cost-usd cost-usd :models models
           :end-reason (transcript/end-reason entries)
           :tool-calls (count calls)
           :tool-errors (count (filter :error calls))
           :journal-at-end (journal-at-end? calls))))

(defn- memo-item [state m]
  (let [blocked? (some #(and (= (:id m) (:memo %)) (= "blocked" (:reason %))) (vals (:dead-letters state)))]
    (assoc m
           :resolution (case (:status m)
                         :read "closed"
                         :dead (if blocked? "blocked" "dead-lettered")
                         (if (:retry-at m) "retry" "open"))
           ;; Woken for it: a wake of its recipient carried it. A memo that
           ;; arrived after the recipient's last wake started is not counted.
           :recipient-woke (boolean (some #(and (= (:to m) (:role %)) (some #{(:id m)} (:memos %)))
                                          (vals (:wakes state)))))))

(defn journal-items [{:keys [state]}]
  (let [ws (wakes state)]
    (vec (for [e (sort-by :id (mapcat val (:journals state)))]
           (assoc e :wake (:id (last (filter #(and (= (:owner e) (:role %)) (< (:id %) (:id e))) ws))))))))

(defn items
  "The things of one kind in a world: {:state :events :sent}, and for the
  newer kinds also :transcripts (wake id to its transcript's entries) and
  :now (relay's clock at the end). Sorted so that failures read the same
  every time."
  [{:keys [state events sent transcripts] :as world} kind]
  (case kind
    :memo (map #(memo-item state %) (sort-by :id (vals (:memos state))))
    :hold (sort-by :id (vals (:holds state)))
    :wake (map #(wake-item transcripts %) (wakes state))
    :fact (fact-items world)
    :journal (journal-items world)
    :note (sort-by (juxt :owner :name) (mapcat vals (vals (:notes state))))
    :doc (core/docs state nil)
    :tool-call (tool-call-items world)
    :role (sort-by :id (vals (:roles state)))
    :routine (sort-by (juxt :owner :name) (mapcat vals (vals (:routines state))))
    :dead-letter (sort-by :id (vals (:dead-letters state)))
    :notification (sort-by :id (vals (:notifications state)))
    :report (sort-by :id (vals (:reports state)))
    :ledger (sort-by :id (vals (:ledger state)))
    :event events
    :sent sent))

(defn- brief
  "x as one line of at most 240 characters, instants as their ISO text."
  [x]
  (let [s (pr-str (walk/postwalk #(if (instance? java.time.Instant %) (str %) %) x))]
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

(defn fact-id
  "The id of the first fact whose :fact item matches `pattern`, or nil."
  [state pattern]
  (:id (first (filter #(match? pattern %) (fact-items {:state state})))))

(defn report-id
  "The id of the first report matching `pattern`, or nil."
  [state pattern]
  (:id (first (filter #(match? pattern %) (sort-by :id (vals (:reports state)))))))

(defn resolve-refs
  "`x` with each {:memo-id pattern} replaced by that memo's id, each
  {:fact-id pattern} by that fact's and each {:report-id pattern} by that
  report's. Throws when nothing matches."
  [state x]
  (walk/postwalk
   (fn [form]
     (cond
       (and (map? form) (= [:memo-id] (keys form)))
       (or (memo-id state (:memo-id form))
           (throw (ex-info (str "No memo matches " (pr-str (:memo-id form)) ".") {:pattern (:memo-id form)})))
       (and (map? form) (= [:fact-id] (keys form)))
       (or (fact-id state (:fact-id form))
           (throw (ex-info (str "No fact matches " (pr-str (:fact-id form)) ".") {:pattern (:fact-id form)})))
       (and (map? form) (= [:report-id] (keys form)))
       (or (report-id state (:report-id form))
           (throw (ex-info (str "No report matches " (pr-str (:report-id form)) ".") {:pattern (:report-id form)})))
       :else form))
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

(def reader
  "The key the quarantined reader's replies are kept under in a script.
  The reader's requests name no role, and a keyword is never a role id."
  ::reader)

(defn script
  "A scenario's whole script: :model's replies by role, and :reader's
  under `reader`."
  [model reader-replies]
  (cond-> (or model {}) (seq reader-replies) (assoc reader (vec reader-replies))))

(defn requester
  "Whose turn a model request is: the role it is for, or `reader` for the
  quarantined reader's."
  [body]
  (if (mock/reader-request? body) reader (role-of body)))

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
    "approve" "deny" "release" "trust" "declassify" "review"})

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

;; --- :clock and :settings

(defn clock-instant
  "The instant a scenario's :clock names, or nil when it has none. Throws
  on one that is not a local date and time."
  [{:keys [clock]}]
  (when clock
    (.toInstant (.atZone (LocalDateTime/parse clock) ^ZoneId zone))))

(defn- kinds-ok? [kinds]
  (and (vector? kinds) (seq kinds) (apply distinct? kinds)
       (every? #(and (string? %) (re-matches config/kind-pattern %)) kinds)))

(defn settings-problems
  "What is wrong with a scenario's :settings."
  [settings]
  (let [allowed (set (keys staging/stand-in-tools))
        known (into allowed ["remind-demo"])]
    (cond
      (nil? settings) []
      (not (map? settings)) [":settings is a map."]
      (not= #{:grants} (set (keys settings))) [":settings may hold only :grants."]
      (not (map? (:grants settings))) [":settings :grants maps a role id to {:tools [...] :quarantine [...] :reports {:kinds [...]}}."]
      :else
      (vec (for [[role grant] (:grants settings)
                 problem [(when-not (string? role) (str "A role in :settings :grants is a string, not " (pr-str role) "."))
                          (when-not (and (map? grant) (seq grant) (every? #{:tools :reports :quarantine} (keys grant))
                                         (or (not (contains? grant :tools)) (vector? (:tools grant)))
                                         (or (not (contains? grant :quarantine)) (vector? (:quarantine grant))))
                            (str "The grant for " role " in :settings is {:tools [...] :quarantine [...]"
                                 " :reports {:kinds [...]}}, any of them, nothing else."))
                          (when (and (map? grant) (contains? grant :reports)
                                     (not (and (map? (:reports grant)) (= #{:kinds} (set (keys (:reports grant))))
                                               (kinds-ok? (:kinds (:reports grant))))))
                            (str "The :reports grant for " role " in :settings is {:kinds [...]}, a vector of distinct"
                                 " names of lower case letters, digits and hyphens, such as [\"grant\"]."))
                          (when-let [bad (seq (remove allowed (when (map? grant) (:quarantine grant))))]
                            (str "The grant for " role " names " (str/join ", " bad) " in :quarantine; :settings may"
                                 " grant the reader only over " (str/join ", " (sort allowed)) "."))
                          (when-let [bad (seq (remove known (when (map? grant) (:tools grant))))]
                            (str "The grant for " role " names " (str/join ", " bad)
                                 "; :settings may grant only " (str/join ", " (sort known)) "."))]
                 :when problem]
             problem)))))

(defn merge-settings
  "The staging relay.edn settings with a scenario's :settings merged in."
  [settings {:keys [grants]}]
  (reduce (fn [s [role grant]]
            (cond-> s
              (contains? grant :tools) (assoc-in [:grants role :tools] (:tools grant))
              (contains? grant :reports) (assoc-in [:grants role :reports] (:reports grant))
              (contains? grant :quarantine) (assoc-in [:grants role :quarantine] (:quarantine grant))))
          settings grants))

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
                           (and (contains? step :script)
                                (not (and (:tick step) (not (:spawn step)) (map? (:script step)) (seq (:script step))
                                          (every? string? (keys (:script step))) (every? vector? (vals (:script step))))))
                           (str "Step " (brief step) " may give :script, a map of role id to replies, only with :tick and without :spawn.")
                           (and (:spawn step) (not (and (:tick step) (= :process (:spawn step)))))
                           (str "Step " (brief step) " may give :spawn :process only with :tick.")
                           (and (:advance-minutes step) (not (pos-int? (:advance-minutes step))))
                           (str "Step " (brief step) " needs a positive :advance-minutes.")
                           (and (contains? step :tty) (not (and (:run step) (vector? (:tty step)) (every? string? (:tty step)))))
                           (str "Step " (brief step) " may give :tty, a vector of strings, only with :run."))))
        assertion-problem (fn [[op kind :as a]]
                            (when-not (and (vector? a) (#{:has :none :count} op) (kinds kind))
                              (str "Assertion " (brief a) " is not [:has|:none|:count kind pattern ...].")))
        all-assertions (concat assert (mapcat :assert steps))]
    (vec (concat (when-not (and (vector? steps) (seq steps)) ["A scenario needs :steps."])
                 (when (:clock scenario)
                   (when-not (and (string? (:clock scenario))
                                  (try (clock-instant scenario) (catch Exception _ nil)))
                     [(str ":clock is a local date and time such as \"2026-10-12T09:00\", not "
                           (pr-str (:clock scenario)) ".")]))
                 (settings-problems (:settings scenario))
                 (when-not (seq assert) ["A scenario needs :assert."])
                 (when-not (or (nil? model) (and (map? model) (every? vector? (vals model))))
                   [":model maps a role id to a vector of replies."])
                 (when-not (or (nil? (:reader scenario)) (vector? (:reader scenario)))
                   [":reader is a vector of the quarantined reader's replies."])
                 (keep step-problem steps)
                 (keep assertion-problem all-assertions)))))
