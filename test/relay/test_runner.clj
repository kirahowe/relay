(ns relay.test-runner
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn- test-namespaces []
  (->> (fs/glob "test" "**_test.clj")
       (map #(-> (str (fs/relativize "test" %))
                 (str/replace #"\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(defn run! [args]
  (let [nses (if (seq args) (map symbol args) (test-namespaces))]
    (apply require nses)
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (when (pos? (+ fail error)) (System/exit 1)))))
