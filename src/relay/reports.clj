(ns relay.reports
  "Research reports, with no I/O. A public role granted :reports writes a
  report of findings with report_submit; relay checks it with the verifier
  (webtool check), turns what came back into flags, and shows the
  operator each finding to accept or reject. This namespace holds the
  pure parts:

    parse-report      the report's strict schema
    canonical-url     one spelling of a URL, for telling repeats apart
    check-requests    the verifier calls a report needs
    parse-check-items the verifier's answer, checked and made plain data
    verify            check results, the report, the ledger and the clock
                      into flags for each finding
    finding-fields    exactly what the operator is shown of a finding,
                      which an acceptance is bound to
    typed-line        the one line of a ledger entry that holds no
                      outsider's text

  Free text an outsider could have written is never trusted without the
  operator reading it. Only typed values (a kind from the operator's
  list, a date, a number, a currency code) are ever shown clean without
  that."
  (:require [clojure.string :as str]
            [relay.text :as text])
  (:import (java.text Normalizer Normalizer$Form)
           (java.time LocalDate)))

;; --- the schema

(def limits
  {:summary 2000 :title 200 :url 2000 :organization 200 :location 200 :finding-summary 500
   :quote 500 :findings 30 :evidence 5})

(def currencies
  "ISO 4217 currency codes. An amount's currency is one of these, so it is
  a value from a list, never three letters an outsider chose freely."
  (set (str/split "AED AFN ALL AMD ANG AOA ARS AUD AWG AZN BAM BBD BDT BGN BHD BIF BMD BND BOB BRL BSD BTN BWP BYN BZD CAD CDF CHF CLP CNY COP CRC CUP CVE CZK DJF DKK DOP DZD EGP ERN ETB EUR FJD FKP GBP GEL GHS GIP GMD GNF GTQ GYD HKD HNL HTG HUF IDR ILS INR IQD IRR ISK JMD JOD JPY KES KGS KHR KMF KPW KRW KWD KYD KZT LAK LBP LKR LRD LSL LYD MAD MDL MGA MKD MMK MNT MOP MRU MUR MVR MWK MXN MYR MZN NAD NGN NIO NOK NPR NZD OMR PAB PEN PGK PHP PKR PLN PYG QAR RON RSD RUB RWF SAR SBD SCR SDG SEK SGD SHP SLE SOS SRD SSP STN SVC SYP SZL THB TJS TMT TND TOP TRY TTD TWD TZS UAH UGX USD UYU UZS VES VND VUV WST XAF XCD XCG XOF XPF YER ZAR ZMW ZWG ZWL"
                  #" ")))

(def max-amount 1e12)

(def kind-pattern #"[a-z][a-z0-9-]{0,39}")

(defn- problem! [msg] (throw (ex-info msg {::problem true})))

(defn- exact-keys! [m required optional what]
  (when-not (map? m) (problem! (str what " must be an object.")))
  (when-let [missing (seq (remove #(contains? m %) required))]
    (problem! (str what " needs " (str/join ", " (map name missing)) ".")))
  (when (seq (remove (set (concat required optional)) (keys m)))
    ;; Fixed text: the refusal must not quote an outsider's key.
    (problem! (str what " takes only " (str/join ", " (map name (concat required optional))) "."))))

(defn- string! [v limit what & {:keys [one-line]}]
  (when-not (string? v) (problem! (str what " must be a string.")))
  (when (str/blank? v) (problem! (str what " must not be empty.")))
  (when (> (count v) limit) (problem! (str what " is at most " limit " characters.")))
  (when (and one-line (re-find #"[\r\n]" v)) (problem! (str what " is one line.")))
  v)

(defn- url! [v what]
  (string! v (:url limits) what)
  (when-not (re-matches #"(?i)https?://[^\s/?#@]+([/?#]\S*)?" v)
    (problem! (str what " must be an http or https URL, with no spaces and no user name.")))
  v)

(defn- vector! [v lo hi what]
  (when-not (sequential? v) (problem! (str what " must be a list.")))
  (when-not (<= lo (count v) hi) (problem! (str what " has " lo " to " hi " items.")))
  (vec v))

(defn- amount! [a]
  (exact-keys! a [:value :currency] [] "An amount")
  (let [{:keys [value currency]} a]
    (when-not (and (number? value) (not (ratio? value)) (Double/isFinite (double value))
                   (<= 0 value max-amount))
      (problem! (str "An amount's value is a number from 0 to " (long max-amount) ".")))
    (when-not (currencies currency)
      (problem! "An amount's currency is an ISO 4217 code, such as USD, EUR or CAD."))
    {:value value :currency currency}))

(defn- evidence! [e]
  (exact-keys! e [:url :quote] [] "Each piece of evidence")
  {:url (url! (:url e) "An evidence url")
   :quote (string! (:quote e) (:quote limits) "A quote")})

(defn- finding! [kinds f]
  (exact-keys! f [:title :url :organization :kind :summary :evidence] [:deadline :amount :location]
               "Each finding")
  (let [{:keys [kind deadline amount location]} f]
    (when-not (some #{kind} kinds)
      (problem! (str "A finding's kind is one of " (str/join ", " kinds) ".")))
    (when (and (some? deadline) (not (and (string? deadline) (re-matches #"\d{4}-\d{2}-\d{2}" deadline))))
      (problem! "A deadline is a date written YYYY-MM-DD."))
    (cond-> {:title (string! (:title f) (:title limits) "A title" :one-line true)
             :url (url! (:url f) "A finding's url")
             :organization (string! (:organization f) (:organization limits) "An organization" :one-line true)
             :kind kind
             :summary (string! (:summary f) (:finding-summary limits) "A finding's summary")
             :evidence (mapv evidence! (vector! (:evidence f) 1 (:evidence limits) "Evidence"))}
      (some? deadline) (assoc :deadline deadline)
      (some? amount) (assoc :amount (amount! amount))
      (some? location) (assoc :location (string! location (:location limits) "A location" :one-line true)))))

(defn parse-report
  "A report_submit input checked against the schema, for a role whose
  grant allows `kinds`: {:report {:summary :findings}} or {:problem why}.
  Nothing outside the schema is accepted, and the reason never quotes the
  input."
  [kinds input]
  (try
    (exact-keys! input [:summary :findings] [] "A report")
    {:report {:summary (string! (:summary input) (:summary limits) "The report's summary")
              :findings (mapv #(finding! kinds %) (vector! (:findings input) 1 (:findings limits) "Findings"))}}
    (catch clojure.lang.ExceptionInfo e
      (if (::problem (ex-data e)) {:problem (ex-message e)} (throw e)))))

(defn input-schema
  "report_submit's JSON Schema, for the model. relay checks the input
  itself with parse-report."
  [kinds]
  (let [s (fn [n & [desc]] (cond-> {:type "string" :maxLength n} desc (assoc :description desc)))]
    {:type "object"
     :additionalProperties false
     :required ["summary" "findings"]
     :properties
     {:summary (s 2000 "What you searched for and what you found, briefly.")
      :findings {:type "array" :minItems 1 :maxItems 30
                 :items {:type "object" :additionalProperties false
                         :required ["title" "url" "organization" "kind" "summary" "evidence"]
                         :properties
                         {:title (s 200 "One line.")
                          :url (s 2000 "The page for this finding, http or https.")
                          :organization (s 200 "Who offers it, one line.")
                          :kind {:type "string" :enum (vec kinds)}
                          :deadline {:type "string" :description "YYYY-MM-DD, if there is one."}
                          :amount {:type "object" :additionalProperties false :required ["value" "currency"]
                                   :properties {:value {:type "number"}
                                                :currency {:type "string" :description "ISO 4217, such as USD."}}}
                          :location (s 200 "One line, if it matters.")
                          :summary (s 500)
                          :evidence {:type "array" :minItems 1 :maxItems 5
                                     :items {:type "object" :additionalProperties false :required ["url" "quote"]
                                             :properties {:url (s 2000 "A page you fetched.")
                                                          :quote (s 500 "Copied verbatim from that page.")}}}}}}}}))

;; --- canonical URLs

(def dropped-params
  "Query parameters that track a click and say nothing about the page."
  #{"fbclid" "gclid" "dclid" "gbraid" "wbraid" "msclkid" "yclid" "mc_eid" "mc_cid" "igshid"
    "_hsenc" "_hsmi" "mkt_tok" "ref_src" "spm"})

(defn- dropped-param? [segment]
  (let [k (str/lower-case (first (str/split segment #"=" 2)))]
    (or (str/starts-with? k "utm_") (dropped-params k))))

(defn canonical-url
  "One spelling of a URL: scheme and host in lower case, no fragment, no
  tracking parameters (utm_*, fbclid, gclid, mc_eid and the like), no
  default port, and no trailing slash on the path, the root being /. A
  fragment starting #/ or #! is kept: single-page sites route by it, so
  it names the page. Idempotent. Text that is not an http or https URL
  comes back as it is."
  [s]
  (let [s (str s)]
    (if-let [[_ scheme authority path query fragment] (re-matches #"(?is)(https?)://([^/?#]*)([^?#]*)(?:\?([^#]*))?(#.*)?" s)]
      (let [scheme (str/lower-case scheme)
            [_ userinfo hostport] (re-matches #"(?:(.*)@)?(.*)" authority)
            hostport (str/lower-case hostport)
            hostport (str/replace hostport (re-pattern (str ":" (if (= "http" scheme) "80" "443") "$")) "")
            hostport (str/replace hostport #":$" "")
            path (str/replace path #"/+$" "")
            path (if (str/blank? path) "/" path)
            kept (when query (remove #(or (str/blank? %) (dropped-param? %)) (str/split query #"&")))]
        (str scheme "://" (when userinfo (str userinfo "@")) hostport path
             (when (seq kept) (str "?" (str/join "&" kept)))
             (when (and fragment (re-find #"^#[/!]" fragment)) fragment)))
      s)))

(defn url-host
  "The host of an http or https URL, lower case, or nil."
  [s]
  (when-let [[_ authority] (re-matches #"(?i)https?://([^/?#]*).*" (str s))]
    (let [hostport (last (str/split authority #"@"))
          host (if (str/starts-with? hostport "[")
                 (first (str/split hostport #"\]"))
                 (first (str/split hostport #":")))]
      (some-> host str/lower-case (str/replace #"\.$" "") not-empty))))

(def ^:private two-level-suffixes
  "Common second-level public suffixes, for guessing a registrable domain
  when the verifier did not give one (it uses the public suffix list)."
  #{"co.uk" "org.uk" "ac.uk" "gov.uk" "ltd.uk" "me.uk" "net.uk" "nhs.uk" "com.au" "net.au" "org.au"
    "edu.au" "gov.au" "co.nz" "org.nz" "govt.nz" "ac.nz" "co.jp" "or.jp" "ac.jp" "go.jp" "com.br"
    "org.br" "gov.br" "co.in" "org.in" "gov.in" "ac.in" "co.za" "org.za" "gov.za" "ac.za" "com.mx"
    "org.mx" "gob.mx" "com.cn" "org.cn" "gov.cn" "edu.cn" "co.kr" "or.kr" "go.kr" "ac.kr" "com.sg"
    "org.sg" "gov.sg" "edu.sg" "gc.ca" "qc.ca" "on.ca" "bc.ca" "ab.ca" "ns.ca" "nb.ca" "mb.ca" "sk.ca"
    "nl.ca" "pe.ca" "co.il" "org.il" "ac.il" "gov.il" "com.tr" "org.tr" "gov.tr" "edu.tr"})

(defn guess-domain
  "A host's registrable domain, guessed from its last labels and a short
  list of two-level suffixes. The verifier's own answer is better and
  is used whenever there is one."
  [host]
  (when host
    (if (or (re-matches #"[\d.]+" host) (str/starts-with? host "["))
      host
      (let [labels (str/split host #"\.")
            n (count labels)]
        (cond (< n 2) host
              (and (> n 2) (two-level-suffixes (str/join "." (take-last 2 labels))))
              (str/join "." (take-last 3 labels))
              :else (str/join "." (take-last 2 labels)))))))

(defn marked-url
  "A URL with its registrable domain in brackets, so the part that says
  who owns it stands out: https://jobs.[example.org]/x. The domain is
  found where the host ends, compared without changing the text's length
  (a dotted capital I lower-cases to two characters), and a URL it does
  not fit comes back unmarked. Never throws."
  [url domain]
  (let [url (str url)
        domain (str domain)]
    (or (try
          (when-let [[_ prefix authority] (re-matches #"(?i)(https?://)([^/?#]*).*" url)]
            (let [start (+ (count prefix) (inc (or (str/last-index-of authority "@") -1)))
                  hostport (subs url start (+ (count prefix) (count authority)))
                  host-len (if (str/starts-with? hostport "[")
                             (count hostport)
                             (or (str/index-of hostport ":") (count hostport)))
                  end (+ start host-len)
                  i (- end (count domain))]
              (when (and (not (str/blank? domain)) (>= i start)
                         (.regionMatches url true (int i) domain 0 (count domain))
                         (or (= i start) (= \. (.charAt url (dec i)))))
                (str (subs url 0 i) "[" (subs url i end) "]" (subs url end)))))
          (catch Exception _ nil))
        url)))

;; --- calling the verifier

(defn check-urls
  "Every URL a report cites, in order of first mention, each with the
  quotes the report says it holds."
  [{:keys [findings]}]
  (let [pairs (mapcat (fn [{:keys [url evidence]}]
                        (cons [url nil] (map (juxt :url :quote) evidence)))
                      findings)
        order (distinct (map first pairs))
        quotes (reduce (fn [m [u q]] (cond-> (update m u #(or % [])) q (update u #(if (some #{q} %) % (conj % q)))))
                       {} pairs)]
    (mapv (fn [u] {:url u :quotes (get quotes u)}) order)))

(def max-items 20)
(def max-quotes 20)

(defn check-requests
  "The verifier calls a report needs: each an input for check, at most 20
  items of at most 20 quotes. A URL with more quotes than that is
  checked more than once."
  [report]
  (let [items (mapcat (fn [{:keys [url quotes]}]
                        (if (empty? quotes)
                          [{:url url :quotes []}]
                          (map (fn [qs] {:url url :quotes (vec qs)}) (partition-all max-quotes quotes))))
                      (check-urls report))]
    (mapv (fn [batch] {:items (vec batch)}) (partition-all max-items items))))

(def field-limits
  "The longest any string from the verifier is kept: what comes from a
  page is cut there, so no page can grow the log."
  {:url 4000 :error 1000 :reason 1000 :domain 300 :sha256 64})

(defn clip
  "A string cut to `n` characters, with an ellipsis when it was longer."
  [s n]
  (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- host-info [h]
  (when (map? h)
    {:name (clip (get h "name") (:domain field-limits)) :unicode (clip (get h "unicode") (:domain field-limits))
     :punycode (true? (get h "punycode")) :mixed-scripts (true? (get h "mixed_scripts"))}))

(def ^:private item-keys
  #{"url" "fetched" "complete" "uncheckable" "error" "status" "final_url" "redirects" "crosses_domains"
    "domain" "final_domain" "host" "final_host" "sha256" "hidden_chars" "quotes"})

(defn parse-check-items
  "The verifier's answer to one request, from its content parsed as JSON:
  the items in order, as plain data, or nil when it is not the answer to
  that request (a key missing or of the wrong type, the wrong number of
  items or quotes). Matched by position, since the verifier escapes
  invisible characters in the strings it echoes."
  [request content]
  (let [items (when (map? content) (get content "items"))
        asked (:items request)]
    (when (and (sequential? items) (= (count items) (count asked)))
      (let [parsed (map (fn [item {:keys [quotes]}]
                          (when (and (map? item) (every? #(contains? item %) item-keys)
                                     (boolean? (get item "fetched")) (boolean? (get item "complete"))
                                     (string? (get item "uncheckable")) (string? (get item "error"))
                                     (integer? (get item "status")) (string? (get item "final_url"))
                                     (boolean? (get item "crosses_domains")) (string? (get item "domain"))
                                     (string? (get item "final_domain")) (map? (get item "host"))
                                     (or (nil? (get item "final_host")) (map? (get item "final_host")))
                                     (string? (get item "sha256")) (integer? (get item "hidden_chars"))
                                     (sequential? (get item "quotes")) (= (count quotes) (count (get item "quotes")))
                                     (every? #(and (map? %) (boolean? (get % "found"))) (get item "quotes")))
                            {:fetched (get item "fetched") :complete (get item "complete")
                             :uncheckable (clip (get item "uncheckable") (:reason field-limits))
                             :error (clip (get item "error") (:error field-limits))
                             :status (get item "status") :final-url (clip (get item "final_url") (:url field-limits))
                             :crosses-domains (get item "crosses_domains")
                             :domain (clip (get item "domain") (:domain field-limits))
                             :final-domain (clip (get item "final_domain") (:domain field-limits))
                             :host (host-info (get item "host")) :final-host (host-info (get item "final_host"))
                             :sha256 (clip (get item "sha256") (:sha256 field-limits))
                             :quotes (mapv (fn [q r] {:quote q :found (get r "found")}) quotes (get item "quotes"))}))
                        items asked)]
        (when (every? some? parsed) (vec parsed))))))

(def ^:private fetch-keys [:fetched :complete :uncheckable :status])

(defn checked-urls
  "What the verifier found for each URL a report cites, in order: the
  first answer for its fetch, every quote's answer, and under :others any
  further fetches of a URL with more quotes than one item holds. Each
  quote's answer carries the standing of the fetch it was checked
  against, so a quote is judged by its own fetch. `results` are the parsed
  items of every request, in order."
  [report results]
  (let [asked (mapcat :items (check-requests report))
        by-url (reduce (fn [m [{:keys [url]} r]]
                         (let [quotes (mapv #(merge % (select-keys r fetch-keys)) (:quotes r))]
                           (if (contains? m url)
                             (-> m (update-in [url :quotes] into quotes)
                                 (update-in [url :others] (fnil conj []) (dissoc r :quotes)))
                             (assoc m url (assoc r :url url :quotes quotes)))))
                       {} (map vector asked results))]
    (mapv #(get by-url (:url %)) (check-urls report))))

;; --- flags

(def flag-kinds
  ["quote-not-found" "page-uncheckable" "fetch-failed" "cross-domain-redirect" "suspicious-host"
   "domain-mismatch" "hidden-characters" "lookalikes" "deadline-unparseable" "deadline-past"
   "organization-not-in-domain" "first-seen-domain" "reader-directed" "rejected-as-fake-before"])

(defn refusal-kind
  "What a fetch error says the verifier refused, from webtool's words:
  \"guard\" when its guard refused the cited URL itself (a private
  address, a local name, a port, credentials), \"redirect\" when the page
  sent the fetch on somewhere refused or too far, \"content\" when the
  page is of a kind it does not read, and nil when it is no refusal."
  [error]
  (let [e (str/triml (str error))]
    (when (str/starts-with? e "refused: ")
      (let [r (subs e (count "refused: "))]
        (cond (or (str/starts-with? r "a redirect to ") (re-matches #"more than \d+ redirects.*" r)
                  (str/starts-with? r "not an absolute URL") (str/starts-with? r "not a URL that can be requested"))
              "redirect"
              (or (str/starts-with? r "the page is a PDF") (str/starts-with? r "the content type"))
              "content"
              :else "guard")))))

(def reader-directed-patterns
  "Phrasing that speaks to whoever reads a report rather than describing a
  finding. Only flagged: a finding about a job can say \"apply now\"."
  [#"(?i)\bignore\s+(all\s+)?(the\s+)?(previous|prior|above|earlier)" #"(?i)\bdisregard\b"
   #"(?i)\byou\s+must\b" #"(?i)\bact\s+now\b" #"(?i)\bclick\b" #"(?i)\burgent(ly)?\b"
   #"(?i)\bsend\s+(it\s+|this\s+|them\s+|your\s+\w+\s+)?to\b" #"(?i)\bemail\s+us\b"
   #"(?i)\bcontact\s+us\s+(now|immediately|today)" #"(?i)\breply\s+(to|with)\b"
   #"(?i)\b(password|passcode|login\s+details)\b" #"(?i)\bwire\s+(the\s+)?(money|funds|transfer)"
   #"(?i)\bsystem\s+prompt\b" #"(?i)\b(do\s+not|don't)\s+tell\b" #"(?i)\bimmediately\b"
   #"(?i)\bnew\s+instructions\b" #"(?i)\bas\s+an\s+ai\b"])

(defn reader-directed? [s] (boolean (some #(re-find % (str s)) reader-directed-patterns)))

(defn- hidden-chars? [s]
  (boolean (some text/hidden? (iterator-seq (.iterator (.codePoints (str s)))))))

(defn text-fields
  "A finding's fields that are free text, by name, its evidence included."
  [{:keys [title url organization location summary evidence]}]
  (concat [["title" title] ["url" url] ["organization" organization]]
          (when location [["location" location]])
          [["summary" summary]]
          (mapcat (fn [i {:keys [url quote]}]
                    [[(str "evidence " (inc i) " url") url] [(str "evidence " (inc i) " quote") quote]])
                  (range) evidence)))

(defn text-flags
  "Flags for free text by its own look: characters that hide or pass for
  others, and phrasing aimed at the reader."
  [fields]
  (concat
   (for [[field v] fields :when (hidden-chars? v)]
     {:kind "hidden-characters" :field field})
   (for [[field v] fields
         :let [odd (vec (text/lookalikes v))]
         :when (seq odd)]
     {:kind "lookalikes" :field field :what odd})
   (for [[field v] fields :when (reader-directed? v)]
     {:kind "reader-directed" :field field})))

(defn ascii-fold
  "Text in lower-case ASCII letters, accents dropped: Montréal is montreal."
  [s]
  (-> (Normalizer/normalize (str s) Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      str/lower-case))

(defn org-in-domain?
  "Whether an organization's name shows in a registrable domain's own
  label: a token of at least four letters of its ASCII-folded name, found
  in the label (example in example.org). A weak signal: many names
  never appear in their domains."
  [organization domain]
  (let [label (str/replace (str/lower-case (first (str/split (str domain) #"\."))) "-" "")
        tokens (filter #(>= (count %) 4) (re-seq #"[a-z]+" (ascii-fold organization)))]
    (boolean (some #(str/includes? label %) tokens))))

(defn url-domain
  "A checked URL's registrable domain: the verifier's, or a guess."
  [checked url]
  (let [d (:domain checked)]
    (if (str/blank? d) (guess-domain (url-host url)) d)))

(defn final-url
  "Where a checked URL landed, or the URL itself when it was not fetched."
  [checked url]
  (let [f (:final-url checked)]
    (if (and (:fetched checked) (not (str/blank? f))) f url)))

(defn- fetch-flags
  "Flags from what the verifier found in one fetch of a URL."
  [url c]
  (let [hosts (remove nil? [(:host c) (:final-host c)])]
    (concat
     (cond
       (nil? c) [{:kind "fetch-failed" :url url :error "the verifier gave no answer for it" :refused false}]
       (not (:fetched c)) [(let [k (refusal-kind (:error c))]
                             (cond-> {:kind "fetch-failed" :url url :error (str (:error c)) :refused (= "guard" k)}
                               k (assoc :refusal k)))]
       (not (str/blank? (:uncheckable c))) [{:kind "page-uncheckable" :url url :reason (:uncheckable c)}]
       :else nil)
     (when (:crosses-domains c)
       [{:kind "cross-domain-redirect" :url url :final-url (:final-url c)}])
     (for [h hosts :when (or (:punycode h) (:mixed-scripts h))]
       {:kind "suspicious-host" :url url :host (:name h) :unicode (:unicode h)
        :punycode (:punycode h) :mixed-scripts (:mixed-scripts h)}))))

(defn- url-flags
  "Flags from every fetch of one URL."
  [url c]
  (distinct (mapcat #(fetch-flags url %) (cons c (:others c)))))

(defn- quote-flags
  "A quote not found on a page that was fetched and could be checked, by
  the fetch it was checked against."
  [url quote c]
  (let [q (first (filter #(= quote (:quote %)) (:quotes c)))
        fetch (merge (select-keys c fetch-keys) (when q (select-keys q fetch-keys)))]
    (when (and c (:fetched fetch) (str/blank? (:uncheckable fetch)) (not (:found q)))
      [{:kind "quote-not-found" :url url :quote quote :status (:status fetch) :incomplete (not (:complete fetch))}])))

(defn- deadline-flags [deadline ^LocalDate today]
  (when deadline
    (if-let [d (try (LocalDate/parse deadline) (catch Exception _ nil))]
      (when (.isBefore d today) [{:kind "deadline-past" :deadline deadline}])
      [{:kind "deadline-unparseable" :deadline deadline}])))

(defn verify
  "Flags for every finding of a report, from what the verifier found
  (`checked`, checked-urls), what relay already knows (`known`) and the
  operator's date `today`. Returns {:flags for the report's own text,
  :findings [{:flags :seen-before :seen-as :canonical-url :final-url
  :domain}]}.

  `known` has the registrable :domains of the ledger, :seen, the
  canonical URLs that hide a finding as seen before, each with what it
  matched (a ledger entry, a finding waiting for review, one judged not
  relevant), and :fake, those of findings rejected as fake. A finding is
  matched by the canonical URL it gives, never by where that landed, so a
  redirect cannot hide it. One matching a finding rejected as fake is
  shown again, flagged."
  [{:keys [summary findings]} checked {:keys [domains seen fake]} ^LocalDate today]
  (let [by-url (into {} (map (juxt :url identity) checked))]
    {:flags (vec (text-flags [["report summary" summary]]))
     :findings
     (first
      (reduce
       (fn [[out earlier] [i {:keys [url evidence organization deadline] :as f}]]
         (let [c (by-url url)
               domain (url-domain c url)
               evidence-domains (set (map #(url-domain (by-url (:url %)) (:url %)) evidence))
               canonical (canonical-url url)
               seen-as (or (get seen canonical)
                           (when-let [j (get earlier canonical)] {:kind "earlier" :finding j}))
               flags (vec (distinct
                           (concat
                            (mapcat (fn [u] (url-flags u (by-url u))) (distinct (cons url (map :url evidence))))
                            (mapcat (fn [{u :url q :quote}] (quote-flags u q (by-url u))) evidence)
                            (when-not (evidence-domains domain)
                              [{:kind "domain-mismatch" :domain domain :evidence-domains (vec (sort (remove nil? evidence-domains)))}])
                            (text-flags (text-fields f))
                            (deadline-flags deadline today)
                            (when-not (org-in-domain? organization domain)
                              [{:kind "organization-not-in-domain" :domain domain}])
                            (when-not (contains? domains domain)
                              [{:kind "first-seen-domain" :domain domain}])
                            (when (and (not seen-as) (contains? fake canonical))
                              [{:kind "rejected-as-fake-before" :url canonical}]))))]
           [(conj out (cond-> {:flags flags :seen-before (some? seen-as) :canonical-url canonical
                               :final-url (final-url c url) :domain domain}
                        seen-as (assoc :seen-as seen-as)))
            (if (contains? earlier canonical) earlier (assoc earlier canonical i))]))
       [[] {}]
       (map-indexed vector findings)))}))

(defn demotion-reason
  "Why a verified report takes its role back to trust level 0, or nil: a
  quote not found on a page that was checked, or a fetch the verifier's
  guard refused (the role cited a page it should never have reached)."
  [{:keys [findings]}]
  (let [flags (mapcat :flags findings)]
    (cond (some #(= "quote-not-found" (:kind %)) flags) "a quote was not found on the page it cited"
          (some #(and (= "fetch-failed" (:kind %)) (:refused %)) flags)
          "the verifier's guard refused the address of a page it cited"
          :else nil)))

(def sample-one-in
  "At trust level 1, one finding in this many is drawn for the operator to
  review even when nothing flags it."
  5)

(defn auto-acceptable?
  "Whether a verified finding is accepted without the operator, at trust
  level 1: nothing flags it or its report, it was not seen before, and
  it was not drawn for the sample."
  [report-flags {:keys [flags seen-before]} sampled]
  (and (empty? report-flags) (empty? flags) (not seen-before) (not sampled)))

;; --- what the operator is shown

(defn amount-text
  "An amount as a plain number and its currency, or nil when it is not a
  finite number. Total."
  [{:keys [value currency]}]
  (when (and (number? value) (not (ratio? value)) (Double/isFinite (double value)))
    (str (.toPlainString (.stripTrailingZeros (bigdec value))) " " currency)))

(defn finding-fields
  "A finding as the operator is shown it, field by field: [name value]
  pairs, in order. An acceptance is bound to a digest of exactly these
  (finding-digest-text), so what is accepted is what was read."
  [{:keys [title url organization kind deadline amount location summary evidence]} {:keys [final-url]}]
  (vec (concat [["title" title] ["organization" organization] ["kind" kind]]
               (when deadline [["deadline" deadline]])
               (when amount [["amount" (amount-text amount)]])
               (when location [["location" location]])
               [["summary" summary] ["url" url]]
               (when (and final-url (not= final-url url)) [["lands at" final-url]])
               (mapcat (fn [i {:keys [url quote]}]
                         [[(str "evidence " (inc i)) url] [(str "quote " (inc i)) quote]])
                       (range) evidence))))

(defn finding-digest-text [finding verification]
  (pr-str (finding-fields finding verification)))

(def statuses ["new" "seen" "applied" "passed"])

(defn typed-line
  "A ledger entry as one line of typed values only: its number, kind,
  deadline, amount and status. No outsider chose any of it freely, so it
  is clean whoever wrote the entry."
  [{:keys [id kind deadline amount status]}]
  (str "entry " id ": " kind ", "
       (cond (nil? deadline) "no deadline"
             (try (LocalDate/parse deadline) (catch Exception _ nil)) (str "deadline " deadline)
             :else "deadline unreadable")
       ", "
       (if-let [a (and amount (currencies (:currency amount)) (amount-text amount))] (str "amount " a) "no amount")
       ", status " status))

(defn typed-line-pattern
  "What a typed line may be, given the kinds relay.edn allows."
  [kinds]
  (re-pattern (str "entry \\d+: (" (str/join "|" (map #(java.util.regex.Pattern/quote %) kinds)) "), "
                   "(no deadline|deadline unreadable|deadline \\d{4}-\\d{2}-\\d{2}), "
                   "(no amount|amount \\d+(\\.\\d+)? (" (str/join "|" (sort currencies)) ")), "
                   "status (" (str/join "|" statuses) ")")))
