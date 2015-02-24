(ns rksm.websocket-test.client
  (:require [gniazdo.core :as ws]
            [clojure.string :as s]
            [clojure.data.json :as json]))

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
  [url]
  (let [url (s/replace url #"^http" "ws")]
    (ws/connect url :on-receive #(prn 'received %))))

(defn ws-connect!
  [& {:keys [host port path protocol] :or {host "0.0.0.0" port 8080 path "/ws" protocol "ws"}}]
  (let [url (str protocol "://" host ":" port path)]
    (create-ws-connection url)))

(defonce connections (atom []))

(defn ensure-connection!
  [& opts]
  (let [id (:id (apply hash-map opts))
        c (or (first (filter #(= id (:id %)) @connections))
              {:id (str (java.util.UUID/randomUUID))
               :ws (apply ws-connect! opts)})]
    (swap! connections conj c)
    c))

(defn stop!
  [c]
  (if-let [ws (:ws c)] (ws/close ws))
  (reset! connections (remove #(= c %) @connections)))

(defn stop-all!
  []
  (doseq [c @connections] (stop! c)))

(defn send!
  [connection msg]
  (let [ws (:ws connection)]
    (assert ws)
    (let [stringified (if (string? msg) msg (json/write-str msg))]
      (ws/send-msg ws stringified))))
