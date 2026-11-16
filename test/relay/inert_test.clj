(ns relay.inert-test
  "Conversation text made inert as it streams (relay#104)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.inert :as inert]
            [relay.text :as text]))

(defn- run
  "What a stream sends for chunks, with :cut between two of them where
  another update goes first, and the end of the turn after the last."
  [steps]
  (loop [st inert/empty-stream [s & more] steps out []]
    (cond
      (nil? s) (let [[_ o] (inert/finish st)] (apply str (conj out o)))
      (= :cut s) (let [[st o] (inert/cut st)] (recur st more (conj out o)))
      :else (let [[st o] (inert/feed st s)] (recur st more (conj out o))))))

(defn- chunks
  "s cut at the given points."
  [^String s points]
  (let [ps (sort (distinct (map #(mod % (inc (count s))) points)))]
    (mapv (fn [[a b]] (subs s a b)) (partition 2 1 (concat [0] ps [(count s)])))))

(def gen-text
  (gen/fmap #(apply str %)
            (gen/vector (gen/elements ["https://" "http:/" "www." "evil" ".com" "." "/x?d=1" " " "  " "\n" " "
                                       "todo.md" "12.50" "a@" "ssh:" "​" "́" "x" "é" "👨" "‍" "👩"
                                       "evil.com/steal" ":" "[.]"])
                        0 30)))

(defspec any-split-sends-what-the-whole-text-would 1000
  ;; With no update between chunks, how the agent cut the text changes
  ;; nothing: relay sends what it would for the text whole.
  (prop/for-all [s gen-text points (gen/vector gen/nat 0 8)]
    (= (text/conversation-text s) (run (chunks s points)))))

(defn- followable?
  "Whether text holds what a link finder needs: a web scheme with its
  slashes, www. before a name, or a dot or colon with a name after it."
  [s]
  (boolean (or (re-find #"(?i)(?<![a-z0-9])(?:https?|ftp):/{0,2}[a-z0-9]" s)
               (re-find #"(?i)(?<![a-z0-9])www\.[\p{L}\p{N}]" s)
               (re-find #"[\p{L}\p{N}_\-]\.[\p{L}]" s))))

(defspec a-word-cut-by-another-update-leaves-nothing-to-follow 1000
  ;; Whatever the agent sends and wherever an update cuts in, the text a
  ;; client puts together holds nothing to follow.
  (prop/for-all [s gen-text points (gen/vector gen/nat 0 8) cuts (gen/vector gen/boolean 0 9)]
    (let [cs (chunks s points)
          steps (mapcat (fn [c cut?] (if cut? [c :cut] [c])) cs (concat cuts (repeat false)))]
      (not (followable? (run steps))))))

(deftest a-tool-call-in-the-middle-of-a-word
  (testing "the word is sent cut, and nothing in it or after it can be followed"
    (let [out (run ["see https://ev" :cut "il.com/x now"])]
      (is (not (followable? out)) out)
      (is (str/starts-with? out "see ") out)
      (is (str/ends-with? out " now") out))))

(deftest a-word-longer-than-the-bound-goes-out-in-pieces
  (let [long-word (str "https://evil.com/" (apply str (repeat (+ 100 inert/max-held) "a")) ".com/x")
        [st out] (inert/feed inert/empty-stream long-word)]
    (is (:cut? st))
    (is (not (followable? out)))
    (is (not (followable? (run [long-word " done"]))))))

(deftest a-whole-word-waits-for-its-space
  (is (= ["" "hello "] (map second [(inert/feed inert/empty-stream "hel")
                                    (inert/feed (first (inert/feed inert/empty-stream "hel")) "lo world")])))
  (is (= "see hxxps://evil[.]com/x" (run ["see htt" "ps://evil.com/x"]))))

;; --- relay#104's third review round: Markdown across a cut

(def gen-markdown
  (gen/fmap #(apply str %)
            (gen/vector (gen/elements ["!" "[" "]" "(" ")" "x" ":" "." "//134744072/p" "<img src=//1/p>" " " "a" "]:"
                                       "[x]" "(//1/p?d=S)" "![x]" "[1]: //e/x"])
                        0 14)))

(defn- live-markdown?
  "Whether text holds an unescaped piece of CommonMark that makes a link,
  an image or HTML: ]( or ]: after a bracket no backslash escapes, or <."
  [s]
  (boolean (re-find #"(?<!\\)\]\(|(?<!\\)\]:|(?<!\\)<" s)))

(defspec no-markdown-link-forms-however-the-text-is-cut 2000
  (prop/for-all [s gen-markdown points (gen/vector gen/nat 0 6) cuts (gen/vector gen/boolean 0 7)]
    (let [cs (chunks s points)
          steps (mapcat (fn [c cut?] (if cut? [c :cut] [c])) cs (concat cuts (repeat false)))]
      (not (live-markdown? (run steps))))))
