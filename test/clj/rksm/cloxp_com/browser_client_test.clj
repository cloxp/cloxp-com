(ns rksm.cloxp-com.browser-client-test
  (:require [rksm.cloxp-com.server :as server]
            [cljs-slimerjs-tester.core :as tester]
            [clojure.test :refer :all]))

(defn abs-path [rel-path]
  (->>
    rel-path
    (clojure.java.io/file ".")
    .getCanonicalPath))

(defn fixture [test]
  (let [server (server/ensure-server! :port 8084)]
    (test)
    (server/stop-server! server)))

(use-fixtures :each fixture)

(deftest all-cljs-tests
  (let [{:keys [test pass fail error]}
        (tester/run-tests (abs-path "cloxp-cljs-build/")
                          ["/cloxp-cljs.js"]
                          'rksm.cloxp-com.test-runner/runner
                          {:port 8095 :timeout 10000})]
    (is (zero? fail))
    (is (zero? error))))

(comment
 (test-ns *ns*)
 )
