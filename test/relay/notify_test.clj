(ns relay.notify-test
  "Pushing to an ntfy server, here a stand-in on loopback."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [org.httpkit.server :as http]
            [relay.config :as config]
            [relay.notify :as notify]
            [relay.text :as text]))

(defn- with-ntfy [f]
  (let [seen (atom [])
        server (http/run-server (fn [req]
                                  (swap! seen conj {:headers (:headers req)
                                                    :body (json/parse-string (slurp (:body req)) true)})
                                  {:status 200 :body "{}"})
                                {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (try (f (str "http://127.0.0.1:" (http/server-port server)) seen)
         (finally (http/server-stop! server)))))

(deftest ntfy-sends-a-bearer-token-from-a-command-or-a-file
  (with-ntfy
    (fn [url seen]
      (let [dir (str (fs/create-temp-dir {:prefix "relay-notify"}))
            file (str dir "/token")]
        (spit file "tk_from_file\n")
        (notify/push! {:kind :ntfy :url url :topic "t1"} {:title "a" :body "b"})
        (notify/push! {:kind :ntfy :url url :topic "t1" :token-command ["printf" "tk_from_command"]}
                      {:title "a" :body "b"})
        (notify/push! {:kind :ntfy :url url :topic "t1" :token-file file} {:title "a" :body "b"})
        (is (= [nil "Bearer tk_from_command" "Bearer tk_from_file"]
               (map #(get-in % [:headers "authorization"]) @seen)))
        (is (= "t1" (get-in (first @seen) [:body :topic])))
        (testing "a token command that fails, or prints nothing, sends nothing"
          (is (thrown? Exception (notify/push! {:kind :ntfy :url url :topic "t" :token-command ["false"]}
                                               {:title "a" :body "b"})))
          (is (thrown? Exception (notify/push! {:kind :ntfy :url url :topic "t" :token-command ["true"]}
                                               {:title "a" :body "b"})))
          (is (= 3 (count @seen))))
        (fs/delete-tree dir)))))

(deftest the-token-settings-are-checked
  (let [base {:timezone "UTC" :default-harness :a
              :harnesses {:a {:kind :agentloop :api-key-command ["true"]}}}
        problem (fn [notify] (try (config/resolve-config (assoc base :notify notify)) nil
                                  (catch clojure.lang.ExceptionInfo e (ex-message e))))
        ntfy {:kind :ntfy :url "http://x" :topic "t"}]
    (is (nil? (problem (assoc ntfy :token-command ["cat" "/x"]))))
    (is (nil? (problem (assoc ntfy :token-file "/x"))))
    (is (str/includes? (problem (assoc ntfy :token-command "cat /x")) ":token-command"))
    (is (str/includes? (problem (assoc ntfy :token-file "")) ":token-file"))
    (is (str/includes? (problem (assoc ntfy :token-file "/x" :token-command ["cat"])) "not both"))))

(deftest a-contaminated-notification-is-marked-and-cannot-be-followed
  (with-ntfy
    (fn [url seen]
      (notify/push! {:kind :ntfy :url url :topic "t"}
                    {:title "Open https://evil.example" :body "Go to www.evil.example/x ‮now"
                     :labels ["contaminated"]})
      (notify/push! {:kind :ntfy :url url :topic "t"} {:title "Fine" :body "https://ok.example" :labels []})
      (let [[bad ok] (map :body @seen)]
        (is (= "[from outside] Open hxxps://evil[.]example" (:title bad)))
        (is (= "[from outside] Go to www[.]evil[.]example/x \\u{202e}now" (:message bad)))
        (is (= "https://ok.example" (:message ok)))))))

(deftest the-file-notifier-appends-a-line-and-reaches-nobody
  (let [dir (str (fs/create-temp-dir {:prefix "relay-notify-file"}))
        file (str dir "/sub/notifications.log")
        settings {:kind :file :path file}]
    (notify/push! settings {:title "First" :body "One"})
    (notify/push! settings {:title "Second" :body "Two https://evil.example now" :labels ["contaminated"]})
    (let [lines (map #(json/parse-string % true) (str/split-lines (slurp file)))]
      (is (= [{:title "First" :body "One" :labels []}
              {:title "[from outside] Second" :body "[from outside] Two hxxps://evil[.]example now"
               :labels ["contaminated"]}]
             (map #(select-keys % [:title :body :labels]) lines))
          "marked and defanged, as for any other kind")
      (is (every? string? (map :at lines))))
    (testing "the file and the directory made for it are private to the owner"
      (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions file))))
      (is (= "rwx------" (fs/posix->str (fs/posix-file-permissions (str dir "/sub"))))))
    (testing "a file that already exists with looser permissions is tightened"
      (let [loose (str dir "/loose.log")]
        (spit loose "")
        (fs/set-posix-file-permissions loose "rw-r--r--")
        (notify/push! {:kind :file :path loose} {:title "T" :body "B"})
        (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions loose))))))
    (testing "relay.edn needs an absolute path for it"
      (let [base {:timezone "UTC" :default-harness :a
                  :harnesses {:a {:kind :agentloop :api-key-command ["true"]}}}
            problem (fn [notify] (try (config/resolve-config (assoc base :notify notify)) nil
                                      (catch clojure.lang.ExceptionInfo e (ex-message e))))]
        (is (nil? (problem {:kind :file :path file})))
        (is (str/includes? (problem {:kind :file}) ":path"))
        (is (str/includes? (problem {:kind :file :path ""}) ":path"))
        (is (str/includes? (problem {:kind :file :path "notifications.log"}) "absolute"))
        (is (str/includes? (problem {:kind :file :path "~/notifications.log"}) "absolute"))))
    (fs/delete-tree dir)))

;; --- cutting the body to ntfy's limit

(defn- unescape
  "The text that text/visible showed as `s`."
  [s]
  (str/replace s #"\\\\|\\u\{([0-9a-f]+)\}"
               (fn [[_ hex]] (if hex (String. (Character/toChars (Integer/parseInt hex 16))) "\\"))))

(def ^:private gen-text
  ;; Letters, backslashes, a newline, CJK, emoji (a surrogate pair each)
  ;; and code points visible escapes, a tag character among them.
  (gen/fmap (fn [cps] (let [sb (StringBuilder.)] (doseq [cp cps] (.appendCodePoint sb (int cp))) (str sb)))
            (gen/vector (gen/elements [0x61 0x5C 0x7B 0x75 0x0A 0x4E2D 0x1F600 0x1F44D 0x200B 0x202E 0x07 0xE0041 0xFE0F])
                        0 40)))

(defspec clip-cuts-between-code-points-and-escapes 500
  ;; relay#32: clip counted UTF-16 units after escaping, so a body could
  ;; end in half a surrogate pair or half an escape such as \u{20.
  (prop/for-all [raw gen-text
                 n (gen/choose 0 80)]
    (let [shown (text/visible raw)
          out (notify/clip shown n)
          cut? (not= out shown)
          kept (if cut? (subs out 0 (dec (count out))) out)]
      (and (if cut? (and (> (count shown) n) (str/ends-with? out "…")) (<= (count shown) n))
           (<= (count kept) n)
           (str/starts-with? shown kept)
           ;; Whole code points: no surrogate is left alone.
           (not-any? #(<= 0xD800 % 0xDFFF) (iterator-seq (.iterator (.codePoints kept))))
           ;; Whole escapes and whole escaped backslashes: what is kept
           ;; reads back as the start of the text it shows.
           (str/starts-with? raw (unescape kept))
           (even? (count (re-find #"\\*$" kept)))
           ;; And no more is dropped than the longest escape, \u{10ffff}.
           (or (not cut?) (> (count kept) (- n 10)))))))

(deftest clip-keeps-an-escape-whole
  (is (= "ab…" (notify/clip "ab\\u{200b}cd" 5)))
  (is (= "ab\\u{200b}…" (notify/clip "ab\\u{200b}cd" 10)))
  (is (= "a…" (notify/clip (str "a" (String. (Character/toChars 0x1F600)) "b") 2)) "not half an emoji"))
