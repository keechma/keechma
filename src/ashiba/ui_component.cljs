(ns ashiba.ui-component
  (:require [cljs.core.async :refer [put!]]))

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
    (get-in this [:components name]))
  (send-command [this command args]
    (put! (:commands-chan this) [[(:topic this) command] args]))
  (renderer [this]
    (partial (:renderer this) this)))

(defrecord UIComponent [component-deps subscription-deps renderer]
  IUIComponent)

(defn reify-component [component opts]
  (let [child-components (or (:components opts) {})
        component-deps (or (:component-deps component) [])
        with-opts-component (merge component opts)]
    (if (and (not-empty component-deps)
             (not= (keys child-components) component-deps))
      (throw (js/Error "Component is missing some component dependencies!"))
      (renderer with-opts-component))))

(defn constructor [opts]
  (let [defaults {:component-deps []
                  :subscription-deps []
                  :renderer (fn [c]
                              [:h1 "MISSING RENDERER!"])}]
    (map->UIComponent (merge defaults opts))))
