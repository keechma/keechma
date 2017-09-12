(ns keechma.test.controller.test-helpers
  (:require [cljs.test :refer-macros [deftest is async]]
            [keechma.controller :as controller]
            [keechma.controller.test-helpers :as controller-th]
            [cljs.core.async :refer [<! timeout]])
  (:require-macros [cljs.core.async.macros :refer [go-loop go]]))

(defrecord StartStopController [])

(defmethod controller/params StartStopController [ctrl route-params]
  {:some {:route :params}})

(defmethod controller/start StartStopController [ctrl params app-db]
  (assoc app-db :start-params params))

(defmethod controller/stop StartStopController [ctrl params app-db]
  (assoc app-db :stop-params params))

(deftest start-stop-controller
  (let [ctrl (controller-th/start! (->StartStopController))]
    (controller-th/stop! ctrl)
    (is (= {:start-params {:some {:route :params}}
            :stop-params {:some {:route :params}}})
        (controller-th/app-db ctrl))
    (controller-th/teardown! ctrl)))

(defrecord HandlerController [])

(defmethod controller/params HandlerController [ctrl route-params]
  true)

(defmethod controller/handler HandlerController [ctrl app-db-atom in-chan out-chan]
  (go-loop []
    (let [[cmd payload] (<! in-chan)]
      (case cmd
        :foo (swap! app-db-atom assoc :foo payload)
        nil)
      (when cmd
        (recur)))))

(deftest handler-controller
  (async done
         (let [ctrl (controller-th/start! (->HandlerController))]
           (go
             (controller/execute ctrl :foo :bar)
             (controller-th/stop! ctrl)
             (controller-th/teardown! ctrl)
             (<! (timeout 1))
             (is (= [[:foo :bar]]
                    (:in-chan (controller-th/log ctrl))))
             (is (= {:foo :bar}
                    (controller-th/app-db ctrl)))
             (done)))))

(defrecord ExecuteStartController [])

(defmethod controller/params ExecuteStartController [ctrl route-params]
  true)

(defmethod controller/handler ExecuteStartController [ctrl app-db-atom in-chan out-chan]
  (go-loop []
    (let [[cmd payload] (<! in-chan)]
      (case cmd
        :foo (js/setTimeout #(swap! app-db-atom assoc :foo payload) 100)
        nil)
      (when cmd
        (recur)))))

(defmethod controller/start ExecuteStartController [ctrl params app-db]
  (controller/execute ctrl :foo :bar)
  app-db)

(deftest execute-start-controller
  (async done
         (let [ctrl (controller-th/start! (->ExecuteStartController))]
           (go
             (controller-th/stop! ctrl)
             (controller-th/teardown! ctrl)
             (<! (timeout 120))
             (is (= [[:foo :bar]]
                    (:in-chan (controller-th/log ctrl))))
             (is (= {:foo :bar}
                    (controller-th/app-db ctrl)))
             (done)))))


(defrecord ExecuteStopController [])

(defmethod controller/params ExecuteStopController [ctrl route-params]
  true)

(defmethod controller/handler ExecuteStopController [ctrl app-db-atom in-chan out-chan]
  (go-loop []
    (let [[cmd payload] (<! in-chan)]
      (case cmd
        :foo (js/setTimeout #(swap! app-db-atom assoc :foo payload) 100)
        nil)
      (when cmd
        (recur)))))

(defmethod controller/stop ExecuteStopController [ctrl params app-db]
  (controller/execute ctrl :foo :bar)
  app-db)

(deftest execute-stop-controller
  (async done
         (let [ctrl (controller-th/start! (->ExecuteStopController))]
           (go
             (controller-th/stop! ctrl)
             (controller-th/teardown! ctrl)
             (<! (timeout 120))
             (is (= [[:foo :bar]]
                    (:in-chan (controller-th/log ctrl))))
             (is (= {:foo :bar}
                    (controller-th/app-db ctrl)))
             (done)))))

(defrecord SendCommandController [])

(defmethod controller/params SendCommandController [ctrl route-params]
  true)

(defmethod controller/start SendCommandController [ctrl params app-db]
  (controller/send-command ctrl [:topic :command] :payload)
  app-db)

(deftest send-command-controller
  (async done
         (go
           (let [ctrl (controller-th/start! (->SendCommandController))]
             (controller-th/stop! ctrl)
             (controller-th/teardown! ctrl)
             (<! (timeout 1))
             (is (= [[[:topic :command] :payload]]
                    (:out-chan (controller-th/log ctrl))))
             (done)))))


(defrecord RedirectController [])

(defmethod controller/params RedirectController [ctrl route-params]
  true)

(defmethod controller/handler RedirectController [ctrl app-db-atom in-chan out-chan]
  (controller/redirect ctrl {:page "about"}))

(deftest redirect-controller
  (let [ctrl (controller-th/start! (->RedirectController))]
    (controller-th/stop! ctrl)
    (controller-th/teardown! ctrl)
    (is (= [{:page "about"}]
           (:route (controller-th/log ctrl))))))


(defrecord ContextController [])

(defmethod controller/params ContextController [ctrl route-params]
  true)

(defmethod controller/handler ContextController [ctrl app-db-atom in-chan out-chan]
  (let [processor (controller/context ctrl :processor)]
    (go-loop []
      (let [[cmd payload] (<! in-chan)]
        (case cmd
          :foo (swap! app-db-atom assoc :foo (processor payload))
          nil)
        (when cmd
          (recur))))))

(deftest context-controller
  (async done
         (go
           (let [ctrl (controller-th/start! (->ContextController)
                                            {}
                                            {:processor inc})]
             (controller/execute ctrl :foo 1)
             (controller-th/stop! ctrl)
             (controller-th/teardown! ctrl)
             (<! (timeout 1))
             (is (= {:foo 2} (controller-th/app-db ctrl)))
             (done)))))
