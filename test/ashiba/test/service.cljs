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
    (service/send-update foo-service (fn [] :schedule-update-fn))
    (service/send-update foo-service (fn [] :immediate-update-fn) true)
    (async done
           (go
             (let [[command-name-1 command-args] (<! out-chan)
                   [command-name-2 update-1] (<! out-chan)
                   [command-name-3 update-2] (<! out-chan)]
               (is (= command-name-1 :command-name))
               (is (= command-args [:command-args]))
               (is (= command-name-2 :schedule-update))
               (is (= (update-1) :schedule-update-fn))
               (is (= command-name-3 :immediate-update))
               (is (= (update-2) :immediate-update-fn))
               (done))))))
