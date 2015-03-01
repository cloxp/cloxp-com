(ns rksm.websocket-test.server-test
  (:require [rksm.websocket-test.server :as server]
            [clojure.test :refer :all]
            [rksm.websocket-test.client :as client]
            [org.httpkit.client :as http-client]
            [clojure.core.async :as async]))

; (require '[rksm.websocket-test.client :as client] :reload)

(def test-servers (atom []))

(defn fixture [test]
  (test)
  (do
    (doseq [s @server/servers]
      (server/stop-server! s)
      (client/stop-all!))
    ; (swap! servers empty)
    ))

(use-fixtures :each fixture)

(deftest create-a-ws-server

  (let [{:keys [port host id] :as s} (server/ensure-server! :port 8082)]

    (testing "server data"
      (is (= 8082 port))
      (is (= "0.0.0.0" host))
      (is (not (nil? id))))

    (testing "simple GET"
      (let [{:keys [body status]} @(http-client/get "http://localhost:8082")]
        (is (= "<p>Page not found.</p>" body))
        (is (= 404 status))))

    (testing "shutdown"
      (is (= s (first @server/servers)))
      (server/stop-server! s)
      (let [{:keys [body status]} @(http-client/get "http://localhost:8082")]
        (is (nil? body))
        (is (nil? status))))))

(deftest add-a-service
  (let [received (promise)
        {server-id :id, :as s} (server/ensure-server! :port 8082)
        {client-id :id, :as c} (client/ensure-connection! :port 8082)]
    (server/add-service! "test-service" s (fn [con msg]  (deliver received msg)))
    (client/send! c {:target server-id :action "test-service" :data "test"})
    (let [{:keys [data id sender target]} (deref received 200 nil)]
      (is (= "test" data))
      (is (not (nil? id)))
      (is (= client-id sender))
      (is (= server-id target)))))

(deftest echo-service-test
  (let [{server-id :id, :as s} (server/ensure-server! :port 8082)
        {client-id :id, :as c} (client/ensure-connection! :port 8082)]
    (server/add-service! "echo" s (fn [con msg]  (server/answer! con msg (:data msg))))
    (let [{:keys [data]} (async/<!! (client/send! c {:target (:id s) :action "echo" :data "test"}))]
      (is (= "test" data)))
    @(future (Thread/sleep 500))
    ))

(comment

 (clojure.test/test-var #'echo-service-test)
 (clojure.test/test-var #'create-a-ws-server)
 (test-ns *ns*)
 )