(ns rksm.websocket-test.net-test
  (:require [rksm.websocket-test.net :as net]
            [cljs.core.async :refer [<! >! put! close! chan pub sub]]
            [rksm.websocket-test.async-util :refer [join]]
            [cognitect.transit :as t]
            [figwheel.client :as fw]
            [cemerick.cljs.test :as test])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cemerick.cljs.test :refer [is deftest run-tests testing test-var use-fixtures done]]))

(def url "ws://localhost:8081/ws")

(defn net-cleanup [t]
  (t)
  (doseq [{id :id} @net/connections]
    (net/remove-connection id)))

(use-fixtures :each net-cleanup)

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(deftest ^:async simple-ws-connect-test
  (let [{id :id, :as c} (net/connect url)]
    (is (string? id))
    (go
     (let [answer-chan (net/send c {:action "echo", :data "test"})
          {:keys [message error] :as answer} (<! answer-chan)]
      (is (= (:data message) "test"))
      (done)))))

(deftest ^:async streaming-response-test
  (let [{id :id, :as c} (net/connect url)
        add-service-msg {:action "add-service"
                         :data {:name "stream"
                                :handler (str '(fn [con msg]
                                                 (rksm.websocket-test.server/answer! con msg 1 :expect-more-responses true)
                                                 (rksm.websocket-test.server/answer! con msg 2 :expect-more-responses true)
                                                 (rksm.websocket-test.server/answer! con msg 3)))}}]
    (go
     (let [_ (<! (net/send c add-service-msg))
           result (<! (join (net/send c {:action "stream" :data nil})))]
       (is (= [1 2 3] (map (comp :data :message) result)))
       (done)))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(enable-console-print!)

; (fw/start {
;   :websocket-url   "ws://localhost:3449/figwheel-ws"
;   :on-jsload (fn [] (print "reloaded"))
; })

(js/setTimeout
 #(run-tests 'rksm.websocket-test.net-test)
 100)