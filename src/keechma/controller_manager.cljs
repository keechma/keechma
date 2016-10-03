(ns keechma.controller-manager
  (:require [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.util :refer [animation-frame]]
            [keechma.controller])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(defn ^:private controller-params [route-params controller]
  (keechma.controller/params controller route-params))

(defn ^:private controller-action [running-params params] 
  (if (= running-params params)
    (when-not (nil? params) :route-changed)
    (cond
     (nil? params) :stop
     (nil? running-params) :start
     :else :restart)))

(defn ^:private controllers-actions [running-controllers controller-params]
  (reduce-kv (fn [m k v]
               (let [running-controller (or (get running-controllers k) {})
                     running-controller-params (:params running-controller)
                     action (controller-action running-controller-params v)] 
                 (if action
                   (assoc m k action)
                   m))) {} controller-params))

(defn ^:private send-command-to [controller command-name args] 
  (do
    (put! (:in-chan controller) [command-name args])
    controller))

(defn ^:private start-controller [app-db-snapshot controller config]
  (let [out-chan (:commands-chan config)
        in-chan (chan)
        name (:name config)
        app-db (:app-db config)
        params (:params config)
        controller (-> controller
                       (assoc :params params)
                       (assoc :route-params (:route-params config))
                       (assoc :in-chan in-chan)
                       (assoc :out-chan out-chan)
                       (assoc :name name)
                       (assoc :running (fn [] (get-in @app-db [:internal :running-controllers name])))) 
        with-started (keechma.controller/start controller params app-db-snapshot)]
    (keechma.controller/handler controller app-db in-chan out-chan) 
    (assoc-in with-started [:internal :running-controllers name] controller)))

(defn ^:private stop-controller [app-db-snapshot controller config] 
  (let [name (:name config)
        with-stopped (keechma.controller/stop controller (:params controller) app-db-snapshot)]
    (close! (:in-chan controller))
    (assoc-in with-stopped [:internal :running-controllers]
              (dissoc (get-in with-stopped [:internal :running-controllers]) name))))

(defn ^:private restart-controller [app-db-snapshot controller running-controller config]
  (-> app-db-snapshot
      (stop-controller running-controller config)
      (start-controller controller config)))

(defn ^:private dispatch-controller-change [app-db-snapshot controller action config]
  (let [name (:name config)
        running (fn [name] (get-in app-db-snapshot [:internal :running-controllers name]))] 
    (case action
      :start (start-controller app-db-snapshot controller config)
      :restart (restart-controller app-db-snapshot controller (running name) config) 
      :stop (stop-controller app-db-snapshot (running name) config)
      :route-changed (do
                       (send-command-to (running name) :route-changed (:route-params config))
                       app-db-snapshot))))

(defn ^:private make-controller-change-applier [controllers controllers-params controllers-actions config]
  (fn [app-db-snapshot name action]
    (let [controller (get controllers name)
          params (get controllers-params name)
          config (merge config {:name name :params params})]
      (dispatch-controller-change app-db-snapshot controller action config))))

(defn ^:private apply-controllers-change [app-db-snapshot controllers controllers-params controllers-actions config]
  (reduce-kv
   (make-controller-change-applier controllers controllers-params controllers-actions config)
   app-db-snapshot
   controllers-actions))

(defn ^:private route-changed [route-params app-db commands-chan controllers]
  (let [app-db-snapshot @app-db
        running-controllers (get-in app-db-snapshot [:internal :running-controllers])
        controllers-params (reduce-kv (fn [m k controller]
                                        (assoc m k (controller-params route-params controller))){} controllers)
        controllers-actions (controllers-actions running-controllers controllers-params)] 
    (apply-controllers-change (assoc app-db-snapshot :route route-params)
                              controllers
                              controllers-params
                              controllers-actions
                              {:commands-chan commands-chan
                               :app-db app-db
                               :route-params route-params})))


(defn ^:private route-command-to-controller [controllers command-name command-args]
  (let [[controller-name command-name] command-name
        controller (get controllers controller-name)]
    (if controller
      (send-command-to controller command-name command-args)
      (.log js/console (str "Trying to send command " command-name " to the " controller-name " controller which is not started.")))))

(defn start
  "Starts the controller manager. Controller manager is the central part
  of the application that manages the lifecycle of the controllers and routes
  the messages sent to them.

  `start` function receives the following parameters:
  
  - `route-chan` - Route changes will communicated through this channel
  - `commands-chan` - User (UI) commands will be sent through this channel
  - `app-db` - application state atom
  - `controllers` map of controllers registered for the app

  Each time when the new route data comes through the `route-chan` controller
  manager will do the following:

  - call the `params` function on each registered controller
  - compare the value returned by the `params` function with the value that
  was returned last time when the route changes
  - based on the comparison it will do one of the following:
    - if the last value was `nil` and the current value is `nil` - do nothing
    - if the last value was `nil` and the current value is not `nil` - start the controller
    - if the last value was not `nil` and the current value is `nil` - stop the controller
    - if the last value was not `nil` and the current value is not `nil` and these values are the same - do nothing
    - if the last value was not `nil` and the current value is not `nil` and these values are different - restart the controller (stop the current instance and start the new one)

  Controller manager also acts as a command router. Each time a command comes - through the `commands-chan`
  the name of the command should look like this `[:controlnler-key :command-name]`. Controller manager will route the `:command-name` command to the appropriate controller based on the `:controller-key`. Controller key is the key under which the controller was registered in the `controllers` argument.
  "

  
  [route-chan commands-chan app-db controllers]
  (let [stop-route-block (chan)
        stop-command-block (chan)
        scheduled-updates (atom []) 
        running-chans
        [(go
           (loop []
             ;; When route changes:
             ;;   - stop controllers that return nil from their params functions
             ;;   - start controllers that need to be started
             ;;   - restart controllers that were running with the different params (stop the old instance and start the new one)
             ;;   - send "route-changed" command to controllers that were already running
             (let [[val channel] (alts! [stop-route-block route-chan])]
               (when (and (not= channel stop-route-block) val)
                 (let [route-params val]
                   (reset! app-db (route-changed route-params app-db commands-chan controllers))
                   (recur))))))
         (go
           (loop []
             (let [[val channel] (alts! [stop-command-block commands-chan])]
               (when-not (= channel stop-command-block)
                 (let [[command-name command-args] val 
                       running-controllers (get-in @app-db [:internal :running-controllers])]
                   (when (not (nil? command-name))
                     (route-command-to-controller running-controllers command-name command-args))
                   (recur))))))]]
    {:running-chans running-chans
     :stop (fn []
             (let [controllers (get-in @app-db [:internal :running-controllers])]
               (close! stop-route-block)
               (close! stop-command-block)
               (doseq [running running-chans] (close! running))
               (doseq [[k controller] controllers]
                 (stop-controller @app-db controller {:name (:name controller)}))))}))

