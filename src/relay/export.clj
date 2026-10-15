(ns relay.export
  "A snapshot of what relay knows, as plain files for a person to read. The
  log stays the only record: nothing reads an export back, and its files
  are written read-only to say so."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [relay.core :as core]
            [relay.inbound :as inbound]
            [relay.tools :as tools]))

(defn- write! [root rel content]
  (let [f (fs/path root rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) content)
    (fs/set-posix-file-permissions f "r--r--r--")))

(defn- tool-result [sys tool args]
  (let [{:keys [ok error labels]} (tools/run-tool sys core/operator (get tools/builtin tool)
                                                  (assoc args :accept_contamination true) [])]
    (when error (throw (ex-info error {})))
    [ok labels]))

(defn- with-labels
  "A file's text under a first line naming the labels of what it shows."
  [labels text]
  (str "labels: " (if (seq labels) (str/join ", " labels) "none") "\n\n" text))

(defn- facts-page [sys]
  (let [[now now-labels] (tool-result sys "facts" {})
        [all all-labels] (tool-result sys "facts" {:all true})]
    (with-labels (core/union now-labels all-labels)
      (str "# Facts\n\nWhat holds now, then everything else recorded, ended or not yet begun.\n\n"
           "## Holding now\n\n" now "\n\n"
           "## Every fact\n\n" all "\n"))))

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
                 [(str "docs/" path) (with-labels (core/content-labels (core/version-labels state v)
                                                                       (core/path-labels state path))
                                       content)])
               (for [id (sort (keys (:roles state)))
                     {:keys [name text labels]} (core/notes state id)]
                 [(str "roles/" id "/notes/" name ".md") (with-labels (core/content-labels labels) (str text "\n"))])
               (for [id (sort (keys (:journals state)))
                     :let [entries (core/journal state id nil)]]
                 [(str "roles/" id "/journal.md")
                  (with-labels (core/journal-labels entries)
                    (str "# Journal of " id "\n\n"
                         (str/join "\n\n" (for [{:keys [at text]} entries]
                                            (str "## " at "\n\n" text)))
                         "\n"))]))]
    (fs/create-dirs dir)
    (doseq [[rel content] files] (write! dir rel content))
    (count files)))
