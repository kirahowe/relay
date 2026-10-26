(ns relay.red-team-test
  "Regression tests from an independent red-team review of 1b580c72. Each failed there."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.cli :as cli]
            [relay.config :as config]
            [relay.core :as core]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.tools :as tools]))

(def settings {:timezone "UTC"
               :default-harness :agentloop
               :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}
                           :bot {:kind :script :command ["true"]}}
               :notify {:kind :none}
               :tools {"post" {:class :edit :labels [] :description "Post." :command ["true"]}}
               :grants {"clerk" {:tools ["post"]}}})

(defn- with-data [f]
  (let [dir (fs/create-temp-dir {:prefix "relay-review"})
        data (str (fs/path dir "data"))]
    (fs/create-dirs data)
    (log/init! data)
    (spit (str data "/relay.edn") (pr-str settings))
    (try (f data) (finally (fs/delete-tree dir)))))

(defn- relay [data & args] (cli/execute (concat ["--data" data] args)))

(def C ["contaminated"])

(deftest operator-views-escape-role-written-text
  (with-data
    (fn [data]
      (let [sys (inbound/system data)
            submit #(inbound/submit! sys %)
            esc "\u001b]0;pwned\u0007\u001b[2J"]
        (submit {:type :role/add :actor "operator" :args {:id "clerk" :title (str "Clerk" esc) :instructions "x"}})
        (submit {:type :memo/send :actor "clerk" :labels C :args {:to "operator" :subject "hi" :body (str "body" esc)}})
        (submit {:type :doc/put :actor "clerk" :labels C :args {:path "a.md" :content (str "doc" esc)}})
        (submit {:type :fact/add :actor "clerk" :labels C :args {:text (str "fact" esc)}})
        (submit {:type :note/write :actor "clerk" :labels C :args {:name "n" :text (str "note" esc)}})
        (doseq [args [["inbox"] ["status"] ["roles"] ["doc" "a.md"] ["facts"] ["notes" "clerk"] ["log"]]]
          (testing (str/join " " args)
            (is (not (str/includes? (str/join "\n" (:out (apply relay data args))) "\u001b"))
                "raw ESC reaches the operator's terminal")))))))

(deftest a-script-role-launders-labels-through-builtins
  ;; D2: a role on a :script harness is still offered every builtin, and
  ;; relay believes whatever labels the script's envelope claims.
  (with-data
    (fn [data]
      (let [sys (inbound/system data)
            submit #(inbound/submit! sys %)]
        (testing "a role granted tools may not run on a :script harness"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"script harness gets no"
                                (submit {:type :role/add :actor "operator"
                                         :args {:id "clerk" :title "C" :instructions "x" :harness "bot"}}))))
        (submit {:type :role/add :actor "operator" :args {:id "scraper" :title "S" :instructions "x" :harness "bot"}})
        (testing "and a script role is offered nothing, and relay tool refuses it"
          (is (empty? (tools/manifest (:config sys) "scraper" {:script? true})))
          (let [{:keys [exit out]} (with-in-str (str "{\"input\":{},\"labels\":[],\"call_id\":\"c\"}")
                                     (relay data "tool" "--role" "scraper" "inbox"))]
            (is (= 1 exit))
            (is (empty? out))))
        (submit {:type :role/add :actor "operator" :args {:id "clerk" :title "C" :instructions "x"}})
        (submit {:type :doc/put :actor "scraper" :labels C :args {:path "web.md" :content "attacker text"}})
        (is (nil? (try (config/resolve-config settings) nil (catch Exception e (ex-message e))))
            "config granting tools to a role whose harness is :script validates")
        (let [{:keys [ok labels]} (tools/call! sys "scraper" "doc_get" {:path "web.md"} [])]
          (is (core/contaminated? labels))
          ;; the script now writes what it read, claiming a clean session
          (tools/call! sys "scraper" "memo_send" {:to "clerk" :subject "fyi" :body ok} []))
        (let [m (first (core/unread-memos (inbound/current-state sys) "clerk"))]
          (is (str/starts-with? (:body m) "attacker text"))
          (is (core/contaminated? (:labels m)) "what a script read stays labelled when it passes it on"))))))

(deftest fact-correct-copies-contaminated-times-without-their-label
  (let [cfg (config/resolve-config settings)
        t0 (java.time.Instant/parse "2026-10-14T12:00:00Z")
        run (fn [s actor type args labels]
              (first (core/run-command s {:type type :actor actor :args args :labels labels} {:now t0 :config cfg})))
        s (-> core/empty-state
              (run "operator" :role/add {:id "clerk" :title "C" :instructions "x"} [])
              (run "clerk" :fact/add {:text "x" :valid-from "2031-07-04"} C))
        id (first (keys (:facts s)))
        s (run s "operator" :fact/correct {:fact id :text "clean words" :topic "a" :why "fix"} [])
        v (core/fact-as-known (core/fact s id) nil)]
    (is (= "2031-07-04T00:00:00Z" (str (:valid-from v))))
    (is (core/contaminated? (:labels v)) "the contaminated session's valid_from survives in a clean version")))
