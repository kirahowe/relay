(ns relay.held-sessions-test
  "Regression tests from a review of held sessions keeping their memos."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relay.tools]
            [relay.config :as config]
            [relay.core :as core])
  (:import (java.time Instant)))

(def t0 (Instant/parse "2026-10-14T12:00:00Z"))
(def cfg (config/resolve-config
          {:timezone "UTC" :default-harness :agentloop
           :harnesses {:agentloop {:kind :agentloop :api-key-command ["true"] :bin "/nonexistent/agentloop"}}
           :notify {:kind :none}
           :tools {"post" {:class :egress :labels [] :description "Post." :command ["true"]}}
           :grants {"hub" {:may-add true} "clerk" {:tools ["post"]}}}))

(defn- run [{:keys [state now] :as w} actor type args & [labels]]
  (let [[state' events] (core/run-command state {:type type :actor actor :args args :labels labels}
                                          {:now now :config cfg})]
    (assoc w :state state' :last events)))

(defn- two-held []
  (let [w (-> {:state core/empty-state :now t0}
              (run "operator" :role/add {:id "hub" :title "Hub" :instructions "Run relay."})
              (run "operator" :role/add {:id "clerk" :title "Clerk" :instructions "File things."})
              (run "operator" :memo/send {:to "clerk" :subject "Post it" :body "Please."})
              (run "dispatcher" :wake/start {:role "clerk" :dispatched true}))
        wake (core/running-wake (:state w) "clerk")]
    (run w "dispatcher" :wake/end {:wake (:id wake) :outcome "ran" :exit 0 :session "sess-1" :said ""
                                   :labels ["contaminated" "private"]
                                   :held [{:id "c1" :name "post" :input {:a 1} :input-sha256 "h1"}
                                          {:id "c2" :name "post" :input {:a 2} :input-sha256 "h2"}]})))

(deftest deny-end-is-not-undone-by-a-sibling-hold
  (let [w (two-held)
        [h1 h2] (core/open-holds (:state w) "clerk")
        w (run w "operator" :hold/resolve {:hold (:id h1) :verdict "deny" :end true})
        _ (is (= :denied (:status (core/hold (:state w) (:id h2)))) "ending it denies its other open calls")
        _ (is (thrown? clojure.lang.ExceptionInfo (run w "operator" :hold/resolve {:hold (:id h2) :verdict "allow"})))
        w (run w "dispatcher" :wake/start {:role "clerk" :dispatched true})
        wake (core/running-wake (:state w) "clerk")]
    (is (nil? (:resumes wake)) "the ended session must not be carried on")
    (is (= 1 (count (:memos wake))) "its memo goes on in a fresh session")))

(deftest a-fresh-session-can-close-a-kept-memo
  (let [w (two-held)
        kept (:id (first (core/unread-memos (:state w) "clerk")))
        w (run w "operator" :memo/send {:to "clerk" :subject "Other" :body "b"})
        w (run w "dispatcher" :wake/start {:role "clerk" :dispatched true})]
    (is (core/running-wake (:state w) "clerk"))
    (is (nil? (try (run w "clerk" :memo/close {:memo kept :outcome "done by the fresh session"}) :closed
                   (catch clojure.lang.ExceptionInfo _ nil)))
        "a fresh session closes a memo the held session keeps")))

(deftest a-cut-short-fresh-session-is-dropped-by-the-resume
  (let [w (two-held)
        [h1 h2] (core/open-holds (:state w) "clerk")
        w (run w "operator" :memo/send {:to "clerk" :subject "Other" :body "b"})
        w (run w "dispatcher" :wake/start {:role "clerk" :dispatched true})
        fresh (core/running-wake (:state w) "clerk")
        w (run w "dispatcher" :wake/end {:wake (:id fresh) :outcome "ran" :exit "timeout" :cut-short true :session "sess-2" :said ""})
        _ (is (= (:id fresh) (:interrupted-wake (core/role (:state w) "clerk"))))
        w (run w "operator" :hold/resolve {:hold (:id h1) :verdict "allow"})
        w (run w "operator" :hold/resolve {:hold (:id h2) :verdict "allow"})
        w (run w "dispatcher" :wake/start {:role "clerk" :dispatched true})
        resumed (core/running-wake (:state w) "clerk")
        w (run w "dispatcher" :wake/end {:wake (:id resumed) :outcome "ran" :exit 0 :session "sess-1" :said ""})]
    (is (= (:id fresh) (:interrupted-wake (core/role (:state w) "clerk"))) "sess-2 should still be resumable")))


(deftest a-kept-memo-is-shown-as-kept-to-a-fresh-session
  (let [w (two-held)
        kept (:id (first (core/unread-memos (:state w) "clerk")))
        w (run w "operator" :memo/send {:to "clerk" :subject "Other" :body "b"})
        w (run w "dispatcher" :wake/start {:role "clerk" :dispatched true})
        inbox ((:read (get relay.tools/builtin "inbox")) {:state (:state w) :config cfg :now t0} "clerk" {})]
    (is (str/includes? (:content inbox) (str "Memo " kept " from operator: kept by a session waiting")))
    (is (not (str/includes? (:content inbox) "Post it")))
    (is (thrown? clojure.lang.ExceptionInfo (run w "clerk" :memo/block {:memo kept :explanation "x"})))))

(deftest the-session-that-keeps-a-memo-may-close-it
  (let [w (two-held)
        kept (:id (first (core/unread-memos (:state w) "clerk")))
        [h1 h2] (core/open-holds (:state w) "clerk")
        w (-> w (run "operator" :hold/resolve {:hold (:id h1) :verdict "allow"})
              (run "operator" :hold/resolve {:hold (:id h2) :verdict "allow"})
              (run "dispatcher" :wake/start {:role "clerk" :dispatched true}))
        w (run w "clerk" :memo/close {:memo kept :outcome "Posted."})]
    (is (= :read (:status (core/memo (:state w) kept))))))
