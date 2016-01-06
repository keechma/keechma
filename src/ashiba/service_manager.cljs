(ns ashiba.service-manager
  (:require [cljs.core.async :refer [<! >! chan close! put! alts!]]
            [ashiba.service])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))

(defn service-params [route-params service]
  (ashiba.service/params service route-params))

(defn service-action [running-params params] 
  (when (not= running-params params)
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

(defn start-service [app-db-snapshot service-name service service-params]
  (let [service-with-params (assoc service :params service-params)]
    (-> (ashiba.service/start service-with-params service-params app-db-snapshot)
        (assoc-in [:running-services service-name] service-with-params))))

(defn stop-service [app-db-snapshot service-name service]
  (-> (ashiba.service/stop service (:params service) app-db-snapshot)
      (assoc :running-services (dissoc (:running-services app-db-snapshot) service-name))))

(defn restart-service [app-db-snapshot service-name service service-params]
  (-> app-db-snapshot
      (stop-service service-name service)
      (start-service service-name service service-params)))

(defn apply-service-change [services services-params app-db-snapshot service-name action]
  (let [service (service-name services)
        service-params (service-name services-params)]
    (case action
      :start (start-service app-db-snapshot service-name service service-params)
      :restart (restart-service app-db-snapshot service-name service service-params) 
      :stop (stop-service app-db-snapshot service-name service))))

(defn apply-services-change [app-db-snapshot services services-params services-actions]
  (reduce-kv (partial apply-service-change services services-params) app-db-snapshot services-actions))

(defn route-changed! [route-params app-db-snapshot commands-chan services]
  (let [running-services (:running-services app-db-snapshot)
        services-params (reduce-kv (fn [m k service]
                                     (assoc m k (service-params route-params service))){} services)
        services-actions (services-actions running-services services-params)]
    (apply-services-change app-db-snapshot
                           services
                           services-params
                           services-actions)))

(defn apply-command [command services])

(defn start [route-chan commands-chan app-db services]
  (go (loop []
        (let [[message channel] (alts! [route-chan commands-chan])]
          (case chan
            route-chan (reset! app-db (route-changed! message @app-db commands-chan services))
            commands-chan (apply-command message services))
          (recur)))))
