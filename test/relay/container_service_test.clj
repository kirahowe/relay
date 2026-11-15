(ns relay.container-service-test
  "relay#99: while Apple's container service is stopped, the checks that
  need a real container say so in one line."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.container-service :as service]))

(def ^:private start "Start it with: container system start")

(deftest a-stopped-service-is-said-in-one-line
  (testing "what container 1.7.1 prints while its service is stopped"
    (is (= (str "Apple's container service is not running (container system status said: apiserver is not"
                " running and not registered with launchd). " start)
           (service/stopped-line {:exit 1 :err ""
                                  :out "apiserver is not running and not registered with launchd\n"}))))
  (testing "a service that answers gives no line, whatever it printed"
    (is (nil? (service/stopped-line {:exit 0 :out "anything\n" :err "anything"}))))
  (testing "a failure of another kind is quoted, so it shows for what it is"
    (is (= (str "Apple's container service is not running (container system status said: Error: Unknown option"
                " '--x'). " start)
           (service/stopped-line {:exit 64 :out "" :err "\n  Error: Unknown option '--x'\nUsage: container\n"}))))
  (testing "a program that printed nothing still gets the line"
    (is (= (str "Apple's container service is not running. " start)
           (service/stopped-line {:exit 1 :out "" :err ""})))))

(def ^:private line-breaks
  "Every line break Java's \\R knows, by code point."
  (mapv #(str (char %)) [0x0A 0x0B 0x0C 0x0D 0x85 0x2028 0x2029]))

(def ^:private gen-output
  "What a program may print: any text, with line breaks of every kind."
  (gen/fmap str/join (gen/vector (gen/one-of [gen/string (gen/elements (conj line-breaks "\r\n"))]) 0 6)))

(defspec the-line-is-one-line-whatever-the-program-printed 300
  (prop/for-all [exit (gen/such-that (complement zero?) gen/small-integer)
                 out gen-output
                 err gen-output]
    (let [line (service/stopped-line {:exit exit :out out :err err})]
      (and (str/starts-with? line "Apple's container service is not running")
           (str/ends-with? line start)
           (not (re-find #"\R" line))))))
