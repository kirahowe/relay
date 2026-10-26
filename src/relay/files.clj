(ns relay.files
  "Organizing the operator's files: the files_move tool and its undo.

  The operator grants a role folders to organize, in relay.edn:

    :grants {\"steward\" {:files [\"/Users/you/Downloads\" \"/Users/you/Documents/Taxes\"]}}

  These writable folders are separate from the shell's mounts, which stay
  read-only; a folder may be both. The steward plans with its shell, and
  relay's own code, on the host, carries out the moves. The model never
  touches the disk, and neither does the container.

  This namespace decides, and reads and changes the disk one step at a
  time; relay.moves runs a call under the files lock and records it.

  The walls, all kept here rather than asked of the model:

  - A writable folder is never, never holds and never sits in a secret,
    the home directory, relay's data directory or the data volume: the
    shell's place-problem on the path as written, and on its real path
    with identity-problem when a call runs.
  - Every path is taken as its real path, with symbolic links resolved,
    and must lie inside a writable folder. A symbolic link is never
    moved, nor a folder holding one, so nothing a move makes can point out.
  - A move never overwrites: one whose target exists is refused. It never
    deletes, and never copies: a move to another disk is refused rather
    than copied and removed.
  - A writable folder itself is never moved, nor a folder holding one.
  - The folders a target needs are made, and recorded, so undo removes
    only the folders it made, and only when they are empty again.

  Every call is two events in the log: files/moving before anything moves,
  with what the call asked for, and files/moved after, with what it did and
  a fingerprint of each thing moved (sizes and SHA-256 of the content).
  Both carry the session's labels and private. `relay undo <call>` puts
  everything back only when every moved thing is still where the call left
  it, unchanged, and nothing has taken its old place; otherwise it says
  what changed and touches nothing (I7).

  Within one call no move may touch a path another move touches (the same
  path, or one inside the other), so the moves of a call do not depend on
  each other's order, and undo can check each against the disk alone."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [relay.shell :as shell])
  (:import (java.io FileInputStream)
           (java.nio.file CopyOption FileAlreadyExistsException Files
                          LinkOption Path StandardCopyOption)
           (java.nio.file.attribute BasicFileAttributes)
           (java.security MessageDigest)))

;; --- pure: paths

(def max-entries
  "The most files and folders a moved folder may hold."
  10000)

(defn path-problem
  "Why `p` is not a full, plainly written path, or nil."
  [p]
  (cond
    (not (and (string? p) (not (str/blank? p)))) "it is not a path"
    (re-find #"[\u0000-\u001f\u007f]" p) "it has a control character"
    (not (str/starts-with? p "/")) "it is not a full path from /"
    (= "/" p) "it is the whole disk"
    (or (str/ends-with? p "/") (str/includes? p "//")) "it is not written plainly (a trailing or double slash)"
    (some #{"." ".."} (str/split p #"/")) "it has . or .. in it"
    (> (count p) 4096) "it is too long"))

(defn under?
  "Whether path a is b or inside it."
  [a b]
  (or (= a b) (str/starts-with? a (str b "/"))))

(defn root-problem
  "Why `root` may not be a writable folder, or nil."
  [root where]
  (or (path-problem root) (shell/place-problem root where)))

(defn grant-problem
  "What is wrong with a role's :files grant as written, or nil."
  [role roots]
  (cond
    (not (and (vector? roots) (seq roots)))
    (str "Role " role " has :files " (pr-str roots) " in relay.edn; it is a vector of the folders it may"
         " organize, such as [\"/Users/you/Downloads\"].")
    (some path-problem roots)
    (let [r (first (filter path-problem roots))]
      (str "Role " role " cannot organize " (pr-str r) ": " (path-problem r) "."))
    (not (apply distinct? roots))
    (str "Role " role " names a folder twice in :files.")))

(defn roots-problem
  "The first writable folder in relay.edn that may not be one, as a
  sentence, or nil. `where` has the real :home and :data-dir."
  [config where]
  (first (for [[role g] (sort-by key (:grants config))
               r (:files g)
               :let [why (root-problem r where)]
               :when why]
           (str "Role " role " cannot organize " r ": " why "."))))

(defn- parent-of [p]
  (let [i (str/last-index-of p "/")] (if (pos? i) (subs p 0 i) "/")))

(defn- name-of [p] (subs p (inc (str/last-index-of p "/"))))

(defn- touches? [a b] (or (under? a b) (under? b a)))

(defn source-problem
  "Why the thing at a source may not be moved, or nil. `s` is what the
  edge found: :real, the path with links resolved (nil when it does not
  exist), :kind (\"file\", \"dir\", \"link\" or \"other\"), and for a
  folder :links (whether it holds a link or anything not a file or folder),
  :entries (how many things it holds) and :unreadable (whether relay could
  not read all of it)."
  [{:keys [real kind links entries unreadable]} roots]
  (cond
    (nil? real) "it does not exist"
    (= "link" kind) "it is a symbolic link, and relay moves only files and folders"
    (= "other" kind) "it is not a file or a folder"
    (some #(= real %) roots) "it is one of the folders you may organize, which stay where they are"
    (not (some #(under? real %) roots)) "it is not inside a folder you may organize"
    (some #(under? % real) roots) "it holds one of the folders you may organize"
    (and (= "file" kind) unreadable) "relay cannot read it"
    (and (= "dir" kind) unreadable) "it holds something relay cannot read"
    (and (= "dir" kind) links) "it holds a symbolic link or something that is not a file or folder"
    (and (= "dir" kind) (> entries max-entries)) (str "it holds more than " max-entries " things")))

(defn target-problem
  "Why a move may not go to a target, or nil. `t` is what the edge found:
  :exists (anything at the path, a link included), :base (the real path of
  the nearest folder above it that exists, or nil), :base-kind, :real (the
  target as it would be), and :same-disk (whether :base is on the
  source's disk)."
  [{:keys [exists same-file base base-kind real same-disk]} source-real roots]
  (cond
    same-file (str "it is the same file under another name, as when only upper and lower case differ;"
                   " rename it in two moves, through another name")
    exists "something is already there, and relay never overwrites"
    (nil? base) "no folder above it can be found"
    (not= "dir" base-kind) (str base " is a file, not a folder")
    (not (some #(and (under? real %) (not= real %)) roots)) "it is not inside a folder you may organize"
    (= real source-real) "it is where the file already is"
    (under? real source-real) "a folder cannot go inside itself"
    (not same-disk) "it is on another disk, and relay moves only within one disk"))

(defn batch-problems
  "Moves of one call that touch the same place: each as [i j], 0-based.
  `resolved` are {:from :to} real paths."
  [resolved]
  (let [v (vec resolved)]
    (for [i (range (count v)) j (range (inc i) (count v))
          :let [a (v i) b (v j)]
          :when (some (fn [[x y]] (touches? x y))
                      (for [x [(:from a) (:to a)] y [(:from b) (:to b)]] [x y]))]
      [i j])))

(defn creates
  "The folders a target needs that do not exist yet, outermost first:
  every folder between `base` and the target `real`."
  [base real]
  (let [b (if (= "/" base) "" base)
        parts (butlast (str/split (subs real (inc (count b))) #"/"))]
    (vec (rest (reductions (fn [acc part] (str acc "/" part)) b parts)))))

(defn undo-problem
  "Why one done step cannot be put back, or nil. `w` is what the edge
  found now: at the step's target :to-kind, :to-fingerprint and
  :to-parent-real; at its source :from-exists, :from-parent-kind and
  :from-parent-real."
  [{:keys [from to]} fingerprint {:keys [to-kind to-fingerprint to-parent-real from-exists
                                         from-parent-kind from-parent-real]}]
  (cond
    (nil? to-kind) (str "Nothing is at " to " any more.")
    (not= to-parent-real (parent-of to)) (str (parent-of to) " is not the folder it was.")
    (not= fingerprint to-fingerprint) (str to " has changed since it was moved there.")
    from-exists (str "Something new is at " from ".")
    (not= "dir" from-parent-kind) (str "The folder " (parent-of from) " is gone.")
    (not= from-parent-real (parent-of from)) (str (parent-of from) " is not the folder it was.")))

(defn removable-dirs
  "The folders a call made, deepest first, for undo to remove when empty."
  [created]
  (vec (sort-by (juxt #(- (count (str/split % #"/"))) identity) (distinct created))))

;; --- pure: what the steward and the operator are told

(defn tool-description [roots]
  (str "Move or rename files and folders inside the folders you may organize: " (str/join ", " roots) ". "
       "Give each move's full path, from and to. relay checks every move before it makes any, and makes"
       " none if one is refused: nothing is overwritten or deleted, a move whose target exists is"
       " refused, and the folders a target needs are made. Within one call no two moves may touch the"
       " same path or one inside another; rename a folder and fill it in separate calls. Plan with sh"
       " first, where these folders show when they are mounted. The operator can undo a whole call."))

(defn move-line [{:keys [from to]}] (str "  " from " → " to))

(defn moved-text
  "What the steward is told after a call."
  [call moves done stopped]
  (str/join "\n"
            (concat [(str (if stopped "Moved " "Moved all ") (count done) " of " (count moves)
                          (when stopped (str ", then stopped: " stopped))
                          ". This is move " call "; the operator can undo it with relay undo " call ".")]
                    (map #(move-line (moves (:index %))) done))))

(defn calls-lines
  "relay moves: each call, newest last, with what it moved and what is undone."
  [calls zone]
  (if (empty? calls)
    ["No files have been moved."]
    (mapcat (fn [{:keys [id actor at moves done undone status stopped]}]
              (let [undone (set undone)
                    stamp (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")
                                  (.atZone ^java.time.Instant at zone))]
                (concat [(str "Move " id ", " stamp ", by " actor ": "
                              (case status
                                :moving "relay stopped before it could record what moved; check by hand"
                                :moved (str (count done) " of " (count moves) " moved"
                                            (cond (and (seq done) (= (count undone) (count done))) ", all undone"
                                                  (seq undone) (str ", " (count undone) " undone")
                                                  stopped (str ", stopped: " stopped))))
                              ".")]
                        (if (= :moving status)
                          (map move-line moves)
                          (for [{:keys [index]} done]
                            (str (move-line (moves index)) (when (undone index) " (undone)")))))))
            calls)))

;; --- the edges: what is on the disk

(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- path ^Path [p] (fs/path p))

(defn kind
  "What is at `p`, without following a link: \"file\", \"dir\", \"link\",
  \"other\", or nil when nothing is."
  [p]
  (try (let [a (Files/readAttributes (path p) BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" no-follow)]
         (cond (.isSymbolicLink a) "link" (.isDirectory a) "dir" (.isRegularFile a) "file" :else "other"))
       (catch java.io.IOException _ nil)))

(defn- real [p] (try (str (fs/real-path p)) (catch java.io.IOException _ nil)))

(defn- sha256-file [p]
  (let [d (MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (FileInputStream. (str p))]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update d buf 0 n) (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(defn- readable? [p] (Files/isReadable (path p)))

(defn- tree
  "A folder's contents, without following links: [{:path :kind}] by path
  inside it, whether anything there is not a file or folder, and whether
  anything there could not be read. A folder that cannot be listed counts
  as unreadable rather than throwing, so no error carries its path."
  [dir]
  (loop [todo [dir] out [] links false unreadable false]
    (if (or (empty? todo) (> (count out) max-entries))
      [out links unreadable]
      (let [d (peek todo)
            kids (try (sort (map str (fs/list-dir d))) (catch java.io.IOException _ nil))
            entries (map (fn [k] {:abs k :path (subs k (inc (count dir))) :kind (kind k)}) kids)]
        (recur (into (pop todo) (map :abs (filter #(= "dir" (:kind %)) entries)))
               (into out entries)
               (or links (boolean (some #(not (#{"file" "dir"} (:kind %))) entries)))
               (or unreadable (nil? kids)
                   (boolean (some #(and (= "file" (:kind %)) (not (readable? (:abs %)))) entries))))))))

(defn fingerprint
  "What a file or folder is, to tell later whether it changed: for a file
  its size and the SHA-256 of its content; for a folder every file and
  folder in it, by path, the same way."
  [p]
  (case (kind p)
    "file" {:kind "file" :size (Files/size (path p)) :sha256 (sha256-file p)}
    "dir" (let [[entries _] (tree p)]
            {:kind "dir"
             :entries (vec (for [{:keys [abs kind] rel :path} (sort-by :path entries)]
                             (if (= "file" kind)
                               {:path rel :kind "file" :size (Files/size (path abs)) :sha256 (sha256-file abs)}
                               {:path rel :kind (or kind "gone")})))})
    nil))

(defn- store [p] (try (Files/getFileStore (path p)) (catch java.io.IOException _ nil)))

(defn- probe-source
  "What source-problem needs about `p`. A folder is walked only when its
  real path is strictly inside one of `roots` and holds none of them, so
  relay never looks through a folder it may not organize."
  [p roots]
  (let [k (kind p)
        r (when k (if (= "link" k)
                    (some-> (real (parent-of p)) (str "/" (name-of p)))
                    (real p)))
        walk? (and r (some #(and (under? r %) (not= r %)) roots) (not (some #(under? % r) roots)))]
    (cond-> {:real r :kind k}
      (and (= "file" k) walk?) (assoc :unreadable (not (readable? r)))
      (and (= "dir" k) walk?) (merge (let [[entries links unreadable] (tree r)]
                                       {:entries (count entries) :links links :unreadable unreadable})))))

(defn- probe-target [p source-real]
  (let [exists (some? (kind p))
        base (loop [a (parent-of p)] (cond (kind a) a (= "/" a) nil :else (recur (parent-of a))))
        base-real (when base (real base))]
    {:exists exists
     :same-file (and exists source-real (= source-real (real p)))
     :base base-real
     :base-kind (when base-real (kind base-real))
     :real (when base-real (str (if (= "/" base-real) "" base-real) (subs p (if (= "/" base) 0 (count base)))))
     :same-disk (and base-real source-real (= (store base-real) (store (parent-of source-real))))}))

(defn- refuse!
  "A refusal for the steward: its text names the operator's paths."
  [msg]
  (throw (ex-info msg {:relay/rejected :invalid :relay/labels ["private"]})))

(defn move! [from to]
  (when (kind to) (throw (FileAlreadyExistsException. (str to))))
  (Files/move (path from) (path to) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE])))

(defn why-failed [^Exception e]
  (cond (instance? FileAlreadyExistsException e) "something appeared at the target"
        (str/includes? (.getName (class e)) "AtomicMoveNotSupported") "the target is on another disk"
        :else (or (ex-message e) (str (class e)))))

(defn remove-empty!
  "Removes each folder that is still an empty folder at its own real path.
  Returns [removed kept]."
  [dirs]
  (reduce (fn [[removed kept] d]
            (if (and (= "dir" (kind d)) (= d (real d)) (empty? (fs/list-dir d))
                     (try (Files/delete (path d)) true (catch java.io.IOException _ false)))
              [(conj removed d) kept]
              [removed (cond-> kept (kind d) (conj d))]))
          [[] []] (removable-dirs dirs)))

(defn make-dirs!
  "Makes the folders a target needs, adding each one made to the atom
  `made`. One that exists already, made by an earlier move of the call,
  is used as it is."
  [made dirs]
  (doseq [d dirs]
    (try (Files/createDirectory (path d) (make-array java.nio.file.attribute.FileAttribute 0))
         (swap! made conj d)
         (catch FileAlreadyExistsException e
           (when-not (and (= "dir" (kind d)) (= d (real d))) (throw e))))))

(defn plan
  "Each move's real paths and what a target needs, or throws a refusal
  listing every move that may not be made."
  [moves roots]
  (let [shape (for [[i {:keys [from to]}] (map-indexed vector moves)
                    :let [why (or (some->> (path-problem from) (str "from: "))
                                  (some->> (path-problem to) (str "to: ")))]
                    :when why]
                (str "Move " (inc i) ": " why "."))
        _ (when (seq shape) (refuse! (str/join "\n" shape)))
        resolved (vec (for [{:keys [from to]} moves]
                        (let [[s t] (try (let [s (probe-source from roots)] [s (probe-target to (:real s))])
                                         (catch java.io.IOException _
                                           (refuse! (str "Nothing was moved.\n" from ": relay could not look at it."))))]
                          {:from (:real s) :to (:real t) :source s :target t
                           :problem (or (some->> (source-problem s roots) (str from ": "))
                                        (some->> (target-problem t (:real s) roots) (str to ": ")))
                           :create (when (and (:base t) (:real t)) (creates (:base t) (:real t)))})))
        problems (or (seq (keep :problem resolved))
                     (for [[i j] (batch-problems resolved)]
                       (str "Moves " (inc i) " and " (inc j) " touch the same place; make them in separate calls.")))]
    (when (seq problems)
      (refuse! (str "Nothing was moved.\n" (str/join "\n" problems))))
    resolved))

(defn world-now
  "What undo-problem needs to know about one step, from the disk now."
  [{:keys [from to]}]
  {:to-kind (kind to)
   :to-fingerprint (fingerprint to)
   :to-parent-real (real (parent-of to))
   :from-exists (some? (kind from))
   :from-parent-kind (kind (parent-of from))
   :from-parent-real (real (parent-of from))})

(defn real-roots
  "The real paths of writable folders, each checked on the disk again:
  it must exist, and its real path pass place-problem and identity-problem,
  so neither a link nor another name for a folder can open a secret."
  [roots data-dir]
  (let [where {:home (str (fs/real-path (System/getProperty "user.home")))
               :data-dir (str (fs/real-path data-dir))}]
    (vec (for [r roots]
           (let [rr (real r)]
             (when-not (and rr (= "dir" (kind rr)))
               (refuse! (str "The folder " r ", which you may organize, does not exist.")))
             (when-let [why (or (shell/place-problem rr where) (shell/identity-problem rr where))]
               (refuse! (str "relay will not organize " r " (" rr "): " why ".")))
             rr)))))
