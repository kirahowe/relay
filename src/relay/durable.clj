(ns relay.durable
  "Writes that have reached storage before they return, so that a crash
  leaves what was written (the steward shell's state, say), which a write
  still in the operating system's cache would not."
  (:require [babashka.fs :as fs])
  (:import (java.nio ByteBuffer)
           (java.nio.channels FileChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file OpenOption StandardOpenOption)))

(defn- force-directory!
  "Makes the directory's entries durable: a new file's name, or a removed
  one's absence. A platform that will not let a directory be opened has no
  such barrier to offer, so that is skipped; once it is open, a failure to
  force it is a real failure and propagates."
  [dir]
  (when-let [ch (try (FileChannel/open (fs/path dir) (into-array OpenOption [StandardOpenOption/READ]))
                     (catch Exception _ nil))]
    (with-open [ch ch] (.force ch true))))

(defn write!
  "Writes `text` to `file`, replacing it when `append?` is false and adding
  to the end when it is true, and forces the file and its directory to
  storage."
  [file text append?]
  (fs/create-dirs (fs/parent file))
  (let [existed? (fs/exists? file)
        opts (into-array OpenOption
                         (concat [StandardOpenOption/CREATE StandardOpenOption/WRITE]
                                 (if append? [StandardOpenOption/APPEND] [StandardOpenOption/TRUNCATE_EXISTING])))]
    (with-open [ch (FileChannel/open (fs/path file) opts)]
      (let [buf (ByteBuffer/wrap (.getBytes ^String text StandardCharsets/UTF_8))]
        (while (.hasRemaining buf) (.write ch buf))
        (.force ch true)))
    (when-not existed? (force-directory! (fs/parent file)))
    nil))

(defn delete!
  "Removes `file` if it is there, and makes the removal durable. True when
  there was a file."
  [file]
  (let [removed (fs/delete-if-exists file)]
    (when removed (force-directory! (fs/parent file)))
    (boolean removed)))

(defn append! [file text] (write! file text true))
(defn replace! [file text] (write! file text false))
