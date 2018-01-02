(ns keechma.test.controller-manager
  (:require [cljs.test :refer-macros [deftest is async]]
            [keechma.controller :as controller]
            [keechma.util :refer [animation-frame]]
            [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.controller-manager :as controller-manager])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))

;; Setup ---------------------------------------------

(defrecord FooController [])

(defmethod controller/start FooController [_ params state]
  (let [runs (or (:runs state) 0)]
    (merge state {:params params :runs (inc runs) :state :started})))

(defmethod controller/stop FooController [_ params state]
  (assoc state :state :stopped))

(def foo-controller (assoc (->FooController) :in-chan (chan)))

(defn add-to-log [state msg]
  (assoc state :log (conj (or (:log state) []) msg)))

(defrecord UsersController [])
 
(defmethod controller/params UsersController [_ route-params]
  (when (= (:page route-params) "users")
    true))
(defmethod controller/start UsersController [_ params state]
  (add-to-log state [:users :start]))
(defmethod controller/stop UsersController [_ params state]
  (add-to-log state [:users :stop]))



(defrecord CurrentUserController [])

(defmethod controller/params CurrentUserController [_ route-params]
  (when (= "current-user" (:page route-params))
    (:user-id route-params)))
(defmethod controller/start CurrentUserController [_ params state]
  (add-to-log state [:current-user :start]))
(defmethod controller/handler CurrentUserController [this app-db in-chan out-chan]
  (go
    (loop []
      (let [[command args] (<! in-chan)]
        (when command
          (reset! app-db (add-to-log @app-db [:current-user :command command]))
          (when (= :stop command)
            ;; Channel should be closed before this can be put on it
            (put! in-chan [:this-should-be :ignored]))
          (recur))))))
(defmethod controller/stop CurrentUserController [this params state]
  (controller/execute this :stop)
  (add-to-log state [:current-user :stop]))

(defrecord SessionController [])
(defmethod controller/params SessionController [_ _] true)
(defmethod controller/start SessionController [this params state]
  (controller/execute this :start)
  (add-to-log state [:session :start]))
(defmethod controller/handler SessionController [this app-db in-chan out-chan]
  (go (loop []
        (let [[command args] (<! in-chan)]
          (reset! app-db (add-to-log @app-db [:session :command command]))
          (when command (recur))))))

(defrecord LoginController [])

(defmethod controller/params LoginController [_ route-params]
  (when (= "login" (:page route-params))
    (:user-id route-params)))
(defmethod controller/start LoginController [_ params state]
  (add-to-log state [:login :start]))
(defmethod controller/stop LoginController [this params state]
  (controller/execute this :stop)
  (add-to-log state [:login :stop]))

;; End Setup -----------------------------------------

(deftest app-state []
  (let [route-chan (chan)
        commands-chan (chan)
        app-db (atom {})
        controllers {:users (->UsersController)
                     :current-user (->CurrentUserController)
                     :session (->SessionController)}
        app-instance (atom nil)
        log [
             ;; app-start
             [[:session :start]]

             ;; after the session handler was called
             [[:session :command :start]]

              ;; first route
             [[:users :start]
              [:session :command :route-changed]]

             ;; second route
             [[:users :stop]
              [:current-user :start]
              [:session :command :route-changed]]

             ;; command sent to session
             [[:session :command :test-command]]

             ;; third route
             [[:current-user :stop]
              [:current-user :start]
              [:current-user :command :stop]
              [:session :command :route-changed]]]
        get-log (fn [indices]
                  (apply concat (map (fn [i]
                                       (get log i)) indices)))]
    (async done
           (go
             (reset! app-instance (controller-manager/start route-chan (fn [route _] route) commands-chan app-db controllers (fn [& args])))
             (is (= (get-log [0]) (:log @app-db)))
             (is (= [:session] (keys (get-in @app-db [:internal :running-controllers]))))
             (<! (timeout 20))
             (is (= (get-log (range 2)) (:log @app-db)))

             (>! route-chan {:page "users"})
             (<! (timeout 20))
             (is (= (get-log (range 3)) (:log @app-db)))
             (is (= (set [:users :session]) (set (keys (get-in @app-db [:internal :running-controllers])))))


             (>! route-chan {:page "current-user" :user-id 1})
             (<! (timeout 20))
             (is (= (get-log (range 4)) (:log @app-db)))
             (is (= (set [:current-user :session]) (set (keys (get-in @app-db [:internal :running-controllers])))))


             (>! commands-chan [[:session :test-command] "some argument"])
             (<! (timeout 20))
             (is (= (get-log (range 5)) (:log @app-db)))


             (>! route-chan {:page "current-user" :user-id 2})
             (<! (timeout 20))
             (is (= (get-log (range 6)) (:log @app-db)))
             (is (= (set [:current-user :session]) (set (keys (get-in @app-db [:internal :running-controllers])))))

             ((:stop @app-instance))
             (done)))))

(defn route-processor [route _]
  (if (and (= "current-user" (:page route))
           (= 1 (:user-id route)))
    {:page "login" :user-id 1}
    route))

(deftest app-state-with-route-processor []
  (let [route-chan (chan)
        commands-chan (chan)
        app-db (atom {})
        controllers {:users (->UsersController)
                     :current-user (->CurrentUserController)
                     :login (->LoginController)
                     :session (->SessionController)
                     }
        app-instance (atom nil)
        log [
             ;; app-start
             [[:session :start]]

             ;; after the session handler was called
             [[:session :command :start]]

              ;; first route
             [[:users :start]
              [:session :command :route-changed]]

             ;; second route
             [[:users :stop]
              [:login :start]
              [:session :command :route-changed]]

             ;; command sent to session
             [[:session :command :test-command]]

             ;; third route
             [[:login :stop]
              [:current-user :start]
              [:session :command :route-changed]]]
        get-log (fn [indices]
                  (apply concat (map (fn [i]
                                       (get log i)) indices)))]
    (async done
           (go
             (reset! app-instance (controller-manager/start route-chan route-processor commands-chan app-db controllers (fn [& args])))
             (is (= (get-log [0]) (:log @app-db)))
             (is (= [:session] (keys (get-in @app-db [:internal :running-controllers]))))
             (<! (timeout 20))
             (is (= (get-log (range 2)) (:log @app-db)))

             (>! route-chan {:page "users"})
             (<! (timeout 20))
             (is (= (get-log (range 3)) (:log @app-db)))
             (is (= (set [:users :session]) (set (keys (get-in @app-db [:internal :running-controllers])))))


             (>! route-chan {:page "current-user" :user-id 1})
             (<! (timeout 20))
             (is (= (get-log (range 4)) (:log @app-db)))
             (is (= (set [:login :session]) (set (keys (get-in @app-db [:internal :running-controllers])))))


             (>! commands-chan [[:session :test-command] "some argument"])
             (<! (timeout 20))
             (is (= (get-log (range 5)) (:log @app-db)))


             (>! route-chan {:page "current-user" :user-id 2})
             (<! (timeout 20))
             (is (= (get-log (range 6)) (:log @app-db)))
             (is (= (set [:current-user :session]) (set (keys (get-in @app-db [:internal :running-controllers])))))

             ((:stop @app-instance))
             (done)))))
