(ns relay.config-test
  "What relay.edn may hold at the top level, and how a mistake there is
  told: a misspelled section is refused with the likely key, a removed one
  with what replaced it, and a ~ in a hand-written path is expanded."
  (:require [babashka.fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.config :as config]))

(def complete {:timezone "UTC" :default-harness :agentloop
               :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"]}}})

(defn- problem [settings]
  (try (config/resolve-config settings) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest a-misspelled-section-is-refused-with-the-likely-key
  (is (= "relay.edn has :mesages, which relay does not know. Did you mean :messages?"
         (problem (assoc complete :mesages {}))))
  (is (str/includes? (problem (assoc complete :daily-notes {:at "07:30" :role "steward"})) "Did you mean :daily-note?"))
  (is (str/includes? (problem (assoc complete :holds true)) "which relay does not know"))
  (testing "nothing close: no guess, a pointer to the list"
    (is (str/includes? (problem (assoc complete :xyzzy-plugh 1)) "README: Settings"))))

(deftest a-removed-section-says-what-replaced-it
  (doseq [k [:notify :brief :verifier]]
    (is (str/includes? (problem (assoc complete k {})) "no longer has") (str k))))

(deftest the-format-is-the-one-relay-reads
  (is (nil? (problem (assoc complete :format config/settings-format))))
  (is (nil? (problem complete)) ":format is optional")
  (is (str/includes? (problem (assoc complete :format 0)) "relay reads format 1")))

(deftest an-act-tool-is-told-what-to-do-instead
  (is (= (str "Tool web has :class :act, which relay no longer has: relay never acts as you."
              " Make it a drafting tool with :class :edit, or remove it.")
         (problem (assoc complete :tools {"web" {:class :act :labels [] :command ["true"]}})))))

(deftest removed-grants-say-they-are-gone
  (doseq [k [:reports :quarantine :workbench]]
    (is (str/includes? (problem (assoc complete :grants {"steward" {k true}})) "no longer has") (str k))))

(def key-gen
  (gen/fmap keyword (gen/such-that seq (gen/fmap str/join (gen/vector (gen/elements "abcdefghijklmnopqrstuvwxyz-") 1 14)))))

(defspec every-unknown-top-level-key-is-refused 200
  (prop/for-all [k key-gen]
    (let [known? (or (config/settings-keys k) (contains? config/removed-keys k))
          why (config/unknown-keys-problem (assoc complete k 1))]
      (if known?
        (nil? why)
        (and (str/includes? why (pr-str k))
             ;; Any guess is a key relay knows.
             (let [[_ guess] (re-find #"Did you mean (:[a-z-]+)\?" why)]
               (or (nil? guess) (contains? config/settings-keys (keyword (subs guess 1))))))))))

(defspec a-key-one-typo-away-is-guessed 200
  (prop/for-all [k (gen/elements (sort (filter #(> (count (name %)) 5) config/settings-keys)))
                 i gen/nat
                 c (gen/elements "qxz")]
    (let [s (name k)
          i (mod i (count s))
          typo (keyword (str (subs s 0 i) c (subs s (inc i))))]
      (or (config/settings-keys typo)
          (some? (config/did-you-mean typo config/settings-keys))))))

(defspec expanding-home-is-idempotent-and-leaves-other-paths 200
  (prop/for-all [tail (gen/fmap #(str/join "/" %) (gen/vector (gen/elements ["a" "notes" "b c"]) 0 3))
                 lead (gen/elements ["~" "~/" "/abs/" "rel/" "x~/"])]
    (let [p (str lead tail)
          once (config/expand-home p "/Users/you")]
      (and (= once (config/expand-home once "/Users/you"))
           (if (or (= p "~") (str/starts-with? p "~/"))
             (str/starts-with? once "/Users/you")
             (= p once))))))

(deftest mounts-and-files-expand-a-leading-tilde
  (let [s (config/expand-paths {:house {:mounts ["~/Documents" "/abs"]}
                                :grants {"steward" {:shell true :files ["~/Downloads"]} "scout" {:public true}}}
                               "/Users/you")]
    (is (= ["/Users/you/Documents" "/abs"] (get-in s [:house :mounts])))
    (is (= ["/Users/you/Downloads"] (get-in s [:grants "steward" :files])))
    (is (= {:public true} (get-in s [:grants "scout"]))))
  (testing "a malformed setting is left for validation to refuse as written"
    (is (= {:house {:mounts "~/x"}} (config/expand-paths {:house {:mounts "~/x"}} "/h")))))

;; --- the main role's name

(deftest a-setting-that-names-no-role-is-said-with-the-likely-fix
  (let [cfg (config/resolve-config (assoc complete :grants {"hub" {:may-add true}}
                                          :serve {:websocket {:port 8765 :token-command ["true"]}}))
        [handler ws & more] (config/role-name-problems cfg ["clerk" "hub"])]
    (is (nil? more))
    (is (= (str "relay.edn's :dead-letter-handler is \"steward\" (relay's default when it is not set), but there"
                " is no role steward, so work another role cannot finish has no role to go to."
                " Your main role looks like hub: set :dead-letter-handler \"hub\".")
           handler))
    (is (str/includes? ws ":serve :websocket :default-role is \"steward\""))
    (is (str/includes? ws "set :default-role \"hub\""))
    (testing "nothing to say when the names are roles, or there are no roles yet"
      (is (empty? (config/role-name-problems cfg [])))
      (is (empty? (config/role-name-problems (config/resolve-config (assoc complete :dead-letter-handler "hub"))
                                             ["hub"])))
      (is (empty? (config/role-name-problems (config/resolve-config complete) ["steward" "scout"]))))
    (testing "with no likely main role, no guess"
      (is (not (str/includes? (first (config/role-name-problems (config/resolve-config (assoc complete :grants {"s" {:public true}}))
                                                                ["s"]))
                              "looks like"))))))

;; --- a relay.edn relay cannot read

(deftest a-relay-edn-that-is-not-edn-is-told-plainly
  (let [dir (str (babashka.fs/create-temp-dir {:prefix "relay-config-test"}))]
    (spit (str dir "/relay.edn") "{:timezone \"UTC\"\n :ingest\n}\n")
    (try (config/load-config dir)
         (is false "refused")
         (catch clojure.lang.ExceptionInfo e
           (is (:relay/usage (ex-data e)))
           (is (str/starts-with? (ex-message e) "relay.edn is not valid EDN: "))
           (is (str/includes? (ex-message e) "Check the section you last edited."))))
    (babashka.fs/delete-tree dir)))
