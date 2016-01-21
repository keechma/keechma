(ns ashiba.test.service
  (:require [cljs.test :refer-macros [deftest is async]]
            [ashiba.service :as service]
            [cljs.core.async :refer [<! >! chan close! sliding-buffer put! alts! timeout]])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))


(defrecord FooService [out-chan currently-running-service]
  service/IService)

(deftest service-default-behavior []
  (let [out-chan (chan)
        service-cache (atom nil)
        currently-running-service (fn [] @service-cache)
        foo-service (->FooService out-chan currently-running-service)]
    (reset! service-cache foo-service)
    (is (service/is-running? foo-service))
    (service/send-command foo-service :command-name [:command-args])
    (async done
           (go
             (let [[command-name command-args] (<! out-chan)]
               (is (= command-name :command-name))
               (is (= command-args [:command-args]))
               (done))))))
