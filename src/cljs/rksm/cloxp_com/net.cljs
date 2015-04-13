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
  [url error result-channel]
  (let [reason (str "client could not establish connection to server " url)
        error (merge-with str error {:reason reason})]
    (go 
     (>! result-channel {:error error})
     (close! result-channel))))

(defn- send-handshake
  [ws-channel result-channel]
  (go
   (>! ws-channel {:action "info" :sender nil})
   (let [{:keys [message error]} (<! ws-channel)
         {{server-id :id} :data} message
         receive-channel (chan)]
     (go-loop []
       (if-let [data (<! ws-channel)]
         (do
           (>! receive-channel {:raw-msg (or (:message data) data),
                                :connection {:channel ws-channel}})
           (recur))
         (do (close! ws-channel) (close! receive-channel))))
     (>! result-channel {:tracker-id server-id
                         :send-channel ws-channel
                         :receive-channel receive-channel})
     (close! result-channel))))

(defn- establish-server-channel
  [url]
  (let [result-chan (chan)]
    (go
     (let [{:keys [ws-channel error] :as connect-chan} (<! (ws-ch url {:format :json-kw}))]
       (if error
         (handle-establish-server-connection-error url error result-chan)
         (send-handshake ws-channel result-chan))))
    result-chan))

(defrecord MessengerImpl [url tracker-id send-channel receive-channel]
  m/IReceiver
  (receive-chan [this] receive-channel)
  (stop-receiver [this]
                 (close! send-channel)
                 (close! receive-channel))
  m/ISender
  (send-message [this msg]
                (let [msg (merge {:target tracker-id} msg)]
                  (go (>! send-channel msg)))))

(defn create-messenger
  [url]
  (let [result-chan (chan)]
    (go
     (let [{:keys [tracker-id send-channel receive-channel error] :as connected}
           (<! (establish-server-channel url))]
       (if (or error (not connected))
         (let [msg (str "Cannot connect to " url ": "
                        (or error "establish server channel failed"))]
           (.error js/console msg)
           (>! result-chan {:error msg}))
         (let [messenger (m/create-messenger
                          "browser-client"
                          (->MessengerImpl url tracker-id send-channel receive-channel))]
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
