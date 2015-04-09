(ns rksm.websocket-test.messenger
  (:refer-clojure :exclude [send])
  (:require [rksm.websocket-test.com :as com]
            [clojure.core.async :refer [>! <! chan go go-loop sub pub close!]]
            #+cljs [cljs-uuid-utils :as uuid]
            #+clj [clojure.data.json :as json])
  #+clj (:import (java.util UUID)))

(declare default-services)

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; helper

(defn- uuid
  []
  #+clj (str (UUID/randomUUID))
  #+cljs (uuid/uuid-string (uuid/make-random-uuid)))


(defn- json->clj
  [string]
  #+cljs (js->clj (.parse js/JSON string))
  #+clj (json/read-str string :key-fn keyword))

(defn- clj->json
  [obj]
  #+cljs (.stringify js/JSON (clj->js obj))
  #+clj (json/write-str obj))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; interfaces

(defprotocol IReceiver
  (receive-chan [this]))

(defprotocol ISender
  (send-message [this msg]))

(defprotocol IMessenger
  (send [this msg])
  (answer [this msg data more-answers?])
  (add-service [this name handler-fn])
  (lookup-handler [this action]))

(defrecord Messenger [id services responses impl]
  IMessenger
  
  (send [{:keys [impl], :as this} msg]
        (let [{msg-id :id, :as msg} (com/send-msg this msg false)
              sub-chan (sub (:pub responses) msg-id (chan))
            ;   sub-chan (receive-msg-sub-chan impl msg-id)
              client-chan (chan)
              close (fn [] (close! sub-chan) (close! client-chan))]
          
          ; real send
          (send-message impl msg)
          
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
  
  (answer [{:keys [impl], :as this} msg data more-answers?]
          (let [msg (com/answer-msg this msg data more-answers?)]
            (send-message impl msg)
            msg))
  
  (add-service [{:keys [services] :as this} name handler-fn]
               (swap! services assoc name handler-fn))
  
  (lookup-handler [{:keys [services] :as this} action] (some-> services deref (get action))))

(defn create-messenger
  [impl]
  (let [id (uuid)
        response-chan (chan)
        messenger (map->Messenger {:id id
                                   :services (atom (default-services))
                                   :responses {:chan response-chan
                                               :pub (pub response-chan (comp :in-response-to :message))}
                                   :impl impl})]
    (go-loop []
      (let [{:keys [raw-msg connection closed error] :as received} (<! (receive-chan impl))]
        [raw-msg connection closed error received]
        (cond
          (nil? received) (do
                            (println "Receiver channel closed! Closing messenger " id)
                            (close! response-chan))
          closed (do
                ;   (println "Messenger" id "closed:" closed)
                   (close! response-chan))
          error (do
                  (println "Messenger" id "got error while receiving messenges:" error)
                  (recur))
          :default (do
                     (handle-incoming-message messenger connection raw-msg)
                     (recur)))))
    messenger))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; services
; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn answer-message-not-understood
  [receiver msg]
  (answer receiver msg {:error "messageNotUnderstood"} false))

(defn answer-with-info
  [{id :id, :as receiver} msg]
  (answer receiver msg {:id id} false))

(defn echo-service-handler
  [receiver msg]
  (answer receiver msg (:data msg) false))

#_(defn registry-handler
  [{:keys [connection] :as receiver} {:keys [sender] :as msg}]
  (register-connection receiver sender connection)
  (answer receiver msg "OK" false))

(defn add-service-handler
  [receiver {{:keys [name handler]} :data, :as msg}]
  (add-service receiver name (eval (read-string handler)))
  (answer receiver msg "OK" false))

(defn default-services
  []
  {"echo" echo-service-handler
   "add-service" add-service-handler
;   "register" registry-handler
   })


; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; handling incoming messanges
; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn handle-response-message 
 [{:keys [responses] :as messenger} msg] 
  (go (>! (:chan responses) {:message msg}))
  #_(println "Messenger" (:id messenger) 
           "cannot receive message b/c receive channel is unavailable"))

(defn handle-incoming-message
  [messenger connection raw-data]
  ; FIXME: validate raw-data!
  (let [msg (if (string? raw-data) (json->clj raw-data) raw-data)
        {:keys [target action]} msg
        handler (lookup-handler messenger action)
        messenger (assoc messenger :connection connection)]
    (cond
      (contains? msg :in-response-to) (handle-response-message messenger msg)
      (= action "info") (answer-with-info messenger msg)
      (not= (:id messenger) target) (do
                                      (println "message" action "has not target id")
                                      (answer-message-not-understood messenger msg))
      (nil? handler) (answer-message-not-understood messenger msg)
      :default (try (handler messenger msg)
                 (catch Exception e
                   (do
                     (println "Error handling service request " name ":\n" e)
                     (answer messenger msg {:error (str e)} false)))))))