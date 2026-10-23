(ns relay.test-support-test
  "The tests' own rule: they run against the sibling builds of agentloop
  and relay-tui (relay.test-support), never against what is on PATH, which
  may be the live install."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest no-test-looks-for-agentloop-or-relay-tui-on-path
  ;; Regression: acp-test took relay-tui from PATH, the live install, which
  ;; bb deploy moves only after the tests pass. Deploying the commit that
  ;; first asked relay-tui for --open failed there, since the live one was
  ;; older and the test skipped.
  (let [hits (for [f (fs/glob "test" "**.clj")
                   :when (not= "test_support_test.clj" (str (fs/file-name f)))
                   [i line] (map-indexed vector (fs/read-all-lines f))
                   :when (re-find #"fs/which\s+\"(relay-tui|agentloop)\"" line)]
               (str f ":" (inc i) ": " line))]
    (is (empty? hits) (str "Use relay.test-support/relay-tui-bin or agentloop-bin:\n"
                           (str/join "\n" hits)))))
