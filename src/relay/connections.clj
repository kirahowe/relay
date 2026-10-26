(ns relay.connections
  "Remote MCP servers the operator connected with relay connect. Each is a
  file, <data>/connections/<name>.edn, that relay connect writes and only
  the operator edits: the server's URL, the command that prints a fresh
  access token, what relay mcp-token needs to refresh it, and the tools
  the operator allowed, each with its class, labels and the digest its
  definition was pinned at when the operator looked at it.

  relay.edn's :connections names the connections to load. load-config
  merges each into the settings before they are checked: the server into
  :mcp-servers, and each tool that is on into :tools, with :protocol
  :mcp-http. Grants still name tools. No role has a tool that reads or
  writes either file.

  Pure apart from reading the files: validation, the merge and the file's
  text are functions of data."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import (java.net URI)))

(def file-format 1)

(def name-pattern
  "A connection's name: also its file's name and its keychain item's
  (relay-mcp-<name>). Lower case, so two names never share a file on a
  file system that ignores case."
  #"[a-z0-9][a-z0-9_-]{0,31}")

(def tool-name-pattern
  "A tool's name in relay: agentloop's tool names."
  #"[A-Za-z0-9_-]{1,64}")

(defn- problem! [msg] (throw (ex-info msg {:relay/usage true})))

(defn file-path [data-dir conn-name]
  (fs/path data-dir "connections" (str conn-name ".edn")))

(defn keychain-service [conn-name] (str "relay-mcp-" conn-name))

(defn keychain-account
  "The keychain item's account: a digest of the data directory's absolute
  path, so two data directories with a connection of the same name never
  share a sign-in."
  [data-dir]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes (str (fs/normalize (fs/absolutize data-dir))) "UTF-8"))]
    (str "relay-" (subs (apply str (map #(format "%02x" %) d)) 0 16))))

(defn loopback? [host]
  (let [h (str/lower-case (str host))]
    (or (= "localhost" h)
        (re-matches #"127(\.\d{1,3}){3}" h)
        (= "[::1]" h) (= "::1" h))))

(defn url-problem
  "Why a remote server's URL (or an OAuth endpoint's) cannot be used, or
  nil: it must be https, or http to this machine, with a host and no user
  name, password or fragment. agentloop's mcp.CheckURL has the same rule."
  [url]
  (let [u (try (URI. (str url)) (catch Exception _ nil))]
    (cond
      (not (string? url)) "it is not a string"
      (nil? u) "it does not parse"
      (str/blank? (.getHost u)) "it has no host"
      (.getRawUserInfo u) "it holds a user name or password"
      (or (.getRawFragment u) (str/includes? url "#")) "it has a fragment"
      ;; A path that a server would read as another one, such as
      ;; /tenant/../other, could pass a check of where it is.
      (some #{"." ".."} (str/split (str/replace (str/lower-case (str (.getRawPath u))) "%2e" ".") #"/"))
      "its path has . or .. segments"
      (= "https" (.getScheme u)) nil
      (and (= "http" (.getScheme u)) (loopback? (.getHost u))) nil
      (= "http" (.getScheme u)) "it is http to a host that is not this machine; it must be https"
      :else "it is not https")))

(defn- argv? [x]
  (and (vector? x) (seq x) (every? #(and (string? %) (not (str/includes? % "\u0000"))) x)
       (not (str/blank? (first x)))))

(defn json-value?
  "Whether x is a value JSON can hold exactly: what :fixed-args may give."
  [x]
  (cond (or (nil? x) (string? x) (boolean? x) (integer? x)) true
        (number? x) (and (double? x) (Double/isFinite x))
        (vector? x) (every? json-value? x)
        (map? x) (and (every? string? (keys x)) (every? json-value? (vals x)))
        :else false))

(def tool-keys #{:tool :class :labels :digest :on :fixed-args :hidden-args})

(defn check-narrowing
  "A tool's :fixed-args, a map of argument names to JSON values, and
  :hidden-args, a vector of distinct names, none of them fixed too."
  [where {:keys [fixed-args hidden-args]}]
  (when-not (or (nil? fixed-args)
                (and (map? fixed-args) (every? #(and (string? %) (not (str/blank? %))) (keys fixed-args))
                     (every? json-value? (vals fixed-args))))
    (problem! (str where " has :fixed-args " (pr-str fixed-args) "; it must map argument names (strings)"
                   " to the values every call gets, such as {\"send_now\" false}.")))
  (when-not (or (nil? hidden-args)
                (and (vector? hidden-args) (every? #(and (string? %) (not (str/blank? %))) hidden-args)
                     (apply distinct? nil hidden-args)))
    (problem! (str where " has :hidden-args " (pr-str hidden-args) "; it must be a vector of distinct"
                   " argument names, such as [\"reply_to\"].")))
  (when-let [both (seq (filter #(contains? fixed-args %) hidden-args))]
    (problem! (str where " both fixes and hides " (str/join ", " both) "."))))

(defn check
  "Returns the connection, or throws describing its first problem.
  `classes` and `labels` are what relay.edn's tools may have, so a class
  relay comes to know is accepted here too."
  [conn-name conn {:keys [classes labels]}]
  (let [where (str "The connection " conn-name " (connections/" conn-name ".edn)")]
    (when-not (map? conn) (problem! (str where " is not a map.")))
    (when-not (= file-format (:format conn))
      (problem! (str where " has :format " (pr-str (:format conn)) "; relay reads format " file-format ".")))
    (when-let [why (url-problem (:url conn))]
      (problem! (str where " has :url " (pr-str (:url conn)) ", which cannot be used: " why ".")))
    (when-not (argv? (:credentials-command conn))
      (problem! (str where " needs :credentials-command, the command that prints an access token.")))
    (when-let [oauth (:oauth conn)]
      (when-not (and (map? oauth) (string? (:client-id oauth)) (string? (:resource oauth))
                     (nil? (url-problem (:token-endpoint oauth))))
        (problem! (str where " has an :oauth that relay mcp-token cannot use: it needs :client-id, :resource"
                       " and an https :token-endpoint. Run relay connect again."))))
    (let [tools (:tools conn)]
      (when-not (and (map? tools) (seq tools))
        (problem! (str where " has no :tools.")))
      (doseq [[n {:keys [tool class digest on] :as t}] tools
              :let [w (str "Tool " (pr-str n) " of the connection " conn-name)]]
        (when-not (and (string? n) (re-matches tool-name-pattern n))
          (problem! (str w " needs a name of 1 to 64 letters, digits, - or _.")))
        (when-not (map? t) (problem! (str w " is not a map.")))
        (when-let [extra (seq (remove tool-keys (keys t)))]
          (problem! (str w " has " (str/join ", " (map pr-str (sort-by str extra))) ", which relay does not know.")))
        (when-not (and (string? tool) (not (str/blank? tool)) (not (str/includes? tool "\u0000")))
          (problem! (str w " needs :tool, the server's own name for it.")))
        (when-not (contains? classes class)
          (problem! (str w " has :class " (pr-str class) "; it must be one of "
                         (str/join ", " (sort (map name classes))) ".")))
        (when-not (and (vector? (:labels t)) (every? labels (:labels t)))
          (problem! (str w " needs :labels, from " (str/join ", " (sort labels)) ".")))
        (when-not (and (string? digest) (re-matches #"[0-9a-f]{64}" digest))
          (problem! (str w " needs :digest, the SHA-256 its definition was pinned at.")))
        (when-not (boolean? on)
          (problem! (str w " needs :on, true or false.")))
        (check-narrowing w t))
      (doseq [[remote ns] (group-by (comp :tool val) tools) :when (next ns)]
        (problem! (str where " gives the server's tool " (pr-str remote) " twice."))))
    conn))

(defn ->settings
  "What a connection adds to relay.edn: its server, and its tools that are
  on. A tool is pinned at its connect-time digest (:pin), which a changed
  definition replaces in the log; relay.edn's own :digest is stricter and
  is not used here."
  [conn-name conn]
  {:server {:url (:url conn) :credentials-command (:credentials-command conn)}
   :tools (into {}
                (for [[n t] (:tools conn) :when (:on t)]
                  [n (cond-> {:protocol :mcp-http :server conn-name :connection conn-name
                              :tool (:tool t) :class (:class t) :labels (:labels t) :pin (:digest t)}
                       (seq (:fixed-args t)) (assoc :fixed-args (:fixed-args t))
                       (seq (:hidden-args t)) (assoc :hidden-args (:hidden-args t)))]))})

(defn read-file
  "The connection's file, read as EDN, or nil when there is none."
  [data-dir conn-name]
  (let [f (file-path data-dir conn-name)]
    (when (fs/exists? f)
      (try (edn/read-string (slurp (str f)))
           (catch Exception e
             (problem! (str "connections/" conn-name ".edn is not EDN: " (ex-message e))))))))

(defn load-checked
  "The checked connection of that name, or a refusal saying how to make it."
  [data-dir conn-name opts]
  (when-not (and (string? conn-name) (re-matches name-pattern conn-name))
    (problem! (str "relay.edn's :connections has " (pr-str conn-name) ", which is not a connection name:"
                   " 1 to 32 lower case letters, digits, - or _, starting with a letter or digit.")))
  (let [conn (read-file data-dir conn-name)]
    (when (nil? conn)
      (problem! (str "relay.edn names the connection " conn-name ", but there is no connections/" conn-name
                     ".edn. Run relay connect " conn-name " <url> to make it.")))
    (check conn-name conn opts)))

(defn expand
  "relay.edn's settings with every connection it names merged in. A
  connection may not share a name with one of :mcp-servers, nor give a
  tool a name :tools already has."
  [data-dir settings opts]
  (let [names (:connections settings)]
    (when-not (or (nil? names) (and (vector? names) (apply distinct? nil names)))
      (problem! (str "relay.edn has :connections " (pr-str names) "; it must be a vector of distinct"
                     " connection names, such as [\"mailerlite\"].")))
    (reduce (fn [s n]
              (let [{:keys [server tools]} (->settings n (load-checked data-dir n opts))]
                (when (contains? (:mcp-servers s) n)
                  (problem! (str "relay.edn has an MCP server and a connection both called " n ".")))
                (when-let [clash (seq (filter #(contains? (:tools s) %) (keys tools)))]
                  (problem! (str "The connection " n " gives the tool " (first (sort clash))
                                 ", which relay.edn's :tools already has.")))
                (-> s
                    (assoc-in [:mcp-servers n] server)
                    (update :tools merge tools))))
            settings names)))

(defn- sorted-deep [x]
  (cond (map? x) (into (sorted-map-by #(compare (str %1) (str %2))) (update-vals x sorted-deep))
        (vector? x) (mapv sorted-deep x)
        :else x))

(defn render
  "The connection file's text: a comment for the operator, then the
  connection, with every map sorted so the same connection always gives
  the same text."
  [conn-name conn]
  (str ";; relay connect wrote this for the connection " conn-name ". Only you edit it;\n"
       ";; no role can read it. A tool with :on false is not offered to any role.\n"
       ";; Grant tools by name in relay.edn's :grants, and list \"" conn-name "\" in :connections.\n"
       (with-out-str (binding [pprint/*print-right-margin* 100] (pprint/pprint (sorted-deep conn))))))

(defn parse
  "A connection file's text, read back."
  [text]
  (edn/read-string text))
