(ns relay.eval-oracles-2-test
  "Shard 2 of relay.eval-oracles."
  (:require [clojure.test :refer [deftest]]
            [relay.eval-oracles :as oracles]))

(deftest every-oracle-passes-and-every-foil-fails-exactly-its-graders
  (oracles/check-shard! 2))
