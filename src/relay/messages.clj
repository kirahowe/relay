(ns relay.messages
  "What relay tells the operator, as data, and where it goes. Pure.

  A message is one data model whatever carries it (decision 15):

    {:kind        \"note\" | \"alert\" | \"digest\" | \"reply\"
     :urgency     \"low\" | \"normal\" | \"high\"
     :subject     one line
     :sections    [{:heading nil-or-line :list bool
                    :items [{:text :refs [\"[[memo:7]]\" ...] :labels [...]}]}]
     :in-reply-to the id of the message it answers, or nil}

  The log keeps it as a message/created event, whose seq is its id. An
  item's labels are where it came from; the operator is shown that in
  plain words, never a label's name.

  Routing is a pure function of relay.edn's :messages and the time
  (`route`): which channels a message goes to, and when. Every message is
  also always the client's to show (the client channel is implicit and
  has no delivery to record). A channel is named in config, and each one
  is an adapter: a pure renderer in relay.channels and a thin delivery
  edge (relay.push, relay.mail).

    :messages
    {:channels {\"phone\" {:kind :push :via :ntfy :url ... :topic ...}
                \"mail\"  {:kind :email :url \"smtps://smtp.example.org:465\"
                         :from \"agent@example.org\" :to \"you@example.org\" ...}}
     :routes [{:kinds [\"alert\"] :channels [\"phone\" \"mail\"]}
              {:urgencies [\"low\"] :channels [\"mail\"] :at :digest}
              {:channels [\"mail\"]}]
     :quiet-hours {:from \"22:00\" :to \"07:00\"}
     :digest-at \"18:00\"}

  A message goes to every channel any matching route names. A route
  matches when its :kinds (if given) has the message's kind and its
  :urgencies (if given) its urgency. A channel that only :at :digest
  routes name gets the message at the next :digest-at. Quiet hours hold
  every delivery of a message that is not high urgency until they end.
  A high-urgency message goes at once, digest or not.
  Deliveries that come due together on one channel at a tick go as one."
  (:require [clojure.string :as str]
            [relay.provenance :as provenance]
            [relay.text :as text])
  (:import (java.time Instant LocalDate LocalTime ZoneId ZonedDateTime)))

(def kinds
  "What a message is: a note (something to know or look at), an alert
  (something is wrong), a digest (a summary on a schedule) or a reply (an
  answer in a thread the operator started or answered)."
  #{"note" "alert" "digest" "reply"})

(def urgencies ["low" "normal" "high"])

;; Under other names for the functions whose arguments are called kinds
;; and urgencies.
(def ^:private kinds* kinds)
(def ^:private urgencies* urgencies)

(def client-channel
  "The channel every message is always visible on: relay's client
  protocol (_relay/messages). It is not configured and records no
  deliveries, so no configured channel may take its name."
  "client")

(def channel-kinds #{:email :push})

(def push-vias
  "How a push channel reaches the operator: an ntfy server, a command
  given the title as its last argument and the body on stdin, a
  notification on this Mac, or a file of JSON lines that reaches nobody
  (for staging data)."
  #{:ntfy :command :macos :file})

(def subject-limit 200)
(def item-limit 4000)
(def items-limit 50)
(def sections-limit 12)

(def attempts
  "How many times a delivery is tried before relay gives up on it."
  8)

;; --- the message

(defn squeeze-line
  "One line: every run of whitespace, line breaks included, made one
  space, and none at either end."
  [s]
  (str/trim (str/replace (str s) #"(?U)\s+" " ")))

(defn- clip-cp
  "`s` cut to `n` code points, with an ellipsis when it was longer."
  [s n]
  (let [s (str s)]
    (if (> (.codePointCount s 0 (count s)) n)
      (str (subs s 0 (.offsetByCodePoints s 0 (dec n))) "…")
      s)))

(defn clip-subject
  "A subject relay writes itself, made one line and cut to subject-limit,
  so a long memo subject it quotes still makes a message."
  [s]
  (clip-cp (squeeze-line s) subject-limit))

(defn reply-memo
  "The memo the operator's answer to message `parent` becomes, whatever
  channel brought it: {:from :subject :body}. `how` says how it came (\"by
  mail\"), and `text` is her answer, cleaned where it came in. The role
  reads it as a memo from her and answers with tell_operator."
  [parent how text]
  {:from "operator"
   :subject (clip-subject (str "Re: " (:subject parent)))
   :body (str "The operator answered your message \"" (clip-cp (squeeze-line (:subject parent)) 150) "\" " how ":\n\n"
              text
              "\n\nTo answer back, use tell_operator with in_reply_to " (:id parent) ".")})

(defn link?
  "Whether a string is a link relay may give an item: one https URL,
  nothing else."
  [s]
  (boolean (and (string? s) (re-matches #"https://[A-Za-z0-9.\-]+(/[A-Za-z0-9._~%!$&'()*+,;=:@/\-]*)?" s))))

(defn item
  "An item from text, or from {:text :refs :labels :action :link}. An
  action is what the operator can do to the item, which only relay sets:
  {:op \"revert\" | \"dismiss\" :n number}, from the daily note
  (relay.digest). A link is one relay built itself from what it knows
  (a GitHub item's repository and number), which every channel keeps
  followable even when the item's text came from outside; only relay
  sets it too."
  [x]
  (let [{:keys [text refs labels action link]} (if (map? x) x {:text x})]
    (cond-> {:text (str text) :refs (vec (distinct (filter string? refs)))}
      (seq labels) (assoc :labels (vec (sort (distinct labels))))
      (link? link) (assoc :link link)
      (and (map? action) (#{"revert" "dismiss"} (:op action)) (pos-int? (:n action)))
      (assoc :action (select-keys action [:op :n])))))

(defn section
  "A section from {:heading :list :items} or from a list of items."
  [x]
  (let [{:keys [heading list items]} (if (map? x) x {:items x})]
    (cond-> {:items (mapv item items)}
      (not (str/blank? heading)) (assoc :heading (squeeze-line heading))
      list (assoc :list true))))

(defn problem
  "What is wrong with a message's content, as a sentence for whoever
  wrote it, or nil."
  [{:keys [kind urgency subject sections]}]
  (cond
    (not (kinds kind)) (str "A message's kind is one of " (str/join ", " (sort kinds)) ".")
    (not (some #{urgency} urgencies)) (str "A message's urgency is one of " (str/join ", " urgencies) ".")
    (str/blank? subject) "A message needs a subject."
    (let [l (squeeze-line subject)] (> (.codePointCount l 0 (count l)) subject-limit)) (str "A message's subject is at most " subject-limit " characters.")
    (empty? (mapcat :items sections)) "A message needs something to say."
    (> (count sections) sections-limit) (str "A message has at most " sections-limit " sections.")
    (some #(> (count (:items %)) items-limit) sections) (str "A section has at most " items-limit " items.")
    (some #(str/blank? (:text %)) (mapcat :items sections)) "A message's items must not be empty."
    (some #(let [t (:text %)] (> (.codePointCount ^String t 0 (count t)) item-limit)) (mapcat :items sections))
    (str "Each item of a message is at most " item-limit " characters.")))

(defn message
  "A message from its parts: the subject made one line, the sections
  and items in their full form. Throws when it is not a message
  (problem)."
  [{:keys [kind urgency subject sections in-reply-to]}]
  (let [m (cond-> {:kind kind :urgency (or urgency "normal")
                   :subject (squeeze-line subject)
                   :sections (mapv section sections)}
            in-reply-to (assoc :in-reply-to in-reply-to))]
    (when-let [why (problem m)]
      (throw (ex-info why {:relay/message-problem true})))
    m))

;; Relay's own messages quote memos, what a model service said, error
;; text and lists of paths, none of which has a limit. They are made to
;; fit instead of refused: refusing one would undo the transaction that
;; tells the operator, and a tick that throws stops relay for good. Only
;; a role's tell_operator is held to the limits (problem), as it hears
;; why and can try again.

(defn- clip-items [items]
  (if (> (count items) items-limit)
    (conj (vec (take (dec items-limit) items))
          {:text (str "…and " (- (count items) (dec items-limit)) " more.") :refs []})
    items))

(defn fit
  "A message's parts made to fit a message, whatever their text: each
  item cut to item-limit, blank ones dropped, at most items-limit in a
  section and sections-limit sections (the last saying how many more
  there were), the subject one line and cut to subject-limit. A message
  with nothing left to say says its subject. Idempotent, and a message
  that fits already comes back as it was."
  [{:keys [subject sections] :as parts}]
  (let [subject (let [s (clip-subject subject)] (if (str/blank? s) "(no subject)" s))
        sections (->> sections
                      (map section)
                      (map (fn [sec] (update sec :items
                                             (fn [items]
                                               (->> items
                                                    (remove #(str/blank? (:text %)))
                                                    (map #(update % :text clip-cp item-limit))
                                                    vec clip-items)))))
                      (filter #(or (seq (:items %)) (:heading %)))
                      vec)
        sections (if (> (count sections) sections-limit)
                   (conj (vec (take (dec sections-limit) sections))
                         {:items [{:text (str "…and " (- (count sections) (dec sections-limit)) " more parts.")
                                   :refs []}]})
                   sections)
        sections (if (seq (mapcat :items sections))
                   sections
                   (conj sections {:items [{:text subject :refs []}]}))]
    (assoc parts :subject subject :sections sections)))

(defn body-sections
  "A role's text as sections: its paragraphs (split at blank lines) one
  section, and `items`, when given, a listed section after it."
  [body items]
  (let [paras (->> (str/split (str/trim (str body)) #"\n\s*\n")
                   (map str/trim) (remove str/blank?))]
    (cond-> []
      (seq paras) (conj {:items (vec paras)})
      (seq items) (conj {:list true :items (vec items)}))))

;; --- what the log kept before messages: notifications

(def ^:private legacy-alerts
  #{"work-abandoned" "harness-needs-attention" "session-forbidden" "result-refused"
    "budget-reached" "tools-unavailable" "turn-unrecorded" "conversation-line-dropped"
    "runaway-guard-tripped" "public-role-cannot-run" "harness-unreachable"})

(defn from-notification
  "A notification an older relay recorded (notification/created), as the
  message it would be now. Its kind becomes the message's :origin. It has
  no route: whatever happened to its push happened then."
  [{:keys [kind title body] :as data}]
  (merge (dissoc data :kind :title :body)
         {:kind (cond (= "brief" kind) "digest" (legacy-alerts kind) "alert" :else "note")
          :urgency (if (#{"harness-needs-attention" "harness-unreachable" "runaway-guard-tripped"} kind)
                     "high" "normal")
          :subject (squeeze-line (or title "(no subject)"))
          :sections [{:items [{:text (str body) :refs []}]}]
          :origin kind
          :route []}))

;; --- times of day

(defn parse-hhmm
  "The LocalTime in an \"HH:MM\" string, or nil when it is not one."
  [s]
  (when-let [[_ h m] (and (string? s) (re-matches #"(\d{2}):(\d{2})" s))]
    (let [h (parse-long h) m (parse-long m)]
      (when (and (<= 0 h 23) (<= 0 m 59)) (LocalTime/of h m)))))

(defn- at-on ^Instant [^LocalDate d ^LocalTime t ^ZoneId zone]
  ;; A time a clock change skips moves forward by the gap.
  (.toInstant (ZonedDateTime/of d t zone)))

(defn next-at
  "The first instant at or after `now` whose local time in `zone` is `t`."
  ^Instant [^LocalTime t ^ZoneId zone ^Instant now]
  (let [today (.toLocalDate (.atZone now zone))]
    (or (first (for [d [today (.plusDays today 1) (.plusDays today 2)]
                     :let [i (at-on d t zone)]
                     :when (not (.isBefore i now))]
                 i))
        (at-on (.plusDays today 3) t zone))))

(defn quiet?
  "Whether `now` falls in the quiet hours {:from :to}, in `zone`. A window
  whose end is earlier than its start runs past midnight."
  [{:keys [from to]} ^ZoneId zone ^Instant now]
  (let [f (parse-hhmm from) t (parse-hhmm to)
        lt (.toLocalTime (.atZone now zone))]
    (boolean
     (when (and f t (not= f t))
       (if (.isBefore f t)
         (and (not (.isBefore lt f)) (.isBefore lt t))
         (or (not (.isBefore lt f)) (.isBefore lt t)))))))

(defn after-quiet
  "When a delivery due at `due` may go: at once outside the quiet hours,
  else when they end."
  ^Instant [quiet ^ZoneId zone ^Instant due]
  (if (and quiet (quiet? quiet zone due))
    (next-at (parse-hhmm (:to quiet)) zone due)
    due))

;; --- routing

(defn- matches? [{:keys [kind urgency]} {:keys [kinds urgencies]}]
  (and (or (nil? kinds) (some #{kind} kinds))
       (or (nil? urgencies) (some #{urgency} urgencies))))

(defn route
  "Where and when a message goes: [{:channel name :due Instant}], one per
  configured channel a matching route names, sorted by channel. Pure in
  the :messages settings, the zone, the message and the time. The client
  channel is not listed: every message is always its to show."
  [{:keys [channels routes quiet-hours digest-at]} ^ZoneId zone msg ^Instant now]
  (let [matching (filter #(matches? msg %) routes)
        by-channel (reduce (fn [acc {:keys [at] :as r}]
                             (reduce (fn [acc ch] (update acc ch (fnil conj #{}) (or at :now)))
                                     acc (:channels r)))
                           {} matching)]
    (vec (for [[ch ats] (sort-by key by-channel)
               :when (contains? channels ch)
               ;; High urgency goes at once: it waits for neither the
               ;; digest nor the end of the quiet hours.
               :let [due (cond (= "high" (:urgency msg)) now
                               (or (contains? ats :now) (nil? (parse-hhmm digest-at))) now
                               :else (next-at (parse-hhmm digest-at) zone now))
                     due (if (= "high" (:urgency msg)) due (after-quiet quiet-hours zone due))]]
           {:channel ch :due due}))))

(defn routed
  "The message with its route as the log keeps it: due times as ISO
  strings."
  [settings zone msg now]
  (assoc msg :route (mapv #(update % :due str) (route settings zone msg now))))

;; --- deliveries, as the state keeps them

(defn deliveries
  "A message's deliveries from its route, as the state starts them:
  {channel {:status :pending :due Instant :attempts 0}}."
  [route]
  (into (sorted-map) (for [{:keys [channel due]} route]
                       [channel {:status :pending :due (Instant/parse (str due)) :attempts 0}])))

(defn due-now
  "The deliveries the tick makes at `now`, as [{:channel :messages [ids]}],
  oldest message first in each, by channel. A delivery is the tick's when
  it is pending and its time has come, unless it was due the moment its
  message was made, when the process that made it delivers it; that one
  becomes the tick's once it is older than `stale-before`, its process
  having stopped first. A failed one is the tick's once its retry is due.
  What comes due together on one channel goes as one."
  [messages ^Instant now ^Instant stale-before]
  (let [due (for [{:keys [id created-at] :as m} (sort-by :id messages)
                  [ch {:keys [status ^Instant due ^Instant retry-at]}] (:deliveries m)
                  :when (case status
                          :pending (and (not (.isAfter due now))
                                        (or (.isAfter due ^Instant created-at)
                                            (.isBefore ^Instant created-at stale-before)))
                          :failed (and retry-at (not (.isAfter retry-at now)))
                          false)]
              [ch id])]
    (vec (for [[ch pairs] (sort-by key (group-by first due))]
           {:channel ch :messages (mapv second pairs)}))))

(defn immediate
  "The channels a message just made goes to at once: those its route
  makes due when it was made."
  [{:keys [route]} ^Instant at]
  (vec (for [{:keys [channel due]} route
             :when (not (.isAfter (Instant/parse (str due)) at))]
         channel)))

;; --- what the operator is shown of a message's text
;;
;; A message's text reads as written. Its links are broken when its
;; labels say an outsider may have written any of it (the security bound,
;; whatever the operator is told about where it came from), and refs
;; become words. Where it came from is said once, in a footer line, and
;; only for a role's message: relay's own alerts and notes say what they
;; are themselves.

(defn display
  "Text of a message as the operator reads it, before the edge makes it
  safe for its screen: refs as words, links broken when it came from
  outside (relay.text/for-operator)."
  [labels s]
  (text/for-operator labels (text/refs-as-words s)))

(defn shown
  "display, with every hidden character made visible once, for a
  terminal or the client."
  [labels s]
  (text/visible (display labels s)))

(defn shown-line
  "shown, as one line."
  [labels s]
  (text/visible-line (display labels (squeeze-line s))))

(defn plain
  "display, for a mail app or a phone: hidden characters taken out and
  said so (relay.text/visible-plain). They are taken out first, before
  links are broken: one inside a host would hide it from defang, and
  taking it out after would put the live link together again."
  [labels s]
  (let [[t n] (text/strip-hidden s)]
    (str (display labels t) (text/hidden-note n))))

(defn plain-line
  "plain, as one line."
  [labels s]
  (plain labels (squeeze-line s)))

(defn item-labels
  "The labels an item is shown with: its own and the message's."
  [msg it]
  (vec (sort (distinct (concat (:labels msg) (:labels it))))))

(def role-origins
  "What relay records a role's own message as having told: these say
  where they came from in a footer."
  #{"told" "memo-for-operator"})

(defn drew-on
  "The footer line saying what outside text a role's message drew on
  (relay.provenance/drew-on), or nil: for relay's own messages, for the
  operator's own threads, and when the role read nothing from outside."
  [{:keys [origin sources labels]}]
  (when (role-origins origin)
    (provenance/drew-on (if (sequential? sources) sources (provenance/sources labels)))))

(defn one-line
  "A message as one short line of text for a push's body: its first
  item, made one line and cut to `n` characters."
  [msg n]
  (let [it (first (mapcat :items (:sections msg)))]
    (if it
      (let [l (clip-cp (plain-line (item-labels msg it) (:text it)) n)]
        (if (:link it) (str l " " (:link it)) l))
      "")))

;; --- the settings

(defn- argv? [x]
  (and (vector? x) (seq x) (every? #(and (string? %) (not (str/blank? %)) (not (str/includes? % "\u0000"))) x)))

(defn- address? [s]
  ;; One plain address: no display name, no comma, no angle brackets,
  ;; nothing a header could be split on.
  (and (string? s) (re-matches #"[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(\.[A-Za-z0-9\-]+)+" s)))

(defn- smtp-url-parts [url]
  (when (string? url)
    (re-matches #"(smtps?)://(\[[0-9a-fA-F:]+\]|[A-Za-z0-9.\-]+)(:\d{1,5})?/?" url)))

(defn plain-smtp?
  "Whether mail to an email channel's :url may go unencrypted: smtp:// to
  this machine only (a local relay such as mailpit). Any other host gets
  TLS, from the start with smtps:// or by STARTTLS, which curl is told to
  require (relay.channels/curl-argv). Pure."
  [url]
  (let [[_ scheme host] (smtp-url-parts url)]
    (boolean (and (= "smtp" scheme) (#{"127.0.0.1" "localhost" "[::1]"} (str/lower-case host))))))

(defn smtp-url-problem
  "Why an email channel's :url cannot be used, or nil: smtps://host:port,
  TLS from the start (port 465), or smtp://host:port, where relay
  requires STARTTLS (port 587) except to this machine (plain-smtp?)."
  [url]
  (when-not (smtp-url-parts url)
    "it is not smtps://host:port or smtp://host:port"))

(defn- push-problem [where {:keys [via url topic command path token-command token-file link] :as ch}]
  (cond
    (not (push-vias via))
    (str where " is a push channel, and needs :via, one of " (str/join ", " (map name (sort push-vias))) ".")
    (and (= :ntfy via) (not (and (string? url) (re-matches #"https?://\S+" url) (string? topic) (not (str/blank? topic)))))
    (str where " pushes through ntfy, and needs a :url and a :topic.")
    (and (some? token-command) (not (argv? token-command)))
    (str where " has a :token-command that is not a command, such as [\"cat\" \"/path/to/token\"].")
    (and (some? token-file) (not (and (string? token-file) (not (str/blank? token-file)))))
    (str where " has a :token-file that is not a path.")
    (and token-command token-file) (str where " takes :token-command or :token-file, not both.")
    (and (= :command via) (not (argv? command))) (str where " pushes through a command, and needs a :command.")
    (and (= :file via) (not (and (string? path) (str/starts-with? path "/"))))
    (str where " appends to a file, and needs a :path, the absolute path of the file.")
    (and (some? link) (not (and (string? link) (re-matches #"[a-z][a-z0-9+.\-]*://\S+" link))))
    (str where " has a :link that is not a URL; it may hold {id}, the message's number.")
    (seq (remove #{:kind :via :url :topic :token-command :token-file :command :path :link} (keys ch)))
    (str where " has " (str/join ", " (map pr-str (sort (remove #{:kind :via :url :topic :token-command :token-file :command :path :link} (keys ch)))))
         ", which a push channel does not take.")))

(def email-keys #{:kind :url :from :from-name :to :user :credentials-command :timeout-seconds})

(defn from-name?
  "Whether a string can be the name a mail is from: one line of at most
  60 characters, with no quote, backslash, angle bracket or control
  character, so it cannot break the From header it goes in."
  [s]
  (boolean (and (string? s) (not (str/blank? s)) (<= (count s) 60)
                (not (re-find #"[\"\\<>\p{Cntrl}]" s)))))

(defn- email-problem [where {:keys [url from from-name to user credentials-command timeout-seconds] :as ch}]
  (cond
    (smtp-url-problem url) (str where " has :url " (pr-str url) ", which cannot be used: " (smtp-url-problem url) ".")
    (not (address? from)) (str where " needs :from, the agent's own address, such as \"agent@example.org\".")
    (not (address? to)) (str where " needs :to, your address, such as \"you@example.org\". It is the only recipient.")
    (and (some? from-name) (not (from-name? from-name)))
    (str where " has a :from-name that cannot go in a mail header: one line of up to 60 characters, with no quotes or angle brackets.")
    (and (some? user) (not (and (string? user) (not (str/blank? user)) (not (re-find #"[\s\"\\]" user)))))
    (str where " has a :user that is not a login name.")
    (and (some? credentials-command) (not (argv? credentials-command)))
    (str where " has a :credentials-command that is not a command; it prints the password.")
    (and credentials-command (nil? user)) (str where " has a :credentials-command but no :user to log in as.")
    (and (some? timeout-seconds) (not (pos-int? timeout-seconds))) (str where " has a :timeout-seconds that is not a positive whole number.")
    (seq (remove email-keys (keys ch)))
    (str where " has " (str/join ", " (map pr-str (sort (remove email-keys (keys ch)))))
         ", which an email channel does not take. It takes " (str/join ", " (map pr-str (sort email-keys))) ".")))

(defn channel-problem
  "What is wrong with one channel's settings, or nil."
  [ch-name ch]
  (let [where (str "The channel " (pr-str ch-name) " in relay.edn's :messages")]
    (cond
      (not (and (string? ch-name) (re-matches #"[a-z][a-z0-9-]{0,39}" ch-name)))
      (str "relay.edn's :messages has a channel named " (pr-str ch-name)
           "; a name is lower case letters, digits and hyphens, such as \"phone\".")
      (= client-channel ch-name)
      (str "relay.edn's :messages cannot name a channel \"client\": every message is the client's already.")
      (not (and (map? ch) (channel-kinds (:kind ch))))
      (str where " needs a :kind, :email or :push.")
      (= :push (:kind ch)) (push-problem where ch)
      :else (email-problem where ch))))

(def settings-keys #{:channels :routes :quiet-hours :digest-at})
(def route-keys #{:kinds :urgencies :channels :at})

(defn- route-problem [i {:keys [kinds urgencies channels at] :as r} defined digest-at]
  (let [where (str "Route " (inc i) " in relay.edn's :messages")]
    (cond
      (not (map? r)) (str where " is not a map such as {:kinds [\"alert\"] :channels [\"phone\"]}.")
      (seq (remove route-keys (keys r)))
      (str where " has " (str/join ", " (map pr-str (sort (remove route-keys (keys r)))))
           ", which a route does not take. It takes :kinds, :urgencies, :channels and :at.")
      (not (or (nil? kinds) (and (vector? kinds) (seq kinds) (every? kinds* kinds))))
      (str where " has :kinds " (pr-str kinds) "; it is a vector of " (str/join ", " (sort kinds*)) ".")
      (not (or (nil? urgencies) (and (vector? urgencies) (seq urgencies) (every? (set urgencies*) urgencies))))
      (str where " has :urgencies " (pr-str urgencies) "; it is a vector of " (str/join ", " urgencies*) ".")
      (not (and (vector? channels) (seq channels)))
      (str where " needs :channels, the channels it sends to, such as [\"phone\"].")
      (some #(not (contains? defined %)) channels)
      (str where " sends to " (pr-str (first (remove #(contains? defined %) channels)))
           ", which :channels does not define.")
      (not (or (nil? at) (= :digest at))) (str where " has :at " (pr-str at) "; the only one is :digest.")
      (and (= :digest at) (nil? (parse-hhmm digest-at)))
      (str where " waits for the digest, so :messages needs :digest-at, a time such as \"18:00\"."))))

(defn settings-problem
  "What is wrong with relay.edn's :messages, as a sentence for the
  operator, or nil. Checked alone, so a relay.edn broken elsewhere can
  still say so through its channels."
  [{:keys [channels routes quiet-hours digest-at] :as settings}]
  (cond
    (not (map? settings)) "relay.edn's :messages is a map with :channels and :routes."
    (seq (remove settings-keys (keys settings)))
    (str "relay.edn's :messages has " (str/join ", " (map pr-str (sort (remove settings-keys (keys settings)))))
         ", which it does not take. It takes :channels, :routes, :quiet-hours and :digest-at.")
    (not (map? (or channels {}))) "relay.edn's :messages :channels is a map of names to channels."
    :else
    (or (some (fn [[n ch]] (channel-problem n ch)) (sort-by (comp str key) channels))
        (when-not (vector? (or routes [])) "relay.edn's :messages :routes is a vector of routes.")
        (some (fn [[i r]] (route-problem i r (or channels {}) digest-at)) (map-indexed vector routes))
        (when (and (some? quiet-hours)
                   (not (and (map? quiet-hours) (= #{:from :to} (set (keys quiet-hours)))
                             (parse-hhmm (:from quiet-hours)) (parse-hhmm (:to quiet-hours))
                             (not= (:from quiet-hours) (:to quiet-hours)))))
          (str "relay.edn's :messages :quiet-hours is {:from \"22:00\" :to \"07:00\"}, two different times"
               (str ", not " (pr-str quiet-hours)) "."))
        (when (and (some? digest-at) (nil? (parse-hhmm digest-at)))
          (str "relay.edn's :messages :digest-at is a time of day as HH:MM, not " (pr-str digest-at) ".")))))

(def default-settings
  "No channels and no routes: every message is the client's to show, and
  nothing else."
  {:channels {} :routes []})
