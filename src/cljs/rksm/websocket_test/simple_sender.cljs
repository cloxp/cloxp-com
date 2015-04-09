(ns rksm.websocket-test.simple-sender
  (:refer-clojure :exclude [send])
  (:require [rksm.websocket-test.net :as net]
            [rksm.websocket-test.messenger :refer [answer send]]
            [cljs.core.async :refer [<! >! put! close! chan pub sub]]
            [rksm.websocket-test.async-util :refer [join]]
            [cognitect.transit :as t]
            [figwheel.client :as fw])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))



; (let [{id :id, :as c} (net/connect url)
;       add-service-msg {:action "add-service"
;                       :data {:name "stream"
;                               :handler (str '(fn [con msg]
;                                               (rksm.websocket-test.server/answer! con msg 1 :expect-more-responses true)
;                                               (rksm.websocket-test.server/answer! con msg 2 :expect-more-responses true)
;                                               (rksm.websocket-test.server/answer! con msg 3)))}}]
;   (go
;   (let [_ (<! (net/send c add-service-msg))
;          result (<! (join (net/send c {:action "stream" :data nil})))]
;      (is (= [1 2 3] (map (comp :data :message) result)))
;      (done))))
(enable-console-print!)

(println "test 1235")

(defonce cloxp-connection (atom nil))

(defn with-con
  [do-func]
  (if-let [c @cloxp-connection]
    (do-func c)
    (go
     (let [c (<! (net/connect "ws://localhost:8082/ws"))]
       (reset! cloxp-connection c)
       (do-func c)))))

(defn test-send
  [con]
  (go
   (let [t1 (js/Date.)]
     (println (<! (send con {:action "echo" :data "huhu!"})))
     (let [t2 (js/Date.)]
       (println (- t2 t1))
       (dotimes [_ 10]
         (do
           (<! (send con {:action "echo" :data "harhahr!"}))
           (println (- (js/Date.) t2))))))))

(with-con
  (fn [con]
    (send con {:action "register"})
    (println "Connected")))

; (fw/start {
;   :websocket-url   "ws://localhost:3449/figwheel-ws"
;   :on-jsload (fn [] (print "reloaded"))
; })


