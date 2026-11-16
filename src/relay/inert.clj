(ns relay.inert
  "Conversation text made inert as it streams (relay#104). Pure.

  An agent sends a reply in chunks that may cut a word anywhere, and
  links are broken a word at a time (relay.text/defang), so a word must
  be whole before it is judged. A stream holds back the text after the
  last space it has seen and sends only what stands before it. What it
  holds goes out when a space comes, at the end of the turn, or early,
  when another update of the conversation has to go first or the word
  grows past `max-held`. A word sent early is a cut word: every dot and
  colon in it is broken, so neither piece, nor the two joined, holds
  anything that can be followed.

  A stream is {:held string :cut? bool}. :cut? says the held text goes
  on from a piece already sent, so it is cut too."
  (:require [clojure.string :as str]
            [relay.text :as text]))

(def max-held
  "The most characters a stream holds back. A longer word goes out in
  pieces, each broken as a cut word."
  4096)

(def empty-stream {:held "" :cut? false})

(defn- break-cut
  "A piece of a cut word: made safe, then every dot and colon broken, so
  no scheme or host survives in it or across the cut, and a closing
  bracket escaped, so no Markdown link forms across it either."
  [piece]
  (-> (text/conversation-text piece)
      (str/replace #"(?<!\[)\.(?!\])" "[.]")
      (str/replace #"(?<!\[):(?!\])" "[:]")
      (str/replace #"(?<!\[[.:])\](?!\()" "\\]")))

(defn- last-space
  "The index just after the last space in s, or nil."
  [^String s]
  (let [m (re-matcher #"[\s\p{Z}]" s)]
    (loop [end nil]
      (if (.find m) (recur (.end m)) end))))

(defn- first-space [^String s]
  (let [m (re-matcher #"[\s\p{Z}]" s)]
    (when (.find m) (.start m))))

(defn- send-whole
  "Text that ends at a space, as sent. With `cut?` its first word goes on
  from a cut piece, so that word is broken as one."
  [^String s cut?]
  (if-not cut?
    (text/conversation-text s)
    (let [i (or (first-space s) (count s))]
      (str (break-cut (subs s 0 i)) (text/conversation-text (subs s i))))))

(defn feed
  "The stream after `text` arrives, and the text to send now: [stream out]."
  [{:keys [held cut?]} text]
  (let [s (str held text)]
    (if-let [end (last-space s)]
      (let [out (send-whole (subs s 0 end) cut?)
            tail (subs s end)]
        (if (> (count tail) max-held)
          [(assoc empty-stream :cut? true) (str out (break-cut tail))]
          [(assoc empty-stream :held tail) out]))
      (if (> (count s) max-held)
        [(assoc empty-stream :cut? true) (break-cut s)]
        [{:held s :cut? cut?} ""]))))

(defn cut
  "The stream when another update must go out first, and the held text
  to send before it: a cut word, since more of it may follow."
  [{:keys [held cut?]}]
  (if (= "" held)
    [{:held "" :cut? cut?} ""]
    [(assoc empty-stream :cut? true) (break-cut held)]))

(defn finish
  "The stream at the end of a turn, and the held text to send: the word
  is whole, unless it goes on from a cut piece."
  [{:keys [held cut?]}]
  [empty-stream (cond (= "" held) ""
                      cut? (break-cut held)
                      :else (text/conversation-text held))])
