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
  - Nothing is moved into a shell mount marked trusted, or made to hold
    one, from outside every trusted mount (trust-problem), whatever the
    session's labels: the shell reads such a folder as the operator's
    own, so a stranger's file filed there would read as clean. Targets
    are compared as the disk does (case, accents, firmlinks, links and
    file identity); a source counts as inside only by its exact real
    path. A moved folder is judged by where it came from.
  - The folders a target needs are made, and recorded, so undo removes
    only the folders it made, and only when they are empty again.

  Every call is two events in the log: files/moving before anything moves,
  with what the call asked for, and files/moved after, with what it did and
  a fingerprint of each thing moved (sizes and SHA-256 of the content).
  Both carry the session's labels and private. files/moved also names the
  folders the call left empty, which relay never removes. `relay undo
  <call> [<file>...]` puts back each move on its own: one whose moved
  thing is still where the call left it, unchanged, with nothing in its
  old place, goes back; any other stays where it is and is named with
  why, relative to the granted folder (I7). The role that made the call
  is told by memo, and files_moves shows it its record.

  Within one call no move may touch a path another move touches (the same
  path, or one inside the other), so the moves of a call do not depend on
  each other's order, and undo can check each against the disk alone."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [relay.move-view :as move-view]
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

(defn- fold
  "A path as a case-insensitive disk compares it: composed, lower case."
  [p]
  (str/lower-case (java.text.Normalizer/normalize (shell/unfirm p) java.text.Normalizer$Form/NFC)))

(defn trust-problem
  "Why a move from `from` to `to`, both real paths, may not be made because
  it would bring something from outside into a folder the operator marked
  trusted (relay.shell), or nil. What is in such a folder is the
  operator's own to the shell, so its results are clean; a session must
  not be able to file a stranger's text there, whatever it holds.

  `trusted` are the trusted mounts, each {:path as written, :inside its
  real path or nil when it does not exist, :forms every way of writing
  it}. A move comes from a trusted folder only when `from`, firmlinks
  aside, is exactly inside one's real path. It goes into one when `to`
  is in or holds any form of it, compared as a case-insensitive disk
  does, or when the edge found the target's folder is the same file
  (`same-file`, the :path of each mount so found). A moved folder is
  judged by where it came from."
  [from to same-file trusted]
  (let [from (shell/unfirm from)
        inside? (some #(and (:inside %) (under? from (shell/unfirm (:inside %)))) trusted)
        hit (first (filter (fn [{:keys [path forms]}]
                             (or (contains? same-file path)
                                 (some #(touches? (fold to) (fold %)) forms)))
                           trusted))]
    (when (and hit (not inside?))
      (str "it would bring a file from outside into " (:path hit) ", which holds only the operator's own"
           " files. If they want it there, they can move it there themselves"))))

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
  "Why one done step cannot be put back, with what to do about it, or nil.
  `w` is what the edge found now: at the step's target :to-kind,
  :to-fingerprint and :to-parent-real; at its source :from-exists,
  :from-parent-kind and :from-parent-real. `show` is how a path reads to
  whoever is told (relay.move-view/shower); the full path when not given.
  `again` is the command that tries again, such as \"relay undo 10\"."
  ([step fingerprint w] (undo-problem step fingerprint w str "relay undo"))
  ([{:keys [from to]} fingerprint {:keys [to-kind to-fingerprint to-parent-real from-exists
                                          from-parent-kind from-parent-real]} show again]
   (let [came (str "It came from " (show from))]
     (cond
       (nil? to-kind) (str "Nothing is at " (show to) " any more. " came
                           "; once it is back there, relay counts it as put back.")
       (not= to-parent-real (parent-of to)) (str (show (parent-of to)) " is not the folder it was, so relay"
                                                 " leaves " (show to) " alone. " came "; check it by hand.")
       (not= fingerprint to-fingerprint) (str (show to) " has changed since it was moved there. " came
                                              "; move it back there yourself to keep your edits.")
       from-exists (str "Something new is at " (show from) ", where " (show to) " came from. Move the new one"
                        " away and " again " puts " (show to) " back.")
       (not= "dir" from-parent-kind) (str "The folder " (show (parent-of from)) " is gone, so " (show to)
                                          " cannot go back to " (show from) ". Make the folder again and "
                                          again " puts it back.")
       (not= from-parent-real (parent-of from)) (str (show (parent-of from)) " is not the folder it was, so relay"
                                                     " will not put " (show to) " back in it. Check it by hand.")))))

(defn back-by-hand?
  "Whether a step undo-problem stops is back where it came from all the
  same: nothing is at its target, the same kind of thing is at its
  source in the folder it was in, and no move relay has not undone put
  anything there (`pending`, the targets of those moves). The operator
  moved it back, perhaps after editing it, so undo counts it as put back
  and moves nothing."
  [{:keys [from to]} fingerprint {:keys [to-kind from-kind from-parent-kind from-parent-real]} pending]
  (and (nil? to-kind)
       (= (:kind fingerprint) from-kind)
       (= "dir" from-parent-kind)
       (= from-parent-real (parent-of from))
       (not (some #(or (under? % from) (under? from %)) (remove #{to} pending)))))

(defn removable-dirs
  "The folders a call made, deepest first, for undo to remove when empty."
  [created]
  (vec (sort-by (juxt #(- (count (str/split % #"/"))) identity) (distinct created))))

;; --- pure: what the steward and the operator are told

(defn tool-description [roots & [trusted]]
  (str "Move or rename files and folders inside the folders you may organize: " (str/join ", " roots) ". "
       (when (seq trusted)
         (str "The operator keeps only their own files in " (str/join ", " trusted) ": nothing from"
              " outside those folders may be moved into them. "))
       "Give each move's full path, from and to. relay checks every move before it makes any, and makes"
       " none if one is refused: nothing is overwritten or deleted, a move whose target exists is"
       " refused, and the folders a target needs are made. Within one call no two moves may touch the"
       " same path or one inside another; rename a folder and fill it in separate calls. Plan with sh"
       " first, where these folders show when they are mounted. relay never removes a folder a move"
       " leaves empty; it says which. The operator can undo any move, and files_moves shows your record"
       " of them, with what was undone."))

(defn move-line [{:keys [from to]}] (str "  " from " → " to))

(defn moved-text
  "What the steward is told after a call: what moved, and the folders it
  left empty, which relay does not remove."
  ([call moves done stopped] (moved-text call moves done stopped nil))
  ([call moves done stopped emptied]
   (str/join "\n"
             (concat [(str (if stopped "Moved " "Moved all ") (count done) " of " (count moves)
                           (when stopped (str ", then stopped: " stopped))
                           ". This is move " call "; the operator can undo it with relay undo " call ".")]
                     (map #(move-line (moves (:index %))) done)
                     (when (seq emptied)
                       [(str "These folders are empty now, and relay leaves them: " (str/join ", " emptied) ".")])))))

(defn calls-lines
  "relay moves: each call, newest last, with what it moved and what is undone."
  [calls zone]
  (if (empty? calls)
    ["No files have been moved."]
    (mapcat (fn [{:keys [id actor at moves done undone status stopped roots emptied]}]
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
                          ;; One line per file, relative to the granted folder.
                          (map #(str "  " %) (move-view/file-lines roots (for [{:keys [index]} done] [index (moves index)])
                                                                   undone emptied))))))
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

(defn emptied
  "The folders the `froms` of a call were in that are empty now, by real
  path: relay never removes them, so whoever moved them is told. A
  granted folder itself is never counted: it stays whatever it holds."
  [froms roots]
  (vec (sort (distinct (for [f froms
                             :let [d (parent-of f)]
                             :when (and (not (some #{d} roots))
                                        (= "dir" (kind d)) (= d (real d))
                                        (empty? (try (fs/list-dir d) (catch java.io.IOException _ [::unknown]))))]
                         d)))))

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

(defn trusted-places
  "The trusted :mounts of relay.edn's :house, for trust-problem: each with
  its real path when it exists, and every form of it."
  [house]
  (vec (for [m (:mounts house)
             :when (shell/mount-trusted? m)
             :let [p (shell/mount-path m)
                   r (real p)]]
         {:path p :inside r
          :forms (vec (distinct (remove nil? [p (shell/unfirm p) r (some-> r shell/unfirm)])))})))

(defn- same-file-mounts
  "The trusted mounts that the real folder `base`, or one above it, is the
  same file as, by :path: a name a path check would not see."
  [base trusted]
  (if-not base
    #{}
    (set (for [{:keys [path inside]} trusted
               :when (and inside (some #(shell/same-file? % inside) (shell/ancestors-or-self base)))]
           path))))

(defn plan
  "Each move's real paths and what a target needs, or throws a refusal
  listing every move that may not be made. `trusted` (trusted-places):
  none may bring something from outside into a trusted folder."
  [moves roots & [trusted]]
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
                                        (some->> (target-problem t (:real s) roots) (str to ": "))
                                        (some->> (trust-problem (:real s) (:real t)
                                                                (same-file-mounts (:base t) trusted) trusted)
                                                 (str to ": ")))
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
   :from-kind (kind from)
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
