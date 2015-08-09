(ns rksm.cloxp-com.server-test
  (:require [rksm.cloxp-com.server :as server]
            [rksm.cloxp-com.messenger :as m]
            [rksm.cloxp-com.server-client :as client]
            [clojure.test :refer :all]
            [org.httpkit.client :as http-client]
            [clojure.core.async :as async :refer [<!! >!! chan go]]))

(def ^:dynamic *client*)
(def ^:dynamic *server*)
(def port 8083)

(def http-server-dir (when-let [project-dir (loop [dir "."]
                                              (let [file (.getCanonicalFile (clojure.java.io/file dir))
                                                    project-dir? (->> file
                                                                   .listFiles
                                                                   (map #(.getName %))
                                                                   (some #{"project.clj"})
                                                                   boolean)]
                                                (if project-dir? file (recur (.getParent file)))))]
                       (clojure.java.io/file project-dir "test/resources")))

(defn fixture [test]
  (binding [*server* (server/ensure-server! :port port :fs-root http-server-dir)
            *client* (client/ensure-connection! :port port)]
    (test)
    (client/stop! *client*)
    (server/stop-server! *server*)))

(use-fixtures :each fixture)

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(deftest create-a-ws-server

  (testing "server data"
    (is (= [port "0.0.0.0"]
           ((juxt server/port server/host) *server*)))
    (is (contains? *server* :id)))

  (testing "simple GET"
    (let [{:keys [body status]} @(http-client/get (str "http://localhost:" port "/no-such-file.txt"))]
      (is (= "<p>Page not found.</p>" body))
      (is (= 404 status)))
    (let [{:keys [body status]} @(http-client/get (str "http://localhost:" port "/for-server-test.html"))]
      (is (= "<span>this file is being used in the server test</span>" body))
      (is (= 200 status))))

  (testing "shutdown"
    (is (= *server* (first @server/servers)))
    (server/stop-server! *server*)
    (let [{:keys [body status]} @(http-client/get (str "http://localhost:" port))]
      (is (nil? body))
      (is (nil? status)))))

(deftest add-a-service
  (let [received (promise)]
    (m/add-service *server* "test-service" (fn [server msg]  (deliver received msg)))
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
                        :handler (str '(fn [con msg] (rksm.cloxp-com.messenger/answer con msg (-> msg :data (+ 1)) false)))}}))]
      (is (nil? (some-> add-answer :message :data :error)))))

  (testing "call handler"
    (let [{{data :data} :message}
          (<!! (m/send
                *client*
                {:target (:id *server*) :action "adder" :data 3}))]
      (is (= 4 data)))))

(deftest streaming-response-test
  (let [handler '(do
                   (require '[rksm.cloxp-com.messenger :as m])
                   (fn [server msg]
                     (m/send server (m/prep-answer-msg server msg 1 true))
                     (m/send server (m/prep-answer-msg server msg 2 true))
                     (m/send server (m/prep-answer-msg server msg 3 false))))
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
        c (server/find-channel *server* (:id *client*))]
    (is (= "OK" (-> reg-result :message :data)))
    (is (not= nil c))))

(deftest msg-server->client
  (<!! (m/send *client* {:target (:id *server*) :action "register"}))
  (m/add-service *client* "add-something"
                   (fn [server msg] (m/answer server msg (+ 23 (:data msg)) false)))
  (let [{{:keys [data error]} :message}
        (<!! (m/send *server* {:target (:id *client*) :action "add-something" :data 2}))]
    (is (nil? (or error (:error data))))
    (is (= 25 data))))

(comment
 (let [writer (java.io.StringWriter.)]
   (binding [*test-out* (java.io.PrintWriter. writer)]
     (test-ns *ns*)
     (-> writer str println)))

 (test-var #'create-a-ws-server)

 (-> server/servers deref first :impl .stop_receiver)
 (server/stop-all-servers!)
 )
