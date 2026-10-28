(ns relay.eventkit-run
  "Running relay's EventKit binary (relay.eventkit says what it prints).
  The one place relay starts it: the ingest polls, the reminder tools,
  undoing a reminder change and `relay eventkit-access`.

  The binary is tools/eventkit/eventkit in relay's own install, or the
  :bin an ingest source names. It runs with no stdin and a time limit, and
  only the one process: a call that runs out of time is ended by the PID
  relay started."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [relay.eventkit :as eventkit]
            [relay.ingest :as ingest]))

(defn install-root
  "The relay checkout or install this code runs from, or nil."
  []
  (some-> (io/resource "relay/setup.clj") io/file fs/parent fs/parent fs/parent str))

(defn default-bin
  "Where relay's own EventKit binary is."
  []
  (str (fs/path (or (install-root) ".") "tools" "eventkit" "eventkit")))

(defn bin-of
  "The binary a source runs: its :bin, a leading ~ expanded, else relay's
  own."
  [{:keys [bin]}]
  (if (string? bin) (str (fs/expand-home bin)) (default-bin)))

(defn run!
  "Runs the binary `bin` with `argv`. Returns the parsed JSON object it
  printed. Throws an ex-info saying what went wrong, in plain words, with
  :code in its data (relay.eventkit/output)."
  [bin argv timeout-seconds]
  (when-not (fs/executable? bin)
    (throw (ex-info (str "relay's eventkit tool is not built at " bin
                         ". Build it with tools/eventkit/build.sh, or the Homebrew install does.")
                    {:code "missing"})))
  (let [proc (p/process (into [bin] argv) {:in "" :out :string :err :string})
        r (deref proc (* 1000 timeout-seconds) ::timeout)]
    (when (= ::timeout r)
      (p/destroy-tree proc)
      (throw (ex-info (str "eventkit did not answer within " timeout-seconds " seconds.") {:code "timeout"})))
    (let [{:keys [ok error code]} (eventkit/output (:exit r) (:out r))]
      (when error (throw (ex-info error {:code code})))
      ok)))

(defn fetch-calendar
  "A source's calendar read at `now`, in relay.ics-feed/poll's shape:
  {:items :keys}. Its :calendars, or every calendar."
  [src zone now]
  (let [{:keys [from to]} (eventkit/window now)
        r (run! (bin-of src) (eventkit/events-argv from to (:calendars src)) (ingest/timeout-seconds src))]
    (when-not (sequential? (:events r))
      (throw (ex-info "eventkit's answer had no list of events." {:code "failed"})))
    (eventkit/poll-events (:events r) zone now)))

(defn fetch-reminders
  "A source's reminders: {:items :keys} (relay.eventkit/poll-reminders)."
  [src]
  (let [r (run! (bin-of src) (eventkit/reminders-argv (:lists src)) (ingest/timeout-seconds src))]
    (when-not (sequential? (:reminders r))
      (throw (ex-info "eventkit's answer had no list of reminders." {:code "failed"})))
    (eventkit/poll-reminders (:reminders r))))

(defn write!
  "Runs a write for a source: every list it may write to as --allow, then
  `tail` (relay.eventkit/plan-write). Returns {:before :after} as
  relay.eventkit/reminder reads them (:before nil for an add). Throws
  when the binary fails or answers with no reminder."
  [src tail]
  (let [r (run! (bin-of src) (eventkit/write-argv (:lists src) tail) (ingest/timeout-seconds src))
        after (eventkit/reminder (:after r))]
    (when-not after (throw (ex-info "eventkit's answer had no reminder." {:code "failed"})))
    {:before (some-> (:before r) eventkit/reminder) :after after}))

(defn access!
  "Runs `access` with the terminal's input and error, so macOS can ask,
  and returns {:calendar :reminders} as the binary says, or {:error why}."
  [bin]
  (if-not (fs/executable? bin)
    {:error (str "relay's eventkit tool is not built at " bin
                 ". Build it with tools/eventkit/build.sh, or the Homebrew install does.")}
    (let [r @(p/process [bin "access"] {:in :inherit :out :string :err :inherit})
          {:keys [ok error]} (eventkit/output (:exit r) (:out r))]
      (if error {:error error} (select-keys ok [:calendar :reminders])))))

(defn access-lines
  "What `relay eventkit-access` says, from access!'s answer, in plain
  words. Pure."
  [{:keys [error calendar reminders]}]
  (if error
    [error]
    (let [say (fn [what v] (str what " access: " (if (= "granted" (str v)) "granted." (str "not granted (" v ").")) ))
          ok? (every? #(= "granted" (str %)) [calendar reminders])]
      (cond-> [(say "Calendar" calendar) (say "Reminders" reminders)]
        (not ok?) (conj eventkit/access-help)
        ok? (conj "relay can read your calendar and reminders.")))))
