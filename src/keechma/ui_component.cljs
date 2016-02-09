(ns keechma.ui-component
  (:require [cljs.core.async :refer [put!]]
            [com.stuartsierra.dependency :as dep]
            [keechma.util :as util]
            [clojure.string :refer [join]]
            [clojure.set :as set]))

(declare component->renderer)

(defn component-dep-graph [components]
  (reduce-kv (fn [graph k v]
               (if-not (fn? v)
                 (let [component-deps (:component-deps v)]
                   (if (util/in? component-deps :main)
                     (throw (js/Error "Nothing can depend on the :main component!"))
                     (reduce #(dep/depend %1 k %2) graph component-deps)))
                 graph)) (dep/graph) components))

(defn missing-component-deps [components]
  (reduce-kv (fn [missing k v]
               (if (nil? v)
                 (conj missing k)
                 missing)) [] components))


(defn component-with-deps [component-key component system]
  (let [dep-keys (:component-deps component)]
    (if-not (empty? dep-keys)
      (let [components (select-keys system dep-keys)
            missing-deps (missing-component-deps components)]  
        (if-not (empty? missing-deps)
          (throw (js/Error (str "Missing dependencies " (join ", " missing-deps) " for component " component-key)))
          (-> component
              (assoc :components components)
              (assoc :component-deps []))))
      component)))

(defn resolve-dep [dep-kw coll-kw component key component-dep]
  (-> component
      (assoc-in [coll-kw key] component-dep)
      (assoc dep-kw (util/without (dep-kw component) key))))

(def resolve-subscription-dep (partial resolve-dep :subscription-deps :subscriptions))
(def resolve-component-dep (partial resolve-dep :component-deps :components))

(defn resolved-system [components sorted-keys]
  (reduce (fn [system key]
            (let [component (get system key)]
              (if (fn? component)
                (assoc system key component)
                (assoc system key (component-with-deps key component system))))) components sorted-keys))

(defn resolve-component-subscriptions [component subscriptions]
  (reduce (fn [c dep]
            (let [sub (get subscriptions dep)]
              (if (nil? sub)
                (throw (js/Error (str "Missing subscription: " dep)))
                (resolve-subscription-dep c dep sub))))
          component (or (:subscription-deps component) [])))

(defn resolve-subscriptions [components subscriptions]
  (reduce-kv (fn [components k c]
               (assoc components k (resolve-component-subscriptions c subscriptions)))
             {} components))

(defn system
  ([components] (system components {}))
  ([components subscriptions]
   (if (nil? (:main components))
     (throw (js/Error "System must have a :main component!"))
     (let [graph (component-dep-graph components)
           sorted-keys (dep/topo-sort graph)
           components-with-resolved-deps (resolve-subscriptions components subscriptions)]
       (:main (resolved-system components-with-resolved-deps sorted-keys))))))
 
(defprotocol IUIComponent  
  (url [this params])
  (subscription [this name])
  (component [this name])
  (send-command [this command] [this command args])
  (renderer [this])
  (current-route [this]))

(extend-type default
  IUIComponent
  (url [this params]
    (let [url-fn (:url-fn this)]
      (url-fn params)))
  (current-route [this]
    (let [current-route-fn (:current-route-fn this)]
      (current-route-fn)))
  (subscription [this name]
    ((get-in this [:subscriptions name])))
  (component [this name]
    (get-in this [:components name]))
  (send-command
    ([this command]
     (send-command this command nil))
    ([this command args]
     (put! (:commands-chan this) [[(:topic this) command] args])))
  (renderer [this]
    (let [child-renderers (reduce-kv (fn [c k v]
                                       (assoc c k (component->renderer this v)))
                                     {} (:components this))
          subscriptions (reduce-kv (fn [s k v]
                                     (assoc s k (partial v (:app-db this))))
                                   {} (:subscriptions this))]
      (partial (:renderer this)
               (-> this
                   (assoc :subscriptions subscriptions)
                   (assoc :components child-renderers))))))


(defn component->renderer [parent component]
  (renderer (-> component 
                (assoc :commands-chan (:commands-chan parent))
                (assoc :url-fn (or (:url-fn component) (:url-fn parent)))
                (assoc :current-route-fn (:current-route-fn parent))
                (assoc :app-db (:app-db parent)))))


(defrecord UIComponent [component-deps subscription-deps renderer]
  IUIComponent)

(defn constructor [opts]
  (let [defaults {:component-deps []
                  :subscription-deps []
                  :topic :ui
                  :renderer (fn [c]
                              [:h1 "MISSING RENDERER!"])}]
    (map->UIComponent (merge defaults opts))))
