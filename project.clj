(defproject keechma/keechma "1.0.0-SNAPSHOT"
  :description "Frontend micro framework for ClojureScript and Reagent"
  :url "http://github.com/keechma/keechma"
  :license {:name "MIT"}

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.597"]
                 [org.clojure/core.async "0.7.559"]
                 [keechma/router "0.1.5"]
                 [reagent "0.10.0"]
                 [cljsjs/react "16.13.0-0"]
                 [cljsjs/react-dom "16.13.0-0"]
                 [cljsjs/react-dom-server "16.13.0-0"]
                 [com.stuartsierra/dependency "0.2.0"]
                 [com.cognitect/transit-cljs "0.8.256"]]

  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-figwheel "0.5.19"]
            [lein-doo "0.1.11"]
            [lein-codox "0.9.3"]]

  :source-paths ["src"]

  :codox {:language :clojurescript
          :metadata {:doc/format :markdown}
          :namespaces [keechma.app-state keechma.controller keechma.controller-manager keechma.ui-component]}

  :clean-targets ^{:protect false} ["resources/public/js/compiled" "target"]

  :aliases {"test" ["with-profile" "test" "doo" "chrome" "test"]}

  :profiles {:test {:dependencies [[prismatic/dommy "1.1.0"]
                                   [lein-doo "0.1.11"]
                                   [cljsjs/jquery "3.4.0-0"]
                                   [funcool/promesa "1.9.0"]]}}

  :cljsbuild {:builds
              [{:id "test"
                :source-paths ["src" "test"]
                :compiler {:output-to "resources/public/js/compiled/test.js"
                           :optimizations :none
                           :main keechma.test.core
                           :install-deps true
                           :npm-deps {:syn "0.14.1"
                                      :karma "^0.13.22"
                                      :karma-chrome-launcher "^0.2.2"
                                      :karma-cljs-test "^0.1.0"
                                      :resolve "^1.5.0"}}}]})
