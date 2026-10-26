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

(defn shower
  "How one path reads to the operator, as a function: relative to the
  deepest of `roots` (or, with none, the folder every one of `paths`
  shares) that holds it, under that folder's short name when the call
  spans several. A path that is a granted folder reads as its short name;
  one under none of them reads in full."
  [roots paths]
  (let [roots (or (seq roots) (let [c (common-folder paths)] (when (seq c) [c])))
        names (vec (distinct (keep #(when-let [r (folder-of roots %)] r) paths)))
        many? (> (count names) 1)]
    (fn [p]
      (let [p (str p)]
        (if-let [r (first (filter #(= % p) roots))]
          (file-name r)
          (if-let [r (folder-of roots p)]
            (str (when many? (str (file-name r) ": ")) (subs p (inc (count r))))
            p))))))

(defn still-empty
  "The folders call `c` left empty (its :emptied) that nothing has gone
  back into since, as far as the log knows: no undo of `c` or of a later
  call put a file back in one, and no later move that is not undone put
  one there. `calls` are every move call, `c` among them. Pure."
  [calls c]
  (let [later (filter #(>= (:id %) (:id c)) calls)
        undone-of (fn [k] (set (:undone k)))
        filled (concat
                ;; put back where they came from, by an undo
                (for [k later i (undone-of k)] (:from ((:moves k) i)))
                ;; moved in by a later call that is not undone
                (for [k later :when (> (:id k) (:id c))
                      {:keys [index]} (:done k) :when (not ((undone-of k) index))]
                  (:to ((:moves k) index))))]
    (vec (remove (fn [d] (some #(under? d %) filled)) (:emptied c)))))

(defn view
  "How a files_move call reads to the operator, given its `roots` (the
  folders it was granted, or nil to use the folder every path shares):
  {:title :lines}, the lines being a summary, then one line per file,
  \"from → to\" relative to the granted folder, then a line naming the
  folders the call left empty (`emptied`, those still empty: still-empty),
  while any of it is not undone. When some of it is undone, the summary
  says how many are still moved, and those come first. When the files
  are in several granted folders, each line starts with its folder's
  short name. `shown` is [[index move]]; `undone` the set of indexes put
  back."
  ([roots shown undone] (view roots shown undone nil))
  ([roots shown undone emptied]
   (let [paths (mapcat (fn [[_ m]] [(:from m) (:to m)]) shown)
         roots (or (seq roots) (let [c (common-folder paths)] (when (seq c) [c])))
         rel (fn [p] (if-let [r (folder-of roots p)] [(file-name r) (subs p (inc (count r)))] [nil p]))
         names (vec (distinct (keep (fn [[_ m]] (first (rel (:from m)))) shown)))
         many? (> (count names) 1)
         show (shower roots paths)
         n (count shown)
         back (count (filter (fn [[i _]] (undone i)) shown))
         dirs (count (distinct (map (fn [[_ m]] (parent-path (second (rel (:to m))))) shown)))
         files (str n (if (= 1 n) " file" " files"))
         in (when (seq names) (str " in " (str/join ", " names)))
         partly? (< 0 back n)
         all-undone? (and (seq shown) (= back n))]
     {:title (cond (= 1 n) (file-name (:from (second (first shown))))
                   partly? (str files ", " back " put back")
                   :else files)
      :lines (cond-> (into [(if partly?
                              (str (- n back) " of " n " still moved (" back " put back)" in)
                              (str files in (when (and (> n 1) (> dirs 1)) (str ", into " dirs " folders"))))]
                           ;; What is still moved first, then what went back.
                           (for [[i m] (sort-by (fn [[i _]] (boolean (undone i))) shown)
                                 :let [[rn from] (rel (:from m)) [_ to] (rel (:to m))]]
                             (str (when (and many? rn) (str rn ": ")) from " → " to (when (undone i) " (undone)"))))
               (and (seq emptied) (not all-undone?))
               (conj (str "Left empty: " (str/join ", " (map show emptied)))))})))

(defn file-lines
  "The lines of `view` that name files and folders, without its summary."
  [roots shown undone emptied]
  (vec (rest (:lines (view roots shown undone emptied)))))
