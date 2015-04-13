(ns rksm.cloxp-com.net
  (:require [chord.client :refer [ws-ch]]
            [cljs.core.async :refer [<! >! put! close! chan sub pub timeout]]
            [cljs-uuid-utils :as uuid]
            [cognitect.transit :as t]
            [rksm.cloxp-com.messenger :as m]
            [rksm.cloxp-com.eval :as eval])
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

(defn- handle-establish-server-connection-error
  [url error result-chan]
  (let [reason (str "client could not establish connection to server " url)
        error (merge-with str error {:reason reason})]
    (log "Error in establish-server-channel:" error)
    (go
     (>! result-chan {:error error})
     (close! result-chan))))

(declare send-handshake)

(defn- reconnect
  [url receive-chan send-chan timeout-per-attempt attempt-no max-attempts]
  (if (> attempt-no max-attempts)
    (log "Giving up reconnecting to" url)
    (go
     (log "Attempt" attempt-no "trying to reconnect to..." url)
     (let [{:keys [ws-channel error]} (<! (ws-ch url {:format :json-kw}))]
       (if error
         (do
           (log "Re-connect to" url "failed, trying again...")
           (log "waiting...")
           (<! (timeout timeout-per-attempt))
           (log "...waiting done")
           (reconnect url receive-chan send-chan
                      timeout-per-attempt (inc attempt-no) max-attempts))
         (send-handshake url ws-channel
                         receive-chan send-chan (chan)))))))

(defn- send-handshake
  "ws-chan: coming from chord, for sending and receiving
  receive-chan: handed to the messenger, for receiving messages, decoupled from
  chord to allow for transparent reconnects
  send-chan: handed to the messenger, decoupled from chord
  result-channel: for when connection is established"
  [url ws-channel receive-chan send-chan result-chan]
  (go

   ; let the server know who we are
   (>! ws-channel {:action "info" :sender nil})
   (let [{:keys [message error]} (<! ws-channel)
         {{server-id :id} :data} message]

     (log "sending info to server" message error)

     ; receive loop
     (go-loop []
       (if-let [data (<! ws-channel)]
         (do
           (>! receive-chan {:raw-msg (or (:message data) data),
                             :connection {:channel ws-channel}})
           (recur))
         (do
           (log "Server connection closed" url (str "(" server-id ")"))
           (reconnect url receive-chan send-chan 1000
                      0 10))))

     ; send loop
     (go-loop []
       (if-let [data (<! send-chan)]
         (do
           (>! ws-channel data)
           (recur))
         (do
           (log "Client closed connection to server" url (str "(" server-id ")"))
        ;   (close! ws-channel)
           (close! receive-chan)
           (close! send-chan))))

     (log "Created connection to server" url (str "(" server-id ")"))

     ; for the messenger
     (>! result-chan {:tracker-id server-id
                      :send-chan send-chan
                      :receive-chan receive-chan
                      :ws-chan ws-channel})
     (close! result-chan))))

(defn- establish-server-channels
  [url]
  (let [result-chan (chan)
        receive-chan (chan)
        send-chan (chan)]
    (go
     (log "Trying to connect to cloxp server" url)
     (let [{:keys [ws-channel error] :as connect-chan} (<! (ws-ch url {:format :json-kw}))]
       (if error
         (handle-establish-server-connection-error url error result-chan)
         (send-handshake url ws-channel receive-chan send-chan result-chan))))
    result-chan))

(defrecord MessengerImpl [url tracker-id send-chan receive-chan]
  m/IReceiver
  (receive-chan [this] receive-chan)
  (stop-receiver [this]
                 (close! send-chan)
                 (close! receive-chan))
  m/ISender
  (send-message [this con msg]
                (log "sending" (:action msg) (:data msg))
                (let [msg (merge {:target tracker-id} msg)]
                  (go (>! send-chan msg)))))

(defn create-messenger
  [url]
  (let [result-chan (chan)]
    (go
     (let [{:keys [tracker-id send-chan receive-chan error] :as connected}
           (<! (establish-server-channels url))]
       (if (or error (not connected))
         (let [msg (str "Cannot connect to " url ": "
                        (or error "establish server channel failed"))]
           (.error js/console msg)
           (>! result-chan {:error msg}))
         (let [messenger (m/create-messenger
                          "browser-client"
                          (->MessengerImpl url tracker-id
                            send-chan receive-chan))]
           (m/add-service messenger "eval-js" eval/eval-js-service)
           (m/add-service messenger "load-js" eval/load-js-service)
           (>! result-chan messenger)))))
    result-chan))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce connections (atom {}))

(defn find-connection
  [id]
  (get @connections id))

(defn find-connection-by-url
  [url-to-find]
  (->> @connections vals
    (filter #(= (-> % :impl :url) url-to-find))
    first))

(defn remove-connection
  [id]
  (when-let [c (find-connection id)]
    (m/stop c)
    (swap! connections dissoc id)))

(defn remove-all-connections
  []
  (doseq [[id _] @connections]
    (remove-connection id)))

(defn connect
  [url]
  (let [result-chan (chan)]
    (go
     (if-let [c (find-connection-by-url url)]
       (>! result-chan c)
       (let [{:keys [id error] :as c} (<! (create-messenger url))]
         (if (and c (not error)) (swap! connections assoc id c))
         (>! result-chan c))))
    result-chan))
