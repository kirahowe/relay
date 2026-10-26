(ns relay.staging-test
  "Staging data: what it holds, and where it may be made."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.config :as config]
            [relay.core :as core]
            [relay.inbound :as inbound]
            [relay.staging :as staging]))

(def segment (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcdefg-")) 1 6)))
(def subpath (gen/fmap #(str/join "/" %) (gen/vector segment 0 3)))
(def flags (gen/hash-map :force? gen/boolean :marked? gen/boolean
                         :entries (gen/elements [nil [] ["/x/a"]])))

(def protected
  [["/Users/op/Sync" "never"] ["/Users/op/.config/relay" "config"]
   ["/Users/op/Library/Application Support/relay" "deploy control"]
   ["/Users/op/code/projects/relay" "a live checkout"] ["/data/live" "the operator's data"]])

(defn- problem [m] (staging/target-problem (merge {:protected protected} m)))

(deftest where-staging-data-may-go
  (testing "a plain new directory is fine"
    (is (nil? (problem {:dir "/Users/op/code/stage" :entries nil}))))
  (testing "an empty directory is fine"
    (is (nil? (problem {:dir "/Users/op/code/stage" :entries []}))))
  (testing "a non-empty one needs --force and the staging marker"
    (is (str/includes? (problem {:dir "/Users/op/code/stage" :entries ["/x"]}) "not empty"))
    (is (str/includes? (problem {:dir "/Users/op/code/stage" :entries ["/x"] :force? true}) "will not touch"))
    (is (nil? (problem {:dir "/Users/op/code/stage" :entries ["/x"] :force? true :marked? true}))))
  (testing "the root is refused"
    (is (some? (problem {:dir "/" :entries nil}))))
  (testing "with nothing protected there is nothing to compare with"
    (is (nil? (problem {:dir "/data/live" :protected [] :entries nil})))))

(deftest nothing-in-or-around-a-protected-place-is-ever-allowed
  (let [r (tc/quick-check
           300
           (prop/for-all [sub subpath f flags]
             (let [under (fn [root] (str root (when (seq sub) (str "/" sub))))]
               (and (every? #(some? (problem (assoc f :dir (under (first %))))) protected)
                    ;; a parent of any holds it
                    (some? (problem (assoc f :dir "/Users/op")))
                    (some? (problem (assoc f :dir "/Users/op/code/projects")))
                    (some? (problem (assoc f :dir "/data")))))))]
    (is (:pass? r) (pr-str (:shrunk r)))))

(deftest a-directory-beside-them-is-allowed
  (let [r (tc/quick-check
           300
           (prop/for-all [sub segment]
             (and (nil? (problem {:dir (str "/Users/op/Syncing/" sub) :entries nil}))
                  (nil? (problem {:dir (str "/data/live2/" sub) :entries nil})))))]
    (is (:pass? r) (pr-str (:shrunk r)))))

(defn- temp [] (str (fs/create-temp-dir {:prefix "relay-staging-test"})))

(defn- init! [opts]
  (staging/init! (merge {:repo-root "." :operator-config "/nonexistent/config.edn"} opts)))

(deftest init-makes-a-usable-data-directory
  (let [home (temp)
        dir (str home "/stage")]
    (try
      (let [made (init! {:dir dir :home home})
            sys (inbound/system made)
            state (inbound/current-state sys)]
        (testing "the settings are the ones staging promises"
          (let [c (config/load-config made)]
            (is (= "America/Halifax" (:timezone c)))
            (is (= :file (get-in c [:notify :kind])))
            (is (str/starts-with? (get-in c [:notify :path]) made))
            (is (= ["security" "find-generic-password" "-s" "relay-anthropic" "-w"]
                   (get-in c [:harnesses :agentloop :api-key-command])))
            (is (str/ends-with? (get-in c [:harnesses :agentloop :bin]) "/agentloop/bin/agentloop"))
            (is (fs/absolute? (get-in c [:harnesses :agentloop :bin])))
            (is (= :edit (get-in c [:tools "remind-demo" :class])) "the clerk's reminders are its operator's own")
            (is (not-any? #(= :egress (get-in c [:tools % :class])) (config/role-tools c "clerk"))
                "nothing the clerk holds can send anything out")))
        (testing "artificial roles and facts"
          (is (= #{"hub" "clerk" "scout"} (set (core/active-role-ids state))))
          (is (= 3 (count (:facts state)))))
        (testing "it is marked, so --force may empty it again"
          (is (fs/exists? (fs/path made staging/marker)))))
      (finally (fs/delete-tree home)))))

(deftest init-refuses-and-forces-by-the-rules
  (let [home (temp)
        dir (str home "/stage")]
    (try
      (init! {:dir dir :home home})
      (testing "a second init into it is refused"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not empty" (init! {:dir dir :home home}))))
      (testing "--force starts it afresh"
        (inbound/submit! (inbound/system dir) {:type :memo/send :actor "operator"
                                               :args {:to "hub" :subject "Old" :body "x"}})
        (init! {:dir dir :home home :force? true})
        (is (empty? (core/unread-memos (inbound/current-state (inbound/system dir)) "hub"))))
      (testing "--force does not touch a directory staging did not make"
        (let [other (str home "/other")]
          (fs/create-dirs other)
          (spit (str other "/precious") "keep")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"will not touch"
                                (init! {:dir other :home home :force? true})))
          (is (= "keep" (slurp (str other "/precious"))))))
      (finally (fs/delete-tree home)))))

(deftest init-refuses-protected-places-even-through-links
  (let [home (temp)
        elsewhere (temp)]
    (try
      (fs/create-dirs (str elsewhere "/real-sync/relay"))
      ;; ~/Sync is itself a link, as it can be with a sync tool.
      (fs/create-sym-link (str home "/Sync") (str elsewhere "/real-sync"))
      (fs/create-dirs (str home "/.config/relay"))
      (fs/create-dirs (str home "/Library/Application Support/relay"))
      (fs/create-dirs (str home "/code/projects/relay"))
      (fs/create-dirs (str home "/live"))
      (spit (str home "/config.edn") (pr-str {:data "~/live"}))
      (fs/create-sym-link (str home "/shortcut") (str home "/Sync"))
      (let [refused? (fn [dir] (try (init! {:dir dir :home home :operator-config (str home "/config.edn") :force? true})
                                    false
                                    (catch clojure.lang.ExceptionInfo e (boolean (:relay/usage (ex-data e))))))]
        (is (refused? (str home "/Sync/relay")))
        (is (refused? (str home "/Sync/new")))
        (is (refused? (str elsewhere "/real-sync/new")) "the link's target is protected too")
        (is (refused? (str home "/shortcut/new")) "a link into Sync is seen for what it is")
        (is (refused? (str home "/.config/relay/staging")))
        (is (refused? (str home "/Library/Application Support/relay/paused")) "a directory where the pause file goes")
        (is (refused? (str home "/code/projects/relay/staging")))
        (is (refused? (str home "/code/projects")) "a parent of a live checkout")
        (is (refused? (str home "/live")) "the directory the operator's config names")
        (is (refused? "~/live"))
        (is (not (fs/exists? (str elsewhere "/real-sync/new"))))
        (is (not (refused? (str home "/stage")))))
      (finally (fs/delete-tree home) (fs/delete-tree elsewhere)))))

(deftest an-operator-config-given-by-flag-adds-to-the-real-one
  (let [home (temp)]
    (try
      (fs/create-dirs (str home "/.config/relay"))
      (fs/create-dirs (str home "/somewhere/live-data"))
      (spit (str home "/.config/relay/config.edn") (pr-str {:data (str home "/somewhere/live-data")}))
      (let [refused? (fn [dir & {:as more}]
                       (try (init! (merge {:dir dir :home home} more)) false
                            (catch clojure.lang.ExceptionInfo e (boolean (:relay/usage (ex-data e))))))]
        (is (refused? (str home "/somewhere/live-data/new")))
        (is (refused? (str home "/somewhere/live-data/new") :operator-config (str home "/no-such-file.edn"))
            "naming a file that does not exist does not switch the real config off")
        (spit (str home "/config2.edn") (pr-str {:data (str home "/other")}))
        (is (refused? (str home "/other/new") :operator-config (str home "/config2.edn"))
            "and a second file protects its own directory too"))
      (finally (fs/delete-tree home)))))
