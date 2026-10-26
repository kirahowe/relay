(ns relay.shown
  "What a scout was shown, and the fetch rule (plan O1, I4): a public
  role's call of an :egress tool runs only when every address in it
  appeared verbatim in an earlier result of an :egress tool in the same
  session. A scout may fetch what a search or a page showed it, but no
  address it built, which could carry what the steward told it out to
  whoever runs that server.

  An address in a string other than `url`, a search query above all, runs from
  its scheme to the end of the string and must hold no space and nothing
  a tool would escape, so a tool's echo of the query can make no longer
  address fetchable.

  relay keeps its own record of those results. Every :egress call reaches
  relay's tool command (an :egress tool is an :envelope tool, relay.config),
  so relay sees each result before the model does, and the record is written
  before the result is handed back. agentloop's session store would also
  hold results, but it is agentloop's format, it holds the results of
  every tool, and the text of relay's own tools (a memo from the steward,
  say) is just what the rule must not count.

  The core is pure: `addresses` and `refusal`. The record is files under
  the wake's run directory, one per result, read back across the wakes a
  session resumed."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn- gap?
  "Whether a code point ends an address: any space Unicode knows, any
  control or format character. An address the model copies holds none."
  [cp]
  (or (Character/isWhitespace (int cp)) (Character/isSpaceChar (int cp))
      (Character/isISOControl (int cp))
      (= Character/FORMAT (Character/getType (int cp)))))

(defn- runs
  "The maximal runs of `s` between gaps."
  [s]
  (->> (str/split (.toString ^StringBuilder
                   (reduce (fn [^StringBuilder b cp]
                             (if (gap? cp) (.append b \space) (.appendCodePoint b (int cp))))
                           (StringBuilder.) (iterator-seq (.iterator (.codePoints (str s))))))
                  #" +")
       (remove str/blank?)))

(def ^:private scheme-url #"[A-Za-z][A-Za-z0-9+.\-]*://.*")

(def ^:private scheme #"[A-Za-z][A-Za-z0-9+.\-]*://")

(defn- strings
  "Every string in a call's input, keys included, however deep."
  [x]
  (cond (string? x) [x]
        (keyword? x) [(subs (str x) 1)]
        (map? x) (mapcat (fn [[k v]] (concat (strings k) (strings v))) x)
        (sequential? x) (mapcat strings x)
        :else []))

(defn addresses
  "What in a call's input must have been shown, as [{:what :url} or
  {:what :inline}, :address]: the whole `url` argument, and, in every
  other string (a search query included, since a tool may echo its query
  back as a result), everything from the first scheme (`x://`) to the end
  of that string. Nothing is trimmed: a tool may echo the string as
  written, escaping what it cannot print, so whatever follows the scheme
  could become part of an address in the echo."
  [input]
  (let [input (if (map? input) input {})
        has-url (or (contains? input :url) (contains? input "url"))
        url (if (contains? input :url) (get input :url) (get input "url"))
        others (strings (dissoc input :url "url"))]
    (vec (concat
          (when has-url [[:url url]])
          (for [s (cond-> others (and has-url (not (string? url))) (concat (strings url)))
                :let [m (re-matcher scheme s)]
                :when (.find m)]
            [:inline (subs s (.start m))])))))

(def refused-made-up
  (str "relay refused this call: it names an address that no search or page in this session showed you."
       " Give an address exactly as a result showed it, character for character, or search for what you need."))

(def refused-malformed
  (str "relay refused this call: url must be one address exactly as a search or page showed it,"
       " starting with its scheme (https://), with no spaces."))

(def refused-inline
  (str "relay refused this call: an address may appear in a search only at its very end, exactly as a"
       " result showed it, with no space or other text after it. Search without the address, or fetch it."))

(def ^:private plain-run
  "An address written in a search: printable ASCII with no space, which a
  tool echoes as it is, so the echo adds nothing to it."
  #"[\x21-\x7E]+")

(defn refusal
  "Why a public role's call of an :egress tool with `input` may not run,
  given `shown`, the text of the earlier :egress results of its session,
  or nil when it may. Pure. Fixed text: it never quotes the call."
  [shown input]
  (let [shown (str shown)]
    (some (fn [[what a]]
            (cond
              (and (= :url what)
                   (not (and (string? a) (re-matches scheme-url a) (= [a] (runs a)))))
              refused-malformed
              (and (= :inline what) (not (re-matches plain-run a))) refused-inline
              (not (str/includes? shown a)) refused-made-up))
          (addresses input))))

;; --- the record, one file per result

(def ^:private separator
  "What goes between two results as they are read back. An address holds
  no newline, so none can run from one result into the next."
  "\n")

(defn session-wakes
  "The wakes whose results count for a call made in `wake-id` by `role`:
  that wake and those it resumed, since a resumed wake goes on with the
  same session. Nothing for a wake that is not the role's."
  [state role wake-id]
  (loop [id wake-id seen []]
    (let [w (get-in state [:wakes id])]
      (if (and w (= role (:role w)) (not (some #{id} seen)))
        (recur (:resumes w) (conj seen id))
        seen))))

(defn- dir [data-dir role wake-id]
  (fs/path data-dir "runs" role (str wake-id) "shown"))

(defn record!
  "Keeps an :egress result's text as shown in the wake's session. Each
  result is a file of its own, so calls run at once never write over
  each other."
  [data-dir role wake-id text]
  (let [d (dir data-dir role wake-id)]
    (fs/create-dirs d)
    (spit (str (fs/path d (str (System/currentTimeMillis) "-" (random-uuid) ".txt"))) (str text))))

(defn read-shown
  "The text of every :egress result kept for these wakes, one result to
  a line or more, never run together."
  [data-dir role wake-ids]
  (str/join separator
            (for [id wake-ids
                  :let [d (dir data-dir role id)]
                  :when (fs/directory? d)
                  f (sort (map str (fs/glob d "*.txt")))]
              (slurp f))))
