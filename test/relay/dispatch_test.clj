(ns relay.dispatch-test
  "Drives the dispatcher against a throwaway data directory with a stand-in
  harness: a shell script that does whatever the test wrote to its plan,
  calling the real `relay tool` command as a model would, and exits with the
  code the test chose."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [relay.core :as core]
            [relay.dispatch :as dispatch]
            [relay.inbound :as inbound]
            [relay.log :as log])
  (:import (java.time Instant)))

(def relay-bin (str (fs/absolutize "bin/relay")))

(def ^:dynamic *dir* nil)
(def ^:dynamic *sys* nil)

(defn- script! [path body]
  (spit path (str "#!/bin/sh\n" body "\n"))
  (fs/set-posix-file-permissions path "rwxr-xr-x")
  (str path))

(defn- plan!
  "Sets what the stand-in harness does on its next runs."
  [body exit-code]
  (spit (str *dir* "/plan") (str body "\nexit " exit-code "\n")))

(def close-everything
  (str "ids=$(\"$RELAY_BIN\" tool inbox </dev/null | sed -n 's/^Memo \\([0-9]*\\):.*/\\1/p')\n"
       "for id in $ids; do\n"
       "  echo \"{\\\"memo\\\": $id, \\\"outcome\\\": \\\"Filed it.\\\"}\" | \"$RELAY_BIN\" tool memo_close\n"
       "done"))

(def do-nothing ":")

(defn- report! [m]
  (str "cat > \"$RELAY_REPORT\" <<'JSON'\n" (json/generate-string m) "\nJSON"))

(defn- write-config! [extra]
  (spit (str *dir* "/data/relay.edn")
        (pr-str (merge {:timezone "UTC"
                        :default-harness :fake
                        :harnesses {:fake {:kind :script :command [(str *dir* "/harness")]}}
                        :notify {:kind :command :command [(str *dir* "/notify")]}
                        :max-attempts 2
                        :grants {"hub" {:may-add true}}}
                       extra))))

(defn- system [] (inbound/system (str *dir* "/data")))

(use-fixtures :each
  (fn [run-test]
    (let [dir (str (fs/create-temp-dir {:prefix "relay-dispatch-test"}))
          before (System/getProperty "relay.bin")]
      (System/setProperty "relay.bin" relay-bin)
      (script! (str dir "/harness") (str "env > \"" dir "/env\"\n"
                                         "cat > \"" dir "/prompt\"\n"
                                         ". \"" dir "/plan\""))
      (script! (str dir "/notify") (str "echo \"$1\" >> \"" dir "/notifications\""))
      (log/init! (str dir "/data"))
      (binding [*dir* dir]
        (write-config! {})
        (binding [*sys* (system)]
          (inbound/submit! *sys* {:type :role/add :actor "operator" :args {:id "hub" :title "Hub" :instructions "Run relay."}})
          (inbound/submit! *sys* {:type :role/add :actor "operator" :args {:id "clerk" :title "Clerk" :instructions "File things."}})
          (plan! close-everything 0)
          (try (run-test)
               (finally
                 (if before (System/setProperty "relay.bin" before) (System/clearProperty "relay.bin"))
                 (fs/delete-tree dir))))))))

(defn- state [] (inbound/current-state *sys*))
(defn- notifications []
  (let [f (str *dir* "/notifications")]
    (if (fs/exists? f) (str/split-lines (slurp f)) [])))
(defn- memo! [to subject]
  (:seq (first (inbound/submit! *sys* {:type :memo/send :actor "operator"
                                       :args {:to to :subject subject :body "Body."}}))))

(deftest nothing-to-do
  (is (= :idle (:result (dispatch/wake! *sys* "clerk" {})))))

(deftest a-wake-the-role-finishes
  (let [id (memo! "clerk" "File this")
        result (dispatch/wake! *sys* "clerk" {})]
    (is (= {:result :ran :outcome "ran" :left-open 0} (dissoc result :wake)))
    (is (= :read (:status (core/memo (state) id))))
    (is (= "Filed it." (:outcome (core/memo (state) id))))
    (is (= "clerk" (:closed-by (core/memo (state) id))))
    (testing "the harness was given the prompt, and its transcript is kept"
      (is (str/includes? (slurp (str *dir* "/prompt")) "Memo 3: File this"))
      (is (fs/exists? (get-in (state) [:wakes (:wake result) :transcript]))))))

(deftest the-harness-runs-in-a-clean-environment-as-the-role
  (memo! "clerk" "x")
  (dispatch/wake! *sys* "clerk" {})
  (let [env (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp (str *dir* "/env")))))]
    (is (= "clerk" (get env "RELAY_ROLE")))
    (is (= relay-bin (get env "RELAY_BIN")))
    (is (some? (get env "RELAY_WAKE")))
    (testing "nothing leaks in from whoever started relay"
      (is (not-any? #(re-find #"^(CLAUDE|ANTHROPIC|OPENAI|AWS_|GITHUB)" %) (keys env))))))

(deftest a-clean-exit-that-closes-nothing-is-a-failed-attempt-then-a-dead-letter
  (let [id (memo! "clerk" "File this")]
    (plan! do-nothing 0)
    (is (= 1 (:left-open (dispatch/wake! *sys* "clerk" {}))))
    (is (= 1 (:attempts (core/memo (state) id))))
    (dispatch/wake! *sys* "clerk" {})
    (is (= :dead (:status (core/memo (state) id))))
    (let [[report] (core/unread-memos (state) "hub")]
      (is (str/starts-with? (:subject report) "Dead letter")))
    (is (empty? (notifications)) "the operator is not bothered")))

(deftest a-rate-limited-run-pauses-and-the-next-wake-resumes-its-session
  (let [id (memo! "clerk" "File this")]
    (plan! (report! {:outcome "limited" :session "s-1" :resets-at "2099-01-01T00:00:00Z"}) 1)
    (is (= "limited" (:outcome (dispatch/wake! *sys* "clerk" {}))))
    (is (zero? (:attempts (core/memo (state) id))))
    (is (some? (core/paused-until (state) "fake" (Instant/now))))
    (is (empty? (dispatch/tick! *sys* {:spawn! (fn [& _] (throw (ex-info "should not spawn" {})))})))
    (plan! (str "echo \"$RELAY_RESUME\" > \"" *dir* "/resumed\"\n" close-everything) 0)
    (is (= "ran" (:outcome (dispatch/wake! *sys* "clerk" {}))))
    (is (= "s-1" (str/trim (slurp (str *dir* "/resumed")))))
    (is (str/includes? (slurp (str *dir* "/prompt")) "cut short"))
    (is (nil? (core/paused-until (state) "fake" (Instant/now))))))

(deftest a-harness-that-needs-a-person-tells-the-operator-once
  (memo! "clerk" "File this")
  (plan! (report! {:outcome "needs-attention" :said "Not logged in"}) 1)
  (dispatch/wake! *sys* "clerk" {})
  (dispatch/wake! *sys* "clerk" {})
  (is (= ["fake needs you before relay can work"] (notifications))))

(deftest a-run-past-its-deadline-is-stopped-and-counts-as-a-crash
  (write-config! {:wake-timeout-minutes 0.03})
  (binding [*sys* (system)]
    (let [id (memo! "clerk" "Slow")]
      (plan! "sleep 30" 0)
      (let [start (System/currentTimeMillis)
            result (dispatch/wake! *sys* "clerk" {})
            wake (get-in (state) [:wakes (:wake result)])]
        (is (< (- (System/currentTimeMillis) start) 15000))
        (is (= "timeout" (:exit wake)))
        (is (true? (:crashed wake)))
        (is (= 1 (:attempts (core/memo (state) id))))))))

(deftest a-missing-harness-is-reported-not-crashed-on
  (memo! "clerk" "x")
  (write-config! {:harnesses {}})
  (binding [*sys* (system)]
    (is (= "needs-attention" (:outcome (dispatch/wake! *sys* "clerk" {}))))
    (is (= 1 (count (notifications))))))

(deftest memos-to-the-operator-notify-and-a-failed-push-is-recorded
  (memo! "operator" "Approve the filing?")
  (is (= ["Approve the filing?"] (notifications)))
  (is (= [:delivered] (map :delivery (vals (:notifications (state))))))
  (script! (str *dir* "/notify") "exit 3")
  (memo! "operator" "Another")
  (is (= #{:delivered :failed} (set (map :delivery (vals (:notifications (state)))))))
  (testing "the memo is in the log either way"
    (is (= 2 (count (core/unread-memos (state) "operator"))))))

(deftest a-wake-whose-process-died-is-reaped-by-the-tick
  (let [id (memo! "clerk" "x")
        dead (p/process ["true"])
        _ @dead
        pid (.pid ^Process (:proc dead))]
    (inbound/submit! *sys* {:type :wake/start :actor "dispatcher"
                            :args {:role "clerk" :pid pid :pid-start "1970-01-01T00:00:00Z"}})
    (is (core/running? (state) "clerk"))
    (dispatch/tick! *sys* {:spawn! (fn [& _])})
    (is (not (core/running? (state) "clerk")))
    (is (= 1 (:attempts (core/memo (state) id))))))

(deftest a-live-wake-is-not-reaped
  (memo! "clerk" "x")
  (inbound/submit! *sys* {:type :wake/start :actor "dispatcher"
                          :args (merge {:role "clerk"} (dispatch/current-process))})
  (dispatch/tick! *sys* {:spawn! (fn [& _])})
  (is (core/running? (state) "clerk")))

(deftest the-tick-wakes-roles-in-their-own-processes
  (let [a (memo! "clerk" "One")
        b (memo! "hub" "Two")]
    (is (= ["clerk" "hub"] (dispatch/tick! *sys*)))
    (loop [n 0]
      (when (and (< n 150) (some #(not= :read (:status (core/memo (state) %))) [a b]))
        (Thread/sleep 200)
        (recur (inc n))))
    (is (= [:read :read] (map #(:status (core/memo (state) %)) [a b])))
    (is (= 2 (count (filter #(= :ended (:status %)) (vals (:wakes (state)))))))))

(deftest two-wakes-of-one-role-at-once-are-refused
  (memo! "clerk" "x")
  (plan! (str "sleep 2\n" close-everything) 0)
  (let [env {"RELAY_DATA" (str *dir* "/data")}
        procs (doall (for [_ (range 3)]
                       (p/process [relay-bin "wake" "clerk"] {:extra-env env :out :string :err :string})))
        results (map (comp str/trim :out deref) procs)]
    (is (= 1 (count (filter #(str/starts-with? % "ran") results))) (pr-str results))
    (is (= 2 (count (filter #(str/starts-with? % "busy") results))) (pr-str results))))

(deftest a-role-can-only-call-the-tools-it-is-offered
  (write-config! {:tools {"echo" {:class :read :description "Echo." :command ["echo" "said:"]}}
                  :grants {"hub" {:may-add true :tools ["echo"]}}})
  (let [run (fn [role tool args]
              (p/shell {:out :string :err :string :continue true :in (json/generate-string args)
                        :extra-env {"RELAY_DATA" (str *dir* "/data") "RELAY_ROLE" role}}
                       relay-bin "tool" tool))]
    (is (= "said: hi" (str/trim (:out (run "hub" "echo" {:args ["hi"]})))))
    (let [{:keys [exit out]} (run "clerk" "echo" {:args ["hi"]})]
      (is (= 1 exit))
      (is (str/includes? out "no tool called echo")))
    (testing "registry tools need the grant"
      (is (= 1 (:exit (run "clerk" "role_add" {:id "x" :title "X" :instructions "c"}))))
      (is (zero? (:exit (run "hub" "role_add" {:id "x" :title "X" :instructions "c"})))))
    (testing "a role cannot act as anyone else, even the operator"
      (let [{:keys [exit err]} (p/shell {:out :string :err :string :continue true :in ""
                                         :extra-env {"RELAY_DATA" (str *dir* "/data") "RELAY_ROLE" "clerk"}}
                                        relay-bin "tick")]
        (is (= 1 exit))
        (is (str/includes? err "Only the operator"))))))

(deftest knowledge-goes-through-the-tools-and-notes-reach-the-next-wake
  (let [run (fn [tool args]
              (p/shell {:out :string :err :string :continue true :in (json/generate-string args)
                        :extra-env {"RELAY_DATA" (str *dir* "/data") "RELAY_ROLE" "clerk"}}
                       relay-bin "tool" tool))]
    (is (str/starts-with? (:out (run "fact_add" {:text "The cabinet key is in the drawer."
                                                 :topic "office" :valid_from "2026-01-01"}))
                          "Recorded fact"))
    (is (str/includes? (:out (run "facts" {:topic "office"})) "The cabinet key is in the drawer."))
    (is (= 1 (:exit (run "fact_correct" {:fact 999 :why "x" :text "y"}))))
    (is (zero? (:exit (run "note_write" {:name "keys" :text "Ask before moving the key."}))))
    (let [state (state)
          request (dispatch/wake-request *sys* state {:id 1 :role "clerk" :memos [] :harness "fake"})]
      (is (str/includes? (:system-prompt request) "## keys\n\nAsk before moving the key."))
      (is (some #{"fact_add" "doc_put" "journal_write"} (map :name (:tools request))))
      (testing "and a role is told what it is granted"
        (is (str/includes? (:system-prompt request) "relay's own tools and nothing beyond them"))
        (let [hub (dispatch/wake-request *sys* state {:id 1 :role "hub" :memos [] :harness "fake"})]
          (is (str/includes? (:system-prompt hub) "`role_add`"))
          (is (str/includes? (:system-prompt hub) "comes to you as dead letters")))))))
