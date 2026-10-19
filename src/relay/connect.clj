(ns relay.connect
  "relay connect <name> <url> and relay mcp-token <name>: the operator
  signs relay in to a remote MCP server, chooses which of its tools roles
  may have and what each may do, and relay writes the connection file
  (relay.connections).

  The sign-in is OAuth 2.1's authorization code flow with PKCE (S256), as
  MCP's authorization spec has it: the server's protected-resource
  metadata names its authorization server, whose metadata gives the
  endpoints; relay registers itself as a client when the server offers
  dynamic registration, and otherwise asks the operator for a client id;
  the browser comes back to a listener on 127.0.0.1 at a random port, and
  relay checks the state it sent. Every request names the MCP server as
  the resource (RFC 8707), so a token is good only there. The refresh
  token, and the access token while it lasts, go in the login keychain,
  never in a file. relay mcp-token prints an access token, refreshing it
  when it is near its end; agentloop runs it as the server's credentials
  command.

  The pure parts (classification, the summary, metadata checks, the
  authorization URL, the token blob) are plain functions. The edges
  (HTTP, the browser, the keychain, the terminal, agentloop) come in a
  map, so tests give their own."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as httpkit]
            [relay.connections :as connections])
  (:import (java.net URI URLDecoder URLEncoder)
           (java.nio.channels FileChannel)
           (java.nio.file OpenOption StandardOpenOption)
           (java.security MessageDigest SecureRandom)
           (java.time Instant)
           (java.util Base64)))

(defn- problem! [msg] (throw (ex-info msg {:relay/usage true})))

;; --- proposing what each tool may do

(def default-labels
  "What a remote tool's output is labelled: the operator's private data,
  which an outsider (whoever else writes to the service) may have
  written."
  ["contaminated" "private"])

(def delete-words #{"delete" "remove" "destroy" "purge" "erase" "wipe" "trash"})

(defn words
  "The lower case words of a tool name or title: split at anything not a
  letter or digit, and where camelCase changes case."
  [s]
  (->> (str/split (str/replace (str s) #"([a-z0-9])([A-Z])" "$1 $2") #"[^A-Za-z0-9]+")
       (remove str/blank?)
       (map str/lower-case)))

(defn delete-like?
  "Whether a tool's name or title says it deletes something. Such a tool
  starts off."
  [{:keys [name title]}]
  (boolean (some delete-words (concat (words name) (words title)))))

(defn propose-class
  "The class a tool starts with: :read when its annotations say it only
  reads (readOnlyHint), :act otherwise, whatever else they say. Nothing
  is proposed a class that runs unasked outside relay on its own say-so."
  [{:keys [annotations]}]
  (if (true? (:readOnlyHint annotations)) :read :act))

(defn relay-name
  "The name relay gives a server's tool: the connection's name, an
  underscore and the tool's, with anything agentloop does not take made
  an underscore, cut to 64 characters, and numbered when that is taken."
  [conn-name remote taken]
  (let [base (subs (str conn-name "_" (str/replace (str remote) #"[^A-Za-z0-9_-]" "_"))
                   0 (min 64 (+ 1 (count conn-name) (count (str remote)))))]
    (first (remove taken (cons base (for [i (range 2 1000)]
                                      (let [sfx (str "_" i)]
                                        (str (subs base 0 (min (count base) (- 64 (count sfx)))) sfx))))))))

(def kept-choices
  "What an earlier connect's choice for a tool carries over to a new one."
  [:class :labels :on :fixed-args :hidden-args :always-hold :consent])

(defn propose
  "Every listed tool with what it would start as: its relay name, class,
  labels, and whether it is on. An act tool always asks the operator
  (:always-hold). A tool the operator chose for before, in the connection
  file `previous`, keeps that choice and its name. No new name is one of
  `taken`, the names relay.edn's own tools have."
  [conn-name listed previous & [taken]]
  (let [before (into {} (for [[n t] (:tools previous)] [(:tool t) (assoc t :name n)]))]
    (:out (reduce (fn [{:keys [taken out]} {:keys [name] :as l}]
                    (let [old (get before name)
                          rn (or (:name old) (relay-name conn-name name taken))
                          class (propose-class l)
                          t (merge {:name rn :tool name :title (:title l) :description (:description l)
                                    :digest (:digest l) :class class :labels default-labels
                                    :on (not (delete-like? l))}
                                   (when (= :act class) {:always-hold true})
                                   (select-keys old kept-choices))]
                      {:taken (conj taken rn) :out (conj out t)}))
                  {:taken (into (set taken) (keep :name (vals before))) :out []}
                  (sort-by :name listed)))))

(defn- one-line [s] (str/trim (str/replace (str s) #"\s+" " ")))

(defn does
  "What a tool may do, in the operator's words."
  [{:keys [class on always-hold]}]
  (cond
    (not on) "Off: nothing can use it."
    always-hold (case class
                  :read "Reads, and asks you before each use."
                  "Acts for you, and asks you first every time.")
    :else (case class
            :read "Reads only. It runs without asking."
            :act "Acts for you, and asks you first when what it works with came from outside."
            :egress "Can send what it is given out to the service, and asks you first when that mixes your private things with outside text."
            :house "Changes your own things there, which nobody else sees. It runs without asking."
            (str "Class " (name class) "."))))

(defn summary-line [i {:keys [title tool description] :as t}]
  (str (format "%2d. " (inc i)) (if (str/blank? title) tool (str title " (" tool ")")) "\n"
       "    " (does t)
       ;; All of it: the model is shown all of it, and pinning it means
       ;; the operator accepted what they read.
       (when-not (str/blank? description) (str "\n    The server says: " (one-line description)))))

(defn summary
  "The lines that show the operator what connecting would allow."
  [proposal]
  (vec (map-indexed summary-line proposal)))

(defn choices
  "The answers that set a tool's class or turn it on or off, by key, for
  the classes relay knows. :write (relay's own data) is never a remote
  tool's."
  [classes]
  (into (array-map)
        (concat [["r" {:class :read :label "reads only"}]
                 ["a" {:class :act :label "acts for you, asks you every time"}]]
                (when (contains? classes :egress) [["e" {:class :egress :label "sends things out to the service"}]])
                (when (contains? classes :house) [["h" {:class :house :label "changes your own things, no asking"}]])
                [["off" {:on false :label "off"}]
                 ["on" {:on true :label "on"}]])))

(defn apply-choice
  "The proposal with one tool changed by an answer from `choices`. A tool
  set to act asks every time; one set to anything else does not, unless
  the operator wrote :always-hold in the file."
  [proposal i {:keys [class on]}]
  (update proposal i
          (fn [t]
            (cond-> t
              (some? on) (assoc :on on)
              class (assoc :class class)
              (= :act class) (assoc :always-hold true)
              (and class (not= :act class)) (dissoc :always-hold)))))

(defn connection
  "The connection file's map from what the operator chose."
  [{:keys [url credentials-command oauth connected]} proposal]
  (cond-> {:format connections/file-format
           :url url
           :credentials-command (vec credentials-command)
           :connected connected
           :tools (into {} (for [t proposal]
                             [(:name t) (into {} (filter (comp some? val))
                                              (select-keys t (into [:tool :digest] kept-choices)))]))}
    oauth (assoc :oauth oauth)))

;; --- OAuth: the pure parts

(defn- b64url [^bytes b] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn random-token
  "32 random bytes, base64url: a PKCE verifier or a state."
  [^SecureRandom rng]
  (let [b (byte-array 32)] (.nextBytes rng b) (b64url b)))

(defn challenge
  "The S256 code challenge of a verifier."
  [^String verifier]
  (b64url (.digest (MessageDigest/getInstance "SHA-256") (.getBytes verifier "US-ASCII"))))

(defn- origin [url]
  (let [u (URI. url)
        port (if (neg? (.getPort u)) (if (= "https" (.getScheme u)) 443 80) (.getPort u))]
    (str (str/lower-case (.getScheme u)) "://" (str/lower-case (.getHost u)) ":" port)))

(defn- path-of [url] (let [p (.getRawPath (URI. url))] (if (or (nil? p) (= "/" p)) "" (str/replace p #"/$" ""))))

(defn- origin-url [url] (let [u (URI. url)] (str (.getScheme u) "://" (.getRawAuthority u))))

(defn prm-urls
  "Where a server's protected-resource metadata may be (RFC 9728): under
  .well-known with the server's path, then without it."
  [mcp-url]
  (distinct (remove nil? [(str (origin-url mcp-url) "/.well-known/oauth-protected-resource" (path-of mcp-url))
                          (str (origin-url mcp-url) "/.well-known/oauth-protected-resource")])))

(defn as-metadata-urls
  "Where an authorization server's metadata may be, in the order MCP's
  spec tries them (RFC 8414, then OpenID Connect Discovery)."
  [issuer]
  (let [o (origin-url issuer) p (path-of issuer)]
    (if (str/blank? p)
      [(str o "/.well-known/oauth-authorization-server") (str o "/.well-known/openid-configuration")]
      [(str o "/.well-known/oauth-authorization-server" p) (str o "/.well-known/openid-configuration" p)
       (str o p "/.well-known/openid-configuration")])))

(defn www-authenticate
  "The parameters of a Bearer WWW-Authenticate header, by name."
  [header]
  (into {} (for [[_ k v1 v2] (re-seq #"([A-Za-z_]+)=(?:\"([^\"]*)\"|([^\s,]+))" (str header))]
             [(str/lower-case k) (or v1 v2)])))

(defn- usable-url! [what url]
  (when-let [why (connections/url-problem url)]
    (problem! (str "The server's " what " " (pr-str url) " cannot be used: " why ". Nothing was changed."))))

(defn check-prm
  "The resource a server's protected-resource metadata names, and its
  authorization server. The resource must be the server itself or a part
  of its origin above it."
  [prm mcp-url]
  (let [resource (or (get prm "resource") mcp-url)
        servers (get prm "authorization_servers")]
    (when-not (and (string? resource) (= (origin resource) (origin mcp-url))
                   (let [rp (path-of resource) mp (path-of mcp-url)]
                     (or (= rp mp) (str/starts-with? mp (str rp "/")))))
      (problem! (str "The server's metadata names the resource " (pr-str resource) ", which is not "
                     mcp-url ". Nothing was changed.")))
    (when-not (and (sequential? servers) (string? (first servers)))
      (problem! "The server's metadata names no authorization server. Nothing was changed."))
    (usable-url! "authorization server" (first servers))
    {:resource resource :issuer (first servers)
     :scopes (let [s (get prm "scopes_supported")] (when (and (sequential? s) (every? string? s)) (vec s)))}))

(defn check-as-metadata
  "An authorization server's metadata, checked: it names the issuer it
  was fetched for, its endpoints are https (or this machine), and it
  takes PKCE with S256 if it says what it takes."
  [m issuer]
  (when-not (= issuer (get m "issuer"))
    (problem! (str "The authorization server's metadata is for " (pr-str (get m "issuer")) ", not " issuer
                   ". Nothing was changed.")))
  (doseq [k ["authorization_endpoint" "token_endpoint"]]
    (when-not (string? (get m k))
      (problem! (str "The authorization server gives no " k ". Nothing was changed.")))
    (usable-url! k (get m k)))
  (when-let [r (get m "registration_endpoint")] (usable-url! "registration_endpoint" r))
  (when-let [methods (get m "code_challenge_methods_supported")]
    (when-not (and (sequential? methods) (some #{"S256"} methods))
      (problem! "The authorization server does not take PKCE with S256, which relay needs. Nothing was changed.")))
  m)

(defn- query-string [params]
  (str/join "&" (for [[k v] params :when (some? v)]
                  (str (URLEncoder/encode (name k) "UTF-8") "=" (URLEncoder/encode (str v) "UTF-8")))))

(defn authorize-url
  [as {:keys [client-id redirect challenge state resource scope]}]
  (let [ep (get as "authorization_endpoint")]
    (str ep (if (str/includes? ep "?") "&" "?")
         (query-string [[:response_type "code"] [:client_id client-id] [:redirect_uri redirect]
                        [:code_challenge challenge] [:code_challenge_method "S256"] [:state state]
                        [:resource resource] [:scope scope]]))))

(defn parse-query [q]
  (into {} (for [pair (str/split (str q) #"&") :when (not (str/blank? pair))
                 :let [[k v] (str/split pair #"=" 2)]]
             [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")])))

(defn callback-result
  "What the browser brought back: {:code c} when the state is the one
  sent, {:error why} when the server refused or the answer names another
  issuer (RFC 9207, against a mix-up of authorization servers), and nil
  for a request that is not the answer (a wrong state, say), which is
  ignored. `iss-required` is whether the server's metadata says it names
  itself in every answer."
  [params state issuer iss-required]
  (cond
    (not= state (get params "state")) nil
    (if (contains? params "iss") (not= issuer (get params "iss")) iss-required)
    {:error (str "the answer did not come from " issuer)}
    (get params "error") {:error (str (get params "error")
                                      (when-let [d (get params "error_description")] (str ": " d)))}
    (not (str/blank? (get params "code"))) {:code (get params "code")}
    :else nil))

(defn- visible-ascii? [s] (and (string? s) (re-matches #"[\x21-\x7e]+" s)))

(defn token-blob
  "What the keychain keeps after a token response: the access token, when
  it ends, the refresh token (a new one if the server rotated it, else
  the old one) and any client secret. A response without expires_in is
  taken to last 30 minutes."
  [resp ^Instant now old]
  (let [{:strs [access_token token_type expires_in refresh_token]} resp]
    (when-not (visible-ascii? access_token)
      (problem! "The authorization server's answer has no usable access token. Nothing was changed."))
    (when-not (or (nil? token_type) (= "bearer" (str/lower-case (str token_type))))
      (problem! (str "The authorization server gave a " token_type " token; relay takes only bearer tokens.")))
    (cond-> {:access-token access_token
             :expires-at (str (.plusSeconds now (long (if (pos-int? expires_in) expires_in 1800))))}
      (or refresh_token (:refresh-token old)) (assoc :refresh-token (or refresh_token (:refresh-token old)))
      (:client-secret old) (assoc :client-secret (:client-secret old))
      (:bound old) (assoc :bound (:bound old)))))

(defn bound-to
  "What a kept sign-in is for: the MCP server, and the authorization
  server and client that issued it. relay mcp-token gives a token only
  for the connection it was made for."
  [url oauth]
  {:url url :token-endpoint (:token-endpoint oauth) :resource (:resource oauth) :client-id (:client-id oauth)})

(def refresh-margin-seconds
  "An access token with less than this left is refreshed first: a wake
  can run 30 minutes, and agentloop asks again only after a refusal."
  600)

(defn usable?
  "Whether the kept access token has long enough left."
  [blob ^Instant now]
  (boolean (and (:access-token blob) (:expires-at blob)
                (.isAfter (Instant/parse (:expires-at blob)) (.plusSeconds now refresh-margin-seconds)))))

(defn encode-blob [blob] (.encodeToString (Base64/getEncoder) (.getBytes (json/generate-string blob) "UTF-8")))

(defn decode-blob [s]
  (when-not (str/blank? s)
    (try (json/parse-string (String. (.decode (Base64/getDecoder) (str/trim s)) "UTF-8") true)
         (catch Exception _ nil))))

;; --- the edges

(def http-timeout-ms 20000)

(defn http-request
  "One HTTP request that follows no redirect. Returns {:status :headers
  :body}, the body a string."
  [req]
  (let [client (http/client {:follow-redirects :never :connect-timeout http-timeout-ms})]
    (http/request (merge {:client client :throw false :timeout http-timeout-ms} req))))

(defn- json-body [resp]
  (try (json/parse-string (:body resp)) (catch Exception _ nil)))

(defn- get-json [{:keys [http]} url]
  (let [r (http {:method :get :uri url :headers {"Accept" "application/json"}})]
    (when (= 200 (:status r)) (json-body r))))

(defn- header [resp k]
  (let [v (some (fn [[hk hv]] (when (= (str/lower-case (name hk)) k) hv)) (:headers resp))]
    (if (sequential? v) (first v) v)))

(defn discover
  "Finds the authorization server for an MCP server: from the
  WWW-Authenticate header of an unsigned request, else at .well-known,
  else (MCP's 2025-03-26 rule) the MCP server's own origin."
  [env mcp-url]
  (let [probe ((:http env) {:method :post :uri mcp-url
                            :headers {"Content-Type" "application/json" "Accept" "application/json, text/event-stream"}
                            :body (json/generate-string {:jsonrpc "2.0" :id 1 :method "initialize"
                                                         :params {:protocolVersion "2025-06-18" :capabilities {}
                                                                  :clientInfo {:name "relay" :version "1"}}})})
        params (when (= 401 (:status probe)) (www-authenticate (header probe "www-authenticate")))
        hinted (get params "resource_metadata")
        _ (when hinted (usable-url! "resource metadata" hinted))
        prm (some #(get-json env %) (cond->> (prm-urls mcp-url) hinted (cons hinted)))
        {:keys [resource issuer scopes]} (if prm
                                           (check-prm prm mcp-url)
                                           {:resource mcp-url :issuer (origin-url mcp-url)})
        as (or (some #(get-json env %) (as-metadata-urls issuer))
               (problem! (str "relay found no authorization server metadata for " issuer ". Nothing was changed.")))]
    {:resource resource
     :scope (or (get params "scope") (when (seq scopes) (str/join " " scopes)))
     :as (check-as-metadata as issuer)}))

(defn register
  "Registers relay with the authorization server as a public client that
  comes back to `redirect` (RFC 7591)."
  [env as redirect]
  (let [r ((:http env) {:method :post :uri (get as "registration_endpoint")
                        :headers {"Content-Type" "application/json" "Accept" "application/json"}
                        :body (json/generate-string {:client_name "relay" :redirect_uris [redirect]
                                                     :grant_types ["authorization_code" "refresh_token"]
                                                     :response_types ["code"]
                                                     :token_endpoint_auth_method "none"})})
        body (json-body r)]
    (when-not (and (#{200 201} (:status r)) (string? (get body "client_id")))
      (problem! (str "The authorization server would not register relay (HTTP " (:status r) ")."
                     " Nothing was changed.")))
    {:client-id (get body "client_id")
     :client-secret (get body "client_secret")
     :auth-method (get body "token_endpoint_auth_method" "none")}))

(defn token-request
  "A request to the token endpoint, with the client secret when there is
  one, the way the client registered to send it."
  [env token-endpoint {:keys [client-id client-secret auth-method]} params]
  (let [basic? (and client-secret (= "client_secret_basic" auth-method))
        form (cond-> (merge params {:client_id client-id})
               (and client-secret (not basic?)) (assoc :client_secret client-secret))
        r ((:http env) {:method :post :uri token-endpoint
                        :headers (cond-> {"Content-Type" "application/x-www-form-urlencoded" "Accept" "application/json"}
                                   basic? (assoc "Authorization"
                                                 (str "Basic " (.encodeToString (Base64/getEncoder)
                                                                                (.getBytes (str (URLEncoder/encode client-id "UTF-8") ":"
                                                                                                (URLEncoder/encode client-secret "UTF-8"))
                                                                                           "UTF-8")))))
                        :body (query-string (sort-by key form))})]
    {:status (:status r) :body (json-body r)}))

(defn listen
  "Listens on 127.0.0.1 at a free port for the browser's return to
  /callback. Returns {:redirect :result (a promise) :stop}. Only the
  first answer counts."
  [state issuer iss-required]
  (let [result (promise)
        handler (fn [{:keys [uri query-string request-method]}]
                  (if-let [r (and (= :get request-method) (= "/callback" uri)
                                  (callback-result (parse-query query-string) state issuer iss-required))]
                    (do (deliver result r)
                        {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
                         :body (if (:code r)
                                 "<p>relay is signed in. You can close this tab and go back to the terminal.</p>"
                                 "<p>The sign-in was refused. Go back to the terminal.</p>")})
                    {:status 404 :body ""}))
        srv (httpkit/run-server handler {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    {:redirect (str "http://127.0.0.1:" (httpkit/server-port srv) "/callback")
     :result result
     :stop #(httpkit/server-stop! srv)}))

(defn open-browser
  "Opens a URL in the operator's browser."
  [url]
  (p/process ["open" url] {:out :discard :err :discard}))

(def chunk-chars
  "The most secret characters in one keychain item. security -i reads a
  line of at most 4095 characters and runs whatever is past that as a
  command of its own, so each item's line stays well under it."
  3000)

(defn chunk-item
  "The keychain item that holds chunk i. A dot, which no connection name
  has, so no chunk is ever another connection's item."
  [service i]
  (if (zero? i) service (str service "." i)))

(defn keychain-lines
  "The security -i lines that keep a base64 secret under `service`: the
  secret in items of at most chunk-chars, the first (`service` itself)
  saying how many there are, as c<n>.<chunk>. Base64 has no dot, so the
  count reads back unambiguously. The first item is written last."
  [service account secret]
  (let [chunks (mapv #(apply str %) (partition-all chunk-chars secret))
        n (count chunks)]
    (vec (for [i (reverse (range n))]
           (str "add-generic-password -U -a " account " -s " (chunk-item service i) " -w "
                (if (zero? i) (str "c" n "." (chunks 0)) (chunks i)))))))

(defn assemble
  "The secret from its items, read by `read-item` (a function of the
  item's service), or nil when any is missing."
  [service read-item]
  (when-let [[_ n first-chunk] (some->> (read-item service) (re-matches #"c([0-9]{1,3})\.([A-Za-z0-9+/=]*)"))]
    (let [rest-chunks (for [i (range 1 (parse-long n))] (read-item (chunk-item service i)))]
      (when (every? some? rest-chunks)
        (apply str first-chunk rest-chunks)))))

(defn keychain-read
  "The secret kept under `service` and `account` in the login keychain,
  or nil."
  [service account]
  (assemble service
            (fn [item]
              (let [r @(p/process ["security" "find-generic-password" "-a" account "-s" item "-w"]
                                  {:out :string :err :string :in ""})]
                (when (zero? (:exit r)) (str/trim (:out r)))))))

(defn keychain-write!
  "Keeps `secret` (base64, so it needs no quoting) under `service` in the
  login keychain, replacing what was there. The secret goes to security
  on stdin, never in an argument list, and what security prints is never
  repeated, since it can echo what it was given."
  [service account secret]
  (when-not (and (re-matches #"[A-Za-z0-9+/=]+" secret) (re-matches #"[A-Za-z0-9_.-]+" (str service account)))
    (throw (ex-info "A keychain secret must be base64, and its service and account plain names." {})))
  (let [r @(p/process ["security" "-i"]
                      {:in (str (str/join "\n" (keychain-lines service account secret)) "\n")
                       :out :string :err :string})]
    (when-not (and (zero? (:exit r)) (str/blank? (:err r)))
      (problem! (str "relay could not keep the sign-in in the login keychain (security exited " (:exit r)
                     "). Nothing else was changed.")))))

(defn list-tools
  "Every tool of the remote server, as agentloop mcp-tools lists them,
  each with the digest agentloop pins it at. The token goes to agentloop
  on stdin."
  [agentloop-bin url token]
  (let [r @(p/process [agentloop-bin "mcp-tools"]
                      {:in (json/generate-string {:url url :token token}) :out :string :err :string})]
    (when-not (zero? (:exit r))
      (problem! (str "relay could not list the server's tools: " (str/trim (:err r)) " Nothing was changed.")))
    (:tools (json/parse-string (:out r) true))))

(def ^:dynamic *edges*
  "Overrides for the edges, bound in tests: any of :http, :open-browser,
  :keychain-read, :keychain-write!, :now, :wait-ms."
  {})

(defn edges []
  (merge {:http http-request :open-browser open-browser :keychain-read keychain-read
          :keychain-write! keychain-write! :now #(Instant/now) :wait-ms (* 5 60 1000)}
         *edges*))

;; --- relay connect

(defn- sign-in!
  "Runs the sign-in and returns {:oauth (for the file) :blob (for the
  keychain)}."
  [env {:keys [url say ask client-id]}]
  (let [rng (SecureRandom.)
        {:keys [resource scope as]} (discover env url)
        verifier (random-token rng)
        state (random-token rng)
        {:keys [redirect result stop]} (listen state (get as "issuer")
                                                (true? (get as "authorization_response_iss_parameter_supported")))]
    (try
      (let [client (cond
                     client-id {:client-id client-id}
                     (get as "registration_endpoint") (register env as redirect)
                     :else (let [_ (say (str "This server does not let relay register itself. Give the client id"
                                             " you were given for relay (its redirect is http://127.0.0.1 at any port): "))
                                 id (some-> (ask) str/trim)]
                             (when (str/blank? id) (problem! "No client id was given. Nothing was changed."))
                             {:client-id id}))
            auth (authorize-url as {:client-id (:client-id client) :redirect redirect :challenge (challenge verifier)
                                    :state state :resource resource :scope scope})]
        (say (str "Opening your browser to sign in. If it does not open, visit this address:\n" auth "\n"))
        ((:open-browser env) auth)
        (let [r (deref result (:wait-ms env) nil)]
          (cond
            (nil? r) (problem! "The sign-in did not come back within five minutes. Nothing was changed.")
            (:error r) (problem! (str "The sign-in was refused: " (:error r) ". Nothing was changed."))))
        (let [{:keys [status body]} (token-request env (get as "token_endpoint") client
                                                   {:grant_type "authorization_code" :code (:code @result)
                                                    :redirect_uri redirect :code_verifier verifier
                                                    :resource resource})]
          (when-not (= 200 status)
            (problem! (str "The authorization server would not give relay a token (HTTP " status
                           (when-let [e (get body "error")] (str ", " e)) "). Nothing was changed.")))
          (let [oauth (cond-> {:issuer (get as "issuer") :token-endpoint (get as "token_endpoint")
                               :client-id (:client-id client) :resource resource}
                        (:auth-method client) (assoc :auth-method (:auth-method client))
                        scope (assoc :scope scope))]
            {:oauth oauth
             :blob (token-blob body ((:now env)) {:client-secret (:client-secret client)
                                                  :bound (bound-to url oauth)})})))
      (finally (stop)))))

(defn- choose!
  "Shows the proposal and lets the operator change it, until they press
  Enter. Returns the proposal as chosen."
  [proposal {:keys [say ask classes]}]
  (let [opts (choices classes)]
    (loop [proposal proposal]
      (say (str "\n" (str/join "\n" (summary proposal))
                "\n\nType a number to change that tool, or press Enter if this is right: "))
      (let [a (some-> (ask) str/trim)
            i (some-> a parse-long dec)]
        (cond
          (nil? a) (problem! "The terminal closed. Nothing was changed.")
          (str/blank? a) proposal
          (not (and i (< -1 i (count proposal)))) (recur proposal)
          :else (do (say (str (:tool (proposal i)) ": "
                              (str/join ", " (for [[k {:keys [label]}] opts] (str k " " label))) ": "))
                    (if-let [c (get opts (some-> (ask) str/trim str/lower-case))]
                      (recur (apply-choice proposal i c))
                      (recur proposal))))))))

(defn connect!
  "relay connect. `opts`: :name, :url, :data-dir (absolute), :relay-bin,
  :agentloop, :taken (tool names relay.edn already gives), :classes (what relay.edn's tools may be), :client-id, the
  terminal's :say and :ask, :confirm! (shows lines and asks for the typed
  code, throwing when it is wrong) and :pin! (records the pins in the
  log). Returns the lines to print."
  [{:keys [name url data-dir relay-bin agentloop say confirm! pin!] :as opts}]
  (when-not (and (string? name) (re-matches connections/name-pattern name))
    (problem! (str "A connection's name is 1 to 32 lower case letters, digits, - or _, such as mailerlite;"
                   " not " (pr-str name) ".")))
  (when-let [why (connections/url-problem url)]
    (problem! (str "The address " (pr-str url) " cannot be used: " why ".")))
  (when-not agentloop
    (problem! "relay connect needs agentloop to list the server's tools: pass --agentloop <bin>."))
  (let [env (edges)
        previous (try (connections/read-file data-dir name) (catch Exception _ nil))
        _ (say (str "Connecting to " url ".\n"))
        {:keys [oauth blob]} (sign-in! env opts)
        listed (list-tools agentloop url (:access-token blob))
        _ (when (empty? listed) (problem! "The server offers no tools. Nothing was changed."))
        proposal (choose! (propose name listed previous (:taken opts)) opts)
        on (filter :on proposal)
        conn (connection {:url url :oauth oauth :connected (str ((:now env)))
                          :credentials-command [relay-bin "mcp-token" name "--data" data-dir "--url" url]}
                         proposal)]
    (confirm! (concat [(str "Connect relay to " url " as " name ", with these tools:") ""]
                      (summary proposal)
                      [""
                       (str "relay keeps the sign-in in your login keychain, as " (connections/keychain-service name) ".")
                       (str "It writes " (str (connections/file-path data-dir name)) ", which no role can read.")
                       (if (seq on)
                         "No role can use these tools until relay.edn grants them."
                         "Every tool is off, so no role can use any of them.")]))
    ((:keychain-write! env) (connections/keychain-service name) (connections/keychain-account data-dir) (encode-blob blob))
    (let [f (connections/file-path data-dir name)
          tmp (fs/path (fs/parent f) (str "." name ".edn.tmp"))]
      (fs/create-dirs (fs/parent f))
      (fs/set-posix-file-permissions (fs/parent f) "rwx------")
      (spit (str tmp) (connections/render name conn))
      (fs/set-posix-file-permissions tmp "rw-------")
      (fs/move tmp f {:replace-existing true :atomic-move true}))
    (when (seq on)
      (pin! (into {} (for [t on] [(:name t) (:digest t)]))))
    (concat [(str "Connected " name ".")]
            (when (seq on)
              [(str "To use it, add \"" name "\" to :connections in relay.edn, and grant tools by name in :grants,"
                    " such as {\"hub\" {:tools [\"" (:name (first on)) "\"]}}.")
               (str "Its tools: " (str/join ", " (map :name on)) ".")]))))

;; --- relay mcp-token

(defn- with-lock
  "Runs f holding the connection's lock file, so two runs never refresh
  at once and spend a refresh token twice."
  [data-dir conn-name f]
  (let [lock (fs/path data-dir "connections" (str "." conn-name ".lock"))]
    (with-open [ch (FileChannel/open (.toPath (fs/file lock))
                                     (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
      ;; Closing the channel releases the lock.
      (.lock ^FileChannel ch)
      (f))))

(defn token!
  "An access token for the connection, refreshed first when it has less
  than refresh-margin-seconds left. A rotated refresh token replaces the
  old one in the keychain. `url` is the server the token is for, as the
  credentials command names it: a token is given only when the
  connection still names that server and the kept sign-in was made for
  it, so a session started before the operator connected the name
  elsewhere never gets the other server's token."
  [data-dir conn-name url]
  (when-not (and (string? conn-name) (re-matches connections/name-pattern conn-name) (string? url))
    (problem! "usage: relay mcp-token <connection> --url <url>"))
  (let [env (edges)
        conn (or (connections/read-file data-dir conn-name)
                 (problem! (str "There is no connection " conn-name ". Run relay connect " conn-name " <url>.")))
        {:keys [oauth]} conn
        service (connections/keychain-service conn-name)
        account (connections/keychain-account data-dir)
        again (str "Run relay connect " conn-name " " (:url conn) " to sign in again.")]
    (when-not (and (map? oauth) (string? (:token-endpoint oauth)))
      (problem! (str "The connection " conn-name " was not signed in with relay connect.")))
    (when-not (= url (:url conn))
      (problem! (str "The connection " conn-name " is now for " (:url conn) ", not " url
                     ". A session that started before it changed gets no token; the next one will.")))
    (with-lock data-dir conn-name
      (fn []
        (let [blob (decode-blob ((:keychain-read env) service account))
              now ((:now env))]
          (cond
            (nil? blob) (problem! (str "The login keychain has no sign-in for " conn-name ". " again))
            (not= (:bound blob) (bound-to (:url conn) oauth))
            (problem! (str "The sign-in kept for " conn-name " was made for another server. " again))
            (usable? blob now) (:access-token blob)
            (nil? (:refresh-token blob)) (problem! (str "The sign-in for " conn-name " has run out. " again))
            :else
            (let [{:keys [status body]} (token-request env (:token-endpoint oauth)
                                                       {:client-id (:client-id oauth) :client-secret (:client-secret blob)
                                                        :auth-method (:auth-method oauth)}
                                                       {:grant_type "refresh_token" :refresh_token (:refresh-token blob)
                                                        :resource (:resource oauth)})]
              (when-not (= 200 status)
                (problem! (str "The server would not renew the sign-in for " conn-name " (HTTP " status
                               (when-let [e (get body "error")] (str ", " e)) "). " again)))
              (let [fresh (token-blob body now blob)]
                ((:keychain-write! env) service account (encode-blob fresh))
                (:access-token fresh)))))))))
