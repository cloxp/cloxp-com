(ns rksm.cloxp-com.cloxp-client
  (:refer-clojure :exclude [send])
  (:require [rksm.cloxp-com.net :as net]
            [rksm.cloxp-com.messenger :refer [answer send]]
            [cljs.core.async :refer [<! >! put! close! chan pub sub]]
            [clojure.browser.repl :as brepl])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(enable-console-print!)

(defonce cloxp-connection (atom nil))

(def default-url "ws://localhost:8082/ws")

(defn with-con
  [do-func & [{:keys [url] :as opts}]]
  (if-let [c @cloxp-connection]
    (do-func c)
    (go
     (let [url (or url default-url)
           c (<! (net/connect url))]
       (reset! cloxp-connection c)
       (do-func c)))))

(defn start
  []
  (do
    (println "running bootstrap...")
    (brepl/bootstrap))
  (with-con
    (fn [con]
      (send con {:action "register"
                 :data {:id (:id con)
                        :cloxp-client? true
                        :services (-> con :services deref keys)
                        :document-url (. js/document -URL)}})
      (println "Connected"))))

(defn test-send
  [con]
  (go
   (let [t1 (js/Date.)]
     (println (<! (send con {:action "echo" :data "huhu!"})))
     (let [t2 (js/Date.)]
       (println (- t2 t1))
       (dotimes [_ 10]
         (do
           (<! (send con {:action "echo" :data "harhahr!"}))
           (println (- (js/Date.) t2))))))))
