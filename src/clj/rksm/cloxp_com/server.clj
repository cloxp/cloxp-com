(ns rksm.cloxp-com.server
  (:refer-clojure :exclude [send])
  (:require [compojure.route :as route]
            [compojure.handler :as handler]
            [compojure.response :as response]
            [org.httpkit.server :as http]
            [compojure.core :refer [defroutes GET POST DELETE ANY context]]
            [clojure.data.json :as json]
            [rksm.cloxp-com.messenger :as m]
            [clojure.core.async :as async :refer [>!! >! <! chan go go-loop sub pub close! put! timeout]]
            [medley.core :refer [dissoc-in]]))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce log (agent []))

(def debug false)

(defn log-req
  [app]
  (fn [req]
    (if debug
      (let [{:keys [server-port content-length websocket?
                    content-type uri server-name scheme
                    request-method]} req]
        (println (format "%s %s://%s:%s%s (%s, %s)"
                         (.toUpperCase (name request-method))
                         (name scheme) server-name server-port uri
                         content-length content-type))))
    (app req)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; server connection, sending / receiving

(declare register-channel find-channel channels app
         register-service-handler close-connection-service-handler
         send-message-impl)

(defrecord MessengerImpl [host port stop-fn receive-channel]
  m/IReceiver
  (receive-chan [this] receive-channel)
  (stop-receiver [this]
                 (close! receive-channel)
                 (stop-fn :timeout 100))
  m/ISender
  (send-message [this con msg] (send-message-impl this con msg)))

(defn create-messenger
  [& {:keys [port host], :or {port 8081 host "0.0.0.0"} :as opts}]
  (let [stop (http/run-server #'app {:port port :host host})
        services (merge (m/default-services)
                        {"register" #'register-service-handler
                         "close-connection" #'close-connection-service-handler})
        messenger (m/create-messenger
                   "server-messenger"
                   (->MessengerImpl host port stop (chan))
                   services)]
    messenger))

(defn send-message-impl
  [{:keys [host port] :as server-impl} con msg]
  (if-let [chan (or (:channel m/*current-connection*)
                    (find-channel
                     (find-server-by-host-and-port :host host :port port)
                     (:target msg))
                    (some->> con :channel))]
    (try
      (http/send! chan (json/write-str msg))
      (catch Exception e (println e)))
    (throw (Exception. (str "Cannot find channel for target " (:target msg))))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; routing

(declare find-server-by-host-and-port)

(defn websocket-service-handler [req]
  (http/with-channel req channel
    (http/on-receive
     channel
     (fn [data]
       (let [{host :server-name, port :server-port} req
             server (find-server-by-host-and-port :host host :port port)]
         (-> server
           :impl m/receive-chan
           (>!! {:raw-msg data, :connection {:req req, :channel channel}})))))))

(defn cljs-file-handler
  [req]
  (if-let [content (some-> req :params :* ring.util.codec/url-decode rksm.system-files/file)]
    content
    (-> (response/render "<span>not here</span>" req)
      (assoc :status 404))))

(defroutes all-routes
;   (GET "/" [] show-landing-page)
  (GET "/ws" [] websocket-service-handler)
  (GET "/cljs-files/*" [] cljs-file-handler)
  (route/files "/" {:root "./public" :allow-symlinks? true})
  (route/not-found "<p>Page not found.</p>"))

(def app (-> all-routes log-req))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce servers (atom []))

(defn host [server] (-> server :impl :host))
(defn port [server] (-> server :impl :port))

(defn find-server-by-host-and-port
  [& {h :host, p :port}]
  (or (->> @servers
        (filter #(= [p h] ((juxt port host) %)))
        first)
      (if (= h "localhost")
        (find-server-by-host-and-port :host "0.0.0.0" :port p))))

(defn stop-server!
  [server]
  (when-not (nil? server)
    (m/stop server)
    (swap! servers #(filter (fn [s] (not= s server)) %))))

(defn stop-all-servers!
  []
  (doseq [s @servers] (stop-server! s)))

(defn start-server!
  [& {:keys [port host], :or {port 8081 host "0.0.0.0"} :as opts}]
  (let [s (create-messenger :host host :port port)]
    (swap! servers conj s)
    s))

(defn ensure-server!
  [& opts]
  (let [{:keys [host port]} (apply hash-map opts)]
    (or (find-server-by-host-and-port :host host :port port)
        (apply start-server! opts))))


; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; client channel management

(defonce channels (atom {}))

(defn find-connection
  [{server-id :id, :as server} id]
  (get-in @channels [server-id id]))

(defn server-connections
  [{server-id :id, :as server}]
  (some-> @channels (get server-id) vals))

(defn all-connections
  []
  (mapcat (fn [s] (map #(assoc % :server s)
                       (server-connections s)))
          @servers))

(defn find-channel
  [server id]
  (:channel (find-connection server id)))

(defn- register-channel
  [{server-id :id, :as server} channel id register-data]
  (http/on-close
   channel (fn [status] (swap! channels dissoc-in [server-id id])))
  (swap! channels assoc-in [server-id id] (merge register-data {:channel channel})))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; services

(defn register-service-handler
  [server {:keys [sender data] :as msg}]
  (if m/*current-connection*
    (do
      (register-channel
       server (:channel m/*current-connection*)
       sender data)
      (m/answer server msg :OK false))
    (m/answer server msg
              {:error (str "Cannot register connection for" sender)}
              false)))

(defn close-connection-service-handler
  [server {{id :id} :data :as msg}]
  (if-let [con (if id
                 (find-channel server id)
                 (:channel m/*current-connection*))]
    (try
      (m/answer server msg :OK false)
      (.serverClose con 200)
      (catch Exception e (m/answer server msg {:error (str e)} false)))
    (m/answer server msg
              {:error (str "cannot find channel for" id)}
              false)))

(comment
 (start-server! :port 8084)
 (stop-all-servers!)
 )
