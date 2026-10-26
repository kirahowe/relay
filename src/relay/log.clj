(ns relay.log
  "The event log, relay's one system of record. Everything relay knows is an
  event in it, and every other view is derived from it.

  The log is a directory of JSONL segments, one per calendar month (UTC) of
  recording, read in name order. Each line is one event:

    {\"seq\": 41, \"at\": \"2026-10-14T17:03:22.118Z\", \"type\": \"memo/sent\",
     \"actor\": \"operator\", \"labels\": [], \"data\": {...}}

  An event that wrote an item a public role may read also has
  \"public\": true.

  `seq` counts from 1 with no gaps across segments, so it is both the order
  and the identity of an event. `at` is when relay recorded the event, which
  never decreases. When a thing was true in the world is a separate matter,
  carried in an event's data.

  There is one writer at a time, enforced by an OS file lock, and appends
  only add whole lines. A crash in the middle of an append can leave a
  partial last line. Readers ignore it, and the next writer cuts it off
  before appending, since the append it belonged to never returned. Anything
  else that does not parse is corruption, and reading stops with an error
  rather than skipping it."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.nio ByteBuffer)
           (java.nio.channels FileChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file OpenOption StandardOpenOption)
           (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def format-version 1)

(defn log-dir [data-dir] (str (fs/path data-dir "log")))

(defn- segment-name [^Instant at]
  (str (.format (DateTimeFormatter/ofPattern "yyyy-MM") (.atOffset at ZoneOffset/UTC)) ".jsonl"))

(defn segments
  "The segment files in order."
  [data-dir]
  (let [dir (log-dir data-dir)]
    (if (fs/directory? dir)
      (->> (fs/glob dir "*.jsonl") (map str) sort vec)
      [])))

;; --- reading

(defn- complete-lines
  "The complete lines of a segment's text, and whether a partial line
  followed them."
  [^String text]
  (let [end (inc (.lastIndexOf text "\n"))]
    [(remove str/blank? (str/split-lines (subs text 0 end)))
     (< end (count text))]))

(defn- parse-line [file n line]
  (let [event (try (json/parse-string line true)
                   (catch Exception e
                     (throw (ex-info (str "The log is corrupt: line " n " of " file
                                          " is not JSON.")
                                     {:file file :line n} e))))]
    (when-not (and (map? event) (integer? (:seq event)) (string? (:type event))
                   (string? (:at event)) (vector? (:labels event)))
      (throw (ex-info (str "The log is corrupt: line " n " of " file
                           " is not a well-formed event.")
                      {:file file :line n})))
    event))

(defn read-events
  "Every event in the log, oldest first. Throws when the log is corrupt: a
  line that is not an event, or a gap or repeat in `seq`. A partial last line
  in the final segment is an append that never finished, and is left out."
  [data-dir]
  (let [files (segments data-dir)
        last-file (peek files)]
    (loop [[file & more] files
           expected 1
           out (transient [])]
      (if-not file
        (persistent! out)
        (let [[lines partial?] (complete-lines (slurp file))
              _ (when (and partial? (not= file last-file))
                  (throw (ex-info (str "The log is corrupt: " file
                                       " ends in a partial line but is not the last segment.")
                                  {:file file})))
              [out expected]
              (reduce (fn [[out expected] [n line]]
                        (let [event (parse-line file n line)]
                          (when (not= expected (:seq event))
                            (throw (ex-info (str "The log is corrupt: expected event "
                                                 expected " in " file " at line " n
                                                 " but found " (:seq event) ".")
                                            {:file file :line n})))
                          [(conj! out event) (inc expected)]))
                      [out expected]
                      (map-indexed (fn [i l] [(inc i) l]) lines))]
          (recur more expected out))))))

;; --- writing

(def ^:private jvm-lock
  "FileChannel locks belong to the whole process, and a second attempt from
  the same process throws rather than waits, so threads queue here first."
  (Object.))

(defn- with-file-lock [data-dir f]
  (locking jvm-lock
    (let [file (fs/path (log-dir data-dir) ".lock")]
      (fs/create-dirs (fs/parent file))
      (with-open [ch (FileChannel/open file (into-array OpenOption [StandardOpenOption/CREATE
                                                                    StandardOpenOption/WRITE]))
                  _lock (.lock ch)]
        (f)))))

(defn- truncate-partial-tail!
  "Cuts a partial last line off the final segment. Only called with the lock
  held, so no append is in flight."
  [data-dir]
  (when-let [file (peek (segments data-dir))]
    (let [text (slurp file)
          end (inc (.lastIndexOf ^String text "\n"))]
      (when (< end (count text))
        (let [keep-bytes (count (.getBytes (subs text 0 end) StandardCharsets/UTF_8))]
          (with-open [ch (FileChannel/open (fs/path file)
                                           (into-array OpenOption [StandardOpenOption/WRITE]))]
            (.truncate ch keep-bytes)
            (.force ch true)))))))

(defn- append-lines! [file ^String text]
  (with-open [ch (FileChannel/open (fs/path file)
                                   (into-array OpenOption [StandardOpenOption/CREATE
                                                           StandardOpenOption/WRITE
                                                           StandardOpenOption/APPEND]))]
    (let [buf (ByteBuffer/wrap (.getBytes text StandardCharsets/UTF_8))]
      (while (.hasRemaining buf) (.write ch buf))
      (.force ch true))))

(defn- encode [event]
  (let [line (json/generate-string event)]
    ;; cheshire escapes control characters, so an event can never span lines.
    (assert (not (str/includes? line "\n")))
    line))

(defn initialized? [data-dir]
  (fs/exists? (fs/path data-dir "relay.edn")))

(defn transact!
  "The only way anything is written. Under the lock, calls `(decide events)`
  with every event so far; it returns a sequence of new events as maps of
  :type, :actor, :data and optionally :labels, :public and :sources. They are given their seq and
  recording time and appended together, and are returned. A decide function
  that throws appends nothing.

  `clock` returns the current Instant. Recording time never goes backwards:
  if the clock does, the last recorded time is used instead.

  A draft may carry its own :seq and :at, when the decide function needed
  to know them, for instance to hand out ids. They are checked rather than
  trusted: the seq must be the one this append gives it, and the time must
  not be earlier than the last recorded one."
  [data-dir clock decide]
  (when-not (initialized? data-dir)
    (throw (ex-info (str data-dir " is not a relay data directory. Run `relay init` first.")
                    {:data-dir data-dir})))
  (with-file-lock data-dir
    (fn []
      (truncate-partial-tail! data-dir)
      (let [events (read-events data-dir)
            drafts (vec (decide events))]
        (if (empty? drafts)
          []
          (let [last-event (peek events)
                now ^Instant (clock)
                floor (some-> last-event :at Instant/parse)
                at (if (and floor (.isBefore now floor)) floor now)
                start (inc (or (:seq last-event) 0))
                stamped (vec (map-indexed
                              (fn [i {:keys [type actor data labels] :as draft}]
                                (when-not (and (string? type) (string? actor))
                                  (throw (ex-info "An event needs a string :type and :actor."
                                                  {:type type :actor actor})))
                                (when (and (:seq draft) (not= (:seq draft) (+ start i)))
                                  (throw (ex-info (str "A draft expected seq " (:seq draft)
                                                       " but would be appended as " (+ start i) ".")
                                                  {:draft draft})))
                                (when (and (:at draft) floor
                                           (.isBefore (Instant/parse (str (:at draft))) floor))
                                  (throw (ex-info "A draft's time is earlier than the log's last event."
                                                  {:draft draft})))
                                (cond-> {:seq (+ start i)
                                         :at (str (or (some-> (:at draft) str Instant/parse) at))
                                         :type type
                                         :actor actor
                                         :labels (vec (sort (distinct labels)))
                                         :data (or data {})}
                                  ;; Set by relay.core on an item a public
                                  ;; writer wrote; absent means not public.
                                  (true? (:public draft)) (assoc :public true)
                                  ;; What its writer read directly
                                  ;; (relay.provenance), when it said.
                                  (sequential? (:sources draft)) (assoc :sources (vec (sort (distinct (:sources draft)))))))
                              drafts))
                ;; Round trip through JSON so the caller gets back exactly
                ;; what a later read will see.
                lines (mapv encode stamped)
                times (set (map :at stamped))
                _ (when (> (count times) 1)
                    (throw (ex-info "The events of one append must share a recording time."
                                    {:times times})))
                file (str (fs/path (log-dir data-dir)
                                   (segment-name (Instant/parse (:at (first stamped))))))]
            (append-lines! file (str (str/join "\n" lines) "\n"))
            (mapv #(json/parse-string % true) lines)))))))

(defn init!
  "Makes `data-dir` a relay data directory. Safe to run twice."
  [data-dir]
  (fs/create-dirs (log-dir data-dir))
  (let [marker (fs/path data-dir "relay.edn")]
    (when-not (fs/exists? marker)
      (spit (str marker)
            (str ";; This directory is relay's data. The log/ directory is the system of\n"
                 ";; record; relay is its only writer.\n"
                 (pr-str {:format format-version}) "\n"))))
  data-dir)
