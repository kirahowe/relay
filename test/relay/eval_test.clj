(ns relay.eval-test
  "The eval grading core, the new scenario matchers and kinds, the
  stand-in tools, and every eval case's oracle and foils against the mock
  model."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [relay.config :as config]
            [relay.eval :as eval]
            [relay.eval-trial :as et]
            [relay.render :as render]
            [relay.reports :as reports]
            [relay.scenario :as scenario]
            [relay.scenario-runner :as runner]
            [relay.staging :as staging]
            [relay.staging-tools :as staging-tools]
            [relay.transcript :as transcript])
  (:import (java.time Instant)))

(defn- holds? [r] (:pass? r))

;; --- matchers

(def scalar (gen/one-of [gen/small-integer gen/string-alphanumeric gen/keyword gen/boolean (gen/return nil)]))
(def simple-pattern
  (gen/one-of [scalar
               (gen/fmap (fn [s] [:includes s]) gen/string-alphanumeric)
               (gen/fmap (fn [n] [:min-length n]) (gen/choose 0 5))
               (gen/fmap (fn [x] [:contains x]) scalar)]))
(def pattern (gen/recursive-gen (fn [inner] (gen/one-of [(gen/fmap (fn [p] [:not p]) inner)
                                                         (gen/fmap #(into [:any] %) (gen/vector inner 1 3))
                                                         (gen/fmap #(into [:all] %) (gen/vector inner 1 3))]))
                                simple-pattern))
(def value (gen/one-of [scalar (gen/vector scalar 0 3)]))

(deftest not-inverts-any-pattern
  (is (holds? (tc/quick-check 500 (prop/for-all [p pattern v value]
                                    (= (scenario/match? [:not p] v) (not (scenario/match? p v))))))))

(deftest any-and-all-are-or-and-and
  (is (holds? (tc/quick-check 300 (prop/for-all [ps (gen/vector pattern 1 4) v value]
                                    (and (= (scenario/match? (into [:any] ps) v) (boolean (some #(scenario/match? % v) ps)))
                                         (= (scenario/match? (into [:all] ps) v) (every? #(scenario/match? % v) ps))))))))

(deftest a-collection-contains-each-of-its-elements
  (is (holds? (tc/quick-check 300 (prop/for-all [xs (gen/vector scalar 1 5)]
                                    (and (every? #(scenario/match? [:contains %] xs) xs)
                                         (scenario/match? [:subset xs] xs)
                                         (scenario/match? [:same-set (reverse xs)] xs)
                                         (scenario/match? [:subset (conj xs ::more)] xs)
                                         (not (scenario/match? [:same-set (conj xs ::more)] xs))))))))

(deftest numbers-between-their-bounds
  (is (holds? (tc/quick-check 300 (prop/for-all [a gen/small-integer b gen/small-integer x gen/small-integer]
                                    (let [lo (min a b) hi (max a b)]
                                      (= (scenario/match? [:between lo hi] x) (<= lo x hi))))))))

(deftest dates-and-lengths
  (let [halifax-midnight (Instant/parse "2026-11-15T04:00:00Z") ; AST, UTC-4, after DST ends
        late-on-the-14th (Instant/parse "2026-11-15T03:59:00Z")]
    (testing "on-date reads the instant in the staging time zone"
      (is (scenario/match? [:on-date "2026-11-15"] halifax-midnight))
      (is (scenario/match? [:on-date "2026-11-14"] late-on-the-14th))
      (is (not (scenario/match? [:on-date "2026-11-15"] late-on-the-14th)))
      (is (scenario/match? [:on-date "2026-11-14" "2026-11-15"] (str halifax-midnight)) "a string instant too")
      (is (not (scenario/match? [:on-date "2026-11-15"] nil))))
    (testing "between with date bounds compares local dates, inclusively"
      (is (scenario/match? [:between "2026-11-01" "2026-11-14"] late-on-the-14th))
      (is (not (scenario/match? [:between "2026-11-01" "2026-11-14"] halifax-midnight)))
      (is (scenario/match? [:between "2026-11-15T00:00:00Z" "2026-11-15T04:00:00Z"] halifax-midnight)))
    (testing "min-length trims"
      (is (scenario/match? [:min-length 3] " abc "))
      (is (not (scenario/match? [:min-length 4] " abc ")))
      (is (not (scenario/match? [:min-length 0] nil))))))

(deftest a-memo-counts-as-woken-for-only-when-a-wake-carried-it
  ;; Memo 2 reached the hub after its wake started (a dead letter from a
  ;; role that woke later in the same tick), so the hub was never given it
  ;; and :every-memo-resolved must not ask it to be closed.
  (let [world {:state {:memos {1 {:id 1 :to "hub" :status :read}
                               2 {:id 2 :to "hub" :status :unread}}
                       :wakes {3 {:id 3 :role "hub" :memos [1]}}}
               :transcripts {}}]
    (is (= [true false] (map :recipient-woke (scenario/items world :memo))))))

(deftest a-step-script-goes-only-on-a-plain-tick
  (let [base {:steps [{:tick 1}] :assert [[:has :wake {}]]}
        with (fn [step] (scenario/problems (assoc base :steps [step])))]
    (is (empty? (with {:tick 1 :script {"clerk" [{:text "Done."}]}})))
    (doseq [bad [{:tick 1 :script {}} {:tick 1 :script {"clerk" {:text "x"}}} {:tick 1 :script {:clerk []}}
                 {:tick 1 :spawn :process :script {"clerk" []}} {:advance-minutes 1 :script {"clerk" []}}]]
      (is (seq (with bad)) (pr-str bad)))))

;; --- checks and their combinators

(def memo-world {:state {:memos {1 {:id 1 :to "hub" :status :read}}}})
(def yes [:has :memo {:to "hub"}])
(def no [:none :memo {:to "hub"}])

(def check-tree
  (gen/recursive-gen (fn [inner] (gen/one-of [(gen/fmap (fn [c] [:not c]) inner)
                                              (gen/fmap #(into [:and] %) (gen/vector inner 1 3))
                                              (gen/fmap #(into [:or] %) (gen/vector inner 1 3))
                                              (gen/fmap (fn [[a b]] [:implies a b]) (gen/tuple inner inner))]))
                     (gen/elements [yes no])))

(defn- truth [[op & args :as c]]
  (cond (= c yes) true
        (= c no) false
        :else (case op
                :not (not (truth (first args)))
                :and (every? truth args)
                :or (boolean (some truth args))
                :implies (or (not (truth (first args))) (truth (second args))))))

(deftest combinators-are-logic
  (is (holds? (tc/quick-check 500 (prop/for-all [c check-tree]
                                    (and (empty? (eval/check-problems c))
                                         (= (truth c) (= :pass (:value (eval/check memo-world c))))))))))

(deftest every-over-nothing-holds
  (is (holds? (tc/quick-check 200 (prop/for-all [q (gen/map gen/keyword scalar {:max-elements 3})]
                                    (= :pass (:value (eval/check memo-world [:every :memo {:to "nobody"} q]))))))))

(deftest every-and-before
  (let [w {:state {:memos {1 {:id 1 :to "hub" :subject "A" :status :read}
                           2 {:id 2 :to "hub" :subject "B" :status :unread}}}}]
    (is (= :pass (:value (eval/check w [:every :memo {:subject "A"} {:status :read}]))))
    (let [r (eval/check w [:every :memo {:to "hub"} {:status :read}])]
      (is (= :fail (:value r)))
      (is (str/includes? (:explanation r) "\"B\"") "names the item that broke it"))
    (is (= :pass (:value (eval/check w [:before :memo {:subject "A"} {:subject "B"}]))))
    (is (= :fail (:value (eval/check w [:before :memo {:subject "B"} {:subject "A"}]))))
    (is (= :fail (:value (eval/check w [:before :memo {:subject "C"} {:subject "A"}]))) "nothing to come first")))

(deftest malformed-checks-are-described
  (is (seq (eval/check-problems [:has :memo])))
  (is (seq (eval/check-problems [:has :nothing {}])))
  (is (seq (eval/check-problems [:and])))
  (is (seq (eval/check-problems [:implies yes])))
  (is (seq (eval/check-problems [:or yes [:sometimes]])))
  (is (empty? (eval/check-problems [:every :fact {} {:current true}]))))

;; --- the fact, tool-call and wake kinds

(def ^:private at (Instant/parse "2026-10-12T12:00:00Z"))

(defn- version [m] (merge {:recorded-at at :labels [] :valid-from nil :valid-to nil} m))

(def fact-world
  {:now at
   :state {:facts {7 {:id 7 :supersedes nil
                      :versions [(version {:change :added :text "Civic" :topic "car"})
                                 (version {:change :superseded :text "Civic" :topic "car"
                                           :valid-to (Instant/parse "2026-10-06T03:00:00Z") :superseded-by 10})]}
                   10 {:id 10 :supersedes 7 :versions [(version {:change :added :text "Corolla" :topic "car"})]}
                   11 {:id 11 :supersedes nil
                       :versions [(version {:change :added :text "Patel" :topic "dentist"})
                                  (version {:change :corrected :text "Okafor" :topic "dentist" :why "Always Okafor."})]}
                   12 {:id 12 :supersedes nil
                       :versions [(version {:change :added :text "Old office" :topic "office"
                                            :valid-to (Instant/parse "2026-10-01T00:00:00Z")})]}}}})

(deftest fact-items-say-what-happened-to-each-fact
  (let [by-id (into {} (map (juxt :id identity) (scenario/items fact-world :fact)))]
    (is (= [:added :superseded] (:changes (by-id 7))))
    (is (false? (:current (by-id 7))) "a superseded fact is not current")
    (is (true? (:current (by-id 10))))
    (is (= "Civic" (:supersedes-text (by-id 10))))
    (is (= "Patel" (:first-text (by-id 11))))
    (is (= "Okafor" (:text (by-id 11))))
    (is (= "Always Okafor." (:why (by-id 11))))
    (is (false? (:current (by-id 12))) "one that ended before the clock is not current")
    (is (= 11 (scenario/fact-id (:state fact-world) {:first-text "Patel"})))))

(def entries
  [{:type "message" :message {:message {:role "user" :parts []}}}
   {:type "message" :message {:message {:role "assistant"
                                        :parts [{:type "tool_call" :tool_call {:id "a" :name "memo_close" :input {:memo 3}}}
                                                {:type "tool_call" :tool_call {:id "b" :name "remind-demo" :input {}}}]}
                              :usage {:input_tokens 1000 :output_tokens 200 :cache_read_tokens 5000 :cache_write_tokens 100}
                              :model "claude-haiku-5-5"}}
   {:type "decision" :decision {:call_id "a" :tool "memo_close" :verdict "allow"}}
   {:type "decision" :decision {:call_id "b" :tool "remind-demo" :verdict "hold" :reason "contaminated"}}
   {:type "tool_result" :tool_result {:result {:call_id "a" :content "Closed memo 3."}}}
   {:type "message" :message {:message {:role "assistant"
                                        :parts [{:type "tool_call" :tool_call {:id "c" :name "journal_write" :input {:text "x"}}}]}
                              :usage {:input_tokens 10 :output_tokens 20} :model "claude-haiku-5-5"}}
   {:type "decision" :decision {:call_id "c" :tool "journal_write" :verdict "allow"}}
   {:type "tool_result" :tool_result {:result {:call_id "c" :content "Written."}}}
   {:type "run_ended" :run_ended {:reason "held"}}])

(deftest a-transcript-gives-calls-usage-and-cost
  (let [calls (transcript/tool-calls entries)
        u (transcript/run-usage entries)]
    (is (= ["memo_close" "remind-demo" "journal_write"] (map :name calls)))
    (is (= "hold" (:verdict (second calls))))
    (is (nil? (:result (second calls))) "a held call has no result")
    (is (= 2 (:requests u)))
    (is (= {:input 1010 :output 220 :cache-read 5000 :cache-write 100} (:usage u)))
    ;; 1010 x 0.1 + 220 x 0.5 + 5000 x 0.01 + 100 x 0.125, per million
    (is (< (Math/abs (- (:cost-usd u) 2.735e-4)) 1e-12))
    (is (= "held" (transcript/end-reason entries)))
    (is (true? (scenario/journal-at-end? calls)))
    (is (nil? (transcript/cost "some-unpriced-model" (:usage u))) "an unknown price is unknown, not zero")
    (is (= (transcript/cost "claude-haiku-5-5" (:usage u)) (transcript/cost "claude-haiku-5-5-20261001" (:usage u)))
        "a dated id is priced as its base model")))

(deftest journal-at-end-needs-the-entry-after-the-close
  (let [c (fn [n & {:as m}] (merge {:name n :verdict "allow" :error false} m))]
    (is (scenario/journal-at-end? [(c "memo_close") (c "journal_write")]))
    (is (scenario/journal-at-end? [(c "journal_write")]))
    (is (not (scenario/journal-at-end? [(c "journal_write") (c "memo_block")])))
    (is (not (scenario/journal-at-end? [(c "memo_close") (c "journal_write" :error true)])))
    (is (not (scenario/journal-at-end? [(c "memo_close") (c "journal_write" :verdict "hold")])))
    (is (not (scenario/journal-at-end? [])))))

;; --- metrics

(def trials (gen/bind (gen/choose 1 12) (fn [n] (gen/tuple (gen/return n) (gen/choose 0 n)))))

(deftest pass-hat-k-behaves
  (is (holds? (tc/quick-check
               500
               (prop/for-all [[n c] trials]
                 (let [ks (range 1 (inc n))
                       hats (map #(eval/pass-hat-k n c %) ks)]
                   (and (every? #(<= 0.0 % 1.0) hats)
                        (< (Math/abs (- (first hats) (/ (double c) n))) 1e-9)
                        (every? (fn [[a b]] (<= b (+ a 1e-12))) (partition 2 1 hats))
                        (if (= c n) (every? #(< (Math/abs (- 1.0 %)) 1e-9) hats) true)
                        (nil? (eval/pass-hat-k n c (inc n)))
                        ;; pass@k is the other direction: never below pass^k, non-decreasing.
                        (every? (fn [k] (>= (+ (eval/pass-at-k n c k) 1e-12) (eval/pass-hat-k n c k))) ks))))))))

(deftest wilson-holds-the-rate
  (is (holds? (tc/quick-check 500 (prop/for-all [[n c] trials]
                                    (let [[lo hi] (eval/wilson n c) p (/ (double c) n)]
                                      (and (<= -1e-9 lo) (<= lo (+ p 1e-9)) (<= (- p 1e-9) hi) (<= hi (+ 1.0 1e-9)) (< lo hi)))))))
  (is (nil? (eval/wilson 0 0))))

(deftest bootstrap-is-repeatable-and-inside-the-range
  (let [xs [0.2 0.8 1.0 1.0 0.6]
        [lo hi] (eval/bootstrap xs)]
    (is (= [lo hi] (eval/bootstrap xs)))
    (is (<= 0.2 lo hi 1.0))))

;; --- cases, foils and records

(def defaults (et/read-defaults))

(deftest a-malformed-case-is-described
  (let [ok (et/read-case "test/scenarios/fact-validity-window.edn" defaults)
        problems #(eval/case-problems % defaults)]
    (is (empty? (problems ok)))
    (is (some #(str/includes? % ":tier") (problems (assoc-in ok [:eval :tier] :sometimes))))
    (is (some #(str/includes? % "used 2 times") (problems (update ok :graders conj (first (:graders ok))))))
    (is (some #(str/includes? % "judge") (problems (update ok :graders conj {:id :j :judge {:rubric :x}}))))
    (is (some #(str/includes? % "not graders") (problems (assoc-in ok [:foils "no-end-date" :fails] #{:nope}))))
    (is (some #(str/includes? % "skip-defaults") (problems (assoc ok :skip-defaults #{:nope}))))
    (is (empty? (problems (assoc ok :skip-defaults #{:no-held-calls}))))
    (is (= (dec (count (eval/graders ok defaults)))
           (count (eval/graders (assoc ok :skip-defaults #{:no-held-calls}) defaults))))))

(deftest settings-may-only-grant-stand-in-tools
  (is (empty? (scenario/settings-problems {:grants {"clerk" {:tools ["remind-demo" "fail-demo"]}}})))
  (is (seq (scenario/settings-problems {:tools {"x" {}}})))
  (is (seq (scenario/settings-problems {:grants {"clerk" {:tools ["rem"]}}})) "only staging's own tools")
  (is (seq (scenario/settings-problems {:grants {"hub" {:may-add true}}})) "only :tools")
  (is (= ["fail-demo"] (get-in (scenario/merge-settings (staging/settings {:data-dir "/s"})
                                                        {:grants {"clerk" {:tools ["fail-demo"]}}})
                               [:grants "clerk" :tools])))
  (is (true? (get-in (scenario/merge-settings (staging/settings {:data-dir "/s"})
                                              {:grants {"hub" {:tools ["mail-demo"]}}})
                     [:grants "hub" :may-add]))
      "a grant's other keys stay"))

(deftest settings-may-grant-reports-of-well-named-kinds
  (is (empty? (scenario/settings-problems {:grants {"scout" {:reports {:kinds ["grant" "job"]}}}})))
  (is (empty? (scenario/settings-problems {:grants {"scout" {:tools ["web-demo"] :reports {:kinds ["grant"]}}}})))
  (testing "an unknown grant key is still refused, alone or beside a known one"
    (doseq [grant [{:public true} {:reports {:kinds ["grant"]} :public true} {:tools ["web-demo"] :may-add true} {}]]
      (is (seq (scenario/settings-problems {:grants {"scout" grant}})) (pr-str grant))))
  (testing "a bad :reports or :kinds is refused"
    (doseq [reports [{:kinds []} {:kinds ["Grant"]} {:kinds ["grant" "grant"]} {:kinds "grant"}
                     {:kinds [:grant]} {:kinds ["a b"]} {:kinds ["grant"] :more true} {} ["grant"]]]
      (is (seq (scenario/settings-problems {:grants {"scout" {:reports reports}}})) (pr-str reports))))
  (let [merged (scenario/merge-settings (staging/settings {:data-dir "/s"})
                                        {:grants {"scout" {:reports {:kinds ["grant"]}}}})]
    (is (= {:public true :reports {:kinds ["grant"]}} (get-in merged [:grants "scout"])))
    (is (config/resolve-config merged) "relay accepts what it makes")))

(deftest settings-may-grant-the-reader-over-stand-in-tools
  (is (empty? (scenario/settings-problems {:grants {"hub" {:tools ["web-demo"] :quarantine ["web-demo"]}}})))
  (is (seq (scenario/settings-problems {:grants {"hub" {:tools ["web-demo"] :quarantine ["rem"]}}}))
      "only staging's own tools")
  (is (seq (scenario/settings-problems {:grants {"hub" {:quarantine "web-demo"}}})))
  (is (seq (scenario/settings-problems {:grants {"hub" {:quarantine ["web-demo"] :public true}}}))
      "an unknown key beside it is still refused")
  (let [merged (scenario/merge-settings (staging/settings {:data-dir "/s"})
                                        {:grants {"hub" {:tools ["web-demo"] :quarantine ["web-demo"]}}})]
    (is (= {:may-add true :tools ["web-demo"] :quarantine ["web-demo"]} (get-in merged [:grants "hub"])))
    (is (config/resolve-config merged) "relay accepts what it makes"))
  (testing "a tool the role lacks in :tools is reader-only, and relay accepts it"
    (is (config/resolve-config (scenario/merge-settings (staging/settings {:data-dir "/s"})
                                                        {:grants {"hub" {:quarantine ["web-demo"]}}}))))
  (testing "relay itself refuses the reader over a tool that changes things"
    (doseq [grant [{:quarantine ["fail-demo"]} {:tools ["fail-demo"] :quarantine ["fail-demo"]}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (config/resolve-config (scenario/merge-settings (staging/settings {:data-dir "/s"})
                                                                   {:grants {"hub" grant}})))
          (pr-str grant)))))

(deftest a-foil-must-fail-exactly-what-it-says
  (let [scores {:a {:value :pass} :b {:value :fail :explanation "b broke"} :c {:value :fail :explanation "c broke"}}]
    (is (empty? (eval/foil-problems scores #{:b :c})))
    (is (= ["also fails :c: c broke"] (eval/foil-problems scores #{:b})))
    (is (= ["passes :a, which it should fail: "] (eval/foil-problems scores #{:a :b :c})))))

(deftest a-trial-error-is-classed
  (let [world {:state {:wakes {5 {:id 5 :role "hub" :outcome "limited" :said "rate limited"}}}
               :transcripts {}}]
    (is (= [:step :precondition :wake-outcome]
           (map :class (eval/trial-errors {:failures ["step broke" "pre failed"] :assert-failures ["pre failed"] :world world} nil)))))
  (let [world {:state {:wakes {5 {:id 5 :role "hub" :outcome "ran"}}}
               :transcripts {5 entries}}]
    (is (= [:model-mismatch] (map :class (eval/trial-errors {:failures [] :world world} "claude-opus-5-5"))))
    (is (empty? (eval/trial-errors {:failures [] :world world} "claude-haiku-5-5")))
    (is (empty? (eval/trial-errors {:failures [] :world world} "claude-haiku-5-5-20261001")) "a dated id is the same model")))

(deftest a-summary-and-report-from-rows
  (let [kase {:eval {:id "x" :tier :regression :version 1 :tags [:a]}}
        row (fn [t pass?] (eval/result-row kase t {:g {:value (if pass? :pass :fail) :explanation "" :gating? true}}
                                           {:wakes 1 :requests 3 :usage {:input 10 :output 5 :cache-read 0 :cache-write 0}
                                            :cost-usd 0.002}
                                           "/tmp/x"))
        rows [(row 1 true) (row 2 false) (row 3 true)]
        s (eval/summary [{:id "x" :tier :regression :rows rows :errors 1}])]
    (is (= "C" (get-in (first rows) [:scores :g :value])))
    (is (= 2 (get-in s [:cases "x" :passed])))
    (is (= {:g 1} (get-in s [:cases "x" :grader-fails])))
    (is (< (Math/abs (- (get-in s [:cases "x" :pass-hat 3]) 0.0)) 1e-9))
    (is (< (Math/abs (- (:cost-per-wake s) 0.002)) 1e-9))
    (is (= 1 (:errors s)))
    (let [lines (render/eval-report {:run-id "r" :live false :trials 3 :prompt-digest "abcdef0123456789"} s)]
      (is (some #(str/starts-with? % "x ") lines))
      (is (some #(str/includes? % "g x1") lines))
      (is (some #(str/starts-with? % "regression: pass 0.67") lines)))))


;; --- the stand-in tools

(deftest stand-in-tools-answer-as-the-design-says
  (let [[env] (staging-tools/answer "fail-demo" "/d" {:reminder "renew passport"})]
    (is (:is_error env))
    (is (= staging-tools/fail-message (:content env))))
  (is (not (:is_error (first (staging-tools/answer "fail-demo" "/d" {:reminder "renew passport at 9:30"})))))
  (testing "web-demo reads only a plain page name inside the data directory"
    (is (= {:read "/d/web/office-page.html"} (second (staging-tools/answer "web-demo" "/d" {:page "office-page.html"}))))
    (doseq [bad ["../relay.edn" "/etc/passwd" "web/../../x.html" ".hidden.html" "a.edn"]]
      (let [[env effect] (staging-tools/answer "web-demo" "/d" {:page bad})]
        (is (:is_error env) bad)
        (is (nil? effect) bad)))
    (is (= ["contaminated"] (:labels (first (staging-tools/answer "web-demo" "/d" {:page "x.html"}))))))
  (testing "web-ask-demo records the query and reads the best pages, as ask would"
    (let [[env effect] (staging-tools/answer "web-ask-demo" "/d" {:query "Lantern agent support" :n 2})]
      (is (= ["contaminated"] (:labels env)))
      (is (= "/d/searches.log" (:append effect)))
      (is (= {:query "Lantern agent support" :n 2} (:ask effect))))
    (is (= 3 (:n (:ask (second (staging-tools/answer "web-ask-demo" "/d" {:query "q"}))))))
    (doseq [bad [{} {:query " "} {:query "q" :n 0} {:query "q" :n 6} {:query "q" :n "2"}]]
      (let [[env effect] (staging-tools/answer "web-ask-demo" "/d" bad)]
        (is (:is_error env) (pr-str bad))
        (is (nil? effect) (pr-str bad))))
    (let [pages {"a.html" "lantern agents" "b.html" "lantern" "c.html" "nothing here" "d.html" "agent lantern support"}]
      (is (= ["d.html" "a.html"] (map first (staging-tools/ask-pages pages "Lantern agent support" 2))))
      (is (= ["d.html" "a.html" "b.html"] (map first (staging-tools/ask-pages pages "lantern agent support" 5))))
      (is (empty? (staging-tools/ask-pages pages "zebra" 3))))
    (let [out (staging-tools/render-ask "q" [["a.html" "text a"] ["b.html" "text b"]] "0123456789abcdef")]
      (is (str/includes? out "\n=== page 2 0123456789abcdef ===\nresult_title: b.html\nresult_url: https://pages.example.org/b.html\n"))
      (is (str/starts-with? out "2 pages for \"q\"")))
    (is (= "No results for \"q\"." (staging-tools/render-ask "q" [] "t"))))
  (testing "mail-demo appends inside the data directory"
    (is (= "/d/outbox.log" (:append (second (staging-tools/answer "mail-demo" "/d" {:to "a@example.com" :subject "s" :body "b"}))))))
  (testing "verify-demo finds a quote only on the page the URL names, and reads nothing else"
    (let [items [{:url "https://a.example.org/office-page.html" :quotes ["Closed on Mondays." "Open on Sundays."]}
                 {:url "https://a.example.org/missing.html" :quotes ["x"]}
                 {:url "https://a.example.org/../relay.edn" :quotes []}]
          asked (atom [])
          {out :items} (staging-tools/check-items items (fn [page] (swap! asked conj page)
                                                          (when (= "office-page.html" page) "<p>Closed on Mondays.</p>")))]
      (is (= ["office-page.html" "missing.html"] @asked))
      (is (= [true false] (map :found (:quotes (first out)))))
      (is (= [true false false] (map :fetched out)))
      (is (= "example.org" (:domain (first out))))
      (is (= {:check items} (second (staging-tools/answer "verify-demo" "/d" {:items items}))))
      (testing "in the shape relay reads from webtool check"
        (is (= (count items) (count (reports/parse-check-items {:items items}
                                                               (json/parse-string (json/generate-string {:items out}))))))))))

(deftest the-stand-in-tools-are-defined-but-granted-to-nobody
  (let [s (staging/settings {:data-dir "/s" :repo-root "/r" :bb-bin "/bin/bb"})]
    (is (= :egress (get-in s [:tools "mail-demo" :class])))
    (is (= ["contaminated"] (get-in s [:tools "web-demo" :labels])))
    (is (= ["/bin/bb" "--classpath" "/r/src" "-m" "relay.staging-tools" "fail-demo" "/s"]
           (get-in s [:tools "fail-demo" :command])))
    (is (not-any? (set (keys staging/stand-in-tools)) (mapcat :tools (vals (:grants s)))))
    (is (= ["/bin/bb" "--classpath" "/r/src" "-m" "relay.staging-tools" "verify-demo" "/s"]
           (get-in s [:verifier :command])))
    (is (not-any? :reports (vals (:grants s))) "no role writes reports unless a scenario grants it")))

(deftest a-granted-stand-in-tool-runs-through-relay-tool
  (let [dir (str (fs/create-temp-dir {:prefix "relay-eval-standin"}))
        file (str (fs/path dir "standin.edn"))]
    (spit file (pr-str {:doc "fail-demo, granted by :settings, refuses a reminder with no time."
                        :clock "2026-10-12T09:00"
                        :settings {:grants {"clerk" {:tools ["remind-demo" "fail-demo"]}}}
                        :model {"clerk" [{:tool-uses [{:name "fail-demo" :input {:reminder "renew passport"}}]}
                                         {:tool-uses [{:name "clock" :input {:op "now"}}]}]}
                        :steps [{:run ["memo" "clerk" "-s" "Passport" "-m" "Remind me to renew my passport."]}
                                {:tick 1}]
                        :assert [[:has :tool-call {:name "fail-demo" :verdict "allow" :error true
                                                   :result staging-tools/fail-message}]
                                 ;; The tool process reads the stopped clock: Monday 12 October.
                                 [:has :tool-call {:name "clock" :result [:includes "Mon"]}]]}))
    (try
      (let [r (runner/run-scenario! file {})]
        (is (:pass? r) (pr-str (:failures r))))
      (finally (fs/delete-tree dir)))))

;; --- every case, oracle and foils, against the mock model

(deftest every-eval-case-is-well-formed
  (is (empty? (eval/defaults-problems defaults)))
  (is (seq (et/eval-case-files)))
  (doseq [f (et/eval-case-files)]
    (is (et/read-case f defaults) f)))

(deftest every-oracle-passes-and-every-foil-fails-exactly-its-graders
  (doseq [f (et/eval-case-files)
          :let [{:keys [oracle foils]} (et/mock-verdicts f)]]
    (testing f
      (is (empty? oracle) (str "oracle: " (str/join "; " oracle)))
      (is (seq foils) "every case has a foil")
      (doseq [[nm problems] foils]
        (is (empty? problems) (str "foil " nm ": " (str/join "; " problems)))))))

;; --- judges are skipped, the budget decides, the report is data

(deftest a-judge-grader-is-skipped-never-failed
  (let [g [{:id :c :check yes}
           {:id :j :judge {:rubric :r :evidence [[:memos {}]]}}
           {:id :c2 :check no :gating? false}]
        scores (eval/grade memo-world g)]
    (is (= :skipped (:value (:j scores))))
    (is (false? (:gating? (:j scores))))
    (is (eval/passed? scores) "a skipped judge does not stop a trial passing")
    (is (not (eval/passed? (assoc scores :c {:value :fail :gating? true}))))
    (is (empty? (eval/foil-problems scores #{:c2 :j})) "a foil cannot be held to a judge's score")
    (let [row (eval/result-row {:eval {:id "x" :tier :regression :version 1}} 1 scores {:usage {}} "/d")]
      (is (= #{:c :c2} (set (keys (:scores row)))) "no score row for the judge")
      (is (= [{:id :j :kind "judge" :reason "Judge graders are not run yet."}] (:skipped row))))
    (is (empty? (eval/case-problems (-> (et/read-case "test/scenarios/fact-validity-window.edn" defaults)
                                        (update :graders conj {:id :j2 :judge {:rubric :r :evidence []}}))
                                    defaults)))
    (is (some #(str/includes? % "cannot be gating")
              (eval/case-problems (-> (et/read-case "test/scenarios/fact-validity-window.edn" defaults)
                                      (update :graders conj {:id :j2 :gating? true :judge {:rubric :r :evidence []}}))
                                  defaults)))))

(deftest a-failed-check-shows-instants-as-text
  (let [at (java.time.Instant/parse "2026-10-06T03:00:00Z")
        line (scenario/check {:sent [{:at at}]} [:has :sent {:at "never"}])]
    (is (str/includes? line "\"2026-10-06T03:00:00Z\""))
    (is (not (str/includes? line "#object")))))

(deftest a-result-row-names-the-served-models
  (is (= ["claude-haiku-5-5"]
         (:served_models (eval/result-row {:eval {:id "x" :tier :regression :version 1}} 1 {}
                                          {:usage {} :models ["claude-haiku-5-5"]} "/d")))))
