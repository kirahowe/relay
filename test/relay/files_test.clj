(ns relay.files-test
  "Organizing files (relay.files, relay.moves): what relay.edn may grant,
  the walls a move keeps (never overwrite, never delete, never leave the
  writable folders, links resolved), the log's record of every call, and
  undo, move by move: each move goes back exactly, or stays where it is
  and is named. I7 extended: for
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
            [relay.hub :as hub]
            [relay.hub-test :as ht]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.moves :as moves]
            [relay.ready :as ready]
            [relay.serve :as serve]
            [relay.tools :as tools]))

(def base
  {:timezone "UTC"
   :default-harness :agentloop
   :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}}
   :tools {}
   :dead-letter-handler "hub"
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

;; --- Codex R2: nothing from outside moves into a trusted folder

(def trusted-notes [{:path "/r/notes" :inside "/r/notes" :forms ["/r/notes"]}])

(deftest a-move-into-a-trusted-folder-must-come-from-one
  (let [t trusted-notes
        refused? #(some? (files/trust-problem %1 %2 #{} t))]
    (is (re-find #"from outside into /r/notes" (files/trust-problem "/r/dl/injected.txt" "/r/notes/i.txt" #{} t)))
    (is (refused? "/r/dl/injected" "/r/notes/sub/injected") "a folder is judged by where it came from")
    (is (not (refused? "/r/notes/a.txt" "/r/dl/a.txt")) "out of a trusted folder")
    (is (not (refused? "/r/notes/a.txt" "/r/notes/old/a.txt")) "within one")
    (is (not (refused? "/r/dl/a.txt" "/r/dl/old/a.txt")) "within untrusted places")
    (let [two (conj t {:path "/r/work" :inside "/r/work" :forms ["/r/work"]})]
      (is (nil? (files/trust-problem "/r/notes/a.txt" "/r/work/a.txt" #{} two))
          "from one trusted folder to another"))
    (testing "the target is compared as the disk would"
      (is (refused? "/r/dl/a.txt" "/r/NOTES/a.txt") "a case-insensitive disk")
      (is (refused? "/r/dl/a.txt" "/r/Notes/a.txt"))
      (let [composed "/r/r\u00e9sum\u00e9s" decomposed "/r/re\u0301sume\u0301s"]
        (is (some? (files/trust-problem "/r/dl/a.txt" (str decomposed "/a.txt") #{}
                                        [{:path composed :inside composed :forms [composed]}]))
            "a composed and a decomposed accent name the same folder"))
      (is (refused? "/r/dl/a.txt" "/System/Volumes/Data/r/notes/a.txt") "a firmlink")
      (is (refused? "/System/Volumes/Data/r/dl/a.txt" "/r/notes/a.txt"))
      (is (not (refused? "/System/Volumes/Data/r/notes/a.txt" "/r/dl/a.txt"))
          "a firmlinked source in the trusted folder is in it")
      (is (some? (files/trust-problem "/r/dl/a.txt" "/r/x/a.txt" #{"/r/notes"} t))
          "the same file by identity"))
    (testing "a target that would become a trusted folder, or hold one"
      (is (refused? "/r/dl/notes" "/r/notes"))
      (is (refused? "/r/dl/pkg" "/r") "holds it"))
    (testing "a source is in a trusted folder only by its own exact real path"
      (is (refused? "/r/NOTES/a.txt" "/r/notes/b.txt"))
      (is (refused? "/r/notesx/a.txt" "/r/notes/b.txt"))
      (is (refused? "/r/a.txt" "/r/notes/b.txt"))
      (is (some? (files/trust-problem "/r/dl/a.txt" "/r/notes/b.txt" #{}
                                      [{:path "/r/notes" :inside nil :forms ["/r/notes"]}]))
          "a trusted folder that does not exist yet holds nothing to move"))))

(def gen-mount-path (gen/elements ["/r/notes" "/r/dl" "/r/notes/deep" "/r/work" "/s/notes"]))
(def gen-move-path (gen/elements ["/r/notes/a" "/r/NOTES/a" "/r/dl/a" "/r/notes/deep/a" "/r/work/a"
                                  "/r" "/r/notes" "/r/a" "/s/notes/a" "/s/a" "/r/notesx/a"
                                  "/System/Volumes/Data/r/notes/a" "/System/Volumes/Data/r/dl/a"]))

(defspec a-move-into-a-trusted-folder-is-refused-exactly-when-it-comes-from-outside 500
  (prop/for-all [mounts (gen/vector-distinct gen-mount-path {:min-elements 1 :max-elements 4})
                 from gen-move-path
                 to gen-move-path]
    (let [t (mapv (fn [m] {:path m :inside m :forms [m]}) mounts)
          plain (fn [p] (str/lower-case (str/replace p #"^/System/Volumes/Data" "")))
          inside? (fn [p m] (or (= p m) (str/starts-with? p (str m "/"))))
          from-trusted (some #(inside? (str/replace from #"^/System/Volumes/Data" "") %) mounts)
          into (some #(or (inside? (plain to) %) (inside? % (plain to))) mounts)
          problem (files/trust-problem from to #{} t)]
      (= (boolean (and into (not from-trusted))) (some? problem)))))

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
  [dir & [{:keys [roots mounts]}]]
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
    (spit (str data "/relay.edn") (pr-str (cond-> (assoc base :grants {"steward" {:files (or roots [root])}
                                                                       "clerk" {}})
                                            mounts (assoc :house {:mounts mounts}))))
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
        (is (re-find (re-pattern (str "Move " call ", .*by steward: 2 of 2 moved")) (first out)))
        (is (every? #(<= (count %) 100) out) "short lines")
        (is (not-any? #(str/includes? % (str *dir*)) out) "relative to the folder, never the full path")
        (is (some #(str/includes? % "Downloads/confirmation.pdf → Taxes/2025/Returns/confirmation.pdf") out))))
    (testing "relay undo puts everything back and removes only the folders it made"
      (let [r (cli/execute ["--data" (:data-dir sys) "undo" (str call)])]
        (is (= 0 (:exit r)) (pr-str r))
        (is (re-find #"put back 2 of 2.*Removed 2 empty folder" (first (:out r)))))
      (is (= before (snapshot *dir*)))
      (is (= [{:call call :steps [0 1] :removed [{:call call :path (str root "/Taxes/2025/Returns")} {:call call :path (str root "/Bank")}]}]
             (mapv :data (events sys "files/undone"))))
      (is (= [["contaminated" "private"]] (mapv :labels (events sys "files/undone")))
          "what undo removed names the operator's folders, so it keeps the call's labels"))
    (testing "a second undo has nothing to do"
      (is (re-find #"already undone"
                   (try (cli/execute ["--data" (:data-dir sys) "undo" (str call)]) ""
                        (catch clojure.lang.ExceptionInfo e (ex-message e))))))))

(defn- refused [sys moves & [labels]]
  (let [before (snapshot *dir*)
        n (count (log/read-events (:data-dir sys)))
        r (move sys moves labels)]
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

(deftest undo-removes-only-the-folders-tied-to-what-it-puts-back
  (let [{:keys [sys root]} (world! *dir*)
        d (str root "/Downloads")
        call! (fn [moves] (move sys moves) (:seq (last (events sys "files/moving"))))]
    (testing "a folder kept for something new, emptied later by Kira, is not swept by an unrelated undo"
      (let [c3 (call! [{:from (str d "/confirmation.pdf") :to (str root "/New/confirmation.pdf")}])]
        (spit (str root "/New/mine.txt") "mine")
        (is (re-find #"Kept New: something new is in it" (str/join "\n" (:ok (moves/undo! sys c3)))))
        (fs/delete (str root "/New/mine.txt"))
        (let [c7 (call! [{:from (str d "/statement-oct.pdf") :to (str root "/Bank/s.pdf")}])
              r (moves/undo! sys c7)]
          (is (fs/directory? (str root "/New")) "Kira's empty New stays")
          (is (not (fs/exists? (str root "/Bank"))) "the folder move 7 made goes")
          (is (= [{:call c7 :path (str root "/Bank")}] (:removed (:data (last (events sys "files/undone"))))))
          (is (re-find #"Removed 1 empty folder " (first (:ok r)))))))
    (testing "a folder recreated after an undo removed it, recorded under another call, stays"
      (fs/delete (str root "/New"))
      (let [a (call! [{:from (str d "/confirmation.pdf") :to (str root "/New/c.pdf")}])
            b (call! [{:from (str d "/statement-oct.pdf") :to (str root "/New/s.pdf")}])]
        (is (:ok (moves/undo! sys a)))
        (is (fs/directory? (str root "/New")) "move b's file is still in it")
        (is (:ok (moves/undo! sys b)))
        (is (not (fs/exists? (str root "/New"))) "empty once b is back, so the folder a made goes")
        (is (= [{:call a :path (str root "/New")}] (:removed (:data (last (events sys "files/undone")))))
            "recorded against the call that made it")
        (fs/create-dirs (str root "/New"))
        (let [c (call! [{:from (str d "/confirmation.pdf") :to (str root "/Other/c.pdf")}])]
          (is (:ok (moves/undo! sys c)))
          (is (fs/directory? (str root "/New")) "the folder Kira made again stays"))))
    (testing "a folder kept for a later move, emptied by Kira's hand, is not swept by an unrelated undo"
      (fs/delete (str root "/New"))
      (let [a (call! [{:from (str d "/confirmation.pdf") :to (str root "/New/c.pdf")}])
            _ (call! [{:from (str d "/statement-oct.pdf") :to (str root "/New/s.pdf")}])]
        (is (:ok (moves/undo! sys a)))
        (fs/move (str root "/New/s.pdf") (str root "/s.pdf"))
        (let [c (call! [{:from (str d "/confirmation.pdf") :to (str root "/Other/c.pdf")}])]
          (is (:ok (moves/undo! sys c)))
          (is (fs/directory? (str root "/New")) "only an undo that puts something back from it removes it"))))))

(deftest undo-keeps-a-folder-a-later-call-took-something-out-of
  (let [{:keys [sys root]} (world! *dir*)
        d (str root "/Downloads")
        original (snapshot *dir*)
        call! (fn [moves] (move sys moves) (:seq (last (events sys "files/moving"))))
        ;; The move that goes back is the one that made new/.
        c1 (call! [{:from (str d "/statement-oct.pdf") :to (str root "/new/s.pdf")}
                   {:from (str d "/confirmation.pdf") :to (str root "/new/c.pdf")}])
        c2 (call! [{:from (str root "/new/c.pdf") :to (str root "/b/c.pdf")}])]
    (let [r (moves/undo! sys c1)]
      (is (re-find #"put back 1 of 2" (first (:ok r))))
      (is (some #(str/includes? % (str "Move " c2 " has moved new/c.pdf since. relay undo " c2 " first")) (:ok r)))
      (is (fs/directory? (str root "/new")) "move 2's undo puts c.pdf back in new"))
    (is (:ok (moves/undo! sys c2)))
    (is (:ok (moves/undo! sys c1)))
    (is (= original (snapshot *dir*)))))

(deftest undo-leaves-what-a-later-move-put-back-in-its-place
  ;; Moved away and back by later calls, the file looks unchanged, but it
  ;; is the later call's to put back: undoing the first call early must
  ;; not take it, or that call's undo has nothing left to undo.
  (let [{:keys [sys root]} (world! *dir*)
        original (snapshot *dir*)
        call! (fn [moves] (move sys moves) (:seq (last (events sys "files/moving"))))
        c1 (call! [{:from (str root "/Downloads/confirmation.pdf") :to (str root "/a.pdf")}])
        c2 (call! [{:from (str root "/a.pdf") :to (str root "/b.pdf")}])
        c3 (call! [{:from (str root "/b.pdf") :to (str root "/a.pdf")}])]
    (let [r (moves/undo! sys c1)]
      (is (re-find (re-pattern (str "Move " c3 " has moved a.pdf since")) (str/join "\n" (:refused r)))))
    (doseq [c [c3 c2 c1]] (is (:ok (moves/undo! sys c)) (str "undo " c)))
    (is (= original (snapshot *dir*)))
    (testing "nor take the old place a later move needs to put something back in"
      (let [c1 (call! [{:from (str root "/Downloads/confirmation.pdf") :to (str root "/x/c.pdf")}])
            c2 (call! [{:from (str root "/Downloads/statement-oct.pdf") :to (str root "/Downloads/confirmation.pdf")}])
            c3 (call! [{:from (str root "/Downloads/confirmation.pdf") :to (str root "/s.pdf")}])]
        (let [r (moves/undo! sys c1)]
          (is (str/includes? (str/join "\n" (:refused r))
                             (str "Move " c3 " has moved something at Downloads/confirmation.pdf since. relay undo " c3
                                  " first, then relay undo " c1 " puts x/c.pdf back."))))
        (doseq [c [c3 c2 c1]] (is (:ok (moves/undo! sys c)) (str "undo " c)))
        (is (= original (snapshot *dir*)))))))

(deftest undo-never-removes-a-folder-an-earlier-undo-removed
  (let [{:keys [sys root]} (world! *dir*)
        d (str root "/Downloads")
        _ (move sys [{:from (str d "/confirmation.pdf") :to (str root "/F/c.pdf")}
                     {:from (str d "/statement-oct.pdf") :to (str root "/G/s.pdf")}])
        c (:seq (last (events sys "files/moving")))]
    (is (:ok (moves/undo! sys c #{0})))
    (is (not (fs/exists? (str root "/F"))))
    (fs/create-dirs (str root "/F"))
    (is (:ok (moves/undo! sys c #{1})))
    (is (fs/directory? (str root "/F")) "Kira made F again; it is hers")
    (is (not (fs/exists? (str root "/G"))))))

(deftest a-refused-undo-says-where-the-file-came-from-and-what-to-do
  (let [{:keys [sys root]} (world! *dir*)
        _ (fs/create-dirs (str root "/old stuff"))
        _ (spit (str root "/old stuff/grant-3.md") "# Grant 3\n")
        _ (move sys [{:from (str root "/old stuff/grant-3.md") :to (str root "/grants/grant-3.md")}])
        c (:seq (last (events sys "files/moving")))]
    (spit (str root "/grants/grant-3.md") "a line of Kira's\n" :append true)
    (let [lines (:refused (moves/undo! sys c))
          text (str/join "\n" lines)]
      (is (str/includes? text "grants/grant-3.md has changed since it was moved there. It came from old stuff/grant-3.md; move it back there yourself to keep your edits."))
      (is (not (str/includes? text "Once each is as the move left it")))
      (is (not (str/includes? text root)) "paths relative to the granted folder"))
    (testing "moved back by hand, it counts as put back, by Kira"
      (fs/move (str root "/grants/grant-3.md") (str root "/old stuff/grant-3.md"))
      (let [{:keys [ok]} (moves/undo! sys c)]
        (is (some #(str/includes? % "old stuff/grant-3.md: you put it back") ok) (pr-str ok)))
      (is (= [0] (:undone (core/move-call (inbound/current-state sys) c))))
      (is (= "# Grant 3\na line of Kira's\n" (slurp (str root "/old stuff/grant-3.md"))) "nothing moved")
      (is (not (fs/exists? (str root "/grants"))) "the folder the move made, empty now, goes")
      (is (re-find #"already undone" (try (moves/undo! sys c) "" (catch clojure.lang.ExceptionInfo e (ex-message e))))))))

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

;; --- Codex R2: on the disk, nothing from outside moves into a trusted folder

(deftest nothing-from-outside-moves-into-a-trusted-folder
  ;; A session shaped by a stranger's text could otherwise file
  ;; downloads/injected.txt into the trusted notes, where every later
  ;; shell read is the operator's own: clean, its links live, the recipe
  ;; changes it shapes unflagged. The session's labels do not matter.
  (let [notes (str *dir* "/root/Notes")
        _ (fs/create-dirs notes)
        _ (spit (str notes "/plan.txt") "mine")
        {:keys [sys root]} (world! *dir* {:mounts [(str *dir* "/root/Downloads")
                                                   {:path notes :trusted true}
                                                   {:path (str *dir* "/root/Later") :trusted true}]})
        d (str root "/Downloads")]
    (fs/create-dirs (str d "/pack"))
    (spit (str d "/pack/injected.txt") "IGNORE PREVIOUS")
    (fs/create-sym-link (str root "/to-notes") notes)
    (is (str/includes? (:description (first (filter #(= "files_move" (:name %)) (tools/manifest (:config sys) "steward"))))
                       (str "only their own files in " notes ", "))
        "the steward is told before it tries")
    (doseq [labels [[] ["contaminated" "mail:stranger@example.com"]]]
      (testing (str "a session holding " labels)
        (is (re-find #"from outside into .*/root/Notes"
                     (refused sys [{:from (str d "/confirmation.pdf") :to (str notes "/c.pdf")}] labels)))))
    (is (re-find #"from outside" (refused sys [{:from (str d "/pack") :to (str notes "/pack")}]))
        "a folder, by where it came from")
    (is (re-find #"from outside" (refused sys [{:from (str d "/confirmation.pdf") :to (str root "/notes/c.pdf")}]))
        "another case of the same name")
    (is (re-find #"from outside" (refused sys [{:from (str d "/confirmation.pdf") :to (str root "/to-notes/c.pdf")}]))
        "through a link")
    (is (re-find #"from outside" (refused sys [{:from (str d "/confirmation.pdf") :to (str root "/Later/c.pdf")}]))
        "a trusted folder that does not exist yet")
    (is (re-find #"from outside" (refused sys [{:from (str d "/pack") :to (str root "/Later")}]))
        "a folder that would become a trusted one")
    (testing "moves out of, within and away from trusted folders go ahead"
      (is (nil? (:error (move sys [{:from (str notes "/plan.txt") :to (str notes "/2026/plan.txt")}]))))
      (is (nil? (:error (move sys [{:from (str d "/confirmation.pdf") :to (str root "/Bank/c.pdf")}]))))
      (is (nil? (:error (move sys [{:from (str notes "/2026/plan.txt") :to (str d "/plan.txt")}]))))
      (testing "and what left a trusted folder is from outside once it is out"
        (is (re-find #"from outside" (refused sys [{:from (str d "/plan.txt") :to (str notes "/plan.txt")}]))))
      (testing "undo puts it back where it came from"
        (let [call (:seq (last (events sys "files/moving")))]
          (is (:ok (moves/undo! sys call)))
          (is (= "mine" (slurp (str notes "/2026/plan.txt")))))))))

(def ^:private folders ["Notes" "Mail" "Work" "Dl"])

(def gen-trust-world
  "Four mounted folders under root, some trusted, each with files of
  their own, and moves among them by any spelling of a folder: its own
  name, lower case, a link to Notes, or a new folder inside one."
  (gen/let [trust (gen/vector gen/boolean 4)
            calls (gen/vector (gen/vector (gen/tuple (gen/tuple (gen/elements folders) (gen/elements ["f" "g" ""]))
                                                     (gen/tuple (gen/elements (concat folders ["notes" "mail" "L" "Notes/sub" "Fresh"]))
                                                                (gen/elements ["x" "y" "z"])))
                                          1 2)
                              1 6)]
    {:trust (zipmap folders trust) :calls calls}))

(defspec nothing-from-outside-ever-lands-in-a-trusted-folder 60
  (prop/for-all [{:keys [trust calls]} gen-trust-world]
    (let [dir (str (fs/real-path (fs/create-temp-dir {:prefix "relay-trust-prop"})))]
      (try
        (let [root (str dir "/root")
              _ (doseq [f folders]
                  (fs/create-dirs (str root "/" f))
                  (spit (str root "/" f "/f") (str f " f"))
                  (spit (str root "/" f "/g") (str f " g")))
              _ (fs/create-sym-link (str root "/L") (str root "/Notes"))
              trusted (set (filter trust folders))
              mine (set (for [f trusted n ["f" "g"]] (str f " " n)))
              {:keys [sys]} (binding [*dir* dir]
                              (world! (str dir "/w") {:roots [root]
                                                      :mounts (vec (for [f folders]
                                                                     {:path (str root "/" f) :trusted (get trust f)}))}))
              in-trusted (fn [] (set (for [f trusted
                                           p (fs/glob (str root "/" f) "**")
                                           :when (fs/regular-file? p {:nofollow-links true})]
                                       (slurp (str p)))))
              results (for [moves calls]
                        (let [ms (vec (for [[[sf sn] [tf tn]] moves]
                                        {:from (str root "/" sf (when (seq sn) (str "/" sn)))
                                         :to (str root "/" tf "/" tn)}))
                              r (tools/call! sys "steward" "files_move" {:moves ms} [] "c" nil)
                              ;; A move counts as into a trusted folder by
                              ;; where its target really is.
                              into? (fn [{:keys [to]}]
                                      (let [t (-> to (str/replace (str root "/L") (str root "/Notes"))
                                                  (str/replace (str root "/notes") (str root "/Notes"))
                                                  (str/replace (str root "/mail") (str root "/Mail")))]
                                        (some #(str/starts-with? t (str root "/" % "/")) trusted)))
                              from-trusted? (fn [{:keys [from]}] (some #(str/starts-with? from (str root "/" % "/")) trusted))
                              ;; Nothing moves around under the generator's
                              ;; feet: what was in a folder is checked by
                              ;; the disk, at the end, by content.
                              trust-refused (boolean (re-find #"from outside" (str (:error r))))]
                          {:ok (if trust-refused
                                 (some #(and (into? %) (not (from-trusted? %))) ms)
                                 true)}))]
          (and (every? :ok (doall results))
               (every? mine (in-trusted))))
        (finally (fs/delete-tree dir))))))

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
  "Edits, adds or removes one file in what the last call's first move
  moved, checks that undo then leaves that move where it is, untouched,
  and puts back every other (refusing, and touching nothing, when there
  is no other), and puts the change back."
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
          rel (subs to (inc (count dir)))
          at-to (fn [snap] (into {} (filter #(or (= rel (key %)) (str/starts-with? (key %) (str rel "/"))) snap)))
          changed (at-to before)
          r (moves/undo! sys call)
          after (snapshot dir)
          undone (set (:undone (core/move-call (inbound/current-state sys) call)))
          ok (and (not (undone (:index (first done))))
                  (= changed (at-to after)))
          ok (if (= 1 (count done))
               (and ok (:refused r) (= before after))
               (and ok (:ok r) (= (set (map :index (rest done))) undone)))]
      (case change
        :edit (spit file content)
        :remove (spit file content)
        :add (fs/delete added))
      ok)))

(defspec undoing-every-call-restores-the-tree-byte-for-byte 200
  (prop/for-all [tree gen-tree
                 calls gen-calls
                 change (gen/elements [:edit :add :remove])
                 ;; Which moves of the first call to put back early, on
                 ;; their own, before the rest.
                 early (gen/vector gen/boolean 2)]
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
              ;; Moves put back one by one: each goes back or is left
              ;; where it is, never half.
              _ (when-let [c (first made)]
                  (let [left (core/move-steps-left (core/move-call (inbound/current-state sys) c))
                        only (set (keep (fn [[s e]] (when e (:index s))) (map vector left early)))]
                    (when (seq only) (moves/undo! sys c only))))
              undos (vec (for [c (reverse made)
                               :when (seq (core/move-steps-left (core/move-call (inbound/current-state sys) c)))]
                           (moves/undo! sys c)))]
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

;; --- moves in the ready view, and undo from the client

(defn- client-undo
  "Asks the hub, as a client on connection 1, to undo `call`, carrying
  out its effects as relay's shell does (relay.serve/undo-result). The
  answer the client was written."
  [sys call]
  (let [written (atom [])
        h (-> (ht/live) (assoc :state (inbound/current-state sys)))]
    (hub/drive h {:event :connection-line :conn 1 :line (ht/req 9 "_relay/undo" {:move call})}
               (fn [{:keys [effect] :as f}]
                 (case effect
                   :write (do (when (= 1 (:conn f)) (swap! written conj (ht/parse (:line f)))) nil)
                   :undo (merge {:event :submit-result} (serve/undo-result sys (:move f)))
                   nil)))
    (first (filter #(= 9 (get % "id")) @written))))

(defn- move-item [sys call]
  (first (filter #(= (str "move:" call) (:id %)) (ready/ready (inbound/current-state sys)))))

(deftest a-move-is-ready-with-its-undo-and-the-client-can-undo-it
  (let [{:keys [sys root]} (world! *dir*)
        before (snapshot *dir*)
        from (str root "/Downloads/statement-oct.pdf")
        to (str root "/Bank/statement.pdf")
        _ (move sys [{:from from :to to}] ["contaminated" "mail:eve@example.net"])
        call (:seq (first (events sys "files/moving")))
        it (move-item sys call)
        wire (ready/wire it)]
    (testing "the item says what moved where, who moved it, and where the reason came from"
      (is (= [:move "statement-oct.pdf" call call] ((juxt :kind :title :undo :move) it)))
      (is (= ["1 file in root" "Downloads/statement-oct.pdf → Bank/statement.pdf"] (:lines it))
          "relative to the folder she granted, under its short name; no role named")
      (is (= "Drew on: a mail from eve@example.net" (get wire "from")))
      (is (= {"move" call "role" "steward" "undo" call "undone" false "seq" call}
             (select-keys wire ["move" "role" "undo" "undone" "seq"]))))
    (testing "relay ready names the command that undoes it"
      (let [out (:out (cli/execute ["--data" (:data-dir sys) "ready"]))]
        (is (some #(re-matches #"Moved: statement-oct\.pdf \(.*\)" %) out) "a file name is no link, so it is left as it is")
        (is (some #(str/ends-with? % "Bank/statement.pdf") out) "a move shows where it went, not cut")
        (is (not-any? #(str/includes? % (str *dir*)) out) "and never the full path")
        (is (some #{(str "  Undo: relay undo " call)} out))))
    (testing "undo is refused cleanly, with B9's reason, when the file changed"
      (spit to "edited by Kira")
      (let [answer (client-undo sys call)]
        (is (= -32000 (get-in answer ["error" "code"])))
        (is (re-find #"was not undone, and nothing changed: .*changed since it was moved" (get-in answer ["error" "message"]))))
      (is (= call (:undo (move-item sys call))) "it can still be undone")
      (spit to "bank october"))
    (testing "the client's undo is relay undo"
      (let [answer (client-undo sys call)]
        (is (re-find #"Undid move .*put back 1 of 1" (first (get-in answer ["result" "lines"]))) (pr-str answer))
        (is (= "Put back 1 file." (get-in answer ["result" "summary"]))))
      (is (= before (snapshot *dir*)))
      (let [it (move-item sys call)]
        (is (nil? (:undo it)))
        (is (:undone it))
        (is (= "statement-oct.pdf" (:title it)) "the kind says Moved back; the title does not say it twice")))
    (testing "once undone, a second undo is refused with the reason"
      (is (re-find #"already undone" (get-in (client-undo sys call) ["error" "message"]))))
    (testing "a move that does not exist"
      (is (re-find #"There is no move 9999" (get-in (client-undo sys 9999) ["error" "message"]))))))

(deftest a-move-relay-never-finished-is-ready-to-check
  (let [{:keys [sys root]} (world! *dir*)
        call (:seq (first (inbound/submit! sys {:type :files/start :actor "steward" :labels []
                                                 :args {:moves [{:from (str root "/a") :to (str root "/b")}]}})))
        it (move-item sys call)]
    (is (= call (:undo it)) "undo is offered, and lists the moves to check")
    (is (re-find #"check them by hand" (:title it)))
    (is (re-find #"Check them by hand" (get-in (client-undo sys call) ["error" "message"])))))

(deftest undo-params-are-checked
  (doseq [params [{} {:move "1"} {:move 0} {:move 1 :call 2}]]
    (let [[_ fx] (ht/say (ht/live) 1 (ht/req 9 "_relay/undo" params))]
      (is (= -32602 (get-in (first (ht/writes fx 1)) ["error" "code"])) (pr-str params))
      (is (empty? (filter #(= :undo (:effect %)) fx))))))


;; --- undo, move by move (U1e)

(defn- three-moves!
  "Moves three downloads into folders the call makes; the call's number."
  [sys root]
  (move sys [{:from (str root "/Downloads/confirmation.pdf") :to (str root "/Filed/confirmation.pdf")}
             {:from (str root "/Downloads/statement-oct.pdf") :to (str root "/Filed/statement.pdf")}
             {:from (str root "/Downloads/todo.txt") :to (str root "/Notes/todo/todo.txt")}])
  (:seq (last (events sys "files/moving"))))

(deftest undo-puts-back-every-move-it-can-and-names-the-rest
  (let [{:keys [sys root]} (world! *dir*)
        _ (spit (str root "/Downloads/todo.txt") "buy milk")
        before (snapshot *dir*)
        call (three-moves! sys root)]
    (spit (str root "/Notes/todo/todo.txt") "buy milk\nand eggs")
    (let [{:keys [ok refused summary]} (moves/undo! sys call)
          text (str/join "\n" ok)]
      (is (nil? refused))
      (is (= "Put back 2 of 3 files." summary))
      (is (re-find #"put back 2 of 3" (first ok)))
      (is (str/includes? text "Not put back:"))
      (is (str/includes? text "Notes/todo/todo.txt has changed since it was moved there.")
          "named relative to the folder she granted")
      (is (not (str/includes? text (str *dir*))) "never the full path")
      (is (str/includes? text "It came from Downloads/todo.txt; move it back there yourself to keep your edits.")
          "where it came from, and a next step that keeps her edit")
      (is (= "filed 2025" (slurp (str root "/Downloads/confirmation.pdf"))))
      (is (= "buy milk\nand eggs" (slurp (str root "/Notes/todo/todo.txt"))) "the edited file stays as she left it")
      (is (not (fs/exists? (str root "/Filed"))) "a folder the move made is removed once it is empty again"))
    (testing "once the file is as the move left it, undo puts back the rest"
      (spit (str root "/Notes/todo/todo.txt") "buy milk")
      (let [{:keys [ok]} (moves/undo! sys call)]
        (is (re-find #"put back 1 of 1.*Removed 2 empty folders" (first ok))))
      (is (= before (snapshot *dir*))))))

(deftest undo-takes-the-files-to-put-back-by-name
  (let [{:keys [sys root]} (world! *dir*)
        _ (spit (str root "/Downloads/todo.txt") "buy milk")
        call (three-moves! sys root)
        undo (fn [& args] (try (cli/execute (into ["--data" (:data-dir sys) "undo" (str call)] args))
                               (catch clojure.lang.ExceptionInfo e {:error (ex-message e)})))]
    (is (re-find #"did not move Downloads/nope.txt" (:error (undo "Downloads/nope.txt"))) "a name it did not move")
    (is (re-find #"no option --except" (:error (undo "--except" "Notes/todo/todo.txt"))) "an unknown option")
    (is (= 3 (count (core/move-steps-left (core/move-call (inbound/current-state sys) call)))) "and nothing moved")
    (let [r (undo "Notes/todo/todo.txt" (str root "/Filed/statement.pdf"))]
      (is (= 0 (:exit r)) (pr-str r))
      (is (re-find #"put back 2 of 2" (first (:out r)))))
    (is (= "buy milk" (slurp (str root "/Downloads/todo.txt"))))
    (is (= "bank october" (slurp (str root "/Downloads/statement-oct.pdf"))))
    (is (= "filed 2025" (slurp (str root "/Filed/confirmation.pdf"))) "the one not named stays")
    (is (not (fs/exists? (str root "/Notes"))) "the folders made for the files put back are gone")
    (is (fs/exists? (str root "/Filed")) "the one still holding a moved file stays")
    (is (re-find #"already back" (:error (undo "Notes/todo/todo.txt"))))
    (is (re-find #"put back 1 of 1" (first (:out (undo)))) "the rest")
    (is (not (fs/exists? (str root "/Filed"))))))

(deftest a-move-says-which-folders-it-left-empty
  (let [{:keys [sys root]} (world! *dir*)]
    (fs/create-dirs (str root "/old stuff"))
    (spit (str root "/old stuff/archive1.md") "old")
    (let [r (move sys [{:from (str root "/old stuff/archive1.md") :to (str root "/archive/archive1.md")}])
          call (:seq (last (events sys "files/moving")))]
      (is (str/includes? (:ok r) (str "These folders are empty now, and relay leaves them: " root "/old stuff")))
      (is (fs/exists? (str root "/old stuff")) "relay never removes it")
      (is (= [(str root "/old stuff")] (:emptied (core/move-call (inbound/current-state sys) call))))
      (is (some #{"Left empty: old stuff"} (:lines (move-item sys call))) "the operator sees it, relative")
      (is (some #{"  Left empty: old stuff"} (:out (cli/execute ["--data" (:data-dir sys) "moves"]))))
      (testing "undo fills it again, so it is no longer said"
        (is (:ok (moves/undo! sys call)))
        (is (not-any? #(str/starts-with? % "Left empty") (:lines (move-item sys call))))))
    (testing "a granted folder is never called empty"
      (let [r (move sys [{:from (str root "/Downloads/confirmation.pdf") :to (str root "/c.pdf")}
                         {:from (str root "/Downloads/statement-oct.pdf") :to (str root "/s.pdf")}])]
        (is (str/includes? (:ok r) (str root "/Downloads")) "Downloads is a folder inside the granted one")
        (is (not (str/includes? (:ok r) (str root ","))))))))

(deftest the-role-hears-when-the-operator-undoes-its-move
  (let [{:keys [sys root]} (world! *dir*)
        _ (move sys [{:from (str root "/Downloads/confirmation.pdf") :to (str root "/Taxes/c.pdf")}] ["mail:eve@example.net"])
        call (:seq (last (events sys "files/moving")))]
    (is (empty? (events sys "memo/sent")) "moving tells nobody")
    (moves/undo! sys call)
    (let [[m & more] (events sys "memo/sent")]
      (is (nil? more) "one memo")
      (is (= {:from "dispatcher" :to "steward"} (select-keys (:data m) [:from :to])))
      (is (= (str "The operator undid move " call) (get-in m [:data :subject])))
      (is (str/includes? (get-in m [:data :body]) (str root "/Taxes/c.pdf → " root "/Downloads/confirmation.pdf")))
      (is (str/includes? (get-in m [:data :body]) "files_moves"))
      (is (every? (set (:labels m)) ["private" "mail:eve@example.net"]) "it names her files, as the move did"))
    (testing "files_moves shows the role its record, put back marked"
      (let [r (tools/call! sys "steward" "files_move" {:moves [{:from (str root "/Downloads/statement-oct.pdf") :to (str root "/s.pdf")}]} [] "c2" nil)
            _ (is (nil? (:error r)))
            {:keys [ok labels]} (tools/call! sys "steward" "files_moves" {} [] "c3" nil)]
        (is (str/includes? ok (str "Move " call)))
        (is (str/includes? ok "all put back by the operator"))
        (is (str/includes? ok (str root "/Downloads/confirmation.pdf → " root "/Taxes/c.pdf (put back)")))
        (is (str/includes? ok (str root "/Downloads/statement-oct.pdf → " root "/s.pdf")))
        (is (every? (set labels) ["private" "mail:eve@example.net"]))))
    (testing "only a role granted folders has it, and it shows only its own"
      (is (contains? (tools/offered (:config sys) "steward") "files_moves"))
      (is (not (contains? (tools/offered (:config sys) "clerk") "files_moves"))))))

(deftest a-refused-undo-names-files-relative-to-the-folder
  (let [{:keys [sys root]} (world! *dir*)
        _ (move sys [{:from (str root "/Downloads/statement-oct.pdf") :to (str root "/Bank/statement.pdf")}])
        call (:seq (last (events sys "files/moving")))]
    (spit (str root "/Bank/statement.pdf") "edited")
    (let [msg (get-in (client-undo sys call) ["error" "message"])]
      (is (str/includes? msg "Bank/statement.pdf has changed since it was moved there."))
      (is (not (str/includes? msg (str *dir*)))))
    (is (empty? (events sys "memo/sent")) "nothing put back, so nothing to tell")))
