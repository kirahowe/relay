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

(defn- fmt-notes [notes]
  (str "\n# Your notes\n\n"
       (if (empty? notes)
         "You have no notes yet. Keep what you need to remember between wakes with `note_write`.\n"
         (str "Your memory from earlier wakes, as you left it. Keep it current with `note_write`"
              " and `note_remove`.\n"
              (str/join (for [{:keys [name text]} notes]
                          (str "\n## " name "\n\n" (str/trim text) "\n")))))))

(defn- fmt-skills [skills]
  (str "\n# Your skills\n\n"
       (if (empty? skills)
         (str "You have no skills yet. When you finish multi-step work that will come back, write"
              " a skill for it with `doc_put` at `skills/<your id>/<slug>.md`: a `# Title` line, a"
              " line starting `Use when:`, then the procedure.\n")
         (str "Procedures you wrote for work that comes back. Before multi-step work, read the one"
              " that matches with `doc_get` and follow it. Update it when the operator corrects you."
              "\n\n"
              (str/join "\n" (for [{:keys [path title use-when]} skills]
                               (str "- " (or title path) " (`" path "`)"
                                    (when use-when (str ". Use when: " use-when)))))
              "\n"))))

(defn instructions
  "The system prompt for a role: the shared instructions, the operator's own
  additions if there are any, the role's own instructions, what it is
  granted, its notes and its skills."
  [{:keys [shared operator-additions notes granted skills steward]} {:keys [id title instructions]}]
  (str (str/trim shared) "\n\n"
       (when-not (str/blank? operator-additions)
         (str "---\n\n" (str/trim operator-additions) "\n\n"))
       "---\n\n# Your role\n\n"
       "Your id is `" id "` and your title is " title ". Your own instructions, set by the"
       " operator or the steward, follow. You cannot change them; if they are wrong or too"
       " narrow, send a memo to whoever you report to."
       ;; The shared instructions say "the steward"; this names it.
       (when (and steward (not= steward id)) (str " The steward is the role `" steward "`."))
       (when (= steward id) " You are the steward.")
       "\n\n"
       (str/trim instructions) "\n"
       (when-not (str/blank? granted)
         (str "\n# What you are granted\n\n" granted "\n"))
       (fmt-notes notes)
       (fmt-skills skills)))

(defn- fmt-memo [zone {:keys [id from subject body sent-at attempts routine request?]}]
  (str "\n## Memo " id ": " subject "\n\n"
       (if routine
         (str "Your routine `" routine "` coming due.")
         (str "From " from ", sent " (local sent-at zone) "."))
       (when request?
         (str " This is the operator's own request. The outcome you give `memo_close` reaches them as"
              " the reply to it, and your final words in this wake do not, so put your whole answer in"
              " the outcome, including the answer to anything they asked. If you tell them about it"
              " with `tell_operator` instead, pass `memo` " id " there, and relay will not send the"
              " outcome as well."))
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

(defn- fmt-journal [zone entries]
  (str "\n# Your journal\n\n"
       (if (empty? entries)
         "Your journal is empty.\n"
         (str "Your latest entries, newest last. Read more with `journal`.\n"
              (str/join (for [{:keys [at text]} entries]
                          (str "\n## " (local at zone) "\n\n" (cut text journal-entry-chars) "\n")))))))

(defn- fmt-topics [{:keys [topics]}]
  (str "\n# Fact topics\n\n"
       (if (seq topics) "How many facts hold now under each topic" "No facts hold now")
       "."
       (when (seq topics)
         (str " Look them up with `facts` by topic or search.\n\n"
              (str/join ", " (for [{:keys [topic count]} topics]
                                (str (or topic "(no topic)") " (" count ")")))))
       "\n"))

(defn board
  "What a prompt says of the operator's board: one line per active card
  that `cards` (relay.core/prompt-cards) lists. Nil when `cards` is nil,
  as for a public role, which has no board."
  [{:keys [cards] :as board}]
  (when board
    (str "\n# The operator's board\n\n"
         (if (seq cards)
           (str "The active cards, which you read with `cards` and change with `card_op`:\n\n"
                (str/join "\n" (for [{:keys [id title kind progress]} cards]
                                 (str "card " id ": " title " (" kind
                                      (when progress (str ", " (:done progress) " of " (:total progress) " done"))
                                      ")"))))
           "The board has no cards.")
         "\n")))

(defn wake
  "The prompt for one wake. `journal` is the tail of the role's journal
  (relay.core/prompt-journal) and `fact-topics` the topics with a count
  each (relay.core/prompt-fact-topics); either is left out when nil, as
  is `cards`, the board (relay.core/prompt-cards)."
  [{:keys [role memos note now zone resumed? journal fact-topics cards]}]
  (str "You are " (:title role) " (`" (:id role) "`). It is " (local now zone) ".\n\n"
       (cond
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
       (when journal (fmt-journal zone journal))
       (when fact-topics (fmt-topics fact-topics))
       (board cards)))
