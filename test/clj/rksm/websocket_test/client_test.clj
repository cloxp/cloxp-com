(ns rksm.websocket-test.client-test
  (:require [clojure.core.async :as async :refer [go go-loop <!! >!! thread]]
            [rksm.cloxp-projects.core :as proj]
            [rksm.subprocess :as subp]
            [rksm.websocket-test.server :as server]
            [rksm.websocket-test.client :as client]
            [clojure.test :refer :all]))

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

(defn project-clj
  []
  (-> (clojure.java.io/file ".")
    proj/lein-project-conf-content
    (assoc :eval-in :leiningen)))

(defn run-cljs-tests
  []
  (some-> (project-clj)
    :cljsbuild :test-commands (get "unit-tests")
    (->> (apply subp/async-proc))))

(deftest all-cljs-tests
  (let [proc (run-cljs-tests)]
    (subp/wait-for proc)
    (is (= 0 (subp/exit-code proc)))
    (is (re-find #"Testing complete: 0 failures, 0 errors."
                 (subp/stdout proc)))))

(comment
 (test-ns *ns*))