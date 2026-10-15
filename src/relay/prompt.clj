(ns relay.prompt
  "What a role is told: its standing instructions, and the prompt for one
  wake. Pure."
  (:require [clojure.string :as str])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)))

(defn- local [^Instant at ^ZoneId zone]
  (.format (.atZone at zone) (DateTimeFormatter/ofPattern "EEE d MMM yyyy, HH:mm")))

(defn- fmt-notes [notes withheld]
  (str "\n# Your notes\n\n"
       (if (and (empty? notes) (zero? withheld))
         "You have no notes yet. Keep what you need to remember between wakes with `note_write`.\n"
         (str "Your memory from earlier wakes, as you left it. Keep it current with `note_write`"
              " and `note_remove`.\n"
              (str/join (for [{:keys [name text]} notes]
                          (str "\n## " name "\n\n" (str/trim text) "\n")))
              (when (pos? withheld)
                ;; Not how many: that is up to whoever wrote them.
                (str "\nSome notes are withheld because they are contaminated: you wrote them"
                     " after reading something an outsider may have written. Read them with"
                     " `notes` and accept_contamination only if you need them.\n"))))))

(defn instructions
  "The system prompt for a role: the shared instructions, the operator's own
  additions if there are any, the role's own instructions, what it is
  granted, and its notes."
  [{:keys [shared operator-additions notes withheld-notes granted]} {:keys [id title instructions]}]
  (str (str/trim shared) "\n\n"
       (when-not (str/blank? operator-additions)
         (str "---\n\n" (str/trim operator-additions) "\n\n"))
       "---\n\n# Your role\n\n"
       "Your id is `" id "` and your title is " title ". Your own instructions, set by the"
       " operator or the hub, follow. You cannot change them; if they are wrong or too"
       " narrow, send a memo to whoever you report to.\n\n"
       (str/trim instructions) "\n"
       (when-not (str/blank? granted)
         (str "\n# What you are granted\n\n" granted "\n"))
       (fmt-notes notes (or withheld-notes 0))))

(defn- fmt-memo [zone {:keys [id from subject body sent-at attempts routine]}]
  (str "\n## Memo " id ": " subject "\n\n"
       (if routine
         (str "Your routine `" routine "` coming due.")
         (str "From " from ", sent " (local sent-at zone) "."))
       (when (pos? attempts)
         (str " Earlier wakes left it open " attempts " time(s); find out what went wrong"
              " before trying the same thing again."))
       "\n\n" body "\n"))

(defn wake
  "The prompt for one wake."
  [{:keys [role memos note now zone resumed? decided]}]
  (str "You are " (:title role) " (`" (:id role) "`). It is " (local now zone) ".\n\n"
       (cond
         (seq decided)
         (str "Your last wake stopped at tool calls that need the operator's approval. They"
              " have decided: " (str/join ", " (for [{:keys [tool status]} decided]
                                                 (str "`" tool "` " (if (= :allowed status) "allowed" "refused"))))
              ". The results are in your session. Carry on from where you stopped.\n\n")
         resumed?
         (str "Your last wake was cut short through no fault of yours: the model's"
              " rate limit ran out or the service went away. It is back now. Carry on"
              " from where you stopped, and check what you had already done before"
              " repeating anything.\n\n")
         :else "")
       (if (seq memos)
         (str "You have been woken for the memos below. Every memo stays open until"
              " you close it with `memo_close`, saying what you did. If you cannot do"
              " one, use `memo_block` and say why. A memo you leave open is given back"
              " to you later, and after enough tries it is taken off you. Close only"
              " what you have done.\n\n# Memos\n"
              (str/join (map #(fmt-memo zone %) memos)))
         "You have no open memos.\n")
       (when-not (str/blank? note)
         (str "\n# Note from whoever woke you\n\n" note "\n"))))
