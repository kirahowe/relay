(ns relay.mailbox
  "Mail that comes into the agent's own mailbox, read as data. Pure: raw
  RFC 5322 text in, parts out.

    parse      the raw message as {:headers [[name value] ...] :body ...}
    verdict    whether a mail is the operator's own: from their configured
               address, with the configured authserv-id's
               Authentication-Results saying DKIM passed for their domain
    text-of    what the mail says, as plain text, and what it carries
    thread-ids the Message-IDs relay gave its own mail that this one
               answers (In-Reply-To, then References)

  A message is kept as a string with one char per byte (ISO-8859-1), so
  nothing is lost before each part is decoded with its own charset.

  verdict is strict on purpose: anything it cannot read for certain makes
  the mail an outsider's. A mail the operator really sent is then only
  shown as from outside, which is a nuisance; the other way round would
  let anyone write as the operator."
  (:require [clojure.string :as str])
  (:import (java.nio.charset Charset StandardCharsets)
           (java.util Base64)))

(def ^:private latin1 StandardCharsets/ISO_8859_1)
(def ^:private utf8 StandardCharsets/UTF_8)

(defn bytes->raw
  "Raw bytes as the one-char-per-byte string this namespace reads."
  ^String [^bytes bs]
  (String. bs latin1))

(defn- raw->bytes ^bytes [^String s] (.getBytes s latin1))

(defn- charset-of ^Charset [name]
  (or (when (string? name)
        (try (Charset/forName (str/trim name)) (catch Exception _ nil)))
      utf8))

(defn- decode-raw
  "Raw text decoded with the named charset, UTF-8 when it names none or
  one this machine does not have."
  [^String raw charset]
  (String. (raw->bytes raw) (charset-of charset)))

;; --- the message

(defn- unfold
  "A header value with its folds (a line break before white space)
  removed."
  [s]
  (str/replace s #"\r?\n(?=[ \t])" ""))

(defn- header-section-ok?
  "Whether a header section ends every line with CRLF and holds no other
  CR or LF. A bare one is a line end to some programs and not to others,
  which is how a header can hide in another's value."
  [^String head]
  (not (re-find #"\r(?!\n)|(?<!\r)\n" head)))

(defn parse
  "A raw message as {:headers [[name value] ...] :body raw :well-formed
  bool}. Values are unfolded and as they were, without the name, its
  colon or the CRLF. :well-formed is false when a header line had no
  name, or a line ended with anything but CRLF."
  [^String raw]
  (let [raw (str raw)
        [_ head sep body] (or (re-matches #"(?s)(.*?)(\r?\n\r?\n)(.*)" raw)
                              [nil raw nil ""])
        lines (str/split (str head) #"\r?\n(?![ \t])")
        parsed (for [l lines :when (not= "" l)]
                 (if-let [[_ n v] (re-matches #"(?s)([!-9;-~]+):(.*)" l)]
                   [n (unfold v)]
                   ::bad))]
    {:headers (vec (remove #{::bad} parsed))
     :body (str body)
     :well-formed (and (not-any? #{::bad} parsed)
                       (header-section-ok? (str head))
                       ;; The blank line that ends the headers is CRLF
                       ;; CRLF itself, not a CRLF CRLF later in the body.
                       (= "\r\n\r\n" sep))}))

(defn values
  "Every value of the header `name`, case aside, in order."
  [{:keys [headers]} name]
  (vec (for [[n v] headers :when (.equalsIgnoreCase ^String n ^String name)] v)))

(defn header
  "The one value of the header `name`, trimmed, or nil when there is none
  or more than one."
  [msg name]
  (let [vs (values msg name)]
    (when (= 1 (count vs)) (str/trim (first vs)))))

;; --- RFC 5322 structure: comments, quoted strings, addresses

(defn strip-comments
  "s without its comments (parenthesized, nested, with backslash
  escapes), quoted strings kept whole. Nil when the parentheses or quotes
  do not balance."
  [s]
  (let [s (str s) n (count s) sb (StringBuilder.)]
    (loop [i 0 depth 0 quoted false]
      (if (>= i n)
        (when (and (zero? depth) (not quoted)) (str sb))
        (let [c (.charAt s i)]
          (cond
            (and (= \\ c) (< (inc i) n))
            (do (when (and (zero? depth)) (.append sb c) (.append sb (.charAt s (inc i))))
                (recur (+ i 2) depth quoted))
            quoted (do (.append sb c) (recur (inc i) depth (not= \" c)))
            (= \( c) (recur (inc i) (inc depth) false)
            (and (= \) c) (pos? depth)) (do (when (= 1 depth) (.append sb " ")) (recur (inc i) (dec depth) false))
            (= \) c) nil
            (pos? depth) (recur (inc i) depth false)
            (= \" c) (do (.append sb c) (recur (inc i) depth true))
            :else (do (.append sb c) (recur (inc i) depth false))))))))

(defn- strip-quoted
  "s with every quoted string replaced by a space."
  [s]
  (str/replace s #"\"(?:[^\"\\]|\\.)*\"" " "))

(def ^:private addr-spec #"[A-Za-z0-9!#$%&'*+/=?^_`{|}~.\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+")

(defn mailbox-address
  "The one address a From header names, lower case, or nil when it names
  none, several, a group, or something this cannot read for certain. A
  display name, quoted or not, is ignored: only the address in angle
  brackets counts, or the whole value when there are none."
  [value]
  (when-let [s (some-> value strip-comments)]
    (let [s (str/trim (strip-quoted s))]
      (when-not (re-find #"[,;:\\\"]" s)
        (let [angles (re-seq #"<([^<>]*)>" s)
              outside (str/replace s #"<[^<>]*>" "")]
          (cond
            (and (= 1 (count angles)) (not (re-find #"[<>@]" outside)))
            (let [a (str/trim (second (first angles)))]
              (when (re-matches addr-spec a) (str/lower-case a)))
            (and (empty? angles) (not (re-find #"[<>\s]" s)) (re-matches addr-spec s))
            (str/lower-case s)))))))

(defn domain-of [address] (some-> (second (re-matches #"[^@]+@(.+)" (str address))) str/lower-case))

;; --- Authentication-Results (RFC 8601)

(defn- split-outside-quotes
  "s split at `sep`, a char, wherever it is not inside a quoted string."
  [s sep]
  (let [n (count s)]
    (loop [i 0 start 0 quoted false out []]
      (if (>= i n)
        (conj out (subs s start))
        (let [c (.charAt ^String s i)]
          (cond
            (and quoted (= \\ c)) (recur (+ i 2) start quoted out)
            (= \" c) (recur (inc i) start (not quoted) out)
            (and (not quoted) (= sep c)) (recur (inc i) (inc i) quoted (conj out (subs s start i)))
            :else (recur (inc i) start quoted out)))))))

(defn- unquote* [s]
  (if-let [[_ q] (re-matches #"\"((?:[^\"\\]|\\.)*)\"" s)]
    (str/replace q #"\\(.)" "$1")
    s))

(defn- tokens [s]
  (re-seq #"(?:[^\s\"]|\"(?:[^\"\\]|\\.)*\")+" (str/replace s #"\s*=\s*" "=")))

(defn auth-results
  "An Authentication-Results value as {:authserv-id :results [{:method
  :result :props [[\"header.d\" value] ...]}]}, names and results in
  lower case and comments gone, or nil when it cannot be read."
  [value]
  (when-let [s (strip-comments value)]
    (let [[head & infos] (split-outside-quotes s \;)
          [id & more] (tokens (str head))]
      (when (and id (every? #(re-matches #"\d+" %) more) (not (str/includes? id "=")))
        {:authserv-id (str/lower-case (unquote* id))
         :results (vec (for [info infos
                             :let [[ms & props] (tokens info)
                                   [_ method result] (when ms (re-matches #"([A-Za-z0-9_\-]+)(?:/\d+)?=([A-Za-z0-9_\-]+)" ms))]
                             :when method]
                         {:method (str/lower-case method) :result (str/lower-case result)
                          :props (vec (for [p props
                                            :let [[_ k v] (re-matches #"(?s)([A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+)=(.+)" p)]
                                            :when k]
                                        [(str/lower-case k) (unquote* v)]))}))}))))

(defn- bare-domain [d] (str/lower-case (str/replace (str/trim (str d)) #"\.$" "")))

(defn dkim-pass?
  "Whether a parsed Authentication-Results says a DKIM signature by
  `domain` passed: a dkim=pass result with exactly one header.d, equal
  to the domain."
  [{:keys [results]} domain]
  (boolean
   (some (fn [{:keys [method result props]}]
           (let [ds (keep (fn [[k v]] (when (= "header.d" k) v)) props)]
             (and (= "dkim" method) (= "pass" result) (= 1 (count ds))
                  (= (bare-domain (first ds)) (bare-domain domain)))))
         results)))

(defn verdict
  "Whether a parsed mail is the operator's own, as {:trusted bool :reason
  keyword :from address-or-nil}. It is only when every one of these
  holds:

    - the operator's address and an authserv-id are configured;
    - every header line ends CRLF and has a name (parse :well-formed);
    - there is exactly one From header, naming exactly one address,
      the operator's;
    - every Authentication-Results header can be read, and exactly one
      has the configured authserv-id, so a forged one from another
      server is ignored and a forged one with this server's name next to
      the real one makes the mail unverified;
    - that one says dkim=pass with header.d the operator's domain.

  Anything else is :trusted false."
  [msg {:keys [operator authserv-id]}]
  (let [froms (values msg "From")
        from (when (= 1 (count froms)) (mailbox-address (first froms)))
        no (fn [reason] {:trusted false :reason reason :from from})
        ars (map auth-results (values msg "Authentication-Results"))
        ours (filter #(= (some-> authserv-id str/lower-case) (:authserv-id %)) ars)]
    (cond
      (str/blank? operator) (no :no-operator)
      (not (:well-formed msg)) (no :malformed)
      (nil? from) (no :from)
      (not= from (str/lower-case operator)) (no :not-operator)
      (str/blank? authserv-id) (no :no-authserv-id)
      (some nil? ars) (no :unreadable-authentication-results)
      (not= 1 (count ours)) (no :authentication-results)
      (not (dkim-pass? (first ours) (domain-of operator))) (no :dkim)
      :else {:trusted true :reason :verified :from from})))

;; --- encoded words (RFC 2047)

(defn- q-decode ^bytes [^String s underscore?]
  (let [out (java.io.ByteArrayOutputStream.)
        n (count s)]
    (loop [i 0]
      (when (< i n)
        (let [c (.charAt s i)]
          (cond
            (and (= \= c) (< (+ i 2) n) (re-matches #"[0-9A-Fa-f]{2}" (subs s (inc i) (+ i 3))))
            (do (.write out (Integer/parseInt (subs s (inc i) (+ i 3)) 16)) (recur (+ i 3)))
            (and underscore? (= \_ c)) (do (.write out 32) (recur (inc i)))
            :else (do (.write out (bit-and 0xff (int c))) (recur (inc i)))))))
    (.toByteArray out)))

(defn decode-words
  "A header value with its encoded words decoded, the white space between
  two of them dropped, and raw bytes read as UTF-8."
  [value]
  (let [s (str/replace (str value) #"(\?=)\s+(?==\?)" "$1")]
    (str/replace (decode-raw s "UTF-8")
                 #"=\?([^?\s]+)\?([BbQq])\?([^?\s]*)\?="
                 (fn [[whole cs enc text]]
                   (try
                     (String. (if (#{"B" "b"} enc)
                                (.decode (Base64/getDecoder) ^String text)
                                (q-decode text true))
                              (charset-of (first (str/split cs #"\*"))))
                     (catch Exception _ whole))))))

;; --- MIME: what the mail says

(defn quoted-printable
  "Raw quoted-printable text decoded to raw bytes (as a raw string)."
  [^String s]
  (bytes->raw (q-decode (str/replace s #"=\r?\n" "") false)))

(defn- params
  "A Content-Type or Content-Disposition value as [value {param value}],
  names in lower case."
  [v]
  (let [[main & ps] (split-outside-quotes (or (strip-comments (str v)) "") \;)]
    [(str/lower-case (str/trim (str main)))
     (into {} (for [p ps
                    :let [[_ k val] (re-matches #"(?s)\s*([^=\s]+)\s*=\s*(.*?)\s*" p)]
                    :when k]
                [(str/lower-case k) (unquote* val)]))]))

(defn- transfer-decoded [^String body encoding]
  (case (some-> encoding str/trim str/lower-case)
    "base64" (try (bytes->raw (.decode (Base64/getMimeDecoder) (str/replace body #"[^A-Za-z0-9+/=]" "")))
                  (catch Exception _ ""))
    "quoted-printable" (quoted-printable body)
    body))

(defn- html->text [s]
  (-> s
      (str/replace #"(?is)<(script|style)[^>]*>.*?</\1\s*>" "")
      (str/replace #"(?i)<br\s*/?>|</p\s*>|</div\s*>|</li\s*>|</tr\s*>" "\n")
      (str/replace #"<[^>]*>" "")
      (str/replace "&nbsp;" " ") (str/replace "&lt;" "<") (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"") (str/replace "&#39;" "'") (str/replace "&amp;" "&")
      (str/replace #"[ \t]+\n" "\n")
      (str/replace #"\n{3,}" "\n\n")))

(def ^:private max-parts 50)
(def ^:private max-depth 5)

(defn- entity-parts
  "The leaves of an entity, depth first: [{:type :charset :name :text-raw
  :size}], at most max-parts."
  [{:keys [body] :as entity} depth]
  (let [[ctype ps] (params (or (header entity "Content-Type") "text/plain"))
        [disposition dps] (params (header entity "Content-Disposition"))
        boundary (get ps "boundary")]
    (if (and (str/starts-with? ctype "multipart/") boundary (< depth max-depth))
      (let [b (java.util.regex.Pattern/quote boundary)
            ;; The parts end at the closing delimiter; what follows it is
            ;; an epilogue no reader shows.
            inside (first (str/split body (re-pattern (str "(?m)^--" b "--[ \t]*\r?$")) 2))
            pieces (rest (str/split inside (re-pattern (str "(?m)^--" b "[ \t]*\r?$"))))
            pieces (->> pieces (map #(str/replace-first % #"^\r?\n" "")) (remove str/blank?))]
        (vec (take max-parts
                   (mapcat #(entity-parts (parse %) (inc depth))
                           (take max-parts pieces)))))
      (let [raw (transfer-decoded body (header entity "Content-Transfer-Encoding"))]
        [{:type ctype :charset (get ps "charset")
          :name (some-> (or (get dps "filename") (get ps "name")) decode-words)
          :attachment (= "attachment" disposition)
          :raw raw :size (count raw)}]))))

(defn text-of
  "What a parsed mail says as plain text, and its attachments: {:text
  :attachments [{:name :type :size}]}. The text is every plain-text part
  that is not an attachment, or failing those, the HTML ones made plain."
  [msg]
  (let [parts (entity-parts msg 0)
        textual (fn [t] (filter #(and (= t (:type %)) (not (:attachment %)) (nil? (:name %))) parts))
        plain (textual "text/plain")
        chosen (if (seq plain) plain (textual "text/html"))
        text (str/join "\n\n" (for [p chosen
                                    :let [t (decode-raw (:raw p) (:charset p))]]
                                (if (= "text/html" (:type p)) (html->text t) t)))]
    {:text (str/trim (str/replace text "\r\n" "\n"))
     :attachments (vec (for [p parts :when (not (some #{p} chosen))
                             :when (or (:name p) (:attachment p) (not (str/starts-with? (:type p) "text/")))]
                         {:name (or (:name p) "(unnamed)") :type (:type p) :size (:size p)}))}))

(defn without-quoted-tail
  "A reply's text without the quoted message at its end: the trailing
  lines that start with >, and the \"On ... wrote:\" line before them. A
  reply that interleaves its answers keeps them all."
  [text]
  (let [lines (str/split-lines (str text))
        kept (->> (reverse lines)
                  (drop-while #(or (str/blank? %) (str/starts-with? (str/triml %) ">")))
                  reverse vec)
        kept (if (and (< (count kept) (count lines))
                      (seq kept)
                      (re-find #"(?i)\bwrote:\s*$" (peek kept)))
               (pop kept)
               kept)]
    (if (every? str/blank? kept) (str/trim (str text)) (str/trim (str/join "\n" kept)))))

;; --- threading

(def ^:private relay-id #"relay\.(\d+(?:-\d+)*)\.(\d+)@([A-Za-z0-9.\-]+)")

(defn thread-ids
  "The Message-IDs of relay's own mail this one answers, most direct
  first: In-Reply-To's, then References' from last to first. Each as
  {:ids [message ids] :millis created-at :domain}; relay.channels makes
  them."
  [msg]
  (let [ids (fn [v] (map second (re-seq #"<([^<>\s]+)>" (str v))))
        candidates (concat (mapcat ids (values msg "In-Reply-To"))
                           (reverse (mapcat ids (values msg "References"))))]
    (vec (distinct
          (for [c candidates
                :let [[_ ns millis domain] (re-matches relay-id c)]
                :when ns]
            {:ids (mapv parse-long (str/split ns #"-")) :millis (parse-long millis)
             :domain (str/lower-case domain)})))))
