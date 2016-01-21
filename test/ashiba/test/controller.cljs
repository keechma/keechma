(ns ashiba.test.controller
  (:require [cljs.test :refer-macros [deftest is async]]
            [ashiba.controller :as controller]
            [cljs.core.async :refer [<! >! chan close! sliding-buffer put! alts! timeout]])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))


(defrecord FooController [out-chan currently-running-controller]
  controller/IController)

(deftest controller-default-behavior []
  (let [out-chan (chan)
        controller-cache (atom nil)
        currently-running-controller (fn [] @controller-cache)
        foo-controller (->FooController out-chan currently-running-controller)]
    (reset! controller-cache foo-controller)
    (is (controller/is-running? foo-controller))
    (controller/send-command foo-controller :command-name [:command-args])
    (async done
           (go
             (let [[command-name command-args] (<! out-chan)]
               (is (= command-name :command-name))
               (is (= command-args [:command-args]))
               (done))))))
