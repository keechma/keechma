(ns ashiba.app-state
  (:require [reagent.core :as reagent :refer [atom cursor]]
            ))

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
