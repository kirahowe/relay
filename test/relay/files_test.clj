(ns relay.files-test
  "Organizing files (relay.files, relay.moves): what relay.edn may grant,
  the walls a move keeps (never overwrite, never delete, never leave the
  writable folders, links resolved), the log's record of every call, and
  undo, which is exact or refuses and touches nothing. I7 extended: for
  any generated tree and sequence of calls, undoing them all restores the
  tree byte for byte."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cli :as cli]
            [relay.config :as config]
            [relay.core :as core]
            [relay.files :as files]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.moves :as moves]
            [relay.tools :as tools]))

(def base
  {:timezone "UTC"
   :default-harness :agentloop
   :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}}
   :tools {}
   :grants {"steward" {:files ["/Users/someone/Downloads" "/Users/someone/Documents/Taxes"]}}})

(defn- problem [settings]
  (try (config/resolve-config settings) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

;; --- relay.edn

(deftest the-files-grant-is-checked
  (is (nil? (problem base)))
  (doseq [[roots why] [[[] #"vector of the folders"]
                       ["/a" #"vector of the folders"]
                       [["downloads"] #"not a full path"]
                       [["/"] #"whole disk"]
                       [["/a/"] #"plainly"]
                       [["/a/../b"] #"\.\."]
                       [["/a\nb"] #"control"]
                       [["/a" "/a"] #"twice"]]]
    (is (re-find why (str (problem (assoc-in base [:grants "steward" :files] roots)))) (pr-str roots)))
  (testing "a public role never organizes files: their names are private"
    (is (re-find #"public, so it may not have :files"
                 (str (problem (assoc-in base [:grants "steward" :public] true))))))
  (testing "a script harness gets no tools"
    (is (re-find #"script harness"
                 (str (problem (assoc base :harnesses {:s {:kind :script :command ["true"]}} :default-harness :s)))))))

(def where {:home "/Users/someone" :data-dir "/Users/someone/Sync/relay"})

(deftest a-writable-folder-is-never-a-secret
  (doseq [r ["/Users/someone" "/Users" "/" "/Users/someone/.ssh" "/Users/someone/.ssh/keys"
             "/Users/someone/Library/Keychains" "/Users/someone/.config/relay" "/Users/someone/.codex-x"
             "/Users/someone/Sync" "/Users/someone/Sync/relay" "/Users/someone/Sync/relay/log"
             "/System/Volumes/Data" "/System/Volumes/Data/Users/someone/.ssh" "/System"]]
    (is (files/root-problem r where) r))
  (doseq [r ["/Users/someone/Downloads" "/Users/someone/Documents/Taxes" "/Volumes/Archive/scans"
             "/Users/someone/Sync/other"]]
    (is (nil? (files/root-problem r where)) r)))

(deftest load-config-refuses-a-folder-that-holds-the-data-directory
  (let [dir (str (fs/real-path (fs/create-temp-dir {:prefix "relay-files-cfg"})))
        data (str dir "/data")]
    (try
      (log/init! data)
      (doseq [[r ok?] [[(str dir "/inbox") true] [data false] [(str data "/log") false] [dir false]]]
        (spit (str data "/relay.edn") (pr-str (assoc-in base [:grants "steward" :files] [r])))
        (let [msg (try (config/load-config data) nil (catch clojure.lang.ExceptionInfo e (ex-message e)))]
          (if ok? (is (nil? msg) r) (is (re-find #"data directory" (str msg)) r))))
      (finally (fs/delete-tree dir)))))

;; --- pure decisions

(deftest a-source-is-checked
  (let [roots ["/r" "/s/t"]]
    (is (nil? (files/source-problem {:real "/r/a.pdf" :kind "file"} roots)))
    (is (re-find #"does not exist" (files/source-problem {:real nil} roots)))
    (is (re-find #"symbolic link" (files/source-problem {:real "/r/l" :kind "link"} roots)))
    (is (re-find #"not inside" (files/source-problem {:real "/x/a" :kind "file"} roots)))
    (is (re-find #"stay where they are" (files/source-problem {:real "/r" :kind "dir"} roots)))
    (is (re-find #"holds one of the folders" (files/source-problem {:real "/r/a" :kind "dir"} ["/r" "/r/a/b"])))
    (is (re-find #"symbolic link" (files/source-problem {:real "/r/d" :kind "dir" :links true :entries 1} roots)))
    (is (re-find #"more than" (files/source-problem {:real "/r/d" :kind "dir" :entries (inc files/max-entries)} roots)))))

(deftest a-target-is-checked
  (let [ok {:exists false :base "/r" :base-kind "dir" :real "/r/b/c.pdf" :same-disk true}]
    (is (nil? (files/target-problem ok "/r/a.pdf" ["/r"])))
    (is (re-find #"never overwrites" (files/target-problem (assoc ok :exists true) "/r/a.pdf" ["/r"])))
    (is (re-find #"upper and lower case" (files/target-problem (assoc ok :exists true :same-file true) "/r/a.pdf" ["/r"])))
    (is (re-find #"not inside" (files/target-problem (assoc ok :real "/x/c.pdf") "/r/a.pdf" ["/r"])))
    (is (re-find #"not inside" (files/target-problem (assoc ok :real "/r") "/r/a.pdf" ["/r"])))
    (is (re-find #"inside itself" (files/target-problem (assoc ok :real "/r/a/b") "/r/a" ["/r"])))
    (is (re-find #"is a file" (files/target-problem (assoc ok :base-kind "file") "/r/a.pdf" ["/r"])))
    (is (re-find #"another disk" (files/target-problem (assoc ok :same-disk false) "/r/a.pdf" ["/r"])))))

(deftest the-folders-a-target-needs
  (is (= [] (files/creates "/r" "/r/a.pdf")))
  (is (= ["/r/x" "/r/x/y"] (files/creates "/r" "/r/x/y/a.pdf")))
  (is (= ["/x"] (files/creates "/" "/x/a")))
  (is (= ["/r/x/y" "/r/x" "/r/z"] (files/removable-dirs ["/r/x" "/r/x/y" "/r/z" "/r/x"]))))

(defspec moves-in-one-call-never-touch-the-same-place 300
  (prop/for-all [moves (gen/vector (gen/hash-map :from (gen/elements ["/r/a" "/r/a/b" "/r/b" "/r/c"])
                                                 :to (gen/elements ["/r/d" "/r/a/e" "/r/b" "/r/d/f"]))
                                   1 4)]
    (let [clash (set (files/batch-problems moves))
          touches? (fn [a b] (or (files/under? a b) (files/under? b a)))]
      (every? (fn [[i j]]
                (= (contains? clash [i j])
                   (boolean (some (fn [[x y]] (touches? x y))
                                  (for [x ((juxt :from :to) (moves i)) y ((juxt :from :to) (moves j))] [x y])))))
              (for [i (range (count moves)) j (range (inc i) (count moves))] [i j])))))

;; --- on the disk

(def ^:dynamic *dir* nil)

(use-fixtures :each
  (fn [run-test]
    (let [dir (str (fs/real-path (fs/create-temp-dir {:prefix "relay-files"})))]
      (try (binding [*dir* dir] (run-test))
           (finally (fs/delete-tree dir))))))

(defn- world!
  "A data directory, a writable folder `root` with an inbox of downloads
  and a filing cabinet, a folder outside it, and a steward granted root."
  [dir & [{:keys [roots]}]]
  (let [data (str dir "/data")
        root (str dir "/root")]
    (fs/create-dirs (str root "/Downloads"))
    (fs/create-dirs (str root "/Taxes/2025"))
    (fs/create-dirs (str dir "/outside"))
    (spit (str root "/Downloads/confirmation.pdf") "filed 2025")
    (spit (str root "/Downloads/statement-oct.pdf") "bank october")
    (spit (str root "/Taxes/2025/w2.pdf") "wages")
    (spit (str dir "/outside/secret.txt") "not yours")
    (log/init! data)
    (spit (str data "/relay.edn") (pr-str (assoc base :grants {"steward" {:files (or roots [root])}
                                                               "clerk" {}})))
    (let [sys (inbound/system data)]
      (doseq [id ["steward" "clerk"]]
        (inbound/submit! sys {:type :role/add :actor "operator" :args {:id id :title id :instructions "x"}}))
      {:sys sys :root root :data data})))

(defn- snapshot
  "Everything under `dir`, without following links: each path by what it
  is, a file's content, or a link's target."
  [dir]
  (into (sorted-map)
        (for [p (fs/glob dir "**" {:hidden true :follow-links false})
              :let [s (str p)
                    rel (subs s (inc (count dir)))]
              :when (not (re-find #"^(data|w)(/|$)" rel))]
          [rel (cond (fs/sym-link? p) [:link (str (fs/read-link p))]
                     (fs/directory? p {:nofollow-links true}) [:dir]
                     :else [:file (slurp s)])])))

(defn- move [sys moves & [labels]]
  (tools/call! sys "steward" "files_move" {:moves moves} (or labels []) "c1" nil))

(defn- events [sys type] (filter #(= type (:type %)) (log/read-events (:data-dir sys))))

(deftest a-move-files-a-download-and-undo-puts-it-back
  (let [{:keys [sys root]} (world! *dir*)
        before (snapshot *dir*)
        r (move sys [{:from (str root "/Downloads/confirmation.pdf") :to (str root "/Taxes/2025/Returns/confirmation.pdf")}
                     {:from (str root "/Downloads/statement-oct.pdf") :to (str root "/Bank/2025-10 statement.pdf")}]
                ["contaminated"])
        call (:seq (first (events sys "files/moving")))]
    (is (nil? (:error r)) (:error r))
    (is (str/includes? (:ok r) (str "relay undo " call)))
    (is (= ["contaminated" "private"] (:labels r)) "file names are private, and the session's labels go with them")
    (is (= "bank october" (slurp (str root "/Bank/2025-10 statement.pdf"))))
    (is (= "filed 2025" (slurp (str root "/Taxes/2025/Returns/confirmation.pdf"))))
    (is (not (fs/exists? (str root "/Downloads/confirmation.pdf"))))
    (testing "both events carry the session's labels and private"
      (is (= [["contaminated" "private"] ["contaminated" "private"]]
             (mapv :labels (concat (events sys "files/moving") (events sys "files/moved"))))))
    (testing "the log records the folders it made"
      (is (= [[(str root "/Taxes/2025/Returns")] [(str root "/Bank")]]
             (mapv :created (:done (core/move-call (inbound/current-state sys) call))))))
    (testing "relay moves lists the call"
      (let [out (:out (cli/execute ["--data" (:data-dir sys) "moves"]))]
        (is (re-find (re-pattern (str "Move " call ", .*by steward: 2 of 2 moved")) (first out)))))
    (testing "relay undo puts everything back and removes only the folders it made"
      (let [r (cli/execute ["--data" (:data-dir sys) "undo" (str call)])]
        (is (= 0 (:exit r)) (pr-str r))
        (is (re-find #"put back 2 of 2.*Removed 2 empty folder" (first (:out r)))))
      (is (= before (snapshot *dir*)))
      (is (= [{:call call :steps [0 1] :removed [(str root "/Taxes/2025/Returns") (str root "/Bank")]}]
             (mapv :data (events sys "files/undone"))))
      (is (= [["contaminated" "private"]] (mapv :labels (events sys "files/undone")))
          "what undo removed names the operator's folders, so it keeps the call's labels"))
    (testing "a second undo has nothing to do"
      (is (re-find #"already undone"
                   (try (cli/execute ["--data" (:data-dir sys) "undo" (str call)]) ""
                        (catch clojure.lang.ExceptionInfo e (ex-message e))))))))

(defn- refused [sys moves]
  (let [before (snapshot *dir*)
        n (count (log/read-events (:data-dir sys)))
        r (move sys moves)]
    (is (:error r) (pr-str r))
    (is (= before (snapshot *dir*)) "nothing moved")
    (is (= n (count (log/read-events (:data-dir sys)))) "nothing recorded")
    (is (some #{"private"} (:labels r)))
    (:error r)))

(deftest a-move-never-overwrites-deletes-or-leaves-the-folders
  (let [{:keys [sys root]} (world! *dir*)
        d (str root "/Downloads")]
    (fs/create-sym-link (str root "/out") (str *dir* "/outside"))
    (fs/create-sym-link (str d "/link.pdf") (str *dir* "/outside/secret.txt"))
    (fs/create-dirs (str d "/holds-link"))
    (fs/create-sym-link (str d "/holds-link/l") (str *dir* "/outside"))
    (is (re-find #"never overwrites" (refused sys [{:from (str d "/confirmation.pdf") :to (str d "/statement-oct.pdf")}])))
    (is (re-find #"never overwrites" (refused sys [{:from (str d "/confirmation.pdf") :to (str root "/Taxes")}])))
    (is (re-find #"not inside" (refused sys [{:from (str d "/confirmation.pdf") :to (str *dir* "/outside/c.pdf")}])))
    (is (re-find #"not inside" (refused sys [{:from (str d "/confirmation.pdf") :to (str root "/out/c.pdf")}]))
        "a link out of the folder is resolved")
    (is (re-find #"not inside" (refused sys [{:from (str root "/out/secret.txt") :to (str d "/s.txt")}])))
    (is (re-find #"not inside" (refused sys [{:from (str *dir* "/outside/secret.txt") :to (str d "/s.txt")}])))
    (is (re-find #"symbolic link" (refused sys [{:from (str d "/link.pdf") :to (str d "/l2.pdf")}])))
    (is (re-find #"symbolic link" (refused sys [{:from (str d "/holds-link") :to (str d "/h2")}])))
    (is (re-find #"stay where they are" (refused sys [{:from root :to (str *dir* "/root2")}])))
    (is (re-find #"inside itself" (refused sys [{:from (str root "/Taxes") :to (str root "/Taxes/2025/T")}])))
    (is (re-find #"does not exist" (refused sys [{:from (str d "/nope.pdf") :to (str d "/n.pdf")}])))
    (is (re-find #"not a full path" (refused sys [{:from "confirmation.pdf" :to (str d "/n.pdf")}])))
    (is (re-find #"\.\." (refused sys [{:from (str d "/../Downloads/confirmation.pdf") :to (str d "/n.pdf")}])))
    (is (re-find #"same place" (refused sys [{:from (str d "/confirmation.pdf") :to (str root "/New/c.pdf")}
                                             {:from (str d "/statement-oct.pdf") :to (str root "/New")}])))
    (testing "one bad move stops the whole call before anything moves"
      (is (re-find #"Nothing was moved" (refused sys [{:from (str d "/confirmation.pdf") :to (str root "/c.pdf")}
                                                      {:from (str d "/statement-oct.pdf") :to (str d "/confirmation.pdf")}]))))
    (testing "the input is exactly from and to"
      (is (:error (move sys [{:from (str d "/confirmation.pdf") :to (str root "/c.pdf") :force true}]))))))

(deftest a-folder-moves-whole-and-comes-back-whole
  (let [{:keys [sys root]} (world! *dir*)
        before (snapshot *dir*)
        r (move sys [{:from (str root "/Taxes/2025") :to (str root "/Archive/Taxes 2025")}])
        call (:seq (first (events sys "files/moving")))]
    (is (nil? (:error r)))
    (is (= "wages" (slurp (str root "/Archive/Taxes 2025/w2.pdf"))))
    (is (:ok (moves/undo! (inbound/system (:data-dir sys)) call)) "undo is the operator's, from any process")
    (is (= before (snapshot *dir*)))))

(deftest a-folder-relay-cannot-read-is-refused-and-named-by-no-one
  ;; An unreadable folder deep in a source must not reach the steward as a
  ;; bare error naming a path, inside the writable folder or outside it,
  ;; and relay never walks a folder outside the writable folders at all.
  (let [{:keys [sys root]} (world! *dir*)
        outside-hidden (str *dir* "/outside/a/b/private-name-outside")
        inside-hidden (str root "/d/hidden-secret-name")
        lock! (fn [p] (fs/create-dirs p) (fs/set-posix-file-permissions p "---------"))]
    (lock! outside-hidden)
    (lock! inside-hidden)
    (try
      (let [e (refused sys [{:from (str *dir* "/outside") :to (str root "/x")}])]
        (is (re-find #"not inside a folder you may organize" e))
        (is (not (str/includes? e "private-name-outside"))))
      (let [e (refused sys [{:from (str root "/d") :to (str root "/e")}])]
        (is (re-find #"something relay cannot read" e))
        (is (not (str/includes? e "hidden-secret-name"))))
      (finally
        (doseq [p [outside-hidden inside-hidden]] (fs/set-posix-file-permissions p "rwx------"))))))

(deftest undo-refuses-when-the-world-changed-and-touches-nothing
  (let [{:keys [sys root]} (world! *dir*)
        d (str root "/Downloads")
        call! (fn [moves] (move sys moves) (:seq (last (events sys "files/moving"))))
        undo (fn [call] (moves/undo! sys call))]
    (testing "a file edited since"
      (let [call (call! [{:from (str d "/confirmation.pdf") :to (str root "/c.pdf")}])]
        (spit (str root "/c.pdf") "edited by Kira")
        (let [before (snapshot *dir*)
              {:keys [refused]} (undo call)]
          (is (re-find #"changed since it was moved" (str/join "\n" refused)))
          (is (re-find #"nothing changed" (first refused)))
          (is (= before (snapshot *dir*))))
        (spit (str root "/c.pdf") "filed 2025")
        (is (:ok (undo call)) "back as it was, so undo can run")))
    (testing "a file moved again by Kira"
      (let [call (call! [{:from (str d "/confirmation.pdf") :to (str root "/c.pdf")}])]
        (fs/move (str root "/c.pdf") (str root "/Taxes/c.pdf"))
        (is (re-find #"Nothing is at .*c.pdf any more" (str/join "\n" (:refused (undo call)))))
        (fs/move (str root "/Taxes/c.pdf") (str root "/c.pdf"))
        (is (:ok (undo call)))))
    (testing "something new where the file was"
      (let [call (call! [{:from (str d "/confirmation.pdf") :to (str root "/c.pdf")}])]
        (spit (str d "/confirmation.pdf") "a new download")
        (is (re-find #"Something new is at" (str/join "\n" (:refused (undo call)))))
        (is (= "a new download" (slurp (str d "/confirmation.pdf"))))
        (fs/delete (str d "/confirmation.pdf"))
        (is (:ok (undo call)))))
    (testing "a folder it made that now holds something new is kept"
      (let [call (call! [{:from (str d "/confirmation.pdf") :to (str root "/New/c.pdf")}])]
        (spit (str root "/New/mine.txt") "Kira's")
        (let [{:keys [ok]} (undo call)]
          (is (re-find #"Kept .*New" (str/join "\n" ok))))
        (is (= "Kira's" (slurp (str root "/New/mine.txt"))))
        (is (= "filed 2025" (slurp (str d "/confirmation.pdf"))))))
    (testing "a link put where a folder was is not followed"
      (fs/create-dirs (str root "/Box"))
      (let [call (call! [{:from (str d "/statement-oct.pdf") :to (str root "/Box/s.pdf")}])]
        (fs/move (str root "/Box") (str *dir* "/outside/Box"))
        (fs/create-sym-link (str root "/Box") (str *dir* "/outside/Box"))
        (is (re-find #"not the folder it was" (str/join "\n" (:refused (undo call)))))
        (is (fs/exists? (str *dir* "/outside/Box/s.pdf")))))
    (testing "a link put where a folder further up the source was is not followed"
      (fs/create-dirs (str root "/X/In"))
      (spit (str root "/X/In/f.txt") "deep")
      (let [call (call! [{:from (str root "/X/In/f.txt") :to (str root "/f.txt")}])]
        (fs/move (str root "/X") (str *dir* "/outside/X"))
        (fs/create-sym-link (str root "/X") (str *dir* "/outside/X"))
        (is (re-find #"X/In is not the folder it was" (str/join "\n" (:refused (undo call)))))
        (is (not (fs/exists? (str *dir* "/outside/X/In/f.txt"))))
        (is (= "deep" (slurp (str root "/f.txt"))))))
    (testing "a file edited inside a moved folder"
      (let [call (call! [{:from (str root "/Taxes/2025") :to (str root "/T25")}])]
        (spit (str root "/T25/w2.pdf") "wages, corrected by Kira")
        (let [before (snapshot *dir*)]
          (is (re-find #"T25 has changed since" (str/join "\n" (:refused (undo call)))))
          (is (= before (snapshot *dir*))))))))

(deftest a-call-relay-never-finished-says-to-check-by-hand
  (let [{:keys [sys root]} (world! *dir*)
        e (inbound/submit! sys {:type :files/start :actor "steward" :labels []
                                :args {:moves [{:from (str root "/a") :to (str root "/b")}]}})
        call (:seq (first e))
        {:keys [refused]} (moves/undo! sys call)]
    (is (re-find #"Check them by hand" (first refused)))
    (is (str/includes? (second refused) (str root "/a → " root "/b")))
    (is (re-find #"check by hand" (first (files/calls-lines (core/move-calls (inbound/current-state sys)) java.time.ZoneOffset/UTC))))))

(deftest only-a-role-granted-folders-moves-files
  (let [{:keys [sys root]} (world! *dir*)]
    (is (contains? (tools/offered (:config sys) "steward") "files_move"))
    (is (= "edit" (:class (first (filter #(= "files_move" (:name %)) (tools/manifest (:config sys) "steward"))))))
    (is (not (contains? (tools/offered (:config sys) "clerk") "files_move")))
    (is (= "You have no tool called files_move."
           (:error (tools/call! sys "clerk" "files_move" {:moves []} [] "c" nil))))
    (testing "the log refuses a move from anyone else"
      (doseq [actor ["operator" "clerk"]]
        (is (= :forbidden (try (inbound/submit! sys {:type :files/start :actor actor :labels []
                                                     :args {:moves [{:from (str root "/a") :to (str root "/b")}]}})
                               nil
                               (catch clojure.lang.ExceptionInfo e (core/rejection e)))))))
    (testing "only the operator undoes"
      (is (= :forbidden (try (inbound/submit! sys {:type :files/undo :actor "steward" :labels []
                                                   :args {:call 1 :steps [] :removed []}})
                             nil
                             (catch clojure.lang.ExceptionInfo e (core/rejection e))))))))

(deftest a-writable-folder-that-is-a-link-to-a-secret-is-refused-when-it-runs
  ;; relay.edn passes: as written, the path is fine. Its real path is the
  ;; data directory.
  (let [data (str *dir* "/data")
        _ (fs/create-dirs (str *dir* "/x"))
        link (str *dir* "/x/inbox")
        {:keys [sys]} (world! *dir* {:roots [link]})]
    (fs/create-sym-link link data)
    (is (re-find #"will not organize" (str (:error (move sys [{:from (str link "/relay.edn") :to (str link "/r.edn")}])))))
    (is (fs/exists? (str data "/relay.edn")))))

;; --- I7 extended: undo restores any tree byte for byte

(def gen-name (gen/elements ["a" "b" "c.pdf" "d e.txt" "Ä.md"]))
(def gen-rel (gen/vector gen-name 1 3))

(def gen-tree
  (gen/vector (gen/tuple gen-rel (gen/elements ["", "x", "statement", "the same"])) 1 8))

(def gen-place
  "Where a generated move starts or ends: inside the writable folder,
  through a link out of it, or outside it."
  (gen/tuple (gen/frequency [[8 (gen/return "root")] [1 (gen/return "root/out")] [1 (gen/return "outside")]])
             gen-rel))

(def gen-source
  "Mostly something in the folder as it stands when the call is made, by
  its place in the list; sometimes any place at all."
  (gen/frequency [[4 (gen/fmap (fn [n] [:existing n]) gen/nat)] [1 gen-place]]))

(def gen-calls (gen/vector (gen/vector (gen/tuple gen-source gen-place) 1 2) 1 8))

(defn- build! [dir tree]
  (fs/create-dirs (str dir "/root"))
  (fs/create-dirs (str dir "/outside"))
  (spit (str dir "/outside/kept") "outside")
  (fs/create-sym-link (str dir "/root/out") (str dir "/outside"))
  (doseq [[rel content] tree
          :let [p (str dir "/root/" (str/join "/" rel))]]
    ;; A path where a file already stands, or under one, is skipped.
    (when (and (not (fs/exists? p {:nofollow-links false}))
               (every? #(let [a (str dir "/root/" (str/join "/" %))]
                          (or (not (fs/exists? a)) (fs/directory? a)))
                       (map #(subvec rel 0 %) (range 1 (count rel)))))
      (fs/create-dirs (fs/parent p))
      (spit p content))))

(defn- place [dir [top rel]]
  (if (= :existing top)
    (let [here (vec (keys (into (sorted-map) (filter #(str/starts-with? (key %) "root/") (snapshot dir)))))]
      (str dir "/" (here (mod rel (count here)))))
    (str dir "/" top "/" (str/join "/" rel))))

(defn- contents [snap] (sort (keep (fn [[_ [k c]]] (when (= :file k) c)) snap)))

(defn- a-change-stops-undo?
  "Edits, adds or removes one file in what the last call moved, checks that
  undo then refuses and touches nothing, and puts the change back."
  [sys dir call change]
  (let [{:keys [moves done]} (core/move-call (inbound/current-state sys) call)
        to (:to (moves (:index (first done))))
        dir? (fs/directory? to {:nofollow-links true})
        file (if dir?
               (some #(when (fs/regular-file? % {:nofollow-links true}) (str %)) (sort (fs/glob to "**" {:hidden true})))
               to)
        change (cond (and dir? (or (= :add change) (nil? file))) :add
                     (= :add change) :edit
                     :else change)
        added (str to "/zz-new")
        content (when file (slurp file))]
    (case change
      :edit (spit file "changed by Kira" :append true)
      :remove (fs/delete file)
      :add (spit added "new"))
    (let [before (snapshot dir)
          r (moves/undo! sys call)
          ok (and (:refused r) (= before (snapshot dir)))]
      (case change
        :edit (spit file content)
        :remove (spit file content)
        :add (fs/delete added))
      ok)))

(defspec undoing-every-call-restores-the-tree-byte-for-byte 60
  (prop/for-all [tree gen-tree
                 calls gen-calls
                 change (gen/elements [:edit :add :remove])]
    (let [dir (str (fs/real-path (fs/create-temp-dir {:prefix "relay-files-prop"})))]
      (try
        (build! dir tree)
        (let [original (snapshot dir)
              outside (fn [snap] (into {} (filter #(str/starts-with? (key %) "outside") snap)))
              {:keys [sys]} (binding [*dir* dir] (world! (str dir "/w") {:roots [(str dir "/root")]}))
              made (reduce (fn [made moves]
                             (let [r (tools/call! sys "steward" "files_move"
                                                  {:moves (vec (for [[f t] moves] {:from (place dir f) :to (place dir t)}))}
                                                  [] "c" nil)]
                               (if (:error r) made (conj made (:seq (last (events sys "files/moving")))))))
                           [] calls)
              after (snapshot dir)
              finished (every? #(nil? (:stopped (core/move-call (inbound/current-state sys) %))) made)
              ;; A change since the last call stops its undo, which then
              ;; touches nothing.
              stops (or (empty? made) (a-change-stops-undo? sys dir (peek made) change))
              undos (mapv #(moves/undo! sys %) (reverse made))]
          (and
           ;; Nothing outside the folder changed, and no file was lost or
           ;; overwritten: the same contents are all still there.
           (= (outside original) (outside after))
           (= (contents original) (contents after))
           finished
           stops
           ;; Every step stayed inside the folder, links resolved.
           (every? (fn [c] (every? (fn [{:keys [from to]}]
                                     (and (str/starts-with? from (str dir "/root/"))
                                          (str/starts-with? to (str dir "/root/"))
                                          (not (str/starts-with? from (str dir "/root/out/")))
                                          (not (str/starts-with? to (str dir "/root/out/")))))
                                   (:moves (core/move-call (inbound/current-state sys) c))))
                   made)
           (every? :ok undos)
           (= original (snapshot dir))))
        (finally (fs/delete-tree dir))))))
