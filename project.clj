(defproject websocket-test "1.0.0-SNAPSHOT"
  :description "FIXME: write description"
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/clojurescript "0.0-3126"]
                 [http-kit "2.1.19"]
                 [compojure/compojure "1.3.2"]
                 [javax.servlet/servlet-api "2.5"]
                 [stylefruits/gniazdo "0.4.0"]
                 [jarohen/chord "0.6.0"]
                 [org.clojure/data.json "0.2.3"]
                 [org.clojars.franks42/cljs-uuid-utils "0.1.3"]
                 [com.cognitect/transit-cljs "0.8.205"]
                 [figwheel/figwheel "0.2.5-SNAPSHOT"]
                 [figwheel-sidecar/figwheel-sidecar "0.2.5-SNAPSHOT"]]
  :source-paths ["src/clj" "src/cljs" "target/classes"]
  :test-paths ["test/clj" "test/cljs"]
  :auto-clean false ; for cljx
  :cljx {:builds [{:source-paths ["src/clj" "test/clj" "src/cljs" "test/cljs"]
                   :output-path "target/classes"
                   :rules :clj}
                  {:source-paths ["src/clj" "test/clj" "src/cljs" "test/cljs"]
                   :output-path "target/classes"
                   :rules :cljs}]}
  ; :dev-dependencies [[com.cemerick/clojurescript.test "0.3.3"]]
  :profiles {:dev {:dependencies [[com.cemerick/clojurescript.test "0.3.3"]
                                  [rksm/subprocess "0.1.2"]
                                  [org.rksm/cloxp-projects "0.1.2-SNAPSHOT"]]
                   :plugins [[lein-cljsbuild "1.0.4"]
                             [com.keminglabs/cljx "0.6.0"]
                             [lein-figwheel "0.2.5-SNAPSHOT"]]
                   :aliases {"cleantest" ["do"
                                          "clean,"
                                          "cljx" "once,"
                                          "cljsbuild" "once,"
                                          "test"]
                             "cleaninstall" ["do" "cleantest," "install"]
                             "deploy" ["do" "clean," "cljx" "once," "deploy" "clojars"]}}}
  :cljsbuild {:builds {:default {:source-paths ["src/cljs/" "test/cljs/" "target/classes/"]
                                 :compiler {:output-to "cloxp-cljs-build/cloxp-cljs.js",
                                            :output-dir "cloxp-cljs-build/out",
                                            :optimizations :none,
                                            :cache-analysis true,
                                            :warnings true}}}
              :test-commands {"unit-tests" ["slimerjs" "public/slimerjs-test.js"]}})
