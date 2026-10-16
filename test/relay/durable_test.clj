(ns relay.durable-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.durable :as durable]))

(deftest appending-adds-and-replacing-replaces
  (let [dir (str (fs/create-temp-dir {:prefix "relay-durable"}))
        file (str dir "/a/b/file")
        r (tc/quick-check
           100
           (prop/for-all [parts (gen/vector gen/string 1 5)]
             (fs/delete-if-exists file)
             (doseq [p parts] (durable/append! file p))
             (and (= (apply str parts) (slurp file))
                  (do (durable/replace! file (first parts)) (= (first parts) (slurp file))))))]
    (try (is (:pass? r) (pr-str (:shrunk r)))
         (finally (fs/delete-tree dir)))))

(deftest deleting-reports-whether-there-was-a-file
  (let [dir (str (fs/create-temp-dir {:prefix "relay-durable"}))
        file (str dir "/f")]
    (try (spit file "x")
         (is (true? (durable/delete! file)))
         (is (not (fs/exists? file)))
         (is (false? (durable/delete! file)))
         (finally (fs/delete-tree dir)))))
