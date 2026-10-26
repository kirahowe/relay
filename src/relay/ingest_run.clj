(ns relay.ingest-run
  "The ingestors' edges: fetch what each source in relay.edn's :ingest
  has, and submit it as one :ingest/record command, which relay.core
  turns into memos. Only relay.edn says what to fetch. A failed fetch is
  submitted as :ingest/failed.

    POP3     curl, the login on stdin, never on a command line. relay
             never deletes from the mailbox: it remembers each mail's
             UIDL instead.
    GitHub   the REST API's issues list for each repository (pull
             requests included), with since= and If-None-Match, so a poll
             that finds nothing new costs little of the rate limit. The
             token, when there is one, is what :credentials-command prints.
    ICS      one GET of the feed's URL. The URL of a private calendar is
             a secret, so no error quotes it.

  No redirect is followed: where a fetch goes is relay.edn's to say."
  (:require [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.string :as str]
            [relay.channels :as channels]
            [relay.config :as config]
            [relay.core :as core]
            [relay.ics-feed :as ics-feed]
            [relay.inbound :as inbound]
            [relay.ingest :as ingest]
            [relay.mailbox :as mailbox]
            [relay.text :as text])
  (:import (java.io InputStream)
           (java.time Instant)))

(def body-limit
  "The most bytes relay reads of a GitHub answer or a calendar feed."
  (* 10 1024 1024))

(defn secret
  "What a source's :credentials-command prints, trimmed of its last line
  break, or nil when it has none. Read at each poll, so relay holds no
  secret between them."
  [{:keys [credentials-command]}]
  (when (seq credentials-command)
    (let [{:keys [exit out]} (apply p/shell {:out :string :err :string :continue true :in ""} credentials-command)]
      (when-not (zero? exit)
        (throw (ex-info (str "the :credentials-command exited " exit) {})))
      (let [s (str/trim-newline out)]
        (when (str/blank? s) (throw (ex-info "the :credentials-command printed nothing" {})))
        s))))

(defn- first-line [s n]
  (let [l (str/trim (or (first (str/split-lines (str s))) ""))]
    (if (> (count l) n) (subs l 0 n) l)))

;; --- POP3

(defn pop3-argv
  "The curl command for one POP3 request to a source: `path` after the
  server (a message number, or empty), `command` a POP3 command to send
  instead of the default (UIDL, TOP n 0), or nil. -q first, so no .curlrc
  is read; no proxy; only POP3. The login comes on stdin (-K -)."
  [{:keys [url] :as src} path command]
  (cond-> ["curl" "-q" "--silent" "--show-error" "--noproxy" "*" "--proto" "=pop3,pop3s"
           "--max-time" (str (ingest/timeout-seconds src))
           "--url" (str (str/replace url #"/$" "") "/" path)]
    command (into ["-X" command])
    true (into ["-K" "-"])))

(defn- pop3!
  "One POP3 request; its output as raw bytes. Throws when curl fails."
  ^bytes [src login path command]
  (let [{:keys [exit out err]} (apply p/shell {:in login :out :bytes :err :string :continue true}
                                      (pop3-argv src path command))]
    (when-not (zero? exit)
      (throw (ex-info (str "the mail server said: " (first-line err 200)) {:exit exit})))
    out))

(defn numbered
  "POP3 lines of \"n value\" as {n value}."
  [^bytes out]
  (into {} (for [l (str/split-lines (String. out "ISO-8859-1"))
                 :let [[_ n v] (re-matches #"\s*(\d+)\s+(\S+)\s*" l)]
                 :when n]
             [(parse-long n) v])))

(defn fetch-mailbox
  "The new mails in a POP3 mailbox: those whose UIDL `seen` does not
  have, the first mails-per-poll of them, as items {:key uidl :version
  \"1\" :raw :size :partial}. A mail over mail-size-limit is read as its
  headers only (TOP n 0). Throws when the mailbox changed under the
  reads, so a mail is never recorded under another's UIDL."
  [src seen]
  (let [pw (or (secret src) "")
        login (or (channels/curl-login (:user src) pw)
                  (throw (ex-info "the mailbox login holds a line break, which curl cannot take" {})))
        uidls (numbered (pop3! src login "" "UIDL"))
        wanted (->> uidls (remove (fn [[_ u]] (contains? seen u))) (sort-by key) (take ingest/mails-per-poll))]
    (if (empty? wanted)
      []
      (let [sizes (into {} (for [[n s] (numbered (pop3! src login "" nil))] [n (parse-long s)]))
            items (vec (for [[n u] wanted
                             :let [size (or (get sizes n) 0)
                                   partial (> size ingest/mail-size-limit)
                                   raw (if partial
                                         (pop3! src login "" (str "TOP " n " 0"))
                                         (pop3! src login (str n) nil))]]
                         {:key u :version "1" :raw (mailbox/bytes->raw raw) :size size :partial partial}))
            after (numbered (pop3! src login "" "UIDL"))]
        (when-not (every? (fn [[n u]] (= u (get after n))) wanted)
          (throw (ex-info "the mailbox changed while relay read it; it tries again next time" {})))
        items))))

;; --- HTTP

(defn- read-limited
  "At most `limit` bytes of a stream, as a UTF-8 string. Throws when there
  is more."
  [^InputStream in limit]
  (with-open [in in]
    (let [buf (.readNBytes in (int (inc limit)))]
      (when (> (count buf) limit) (throw (ex-info "the answer was too big to read" {})))
      (String. buf "UTF-8"))))

(defn- get!
  "GETs a URL from relay.edn: {:status :headers :body}. Never follows a
  redirect. An error names what went wrong, never the URL."
  [url headers timeout-seconds]
  (let [client (http/client {:follow-redirects :never :connect-timeout (* 1000 (min 30 timeout-seconds))})
        r (try (http/get url {:client client :headers headers :throw false :as :stream
                              :timeout (* 1000 timeout-seconds)})
               (catch Exception e
                 (throw (ex-info (str "could not reach it: "
                                      (str/replace (or (ex-message e) (.getSimpleName (class e))) url "its URL"))
                                 {}))))]
    (assoc r :body (if (= 200 (:status r)) (read-limited (:body r) body-limit)
                       (do (some-> ^InputStream (:body r) .close) "")))))

(defn- header-of [r n] (let [v (get (:headers r) n)] (if (sequential? v) (first v) v)))

(defn fetch-repo
  "One repository's issues and pull requests updated since its cursor,
  {:items [...] :cursor {:repo :since :etag}}. A repository seen for the
  first time starts at `now`: what was there before is not news."
  [src token repo {:keys [since etag] :as cursor} ^Instant now]
  (if (nil? since)
    {:items [] :cursor {:repo repo :since (str now) :etag nil}}
    (let [r (get! (ingest/github-url src repo since)
                  (cond-> {"Accept" "application/vnd.github+json" "User-Agent" "relay"
                           "X-GitHub-Api-Version" "2022-11-28"}
                    etag (assoc "If-None-Match" etag)
                    token (assoc "Authorization" (str "Bearer " token)))
                  (ingest/timeout-seconds src))]
      (case (int (:status r))
        304 {:items [] :cursor cursor}
        200 (let [items (ingest/github-items repo (:body r))
                  latest (reduce (fn [a b] (if (pos? (compare b a)) b a)) since (map :version items))]
              {:items items
               ;; The ETag is for this since=; a later one is another query.
               :cursor {:repo repo :since latest :etag (when (= latest since) (header-of r "etag"))}})
        (throw (ex-info (str "GitHub answered " (:status r) " for " repo) {}))))))

(defn fetch-github
  "Every repository a GitHub source lists: {:items :cursor [per repo]}."
  [src cursor ^Instant now]
  (let [token (secret src)
        by-repo (into {} (for [c cursor :when (map? c)] [(:repo c) c]))
        results (for [repo (:repos src)] (fetch-repo src token repo (get by-repo repo) now))]
    {:items (vec (mapcat :items results)) :cursor (vec (map :cursor results))}))

(defn fetch-calendar
  "A calendar feed: {:items :keys} (relay.ics-feed/poll)."
  [{:keys [url] :as src} zone ^Instant now]
  (let [r (get! url {"Accept" "text/calendar" "User-Agent" "relay"} (ingest/timeout-seconds src))]
    (when-not (= 200 (:status r))
      (throw (ex-info (str "the calendar answered " (:status r)) {})))
    (ics-feed/poll (:body r) zone now)))

;; --- polling

(defn poll!
  "Polls one source now and records what it found, or that it failed.
  Returns the events appended. Does nothing while the role it reports
  to is not active: nothing is lost, since nothing is consumed."
  [{:keys [config] :as sys} source-name]
  (let [src (get-in config [:ingest source-name])
        state (inbound/current-state sys)
        now ((:clock sys))
        {:keys [seen cursor]} (core/ingest-record state source-name)]
    (when (and src (core/active-role? state (:to src)))
      (let [found (try
                    (case (:kind src)
                      :pop3 {:items (fetch-mailbox src (set (keys seen)))}
                      :github (fetch-github src cursor now)
                      :ics (fetch-calendar src (config/zone config) now))
                    (catch Exception e {:error (or (ex-message e) (.getSimpleName (class e)))}))]
        (if (:error found)
          (inbound/submit! sys {:type :ingest/failed :actor core/dispatcher
                                :args {:source source-name :error (:error found)}})
          (inbound/submit! sys {:type :ingest/record :actor core/dispatcher
                                :args (merge {:source source-name :items [] :keys [] :cursor cursor} found)}))))))

(defn poll-due!
  "Polls every source due at `now` (relay.ingest/due?), one after
  another. A source that throws stops no other: its reason goes to
  stderr."
  [{:keys [config] :as sys} ^Instant now]
  (doseq [[source-name src] (sort-by key (:ingest config))
          :when (ingest/due? src now)]
    (try (poll! sys source-name)
         (catch Exception e
           (binding [*out* *err*]
             (println (str "relay tick: could not record what " source-name " brought in: " (ex-message e))))))))

(defn- and-list [xs]
  (let [xs (vec xs)]
    (case (count xs)
      0 ""
      1 (first xs)
      (str (str/join ", " (pop xs)) " and " (peek xs)))))

(defn- named
  "Up to three names, each cut to 40 characters and never a link (an
  outsider wrote them), then how many more."
  [names]
  (let [cut #(let [s (str/trim (str %))] (if (> (count s) 40) (str (subs s 0 39) "…") s))
        shown (map (comp text/defang cut) (take 3 names))
        more (- (count names) 3)]
    (str (str/join ", " shown) (when (pos? more) (str " and " more " more")))))

(defn- after-prefix [subject]
  (str/trim (or (second (re-find #"^[^:]*:\s*(.*)$" (str subject))) subject)))

(defn came-in
  "What a poll's memos brought in, in a few words for relay poll: the
  events of a calendar, the issues and pull requests of each repository,
  the subjects of mail. Pure."
  [{:keys [kind]} memos]
  (case kind
    :ics (str/join "; " (for [{:keys [subject body]} memos
                              :let [names (keep #(second (re-find #"^(?:New|Changed): (.+)$" %))
                                                (str/split-lines (str body)))]]
                          (str (after-prefix subject) (when (seq names) (str " (" (named names) ")")))))
    :github (str/join "; " (for [{:keys [subject body]} memos
                                 :let [repo (second (re-find #"^GitHub ([^:]+):" (str subject)))
                                       items (for [l (str/split-lines (str body))
                                                   :let [[_ n user] (re-find #"^New (?:issue|pull request) #(\d+) by (\S+):" l)]
                                                   :when n]
                                               (str "#" n " by " user))]]
                             (str (after-prefix subject) (when repo (str " in " repo))
                                  (when (seq items) (str " (" (named items) ")")))))
    (str (count memos) (if (= 1 (count memos)) " new mail" " new mails")
         " (" (named (map :subject memos)) ")")))

(defn poll-line
  "What relay poll says for one source: `events` the poll appended (nil
  when it did not poll), and `started` the GitHub repositories it saw for
  the first time, which only mark where watching begins, and `still` the
  source's recorded failure when this poll failed the same way. Pure."
  ([source-name src events started] (poll-line source-name src events started nil))
  ([source-name src events started still]
   (let [sent (map :data (filter #(= "memo/sent" (:type %)) events))
         memos (count sent)
         failed (first (filter #(#{"ingest/failed" "error"} (:type %)) events))]
     (str source-name ": "
          (cond
            (nil? events) (str "not checked, since " (:to src) " is not a role.")
            failed (str "could not check it: " (get-in failed [:data :error]))
            ;; The same failure as last time, which is not recorded again.
            still (str "could not check it: " still)
            (and (zero? memos) (seq started))
            (str "now watching " (and-list started) "; new issues and pull requests show up from the next check.")
            (zero? memos) "nothing new."
            :else (str (came-in src sent) "."))))))

(defn poll-now!
  "Polls the named source, or every one, now, due or not (relay poll).
  Returns a line for each, saying what came in."
  [{:keys [config] :as sys} only]
  (let [sources (cond->> (sort-by key (:ingest config)) only (filter #(= only (key %))))]
    (when (and only (empty? sources))
      (throw (ex-info (str "relay.edn's :ingest has no source " only ".") {:relay/usage true})))
    (when (empty? sources)
      (throw (ex-info "relay.edn has no :ingest sources to check." {:relay/usage true})))
    (vec (for [[n src] sources]
           (let [watched (set (keep :repo (filter map? (:cursor (core/ingest-record (inbound/current-state sys) n)))))
                 started (when (= :github (:kind src)) (remove watched (:repos src)))
                 events (try (poll! sys n) (catch Exception e [{:type "error" :data {:error (ex-message e)}}]))
                 ;; A failure the same as the last is not recorded again,
                 ;; so it is read from the source's record.
                 still (when (and (some? events) (not-any? #(= "ingest/polled" (:type %)) events))
                         (get-in (inbound/current-state sys) [:ingest n :failing :error]))]
             (poll-line n src events started still))))))
