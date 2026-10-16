(ns relay.test-runner
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [relay.test-support :as support]))

(defn- test-namespaces []
  (->> (fs/glob "test" "**_test.clj")
       (map #(-> (str (fs/relativize "test" %))
                 (str/replace #"\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(defn- agentloop-option
  "The binary named by --agentloop <path>, and the other arguments."
  [args]
  (loop [[a & more] args path nil rest []]
    (cond (nil? a) [path rest]
          (= "--agentloop" a) (recur (next more) (first more) rest)
          :else (recur more path (conj rest a)))))

(defn run!
  "Runs the named test namespaces, or all of them. --agentloop <path> runs
  them against that agentloop build instead of ../agentloop's."
  [args]
  (support/isolate!)
  (let [[agentloop args] (agentloop-option args)
        _ (when agentloop (support/use-agentloop! agentloop))
        nses (if (seq args) (map symbol args) (test-namespaces))]
    (apply require nses)
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (when (pos? (+ fail error)) (System/exit 1)))))
