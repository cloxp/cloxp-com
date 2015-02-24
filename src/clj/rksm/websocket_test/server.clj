(ns rksm.websocket-test.server
  (:require [compojure.route :as route]
            [compojure.handler :as handler]
            [org.httpkit.server :as http]
            [compojure.core :refer [defroutes GET POST DELETE ANY context]]
            [clojure.data.json :as json]))

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
    (http/on-close channel (fn [status]))
    (http/on-receive channel (fn [data]
                               (let [{host :server-name, port :server-port} req
                                     server (find-server-by-host-and-port :host host :port port)]
                                 (handle-service-request! server channel data))))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn app [req]
   ;; all other, return 404

; (run-server (site #'all-routes) {:port 8080})
)

(defroutes all-routes
;   (GET "/" [] show-landing-page)
  (GET "/ws" [] websocket-service-handler)
  (route/files "/" {:root "resources" :allow-symlinks? true})
  (route/not-found "<p>Page not found.</p>"))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce servers (atom []))

(defn find-server-by-host-and-port
  [& {:keys [host port]}]
  (first (filter #(and (= host (:host %)) (= port (:port %))) @servers)))

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
  ;   (reset! server (run-server #'app {:port 8081}))
  (let [stop (http/run-server #'all-routes {:port port :host host})
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
      :default (handler channel msg))))

; (defn send!
;   [& {:keys [target connection data]}]
;   123)

(comment
 (start-server)
 (stop-server (first @servers))
 (-> (first @servers) .)
 servers
 )