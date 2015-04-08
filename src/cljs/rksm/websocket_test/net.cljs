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

(defrecord Connection [url id tracker-id receive ws services]
  com/IConnection
  (send [this msg] (send-to-server this msg))
  (answer [this msg data] (send-to-server this (com/answer-msg this msg data false)))
  (handle-request [this channel raw-data] (com/default-handle-request this channel raw-data))
  (handle-response-request [this msg] (go (>! (:chan receive) {:message msg})))
  (register-connection [this id channel] (println "register-connection not yet implemented"))
  (add-service [this name handler-fn] (println "add-service not yet implemented"))
  (lookup-handler [this action] (get-in this [:services action])))

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
    (some-> c :receive :chan close!)
    (swap! connections dissoc id)))

(defn remove-all-connections
  []
  (doseq [[id _] @connections]
    (remove-connection id)))

(defn connect
  [url]
  (if-let [c (find-connection-by-url url)]
    (do (println "found exsiting connection to " url (:id c)) c)
    (let [id (uuid/uuid-string (uuid/make-random-uuid))
          receive-chan (chan)
          c (map->Connection {:url url :id id
                              :receive {:chan receive-chan
                                        :pub (pub receive-chan (comp :in-response-to :message))}
                              :services (com/default-services)
                              :tracker-id nil
                              :ws nil})]
      (swap! connections assoc id c)
      c)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn- handle-establish-server-connection-error
  [{:keys [id url ws], :as con} error result-channel]
  (let [reason (str "client " id " could not establish connection to server " url)
        error (merge-with str error {:reason reason})]
    (go (>! result-channel {:error error}))))

(defn- send-handshake
  [{id :id, :as con} ws-channel result-channel]
  (go
   (>! ws-channel {:action "info" :sender id})
   (let [{:keys [message error]} (<! ws-channel)
         {{server-id :id} :data} message
         con (map->Connection (assoc con
                                     :tracker-id server-id
                                     :ws {:chan ws-channel}))]
     (swap! connections assoc id con)

     ; main message receive loop
     (go-loop []
       (if-let [data (<! ws-channel)]
         (do
           (com/handle-request con ws-channel (or (:message data) data))
           (recur))
         (do (println "Connection" id "closed"))))

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

(defn send-to-server
  "creates a channel that acts as an interface to sending messages to the
  server / tracker. Transparently reconnects."
  [{ws :ws, :as con} msg
   & {:keys [expect-more-responses], :or {expect-more-responses false}}]
  (let [client-chan (chan)]
    (go
     (let [{:keys [id ws receive tracker-id error] :as con} (<! (establish-server-channel con))]
       (if error
         (>! client-chan {:error error})
         (do
           (let [msg (merge {:target tracker-id} msg)
                 {msg-id :id, target :target, :as msg} (com/send-msg con msg expect-more-responses)
                 sub-chan (sub (:pub receive) msg-id (chan))
                 client-chan client-chan
                 close (fn [] (close! sub-chan) (close! client-chan))]

             ; real send
             (if-let [ws-chan (:chan ws)]
               (>! ws-chan msg)
               (let [msg (str "Cannot send message " msg
                              " to target " target
                              ", websocket channel not available")]
                 (println msg)
                 (throw (js/Error. msg))))

             ; wait for responses
             (go-loop []
               (let [{:keys [message error] :as msg} (<! sub-chan)]
                 (if msg (>! client-chan msg))
                 (cond
                   (nil? msg) (close)
                   error (close)
                   (not (:expect-more-responses msg)) (close)
                   :default (recur)))))))))

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
