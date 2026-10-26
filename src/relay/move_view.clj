(ns relay.move-view
  "How a files_move call reads to the operator. Pure.

  A call holds full paths. She is shown them relative to the folder she
  granted, under that folder's short name, one line per file."
  (:require [clojure.string :as str]))

(defn- file-name [path] (last (str/split (str path) #"/")))

(defn- parent-path [p]
  (let [i (str/last-index-of (str p) "/")]
    (if (and i (pos? i)) (subs p 0 i) "")))

(defn- under? [root p] (str/starts-with? (str p) (str root "/")))

(defn common-folder
  "The deepest folder every path is under, or \"\" when they share none.
  Stands in for the granted folder on a call recorded without it."
  [paths]
  (let [parts (map #(str/split (parent-path %) #"/") paths)]
    (if (empty? parts)
      ""
      (str/join "/" (reduce (fn [a b] (map first (take-while (fn [[x y]] (= x y)) (map vector a b))))
                            parts)))))

(defn- folder-of
  "The deepest of `roots` that holds `path`, or nil."
  [roots path]
  (first (sort-by (comp - count) (filter #(under? % path) roots))))

(defn view
  "How a files_move call reads to the operator, given its `roots` (the
  folders it was granted, or nil to use the folder every path shares):
  {:title :lines}, the lines being a summary, then one line per file,
  \"from → to\" relative to the granted folder. When the files are in
  several granted folders, each line starts with its folder's short
  name. `shown` is
  [[index move]]; `undone` the set of indexes put back."
  [roots shown undone]
  (let [paths (mapcat (fn [[_ m]] [(:from m) (:to m)]) shown)
        roots (or (seq roots) (let [c (common-folder paths)] (when (seq c) [c])))
        rel (fn [p] (if-let [r (folder-of roots p)] [(file-name r) (subs p (inc (count r)))] [nil p]))
        names (vec (distinct (keep (fn [[_ m]] (first (rel (:from m)))) shown)))
        many? (> (count names) 1)
        n (count shown)
        dirs (count (distinct (map (fn [[_ m]] (parent-path (second (rel (:to m))))) shown)))
        files (str n (if (= 1 n) " file" " files"))]
    {:title (if (= 1 n) (file-name (:from (second (first shown)))) files)
     :lines (into [(str files (when (seq names) (str " in " (str/join ", " names)))
                        (when (and (> n 1) (> dirs 1)) (str ", into " dirs " folders")))]
                  (for [[i m] shown
                        :let [[rn from] (rel (:from m)) [_ to] (rel (:to m))]]
                    (str (when (and many? rn) (str rn ": ")) from " → " to (when (undone i) " (undone)"))))}))
