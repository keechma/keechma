(ns keechma.test.controller
  (:require [cljs.test :refer-macros [deftest is async]]
            [keechma.controller :as controller]
            [cljs.core.async :refer [<! >! chan close! sliding-buffer put! alts! timeout]])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))


(defrecord FooController [out-chan running name]
  controller/IController)

(deftest controller-default-behavior []
  (let [out-chan (chan)
        app-db (atom {})
        running (fn [] (get-in @app-db [:internal :running-controllers :foo]))
        foo-controller (->FooController out-chan running :foo)]
    (reset! app-db {:internal {:running-controllers {:foo foo-controller}}})
    (is (controller/is-running? foo-controller))
    (controller/send-command foo-controller :command-name [:command-args])
    (async done
           (go
             (let [[command-name command-args] (<! out-chan)]
               (is (= command-name :command-name))
               (is (= command-args [:command-args]))
               (done))))))
