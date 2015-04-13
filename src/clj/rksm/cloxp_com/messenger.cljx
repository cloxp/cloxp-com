(ns rksm.cloxp-com.messenger
  (:refer-clojure :exclude [send])
  (:require #+clj [clojure.core.async :refer [>! <! chan go go-loop sub pub close! timeout]]
            #+cljs [cljs.core.async :refer [<! >! put! close! chan sub pub timeout]]
            #+cljs [cljs-uuid-utils :as uuid]
            #+clj [clojure.data.json :as json])
  #+cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  #+clj (:import (java.util UUID)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(declare default-services handle-incoming-message)

(def ^:dynamic *current-connection*)

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
  (send-message [this con msg]))

#+clj (println "defprotocol IMessenger")

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
  (send [this msg]
        (let [{msg-id :id, :as msg} (prep-send-msg this msg false)
              sub-chan (sub (:pub responses) msg-id (chan))
              ;   sub-chan (receive-msg-sub-chan impl msg-id)
              client-chan (chan)
              close (fn [] (close! sub-chan) (close! client-chan))]
          
          ; real send
          (send-message impl *current-connection* msg)
          
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
  
  (answer [this msg data more-answers?]
          (let [msg (prep-answer-msg this msg data more-answers?)]
            (send-message impl *current-connection* msg)
            msg))
  
  (add-service [this name handler-fn]
               (swap! services assoc name handler-fn))
  
  (lookup-handler [this action]
                  (let [handler (some-> services deref (get action))]
                    (if #+clj (var? handler) #+cljs false
                      (deref handler)
                      handler)))
  
  (stop [this] (stop-receiver impl) (-> responses :chan close!)))

(defn create-messenger
  [name impl & [services]]
  (let [id (uuid)
        response-chan (chan)
        messenger (map->Messenger {:name name
                                   :id id
                                   :services (atom (or services (default-services)))
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
  (go 
   (<! (timeout 1000))
   (answer receiver msg (:data msg) false)))

(defn- add-service-handler
  [receiver {{:keys [name handler]} :data, :as msg}]
  #+clj (do 
          (add-service receiver name (eval (read-string handler)))
          (answer receiver msg "OK" false))
  #+cljs (answer receiver msg
                 "add-service currently not supported for cljs" false))

(defn default-services
  []
  {"echo" #'echo-service-handler
   "add-service" #'add-service-handler
   "info" #'info-service-handler})

(defn- handle-response-message
  [{:keys [responses] :as messenger} con msg]
  (go (some-> messenger
        :responses :chan
        (>! {:message msg :connection con}))))

(defn- handle-incoming-message
  [messenger connection raw-data]
  ; FIXME: validate raw-data!
  (binding [*current-connection* connection]
    (let [msg (if (string? raw-data) (json->clj raw-data) raw-data)
         {:keys [target action]} msg
         handler (lookup-handler messenger action)]
     (cond
       (contains? msg :in-response-to) (handle-response-message messenger connection msg)
       (and target (not= (:id messenger) target)) (handle-target-not-found messenger msg)
       (nil? handler) (answer-message-not-understood messenger msg)
       :default (try (handler messenger msg)
                  (catch #+clj Exception #+cljs js/Error e
                    (do
                      (println "Error handling service request " name ":\n" e)
                      (answer messenger msg {:error (str e)} false))))))))
