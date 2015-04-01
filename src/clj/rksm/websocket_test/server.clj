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
                             (println "[app-websocket-echo] channel closed" status)))
    (if (http/websocket? channel)
      (println "WebSocket channel")
      (println "HTTP channel"))
    (http/on-receive channel (fn [data]
                               (http/send! channel data)))))

(defn websocket-service-handler [req]
  (http/with-channel req channel
    (http/on-close channel (fn [status] (println "[app-websocket-echo] channel closed" status)))
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

(declare default-services add-service!)

(defn start-server!
  [& {:keys [port host], :or {port 8081 host "0.0.0.0"} :as opts}]
  (let [stop (http/run-server #'app {:port port :host host})
        s {:id (str (java.util.UUID/randomUUID))
           :stop stop
           :port port :host host}]
    (swap! servers conj s)
    (doseq [[name handler] (default-services)]
      (add-service! name s handler))
    s))

(defn ensure-server!
  [& opts]
  (let [{:keys [host port]} (apply hash-map opts)]
    (or (find-server-by-host-and-port :host host :port port)
        (apply start-server! opts))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; sending / receiving

(defn send!
  [{c :channel, :as con} msg
   & {:keys [expect-more-responses], :or {expect-more-responses false}}]
  (com/send! #(http/send! c (json/write-str %)) con msg
             :expect-more-responses expect-more-responses))

(defn answer!
  [{c :channel, :as con} msg data
   & {:keys [expect-more-responses], :or {expect-more-responses false}}]
  (com/answer! #(http/send! c (json/write-str %)) con msg data
              :expect-more-responses expect-more-responses))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; services

(defn add-service!
  [name {id :id :as server} handler]
  (let [s (first (filter #(= id (:id %)) @servers))]
    (assert s (str "server with id " id " not found"))
    (swap! servers (partial replace {s (assoc-in s [:services name] handler)}))
    [@servers]))

(defn answer-message-not-understood
  [con msg]
  (answer! con msg {:error "messageNotUnderstood"}))

(defn answer-with-info
  [{id :id, :as con} msg]
  (answer! con msg {:id id}))

(defn echo-service-handler
  [con msg]
  (println "echo service called")
  (answer! con msg (:data msg)))

(defn add-service-handler
  [server {{:keys [name handler]} :data, :as msg}]
  (try 
    (add-service! name server (eval (read-string handler)))
    (answer! server msg "OK")
    (catch Exception e
      (answer! server msg {:error (str e)}))))

(defn default-services
  []
  {"echo" echo-service-handler
   "add-service" add-service-handler})

(defn handle-service-request!
  [server channel data]
  ; FIXME: validate data!
  (let [msg (if (string? data) (json/read-str data :key-fn keyword) data)
        {:keys [target action]} msg
        handler (get-in server [:services action])
        connection (assoc server :channel channel)]
    msg
    (cond
      (= action "info") (answer-with-info connection msg)
      (not= (:id server) target) (answer-message-not-understood connection msg)
      (nil? handler) (answer-message-not-understood connection msg)
      :default (handler connection msg))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(comment
 (start-server! :port 8081)

 (let [s (first @servers)] (add-service! "echo" s echo-service-handler))
 
 (stop-all-servers!)
 (stop-server (first @servers))
 (-> (first @servers) .)
 servers
 )