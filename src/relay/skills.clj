(ns relay.skills
  "A role's skills: docs at skills/<role-id>/<slug>.md. The first line is
  `# Title`, and a line starting `Use when:` says when to reach for it.
  Pure."
  (:require [clojure.string :as str]))

(defn path
  "Where a role keeps the skill called `slug`."
  [role-id slug]
  (str "skills/" role-id "/" slug ".md"))

(defn skill-path?
  "Whether a doc path is under skills/."
  [p]
  (boolean (some-> p str (str/starts-with? "skills/"))))

(defn owner
  "The role a skills path belongs to, nil when the path is not under
  skills/ or names no role."
  [p]
  (when (skill-path? p)
    (let [[_ role rest*] (str/split p #"/" 3)]
      (when (and (not (str/blank? role)) rest*) role))))

(defn writable-by?
  "Whether a role may write a doc at `p`: any path outside skills/, and
  under skills/ only its own."
  [role-id p]
  (or (not (skill-path? p))
      (= role-id (owner p))))

(defn parse
  "A skill's title and use-when line from its content. The title is the
  first line when it starts with `# `; use-when is the text after the
  first line starting `Use when:`. Either is nil when missing."
  [content]
  (let [lines (str/split-lines (str content))
        title (when-let [l (first lines)]
                (when (str/starts-with? l "# ") (str/trim (subs l 2))))
        use (some #(when (str/starts-with? % "Use when:") (str/trim (subs % 9))) lines)]
    {:title (not-empty title) :use-when (not-empty use)}))
