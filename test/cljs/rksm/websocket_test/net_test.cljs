(ns ^{:figwheel-always true
      :doc "See rksm.websocket-test.client-test for how running these tests as
      part of the server tests"} rksm.websocket-test.net-test
  (:require [rksm.websocket-test.net :as net]
            [rksm.websocket-test.com :as com]
            [cljs.core.async :refer [<! >! put! close! chan pub sub timeout]]
            [rksm.websocket-test.async-util :refer [join]]
            [cognitect.transit :as t]
            [figwheel.client :as fw]
            [cemerick.cljs.test :refer [testing-complete?]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cemerick.cljs.test :refer [is deftest testing use-fixtures done run-tests]]))

(def url "ws://localhost:8082/ws")

(defn net-cleanup [t]
  (t)
  (net/remove-all-connections))

(use-fixtures :each net-cleanup)

(defn start-tests []
  (let [done-chan (chan)
        test-env (run-tests 'rksm.websocket-test.net-test)]
    (go-loop []
      (if (testing-complete? test-env)
        (>! done-chan test-env)
        (do (<! (timeout 100)) (recur))))
    done-chan))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(deftest ^:async simple-ws-connect-test
  (let [{id :id, :as c} (net/connect url)]
    (is (string? id))
    (go
     (let [{:keys [message error] :as answer}
           (<! (com/send c {:action "echo", :data "test"}))]
       (is (nil? error) (str error))
       (is (= (:data message) "test"))
       (net/remove-all-connections) ; FIXME why does fixure not work???
       (done)))))

(deftest ^:async streaming-response-test
  (let [{id :id, :as c} (net/connect url)
        stream-handler '(do
                          (require '[rksm.websocket-test.com :as com])
                          (fn [con msg]
                           (com/send con (com/answer-msg con msg 1 true))
                           (com/send con (com/answer-msg con msg 2 true))
                           (com/send con (com/answer-msg con msg 3 false))))
        add-service-msg {:action "add-service"
                         :data {:name "stream" :handler (str stream-handler)}}]

    (go
     (testing "add-service"
       (let [add-service-answer (<! (com/send c add-service-msg))]
         (is (= "OK" (-> add-service-answer :message :data))))
       (testing "invoke added service"
         (let [result (<! (join (com/send c {:action "stream" :data nil})))]
           (net/remove-all-connections) ; FIXME why does fixure not work???
           (done)))))))

(deftest ^:async server->client-send
  (let [send-new-message-handler
        '(do
           (require '[rksm.websocket-test.com :as com])
           (require '[clojure.core.async :as async])
           (fn [con {:keys [data] :as msg}]
             (com/answer con msg :OK)
             (let [{:keys [wait msg-to-send]} data]
               (async/go
                (async/<! (async/timeout wait))
                (println "answer from browser:"
                         (async/<! (let [msg (com/send-msg con msg-to-send false)]
                                     (com/send con msg))))))))
        {id :id, :as c} (net/connect url)
        add-service-msg {:action "add-service"
                         :data {:name "send-new-message"
                                :handler (str send-new-message-handler)}}
        msg-to-send {:target (:id c) :action "echo" :data "from-server"}
        send-new-message-msg {:action "send-new-message" :data {:wait 300 :msg-to-send msg-to-send}}]

    (go
     (testing "add-server"
       (let [{{add-status :data} :message} (<! (com/send c add-service-msg))]
         (is (= "OK" add-status))

         (testing "invoke server"
           (let [{{send-status :data} :message} (<! (com/send c send-new-message-msg))]
             (is (= "OK" send-status))
             (<! (timeout 1000))
             (net/remove-all-connections) ; FIXME why does fixure not work???
             (done))))))))

; (deftest ^:async eval-test
;   (let [{id :id, :as c} (net/connect url)]
;     (go
;      (let [result (<! (join (com/send c {:action "eval" :data {:expr "(+ 1 2)"}})))]
;       (is (= [1 2 3] (map (comp :data :message) result)))
;       (done)))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(enable-console-print!)

(go
 (let [result (<! (start-tests))
       result (apply merge
                ((juxt #(select-keys % [:test :pass :fail :error])
                       (comp deref :async))
                 result))]
   (println result)
   (<! (timeout 100))
   (set! (.-cljs_tests_done js/window) (clj->js result))))

; registered-test-hooks
; (fw/start {
;   :websocket-url   "ws://localhost:3449/figwheel-ws"
;   :on-jsload (fn [] (print "reloaded"))
; })
