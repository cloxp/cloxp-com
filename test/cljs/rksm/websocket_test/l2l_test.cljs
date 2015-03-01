(ns rksm.websocket-test.l2l-test
  (:require-macros [cemerick.cljs.test :refer (is deftest run-tests testing test-var)])
  (:require [cemerick.cljs.test :as t]))

(deftest simple-ws-connect
  (is (= 1 2)))

(enable-console-print!)

(run-tests 'rksm.websocket-test.l2l-test)