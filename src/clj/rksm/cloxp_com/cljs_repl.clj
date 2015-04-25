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
  (-setup [this opts]
          #_(eval-cljs (println "connected!") this))
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
  (if-let [{:keys [server id] :as connection} (first (server/all-connections))]
    (->CloxpCljsReplEnv server id)
    (throw (Exception. "No cljs-repl connection found"))))

(defn repl-env-for-client
  [client-id]
  (if-let [{:keys [server] :as con} (server/find-connection client-id)]
    (->CloxpCljsReplEnv server client-id)
    (throw (Exception. (str "No cljs-repl connection for client " client-id " found")))))

(defn eval-cljs
  ([form {:keys [target-id] :as opts}]
   (let [env (if target-id
               (repl-env-for-client target-id)
               (default-repl-env))]
     (eval-cljs form env (or opts {}))))
  ([form cloxp-repl-env {:keys [ns-sym] :as opts}]
   (let [c-env (or env/*compiler* (env/default-compiler-env))
         ana-env (merge (ana/empty-env) {:ns 'cljs.user})]
     (env/with-compiler-env c-env
       (repl/evaluate-form cloxp-repl-env
                           (assoc ana-env :ns (ana/get-namespace
                                               (or ns-sym ana/*cljs-ns*)))
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
