(ns relay.sanitize-gen
  "Generators of pastes for the sanitize tests: hidden characters mixed
  with ordinary text, so every property sees both."
  (:require [clojure.test.check.generators :as gen]))

(def sneaky
  "Code points sanitize must remove or rewrite: invisible, bidi-control,
  tag, control, private-use, separators."
  [0 1 7 27 127 0x85 0xad 0x34f 0x61c 0x180e 0x200b 0x200c 0x200d 0x200e 0x200f 0x202a 0x202e 0x2060
   0x2066 0x2069 0x2028 0x2029 0x2800 0x3164 0xfe00 0xfe0f 0xfeff 0xe000 0xe0001 0xe0041 0xe007f 0xe0100])

(defn cp-str [cp] (String. (Character/toChars (int cp))))

(def gen-paste
  (gen/fmap #(apply str %)
            (gen/vector (gen/one-of [(gen/fmap cp-str (gen/elements sneaky))
                                     gen/string
                                     gen/string-alphanumeric
                                     (gen/elements ["\n" "\t" "\r\n" " " "é" "e\u0301" "a\u0308" "Ω" "р" "日本語" "😀" "👨\u200d👩"])])
                        0 14)))
