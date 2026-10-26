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

(deftest the-removed-brief-names-the-main-role
  (is (str/includes? (problem (assoc complete :brief {})) ":daily-note {:at \"07:30\" :role \"steward\"}")
      "relay's default main role")
  (let [said (problem (assoc complete :brief {} :dead-letter-handler "hub"))]
    (is (str/includes? said ":daily-note {:at \"07:30\" :role \"hub\"}"))
    (is (not (str/includes? said "steward")))))

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

(deftest a-misspelled-tool-setting-is-refused-with-the-likely-key
  (is (= "Tool notes in relay.edn has :comand, which a tool does not take. Did you mean :command?"
         (problem (assoc complete :tools {"notes" {:class :read :labels [] :comand ["true"]}}))))
  (is (str/includes? (problem (assoc complete :tools {"notes" {:class :read :labels [] :command ["true"]
                                                              :timeout-secs 5}}))
                     "Did you mean :timeout-seconds?"))
  (testing "nothing close: the keys a tool takes"
    (is (str/includes? (problem (assoc complete :tools {"notes" {:class :read :labels [] :command ["true"] :xyzzy 1}}))
                       "A tool takes :class, :command")))
  (testing "an MCP tool's misspelling is guessed too"
    (is (str/includes? (problem (assoc complete
                                       :mcp-servers {"files" {:command ["files-mcp"]}}
                                       :tools {"read" {:protocol :mcp-stdio :server "files" :tool "read"
                                                       :class :read :labels [] :digets "x"}}))
                       "Did you mean :digest?")))
  (testing "an MCP key on a plain tool still says what it is for"
    (is (str/includes? (problem (assoc complete :tools {"notes" {:class :read :labels [] :command ["true"] :server "x"}}))
                       "only a tool with :protocol :mcp-stdio or :mcp-http takes"))))

(defspec every-tool-key-one-typo-away-is-refused-with-a-guess 200
  (prop/for-all [k (gen/elements (sort (filter #(> (count (name %)) 6) config/plain-tool-keys)))
                 i gen/nat
                 c (gen/elements "qxz")]
    (let [s (name k)
          i (mod i (count s))
          typo (keyword (str (subs s 0 i) c (subs s (inc i))))
          why (problem (assoc complete :tools {"t" {:class :read :labels [] :command ["true"] typo 1}}))]
      (or (contains? (into config/plain-tool-keys config/mcp-tool-keys) typo)
          (and (some? why) (str/includes? why "Did you mean"))))))

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
    (testing "the daily note's writer"
      (let [cfg (config/resolve-config (assoc complete :grants {"hub" {:may-add true}} :dead-letter-handler "hub"
                                              :daily-note {:at "07:30" :role "steward"}))]
        (is (= [(str "relay.edn's :daily-note :role is \"steward\", but there is no role steward, so nobody"
                     " writes the daily note. Your main role looks like hub: set :role \"hub\".")]
               (config/role-name-problems cfg ["hub"])))
        (is (empty? (config/role-name-problems cfg ["hub" "steward"])))))
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

(deftest a-trusted-mount-expands-a-leading-tilde-too
  (is (= [{:path "/h/notes" :trusted true} "/h/Downloads"]
         (get-in (config/expand-paths {:house {:mounts [{:path "~/notes" :trusted true} "~/Downloads"]}} "/h")
                 [:house :mounts]))))

;; --- every problem at once

(def ^:private breakages
  "Independent mistakes an old relay.edn may hold, each with a phrase of
  its refusal."
  [[#(assoc % :brief {:at "07:00"}) ":brief"]
   [#(assoc % :chat-client ["x"]) ":chat-client"]
   [#(assoc % :notify {}) ":notify"]
   [#(assoc-in % [:harnesses :agentloop :workbench] {}) ":workbench"]
   [#(assoc-in % [:tools "mail"] {:class :edit :labels [] :command ["true"] :consent true}) ":consent"]
   [#(assoc-in % [:tools "pay"] {:class :act :labels [] :command ["true"]}) ":class :act"]
   [#(assoc-in % [:grants "steward"] {:quarantine true}) ":quarantine"]
   [#(assoc-in % [:grants "clerk"] {:tools ["send-mail"]}) "send-mail"]
   [#(assoc % :max-attempts 0) ":max-attempts"]])

(defspec every-problem-in-relay-edn-is-told-at-once 100
  (prop/for-all [picked (gen/fmap (comp vec distinct) (gen/vector (gen/choose 0 (dec (count breakages))) 1 9))]
    (let [settings (reduce (fn [s i] ((first (breakages i)) s)) complete picked)
          e (try (config/resolve-config settings) nil (catch clojure.lang.ExceptionInfo e e))
          msg (ex-message e)
          problems (:problems (ex-data e))]
      (and e (:relay/usage (ex-data e))
           (= (count picked) (count problems))
           (every? (fn [i] (some #(str/includes? % (second (breakages i))) problems)) picked)
           (if (next problems)
             (= (str/split-lines msg)
                (cons (str "relay.edn has " (count problems) " things to change:")
                      (map-indexed #(str (inc %1) ". " %2) problems)))
             (= msg (first problems)))))))

(deftest an-old-relay-edn-is-told-in-one-run
  (let [settings (reduce (fn [s [f]] (f s)) complete breakages)
        e (try (config/resolve-config settings) (catch clojure.lang.ExceptionInfo e e))
        lines (str/split-lines (ex-message e))]
    (is (= "relay.edn has 9 things to change:" (first lines)))
    (is (str/starts-with? (second lines) "1. relay.edn has :brief"))
    (is (= 10 (count lines)))))

(deftest the-old-web-search-tool-is-told-its-one-line-fix
  (let [msg (problem (-> complete
                         (assoc-in [:tools "web"] {:class :egress :labels ["contaminated"] :protocol :envelope
                                                   :command ["webtool"]
                                                   :parameters {:type "object" :properties {:query {:type "string"}}}})
                         (assoc-in [:grants "scout"] {:public true :tools ["web"]})))]
    (is (str/starts-with? msg "Tool web in relay.edn sends query out as free text"))
    (is (str/includes? msg "add :queries [\"query\"]"))))

(deftest the-agents-calendar-has-a-name
  (is (= "Agenda" (config/calendar-name (config/resolve-config complete))))
  (is (= "Harbour" (config/calendar-name (config/resolve-config (assoc complete :calendar {:name "Harbour"})))))
  (is (= "Pat's assistant"
         (config/calendar-name (config/resolve-config
                                (assoc complete :messages {:channels {"mail" {:kind :email :url "smtps://smtp.example.org:465"
                                                                               :from "a@example.org" :from-name "Pat's assistant"
                                                                               :to "p@example.org"}}
                                                           :routes []}))))
      "after the agent's mail, when it has one")
  (doseq [bad [{:name ""} {:name "a\nb"} {:title "x"} "Harbour"]]
    (is (str/includes? (str (problem (assoc complete :calendar bad))) ":calendar is") (pr-str bad))))

(deftest a-check-that-crashes-never-lets-relay-edn-through
  (let [collecting @#'config/collecting
        crash #(throw (ClassCastException. "boom"))
        refuse #(throw (ex-info "Fix :x." {:relay/usage true}))]
    (testing "a crash alone is thrown as it is"
      (is (thrown? ClassCastException (collecting [crash (constantly :value)]))))
    (testing "a crash alongside a refusal reports the refusal"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^Fix :x\.$" (collecting [crash refuse]))))
    (testing "a malformed tool that makes a check crash is refused, not accepted"
      (is (thrown? Exception
                   (config/resolve-config {:timezone "UTC" :default-harness :x
                                           :harnesses {:x {:kind :agentloop :api-key-command ["true"]}}
                                           :tools {"x" 5}}))))))
