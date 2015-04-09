(ns rksm.cloxp-com.server
  (:refer-clojure :exclude [send])
  (:require [compojure.route :as route]
            [compojure.handler :as handler]
            [org.httpkit.server :as http]
            [compojure.core :refer [defroutes GET POST DELETE ANY context]]
            [clojure.data.json :as json]
            [rksm.cloxp-com.messenger :as m]
            [clojure.core.async :as async :refer [>!! >! <! chan go go-loop sub pub close! put!]]))

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

(declare register-channel find-channel app)

(defrecord MessengerImpl [host port stop-fn receive-channel]
  m/IReceiver
  (receive-chan [this] receive-channel)
  (stop-receiver [this] 
                 (close! receive-channel)
                 (stop-fn :timeout 100))
  m/ISender
  (send-message [this msg]
                (if-let [c (or (some-> this :connection :channel)
                               (-> msg :target find-channel))]
                  (http/send! c (json/write-str msg))
                  (throw (Exception. (str "Cannot find channel for target " (:target msg)))))))

(defn create-messenger
  [& {:keys [port host], :or {port 8081 host "0.0.0.0"} :as opts}]
  (let [stop (http/run-server #'app {:port port :host host})
        messenger (m/create-messenger "server-messenger"
                                      (->MessengerImpl host port stop (chan)))]
    (m/add-service messenger "register"
                   (fn [server msg]
                     (if-let [c (some-> server :impl :connection :channel)]
                       (do
                         (register-channel server (:sender msg) c)
                         (m/answer server msg :OK false))
                       (m/answer server msg :error "Registering channel failed"))))
    messenger))

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

(defroutes all-routes
;   (GET "/" [] show-landing-page)
  (GET "/ws" [] websocket-service-handler)
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
 )
