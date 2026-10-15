(ns relay.notify
  "Pushing a notification to the operator. Configured in relay.edn as :notify:

    {:kind :ntfy :url \"http://127.0.0.1:2586\" :topic \"relay\"}
    {:kind :command :command [\"/path/to/script\"]}   ; title as last arg, body on stdin
    {:kind :macos}                                   ; a notification on this Mac
    {:kind :none}                                    ; record only

  relay.edn must say which; see relay.config."
  (:require [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]))

(def ntfy-message-limit
  ;; ntfy rejects messages over 4096 bytes; the whole text is in the log.
  3000)

(defn- clip [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn push!
  "Sends one notification ({:title :body}). Throws when it could not be sent."
  [{:keys [kind url topic command]} {:keys [title body]}]
  (case kind
    ;; Published as JSON so that a non-ASCII title is not sent in a header.
    :ntfy (let [resp (http/post url {:headers {"Content-Type" "application/json"}
                                     :body (json/generate-string
                                            {:topic topic :title title
                                             :message (clip body ntfy-message-limit)})
                                     :throw false
                                     :timeout 10000})]
            (when-not (<= 200 (:status resp) 299)
              (throw (ex-info (str "ntfy answered " (:status resp)) {:status (:status resp)}))))
    :command (let [{:keys [exit err]} (apply p/shell {:in (str body) :out :string :err :string
                                                     :continue true}
                                             (conj (vec command) (str title)))]
               (when-not (zero? exit)
                 (throw (ex-info (str "The notify command exited " exit ": " err) {}))))
    :macos (p/shell {:out :string :err :string}
                    "osascript" "-e"
                    (format "display notification %s with title %s"
                            (json/generate-string (clip body 240))
                            (json/generate-string (str title))))
    :none nil))
