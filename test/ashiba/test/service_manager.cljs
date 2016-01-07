(ns ashiba.test.service-manager
  (:require [cljs.test :refer-macros [deftest is]]
            [ashiba.service :as service]
            [ashiba.service-manager :as service-manager]))

;; Setup ---------------------------------------------

(defrecord FooService []
    service/IService
    (start [_ params state]
      (let [runs (or (:runs state) 0)]
        (merge state {:params params :runs (inc runs) :state :started})))
    (stop [_ params state]
      (assoc state :state :stopped)))

(def foo-service (->FooService))

;; End Setup -----------------------------------------

(deftest services-actions []
  (let [running-services {:news {:params {:page 1 :per-page 10}}
                          :users {:params true}
                          :comments {:params {:news-id 1}}}
        services {:news {:page 2 :per-page 10}
                  :users true
                  :category {:id 1}
                  :comments nil
                  :image-gallery nil}
        services-actions (service-manager/services-actions
                          running-services
                          services)]
    (is (= services-actions {:news :restart
                            :comments :stop
                            :category :start}))))

(deftest start-service []
  (let [new-state (service-manager/start-service {:what :that} :foo foo-service {:foo :bar})]
    (is (= (dissoc new-state :running-services) {:what :that :params {:foo :bar} :runs 1 :state :started}))
    (is (instance? FooService (get-in new-state [:running-services :foo])))
    (is (= (get-in new-state [:running-services :foo :params]) {:foo :bar}))))

(deftest stop-service [] 
  (let [new-state (service-manager/stop-service {:what :that :running-services {:foo foo-service}} :foo foo-service)]
    (is (= new-state {:what :that :state :stopped :running-services {}}))))

(deftest restart-service []
  (let [started-state (service-manager/start-service {:what :that} :foo foo-service {:start 1})
        restarted-state (service-manager/restart-service started-state :foo foo-service {:start 2})]
    (is (= (dissoc restarted-state :running-services) {:what :that :params {:start 2} :state :started :runs 2}))
    (is (instance? FooService (get-in restarted-state [:running-services :foo])))
    (is (= (get-in restarted-state [:running-services :foo :params]) {:start 2}))))

