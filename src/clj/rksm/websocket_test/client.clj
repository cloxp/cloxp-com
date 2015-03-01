(ns rksm.websocket-test.client
  (:require [gniazdo.core :as ws]
            [clojure.string :as s]
            [clojure.data.json :as json]
            [rksm.websocket-test.com :as com]
            [clojure.core.async :as async])
  (:import (java.util UUID)))

(defonce connections (atom {}))

(defn find-connection
  [id]
  (get @connections id))

; methods:
; (ws/connect [url & options])
; (ws/send-msg [conn message])
; (ws/close [conn])
; (ws/client [] [uri])

(defn create-ws-connection
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
  [url on-receive]
  (let [url (s/replace url #"^http" "ws")]
    (ws/connect url
                :on-receive on-receive
                :headers {"sec-websocket-protocol" ["lively-json"]})))

(defn ws-connect!
  [{:keys [host port path protocol on-receive],
    :or {host "0.0.0.0", port 8080, path "/ws", protocol "ws"}}]
  (let [url (str protocol "://" host ":" port path)]
    (create-ws-connection url on-receive)))

(declare receive-msg)

(defn ensure-connection!
  [& {:keys [id], :as opts}]
  (if-let [c (find-connection id)]
    c
    (let [id (str (UUID/randomUUID))
          on-receive (fn [msg] (if-let [c (find-connection id)] (receive-msg c msg)))
          c {:id id, :ws (ws-connect! (assoc opts :on-receive on-receive))}]
      (swap! connections assoc id c)
      c)))

(defn stop!
  [{:keys [ws id] :as con}]
  (if ws (ws/close ws))
  (swap! connections dissoc id))

(defn stop-all!
  []
  (doseq [[_ c] @connections] (stop! c)))

(defonce receive-channels (atom {}))

(defn remove-channel!
  [id]
  (when-let [c (get @receive-channels id)]
    (async/close! c)
    (swap! receive-channels dissoc id)))

(defn remove-all-channels!
  []
  (doseq [[id _] @receive-channels]
    (remove-channel! id)))

(defn receive-msg
  "Will
  1. parse msg-string into map
  2. lookup channels registered under the in-response-to message id
  3. if expect-more-responses is falsish remove and close channel"
  [{sender :id, :as con} msg-string]
  (try (let [{:keys [in-response-to expect-more-responses], :as msg}
             (json/read-str msg-string :key-fn keyword)]
         (when-let [c (get @receive-channels in-response-to)]
           (async/>!! c msg)
           (if-not expect-more-responses
             (remove-channel! in-response-to))))
    (catch Exception e (println "Error receiving message " msg-string))))

(defn send!
  [{ws :ws, :as con} msg]
  (let [c (async/chan)
        {msg-id :id} (com/send! #(ws/send-msg ws (json/write-str %)) con msg)]
    (swap! receive-channels assoc msg-id c)
    c))

(defn answer!
 [{ws :ws, :as con} msg data]
  (com/answer! #(ws/send-msg ws (json/write-str %)) con msg data))
