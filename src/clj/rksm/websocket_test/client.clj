(ns rksm.websocket-test.client
  (:refer-clojure :exclude [send])
  (:require [gniazdo.core :as ws]
            [clojure.string :as s]
            [clojure.data.json :as json]
            [rksm.websocket-test.com :as com]
            [clojure.core.async :as async :refer [>! >!! <! <!! chan go go-loop sub pub close!]])
  (:import (java.util UUID)))

(declare send!)

(defprotocol IConnection
  (send [this msg]))

(defrecord Connection [url id tracker-id ws]
  IConnection
  (send [this msg] (send! this msg)))

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

(defn- receive-msg
  "Will
  1. parse msg-string into map
  2. lookup channels registered under the in-response-to message id
  3. if expect-more-responses is falsish remove and close channel"
  [ws-chan msg-string]
  (let [msg (json/read-str msg-string :key-fn keyword)]
    (>!! ws-chan msg))
;   (try (let [{:keys [in-response-to expect-more-responses], :as msg}
;              (json/read-str msg-string :key-fn keyword)]
;          (when-let [c (get @receive-channels in-response-to)]
;           (>!! c msg)
;           (if-not expect-more-responses
;              (remove-channel! in-response-to))))
;     (catch Exception e (println "Error receiving message " msg-string)))
  )

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
  [url ws-chan]
  (let [url (s/replace url #"^http" "ws")]
    (ws/connect url
                :on-receive (fn [msg-string]
                              (let [msg (json/read-str msg-string :key-fn keyword)]
                                (>!! ws-chan {:message msg})))
                :on-error (fn [err] (>!! ws-chan {:error err}))
                :on-close (fn [err] (close! ws-chan))
                :headers {"sec-websocket-protocol" ["lively-json"]})))

(defn- ws-connect!
  [{:keys [host port path protocol],
    :or {host "0.0.0.0", port 8080, path "/ws", protocol "ws"}}]
  (let [url (str protocol "://" host ":" port path)
        ws-chan (chan)
        recv-chan (pub ws-chan (comp :in-response-to :message))]
    {:socket (create-ws-connection url ws-chan)
     :chan ws-chan
     :receiver recv-chan}))

(defn ensure-connection!
  [& {:keys [id], :as opts}]
  (if-let [c (find-connection id)]
    c
    (let [id (str (UUID/randomUUID))
          c {:id id, :ws (ws-connect! opts)}]
      (swap! connections assoc id c)
      c)))

(defn stop!
  [{:keys [ws id] :as con}]
  (some-> ws :socket ws/close)
  (swap! connections dissoc id))

(defn stop-all!
  []
  (doseq [[_ c] @connections] (stop! c)))

(defn send!
  "sends message via websocket and returns a channel that will receive answer
  messages matching the message id of the sent message. client facing channel
  will close when there are no more answers to be expected."
  [{ws :ws, :as con} msg]
  (let [{msg-id :id} (com/send! #(ws/send-msg (:socket ws) (json/write-str %)) con msg)
        client-chan (chan)
        sub-chan (sub (:receiver ws) msg-id (chan))
        close (fn [] (close! sub-chan) (close! client-chan))]
    (go-loop []
      (let [{:keys [message error] :as msg} (<! sub-chan)]
        (if msg (>! client-chan msg))
        (cond
          (nil? msg) (close)
          error (close)
          (not (:expect-more-responses message)) (close)
          :default (recur))))
    client-chan))

(defn answer!
 [{ws :ws, :as con} msg data]
  (com/answer! #(ws/send-msg (:socket ws) (json/write-str %)) con msg data))
