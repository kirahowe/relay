;; Writes hostile text as relay shows it, for scripts/inert-text-check.swift
;; to ask a link finder whether any of it can be followed.
;;
;; Text an outsider may have shaped is inert: relay breaks every link in
;; it (relay.text/defang). defang decides what a link is, and a link
;; finder decides for itself, so the only proof that they agree is to
;; ask the finder. This builds random strings that try to pass for a web
;; address in ways finders accept (schemes written short, hosts hidden in
;; file names and mail addresses, hidden characters, characters with no
;; width of their own, emoji held together by a joiner, other alphabets,
;; and now and then any character there is) and prints each as it is
;; shown four ways:
;;
;;   plain   a mail or a push (relay.messages/plain)
;;   shown   a client or a terminal (relay.messages/shown)
;;   wrap    relay ready's lines, cut at 60 columns (relay.render/wrap)
;;   kept    hidden characters taken out but for the joiner or selector
;;           that holds an emoji together, then links broken. No channel
;;           shows this today: mail takes every hidden character out and
;;           a screen shows each as an escape. It is how conversation
;;           text is to be sent (relay#104), and the only way a kept
;;           joiner reaches a link finder raw.
;;
;; One line per case: the string as written, U+0001, the shown form with
;; line breaks as spaces. scripts/inert-text-fuzz.sh runs this with the
;; checker, and bb verify runs that. By hand, from the repo:
;;
;;   swiftc -O scripts/inert-text-check.swift -o /tmp/inert-text-check
;;   bb scripts/inert-text-fuzz.clj 1 200000 | /tmp/inert-text-check
;;
;; The first argument is the seed, the second how many strings.

(require '[clojure.string :as str]
         '[relay.messages :as messages]
         '[relay.render :as render]
         '[relay.text :as text])

(def rng (java.util.Random. (parse-long (or (first *command-line-args*) "1"))))
(def cases (parse-long (or (second *command-line-args*) "100000")))

(defn pick [v] (v (.nextInt rng (count v))))
(defn maybe [p v] (if (< (.nextDouble rng) p) (pick v) ""))

(def before
  ["" "" "" "see " "(" "\"" "'" "<" "[" "x" "a@" "." ".@" "+@" "-@" "__@" "_" "-" "*" "**" "`" "/" "//" ":" "%"
   "é" "1" "|" "{" "=" "&" "?" "#" "~" "+" "\u3000" "\u00a0" "\u200b" "\u00ad" "·" "a." "a_" "a-" "1." "x:" "@"
   "a.b:1" "v1.2:3" "x.org:80" "a.bc:1" "x.yz:9" "me@a.bc:1"
   ;; A mail domain a link finder takes for none (relay#130's second round).
   "a@-" "a@_" "a@x-." "a@x_." "a@b.-" "a@foo--"])

(def scheme
  ["" "" "" "http://" "https://" "HTTPS://" "https:" "https:/" "http:\\\\" "https:\\" "ftp://" "www." "WWW." "_www."
   "ｈｔｔｐｓ://" "https：//" "https:／／" "hTtP://" "h\u200bttps://" "https\u200b://" "https:/\u200b/" "//"
   "https://user@" "https://a:b@" "feed:" "feed:https://" "view-source:https://" "itms-services://"
   "x-web-search://" "https%3A%2F%2F" "https:%2F%2F" "ssh:" "ssh:x@" "file:" "file:/" "magnet:?xt=" "news:" "git:"
   ;; relay#130: a mark in or just after the scheme, which Apple's link
   ;; finder reads straight through.
   "https:\u0301//" "https:/\u0301/" "htt\u0301ps://" "https:\u1161//" "https:\ufe0f\u20e3//" "ww\u0323w."
   "http:\u0301//" "https\u0301://" "https://\u0301"])

(def marks
  ;; Characters with no width of their own that relay.text/hidden? does not
  ;; take (relay#130): combining marks, an enclosing mark, a conjoining
  ;; jamo, keycap glue, and two that draw as nothing by design, the Brahmi
  ;; number joiner and the Duployan thick letter selector. A reader that
  ;; skips them reads an address straight through one.
  ["\u0301" "\u0323" "\u0489" "\u1161" "\u20e3" "\ufe0f\u20e3"
   (String. (Character/toChars 0x1107F)) (String. (Character/toChars 0x1BC9D))])

(defn- cp [n] (String. (Character/toChars (int n))))

(def glue
  ;; Emoji sequences whose joiner or selector relay.text keeps when it
  ;; takes hidden characters out (emoji-glue?): a family, a selector
  ;; after a pictograph, keycaps. A kept joiner is a format character,
  ;; not a mark, so no rule for marks sees it.
  [(str (cp 0x1F468) "\u200d" (cp 0x1F469)) (str (cp 0x1F468) "\u200d" (cp 0x1F469) "\u200d" (cp 0x1F467))
   (str (cp 0x1F44D) "\ufe0f") (str (cp 0x2764) "\ufe0f" "\u200d" (cp 0x1F525))
   "1\ufe0f\u20e3" "#\ufe0f\u20e3"])

(def others
  ;; Characters a link finder reads otherwise than relay's patterns did
  ;; (relay#130's review): a superscript or another script after an
  ;; ending, a modifier letter or a spacing mark in a mail address, a
  ;; Hangul filler, circled and squared letters, and a few that are only
  ;; letters and quotes from elsewhere.
  ["\u00b2" "\u00b9" "\u00bd" "\u7b2c" "\u306e" "\u30fc" "\u3005" "\uff70" "\u02b9" "\u02bc" "\u0982"
   "\u3164" "\u115f" "\uffa0" "\u24d0" "\u24b6" (cp 0x1F130) (cp 0x1F170) "\u00e9" "\u201d" "\u00ab"])

(defn any-character
  "Any assigned code point outside ASCII that is not a space."
  []
  (loop []
    (let [n (if (< (.nextDouble rng) 0.6) (+ 0x80 (.nextInt rng (- 0xFFFE 0x80))) (+ 0x10000 (.nextInt rng 0x30000)))]
      (if (and (Character/isDefined (int n)) (not (<= 0xD800 n 0xDFFF))
               (not (Character/isWhitespace (int n))) (not (Character/isSpaceChar (int n))))
        (cp n)
        (recur)))))

(defn marked
  "`s` with a mark, an emoji sequence or some other character from
  outside ASCII put in at a random place, about one time in three, and
  a second one now and then."
  [s]
  (let [put (fn [s]
              ;; Never between the two halves of a pair.
              (let [i (.nextInt rng (inc (count s)))
                    i (if (and (< 0 i (count s)) (Character/isLowSurrogate (.charAt s i))) (dec i) i)
                    p (.nextDouble rng)]
                (str (subs s 0 i)
                     (cond (< p 0.25) (pick glue) (< p 0.55) (pick marks) (< p 0.8) (pick others) :else (any-character))
                     (subs s i))))
        p (.nextDouble rng)]
    (cond (< p 0.07) (put (put s))
          (< p 0.33) (put s)
          :else s)))

(def label
  ["evil" "secret" "x" "a1" "a-b" "a_b" "-x" "x-" "_x" "x_" "a--b" "évil" "пример" "例" "xn--e1afmkfd" "1" "123" "0x7f" "com" "co" "www" "data"
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
        s (marked (str (pick before) (pick scheme) host (pick after) (maybe 0.3 after)))
        shown (messages/shown outside s)
        one-line #(str/replace % #"[\n\r]" " ")]
    (doseq [[channel out] [["plain" (messages/plain outside s)]
                           ["shown" shown]
                           ["wrap" (str/join "\n" (render/wrap shown 60))]
                           ["kept" (text/for-operator outside (first (#'text/strip s)))]]]
      ;; wrap keeps its line breaks: a link finder reads each line.
      (println (str channel " " (pr-str s) "\u0001"
                    (if (= "wrap" channel) (str/replace out "\n" "\u0002") (one-line out)))))))
