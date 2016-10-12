(ns keechma.app-state.hashchange
  (:require [keechma.app-state.core :as core :refer [IRouter]]
            [router.core :as router]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [cljs.core.async :refer [put!]])
  (:import goog.History))

(defn hashchange-listener [routes routes-chan e]
  ;; (subs (.-token e) 1) Removes ! from the start of the route
  (let [clean-route (subs (.-token e) 1) 
        route-params (router/url->map routes clean-route)]
    (put! routes-chan route-params)))

(defn get-history []
  (History. false nil
            (.getElementById js/document "history_state0")
            (.getElementById js/document "history_iframe0")))

(defrecord HashchangeRouter [routes routes-chan hashchange-listener]
  IRouter
  (start! [this]
    (let [history (get-history)
          ;; (subs (.. js/window -location -hash) 2) removes #! from the start of the route
          current-route-params (router/url->map (:routes this) (subs (.. js/window -location -hash) 2))]
      (events/listen history EventType/NAVIGATE (:hashchange-listener this))
      (doto history (.setEnabled true))
      (put! (:routes-chan this) current-route-params)
      (assoc this :history history)))
  (stop! [this]
    (events/unlisten (:history this) EventType/NAVIGATE (:hashchange-listener this)))
  (redirect! [this params]
    (set! (.-hash js/location) (str "#!" (router/map->url (:routes this) params))))
  (url [this params]
    (str "#!" (router/map->url (:routes this) params))))

(defn constructor [routes routes-chan]
  (let [listener (partial hashchange-listener routes routes-chan)] 
    (core/start! (->HashchangeRouter (router/expand-routes routes) routes-chan listener))))
