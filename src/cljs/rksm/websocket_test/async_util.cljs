(ns rksm.websocket-test.async-util
  (:require [cljs.core.async :refer [<! >! put! close! chan pub sub]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn join
  "takes all values from ch until closed. returns a new channel which will
  receive the result"
  [ch]
  (let [result (chan)]
    (go
     (loop [values []]
       (if-let [next (<! ch)]
         (recur (conj values next))
         (do
           (>! result values)
           (close! result)))))
    result))
