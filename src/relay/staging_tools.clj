(ns relay.staging-tools
  "The stand-in tools that staging data defines (see relay.staging), for
  eval cases to grant. Each is an envelope tool: the call arrives as JSON on
  stdin and one result envelope goes to stdout. The data directory is the
  first argument after the tool's name, and nothing is read or written
  outside it.

    fail-demo <data>   act: sets a pretend reminder, but refuses one with no
                       HH:MM time in it
    web-demo <data>    read, contaminated: prints <data>/web/<page>, a page
                       copied from test/scenarios/fixtures by staging init
    mail-demo <data>   egress: appends the message to <data>/outbox.log

  Kept apart from relay.staging so that each call starts quickly: this
  namespace needs nothing of relay's."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def fail-message "The reminders service rejects a reminder without a time.")

(def page-name #"[a-z0-9][a-z0-9-]*\.(html|txt|md)")

(defn- envelope [content & {:keys [error labels]}]
  (cond-> {:content content :labels (vec labels)}
    error (assoc :is_error true)))

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

    "mail-demo"
    (let [{:keys [to subject body]} input]
      (if (str/blank? (str to))
        [(envelope "A message needs a `to`." :error true)]
        [(envelope (str "Sent to " to "."))
         {:append (str (fs/path dir "outbox.log"))
          :line (json/generate-string {:to (str to) :subject (str subject) :body (str body)})}]))

    [(envelope (str "No stand-in tool " tool ".") :error true)]))

(defn -main [& [tool dir]]
  (let [call (json/parse-string (slurp *in*) true)
        [env effect] (answer tool dir (:input call))
        env (cond
              (:append effect) (do (spit (:append effect) (str (:line effect) "\n") :append true) env)
              (:read effect) (if (fs/exists? (:read effect))
                               (assoc env :content (slurp (:read effect)))
                               (assoc env :content "No such page." :is_error true))
              :else env)]
    (println (json/generate-string env))))
