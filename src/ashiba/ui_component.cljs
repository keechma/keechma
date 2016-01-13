(ns ashiba.ui-component
  (:require [cljs.core.async :refer [put!]]
            [com.stuartsierra.dependency :as dep]
            [ashiba.util :as util]))

(defprotocol IUIComponent  
  (url [this params])
  (subscription [this name])
  (component [this name])
  (send-command [this command args])
  (renderer [this]))

(extend-type js/Object
  IUIComponent
  (url [this params]
    (let [url-fn (:url-fn this)]
      (url-fn params)))
  (subscription [this name]
    (get-in this [:subscriptions name]))
  (component [this name]
    (let [component (get-in this [:components name])]
      (renderer (-> component 
                    (assoc :commands-chan (:commands-chan this))
                    (assoc :url-fn (or (:url-fn component) (:url-fn this)))))))
  (send-command [this command args]
    (put! (:commands-chan this) [[(:topic this) command] args]))
  (renderer [this]
    (partial (:renderer this) this)))

(defrecord UIComponent [component-deps subscription-deps renderer]
  IUIComponent)

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
          (throw (js/Error (str "Missing dependencies " (clojure.string/join ", " missing-deps) " for component " component-key)))
          (assoc component :components components)))
      component)))

(defn system [components]
  (if (nil? (:main components))
    (throw (js/Error "System must have a :main component!"))
    (let [graph (component-dep-graph components)
          sorted-keys (dep/topo-sort graph)]
      (:main (reduce (fn [system key]
                       (let [component (get system key)]
                         (if (fn? component)
                           (assoc system key component)
                           (assoc system key (component-with-deps key component system))))) components sorted-keys)))))

(defn constructor [opts]
  (let [defaults {:component-deps []
                  :subscription-deps []
                  :topic :ui
                  :renderer (fn [c]
                              [:h1 "MISSING RENDERER!"])}]
    (map->UIComponent (merge defaults opts))))
