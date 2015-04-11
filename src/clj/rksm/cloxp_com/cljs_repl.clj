(ns rksm.cloxp-com.cljs-repl
  (:require [cljs.repl :as repl]
            [cljs.analyzer :as ana]
            [cljs.env :as env]
            [rksm.cloxp-com.server :as server]
            [rksm.cloxp-com.messenger :as msg]
            [clojure.core.async :refer [>!! <!! >! <! go]]))

(defn send-to-js
  [server client-id action data]
  (let [msg {:target client-id,
             :action action,
             :data data}]
    (if-let [result (->> msg (msg/send server) <!! :message :data)]
      (update-in result [:status] keyword)
      {:status :error :value (str "Invalid msg " msg)})))

(defrecord CloxpCljsReplEnv [server client-id]
  repl/IJavaScriptEnv
  (-setup [this opts])
  (-evaluate [this filename line js]
             (send-to-js server client-id
                         "eval-js" {:code js :filename filename :line line}))
  (-load [this provides uri]
         (let [[host port] ((juxt server/host server/port) server)]
           (send-to-js server client-id
                      "load-js" {:provides provides :path (str uri)
                                 :host host :port port})))
  (-tear-down [_]))

(defn default-repl-env
  []
  (if-let [s (server/find-server-by-host-and-port :port 8082 :host "0.0.0.0")]
    (if-let [client-id (-> server/channels deref keys first)]
      (->CloxpCljsReplEnv s client-id)
      (throw (Exception. "No cljs-repl client found")))
    (throw (Exception. "No server for cljs-repl found"))))

(defn eval-cljs
  ([form opts]
   (eval-cljs form (default-repl-env) (or opts {})))
  ([form cloxp-repl-env opts]
   (let [c-env (or env/*compiler* (env/default-compiler-env))
         ana-env (merge (ana/empty-env) {:ns 'cljs.user})]
     (env/with-compiler-env c-env
       (repl/evaluate-form cloxp-repl-env
                           ana-env
                           "<cloxp-cljs-repl>"
                           form
                           identity ; wrap-fn
                           opts)))))

(defn load-namespace
  ([sym opts]
   (load-namespace sym (default-repl-env) (or opts {})))
  ([sym cloxp-repl-env opts]
   sym
   (let [c-env (env/default-compiler-env)
         ana-env (merge (ana/empty-env) {:ns 'cljs.user})]
     (env/with-compiler-env c-env
       (cljs.repl/load-namespace cloxp-repl-env sym opts)))))
