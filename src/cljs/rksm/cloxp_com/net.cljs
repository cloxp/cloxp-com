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
  [url impl-state timeout-per-attempt attempt-no max-attempts]
  (let [{:keys [closed?]} @impl-state]
    (if (or (> attempt-no max-attempts) closed?)
      (log "Giving up reconnecting to" url)
      (go
       (log "Attempt" attempt-no "trying to reconnect to..." url)
       (let [{:keys [ws-channel error]} (<! (ws-ch url {:format :json-kw}))]
         (if (or error (nil? ws-channel))
           (do
             (log "Re-connect to" url "failed, trying again...")
             (<! (timeout timeout-per-attempt))
             (reconnect url impl-state timeout-per-attempt (inc attempt-no) max-attempts))
           (do
             (swap! impl-state assoc :ws-chan ws-channel)
             (send-handshake url impl-state (chan)))))))))

(defn- start-receive-loop
  [url impl-state]
  (go-loop []
    (let [{:keys [closed? ws-chan receive-chan]} @impl-state]
      (if closed?
        (do
          (log "Client ended connection to" url)
          (some-> ws-chan close!))
        (let [data (and ws-chan (<! ws-chan))]
          (cond
            (nil? data) (do
                          (log "Server closed connection" url)
                          (swap! impl-state dissoc :ws-chan)
                          (reconnect url impl-state 1000 0 10))
            :default (do
                       (>! receive-chan {:raw-msg (or (:message data) data),
                                         :connection {:channel ws-chan}})
                       (recur))))))))

(defn- send-handshake
  "ws-chan: coming from chord, for sending and receiving
  receive-chan: handed to the messenger, for receiving messages, decoupled from
  chord to allow for transparent reconnects
  result-channel: for when connection is established"
  [url impl-state result-chan]
  (let [{:keys [ws-chan]} @impl-state]
   (go

    ; let the server know who we are
    (>! ws-chan {:action "info" :sender nil})
    (let [{:keys [message error]} (<! ws-chan)
          {{server-id :id} :data} message]
      (log "Created connection to server" url (str "(" server-id ")"))
      (swap! impl-state assoc :server-id server-id)
      (start-receive-loop url impl-state)

      ; for the messenger
      (>! result-chan impl-state)))))

(defn- establish-server-channels
  [url]
  (let [result-chan (chan)]
    (go
     (log "Trying to connect to cloxp server" url)
     (let [{:keys [ws-channel error] :as connect-chan} (<! (ws-ch url {:format :json-kw}))
           impl-state (atom {:receive-chan (chan)
                             :ws-chan ws-channel
                             :closed? false
                             :server-id nil})]
       (if error
         (handle-establish-server-connection-error url error result-chan)
         (send-handshake url impl-state result-chan))))
    result-chan))

(defrecord MessengerImpl [url state]
  m/IReceiver
  (receive-chan [this] (@state :receive-chan))
  (stop-receiver [this]
                 (swap! state assoc :closed? true)
                 (let [{:keys [ws-chan receive-chan]} @state]
                   (some-> ws-chan close!)
                   (close! receive-chan)))
  m/ISender
  (send-message [this con msg]
                (go-loop []
                  (let [{:keys [closed? ws-chan server-id]} @state]
                    (when closed?
                     (throw
                       (js/Error.
                        (str "Messenger to " url" is closed"
                             " (when trying to send " msg ")"))))
                   (if ws-chan
                     (>! ws-chan (merge {:target server-id} msg))
                     (do
                       (<! (timeout 500))
                       (recur)))))))

(defn create-messenger
  [url]
  (let [result-chan (chan)]
    (go
     (let [{:keys [server-id ws-chan receive-chan error] :as state}
           (<! (establish-server-channels url))]
       (if (or error (not state))
         (let [msg (str "Cannot connect to " url ": "
                        (or error "establish server channel failed"))]
           (.error js/console msg)
           (>! result-chan {:error msg}))
         (let [messenger (m/create-messenger
                          "browser-client"
                          (->MessengerImpl url state)
                          (merge
                           (m/default-services)
                           {"eval-js" eval/eval-js-service
                            "load-js" eval/load-js-service}))]
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
