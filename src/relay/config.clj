(ns relay.config
  "The operator's settings, from relay.edn at the root of the data directory.
  Settings are not data: they say how relay runs and what each role is
  granted, and only the operator edits them. No role has a tool that can
  read or write the file."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn])
  (:import (java.time ZoneId)))

(def tool-classes
  "Every tool a role can be granted is named in config and given one of
  these classes. Nothing depends on the class yet; it is there so that a
  policy can be added later without renaming or reclassifying anything.

    :read    reads relay data or the outside world, changes nothing
    :write   changes relay data
    :egress  sends data off the machine
    :act     changes something outside relay, such as a reminder"
  #{:read :write :egress :act})

(def defaults
  {:timezone nil                     ; nil means the system zone
   :default-harness :agentloop
   :harnesses {}
   :notify {}
   :dead-letter-handler "hub"
   :wake-timeout-minutes 30
   ;; A memo a wake carries and leaves open is retried after a wait that
   ;; doubles from the base up to the ceiling, and dead-lettered once it
   ;; has failed max-attempts times.
   :max-attempts 8
   :retry-base-seconds 60
   :retry-ceiling-seconds 3600
   ;; A harness that hit its rate limit without saying when it resets is
   ;; tried again after this long.
   :limit-probe-minutes 15
   ;; How long a harness may stay unreachable before the operator is told.
   :outage-patience-minutes 120
   ;; A last resort against a role stuck in a loop, far above ordinary use.
   :runaway-wakes 30
   :runaway-window-minutes 60
   :tools {}
   :grants {}})

(defn validate
  "Returns the config, or throws describing the first problem found."
  [{:keys [tools grants max-attempts] :as config}]
  (doseq [[tool-name {:keys [class]}] tools]
    (when-not (tool-classes class)
      (throw (ex-info (str "Tool " tool-name " needs a :class, one of "
                           (sort (map name tool-classes)) ".")
                      {:tool tool-name}))))
  (doseq [[role {granted :tools}] grants
          t granted]
    (when-not (contains? tools t)
      (throw (ex-info (str "Role " role " is granted tool " t ", which :tools does not define.")
                      {:role role :tool t}))))
  (when-not (and (integer? max-attempts) (pos? max-attempts))
    (throw (ex-info ":max-attempts must be a positive integer." {})))
  config)

(defn resolve-config
  "Defaults overlaid with the operator's settings."
  [settings]
  (validate (merge defaults (dissoc settings :format))))

(defn load-config [data-dir]
  (let [file (fs/path data-dir "relay.edn")]
    (resolve-config (if (fs/exists? file) (edn/read-string (slurp (str file))) {}))))

(defn zone ^ZoneId [config]
  (if-let [tz (:timezone config)] (ZoneId/of tz) (ZoneId/systemDefault)))

(defn grants
  "What a role is granted beyond the defaults every role has."
  [config role]
  (get-in config [:grants role] {}))

(defn may-add? [config actor]
  (boolean (:may-add (grants config actor))))

(defn retry-delay-seconds
  "The wait after the nth failure: the base, doubled for each failure after
  the first, never more than the ceiling."
  [{:keys [retry-base-seconds retry-ceiling-seconds]} n]
  (long (min retry-ceiling-seconds
             (* retry-base-seconds (Math/pow 2 (min 40 (dec (max 1 n))))))))
