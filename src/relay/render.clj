(ns relay.render
  "What the relay command shows. Pure: each function takes the data to
  show and returns lines of text, which relay.cli prints, each through
  text/visible-line.

  One rule keeps a role's or an outsider's text from passing for the
  screen's own: it never decides where a line starts. A one-line field is
  part of a line the renderer writes, and any line break in it is escaped
  where it is printed. Text that may run to several lines is shown with
  text/block: every line behind text/gutter, which nothing else starts
  with, under a [contaminated] line when it is contaminated."
  (:require [cheshire.core :as json]
            [clojure.walk]
            [clojure.string :as str]
            [relay.cards :as cards]
            [relay.config :as config]
            [relay.core :as core]
            [relay.reports :as reports]
            [relay.spend :as spend]
            [relay.text :as text]
            [relay.week :as week])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)))

(defn local
  "An instant as the operator's local time, or nil."
  [^Instant at zone]
  (when at (.format (.atZone at ^ZoneId zone) (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))))

(defn when*
  "An instant as the operator's local time, as a bare date when it is
  midnight there, or nil."
  [^Instant at zone]
  (when at
    (let [z (.atZone at ^ZoneId zone)]
      (if (= java.time.LocalTime/MIDNIGHT (.toLocalTime z))
        (str (.toLocalDate z))
        (local at zone)))))

(defn memo
  "One memo, as the operator's inbox shows it. One from a contaminated
  source is marked and its URLs broken."
  [zone {:keys [id from subject body sent-at labels]}]
  (concat
   ["" (str "[" id "] " (text/for-operator labels subject))
    (str "    from " from ", " (local sent-at zone))]
   (text/block labels body)))

(defn declassify
  "What relay declassify shows of a memo `d` already as declassifying makes
  it (relay.core/declassified): that text exactly, with the
  [contaminated] marker as the only thing added. It is not defanged again:
  core did that, and the role gets the same text."
  [zone {:keys [id from subject body sent-at labels]}]
  (concat
   ["" (str "[" id "] " (when (text/contaminated? labels) "[contaminated] ") subject)
    (str "    from " from ", " (local sent-at zone))]
   (when (text/contaminated? labels) ["  [contaminated]"])
   ;; Behind the gutter, but not defanged again: core did that.
   (text/block [] body)))

(defn inbox [zone memos]
  (if (empty? memos)
    ["Nothing waiting."]
    (mapcat #(memo zone %) memos)))

(defn- open-memo [zone {:keys [id subject attempts retry-at]}]
  (format "    open  [%d] %s%s" id subject
          (cond-> ""
            (pos? attempts) (str " (" attempts " failed attempts")
            (and (pos? attempts) retry-at) (str ", next " (local retry-at zone))
            (pos? attempts) (str ")"))))

(defn- role-line-labels [state id]
  (core/union (core/role-labels state id) (core/id-labels state (:reports-to (core/role state id)))))

(defn- role-status [state zone now id]
  (let [{:keys [title reports-to]} (core/role state id)
        id-ls (core/id-labels state id)]
    (concat
     [(text/for-operator (role-line-labels state id)
                         (format "  %-20s %s%s%s" id title
                                 (if reports-to (str ", reports to " reports-to) "")
                                 (if (core/running? state id) "  [running]" "")))]
     (for [{:keys [name schedule labels]} (core/active-routines state id)]
       (text/for-operator (core/union labels id-ls) (format "    routine %-18s %s" name schedule)))
     (map #(text/for-operator (core/union (:labels %) id-ls) (open-memo zone %)) (core/unread-memos state id))
     (for [{memo-id :id :keys [subject labels]} (core/dead-memos state id)]
       (text/for-operator (core/union labels id-ls)
                          (format "    dead  [%d] %s (relay retry %s)" memo-id subject id)))
     (when-let [{:keys [server tool fresh retry-at]} (get-in state [:roles id :tools-unavailable])]
       [(text/for-operator id-ls
                           (str "    tools unavailable"
                                (cond tool (str ": " tool) server (str ": server " server))
                                (cond
                                  (or fresh (nil? retry-at)) (str " changed (relay fresh " id ")")
                                  (.isAfter ^Instant retry-at now) (str ", next try " (local retry-at zone))
                                  :else ", tried again at the next tick")))]))))

(defn- dollars
  "An amount in US dollars to the cent. One above zero that rounds to
  nothing is shown as under a cent, so a priced wake never reads as free."
  [x]
  (let [x (double x)]
    (if (and (pos? x) (< x 0.005)) "<$0.01" (format "$%.2f" x))))

(def spend-weeks
  "How many weeks `relay status` shows spend for: this one and the ones
  before it."
  5)

(defn spend
  "This week's spend per role and the total, against the weekly budget if
  there is one, and the total of each week before. `weeks` is
  relay.spend/weekly's result, newest first. A role's id carries its
  labels."
  [state weeks budget]
  (let [[{:keys [week roles cost-usd unpriced]} & earlier] weeks
        without (fn [n] (when (pos? n) (str ", " n (if (= 1 n) " wake" " wakes") " without a cost")))]
    (concat
     ["" (str "Spend this week, from Monday " week)]
     (for [[role {c :cost-usd n :unpriced}] (sort-by key roles)]
       (text/for-operator (core/id-labels state role)
                          (format "  %-20s %s%s" role (dollars c) (str (without n)))))
     [(str (format "  %-20s %s" "total" (dollars cost-usd))
           (when budget (str " of a " (dollars budget) " weekly budget"))
           (without unpriced))]
     (when (seq earlier)
       [(str "  earlier weeks: " (str/join ", " (for [w earlier] (str (:week w) " " (dollars (:cost-usd w))))))]))))

(defn status
  "Roles with their routines and open work, harnesses, the operator's
  inbox, what the roles spent this week and the weeks before, and the
  latest wakes, as of `now`. `paused` is the lines to show
  when relay is paused (relay.pause/recovery-lines), or nil."
  [state zone ^Instant now & [config paused]]
  (concat
   (when paused
     (concat paused [""]))
   ["Roles"]
   (mapcat #(role-status state zone now %) (core/active-role-ids state))
   (mapcat (fn [line] ["" line])
    (for [[h {:keys [condition paused-until]}] (sort-by key (:harnesses state))]
     (str "Harness " h ": "
          (if (and paused-until (.isAfter ^Instant paused-until now))
            (str condition ", next try " (local paused-until zone))
            "available"))))
   ["" (format "Operator's inbox: %d open" (count (core/unread-memos state core/operator)))]
   (when-let [rs (seq (core/reports-waiting state))]
     [(format "Reports waiting for review: %s (relay review <report>)" (str/join ", " (map :id rs)))])
   (when-let [hs (seq (core/open-holds state))]
     [(format "Held tool calls: %d (relay holds)" (count hs))])
   (when-let [qs (seq (filter #(core/quarantined? config %) (filter #(= :unread (:status %)) (vals (:memos state)))))]
     [(format "Quarantined memos: %s (relay release <memo>)" (str/join ", " (sort (map :id qs))))])
   (for [id (core/active-role-ids state)
         :let [why (when config (core/unwakeable-reason state config id))]
         :when why]
     (text/for-operator (core/id-labels state id) (str "Public role " id " cannot run: " why)))
   (when config
     (mapcat (fn [role]
               (let [{:keys [level since reports findings flags accepted unread fake not-relevant seen-before]}
                     (core/research-record state role)
                     ls (core/id-labels state role)]
                 (cons "" (map #(text/for-operator ls %)
                               [(str "Research role " role ": trust level " level
                                     (when since (str " since " (local (:at since) zone) ", as " (:why since))))
                                (str "    " reports " reports, " findings " findings, " seen-before " seen before")
                                (str "    flags: " (if (seq flags) (str/join ", " (map (fn [[k n]] (str k " " n)) flags)) "none"))
                                (str "    accepted " accepted (when (pos? unread) (str " (" unread " unread, by relay)"))
                                     ", rejected as fake " fake ", not relevant " not-relevant)]))))
             (filter #(core/active-role? state %) (config/research-roles config))))
   (when-let [ds (seq (filter #(core/awaiting-declassification? config %) (filter #(= :unread (:status %)) (vals (:memos state)))))]
     [(format "Memos waiting to be declassified: %s (relay declassify <memo>)" (str/join ", " (sort (map :id ds))))])
   (let [budget (get-in config [:budget :weekly-usd])]
     (when (or budget (seq (:wakes state)))
       (spend state (spend/weekly (vals (:wakes state)) zone now spend-weeks) budget)))
   (when-let [recent (seq (take-last 5 (sort-by :id (vals (:wakes state)))))]
     (list* "" "Recent wakes"
           (for [{:keys [id role started-at status outcome crashed chat]} recent]
             (text/for-operator (core/id-labels state role)
                                (format "  %-6d %s  %-20s %s%s" id (local started-at zone) role
                                        (if (= :running status) "running"
                                            (str outcome (when crashed ", crashed")))
                                        ;; Its id is what relay flag takes.
                                        (if chat ", conversation" ""))))))))

(defn roles [state]
  (for [id (core/active-role-ids state)
        :let [{:keys [title reports-to]} (core/role state id)]]
    (text/for-operator (role-line-labels state id)
                       (str id ": " title (when reports-to (str ", reports to " reports-to))))))

(defn routines
  "The routines of the given owners, or of every active role."
  [state zone owners]
  (for [owner (or (seq owners) (core/active-role-ids state))
        {:keys [name schedule baseline labels]} (core/active-routines state owner)]
    (text/for-operator (core/union labels (core/id-labels state owner))
                       (format "%-16s %-18s %s (since %s)" owner name schedule (local baseline zone)))))

(defn notes [zone notes]
  (mapcat (fn [{:keys [name text written-at labels]}]
            (concat [(str "## " name "  (" (local written-at zone) ")")] (text/block labels text) [""]))
          notes))

(defn- validity [zone {:keys [valid-from valid-to]}]
  (let [t #(when* % zone)]
    (cond (and valid-from valid-to) (str "from " (t valid-from) " until " (t valid-to))
          valid-from (str "from " (t valid-from))
          valid-to (str "until " (t valid-to))
          :else "with no known start or end")))

(defn facts
  "Facts as relay facts shows them, each a line of its own and its text
  behind the gutter."
  [state zone facts]
  (if (empty? facts)
    ["No facts match."]
    (mapcat (fn [{:keys [id topic text supersedes superseded-by labels] :as f}]
              (concat [(str "Fact " id (when topic (str " [" topic "]")) ", holds " (validity zone f)
                            (when supersedes (str ", replacing fact " supersedes))
                            (when superseded-by (str ", superseded by fact " superseded-by)))]
                      (text/block labels text)))
            facts)))

(defn fact-history
  "Every version of a fact, as relay fact <id> shows it."
  [state zone {:keys [id supersedes versions]}]
  (cons (str "Fact " id (when supersedes (str ", which replaced fact " supersedes)))
        (mapcat (fn [{:keys [change recorded-at actor why superseded-by labels] :as v}]
                  (concat [(str "- " (when* recorded-at zone) " by " actor ": "
                                (case change
                                  :added "recorded"
                                  :superseded (str "superseded by fact " superseded-by)
                                  :corrected (str "corrected, because " (text/for-operator labels why))
                                  :retracted (str "retracted, because " (text/for-operator labels why))))]
                          (when-not (#{:superseded :retracted} change)
                            (cons (str "  holds " (validity zone v) (when (:topic v) (str " [" (:topic v) "]")))
                                  (text/block labels (:text v))))))
                versions)))

(defn journal
  "Journal entries, oldest first, each under its time."
  [zone entries]
  (if (empty? entries)
    ["The journal is empty."]
    (mapcat (fn [{:keys [at text labels]}] (concat [(when* at zone)] (text/block labels text) [""])) entries)))

(defn docs
  "The docs, one line each."
  [state zone docs]
  (if (empty? docs)
    ["There are no docs."]
    (for [{:keys [path at actor content labels]} docs]
      (text/for-operator labels (str path " (" (count content) " characters, last written "
                                     (when* at zone) " by " actor ")")))))

(defn doc
  "One version of a doc: its content, all of it behind the gutter."
  [{:keys [path seq content removed labels]}]
  (if removed
    [(str path " was removed in version " seq ".")]
    (concat [(str path ", version " seq ":")] (text/block labels content))))

(defn doc-history
  "The versions of a doc, one line each."
  [zone path versions]
  (cons path
        (for [{:keys [seq at actor content removed labels]} versions]
          (text/for-operator labels (str "  version " seq ", " (when* at zone) " by " actor
                                         (if removed ": removed" (str ", " (count content) " characters")))))))

(defn log
  "Events one to a line, the last `tail` of them when given: as JSON, or
  as a summary with the event's data cut to 120 characters."
  [events {:keys [tail json]}]
  (for [e (if tail (take-last tail events) events)
        :let [tainted? (text/contaminated? (:labels e))]]
    (if json
      ;; Still JSON, and plain ASCII: every other code point is written as
      ;; \uXXXX by the encoder, so nothing can drive the terminal and
      ;; every line still parses. A contaminated event's strings are
      ;; defanged in place.
      ;; The encoder leaves DEL as it is, the one ASCII control it does.
      (-> (json/generate-string (cond-> e tainted? (update :data #(clojure.walk/postwalk
                                                                   (fn [x] (if (string? x) (text/defang x) x)) %)))
                                {:escape-non-ascii true})
          (str/replace "\u007f" "\\u007f"))
      (text/for-operator (:labels e)
                         (format "%5d %s %-22s %-12s %s" (:seq e) (:at e) (:type e) (:actor e)
                                 (let [s (pr-str (:data e))] (subs s 0 (min 120 (count s)))))))))

(defn wake-result
  "What `relay wake` says it did."
  [result]
  [(str (name (:result result)) " " (pr-str (dissoc result :result)))])

(defn doc-put [path events]
  [(if (seq events)
     (str "Wrote " path ", version " (:seq (first events)))
     (str path " already says that."))])

(defn card
  "One card as relay card shows it: its text form, with each element's
  text marked and defanged when it is contaminated, and a note's text
  behind the gutter."
  [c]
  (cards/text-lines c {:mark text/for-operator :block text/block}))

(defn board
  "The cards on the board, a blank line between them."
  [cs]
  (if (empty? cs)
    ["The board is empty."]
    (vec (rest (mapcat #(cons "" (card %)) cs)))))

(defn- why-held
  "Why agentloop's policy held a call, from the tool's class and the
  session's labels."
  [{:keys [class always-hold]} labels]
  (let [ls (set labels)]
    (cond
      always-hold "the tool is set to always ask you"
      (= :act class)
      "others would see it, it spends money, or it cannot be undone, and no consent of yours in relay.edn covers this input"
      (and (= config/edit-class class) (ls "contaminated"))
      "it changes your things outside relay, and the session has seen an outsider's text"
      (and (= :egress class) (ls "private") (ls "contaminated"))
      "it can send data out, and the session has seen both your private data and an outsider's text"
      :else "the policy held it")))

(defn- defanged-block
  "Text as text/block shows it, with its links defanged whatever its
  labels. text/block defangs a contaminated source itself, and defang is
  not idempotent, so a clean one is defanged here, once."
  [labels s]
  (if (text/contaminated? labels)
    (text/block labels s)
    (text/block labels (text/defang s))))

(defn preview
  "A held call's preview, as screen lines: what its tool's :preview command
  read from the service about what the call would do, never what the role
  said it would do. Links to a host not in `approved` (hosts in previews
  the operator approved before) are listed, and so are characters that can
  pass for others. A preview is the service's text, so its links are
  always defanged, whatever its labels."
  [{:keys [content is-error labels] :as p} approved]
  (if-not p
    []
    (let [new-hosts (remove (set approved) (text/link-hosts content))
          odd (text/lookalikes (str content))
          gaps (text/placeholders content)]
      (concat
       [(if is-error
          "    preview: the preview command could not say what this call would do:"
          "    what it would do (from the tool's preview, not from the role):")]
       (defanged-block labels content)
       (when (seq gaps)
         (cons "    still to fill in, and would go out as written:"
               (map #(str "      " (text/defang %)) gaps)))
       (when (seq new-hosts)
         (cons "    links to places you have not approved before:"
               (map #(str "      " (text/defang %)) new-hosts)))
       (when (seq odd)
         ;; Each note quotes the word it is in, which can be a link.
         (cons "    look closely, the preview has characters that can pass for others:"
               (map #(str "      " (text/defang %)) odd)))))))

(def ^:private destination-key
  #"(?i)url|uri|link|to|cc|bcc|recipient|host|address|email|endpoint|dest|channel|repo|webhook|server|domain|phone|number")

(defn- destinations
  "What in a call's input says where data would go: values under keys
  that name a destination, and any other string that looks like an
  address."
  [input]
  (let [nodes (tree-seq coll? seq input)
        keyed (for [n nodes :when (map-entry? n)
                    :let [[k v] n]
                    :when (and (re-find destination-key (name k)) (or (string? v) (number? v)))]
                [(name k) v])
        keyed-values (set (map second keyed))
        loose (for [n nodes :when (and (string? n) (not (keyed-values n))
                                       (re-find #"://|@|\.[a-z]{2,}(/|$)" n))]
                n)]
    (distinct (concat (map (fn [[k v]] (str k ": " v)) keyed) loose))))

(defn hold
  "One held tool call, with what the operator needs to decide it: why it
  was held, what the tool is, where the data would go, what the session
  has seen and from where, anything in the input that could pass for
  something else, and the whole input. Every link in what it shows of the
  input is defanged, whatever the labels: the operator reads the input to
  judge it, and a defanged link still shows where it goes. The input is
  marked contaminated when the session or the preview is. The binding is
  to the exact input, which relay keeps as it is."
  ([h] (hold h nil))
  ([h config] (hold h config #{}))
  ([{:keys [id role tool session call labels label-sources fixed-args] :as h} config approved]
   (let [;; What the call would run on: with the arguments the operator
         ;; fixed, which the approval is bound to.
         input (if (map? fixed-args) (or (:effective-input h) (:input h)) (:input h))
         inner (when (= core/reader-tool tool) (core/held-tool tool input))
         t (get-in config [:tools (or inner tool)])
         class (or (:class t) (when-not t :builtin))
         input-text (json/generate-string input {:pretty true})
         shown-labels (core/union labels (get-in h [:preview :labels]))
         odd (text/lookalikes (str/join " " (filter string? (tree-seq coll? seq input))))]
     (concat
      [(str "Hold " id ": " role " wants to use " (if inner (str inner ", through the reader") tool))
       (str "    held because " (why-held t labels))
       (str "    the tool: " (if (= :builtin class) "one of relay's own" (name (or class :unknown)))
            (when (:description t) (str ", " (:description t))))]
      (when (#{:egress :act} class)
        (let [ds (destinations (if inner (or (:input input) (get input "input")) input))]
          [(str "    where it would go: " (if (seq ds) (text/defang (str/join "; " ds)) "nothing in the input names a place"))]))
      [(str "    the session has seen: " (if (seq labels) (str/join ", " labels) "nothing labelled"))]
      (for [[label src] (sort-by key label-sources)]
        (str "      " (name label) " from "
             (case (get src :kind (get src "kind"))
               "tool" (str "the tool " (or (:tool src) (get src "tool")) " (call " (or (:call_id src) (get src "call_id")) ")")
               "seed" "what relay put in its prompt"
               "prompt" "a prompt it was given"
               (pr-str src))))
      (when (seq odd)
        (cons "    look closely, the input has characters that can pass for others:"
              (map #(str "      " (text/defang %)) odd)))
      (preview (:preview h) approved)
      (when (and config (core/fixed-args-changed? config h))
        ["    your settings for this tool changed since it was held, so it will not run as shown: deny it"])
      [(str "    session " session ", call " call)
       "    input (links are defanged: hxxp is http, [.] is a dot):"]
      (defanged-block shown-labels input-text)
      (when (seq fixed-args)
        (cons "    set by your settings, not by the role:"
              (for [[k v] (sort-by (comp str key) fixed-args)]
                (str "      " (name k) " = " (text/defang (json/generate-string v))))))))))

(defn holds
  ([hs] (holds hs nil))
  ([hs config] (holds hs config #{}))
  ([hs config approved]
   (if (empty? hs)
     ["No tool calls are waiting for you."]
     (concat (mapcat #(concat (hold % config approved) [""]) hs)
             ["relay approve <hold> lets one run; relay deny <hold> [why] refuses it,"
              "and with --end also starts the role's next wake afresh."]))))

;; --- eval runs (bb eval)

(defn- money [x] (if x (format "$%.4f" (double x)) "?"))
(defn- ratio [x] (if x (format "%.2f" (double x)) "-"))
(defn- pad [s n] (let [s (str s)] (if (>= (count s) n) s (str s (apply str (repeat (- n (count s)) " "))))))

(defn eval-report
  "The terminal report for an eval run. `run` is run.json's data, `summary`
  summary.json's, both with keyword keys."
  [run summary]
  (let [{:keys [run-id model effort live trials prompt-digest relay-commit agentloop-commit]} run]
    (concat
     [(str "relay evals  " run-id "  " (if live (str model " effort " effort) "mock model")
           "  " (if trials (str trials " trial(s)") "default trials"))
      (str "prompt digest " (subs (str prompt-digest "            ") 0 12)
           "  relay " (or relay-commit "?") "  agentloop " (or agentloop-commit "?"))
      ""
      (str (pad "case" 26) (pad "tier" 6) (pad "pass" 7) (pad "pass^3" 8) (pad "errors" 8)
           (pad "cost/trial" 12) "failed graders")]
     (for [[id c] (:cases summary)]
       (str (pad id 26) (pad (if (= :capability (:tier c)) "cap" "reg") 6)
            (pad (str (:passed c) "/" (:scored c)) 7)
            (pad (ratio (get-in c [:pass-hat 3])) 8)
            (pad (:errors c) 8)
            (pad (money (:mean-cost-usd c)) 12)
            (str/join ", " (for [[g k] (:grader-fails c)] (str (name g) " x" k)))))
     [""]
     (for [tier [:regression :capability]
           :let [t (get summary tier)]
           :when t]
       (str (name tier) ": pass " (ratio (:pass-rate t))
            (when-let [[lo hi] (:interval t)] (str " [" (ratio lo) ", " (ratio hi) "]"))
            "  pass^3 " (ratio (:pass-hat-3 t)) "  errors " (:errors t)))
     [(str "total " (money (:cost-usd summary)) " model; " (:trials summary) " trials, " (:wakes summary)
           " wakes, " (:requests summary) " requests; " (money (:cost-per-wake summary)) " per wake")])))


;; --- research reports
;;
;; A report's text was chosen by whoever wrote the pages, so it never
;; decides where a line of the review screen starts. A field of one line
;; is one screen line, its line breaks escaped at the edge
;; (text/visible-line); a field of many lines (a summary, a quote) goes
;; behind text/gutter (text/block).

(defn- one-line
  "A field of one line. The edge escapes any line break in it."
  [s]
  (str s))

(defn- block
  "A field of many lines: its label on a line of the screen's own, then
  text/block."
  [label labels s]
  (cons (str "  " label ":") (text/block labels s)))

(defn- url-line
  "A URL printed in full with its registrable domain in brackets, and how
  its host displays when that is punycode. The edge that prints it makes
  its characters visible."
  [url {:keys [domain host]}]
  (one-line (str (reports/marked-url url domain)
                 (when (:punycode host) (str "  (its host shows as " (:unicode host) ")")))))

(defn flag-text
  "One flag in plain words, on one line."
  [{:keys [kind url quote error refused refusal reason final-url host unicode domain evidence-domains field what
           deadline incomplete status]}]
  (one-line
   (case kind
     "quote-not-found" (str "a quote was not found on " url (when (and status (not= 200 status)) (str ", which answered " status))
                            (when incomplete " (the page was not read whole, so it may be in the rest)")
                            ": \"" quote "\"")
     "page-uncheckable" (str url " could not be checked: " reason)
     "fetch-failed" (cond refused (str "the verifier's guard refused the address of " url ": " error)
                          (= "redirect" refusal) (str url " redirects where the verifier will not follow: " error)
                          :else (str url " could not be fetched: " error))
     "cross-domain-redirect" (str url " sends you on to another domain, " final-url)
     "suspicious-host" (str "the host " host " is written in punycode or mixes scripts; it shows as " unicode)
     "domain-mismatch" (str "the finding is on " domain ", and none of its evidence is"
                            (when (seq evidence-domains) (str " (the evidence is on " (str/join ", " evidence-domains) ")")))
     "hidden-characters" (str "the " field " has characters that do not show")
     "lookalikes" (str "the " field " has characters that can pass for others: " (str/join "; " what))
     "deadline-unparseable" (str "the deadline " deadline " is not a real date")
     "deadline-past" (str "the deadline " deadline " has passed")
     "organization-not-in-domain" (str "the organization's name is not in " domain " (a weak sign)")
     "first-seen-domain" (str domain " has not come up before")
     "reader-directed" (str "the " field " speaks to whoever reads it, as an instruction would")
     "rejected-as-fake-before" (str "this URL was rejected as fake before: " url)
     (str "flagged: " kind))))

(defn- verified-url
  "What the verifier found of a URL a report cites."
  [r url]
  (first (filter #(= url (:url %)) (get-in r [:verification :urls]))))

(def ^:private many-lines
  "The fields that may run to many lines; every other is one line."
  #(or (= "summary" %) (str/starts-with? % "quote ")))

(defn finding
  "One finding as the operator reviews it: every field, the text as from
  a contaminated source, each URL in full with its domain marked, and
  the flags in plain words. Exactly the fields relay.reports/finding-fields
  gives, which accepting it is bound to. Total: whatever the finding and
  the verifier's data hold, it returns lines."
  [r i]
  (let [f (get-in r [:findings i])
        v (get-in r [:verification :findings i])
        labels (get-in r [:verification :labels])
        urlish #{"url" "lands at"}
        field (fn [[k value]]
                (cond
                  (many-lines k) (block k labels value)
                  (urlish k) [(str "  " k ": " (url-line value (if (= "url" k) (verified-url r value)
                                                                    {:domain (some-> (verified-url r (:url f)) :final-domain)
                                                                     :host (:final-host (verified-url r (:url f)))})))]
                  (str/starts-with? k "evidence ") [(str "  " k ": " (url-line value (verified-url r value)))]
                  :else [(str "  " k ": " (text/for-operator labels (one-line value)))]))]
    (concat
     [(str "Finding " (inc i) " of " (count (:findings r)) ", report " (:id r) " from " (one-line (:role r)))]
     (mapcat field (reports/finding-fields f v))
     (if-let [flags (seq (:flags v))]
       (cons "  flags:" (map #(str "    - " (text/for-operator labels (flag-text %))) flags))
       ["  flags: none"]))))

(defn- seen-as-text [{:keys [kind entry report finding decision]}]
  (case kind
    "ledger" (str "matches ledger entry " entry)
    "pending" (str "matches finding " (some-> finding inc) " of report " report
                   (case decision
                     nil ", waiting for review"
                     "accept" ", which has since been accepted"
                     "not-relevant" ", which you have since judged not relevant"
                     (str ", since decided " decision)))
    "not-relevant" (str "matches finding " (some-> finding inc) " of report " report ", which you judged not relevant")
    "earlier" (str "matches finding " (some-> finding inc) " of this report")
    "matches something seen before"))

(defn report-header
  "What a report says about itself, what flags its own text, and which of
  its findings are hidden as seen before and what each matched."
  [zone r]
  (let [labels (get-in r [:verification :labels] (:labels r))
        hidden (keep-indexed (fn [i f] (when (:seen-before f) [i f])) (get-in r [:verification :findings]))]
    (concat
     [(str "Report " (:id r) " from " (one-line (:role r)) ", submitted " (local (:submitted-at r) zone))]
     (block "summary" labels (:summary r))
     (map #(str "  flag: " (text/for-operator labels (flag-text %))) (get-in r [:verification :flags]))
     (when (seq hidden)
       (cons (str "  hidden as seen before: " (count hidden))
             (for [[i f] hidden] (str "    - finding " (inc i) " " (seen-as-text (:seen-as f)))))))))

(defn reports-waiting
  "The reports with findings for the operator to review."
  [zone rs]
  (if (empty? rs)
    ["No reports are waiting for you."]
    (for [r rs]
      (str "Report " (:id r) " from " (:role r) ", submitted " (local (:submitted-at r) zone) ": "
           (count (core/pending-findings r)) " of " (count (:findings r)) " findings to review"
           " (relay review " (:id r) ")"))))

(defn ledger
  "The ledger as the operator sees it: every entry on lines of its own,
  its text marked and defanged when relay accepted it without the
  operator. Each value stays on its line; the edge escapes any break."
  [zone entries]
  (if (empty? entries)
    ["The ledger has no entries."]
    (mapcat (fn [{:keys [id kind title organization url location deadline amount status trusted role added-at labels]}]
              (map #(text/for-operator labels %)
                   [(str "[" id "] " kind ": " title " at " organization (when location (str ", " location)))
                    (str "    " (if deadline (str "deadline " deadline) "no deadline") ", "
                         (if amount (str "amount " (reports/amount-text amount)) "no amount")
                         ", status " status ", from " role ", " (local added-at zone)
                         (if trusted ", accepted by you" ", accepted by relay unread"))
                    (str "    " url)]))
            entries)))

;; --- the morning brief (relay.brief)
;;
;; One push: a title of counts and a body of short lines. The text from
;; the calendar and reminders is one field of one line of what the tool
;; printed, so it holds no line break; the push edge (relay.notify) makes
;; every character visible and, when the brief's labels say contaminated,
;; marks the whole brief and breaks its URLs.

(def ^:private brief-names
  {:calendar ["Calendar" "event" "events"]
   :reminders ["Reminders due" "reminder" "reminders"]
   :reports ["Reports waiting for review" "report" "reports"]
   :holds ["Held calls" "held call" "held calls"]})

(defn- counted [n one many] (str n " " (if (= 1 n) one many)))

(defn- brief-count
  "A section in the title: its count, or that it is unavailable. Nil for
  a tool that is not configured."
  [{:keys [key status count]}]
  (let [[_ one many] (brief-names key)]
    (case status
      :ok (counted count one many)
      :unavailable (str (name key) " unavailable")
      :missing nil)))

(defn- brief-section [{:keys [key status count lines tool why]}]
  (let [[heading] (brief-names key)]
    (case status
      :ok (if (zero? count)
            [(str heading ": none")]
            (cons (str heading ": " count) (map #(str "  " %) lines)))
      :unavailable [(str heading ": unavailable, the tool " tool " " why ".")]
      :missing [(str heading ": not configured, relay.edn has no tool " tool ".")])))

(defn brief
  "A composed brief (relay.brief/compose) as the push that carries it:
  {:title :body}."
  [{:keys [date sections]}]
  {:title (str "Morning brief for " date ": " (str/join ", " (keep brief-count sections)))
   :body (str/join "\n" (mapcat brief-section sections))})

;; --- relay week

(defn duration
  "Seconds as a short span: 45s, 12m, 3h 05m, 2d 4h."
  [s]
  (when s
    (let [s (long (Math/round (double s)))]
      (cond (< s 60) (str s "s")
            (< s 3600) (str (quot s 60) "m")
            (< s 86400) (format "%dh %02dm" (quot s 3600) (quot (mod s 3600) 60))
            :else (format "%dd %dh" (quot s 86400) (quot (mod s 86400) 3600))))))

(defn- interruption-ref [{:keys [kind ref]}]
  (case kind
    "hold" (str "hold " ref)
    "permission" (str "permission request " ref)
    ("quarantine" "declassify") (str "memo " ref)
    "fresh" (str "relay fresh " ref)
    "finding" (let [[r i] ref] (str "report " r " finding " (inc i)))
    "harness" (str "harness " ref)
    "dead-letter" (str "dead letter " ref)
    (str kind " " ref)))

(defn- days-between
  "Every date from the window's first day to its last, in `zone`, for a
  window of up to a month; for a longer one, the days with any."
  [{:keys [^Instant from ^Instant until]} ^ZoneId zone per-day]
  (if (and from until (<= (.toDays (java.time.Duration/between from until)) 31))
    (let [first-day (java.time.LocalDate/ofInstant from zone)
          last-day (java.time.LocalDate/ofInstant (.minusNanos until 1) zone)]
      (map str (take-while #(not (.isAfter ^java.time.LocalDate % last-day)) (iterate #(.plusDays ^java.time.LocalDate % 1) first-day))))
    (keys per-day)))

(defn- wake-name [chat id] (str (if chat "conversation " "wake ") id))

(defn week-report
  "relay week's report (relay.week/report) as lines."
  [{:keys [window interruptions actions notifications roles health flags]} ^ZoneId zone]
  (let [{:keys [from until]} window
        names (into {} (map (juxt first second) week/interruption-kinds))
        how (into {} (map (juxt first #(nth % 2)) week/interruption-kinds))
        role-line (fn [labels s] (text/for-operator labels s))]
    (concat
     [(str "relay week, " (or (local from zone) "the start") " to " (or (local until zone) "now")
           " (" zone ")")
      ""
      "Interruptions: each time relay asked you for something"
      (format "  %-32s %6s %8s %8s %8s %8s" "" "arose" "decided" "pending" "median" "longest")]
     (mapcat (fn [{:keys [kind arose decided pending decisions median-seconds max-seconds oldest-pending-seconds]}]
               (cons (format "  %-32s %6d %8d %8d %8s %8s" (names kind) arose decided pending
                             (or (duration median-seconds) "-") (or (duration max-seconds) "-"))
                     (when (pos? arose)
                       [(str "    " (how kind)
                             (when (seq decisions)
                               (str "; " (str/join ", " (for [[d n] decisions] (str d " " n)))))
                             (when oldest-pending-seconds
                               (str "; oldest waiting " (duration oldest-pending-seconds))))])))
             (:kinds interruptions))
     (when-let [ps (seq (:pending interruptions))]
       (cons "  Still waiting:"
             (for [{:keys [role arose-at] :as p} ps]
               (str "    " (interruption-ref p) (when role (str ", " role)) ", since " (local arose-at zone)))))
     [(str "  Your own decisions: " (if (seq actions) (str/join ", " (for [[a n] actions] (str a " " n))) "none"))
      ""
      (str "Notifications: " (:total notifications))]
     (let [per-day (:per-day notifications)]
       (for [d (days-between window zone per-day)] (format "  %s  %d" d (get per-day d 0))))
     (when (seq (:per-kind notifications))
       [(str "  by kind: " (str/join ", " (for [[k n] (sort-by (comp - val) (:per-kind notifications))] (str k " " n))))])
     (when (seq (:memos-by-role notifications))
       [(str "  memos to you, by sender: "
             (str/join ", " (for [[r n] (:memos-by-role notifications)] (str r " " n))))])
     ["" "Wakes by role"]
     (if (empty? roles)
       ["  none"]
       (mapcat (fn [{:keys [role role-labels wakes conversations memos-closed memos-blocked memos-left-open
                            memos-dead-lettered cut-short crashed held tools-unavailable cost-usd
                            conversation-cost-usd priced-wakes cost-per-wake unpriced]}]
                 (map #(role-line role-labels %)
                      [(str "  " role ": " wakes (if (= 1 wakes) " wake, " " wakes, ")
                            conversations (if (= 1 conversations) " conversation" " conversations"))
                       (str "    memos: " memos-closed " closed, " memos-blocked " blocked, " memos-left-open
                            " left open, " memos-dead-lettered " dead-lettered")
                       (str "    " cut-short " cut short, " crashed " crashed, " held " held, "
                            tools-unavailable " without their tools")
                       (str "    cost " (dollars cost-usd)
                            (when cost-per-wake (str ", " (dollars cost-per-wake) " a wake over " priced-wakes " priced"))
                            (when (pos? conversation-cost-usd) (str ", conversations " (dollars conversation-cost-usd)))
                            (when (pos? unpriced) (str "; " unpriced " without a cost")))]))
               roles))
     ["" "Health checks: wakes that look wrong"]
     (if (empty? health)
       ["  none"]
       (for [{:keys [id role role-labels chat started-at failed]} health]
         (role-line role-labels (str "  " (wake-name chat id) ", " role ", " (local started-at zone) ": "
                                     (str/join ", " failed)))))
     ["" "Flags"]
     (if (empty? flags)
       ["  none (relay flag <wake> good|bad [note] marks one)"]
       (for [{:keys [wake role role-labels chat started-at value note]} flags]
         (role-line role-labels (str "  " (wake-name chat wake) ", " role ", " (local started-at zone) ": " value
                                     (when note (str ": " note)))))))))
