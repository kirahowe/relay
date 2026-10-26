(ns relay.shell-test
  "The steward's shell (relay.shell): what relay.edn may say about it, the
  argument vectors relay composes for the runner (I1), what may never be
  mounted (I7), where its output's labels come from, and its life, run
  against a stand-in runner. The live tests against Apple container are in
  shell-live-test."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cli :as cli]
            [relay.config :as config]
            [relay.dispatch :as dispatch]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.shell :as shell]
            [relay.tools :as tools]))

(def base
  {:timezone "UTC"
   :default-harness :agentloop
   :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}}
   :notify {:kind :none}
   :tools {"web" {:class :egress :labels ["contaminated"] :description "Search." :command ["true"]}}
   :house {:mounts ["/Users/someone/notes"]}
   :grants {"hub" {:shell true} "scout" {:public true :tools ["web"]}}})

(defn- problem [settings]
  (try (config/resolve-config settings) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

;; --- relay.edn

(deftest the-house-section-is-checked
  (is (nil? (problem base)))
  (is (nil? (problem (assoc base :house {}))) "every setting has a default")
  (doseq [[house why] [[[] #"is a map"]
                       [{:mount ["/a"]} #"takes"]
                       [{:runner "container"} #"container program"]
                       [{:runner []} #"container program"]
                       [{:runner ["docker" "--network=host"]} #"names a network"]
                       [{:mounts "/a"} #"vector"]
                       [{:mounts ["notes"]} #"not absolute"]
                       [{:mounts ["/"]} #"whole disk"]
                       [{:mounts ["/a/../b"]} #"\.\."]
                       [{:mounts ["/a/"]} #"plainly"]
                       [{:mounts ["/a:/etc"]} #"colon"]
                       [{:mounts ["/a,b"]} #"comma"]
                       [{:mounts ["/etc/hosts"]} #"system"]
                       [{:mounts ["/scratch"]} #"scratch"]
                       [{:mounts ["/a" "/a"]} #"twice"]
                       [{:image "Not An Image"} #"image name"]
                       [{:timeout-seconds 0} #"timeout"]
                       [{:timeout-seconds 7200} #"timeout"]
                       [{:output-limit -1} #"output-limit"]
                       [{:cpus 0} #"cpus"]
                       [{:memory "2 gigs"} #"memory"]]]
    (is (re-find why (str (problem (assoc base :house house)))) (pr-str house))))

(deftest only-a-house-role-has-the-shell
  (is (re-find #"public, so it may not have :shell"
               (problem (assoc-in base [:grants "scout" :shell] true))))
  (is (re-find #"needs a :house section" (problem (dissoc base :house))))
  (is (re-find #"true or false" (problem (assoc-in base [:grants "hub" :shell] "yes"))))
  (is (re-find #"script harness"
               (problem (assoc base :harnesses {:s {:kind :script :command ["true"]}} :default-harness :s
                               :grants {"hub" {:shell true}}))))
  (testing "a house without anyone granted the shell is fine"
    (is (nil? (problem (assoc base :grants {}))))))

(deftest a-house-role-may-have-a-remote-tool-only-to-read-or-edit
  (let [remote (fn [class]
                 (-> base
                     (assoc :mcp-servers {"svc" {:url "https://mcp.example.com/mcp"}})
                     (assoc-in [:tools "svc_do"] {:protocol :mcp-http :server "svc" :tool "do" :class class :labels []})
                     (assoc-in [:grants "hub" :tools] ["svc_do"])))]
    (is (nil? (problem (remote :read))))
    (is (nil? (problem (remote :edit))))
    (is (re-find #"only as :read or :edit" (str (problem (remote :write)))))
    (is (re-find #"egress" (str (problem (remote :egress)))))))

(deftest load-config-refuses-a-mount-of-the-data-directory
  (let [dir (str (fs/create-temp-dir {:prefix "relay-shell-cfg"}))
        data (str dir "/data")]
    (try
      (log/init! data)
      (doseq [[m ok?] [[(str dir "/notes") true]
                       [data false]
                       [(str data "/log") false]
                       [dir false]]]
        (spit (str data "/relay.edn") (pr-str (assoc base :house {:mounts [(str (fs/real-path dir) (subs m (count dir)))]})))
        (let [r (try (config/load-config data) nil (catch clojure.lang.ExceptionInfo e (ex-message e)))]
          (if ok? (is (nil? r) m) (is (re-find #"data directory" (str r)) m))))
      (finally (fs/delete-tree dir)))))

;; --- what may be mounted

(def home "/Users/someone")
(def data-dir "/Users/someone/Sync/relay")
(def where {:home home :data-dir data-dir})

(deftest secrets-are-never-mounted
  (doseq [m ["/Users/someone" "/Users" "/Users/someone/.ssh" "/Users/someone/.ssh/id_ed25519"
             "/Users/someone/Library" "/Users/someone/Library/Keychains/login.keychain-db"
             "/Users/someone/.codex" "/Users/someone/.codex-work/x" "/Users/someone/.claude-kira"
             "/Users/someone/.config" "/Users/someone/.config/relay/config.edn"
             "/Users/someone/Sync" "/Users/someone/Sync/relay" "/Users/someone/Sync/relay/log"
             "/Users/someone/Library/Application Support/Firefox/Profiles"
             ;; macOS firmlinks: the same files, under the data volume.
             "/System/Volumes/Data" "/System/Volumes/Data/Users" "/System/Volumes/Data/Users/someone"
             "/System/Volumes/Data/Users/someone/.ssh" "/System/Volumes/Data/Users/someone/.codex-work"
             "/System/Volumes/Data/Users/someone/Sync/relay/log"]]
    (is (shell/mount-problem m where) m))
  (doseq [m ["/Users/someone/notes" "/Users/someone/Documents/grants" "/Users/someone/Sync/other"
             "/Users/someone/.config/nvim" "/Volumes/Archive/photos" "/Users/someone/code/codex-notes"]]
    (is (nil? (shell/mount-problem m where)) m)))

(def segment (gen/elements ["Users" "someone" "notes" ".ssh" ".codex" ".codexx" "Library" "Keychains"
                            "Sync" "relay" "log" "Documents" ".config" "Application Support" "Chrome"]))

(defn- shows?
  "Whether mounting m would show f, hold it or sit inside it, comparing
  path segments rather than strings. f may end in * as in shell/forbidden."
  [m f]
  (let [segs #(vec (remove str/blank? (str/split % #"/")))
        ms (segs m)]
    (if (str/ends-with? f "*")
      (let [fs* (segs (subs f 0 (dec (count f))))
            dir (pop fs*)
            stem (peek fs*)]
        (or (and (<= (count ms) (count dir)) (= ms (subvec dir 0 (count ms))))
            (and (> (count ms) (count dir)) (= (subvec ms 0 (count dir)) dir)
                 (str/starts-with? (nth ms (count dir)) stem))))
      (let [fs* (segs f) n (min (count ms) (count fs*))]
        (= (subvec ms 0 n) (subvec fs* 0 n))))))

(defspec a-mount-that-passes-shows-no-secret 300
  (prop/for-all [segs (gen/vector segment 1 6)]
    (let [m (str "/" (str/join "/" segs))]
      (or (some? (shell/mount-problem m where))
          (not-any? #(shows? m %) (shell/forbidden home data-dir))))))

;; --- the runner's arguments (I1)

(def gen-mount
  (gen/fmap #(str "/Users/someone/" (str/join "/" %))
            (gen/vector (gen/elements ["notes" "docs" "a b" "Grants 2026" "x.y" "é"]) 1 3)))

(def gen-house
  (gen/let [runner (gen/elements [["container"] ["docker"] ["podman" "--remote"] ["/usr/local/bin/container"]])
            mounts (gen/fmap (comp vec distinct) (gen/vector gen-mount 0 4))
            cpus (gen/one-of [(gen/return nil) (gen/choose 1 8)])
            memory (gen/elements [nil "512M" "2G"])
            image (gen/elements [nil "relay-shell:latest" "example/shell:1"])]
    (cond-> {:runner runner :mounts mounts}
      cpus (assoc :cpus cpus)
      memory (assoc :memory memory)
      image (assoc :image image))))

(defn- pairs [argv] (map vector argv (rest argv)))

(defspec the-shell-always-runs-with-no-network-and-read-only-mounts 300
  (prop/for-all [house gen-house
                 dir (gen/fmap #(str "/data/" %) gen/string-alphanumeric)]
    (let [names (shell/names house dir)
          mounts (mapv (fn [m] [m m]) (:mounts house))
          argv (shell/run-argv house names mounts)
          after-run (drop (inc (count (:runner house))) argv)
          volumes (keep (fn [[a b]] (when (= "--volume" a) b)) (pairs argv))]
      (and (nil? (shell/settings-problem house))
           (= (:runner house) (take (count (:runner house)) argv))
           (= "run" (nth argv (count (:runner house))))
           (= 1 (count (filter #{"--network"} argv)))
           (some #{["--network" "none"]} (pairs argv))
           (some #{"--read-only"} argv)
           (not-any? #(re-find #"^(-p|--publish|--publish-socket|--mount|--ssh|--privileged|--cap-add|--virtualization|--net=|--network=|--dns)" %)
                     after-run)
           (= (count volumes) (count (:mounts house)))
           (every? #(str/ends-with? % ":ro") volumes)
           (= (set (map #(str % ":" % ":ro") (:mounts house))) (set volumes))
           (= ["sleep" "infinity"] (take-last 2 argv))))))

(def gen-data-path
  (gen/fmap #(str "/" (str/join "/" %))
            (gen/vector (gen/elements ["x" "y" "Users" "kira" "Sync" "relay" "data" "a b"]) 1 4)))

(defspec names-come-from-the-data-directory 300
  (prop/for-all [a gen-data-path b gen-data-path]
    (let [na (shell/names {} a) nb (shell/names {} b)]
      (and (= na (shell/names {} a))
           (= (= a b) (= na nb))
           (re-matches #"relay-shell-[0-9a-f]{12}" (:container na))
           (re-matches shell/image-pattern (:image na))))))

(deftest data-directories-with-the-same-name-do-not-share-a-shell
  (is (not= (shell/names {} "/x/relay") (shell/names {} "/y/relay"))))

(deftest a-command-runs-as-the-steward-with-a-timeout-inside
  (let [argv (shell/exec-argv {:runner ["container"]} {:container "c"} 60 "ls -la")]
    (is (= ["container" "exec" "--interactive" "--user" "steward" "--workdir" "/scratch" "c"
            "timeout" "-k" "5" "60" "sh" "-c" "ls -la"]
           argv))))

;; --- what the steward reads

(deftest results-say-how-a-command-ended
  (let [h {:timeout-seconds 60 :output-limit 100}]
    (is (= "hi\n" (shell/result-text {:exit 0 :out "hi\n" :err ""} h)))
    (is (= "(no output)" (shell/result-text {:exit 0 :out "" :err ""} h)))
    (is (= "stderr:\nnope\nExit status 2." (shell/result-text {:exit 2 :out "" :err "nope"} h)))
    (is (str/includes? (shell/result-text {:exit 0 :out "x" :err "" :out-cut true} h) "[cut: more than 100 bytes]"))
    (is (str/includes? (shell/result-text {:exit 124 :out "" :err "" :timed-out true} h)
                       "ran out of time after 60 seconds"))))

(defspec every-result-is-private-and-keeps-what-scratch-holds 100
  (prop/for-all [ls (gen/vector (gen/elements ["private" "contaminated"]))]
    (let [r (shell/result-labels ls)]
      (and (some #{"private"} r) (every? (set r) ls) (= r (vec (sort (distinct r))))))))

;; --- the shell's life, against a stand-in runner

(def ^:dynamic *dir* nil)

(use-fixtures :each
  (fn [run-test]
    (let [dir (str (fs/real-path (fs/create-temp-dir {:prefix "relay-shell"})))]
      (try (binding [*dir* dir] (run-test))
           (finally (fs/delete-tree dir))))))

(defn- runner!
  "A stand-in for the container program: it logs each call, keeps whether
  the container runs in a file, and runs an exec's command with the host's
  sh in a scratch directory."
  []
  (let [s (str *dir* "/runner")
        path (str *dir* "/fake-container")]
    (fs/create-dirs (str s "/scratch"))
    (spit (str s "/image") "")
    (spit path (str "#!/bin/bash\nS=" s "\n"
                    "printf '%s\\n' \"$*\" >> $S/calls\n"
                    "case \"$1\" in\n"
                    "  image) [ -e $S/image ] && exit 0; echo 'Error: image not found' >&2; exit 1;;\n"
                    "  build) touch $S/image; exit 0;;\n"
                    "  rm) rm -f $S/running; exit 0;;\n"
                    "  run) [ -e $S/fail-run ] && { echo 'Error: it broke' >&2; exit 1; }; touch $S/running; exit 0;;\n"
                    "  exec) [ -e $S/running ] || { echo 'Error: container not found' >&2; exit 1; }\n"
                    "        [ \"$3\" = true ] && exit 0\n"
                    "        while [ \"$1\" != -c ]; do shift; done; shift\n"
                    "        cd $S/scratch && exec sh -c \"$1\";;\n"
                    "esac\nexit 3\n"))
    (fs/set-posix-file-permissions path "rwxr-xr-x")
    path))

(defn- calls [] (let [f (str *dir* "/runner/calls")] (if (fs/exists? f) (str/split-lines (slurp f)) [])))
(defn- clear-calls! [] (fs/delete-if-exists (str *dir* "/runner/calls")))
(defn- runs [] (filter #(str/starts-with? % "run ") (calls)))

(defn- data!
  "A data directory whose relay.edn has a :house run by the stand-in, a
  notes directory to mount, and a hub granted the shell."
  [house]
  (let [data (str *dir* "/data")
        notes (str *dir* "/notes")]
    (fs/create-dirs notes)
    (spit (str notes "/todo.txt") "buy oat milk\n")
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str (assoc base :house (merge {:runner [(runner!)] :mounts [notes]} house))))
    (let [sys (inbound/system data)]
      (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "hub" :title "Hub" :instructions "x"}})
      sys)))

(defn- ctx [sys] (shell/context (:data-dir sys) (:config sys)))

(defn- sh [sys labels command]
  (tools/call! sys "hub" "sh" {:command command} labels "c1" nil))

(deftest up-starts-the-shell-once-and-down-removes-it
  (let [sys (data! {})]
    (is (= :started (shell/up! (ctx sys))))
    (is (= :running (shell/up! (ctx sys))))
    (is (= 1 (count (runs))))
    (is (str/includes? (first (runs)) "--network none"))
    (is (str/includes? (first (runs)) (str *dir* "/notes:" *dir* "/notes:ro")))
    (is (= {:running true :current true :image-present true} (shell/status (ctx sys))))
    (is (= :stopped (shell/down! (ctx sys))))
    (is (= :absent (shell/down! (ctx sys))))
    (is (false? (:running (shell/status (ctx sys)))))))

(deftest new-settings-restart-the-shell
  (let [sys (data! {})]
    (shell/up! (ctx sys))
    (let [sys2 (assoc sys :config (assoc-in (:config sys) [:house :timeout-seconds] 5))
          sys3 (assoc sys :config (assoc-in (:config sys) [:house :memory] "1G"))]
      (is (= :running (shell/up! (ctx sys2))) "the timeout is not how it runs")
      (is (false? (:current (shell/status (ctx sys3)))))
      (is (= :started (shell/up! (ctx sys3))))
      (is (= 2 (count (runs)))))))

(deftest the-shell-needs-its-image-and-says-why-it-will-not-start
  (let [sys (data! {})]
    (fs/delete (str *dir* "/runner/image"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not built\. Run relay house build" (shell/up! (ctx sys))))
    (is (= {:restarted false} (shell/build! (ctx sys))))
    (spit (str *dir* "/runner/fail-run") "")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"would not start: Error: it broke" (shell/up! (ctx sys))))))

(deftest a-mount-must-exist-and-may-not-lead-to-the-data-directory
  (let [sys (data! {})
        link (str *dir* "/sneaky")]
    (fs/create-sym-link link (str *dir* "/data"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not exist"
                          (ctx (assoc-in sys [:config :house :mounts] [(str *dir* "/missing")]))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"data directory"
                          (ctx (assoc-in sys [:config :house :mounts] [link]))))))

(deftest the-hub-runs-a-command-and-reads-what-it-printed
  (let [sys (data! {})]
    (testing "the shell starts on the first call"
      (is (= {:ok "buy oat milk\n" :labels ["private"]}
             (sh sys ["private"] (str "cat " *dir* "/notes/todo.txt")))))
    (is (= "stderr:\nno\nExit status 3." (:ok (sh sys [] "echo no >&2; exit 3"))))
    (testing "only a role granted the shell has it"
      (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title "C" :instructions "x"}})
      (is (= "You have no tool called sh." (:error (tools/call! sys "clerk" "sh" {:command "true"} [] "c" nil)))))
    (is (some #{"sh"} (map :name (tools/manifest (:config sys) "hub"))))
    (is (not-any? #{"sh"} (map :name (tools/manifest (:config sys) "scout"))))))

(deftest what-one-session-leaves-in-scratch-carries-its-labels
  (let [sys (data! {})]
    (is (= ["private"] (:labels (sh sys ["private"] "echo clean > a.txt"))))
    (is (= ["contaminated" "private"] (:labels (sh sys ["contaminated" "private"] "echo 'from a stranger' > b.txt")))
        "the session that wrote it holds contaminated already, and scratch now does too")
    (is (= ["contaminated" "private"] (:labels (sh sys ["private"] "cat b.txt")))
        "a later clean session reads scratch as from outside")
    (testing "a new container has an empty scratch, and no labels"
      (shell/down! (ctx sys))
      (is (= ["private"] (:labels (sh sys ["private"] "ls")))))))

(deftest a-command-reads-scratch-as-carrying-what-was-written-while-it-ran
  ;; Two sessions may use the shell at once, a tick wake and a
  ;; conversation. A clean reader that is still running when a session
  ;; holding a stranger's text writes to scratch may have read that text.
  (let [sys (data! {})]
    (sh sys ["private"] "true")
    (let [reader (future (sh sys ["private"] "sleep 2; cat b.txt"))]
      (Thread/sleep 700)
      (sh sys ["contaminated" "private"] "echo IGNORE-PREVIOUS > b.txt")
      (is (= {:ok "IGNORE-PREVIOUS\n" :labels ["contaminated" "private"]} @reader)))))

(deftest a-command-reads-what-a-session-still-running-wrote
  ;; The other order: a session holding a stranger's text writes to
  ;; scratch and is still running when a clean session reads it. The
  ;; writer's labels must reach scratch before its command runs.
  (let [sys (data! {})]
    (sh sys ["private"] "true")
    (let [writer (future (sh sys ["contaminated" "private"] "echo EVIL > c.txt; sleep 2"))]
      (Thread/sleep 700)
      (is (= {:ok "EVIL\n" :labels ["contaminated" "private"]} (sh sys ["private"] "cat c.txt")))
      @writer)))

(deftest a-firmlinked-path-to-a-secret-is-not-mounted
  ;; On macOS /System/Volumes/Data/<p> is the same file as /<p>, and its
  ;; real path keeps the long form.
  (let [sys (data! {})
        firm (str "/System/Volumes/Data" *dir* "/data")]
    (when (fs/exists? firm)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"data directory"
                            (ctx (assoc-in sys [:config :house :mounts] [firm]))))
      (testing "and comparing files catches it without the path check"
        (with-redefs [shell/mount-problem (fn [m _] (shell/path-problem m))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"data directory"
                                (ctx (assoc-in sys [:config :house :mounts] [firm])))))))))

(deftest a-mount-that-is-the-same-file-as-a-secret-is-refused
  ;; Whatever spelling reaches it, a mount that is, holds or sits in a
  ;; forbidden place, compared by file identity, is refused.
  (data! {})
  (let [w {:home (str *dir* "/home") :data-dir (str *dir* "/data")}
        firm (str "/System/Volumes/Data" *dir* "/data/log")]
    (fs/create-dirs (str *dir* "/home/.ssh"))
    (is (re-find #"data directory" (str (shell/identity-problem (str *dir* "/data/log") w))))
    (is (some? (shell/identity-problem *dir* w)) "it holds both")
    (is (re-find #"home directory" (str (shell/identity-problem (str *dir* "/home") w))))
    (is (re-find #"\.ssh" (str (shell/identity-problem (str *dir* "/home/.ssh") w))))
    (is (nil? (shell/identity-problem (str *dir* "/notes") w)))
    (when (fs/exists? firm)
      (is (re-find #"data directory" (str (shell/identity-problem firm w)))))))

(deftest a-shell-that-went-away-is-started-again
  (let [sys (data! {})]
    (sh sys [] "true")
    (fs/delete (str *dir* "/runner/running"))
    (clear-calls!)
    (is (= "back\n" (:ok (sh sys [] "echo back"))))
    (is (= 1 (count (runs))))))

(deftest output-past-the-limit-is-cut
  (let [sys (data! {:output-limit 1000})
        r (sh sys [] "yes | head -c 100000")]
    (is (str/ends-with? (:ok r) "[cut: more than 1000 bytes]"))
    (is (< (count (:ok r)) 1100))))

(deftest the-tick-starts-the-shell
  (let [sys (data! {})]
    (dispatch/tick! sys {:spawn! (fn [& _])})
    (is (= 1 (count (runs))))
    (dispatch/tick! sys {:spawn! (fn [& _])})
    (is (= 1 (count (runs))) "and leaves a running one be")
    (testing "a shell that will not start stops nothing else"
      (shell/down! (ctx sys))
      (fs/delete (str *dir* "/runner/image"))
      (let [err (java.io.StringWriter.)]
        (binding [*err* err] (dispatch/tick! sys {:spawn! (fn [& _])}))
        (is (str/includes? (str err) "the shell is not running"))))))

(deftest the-operator-runs-the-shell-from-the-command-line
  (let [sys (data! {})
        relay #(cli/execute (concat ["--data" (:data-dir sys) "house"] %&))]
    (is (= ["The shell is not running. relay house up starts it."] (:out (relay "status"))))
    (is (= ["Started the shell."] (:out (relay "up"))))
    (is (= ["The shell is already running."] (:out (relay "up"))))
    (is (= ["The shell is running."] (:out (relay "status"))))
    (is (= ["Built the shell's image, and restarted the shell with it."] (:out (relay "build"))))
    (is (= ["Stopped the shell. Its scratch files are gone."] (:out (relay "down"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"usage: relay house" (relay "sideways")))))

;; --- I1 over any relay.edn

(def gen-settings
  (gen/let [classes (gen/vector (gen/elements [:read :write :edit :egress]) 1 5)
            remote (gen/vector gen/boolean (count classes))
            roles (gen/vector (gen/tuple (gen/elements ["hub" "scribe" "scout" "clerk"])
                                         gen/boolean gen/boolean
                                         (gen/vector (gen/choose 0 (dec (count classes))) 0 3))
                              0 4)
            house? gen/boolean
            house gen-house]
    (let [tool-name #(str "t" %)
          tools (into {} (map-indexed
                          (fn [i c]
                            [(tool-name i)
                             (if (nth remote i)
                               {:protocol :mcp-http :server "svc" :tool (str "x" i) :class c :labels []}
                               {:class c :labels [] :description "d" :command ["true"]})])
                          classes))]
      (cond-> (assoc base
                     :tools tools
                     :grants (into {} (for [[r public shell ts] roles]
                                        [r (cond-> {:tools (vec (distinct (map tool-name ts)))}
                                             public (assoc :public true)
                                             shell (assoc :shell true))])))
        (some true? remote) (assoc :mcp-servers {"svc" {:url "https://mcp.example.com/mcp"}})
        true (dissoc :house)
        house? (assoc :house house)))))

(defspec no-session-for-a-house-role-has-a-way-out 300
  (prop/for-all [settings gen-settings]
    (let [c (try (config/resolve-config settings) (catch clojure.lang.ExceptionInfo _ nil))]
      (or (nil? c)
          (every? (fn [role]
                    (let [m (tools/manifest c role)]
                      (if (config/public? c role)
                        (not-any? #{"sh"} (map :name m))
                        (and (not-any? #(= "egress" (:class %)) m)
                             (every? #(#{"read" "edit"} (:class %)) (filter #(= :mcp-http (get-in % [:mcp :protocol])) m))
                             (= (boolean (and (:house c) (:shell (config/grants c role))))
                                (boolean (some #{"sh"} (map :name m))))
                             (or (not (:house c))
                                 (some #{["--network" "none"]}
                                       (pairs (shell/run-argv (:house c) (shell/names (:house c) "/d") []))))))))
                  (keys (:grants c)))))))

(deftest a-session-is-never-built-with-a-way-out-even-past-relay-edn
  ;; relay.edn refuses this; the manifest, where every session's tools come
  ;; from, refuses it again.
  (let [c (assoc-in (config/resolve-config base) [:grants "hub" :tools] ["web"])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"will not start a session for hub with web"
                          (tools/manifest c "hub")))
    (is (= ["web"] (keep #(when (= "egress" (:class %)) (:name %)) (tools/manifest c "scout")))))
  (testing "a remote tool that could do more than read or edit"
    (let [c (-> (config/resolve-config base)
                (assoc :mcp-servers {"svc" {:url "https://mcp.example.com/mcp"}})
                (assoc-in [:tools "svc_do"] {:protocol :mcp-http :server "svc" :tool "do" :class :write :labels []})
                (assoc-in [:grants "hub" :tools] ["svc_do"])
                (assoc-in [:grants "scout" :tools] ["web" "svc_do"]))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"will not start a session for hub with svc_do"
                            (tools/manifest c "hub")))
      (is (some #{"svc_do"} (map :name (tools/manifest c "scout")))))))

(defspec the-manifest-alone-keeps-a-house-role-in 300
  ;; The same as no-session-for-a-house-role-has-a-way-out, without
  ;; relay.edn's own checks in front.
  (prop/for-all [settings gen-settings]
    (let [c (merge config/defaults settings)]
      (every? (fn [role]
                (or (config/public? c role)
                    (let [m (try (tools/manifest c role) (catch clojure.lang.ExceptionInfo _ ::refused))]
                      (or (= ::refused m)
                          (and (not-any? #(= "egress" (:class %)) m)
                               (every? #(#{"read" "edit"} (:class %))
                                       (filter #(= :mcp-http (get-in % [:mcp :protocol])) m)))))))
              (keys (:grants c))))))
