(ns ashiba.service-manager
  (:require [cljs.core.async :refer [<! >! chan close! put! alts!]]
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

(defn send-command [chan-name service command-name args]
  (do
    (put! (chan-name service) [command-name args])
    service))

(def send-command-to (partial send-command :in-chan))
(def send-command-from (partial send-command :out-chan))

(defn send-update
  ([service update-fn]
   (send-updates-command service update-fn false))
  ([service update-fn is-immediate?]
   (let [command-name (if is-immediate? :immediate-update :schedule-update)]
     (send-command-from service command-name update-fn))))

(defn start-service [app-db-snapshot service-name service service-params extras]
  (let [service (-> service
                    (assoc :send-update (partial send-update service))
                    (assoc :send-command (partial send-command-from service))
                    (assoc :params service-params)
                    (assoc :in-chan (chan))
                    (assoc :out-chan (:main-commands-chan extras))
                    (assoc :is-running? (partial (:is-service-running? extras) service-name service))) 
        app-db (ashiba.service/start service service-params app-db-snapshot)]
    (ashiba.service/handler (:in-chan service) (:out-chan extras))
    (send-command-to service :start [(:route-params extras)])
    (assoc-in app-db[:running-services service-name] service)))

(defn stop-service [app-db-snapshot service-name service]
  (send-command-to service :stop [])
  (close! (:in-chan service))
  (-> (ashiba.service/stop service (:params service) app-db-snapshot)
      (assoc :running-services (dissoc (:running-services app-db-snapshot) service-name))))

(defn restart-service [app-db-snapshot service-name service service-params extras]
  (-> app-db-snapshot
      (stop-service service-name service)
      (start-service service-name service service-params route-params)))

(defn apply-service-change [services services-params commands-chan extras app-db-snapshot service-name action]
  (let [service (service-name services)
        service-params (service-name services-params)]
    (case action
      :start (start-service app-db-snapshot service-name service service-params extras)
      :restart (restart-service app-db-snapshot service-name service service-params extras) 
      :stop (stop-service app-db-snapshot service-name service)
      :route-changed (send-command-to service :route-changed [route-params]))))

(defn apply-services-change [app-db-snapshot services services-params services-actions extras]
  (reduce-kv (partial apply-service-change
                      services
                      services-params
                      extras) app-db-snapshot services-actions))

(defn route-changed [route-params app-db-snapshot commands-chan services is-service-running?]
  (let [running-services (:running-services app-db-snapshot)
        services-params (reduce-kv (fn [m k service]
                                     (assoc m k (service-params route-params service))){} services)
        services-actions (services-actions running-services services-params)] 
    (apply-services-change app-db-snapshot
                           services
                           services-params
                           services-actions
                           {:main-commands-chan commands-chan
                            :route-params route-params
                            :is-service-running? is-service-running?})))

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
        is-service-running? (partial
                             (fn [app-db service-name service]
                               (identical? (get-in @app-db [:running-services service-name])
                                           service)) app-db)
        running-chans
        [(go
           (while true
             ;; When route changes:
                           ;;   - send "stop" command to services that will be stopped
             ;;   - stop services that return nil from their params functions
             ;;   - start services that need to be started
             ;;   - send "start" command to started services
             ;;   - send "route-changed" command to services that were already running
             (reset! app-db (route-changed (<! route-chan) @app-db commands-chan services is-service-running?))))
         (go
           (while true
             (let [[command-name command-args] (<! commands-chan)
                   running-services (:running-services @app-db)]
               (cond
                (= command-name :schedule-update) (swap! scheduled-updates conj (first command-args))
                (= command-name :immediate-update) (reset! app-db ((first command-args) @app-db))
                :else (route-command-to-service running-services command-name command-args)))))
         (go
           (while true
             (<! (timeout 1))
             (let [updates-fns scheduled-updates]
               (when-not (empty? updates)
                 (reset! app-db (apply-scheduled-updates @app-db updates-fns))
                 (reset! scheduled-updates [])))))]]
    {:running-chans running-chans
     :stop (fn []
             (let [services (:running-services @app-db)]
               (map (fn [[_ s]]
                      (close! (:commands-chan s))) services)
               (close! commands-chan)
               (close! routes-chan)
               (map close! running-chans)))}))
