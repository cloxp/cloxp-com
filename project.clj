(defproject websocket-test "1.0.0-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/clojurescript "0.0-2905"]
                 [jarohen/chord "0.6.0"]
                 [http-kit "2.1.16"]
                 [compojure/compojure "1.3.2"]
                 [javax.servlet/servlet-api "2.5"]
                 [stylefruits/gniazdo "0.3.1"]
                 [org.clojure/data.json "0.2.3"]]
  :dev-dependencies [[com.cemerick/clojurescript.test "0.3.3"]]
  :source-paths ["target/classes"])
