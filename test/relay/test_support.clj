(ns relay.test-support
  "What every test shares about the machine it runs on. The tests must never
  reach the operator's real relay: not the per-user config file, which names
  the live data directory, not the relay command on PATH, and not the
  agentloop on PATH, which may be the live install."
  (:require [babashka.fs :as fs]
            [relay.cli :as cli]
            [relay.connect :as connect]
            [relay.core :as core]
            [relay.serve :as serve]))

(def relay-bin (str (fs/absolutize "bin/relay")))

(def agentloop-bin
  "agentloop built in the sibling checkout (make -C ../agentloop build), or
  nil when it is not built, which skips the tests that need it. `bb test
  --agentloop <path>` names another build instead (use-agentloop!)."
  (let [sibling (fs/absolutize "../agentloop/bin/agentloop")]
    (when (fs/executable? sibling) (str (fs/normalize sibling)))))

(defn use-agentloop!
  "Runs the tests against the agentloop binary at `path`, which must exist,
  in place of the sibling checkout's."
  [path]
  (let [p (fs/absolutize path)]
    (when-not (fs/executable? p)
      (throw (ex-info (str "No agentloop binary at " p ".") {})))
    (alter-var-root #'agentloop-bin (constantly (str (fs/normalize p))))))

(defn use-agentloop-from-env!
  "use-agentloop! for the path in RELAY_TEST_AGENTLOOP, when it is set. This is
  how `bb test --agentloop <path>` reaches each volley worker."
  []
  (when-let [path (System/getenv "RELAY_TEST_AGENTLOOP")]
    (use-agentloop! path)))

(defn isolate!
  "Points relay away from anything real for the rest of the run: the
  per-user config file and the pause file at paths that do not exist, and the relay command
  harnesses run at this checkout's bin/relay."
  []
  (alter-var-root #'cli/*config-file*
                  (constantly (str (fs/path (fs/create-temp-dir {:prefix "relay-test-home"})
                                            "no-config.edn"))))
  ;; Nor the service's sockets in the user's home.
  (alter-var-root #'serve/*run-dir*
                  (constantly (str (fs/create-temp-dir {:dir "/tmp" :prefix "relay-run"}))))
  (alter-var-root #'cli/*pause-file*
                  (constantly (str (fs/path (fs/create-temp-dir {:prefix "relay-test-pause"}) "paused"))))
  ;; No test may touch the login keychain or open a browser: one that
  ;; needs them binds connect/*edges* with its own.
  (alter-var-root #'connect/*edges*
                  (constantly {:keychain-read (fn [& _] (throw (ex-info "tests never read the keychain" {})))
                               :keychain-write! (fn [& _] (throw (ex-info "tests never write the keychain" {})))
                               :open-browser (fn [_] (throw (ex-info "tests never open a browser" {})))}))
  (when-not (System/getProperty "relay.bin")
    (System/setProperty "relay.bin" relay-bin)))

(defn calling-wake
  "The wake a test takes a role's call to come from, as relay's tool
  command names it: the role's working wake, else its open conversation."
  [state role-id]
  (or (core/working-wake state role-id) (core/open-conversation state role-id)))
