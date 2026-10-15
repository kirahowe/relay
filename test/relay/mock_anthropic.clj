(ns relay.mock-anthropic
  "A stand-in for the Anthropic Messages API, so the real pi harness can be
  driven end to end without a key or a bill. It records every request and
  answers with whatever the test's responder returns for it:

    {:text \"...\"}                               a plain reply
    {:tool-uses [{:name n :input {...}}] :text t}  a reply that calls tools
    {:stop-reason \"refusal\"}                     a reply that ends the way named
    {:delay-ms n}                                  any reply, after a wait
    {:status 429 :type \"rate_limit_error\" :message m :headers {...}}
                                                   an API error

  It also stands in for the Models API: GET /v1/models/{id} describes any
  model, with a context window of `window` tokens. Those requests are not
  recorded and never reach the responder."
  (:require [cheshire.core :as json]
            [org.httpkit.server :as http]))

(defn- sse [events]
  (apply str (for [e events]
               (str "event: " (:type e) "\ndata: " (json/generate-string e) "\n\n"))))

(defn- message-events [model {:keys [text tool-uses stop-reason]}]
  (let [blocks (concat (when text [{:kind :text :text text}])
                       (map-indexed (fn [i t] (assoc t :kind :tool :id (str "toolu_" (System/nanoTime) "_" i)))
                                    tool-uses))]
    (concat
     [{:type "message_start"
       :message {:id (str "msg_" (System/nanoTime)) :type "message" :role "assistant" :model model
                 :content [] :stop_reason nil :stop_sequence nil
                 :usage {:input_tokens 10 :output_tokens 1}}}]
     (apply concat
            (map-indexed
             (fn [i {:keys [kind] :as b}]
               (if (= :text kind)
                 [{:type "content_block_start" :index i :content_block {:type "text" :text ""}}
                  {:type "content_block_delta" :index i :delta {:type "text_delta" :text (:text b)}}
                  {:type "content_block_stop" :index i}]
                 [{:type "content_block_start" :index i
                   :content_block {:type "tool_use" :id (:id b) :name (:name b) :input {}}}
                  {:type "content_block_delta" :index i
                   :delta {:type "input_json_delta" :partial_json (json/generate-string (:input b))}}
                  {:type "content_block_stop" :index i}]))
             blocks))
     [{:type "message_delta" :delta {:stop_reason (or stop-reason (if (seq tool-uses) "tool_use" "end_turn"))
                                     :stop_sequence nil}
       :usage {:output_tokens 5}}
      {:type "message_stop"}])))

(def window
  "The context window, in tokens, of every model the Models API describes."
  100000)

(defn- describe [uri]
  (let [id (subs uri (count "/v1/models/"))]
    {:status 200
     :headers {"Content-Type" "application/json" "request-id" "req_mock"}
     :body (json/generate-string {:type "model" :id id :display_name "Mock model"
                                  :created_at "2026-01-01T00:00:00Z"
                                  :max_input_tokens window :max_tokens 64000 :capabilities {}})}))

(defn- answer [requests responder req]
  (let [body (json/parse-string (slurp (:body req)) true)
        _ (swap! requests conj {:uri (:uri req) :headers (:headers req) :body body})
        {:keys [status type message headers delay-ms] :as reply} (responder body)
        _ (when delay-ms (try (Thread/sleep (long delay-ms)) (catch InterruptedException _ nil)))]
    (if status
      {:status status
       :headers (merge {"Content-Type" "application/json" "request-id" "req_mock"} headers)
       :body (json/generate-string {:type "error" :error {:type type :message message}})}
      {:status 200
       :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache" "request-id" "req_mock"}
       :body (sse (message-events (:model body) reply))})))

(defn- respond [requests responder req]
  (if (and (= :get (:request-method req)) (.startsWith ^String (:uri req) "/v1/models/"))
    (describe (:uri req))
    (answer requests responder req)))

(defn start!
  "Starts a server on a free port. Returns {:url :requests :stop}."
  [responder]
  (let [requests (atom [])
        server (http/run-server #(respond requests responder %)
                                {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    {:url (str "http://127.0.0.1:" (http/server-port server))
     :requests requests
     :stop #(http/server-stop! server)}))

;; --- helpers for responders

(defn last-tool-results
  "The tool results in the request's last user message, as {:name :text}."
  [body]
  (let [msgs (:messages body)
        uses (into {} (for [m msgs :when (= "assistant" (:role m))
                            c (:content m) :when (and (map? c) (= "tool_use" (:type c)))]
                        [(:id c) (:name c)]))
        last-user (last (filter #(= "user" (:role %)) msgs))]
    (vec (for [c (if (sequential? (:content last-user)) (:content last-user) [])
               :when (= "tool_result" (:type c))]
           {:name (uses (:tool_use_id c))
            :error (boolean (:is_error c))
            :text (let [content (:content c)]
                    (if (string? content)
                      content
                      (apply str (keep :text content))))}))))
