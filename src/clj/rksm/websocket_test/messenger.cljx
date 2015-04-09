(ns rksm.websocket-test.messenger
  (:refer-clojure :exclude [send])
  (:require #+clj [clojure.core.async :refer [>! <! chan go go-loop sub pub close!]]
            #+cljs [cljs.core.async :refer [<! >! put! close! chan sub pub]]
            #+cljs [cljs-uuid-utils :as uuid]
            #+clj [clojure.data.json :as json])
  #+cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  #+clj (:import (java.util UUID)))

(declare default-services handle-incoming-message)

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
; messages

(defn prep-send-msg
  [{:keys [id] :as sender} msg expect-more?]
  (cond->> msg
    true (merge {:sender id, :id (uuid)})
    expect-more? (merge {:expect-more-responses true})))

(defn prep-answer-msg
  [responder {:keys [id action sender] :as msg} data expect-more?]
  (prep-send-msg responder {:in-response-to id
                            :target sender
                            :action (str action "-response")
                            :data data}
                 expect-more?))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; interfaces

(defprotocol IReceiver
  (receive-chan [this])
  (stop-receiver [this]))

(defprotocol ISender
  (send-message [this msg]))

(defprotocol IMessenger
  (send [this msg])
  (answer [this msg data more-answers?])
  (add-service [this name handler-fn])
  (lookup-handler [this action])
  (stop [this]))

(defrecord Messenger [name id services responses impl]
  Object
  (toString [this] (str "<" name ":" (.substring id 0 5) "...>"))

  IMessenger
  (send [{:keys [impl], :as this} msg]
        (let [{msg-id :id, :as msg} (prep-send-msg this msg false)
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
          (let [msg (prep-answer-msg this msg data more-answers?)]
            (send-message impl msg)
            msg))
  
  (add-service [{:keys [services] :as this} name handler-fn]
               (swap! services assoc name handler-fn))
  
  (lookup-handler [{:keys [services] :as this} action] (some-> services deref (get action)))
  
  (stop [this] (stop-receiver impl) (-> responses :chan close!)))

(defn create-messenger
  [name impl]
  (let [id (uuid)
        response-chan (chan)
        messenger (map->Messenger {:name name
                                   :id id
                                   :services (atom (default-services))
                                   :responses {:chan response-chan
                                               :pub (pub response-chan (comp :in-response-to :message))}
                                   :impl impl})]
    (go-loop []
      (let [{:keys [raw-msg connection closed error] :as received} (<! (receive-chan impl))]
        (cond
          (nil? received) (do
                            ; (println "Receiver channel closed! Closing messenger " id)
                            (stop messenger))
          closed (do
                ;   (println "Messenger" id "closed:" closed)
                   (stop messenger))
          error (do
                  (println "Messenger" id "got error while receiving messenges:" error)
                  (recur))
          :default (do
                     (handle-incoming-message messenger connection raw-msg)
                     (recur)))))
    messenger))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; services and message handling
; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn answer-message-not-understood
  [receiver msg]
  (answer receiver msg {:error "messageNotUnderstood"} false))

(defn- handle-target-not-found
  [receiver {:keys [target action] :as msg}]
  (let [err (str "cannot forward message " action " to target " target)]
    (println err)
    (answer receiver msg {:error err} false)))

(defn- info-service-handler
  [{id :id, :as receiver} msg]
  (answer receiver msg
          {:id id,
           :services (-> receiver :services deref keys)}
          false))

(defn- echo-service-handler
  [receiver msg]
  (answer receiver msg (:data msg) false))

(defn- add-service-handler
  [receiver {{:keys [name handler]} :data, :as msg}]
  (add-service receiver name (eval (read-string handler)))
  (answer receiver msg "OK" false))

(defn default-services
  []
  {"echo" echo-service-handler
   "add-service" add-service-handler
   "info" info-service-handler})

(defn- handle-response-message 
  [{:keys [responses] :as messenger} msg] 
  (go (some-> messenger :responses :chan (>! {:message msg})))
  #_(println "Messenger" (:id messenger) 
             "cannot receive message b/c receive channel is unavailable"))

(defn- handle-incoming-message
  [messenger connection raw-data]
  ; FIXME: validate raw-data!
  (let [msg (if (string? raw-data) (json->clj raw-data) raw-data)
        {:keys [target action]} msg
        handler (lookup-handler messenger action)
        ; FIXME make dynamic var for current connection?
        messenger (update-in messenger [:impl] assoc :connection connection)]
    (cond
      (contains? msg :in-response-to) (handle-response-message messenger msg)
      (and target (not= (:id messenger) target)) (handle-target-not-found messenger msg)
      (nil? handler) (answer-message-not-understood messenger msg)
      :default (try (handler messenger msg)
                 (catch #+clj Exception #+cljs js/Error e
                   (do
                     (println "Error handling service request " name ":\n" e)
                     (answer messenger msg {:error (str e)} false)))))))
