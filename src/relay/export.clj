(ns relay.export
  "A snapshot of what relay knows, as plain files for a person to read. The
  log stays the only record: nothing reads an export back, and its files
  are written read-only to say so."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [relay.core :as core]
            [relay.config :as config]
            [relay.inbound :as inbound]
            [relay.render :as render]
            [relay.text :as text]))

(defn- write! [root rel content]
  (let [f (fs/path root rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) content)
    (fs/set-posix-file-permissions f "r--r--r--")))

(defn- with-labels
  "A file's text under a first line naming the labels of what it shows,
  with nothing in it that could drive a terminal it is printed on, and,
  from a contaminated source, its URLs broken."
  [labels text]
  (str "labels: " (if (seq labels) (str/join ", " labels) "none") "\n\n"
       (text/visible (if (text/contaminated? labels) (text/defang text) text))))

(defn- page
  "A file of lines (relay.render's): under a first line naming the labels
  of what it shows, each line made visible, so a role's text, behind the
  gutter, cannot pass for the page's own headings."
  [labels lines]
  (str "labels: " (if (seq labels) (str/join ", " labels) "none") "\n\n"
       (str/join "\n" (map text/visible-line lines)) "\n"))

(defn- facts-page [sys]
  (let [state (inbound/current-state sys)
        zone (config/zone (:config sys))
        now (core/facts state {:as-of ((:clock sys))})
        all (core/facts state {:all true})]
    (page (apply core/union (map core/item-labels (concat now all)))
          (concat ["# Facts" "" "What holds now, then everything else recorded, ended or not yet begun." ""
                   "## Holding now" ""]
                  (render/facts state zone now)
                  ["" "## Every fact" ""]
                  (render/facts state zone all)))))

(defn export!
  "Writes the snapshot into `dir`, which must not exist or be empty. Returns
  the number of files written."
  [sys dir]
  (when (and (fs/exists? dir) (seq (fs/list-dir dir)))
    (throw (ex-info (str dir " is not empty; export into a new directory.") {:relay/usage true})))
  (let [state (inbound/current-state sys)
        files (concat
               [["README.md" (str "# relay export\n\nA snapshot of relay's knowledge as of event "
                                  (dec (:next-seq state)) ", recorded " (:last-at state) ".\n\n"
                                  "Nothing reads these files back: changing them changes nothing"
                                  " in relay.\n")]
                ["facts.md" (facts-page sys)]]
               (for [{:keys [path content] :as v} (core/docs state nil)]
                 [(str "docs/" path) (with-labels (core/item-labels v (core/version-labels state v)
                                                                    (core/path-labels state path))
                                       content)])
               (for [id (sort (keys (:roles state)))
                     {:keys [name text] :as n} (core/notes state id)]
                 [(str "roles/" id "/notes/" name ".md") (with-labels (core/item-labels n) (str text "\n"))])
               (for [id (sort (keys (:journals state)))
                     :let [entries (core/journal state id nil)]]
                 [(str "roles/" id "/journal.md")
                  (page (core/journal-labels entries)
                        (concat [(str "# Journal of " id) ""]
                                (mapcat (fn [{:keys [at text labels]}]
                                          (concat [(str "## " at)] (text/block labels text) [""]))
                                        entries)))]))]
    (fs/create-dirs dir)
    (doseq [[rel content] files] (write! dir rel content))
    (count files)))
