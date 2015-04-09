(ns rksm.websocket-test.server-test
  (:require [rksm.websocket-test.server :as server]
            [rksm.websocket-test.messenger :as m]
            [rksm.websocket-test.server-client :as client]
            [clojure.test :refer :all]
            [org.httpkit.client :as http-client]
            [clojure.core.async :as async :refer [<!! >!! chan go]]))

(def ^:dynamic *client*)
(def ^:dynamic *server*)

(defn fixture [test]
  (binding [*server* (server/ensure-server! :port 8082)
            *client* (client/ensure-connection! :port 8082)]
    (test)
    (client/stop! *client*) 
    (server/stop-server! *server*)))

(use-fixtures :each fixture)

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(deftest create-a-ws-server

  (testing "server data"
    (is (= [8082 "0.0.0.0"]
           ((juxt server/port server/host) *server*)))
    (is (contains? *server* :id)))

  (testing "simple GET"
    (let [{:keys [body status]} @(http-client/get "http://localhost:8082")]
      (is (= "<p>Page not found.</p>" body))
      (is (= 404 status))))

  (testing "shutdown"
    (is (= *server* (first @server/servers)))
    (server/stop-server! *server*)
    (let [{:keys [body status]} @(http-client/get "http://localhost:8082")]
      (is (nil? body))
      (is (nil? status)))))

(deftest add-a-service
  (let [received (promise)]
    (m/add-service *server* "test-service" (fn [con msg]  (deliver received msg)))
    (m/send *client* {:target (:id *server*) :action "test-service" :data "test"})
    (let [{:keys [data id sender target]} (deref received 200 nil)]
      (is (= "test" data))
      (is (not (nil? id)))
      (is (= (:id *client*) sender))
      (is (= (:id *server*) target)))))

(deftest echo-service-test
  (let [{{data :data} :message}
        (<!! (m/send
              *client*
              {:target (:id *server*) :action "echo" :data "test"}))]
    (is (= "test" data))))

(deftest install-service-test

  (testing "add handler"
    (let [add-answer
          (<!! (m/send
                *client*
                {:target (:id *server*)
                 :action "add-service"
                 :data {:name "adder"
                        :handler (str '(fn [con msg] (rksm.websocket-test.messenger/answer con msg (-> msg :data (+ 1)) false)))}}))]
      (is (nil? (some-> add-answer :message :data :error)))))

  (testing "call handler"
    (let [{{data :data} :message}
          (<!! (m/send
                *client*
                {:target (:id *server*) :action "adder" :data 3}))]
      (is (= 4 data)))))

(deftest streaming-response-test
  (let [handler '(do
                   (require '[rksm.websocket-test.messenger :as m])
                   (fn [con msg]
                     (m/send con (m/prep-answer-msg con msg 1 true))
                     (m/send con (m/prep-answer-msg con msg 2 true))
                     (m/send con (m/prep-answer-msg con msg 3 false))))
        _ (<!! (m/send
                *client*
                {:target (:id *server*)
                 :action "add-service"
                 :data {:name "stream" :handler (str handler)}}))
        recv (m/send *client* {:target (:id *server*) :action "stream"})
        data (loop [respones []]
               (if-let [next (-> (<!! recv) :message :data)]
                 (recur (conj respones next))
                 respones))]
    (is (= [1 2 3] data))))

(deftest register-a-client
  (let [register-msg {:target (:id *server*) :action "register" :data nil}
        reg-result (<!! (m/send *client* register-msg))
        c (server/find-channel (:id *client*))]
    (is (= "OK" (-> reg-result :message :data)))
    (is (not= nil c))))

(deftest msg-server->client
  (<!! (m/send *client* {:target (:id *server*) :action "register"}))
  (m/add-service *client* "add-something"
                   (fn [con msg] (m/answer con msg (+ 23 (:data msg)) false)))
  (let [{{:keys [data error]} :message}
        (<!! (m/send *server* {:target (:id *client*) :action "add-something" :data 2}))]
    (is (nil? (or error (:error data))))
    (is (= 25 data))))

(comment
 (test-ns *ns*)
 (server/stop-all-servers!)
 )
