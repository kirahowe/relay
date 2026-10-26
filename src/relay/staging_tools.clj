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
    campaign-draft-demo <data>
                       edit: saves a draft campaign (subject, body, group)
                       in <data>/campaigns.edn, or a new version of one
                       given its id. Every version is kept, so a change
                       can be undone. Nothing is ever sent: the operator
                       sends a campaign herself, in the service

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

(defn page-of
  "The page in <data>/web a URL stands for: the last part of its path,
  when that is a page name. Nil otherwise."
  [url]
  (let [path (second (re-find #"(?i)^https?://[^/?#]+(/[^?#]*)?" (str url)))
        last-part (last (str/split (str path) #"/"))]
    (when (and last-part (re-matches page-name last-part)) last-part)))

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

;; --- the campaign stand-in

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

(defn- read-edn [file]
  (when (fs/exists? file) (edn/read-string (slurp (str file)))))

(defn- campaign! [dir input]
  (let [file (str (fs/path dir "campaigns.edn"))
        [saved env] (save-draft (read-edn file) input)]
    (when saved (spit file (pr-str saved)))
    env))

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

    "campaign-draft-demo"
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
              (:campaign effect) (campaign! dir (:input effect))
              (:append effect) (do (spit (:append effect) (str (:line effect) "\n") :append true) env)
              (:read effect) (if (fs/exists? (:read effect))
                               (assoc env :content (slurp (:read effect)))
                               (assoc env :content "No such page." :is_error true))
              :else env)]
    (println (json/generate-string env))))
