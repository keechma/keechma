(ns ashiba.test.service-manager
  (:require [cljs.test :refer-macros [deftest is async]]
            [ashiba.service :as service]
            [ashiba.util :refer [animation-frame]]
            [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [ashiba.service-manager :as service-manager])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))

;; Setup ---------------------------------------------

(defrecord FooService []
  service/IService
  (start [_ params state]
    (let [runs (or (:runs state) 0)]
      (merge state {:params params :runs (inc runs) :state :started})))
  (stop [_ params state]
    (assoc state :state :stopped)))

(def foo-service (assoc (->FooService) :in-chan (chan)))

(defn add-to-log [state msg]
  (assoc state :log (conj (or (:log state) []) msg)))

(defrecord UsersService []
  service/IService
  (params [_ route-params]
    (when (= (:page route-params) "users")
      true))
  (start [_ params state]
    (add-to-log state [:users :start]))
  (stop [_ params state]
    (add-to-log state [:users :stop])))

(defrecord CurrentUserService []
  service/IService
  (params [_ route-params]
    (:user-id route-params))
  (start [_ params state]
    (add-to-log state [:current-user :start]))
  (stop [_ params state]
    (add-to-log state [:current-user :stop])))

(defrecord SessionService []
  service/IService
  (params [_ _] true)
  (start [_ params state]
    (add-to-log state [:session :start]))
  (handler [this app-db in-chan out-chan]
    (go (loop []
          (let [[command args] (<! in-chan)]
            (reset! app-db (add-to-log @app-db [:session :command command]))
            (when command (recur)))))))

;; End Setup -----------------------------------------

(deftest services-actions []
  (let [running-services {:news {:params {:page 1 :per-page 10} :in-chan (chan)}
                          :users {:params true :in-chan (chan)}
                          :comments {:params {:news-id 1} :in-chan (chan)}}
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
                            :category :start
                            :users :route-changed}))))

(deftest start-service []
  (let [new-state (service-manager/start-service {:what :that} :foo foo-service {:foo :bar} {:commands-chan (chan)})]
    (is (= (dissoc new-state :internal) {:what :that :params {:foo :bar} :runs 1 :state :started}))
    (is (instance? FooService (get-in new-state [:internal :running-services :foo])))
    (is (= (get-in new-state [:internal :running-services :foo :params]) {:foo :bar}))))

(deftest stop-service [] 
  (let [new-state (service-manager/stop-service {:what :that :internal {:running-services {:foo foo-service}}} :foo foo-service)]
    (is (= new-state {:what :that :state :stopped :internal {:running-services {}}}))))

(deftest restart-service []
  (let [started-state (service-manager/start-service {:what :that} :foo foo-service {:start 1} {:commands-chan (chan)})
        started-service (get-in started-state [:internal :running-services :foo])
        restarted-state (service-manager/restart-service started-state :foo started-service foo-service {:start 2} {:commands-chan (chan)})]
    (is (= (dissoc restarted-state :internal) {:what :that :params {:start 2} :state :started :runs 2}))
    (is (instance? FooService (get-in restarted-state [:internal :running-services :foo])))
    (is (= (get-in restarted-state [:internal :running-services :foo :params]) {:start 2}))))

(deftest app-state []
  (let [route-chan (chan)
        commands-chan (chan)
        app-db (atom {})
        services {:users (->UsersService)
                  :current-user (->CurrentUserService)
                  :session (->SessionService)}
        app-instance (atom nil)
        log [;; first route
             [[:users :start]
              [:session :start]
              [:session :command :start]]

             ;; second route
             [[:users :stop]
              [:current-user :start]
              [:session :command :route-changed]]

             ;; command sent to session
             [[:session :command :test-command]]

             ;; third route
             [[:current-user :stop]
              [:current-user :start]
              [:session :command :route-changed]]]
        get-log (fn [indices]
                  (apply concat (map (fn [i]
                                       (get log i)) indices)))]
    (async done
           (go
             (reset! app-instance (service-manager/start route-chan commands-chan app-db services))
             (>! route-chan {:page "users"})
             (<! (animation-frame 2))
             (is (= (get-log [0]) (:log @app-db)))
             (>! route-chan {:page "current-user" :user-id 1})
             (<! (animation-frame 2))
             (is (= (get-log (range 2)) (:log @app-db)))
             (>! commands-chan [[:session :test-command] "some argument"])
             (<! (animation-frame 2))
             (is (= (get-log (range 3)) (:log @app-db)))
             (>! route-chan {:page "current-user" :user-id 2})
             (<! (animation-frame 2))
             (is (= (get-log (range 4)) (:log @app-db)))
             ((:stop @app-instance))
             (done)))))
