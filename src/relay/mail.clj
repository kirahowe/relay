(ns relay.mail
  "The email channel's delivery edge: submits one rendered email
  (relay.channels/email) with curl over SMTP, from the agent's own
  address to the operator's, both from relay.edn:

    {:kind :email
     :url \"smtps://smtp.example.org:465\"      ; smtp:// only to this machine
     :from \"agent@example.org\"                ; the agent's own address
     :to \"you@example.org\"                    ; yours, the only recipient
     :user \"agent@example.org\"                ; the login, if the server wants one
     :credentials-command [\"security\" \"find-generic-password\" \"-s\" \"relay-mail\" \"-w\"]}

  The password is what :credentials-command prints, read at each send and
  passed to curl on stdin, so it is on no command line and in no file.
  The message itself is written to an owner-only temporary file for curl
  to upload, and removed after."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [relay.channels :as channels]))

(defn password
  "What the channel's :credentials-command prints, without its trailing
  line break. Throws when it fails or prints nothing."
  [{:keys [credentials-command]}]
  (let [{:keys [exit out]} (apply p/shell {:out :string :err :string :continue true :in ""} credentials-command)]
    (when-not (zero? exit)
      (throw (ex-info (str "The mail :credentials-command exited " exit ".") {})))
    (let [pw (str/trim-newline out)]
      (when (str/blank? pw) (throw (ex-info "The mail :credentials-command printed nothing." {})))
      pw)))

(defn- first-line [s n]
  (let [l (str/trim (first (str/split-lines (str s))))]
    (if (> (count l) n) (subs l 0 n) l)))

(defn send!
  "Submits one rendered email. Throws when it was not accepted."
  [{:keys [user credentials-command] :as channel} {:keys [text]}]
  (let [dir (fs/create-temp-dir {:prefix "relay-mail" :posix-file-permissions "rwx------"})
        file (fs/path dir "message.eml")]
    (try
      (fs/create-file file {:posix-file-permissions "rw-------"})
      (spit (str file) text)
      (let [login (when user
                    (or (channels/curl-login user (if credentials-command (password channel) ""))
                        (throw (ex-info "The mail login holds a line break, which curl cannot take." {}))))
            {:keys [exit err]} (apply p/shell {:in (or login "") :out :string :err :string :continue true}
                                      (channels/curl-argv channel file))]
        (when-not (zero? exit)
          (throw (ex-info (str "The mail server did not take it (curl " exit "): " (first-line err 200)) {:exit exit}))))
      (finally (fs/delete-tree dir)))))
