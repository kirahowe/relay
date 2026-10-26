(ns relay.verify
  "`bb verify`: every check relay has, in one run, with one summary. See
  VERIFY.md for what each step proves. The plan is data (`plan`), and so is
  the summary (`summary-lines`); only `run!` starts processes."
  (:refer-clojure :exclude [run!])
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def usage
  "usage: bb verify [options]

Runs, in order: agentloop, relay-tui and webtool's own tests and builds,
relay's tests (the mock scenarios among them) and the pentests. With --live it also
runs the behaviour evals against the real model, which costs a little.

  --agentloop-dir <dir>  agentloop checkout (default ../agentloop)
  --relay-tui-dir <dir>  relay-tui checkout (default ../relay-tui)
  --webtool-dir <dir>    webtool checkout (default ../webtool)
  --only <a,b>           run only these steps, by id
  --skip <a,b>           leave these steps out, by id
  --live                 also run bb eval --live
  --max-cost <usd>       spend limit for --live (default 2)
  --list                 list the steps and exit")

(def ^:private flags-with-values
  #{"--agentloop-dir" "--relay-tui-dir" "--webtool-dir" "--only" "--skip" "--max-cost"})

(defn parse-args
  "Options from the command line, or {:error msg}."
  [args]
  (loop [[a & more] args opts {}]
    (cond
      (nil? a) opts
      (flags-with-values a) (if (seq more)
                              (recur (next more) (assoc opts (keyword (subs a 2)) (first more)))
                              {:error (str a " needs a value")})
      (= "--live" a) (recur more (assoc opts :live true))
      (= "--list" a) (recur more (assoc opts :list true))
      (#{"--help" "-h"} a) (recur more (assoc opts :help true))
      :else {:error (str "unknown argument " a)})))

(defn- ids [s] (when s (set (str/split s #","))))

(defn plan
  "The steps to run, in order, for options and the relay checkout root.
  Each step is {:id :what :dir :cmd}; :cmd is a vector run without a shell."
  [opts root]
  (let [sib (fn [k default] (str (fs/absolutize (or (get opts k) (fs/path root ".." default)))))
        al (sib :agentloop-dir "agentloop")
        tui (sib :relay-tui-dir "relay-tui")
        wt (sib :webtool-dir "webtool")
        al-bin (str (fs/path al "bin" "agentloop"))
        steps [{:id "agentloop" :what "agentloop's tests, then its build" :dir al
                :cmd ["sh" "-c" "make test && make build"]}
               {:id "relay-tui" :what "relay-tui's checks, then its build" :dir tui
                :cmd ["sh" "-c" "make check && make build"]}
               {:id "webtool" :what "webtool's tests, then its release and fixture builds" :dir wt
                :cmd ["sh" "-c" "make test && make build && make fixture"]}
               {:id "relay" :what "relay's tests and the mock scenarios against that agentloop" :dir root
                :cmd ["bb" "test" "--agentloop" al-bin]}
               {:id "pentest" :what "the whole system attacked on artificial data" :dir root
                :cmd ["bb" "pentest" "--jobs" "4"
                      "--agentloop" al-bin
                      "--relay-tui" (str (fs/path tui "bin" "relay-tui"))
                      "--webtool" (str (fs/path wt "bin" "webtool-fixture"))]}
               {:id "eval" :what "behaviour evals against the real model (costs money)" :dir root
                :live true
                :cmd ["bb" "eval" "--live" "--agentloop" al-bin
                      "--max-cost" (or (:max-cost opts) "2")
                      "--out" (str (fs/path root "out" "verify-eval"))]}]
        only (ids (:only opts))
        skip (ids (:skip opts))]
    (->> steps
         (filter #(or (not (:live %)) (:live opts) (and only (only (:id %)))))
         (filter #(or (nil? only) (only (:id %))))
         (remove #(and skip (skip (:id %))))
         vec)))

(defn passed?
  "Whether every step ran and exited 0. A step that could not run (no
  checkout) has not passed."
  [results]
  (every? #(and (not (:skipped %)) (zero? (:exit %))) results))

(defn summary-lines
  "The table printed at the end, from each step and its result
  {:exit n :secs s}, or {:skipped why}."
  [results]
  (let [w (apply max 4 (map (comp count :id :step) results))
        pad #(str % (apply str (repeat (- w (count %)) " ")))]
    (concat
     ["" "bb verify"]
     (for [{:keys [step exit secs skipped]} results]
       (str "  " (pad (:id step)) "  "
            (cond skipped (str "SKIP  " skipped)
                  (zero? exit) (format "PASS  %.0fs" (double secs))
                  :else (format "FAIL  exit %d after %.0fs" exit (double secs)))))
     [(if (passed? results)
        "All passed."
        (str "Failed: " (str/join ", " (for [r results :when (not (passed? [r]))]
                                         (:id (:step r))))))])))

(defn- run-step [{:keys [dir cmd what id] :as step}]
  (if-not (fs/directory? dir)
    {:step step :skipped (str "no checkout at " dir)}
    (do (println (str "\n== " id ": " what))
        (println (str "   " (str/join " " cmd) "   (in " dir ")"))
        (let [t0 (System/nanoTime)
              {:keys [exit]} @(p/process cmd {:dir dir :inherit true})]
          {:step step :exit exit :secs (/ (- (System/nanoTime) t0) 1e9)}))))

(def ^:private here *file*)

(defn run!
  "Runs the plan and exits non-zero if any step failed or could not run.
  A step whose checkout is missing is reported as SKIP and fails the run."
  [args]
  (let [opts (parse-args args)
        root (str (fs/parent (fs/parent (fs/parent here))))]
    (cond
      (:error opts) (do (println (:error opts)) (println usage) (System/exit 2))
      (:help opts) (println usage)
      (:list opts) (doseq [{:keys [id what live]} (plan (assoc opts :live true) root)]
                     (println (format "  %-10s %s%s" id what (if live "  (--live only)" ""))))
      :else (let [results (mapv run-step (plan opts root))]
              (doseq [l (summary-lines results)] (println l))
              (when-not (passed? results)
                (System/exit 1))))))
