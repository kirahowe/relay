;; Writes hostile text as relay shows it, for scripts/inert-text-check.swift
;; to ask a link finder whether any of it can be followed.
;;
;; Text an outsider may have shaped is inert: relay breaks every link in
;; it (relay.text/defang). defang decides what a link is, and a link
;; finder decides for itself, so the only proof that they agree is to
;; ask the finder. This builds random strings that try to pass for a web
;; address in ways finders accept (schemes written short, hosts hidden in
;; file names and mail addresses, hidden characters, other alphabets) and
;; prints each as three channels show it:
;;
;;   plain   a mail or a push (relay.messages/plain)
;;   shown   a client or a terminal (relay.messages/shown)
;;   wrap    relay ready's lines, cut at 60 columns (relay.render/wrap)
;;
;; One line per case: the string as written, U+0001, the shown form with
;; line breaks as spaces. Run from the repo:
;;
;;   swiftc -O scripts/inert-text-check.swift -o /tmp/inert-text-check
;;   bb scripts/inert-text-fuzz.clj 1 200000 | /tmp/inert-text-check
;;
;; The first argument is the seed, the second how many cases.

(require '[clojure.string :as str]
         '[relay.messages :as messages]
         '[relay.render :as render])

(def rng (java.util.Random. (parse-long (or (first *command-line-args*) "1"))))
(def cases (parse-long (or (second *command-line-args*) "100000")))

(defn pick [v] (v (.nextInt rng (count v))))
(defn maybe [p v] (if (< (.nextDouble rng) p) (pick v) ""))

(def before
  ["" "" "" "see " "(" "\"" "'" "<" "[" "x" "a@" "." ".@" "+@" "-@" "__@" "_" "-" "*" "**" "`" "/" "//" ":" "%"
   "é" "1" "|" "{" "=" "&" "?" "#" "~" "+" "\u3000" "\u00a0" "\u200b" "\u00ad" "·" "a." "a_" "a-" "1." "x:" "@"
   "a.b:1" "v1.2:3" "x.org:80"])

(def scheme
  ["" "" "" "http://" "https://" "HTTPS://" "https:" "https:/" "http:\\\\" "https:\\" "ftp://" "www." "WWW." "_www."
   "ｈｔｔｐｓ://" "https：//" "https:／／" "hTtP://" "h\u200bttps://" "https\u200b://" "https:/\u200b/" "//"
   "https://user@" "https://a:b@" "feed:" "feed:https://" "view-source:https://" "itms-services://"
   "x-web-search://" "https%3A%2F%2F" "https:%2F%2F"])

(def label
  ["evil" "secret" "x" "a1" "a-b" "a_b" "évil" "пример" "例" "xn--e1afmkfd" "1" "123" "0x7f" "com" "co" "www" "data"
   "s3" "Quarterly-board-pack-for-the-trustees-meeting-final-v02"])

(def dot
  ["." "." "." "." "。" "．" "｡" "\u200b." ".\u200b" "\u00ad." "[.]" "%2E" "\u2024" "\ufe52" "\u0701" "\u30fb"])

(def ending
  ["com" "co" "io" "in" "zip" "mov" "md" "sh" "txt" "json" "pdf" "рф" "xn--p1ai" "c0m" "com-x" "com_x" "museum" "app"
   "dev" "ai" "py" "rs" "pl" "so" "do" "1" "md5" "tk" "ly" "me" "to" "ini" "docx" "cc" "ms" "is" "it" "at" "be" "us"
   "es" "de"])

(def after
  ["" "" "" "/" "/a" "/a/b?c=d" "?q=1" "#f" ":80" ":80/x" ":1secret.attacker.com/x" "." "," ")" "!" "?" "'" "\"" ">"
   "]" "/." "\u200b/x" "\u00ad" "-report.txt" ".txt" "_x.json" "@x" "\\x" "%2Fx" "…" ":" ";" "*" "**" "~" "/)" "）"
   "】"])

(def outside ["contaminated"])

(dotimes [_ cases]
  (let [n (inc (.nextInt rng 3))
        host (apply str (concat (interleave (repeatedly n #(pick label)) (repeatedly n #(pick dot))) [(pick ending)]))
        s (str (pick before) (pick scheme) host (pick after) (maybe 0.3 after))
        shown (messages/shown outside s)
        one-line #(str/replace % #"[\n\r]" " ")]
    (doseq [[channel out] [["plain" (messages/plain outside s)]
                           ["shown" shown]
                           ["wrap" (str/join "\n" (render/wrap shown 60))]]]
      ;; wrap keeps its line breaks: a link finder reads each line.
      (println (str channel " " (pr-str s) "\u0001"
                    (if (= "wrap" channel) (str/replace out "\n" "\u0002") (one-line out)))))))
