(ns keechma.test.controller-manager
  (:require [cljs.test :refer-macros [deftest is async]]
            [keechma.controller :as controller]
            [keechma.util :refer [animation-frame]]
            [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.controller-manager :as controller-manager])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))

;; Setup ---------------------------------------------

(defrecord FooController []
  controller/IController
  (start [_ params state]
    (let [runs (or (:runs state) 0)]
      (merge state {:params params :runs (inc runs) :state :started})))
  (stop [_ params state]
    (assoc state :state :stopped)))

(def foo-controller (assoc (->FooController) :in-chan (chan)))

(defn add-to-log [state msg]
  (assoc state :log (conj (or (:log state) []) msg)))

(defrecord UsersController []
  controller/IController
  (params [_ route-params]
    (when (= (:page route-params) "users")
      true))
  (start [_ params state]
    (add-to-log state [:users :start]))
  (stop [_ params state]
    (add-to-log state [:users :stop])))

(defrecord CurrentUserController []
  controller/IController
  (params [_ route-params]
    (:user-id route-params))
  (start [_ params state]
    (add-to-log state [:current-user :start]))
  (handler [this app-db in-chan out-chan]
    (go
      (let [[command args] (<! in-chan)]
        (reset! app-db (add-to-log @app-db [:users :command command])))))
  (stop [this params state]
    (do
      (controller/execute this :stop)
      (add-to-log state [:current-user :stop]))))

(defrecord SessionController []
  controller/IController
  (params [_ _] true)
  (start [this params state]
    (do
      (controller/execute this :start) 
      (add-to-log state [:session :start])))
  (handler [this app-db in-chan out-chan]
    (go (loop []
          (let [[command args] (<! in-chan)]
            (reset! app-db (add-to-log @app-db [:session :command command]))
            (when command (recur)))))))

;; End Setup -----------------------------------------

(deftest app-state []
  (let [route-chan (chan)
        commands-chan (chan)
        app-db (atom {})
        controllers {:users (->UsersController)
                     :current-user (->CurrentUserController)
                     :session (->SessionController)}
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
              ;; I would expect this to be earlier in the log,
              ;; but it seems that because of the core.async timing
              ;; it ends up here. Doesn't matter though, it's important
              ;; that the command was called.
              [:users :command :stop]
              [:session :command :route-changed]]]
        get-log (fn [indices]
                  (apply concat (map (fn [i]
                                       (get log i)) indices)))]
    (async done
           (go
             (reset! app-instance (controller-manager/start route-chan commands-chan app-db controllers (fn [_ _ _ _ _ _ _])))
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
