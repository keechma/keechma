(ns keechma.app-state
  (:require [reagent.core :as reagent :refer [cursor]]
            [cljs.core.async :refer [put! close! chan timeout <!]]
            [keechma.ui-component :as ui]
            [keechma.controller-manager :as controller-manager]
            [keechma.app-state.core :as app-state-core]
            [keechma.app-state.hashchange-router :as hashchange-router]
            [keechma.app-state.react-native-router :as react-native-router]
            [keechma.app-state.history-router :as history-router])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]
                   [reagent.ratom :refer [reaction]]))

(defn ^:private app-db [initial-data]
  (reagent/atom (merge {:route {}
                        :entity-db {}
                        :kv {}
                        :internal {}}
                       initial-data)))

(defn ^:private default-config [initial-data]
  {:name :application
   :reporter (fn [app-name type direction topic name payload severity])
   :router :hashchange
   :routes-chan (chan)
   :commands-chan (chan)
   :app-db (app-db initial-data)
   :subscriptions-cache (atom {})
   :components {}
   :controllers {}
   :html-element nil
   :stop-fns []})

(defn ^:private process-config [config]
  (let [name (keyword (gensym (:name config)))
        reporter (partial (:reporter config) name)]
    (assoc config
           :name name
           :reporter reporter)))

(defn ^:private add-reporter-to-controllers [controllers reporter]
  (reduce-kv (fn [m k v]
            (assoc m k (assoc v :reporter reporter))) {} controllers))

(defn ^:private add-redirect-fn-to-controllers [controllers router]
  (reduce-kv (fn [m k v]
            (assoc m k (assoc v :redirect-fn
                              (partial app-state-core/redirect! router)))) {} controllers))

(defn ^:private add-stop-fn [state stop-fn]
  (assoc state :stop-fns (conj (:stop-fns state) stop-fn)))

(defn start-selected-router! [state constructor]
  (let [routes (:routes state) 
        routes-chan (:routes-chan state)
        router (constructor routes routes-chan state)]
    (-> state
        (assoc :router router)
        (add-stop-fn (fn [s]
                       (app-state-core/stop! router)
                       s)))))

(defn start-router! [state]
  (let [router (:router state)]
    (case router
      :hashchange (start-selected-router! state hashchange-router/constructor)
      :react-native (start-selected-router! state react-native-router/constructor)
      :history (start-selected-router! state history-router/constructor)
      state)))

(defn ^:private resolve-main-component [state]
  (let [router (:router state)
        current-route-reaction (reaction (:route @(:app-db state)))
        resolved
        (partial ui/component->renderer
                 {:commands-chan (:commands-chan state)
                  :reporter (partial (:reporter state) :component :out)
                  :app-db (:app-db state)
                  :url-fn (partial app-state-core/url router) 
                  :redirect-fn (partial app-state-core/redirect! router)
                  :current-route-fn (fn [] current-route-reaction)})]
    (assoc state :main-component
           (-> (ui/system (:components state) (or (:subscriptions state) {}))
               (resolved)))))

(defn app-renderer [state]
  [(fn []
      (let [route-data (get-in @(:app-db state) [:route :data])
            main-component (:main-component state)
            router (:router state)
            route-wrap-component (app-state-core/wrap-component router)]
        (when route-data
          (if route-wrap-component
            [route-wrap-component [main-component]]
            [main-component]))))])

(defn ^:private mount-to-element! [state]
  (let [main-component (:main-component state) 
        container (:html-element state)]
    (reagent/render-component (app-renderer state) container)
    (add-stop-fn state (fn [s] 
                         (reagent/unmount-component-at-node container)))))

(defn ^:private start-controllers [state]
  (let [router (:router state)
        reporter (:reporter state)
        controllers (-> (:controllers state)
                        (add-reporter-to-controllers reporter)
                        (add-redirect-fn-to-controllers router))
        routes-chan (:routes-chan state)
        commands-chan (:commands-chan state)
        app-db (:app-db state)
        manager (controller-manager/start routes-chan commands-chan app-db controllers reporter)]
    (add-stop-fn state (fn [s]
                         ((:stop manager))
                         s))))

(defn add-sub-cache [cache [key sub]]
  [key
   (fn [app-db-atom & args]
     (let [cached (get @cache [key args])]
       (if cached
         cached
         (let [sub-reaction (apply sub (into [app-db-atom] (vec args)))]
           (swap! cache assoc [key args] sub-reaction)
           sub-reaction))))])

(defn ^:private start-subs-cache [state]
  (let [subscriptions (:subscriptions state)
        subs-cache (:subscriptions-cache state)
        cached-subscriptions (into {} (map #(add-sub-cache subs-cache %) subscriptions))]
    (assoc state :subscriptions cached-subscriptions)))

(defn ^:private log-state [state]
  (do
    (.log js/console (clj->js state))
    state))

(defn restore-app-db [old-app new-app]
  (let [old-app-db @(:app-db old-app)
        new-app-db-atom (:app-db new-app)]
    (reset! new-app-db-atom
            (merge @new-app-db-atom
                   (-> old-app-db
                       (dissoc :internal)
                       (dissoc :route))))))

(defn start!
  "Starts the application. It receives the application config `map` as the first argument.
  It receives `boolean` `should-mount?` as the second element. Default value for `should-mount?`
  is `true`.

  You can pass false to the `should-mount?` argument if you want to start the app,
  but you want to manually mount the application (for instance another app could manage mounting
  and unmounting). In that case you can get the main app component at the `:main-component` of the
  map returned from the `start!` function.

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
  4. Application is (optionally) mounted into the DOM
  
  "
  ([config] (start! config true))
  ([config should-mount?]
   (let [initial-data (or (:init-data config) {})
         config (process-config (merge (default-config initial-data) config))
         mount (if should-mount? mount-to-element! identity)]
     (-> config
         (start-subs-cache)
         (start-router!)
         (start-controllers)
         (resolve-main-component)
         (mount)))))

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

