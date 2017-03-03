(ns keechma.app-state.react-native-router
  (:require [keechma.app-state.core :as core :refer [IRouter]]
            [reagent.core :as r]
            [cljs.core.async :refer [put!]]))

(def initial-route
  {:index 0
   :key :init
   :routes [{:key :init}]})

(defonce route-atom
  (r/atom initial-route))

(defn update-navigation-key [route]
  (assoc route :key (get-in route [:routes (:index route) :key])))

(defn push-route [route value]
  (-> route
      (update :index inc)
      (update :routes #(conj % value))
      (update-navigation-key)))

(defn pop-route [route _]
  (if (< 1 (count (:routes route)))
    (-> route
        (update :index dec)
        (update :routes pop)
        (update-navigation-key))
    route))

(defn home-route [route _]
  initial-route)

(defn navigate!
  ([action] (navigate! action nil))
  ([action payload]
   (let [action-fn (get {:push push-route
                         :pop pop-route
                         :home home-route} action)]
     (reset! route-atom (action-fn @route-atom payload)))))


(defrecord ReactNativeRouter [routes-chan watch-id app-db]
  IRouter
  (start! [this]
    (let [routes-chan (:routes-chan this)
          watch-id (:watch-id this)]
      (add-watch route-atom watch-id
                 (fn [_ _ _ route-data]
                   (put! routes-chan route-data)))
      (swap! app-db assoc :route @route-atom)
      this))
  (stop! [this]
    (remove-watch route-atom (:watch-id this)))
  (redirect! [this params]
    (navigate! :push params)))

(defn constructor [_ routes-chan state]
  (let [watch-id (keyword (gensym :route-watch))]
    (core/start! (->ReactNativeRouter routes-chan watch-id (:app-db state)))))
