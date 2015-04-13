(ns rksm.cloxp-com.server-client
  (:refer-clojure :exclude [send])
  (:require [gniazdo.core :as ws]
            [clojure.string :as s]
            [clojure.data.json :as json]
            [rksm.cloxp-com.messenger :as m]
            [clojure.core.async :as async :refer [>!! >!! <! <!! chan go go-loop sub pub close!]]))

(defrecord MessengerImpl [url sock ws-chan]
  m/IReceiver
  (receive-chan [this] ws-chan)
  (stop-receiver [this] (close! ws-chan) (.close sock))
  m/ISender
  (send-message [this _ msg] (ws/send-msg sock (json/write-str msg))))


(defn create-messenger
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
  (let [ws-chan (chan)
        sock (ws/connect
              url
              :on-receive (fn [msg-string] (>!! ws-chan {:raw-msg msg-string
                                                         :connection ws-chan}))
              :on-error (fn [err] (>!! ws-chan {:error err}))
              :on-close (fn [code reason]
                          (>!! ws-chan {:closed {:status code :reason reason}})
                          (close! ws-chan))
              :headers {"sec-websocket-protocol" ["lively-json"]})
        sender-receiver (->MessengerImpl url sock ws-chan)]
    (m/create-messenger "server-client" sender-receiver)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce connections (atom {}))

(defn opts->url
  [{:keys [id host port path protocol],
    :or {host "0.0.0.0", port 8080, path "/ws", protocol "ws"}
    :as opts}]
  (let [url (if (string? opts) opts (str protocol "://" host ":" port path))]
    (s/replace url #"^http" "ws")))

(defn find-connection
  [id]
  (get @connections id))

(defn find-connection-by-url
  [url]
  (first (filter #(= url (-> % :impl :url)) @connections)))

(defn ensure-connection!
  [& {:keys [url] :as url-or-opts}]
  (let [url (or url (opts->url url-or-opts))]
    (if-let [c (some-> url find-connection-by-url)]
      c
      (let [{:keys [id] :as c} (create-messenger url)]
        (swap! connections assoc id c)
        c))))

(defn stop!
  [{:keys [id] :as con}]
  (m/stop con)
  (swap! connections dissoc id))

(defn stop-all!
  []
  (doseq [[_ c] @connections] (stop! c)))
