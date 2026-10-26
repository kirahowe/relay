(ns relay.provenance
  "Where something the operator is shown came from. Pure.

  Labels say everything a session could have seen, and they stay on every
  event for security: a public role is never shown anything private
  (forbid_labels, I3). But a role's prompt holds a lot it did not go and
  read in this wake: its notes, skills, journal tail, fact topics and
  board, all written earlier by sessions that may have read outside text.
  If provenance followed the labels, one calendar poll would mark nearly
  everything after it as from the calendar.

  So relay keeps a second, narrower record, the sources: the outside text
  a session read directly in this wake. That is the memos it was woken
  for and what its tool calls returned, each counted by its own sources,
  never by what its writer had in context. What the operator is shown
  about where something came from, and which changes to how a role works
  the daily note flags (decision 14), follow the sources only. Labels stay
  the bound for security.

  A source is a label that names an outsider: `contaminated` (something
  from outside, no more known), or kind:what such as
  mail:dana@example.org, github:kira/tidy, ics:calendar, files:downloads
  (a folder mounted in the shell and not marked trusted), and two that
  relay records itself and never sends to agentloop: web:<page or URL>
  for a page a tool read, and search:web for a web search."
  (:require [clojure.string :as str]
            [relay.text :as text]))

(def contaminated "contaminated")

(defn source?
  "Whether a label names an outsider: contaminated, or kind:what."
  [l]
  (boolean (and (string? l) (or (= contaminated l) (re-matches #"[a-z]+:.+" l)))))

(defn sources
  "The labels among `labels` that name an outsider, distinct and sorted.
  Private and anything else are left out. A specific source makes the
  bare `contaminated` redundant, so it is kept only alone."
  [labels]
  (let [ss (sort (distinct (filter source? labels)))
        specific (remove #{contaminated} ss)]
    (vec (if (seq specific) specific ss))))

(defn outside?
  "Whether any of these sources is an outsider."
  [srcs]
  (boolean (seq (sources srcs))))

(defn direct
  "The sources of what a session read directly in a wake: the memos it
  was woken for (`carried`, each memo's own sources), what relay's tool
  command handed back to it (`read`, each result's own sources), and,
  for the tools agentloop calls itself (MCP), whose results relay never
  sees, any of their static labels the session holds (`mcp` the labels
  those tools give, `session` what the session holds). A label of an MCP
  tool counts whenever the session holds it, even when it might have come
  from the prompt: relay cannot tell, and saying too much is the safe
  way."
  [{:keys [carried read session mcp]}]
  (sources (concat carried read (filter (set mcp) session))))

;; --- sources relay records itself

(def ^:private target-limit 100)

(defn- cut [s n]
  (let [s (str/trim (str/replace (str s) #"(?U)\s+" " "))]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn tool-sources
  "The sources of one call to an outside tool (one whose static labels
  carry contaminated), given its input and the labels of its result: the
  page or URL it read as web:<target>, a search as search:web, else what
  the labels name. Nil sources when nothing in it came from outside."
  [input labels]
  (let [labels (sources labels)
        input (if (map? input) input {})
        get* (fn [k] (let [v (or (get input k) (get input (name k)))] (when (and (string? v) (not (str/blank? v))) v)))
        target (or (get* :url) (get* :page))]
    (cond
      (empty? labels) []
      target (sources (conj (vec (remove #{contaminated} labels)) (str "web:" (cut target target-limit))))
      (get* :query) (sources (conj (vec (remove #{contaminated} labels)) "search:web"))
      :else labels)))

;; --- in words

(def ^:private calendar-words
  "Names an operator gives their own calendar feed, which read as \"your
  calendar\" rather than a doubled \"the calendar calendar\"."
  #{"calendar" "cal" "ics" "my-calendar" "mine" "personal" "main" "default"})

(defn phrase
  "One source in plain words, or nil for `contaminated`, which says no
  more than that something came from outside. Anything an outsider chose
  (a sender's address, a page) is defanged, so it is never a link."
  [l]
  (when (and (source? l) (not= contaminated l))
    (let [[kind what] (str/split l #":" 2)]
      (case kind
        "mail" (cond (= "unverified-operator" what) "a mail that says it is from you, not verified"
                     (= "unknown" what) "a mail from an unknown sender"
                     :else (str "a mail from " (text/defang (text/without-hidden what))))
        "github" (str "GitHub (" what ")")
        "ics" (cond (calendar-words what) "your calendar"
                    (str/includes? what "cal") (str "the calendar feed " what)
                    :else (str "the " what " calendar"))
        "web" (str "the web page " (text/defang (cut (text/without-hidden what) 80)))
        "search" "a web search"
        "files" (str "your files in " (text/without-hidden what))
        "something from outside"))))

(defn subject-prefix
  "How relay.ingest begins the subject of a memo it writes for a source,
  such as \"GitHub owner/repo: \" or \"Mail from eve@example.org: \", or
  nil for a source whose memos relay does not title (the web). A subject
  that starts with it names its source in relay's own words, not an
  outsider's. A mail that says it is from the operator and is not
  verified has none: where it came from is always said in full. Pure."
  [l]
  (when (and (source? l) (not= contaminated l))
    (let [[kind what] (str/split l #":" 2)]
      (case kind
        "mail" (cond (= "unverified-operator" what) nil
                     (= "unknown" what) "Mail from an unknown sender: "
                     :else (str "Mail from " what ": "))
        "github" (str "GitHub " what ": ")
        "ics" (let [w (phrase l)] (str (str/upper-case (subs w 0 1)) (subs w 1) ": "))
        nil))))

(defn phrases
  "The sources in plain words, distinct, in order: \"something from
  outside\" only when nothing says more."
  [srcs]
  (let [ss (sources srcs)
        ws (vec (distinct (keep phrase ss)))]
    (cond (seq ws) ws
          (seq ss) ["something from outside"]
          :else [])))

(def ^:private shown-at-most 3)

(defn- listed [ws sep]
  (let [n (count ws)]
    (if (<= n shown-at-most)
      (str/join sep ws)
      (str (str/join sep (take shown-at-most ws)) " and " (- n shown-at-most) " more"))))

(defn summary
  "The sources as one phrase for a sentence (\"shaped by ...\"): up to
  three, then how many more. Nil when there are none."
  [srcs]
  (let [ws (phrases srcs)]
    (when (seq ws)
      (if (<= (count ws) 2)
        (str/join " and " ws)
        (listed ws ", ")))))

(defn drew-on
  "The one footer line a message's provenance is shown as: \"Drew on: the
  web page x; a mail from y\". Nil when nothing came from outside."
  [srcs]
  (let [ws (phrases srcs)]
    (when (seq ws) (str "Drew on: " (listed ws "; ")))))

(defn- kind-words
  "What kind of thing a source is, in two or three words, never what it
  names: no address or page an outsider chose."
  [l]
  (when (and (source? l) (not= contaminated l))
    (case (first (str/split l #":" 2))
      "web" "a web page"
      "search" "a web search"
      "mail" "a mail"
      "github" "GitHub"
      "ics" "a calendar"
      "files" "your files"
      nil)))

(defn mark
  "A short mark for a line in a listing, such as a routine or a skill:
  \"(from a web page)\", saying only what kind of outside text shaped
  it. Two kinds are named; more, or none known, read \"(from outside)\".
  Nil when nothing came from outside."
  [srcs]
  (let [ss (sources srcs)
        ws (vec (distinct (keep kind-words ss)))]
    (cond (empty? ss) nil
          (<= 1 (count ws) 2) (str "(from " (str/join " and " ws) ")")
          :else "(from outside)")))
