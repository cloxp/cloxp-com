(ns rksm.websocket-test.server
  (:use org.httpkit.server))

(defonce server (atom nil))

(defn app-simple-http [req]
  {:status  200
  :headers {"Content-Type" "text/html"}
  :body    "hello2 HTTP!"} )

(defn app-streaming-response [req]
  (with-channel req ch
    (future
     (Thread/sleep 1000)
     (send! ch {:status  200
                :headers {"Content-Type" "text/html"}
                :body "fooo abbbrrrr"}))))

(defn app [req]
  (with-channel req channel    
    (on-close channel (fn [status]
                        (println "channel closed")))
    (if (websocket? channel)
      (println "WebSocket channel")
      (println "HTTP channel"))
    (on-receive channel (fn [data]
                          (send! channel data)))))


(defn stop-server []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))

(defn start-server []
  (reset! server (run-server #'app {:port 8081})))

(comment
 (start-server)
 (stop-server)
 server
 )
