(ns relay.websocket
  "WebSocket (RFC 6455) for the relay service's phone listener: what to do
  with a request before the upgrade, and reading and writing frames on a
  connection's streams. relay.serve runs the listener; this namespace
  decides and frames.

  The decisions are pure: `decide` takes a request's head and the
  listener's settings and says whether to upgrade or which bare status to
  refuse with. Reading and writing frames work on the streams they are
  given and keep no state of their own, so a connection's writer can
  block on a client that reads slowly, which is what bounds what relay
  holds for it.

  One text message is one JSON-RPC line. The service sends each line as
  one unfragmented text frame. It reads a client's message whole, from
  however many frames, up to a limit, and closes the connection on a
  binary message, a message over the limit, or anything RFC 6455 forbids."
  (:require [clojure.string :as str])
  (:import (java.io DataInputStream EOFException InputStream OutputStream)
           (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

;; --- the request before the upgrade

(def head-limit
  "How many bytes a request's head (its request line and headers) may
  take. A WebSocket upgrade needs a few hundred."
  16384)

(defn parse-head
  "A request head, as text without its closing blank line, as
  {:method :target :version :headers}, where :headers maps each lower-case
  name to the values it was given, in order. Nil when it is not one."
  [text]
  (let [[request-line & header-lines] (str/split text #"\r?\n" -1)
        [_ method target version] (some->> request-line (re-matches #"([A-Z]+) (\S+) (HTTP/\d\.\d)"))]
    (when (and method
               (every? #(re-matches #"[!#$%&'*+.^_`|~0-9A-Za-z-]+:[^\r\n]*" %) header-lines))
      {:method method :target target :version version
       :headers (reduce (fn [m l]
                          (let [i (str/index-of l ":")]
                            (update m (str/lower-case (subs l 0 i)) (fnil conj []) (str/trim (subs l (inc i))))))
                        {} header-lines)})))

(defn- tokens
  "A header's comma-separated tokens, lower-cased, across every time it
  was given."
  [head header]
  (set (for [v (get-in head [:headers header]) t (str/split v #",")] (str/lower-case (str/trim t)))))

(defn- one [head header]
  (let [vs (get-in head [:headers header])] (when (= 1 (count vs)) (first vs))))

(defn- sha256 ^bytes [^String s]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s StandardCharsets/UTF_8)))

(defn token-matches?
  "Whether `given` is the token, taking the same time whatever either
  holds: both are hashed and the hashes compared in constant time, so
  neither a prefix nor a length shows through."
  [given token]
  (boolean (and (string? given) (string? token)
                (MessageDigest/isEqual (sha256 given) (sha256 token)))))

(defn bearer
  "The token an Authorization header carries as `Bearer <token>`, or nil."
  [value]
  (some->> value (re-matches #"(?i)bearer +(\S+) *") second))

(defn- valid-key? [k]
  (boolean (when (string? k)
             (try (= 16 (alength (.decode (Base64/getDecoder) ^String k))) (catch IllegalArgumentException _ false)))))

(defn accept-key
  "The Sec-WebSocket-Accept for a Sec-WebSocket-Key."
  [k]
  (.encodeToString (Base64/getEncoder)
                   (.digest (MessageDigest/getInstance "SHA-1")
                            (.getBytes (str k "258EAFA5-E914-47DA-95CA-C5AB0DC85B11") StandardCharsets/US_ASCII))))

(defn decide
  "What the listener does with a request, given its head (parse-head's,
  or nil for one that could not be read) and {:token :allowed-origins}:
  {:upgrade accept-key}, or {:status n} to answer with no body. Total:
  every head, readable or not, gets one. The order:

    unreadable                         400
    not an upgrade to websocket        404, as for a path nothing serves
    an Origin not allowed              403
    no token, or not the token         401
    an upgrade RFC 6455 would refuse   400
    otherwise                          upgrade

  A refusal says nothing else, and nothing about relay. Any Origin header
  is refused unless that exact origin is allowed: a web page in a browser
  on the Mac sends one, and a native client does not."
  [head {:keys [token allowed-origins]}]
  (let [origins (get-in head [:headers "origin"])
        auth (get-in head [:headers "authorization"])]
    (cond
      (nil? head) {:status 400}
      (not (contains? (tokens head "upgrade") "websocket")) {:status 404}
      (and (seq origins)
           (not (and (= 1 (count origins)) (some #(= (first origins) %) allowed-origins))))
      {:status 403}
      (not (and (= 1 (count auth)) (token-matches? (bearer (first auth)) token))) {:status 401}
      (not (and (= "GET" (:method head)) (= "HTTP/1.1" (:version head))
                (contains? (tokens head "connection") "upgrade")
                (= "13" (one head "sec-websocket-version"))
                (valid-key? (one head "sec-websocket-key"))))
      {:status 400}
      :else {:upgrade (accept-key (one head "sec-websocket-key"))})))

(def calendar-path
  "The one path besides the upgrade that the listener answers: the agent's
  calendar feed."
  "/calendar.ics")

(defn- percent-decode
  "A query value with its %XX escapes decoded, or nil when it has a bad one.
  A plus is a plus: a token may hold one."
  [^String v]
  (try (java.net.URLDecoder/decode (str/replace v "+" "%2B") "UTF-8")
       (catch IllegalArgumentException _ nil)))

(defn- split-target
  "A request target as [path query-string]."
  [target]
  (let [i (str/index-of target "?")]
    (if i [(subs target 0 i) (subs target (inc i))] [target nil])))

(defn calendar-request?
  "Whether a request head asks for the calendar feed: a GET of exactly
  calendar-path, whatever its query."
  [head]
  (boolean (and head (= "GET" (:method head))
                (= calendar-path (first (split-target (:target head)))))))

(defn query-token
  "The token a feed URL carries as `?token=<token>`, or nil when it has
  none, has it twice or has it badly escaped. A calendar app subscribes to
  a URL and cannot send a header."
  [target]
  (let [[_ q] (split-target target)
        vals (for [pair (some-> q (str/split #"&")) :when (str/starts-with? pair "token=")]
               (percent-decode (subs pair 6)))]
    (when (= 1 (count vals)) (first vals))))

(defn decide-calendar
  "What the listener does with a request for the calendar feed: {:calendar
  true} to answer with it, or {:status n}. The order:

    an Origin not allowed              403
    no token, or not the token         401
    otherwise                          the feed

  The token is the listener's, the same one an ACP client sends, given as
  a bearer token or in the URL. Nothing else about the request matters."
  [head {:keys [token allowed-origins]}]
  (let [origins (get-in head [:headers "origin"])
        auth (get-in head [:headers "authorization"])
        given (if (seq auth)
                (when (= 1 (count auth)) (bearer (first auth)))
                (query-token (:target head)))]
    (cond
      (and (seq origins)
           (not (and (= 1 (count origins)) (some #(= (first origins) %) allowed-origins))))
      {:status 403}
      (not (token-matches? given token)) {:status 401}
      :else {:calendar true})))

(defn route
  "decide, or decide-calendar for a request for the feed."
  [head settings]
  (if (calendar-request? head)
    (decide-calendar head settings)
    (decide head settings)))

(defn calendar-response-bytes
  "The bytes of the feed's answer, given its text: 200, never cached,
  closed after."
  ^bytes [^String body]
  (let [b (.getBytes body StandardCharsets/UTF_8)
        head (.getBytes (str "HTTP/1.1 200 OK\r\nContent-Type: text/calendar; charset=utf-8\r\n"
                             "Cache-Control: no-store\r\nContent-Length: " (alength b) "\r\nConnection: close\r\n\r\n")
                        StandardCharsets/US_ASCII)
        out (byte-array (+ (alength head) (alength b)))]
    (System/arraycopy head 0 out 0 (alength head))
    (System/arraycopy b 0 out (alength head) (alength b))
    out))

(def ^:private reasons {101 "Switching Protocols" 400 "Bad Request" 401 "Unauthorized"
                        403 "Forbidden" 404 "Not Found" 503 "Service Unavailable"})

(defn response-bytes
  "The bytes of the answer to `decide`'s decision."
  ^bytes [{:keys [upgrade status]}]
  (.getBytes (if upgrade
               (str "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    "Sec-WebSocket-Accept: " upgrade "\r\n\r\n")
               (str "HTTP/1.1 " status " " (reasons status) "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"))
             StandardCharsets/US_ASCII))

(defn read-head
  "Reads a request's head from `in`, up to and including its blank line:
  its text, without the blank line, or nil when the stream ends first or
  the head is longer than head-limit. Reads nothing past the blank line."
  [^InputStream in]
  (let [buf (java.io.ByteArrayOutputStream.)]
    (loop [last4 0]
      (let [b (.read in)]
        (cond
          (neg? b) nil
          (>= (.size buf) head-limit) nil
          :else
          (let [last4 (bit-and 0xffffffff (bit-or (bit-shift-left last4 8) b))]
            (.write buf b)
            (cond
              (= last4 0x0d0a0d0a) (let [s (.toString buf "ISO-8859-1")] (subs s 0 (- (count s) 4)))
              (= (bit-and last4 0xffff) 0x0a0a) (let [s (.toString buf "ISO-8859-1")] (subs s 0 (- (count s) 2)))
              :else (recur last4))))))))

;; --- frames

(def opcodes {:continuation 0 :text 1 :binary 2 :close 8 :ping 9 :pong 10})

(defn frame-bytes
  "One frame: FIN set, the opcode, and the payload, masked with the four
  bytes of `mask` when given (as a client sends) and plain when not (as
  the service sends). `fin?` false makes a fragment."
  (^bytes [opcode ^bytes payload] (frame-bytes opcode payload nil true))
  (^bytes [opcode ^bytes payload ^bytes mask fin?]
   (let [n (alength payload)
         len-bytes (cond (< n 126) 0 (< n 65536) 2 :else 8)
         head (+ 2 len-bytes (if mask 4 0))
         out (ByteBuffer/allocate (+ head n))
         mask-bit (if mask 0x80 0)]
     (.put out (unchecked-byte (bit-or (if fin? 0x80 0) (get opcodes opcode opcode))))
     (case len-bytes
       0 (.put out (unchecked-byte (bit-or mask-bit n)))
       2 (do (.put out (unchecked-byte (bit-or mask-bit 126))) (.putShort out (unchecked-short n)))
       8 (do (.put out (unchecked-byte (bit-or mask-bit 127))) (.putLong out n)))
     (when mask (.put out mask))
     (dotimes [i n]
       (.put out (unchecked-byte (if mask (bit-xor (aget payload i) (aget mask (mod i 4))) (aget payload i)))))
     (.array out))))

(defn text-frame ^bytes [^String s] (frame-bytes :text (.getBytes s StandardCharsets/UTF_8)))

(defn close-frame
  "A close frame with a status code."
  ^bytes [code]
  (frame-bytes :close (.array (doto (ByteBuffer/allocate 2) (.putShort (unchecked-short code))))))

(defn- utf8 [^bytes bs]
  (try (str (.decode (-> StandardCharsets/UTF_8 .newDecoder
                         (.onMalformedInput CodingErrorAction/REPORT)
                         (.onUnmappableCharacter CodingErrorAction/REPORT))
                     (ByteBuffer/wrap bs)))
       (catch CharacterCodingException _ nil)))

(def max-frames
  "How many frames one message may take, control frames between its
  fragments included."
  1024)

(def max-message-ms
  "How long one message may take, from its first byte to its last."
  30000)

(defn close-code-ok?
  "Whether a client may send this status in a close frame (RFC 6455
  7.4): the defined codes it may send, those IANA has registered since,
  and the ranges for libraries and applications. 1004, 1005, 1006 and
  1015 are reserved and never sent."
  [code]
  (or (contains? #{1000 1001 1002 1003 1007 1008 1009 1010 1011 1012 1013 1014} code)
      (<= 3000 code 4999)))

(defn- close-of
  "What a client's close frame comes to: 1000, the normal answer, or
  1002 when its payload breaks RFC 6455 (one byte, a status a client may
  not send, or a reason that is not UTF-8)."
  [^bytes payload]
  (let [n (alength payload)]
    (cond
      (zero? n) {:close 1000}
      (= 1 n) {:close 1002}
      (not (close-code-ok? (bit-and 0xffff (.getShort (ByteBuffer/wrap payload))))) {:close 1002}
      (nil? (utf8 (java.util.Arrays/copyOfRange payload 2 n))) {:close 1002}
      :else {:close 1000})))

(defn read-message
  "Reads frames from a client until a message is whole, answering pings
  with (pong! payload) on the way. Returns {:text s} for a text message,
  or {:close code} when the connection is to end: 1000 for the client's
  own close, or relay's reason: 1003 for a binary message, 1009 for one
  longer than :limit bytes or taking more than :max-frames frames, 1007
  for text that is not UTF-8, and 1002 for anything else RFC 6455
  forbids, such as an unmasked frame, a length not given in its shortest
  form or a malformed close; nil when the stream ends.

  A frame's declared length is checked against what is left of the
  limit before its payload is read, and a message's fragments go into
  one buffer, so nothing past the limit is ever held.

  (busy! t) is called with (now) as soon as the first byte of a message
  arrives, a ping or a close between messages included, and (busy! nil)
  once it is whole or the reader returns. Every read in between belongs
  to that message, so the caller can bound the whole of it, however the
  client stalls, by closing the stream; waiting for a message's first
  byte is not bounded."
  [^InputStream in {:keys [limit max-frames now busy!]
                    :or {max-frames max-frames now #(System/currentTimeMillis) busy! (fn [_])}}
   pong!]
  (let [din (DataInputStream. in)
        buf (java.io.ByteArrayOutputStream.)
        stop (fn [r] (busy! nil) r)]
    (try
      (loop [frames 0 started? false]
        (let [b0 (.readUnsignedByte din)
              ;; The message's first byte: from here on it is timed.
              _ (when (zero? frames) (busy! (now)))
              b1 (.readUnsignedByte din)
              fin? (bit-test b0 7)
              op (bit-and b0 0x0f)
              masked? (bit-test b1 7)
              n7 (bit-and b1 0x7f)
              n (case n7 126 (.readUnsignedShort din) 127 (.readLong din) n7)
              control? (>= op 8)
              frames (inc frames)]
          (cond
            (not= 0 (bit-and b0 0x70)) (stop {:close 1002})
            (not masked?) (stop {:close 1002})
            ;; The shortest form only, as RFC 6455 5.2 asks.
            (or (and (= 126 n7) (< n 126)) (and (= 127 n7) (< n 65536))) (stop {:close 1002})
            (neg? n) (stop {:close 1002})
            (and control? (or (not fin?) (> n 125))) (stop {:close 1002})
            (not (#{0 1 2 8 9 10} op)) (stop {:close 1002})
            (= 2 op) (stop {:close 1003})
            (and (= 0 op) (not started?)) (stop {:close 1002})
            (and (= 1 op) started?) (stop {:close 1002})
            ;; Against what is left, so a length near Long/MAX_VALUE
            ;; cannot overflow a sum.
            (and (not control?) (> n (- limit (.size buf)))) (stop {:close 1009})
            (> frames max-frames) (stop {:close 1009})
            :else
            (let [mask (byte-array 4)
                  _ (.readFully din mask)
                  payload (byte-array n)
                  _ (.readFully din payload)
                  _ (dotimes [i n] (aset payload i (unchecked-byte (bit-xor (aget payload i) (aget mask (mod i 4))))))]
              (case (int op)
                8 (stop (close-of payload))
                ;; A ping or pong between messages is done once read: what
                ;; follows is idle until the next first byte.
                9 (do (pong! payload) (if started? (recur frames true) (do (busy! nil) (recur 0 false))))
                10 (if started? (recur frames true) (do (busy! nil) (recur 0 false)))
                (do (.write buf payload)
                    (if-not fin?
                      (recur frames true)
                      (stop (if-let [s (utf8 (.toByteArray buf))] {:text s} {:close 1007})))))))))
      (catch EOFException _ (stop nil))
      (catch java.io.IOException _ (stop nil)))))

(defn write-frame!
  "Writes a frame's bytes to `out` and flushes, holding a lock on `out`
  so the writer and a pong never interleave."
  [^OutputStream out ^bytes bs]
  (locking out (.write out bs) (.flush out)))
