(ns ashiba.app-state
  (:require [reagent.core :as reagent :refer [atom cursor]]
            [com.stuartsierra.component :as component]))

(defn app-db []
  (atom {:route {}
         :entity-db {}
         :kv {}}))

(defn route-store [app-db]
  (cursor app-db [:route]))

(defn kv-store [app-db]
  (cursor app-db [:kv]))

(defn entity-db [app-db]
  (cursor app-db [:entity-db]))



(defrecord AppState [route-component app-db services]
  component/Lifecycle
  (start [component]
    (-> component
        (assoc :route-chan (:changes route-component))
        (assoc :app-db app-db)
        (assoc :services services)))
  (stop [component]))
