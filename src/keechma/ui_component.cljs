(ns keechma.ui-component
  (:require [cljs.core.async :refer [put!]]
            [com.stuartsierra.dependency :as dep]
            [keechma.util :as util]
            [clojure.string :refer [join]]
            [clojure.set :as set]
            [keechma.reporter :as reporter]))

(defn make-ex [msg]
  (ex-info msg {:type ::error}))

(declare component->renderer)

(defprotocol IUIComponent
  "IUIComponent protocol defines functions that can be called on
  the UIComponent records."
  (url [this params]
    "Returns a URL based on the params. It will use the `:url-fn` that is injected
    from the outside to generate the URL based on the current app routes.")
  (report [this name payload] [this name payload path] [this name payload path severity])
  (redirect [this params] [this params replace?]
    "Redirects page to the URL generated from params")
  (subscription [this key] [this key args]
    "Returns a subscription based on the key.")
  (component [this key]
    "Returns a component based on the key.")
  (send-command [this command] [this command args]
    "Sends a command to the controller.")
  (renderer [this]
    "Returns a component renderer function that has the component record
    partially applied (`ctx`) as the first argument.")
  (current-route [this]
    "Returns a current route data. It will use the `:current-route-fn` that is
    injected from the outside to return the data.")
  (router [this]
    "Returns the router")
  (reroute [this]
    "Restarts the route process. This is useful in combination with the `:route-processor`.
    In some cases route processor might use info from the app-db to determine the current route,
    which means that the value from the route processor might be different without the actual
    route change happening."))

(extend-type default
  IUIComponent
  (url [this params]
    (let [url-fn (:url-fn this)]
      (url-fn params)))
  (report
    ([this name payload] (report this name payload (reporter/cmd-info) :info))
    ([this name payload cmd-info] (report this name payload cmd-info :info))
    ([this name payload cmd-info severity]
     (let [reporter (or (:reporter this) (fn [_ _ _ _ _ _ _ _]))]
       (reporter (:name this) [(:topic this) name] payload cmd-info severity))))
  (router [this]
    (:router this))
  (redirect
    ([this params] (redirect this params :push))
    ([this params action]
     ((:redirect-fn this) params action)))
  (current-route [this]
    (let [current-route-fn (:current-route-fn this)]
      (current-route-fn)))
  (subscription
    ([this key] (subscription this key []))
    ([this key args]
     (let [subscription (get-in this [:subscriptions key])]
       (if (nil? subscription)
         (throw (make-ex (str "Can't resolve the subscription with key: " key)))
         (apply subscription (into [(:app-db this)] args))))))
  (component [this key]
    (let [component (get-in this [:components key])]
      (if (nil? component)
        (throw (make-ex (str "Can't resolve the component with key: " key)))
        component)))
  (send-command
    ([this command]
     (send-command this command nil))
    ([this command args]
     (let [cmd-info (reporter/cmd-info)]
       (report this command args cmd-info)
       (if (vector? command)
         (put! (:commands-chan this) [command args cmd-info])
         (put! (:commands-chan this) [[(:topic this) command] args cmd-info]))
       nil)))
  (renderer [this]
    (let [child-renderers (reduce-kv (fn [c k v]
                                       (assoc c k (component->renderer k this v)))
                                     {} (:components this))
          renderer-context (assoc this :components child-renderers)]
      (with-meta (partial (:renderer this) renderer-context)
        {:name (:name this)
         ::renderer (:renderer this)
         ::context renderer-context})))
  (reroute [this]
    (let [cmd-info (reporter/cmd-info)
          command :keechma.controller-manager/reroute]
       (report this command nil cmd-info)
       (put! (:commands-chan this) [command nil cmd-info])
       nil)))

(defrecord UIComponent [component-deps subscription-deps renderer]
  IUIComponent)

(defn component-dep-graph [components]
  (reduce-kv (fn [graph k v]
               (if-not (fn? v)
                 (let [component-deps (:component-deps v)]
                   (if (util/in? component-deps :main)
                     (throw (make-ex "Nothing can depend on the :main component!"))
                     (reduce #(dep/depend %1 k %2) graph component-deps)))
                 graph)) (dep/graph) components))

(defn missing-component-deps [components]
  (reduce-kv (fn [missing k v]
               (if (nil? v)
                 (conj missing k)
                 missing)) [] components))

(defn resolve-component-aliases [components system]
  (reduce-kv 
   (fn [m k v]
     (if (keyword? v)
       (assoc m k (get system v))
       (assoc m k v)))
   {}
   components))

(defn component-with-deps [component-key component system]
  (let [dep-keys (:component-deps component)]
    (if-not (empty? dep-keys)
      (let [components (resolve-component-aliases 
                        (merge (select-keys system dep-keys) (:components component))
                        system)
            missing-deps (missing-component-deps components)]  
        (if-not (empty? missing-deps)
          (throw (make-ex (str "Missing dependencies " (join ", " missing-deps) " for component " component-key)))
          (assoc component
                 :components components 
                 :component-deps [])))
      component)))

(defn resolve-dep
  "Resolves a dependency on the UIComponent record. Returns a new version
  of the record with the resolved dependency.

  ```clojure
  (def news-component (constructor {:component-deps [:user-profile :similar-news]
                                    :subscription-deps [:current-news :comments]}))

  (resolve-dep :subscription-deps :subscriptions news-component :current-news (fn []...))
  ;; Returns a new version of the `news-component` with the :current-news subscription resolved

  (resolve-dep :component-deps :components news-component :user-profile user-profile-component))
  ;; Returns a new version of the `news-component` with the :user-profile component resolved
  ```

  Two shorthand functions are defined that allow you to omit the first two arguments:
  
  - `resolve-subscription-dep` - Resolves a subscription dependency
  - `resolve-component-dep` - Resolves a component dependency
  "
  [dep-kw coll-kw component key component-dep]
  (-> component
      (assoc-in [coll-kw key] component-dep)
      (assoc dep-kw (util/without (dep-kw component) key))))

(def resolve-subscription-dep
  "See [[resolve-dep]]."
  (partial resolve-dep :subscription-deps :subscriptions))

(def resolve-component-dep
  "See [[resolve-dep]]"
  (partial resolve-dep :component-deps :components))

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
                (throw (make-ex (str "Missing subscription: " dep)))
                (resolve-subscription-dep c dep sub))))
          component (or (:subscription-deps component) [])))

(defn resolve-subscriptions [components subscriptions]
  (reduce-kv (fn [components k c]
               (assoc components k (resolve-component-subscriptions c subscriptions)))
             {} components))

(defn assoc-name [components]
  (reduce-kv (fn [components k c]
               (assoc components k (assoc c :name k))) {} components))

(defn default-ctx-processor [])

(defn component->renderer 
  ([parent component]
   (component->renderer nil parent component))
  ([component-key parent component]
   (let [ctx-processor (or (::ctx-processor parent) identity)]
     (renderer  
      (ctx-processor
       (assoc component
              :reporter (:reporter parent)
              :redirect-fn (:redirect-fn parent)
              :commands-chan (:commands-chan parent)
              :url-fn (:url-fn parent)
              :current-route-fn (:current-route-fn parent)
              :app-db (:app-db parent)
              :router (:router parent)
              :context (:context parent)
              :path (conj (:path parent) component-key)
              ::system (::system parent)
              ::ctx-processor ctx-processor))))))

(defn system
  "Creates a component system.

  Component system is a group of components that are interdependent. Each component
  system must define a `:main` component that will be returned from the `system`
  function.

  `system` function resolves dependencies between components.

  For example, let's say that your application consists of three components:

  - layout
  - sidebar
  - chat room list

  Layout is rendering the sidebar component and sidebar component is rendering the 
  chat room list component.

  Chat room list component has a subscription dependency on `:chat-rooms` which will
  return the list of chat rooms to render.

  Usually, you would either pass the chat rooms subscription from layout to sidebar to the
  chat room list component, or the chat room list component would have a dependency
  on a global `:chat-rooms` subscription.

  Keechma allows you to avoid both of these problems:

  - only the chat room list component will know about it's dependencies
  - the dependencies will be injected from the outside by the `system` function 

  ```clojure
  (defn layout-renderer [ctx] ;; ctx is `layout-component` record with resolved dependencies
  [:div.main
  [:div.sidebar [(component ctx :sidebar)]]]) ;; Resolve the `:sidebar` component

  (def layout-component (constructor {:component-deps [:sidebar]
  :renderer layout-renderer}))

  (defn sidebar-renderer [ctx]
  [:div.sidebar
  [(component ctx :chat-room-list)]) ;; Resolve the `:chat-room-list` component

  (def sidebar-component (constructor {:component-deps [:chat-room-list]
  :renderer sidebar-renderer}))

  (defn chat-room-list-renderer [ctx]
  (let [chat-rooms (subscription ctx :chat-rooms)])) ;; Resolve the `:chat-rooms` subscription

  (def chat-room-list-component (constructor {:subscription-deps [:chat-rooms]}))

  (def main-component (system {:main layout-component ;; Map components to keys
  :sidebar sidebar-component
  :chat-room-list chat-room-list-component}
  {:chat-rooms (fn [app-state-atom])})) ;; Map subscriptions to keys
  ```
  
  In the example above `main-component` will be a Reagent component that can be mounted
  into the DOM, with all of the dependencies between components resolved.

  `system` function provided everything that components need from the outside, and all of the 
  components are reusable by design.
  "
  ([components] (system components {}))
  ([components subscriptions]
   (if (nil? (:main components))
     (throw (make-ex "System must have a :main component!"))
     (let [graph (component-dep-graph components)
           sorted-keys (dep/topo-sort graph)
           components-with-resolved-deps (resolve-subscriptions (assoc-name components) subscriptions)
           system (resolved-system components-with-resolved-deps sorted-keys)]
       (assoc (:main system) ::system system)))))

(defn constructor
  "Creates a UIComponent record. Accepts `opts` as the argument. `opts` is
  a map that can have the following params:

  - `:component-deps` - Which child component is this component dependent on
  - `:subscription-deps` - Which subscriptions is this component dependent on
  - `:topic` - On which topic should this component send commands (to the controller)
  - `:renderer` - A renderer function (Reagent component)

  The UIComponent record is a way to list all the dependencies for a component. When
  the application is started each component's renderer function will be partially 
  applied with a version of its UIComponent that has the component and subscription
  dependencies resolved.

  This allows you to write components that are completely decoupled from the rest
  of the application.

  When you want to resolve injected component or subscription dependencies, you can
  use the functions defined on the `UIComponent` protocol:

  ```clojure
  (def render [ctx] ;; `ctx` is a UIComponent with resolved dependencies
    (let [child-component (component ctx :component-key)
          subscription (subscription ctx :subscription-key)])

  (def component (constructor {:renderer render
                               :component-deps [:component-key]
                               :subscription-deps [:subscription-key]))
  ```
  "
  [opts]
  (let [defaults {:component-deps []
                  :subscription-deps []
                  :topic :ui
                  :renderer (fn [c]
                              [:h1 "MISSING RENDERER!"])}
        opts' (merge defaults opts)]
    (map->UIComponent (merge defaults opts))))