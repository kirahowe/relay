(ns relay.connect-test
  "relay connect and relay mcp-token: what is proposed for each tool, the
  summary the operator reads, the connection file and how relay.edn loads
  it, and the whole sign-in against agentloop's fake remote server and
  authorization server (internal/cmd/mcpremote), with the keychain and
  the browser stood in for. Nothing here reaches a real service or the
  real keychain."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.cli :as cli]
            [relay.config :as config]
            [relay.connect :as connect]
            [relay.connections :as connections]
            [relay.core :as core]
            [relay.harness.agentloop :as agentloop]
            [relay.inbound :as inbound]
            [relay.log :as log]
            [relay.test-support :as support]
            [relay.tools :as tools])
  (:import (java.time Instant)))

(def digest (apply str (repeat 64 "b")))

;; --- what is proposed

(def gen-annotations
  (gen/let [ks (gen/vector (gen/elements [:readOnlyHint :destructiveHint :idempotentHint :openWorldHint]) 0 4)
            vs (gen/vector (gen/elements [true false nil "yes" 1]) 4)]
    (into {} (map vector ks vs))))

(deftest only-a-read-only-hint-proposes-read
  (is (:pass? (tc/quick-check 300
                              (prop/for-all [a (gen/one-of [(gen/return nil) gen-annotations])]
                                (= (if (true? (:readOnlyHint a)) :read :edit)
                                   (connect/propose-class {:annotations a}))))))
  (testing "nothing else is ever proposed, whatever the hints"
    (is (= #{:read :edit} (set (map #(connect/propose-class {:annotations %})
                                   (gen/sample gen-annotations 200)))))))

(deftest tools-that-delete-start-off
  (doseq [[t off?] [[{:name "delete_subscriber"} true] [{:name "removeTag"} true]
                    [{:name "list" :title "Erase a list"} true] [{:name "send_campaign"} false]
                    [{:name "deleted_count"} false] [{:name "undelete"} false]]]
    (is (= off? (connect/delete-like? t)) (pr-str t))))

(deftest relay-names-are-valid-and-distinct
  (is (:pass? (tc/quick-check 300
                              (prop/for-all [remotes (gen/vector gen/string 1 12)]
                                (let [names (reduce (fn [taken r] (conj taken (connect/relay-name "mail" r (set taken))))
                                                    [] remotes)]
                                  (and (apply distinct? names)
                                       (every? #(re-matches connections/tool-name-pattern %) names)))))))
  (is (= "mail_send_campaign" (connect/relay-name "mail" "send_campaign" #{})))
  (is (= "mail_a_b" (connect/relay-name "mail" "a.b" #{})))
  (is (= 64 (count (connect/relay-name "mail" (apply str (repeat 80 "x")) #{})))))

(def listed
  [{:name "send_campaign" :description "Send a campaign to a group. It goes out at once." :digest digest
    :annotations {:readOnlyHint false :destructiveHint false}}
   {:name "list_subscribers" :title "List subscribers" :description "List subscribers." :digest digest
    :annotations {:readOnlyHint true}}
   {:name "delete_subscriber" :description "Delete one." :digest digest :annotations {:destructiveHint true}}])

(deftest a-class-relay-no-longer-has-is-not-carried-over
  (let [previous {:tools {"mail_send_campaign" {:tool "send_campaign" :class :act :on true}}}
        send (first (filter #(= "send_campaign" (:tool %)) (connect/propose "mail" listed previous)))]
    (is (= [:edit false] ((juxt :class :on) send)) "proposed afresh, and off")))

(deftest the-proposal-and-its-summary
  (let [p (connect/propose "mail" listed nil)
        by (into {} (map (juxt :tool identity) p))]
    (is (= ["delete_subscriber" "list_subscribers" "send_campaign"] (map :tool p)))
    (is (= :read (get-in by ["list_subscribers" :class])))
    (is (= [:edit false] ((juxt :class :on) (by "send_campaign")))
        "a tool that is not read-only starts off, for the operator to turn on")
    (is (false? (get-in by ["delete_subscriber" :on])))
    (is (every? #(= ["contaminated" "private"] (:labels %)) p))
    (let [text (str/join "\n" (connect/summary p))]
      (is (str/includes? text "List subscribers (list_subscribers)\n    Reads only."))
      (is (str/includes? text "send_campaign\n    Off. It may change things there."))
      (is (str/ends-with? text "The server says: Send a campaign to a group. It goes out at once.")
          "the operator reads all the model will")
      (is (str/includes? text "delete_subscriber\n    Off."))
      (is (not-any? #(re-find #"(?i)\b(role|grant|label|contaminated|hold)\b" %) (connect/summary p))
          "the summary speaks in the operator's terms"))
    (testing "an earlier connect's choices are kept"
      (let [previous {:tools {"mail_send" {:tool "send_campaign" :class :read :on false :labels ["private"]
                                           :fixed-args {"send_now" false} :digest digest}}}
            again (into {} (map (juxt :tool identity) (connect/propose "mail" listed previous)))]
        (is (= ["mail_send" :read false {"send_now" false}]
               ((juxt :name :class :on :fixed-args) (again "send_campaign"))))))))

;; --- the connection file

(def gen-name (gen/fmap #(apply str "t" %) (gen/vector (gen/elements (seq "abcxyz_-019")) 0 10)))

(def gen-json
  (gen/recursive-gen (fn [inner] (gen/one-of [(gen/vector inner 0 2) (gen/map gen/string-alphanumeric inner {:max-elements 2})]))
                     (gen/one-of [gen/string gen/boolean gen/large-integer (gen/return nil)
                                  (gen/double* {:infinite? false :NaN? false})])))

(def gen-tool
  (gen/let [tool gen/string-alphanumeric
            class (gen/elements (vec config/tool-classes))
            labels (gen/elements [[] ["private"] ["contaminated" "private"]])
            on gen/boolean
            fixed (gen/map (gen/fmap #(str "f" %) gen/string-alphanumeric) gen-json {:max-elements 3})
            hidden (gen/fmap #(vec (distinct %)) (gen/vector (gen/fmap #(str "h" %) gen/string-alphanumeric) 0 3))]
    (cond-> {:tool (str "x" tool) :class class :labels labels :digest digest :on on}
      (seq fixed) (assoc :fixed-args fixed)
      (seq hidden) (assoc :hidden-args hidden))))

(def gen-connection
  (gen/let [tools (gen/map gen-name gen-tool {:min-elements 1 :max-elements 5})
            port (gen/choose 1 65535)]
    {:format 1 :url (str "http://127.0.0.1:" port "/mcp")
     :credentials-command ["/bin/relay" "mcp-token" "mail" "--data" "/d"]
     :connected "2026-10-18T00:00:00Z"
     :oauth {:issuer "https://as.example" :token-endpoint "https://as.example/token" :client-id "c" :resource "https://mcp.example/mcp"}
     ;; One tool per server name.
     :tools (into {} (map-indexed (fn [i [n t]] [n (update t :tool str i)]) tools))}))

(def opts {:classes config/tool-classes :labels config/label-names})

(deftest a-connection-file-reads-back-as-written
  (is (:pass? (tc/quick-check 60
                              (prop/for-all [c gen-connection]
                                (let [text (connections/render "mail" c)]
                                  (and (= c (connections/parse text))
                                       (= c (connections/check "mail" (connections/parse text) opts))
                                       (= text (connections/render "mail" (connections/parse text))))))))))

(deftest a-bad-connection-file-is-refused
  (let [good {:format 1 :url "https://mcp.example.com/mcp" :credentials-command ["x"]
              :tools {"mail_send" {:tool "send" :class :edit :labels ["private"] :digest digest :on true}}}
        problem (fn [f] (try (connections/check "mail" (f good) opts) nil
                             (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (is (nil? (problem identity)))
    (doseq [[why f expected]
            [["plain http elsewhere" #(assoc % :url "http://mcp.example.com/mcp") "must be https"]
             ["a password in the url" #(assoc % :url "https://u:p@mcp.example.com/mcp") "password"]
             ["no token command" #(dissoc % :credentials-command) ":credentials-command"]
             ["an unknown class" #(assoc-in % [:tools "mail_send" :class] :magic) ":class"]
             ["a class that acts as the operator" #(assoc-in % [:tools "mail_send" :class] :act) ":class"]
             ["a hold" #(assoc-in % [:tools "mail_send" :always-hold] true) "does not know"]
             ["an unknown label" #(assoc-in % [:tools "mail_send" :labels] ["secret"]) ":labels"]
             ["no digest" #(update-in % [:tools "mail_send"] dissoc :digest) ":digest"]
             ["no on" #(update-in % [:tools "mail_send"] dissoc :on) ":on"]
             ["an unknown key" #(assoc-in % [:tools "mail_send" :command] ["sh"]) "does not know"]
             ["fixed and hidden" #(update-in % [:tools "mail_send"] assoc :fixed-args {"a" 1} :hidden-args ["a"]) "both fixes and hides"]
             ["fixed not JSON" #(assoc-in % [:tools "mail_send" :fixed-args] {"a" :keyword}) ":fixed-args"]
             ["a server tool twice" #(assoc-in % [:tools "mail_send2"] (get-in % [:tools "mail_send"])) "twice"]
             ["another format" #(assoc % :format 2) ":format"]]]
      (is (some-> (problem f) (str/includes? expected)) (str why ": " (problem f))))))

(def settings
  {:timezone "UTC" :default-harness :agentloop
   :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}}
   :connections ["mail"]
   :tools {"fetch" {:class :read :labels ["contaminated"] :description "Fetch." :command ["true"]}}
   :grants {"hub" {:tools ["mail_send" "mail_list"]}}})

(def mail-connection
  {:format 1 :url "https://mcp.example.com/mcp" :credentials-command ["/bin/relay" "mcp-token" "mail" "--data" "/d"]
   :tools {"mail_send" {:tool "send_campaign" :class :edit :labels ["contaminated" "private"] :digest digest :on true
                        :fixed-args {"send_now" false} :hidden-args ["reply_to"]}
           "mail_list" {:tool "list_subscribers" :class :read :labels ["contaminated" "private"] :digest digest :on true}
           "mail_delete" {:tool "delete_subscriber" :class :edit :labels ["private"] :digest digest :on false}}})

(defn- data-with [conn & [s]]
  (let [data (str (fs/create-temp-dir {:prefix "relay-connect"}))]
    (fs/create-dirs (fs/path data "connections"))
    (spit (str (fs/path data "relay.edn")) (pr-str (or s settings)))
    (when conn (spit (str (connections/file-path data "mail")) (connections/render "mail" conn)))
    data))

(deftest relay-edn-loads-the-connections-it-names
  (let [c (config/load-config (data-with mail-connection))]
    (is (= {:url "https://mcp.example.com/mcp" :credentials-command ["/bin/relay" "mcp-token" "mail" "--data" "/d"]}
           (get-in c [:mcp-servers "mail"])))
    (is (= #{"fetch" "mail_send" "mail_list"} (set (keys (:tools c)))) "a tool that is off is not loaded")
    (is (= :mcp-http (get-in c [:tools "mail_send" :protocol])))
    (is (= digest (get-in c [:tools "mail_send" :pin])))
    (is (nil? (get-in c [:tools "mail_send" :digest])) "the connect pin can be replaced by relay fresh")
    (testing "the request names the remote server and carries the narrowing"
      (let [manifest (tools/manifest c "hub")
            send (first (filter #(= "mail_send" (:name %)) manifest))
            req (agentloop/request-tool send)]
        (is (= {:name "mail_send" :protocol "mcp-http" :server "mail" :tool "send_campaign"
                :fixed_args {"send_now" false} :hidden_args ["reply_to"]}
               req))
        (is (= {"mail" {:url "https://mcp.example.com/mcp"
                        :credentials_command ["/bin/relay" "mcp-token" "mail" "--data" "/d"]}}
               (agentloop/request-mcp-servers (config/mcp-servers-for c ["mail_send"])))))))
  (doseq [[why data expected]
          [["no file" (data-with nil) "relay connect mail"]
           ["a clash with :tools" (data-with (assoc-in mail-connection [:tools "fetch"] (assoc (get-in mail-connection [:tools "mail_list"]) :tool "other")))
            "already has"]
           ["a clash with :mcp-servers" (data-with mail-connection (assoc settings :mcp-servers {"mail" {:command ["x"]}}))
            "both called mail"]
           ["a bad name" (data-with mail-connection (assoc settings :connections ["Mail"])) "not a connection name"]
           ["a :pin in relay.edn" (data-with nil (-> settings (dissoc :connections)
                                                     (assoc :mcp-servers {"m" {:command ["x"]}})
                                                     (assoc-in [:tools "mail_send"] {:protocol :mcp-stdio :server "m" :tool "t"
                                                                                     :class :read :labels [] :pin digest})
                                                     (assoc-in [:grants "hub" :tools] ["mail_send"])))
            "only a connection file"]
           ["an http tool from a stdio server" (data-with nil (-> settings (dissoc :connections)
                                                                  (assoc :mcp-servers {"m" {:command ["x"]}})
                                                                  (assoc-in [:tools "mail_send"] {:protocol :mcp-http :server "m" :tool "t"
                                                                                                  :class :read :labels []})
                                                                  (assoc-in [:grants "hub" :tools] ["mail_send"])))
            "run over stdio"]]]
    (is (some-> (try (config/load-config data) nil (catch clojure.lang.ExceptionInfo e (ex-message e)))
                (str/includes? expected))
        why)))

;; --- the whole sign-in, against the fakes

(def ^:private fake-bin
  (delay
    (let [repo (str (fs/parent (fs/parent support/agentloop-bin)))
          out (str (fs/path (fs/create-temp-dir {:prefix "mcpremote"}) "mcpremote"))
          go (or (some-> (fs/which "go") str) "/opt/homebrew/bin/go")
          r @(p/process [go "build" "-o" out "./internal/cmd/mcpremote"] {:dir repo :out :string :err :string})]
      (when-not (zero? (:exit r)) (throw (ex-info "could not build mcpremote" r)))
      out)))

(defn- start-fake
  "Starts the fake remote server; returns {:url :base :stop}."
  [& flags]
  (let [proc (p/process (into [@fake-bin] flags) {:err :inherit})
        base (str/trim (.readLine (io/reader (:out proc))))]
    {:base base :url (str base "/mcp")
     :stop (fn [] (.close ^java.io.OutputStream (:in proc)) (p/destroy proc))}))

(defn- browser
  "Stands in for the operator's browser: follows the authorization URL's
  redirects back to relay's listener."
  [url]
  (future (http/get url {:throw false})))

(defn- edges [keychain clock]
  {:keychain-read (fn [service account] (get @keychain [service account]))
   :keychain-write! (fn [service account secret] (swap! keychain assoc [service account] secret))
   :open-browser browser
   :now (fn [] @clock)
   :wait-ms 20000})

(defn- terminal
  "A terminal that answers each question in turn."
  [shown answers]
  (let [left (atom answers)]
    (fn [] {:say (fn [t] (swap! shown str t))
            :ask (fn [] (let [a (first @left)] (swap! left rest) a))})))

(defn- connect-data []
  (let [data (str (fs/create-temp-dir {:prefix "relay-connect"}))]
    (log/init! data)
    (spit (str (fs/path data "relay.edn"))
          (pr-str (-> settings (dissoc :connections) (assoc :grants {"hub" {:tools ["fetch"]}})
                      (assoc-in [:harnesses :agentloop :bin] support/agentloop-bin))))
    data))

(declare agentloop-list)

(defn- kept [keychain data] (get @keychain ["relay-mcp-fake" (connections/keychain-account data)]))

(deftest ^:volley/serial connect-signs-in-chooses-and-pins
  (when support/agentloop-bin
    (let [{:keys [url stop]} (start-fake "-rotate" "-ttl" "3600")
          keychain (atom {})
          clock (atom (Instant/parse "2026-10-18T12:00:00Z"))
          data (connect-data)
          shown (atom "")
          relay #(cli/execute (concat ["--data" data] %&))]
      (try
        (binding [connect/*edges* (edges keychain clock)]
          (testing "the operator is shown what relay proposes, and types no code"
            ;; add_note has no hints, so it is proposed edit, and off.
            (binding [cli/*terminal* (terminal shown [])]
              (let [r (relay "connect" "fake" url)]
                (is (= 0 (:exit r)))
                (is (some #(str/includes? % "add \"fake\" to :connections") (:out r)))))
            (is (str/includes? @shown "Opening your browser"))
            (is (str/includes? @shown "List subscribers (list_subscribers)\n    Reads only."))
            (is (str/includes? @shown "send_campaign\n    Off. It may change things there."))
            (is (not (str/includes? @shown "Type "))))
          (let [conn (connections/check "fake" (connections/parse (slurp (str (connections/file-path data "fake")))) opts)
                tools (into {} (map (fn [[n t]] [(:tool t) (assoc t :name n)]) (:tools conn)))]
            (testing "the file has what relay proposed, pinned"
              (is (= url (:url conn)))
              (is (= ["mcp-token" "fake" "--data" data "--url" url] (rest (:credentials-command conn))))
              (is (= [:read true] ((juxt :class :on) (tools "list_subscribers"))))
              (is (= [:edit false] ((juxt :class :on) (tools "send_campaign"))))
              (is (false? (:on (tools "delete_subscriber"))) "a delete tool starts off")
              (is (= [:edit false] ((juxt :class :on) (tools "add_note"))))
              (is (every? #(re-matches #"[0-9a-f]{64}" (:digest %)) (vals tools)))
              (is (= "rw-------" (java.nio.file.attribute.PosixFilePermissions/toString (fs/posix-file-permissions (connections/file-path data "fake")))))
              (is (not (str/includes? (slurp (str (connections/file-path data "fake"))) "at-")) "no token in the file"))
            (testing "the log pins the tools that are on"
              (let [state (inbound/current-state (inbound/system data))]
                (is (= (:digest (tools "list_subscribers")) (core/tool-pin state "fake_list_subscribers")))
                (is (nil? (core/tool-pin state "fake_send_campaign")) "a tool that starts off is not pinned")
                (is (nil? (core/tool-pin state "fake_delete_subscriber")))))
            (testing "the keychain has the sign-in, and the token works"
              (let [blob (connect/decode-blob (kept keychain data))]
                (is (str/starts-with? (:refresh-token blob) "rt-"))
                (is (= (:access-token blob) (first (:out (relay "mcp-token" "fake" "--url" url)))) "a fresh token is kept")
                (testing "near its end it is refreshed, and the rotated refresh token kept"
                  (swap! clock #(.plusSeconds ^Instant % 3300))
                  (let [t (first (:out (relay "mcp-token" "fake" "--url" url)))
                        after (connect/decode-blob (kept keychain data))]
                    (is (not= t (:access-token blob)))
                    (is (= t (:access-token after)))
                    (is (not= (:refresh-token blob) (:refresh-token after)))
                    (is (= 4 (count (agentloop-list support/agentloop-bin url t))) "the new token works")))))
            (testing "relay.edn loads it"
              (spit (str (fs/path data "relay.edn"))
                    (pr-str (-> (read-string (slurp (str (fs/path data "relay.edn"))))
                                (assoc :connections ["fake"])
                                (assoc-in [:grants "hub" :tools] ["fake_list_subscribers"]))))
              (is (= :mcp-http (get-in (config/load-config data) [:tools "fake_list_subscribers" :protocol]))))
            (testing "a token is given only for the server the command names"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is now for"
                                    (relay "mcp-token" "fake" "--url" "https://elsewhere.example/mcp"))))
            (testing "a sign-in kept for another server is not given out"
              (let [before (kept keychain data)]
                (swap! keychain update ["relay-mcp-fake" (connections/keychain-account data)]
                       #(connect/encode-blob (assoc-in (connect/decode-blob %) [:bound :resource] "https://other.example/mcp")))
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"made for another server" (relay "mcp-token" "fake" "--url" url)))
                (swap! keychain assoc ["relay-mcp-fake" (connections/keychain-account data)] before)))
            (testing "another data directory has a keychain item of its own"
              (is (not= (connections/keychain-account data) (connections/keychain-account (str data "-other")))))
            (testing "a refresh the server refuses says to sign in again"
              (swap! keychain update ["relay-mcp-fake" (connections/keychain-account data)]
                     #(connect/encode-blob (assoc (connect/decode-blob %) :refresh-token "rt-revoked" :expires-at "2000-01-01T00:00:00Z")))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"relay connect fake" (relay "mcp-token" "fake" "--url" url))))))
        (finally (stop))))))

(defn- agentloop-list [bin url token]
  (:tools (json/parse-string (:out @(p/process [bin "mcp-tools"] {:in (json/generate-string {:url url :token token})
                                                                  :out :string :err :string}))
                             true)))

(deftest ^:volley/serial a-refused-sign-in-changes-nothing
  (when support/agentloop-bin
    (let [{:keys [url stop]} (start-fake "-deny")
          keychain (atom {})
          data (connect-data)]
      (try
        (binding [connect/*edges* (edges keychain (atom (Instant/now)))
                  cli/*terminal* (terminal (atom "") [])]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused"
                                (cli/execute ["--data" data "connect" "fake" url]))))
        (is (empty? @keychain))
        (is (not (fs/exists? (connections/file-path data "fake"))))
        (finally (stop))))))

(deftest ^:volley/serial a-server-that-does-not-register-gets-the-operators-client-id
  (when support/agentloop-bin
    (let [{:keys [url stop]} (start-fake "-no-registration" "-client-id" "given-id")
          keychain (atom {})
          data (connect-data)
          shown (atom "")]
      (try
        (binding [connect/*edges* (edges keychain (atom (Instant/now)))
                  cli/*terminal* (terminal shown ["given-id"])]
          (is (= 0 (:exit (cli/execute ["--data" data "connect" "fake" url])))))
        (is (str/includes? @shown "client id"))
        (is (= "given-id" (get-in (connections/read-file data "fake") [:oauth :client-id])))
        (finally (stop))))))

(deftest the-metadata-checks
  (testing "a resource on another origin is refused"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not https://mcp.example.com/mcp"
                          (connect/check-prm {"resource" "https://evil.example/mcp" "authorization_servers" ["https://as.example"]}
                                             "https://mcp.example.com/mcp"))))
  (testing "an authorization server on plain http elsewhere is refused"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be https"
                          (connect/check-prm {"authorization_servers" ["http://as.example"]} "https://mcp.example.com/mcp"))))
  (let [as {"issuer" "https://as.example" "authorization_endpoint" "https://as.example/a" "token_endpoint" "https://as.example/t"}]
    (is (= as (connect/check-as-metadata as "https://as.example")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is for" (connect/check-as-metadata as "https://other.example")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"S256"
                          (connect/check-as-metadata (assoc as "code_challenge_methods_supported" ["plain"]) "https://as.example")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"token_endpoint"
                          (connect/check-as-metadata (assoc as "token_endpoint" "http://as.example/t") "https://as.example"))))
  (testing "the authorization URL carries PKCE, the state and the resource"
    (let [u (connect/authorize-url {"authorization_endpoint" "https://as.example/a"}
                                   {:client-id "c" :redirect "http://127.0.0.1:9/callback" :challenge "ch"
                                    :state "st" :resource "https://mcp.example.com/mcp"})
          q (connect/parse-query (second (str/split u #"\?" 2)))]
      (is (= {"response_type" "code" "client_id" "c" "redirect_uri" "http://127.0.0.1:9/callback" "code_challenge" "ch"
              "code_challenge_method" "S256" "state" "st" "resource" "https://mcp.example.com/mcp"}
             q))))
  (testing "only the right state is taken back"
    (is (nil? (connect/callback-result {"code" "c" "state" "other"} "st" "https://as" false)))
    (is (= {:code "c"} (connect/callback-result {"code" "c" "state" "st"} "st" "https://as" false)))
    (is (:error (connect/callback-result {"error" "access_denied" "state" "st"} "st" "https://as" false))))
  (testing "an answer naming another issuer is refused (RFC 9207)"
    (is (:error (connect/callback-result {"code" "c" "state" "st" "iss" "https://evil"} "st" "https://as" false)))
    (is (= {:code "c"} (connect/callback-result {"code" "c" "state" "st" "iss" "https://as"} "st" "https://as" true)))
    (is (:error (connect/callback-result {"code" "c" "state" "st"} "st" "https://as" true))
        "a server that says it names itself must"))
  (testing "a resource is the server or a path above it, never a sibling"
    (is (= "https://h/tenant" (:resource (connect/check-prm {"resource" "https://h/tenant" "authorization_servers" ["https://as"]}
                                                            "https://h/tenant/mcp"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (connect/check-prm {"resource" "https://h/tenant" "authorization_servers" ["https://as"]} "https://h/tenant-evil/mcp"))))
  (testing "the S256 challenge is RFC 7636's"
    (is (= "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
           (connect/challenge "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))))
  (testing "where metadata is looked for"
    (is (= ["https://h/.well-known/oauth-protected-resource/mcp" "https://h/.well-known/oauth-protected-resource"]
           (connect/prm-urls "https://h/mcp")))
    (is (= ["https://h/.well-known/oauth-authorization-server/t" "https://h/.well-known/openid-configuration/t"
            "https://h/t/.well-known/openid-configuration"]
           (connect/as-metadata-urls "https://h/t")))))

(deftest only-the-operator-pins-definitions
  (let [data (connect-data)
        sys (inbound/system data)]
    (inbound/submit! sys {:type :role/add :actor "operator" :args {:id "hub" :title "hub" :instructions "x"}})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Only the operator"
                          (inbound/submit! sys {:type :tools/pin :actor "hub" :args {:pins {"fake_x" digest}}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"SHA-256"
                          (inbound/submit! sys {:type :tools/pin :actor "operator" :args {:pins {"fake_x" "abc"}}})))
    (inbound/submit! sys {:type :tools/pin :actor "operator" :args {:pins {"fake_x" digest}}})
    (is (= digest (core/tool-pin (inbound/current-state sys) "fake_x")))))

(deftest a-new-name-never-takes-one-relay-edn-has
  (is (= "mail_send_campaign_2"
         (:name (last (connect/propose "mail" [(first listed)] nil #{"mail_send_campaign"}))))))

(deftest a-secret-of-any-length-is-kept-in-lines-security-reads-whole
  (is (:pass? (tc/quick-check 100
                              (prop/for-all [n (gen/choose 1 20000)]
                                (let [secret (apply str (take n (cycle "QUJDREVGR0hJSktMTU5PUA==")))
                                      lines (connect/keychain-lines "relay-mcp-x" "relay-0123" secret)
                                      items (into {} (for [l lines :let [[_ svc v] (re-matches #".* -s (\S+) -w (\S+)" l)]] [svc v]))]
                                  (and (every? #(< (count %) 4000) lines)
                                       (= secret (connect/assemble "relay-mcp-x" items))
                                       (str/includes? (last lines) "-s relay-mcp-x -w c"))))))
      "the item that names the count is written last")
  (is (nil? (connect/assemble "s" {"s" "c2.QUJD"})) "a missing item reads as no sign-in")
  (testing "no chunk is another connection's item"
    (let [secret (apply str (repeat 7000 "A"))
          services (set (for [l (connect/keychain-lines "relay-mcp-mail" "a" secret)] (second (re-find #" -s (\S+) " l))))]
      (is (= 3 (count services)))
      (is (not-any? #(re-matches (re-pattern (str "relay-mcp-" connections/name-pattern)) %) (disj services "relay-mcp-mail"))))))

(deftest a-url-with-dot-segments-is-refused
  (doseq [u ["https://h/tenant/../evil/mcp" "https://h/./mcp" "https://h/a/%2E%2E/mcp" "https://h/a/.."]]
    (is (connections/url-problem u) u))
  (is (nil? (connections/url-problem "https://h/a.b/..c/mcp"))))

;; --- a second authorization server that tries a mix-up

(defn- start-evil
  "An MCP server whose authorization server is an attacker's: it sends
  the operator to another server's sign-in (`authorize-at`) and keeps a
  token endpoint of its own. `iss` makes its metadata say it names itself.
  Its sign-in page, when it is its own, answers with the other server's
  issuer. Returns {:url :stop :token-hits}."
  [{:keys [authorize-at iss other-issuer]}]
  (let [hits (atom 0)
        base (atom nil)
        json-resp (fn [m] {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string m)})
        handler (fn [{:keys [uri query-string request-method]}]
                  (let [b @base]
                    (case uri
                      "/mcp" {:status 401 :headers {"WWW-Authenticate" "Bearer"} :body ""}
                      "/.well-known/oauth-protected-resource/mcp"
                      (json-resp {"resource" (str b "/mcp") "authorization_servers" [(str b "/as")]})
                      "/.well-known/oauth-authorization-server/as"
                      (json-resp (cond-> {"issuer" (str b "/as")
                                          "authorization_endpoint" (or authorize-at (str b "/as/authorize"))
                                          "token_endpoint" (str b "/as/token")
                                          "registration_endpoint" (str b "/as/register")
                                          "code_challenge_methods_supported" ["S256"]}
                                   iss (assoc "authorization_response_iss_parameter_supported" true)))
                      "/as/register" (json-resp {"client_id" "evil-client" "token_endpoint_auth_method" "none"})
                      "/as/authorize" (let [q (connect/parse-query query-string)]
                                        {:status 302
                                         :headers {"Location" (str (get q "redirect_uri") "?code=stolen&state="
                                                                   (java.net.URLEncoder/encode (get q "state") "UTF-8")
                                                                   "&iss=" (java.net.URLEncoder/encode other-issuer "UTF-8"))}})
                      "/as/token" (do (swap! hits inc) (json-resp {"access_token" "x" "token_type" "Bearer"}))
                      {:status 404 :body ""})))
        srv (org.httpkit.server/run-server handler {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    (reset! base (str "http://127.0.0.1:" (org.httpkit.server/server-port srv)))
    {:url (str @base "/mcp") :token-hits hits :stop #(org.httpkit.server/server-stop! srv)}))

(deftest ^:volley/serial a-mix-up-of-authorization-servers-is-refused
  (when support/agentloop-bin
    (let [honest (start-fake "-iss")]
      (try
        (testing "an authorization server that sends you to another's sign-in, and does not name itself, is refused"
          (let [{:keys [url stop token-hits]} (start-evil {:authorize-at (str (:base honest) "/as/authorize")
                                                           :other-issuer (str (:base honest) "/as")})
                opened (atom [])
                data (connect-data)]
            (try
              (binding [connect/*edges* (assoc (edges (atom {}) (atom (Instant/now))) :open-browser #(swap! opened conj %))
                        cli/*terminal* (terminal (atom "") [])]
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be sure an answer is its own"
                                      (cli/execute ["--data" data "connect" "evil" url]))))
              (is (empty? @opened) "the browser was never opened")
              (is (zero? @token-hits))
              (is (not (fs/exists? (connections/file-path data "evil"))))
              (finally (stop)))))
        (testing "an answer naming another issuer is refused, and the code and verifier go nowhere"
          (let [{:keys [url stop token-hits]} (start-evil {:iss true :other-issuer (str (:base honest) "/as")})
                keychain (atom {})
                data (connect-data)]
            (try
              (binding [connect/*edges* (edges keychain (atom (Instant/now)))
                        cli/*terminal* (terminal (atom "") [])]
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not come from"
                                      (cli/execute ["--data" data "connect" "evil" url]))))
              (is (zero? @token-hits) "the token endpoint was never sent the code")
              (is (empty? @keychain))
              (finally (stop)))))
        (testing "a server that names itself signs in as usual, at a redirect of this flow's own"
          (let [keychain (atom {})
                data (connect-data)
                shown (atom "")]
            (binding [connect/*edges* (edges keychain (atom (Instant/now)))
                      cli/*terminal* (terminal shown [])]
              (is (= 0 (:exit (cli/execute ["--data" data "connect" "fake" (:url honest)])))))
            (is (re-find #"redirect_uri=http%3A%2F%2F127\.0\.0\.1%3A\d+%2Fcallback%2F[A-Za-z0-9_-]{16}" @shown))))
        (finally ((:stop honest)))))))

;; --- a server's words

(deftest server-text-on-a-line-of-its-own-cannot-start-another-or-be-followed
  (let [[t] (connect/propose "odd" [{:name "x" :title "See https://evil.example/collect"
                                      :description "Gets.\nSign in again at https://evil.example/login"
                                      :digest digest}]
                             nil)
        line (connect/summary-line 0 t)]
    (is (not (str/includes? line "https://")) "every link is broken")
    (doseq [l (str/split-lines line)]
      (is (or (str/starts-with? l " ") (str/starts-with? l " 1.")) (str "a line of the screen's own or behind the gutter: " l))
      (is (not (str/starts-with? (str/triml l) "Sign in")) l))))
