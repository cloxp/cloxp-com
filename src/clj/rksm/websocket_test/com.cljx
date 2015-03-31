(ns rksm.websocket-test.com
  #+cljs (:require [cljs-uuid-utils :as uuid])
  #+clj (:import (java.util UUID)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn- uuid
  []
  #+clj (str (UUID/randomUUID))
  #+cljs (uuid/uuid-string (uuid/make-random-uuid)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

; (defprotocol IConnection
;   (send [this msg]))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn send-msg
  [{:keys [id] :as sender} msg expect-more-responses]
  (merge {:sender id,
          :id (uuid),
          :expect-more-responses expect-more-responses}
         msg))

(defn answer-msg
  [responder {:keys [id action sender] :as msg} data expect-more-responses]
  (send-msg responder {:in-response-to id
                       :target sender
                       :action (str action "-response")
                       :data data}
            expect-more-responses))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn send! 
  [send-fn {id :id, :as sender} msg
   & {:keys [expect-more-responses], :or {expect-more-responses false}}]
  (let [msg (send-msg sender msg expect-more-responses)]
    (send-fn msg) msg))

(defn answer! 
  [send-fn responder msg data
   & {:keys [expect-more-responses], :or {expect-more-responses false}}]
  (let [msg (answer-msg responder msg data expect-more-responses)]
    (send-fn msg)
    msg))
