(ns rksm.websocket-test.browser-client-test
  (:require [clojure.core.async :as async :refer [go go-loop <!! >!! thread]]
            [rksm.cloxp-projects.lein :as lein]
            [rksm.subprocess :as subp]
            [rksm.websocket-test.server :as server]
            [clojure.test :refer :all]))

(def ^:dynamic *server*)

(defn fixture [test]
  (binding [*server* (server/ensure-server! :port 8082)]
    (test)
    (server/stop-server! *server*)))

(use-fixtures :each fixture)

(defn project-clj
  []
  (-> (clojure.java.io/file ".")
    lein/lein-project-conf-content
    (assoc :eval-in :leiningen)))

(defn run-cljs-tests
  []
  (some-> (project-clj)
    :cljsbuild :test-commands (get "unit-tests")
    (->> (apply subp/async-proc))))

(deftest all-cljs-tests
  (let [proc (run-cljs-tests)
        _ (subp/wait-for proc)
        code (subp/exit-code proc)
        out (subp/stdout proc)
        [_ test pass fail error]
        (re-find #":test ([0-9]+), :pass ([0-9]+), :fail ([0-9]+), :error ([0-9]+)" out)
        [test pass fail error] (->> [test pass fail error]
                                 (map #(or % "nil"))
                                 (map read-string))]
    (println [test pass fail error])
    (is (= 0 code))
    (is (= 0 fail))
    (is (= 0 error))))

(comment
 (test-ns *ns*)
 )
