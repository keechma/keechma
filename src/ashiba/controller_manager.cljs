(ns ashiba.controller-manager
  (:require [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [ashiba.util :refer [animation-frame]]
            [ashiba.controller])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(defn controller-params [route-params controller]
  (ashiba.controller/params controller route-params))

(defn controller-action [running-params params] 
  (if (= running-params params)
    (when-not (nil? params) :route-changed)
    (cond
     (nil? params) :stop
     (nil? running-params) :start
     :else :restart)))

(defn controllers-actions [running-controllers controller-params]
  (reduce-kv (fn [m k v]
               (let [running-controller (or (get running-controllers k) {})
                     running-controller-params (:params running-controller)
                     action (controller-action running-controller-params v)] 
                 (if action
                   (assoc m k action)
                   m))) {} controller-params))

(defn send-command-to [controller command-name args] 
  (do
    (put! (:in-chan controller) [command-name args])
    controller))

(defn start-controller [app-db-snapshot controller-name controller controller-params extras]
  (let [out-chan (:commands-chan extras)
        controller (-> controller 
                    (assoc :params controller-params)
                    (assoc :in-chan (chan))
                    (assoc :out-chan out-chan)
                    (assoc :currently-running-controller (partial (:currently-running-controller extras)
                                                               controller-name))) 
        app-db (ashiba.controller/start controller controller-params app-db-snapshot)]
    (ashiba.controller/handler controller (:app-db extras) (:in-chan controller) (:out-chan controller)) 
    (assoc-in app-db [:internal :running-controllers controller-name] controller)))

(defn stop-controller [app-db-snapshot controller-name controller] 
  (let [app-db-with-stopped-controller (ashiba.controller/stop controller (:params controller) app-db-snapshot)]
    (close! (:in-chan controller))
    (assoc-in app-db-with-stopped-controller [:internal :running-controllers]
              (dissoc (get-in app-db-snapshot [:internal :running-controllers]) controller-name))))

(defn restart-controller [app-db-snapshot controller-name running-controller controller controller-params extras]
  (-> app-db-snapshot
      (stop-controller controller-name running-controller)
      (start-controller controller-name controller controller-params extras)))

(defn apply-controller-change [controllers controllers-params extras app-db-snapshot controller-name action]
  (let [controller (controller-name controllers)
        controller-params (controller-name controllers-params)
        get-running-controller (fn [name] (get-in app-db-snapshot [:internal :running-controllers name]))] 
    (case action
      :start (start-controller app-db-snapshot controller-name controller controller-params extras)
      :restart (restart-controller app-db-snapshot controller-name (get-running-controller controller-name) controller controller-params extras) 
      :stop (stop-controller app-db-snapshot controller-name (get-running-controller controller-name))
      :route-changed (do
                       (send-command-to (get-running-controller controller-name) :route-changed [(:route-params extras)])
                       app-db-snapshot))))

(defn apply-controllers-change [app-db-snapshot controllers controllers-params controllers-actions extras]
  (reduce-kv (partial apply-controller-change
                      controllers
                      controllers-params
                      extras) app-db-snapshot controllers-actions))

(defn route-changed [route-params app-db commands-chan controllers currently-running-controller]
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
                            :route-params route-params
                            :currently-running-controller currently-running-controller})))

(defn route-command-to-controller [controllers command-name command-args]
  (let [[controller-name command-name] command-name
        controller (get controllers controller-name)]
    (if controller
      (send-command-to controller command-name command-args)
      (.log js/console (str "Trying to send command " command-name " to the " controller-name " controller which is not started.")))))

(defn apply-scheduled-updates [state fns]
  (reduce (fn [state cb] (cb state)) state fns))

(defn start [route-chan commands-chan app-db controllers]
  (let [stop-route-block (chan)
        stop-command-block (chan)
        scheduled-updates (atom [])
        currently-running-controller (fn [controller-name]
                                    (get-in @app-db [:internal :running-controllers controller-name])) 
        running-chans
        [(go
           (loop []
             ;; When route changes:
             ;;   - send "stop" command to controllers that will be stopped
             ;;   - stop controllers that return nil from their params functions
             ;;   - start controllers that need to be started
             ;;   - send "start" command to started controllers
             ;;   - send "route-changed" command to controllers that were already running
             (let [[val channel] (alts! [stop-route-block route-chan])]
               (when-not (= channel stop-route-block)
                 (let [route-params val]
                   (reset! app-db (route-changed route-params app-db commands-chan controllers currently-running-controller))
                   (recur))))))
         (go
           (loop []
             (let [[val channel] (alts! [stop-command-block commands-chan])]
               (when-not (= channel stop-command-block)
                 (let [[command-name command-args] val 
                       running-controllers (get-in @app-db [:internal :running-controllers])]
                   (when (not (nil? command-name))
                     (route-command-to-controller running-controllers command-name command-args))
                   (recur))))))
         ]]
    {:running-chans running-chans
     :stop (fn []
             (let [controllers (get-in @app-db [:internal :running-controllers])]
               (close! stop-route-block)
               (close! stop-command-block)
               (doseq [running running-chans] (close! running))
               (doseq [[k controller] controllers] (close! (:in-chan controller)))))}))

