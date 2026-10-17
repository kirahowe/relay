(ns relay.prompt
  "What a role is told: its standing instructions, and the prompt for one
  wake. Pure."
  (:require [clojure.string :as str])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(defn- local [^Instant at ^ZoneId zone]
  (.format (.atZone at zone) (DateTimeFormatter/ofPattern "EEE d MMM yyyy, HH:mm")))

(defn conversation-start
  "What a conversation is told about time: when it started, in the
  operator's zone, and where to get the time after that. The time here
  goes stale as the conversation runs, so the `clock` tool is the source
  for the current time and for any date arithmetic."
  [^Instant now ^ZoneId zone]
  (let [at (.atZone now zone)]
    (str "This conversation started on "
         (.format at (DateTimeFormatter/ofPattern "EEEE d MMMM yyyy 'at' HH:mm" Locale/ENGLISH))
         ", " zone " time (UTC" (.format at (DateTimeFormatter/ofPattern "xxx")) "). It may run for"
         " hours, so for the current time and date, and for any date arithmetic (adding days to a date,"
         " the days between two dates, the next Monday), call the `clock` tool instead of working"
         " it out yourself.")))

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

(def journal-entry-chars
  "How much of each journal entry a wake's prompt shows. An entry longer
  than this is cut, and the role can read it whole with `journal`."
  600)

(defn- cut [s n]
  (let [s (str/trim (str s))]
    (if (<= (count s) n)
      s
      ;; Never between the halves of a surrogate pair, such as an emoji.
      (let [n (if (Character/isHighSurrogate (.charAt s (dec n))) (dec n) n)]
        (str (str/trimr (subs s 0 n)) " [cut; read it whole with `journal`]")))))

(defn- fmt-journal [zone entries public?]
  (str "\n# Your journal\n\n"
       (if (empty? entries)
         "Your journal is empty.\n"
         (str "Your latest entries, newest last. Read more with `journal`.\n"
              (str/join (for [{:keys [id at text withheld]} entries]
                          (str "\n## " (local at zone) "\n\n"
                               (case withheld
                                 :contaminated
                                 (if public?
                                   ;; A public role's journal reads contaminated
                                   ;; entries without asking.
                                   (str "Entry " id " is withheld because it is contaminated. Reading it"
                                        " with `journal` makes this session contaminated.")
                                   (str "Entry " id " is withheld because it is contaminated. Read it with"
                                        " `journal` and accept_contamination only if you need it."))
                                 :unseen
                                 (str "Entry " id " carries data this wake has not seen. Read it with"
                                      " `journal` if you need it.")
                                 (cut text journal-entry-chars))
                               "\n")))))))

(defn- fmt-topics [{:keys [topics left-out]} public?]
  (str "\n# Fact topics\n\n"
       (cond (seq topics) "How many facts hold now under each topic"
             left-out "No facts are counted here"
             :else "No facts hold now")
       ;; Said whether or not there are any, as the facts tool says it.
       (when-not public? ", not counting contaminated ones")
       "."
       (when (seq topics)
         (str " Look them up with `facts` by topic or search.\n\n"
              (str/join ", " (for [{:keys [topic count]} topics]
                                (str (or topic "(no topic)") " (" count ")")))))
       (when left-out
         (str (if (seq topics) "\n\n" " ")
              "Some facts carry data this wake has not seen, so they are not counted here."
              " `facts` shows them."))
       "\n"))

(defn wake
  "The prompt for one wake. `journal` is the tail of the role's journal
  (relay.core/prompt-journal) and `fact-topics` the topics with a count
  each (relay.core/prompt-fact-topics); either is left out when nil.
  Both show only what the wake's labels already cover."
  [{:keys [role memos note now zone resumed? decided journal fact-topics public?]}]
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
              " what you have done. Before you stop, write one journal entry saying"
              " what you did and what is still open.\n\n# Memos\n"
              (str/join (map #(fmt-memo zone %) memos)))
         "You have no open memos.\n")
       (when-not (str/blank? note)
         (str "\n# Note from whoever woke you\n\n" note "\n"))
       (when journal (fmt-journal zone journal public?))
       (when fact-topics (fmt-topics fact-topics public?))))
