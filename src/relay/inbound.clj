(ns relay.inbound
  "The one way into relay. Every change, whoever asks for it (the operator at
  the command line, a role through a tool, the dispatcher, and later a phone),
  is a command submitted here. It is decided against the current state and
  appended in one step under the log's lock, so two writers can never both
  act on a state that one of them has already changed.

  A system is a map of :data-dir, :config and :clock. Messages to the
  operator that a command produces are delivered after the append, since
  the log, not the delivery, is what records them."
  (:require [relay.channels :as channels]
            [relay.config :as config]
            [relay.core :as core]
            [relay.log :as log]
            [relay.mail :as mail]
            [relay.messages :as messages]
            [relay.push :as push])
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
  "Decides and appends a command, then delivers any message it produced
  to the channels its route makes due at once. Returns the events
  appended. Throws a rejection (see core/rejection) when the command
  cannot be carried out, having written nothing."
  [sys command]
  (let [events (submit-quietly! sys command)]
    (doseq [{:keys [seq at data] :as e} events
            :when (= "message/created" (:type e))
            ch (messages/immediate data (Instant/parse at))]
      (deliver! sys ch [seq]))
    events))

(defn send!
  "Sends rendered messages through one channel's edge. Throws when it
  could not. `opts` are what the email renderer needs to know beyond the
  messages (relay.channels/email)."
  ([channel msgs parent] (send! channel msgs parent {}))
  ([channel msgs parent opts]
   (case (:kind channel)
     :email (mail/send! channel (channels/email channel msgs parent opts))
     :push (push/push! channel (channels/push channel msgs)))))

(defn deliver!
  "Delivers messages, by id, through one configured channel, as one, and
  records how it went. A failed delivery is recorded and tried again by
  the tick: the message itself is in the log, and the client shows it
  whatever happens here."
  [{:keys [config] :as sys} channel-name ids]
  (let [state (current-state sys)
        msgs (keep #(core/message state %) ids)
        channel (get-in config [:messages :channels channel-name])
        result (cond
                 (empty? msgs) nil
                 (nil? channel) {:error (str "relay.edn no longer has the channel " channel-name ".") :final true}
                 :else (try (send! channel msgs #(core/message state %)
                                   {:mailed? #(= :delivered (get-in (core/message state %) [:deliveries channel-name :status]))
                                    :replies? (boolean (some #(= :pop3 (:kind (val %))) (:ingest config)))})
                            {:ok true}
                            (catch Exception e {:error (or (ex-message e) (str (class e)))})))]
    (when result
      (try
        (submit-quietly! sys {:type (if (:ok result) :message/delivered :message/failed)
                              :actor core/dispatcher
                              :args {:channel channel-name :messages (mapv :id msgs)
                                     :error (:error result) :final (:final result)}})
        (catch Exception _ nil)))
    result))
