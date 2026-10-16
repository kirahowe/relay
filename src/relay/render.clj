(ns relay.render
  "What the relay command shows. Pure: each function takes the data to
  show and returns lines of text, which relay.cli prints."
  (:require [cheshire.core :as json]
            [clojure.walk]
            [clojure.string :as str]
            [relay.core :as core]
            [relay.text :as text])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)))

(defn local
  "An instant as the operator's local time, or nil."
  [^Instant at zone]
  (when at (.format (.atZone at ^ZoneId zone) (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))))

(defn memo
  "One memo, as the operator's inbox shows it. One from a contaminated
  source is marked and its URLs broken."
  [zone {:keys [id from subject body sent-at labels]}]
  [(str "\n[" id "] " (text/for-operator labels subject))
   (str "    from " from ", " (local sent-at zone))
   ""
   (str/replace (text/for-operator labels (str body)) #"(?m)^" "    ")])

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

(defn- role-status [state zone id]
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
                          (format "    dead  [%d] %s (relay retry %s)" memo-id subject id))))))

(defn status
  "Roles with their routines and open work, harnesses, the operator's
  inbox and the latest wakes, as of `now`. `paused` is the lines to show
  when relay is paused (relay.pause/recovery-lines), or nil."
  [state zone ^Instant now & [config paused]]
  (concat
   (when paused
     (concat paused [""]))
   ["Roles"]
   (mapcat #(role-status state zone %) (core/active-role-ids state))
   (for [[h {:keys [condition paused-until]}] (sort-by key (:harnesses state))]
     (str "\nHarness " h ": "
          (if (and paused-until (.isAfter ^Instant paused-until now))
            (str condition ", next try " (local paused-until zone))
            "available")))
   [(format "\nOperator's inbox: %d open" (count (core/unread-memos state core/operator)))]
   (when-let [hs (seq (core/open-holds state))]
     [(format "Held tool calls: %d (relay holds)" (count hs))])
   (when-let [qs (seq (filter #(core/quarantined? config %) (filter #(= :unread (:status %)) (vals (:memos state)))))]
     [(format "Quarantined memos: %s (relay release <memo>)" (str/join ", " (sort (map :id qs))))])
   (when-let [recent (seq (take-last 5 (sort-by :id (vals (:wakes state)))))]
     (cons "\nRecent wakes"
           (for [{:keys [id role started-at status outcome crashed]} recent]
             (text/for-operator (core/id-labels state role)
                                (format "  %-6d %s  %-20s %s" id (local started-at zone) role
                                        (if (= :running status) "running"
                                            (str outcome (when crashed ", crashed"))))))))))

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
  (for [{:keys [name text written-at labels]} notes]
    (text/for-operator labels (str "## " name "  (" (local written-at zone) ")\n\n" text "\n"))))

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

(defn- why-held
  "Why agentloop's policy held a call, from the tool's class and the
  session's labels."
  [{:keys [class always-hold]} labels]
  (let [ls (set labels)]
    (cond
      always-hold "the tool is set to always ask you"
      (and (= :egress class) (ls "private") (ls "contaminated"))
      "it can send data out, and the session has seen both your private data and an outsider's text"
      (and (= :act class) (ls "contaminated"))
      "it acts in your name, and the session has seen an outsider's text"
      :else "the policy held it")))

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
  something else, and the whole input."
  ([h] (hold h nil))
  ([{:keys [id role tool input session call labels label-sources]} config]
   (let [t (get-in config [:tools tool])
         class (or (:class t) (when-not t :builtin))
         input-text (json/generate-string input {:pretty true})
         odd (text/lookalikes (str/join " " (filter string? (tree-seq coll? seq input))))]
     (concat
      [(str "Hold " id ": " role " wants to use " tool)
       (str "    held because " (why-held t labels))
       (str "    the tool: " (if (= :builtin class) "one of relay's own" (name (or class :unknown)))
            (when (:description t) (str ", " (:description t))))]
      (when (#{:egress :act} class)
        (let [ds (destinations input)]
          [(str "    where it would go: " (if (seq ds) (str/join "; " ds) "nothing in the input names a place"))]))
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
              (map #(str "      " %) odd)))
      [(str "    session " session ", call " call)
       "    input:"
       (str/replace input-text #"(?m)^" "      ")]))))

(defn holds
  ([hs] (holds hs nil))
  ([hs config]
   (if (empty? hs)
     ["No tool calls are waiting for you."]
     (concat (mapcat #(concat (hold % config) [""]) hs)
             ["relay approve <hold> lets one run; relay deny <hold> [why] refuses it,"
              "and with --end also starts the role's next wake afresh."]))))
