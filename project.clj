(defproject org.rksm/cloxp-com "0.1.8"
  :description "intra- and inter-system messaging for cloxp"
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/clojurescript "0.0-3211"]
                 [http-kit "2.1.19"]
                 [compojure/compojure "1.3.2"]
                 [javax.servlet/servlet-api "2.5"]
                 [stylefruits/gniazdo "0.4.0"]
                 [jarohen/chord "0.6.0" :exclusions [com.cemerick/clojurescript.test]]
                 [org.clojure/data.json "0.2.3"]
                 [org.clojars.franks42/cljs-uuid-utils "0.1.3"]
                 [com.cognitect/transit-cljs "0.8.205"]
                 [medley/medley "0.5.5"]]
  :source-paths ["src/clj" "src/cljs" "target/classes"]
  :test-paths ["test/clj" "test/cljs"]
  :profiles {:dev {:dependencies [[com.cemerick/clojurescript.test "0.3.3"]
                                  [org.rksm/cljs-slimerjs-tester "0.1.0"]]
                   :plugins [[lein-cljsbuild "1.0.4"]
                             [com.keminglabs/cljx "0.6.0"]]
                   :aliases {"cleanbuild" ["do" "clean," "cljx" "once," "cljsbuild" "once"]
                             "cleantest" ["do" "cleanbuild," "test"]
                             "cleaninstall" ["do" "cleanbuild," "install"]
                             "cleandeploy" ["do" "cleanbuild," "deploy" "clojars"]}}}
  :auto-clean false ; for cljx
  :cljx {:builds [{:source-paths ["src/clj" "test/clj" "src/cljs" "test/cljs"]
                   :output-path "target/classes"
                   :rules :clj}
                  {:source-paths ["src/clj" "test/clj" "src/cljs" "test/cljs"]
                   :output-path "target/classes"
                   :rules :cljs}]}
  :cljsbuild {:builds {:default {:source-paths ["src/cljs/" "test/cljs/" "target/classes/"]
                                 :compiler {:output-to "cloxp-cljs-build/cloxp-cljs.js",
                                            :output-dir "cloxp-cljs-build/out",
                                            :optimizations :none,
                                            :cache-analysis true,
                                            :warnings true}}}
              :test-commands {"unit-tests" ["slimerjs" "public/slimerjs-test.js"]}})
