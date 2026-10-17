(ns relay.notify
  "Pushing a notification to the operator. Configured in relay.edn as :notify:

    {:kind :ntfy :url \"http://127.0.0.1:2586\" :topic \"relay\"}
    {:kind :ntfy :url ... :topic ... :token-command [\"cat\" \"/path/to/token\"]}
    {:kind :ntfy :url ... :topic ... :token-file \"/path/to/token\"}
    {:kind :command :command [\"/path/to/script\"]}   ; title as last arg, body on stdin
    {:kind :macos}                                   ; a notification on this Mac
    {:kind :file :path \"/path/to/notifications.log\"}  ; one JSON line per notification
    {:kind :none}                                    ; record only

  :file reaches nobody. It is for staging data, where an agent checks that a
  notification was sent without any being pushed to the operator's phone.

  relay.edn must say which; see relay.config."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [relay.text :as text])
  (:import (java.time Instant)))

(def ntfy-message-limit
  ;; ntfy rejects messages over 4096 bytes; the whole text is in the log.
  3000)

(defn token
  "The ntfy access token: what :token-command prints, or the contents of
  :token-file, trimmed. Nil when neither is set. Read at each push, so
  relay holds no token between them."
  [{:keys [token-command token-file]}]
  (let [t (cond
            (seq token-command)
            (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true :in ""}
                                                token-command)]
              (when-not (zero? exit)
                (throw (ex-info (str "The ntfy :token-command exited " exit ": " err) {})))
              out)
            token-file (slurp token-file))]
    (when t
      (let [t (str/trim t)]
        (when (str/blank? t) (throw (ex-info "The ntfy token is empty." {})))
        t))))

(defn- clip [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn push!
  "Sends one notification ({:title :body}). Throws when it could not be sent."
  [{:keys [kind url topic command] :as settings} {:keys [labels] :as notification}]
  ;; The operator may tap what arrives, so text from a contaminated source
  ;; is marked and its URLs broken, and nothing in it can hide itself.
  ;; A push is one title and one body, so a line break in the title is
  ;; escaped; the body may run to several lines.
  (let [title (text/visible-line (text/for-operator labels (:title notification)))
        body (text/visible (text/for-operator labels (:body notification)))]
  (case kind
    ;; Published as JSON so that a non-ASCII title is not sent in a header.
    :ntfy (let [tok (token settings)
                resp (http/post url {:headers (cond-> {"Content-Type" "application/json"}
                                                tok (assoc "Authorization" (str "Bearer " tok)))
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
    ;; Private: the body may be the operator's private text. A directory
    ;; made for it, and the file itself, are owner-only.
    :file (let [file (fs/path (:path settings))]
            (fs/create-dirs (fs/parent file) {:posix-file-permissions "rwx------"})
            (when-not (fs/exists? file)
              (fs/create-file file {:posix-file-permissions "rw-------"}))
            (fs/set-posix-file-permissions file "rw-------")
            (spit (str file)
                  (str (json/generate-string {:at (str (Instant/now)) :title (str title) :body (str body)
                                              :labels (vec labels)})
                       "\n")
                  :append true))
    :none nil)))
