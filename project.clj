(defproject org.rksm/cloxp-com "0.1.9-SNAPSHOT"
  :description "intra- and inter-system messaging for cloxp"
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :url "https://github.com/cloxp/cloxp-com"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/clojurescript "1.7.48"]
                 [http-kit "2.1.19"]
                 [compojure "1.4.0"]
                 [javax.servlet/servlet-api "2.5"]
                 [stylefruits/gniazdo "0.4.0"]
                 [jarohen/chord "0.6.0" :exclusions [com.cemerick/clojurescript.test
                                                     com.cognitect/transit-clj]]
                 [org.clojure/data.json "0.2.6"]
                 [com.lucasbradstreet/cljs-uuid-utils "1.0.2"]
                 [medley/medley "0.7.0"]
                 [org.rksm/system-files "0.1.7-SNAPSHOT"]]
  :source-paths ["src/clj" "src/cljs"]
  :test-paths ["test/clj" "test/cljs"]
  :clean-targets [:target-path "cloxp-cljs-build"]
  :profiles {:dev {:dependencies [[com.cemerick/clojurescript.test "0.3.3"]
                                  [org.rksm/cljs-slimerjs-tester "0.1.1-SNAPSHOT"]]
                   :plugins [[lein-cljsbuild "1.0.4"]]
                   :aliases {"cleanbuild" ["do" "clean," "cljsbuild" "once"]
                             "cleantest" ["do" "cleanbuild," "test"]
                             "cleaninstall" ["do" "cleanbuild," "install"]
                             "cleandeploy" ["do" "cleanbuild," "deploy" "clojars"]}}}
  :cljsbuild {:builds {:default {:source-paths ["src/cljs/" "test/cljs/"]
                                 :compiler {:output-to "cloxp-cljs-build/cloxp-cljs.js",
                                            :output-dir "cloxp-cljs-build/out",
                                            :optimizations :none,
                                            :cache-analysis true,
                                            :warnings true
                                            :verbose true}}}
              :test-commands {"unit-tests" ["slimerjs" "public/slimerjs-test.js"]}})
