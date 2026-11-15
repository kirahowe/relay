;; Writes every character there is, one at a time, into each place of an
;; address where it could matter, as relay shows the result, for
;; scripts/inert-text-check.swift to ask a link finder about.
;;
;; The fuzz (inert-text-fuzz.clj) tries random strings. This is its
;; counterpart for single characters: each assigned code point outside
;; ASCII, put into every template below (inside a scheme, around an @,
;; inside a host, after its ending, inside a file name). relay.text breaks
;; every dot a host name could have, whatever stands around it; this is
;; the check that no character makes a finder read a host where relay saw
;; none.
;;
;; One line per case, in the fuzz's form: a label and the string as
;; written, U+0001, the string as shown. A character relay.text/hidden?
;; takes is shown both ways it can be, taken out (mail) and escaped (a
;; screen); any other is shown once, since both ways are then the same.
;; Run from the repo when link breaking changes, about four minutes:
;;
;;   swiftc -O scripts/inert-text-check.swift -o /tmp/inert-text-check
;;   bb scripts/inert-text-sweep.clj | /tmp/inert-text-check
;;
;; An argument limits it to the code points from that number on, and a
;; second to those below it: bb scripts/inert-text-sweep.clj 0x3000 0x3100

(require '[relay.messages :as messages]
         '[relay.text :as text])

(def templates
  "Where the character goes: [before after]."
  [["https:" "//evil.com/x"] ["https:/" "/evil.com/x"] ["htt" "ps://evil.com/x"] ["" "https://evil.com/x"]
   ["https://" "evil.com/x"] ["https://x" "@evil.com"] ["https:evil.com" ""] ["https:" "evil.com"]
   ["ww" "w.evil.com"] ["www" ".evil.com"] ["www." "evil.com"]
   ["a" "@evil.com"] ["a@" "evil.com"] ["a@ev" "il.com"] ["a@evil" ".com"] ["a@evil.com" ""] ["a@evil.co" "m"]
   ["" "evil.com"] ["ev" "il.com/x"] ["evil" ".com"] ["evil." "com"] ["evil.co" "m"] ["evil.com" ""]
   ["evil.com" "1"] ["evil.com" "/x"] ["evil.co" ".uk"] ["x.org:80" "evil.com"] ["10.0.0" ".1"]
   ["to" "do.md"] ["todo" ".md"] ["todo." "md"] ["todo.md" ""] ["notes.zip" "x"] ["(see evil.com" ")"]])

(def outside ["contaminated"])

(defn- parse [s] (when s (Long/decode s)))

(let [lo (or (parse (first *command-line-args*)) 0x80)
      hi (or (parse (second *command-line-args*)) 0x110000)]
  (doseq [cp (range lo hi)
          :when (and (Character/isDefined (int cp))
                     (not (<= 0xD800 cp 0xDFFF))
                     ;; A space of any kind only makes two words.
                     (not (Character/isWhitespace (int cp)))
                     (not (Character/isSpaceChar (int cp))))
          :let [c (String. (Character/toChars (int cp)))]
          [before after] templates
          :let [s (str before c after)
                label (format "U+%04X %s|%s" cp before after)]]
    (println (str label "\u0001" (messages/plain outside s)))
    (when (text/hidden? cp)
      (println (str label " (escaped)" "\u0001" (messages/shown outside s))))))
