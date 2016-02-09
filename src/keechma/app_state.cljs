(ns keechma.app-state
  (:require [reagent.core :as reagent :refer [atom cursor]]
            [cljs.core.async :refer [put! close! chan timeout]]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [keechma.router :as router]
            [keechma.ui-component :as ui]
            [keechma.controller-manager :as controller-manager])
  (:import goog.History)
  (:require-macros [cljs.core.async.macros :as m :refer [go]]
                   [reagent.ratom :refer [reaction]]))

(defn ^:private app-db []
  (atom {:route {}
         :entity-db {}
         :kv {}
         :internal {}}))

(defn ^:private history []
  (History.))

(defn ^:private default-config []
  {:routes []
   :routes-chan (chan)
   :route-prefix "#!"
   :commands-chan (chan)
   :app-db (app-db)
   :components {}
   :controllers {}
   :html-element nil
   :stop-fns []})

(defn ^:private add-stop-fn [state stop-fn]
  (assoc state :stop-fns (conj (:stop-fns state) stop-fn)))

(defn ^:private expand-routes [state]
  (assoc state :routes (router/expand-routes (:routes state))))

(defn ^:private bind-history! [state]
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

(defn ^:private render-to-element! [state]
  (let [reify-main-component
        (partial ui/component->renderer
                 {:commands-chan (:commands-chan state)
                  :url-fn (fn [params]
                            ;; Clean this when HTML5 History API will be implemented
                            (str "#!" (router/map->url (:routes state) params)))
                  :app-db (:app-db state) 
                  :current-route-fn (fn []
                                      (let [app-db (:app-db state)]
                                        (reaction
                                         (:route @app-db))))})
        main-component (-> (ui/system (:components state) (or (:subscriptions state) {}))
                           (reify-main-component))
        container (:html-element state)] 
    (reagent/render-component [main-component] container) 
    (add-stop-fn state (fn [s] 
                         (reagent/unmount-component-at-node container)))))

(defn ^:private start-controllers [state]
  (let [controllers (:controllers state)
        routes-chan (:routes-chan state)
        commands-chan (:commands-chan state)
        app-db (:app-db state)
        manager (controller-manager/start routes-chan commands-chan app-db controllers)]
    (add-stop-fn state (fn [s]
                         (do
                           ((:stop manager))
                           s)))))

(defn ^:private log-state [state]
  (do
    (.log js/console (clj->js state))
    state))

(defn start!
  "Starts the application. It receives the application config `map` as an argument.

  Application config contains all the parts needed to run the application:

  - Route defintions
  - Controllers
  - UI subscriptions
  - UI components 
  - HTML element to which the component should be mounted
  - Routes chan (through which the route changes will be communicated)
  - Commands chan (through which the UI sends the commands to the controllers)

  `start!` function returns the updated config map which can be passed to the `stop!`
  function to stop the application.

  Example:

  ```clojure
  (def app-config {:controllers {:users (->users/Controller)}
                   :subscriptions {:user-list (fn [app-db-atom])}
                   :components {:main layout/component
                                :users users/component}
                   :html-element (.getElementById js/document \"app\")})
  ```

  If any of the params is missing, the defaults will be used.

  When the application is started, the following happens:

  1. Routes are expanded (converted to regexps, etc.)
  2. Application binds the listener the history change event
  3. Controller manager is started
  4. Application is mounted into the DOM
  
  "
  [config]
  (let [config (merge (default-config) config)]
    (-> config
        (expand-routes)
        (bind-history!)
        (start-controllers)
        (render-to-element!))))

(defn stop!
  "Stops the application. `stop!` function receives the following as the arguments:

  - `config` - App config map returned from the `start!` function
  - `done` - An optional callback function that will be called when the application
  is stopped.

  Purpose of the `stop!` function is to completely clean up after the application. When the
  application is stopped, the following happens:

  1. History change event listener is unbound
  2. Controller manager and any running controllers are stopped
  3. Any channels used by the app (`routes-chan`, `commands-chan`,...) are closed
  4. Application is unmounted and removed from the DOM
  "
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

