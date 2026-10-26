(ns relay.external-tool-test
  "Plain external tools and the timed runner they share with the morning
  brief (relay.tools/run-external!), run against stand-in scripts."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [relay.config :as config]
            [relay.harness :as harness]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.tools :as tools]))

(def ^:dynamic *dir* nil)

(use-fixtures :each
  (fn [run-test]
    (let [dir (str (fs/create-temp-dir {:prefix "relay-external-tool"}))]
      (try (binding [*dir* dir] (run-test))
           (finally (fs/delete-tree dir))))))

(defn- script! [n body]
  (let [path (str *dir* "/" n)]
    (spit path (str "#!/bin/sh\n" body "\n"))
    (fs/set-posix-file-permissions path "rwxr-xr-x")
    path))

(defn- alive? [pid]
  (zero? (:exit (p/shell {:continue true :out :string :err :string} "kill" "-0" (str pid)))))

(defn- gone-within? [pid ms]
  (loop [left ms]
    (cond (not (alive? pid)) true
          (neg? left) false
          :else (do (Thread/sleep 50) (recur (- left 50))))))

(defn- hanging-tool!
  "A tool that starts a child which outlives it unless its tree is ended,
  writes the child's pid, and then hangs."
  []
  (fs/delete-if-exists (str *dir* "/child.pid"))
  (script! "hang" (str "sleep 60 &\necho $! > " *dir* "/child.pid\nsleep 60")))

(defn- child-pid []
  (loop [n 0]
    (let [f (str *dir* "/child.pid")]
      (if (and (fs/exists? f) (not (str/blank? (slurp f))))
        (parse-long (str/trim (slurp f)))
        (when (< n 100) (Thread/sleep 20) (recur (inc n)))))))

(deftest a-command-with-a-slash-runs-as-written
  (let [ok (script! "ok" "echo \"$@\"")]
    (testing "relative to relay's directory"
      (is (= {:out "a\n" :labels []}
             (tools/run-external! "t" {:command [(str (fs/relativize (fs/cwd) ok))] :labels []} ["a"]))))
    (testing "a path that is not there cannot start"
      (is (:error (binding [*err* (java.io.StringWriter.)]
                    (tools/run-external! "t" {:command [(str *dir* "/missing")] :labels []} [])))))))

(deftest the-runner-honours-the-tool-s-time-limit
  (testing "a tool that answers in time"
    (is (= {:out "a b\n" :labels ["private"]}
           (tools/run-external! "t" {:command [(script! "ok" "echo \"$@\"")] :labels ["private"]} ["a" "b"]))))
  (testing "a tool that hangs is stopped at its :timeout-seconds, with its whole process tree"
    (let [started (System/nanoTime)
          r (binding [*err* (java.io.StringWriter.)]
              (tools/run-external! "t" {:command [(hanging-tool!)] :labels [] :timeout-seconds 1} []))
          took-ms (/ (- (System/nanoTime) started) 1e6)]
      (is (= {:error "timed out after 1 seconds" :timed-out true} r))
      (is (< took-ms 10000) "the tool's own limit, not the default")
      (is (some? (child-pid)))
      (is (gone-within? (child-pid) 3000) "the child it started is ended too")))
  (testing "without :timeout-seconds the caller's default applies"
    (let [r (binding [*err* (java.io.StringWriter.)]
              (tools/run-external! "t" {:command [(hanging-tool!)] :labels []} [] 1))]
      (is (:timed-out r))
      (is (gone-within? (child-pid) 3000))))
  (testing "the default is the envelope protocol's"
    (is (= 600 tools/default-timeout-seconds (tools/timeout-seconds {})))
    (is (= 5 (tools/timeout-seconds {:timeout-seconds 5}))))
  (testing "a failure and a tool that cannot start say why, and print only to stderr"
    (let [err (java.io.StringWriter.)
          r (binding [*err* err]
              (tools/run-external! "t" {:command [(script! "bad" "echo secret-out; echo secret-err >&2; exit 3")]
                                        :labels []} []))]
      (is (= {:error "exited 3"} r))
      (is (str/includes? (str err) "secret-out"))
      (is (str/includes? (str err) "secret-err")))
    (is (str/starts-with? (:error (binding [*err* (java.io.StringWriter.)]
                                    (tools/run-external! "t" {:command [(str *dir* "/missing")] :labels []} [])))
                          "could not start"))))

(deftest the-wrapper-is-quiet-about-a-locale-that-is-not-installed
  ;; Regression (relay#33): perl warned on stderr about LANG, which only
  ;; showed in failure logs. The command still gets the LANG it was given
  ;; and never sees the wrapper's own switch.
  (let [env (tools/group-env (assoc (harness/base-env {}) "LANG" "xx_YY.UTF-8"))
        proc @(p/process (conj (vec @#'tools/in-own-group) "/usr/bin/env")
                         {:out :string :err :string :env env})]
    (is (zero? (:exit proc)))
    (is (= "" (:err proc)))
    (is (str/includes? (:out proc) "LANG=xx_YY.UTF-8"))
    (is (not (str/includes? (:out proc) "PERL_BADLANG")))))

(defn- with-tool [spec f]
  (let [data (str (fs/create-temp-dir {:dir *dir*}) "/data")]
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :a
                   :harnesses {:a {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}} :tools {"slow" spec} :grants {"clerk" {:tools ["slow"]}}}))
    (let [sys (inbound/system data)]
      (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "clerk" :title "C" :instructions "x"}})
      (f sys))))

(deftest a-plain-tool-that-runs-out-of-time-says-so
  (with-tool {:class :read :labels ["private"] :description "Slow." :command [(hanging-tool!)] :timeout-seconds 1}
    (fn [sys]
      (let [r (binding [*err* (java.io.StringWriter.)]
                (tools/call! sys "clerk" "slow" {:args []} [] "c1"))]
        (is (= {:error "The tool slow timed out after 1 seconds." :labels ["private"]} r))
        (is (gone-within? (child-pid) 3000)))))
  (testing "one that fails is told only that it failed"
    (with-tool {:class :read :labels [] :description "Bad." :command [(script! "bad" "echo hidden; exit 1")]}
      (fn [sys]
        (is (= {:error "The tool slow failed." :labels []}
               (binding [*err* (java.io.StringWriter.)]
                 (tools/call! sys "clerk" "slow" {:args []} [] "c1"))))))))

(deftest a-tool-s-time-limit-is-checked
  (let [base {:timezone "UTC" :default-harness :x :harnesses {:x {:kind :agentloop :api-key-command ["true"]}}}
        ok? (fn [t] (try (config/resolve-config (assoc base :tools {"t" (merge {:class :read :labels [] :command ["t"]} t)}))
                         true
                         (catch clojure.lang.ExceptionInfo _ false)))]
    (is (ok? {}))
    (is (ok? {:timeout-seconds 30}))
    (is (not (ok? {:timeout-seconds 0})))
    (is (not (ok? {:timeout-seconds "30"})))
    (is (not (ok? {:timeout-seconds 1.5})))))

(deftest a-child-holding-the-output-does-not-outlast-the-limit
  ;; relay#32. The command exits at once, but the child it leaves holds
  ;; stdout open. The timed wait saw the exit and then waited with no limit
  ;; for the output, so the tool ran as long as the child did.
  (let [pid-file (str *dir* "/child.pid")
        command ["sh" "-c" (str "sleep 30 & echo $! > " pid-file "; echo hi")]
        timed (fn [f]
                (fs/delete-if-exists pid-file)
                (let [started (System/nanoTime)
                      r (binding [*err* (java.io.StringWriter.)] (f))]
                  [r (/ (- (System/nanoTime) started) 1e6) (child-pid)]))]
    (testing "a plain tool"
      (let [[r took-ms pid] (timed #(tools/run-external! "t" {:command command :labels [] :timeout-seconds 1} []))]
        (is (= {:error "timed out after 1 seconds" :timed-out true} r))
        (is (< took-ms 2500) (str "returned after " took-ms " ms"))
        (is (some? pid))
        (is (not (alive? pid)) "the sleep it left was ended")))
    (testing "an envelope tool"
      (with-tool {:class :read :labels [] :description "Leaves a child." :protocol :envelope
                  :parameters {:type "object"} :command command :timeout-seconds 1}
        (fn [sys]
          (let [[r took-ms pid] (timed #(tools/call! sys "clerk" "slow" {} [] "c1"))]
            (is (= "The tool slow failed." (:error r)))
            (is (< took-ms 2500) (str "returned after " took-ms " ms"))
            (is (some? pid))
            (is (not (alive? pid)) "the sleep it left was ended")))))))

(deftest a-child-that-ignores-sigterm-is-killed
  (let [pid-file (str *dir* "/child.pid")
        command ["sh" "-c" (str "sh -c 'trap \"\" TERM; sleep 30' & echo $! > " pid-file "; echo hi")]
        r (binding [*err* (java.io.StringWriter.)]
            (tools/run-external! "t" {:command command :labels [] :timeout-seconds 1} []))
        pid (child-pid)]
    (is (:timed-out r))
    (is (some? pid))
    (is (not (alive? pid)) "SIGKILL after the grace")))
