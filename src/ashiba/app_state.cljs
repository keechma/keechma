(ns ashiba.app-state
  (:require [reagent.core :as reagent :refer [atom cursor]]
            [reagent.dom :refer [unmount-component-at-node]]
            [cljs.core.async :refer [put! close!]]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [ashiba.router :as router]
            [ashiba.ui-component :as ui]
            [ashiba.service-manager :as service-manager])
  (:import goog.History))

(defn app-db []
  (atom {:route {}
         :entity-db {}
         :kv {}}))

(defn default-config []
  {:routes []
   :routes-chan (chan)
   :route-prefix "#!"
   :commands-chan (chan)
   :app-state (app-db)
   :components {}
   :services {}
   :html-element []
   :stop-fns []})

(defn add-main-stop-fn [state]
  (assoc state :stop
         (fn []
           (close! (:commands-chan state))
           (close! (:routes-chan state))
           (reduce (fn [s stop-fn]
                     (or (stop-fn s) state)) state (:stop-fns state)))))

(defn add-stop-fn [state stop-fn]
  (assoc state :stop-fns (conj (:stop-fns state) stop-fn)))

(defn expand-routes [state]
  (assoc state :routes (router/expand-routes (:routes state))))

(defn bind-history! [state] state
  (let [routes-chan (:routes-chan state)
        route-prefix (:route-prefix state)
        routes (:routes state)
        h (History.)
        listener (fn [e]
                   (let [clean-route (clojure.string.subs (.-token e) (count route-prefix))
                         route-params (router/url->map routes clean-route)]
                     (put! routes-chan route-params)))]
    (goog.events/listen h EventType/Navigate listener)
    (doto h (.setEnabled true))
    (add-stop-fn state (fn [_] (goog.events/unlisten h EventType/Navigate listener)))))

(defn render-to-element! [state]
  (let [main-component (-> (ui/system (:components state))
                           (assoc :commands-chan (:commands-chan state))
                           (assoc :url-fn (partial router/map->url (:routes state)))
                           (assoc :current-route (fn []
                                                   (:route (deref (:app-state state)))))
                           (ui/renderer))]
    (reagent/render-component (:html-element state) main-component)
    (add-stop-fn state (fn [_] (unmount-component-at-node (:html-element state))))))

(defn start-services [state]
  (let [services (:services state)
        routes-chan (:routes-chan state)
        commands-chan (:commands-chan state)
        app-state (:app-state state)
        manager (service-manager/start routes-chan commands-chan app-state services)]
    (add-stop-fn state (fn [s]
                         (do
                           ((:stop manager))
                           s)))))


(defn start! [config]
  (let [config (merge (default-config) config)]
    (-> config
        (expand-routes)
        (bind-history!)
        (render-to-element!)
        (add-main-stop-fn))))
