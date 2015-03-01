(ns rksm.websocket-test.com
  (:require [clojure.core.async :as async])
  (:import (java.util UUID)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn send-msg
  [{:keys [id] :as sender} msg]
  (merge {:sender id, :id (str (UUID/randomUUID))} msg))

(defn answer-msg
  [responder {:keys [id action sender] :as msg} data]
  (send-msg responder {:in-response-to id
                       :target sender
                       :action (str action "-response")
                       :data data}))

(defn stringify-for-send
  [{:keys [id] :as sender} msg]
  (let [msg (merge {:sender id, :id (str (UUID/randomUUID))} msg)
        stringified (json/write-str msg)]
    stringified))

(defn stringify-for-answer
  [responder {:keys [id action sender] :as msg} data]
  (stringify-for-send responder {:in-response-to id
                                 :target sender
                                 :action (str action "-response")
                                 :data data}))

(defn relay-answers
  [id channel]
  (async/>!! channel ))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn send! 
  [send-fn {id :id, :as sender} msg]
  (let [msg (send-msg sender msg)]
    (send-fn msg)
    ; (relay-answers id c)
    msg
    ))

(defn answer! 
  [send-fn responder msg data]
  (let [msg (answer-msg responder msg data)]
    (send-fn msg)
    msg))