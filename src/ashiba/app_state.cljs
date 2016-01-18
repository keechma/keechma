(ns ashiba.app-state
  (:require [reagent.core :as reagent :refer [atom cursor]]
            [reagent.dom :refer [unmount-component-at-node]]
            [cljs.core.async :refer [put! close! chan timeout]]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [ashiba.router :as router]
            [ashiba.ui-component :as ui]
            [ashiba.service-manager :as service-manager])
  (:import goog.History)
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(defn app-db []
  (atom {:route {}
         :entity-db {}
         :kv {}
         :internal {}}))

(defn history []
  (History.))

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

(defn add-stop-fn [state stop-fn]
  (assoc state :stop-fns (conj (:stop-fns state) stop-fn)))

(defn expand-routes [state]
  (assoc state :routes (router/expand-routes (:routes state))))

(defn bind-history! [state]
  (let [routes-chan (:routes-chan state)
        route-prefix (:route-prefix state)
        routes (:routes state)
        h (History.)
        listener (fn [e]
                   (let [clean-route (subs (.-token e) (count route-prefix))
                         route-params (router/url->map routes clean-route)]
                     (put! routes-chan route-params)))]
    (events/listen h EventType/Navigate listener)
    (doto h (.setEnabled true))
    (add-stop-fn state (fn [_] (events/unlisten h EventType/Navigate listener)))))

(defn render-to-element! [state]
  (let [reify-main-component
        (partial ui/component->renderer
                 {:commands-chan (:commands-chan state)
                  :url-fn (partial router/map->url (:routes state))
                  :current-route-fn (fn []
                                      (:route (deref (:app-state state))))})
        main-component (-> (ui/system (:components state))
                           (reify-main-component))] 
    (reagent/render-component [main-component] (:html-element state))
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

(defn log-state [state]
  (do
    (.log js/console (clj->js state))
    state))

(defn start! [config]
  (let [config (merge (default-config) config)]
    (-> config
        (expand-routes)
        (bind-history!)
        (start-services)
        (render-to-element!))))

(defn stop!
  ([config]
   (stop! config (fn [])))
  ([config done]
   (let [routes-chan (:routes-chan config)
         commands-chan (:commands-chan config)]
     (go
       (<! (timeout 1))
       (doseq [stop-fn (:stop-fns config)] (stop-fn config))
       (<! (timeout 1))
       (close! commands-chan)
       (<! (timeout 1))
       (close! routes-chan)
       (done)))))
