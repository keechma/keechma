(ns keechma.test.controller-manager
  (:require [cljs.test :refer-macros [deftest is async]]
            [keechma.controller :as controller]
            [keechma.util :refer [animation-frame]]
            [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.controller-manager :as controller-manager])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))

(defn get-running-controllers [app-db]
  (set (keys (get-in @app-db [:internal :running-controllers])))) 

(defn add-to-log [state msg]
  (assoc state :log (conj (or (:log state) []) msg)))

;; Setup ---------------------------------------------

(defrecord FooController [])

(defmethod controller/start FooController [_ params state]
  (let [runs (or (:runs state) 0)]
    (merge state {:params params :runs (inc runs) :state :started})))

(defmethod controller/stop FooController [_ params state]
  (assoc state :state :stopped))

(def foo-controller (assoc (->FooController) :in-chan (chan)))

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
             (is (= #{:session}(get-running-controllers app-db)))
             (<! (timeout 20))
             (is (= (get-log (range 2)) (:log @app-db)))

             (>! route-chan {:page "users"})
             (<! (timeout 20))
             (is (= (get-log (range 3)) (:log @app-db)))
             (is (= #{:users :session}(get-running-controllers app-db)))


             (>! route-chan {:page "current-user" :user-id 1})
             (<! (timeout 20))
             (is (= (get-log (range 4)) (:log @app-db)))
             (is (= (set [:current-user :session])(get-running-controllers app-db)))


             (>! commands-chan [[:session :test-command] "some argument"])
             (<! (timeout 20))
             (is (= (get-log (range 5)) (:log @app-db)))


             (>! route-chan {:page "current-user" :user-id 2})
             (<! (timeout 20))
             (is (= (get-log (range 6)) (:log @app-db)))
             (is (= #{:current-user :session}(get-running-controllers app-db)))

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
                  (apply concat (map (fn [i] (get log i)) indices)))]
    (async done
           (go
             (reset! app-instance (controller-manager/start route-chan route-processor commands-chan app-db controllers (fn [& args])))
             (is (= (get-log [0]) (:log @app-db)))
             (is (= #{:session}(get-running-controllers app-db)))
             (<! (timeout 20))
             (is (= (get-log (range 2)) (:log @app-db)))

             (>! route-chan {:page "users"})
             (<! (timeout 20))
             (is (= (get-log (range 3)) (:log @app-db)))
             (is (= #{:users :session}(get-running-controllers app-db)))


             (>! route-chan {:page "current-user" :user-id 1})
             (<! (timeout 20))
             (is (= (get-log (range 4)) (:log @app-db)))
             (is (= #{:login :session}(get-running-controllers app-db)))


             (>! commands-chan [[:session :test-command] "some argument"])
             (<! (timeout 20))
             (is (= (get-log (range 5)) (:log @app-db)))


             (>! route-chan {:page "current-user" :user-id 2})
             (<! (timeout 20))
             (is (= (get-log (range 6)) (:log @app-db)))
             (is (= #{:current-user :session}(get-running-controllers app-db)))

             ((:stop @app-instance))
             (done)))))

(defrecord StaticParamsController [])

(defmethod controller/params StaticParamsController [_ route-params]
  (when-let [page (:page route-params)]
    {:page page}))
(defmethod controller/start StaticParamsController [this params state]
  (add-to-log state [(:name this) :start params]))
(defmethod controller/stop StaticParamsController [this params state]
  (add-to-log state [(:name this) :stop params]))


(deftest static-params-override-params-fn
  (let [route-chan   (chan)
        commands-chan (chan)
        app-db        (atom {})
        controllers   {:dynamic-params (->StaticParamsController)
                       :static-params  (controller/assoc-static-params (->StaticParamsController) {:static/param 1})}
        app-instance  (atom nil)
        log           [[[:static-params :start {:static/param 1}]]

                       [[:dynamic-params :start {:page "articles"}]]

                       [[:dynamic-params :stop {:page "articles"}]
                        [:dynamic-params :start {:page "users"}]]
                       
                       [[:dynamic-params :stop {:page "users"}]]]
        get-log (fn [indices]
                  (apply concat (map (fn [i]
                                       (get log i)) indices)))]
    (async done
           (go
             (reset! app-instance (controller-manager/start route-chan (fn [route _] route) commands-chan app-db controllers (fn [& args])))
             (is (= (get-log [0]) (:log @app-db)))
             (is (= #{:static-params}(get-running-controllers app-db)))

             (>! route-chan {:page "articles"})
             (<! (timeout 20))
             (is (= (get-log (range 2)) (:log @app-db)))
             (is (= #{:static-params :dynamic-params}(get-running-controllers app-db)))

             (>! route-chan {:page "users"})
             (<! (timeout 20))
             (is (= (get-log (range 3)) (:log @app-db)))
             (is (= #{:static-params :dynamic-params}(get-running-controllers app-db)))

             (>! route-chan {})
             (<! (timeout 20))
             (is (= (get-log (range 4)) (:log @app-db)))
             (is (= #{:static-params}(get-running-controllers app-db)))
             
             ((:stop @app-instance))
             (done)))))

(defrecord TreeLeaf1Controller [])

(defmethod controller/params TreeLeaf1Controller [_ route-params]
  (:tab route-params))

(defmethod controller/start TreeLeaf1Controller [this params state]
  (add-to-log state [(:name this) :start params]))

(defmethod controller/handler TreeLeaf1Controller [this app-db$ in-chan out-chan]
  (controller/dispatcher
   app-db$
   in-chan
   {:register-child-1
    (fn [_]
      (try
        (controller/register-child-controller this :child-1-1 (->TreeLeaf1Controller) :qux)
        (catch :default e
          (swap! app-db$ add-to-log [(:name this) :error (:keechma.anomalies/category (ex-data e))]))))}))

(defmethod controller/stop TreeLeaf1Controller [this params state]
  (add-to-log state [(:name this) :stop params]))

(defrecord TreeRootController [])

(defmethod controller/params TreeRootController [_ route-params]
  (:page route-params))

(defmethod controller/start TreeRootController [this params state]
  (add-to-log state [(:name this) :start params]))

(defmethod controller/handler TreeRootController [this app-db$ in-chan out-chan]
  (controller/dispatcher
   app-db$
   in-chan
   {:register-child-1
    (fn [_]
      (try
        (controller/register-child-controller this :child-1 (->TreeLeaf1Controller) :foo)
        (catch :default e
          (swap! app-db$ add-to-log [(:name this) :error (:keechma.anomalies/category (ex-data e))]))))
    :register-child-2 
    (fn [_]
      (controller/register-child-controller this :child-2 (->TreeLeaf1Controller) :bar))
    :deregister-child-1
    (fn [_]
      (controller/deregister-child-controller this :child-1))
    :deregister-child-2
    (fn [_]
      (controller/deregister-child-controller this :child-2))
    :register-child-1-with-different-params
    (fn [_]
      (controller/register-child-controller this :child-1 (->TreeLeaf1Controller) :baz))
    :register-child-3-with-dynamic-params
    (fn [_]
      (controller/register-child-controller this :child-3 (->TreeLeaf1Controller)))
    :synchronize-child-controllers
    (fn [_]
      (controller/synchronize-child-controllers 
       this
       {:child-4 {:controller (->TreeLeaf1Controller)}
        :child-5 {:controller (->TreeLeaf1Controller)
                  :params :foobarbaz}
        :child-1 {:controller (->TreeLeaf1Controller)
                  :params true}}))}))

(defmethod controller/stop TreeRootController [this params state] 
  (add-to-log state [(:name this) :stop params]))

(defrecord TreeRoot2Controller [])

(defmethod controller/params TreeRoot2Controller [_ route-params]
  true)

(defmethod controller/start TreeRoot2Controller [this params state]
  (add-to-log state [(:name this) :start params]))

(defmethod controller/handler TreeRoot2Controller [this app-db$ in-chan out-chan]
  (controller/register-child-controller this :child-A (->TreeLeaf1Controller) :qux)
  (controller/dispatcher
   app-db$
   in-chan
   {:register-child-1
    (fn [_]
      (try
        (controller/register-child-controller this :child-1 (->TreeLeaf1Controller) :foo)
        (catch :default e
          (swap! app-db$ add-to-log [(:name this) :error/register (:keechma.anomalies/category (ex-data e))]))))
    :deregister-child-1
    (fn [_]
      (try
        (controller/deregister-child-controller this :child-1)
        (catch :default e
          (swap! app-db$ add-to-log [(:name this) :error/deregister (:keechma.anomalies/category (ex-data e))]))))}))

(deftest tree-controllers-1
  (let [route-chan   (chan)
        commands-chan (chan)
        app-db        (atom {})
        controllers   {:tree-root (->TreeRootController)
                       :tree-root-2 (->TreeRoot2Controller)}
        app-instance  (atom nil)
        log          [[[:tree-root-2 :start true]
                       [:child-A :start :qux]]

                      [[:tree-root :start "articles"]]
                      
                      [[:child-1 :start :foo]]

                      [[:child-2 :start :bar]]

                      [[:child-1 :stop :foo]
                       [:child-1 :start :baz]]

                      [[:child-3 :start "recent"]]

                      [[:child-3 :stop "recent"]]

                      [[:tree-root-2 :error/register :keechma.controller-manager/controller-ownership]]
                      
                      [[:tree-root-2 :error/deregister :keechma.controller-manager/controller-ownership]]

                      [[:child-1-1 :start :qux]]

                      [[:child-2 :stop :bar]]

                      [[:child-1-1 :stop :qux]
                       [:child-1 :stop :baz]
                       [:tree-root :stop "articles"]]

                      [[:tree-root :start "users"]]

                      [[:child-1 :start :foo]]
                      
                      [[:child-1-1 :start :qux]]

                      [[:child-1-1 :stop :qux]
                       [:child-1 :stop :foo]]] 
        get-log (fn [indices] (apply concat (map (fn [i] (get log i)) indices)))]
    (async done
           (go
             (reset! app-instance (controller-manager/start route-chan (fn [route _] route) commands-chan app-db controllers (fn [& args])))
             (is (= #{:tree-root-2 :child-A} (get-running-controllers app-db)))
             (is (= (get-log (range 1)) (:log @app-db)))

             (>! route-chan {:page "articles"})
             (<! (timeout 20))
             (is (= (get-log (range 2)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root :register-child-1]])
             (<! (timeout 20))
             (is (= (get-log (range 3)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root :register-child-2]])
             (<! (timeout 20))
             (is (= (get-log (range 4)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-2 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root :register-child-1-with-different-params]])
             (<! (timeout 20))
             (is (= (get-log (range 5)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-2 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root :register-child-3-with-dynamic-params]])
             (<! (timeout 20))
             (is (= (get-log (range 5)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-2 :child-A} (get-running-controllers app-db)))

             (>! route-chan {:page "articles" :tab "recent"})
             (<! (timeout 20))
             (is (= (get-log (range 6)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-2 :child-3 :child-A} (get-running-controllers app-db)))

             (>! route-chan {:page "articles"})
             (<! (timeout 20))
             (is (= (get-log (range 7)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-2 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root-2 :register-child-1]])
             (<! (timeout 20))
             (is (= (get-log (range 8)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-2 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root-2 :deregister-child-1]])
             (<! (timeout 20))
             (is (= (get-log (range 9)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-2 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:child-1 :register-child-1]])
             (<! (timeout 20))
             (is (= (get-log (range 10)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-2 :child-1-1 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root :deregister-child-2]])
             (<! (timeout 20))
             (is (= (get-log (range 11)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-1-1 :child-A} (get-running-controllers app-db)))

             (>! route-chan {})
             (<! (timeout 20))
             (is (= (get-log (range 12)) (:log @app-db)))
             (is (= #{:tree-root-2 :child-A} (get-running-controllers app-db)))

             (>! route-chan {:page "users"})
             (<! (timeout 20))
             (is (= (get-log (range 13)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root :register-child-1]])
             (<! (timeout 20))
             (is (= (get-log (range 14)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:child-1 :register-child-1]])
             (<! (timeout 20))
             (is (= (get-log (range 15)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-1-1 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root :deregister-child-1]])
             (<! (timeout 20))
             (is (= (get-log (range 16)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-A} (get-running-controllers app-db)))
                          
             ((:stop @app-instance))
             (done)))))

(deftest tree-controllers-2
  (let [route-chan   (chan)
        commands-chan (chan)
        app-db        (atom {})
        controllers   {:tree-root (->TreeRootController)
                       :tree-root-2 (->TreeRoot2Controller)}
        app-instance  (atom nil)
        log          [[[:tree-root-2 :start true]
                       [:child-A :start :qux]]

                      [[:tree-root :start "articles"]]
                      
                      [[:child-1 :start :foo]]

                      [[:child-2 :start :bar]]

                      [[:child-1 :stop :foo]
                       [:child-1 :start :baz]]

                      [[:child-3 :start "recent"]]

                      [[:child-1-1 :start :qux]]

                      ;; This part is slightly volatile as we are
                      ;; depending on the order in which the map will be iterated
                      ;; over. The only thing that actually matters is that the
                      ;; stop actions come before start actions, and that :child-1-1
                      ;; is stopped before :child-1
                      [[:child-2 :stop :bar]
                       [:child-3 :stop "recent"]
                       [:child-1-1 :stop :qux]
                       [:child-1 :stop :baz]
                       [:child-4 :start "recent"]
                       [:child-5 :start :foobarbaz]
                       [:child-1 :start true]]] 
        get-log (fn [indices] (apply concat (map (fn [i] (get log i)) indices)))]
    (async done
           (go
             (reset! app-instance (controller-manager/start route-chan (fn [route _] route) commands-chan app-db controllers (fn [& args])))
             (is (= #{:tree-root-2 :child-A} (get-running-controllers app-db)))
             (is (= (get-log (range 1)) (:log @app-db)))

             (>! route-chan {:page "articles"})
             (<! (timeout 20))
             (is (= (get-log (range 2)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root :register-child-1]])
             (<! (timeout 20))
             (is (= (get-log (range 3)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root :register-child-2]])
             (<! (timeout 20))
             (is (= (get-log (range 4)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-2 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root :register-child-1-with-different-params]])
             (<! (timeout 20))
             (is (= (get-log (range 5)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-2 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root :register-child-3-with-dynamic-params]])
             (<! (timeout 20))
             (is (= (get-log (range 5)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-2 :child-A} (get-running-controllers app-db)))

             (>! route-chan {:page "articles" :tab "recent"})
             (<! (timeout 20))
             (is (= (get-log (range 6)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-2 :child-3 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:child-1 :register-child-1]])
             (<! (timeout 20))
             (is (= (get-log (range 7)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-2 :child-3 :child-1-1 :child-A} (get-running-controllers app-db)))

             (>! commands-chan [[:tree-root :synchronize-child-controllers]])
             (<! (timeout 20))
             (is (= (get-log (range 8)) (:log @app-db)))
             (is (= #{:tree-root :tree-root-2 :child-1 :child-4 :child-5 :child-A} (get-running-controllers app-db)))
                          
             ((:stop @app-instance))
             (done)))))
