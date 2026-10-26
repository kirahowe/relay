(ns relay.shell-live-test
  "The steward's shell against the real runner, Apple container: the image
  builds from the Containerfile in the repo, the container has no way out
  (I1), the operator's files cannot be changed from inside (I7), and the
  time limit holds inside the VM. Each run uses a fresh data directory, so
  its container and image have names of their own, and both are removed
  by name at the end. Skipped only when `container` is not installed."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.shell :as shell]
            [relay.shell-probe :as probe]
            [relay.tools :as tools]))

(def container-bin
  (some-> (fs/which "container" {:paths ["/opt/homebrew/bin" "/usr/local/bin" "/usr/bin"]}) str))

(defn- settings [notes]
  {:timezone "UTC"
   :default-harness :agentloop
   :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}}
   :house {:runner [container-bin] :mounts [notes] :timeout-seconds 3}
   :grants {"hub" {:shell true}}})

(defn- remove-by-name! [ctx]
  (let [{:keys [house names]} ctx]
    (shell/run-runner! (shell/remove-argv house names) {:ms 60000})
    (shell/run-runner! [container-bin "image" "rm" (:image names)] {:ms 60000})))

(deftest ^:volley/serial the-shell-runs-in-a-real-container-with-no-way-out
  (if-not container-bin
    (println "SKIPPED: Apple container is not installed, so the shell's live test cannot run")
    (let [dir (str (fs/real-path (fs/create-temp-dir {:prefix "relay-shell-live"})))
          data (str dir "/data")
          notes (str dir "/notes")
          _ (do (fs/create-dirs notes)
                (spit (str notes "/todo.txt") "buy oat milk\n")
                (log/init! data)
                (spit (str data "/relay.edn") (pr-str (settings notes))))
          sys (inbound/system data)
          ctx (shell/context data (:config sys))
          sh (fn [command] (tools/call! sys "hub" "sh" {:command command} ["private"] "c" nil))
          ;; The probe tries thousands of ports, which takes longer than
          ;; the 3 seconds the time-limit check uses. The limit is not how
          ;; the container runs, so it is not restarted for this.
          slow-sh (fn [command]
                    (tools/call! (assoc-in sys [:config :house :timeout-seconds] 300) "hub" "sh"
                                 {:command command} ["private"] "c" nil))]
      (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "hub" :title "Hub" :instructions "x"}})
      (try
        (is (= {:restarted false} (shell/build! ctx)) "the image builds from the repo's Containerfile")
        (is (= :started (shell/up! ctx)))
        (testing "the operator's files are there, at their own path"
          (is (= {:ok "buy oat milk\n" :labels ["private"]} (sh (str "cat " notes "/todo.txt")))))
        (testing "I7: nothing inside can change them"
          (doseq [cmd [(str "echo gone > " notes "/todo.txt") (str "rm " notes "/todo.txt")
                       (str "touch " notes "/new.txt") (str "mv " notes "/todo.txt " notes "/moved.txt")]]
            (let [{:keys [ok]} (sh cmd)]
              (is (re-find #"Read-only file system" ok) cmd)
              (is (re-find #"Exit status [1-9]" ok) cmd)))
          (is (= "buy oat milk\n" (slurp (str notes "/todo.txt"))))
          (is (= ["todo.txt"] (map str (map fs/file-name (fs/list-dir notes))))))
        (testing "nor the system; only scratch is writable, as an unprivileged user"
          (is (re-find #"Read-only file system" (:ok (sh "touch /etc/x"))))
          (is (= "1000\nhi\n" (:ok (sh "id -u; echo hi > /scratch/f && cat f")))))
        (testing "relay's data directory is not there"
          (is (re-find #"No such file" (:ok (sh (str "ls " data))))))
        (testing "the tools the steward is told of are there"
          (is (= "(no output)" (:ok (sh (str "for t in sh find grep rg awk sed python3 sqlite3 jq pdftotext"
                                             " file tar zip unzip; do command -v $t >/dev/null || echo missing $t; done"))))))
        (testing "I1: TCP, UDP, DNS, vsock and unix sockets all fail"
          (let [r (probe/results (:ok (slow-sh (probe/command))))]
            (is (<= 12 (count r)) r)
            (is (every? #{"fail"} (vals r)) r)))
        (testing "the time limit is kept inside the VM"
          (let [t0 (System/currentTimeMillis)
                {:keys [ok]} (sh "sleep 30; echo late")]
            (is (str/includes? ok "ran out of time after 3 seconds") ok)
            (is (not (str/includes? ok "late")))
            (is (< (- (System/currentTimeMillis) t0) 20000)))
          (is (= "0\n" (:ok (sh "ps -o args | grep -c '^sleep 30' || true")))
              "the timed-out command is gone"))
        (testing "down removes it, and the next call starts it again with an empty scratch"
          (is (= :stopped (shell/down! ctx)))
          (is (= "absent\n" (:ok (sh "test -e /scratch/f && echo there || echo absent")))))
        (finally
          (remove-by-name! ctx)
          (fs/delete-tree dir))))))
