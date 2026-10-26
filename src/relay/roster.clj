(ns relay.roster
  "The roster: each active role with what it is doing now, the way every
  client of relay shows it. It is read from the state alone, so it holds
  nothing the log does not, and it carries data, never layout or text a
  client must show as it is.

    roster  each active role, in `relay roles` order:
              {:id :title :labels :working? :conversation}
            :labels are those of the role's id and title, which is all
            the roster shows of the role. :working? says a wake of the
            role works through its memos. :conversation is nil, or the
            role's open conversation:
              {:wake :session :opened-at :last-activity :turn :turns
               :unread?}
            :session is the ACP session its last turn ran in (nil before
            its first), :turn is :idle or :running, :turns counts the
            turns started, and :unread? says a turn ended after the last
            one the operator saw.
    wire    the roster as JSON-ready data, every role in the form
            `_relay/roles` sends."
  (:require [relay.core :as core]))

(defn conversation
  "A role's open conversation as the roster shows it, or nil."
  [state role-id]
  (when-let [{:keys [id turn-session started-at last-activity turn turns turns-ended seen]}
             (core/open-conversation state role-id)]
    {:wake id
     :session turn-session
     :opened-at started-at
     :last-activity (or last-activity started-at)
     :turn (if (= :running turn) :running :idle)
     :turns (or turns 0)
     :unread? (> (or turns-ended 0) (or seen 0))}))

(defn entry
  "One role as the roster shows it."
  [state id]
  (let [{:keys [title id-labels title-labels]} (core/role state id)]
    {:id id
     :title title
     :labels (core/union id-labels title-labels)
     :working? (some? (core/working-wake state id))
     :conversation (conversation state id)}))

(defn roster
  "Each active role with what it is doing as of the state. The config and
  the time are taken so that a status that depends on them (a paused
  harness, say) can join it without changing its callers; none does yet."
  [state _config _now]
  (mapv #(entry state %) (core/active-role-ids state)))

;; --- the wire form

(defn wire-role
  "A roster entry as JSON-ready data with string keys. Times are ISO-8601
  instants; the wake id stays relay's own."
  [{:keys [id title labels working? conversation]}]
  {"id" id
   "title" title
   "labels" (vec labels)
   "working" (boolean working?)
   "conversation" (when-let [{:keys [session opened-at last-activity turn turns unread?]} conversation]
                    {"sessionId" session
                     "openedAt" (str opened-at)
                     "lastActivity" (str last-activity)
                     "turn" (name turn)
                     "turns" turns
                     "unread" (boolean unread?)})})

(defn wire
  "The roster as `_relay/roles` sends it: {\"roles\" [Role]}."
  [roster]
  {"roles" (mapv wire-role roster)})
