(ns rksm.websocket-test.cljs-repl
  (:require [cljs.repl :as repl]
            [cljs.analyzer :as ana]
            [cljs.env :as env]
            [rksm.websocket-test.server :as server]
            [rksm.websocket-test.messenger :as msg]
            [clojure.core.async :refer [>!! <!! >! <! go]]))

(defrecord CloxpCljsReplEnv [server client-id]
  repl/IJavaScriptEnv
  (-setup [this opts])
  (-evaluate [this filename line js] 
             (let [msg {:target client-id,
                        :action "eval-js",
                        :data {:code js
                               :filename filename
                               :line line}}]
               (if-let [result (->> msg (msg/send server) <!! :message :data)]
                 (update-in result [:status] keyword)
                 {:status :error :value (str "Invalid msg " msg)})))
  (-load [this provides url]
         {:status :error :value :not-yet-implemented})
  (-tear-down [_]))

(defn default-repl-env
  []
  (if-let [s (server/find-server-by-host-and-port :port 8082 :host "0.0.0.0")]
    (if-let [client-id (-> server/channels deref keys first)]
      (->CloxpCljsReplEnv s client-id)
      (throw (Exception. "No cljs-repl client found")))
    (throw (Exception. "No server for cljs-repl found"))))

(defn eval-cljs
  ([form]
   (eval-cljs form (default-repl-env)))
  ([form cloxp-repl-env]
   (let [c-env (env/default-compiler-env)
         ana-env (merge (ana/empty-env) {:ns 'cljs.user})]
     (env/with-compiler-env c-env
       (cljs.repl/evaluate-form cloxp-repl-env
                                ana-env
                                "<cloxp-cljs-repl>"
                                form
                                identity ; wrap-fn
                                {} ; opts
                                )))))