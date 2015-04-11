(ns rksm.cloxp-com.simple-sender
  (:refer-clojure :exclude [send])
  (:require [rksm.cloxp-com.net :as net]
            [rksm.cloxp-com.messenger :refer [answer send]]
            [cljs.core.async :refer [<! >! put! close! chan pub sub]]
            [rksm.cloxp-com.async-util :refer [join]]
            [cognitect.transit :as t]
            [clojure.browser.repl :as brepl])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(enable-console-print!)

(do
  (println "running bootstrap...")
  (brepl/bootstrap))

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
