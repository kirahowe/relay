(ns relay.channels
  "Each channel's renderer: a message, or several that go together, as
  what its delivery edge sends. Pure. The edges are relay.mail (email,
  submitted with curl) and relay.push (ntfy, a command, this Mac, a
  file); the client channel's form is `wire`, which relay.hub sends.

  Nothing a model wrote chooses where a delivery goes. An email's
  recipient, sender and every header but the subject come from the
  channel's settings and relay's own records; the subject is one line,
  encoded, so text cannot add a header (I2)."
  (:require [clojure.string :as str]
            [relay.messages :as messages]
            [relay.text :as text])
  (:import (java.nio.charset StandardCharsets)
           (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util Base64 Locale)))

;; --- text, as every channel shows it

(defn- item-text
  "An item as text: shown (from outside marked and defanged, hidden
  characters visible), and in a listed section, behind a dash with its
  later lines indented."
  [msg {:keys [list]} it]
  (let [t (messages/shown (messages/item-labels msg it) (str/trim (:text it)))]
    (if list
      (str "- " (str/replace t "\n" "\n  "))
      t)))

(defn body-text
  "A message's sections as plain text, with \\n line breaks: each
  section's heading, then its items, a blank line between paragraphs and
  none between the items of a list."
  [msg]
  (str/join "\n\n"
            (for [{:keys [heading list items] :as sec} (:sections msg)]
              (str (when heading (str (messages/shown-line (:labels msg) heading) "\n\n"))
                   (str/join (if list "\n" "\n\n") (map #(item-text msg sec %) items))))))

(defn subject-text
  "A message's subject as one line the operator is shown."
  [msg]
  (messages/shown-line (:labels msg) (:subject msg)))

(defn- batch-subject [msgs]
  (let [s (subject-text (first msgs))]
    (if (next msgs) (str s " (and " (dec (count msgs)) " more)") s)))

;; --- email

(defn domain-of [address] (second (re-matches #"[^@]+@(.+)" (str address))))

(defn message-id
  "The Message-ID relay gives a message: its number and when it was made,
  at the domain of the address it is sent from. The same message always
  gets the same one, so a reply can thread to it."
  [{:keys [id ^Instant created-at]} from]
  (str "<relay." id "." (.toEpochMilli created-at) "@" (domain-of from) ">"))

(defn- batch-id [msgs from]
  (if (next msgs)
    (str "<relay." (str/join "-" (map :id msgs)) "." (.toEpochMilli ^Instant (:created-at (first msgs)))
         "@" (domain-of from) ">")
    (message-id (first msgs) from)))

(def ^:private date-format
  (DateTimeFormatter/ofPattern "EEE, d MMM yyyy HH:mm:ss Z" Locale/ENGLISH))

(defn- printable-ascii? [s] (every? #(<= 0x20 (int %) 0x7e) s))

(defn encode-header
  "A header's text as RFC 2047 encoded words, each at most 75 characters
  and folded onto lines of their own, unless it is short printable ASCII
  that holds nothing a reader would take for an encoded word. Never
  holds a line break that is not a fold."
  [s]
  (let [s (str s)]
    (if (and (printable-ascii? s) (<= (count s) 66) (not (str/includes? s "=?")))
      s
      (let [chunks (loop [cps (iterator-seq (.iterator (.codePoints s))) cur (StringBuilder.) n 0 out []]
                     (if-let [cp (first cps)]
                       (let [c (String. (Character/toChars (int cp)))
                             b (count (.getBytes c StandardCharsets/UTF_8))]
                         (if (> (+ n b) 45)
                           (recur (rest cps) (StringBuilder. c) b (conj out (str cur)))
                           (recur (rest cps) (.append cur c) (+ n b) out)))
                       (cond-> out (pos? n) (conj (str cur)))))]
        (str/join "\r\n " (for [c chunks]
                            (str "=?UTF-8?B?" (.encodeToString (Base64/getEncoder) (.getBytes ^String c StandardCharsets/UTF_8)) "?=")))))))

(defn- crlf [s] (str/replace (str/replace s "\r\n" "\n") #"[\r\n]" "\r\n"))

(defn email
  "One email for a message or for several that go together: {:from :to
  :headers [[name value] ...] :text the whole RFC 5322 message with CRLF
  line ends}. `parent` gives the message a message answers, by id, so the
  mail threads to it. Text only, UTF-8, one recipient: the channel's :to."
  [{:keys [from to]} msgs parent]
  (let [msgs (vec msgs)
        one (when-not (next msgs) (first msgs))
        p (when one (some-> (:in-reply-to one) parent))
        body (if one
               (body-text one)
               (str/join "\n\n----\n\n" (for [m msgs] (str (subject-text m) "\n\n" (body-text m)))))
        headers (cond-> [["From" from]
                         ["To" to]
                         ["Subject" (encode-header (batch-subject msgs))]
                         ["Date" (.format date-format (.atOffset ^Instant (:created-at (last msgs)) ZoneOffset/UTC))]
                         ["Message-ID" (batch-id msgs from)]]
                  p (into [["In-Reply-To" (message-id p from)] ["References" (message-id p from)]])
                  true (into [["MIME-Version" "1.0"]
                              ["Content-Type" "text/plain; charset=UTF-8"]
                              ["Content-Transfer-Encoding" "base64"]]))
        encoded (.encodeToString (Base64/getMimeEncoder) (.getBytes (str (crlf body) "\r\n") StandardCharsets/UTF_8))]
    {:from from :to to :headers headers
     :text (str (str/join (for [[k v] headers] (str k ": " v "\r\n")))
                "\r\n" encoded "\r\n")}))

(defn curl-quote
  "A value for a curl config file, in double quotes. Nil when it holds a
  line break or NUL, which no config line can carry."
  [s]
  (when-not (re-find #"[\r\n\u0000]" (str s))
    (str "\"" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\"")))

(defn curl-argv
  "The curl command that submits `file` over SMTP for an email channel.
  -q first, so no .curlrc is read; no proxy; only SMTP. With a :user,
  the login is read from stdin (-K -), so it is never on a command line
  anyone can see. The one recipient is the channel's :to."
  [{:keys [url from to user timeout-seconds]} file]
  (cond-> ["curl" "-q" "--silent" "--show-error" "--noproxy" "*" "--proto" "=smtp,smtps"
           "--max-time" (str (or timeout-seconds 60))
           ;; The path is the name curl gives itself in EHLO: the domain
           ;; it sends from, rather than the name of the file it uploads.
           "--url" (str (str/replace url #"/$" "") "/" (domain-of from)) "--mail-from" from "--mail-rcpt" to "--upload-file" (str file)]
    user (into ["-K" "-"])))

(defn curl-login
  "What curl reads on stdin for a channel's login: one user line. Nil
  when the user or password cannot be put in one."
  [user password]
  (when-let [q (curl-quote (str user ":" password))]
    (str "user = " q "\n")))

;; --- push

(def body-limit
  "The most characters of a push's one-line body."
  240)

(defn push
  "A push for a message or several: {:title :body :click}, a one-line
  title and a one-line body, and the channel's :link with {id} filled
  in, if it has one."
  [{:keys [link]} msgs]
  (let [msgs (vec msgs)]
    (cond-> {:title (batch-subject msgs)
             :body (if (next msgs)
                     (let [s (str/join " · " (map subject-text (rest msgs)))]
                       (if (> (count s) body-limit) (str (subs s 0 (dec body-limit)) "…") s))
                     (messages/one-line (first msgs) body-limit))
             :labels (vec (sort (distinct (mapcat :labels msgs))))}
      link (assoc :click (str/replace link "{id}" (str (:id (first msgs))))))))

;; --- the client

(defn- element [labels s]
  {"text" (messages/shown labels s) "outsider" (text/contaminated? labels)})

(defn wire
  "A message as a client is sent it (_relay/messages): string keys, and
  every text already safe to show, with outsider beside it when it came
  from outside. Nothing internal: no origin, no labels' names."
  [{:keys [id kind urgency subject sections in-reply-to thread created-at rev deliveries labels replies] :as msg}]
  {"id" id "kind" kind "urgency" urgency "at" (str created-at) "rev" rev
   "thread" thread "inReplyTo" in-reply-to
   "subject" (element labels (messages/squeeze-line subject))
   "sections" (vec (for [{:keys [heading list items]} sections]
                     (cond-> {"items" (vec (for [it items]
                                             (assoc (element (messages/item-labels msg it) (:text it))
                                                    "refs" (vec (:refs it)))))}
                       heading (assoc "heading" (element labels heading))
                       list (assoc "list" true))))
   "deliveries" (vec (for [[ch {:keys [status]}] deliveries]
                       {"channel" ch "status" (name status)}))
   ;; The operator's own replies, verified by the channel they came
   ;; through, so never from outside.
   "replies" (vec (for [{:keys [id at channel text]} replies]
                    {"id" id "at" (str at) "channel" channel "text" (element [] text)}))})
