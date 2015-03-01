(ns rksm.websocket-test.server
  (:require [compojure.route :as route]
            [compojure.handler :as handler]
            [org.httpkit.server :as http]
            [compojure.core :refer [defroutes GET POST DELETE ANY context]]
            [clojure.data.json :as json]
            [rksm.websocket-test.com :as com]))

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

(declare find-server-by-host-and-port handle-service-request!)

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
                        (println "channel closed")))
    (if (http/websocket? channel)
      (println "WebSocket channel")
      (println "HTTP channel"))
    (http/on-receive channel (fn [data]
                          (http/send! channel data)))))

(defn websocket-service-handler [req]
  (http/with-channel req channel
    (http/on-close channel (fn [status] (println "channel closed" status)))
    (http/on-receive channel (fn [data]
                               (let [{host :server-name, port :server-port} req
                                     server (find-server-by-host-and-port :host host :port port)]
                                 (handle-service-request! server channel data))))))

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
  (->> @servers
    (filter #(= {:host host, :port port} (select-keys % [:host :port])))
    first))

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
        s {:id (str (java.util.UUID/randomUUID))
           :stop stop
           :port port :host host}]
    (swap! servers conj s)
    s))

(defn ensure-server!
  [& opts]
  (let [{:keys [host port]} (apply hash-map opts)]
    (or (find-server-by-host-and-port :host host :port port)
        (apply start-server! opts))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; sending / receiving

(defn send!
  [{c :channel, :as con} msg]
  (com/send! #(http/send! c (json/write-str %)) con msg))

(defn answer!
 [{c :channel, :as con} msg data]
  (com/answer! #(http/send! c (json/write-str %)) con msg data))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; services

(defn add-service!
  [name {id :id :as server} handler]
  (let [s (first (filter #(= id (:id %)) @servers))]
    (assert s (str "server with id " id " not found"))
    (swap! servers (partial replace {s (assoc-in s [:services name] handler)}))))

(defn answer-message-not-understood
  [server channel data]
  (http/send! channel (json/write-str {:error "messageNotUnderstood"})))

(defn handle-service-request!
  [server channel data]
  (let [msg (if (string? data) (json/read-str data :key-fn keyword))
        {:keys [target action]} msg
        handler (get-in server [:services action])]
    (cond
      (not= (:id server) target) (answer-message-not-understood server channel data)
      (nil? handler) (answer-message-not-understood server channel data)
      :default (handler (assoc server :channel channel) msg))))

; (defn send!
;   [& {:keys [target connection data]}]
;   123)

(comment
 (start-server! :port 8081)
 (stop-all-servers!)
 (stop-server (first @servers))
 (-> (first @servers) .)
 servers
 )
