(defproject keechma/keechma "0.1.0-SNAPSHOT"
  :description "Frontend micro framework for ClojureScript and Reagent"
  :url "http://github.com/keechma/keechma"
  :license {:name "MIT"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.170"]
                 [org.clojure/core.async "0.2.374"]
                 [reagent "0.6.0-alpha" :exclusions [cljsjs/react]]
                 [cljs-react-test "0.1.3-SNAPSHOT" :exclusions [cljsjs/react-with-addons]]
                 [prismatic/dommy "1.1.0"]
                 [cljsjs/react-with-addons "0.14.3-0"]
                 [funcool/cuerdas "0.7.0"]
                 [lein-doo "0.1.6"]
                 [com.stuartsierra/dependency "0.2.0"]
                 [secretary "1.2.3"]
                 [keechma/router "0.1.0"]
                 [keechma/entitydb "0.1.0"]]

  :plugins [[lein-cljsbuild "1.1.1"]
            [lein-figwheel "0.5.0-1"]
            [lein-doo "0.1.6"]
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
                :compiler {:output-to 
                           "resources/public/js/compiled/test.js"
                           :optimizations :none
                           :main keechma.test.core}}]}

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
