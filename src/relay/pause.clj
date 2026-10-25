(ns relay.pause
  "The pause file. While it exists, `relay tick` does nothing, so nothing
  new is started while the operator moves or rebuilds relay's checkouts.
  `bb deploy` and `bb rollback` create it, wait for running wakes and
  ticks to finish, and remove it when they succeed. `relay status` says
  when it is there.

  A tick that is already past its check when the file appears would go on
  to start wakes. So each tick leaves a marker file beside the pause file
  while it runs, made before it looks for the pause file; the deploy makes
  the pause file before it looks for markers. Whichever came second sees
  the other, and the deploy waits for the markers of live ticks to go.

  The file lives outside the data directory and outside ~/Sync, at a fixed
  path, so it is neither synced to another machine nor written by a role.
  Its text says why it was made."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [relay.durable :as durable]))

(defn default-file
  "~/Library/Application Support/relay/paused"
  [home]
  (str home "/Library/Application Support/relay/paused"))

(defn reason
  "Why relay is paused, from the file's text, or nil when it is not. A file
  with no text still pauses."
  [file]
  (when (and file (fs/exists? (str file)))
    (let [text (str/trim (slurp (str file)))]
      (if (str/blank? text) "no reason given" text))))

(defn recovery-lines
  "What a person needs to see when relay is paused: that it is, why, where
  the file is and how to lift it. `relay status` prints this whenever the
  file exists, and the deploy commands print it when they refuse because of
  it. A pause made by bb deploy or bb rollback also names bb rollback.
  Given `why`, the file's reason, it reads nothing."
  ([file] (recovery-lines file (reason file)))
  ([file why]
   (when why
     (cond-> [(str "relay is paused: relay tick starts nothing until the pause file is removed. " why)
              (str "Pause file: " file)
              (str "To lift it by hand: rm '" file "'")]
       (str/starts-with? why "bb ")
       (conj (str "A bb deploy or bb rollback made it. If it stopped partway, run bb rollback from a relay "
                  "checkout: it puts the checkouts back where the last deploy found them and lifts the pause."))))))

(defn pause!
  "Creates the file, or rewrites its reason, and returns once it is on
  storage."
  [file why]
  (durable/replace! file (str why "\n")))

(defn- alive? [pid start]
  (let [handle (java.lang.ProcessHandle/of (long pid))]
    (boolean (and (.isPresent handle)
                  (.isAlive ^java.lang.ProcessHandle (.get handle))
                  (let [actual (some-> (.startInstant (.info ^java.lang.ProcessHandle (.get handle))) (.orElse nil) str)]
                    (or (nil? start) (nil? actual) (= start actual)))))))

(defn- marker-glob [file] [(str (fs/parent file)) (str (fs/file-name file) ".ticking.*")])

(defn ticks-running
  "The markers of ticks whose process is alive, as {:pid}. Markers left by
  a tick that died are removed."
  [file]
  (let [[dir pattern] (marker-glob file)]
    (vec (when (fs/directory? dir)
           (for [m (fs/glob dir pattern)
                 :let [[pid start] (str/split (str/trim (try (slurp (str m)) (catch Exception _ ""))) #" " 2)
                       pid' (try (Long/parseLong pid) (catch Exception _ nil))]
                 :when (if (and pid' (alive? pid' start))
                         true
                         (do (fs/delete-if-exists m) false))]
             {:pid pid'})))))

(defn while-ticking
  "Runs `f` with a marker for this process beside the pause file, and
  removes it afterwards. Makes the marker before `f` looks at the pause
  file."
  [file f]
  (let [me (java.lang.ProcessHandle/current)
        marker (str file ".ticking." (.pid me))]
    ;; Best effort: a tick that cannot write its marker must still run.
    (try (fs/create-dirs (fs/parent file))
         (spit marker (str (.pid me) " " (some-> (.startInstant (.info me)) (.orElse nil) str) "\n"))
         (catch Exception _ nil))
    (try (f) (finally (try (fs/delete-if-exists marker) (catch Exception _ nil))))))

(defn resume!
  "Removes the file, and returns once the removal is on storage. True when
  there was one."
  [file]
  (durable/delete! file))
