(defproject keechma/keechma "0.3.9"
  :description "Frontend micro framework for ClojureScript and Reagent"
  :url "http://github.com/keechma/keechma"
  :license {:name "MIT"}

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.238"]
                 [reagent "0.7.0" :exclusions [cljsjs/react cljsjs/react-dom]]
                 [cljsjs/react-with-addons "15.6.1-0"]
                 [cljsjs/react-dom "15.6.1-0" :exclusions [cljsjs/react]]
                 [cljsjs/react-dom-server "15.6.1-0" :exclusions [cljsjs/react]]
                 [org.clojars.mihaelkonjevic/cljs-react-test "0.1.5" :exclusions [cljsjs/react-with-addons]]
                 [prismatic/dommy "1.1.0"]
                 [lein-doo "0.1.7"]
                 [com.stuartsierra/dependency "0.2.0"]
                 [secretary "1.2.3"]
                 [keechma/router "0.1.1"]
                 [com.cognitect/transit-cljs "0.8.239"]
                 [syntest "0.1.0"]]

  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-figwheel "0.5.8"]
            [lein-doo "0.1.7"]
            [lein-codox "0.9.3"]]

  :source-paths ["src"]

  :codox {:language :clojurescript
          :metadata {:doc/format :markdown}
          :namespaces [keechma.app-state keechma.controller keechma.controller-manager keechma.ui-component]}

  :clean-targets ^{:protect false} ["resources/public/js/compiled" "target"]

  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src"]

                :compiler {:main keechma.core
                           :asset-path "js/compiled/out"
                           :output-to "resources/public/js/compiled/keechma.js"
                           :output-dir "resources/public/js/compiled/out"
                           :source-map-timestamp true}}
               ;; This next build is an compressed minified build for
               ;; production. You can build this with:
               ;; lein cljsbuild once min
               {:id "min"
                :source-paths ["src"]
                :compiler {:output-to "resources/public/js/compiled/keechma.js"
                           :main keechma.core
                           :optimizations :advanced
                           :pretty-print false}}
               {:id "test"
                :source-paths ["src" "test"]
                :compiler {:output-to "resources/public/js/compiled/test.js"
                           :optimizations :none
                           :main keechma.test.core
                           :install-deps true
                           :npm-deps {;;:syn "0.10.0"
                                      :karma "^0.13.16"
                                      :karma-chrome-launcher "^0.2.2"
                                      :karma-cljs-test "^0.1.0"}}}]}
  
  :figwheel {;; :http-server-root "public" ;; default and assumes "resources"
             ;; :server-port 3449 ;; default
             ;; :server-ip "127.0.0.1"

             :css-dirs ["resources/public/css"] ;; watch and update CSS

             ;; Start an nREPL server into the running figwheel process
             ;; :nrepl-port 7888

             ;; Server Ring Handler (optional)
             ;; if you want to embed a ring handler into the figwheel http-kit
             ;; server, this is for simple ring servers, if this
             ;; doesn't work for you just run your own server :)
             ;; :ring-handler hello_world.server/handler

             ;; To be able to open files in your editor from the heads up display
             ;; you will need to put a script on your path.
             ;; that script will have to take a file path and a line number
             ;; ie. in  ~/bin/myfile-opener
             ;; #! /bin/sh
             ;; emacsclient -n +$2 $1
             ;;
             ;; :open-file-command "myfile-opener"

             ;; if you want to disable the REPL
             ;; :repl false

             ;; to configure a different figwheel logfile path
             ;; :server-logfile "tmp/logs/figwheel-logfile.log"
             })
