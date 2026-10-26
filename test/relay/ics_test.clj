(ns relay.ics-test
  "The calendar feed is read back by a parser written here, from RFC 5545,
  that knows nothing of how relay.ics writes. What the renderer writes is
  what the parser reads, whatever text a model gave it."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.ics :as ics])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CodingErrorAction StandardCharsets)
           (java.time Duration Instant LocalDate ZoneOffset)))

;; --- a reader of iCalendar, for the tests

(defn- decode-strict
  "Bytes as UTF-8, or throws when they are not."
  ^String [^bytes bs]
  (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))
                (ByteBuffer/wrap bs))))

(defn physical-lines
  "The text's physical lines, each decoded alone as strict UTF-8, so a
  fold inside a UTF-8 sequence fails here. The text must end in CRLF, and
  no bare CR or LF may be in it."
  [^String s]
  (let [bs (.getBytes s StandardCharsets/UTF_8)
        body (decode-strict bs)]
    (assert (str/ends-with? body "\r\n") "the text must end with CRLF")
    (let [lines (str/split (subs body 0 (- (count body) 2)) #"\r\n" -1)]
      (doseq [l lines]
        (assert (not (re-find #"[\r\n]" l)) "a bare CR or LF")
        (decode-strict (.getBytes ^String l StandardCharsets/UTF_8)))
      lines)))

(defn octet-count [^String s] (alength (.getBytes s StandardCharsets/UTF_8)))

(defn unfold
  "Content lines from physical lines: a line starting with a space or tab
  continues the one before, without that first character."
  [lines]
  (reduce (fn [acc l]
            (if (and (seq acc) (re-find #"^[ \t]" l))
              (update acc (dec (count acc)) str (subs l 1))
              (conj acc l)))
          [] lines))

(defn unescape-text
  "A TEXT value's text. Throws on an escape RFC 5545 does not define."
  [^String s]
  (let [sb (StringBuilder.)
        n (count s)]
    (loop [i 0]
      (when (< i n)
        (let [c (.charAt s i)]
          (if (= \\ c)
            (let [d (when (< (inc i) n) (.charAt s (inc i)))]
              (case d
                (\n \N) (.append sb "\n")
                \\ (.append sb "\\")
                \; (.append sb ";")
                \, (.append sb ",")
                (throw (ex-info (str "bad escape at " i " in " (pr-str s)) {})))
              (recur (+ i 2)))
            (do (.append sb c) (recur (inc i)))))))
    (str sb)))

(defn- content-line [l]
  (let [[_ name params value] (re-matches #"([A-Za-z0-9-]+)((?:;[^:]*)?):(.*)" l)]
    (assert name (str "not a content line: " (pr-str l)))
    {:name name :params params :value value}))

(defn parse
  "A calendar as a tree: {:name :props [{:name :params :value}] :children}."
  [text]
  (let [lines (map content-line (unfold (physical-lines text)))
        root {:name :root :props [] :children []}]
    (loop [stack [root] ls lines]
      (if-let [[l & more] (seq ls)]
        (case (:name l)
          "BEGIN" (recur (conj stack {:name (:value l) :props [] :children []}) more)
          "END" (let [done (peek stack) up (pop stack)]
                  (assert (= (:name done) (:value l)) "END does not match BEGIN")
                  (assert (seq up) "END with no BEGIN")
                  (recur (conj (pop up) (update (peek up) :children conj done)) more))
          (recur (conj (pop stack) (update (peek stack) :props conj l)) more))
        (do (assert (= 1 (count stack)) "a component was not closed")
            (first (:children (peek stack))))))))

(defn- prop-values [component name]
  (mapv :value (filter #(= name (:name %)) (:props component))))

(defn- one-prop [component name]
  (let [vs (prop-values component name)]
    (assert (<= (count vs) 1) (str name " given twice"))
    (first vs)))

(defn- parse-utc [s]
  (assert (re-matches #"\d{8}T\d{6}Z" s) (str "not a UTC date-time: " s))
  (Instant/parse (str (subs s 0 4) "-" (subs s 4 6) "-" (subs s 6 8) "T"
                      (subs s 9 11) ":" (subs s 11 13) ":" (subs s 13 15) "Z")))

(defn- one-line-of [component name]
  (let [ls (filter #(= name (:name %)) (:props component))]
    (assert (<= (count ls) 1) (str name " given twice"))
    (first ls)))

(defn- parse-date [s]
  (assert (re-matches #"\d{8}" s) (str "not a DATE: " s))
  (LocalDate/parse (str (subs s 0 4) "-" (subs s 4 6) "-" (subs s 6 8))))

(defn- date-valued?
  "Whether a DTSTART or DTEND is a DATE (RFC 5545 3.3.4): VALUE=DATE and
  nothing else among its parameters."
  [l]
  (case (:params l)
    "" false
    ";VALUE=DATE" true
    (throw (ex-info (str "unexpected parameters " (pr-str (:params l))) {}))))

(defn read-events
  "What a feed says, as maps like the ones relay.ics renders, plus :status.
  An all-day event has :start-date and :end-date, and no :start or :end."
  [text]
  (let [cal (parse text)]
    (assert (= "VCALENDAR" (:name cal)))
    (assert (= ["2.0"] (prop-values cal "VERSION")))
    (vec (for [e (:children cal)
               :let [ds (one-line-of e "DTSTART") de (one-line-of e "DTEND")
                     all-day (date-valued? ds)]]
           (do (assert (= "VEVENT" (:name e)))
               (when de
                 (assert (= all-day (date-valued? de)) "DTSTART and DTEND are of one kind")
                 (assert (if all-day
                           (.isAfter (parse-date (:value de)) (parse-date (:value ds)))
                           (.isAfter (parse-utc (:value de)) (parse-utc (:value ds))))
                         "DTEND is after DTSTART"))
               (merge
                (if all-day
                  {:start-date (parse-date (:value ds)) :end-date (some-> de :value parse-date)}
                  {:start (parse-utc (:value ds)) :end (some-> de :value parse-utc)})
               {:uid (one-prop e "UID")
                :dtstamp (parse-utc (one-prop e "DTSTAMP"))
                :created (parse-utc (one-prop e "CREATED"))
                :modified (parse-utc (one-prop e "LAST-MODIFIED"))
                :sequence (parse-long (one-prop e "SEQUENCE"))
                :title (unescape-text (one-prop e "SUMMARY"))
                :location (some-> (one-prop e "LOCATION") unescape-text)
                :description (some-> (one-prop e "DESCRIPTION") unescape-text)
                :status (one-prop e "STATUS")
                :property-names (set (map :name (:props e)))}))))))

;; --- generators

(def nasty
  "Text that has hurt iCalendar writers: separators, escapes, line breaks of
  every kind, a property name behind a break, folds' worst case."
  ["," ";" "\\" "\\n" "\n" "\r\n" "\r" "\t" "\u0085" "\u2028" "\u2029" "\u00a0" "\u0000" "\u001b"
   "\u200b" "\u202e" "\ufe0f" "\ue000"
   "é" "ü" "日本語" "🎹" "👩\u200d👩\u200d👧" (str (char 0xD800)) "a:b" "BEGIN:VEVENT"
   "\nEND:VEVENT\nBEGIN:VEVENT\nUID:x" " " "x"])

(def gen-text
  (gen/fmap #(apply str %)
            (gen/vector (gen/one-of [(gen/elements nasty) gen/string-alphanumeric gen/string])
                        0 40)))

(def gen-long-text
  "Text long enough to fold many times, mostly multi-byte."
  (gen/fmap #(apply str %)
            (gen/vector (gen/elements ["é" "日" "🎹" "a" "," "\n" "ab" "\\"]) 0 400)))

(def gen-instant
  (gen/fmap #(Instant/ofEpochSecond %) (gen/choose 946684800 4102444800)))

(def gen-event
  (gen/fmap
   (fn [[id title start length location description changes created-gap removed? days]]
     (let [created (.minusSeconds ^Instant start created-gap)
           modified (.plusSeconds ^Instant created (* 60 changes))
           ;; Some are whole days, as relay keeps them: the first day, the
           ;; day after the last, and the starts of both.
           first-day (when days (.toLocalDate (.atZone ^Instant start ZoneOffset/UTC)))]
       (cond-> {:id id :title title :start start
                :end (when length (.plusSeconds ^Instant start length))
                :location location :description description
                :sequence changes :created created :modified modified
                :removed-at (when removed? modified)}
         days (assoc :start-date first-day :end-date (.plusDays first-day days)
                     :start (.toInstant (.atStartOfDay first-day ZoneOffset/UTC))
                     :end (.toInstant (.atStartOfDay (.plusDays first-day days) ZoneOffset/UTC))))))
   (gen/tuple (gen/choose 1 100000)
              (gen/one-of [gen-text gen-long-text])
              gen-instant
              (gen/one-of [(gen/return nil) (gen/choose 1 (* 86400 3))])
              (gen/one-of [(gen/return nil) gen-text gen-long-text])
              (gen/one-of [(gen/return nil) gen-text gen-long-text])
              (gen/choose 0 50)
              (gen/choose 0 100000)
              gen/boolean
              (gen/one-of [(gen/return nil) (gen/choose 1 5)]))))

(def gen-events
  (gen/fmap (fn [es] (vec (vals (into {} (map (juxt :id identity) es)))))
            (gen/vector gen-event 0 8)))

(def now (Instant/parse "2026-10-14T12:00:00Z"))

(def before-all
  "A time before every generated event, so the feed lists all of them."
  (Instant/ofEpochSecond 0))

;; --- text

(defspec escaping-reads-back-as-the-cleaned-text 300
  (prop/for-all [s (gen/one-of [gen-text gen-long-text])]
    (= (ics/clean-text s) (unescape-text (ics/escape-text s)))))

(defspec escaped-text-has-no-bare-separator-or-break 300
  (prop/for-all [s (gen/one-of [gen-text gen-long-text])]
    (let [e (ics/escape-text s)]
      (and (not (re-find #"[\r\n]" e))
           ;; Take out every escape pair; what is left has no special character.
           (not (re-find #"[;,\\]" (str/replace e #"\\[\\;,n]" "")))))))

(defspec cleaned-text-has-one-kind-of-break-and-nothing-hidden 300
  (prop/for-all [s (gen/one-of [gen-text gen-long-text])]
    (let [c (ics/clean-text s)]
      (and (= c (ics/clean-text c))
           (not (re-find #"[\r\u0085\u2028\u2029\u0000\u001b\u200b\u202e\ufe0f\ue000]" c))
           ;; No lone surrogate is left: the text survives UTF-8.
           (= c (String. (.getBytes ^String c StandardCharsets/UTF_8) StandardCharsets/UTF_8))))))

(deftest escaping-names-the-four-characters
  (is (= "a\\,b\\;c\\\\d\\ne" (ics/escape-text "a,b;c\\d\r\ne")))
  (is (= "tab\there" (ics/escape-text "tab\there")))
  (is (= "ab" (ics/escape-text "a\u202eb")) "a bidi override is dropped"))

;; --- folding

(defspec folded-lines-fit-and-unfold-to-the-line 400
  (prop/for-all [s (gen/one-of [gen-long-text gen-text gen/string])]
    (let [line (str/replace (ics/clean-text s) "\n" "")
          folded (ics/fold-line line)
          physical (str/split folded #"\r\n" -1)]
      (and (every? #(<= (octet-count %) ics/line-limit) physical)
           (every? #(str/starts-with? % " ") (rest physical))
           ;; Every physical line is whole UTF-8.
           (every? #(decode-strict (.getBytes ^String % StandardCharsets/UTF_8)) physical)
           (= line (apply str (first physical) (map #(subs % 1) (rest physical))))))))

(deftest folding-is-at-75-octets-on-a-boundary
  (let [line (apply str (repeat 40 "日"))  ; 3 octets each, 120 in all
        physical (str/split (ics/fold-line line) #"\r\n")]
    (is (= [75 46] (mapv octet-count physical)) "25 characters fill the first line; the space and 24 more the second")
    (is (= line (str (first physical) (subs (second physical) 1)))))
  (testing "a four-octet character that does not fit moves to the next line whole"
    (let [line (str (apply str (repeat 73 "a")) "🎹")
          physical (str/split (ics/fold-line line) #"\r\n")]
      (is (= [73 5] (mapv octet-count physical)))))
  (testing "a line of exactly 75 octets is not folded"
    (is (= (apply str (repeat 75 "a")) (ics/fold-line (apply str (repeat 75 "a")))))))

;; --- the feed

(defn- strip [e] (select-keys e [:title :location :description]))

(defspec the-feed-reads-back-what-was-written 60
  (prop/for-all [events gen-events]
    (let [text (ics/render {:now before-all :events (mapv #(assoc % :removed-at nil) events)})
          back (read-events text)
          by-uid (into {} (map (juxt :uid identity) back))]
      (and (= (count events) (count back))
           (every? (fn [e]
                     (let [b (by-uid (ics/uid (:id e)))]
                       (and b
                            (= (ics/clean-text (:title e)) (:title b))
                            (= (when-not (str/blank? (:location e)) (ics/clean-text (:location e))) (:location b))
                            (= (when-not (str/blank? (:description e)) (ics/clean-text (:description e))) (:description b))
                            (if (:start-date e)
                              (and (= (:start-date e) (:start-date b)) (= (:end-date e) (:end-date b))
                                   (nil? (:start b)))
                              (and (= (:start e) (:start b)) (= (:end e) (:end b)) (nil? (:start-date b))))
                            (= (:sequence e) (:sequence b))
                            (= (:created e) (:created b))
                            (= (:modified e) (:modified b) (:dtstamp b))
                            (= "CONFIRMED" (:status b)))))
                   events)))))

(defspec the-feed-has-only-the-properties-relay-writes 60
  (prop/for-all [events gen-events]
    (let [allowed #{"UID" "DTSTAMP" "CREATED" "LAST-MODIFIED" "SEQUENCE" "DTSTART" "DTEND" "SUMMARY"
                    "LOCATION" "DESCRIPTION" "STATUS"}
          text (ics/render {:now before-all :events events})]
      (every? #(set/subset? (:property-names %) allowed) (read-events text)))))

(defspec every-line-of-the-feed-fits-and-ends-in-crlf 60
  (prop/for-all [events gen-events]
    (let [text (ics/render {:now before-all :events events})]
      (and (every? #(<= (octet-count %) ics/line-limit) (physical-lines text))
           (str/ends-with? text "\r\n")))))

(defspec the-feed-is-the-same-whatever-order-the-events-come-in 60
  (prop/for-all [events gen-events]
    (= (ics/render {:now now :events events})
       (ics/render {:now now :events (shuffle events)})
       (ics/render {:now (.plusSeconds now 5) :events events}))))

(defspec uids-are-unique-and-follow-the-id 60
  (prop/for-all [events gen-events]
    (let [events (mapv #(assoc % :removed-at nil) events)
          uids (map :uid (read-events (ics/render {:now before-all :events events})))]
      (and (= (count uids) (count (set uids)))
           (= (set uids) (set (map #(ics/uid (:id %)) events)))))))

(defspec the-feed-lists-exactly-the-events-listed-says 100
  (prop/for-all [events gen-events
                 at (gen/fmap #(Instant/ofEpochSecond %) (gen/choose 946684800 4102444800))]
    (= (set (map #(ics/uid (:id %)) (ics/listed at events)))
       (set (map :uid (read-events (ics/render {:now at :events events})))))))

(deftest an-event-that-ended-long-ago-is-dropped
  (let [e {:id 3 :title "Old" :start (Instant/parse "2026-01-01T09:00:00Z") :end (Instant/parse "2026-01-01T10:00:00Z")
           :sequence 0 :created now :modified now}
        over (:end e)
        feed (fn [at] (read-events (ics/render {:now at :events [e]})))]
    (is (= 1 (count (feed (.minusSeconds (.plus over (Duration/ofDays ics/ended-days)) 1)))))
    (is (empty? (feed (.plus over (Duration/ofDays ics/ended-days)))))))

(deftest an-all-day-event-is-a-date
  ;; Regression (U1): a role said it had added an all-day event, and the
  ;; feed had an hour at midnight.
  (let [d (LocalDate/parse "2026-11-11")
        e {:id 4 :title "Remembrance Day" :start (Instant/parse "2026-11-11T04:00:00Z")
           :end (Instant/parse "2026-11-12T04:00:00Z") :start-date d :end-date (.plusDays d 1)
           :sequence 0 :created now :modified now}
        text (ics/render {:now now :events [e]})]
    (is (str/includes? text "DTSTART;VALUE=DATE:20261111\r\n"))
    (is (str/includes? text "DTEND;VALUE=DATE:20261112\r\n"))
    (is (not (re-find #"DTSTART:" text)))
    (is (= [d (.plusDays d 1)] ((juxt :start-date :end-date) (first (read-events text)))))))

(deftest times-are-utc-to-the-second
  (is (= "20261014T130500Z" (ics/utc (Instant/parse "2026-10-14T13:05:00.999Z"))))
  (is (= "19991231T235959Z" (ics/utc (Instant/parse "1999-12-31T23:59:59Z")))))

(deftest a-removed-event-is-cancelled-for-a-while-and-then-dropped
  (let [removed (Instant/parse "2026-10-01T09:00:00Z")
        e {:id 7 :title "Dentist" :start (Instant/parse "2026-10-20T14:00:00Z") :end nil
           :sequence 2 :created (Instant/parse "2026-09-01T09:00:00Z") :modified removed :removed-at removed}
        feed (fn [at] (read-events (ics/render {:now at :events [e]})))]
    (testing "just after, it is cancelled and its sequence is the one that cancelled it"
      (let [[b] (feed (.plusSeconds removed 60))]
        (is (= "CANCELLED" (:status b)))
        (is (= 2 (:sequence b)))
        (is (= (ics/uid 7) (:uid b)))))
    (testing "the last second of the window, still cancelled"
      (is (= 1 (count (feed (.minusSeconds (.plus removed (Duration/ofDays ics/cancelled-days)) 1))))))
    (testing "after the window, gone"
      (is (empty? (feed (.plus removed (Duration/ofDays ics/cancelled-days))))))))

(deftest an-event-that-had-left-the-feed-stays-off-it-when-removed
  (let [start (Instant/parse "2020-01-01T09:00:00Z")
        e {:id 1 :title "Old" :start start :end nil :removed-at nil}
        removed (Instant/parse "2026-10-01T09:00:00Z")]
    (is (not (ics/listed? removed e)) "ended long ago")
    (is (not (ics/listed? removed (assoc e :removed-at removed))) "removing it does not bring it back")
    (is (ics/listed? start (assoc e :removed-at start)) "one removed while listed is cancelled")))

(deftest a-hostile-title-cannot-add-an-event
  (let [e {:id 1 :title "Lunch\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:evil\r\nSUMMARY:Pay me"
           :start now :end nil :sequence 0 :created now :modified now
           :description "x BEGIN:VEVENT"}
        back (read-events (ics/render {:now now :events [e]}))]
    (is (= 1 (count back)))
    (is (= (ics/uid 1) (:uid (first back))))
    (is (= "Lunch\nEND:VEVENT\nBEGIN:VEVENT\nUID:evil\nSUMMARY:Pay me" (:title (first back))))))

(deftest the-calendar-has-a-name-and-no-event-when-empty
  (let [text (ics/render {:now now :events [] :calendar-name "Agenda, mine"})]
    (is (str/includes? text "X-WR-CALNAME:Agenda\\, mine\r\n"))
    (is (empty? (read-events text)))
    (is (str/starts-with? text "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"))))

;; --- outside text (Codex R1): an event a session wrote after reading
;; outside text cannot put a live link in the operator's calendar app.

(def gen-link
  (gen/fmap (fn [[scheme host path q]] (str scheme "://" host path q))
            (gen/tuple (gen/elements ["https" "http" "HTTPS" "Http"])
                       (gen/elements ["evil.example" "exfil.example.org" "10.0.0.1" "a.b.icu" "xn--e1afmkfd.xn--p1ai"])
                       (gen/elements ["" "/" "/collect" "/c/d.php"])
                       (gen/elements ["" "?data=SECRET" "?d=a%20b&e=f"]))))

(def gen-linked-text
  "Text with links in it, between hostile pieces, and hidden characters
  that could split a link from defang's sight."
  (gen/fmap #(apply str %)
            (gen/vector (gen/one-of [gen-link (gen/elements nasty) gen/string-alphanumeric
                                     (gen/elements [" " "\u200b" "\u2060" "\u00ad" "\ufeff"])])
                        0 12)))

(def gen-labelled-event
  (gen/let [e gen-event
            title gen-linked-text
            location (gen/one-of [(gen/return nil) gen-linked-text])
            description (gen/one-of [(gen/return nil) gen-linked-text])
            labels (gen/elements [[] ["private"] ["contaminated"] ["contaminated" "mail:dana@riverbend.test"]
                                  ["mail:dana@riverbend.test" "private"] ["files:downloads" "private"]])]
    (assoc e :title title :location location :description description :labels labels :removed-at nil)))

(defn- live-link? [s] (boolean (re-find #"(?i)https?://" (str s))))

(defspec an-outside-events-text-holds-no-live-link-and-a-clean-ones-is-unchanged 200
  (prop/for-all [e gen-labelled-event]
    (let [[b] (read-events (ics/render {:now before-all :events [e]}))
          outside? (some #(or (= "contaminated" %) (re-matches #"[a-z]+:.+" %)) (:labels e))
          fields (fn [x] [(:title x) (:location x) (:description x)])
          shown (fn [s] (when-not (str/blank? s) (ics/clean-text s)))]
      (if outside?
        (and (not-any? live-link? (fields b))
             ;; and nothing hidden is left in it
             (= (fields b) (map #(some-> % ics/clean-text) (fields b))))
        (= (fields b) [(ics/clean-text (:title e)) (shown (:location e)) (shown (:description e))])))))

(deftest an-outside-events-link-is-defanged-in-every-text-field
  (let [link "https://evil.example/collect?data=SECRET"
        e {:id 1 :title (str "Call " link) :location (str "ht\u200btps://evil.example/x") :description (str "See " link)
           :start now :sequence 0 :created now :modified now :labels ["contaminated" "mail:dana@riverbend.test"]}
        [b] (read-events (ics/render {:now now :events [e]}))]
    (is (= "Call hxxps://evil[.]example/collect?data=SECRET" (:title b)))
    (is (= "hxxps://evil[.]example/x" (:location b)) "a hidden character is taken out before the link is broken")
    (is (= "See hxxps://evil[.]example/collect?data=SECRET" (:description b)))
    (testing "an event a clean session wrote keeps its link"
      (let [[b] (read-events (ics/render {:now now :events [(assoc e :labels ["private"])]}))]
        (is (= (str "Call " link) (:title b)))))))
