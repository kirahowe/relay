(ns relay.service-test
  (:require [clojure.test :refer [deftest is testing]]
            [relay.service :as service]))

(deftest each-action-does-only-what-the-job-needs
  (is (= [:stop :wait-stopped] (service/steps "stop" true)))
  (is (= [] (service/steps "stop" false)) "a stopped job is left alone")
  (is (= [:start] (service/steps "start" false)))
  (is (= [] (service/steps "start" true)) "a running job is not started twice")
  (is (= [:stop :wait-stopped :start] (service/steps "restart" true)))
  (is (= [:start] (service/steps "restart" false)))
  (is (= [] (service/steps "status" true)))
  (is (nil? (service/steps "bounce" true)) "an unknown action is a usage error"))

;; What launchctl print says, cut down: the job's own state and pid sit one
;; tab in; nested blocks have states of their own.
(def ^:private printed
  "gui/501/relay.serve = {\n\tactive count = 1\n\tpath = /Users/x/Library/LaunchAgents/relay.serve.plist\n\ttype = LaunchAgent\n\tstate = running\n\n\tprogram = /opt/homebrew/bin/bb\n\tpid = 39660\n\tendpoints = {\n\t\tstate = active\n\t}\n}\n")

(deftest launchctls-output-says-whether-it-runs
  (is (= {:running? true :pid 39660} (service/parse-status printed)))
  (testing "a loaded job that is not running has no pid"
    (is (= {:running? false :pid nil}
           (service/parse-status "gui/501/relay.serve = {\n\tstate = not running\n}\n")))))

(deftest the-status-says-it-plainly
  (is (= "relay serve is running (pid 39660)." (service/status-line {:loaded? true :running? true :pid 39660})))
  (is (re-find #"stopped" (service/status-line {:loaded? false}))))
