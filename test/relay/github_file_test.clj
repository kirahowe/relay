(ns relay.github-file-test
  "tools/github-file, run as relay runs an external tool: the configured
  command and its pinned flags, then the role's arguments. A local server
  stands in for GitHub's contents API."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(def script "tools/github-file/github-file")

(defn- with-api
  "Runs f with the base URL of a fake contents API serving files, a map of
  [repo ref path] to body, and an atom of the requests it saw."
  [files f]
  (let [seen (atom [])
        handler (fn [{:keys [uri query-string headers]}]
                  (swap! seen conj {:uri uri :query query-string :accept (get headers "accept")})
                  (let [[_ repo path] (re-matches #"/repos/([^/]+/[^/]+)/contents/(.+)" uri)
                        ref (some->> query-string (re-find #"ref=([^&]+)") second java.net.URLDecoder/decode)]
                    (if-let [body (get files [repo ref path])]
                      {:status 200 :body body}
                      {:status 404 :body "{\"message\":\"Not Found\"}"})))
        srv (server/run-server handler {:port 0 :legacy-return-value? false})]
    (try (f (str "http://127.0.0.1:" (server/server-port srv)) seen)
         (finally (server/server-stop! srv)))))

(defn- run [api & args]
  (apply p/shell {:out :string :err :string :continue true :in ""}
         "bb" script "--api" api "--repo" "kirahowe/slk" "--ref" "claude/upstream-review" args))

(deftest prints-a-file-at-the-pinned-repo-and-ref
  (with-api {["kirahowe/slk" "claude/upstream-review" "state.json"] "{\"counts\":{}}\n"
             ["kirahowe/slk" "claude/upstream-review" "reports/2026-10-16.md"] "# Upstream review\n"}
    (fn [api seen]
      (let [{:keys [exit out]} (run api "get" "state.json")]
        (is (zero? exit))
        (is (= "{\"counts\":{}}\n" out)))
      (testing "a nested path"
        (is (= "# Upstream review\n" (:out (run api "get" "reports/2026-10-16.md")))))
      (testing "asks for the raw file, with the ref encoded"
        (is (= "application/vnd.github.raw" (:accept (first @seen))))
        (is (= "ref=claude%2Fupstream-review" (:query (first @seen))))))))

(deftest a-missing-file-says-so-and-fails
  (with-api {}
    (fn [api _]
      (let [{:keys [exit err]} (run api "get" "latest.md")]
        (is (= 1 exit))
        (is (str/includes? err "No latest.md at claude/upstream-review in kirahowe/slk"))))))

(deftest paths-that-could-leave-the-repo-are-refused-before-any-request
  (with-api {}
    (fn [api seen]
      (doseq [path ["../x" "a/../../b" "/etc/passwd" "a//b" "a b" "" "a?ref=main"]]
        (let [{:keys [exit err]} (run api "get" path)]
          (is (= 2 exit) (str "path " (pr-str path)))
          (is (str/includes? err "Not a file path") (str "path " (pr-str path)))))
      (is (empty? @seen)))))

(deftest the-role-cannot-move-the-pinned-repo-or-ref
  (with-api {["other/repo" "main" "x"] "secret"}
    (fn [api seen]
      (let [{:keys [exit err]} (run api "get" "x" "--repo" "other/repo")]
        (is (= 2 exit))
        (is (str/includes? err "Usage")))
      (is (empty? @seen)))))

(deftest the-role-cannot-supply-flags-before-its-command
  ;; relay appends the role's arguments right after the pinned flags, so
  ;; flags at the head of the role's arguments must not be honoured.
  (with-api {["other/repo" "main" "x"] "secret"}
    (fn [api seen]
      (with-api {}
        (fn [evil evil-seen]
          (doseq [role-args [["--api" (str evil "/exfil/SECRET") "get" "x"]
                             ["--repo" "other/repo" "get" "x"]
                             ["--ref" "main" "get" "x"]
                             ["--repo" "other/repo" "--ref" "main" "get" "x"]]]
            (let [{:keys [exit err]} (apply run api role-args)]
              (is (= 2 exit) (pr-str role-args))
              (is (str/includes? err "Usage") (pr-str role-args))))
          (is (empty? @evil-seen))))
      (is (empty? @seen)))))

(deftest an-api-given-after-the-pinned-flags-is-refused
  (with-api {}
    (fn [api seen]
      (let [{:keys [exit]} (p/shell {:out :string :err :string :continue true :in ""}
                                    "bb" script "--repo" "kirahowe/slk" "--ref" "claude/upstream-review"
                                    "--api" api "get" "state.json")]
        (is (= 2 exit)))
      (is (empty? @seen)))))

(deftest help-describes-the-commands
  (let [{:keys [exit out]} (p/shell {:out :string :err :string :continue true :in ""}
                                    "bb" script "--repo" "kirahowe/slk" "--ref" "main" "help")]
    (is (zero? exit))
    (is (str/includes? out "get <path>"))
    (is (str/includes? out "kirahowe/slk"))))
