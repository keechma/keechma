(ns keechma.app-state
  (:require [reagent.core :as reagent]
            [reagent.dom :as dom]
            [cljs.core.async :refer [put! close! chan timeout <!]]
            [keechma.ui-component :as ui]
            [keechma.controller-manager :as controller-manager]
            [keechma.controller :as controller]
            [keechma.app-state.core :as app-state-core]
            [keechma.app-state.hashchange-router :as hashchange-router]
            [keechma.app-state.react-native-router :as react-native-router]
            [keechma.app-state.history-router :as history-router]
            [keechma.app-state.memory-router :as memory-router]
            [cognitect.transit :as t])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]
                   [reagent.ratom :refer [reaction]]))

(defn default-route-processor [route _] route)

(defrecord AppState
    [name
     reporter
     router
     routes-chan
     route-processor
     commands-chan
     app-db
     subscriptions-cache
     components
     controllers
     context
     html-element
     stop-fns])

(defrecord SerializedAppState [app-db])

(defn get-controller-types-set [app-state]
  (set (map type (vals (:controllers app-state)))))

(defn prepare-for-serialization
  ([value] (prepare-for-serialization value (set {})))
  ([value controller-types]
   (cond
     (= AppState (type value))
     (->SerializedAppState (prepare-for-serialization @(:app-db value) (get-controller-types-set value)))

     (satisfies? IDeref value)
     (prepare-for-serialization (deref value) controller-types)

     (contains? controller-types (type value))
     (controller/->SerializedController (:params value))

     (or (= SerializedAppState (type value))
         (= controller/SerializedController (type value)))
     value

     (map? value)
     (reduce (fn [acc [k v]]
               (assoc acc k (prepare-for-serialization v controller-types))) {} value)
     (vector? value)
     (map #(prepare-for-serialization % controller-types) value)

     :else value)))

(deftype ControllerWriteHandler []
  Object
  (tag [this v] "controller")
  (rep [this v] #js {:params (:params v)})
  (stringRep [this v] nil))

(deftype AppStateWriteHandler []
  Object
  (tag [this v] "app-state")
  (rep [this v]
    #js {:appdb (:app-db v)})
  (stringRep [this v] nil))

(defn serialize-app-state [transit-writers state]
  (let [running-controllers (get-in @(:app-db state) [:internal :running-controllers])
        handlers (assoc transit-writers
                        SerializedAppState (AppStateWriteHandler.)
                        controller/SerializedController (ControllerWriteHandler.))
        writer (t/writer :json
                         {:handlers handlers})
        prepared-state (prepare-for-serialization state)]
    (t/write writer prepared-state)))

(defn deserialize-app-state [transit-readers serialized-state]
  (let [handlers (assoc transit-readers
                        "controller" (fn [data] (controller/->SerializedController (get data "params")))
                        "app-state" (fn [data] (->SerializedAppState (get data "appdb"))))
        reader (t/reader :json
                         {:handlers handlers})]
    (t/read reader serialized-state)))

(defn app-db [initial-data]
  (reagent/atom (merge {:route {}
                        :entity-db {}
                        :kv {}
                        :internal {}}
                       initial-data)))

(defn default-config [initial-data]
  {:name :application
   :reporter (fn [app-name type direction topic name payload cmd-info severity])
   :router :hashchange
   :routes-chan (chan)
   :route-processor default-route-processor
   :commands-chan (chan)
   :app-db (app-db initial-data)
   :subscriptions-cache (atom {})
   :components {}
   :controllers {}
   :context {}
   :html-element nil
   :stop-fns []
   :on {:stop []
        :start []}})

(defn process-config [config]
  (let [name [(:name config) (keyword (gensym "v"))]
        reporter (partial (:reporter config) name)]
    (assoc config
           :name name
           :reporter reporter)))

(defn add-reporter-to-controllers [controllers reporter]
  (reduce-kv (fn [m k v]
               (assoc m k (assoc v :reporter reporter))) {} controllers))

(defn add-redirect-fn-to-controllers [controllers router]
  (reduce-kv (fn [m k v]
               (let [new-v (assoc v
                                  :redirect-fn (partial app-state-core/redirect! router)
                                  :router router)]
                 (assoc m k new-v))) {} controllers))

(defn add-context-to-controllers [controllers context]
  (reduce-kv (fn [m k v]
               (assoc m k (assoc v :context context))) {} controllers))

(defn add-stop-fn [state stop-fn]
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
      :hashchange   (start-selected-router! state hashchange-router/constructor)
      :react-native (start-selected-router! state react-native-router/constructor)
      :history      (start-selected-router! state history-router/constructor)
      :memory       (start-selected-router! state memory-router/constructor)
      state)))

(defn default-ui-context-processor [_ ctx]
  ctx)

(defn resolve-main-component [state]
  (let [router (:router state)
        current-route-reaction (reaction (:route @(:app-db state)))
        ctx-processor (:keechma.ui-component/ctx-processor state) 
        main-component (ui/system (:components state) (or (:subscriptions state) {}))
        resolved
        (partial ui/component->renderer
                 :main
                 {:commands-chan (:commands-chan state)
                  :reporter (partial (:reporter state) :component :out)
                  :app-db (:app-db state)
                  :url-fn (partial app-state-core/url router)
                  :router router
                  :redirect-fn (partial app-state-core/redirect! router)
                  :current-route-fn (fn [] current-route-reaction)
                  :context (:context state)
                  :path []
                  :keechma.ui-component/ctx-processor ctx-processor
                  :keechma.ui-component/system (:keechma.ui-component/system main-component)})]
    (assoc state :main-component (resolved main-component))))

(defn app-renderer [state]
  [(with-meta
     (fn []
       (let [main-component (:main-component state)
             router (:router state)
             route-wrap-component (app-state-core/wrap-component router)]
         (if route-wrap-component
           [(with-meta route-wrap-component {:name :router-wrap}) [main-component]]
           [main-component])))
     {:name (str (flatten (:name state)))})])

(defn mount-to-element! [state]
  (let [main-component (:main-component state) 
        container (:html-element state)]
    (dom/render (app-renderer state) container)
    (add-stop-fn state (fn [s]
                         (dom/unmount-component-at-node container)))))

(defn start-controllers [state]
  (let [router (:router state)
        route-processor (:route-processor state)
        reporter (:reporter state)
        context (:context state)
        controllers (-> (:controllers state)
                        (add-context-to-controllers context)
                        (add-reporter-to-controllers reporter)
                        (add-redirect-fn-to-controllers router))
        routes-chan (:routes-chan state)
        commands-chan (:commands-chan state)
        app-db (:app-db state)
        manager (controller-manager/start routes-chan route-processor commands-chan app-db controllers reporter)]
    (add-stop-fn state (fn [s]
                         ((:stop manager))
                         s))))

(defn add-sub-cache [cache [key sub]]
  [key
   (fn [app-db-atom & args]
     (let [app-db-atom-hash (hash app-db-atom)
           cached (get @cache [app-db-atom-hash key args])]
       (if cached
         cached
         (let [sub-reaction (apply sub (into [app-db-atom] (vec args)))]
           (swap! cache assoc [app-db-atom-hash key args] sub-reaction)
           sub-reaction))))])

(defn start-subs-cache [state]
  (let [subscriptions (:subscriptions state)
        subs-cache (:subscriptions-cache state)
        cached-subscriptions (into {} (map #(add-sub-cache subs-cache %) subscriptions))]
    (assoc state :subscriptions cached-subscriptions)))

(defn restore-app-db [old-app new-app]
  (let [old-app-db @(:app-db old-app)
        new-app-db-atom (:app-db new-app)]
    (reset! new-app-db-atom
            (merge @new-app-db-atom
                   (-> old-app-db
                       (dissoc :internal)
                       (dissoc :route))))))

(defn get-initial-data [config]
  (let [initial-data (:initial-data config)]
    (cond
      (= SerializedAppState (type initial-data)) (:app-db initial-data)
      (nil? initial-data) {}
      :else initial-data)))

(defn run-lifecycle-fns [lifecycle config]
  (let [fns (get-in config [:on lifecycle])]
    (reduce (fn [c f] (f c)) config fns)))

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
   (let [initial-data (get-initial-data config)
         config (map->AppState (process-config (merge (default-config initial-data) config)))
         mount (if should-mount? mount-to-element! identity)
         lifecycle-runner (partial run-lifecycle-fns :start)]
     (-> config
         (start-subs-cache)
         (start-router!)
         (start-controllers)
         (resolve-main-component)
         (mount)
         (lifecycle-runner)))))

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
   (stop! config identity))
  ([config done]
   (go
     (doseq [stop-fn (:stop-fns config)] (stop-fn config))
     (let [config (run-lifecycle-fns :stop config)
           routes-chan (:routes-chan config)
           commands-chan (:commands-chan config)]
       (close! commands-chan)
       (close! routes-chan)
       (done config)))))
