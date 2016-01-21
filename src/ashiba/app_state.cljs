(ns ashiba.app-state
  (:require [reagent.core :as reagent :refer [atom cursor]]
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
   :app-db (app-db)
   :components {}
   :services {}
   :html-element nil
   :stop-fns []})

(defn add-stop-fn [state stop-fn]
  (assoc state :stop-fns (conj (:stop-fns state) stop-fn)))

(defn expand-routes [state]
  (assoc state :routes (router/expand-routes (:routes state))))

(defn bind-history! [state]
  (let [routes-chan (:routes-chan state)
        route-prefix (:route-prefix state)
        routes (:routes state)
        ;; Always try to use existing elements for goog.History. That way 
        ;; page won't be erased when refreshed in development
        ;; https://groups.google.com/forum/#!topic/closure-library-discuss/0vKRKfJPK9c
        h (History. false nil (.getElementById js/document "history_state0") (.getElementById js/document "history_iframe0")) 
        ;; Clean this when HTML5 History API will be implemented
        ;; (subs (.. js/window -location -hash) 2) removes #! from the start of the route
        current-route-params (router/url->map routes (subs (.. js/window -location -hash) 2))
        listener (fn [e]
                   ;; Clean this when HTML5 History API will be implemented
                   ;; (subs (.-token e) 1) Removes ! from the start of the route
                   (let [clean-route (subs (.-token e) 1) 
                       route-params (router/url->map routes clean-route)]
                     (put! routes-chan route-params)))]
    (events/listen h EventType/NAVIGATE listener)
    (doto h (.setEnabled true))
    (put! routes-chan current-route-params)
    (add-stop-fn state (fn [_]
                         (events/unlisten h EventType/NAVIGATE listener)))))

(defn render-to-element! [state]
  (let [reify-main-component
        (partial ui/component->renderer
                 {:commands-chan (:commands-chan state)
                  :url-fn (fn [params]
                            ;; Clean this when HTML5 History API will be implemented
                            (str "#!" (router/map->url (:routes state) params)))
                  :app-db (:app-db state) 
                  :current-route-fn (fn []
                                      (:route (deref (:app-db state))))})
        main-component (-> (ui/system (:components state))
                           (reify-main-component))
        container (:html-element state)] 
    (reagent/render-component [main-component] container) 
    (add-stop-fn state (fn [s] 
                         (reagent/unmount-component-at-node container)))))

(defn start-services [state]
  (let [services (:services state)
        routes-chan (:routes-chan state)
        commands-chan (:commands-chan state)
        app-db (:app-db state)
        manager (service-manager/start routes-chan commands-chan app-db services)]
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
       (doseq [stop-fn (:stop-fns config)] (stop-fn config))
       (close! commands-chan)
       (close! routes-chan)
       (done)))))

