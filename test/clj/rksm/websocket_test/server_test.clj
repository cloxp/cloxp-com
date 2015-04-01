(ns rksm.websocket-test.server-test
  (:require [rksm.websocket-test.server :as server]
            [clojure.test :refer :all]
            [rksm.websocket-test.client :as client]
            [org.httpkit.client :as http-client]
            [clojure.core.async :as async :refer [<!! >!! chan go]]))

(def ^:dynamic *client*)
(def ^:dynamic *server*)

(defn fixture [test]
  (binding [*server* (server/ensure-server! :port 8082)
            *client* (client/ensure-connection! :port 8082)]
    (test))
  (do
    (doseq [s @server/servers]
      (server/stop-server! s)
      (client/stop-all!))))

(use-fixtures :each fixture)

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(deftest create-a-ws-server

  (let [{:keys [port host id] :as s} *server*]

    (testing "server data"
      (is (= 8082 port))
      (is (= "0.0.0.0" host))
      (is (not (nil? id))))

    (testing "simple GET"
      (let [{:keys [body status]} @(http-client/get "http://localhost:8082")]
        (is (= "<p>Page not found.</p>" body))
        (is (= 404 status))))

    (testing "shutdown"
      (is (= s (dissoc (first @server/servers) :services)))
      (server/stop-server! s)
      (let [{:keys [body status]} @(http-client/get "http://localhost:8082")]
        (is (nil? body))
        (is (nil? status))))))

(deftest add-a-service
  (let [received (promise)]
    (server/add-service! "test-service" *server* (fn [con msg]  (deliver received msg)))
    (client/send! *client* {:target (:id *server*) :action "test-service" :data "test"})
    (let [{:keys [data id sender target]} (deref received 200 nil)]
      (is (= "test" data))
      (is (not (nil? id)))
      (is (= (:id *client*) sender))
      (is (= (:id *server*) target)))))

(deftest echo-service-test
  (let [{{data :data} :message} (<!! (client/send!
                                      *client*
                                      {:target (:id *server*) :action "echo" :data "test"}))]
    (is (= "test" data))))

(deftest install-service-test
  (let [_ (<!! (client/send!
                *client*
                {:target (:id *server*)
                 :action "add-service"
                 :data {:name "adder"
                        :handler "(fn [con msg] (rksm.websocket-test.server/answer! con msg (-> msg :data (+ 1))))"}}))
        {{data :data} :message} (<!! (client/send!
                                      *client*
                                      {:target (:id *server*) :action "adder" :data 3}))]
    (is (= 4 data))))

(deftest streaming-response-test
  (let [_ (<!! (client/send!
                *client*
                {:target (:id *server*)
                 :action "add-service"
                 :data {:name "stream"
                        :handler (str '(fn [con msg]
                                         (rksm.websocket-test.server/answer! con msg 1 :expect-more-responses true)
                                         (rksm.websocket-test.server/answer! con msg 2 :expect-more-responses true)
                                         (rksm.websocket-test.server/answer! con msg 3)))}}))
        recv (client/send! *client* {:target (:id *server*) :action "stream" :data 3})
        data (loop [respones []]
               (if-let [next (-> (<!! recv) :message :data)]
                 (recur (conj respones next))
                 respones))]
    (is (= [1 2 3] data))))

(comment

 (clojure.test/test-var #'echo-service-test)
 (clojure.test/test-var #'create-a-ws-server)

 (test-ns *ns*)
 
 )