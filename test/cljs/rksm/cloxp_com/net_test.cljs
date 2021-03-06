(ns ^{:doc "See rksm.cloxp-com.browser-client-test for how running these tests as
      part of the server tests"} rksm.cloxp-com.net-test
  (:require [rksm.cloxp-com.net :as net]
            [rksm.cloxp-com.messenger :as m]
            [cljs.core.async :refer [<! >! put! close! chan pub sub timeout]]
            [rksm.cloxp-com.async-util :refer [join]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cemerick.cljs.test :refer [is deftest testing use-fixtures done]]))

(def port 8084)

(def url (str "ws://localhost:" port "/ws"))

(defn net-cleanup [t]
  (t)
  (net/remove-all-connections))

(use-fixtures :each net-cleanup)

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(deftest ^:async simple-ws-connect-test
  (go
   (println "connecting" url)
   (let [{id :id, :as c} (<! (net/connect url))]
     (is (string? id))
     (go
      (let [{:keys [message error] :as answer}
            (<! (m/send c {:action "echo", :data "test"}))]
        (is (nil? error) (str error))
        (is (= (:data message) "test"))
        (net/remove-all-connections) ; FIXME why does fixure not work???
        (done))))))

(deftest ^:async streaming-response-test
  (go
   (let [{id :id, :as c} (<! (net/connect url))
         stream-handler '(do
                           (require '[rksm.cloxp-com.messenger :as m])
                           (fn [con msg]
                             (m/send con (m/prep-answer-msg con msg 1 true))
                             (m/send con (m/prep-answer-msg con msg 2 true))
                             (m/send con (m/prep-answer-msg con msg 3 false))))
         add-service-msg {:action "add-service"
                          :data {:name "stream" :handler (str stream-handler)}}]

     (testing "add-service"
       (let [add-service-answer (<! (m/send c add-service-msg))]
         (is (= "OK" (-> add-service-answer :message :data))))
       (testing "invoke added service"
         (let [result (<! (join (m/send c {:action "stream" :data nil})))]
           (net/remove-all-connections) ; FIXME why does fixure not work???
           (done)))))))

(deftest ^:async server->client-send
  (go
   (let [send-new-message-handler
         '(do
            (require '[rksm.cloxp-com.messenger :as m])
            (require '[clojure.core.async :as async])
            (fn [con {:keys [data] :as msg}]
              (m/answer con msg :OK false)
              (let [{:keys [wait msg-to-send]} data]
                (async/go
                 (async/<! (async/timeout wait))
                 (println "answer from browser:"
                          (async/<!
                           (m/send
                            con (m/prep-send-msg con msg-to-send false))))))))
         {id :id, :as c} (<! (net/connect url))
         add-service-msg {:action "add-service"
                          :data {:name "send-new-message"
                                 :handler (str send-new-message-handler)}}
         msg-to-send {:target (:id c) :action "echo" :data "from-server"}
         send-new-message-msg {:action "send-new-message" :data {:wait 300 :msg-to-send msg-to-send}}]

     (testing "add-server"
       (let [{{add-status :data} :message} (<! (m/send c add-service-msg))]
         (is (= "OK" add-status))

         (testing "invoke server"
           (let [{{send-status :data} :message} (<! (m/send c send-new-message-msg))]
             (is (= "OK" send-status))
             (<! (timeout 1000))
             (net/remove-all-connections) ; FIXME why does fixure not work???
             (done))))))))

(deftest ^:async reconnect-when-server-drops-connection
  (go
   (let [{id :id, :as c} (<! (net/connect url))
         msg-1 {:action "close-connection" :data {:id id}}
         msg-2 {:action "echo" :data "client send it"}]
     
     (let [_ (<! (m/send c {:action "register" :data {:id id}}))
           {{msg-1-answer :data} :message :as msg} (<! (m/send c msg-1))
           _ (<! (timeout 200))
           {{msg-2-answer :data} :message} (<! (m/send c msg-2))]
       (is (= "OK" msg-1-answer))
       (is (= "client send it" msg-2-answer))
       (net/remove-all-connections)
       (done)))))
