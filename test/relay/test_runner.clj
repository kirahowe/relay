(ns relay.test-runner
  "`bb test`: runs the tests with volley, which reads tests.edn and Kaocha's
  flags. This namespace keeps the two things the old runner took: namespace
  names, and --agentloop <path>."
  (:require [babashka.process :as p]
            [volley.main :as volley]))

(def agentloop-env
  "Carries --agentloop's path to the volley workers, which are separate
  processes (support/use-agentloop-from-env! reads it)."
  "RELAY_TEST_AGENTLOOP")

(defn agentloop-option
  "[path other-args] for `--agentloop <path>` among args."
  [args]
  (loop [[a & more] args path nil rest []]
    (cond (nil? a) [path rest]
          (= "--agentloop" a) (recur (next more) (first more) rest)
          :else (recur more path (conj rest a)))))

(def ^:private options-with-values
  #{"--focus" "--skip" "--focus-meta" "--skip-meta" "--config-file" "--reporter"
    "--profile" "--plugin" "--workers" "--runtime" "--aliases" "--junit-xml-file"})

(defn volley-args
  "A bare word is a namespace name and becomes --focus <name>. Options, and
  the values of options that take one, pass through to volley as they are."
  [args]
  (loop [[a & more] args out []]
    (cond (nil? a) out
          (and (options-with-values a) more) (recur (next more) (conj out a (first more)))
          (.startsWith ^String a "-") (recur more (conj out a))
          :else (recur more (conj out "--focus" a)))))

(defn run!
  "Runs the tests with volley. --agentloop <path> runs them against that
  agentloop build instead of ../agentloop's. The workers are separate
  processes, so the path reaches them in the environment: `bb test`
  starts itself again with it set."
  [args]
  (let [[agentloop args] (agentloop-option args)]
    (if (and agentloop (not (System/getenv agentloop-env)))
      (let [{:keys [exit]} (apply p/shell {:continue true :extra-env {agentloop-env agentloop}}
                                  "bb" "test" args)]
        (System/exit exit))
      (apply volley/-main (volley-args args)))))
