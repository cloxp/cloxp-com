(ns rksm.websocket-test.com
  (:refer-clojure :exclude [send])
  (:require #+cljs [cljs-uuid-utils :as uuid]
            #+clj [clojure.data.json :as json])
  #+clj (:import (java.util UUID)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn- uuid
  []
  #+clj (str (UUID/randomUUID))
  #+cljs (uuid/uuid-string (uuid/make-random-uuid)))

(defn json->clj
  [string]
  #+cljs (js->clj (.parse js/JSON string))
  #+clj (json/read-str string :key-fn keyword))

(defn clj->json
  [obj]
  #+cljs (.stringify js/JSON (clj->js obj))
  #+clj (json/write-str obj))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defprotocol IConnection
  (send [this msg])
  (answer [this msg data])
  (handle-request [this channel raw-data])
  (handle-response-request [this msg])
  (register-connection [this id channel])
  (add-service [this name handler-fn])
  (lookup-handler [this action]))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn send-msg
  [{:keys [id] :as sender} msg expect-more-responses]
  (merge {:sender id,
          :id (uuid),
          :expect-more-responses (or (:expect-more-responses msg)
                                     expect-more-responses)}
         msg))

(defn answer-msg
  [responder {:keys [id action sender] :as msg} data expect-more-responses]
  (send-msg responder {:in-response-to id
                       :target sender
                       :action (str action "-response")
                       :data data}
            expect-more-responses))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn answer-message-not-understood
  [receiver msg]
  (answer receiver msg {:error "messageNotUnderstood"}))

(defn answer-with-info
  [{id :id, :as receiver} msg]
  (answer receiver msg {:id id}))

(defn echo-service-handler
  [receiver msg]
  (answer receiver msg (:data msg)))

(defn registry-handler
  [{:keys [channel] :as receiver} {:keys [sender] :as msg}]
  (register-connection receiver sender channel)
  (answer receiver msg "OK"))

(defn add-service-handler
  [receiver {{:keys [name handler]} :data, :as msg}]
  (println "adding service " name)
  (add-service receiver name (eval (read-string handler)))
  (answer receiver msg "OK"))

(defn default-handle-request
  [receiver channel data]
  ; FIXME: validate data!
  (let [msg (if (string? data) (json->clj data) data)
        {:keys [target action]} msg
        handler (lookup-handler receiver action)
        connection (assoc receiver :channel channel)]
    (cond
      (contains? msg :in-response-to) (handle-response-request receiver msg)
      (= action "info") (answer-with-info connection msg)
      (not= (:id receiver) target) (do
                                     (println "message" action "has not target id")
                                     (answer-message-not-understood connection msg))
      (nil? handler) (answer-message-not-understood connection msg)
      :default (try (handler connection msg)
                 (catch Exception e
                   (do
                     (println "Error handling service request " name ":\n" e)
                     (answer connection msg {:error (str e)})))))))

(defn default-services
  []
  {"echo" echo-service-handler
   "add-service" add-service-handler
   "register" registry-handler})
