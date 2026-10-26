(ns relay.shell
  "The steward's shell: one Linux container per data directory, with no
  network, a read-only root, the operator's chosen files mounted read-only
  at their own paths, and a scratch directory in memory. A role granted
  :shell runs commands in it with the `sh` tool, and reads what they
  print. The model's own code runs there and nowhere else; the harness
  stays on the host (plan O3).

  The walls are structural. relay composes every argument the runner gets
  (run-argv): `--network none` is always there, every mount ends in `:ro`,
  and relay.edn can name only the runner program, the files, the image
  and limits. It cannot add an option, a port or a writable mount.

  relay.edn's :house section:

    {:runner [\"container\"]      ; the program, default Apple container;
                                  ; [\"docker\"] or [\"podman\"] on Linux
     :mounts [\"/Users/you/notes\"] ; read-only, at the same path inside
     :image \"...\"                ; default derived from the data directory
     :timeout-seconds 60          ; per command, enforced inside the VM
     :output-limit 32768          ; bytes kept of each of stdout and stderr
     :cpus 2 :memory \"2G\"}       ; given to the runner when set

  Names come from the data directory (names), so two data directories, or
  two tests, never share a container or an image.

  Provenance: what a command prints shows the operator's files, so it is
  private. The scratch directory outlives a session, so whatever a session
  could have left there carries over: relay keeps the union of the labels
  of every session that ran a command since the container started, and
  every result carries it. A session that read a stranger's mail and wrote
  to /scratch cannot pass that text to a later session as clean."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [relay.durable :as durable])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.nio.channels FileChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file OpenOption StandardOpenOption)
           (java.security MessageDigest)))

;; --- settings

(def defaults
  {:runner ["container"]
   :mounts []
   :timeout-seconds 60
   :output-limit 32768})

(def max-timeout-seconds 3600)
(def max-output-limit 1000000)

(def scratch "/scratch")
(def user "steward")

(def setting-keys #{:runner :mounts :image :timeout-seconds :output-limit :cpus :memory})

(def image-pattern #"[a-z0-9][a-z0-9._/-]{0,127}(:[A-Za-z0-9_][A-Za-z0-9._-]{0,127})?")

(defn- argv? [x]
  (and (vector? x) (seq x)
       (every? #(and (string? %) (not (str/blank? %)) (not (re-find #"[\u0000\n\r]" %))) x)))

(defn path-problem
  "Why `p` cannot be a mount as written, or nil: it must be an absolute,
  normal path, other than /, that the runner's -v syntax can carry."
  [p]
  (cond
    (not (and (string? p) (not (str/blank? p)))) "it is not a path"
    (not (str/starts-with? p "/")) "it is not absolute"
    (= "/" p) "it is the whole disk"
    (re-find #"[:,\u0000-\u001f\u007f]" p) "it has a colon, a comma or a control character"
    (or (str/ends-with? p "/") (str/includes? p "//")) "it is not written plainly (a trailing or double slash)"
    (some #{"." ".."} (str/split p #"/")) "it has . or .. in it"
    (= p scratch) "it is the shell's own scratch directory"
    (some #(or (= p %) (str/starts-with? p (str % "/"))) ["/proc" "/sys" "/dev" "/tmp" "/scratch" "/etc" "/bin" "/sbin" "/usr" "/lib"])
    "it would cover part of the shell's own system"))

(defn settings-problem
  "What is wrong with relay.edn's :house, as a sentence, or nil."
  [house]
  (let [where "relay.edn's :house"
        {:keys [runner mounts image timeout-seconds output-limit cpus memory]} (when (map? house) house)]
    (cond
      (not (map? house))
      (str where " is a map, such as {:mounts [\"/Users/you/notes\"]}, not " (pr-str house) ".")
      (seq (remove setting-keys (keys house)))
      (str where " takes " (str/join ", " (map str (sort setting-keys))) ", and nothing else; not "
           (str/join ", " (map pr-str (sort-by str (remove setting-keys (keys house))))) ".")
      (and (contains? house :runner) (not (argv? runner)))
      (str where " has :runner " (pr-str runner) "; it is the container program, such as [\"container\"] or [\"docker\"].")
      ;; relay always gives --network none itself; a runner that names a
      ;; network could only be a mistake.
      (some #(re-find #"(?i)net" %) runner)
      (str where " has :runner " (pr-str runner) ", which names a network. relay runs the shell with no"
           " network and composes its options itself; :runner is only the program.")
      (and (contains? house :mounts) (not (vector? mounts)))
      (str where " has :mounts " (pr-str mounts) "; it is a vector of absolute paths.")
      (some #(path-problem %) mounts)
      (let [m (first (filter path-problem mounts))]
        (str where " cannot mount " (pr-str m) ": " (path-problem m) "."))
      (and (seq mounts) (not (apply distinct? mounts)))
      (str where " names a path twice in :mounts.")
      (and (contains? house :image) (not (and (string? image) (re-matches image-pattern image))))
      (str where " has :image " (pr-str image) ", which is not an image name such as \"relay-shell:latest\".")
      (and (contains? house :timeout-seconds)
           (not (and (pos-int? timeout-seconds) (<= timeout-seconds max-timeout-seconds))))
      (str where " has :timeout-seconds " (pr-str timeout-seconds) "; it is a whole number from 1 to "
           max-timeout-seconds ".")
      (and (contains? house :output-limit)
           (not (and (pos-int? output-limit) (<= output-limit max-output-limit))))
      (str where " has :output-limit " (pr-str output-limit) "; it is a number of bytes from 1 to "
           max-output-limit ".")
      (and (contains? house :cpus) (not (and (pos-int? cpus) (<= cpus 64))))
      (str where " has :cpus " (pr-str cpus) "; it is a whole number from 1 to 64.")
      (and (contains? house :memory) (not (and (string? memory) (re-matches #"[1-9][0-9]{0,5}[KMG]" memory))))
      (str where " has :memory " (pr-str memory) "; it is an amount such as \"2G\" or \"512M\"."))))

(defn settings
  "relay.edn's :house with relay's defaults filled in, or nil when there
  is none."
  [config]
  (when-let [h (:house config)] (merge defaults h)))

;; --- what may not be mounted

(def ^:private home-secrets
  "Paths under the home directory that are never mounted, nor any path
  that holds one: where a user keeps keys, passwords, tokens and browser
  profiles, coding agents' settings and sessions, and relay's own
  settings. The same list agentloop's workbench refused to read. A name
  ending in * covers every name starting with it."
  ["Library/Keychains" ".ssh" ".gnupg" ".aws" ".netrc" ".git-credentials" ".docker" ".kube"
   ".npmrc" ".pypirc" ".password-store" ".config/gh" ".config/op" ".config/gcloud" ".azure"
   ".gem/credentials" ".cargo/credentials" ".cargo/credentials.toml" ".m2/settings.xml"
   ".terraform.d" ".kaggle" ".huggingface" ".cache/huggingface" ".codex*" ".claude*"
   "Library/Cookies" "Library/Safari" "Library/Containers/com.apple.Safari"
   "Library/Application Support/Google/Chrome" "Library/Application Support/Chromium"
   "Library/Application Support/BraveSoftware" "Library/Application Support/Microsoft Edge"
   "Library/Application Support/Arc" "Library/Application Support/Vivaldi"
   "Library/Application Support/com.operasoftware.Opera" "Library/Application Support/Firefox"
   "Library/Application Support/1Password" "Library/Application Support/Bitwarden"
   "Library/Group Containers/2BUA8C4S2C.com.1password"
   ".config/relay" ".relay" "Library/Application Support/relay"])

(defn forbidden
  "Every path no mount may be, hold or sit inside, for a home directory
  and a data directory: the secrets above, the data directory, and
  /Volumes/.timemachine. A path ending in * stands for every path that
  starts with what comes before it."
  [home data-dir]
  (concat (map #(str home "/" %) home-secrets)
          [data-dir "/Volumes/.timemachine"]))

(defn- under?
  "Whether path a is b or inside it."
  [a b]
  (or (= a b) (str/starts-with? a (str b "/"))))

(defn- parent-of [p]
  (let [i (str/last-index-of p "/")] (if (pos? i) (subs p 0 i) "/")))

(defn- covers?
  "Whether mounting `mount` would show any of `f`, or put the mount inside it."
  [mount f]
  (if (str/ends-with? f "*")
    (let [prefix (subs f 0 (dec (count f)))]
      (or (str/starts-with? mount prefix)
          (= "/" mount)
          (under? (parent-of prefix) mount)))
    (or (under? mount f) (under? f mount))))

(def ^:private firmlink-root
  "Where macOS keeps the data volume. Every firmlinked path, such as /Users
  or /private, is the same file under it, and a real path keeps whichever
  form it was given."
  "/System/Volumes/Data")

(defn- unfirm
  "`p` as the path it is the same file as, without the data volume prefix."
  [p]
  (cond (= p firmlink-root) "/"
        (and p (str/starts-with? p (str firmlink-root "/"))) (subs p (count firmlink-root))
        :else p))

(defn- forbidden-why [f data-dir]
  (if (= f data-dir)
    "it is, holds or sits in relay's own data directory"
    (str "it is, holds or sits in " f ", which holds secrets")))

(def ^:private home-why "it is or holds your whole home directory")

(defn place-problem
  "Why relay may not open the absolute, plainly written path `p` to a role,
  or nil: it is, holds or sits inside the data directory, the home
  directory itself, or one of the secrets. A path under
  /System/Volumes/Data is checked as the path it is the same file as.
  /System and /System/Volumes hold the data volume. The shell's mounts and
  the folders a role may organize (relay.files) both pass through it."
  [p {:keys [home data-dir]}]
  (let [home (unfirm home) data-dir (unfirm data-dir)]
    (or (when (and (not= p firmlink-root) (under? firmlink-root p))
          "it holds the whole disk")
        (let [m (unfirm p)]
          (or (when (= "/" m) "it is the whole disk")
              (when (and home (under? home m)) home-why)
              (when-let [f (first (filter #(covers? m %) (forbidden home data-dir)))]
                (forbidden-why f data-dir)))))))

(defn mount-problem
  "Why `mount` may not be mounted, or nil: path-problem, then
  place-problem. Mounting /System or /System/Volumes can also wedge the
  container so it can be neither stopped nor removed."
  [mount where]
  (or (path-problem mount) (place-problem mount where)))

(defn- ancestors-or-self [p]
  (take-while some? (iterate #(when-not (= "/" %) (parent-of %)) p)))

(defn- same-file? [a b]
  (try (java.nio.file.Files/isSameFile (fs/path a) (fs/path b))
       (catch java.io.IOException _ false)))

(defn identity-problem
  "Why the existing path `mount` may not be mounted, comparing files rather
  than spellings, or nil: it is, holds or sits in one of the forbidden
  places that exist (the wildcard ones aside, which mount-problem covers),
  or it is or holds the home directory. Catches what a path check cannot
  see, such as another name for the same directory."
  [mount {:keys [home data-dir]}]
  (let [mine (ancestors-or-self mount)
        inside? (fn [f] (some #(same-file? % f) mine))
        holds? (fn [f] (some #(same-file? mount %) (ancestors-or-self f)))]
    (or (when (and home (fs/exists? home) (holds? home)) home-why)
        (first (for [f (forbidden home data-dir)
                     :when (and (not (str/ends-with? f "*")) (fs/exists? f)
                                (or (inside? f) (holds? f)))]
                 (forbidden-why f data-dir))))))

(defn mounts-problem
  "The first mount in :house that may not be mounted, as a sentence, or nil."
  [house where]
  (first (for [m (:mounts house)
               :let [why (mount-problem m where)]
               :when why]
           (str "relay.edn's :house cannot mount " m ": " why "."))))

;; --- names and argument vectors, all pure

(defn- sha256-hex [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn names
  "The container's and the image's names for a data directory, given as
  its real absolute path. Derived from it, so data directories never share."
  [house real-data-dir]
  (let [h (subs (sha256-hex real-data-dir) 0 12)]
    {:id h
     :container (str "relay-shell-" h)
     :image (or (:image house) (str "relay-shell-" h ":latest"))}))

(defn run-argv
  "The command that starts the container. Every option is relay's:
  no network, a read-only root, scratch and /tmp in memory, and each
  mount read-only at its own path. `mounts` are [source target] pairs,
  source being the real path of target."
  [house {:keys [container image id]} mounts]
  (let [{:keys [runner cpus memory]} (merge defaults house)]
    (-> (vec runner)
        (into ["run" "--detach" "--name" container "--init"
               "--network" "none" "--read-only"
               "--tmpfs" scratch "--tmpfs" "/tmp"
               "--label" (str "relay.shell=" id)])
        (into (when cpus ["--cpus" (str cpus)]))
        (into (when memory ["--memory" memory]))
        (into (mapcat (fn [[source target]] ["--volume" (str source ":" target ":ro")]) mounts))
        (into [image "sleep" "infinity"]))))

(defn exec-argv
  "The command that runs `command` with sh in the container, as the
  unprivileged user, in the scratch directory, for at most `seconds`: the
  limit is enforced by timeout inside the VM, since ending the runner's
  client on the host does not end what it started there."
  [house {:keys [container]} seconds command]
  (into (vec (:runner (merge defaults house)))
        ["exec" "--interactive" "--user" user "--workdir" scratch container
         "timeout" "-k" "5" (str seconds) "sh" "-c" command]))

(defn probe-argv [house {:keys [container]}]
  (into (vec (:runner (merge defaults house))) ["exec" container "true"]))

(defn remove-argv [house {:keys [container]}]
  (into (vec (:runner (merge defaults house))) ["rm" "--force" container]))

(defn image-inspect-argv [house {:keys [image]}]
  (into (vec (:runner (merge defaults house))) ["image" "inspect" image]))

(defn build-argv [house {:keys [image]} containerfile context]
  (into (vec (:runner (merge defaults house))) ["build" "--tag" image "--file" containerfile context]))

(defn spec
  "A digest of how the container is run, so a change to :house restarts it."
  [run-argv]
  (sha256-hex (pr-str run-argv)))

;; --- what the steward reads back

(defn result-text
  "What the steward is told of one command: its output, what it said on
  stderr, and how it ended, each part only when there is something to say."
  [{:keys [exit out err out-cut err-cut timed-out]} {:keys [timeout-seconds output-limit]}]
  (let [cut (fn [s cut?] (cond-> s cut? (str "\n[cut: more than " output-limit " bytes]")))
        parts (cond-> []
                (seq out) (conj (cut out out-cut))
                (seq err) (conj (str "stderr:\n" (cut err err-cut)))
                timed-out (conj (str "The command ran out of time after " timeout-seconds " seconds and was stopped."))
                (and (not timed-out) (not (zero? exit))) (conj (str "Exit status " exit "."))
                (and (empty? out) (empty? err) (not timed-out) (zero? exit)) (conj "(no output)"))]
    ;; A part that already ends its line needs no newline after it.
    (reduce (fn [acc part] (if (or (empty? acc) (str/ends-with? acc "\n")) (str acc part) (str acc "\n" part)))
            "" parts)))

(defn result-labels
  "The labels of what a command printed: private, since it shows the
  operator's files, and whatever any session that used the shell since the
  container started could have left in scratch."
  [scratch-labels]
  (vec (sort (distinct (cons "private" scratch-labels)))))

;; --- the edges: running the runner

(defn- read-bounded
  "Reads all of `in`, keeping at most `limit` bytes. Returns [text cut?]."
  [^InputStream in limit]
  (let [kept (ByteArrayOutputStream.)
        buf (byte-array 8192)]
    (loop [total 0]
      (let [n (.read in buf)]
        (if (neg? n)
          [(String. (.toByteArray kept) StandardCharsets/UTF_8) (> total limit)]
          (do (when (< (.size kept) limit)
                (.write kept buf 0 (int (min n (- limit (.size kept))))))
              (recur (+ total n))))))))

(defn- program [argv]
  (let [prog (first argv)]
    (or (if (str/includes? prog "/")
          (when (fs/executable? prog) (str prog))
          (some-> (fs/which prog {:paths ["/opt/homebrew/bin" "/usr/local/bin" "/usr/bin" "/bin"
                                           "/usr/sbin" "/sbin"]})
                  str))
        (throw (ex-info (str "relay cannot find " prog ", the program that runs the shell (:house :runner).")
                        {:relay/usage true})))))

(defn run-runner!
  "Runs `argv` with empty stdin and a minimal environment, for at most `ms`.
  Returns {:exit :out :err :out-cut :err-cut :timed-out :ms}, keeping at
  most `limit` bytes of each stream."
  [argv {:keys [ms limit] :or {ms 120000 limit 1000000}}]
  (let [t0 (System/currentTimeMillis)
        proc (p/process (into [(program argv)] (rest argv))
                        {:in "" :out :stream :err :stream
                         :env {"PATH" "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
                               "HOME" (System/getProperty "user.home")}})
        out (future (read-bounded (:out proc) limit))
        err (future (read-bounded (:err proc) limit))
        exited (deref proc ms ::timeout)]
    (if (= ::timeout exited)
      (do (p/destroy-tree proc)
          {:exit -1 :out "" :err "" :timed-out true :ms (- (System/currentTimeMillis) t0)})
      (let [[o oc] (deref out 5000 ["" false])
            [e ec] (deref err 5000 ["" false])]
        {:exit (:exit exited) :out o :err e :out-cut oc :err-cut ec :timed-out false
         :ms (- (System/currentTimeMillis) t0)}))))

;; --- the state relay keeps beside the container

(defn- state-dir [data-dir] (fs/path data-dir "shell"))
(defn- state-file [data-dir] (fs/path (state-dir data-dir) "container.edn"))

(def ^:private jvm-lock (Object.))

(defn- with-lock
  "Runs f with the data directory's shell lock held, so that two processes
  never start, stop or relabel the container at once."
  [data-dir f]
  (locking jvm-lock
    (let [file (fs/path (state-dir data-dir) ".lock")]
      (fs/create-dirs (fs/parent file))
      (with-open [ch (FileChannel/open file (into-array OpenOption [StandardOpenOption/CREATE
                                                                    StandardOpenOption/WRITE]))
                  _lock (.lock ch)]
        (f)))))

(defn- read-state [data-dir]
  (let [f (state-file data-dir)]
    (when (fs/exists? f)
      (try (let [s (edn/read-string (slurp (str f)))] (when (map? s) s))
           (catch Exception _ nil)))))

(defn- write-state! [data-dir s]
  (durable/write! (str (state-file data-dir)) (pr-str s) false))

;; --- the container's life

(defn- real-data-dir [data-dir] (str (fs/real-path data-dir)))

(defn context
  "What the edges need for a data directory and config: the settings, the
  names, the run argv and its spec. Throws when a mount cannot be used:
  each must exist, and its real path must pass mount-problem and
  identity-problem too, so neither a symbolic link nor another name for
  the same directory can point a mount at a secret."
  [data-dir config]
  (let [house (or (settings config)
                  (throw (ex-info "relay.edn has no :house section, so there is no shell." {:relay/usage true})))
        real-dir (real-data-dir data-dir)
        home (str (fs/real-path (System/getProperty "user.home")))
        names (names house real-dir)
        mounts (vec (for [m (:mounts house)]
                      (let [real (when (fs/exists? m) (str (fs/real-path m)))]
                        (when-not real
                          (throw (ex-info (str "relay.edn's :house mounts " m ", which does not exist.")
                                          {:relay/usage true})))
                        (when-let [why (or (mount-problem real {:home home :data-dir real-dir})
                                           (identity-problem real {:home home :data-dir real-dir}))]
                          (throw (ex-info (str "relay.edn's :house cannot mount " m " (" real "): " why ".")
                                          {:relay/usage true})))
                        [real m])))
        argv (run-argv house names mounts)]
    {:house house :names names :run-argv argv :spec (spec argv) :data-dir data-dir}))

(defn running?
  "Whether the container answers."
  [{:keys [house names]}]
  (zero? (:exit (run-runner! (probe-argv house names) {:ms 30000 :limit 4096}))))

(defn- runner-said [{:keys [out err]}]
  (let [s (str/trim (str err "\n" out))]
    (if (str/blank? s) "it said nothing" (last (str/split-lines s)))))

(defn image-present? [{:keys [house names]}]
  (zero? (:exit (run-runner! (image-inspect-argv house names) {:ms 30000 :limit 4096}))))

(defn up!
  "Makes sure the container runs with the current settings. Returns
  :running when it already did, :started when relay started it. A
  container started with other settings is replaced; a new container has
  an empty scratch directory, and so no scratch labels."
  [{:keys [data-dir spec house names run-argv] :as ctx}]
  (with-lock data-dir
    (fn []
      (let [st (read-state data-dir)]
        (if (and (= spec (:spec st)) (running? ctx))
          :running
          (do
            (when-not (image-present? ctx)
              (throw (ex-info (str "The shell's image " (:image names) " is not built. Run relay house build.")
                              {:relay/usage true})))
            (run-runner! (remove-argv house names) {:ms 60000 :limit 4096})
            (let [r (run-runner! run-argv {:ms 120000 :limit 65536})]
              (when-not (zero? (:exit r))
                (throw (ex-info (str "The shell would not start: " (runner-said r)) {:relay/usage true}))))
            (write-state! data-dir {:spec spec :scratch-labels []})
            :started))))))

(defn down!
  "Removes the container, and its scratch directory with it. Returns
  :stopped, or :absent when nothing was running."
  [{:keys [data-dir house names] :as ctx}]
  (with-lock data-dir
    (fn []
      (let [was (running? ctx)]
        (run-runner! (remove-argv house names) {:ms 60000 :limit 4096})
        (fs/delete-if-exists (state-file data-dir))
        (if was :stopped :absent)))))

(defn status
  "{:running :current :image-present}: whether it answers, whether it runs
  with the current settings, and whether the image is built."
  [{:keys [data-dir spec] :as ctx}]
  (let [running (running? ctx)]
    {:running running
     :current (and running (= spec (:spec (read-state data-dir))))
     :image-present (image-present? ctx)}))

(defn containerfile
  "The Containerfile relay builds the image from, as a file path."
  []
  (let [r (io/resource "house/Containerfile")]
    (when-not r (throw (ex-info "relay cannot find its Containerfile (resources/house)." {})))
    (str (fs/path (io/file r)))))

(defn build!
  "Builds the image, which needs the network, then restarts the container
  if it was running, so it runs the new image. Returns {:restarted bool}."
  [{:keys [house names] :as ctx}]
  (let [cf (containerfile)
        r (run-runner! (build-argv house names cf (str (fs/parent cf))) {:ms (* 30 60 1000) :limit 200000})]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "The shell's image did not build: " (runner-said r)) {:relay/usage true})))
    (if (running? ctx)
      (do (down! ctx) (up! ctx) {:restarted true})
      {:restarted false})))

(defn- record-session!
  "Notes that a session holding `session-labels` is about to run a command,
  which may write to scratch, and returns the scratch labels from before.
  Every writer records before its command runs, so a command that reads
  the scratch labels again when it ends (scratch-labels) sees every
  session that could have written to scratch while it ran."
  [data-dir session-labels]
  (with-lock data-dir
    (fn []
      (let [st (read-state data-dir)
            before (vec (:scratch-labels st))
            now (vec (sort (distinct (concat before session-labels))))]
        (when (and st (not= now before))
          (write-state! data-dir (assoc st :scratch-labels now)))
        before))))

(defn- scratch-labels
  "The scratch labels as they stand now, read under the lock."
  [data-dir]
  (with-lock data-dir #(vec (:scratch-labels (read-state data-dir)))))

(defn sh!
  "Runs `command` in the container for a session holding `session-labels`.
  Starts the container first when relay has not started it with the
  current settings. Returns {:content :labels}; throws when the shell
  cannot be reached at all."
  [{:keys [data-dir house names spec] :as ctx} session-labels command]
  (let [{:keys [timeout-seconds output-limit] :as h} (merge defaults house)
        _ (when-not (= spec (:spec (read-state data-dir))) (up! ctx))
        attempt (fn []
                  (let [before (record-session! data-dir session-labels)]
                    [before (run-runner! (exec-argv house names timeout-seconds command)
                                         {:ms (* 1000 (+ timeout-seconds 30)) :limit output-limit})]))
        [before r] (let [[_ r :as first-try] (attempt)]
                     ;; A failure with the container gone means the command
                     ;; never ran: start it and run the command once more.
                     (if (and (not (zero? (:exit r))) (not (:timed-out r)) (not (running? ctx)))
                       (do (up! ctx) (attempt))
                       first-try))
        ;; What scratch held when the command started, and whatever a
        ;; session running beside it could have written there before it
        ;; ended. A container replaced meanwhile empties the second part,
        ;; never the first.
        labels (result-labels (concat before (scratch-labels data-dir)))]
    (when (:timed-out r)
      (throw (ex-info "The shell did not answer." {:relay/labels labels})))
    {:content (result-text (assoc r :timed-out (and (contains? #{124 137} (:exit r))
                                                    (>= (:ms r) (* 1000 timeout-seconds))))
                           h)
     :labels labels}))

;; --- the tool

(defn tool-description [house]
  (let [{:keys [mounts timeout-seconds output-limit]} (merge defaults house)]
    (str "Run a shell command with sh -c on your own Linux machine. It has no network. "
         (if (seq mounts)
           (str "The operator's files are there read-only, at the same paths: " (str/join ", " mounts) ". ")
           "None of the operator's files are mounted. ")
         "Write only under " scratch ", which is the working directory and is kept between"
         " commands until the machine restarts. It has coreutils, find, grep, rg, awk, sed, python3,"
         " sqlite3, jq, pdftotext, file, tar, zip and unzip. A command may run " timeout-seconds
         " seconds; output past " output-limit " bytes is cut.")))
