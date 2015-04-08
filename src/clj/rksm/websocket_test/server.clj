(ns rksm.websocket-test.server
  (:refer-clojure :exclude [send])
  (:require [compojure.route :as route]
            [compojure.handler :as handler]
            [org.httpkit.server :as http]
            [compojure.core :refer [defroutes GET POST DELETE ANY context]]
            [clojure.data.json :as json]
            [rksm.websocket-test.com :as com]
            [clojure.core.async :as async :refer [>! <! chan go go-loop sub pub close! put!]]))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce log (agent []))

(defn log-req
  [app]
  (fn [req]
    (let [{:keys [server-port content-length websocket?
                  content-type uri server-name scheme
                  request-method]} req]
      (println (format "%s %s://%s:%s%s (%s, %s)"
                       (.toUpperCase (name request-method))
                       (name scheme) server-name server-port uri
                       content-length content-type)))
    (app req)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; server connection, sending / receiving

(declare find-server-by-host-and-port handle-service-request!
         send! answer!
         add-service!
         register-channel find-channel channels)

(defrecord Connection [id stop port host services receive]
  com/IConnection
  (send [this msg] (send! this msg))
  (answer [this msg data] (answer! this msg data))
  (handle-request [this channel raw-data] (com/default-handle-request this channel raw-data))
  (handle-response-request [this msg] (go (>! (:chan receive) {:message msg})))
  (register-connection [this id channel]
                       (register-channel this id channel))
  (add-service [this name handler-fn] (add-service! this name handler-fn))
  (lookup-handler [this action] (get-in this [:services action])))

(defn- send!
  [{:keys [channel receive], :as con} msg
   & {:keys [expect-more-responses] :or {expect-more-responses false}}]
  (let [{msg-id :id, target :target, :as msg} (com/send-msg con msg expect-more-responses)
        sub-chan (sub (:pub receive) msg-id (chan))
        client-chan (chan)
        close (fn [] (close! sub-chan) (close! client-chan))]

    ; real send
    (if-let [c (or channel (find-channel target))]
      (http/send! c (json/write-str msg))
      (throw (Exception. (str "Cannot find channel for target " target))))

    ; wait for responses
    (go-loop []
      (let [{:keys [message error] :as msg} (<! sub-chan)]
        (if msg (>! client-chan msg))
        (cond
          (nil? msg) (close)
          error (close)
          (not (:expect-more-responses message)) (close)
          :default (recur))))
    client-chan))

(defn- answer!
  [{:keys [channel], :as con} msg data
   & {:keys [expect-more-responses]
      :or {expect-more-responses false}}]
  (let [{:keys [target] :as answer-msg} (com/answer-msg con msg data expect-more-responses)
        c (or channel (find-channel target))]
    (if-not c (throw (Exception. (str "Cannot find channel for target " target))))
    (http/send! c (json/write-str answer-msg))
    answer-msg))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn app-simple-http [req]
  {:status 200
  :headers {"Content-Type" "text/html"}
  :body    "hello2 HTTP!"} )

(defn app-streaming-response [req]
  (http/with-channel req ch
    (future
     (Thread/sleep 1000)
     (http/send! ch {:status 200
                :headers {"Content-Type" "text/html"}
                :body "fooo abbbrrrr"}))))

(defn app-websocket-echo [req]
  (http/with-channel req channel
    (http/on-close channel (fn [status]
                             (println "[app-websocket-echo] channel closed" status)))
    (if (http/websocket? channel)
      (println "WebSocket channel")
      (println "HTTP channel"))
    (http/on-receive channel (fn [data]
                               (http/send! channel data)))))

(defn websocket-service-handler [req]
  (http/with-channel req channel
    (http/on-receive channel (fn [data]
                               (let [{host :server-name, port :server-port} req
                                     server (find-server-by-host-and-port :host host :port port)]
                                 (com/handle-request server channel data))))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defroutes all-routes
;   (GET "/" [] show-landing-page)
  (GET "/ws" [] websocket-service-handler)
  (route/files "/" {:root "./public" :allow-symlinks? true})
  (route/not-found "<p>Page not found.</p>"))

(def app
  (-> all-routes log-req))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce servers (atom []))

(defn find-server-by-host-and-port
  [& {:keys [host port]}]
  (or (->> @servers
        (filter #(= {:host host, :port port} (select-keys % [:host :port])))
        first)
      (if (= host "localhost")
        (find-server-by-host-and-port :host "0.0.0.0" :port port))))

(defn stop-server!
  [server]
  (when-not (nil? server)
    (let [stop (:stop server)]
      (stop :timeout 100)
      (swap! servers #(filter (fn [s] (not= s server)) %)))))

(defn stop-all-servers!
  []
  (doseq [s @servers] (stop-server! s)))

(defn start-server!
  [& {:keys [port host], :or {port 8081 host "0.0.0.0"} :as opts}]
  (let [stop (http/run-server #'app {:port port :host host})
        receive-chan (chan)
        s (map->Connection
            {:id (str (java.util.UUID/randomUUID))
             :stop stop
             :port port
             :host host
             :services (com/default-services)
             :receive {:chan receive-chan
                       :pub (pub receive-chan (comp :in-response-to :message))}})]
    (swap! servers conj s)
    s))

(defn ensure-server!
  [& opts]
  (let [{:keys [host port]} (apply hash-map opts)]
    (or (find-server-by-host-and-port :host host :port port)
        (apply start-server! opts))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; services

(defn- add-service!
  [{id :id :as server} name handler]
  (let [old-server (first (filter #(= id (:id %)) @servers))]
    (assert old-server (str "server with id " id " not found"))
    (let [server (map->Connection
                   (update-in old-server [:services]
                              assoc name handler))]
      (swap! servers (partial replace {old-server server})))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; client channel management

(defonce channels (atom {}))

(defn find-channel
  [id]
  (get @channels id))

(defn- register-channel
  [server id channel]
  (http/on-close
   channel (fn [status] (swap! channels dissoc id)))
  (swap! channels assoc id channel))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(comment
 (start-server! :port 8082)
 (stop-all-servers!)

 (let [s (first @servers)] (add-service! s "echo" echo-service-handler))

 (stop-all-servers!)
 (stop-server (first @servers))
 (-> (first @servers) .)
 servers
 )
