(ns relay.channels-test
  "Each channel's renderer, and I2: whatever a model gives tell_operator
  and however relay.edn routes it, a delivery goes only to its channel's
  configured destination. An email has exactly one recipient, the
  operator's configured address, no Cc or Bcc, is text/plain only, and
  carries no header the model chose but the subject."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.channels :as channels]
            [relay.core :as core]
            [relay.core-test :as ct]
            [relay.messages :as messages]
            [relay.refs :as refs]
            [relay.test-support :as support])
  (:import (java.nio.charset StandardCharsets)
           (java.time Instant)
           (java.util Base64)))

(def agent-address "agent@example.org")
(def operator-address "kira@example.org")

(def mail-channel {:kind :email :url "smtps://smtp.example.org:465" :from agent-address :to operator-address
                   :user agent-address :credentials-command ["printf" "pw"]})
(def phone-channel {:kind :push :via :ntfy :url "https://ntfy.example.org" :topic "relay" :link "relay://messages/{id}"})

;; --- reading an email back

(defn- unfold [header-block]
  (str/replace header-block #"\r\n[ \t]" " "))

(defn- headers
  "The header block of a rendered email as [[name value] ...], unfolded."
  [text]
  (let [block (first (str/split text #"\r\n\r\n" 2))]
    (for [line (str/split (unfold block) #"\r\n")]
      (let [[_ k v] (re-matches #"([A-Za-z-]+): (.*)" line)]
        [k v]))))

(defn- decode-words
  "An RFC 2047 header value back as text."
  [v]
  (if-let [words (seq (re-seq #"=\?UTF-8\?B\?([A-Za-z0-9+/=]*)\?=" v))]
    (apply str (for [[_ b] words] (String. (.decode (Base64/getDecoder) ^String b) StandardCharsets/UTF_8)))
    v))

(defn- body [text]
  (let [b64 (second (str/split text #"\r\n\r\n" 2))]
    (String. (.decode (Base64/getMimeDecoder) ^String b64) StandardCharsets/UTF_8)))

;; --- what a model can give

(def ^:private gen-hostile-text
  ;; Header injection, addresses, line ends of every kind, hidden and
  ;; bidi characters, refs, and plain Unicode.
  (gen/fmap str/join
            (gen/vector (gen/one-of [gen/string
                                     (gen/elements ["\r\n" "\n" "\r" "\r\n\r\n" " " "\u0085"
                                                    "Bcc: eve@example.net" "Cc: eve@example.net"
                                                    "To: eve@example.net" "\r\nBcc: eve@example.net\r\n"
                                                    "Content-Type: text/html" "<b>hi</b>" "=?UTF-8?B?QmNjOg==?="
                                                    "eve@example.net" "https://evil.example/x?d=" "‮" "​"
                                                    "😀" "café" "[[fact:1]]" "." "\r\n.\r\n"])])
                        0 8)))

(def ^:private gen-hostile-line
  ;; The same, without a line feed, so tell_operator takes it as a
  ;; subject: carriage returns, NEL, the line and paragraph separators,
  ;; encoded words and header names still reach the mail path.
  (gen/fmap #(str/replace (str/join %) "\n" "")
            (gen/vector (gen/one-of [(gen/resize 30 gen/string)
                                     (gen/elements ["\r" " " "\u0085" "\u2028" "\u2029" "\rBcc: eve@example.net\r"
                                                    "Bcc: eve@example.net" "Cc: eve@example.net" "To: eve@example.net"
                                                    "Content-Type: text/html" "<b>hi</b>" "=?UTF-8?B?QmNjOg==?="
                                                    "=?UTF-8?B?DQpCY2M6IGV2ZUBleGFtcGxlLm5ldA==?="
                                                    "eve@example.net" "https://evil.example/x?d=" "\u202e" "\u200b"
                                                    "😀" "café" "[[fact:1]]" "." "\r.\r"])])
                        ;; Four at most keeps it under the subject's limit.
                        1 4)))

(defn- said? [s] (not (str/blank? (messages/squeeze-line s))))

(def ^:private gen-accepted-input
  ;; What tell_operator takes: a subject on one line, something to say,
  ;; a real urgency. Hostile all the same.
  (gen/hash-map :subject (gen/such-that said? gen-hostile-line 100)
                :body (gen/such-that said? gen-hostile-text 100)
                :items (gen/vector (gen/such-that said? gen-hostile-text 100) 0 3)
                :urgency (gen/elements [nil "low" "normal" "high"])))

(def ^:private gen-refused-input
  ;; What it refuses: a line feed in the subject, an urgency that is not
  ;; one, or nothing to say.
  (gen/let [input gen-accepted-input
            how (gen/elements [:subject-lines :urgency :nothing])]
    (case how
      :subject-lines (update input :subject #(str % "\r\nBcc: eve@example.net"))
      :urgency (assoc input :urgency "now")
      :nothing (assoc input :body " \r\n " :items []))))

(def ^:private gen-input
  (gen/frequency [[9 (gen/fmap #(assoc % :accepted true) gen-accepted-input)]
                  [1 (gen/fmap #(assoc % :accepted false) gen-refused-input)]]))

(def ^:private gen-routes
  (gen/vector (gen/let [chs (gen/fmap vec (gen/set (gen/elements ["mail" "phone"]) {:min-elements 1}))
                        urgencies (gen/one-of [(gen/return nil) (gen/fmap vec (gen/set (gen/elements messages/urgencies) {:min-elements 1}))])
                        digest gen/boolean]
                (cond-> {:channels chs}
                  urgencies (assoc :urgencies urgencies)
                  digest (assoc :at :digest)))
              0 4))

(def ^:private expected-headers
  ["From" "To" "Subject" "Date" "Message-ID" "MIME-Version" "Content-Type" "Content-Transfer-Encoding"])

(defn- told
  "The message tell_operator makes of `input` under `settings`, as the
  state keeps it, or nil when relay refused it. The tool's own path:
  sections from the body and items, refs read out of the text."
  [settings {:keys [subject body items urgency]}]
  (let [cfg (assoc ct/cfg :messages settings)
        w (-> (ct/world)
              (ct/run "operator" :role/add {:id "clerk" :title "Clerk" :instructions "x"})
              (ct/run "operator" :memo/send {:to "clerk" :subject "s" :body "b"})
              (ct/run "dispatcher" :wake/start {:role "clerk"}))
        wake (support/calling-wake (:state w) "clerk")
        sections (mapv (fn [sec] (update sec :items #(mapv (fn [t] {:text t :refs (mapv :ref (refs/parse t))}) %)))
                       (messages/body-sections body items))]
    (try
      (let [[state] (core/run-command (:state w) {:type :message/send :actor "clerk" :labels ["private"]
                                                  :session-wake (:id wake)
                                                  :args (cond-> {:subject subject :sections sections}
                                                          urgency (assoc :urgency urgency))}
                                      {:now (:now w) :config cfg})]
        (last (sort-by :id (vals (:messages state)))))
      (catch clojure.lang.ExceptionInfo e
        (when-not (core/rejection e) (throw e))
        nil))))

(defn- email-holds?
  "I2 for one rendered email of `msgs`: relay's headers only, each once,
  the one recipient the channel's :to, text/plain, CRLF line ends, the
  subject and body as the operator is shown them."
  [msgs subject body-text]
  (let [{:keys [from to text]} (channels/email mail-channel msgs (constantly nil))
        hs (headers text)
        h (into {} hs)
        argv (channels/curl-argv mail-channel "/tmp/x/message.eml")]
    (and (= agent-address from) (= operator-address to)
         ;; Exactly relay's headers, each once: no Cc, no Bcc, nothing
         ;; the model could add.
         (= expected-headers (map first hs))
         (= agent-address (h "From"))
         (= operator-address (h "To"))
         (= "text/plain; charset=UTF-8" (h "Content-Type"))
         (= "base64" (h "Content-Transfer-Encoding"))
         ;; Every line ends CRLF, and no line break but a fold.
         (not (re-find #"[^\r]\n|\r[^\n]" (first (str/split text #"\r\n\r\n" 2))))
         ;; The subject is the model's, one line, shown as the operator is
         ;; shown it.
         (= subject (decode-words (h "Subject")))
         (not (re-find #"[\r\n\u0085\u2028\u2029]" subject))
         (= (str (str/replace body-text "\n" "\r\n") "\r\n") (body text))
         ;; One recipient on the wire too: the channel's :to.
         (= 1 (count (filter #{"--mail-rcpt"} argv)))
         (= operator-address (second (drop-while #(not= "--mail-rcpt" %) argv)))
         (= agent-address (second (drop-while #(not= "--mail-from" %) argv))))))

(defspec every-delivery-goes-only-where-its-channel-says 300
  ;; Nine cases in ten are inputs tell_operator takes, routed so that one
  ;; route always sends to mail: those all reach the email checks. The
  ;; rest must be refused.
  (prop/for-all [{:keys [accepted] :as input} gen-input
                 routes gen-routes]
    (let [routes (if accepted (conj routes {:channels ["mail"]}) routes)
          settings {:channels {"mail" mail-channel "phone" phone-channel} :routes routes :digest-at "18:00"}
          _ (assert (nil? (messages/settings-problem settings)))
          m (told settings input)]
      (if-not accepted
        (nil? m)
        (and
         (some? m)
         (contains? (:deliveries m) "mail")
         ;; Deliveries only to configured channels.
         (every? #{"mail" "phone"} (keys (:deliveries m)))
         (every? (fn [ch]
                   (case ch
                     "mail" (email-holds? [m] (channels/subject-text m) (channels/body-text m))
                     "phone"
                     (let [p (channels/push phone-channel [m])]
                       (and (every? #{:title :body :labels :click} (keys p))
                            (not (re-find #"[\r\n]" (str (:title p) (:body p))))
                            (= (str "relay://messages/" (:id m)) (:click p))))))
                 (keys (:deliveries m))))))))

(defspec hostile-messages-that-go-together-are-one-safe-email 100
  (prop/for-all [inputs (gen/vector gen-accepted-input 2 3)]
    (let [settings {:channels {"mail" mail-channel} :routes [{:channels ["mail"]}]}
          msgs (vec (map-indexed (fn [i input] (assoc (told settings input) :id (inc i))) inputs))]
      (and (every? :subject msgs)
           (email-holds? msgs
                         (str (channels/subject-text (first msgs)) " (and " (dec (count msgs)) " more)")
                         (str/join "\n\n----\n\n" (for [m msgs] (str (channels/subject-text m) "\n\n"
                                                                     (channels/body-text m)))))))))

(deftest a-reply-threads-to-what-it-answers
  (let [parent (assoc (messages/message {:kind "note" :subject "Draft ready" :sections [["x"]]})
                      :id 12 :created-at (Instant/parse "2026-10-26T12:00:00Z") :labels [])
        reply (assoc (messages/message {:kind "reply" :subject "Re: Draft ready" :sections [["y"]] :in-reply-to 12})
                     :id 15 :created-at (Instant/parse "2026-10-26T13:00:00Z") :labels [])
        {:keys [text]} (channels/email mail-channel [reply] {12 parent})
        h (into {} (headers text))]
    (is (= (str "<relay.12." (.toEpochMilli (Instant/parse "2026-10-26T12:00:00Z")) "@example.org>")
           (h "In-Reply-To") (h "References")))
    (is (= (str "<relay.15." (.toEpochMilli (Instant/parse "2026-10-26T13:00:00Z")) "@example.org>") (h "Message-ID")))
    (is (= "Mon, 26 Oct 2026 13:00:00 +0000" (h "Date")))
    (is (= (channels/message-id parent agent-address) (h "In-Reply-To")) "the same id every time")))

(deftest messages-that-go-together-are-one-email
  (let [mk (fn [id s] (assoc (messages/message {:kind "note" :subject s :sections [[(str s " body")]]})
                             :id id :created-at (Instant/parse "2026-10-26T12:00:00Z") :labels []))
        {:keys [text]} (channels/email mail-channel [(mk 1 "One") (mk 2 "Two")] (constantly nil))
        h (into {} (headers text))]
    (is (= "One (and 1 more)" (h "Subject")))
    (is (= "One\n\nOne body\n\n----\n\nTwo\n\nTwo body\n" (str/replace (body text) "\r\n" "\n")))))

(defspec a-header-is-encoded-so-it-reads-back 500
  ;; A subject that spells out an encoded word is encoded itself, so a
  ;; mail reader shows the text as written, not what it would decode to.
  (prop/for-all [s (gen/one-of [gen-hostile-text (gen/fmap #(apply str (repeat 30 %)) gen/string-ascii)])]
    (let [line (channels/subject-text {:subject s :labels []})
          enc (channels/encode-header line)]
      (and (= line (decode-words (unfold enc)))
           (every? #(<= (count %) 78) (str/split enc #"\r\n"))
           (not (re-find #"[\r\n](?! )" (str/replace enc "\r\n " "")))))))

(deftest a-login-goes-to-curl-on-stdin-or-not-at-all
  (is (= "user = \"agent@example.org:p\\\"w\\\\d\"\n" (channels/curl-login agent-address "p\"w\\d")))
  (is (nil? (channels/curl-login agent-address "pw\nurl = file:///etc/passwd")) "a line break could add a setting")
  (let [argv (channels/curl-argv mail-channel "/tmp/m.eml")]
    (is (= ["curl" "-q"] (take 2 argv)) "no .curlrc")
    (is (= ["-K" "-"] (take-last 2 argv)))
    (is (not-any? #(str/includes? % "pw") argv) "no password on the command line")
    (is (some #{"=smtp,smtps"} argv))
    (is (= "smtps://smtp.example.org:465/example.org" (second (drop-while #(not= "--url" %) argv)))))
  (is (not (some #{"-K"} (channels/curl-argv (dissoc mail-channel :user :credentials-command) "/tmp/m.eml")))))

(deftest smtp-to-another-host-requires-starttls
  (let [argv #(channels/curl-argv (assoc mail-channel :url %) "/tmp/m.eml")]
    (is (some #{"--ssl-reqd"} (argv "smtp://smtp.example.org:587"))
        "curl refuses to go on if the server will not start TLS")
    (is (some #{"--ssl-reqd"} (argv "smtp://SMTP.example.org:25")))
    (testing "plain SMTP stays for this machine only"
      (doseq [u ["smtp://127.0.0.1:1025" "smtp://localhost:1025" "smtp://[::1]:1025"]]
        (is (not (some #{"--ssl-reqd"} (argv u))) u)))
    (is (some #{"--ssl-reqd"} (argv "smtps://smtp.example.org:465")) "TLS from the start, required all the same")))

;; --- what a mail app shows

(defn- msg-of [id subject text & {:as more}]
  (merge (assoc (messages/message {:kind (if (:in-reply-to more) "reply" "note") :subject subject :sections [[text]]
                                   :in-reply-to (:in-reply-to more)})
                :id id :created-at (Instant/parse "2026-10-26T12:00:00Z") :labels [] :origin "told")
         (dissoc more :in-reply-to)))

(deftest a-mail-has-a-name-it-is-from-and-says-how-to-answer
  (let [{:keys [text]} (channels/email (assoc mail-channel :from-name "Your assistant")
                                       [(msg-of 3 "Draft ready" "It is in your drafts.")]
                                       (constantly nil) {:replies? true})
        h (into {} (headers text))]
    (is (= "Your assistant <agent@example.org>" (h "From")))
    (is (str/ends-with? (body text) "-- \r\nReply to this mail to answer.\r\n")))
  (testing "relay's own alert has nothing to answer, so it does not say so"
    (let [{:keys [text]} (channels/email mail-channel [(msg-of 3 "Could not check mailbox" "x" :origin "ingest-failing")]
                                         (constantly nil) {:replies? true})]
      (is (not (str/includes? (body text) "Reply to this mail")))))
  (testing "nor does a mail when relay does not read the agent's mailbox"
    (is (not (str/includes? (body (:text (channels/email mail-channel [(msg-of 3 "s" "x")] (constantly nil) {:replies? false})))
                            "Reply to this mail"))))
  (testing "a name that could break the header is refused in relay.edn"
    (is (some? (messages/channel-problem "mail" (assoc mail-channel :from-name "Eve\" <eve@x.org>, \"X"))))
    (is (some? (messages/channel-problem "mail" (assoc mail-channel :from-name "a\r\nBcc: e@x.org"))))
    (is (nil? (messages/channel-problem "mail" (assoc mail-channel :from-name "Your assistant"))))))

(deftest a-reply-to-a-mailed-message-threads-and-says-re
  (let [parent (msg-of 12 "Draft ready" "x")
        reply (msg-of 15 "Venue noted" "The Old Hall it is." :in-reply-to 12)
        {:keys [text]} (channels/email mail-channel [reply] {12 parent} {:mailed? #{12}})
        h (into {} (headers text))]
    (is (= "Re: Draft ready" (h "Subject")) "so a mail app that threads by subject keeps it together")
    (is (= (channels/message-id parent agent-address) (h "In-Reply-To")))
    (is (str/starts-with? (body text) "Venue noted\r\n\r\nThe Old Hall it is."))))

(deftest a-reply-to-a-message-never-mailed-says-what-it-answers
  ;; Regression: it threaded to a mail the operator never got.
  (let [parent (assoc (msg-of 12 "Prep notes?" "x") :replies [{:id 13 :text "Yes please, by Thursday."}])
        reply (msg-of 15 "Prep notes started" "I will have them by Wednesday." :in-reply-to 12)
        {:keys [text]} (channels/email mail-channel [reply] {12 parent} {:mailed? #{}})
        h (into {} (headers text))]
    (is (nil? (h "In-Reply-To")))
    (is (nil? (h "References")))
    (is (= "Prep notes started" (h "Subject")))
    (is (str/starts-with? (body text) "In answer to \"Prep notes?\", where you wrote: \"Yes please, by Thursday.\""))))

(deftest provenance-is-one-footer-line-never-a-stamp
  (let [m (msg-of 3 "Holiday hours" "Closed Monday [[fact:4]]. See https://office.example/h"
                  :labels ["contaminated" "private"] :sources ["web:office-holidays.html"])
        b (channels/body-text m)]
    (is (= "Holiday hours" (channels/subject-text m)))
    (is (= "Closed Monday. See hxxps://office[.]example/h\n\nDrew on: the web page office-holidays.html" b)
        "refs as words, links broken, one line at the end")
    (testing "the operator's own thread, and relay's own messages, say nothing of it"
      (is (not (str/includes? (channels/body-text (assoc m :sources [])) "Drew on")))
      (is (not (str/includes? (channels/body-text (assoc m :origin "work-abandoned")) "Drew on"))))
    (testing "the client is sent it as one field"
      (is (= "Drew on: the web page office-holidays.html" (get (channels/wire m) "drewOn"))))))
