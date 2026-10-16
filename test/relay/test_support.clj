(ns relay.test-support
  "What every test shares about the machine it runs on. The tests must never
  reach the operator's real relay: not the per-user config file, which names
  the live data directory, not the relay command on PATH, and not the
  agentloop on PATH, which may be the live install."
  (:require [babashka.fs :as fs]
            [relay.cli :as cli]))

(def relay-bin (str (fs/absolutize "bin/relay")))

(def agentloop-bin
  "agentloop built in the sibling checkout (make -C ../agentloop build), or
  nil when it is not built, which skips the tests that need it."
  (let [sibling (fs/absolutize "../agentloop/bin/agentloop")]
    (when (fs/executable? sibling) (str (fs/normalize sibling)))))

(defn isolate!
  "Points relay away from anything real for the rest of the run: the
  per-user config file and the pause file at paths that do not exist, and the relay command
  harnesses run at this checkout's bin/relay."
  []
  (alter-var-root #'cli/*config-file*
                  (constantly (str (fs/path (fs/create-temp-dir {:prefix "relay-test-home"})
                                            "no-config.edn"))))
  (alter-var-root #'cli/*pause-file*
                  (constantly (str (fs/path (fs/create-temp-dir {:prefix "relay-test-pause"}) "paused"))))
  (when-not (System/getProperty "relay.bin")
    (System/setProperty "relay.bin" relay-bin)))
