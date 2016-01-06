(ns ashiba.service-manager
  (:require [cljs.core.async :refer [<! >! chan close! put! alts!]]
            [ashiba.service :as service])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))

(defn service-params [service route-params]
  (service/params service route-params))

(defn service-action [running-params params] 
  (when (not= running-params params)
    (cond
     (nil? params) :stop
     (nil? running-params) :start
     :else :restart)))

(defn service-actions [running-services service-params]
  (reduce-kv (fn [m k v]
               (let [running-service (or (get running-services k) {})
                     running-service-params (:params running-service)
                     action (service-action running-service-params v)] 
                 (if action 
                  (assoc m k action)
                  m))) {} service-params))

(defn start [route-chan services services-cache]
  (go (loop []
          (let [route-params (<! route-chan)]))))
