(ns rksm.websocket-test.net
  (:require [chord.client :refer [ws-ch]]
            [cljs.core.async :refer [<! >! put! close! chan sub pub]]
            [cljs-uuid-utils :as uuid]
            [cognitect.transit :as t]
            [rksm.websocket-test.com :as com])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(def debug true)

(defn log
  [& msgs]
  (if debug (apply println msgs)))

(defn err
  [& msgs]
  (apply js/console.error msgs)
  (throw (js/Error. (pr-str msgs))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(declare send-to-server)

(defprotocol IConnection
  (send [this msg]))

(defrecord Connection [url id tracker-id ws]
  IConnection
  (send [this msg] (send-to-server this msg)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce connections (atom {}))

(defn find-connection
  [id]
  (get @connections id))

(defn find-connection-by-url
  [url-to-find]
  (->> @connections
    vals
    (filter (fn [{url :url}] (= url-to-find url)))
    first))

(defn remove-connection
  [id]
  (when-let [c (find-connection id)]
    (some-> c :ws :chan close!)
    (swap! connections dissoc id)))

(defn connect
  [url]
  (if-let [c (find-connection-by-url url)]
    c
    (let [id (uuid/uuid-string (uuid/make-random-uuid))
          c (->Connection url id nil nil)]
      (swap! connections assoc id c)
      c)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn- handle-establish-server-connection-error
  [con error result-channel]
  (log "Error establishing connection! %s" error)
  (go (>! result-channel {:error error})))

(defn- send-handshake
  [{id :id, :as con} ws-channel result-channel]
  (go
   (>! ws-channel {:action "info" :sender id})
   (let [{:keys [message error]} (<! ws-channel)
         {{server-id :id} :data} message
         recv-chan (pub ws-channel (comp :in-response-to :message))
         con (assoc con :tracker-id server-id :ws {:chan ws-channel, :receiver recv-chan})]
     (swap! connections assoc id con)
     (>! result-channel con))))

(defn- establish-server-channel
  [{:keys [id url ws], :as con}]
  (let [result (chan)]
    (go
     (if ws (>! result con)
       (let [{:keys [ws-channel error] :as connect-chan} (<! (ws-ch url {:format :json-kw}))]
         
         (if error
           (handle-establish-server-connection-error con error result)
           (send-handshake con ws-channel result)))))
    result))

(defn receive-messages
  [msg-id ws-pub-chan result-chan]
  (let [sub-chan (sub ws-pub-chan msg-id (chan))
        close (fn [] (close! sub-chan) (close! result-chan))]
    (go-loop []
      (let [{:keys [message error] :as msg} (<! sub-chan)]
        (if msg (>! result-chan msg))
        (cond
          (nil? msg) (close)
          error (close)
          (not (:expect-more-responses message)) (close)
          :default (recur))))))

(defn send-to-server
  "creates a channel that acts as an interface to sending messages to the
  server / tracker. Transparently reconnects."
  [con msg]
  (let [client-chan (chan)]
    (go
     (let [{:keys [id ws tracker-id] :as con} (<! (establish-server-channel con))
           msg-id (uuid/uuid-string (uuid/make-random-uuid))
           msg (merge {:id msg-id, :sender id, :target tracker-id} msg)]
       ;   (log "sending..." msg)
       (>! (:chan ws) msg)
       (receive-messages msg-id (:receiver ws) client-chan)))
    client-chan))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(comment
 cljs.core/UUID
 (go
  (let [{:keys [ws-channel error]} (<! (ws-ch "ws://localhost:3000/ws"))]
    (if-not error
      (>! ws-channel "Hello server from client!")
      (js/console.log "Error:" (pr-str error)))))
 
 (go
  (let [{:keys [ws-channel]} (<! (ws-ch "ws://localhost:3000/ws"))
        {:keys [message error]} (<! ws-channel)]
    (if error
      (js/console.log "Uh oh:" error)
      (js/console.log "Hooray! Message:" (pr-str message))))))