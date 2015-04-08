(ns rksm.websocket-test.server-client
  (:refer-clojure :exclude [send])
  (:require [gniazdo.core :as ws]
            [clojure.string :as s]
            [clojure.data.json :as json]
            [rksm.websocket-test.com :as com]
            [clojure.core.async :as async :refer [>! >!! <! <!! chan go go-loop sub pub close!]])
  (:import (java.util UUID)))

(declare send! answer! add-service!)

(defrecord Connection [id ws receive services]
  com/IConnection
  (send [this msg] (send! this msg))
  (answer [this msg data] (answer! this msg data))
  (handle-request [this channel raw-data] (com/default-handle-request this channel raw-data))
  (handle-response-request [this msg] 
                           (if-let [recv-chan (some-> receive :chan)]
                             (>!! recv-chan {:message msg})
                             (println "Cannot receive message in server client" (:id this) "b/c websocket is closed")))
  (register-connection [this id channel] (println "register-connection in server client not yet implemented"))
  (add-service [this name handler-fn] (add-service! this name handler-fn))
  (lookup-handler [this action] (get-in this [:services action])))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce connections (atom {}))

(defn find-connection
  [id]
  (get @connections id))

; methods:
; (ws/connect [url & options])
; (ws/send-msg [conn message])
; (ws/close [conn])
; (ws/client [] [uri])

(defn- create-ws-connection
  "options to ws/connect:
  :on-connect – a unary function called after the connection has been
  established. The handler is a Session instance.
  :on-receive – a unary function called when a message is received. The
  argument is a received String.
  :on-binary – a ternary function called when a message is received. Arguments
  are the raw payload byte array, and two integers: the offset in the array
  where the data starts and the length of the payload.
  :on-error – a unary function called on in case of errors. The argument is a
  Throwable describing the error.
  :on-close – a binary function called when the connection is closed. Arguments
  are an int status code and a String description of reason.
  :headers – a map of string keys and either string or string seq values to be
  used as headers for the initial websocket connection request.
  :client – an optional WebSocketClient instance to be used for connection
  establishment; by default, a new one is created internally on each call.
  gniazdo.core/connect returns an opaque representation of the connection."
  [id url ws-chan]
  (let [url (s/replace url #"^http" "ws")]
    (ws/connect url
                :on-receive (fn [msg-string] (com/handle-request (find-connection id) ws-chan msg-string))
                :on-error (fn [err] (>!! ws-chan {:error err}))
                :on-close (fn [err _] (close! ws-chan))
                :headers {"sec-websocket-protocol" ["lively-json"]})))

(defn- ws-connect!
  [{:keys [id host port path protocol],
    :or {host "0.0.0.0", port 8080, path "/ws", protocol "ws"}}]
  (let [url (str protocol "://" host ":" port path)
        ws-chan (chan)]
    {:socket (create-ws-connection id url ws-chan)
     :chan ws-chan
     :pub (pub ws-chan (comp :in-response-to :message))}))

(defn ensure-connection!
  [& {:keys [id], :as opts}]
  (if-let [c (find-connection id)]
    c
    (let [id (str (UUID/randomUUID))
          {:keys [chan pub] :as ws} (ws-connect! (assoc opts :id id))
          c (map->Connection {:id id,
                              :ws ws
                              :receive {:chan chan :pub pub}
                              :services (com/default-services)})]
      (swap! connections assoc id c)
      c)))

(defn stop!
  [{:keys [ws id] :as con}]
  (some-> ws :socket ws/close)
  (some-> ws :chan close!)
  (swap! connections dissoc id))

(defn stop-all!
  []
  (doseq [[_ c] @connections] (stop! c)))

(defn- send!
  "sends message via websocket and returns a channel that will receive answer
  messages matching the message id of the sent message. client facing channel
  will close when there are no more answers to be expected."
  [{:keys [receive ws], :as con} msg
   & {:keys [expect-more-responses], :or {expect-more-responses false}}]
  (let [{msg-id :id, :as msg} (com/send-msg con msg expect-more-responses)
        sub-chan (sub (:pub receive) msg-id (chan))
        client-chan (chan)
        close (fn [] (close! sub-chan) (close! client-chan))]
    
    ; real send
    (ws/send-msg (:socket ws) (json/write-str msg))
    
    ; wait for responses
    (go-loop []
      (let [{:keys [message error] :as msg} (<! sub-chan)]
        (if msg (>! client-chan msg))
        (cond
          (nil? msg) (close)
          error (close)
          (not (:expect-more-responses message)) (close)
          :default (recur))))
    client-chan))

(defn- answer!
  [{ws :ws, :as con} msg data
   & {:keys [expect-more-responses], :or {expect-more-responses false}}]
  (let [msg (com/answer-msg con msg data expect-more-responses)]
    (ws/send-msg (:socket ws) (json/write-str msg))
    msg))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn- add-service!
  [{:keys [id] :as client} name handler-fn]
  (let [client (map->Connection
                 (update-in client [:services]
                            assoc name handler-fn))]
    [client id name]
    (swap! connections assoc id client)))
