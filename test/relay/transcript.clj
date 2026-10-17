(ns relay.transcript
  "Reading an agentloop run back, for scenarios and evals. Pure: each
  function takes the run's entries, the JSON lines agentloop printed to the
  wake's transcript.jsonl (relay keeps them at runs/<role>/<wake>/), parsed
  with keyword keys.

  The entries this reads:

    {:type \"message\" :message {:message {:role \"assistant\" :parts [...]}
                                :usage {...} :model \"...\" :stop \"...\"}}
    {:type \"decision\" :decision {:call_id :tool :verdict \"allow\"|\"deny\"|\"hold\" :reason}}
    {:type \"tool_result\" :tool_result {:result {:call_id :content :is_error}}}
    {:type \"run_ended\" :run_ended {:reason ...}}

  Each assistant message is one model request."
  (:require [clojure.string :as str]))

(def prices
  "US dollars per million tokens, by model, copied from agentloop's
  anthropic/prices.go (Anthropic's published rates, checked there on
  2026-10-06). Cache writes are the five-minute rate. A model not listed
  has no known price, and its cost is nil rather than zero."
  {"claude-fable-5-1" {:input 10 :output 50 :cache-write 12.5 :cache-read 0.25}
   "claude-opus-5-5" {:input 4 :output 20 :cache-write 5 :cache-read 0.2}
   "claude-opus-5" {:input 5 :output 25 :cache-write 6.25 :cache-read 0.5}
   "claude-sonnet-5-5" {:input 2 :output 10 :cache-write 2.5 :cache-read 0.2}
   "claude-sonnet-5" {:input 2 :output 10 :cache-write 2.5 :cache-read 0.2}
   "claude-haiku-5-5" {:input 0.1 :output 0.5 :cache-write 0.125 :cache-read 0.01}})

(def zero-usage {:input 0 :output 0 :cache-read 0 :cache-write 0})

(defn usage
  "A usage map in agentloop's names, as {:input :output :cache-read :cache-write}."
  [{:keys [input_tokens output_tokens cache_read_tokens cache_write_tokens]}]
  {:input (or input_tokens 0) :output (or output_tokens 0)
   :cache-read (or cache_read_tokens 0) :cache-write (or cache_write_tokens 0)})

(defn add-usage [a b] (merge-with + a b))

(defn base-model
  "A served model id without a date suffix: claude-haiku-5-5-20261001 is
  claude-haiku-5-5."
  [model]
  (some-> model (str/replace #"-\d{8}$" "")))

(defn cost
  "What `u` costs at `model`'s prices, in US dollars, or nil when the price
  is not known. A dated id is priced as its base model."
  [model u]
  (when-let [p (get prices (base-model model))]
    (/ (+ (* (:input u) (:input p)) (* (:output u) (:output p))
          (* (:cache-read u) (:cache-read p)) (* (:cache-write u) (:cache-write p)))
       1e6)))

(defn requests
  "One map per model request: {:model :usage :cost-usd :stop}."
  [entries]
  (vec (for [{:keys [type message]} entries
             :when (and (= "message" type) (= "assistant" (get-in message [:message :role])))
             :let [u (usage (:usage message))]]
         {:model (:model message) :usage u :cost-usd (cost (:model message) u) :stop (:stop message)})))

(defn run-usage
  "The run's totals: {:requests :usage :cost-usd :models}, :cost-usd nil when
  any request's price is unknown."
  [entries]
  (let [rs (requests entries)]
    {:requests (count rs)
     :usage (reduce add-usage zero-usage (map :usage rs))
     :cost-usd (when (every? :cost-usd rs) (reduce + 0.0 (map :cost-usd rs)))
     :models (vec (distinct (keep :model rs)))}))

(defn end-reason
  "How agentloop said the run ended, or nil."
  [entries]
  (some (fn [{:keys [type] :as e}]
          (cond (= "run_ended" type) (get-in e [:run_ended :reason])
                (= "outcome" type) (:reason e)))
        entries))

(defn tool-calls
  "Every tool call the model asked for, in order: {:id :name :input :verdict
  :reason :error :result}. :verdict is agentloop's decision (\"allow\",
  \"deny\", \"hold\"), :error whether the result was an error, :result its
  text. A held or denied call has no result."
  [entries]
  (let [decisions (into {} (for [{:keys [type decision]} entries :when (= "decision" type)]
                             [(:call_id decision) decision]))
        results (into {} (for [{:keys [type tool_result]} entries :when (= "tool_result" type)
                               :let [r (:result tool_result)]]
                           [(:call_id r) r]))]
    (vec (for [{:keys [type message]} entries
               :when (and (= "message" type) (= "assistant" (get-in message [:message :role])))
               part (get-in message [:message :parts])
               :when (= "tool_call" (:type part))
               :let [{:keys [id name input]} (:tool_call part)
                     d (get decisions id)
                     r (get results id)]]
           {:id id :name name :input input
            :verdict (:verdict d) :reason (:reason d)
            :error (boolean (:is_error r)) :result (:content r)}))))
