(ns relay.push
  "The push channel's delivery edge: one rendered push (relay.channels/
  push), {:title :body :click :labels}, sent the way the channel's :via
  says:

    {:kind :push :via :ntfy :url \"http://127.0.0.1:2586\" :topic \"relay\"}
    {:kind :push :via :ntfy :url ... :topic ... :token-command [\"cat\" \"/path/to/token\"]}
    {:kind :push :via :ntfy :url ... :topic ... :token-file \"/path/to/token\"}
    {:kind :push :via :command :command [\"/path/to/script\"]}   ; title as last arg, body on stdin
    {:kind :push :via :macos}                                   ; a notification on this Mac
    {:kind :push :via :file :path \"/path/to/notifications.log\"}  ; one JSON line per push

  :file reaches nobody. It is for staging data, where an agent checks that
  a push was sent without any reaching the operator's phone.

  The text is already what the operator is shown: the renderer broke the
  links of what came from outside and took out every hidden character,
  saying so."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.time Instant)))

(def ntfy-message-limit
  ;; ntfy rejects messages over 4096 bytes.
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

(defn clip
  "`s`, already shown by text/visible, cut to at most `n` UTF-16 units
  with an ellipsis after it when it is longer. The cut falls between
  whole code points, escapes (\\u{...}) and escaped backslashes, so it
  never leaves half a surrogate pair or half an escape."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n)
      s
      (str (reduce (fn [^StringBuilder sb t]
                     (if (> (+ (.length sb) (count t)) n) (reduced sb) (.append sb ^String t)))
                   (StringBuilder.)
                   ;; In Java regex . is one code point, a pair included.
                   (re-seq #"(?s)\\\\|\\u\{[0-9a-f]+\}|." s))
           "…"))))

(defn push!
  "Sends one push. Throws when it could not be sent."
  [{:keys [via url topic command] :as settings} {:keys [title body click labels]}]
  (let [title (str title) body (str body)]
    (case via
      ;; Published as JSON so that a non-ASCII title is not sent in a header.
      :ntfy (let [tok (token settings)
                  resp (http/post url {:headers (cond-> {"Content-Type" "application/json"}
                                                  tok (assoc "Authorization" (str "Bearer " tok)))
                                       :body (json/generate-string
                                              (cond-> {:topic topic :title title
                                                       :message (clip body ntfy-message-limit)}
                                                click (assoc :click click)))
                                       :throw false
                                       :timeout 10000})]
              (when-not (<= 200 (:status resp) 299)
                (throw (ex-info (str "ntfy answered " (:status resp)) {:status (:status resp)}))))
      :command (let [{:keys [exit err]} (apply p/shell {:in body :out :string :err :string
                                                       :continue true}
                                               (conj (vec command) title))]
                 (when-not (zero? exit)
                   (throw (ex-info (str "The push command exited " exit ": " err) {}))))
      :macos (p/shell {:out :string :err :string}
                      "osascript" "-e"
                      (format "display notification %s with title %s"
                              (json/generate-string (clip body 240))
                              (json/generate-string title)))
      ;; Private: the body may be the operator's private text. A directory
      ;; made for it, and the file itself, are owner-only.
      :file (let [file (fs/path (:path settings))]
              (fs/create-dirs (fs/parent file) {:posix-file-permissions "rwx------"})
              (when-not (fs/exists? file)
                (fs/create-file file {:posix-file-permissions "rw-------"}))
              (fs/set-posix-file-permissions file "rw-------")
              (spit (str file)
                    (str (json/generate-string (cond-> {:at (str (Instant/now)) :title title :body body
                                                        :labels (vec labels)}
                                                 click (assoc :click click)))
                         "\n")
                    :append true)))))
