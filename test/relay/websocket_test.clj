(ns relay.websocket-test
  "The phone listener's decisions and framing, without a network: who is
  let in before the upgrade, and how messages are read and written."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.config :as config]
            [relay.websocket :as ws])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)))

(def settings {:token "s3cret-token" :allowed-origins ["https://allowed.example"]})

(def key16 "dGhlIHNhbXBsZSBub25jZQ==")

(defn- head [& {:keys [method version headers] :or {method "GET" version "HTTP/1.1"}}]
  (str/join "\r\n" (cons (str method " / " version)
                         (for [[k v] headers] (str k ": " v)))))

(def upgrade-headers
  [["Host" "127.0.0.1:8765"] ["Upgrade" "websocket"] ["Connection" "Upgrade"]
   ["Sec-WebSocket-Key" key16] ["Sec-WebSocket-Version" "13"]])

(defn- decide [& headers]
  (ws/decide (ws/parse-head (head :headers (concat upgrade-headers headers))) settings))

(deftest the-token-and-the-origin-decide-the-upgrade
  (testing "the token lets a client in, with the accept key RFC 6455 gives"
    (is (= {:upgrade "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="} (decide ["Authorization" "Bearer s3cret-token"])))
    (is (:upgrade (decide ["authorization" "bearer s3cret-token"])) "the scheme and header name in any case"))
  (testing "no token, or a wrong one, is a bare 401"
    (is (= {:status 401} (decide)))
    (is (= {:status 401} (decide ["Authorization" "Bearer s3cret-toke"])))
    (is (= {:status 401} (decide ["Authorization" "Bearer s3cret-token2"])))
    (is (= {:status 401} (decide ["Authorization" "Basic s3cret-token"])))
    (is (= {:status 401} (decide ["Authorization" "Bearer"])))
    (is (= {:status 401} (decide ["Authorization" "Bearer s3cret-token"] ["Authorization" "Bearer s3cret-token"]))
        "two Authorization headers are one too many"))
  (testing "any Origin is refused unless it is allowed exactly, before the token is looked at"
    (is (= {:status 403} (decide ["Origin" "https://evil.example"] ["Authorization" "Bearer s3cret-token"])))
    (is (= {:status 403} (decide ["Origin" "null"] ["Authorization" "Bearer s3cret-token"])))
    (is (= {:status 403} (decide ["Origin" "https://allowed.example/"] ["Authorization" "Bearer s3cret-token"])))
    (is (= {:status 403} (decide ["Origin" "https://evil.example"])))
    (is (= {:status 403} (decide ["Origin" "https://allowed.example"] ["Origin" "https://allowed.example"]
                                 ["Authorization" "Bearer s3cret-token"])))
    (is (:upgrade (decide ["Origin" "https://allowed.example"] ["Authorization" "Bearer s3cret-token"]))))
  (testing "a request that is not an upgrade is a 404, whoever asks"
    (is (= {:status 404} (ws/decide (ws/parse-head (head :headers [["Authorization" "Bearer s3cret-token"]])) settings)))
    (is (= {:status 404} (ws/decide (ws/parse-head (head :method "POST")) settings))))
  (testing "an upgrade RFC 6455 refuses is a 400, once the token is good"
    (let [auth ["Authorization" "Bearer s3cret-token"]
          without (fn [h] (remove #(= h (first %)) upgrade-headers))]
      (is (= {:status 400} (ws/decide (ws/parse-head (head :method "POST" :headers (conj upgrade-headers auth))) settings)))
      (is (= {:status 400} (ws/decide (ws/parse-head (head :headers (conj (without "Sec-WebSocket-Version") auth
                                                                          ["Sec-WebSocket-Version" "8"]))) settings)))
      (is (= {:status 400} (ws/decide (ws/parse-head (head :headers (conj (without "Sec-WebSocket-Key") auth
                                                                          ["Sec-WebSocket-Key" "short"]))) settings)))
      (is (= {:status 400} (ws/decide (ws/parse-head (head :headers (conj (without "Connection") auth))) settings)))))
  (testing "an unreadable request is a 400"
    (is (= {:status 400} (ws/decide nil settings)))
    (is (nil? (ws/parse-head "not http")))
    (is (nil? (ws/parse-head "GET / HTTP/1.1\r\nno colon here")))))

(deftest a-refusal-says-nothing-but-its-status
  (doseq [status [400 401 403 404 503]]
    (let [text (String. (ws/response-bytes {:status status}) StandardCharsets/US_ASCII)]
      (is (str/starts-with? text (str "HTTP/1.1 " status " ")))
      (is (str/ends-with? text "Content-Length: 0\r\nConnection: close\r\n\r\n"))
      (is (not (str/includes? (str/lower-case text) "relay")))
      (is (not (str/includes? (str/lower-case text) "server:"))))))

(defspec the-token-compare-is-string-equality 300
  (prop/for-all [a gen/string
                 b gen/string
                 same? gen/boolean]
    (let [b (if same? a b)]
      (= (= a b) (ws/token-matches? a b)))))

(def gen-header
  (gen/tuple (gen/elements ["Upgrade" "Connection" "Origin" "Authorization" "Sec-WebSocket-Key"
                            "Sec-WebSocket-Version" "Host" "X-Other"])
             (gen/one-of [(gen/elements ["websocket" "Upgrade" "13" key16 "Bearer s3cret-token"
                                         "https://allowed.example" "https://evil.example" ""])
                          gen/string-ascii])))

(defspec the-decision-is-total-and-an-origin-never-gets-past-unless-allowed 500
  (prop/for-all [method (gen/elements ["GET" "POST" "PUT"])
                 headers (gen/vector gen-header 0 10)
                 garbage gen/string]
    (let [h (ws/parse-head (head :method method :headers headers))
          d (ws/decide h settings)
          origins (keep #(when (= "Origin" (first %)) (str/trim (second %))) headers)
          d2 (ws/decide (ws/parse-head garbage) settings)]
      (and (or (string? (:upgrade d)) (#{400 401 403 404} (:status d)))
           (or (string? (:upgrade d2)) (#{400 401 403 404} (:status d2)))
           (or (not (:upgrade d)) (empty? origins) (= ["https://allowed.example"] origins))
           (or (not (:upgrade d)) (some #(= ["Authorization" "Bearer s3cret-token"] %) headers))))))

;; --- frames

(defn- client-frames
  "A text message as a client sends it: masked, split into fragments at
  the given cuts, with pings between them."
  [^String text cuts]
  (let [bs (.getBytes text StandardCharsets/UTF_8)
        n (alength bs)
        points (vec (distinct (sort (concat [0] (filter #(< 0 % n) cuts) [n]))))
        pieces (map (fn [[a b]] (java.util.Arrays/copyOfRange bs (int a) (int b))) (partition 2 1 points))
        pieces (if (empty? pieces) [(byte-array 0)] pieces)
        mask (byte-array [1 2 3 4])
        out (ByteArrayOutputStream.)]
    (doseq [[i p] (map-indexed vector pieces)]
      (.write out (ws/frame-bytes (if (zero? i) :text :continuation) p mask (= i (dec (count pieces)))))
      (.write out (ws/frame-bytes :ping (.getBytes "p" StandardCharsets/UTF_8) mask true)))
    (.toByteArray out)))

(defn- read-all [^bytes bs limit & [opts]]
  (let [in (ByteArrayInputStream. bs) pongs (atom 0)]
    (loop [acc []]
      (let [m (ws/read-message in (merge {:limit limit} opts) (fn [_] (swap! pongs inc)))]
        (if (:text m) (recur (conj acc m)) {:messages acc :end m :pongs @pongs})))))

(defspec a-message-reads-back-whole-however-it-was-split 200
  (prop/for-all [text gen/string
                 cuts (gen/vector gen/nat 0 5)]
    (let [{:keys [messages end]} (read-all (client-frames text cuts) 1000000)]
      (and (= [{:text text}] messages) (nil? end)))))

(deftest a-reader-closes-on-what-it-does-not-take
  (let [mask (byte-array [9 8 7 6])
        frame (fn [op payload & [m fin?]] (ws/frame-bytes op payload (or m mask) (if (nil? fin?) true fin?)))
        cat (fn [& xs] (let [o (ByteArrayOutputStream.)] (doseq [x xs] (.write o ^bytes x)) (.toByteArray o)))
        bytes-of (fn [^String s] (.getBytes s StandardCharsets/UTF_8))]
    (testing "a binary message"
      (is (= {:close 1003} (:end (read-all (frame :binary (bytes-of "x")) 100)))))
    (testing "a message over the limit, known from its declared length before its payload is read"
      (let [header (java.util.Arrays/copyOfRange (frame :text (byte-array 70000)) 0 14)]
        (is (= {:close 1009} (:end (read-all header 65536)))))
      (is (= {:close 1009} (:end (read-all (cat (frame :text (byte-array 60) mask false)
                                                (frame :continuation (byte-array 60)))
                                           100)))
          "fragments count together"))
    (testing "an unmasked frame"
      (is (= {:close 1002} (:end (read-all (ws/text-frame "{}") 100)))))
    (testing "text that is not UTF-8"
      (is (= {:close 1007} (:end (read-all (frame :text (byte-array [(unchecked-byte 0xc3) (unchecked-byte 0x28)])) 100)))))
    (testing "a continuation with nothing to continue, and a new message inside another"
      (is (= {:close 1002} (:end (read-all (frame :continuation (bytes-of "x")) 100))))
      (is (= {:close 1002} (:end (read-all (cat (frame :text (bytes-of "a") mask false) (frame :text (bytes-of "b"))) 100)))))
    (testing "a fragmented or long control frame"
      (is (= {:close 1002} (:end (read-all (frame :ping (bytes-of "x") mask false) 100))))
      (is (= {:close 1002} (:end (read-all (frame :ping (byte-array 126)) 1000)))))
    (testing "the client's close is answered with a normal one, and the end of the stream is nil"
      (is (= {:close 1000} (:end (read-all (frame :close (byte-array 0)) 100))))
      (is (nil? (:end (read-all (byte-array 0) 100)))))
    (testing "pings are answered on the way"
      (is (= 3 (:pongs (read-all (client-frames "abc" [1 2]) 100)))))))

(deftest the-service-writes-each-line-as-one-text-frame
  (doseq [n [0 125 126 65535 65536 70000]]
    (let [s (apply str (repeat n "a"))
          bs (ws/text-frame s)]
      (is (= 0x81 (bit-and 0xff (aget bs 0))) "FIN and text")
      (is (zero? (bit-and 0x80 (aget bs 1))) "a server's frame is not masked")
      (is (= s (String. bs (int (- (alength bs) n)) (int n) StandardCharsets/UTF_8))))))

(deftest a-head-is-read-up-to-its-blank-line-and-no-further
  (let [in (ByteArrayInputStream. (.getBytes "GET / HTTP/1.1\r\nA: b\r\n\r\nREST" StandardCharsets/ISO_8859_1))]
    (is (= "GET / HTTP/1.1\r\nA: b" (ws/read-head in)))
    (is (= "REST" (String. (.readAllBytes in) StandardCharsets/ISO_8859_1))))
  (is (nil? (ws/read-head (ByteArrayInputStream. (.getBytes "GET / HTTP/1.1\r\nA: b\r\n" StandardCharsets/ISO_8859_1))))
      "a stream that ends first")
  (is (nil? (ws/read-head (ByteArrayInputStream. (.getBytes (apply str "GET / HTTP/1.1\r\nA: " (repeat 20000 "b"))
                                                            StandardCharsets/ISO_8859_1))))
      "a head too long"))

;; --- settings

(def complete {:timezone "UTC" :default-harness :agentloop
               :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"]}}
               :notify {:kind :none}})

(deftest the-websocket-settings-are-checked
  (testing "no :serve, or no :websocket in it, is no listener"
    (is (nil? (:serve (config/resolve-config complete))))
    (is (= {} (:serve (config/resolve-config (assoc complete :serve {}))))))
  (testing "the defaults: the hub for a session/new with no role, and no browser origin"
    (is (= {:websocket {:port 8765 :token-command ["security" "find-generic-password" "-s" "relay-websocket" "-w"]
                        :default-role "hub" :allowed-origins []}}
           (:serve (config/resolve-config (assoc complete :serve {:websocket {:port 8765 :token-command ["security" "find-generic-password" "-s" "relay-websocket" "-w"]}}))))))
  (testing "what is given is kept"
    (is (= {:port 1 :token-command ["cat" "/t"] :default-role "clerk" :allowed-origins ["https://a.example"]}
           (get-in (config/resolve-config (assoc complete :serve {:websocket {:port 1 :token-command ["cat" "/t"]
                                                                               :default-role "clerk"
                                                                               :allowed-origins ["https://a.example"]}}))
                   [:serve :websocket]))))
  (doseq [[bad why] [[{:websocket {:token-command ["cat" "/t"]}} #":port"]
                     [{:websocket {:port 0 :token-command ["cat" "/t"]}} #":port"]
                     [{:websocket {:port 70000 :token-command ["cat" "/t"]}} #":port"]
                     [{:websocket {:port "8765" :token-command ["cat" "/t"]}} #":port"]
                     [{:websocket {:port 8765}} #":token-command"]
                     [{:websocket {:port 8765 :token-command []}} #":token-command"]
                     [{:websocket {:port 8765 :token-command "cat /t"}} #":token-command"]
                     [{:websocket {:port 8765 :token-command ["cat" ""]}} #":token-command"]
                     [{:websocket {:port 8765 :token-command ["cat" "/t"] :default-role "Not A Role"}} #":default-role"]
                     [{:websocket {:port 8765 :token-command ["cat" "/t"] :default-role "relay"}} #":default-role"]
                     [{:websocket {:port 8765 :token-command ["cat" "/t"] :allowed-origins "https://a"}} #":allowed-origins"]
                     [{:websocket {:port 8765 :token-command ["cat" "/t"] :allowed-origins [""]}} #":allowed-origins"]
                     [{:websocket {:port 8765 :token-command ["cat" "/t"] :host "0.0.0.0"}} #"takes :port"]
                     [{:websocket nil} #"takes :port"]
                     [{:socket "/x"} #":serve is"]
                     [nil #":serve is"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo why (config/resolve-config (assoc complete :serve bad)))
        (pr-str bad))))

;; --- Codex round 1

(defn- frames-of [& xs]
  (let [o (ByteArrayOutputStream.)] (doseq [x xs] (.write o ^bytes x)) (.toByteArray o)))

(def ^:private m4 (byte-array [5 6 7 8]))

(deftest a-message-is-bounded-in-frames-and-in-time
  (testing "endless empty continuations end at max-frames, however small the limit"
    (let [empties (repeat 2000 (ws/frame-bytes :continuation (byte-array 0) m4 false))
          bs (apply frames-of (ws/frame-bytes :text (byte-array 0) m4 false) empties)]
      (is (= {:close 1009} (:end (read-all bs 0))))))
  (testing "max-frames frames is still a message"
    (let [bs (apply frames-of (ws/frame-bytes :text (.getBytes "a") m4 false)
                    (concat (repeat (- ws/max-frames 2) (ws/frame-bytes :continuation (byte-array 0) m4 false))
                            [(ws/frame-bytes :continuation (.getBytes "b") m4 true)]))]
      (is (= [{:text "ab"}] (:messages (read-all bs 100))))))
  (testing "a message that takes longer than max-ms ends with 1008, and the caller is told when it began and ended"
    (let [clock (atom 0)
          seen (atom [])
          bs (frames-of (ws/frame-bytes :text (.getBytes "a") m4 false)
                        (ws/frame-bytes :continuation (.getBytes "b") m4 false)
                        (ws/frame-bytes :continuation (.getBytes "c") m4 true))
          in (ByteArrayInputStream. bs)
          r (ws/read-message in {:limit 100 :max-ms 1000
                                 :now #(swap! clock + 600)
                                 :partial! #(swap! seen conj %)}
                             (fn [_]))]
      (is (= {:close 1008} r))
      (is (= [600 nil] @seen)))))

(deftest a-length-near-the-largest-long-is-refused-not-overflowed
  (let [header (doto (java.nio.ByteBuffer/allocate 14)
                 (.put (unchecked-byte 0x80)) (.put (unchecked-byte (bit-or 0x80 127)))
                 (.putLong Long/MAX_VALUE) (.put m4))
        bs (frames-of (ws/frame-bytes :text (.getBytes "x") m4 false) (.array header))]
    (is (= {:close 1009} (:end (read-all bs (* 8 1024 1024)))))))

(deftest a-reader-is-strict-about-close-frames-and-lengths
  (let [close (fn [^bytes payload] (:end (read-all (ws/frame-bytes :close payload m4 true) 100)))
        status (fn [code & [^bytes reason]]
                 (let [r (or reason (byte-array 0))]
                   (.array (doto (java.nio.ByteBuffer/allocate (+ 2 (alength r))) (.putShort (unchecked-short code)) (.put r)))))]
    (testing "a close of one byte"
      (is (= {:close 1002} (close (byte-array [3])))))
    (testing "statuses a client may not send"
      (doseq [code [0 999 1004 1005 1006 1015 1016 2999 5000 65535]]
        (is (= {:close 1002} (close (status code))) (str code))))
    (testing "statuses it may"
      (doseq [code [1000 1001 1002 1003 1007 1008 1009 1010 1011 1012 1013 1014 3000 4999]]
        (is (= {:close 1000} (close (status code))) (str code)))
      (is (= {:close 1000} (close (status 1000 (.getBytes "bye" StandardCharsets/UTF_8))))))
    (testing "a reason that is not UTF-8"
      (is (= {:close 1002} (close (status 1000 (byte-array [(unchecked-byte 0xc3) (unchecked-byte 0x28)]))))))
    (testing "a length not in its shortest form"
      (let [short16 (doto (java.nio.ByteBuffer/allocate 9)
                      (.put (unchecked-byte 0x81)) (.put (unchecked-byte (bit-or 0x80 126)))
                      (.putShort (short 1)) (.put m4) (.put (unchecked-byte (bit-xor (int \x) 5))))
            short64 (doto (java.nio.ByteBuffer/allocate 15)
                      (.put (unchecked-byte 0x81)) (.put (unchecked-byte (bit-or 0x80 127)))
                      (.putLong 1) (.put m4) (.put (unchecked-byte (bit-xor (int \x) 5))))]
        (is (= {:close 1002} (:end (read-all (.array short16) 100))))
        (is (= {:close 1002} (:end (read-all (.array short64) 100))))
        (is (= [{:text "x"}] (:messages (read-all (ws/frame-bytes :text (.getBytes "x") m4 true) 100)))
            "the same frame in its shortest form is read")))))
