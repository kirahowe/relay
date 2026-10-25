(ns relay.eval-oracles-1-test
  "Shard 1 of relay.eval-oracles."
  (:require [clojure.test :refer [deftest]]
            [relay.eval-oracles :as oracles]))

(deftest every-oracle-passes-and-every-foil-fails-exactly-its-graders
  (oracles/check-shard! 1))
