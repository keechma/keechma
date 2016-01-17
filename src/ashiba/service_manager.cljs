(ns ashiba.service-manager
  (:require [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [ashiba.service])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(defn service-params [route-params service]
  (ashiba.service/params service route-params))

(defn service-action [running-params params] 
  (if (= running-params params)
    (when-not (nil? params) :route-changed)
    (cond
     (nil? params) :stop
     (nil? running-params) :start
     :else :restart)))

(defn services-actions [running-services service-params]
  (reduce-kv (fn [m k v]
               (let [running-service (or (get running-services k) {})
                     running-service-params (:params running-service)
                     action (service-action running-service-params v)] 
                 (if action
                   (assoc m k action)
                   m))) {} service-params))

(defn send-command-to [service command-name args] 
  (do
    (put! (:in-chan service) [command-name args])
    service))

(defn start-service [app-db-snapshot service-name service service-params extras]
  (let [out-chan (:commands-chan extras)
        service (-> service 
                    (assoc :params service-params)
                    (assoc :in-chan (chan))
                    (assoc :out-chan out-chan)
                    (assoc :currently-running-service (partial (:currently-running-service extras)
                                                               service-name))) 
        app-db (ashiba.service/start service service-params app-db-snapshot)]
    (ashiba.service/handler service (:in-chan service) (:out-chan service))
    (send-command-to service :start [(:route-params extras)])
    (assoc-in app-db [:internal :running-services service-name] service)))

(defn stop-service [app-db-snapshot service-name service]
  (send-command-to service :stop [])
  (close! (:in-chan service))
  (-> (ashiba.service/stop service (:params service) app-db-snapshot)
      (assoc-in [:internal :running-services]
                (dissoc (get-in app-db-snapshot [:internal :running-services]) service-name))))

(defn restart-service [app-db-snapshot service-name running-service service service-params extras]
  (-> app-db-snapshot
      (stop-service service-name running-service)
      (start-service service-name service service-params extras)))

(defn apply-service-change [services services-params extras app-db-snapshot service-name action]
  (let [service (service-name services)
        service-params (service-name services-params)
        get-running-service (fn [name] (get-in app-db-snapshot [:internal :running-services name]))] 
    (case action
      :start (start-service app-db-snapshot service-name service service-params extras)
      :restart (restart-service app-db-snapshot service-name (get-running-service service-name) service service-params extras) 
      :stop (stop-service app-db-snapshot service-name (get-running-service service-name))
      :route-changed (do
                       (send-command-to (get-running-service service-name) :route-changed [(:route-params extras)])
                       app-db-snapshot))))

(defn apply-services-change [app-db-snapshot services services-params services-actions extras]
  (reduce-kv (partial apply-service-change
                      services
                      services-params
                      extras) app-db-snapshot services-actions))

(defn route-changed [route-params app-db-snapshot commands-chan services currently-running-service]
  (let [running-services (get-in app-db-snapshot [:internal :running-services])
        services-params (reduce-kv (fn [m k service]
                                     (assoc m k (service-params route-params service))){} services)
        services-actions (services-actions running-services services-params)] 
    (apply-services-change (assoc app-db-snapshot :route route-params)
                           services
                           services-params
                           services-actions
                           {:commands-chan commands-chan
                            :route-params route-params
                            :currently-running-service currently-running-service})))

(defn route-command-to-service [services command-name command-args]
  (let [[service-name command-name] command-name
        service (get services service-name)]
    (if service
      (send-command-to service command-name command-args)
      (.log js/console (str "Trying to send command " command-name " to the " service-name " service which is not started.")))))

(defn apply-scheduled-updates [state fns]
  (reduce (fn [state cb] (cb state)) state fns))

(defn start [route-chan commands-chan app-db services]
  (let [scheduled-updates (atom [])
        currently-running-service (fn [service-name]
                                    (get-in @app-db [:internal :running-services service-name])) 
        running-chans
        [(go
           (while true
             ;; When route changes:
             ;;   - send "stop" command to services that will be stopped
             ;;   - stop services that return nil from their params functions
             ;;   - start services that need to be started
             ;;   - send "start" command to started services
             ;;   - send "route-changed" command to services that were already running
             (let [route-params (<! route-chan)]
               (reset! app-db (route-changed route-params @app-db commands-chan services currently-running-service)))))
         (go
           (while true
             (let [[command-name command-args] (<! commands-chan)
                   running-services (get-in @app-db [:internal :running-services])]
               (cond
                (= command-name :schedule-update) (swap! scheduled-updates conj command-args)
                (= command-name :immediate-update) (reset! app-db (command-args @app-db))
                (not (nil? command-name)) (route-command-to-service running-services command-name command-args)))))
         (go
           (while true
             (<! (timeout 1))
             (let [updates-fns @scheduled-updates]
               (when-not (empty? updates-fns)
                 (reset! app-db (apply-scheduled-updates @app-db updates-fns))
                 (reset! scheduled-updates [])))))]]
    {:running-chans running-chans
     :stop (fn []
             (let [services (get-in @app-db [:internal :running-services])]
               (map (fn [[name s]]
                      (reset! app-db (stop-service @app-db name s))) services)))}))
