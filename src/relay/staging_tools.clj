(ns relay.staging-tools
  "The stand-in tools that staging data defines (see relay.staging), for
  eval cases to grant. Each is an envelope tool: the call arrives as JSON on
  stdin and one result envelope goes to stdout. The data directory is the
  first argument after the tool's name, and nothing is read or written
  outside it.

    fail-demo <data>   edit: sets a pretend reminder, but refuses one with
                       no HH:MM time in it
    web-demo <data>    read, contaminated: prints <data>/web/<page>, a page
                       copied from test/scenarios/fixtures by staging init
    web-ask-demo <data> egress, contaminated: standing in for webtool ask,
                       appends the query to <data>/searches.log and
                       returns the pages in <data>/web that hold most of
                       its words, at most n, numbered as ask numbers them
    mail-demo <data>   egress: appends the message to <data>/outbox.log
    campaign-draft-demo <data>
                       edit: saves a draft campaign (subject, body, group)
                       in <data>/campaigns.edn, or a new version of one
                       given its id. Every version is kept, so a change
                       can be undone
    campaign-send-demo <data>
                       act: schedules one version of a draft to its group
                       at a time, appending what goes out to
                       <data>/sent.log. It refuses a group other than the
                       one the draft is addressed to
    campaign-preview-demo <data>
                       campaign-send-demo's :preview: renders the draft
                       version a send names as campaigns.edn holds it,
                       with the group's size from
                       <data>/campaign-groups.edn and the send time. The
                       text comes from the stored draft, never from the
                       call
    verify-demo <data> the :verifier, standing in for webtool check: a URL
                       whose last part names a page in <data>/web is
                       fetched, and a quote is found when that page holds
                       it word for word; any other URL is not fetched

  Kept apart from relay.staging so that each call starts quickly: this
  namespace needs nothing of relay's but its JSON reader."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [relay.json :as strict]))

(def fail-message "The reminders service rejects a reminder without a time.")

(def page-name #"[a-z0-9][a-z0-9-]*\.(html|txt|md)")

(defn- envelope [content & {:keys [error labels]}]
  (cond-> {:content content :labels (vec labels)}
    error (assoc :is_error true)))

(defn- host [url] (second (re-find #"(?i)^https?://([^/?#:]+)" (str url))))

(defn- domain [h] (str/join "." (take-last 2 (str/split (str h) #"\."))))

(defn page-of
  "The page in <data>/web a URL stands for: the last part of its path,
  when that is a page name. Nil otherwise."
  [url]
  (let [path (second (re-find #"(?i)^https?://[^/?#]+(/[^?#]*)?" (str url)))
        last-part (last (str/split (str path) #"/"))]
    (when (and last-part (re-matches page-name last-part)) last-part)))

(defn- sha256 [^String text]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn check-items
  "verify-demo's answer to a check: one item per item asked, in the shape
  webtool check prints. `page-text` gives a page's text by name, or nil
  when there is no such page. Pure."
  [items page-text]
  {:items
   (vec (for [{:keys [url quotes]} items
              :let [h (host url)
                    text (some-> (page-of url) page-text)
                    fetched (some? text)
                    info {:name (str h) :unicode (str h) :punycode false :scripts ["Latin"] :mixed_scripts false}]]
          {:url url :fetched fetched :complete fetched :uncheckable ""
           :error (if fetched "" "no such page") :status (if fetched 200 404)
           :final_url (if fetched url "") :redirects [] :crosses_domains false
           :domain (domain h) :final_domain (if fetched (domain h) "")
           :host info :final_host (when fetched info)
           :sha256 (if fetched (sha256 text) "") :hidden_chars 0
           :quotes (vec (for [q quotes]
                          {:quote q :found (boolean (and fetched (str/includes? text (str q)))) :hidden_chars false}))}))})

(defn- query-words [q]
  (distinct (filter #(> (count %) 2) (str/split (str/lower-case (str q)) #"[^\p{L}\p{N}]+"))))

(defn ask-pages
  "The pages web-ask-demo returns for a query: of `pages`, a map of page
  name to text, those holding at least one of the query's words of three
  letters or more, most words first, then by name, at most n. Pure."
  [pages query n]
  (let [words (query-words query)
        score (fn [text] (count (filter #(str/includes? (str/lower-case text) %) words)))]
    (->> pages
         (map (fn [[page text]] [page text (score text)]))
         (filter #(pos? (nth % 2)))
         (sort-by (fn [[page _ s]] [(- s) page]))
         (take n)
         (mapv (fn [[page text]] [page text])))))

(defn render-ask
  "web-ask-demo's content, in webtool ask's shape: each page starts with a
  line holding `token`, then the result's title and URL, then the page.
  Pure."
  [query pages token]
  (if (empty? pages)
    (str "No results for \"" query "\".")
    (str (count pages) (if (= 1 (count pages)) " page" " pages") " for \"" query "\". Each page starts with a line \"=== page N "
         token " ===\".\n"
         (str/join (map-indexed (fn [i [page text]]
                                  (let [url (str "https://pages.example.org/" page)]
                                    (str "\n=== page " (inc i) " " token " ===\n"
                                         "result_title: " page "\nresult_url: " url "\nurl: " url "\nstatus: 200\n\n"
                                         text "\n")))
                                pages)))))

;; --- the campaign stand-ins

(def send-time #"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}")

(defn- text? [x] (and (string? x) (not (str/blank? x))))

(defn save-draft
  "campaign-draft-demo on `campaigns` (what campaigns.edn holds, or nil).
  Returns [campaigns envelope], campaigns being nil when nothing changes.
  A new draft gets the next id; a draft given by id gets a new version,
  and its earlier versions stay. Pure."
  [campaigns {:keys [draft subject body group]}]
  (let [drafts (:drafts campaigns {})]
    (cond
      (not (and (text? subject) (text? body) (text? group)))
      [nil (envelope "A draft needs a subject, a body and a group." :error true)]

      (and (some? draft) (not (contains? drafts draft)))
      [nil (envelope (str "There is no draft " (pr-str draft) ". Leave out `draft` to start a new one.") :error true)]

      :else
      (let [id (or draft (str "c" (inc (count drafts))))
            versions (get-in drafts [id :versions] [])
            version (inc (count versions))]
        [(assoc-in campaigns [:drafts id] {:id id :versions (conj versions {:version version :subject subject
                                                                              :body body :group group})})
         (envelope (str "Saved draft " id ", version " version ", to the group " (pr-str group)
                        ". Earlier versions are kept."))]))))

(defn- stored-version [campaigns draft version]
  (some #(when (= version (:version %)) %) (get-in campaigns [:drafts draft :versions])))

(defn send-problem
  "Why the service would refuse a send, or nil. Pure."
  [campaigns {:keys [draft version group send_at]}]
  (let [stored (stored-version campaigns draft version)]
    (cond
      (not (contains? (:drafts campaigns) draft)) (str "There is no draft " (pr-str draft) ".")
      (not (integer? version)) "A send names the draft's version, a whole number."
      (nil? stored) (str "Draft " draft " has no version " version ".")
      (not= group (:group stored)) (str "Draft " draft " version " version " is addressed to the group "
                                        (pr-str (:group stored)) ", not " (pr-str group) ".")
      (not (and (string? send_at) (re-matches send-time send_at)))
      "A send needs send_at, the local time to send, as YYYY-MM-DD HH:MM.")))

(defn send-draft
  "campaign-send-demo: [envelope line], line being what goes into sent.log,
  or nil when the service refuses. What goes out is the stored version,
  whatever else the call carries. Pure."
  [campaigns groups {:keys [draft version group send_at] :as input}]
  (if-let [why (send-problem campaigns input)]
    [(envelope why :error true) nil]
    (let [{:keys [subject body]} (stored-version campaigns draft version)]
      [(envelope (str "Scheduled draft " draft " version " version " to " (pr-str group) " for " send_at "."))
       (json/generate-string {:draft draft :version version :group group :members (get groups group)
                              :send_at send_at :subject subject :body body})])))

(defn preview-send
  "campaign-preview-demo: what a held send would do, as markdown, from the
  stored draft and the service's groups. Pure."
  [campaigns groups {:keys [draft version group send_at] :as input}]
  (if-let [why (send-problem campaigns input)]
    (envelope (str "The service would refuse this send: " why) :error true)
    (let [{:keys [subject body]} (stored-version campaigns draft version)
          n (get groups group)]
      (envelope (str "**Scheduled send** of draft " draft ", version " version ", to the group " (pr-str group)
                     (if n (str ", " n " people") ", a group the service does not know")
                     ", at " send_at ".\n\nSubject: " subject "\n\n---\n\n" body)))))

(defn- read-edn [file]
  (when (fs/exists? file) (edn/read-string (slurp (str file)))))

(defn- campaign! [tool dir input]
  (let [file (str (fs/path dir "campaigns.edn"))
        campaigns (read-edn file)
        groups (or (read-edn (fs/path dir "campaign-groups.edn")) {})]
    (case tool
      "campaign-draft-demo" (let [[saved env] (save-draft campaigns input)]
                              (when saved (spit file (pr-str saved)))
                              env)
      "campaign-send-demo" (let [[env line] (send-draft campaigns groups input)]
                             (when line (spit (str (fs/path dir "sent.log")) (str line "\n") :append true))
                             env)
      "campaign-preview-demo" (preview-send campaigns groups input))))

(defn answer
  "The envelope for one call. `input` is the call's input; `dir` the data
  directory. Returns [envelope side-effect], the side effect being nil or
  {:append path :line text}, so the decision is pure."
  [tool dir input]
  (case tool
    "fail-demo"
    (let [text (str (:reminder input))]
      (if (re-find #"\b([01]?\d|2[0-3]):[0-5]\d\b" text)
        [(envelope (str "Reminder set: " text))]
        [(envelope fail-message :error true)]))

    "web-demo"
    (let [page (str (:page input))]
      (if (re-matches page-name page)
        [(envelope nil :labels ["contaminated"]) {:read (str (fs/path dir "web" page))}]
        [(envelope (str "No page " (pr-str page) ". Pages are named like office-page.html.")
                   :error true :labels ["contaminated"])]))

    "web-ask-demo"
    (let [{:keys [query n]} input
          n (or n 3)]
      (if (or (str/blank? (str query)) (not (integer? n)) (not (<= 1 n 5)))
        [(envelope "A search needs a query, and n from 1 to 5." :error true)]
        [(envelope nil :labels ["contaminated"])
         {:append (str (fs/path dir "searches.log")) :line (json/generate-string {:query (str query)})
          :ask {:query (str query) :n n}}]))

    "mail-demo"
    (let [{:keys [to subject body]} input]
      (if (str/blank? (str to))
        [(envelope "A message needs a `to`." :error true)]
        [(envelope (str "Sent to " to "."))
         {:append (str (fs/path dir "outbox.log"))
          :line (json/generate-string {:to (str to) :subject (str subject) :body (str body)})}]))

    "verify-demo"
    [(envelope nil :labels ["contaminated"]) {:check (vec (:items input))}]

    ("campaign-draft-demo" "campaign-send-demo" "campaign-preview-demo")
    [(envelope nil) {:campaign tool :input input}]

    [(envelope (str "No stand-in tool " tool ".") :error true)]))

(defn -main [& [tool dir]]
  (let [call (strict/parse (.readAllBytes System/in) {:keywords? true})
        [env effect] (answer tool dir (:input call))
        env (cond
              (:ask effect) (let [_ (spit (:append effect) (str (:line effect) "\n") :append true)
                                  web (fs/path dir "web")
                                  pages (when (fs/directory? web)
                                          (into {} (for [f (fs/list-dir web)
                                                         :let [page (str (fs/file-name f))]
                                                         :when (re-matches page-name page)]
                                                     [page (slurp (str f))])))
                                  {:keys [query n]} (:ask effect)
                                  token (format "%016x" (.nextLong (java.security.SecureRandom.)))]
                              (assoc env :content (render-ask query (ask-pages pages query n) token)))
              (:campaign effect) (campaign! (:campaign effect) dir (:input effect))
              (:append effect) (do (spit (:append effect) (str (:line effect) "\n") :append true) env)
              (:check effect) (let [page-text (fn [page]
                                                (let [f (fs/path dir "web" page)]
                                                  (when (fs/exists? f) (slurp (str f)))))]
                                (assoc env :content (json/generate-string (check-items (:check effect) page-text))))
              (:read effect) (if (fs/exists? (:read effect))
                               (assoc env :content (slurp (:read effect)))
                               (assoc env :content "No such page." :is_error true))
              :else env)]
    (println (json/generate-string env))))
