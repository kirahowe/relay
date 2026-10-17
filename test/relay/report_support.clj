(ns relay.report-support
  "What the tests share about research reports: a stand-in for what the
  verify process passes on, made in memory."
  (:require [relay.reports :as reports]))

(defn answer
  "What the verifier says of one URL and its quotes: fetched, complete,
  checkable, each quote found as `found?` says."
  [url quotes found?]
  (let [host (reports/url-host url)
        d (reports/guess-domain host)
        h {:name host :unicode host :punycode false :mixed-scripts false}]
    {:url url :fetched true :complete true :uncheckable "" :error "" :status 200 :final-url url
     :crosses-domains false :domain d :final-domain d :host h :final-host h :sha256 "abc"
     :quotes (mapv (fn [q] {:quote q :found (boolean (found? q))}) quotes)}))

(defn results
  "The results the verify process would pass on for a report, item by item
  for each request, every quote found unless `found?` says not."
  ([report] (results report (constantly true)))
  ([report found?]
   (vec (for [{:keys [items]} (reports/check-requests report)
              {:keys [url quotes]} items]
          (answer url quotes found?)))))
