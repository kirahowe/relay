(ns relay.text-test
  "Text a role wrote reaches the operator's terminal and phone only in a
  form that cannot drive the terminal, hide what it says, or be followed
  with a tap."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cli :as cli]
            [relay.core :as core]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.render :as render]
            [relay.text :as text]))

(def nasty-chars
  "Code points that could do harm on a terminal, or hide text."
  [0 7 8 13 27 127 0x85 0x9b 0x200b 0x200e 0x202a 0x202e 0x2066 0x2069 0x2028 0x2029 0xfeff
   0xe000 0x10ffff 0x1f600 0xe9])

(def gen-text
  (gen/fmap (fn [parts] (apply str parts))
            (gen/vector (gen/one-of [(gen/fmap #(String. (Character/toChars (int %))) (gen/elements nasty-chars))
                                     gen/string-alphanumeric
                                     (gen/elements ["[2J" "]0;title" "\n" "\t" " " "é"])])
                        1 12)))

(defn- raw-hidden [s]
  (some text/hidden? (iterator-seq (.iterator (.codePoints ^String s)))))

(defn- with-data [f]
  (let [dir (str (fs/create-temp-dir {:prefix "relay-text-test"}))
        data (str dir "/data")]
    (log/init! data)
    (spit (str data "/relay.edn")
          (pr-str {:timezone "UTC" :default-harness :agentloop
                   :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent"}}
                   :notify {:kind :none}}))
    (try (f data) (finally (fs/delete-tree dir)))))

(defspec nothing-a-role-wrote-reaches-the-terminal-raw 25
  (prop/for-all [t gen-text]
    (with-data
      (fn [data]
        (let [sys (inbound/system data)
              submit (fn [actor type args & [labels]]
                       (try (inbound/submit! sys {:type type :actor actor :args args :labels labels})
                            (catch clojure.lang.ExceptionInfo _ nil)))
              s (str "x" t)]
          (submit "operator" :role/add {:id "clerk" :title s :instructions s})
          (submit "clerk" :memo/send {:to "operator" :subject (str/replace s "\n" " ") :body s})
          (submit "clerk" :memo/send {:to "operator" :subject "web" :body s} ["contaminated"])
          (submit "clerk" :fact/add {:text s})
          (submit "clerk" :doc/put {:path "a.md" :content s})
          (submit "clerk" :note/write {:name "n" :text s})
          (submit "clerk" :journal/write {:text s})
          (submit "clerk" :routine/write {:name "r" :schedule "every 1d" :instructions s})
          (submit "operator" :wake/start {:role "clerk" :note s})
          (let [wake (core/running-wake (inbound/current-state sys) "clerk")]
            (submit "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :session "s" :said s
                                            :held [{:id "c1" :name "post" :input {:text s}}]}))
          (let [outs (for [args [["inbox"] ["status"] ["holds"] ["log"] ["log" "--json"] ["docs"]
                                 ["doc" "a.md"] ["doc" "a.md" "--history"] ["facts" "--all"] ["fact" "5"]
                                 ["notes" "clerk"] ["journal" "clerk"] ["roles"] ["routines"]]]
                       (try (:out (cli/execute (concat ["--data" data] args)))
                            (catch clojure.lang.ExceptionInfo e [(ex-message e)])))
                bad (filter raw-hidden (mapcat #(str/split-lines (str/join "\n" %)) outs))]
            (or (empty? bad) (do (prn bad) false))))))))

(def gen-urlish
  (gen/fmap (fn [parts] (apply str parts))
            (gen/vector (gen/elements ["http://" "HTTPS://" "hTtP://" "ftp://" "javascript://" "data://"
                                       "www." "WWW." "evil" ".com" "." "/path" "?q=1" " " "\n" "x"
                                       "‮" "​" ":" "//"])
                        1 15)))

(defspec contaminated-text-for-the-operator-has-no-followable-url 500
  (prop/for-all [s gen-urlish]
    (let [out (text/for-operator ["contaminated"] s)]
      (and (str/starts-with? out "[contaminated] ")
           (not (re-find #"(?i)\b(https?|ftp)://" out))
           ;; Only the defanged schemes keep their ://.
           (every? #{"hxxp" "hxxps" "fxp"}
                   (map (comp str/lower-case second) (re-seq #"(?i)([a-z][a-z0-9+.\-]*)://" out)))
           (not (re-find #"(?i)\bwww\.[^\s\[]" out))
           (not (raw-hidden out))))))

(deftest clean-text-is-only-made-visible
  (is (= "see https://example.com é \\u{202e}" (text/for-operator ["private"] "see https://example.com é ‮"))))

(deftest the-operator-s-inbox-marks-contaminated-memos
  (let [lines (render/inbox java.time.ZoneOffset/UTC
                            [{:id 3 :from "clerk" :subject "Click https://evil.example/x" :body "www.evil.example"
                              :sent-at (java.time.Instant/now) :labels ["contaminated" "private"]}])
        out (str/join "\n" lines)]
    (is (str/includes? out "[3] [contaminated] Click hxxps://evil[.]example/x"))
    (is (str/includes? out "www[.]evil[.]example"))))
