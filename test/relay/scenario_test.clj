(ns relay.scenario-test
  "The scenario runner's pure parts, and every scenario run against the mock
  model."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cli :as relay]
            [relay.scenario :as scenario]
            [relay.scenario-runner :as runner]
            [relay.staging :as staging]
            [relay.test-support :as support]))

(def scalar (gen/one-of [gen/small-integer gen/string-alphanumeric gen/keyword gen/boolean]))
(def item (gen/map gen/keyword (gen/one-of [scalar (gen/map gen/keyword scalar)]) {:max-elements 5}))

(deftest a-map-pattern-matches-anything-that-contains-it
  (let [r (tc/quick-check
           300
           (prop/for-all [m item extra item]
             (let [bigger (merge extra m)
                   sub (select-keys m (take 2 (keys m)))]
               (and (scenario/match? m bigger)
                    (scenario/match? sub bigger)
                    (scenario/match? {} bigger)))))]
    (is (:pass? r) (pr-str (:shrunk r)))))

(deftest a-pattern-with-a-changed-value_does_not_match
  (let [r (tc/quick-check
           300
           (prop/for-all [m (gen/such-that seq item) v scalar]
             (let [k (first (keys m))
                   other (if (= v (get m k)) ::other v)]
               (not (scenario/match? (assoc m k other) m)))))]
    (is (:pass? r) (pr-str (:shrunk r)))))

(deftest value-operators
  (is (scenario/match? {:s [:includes "ell"]} {:s "hello"}))
  (is (not (scenario/match? {:s [:includes "ell"]} {:s nil})))
  (is (scenario/match? {:s [:regex "^h.l+o$"]} {:s "hello"}))
  (is (scenario/match? {:s [:not [:includes "x"]]} {:s "hello"}))
  (is (scenario/match? {:s [:any "a" "hello"]} {:s "hello"}))
  (is (not (scenario/match? {:s [:all [:includes "h"] [:includes "z"]]} {:s "hello"})))
  (is (not (scenario/match? {:missing 1} {})) "a missing key is not a match")
  (is (scenario/match? {:missing nil} {}) "but nil matches nil"))

(def world
  {:state {:memos {3 {:id 3 :from "operator" :to "hub" :subject "A" :status :read}
                   4 {:id 4 :from "hub" :to "operator" :subject "B" :status :unread}}
           :holds {9 {:id 9 :role "clerk" :tool "post" :status :open}}}
   :events [{:seq 1 :type "memo/sent" :data {:to "hub"}}]
   :sent [{:title "Memo for you"}]})

(deftest assertions-say-what-was-missing
  (is (nil? (scenario/check world [:has :memo {:subject "A" :status :read}])))
  (is (nil? (scenario/check world [:none :memo {:subject "A" :status :unread}])))
  (is (nil? (scenario/check world [:count :memo {:to "operator"} 1])))
  (is (nil? (scenario/check world [:has :hold {:tool "post"}])))
  (is (nil? (scenario/check world [:has :event {:type "memo/sent" :data {:to "hub"}}])))
  (is (nil? (scenario/check world [:has :sent {:title [:includes "Memo"]}])))
  (let [msg (scenario/check world [:has :memo {:subject "Z"}])]
    (is (str/includes? msg "Expected a memo matching") msg)
    (is (str/includes? msg ":subject \"A\"") "it shows what there was"))
  (is (some? (scenario/check world [:none :hold {:role "clerk"}])))
  (is (some? (scenario/check world [:count :memo {} 3])))
  (is (str/includes? (scenario/check world [:has :wombat {}]) "Unknown")))

(deftest memo-references-resolve-against-the-state
  (is (= 3 (scenario/memo-id (:state world) {:subject "A"})))
  (is (= {:memo 4 :list [3]}
         (scenario/resolve-refs (:state world) {:memo {:memo-id {:subject "B"}} :list [{:memo-id {:to "hub"}}]})))
  (is (thrown? clojure.lang.ExceptionInfo
               (scenario/resolve-refs (:state world) {:memo {:memo-id {:subject "none"}}}))))

(deftest scripted-replies-come-in-order-per-role-then-run-out
  (let [r (tc/quick-check
           200
           (prop/for-all [hub (gen/vector (gen/fmap (fn [s] {:text s}) gen/string-alphanumeric) 0 4)
                          clerk (gen/vector (gen/fmap (fn [s] {:text s}) gen/string-alphanumeric) 0 4)
                          order (gen/vector (gen/elements ["hub" "clerk"]) 0 12)]
             (let [script {"hub" hub "clerk" clerk}
                   got (loop [used {} [role & more] order out []]
                         (if-not role
                           out
                           (let [[reply used'] (scenario/next-reply script used role)]
                             (recur used' more (conj out [role reply])))))
                   expected (fn [role] (let [mine (filter #(= role (first %)) got)]
                                         (map second mine)))]
               (and (= (take (count (expected "hub")) (concat hub (repeat scenario/done))) (expected "hub"))
                    (= (take (count (expected "clerk")) (concat clerk (repeat scenario/done))) (expected "clerk"))))))]
    (is (:pass? r) (pr-str (:shrunk r))))
  (testing "a role with no script, or none known, gets the default"
    (is (= scenario/done (first (scenario/next-reply {} {} "hub"))))
    (is (= scenario/done (first (scenario/next-reply {"hub" [{:text "x"}]} {} nil))))))

(deftest the-role-is-read-from-the-system-prompt
  (is (= "hub" (scenario/role-of {:system [{:type "text" :text "Shared.\nYour id is `hub`.\n"}]})))
  (is (= "clerk" (scenario/role-of {:system "Your id is `clerk`"})))
  (is (nil? (scenario/role-of {:system []}))))

(deftest a-malformed-scenario-is-described
  (let [ok {:steps [{:tick 1}] :assert [[:has :memo {}]]}]
    (is (empty? (scenario/problems ok)))
    (is (seq (scenario/problems (dissoc ok :assert))))
    (is (seq (scenario/problems (assoc ok :steps [{:tick 0}]))))
    (is (seq (scenario/problems (assoc ok :steps [{:tick 1 :run ["x"]}]))))
    (is (seq (scenario/problems (assoc ok :assert [[:has :wombat {}]]))))
    (is (seq (scenario/problems (assoc ok :model {"hub" {:text "x"}}))))))

;; --- whole scenarios

(deftest every-shipped-scenario-is-well-formed
  (doseq [f (runner/scenario-files)]
    (is (empty? (scenario/problems (clojure.edn/read-string (slurp f)))) f)))

(deftest every-scenario-passes-against-the-mock-model
  (if-not support/agentloop-bin
    (println "SKIPPED: agentloop is not built; run make -C ../agentloop build")
    (doseq [f (runner/scenario-files)]
      (let [r (runner/run-scenario! f {})]
        (is (:pass? r) (str (runner/scenario-name f) ": " (str/join " | " (:failures r))
                            (when (:dir r) (str " (data in " (:dir r) ")"))))))))

(deftest a-scenario-whose-assertion-is-false-fails
  (if-not support/agentloop-bin
    (println "SKIPPED: agentloop is not built; run make -C ../agentloop build")
    (let [dir (str (fs/create-temp-dir {:prefix "relay-scenario-test"}))
          file (str dir "/wrong.edn")]
      (try
        (spit file (pr-str {:steps [{:run ["memo" "hub" "-s" "Hello" "-m" "x"]} {:tick 1}]
                            :model {"hub" [{:text "I did nothing."}]}
                            :assert [[:has :memo {:subject "Hello" :status :read}]]}))
        (let [r (runner/run-scenario! file {})]
          (is (false? (:pass? r)))
          (is (some #(str/includes? % "Expected a memo matching") (:failures r)))
          (when (:dir r) (fs/delete-tree (:dir r))))
        (finally (fs/delete-tree dir))))))

;; --- a scenario can only act on its own data

(deftest a-step-naming-a-data-directory-is-rejected
  (let [with-run (fn [args] {:steps [{:run args}] :assert [[:has :memo {}]]})]
    (doseq [args [["memo" "hub" "--data" "/live"]
                  ["memo" "hub" "--data=/live"]
                  ["memo" "hub" "--data" ""]
                  ["--data" "/live" "memo" "hub"]
                  ["memo" "hub" "-data" "/live"]
                  ["status" "--pause-file" "/outside/secret"]
                  ["status" "--pause-file=/outside/secret"]
                  ["status" "--pause-file=/inside\n/../../secret"]
                  ["status" "--data=/live\n"]]]
      (is (some #(str/includes? % "names a data directory") (scenario/problems (with-run args))) (pr-str args)))
    (is (empty? (scenario/problems (with-run ["memo" "hub" "-s" "database" "-m" "the data is fine"]))))))

(deftest a-step-may-only-run-commands-that-stay-in-the-data-directory
  (let [with-run (fn [args] {:steps [{:run args}] :assert [[:has :memo {}]]})]
    (doseq [args [["export" "/live/new"] ["acp" "hub" "--trace" "/live/file"] ["init"] ["chat" "hub"]
                  ["tool" "--role" "hub" "memo_send"] ["--untrusted" "memo" "hub"] ["nonsense"]]]
      (is (some #(or (str/includes? % "must start with one of the commands")
                     (str/includes? % "path option"))
                (scenario/problems (with-run args)))
          (pr-str args)))
    (is (every? #(empty? (scenario/problems (with-run [% "x"]))) scenario/allowed-commands))))

(deftest no-spelling-of-a-path-option-gets-past-the-runner
  (let [staging (str (fs/create-temp-dir {:prefix "relay-scenario-own"}))
        outside (str (fs/create-temp-dir {:prefix "relay-scenario-secret"}))
        secret (str outside "/paused")]
    (try
      (spit secret "the secret reason")
      (let [run #(#'runner/run-command! {:dir staging :clock (constantly (java.time.Instant/now))} % nil)
            shown (fn [r] (str/join "\n" (concat (:out r) (:err r))))]
        (doseq [opt ["pause-file" "data" "trace"]
                [prefix joiner] [["--" " "] ["-" " "] [":" " "] ["--" "="] ["-" "="] [":" "="] ["--no-" " "]]
                :let [args (if (= joiner "=")
                             ["status" (str prefix opt "=" secret)]
                             ["status" (str prefix opt) secret])]]
          (let [r (run args)]
            (is (= 1 (:exit r)) (pr-str args))
            (is (not (str/includes? (shown r) "secret reason")) (pr-str args))
            (is (str/includes? (shown r) "Refused") (pr-str args))))
        (testing "reading a scenario refuses them too, whatever the spelling"
          (let [file (str staging "/s.edn")]
            (spit file (pr-str {:steps [{:run ["status" ":pause-file" secret]}] :assert [[:has :memo {}]]}))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"path option" (runner/read-scenario file))))))
      (finally (fs/delete-tree staging) (fs/delete-tree outside)))))

(deftest the-parsed-options-guard-stands-without-the-text-screen
  (let [staging (str (fs/create-temp-dir {:prefix "relay-scenario-own"}))
        outside (str (fs/create-temp-dir {:prefix "relay-scenario-secret"}))]
    (try
      (spit (str outside "/paused") "the secret reason")
      ;; With the pattern that screens argument text switched off, relay's own
      ;; reading of the options still refuses every spelling.
      (with-redefs [scenario/path-option? (constantly false)]
        (let [run #(#'runner/run-command! {:dir staging :clock (constantly (java.time.Instant/now))} % nil)]
          (doseq [args [["status" "--pause-file" (str outside "/paused")]
                        ["status" ":pause-file" (str outside "/paused")]
                        ["status" "--pause-file=x"]
                        ["memo" "hub" "--data" outside]
                        ["status" ":trace" "x"]
                        ["status" "--no-data"]]]
            (let [r (run args)]
              (is (= 1 (:exit r)) (pr-str args))
              (is (some #(or (str/includes? % "path option") (str/includes? % "cannot be parsed")) (:err r))
                  (pr-str [args (:err r)]))))))
      (finally (fs/delete-tree staging) (fs/delete-tree outside)))))

(deftest what-the-guard-lets-through-never-reaches-another-path-in-the-combined-line
  ;; The guard reads the step's arguments alone; the command is run with
  ;; --data <staging> in front. For any mix of tokens that looks like
  ;; options, if the guard passes the arguments, the combined line sets no
  ;; pause file or trace, and its data directory is the staging one.
  (let [toks ["memo" "hub" "--data" "--data=x" ":data" "x" "-s" "--subject" "--" "--pause-file" ":pause-file"
              "--trace" "-m" "--no-data" "--tail" "3" "-n" "--json" "status" "-d" "--d" "--da" "-:data"
              "--:data" "-data" ":trace" "--trace=" "-p" "-" ":" "=" "--=" "--data=" ":data=" "\n"]
        rnd (java.util.Random. 42)
        pick #(nth toks (.nextInt rnd (count toks)))
        dir "/tmp/relay-guard-fuzz"
        bad (atom [])]
    (dotimes [_ 5000]
      (let [args (vec (repeatedly (inc (.nextInt rnd 6)) pick))
            line (into ["--data" dir] args)
            guarded? (or (some scenario/path-option? args) (seq (runner/path-options args)))
            opts (try (relay/parsed-options line) (catch Exception _ ::unparsable))]
        (when (and (not guarded?) (map? opts)
                   (or (contains? opts :pause-file) (contains? opts :trace) (not= dir (:data opts))))
          (swap! bad conj [args opts]))))
    (is (empty? @bad) (pr-str (take 5 @bad)))))

(deftest a-status-in-a-scenario-reads-no-file-outside-staging
  (let [staging (str (fs/create-temp-dir {:prefix "relay-scenario-own"}))
        outside (str (fs/create-temp-dir {:prefix "relay-scenario-secret"}))
        secret (str outside "/paused")]
    (try
      (spit secret "the secret reason")
      (staging/init! {:dir (str staging "/data") :check? false})
      (let [run #(#'runner/run-command! {:dir (str staging "/data") :clock (constantly (java.time.Instant/now))} % nil)
            shown (fn [r] (str/join "\n" (concat (:out r) (:err r))))]
        (testing "a step that names the file is refused before it reads it"
          (is (not (str/includes? (shown (run ["status" "--pause-file" secret])) "secret reason"))))
        (testing "an ordinary status looks only in staging, whatever the operator's own default is"
          (let [r (run ["status"])]
            (is (= 0 (:exit r)))
            (is (not (str/includes? (shown r) "paused"))))))
      (finally (fs/delete-tree staging) (fs/delete-tree outside)))))

(deftest the-runner-refuses-any-command-that-would-reach-another-data-directory
  (let [staging (str (fs/create-temp-dir {:prefix "relay-scenario-own"}))
        other (str (fs/create-temp-dir {:prefix "relay-scenario-other"}))]
    (try
      (let [refused? (fn [args]
                       (let [r (#'runner/run-command! {:dir staging :clock #(java.time.Instant/now)} args nil)]
                         (and (= 1 (:exit r)) (some #(str/includes? % "Refused") (:err r)))))]
        (is (refused? ["memo" "hub" "--data" other]))
        (is (refused? ["memo" "hub" "--data=" other]))
        (is (refused? ["memo" "hub" "--data" ""]) "an empty --data falls back to nowhere, not the operator's config")
        (is (refused? ["release" "1" "--data" other]) "the confirmation is never answered for another directory")
        (testing "commands that write to a path they are given"
          (let [victim (str other "/victim")
                target (str other "/exported")]
            (spit victim "x")
            (fs/set-posix-file-permissions victim "rwxr-xr-x")
            (is (refused? ["acp" "missing-role" "--trace" victim]))
            (is (= "rwxr-xr-x" (fs/posix->str (fs/posix-file-permissions victim))) "its permissions are untouched")
            (is (refused? ["export" target]))
            (is (not (fs/exists? target)))))
        (is (not (fs/exists? (str other "/log"))) "nothing was written there"))
      (finally (fs/delete-tree staging) (fs/delete-tree other)))))

;; --- the real spawn

(deftest a-wake-the-real-spawn-cannot-start-fails-the-scenario
  (if-not support/agentloop-bin
    (println "SKIPPED: agentloop is not built; run make -C ../agentloop build")
    (let [root (str (fs/create-temp-dir {:prefix "relay-no-bin"}))]
      (try
        ;; A checkout without bin/relay: nothing can start a wake.
        (let [r (runner/run-scenario! "test/scenarios/wake-through-a-subprocess.edn" {:repo-root root})]
          (is (false? (:pass? r)))
          (is (some #(str/includes? % "threw") (:failures r)) (pr-str (:failures r)))
          (when (:dir r) (fs/delete-tree (:dir r))))
        (finally (fs/delete-tree root))))))

(deftest concurrent-requests-each-get-their-own-place-in-the-script
  (let [dir (str (fs/create-temp-dir {:prefix "relay-scripted"}))]
    (try
      (staging/init! {:dir (str dir "/d") :check? false})
      (let [script {"hub" (mapv (fn [i] {:text (str "hub-" i)}) (range 200))
                    "clerk" (mapv (fn [i] {:text (str "clerk-" i)}) (range 200))}
            {:keys [responder]} (#'runner/scripted-model script (str dir "/d"))
            request (fn [role] {:system [{:text (str "Your id is `" role "`.")}]})
            results (->> (for [role ["hub" "clerk"]]
                           (future (doall (repeatedly 100 #(vector role (:text (responder (request role))))))))
                         doall (mapcat deref))]
        (doseq [role ["hub" "clerk"]]
          (is (= (mapv #(str role "-" %) (range 100))
                 (mapv second (filter #(= role (first %)) results)))
              "every request got a distinct reply, in order")))
      (finally (fs/delete-tree dir)))))
