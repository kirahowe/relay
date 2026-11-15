(ns relay.container-service
  "Whether Apple's container service is running, for the checks that need
  a real container: the shell's live test, pentest s18-shell-walls and
  the summary `bb verify` prints. While the service is stopped each of
  them fails at its first step with the runner's own words (\"See
  'container --help' for more information.\"), which do not say why.
  This says why, in one line.

  `stopped-line` is pure. `stopped` asks the service."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn bin
  "Apple's `container` program, or nil when it is not installed."
  []
  (some-> (fs/which "container" {:paths ["/opt/homebrew/bin" "/usr/local/bin" "/usr/bin"]}) str))

(defn stopped-line
  "The one line saying Apple's container service is not running, from how
  `container system status` ended ({:exit :out :err}), or nil when it
  exited 0, which it does only while the service answers. The line
  quotes the first thing the program printed, so a failure of some other
  kind still shows for what it is. Pure."
  [{:keys [exit out err]}]
  (when-not (zero? exit)
    ;; \R is any line break, so the line stays one line whatever was printed.
    (let [said (first (remove str/blank? (str/split (str out "\n" err) #"\R")))]
      (str "Apple's container service is not running"
           (when said (str " (container system status said: " (str/trim said) ")"))
           ". Start it with: container system start"))))

(defn stopped
  "stopped-line for the `container` program at `bin`, asked now."
  [bin]
  (stopped-line (p/shell {:in "" :out :string :err :string :continue true} bin "system" "status")))
