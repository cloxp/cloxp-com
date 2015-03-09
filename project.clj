(defproject websocket-test "1.0.0-SNAPSHOT"
 :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/clojurescript "0.0-3031"]
                 [jarohen/chord "0.6.0"]
                 [http-kit "2.1.16"]
                 [compojure/compojure "1.3.2"]
                 [javax.servlet/servlet-api "2.5"]
                 [stylefruits/gniazdo "0.3.1"]
                 [org.clojure/data.json "0.2.3"]
                 [org.clojars.franks42/cljs-uuid-utils "0.1.3"]
                 [com.cognitect/transit-cljs "0.8.205"]
                 [figwheel/figwheel "0.2.5-SNAPSHOT"]
                 [figwheel-sidecar/figwheel-sidecar "0.2.5-SNAPSHOT"]]
  :profiles {:dev {:plugins
                   [[lein-cljsbuild "1.0.4"] [lein-figwheel "0.2.5-SNAPSHOT"]],
                   :dependencies [[com.cemerick/clojurescript.test "0.3.3"]]}}
  :cljsbuild {:builds [{:id "example" 
                        :source-paths ["src/cljs/"]
                        :compiler {:output-to "fig-js/example.js"
                                   :output-dir "fig-js/out"
                                   ; :externs ["resources/public/js/externs/jquery-1.9.js"]
                                   :optimizations :none
                                   :source-map true}}]}
 :source-paths ["target/classes"])
