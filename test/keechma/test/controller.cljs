(ns keechma.test.controller
  (:require [cljs.test :refer-macros [deftest is async]]
            [keechma.controller :as controller]
            [cljs.core.async :refer [<! >! chan close! sliding-buffer put! alts! timeout]])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))


(defrecord FooController [out-chan running name]
  controller/IController)

(defrecord DispatcherController [name in-chan]
  controller/IController
  (handler [_ app-db-atom in-chan _]
    (controller/dispatcher app-db-atom in-chan
                           {:baz-command (fn [app-db-atom]
                                           (swap! app-db-atom assoc :called true))})))

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


(deftest controller-dispatcher []
  (let [app-db (atom)
        in-chan (chan)
        dispatcher-controller (->DispatcherController :dispatcher in-chan)] 
    (controller/handler dispatcher-controller app-db in-chan nil)
    (put! in-chan [:baz-command])
    (async done
           (go
             (<! (timeout 1))
             (is (= true (:called @app-db)))
             (done)))))
