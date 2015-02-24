(ns rksm.websocket-test.simple-sender
  (:require [cljs.core.async :refer [put! chan <! >! timeout]])
  (:require-macros [cljs.core.async.macros :refer (go)]))

(def c (chan))


(go 
 (<! (timeout 1000))
 (go (>! c "test"))
    ) 

(go (js/console.log (<! c)))

(comment 
 (async/go
  (js/console.log "foooo?")
  (let [{:keys [ws-channel error]} (<! (ws-ch "ws://localhost:8081"))]
    (if-not error
      (do (>! ws-channel "Hello server from client!")
        (loop []
          (if-let [{:keys [message error]} (<! ws-channel)]
            (if error
              (js/console.log "Connection error:" error)
              (do
                (.log js/console "Got message from server:" (pr-str message))
                (recur)))
            (js/console.log "Connection closed")))
        )
      (js/console.log "Error:" (pr-str error))))))

(comment (ns rksm.websocket-test.simple-sender
  (:require [chord.client :refer [ws-ch]]
            [cljs.core.async :refer [<! >! put! close!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(.log js/console "barrrr?")

(go
 (js/console.log "foooo?")
 (let [{:keys [ws-channel error]} (<! (ws-ch "ws://localhost:8081"))]
   (if-not error
     (do (>! ws-channel "Hello server from client!")
       (loop []
         (if-let [{:keys [message error]} (<! ws-channel)]
           (if error
             (js/console.log "Connection error:" error)
             (do
               (.log js/console "Got message from server:" (pr-str message))
               (recur)))
           (js/console.log "Connection closed")))
       )
          (js/console.log "Error:" (pr-str error)))))
)