(ns relay.inbound
  "The one way into relay. Every change, whoever asks for it (the operator at
  the command line, a role through a tool, the dispatcher, and later a phone),
  is a command submitted here. It is decided against the current state and
  appended in one step under the log's lock, so two writers can never both
  act on a state that one of them has already changed.

  A system is a map of :data-dir, :config and :clock. Notifications that a
  command produces are pushed after the append, since the log, not the push,
  is what records them."
  (:require [relay.config :as config]
            [relay.core :as core]
            [relay.log :as log]
            [relay.notify :as notify])
  (:import (java.time Instant)))

(defn system
  "A system for a data directory, with its config and the real clock."
  ([data-dir] (system data-dir {}))
  ([data-dir overrides]
   (merge {:data-dir data-dir
           :config (config/load-config data-dir)
           :clock #(Instant/now)}
          overrides)))

(defn current-state [{:keys [data-dir]}]
  (core/replay (log/read-events data-dir)))

(defn- not-before ^Instant [^Instant now ^Instant floor]
  (if (and floor (.isBefore now floor)) floor now))

(defn submit-quietly!
  "Decides and appends a command, returning the events appended. Pushes
  nothing; see submit!."
  [{:keys [data-dir config clock]} command]
  (log/transact! data-dir clock
                 (fn [events]
                   (let [state (core/replay events)
                         now (not-before (clock) (:last-at state))]
                     (core/decide state command {:now now :config config})))))

(declare deliver!)

(defn submit!
  "Decides and appends a command, then pushes any notifications it produced.
  Returns the events appended. Throws a rejection (see core/rejection) when
  the command cannot be carried out, having written nothing."
  [sys command]
  (let [events (submit-quietly! sys command)]
    (doseq [e events :when (= "notification/created" (:type e))]
      (deliver! sys e))
    events))

(defn deliver!
  "Pushes one notification and records how it went. A failed push is
  recorded and otherwise dropped: the thing it announced is in the log."
  [{:keys [config] :as sys} {:keys [seq data labels]}]
  (let [result (try (notify/push! (:notify config) (assoc data :labels (or labels (:labels data))))
                    {:ok true}
                    (catch Exception e {:error (or (ex-message e) (str (class e)))}))]
    (try
      (submit-quietly! sys {:type (if (:ok result) :notification/delivered :notification/failed)
                            :actor core/dispatcher
                            :args {:notification seq :error (:error result)}})
      (catch Exception _ nil))
    result))
