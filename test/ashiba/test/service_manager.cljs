(ns ashiba.test.service-manager
  (:require [cljs.test :refer-macros [deftest is]]
            [ashiba.service :as service]
            [ashiba.service-manager :as service-manager]))

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
  (defrecord Foo1 []
    service/IService
    (start [_ params state]
      (merge state {:params params :state :started})))
  (let [foo-service (->Foo1)
        new-state (service-manager/start-service {:what :that} :foo foo-service {:foo :bar})]
    (is (= (dissoc new-state :running-services) {:what :that :params {:foo :bar} :state :started}))
    (is (instance? Foo1 (get-in new-state [:running-services :foo])))
    (is (= (get-in new-state [:running-services :foo :params]) {:foo :bar}))))

(deftest stop-service []
  (defrecord Foo2 []
    service/IService
    (stop [_ params state]
      (assoc state :state :stopped)))
  (let [foo-service (->Foo2)
        new-state (service-manager/stop-service {:what :that :running-services {:foo foo-service}} :foo foo-service)]
    (is (= new-state {:what :that :state :stopped :running-services {}}))))

(deftest restart-service []
  (defrecord Foo3 []
    service/IService
    (start [_ params state]
      (let [runs (or (:runs state) 0)]
        (merge state {:params params :runs (inc runs) :state :started})))
    (stop [_ params state]
      (assoc state :state :stopped)))
  (let [foo-service (->Foo3)
        started-state (service-manager/start-service {:what :that} :foo foo-service {:start 1})
        restarted-state (service-manager/restart-service started-state :foo foo-service {:start 2})]
    (is (= (dissoc restarted-state :running-services) {:what :that :params {:start 2} :state :started :runs 2}))
    (is (instance? Foo3 (get-in restarted-state [:running-services :foo])))
    (is (= (get-in restarted-state [:running-services :foo :params]) {:start 2}))))

