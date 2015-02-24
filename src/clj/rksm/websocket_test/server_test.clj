(ns rksm.websocket-test.server-test
  (:require [rksm.websocket-test.server :refer :all])
  (:require [clojure.test :refer :all])
  (:require [rksm.websocket-test.client :as client])
  (:require [org.httpkit.client :as http-client])
  )

(def test-servers (atom []))

(defn fixture [test]
  (test)
  (do
    (doseq [s @servers]
      (stop-server! s)
      (client/stop-all!))
    ; (swap! servers empty)
    ))

(use-fixtures :each fixture)

(deftest create-a-ws-server

  (let [s (ensure-server! :port 8082)]

    (testing "server data"
      (is (= 8082 (:port s)))
      (is (= "0.0.0.0" (:host s)))
      (contains? s :id))

    (testing "simple GET"
      (let [{:keys [body status]} @(http-client/get "http://localhost:8082")]
        (is (= "<p>Page not found.</p>" body))
        (is (= 404 status))))

    (testing "shutdown"
      (is (= s (first @servers)))
      (stop-server! s)
      (let [{:keys [body status]} @(http-client/get "http://localhost:8082")]
        (is (nil? body))
        (is (nil? status))))))

(deftest add-a-service
  (let [received (promise)
        s (ensure-server! :port 8082)
        c (client/ensure-connection! :port 8082)]
    (add-service! "test-service" s (fn [con msg]  (deliver received (:data msg))))
    (client/send! c {:target (:id s) :action "test-service" :data "test"})
    (is (= "test" (deref received 200 nil)))))

(comment

 (clojure.test/test-var #'create-a-ws-server)
 (test-ns *ns*)
 )