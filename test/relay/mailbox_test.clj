(ns relay.mailbox-test
  "Mail as data: parsing, the verdict on whether a mail is the
  operator's own (a property: no unverified mail is ever trusted, and
  every verified one is), MIME text, encoded words and threading."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.mailbox :as mailbox])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)))

(def operator "kira@example.org")
(def ours "mx.example.org")
(def opts {:operator operator :authserv-id ours})

(defn- mail [& header-lines]
  (str (str/join "\r\n" header-lines) "\r\n\r\nHello.\r\n"))

(defn- verdict [raw] (mailbox/verdict (mailbox/parse raw) opts))

(deftest the-operators-verified-mail-is-trusted
  (is (= {:trusted true :reason :verified :from operator}
         (verdict (mail "From: Kira <kira@example.org>" "Subject: hi"
                        (str "Authentication-Results: " ours "; dkim=pass header.d=example.org header.s=sel"))))))

(deftest anything-short-of-that-is-not
  (doseq [[why raw reason]
          [["another sender" (mail "From: eve@example.net" (str "Authentication-Results: " ours "; dkim=pass header.d=example.org")) :not-operator]
           ["no Authentication-Results" (mail "From: kira@example.org") :authentication-results]
           ["another server's" (mail "From: kira@example.org" "Authentication-Results: mx.evil.net; dkim=pass header.d=example.org") :authentication-results]
           ["two of ours" (mail "From: kira@example.org"
                                (str "Authentication-Results: " ours "; dkim=pass header.d=example.org")
                                (str "Authentication-Results: " ours "; dkim=fail header.d=example.org")) :authentication-results]
           ["dkim failed" (mail "From: kira@example.org" (str "Authentication-Results: " ours "; dkim=fail header.d=example.org")) :dkim]
           ["another domain signed" (mail "From: kira@example.org" (str "Authentication-Results: " ours "; dkim=pass header.d=evil.net")) :dkim]
           ["a subdomain signed" (mail "From: kira@example.org" (str "Authentication-Results: " ours "; dkim=pass header.d=mail.example.org")) :dkim]
           ["the pass is a comment" (mail "From: kira@example.org" (str "Authentication-Results: " ours "; dkim=fail (dkim=pass header.d=example.org) header.d=example.org")) :dkim]
           ["spf passed, not dkim" (mail "From: kira@example.org" (str "Authentication-Results: " ours "; spf=pass smtp.mailfrom=example.org")) :dkim]
           ["two From headers" (mail "From: kira@example.org" "From: eve@example.net" (str "Authentication-Results: " ours "; dkim=pass header.d=example.org")) :from]
           ["two addresses" (mail "From: kira@example.org, eve@example.net" (str "Authentication-Results: " ours "; dkim=pass header.d=example.org")) :from]
           ["the address is only the display name" (mail "From: \"kira@example.org\" <eve@example.net>" (str "Authentication-Results: " ours "; dkim=pass header.d=example.org")) :not-operator]
           ["a header hidden behind a bare LF" (str "From: kira@example.org\r\nX: a\nAuthentication-Results: " ours "; dkim=pass header.d=example.org\r\n\r\nHi\r\n") :malformed]
           ["a header hidden behind a bare CR" (str "From: kira@example.org\r\nX: a\rAuthentication-Results: " ours "; dkim=pass header.d=example.org\r\n\r\nHi\r\n") :malformed]
           ["bimi passed for the domain, dkim did not" (mail "From: kira@example.org" (str "Authentication-Results: " ours "; dkim=fail header.d=example.org; bimi=pass header.d=example.org")) :dkim]
           ["domainkeys passed, not dkim" (mail "From: kira@example.org" (str "Authentication-Results: " ours "; domainkeys=pass header.d=example.org")) :dkim]
           ["two header.d in one dkim result" (mail "From: kira@example.org" (str "Authentication-Results: " ours "; dkim=pass header.d=example.org header.d=evil.net")) :dkim]
           ["the header section ends with a bare LF" (str "From: kira@example.org\r\nAuthentication-Results: " ours "; dkim=pass header.d=example.org\r\n\nHi\r\n\r\nMore\r\n") :malformed]
           ["the blank line starts with a bare LF" (str "From: kira@example.org\r\nAuthentication-Results: " ours "; dkim=pass header.d=example.org\n\r\nHi\r\n") :malformed]
           ["a name with a space before its colon" (mail "From: kira@example.org" (str "Authentication-Results : " ours "; dkim=pass header.d=example.org")) :malformed]
           ["unbalanced comment" (mail "From: kira@example.org" (str "Authentication-Results: " ours "; dkim=pass header.d=example.org")
                                       "Authentication-Results: (mx.example.org; dkim=fail") :unreadable-authentication-results]]]
    (testing why
      (let [v (verdict raw)]
        (is (false? (:trusted v)))
        (is (= reason (:reason v)))))))

(deftest no-configuration-means-no-trust
  (let [raw (mail "From: kira@example.org" (str "Authentication-Results: " ours "; dkim=pass header.d=example.org"))]
    (is (false? (:trusted (mailbox/verdict (mailbox/parse raw) {:operator nil :authserv-id ours}))))
    (is (false? (:trusted (mailbox/verdict (mailbox/parse raw) {:operator operator :authserv-id nil}))))))

(deftest folding-comments-quotes-and-case-are-read
  (is (:trusted (verdict (mail "From: \"Howe, Kira\" (me)\r\n <KIRA@Example.org>"
                               (str "Authentication-Results: MX.example.org 1;\r\n\tdkim=pass (good signature)\r\n header.d=\"example.org\" header.i=@example.org;\r\n spf=pass")))))
  (is (= "kira@example.org" (mailbox/mailbox-address "Kira (the (nested) one) <kira@example.org>")))
  (is (nil? (mailbox/mailbox-address "Undisclosed: kira@example.org;")))
  (is (nil? (mailbox/mailbox-address "kira@example.org <eve@example.net>"))))

;; --- the property

(def ^:private gen-from
  ;; [header lines, whether they name exactly the operator]
  (gen/elements [[["From: kira@example.org"] true]
                 [["From: Kira Howe <kira@example.org>"] true]
                 [["From: \"Kira\" <KIRA@EXAMPLE.ORG>"] true]
                 [["From: kira@example.org (Kira)"] true]
                 [["From: eve@example.net"] false]
                 [["From: \"kira@example.org\" <eve@example.net>"] false]
                 [["From: Kira <kira@example.org.evil.net>"] false]
                 [["From: kira@example.org, eve@example.net"] false]
                 [["From: kira@example.org" "From: kira@example.org"] false]
                 [["From: (kira@example.org) eve@example.net"] false]
                 [[] false]]))

(def ^:private gen-result
  ;; {:text one result :pass bool}: a method, its result, and header.d
  ;; props, none, one or a repeat. Only dkim=pass with exactly one
  ;; header.d, the operator's domain, is a pass; bimi or domainkeys with
  ;; header.d, or two header.d values, never are.
  (gen/let [method (gen/frequency [[4 (gen/return "dkim")] [1 (gen/elements ["bimi" "domainkeys" "spf" "dmarc"])]])
            result (gen/frequency [[5 (gen/return "pass")] [1 (gen/elements ["fail" "neutral" "none" "temperror"])]])
            ds (gen/frequency [[6 (gen/vector (gen/elements [["example.org" true] ["EXAMPLE.ORG" true] ["evil.net" false] ["mail.example.org" false]]) 1)]
                               [1 (gen/vector (gen/elements [["example.org" true] ["evil.net" false]]) 0 3)]])
            decoy (gen/elements ["" " (dkim=pass header.d=example.org)" " (forged; dkim=pass)"])
            other (gen/elements ["" " header.s=sel" " smtp.mailfrom=example.org" " header.from=example.org"])]
    {:text (str method "=" result decoy (str/join (map #(str " header.d=" (first %)) ds)) other)
     :pass (and (= "dkim" method) (= "pass" result) (= 1 (count ds)) (second (first ds)))}))

(def ^:private gen-ar
  ;; {:line text :ours bool :pass bool}: one Authentication-Results with
  ;; one to three results.
  (gen/let [id (gen/frequency [[3 (gen/elements [[ours true] ["MX.EXAMPLE.ORG" true]])]
                                [2 (gen/elements [["mx.evil.net" false] ["example.org" false]])]])
            results (gen/vector gen-result 1 3)
            fold gen/boolean]
    (let [[id-s ours?] id
          v (str id-s "; " (str/join "; " (map :text results)))
          v (if fold (str/replace-first v " header.d" "\r\n\theader.d") v)]
      {:line (str "Authentication-Results: " v) :ours ours? :pass (boolean (some :pass results))})))

(def gen-mail
  (gen/let [[from-lines from-ok] (gen/frequency [[1 gen-from] [1 (gen/elements [[["From: kira@example.org"] true]
                                                                                [["From: Kira <kira@example.org>"] true]])]])
            ars (gen/vector gen-ar 0 3)
            bare (gen/frequency [[6 (gen/return nil)] [1 (gen/elements ["\n" "\r"])]])
            ;; How the header section ends: CRLF CRLF, or with a bare LF
            ;; in the blank line, which some readers take as its end and
            ;; some do not.
            sep (gen/frequency [[8 (gen/return "\r\n\r\n")] [1 (gen/elements ["\r\n\n" "\n\r\n"])]])
            body (gen/elements ["Body\r\n" "Body\r\n\r\nMore\r\n"])]
    (let [lines (vec (concat from-lines (map :line ars) ["Subject: hello" "Message-ID: <a@b>"]))
          head (str/join "\r\n" lines)
          ;; A bare line end somewhere in the header section, or none.
          head (if bare (str head "\r\nX-Note: one" bare "two") head)]
      {:raw (str head sep body)
       :expected (and from-ok (nil? bare) (= "\r\n\r\n" sep)
                      (= 1 (count (filter :ours ars)))
                      (:pass (first (filter :ours ars))))})))

(defspec a-mail-is-trusted-exactly-when-it-is-verified 1000
  (prop/for-all [{:keys [raw expected]} gen-mail]
    (= (boolean expected) (:trusted (verdict raw)))))

(deftest the-generator-makes-both-kinds
  ;; Seeded, so the count is the same every run: unseeded, a run now and
  ;; then made 19 of one kind and failed.
  (let [xs (map #(gen/generate gen-mail (mod % 100) %) (range 400))]
    (is (< 20 (count (filter :expected xs)) 380))))

(defspec no-unverified-mail-is-ever-trusted-whatever-its-bytes 500
  ;; Any text at all, and any text after a verified mail's headers: only
  ;; the headers of a verified mail make it trusted, and random bytes
  ;; never do.
  (prop/for-all [junk gen/string
                 extra (gen/vector (gen/elements ["X-A: b" "Authentication-Results: mx.evil.net; dkim=pass header.d=example.org"
                                                  "Received: from x" "From: eve@example.net"]) 0 3)]
    (and (not (:trusted (verdict junk)))
         (let [raw (str (str/join "\r\n" (concat ["From: kira@example.org"
                                                  (str "Authentication-Results: " ours "; dkim=pass header.d=example.org")]
                                                 extra))
                        "\r\n\r\n" junk)]
           (= (not (some #{"From: eve@example.net"} extra))
              (:trusted (verdict raw)))))))

;; --- what a mail says

(deftest plain-text-in-its-charset-and-transfer-encoding
  (let [b64 (.encodeToString (Base64/getMimeEncoder) (.getBytes "Grüße aus Köln\r\n" StandardCharsets/UTF_8))]
    (is (= "Grüße aus Köln"
           (:text (mailbox/text-of (mailbox/parse (mailbox/bytes->raw (.getBytes (str "Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" b64) StandardCharsets/ISO_8859_1))))))))
  (is (= "café = 3€"
         (:text (mailbox/text-of (mailbox/parse "Content-Type: text/plain; charset=\"UTF-8\"\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\ncaf=C3=A9 =3D 3=E2=82=\r\n=AC\r\n")))))
  (is (= "olé" (:text (mailbox/text-of (mailbox/parse (mailbox/bytes->raw (.getBytes "Content-Type: text/plain; charset=iso-8859-1\r\n\r\nolé" StandardCharsets/ISO_8859_1))))))))

(deftest multipart-prefers-plain-and-lists-attachments
  (let [raw (str "Content-Type: multipart/mixed; boundary=\"outer\"\r\n\r\n"
                 "preamble\r\n--outer\r\n"
                 "Content-Type: multipart/alternative; boundary=inner\r\n\r\n"
                 "--inner\r\nContent-Type: text/plain\r\n\r\nThe plain words.\r\n"
                 "--inner\r\nContent-Type: text/html\r\n\r\n<p>The <b>HTML</b></p>\r\n--inner--\r\n"
                 "--outer\r\nContent-Type: application/pdf; name=\"set list.pdf\"\r\nContent-Disposition: attachment; filename=\"set list.pdf\"\r\nContent-Transfer-Encoding: base64\r\n\r\nJVBERi0x\r\n"
                 "--outer--\r\nepilogue that nobody shows\r\n")
        {:keys [text attachments]} (mailbox/text-of (mailbox/parse raw))]
    (is (= "The plain words." text))
    (is (= [{:name "set list.pdf" :type "application/pdf" :size 6}] attachments)))
  (testing "HTML alone is made plain"
    (is (= "Hello\nthere & you"
           (:text (mailbox/text-of (mailbox/parse "Content-Type: text/html\r\n\r\n<html><style>p{}</style><p>Hello</p>there &amp; you</html>")))))))

(deftest encoded-words-are-decoded
  (is (= "Grüße, Kira" (mailbox/decode-words "=?UTF-8?B?R3LDvMOfZQ==?=, Kira")))
  (is (= "a b c" (mailbox/decode-words "=?iso-8859-1?Q?a_b?= =?utf-8?q?_c?=")))
  (is (= "=?x?B?!!?=" (mailbox/decode-words "=?x?B?!!?="))))

(defspec quoted-printable-undoes-its-encoding 300
  (prop/for-all [bs (gen/vector (gen/choose 0 255) 0 200)]
    (let [enc (str/join (map-indexed (fn [i b]
                                       (str (if (or (< b 33) (> b 126) (= b 61)) (format "=%02X" b) (char b))
                                            (when (zero? (mod (inc i) 20)) "=\r\n")))
                                     bs))]
      (= (vec bs) (mapv #(bit-and 0xff (int %)) (mailbox/quoted-printable enc))))))

(deftest a-reply-loses-its-quoted-tail-only
  (is (= "The Old Hall, please." (mailbox/without-quoted-tail "The Old Hall, please.\n\nOn Mon, Kira's agent wrote:\n> Which venue?\n> x\n")))
  (is (= "> Which venue?\nThe Old Hall.\n> And the date?\nThe 14th."
         (mailbox/without-quoted-tail "> Which venue?\nThe Old Hall.\n> And the date?\nThe 14th.")))
  (is (= "> only quotes" (mailbox/without-quoted-tail "> only quotes"))))

(deftest relay-message-ids-are-found-in-threading-headers
  (let [m (mailbox/parse (mail "In-Reply-To: <relay.12.1700000000000@agent.example.org>"
                               "References: <x@y> <relay.3-4.1600000000000@agent.example.org>\r\n <relay.12.1700000000000@agent.example.org>"))]
    (is (= [{:ids [12] :millis 1700000000000 :domain "agent.example.org"}
            {:ids [3 4] :millis 1600000000000 :domain "agent.example.org"}]
           (mailbox/thread-ids m)))))
