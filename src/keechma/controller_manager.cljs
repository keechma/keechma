(ns keechma.controller-manager
  (:require [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.util :refer [dissoc-in keechma-ex-info]]
            [keechma.controller :as controller :refer [SerializedController]]
            [keechma.reporter :as reporter]
            [clojure.set :as set])
  (:require-macros [cljs.core.async.macros :as m :refer [go go-loop]]))

(declare ^:dynamic *current-stopping-controller*)
(declare ^:dynamic *current-starting-controller*)
(declare deregister-controllers)
(declare synchronize-child-controllers)

(defn get-running-controller-for-topic [app-db$ topic]
  (get-in @app-db$ [:internal :running-controllers topic]))

(defn get-active-topics [app-db$]
  (keys (get-in @app-db$ [:internal :running-controllers])))

(defn get-controller-params [controller route-params]
  (or (controller/get-static-params controller)
      (controller/params controller route-params)))

(defn send-command-to
  ([reporter controller command-name args]
   (send-command-to reporter controller command-name args (when (= :route-changed command-name) (reporter/cmd-info))))
  ([reporter controller command-name args origin]
   (let [cmd-info (reporter/with-origin origin)]
     (if (= :route-changed command-name)
       (do
         (reporter :app :out :controller [(:name controller) command-name] args origin :info)
         (reporter :controller :in (:name controller) command-name args cmd-info :info))
       (reporter :controller :in (:name controller) command-name args cmd-info :info))
     (put! (:in-chan controller) [command-name args cmd-info])
     controller)))

(defn route-command-to-controller [reporter controllers command-name command-args cmd-info]
  (let [[controller-name command-name] command-name
        controller (get controllers controller-name)]
    (if controller
      (send-command-to reporter controller command-name command-args cmd-info)
      (when (.-DEBUG js/goog)
        (.warn js/console "Trying to send command to a controller that is not running" (pr-str {:controller controller-name :command command-name :args command-args}))))))

(defn report-running-controllers [app-db$]
  (let [running-controllers (get-in @app-db$ [:internal :running-controllers])]
    (reduce (fn [acc [k v]] (assoc acc k (:params v))) {} running-controllers)))

(defn get-descendant-controller-topics
  ([topic topics-grouped-by-owner] (get-descendant-controller-topics topic topics-grouped-by-owner []))
  ([topic topics-grouped-by-owner result]
   (reduce
    (fn [acc t]
      (vec (concat acc (conj (get-descendant-controller-topics t topics-grouped-by-owner) t))))
    result
    (get topics-grouped-by-owner topic))))

(defn get-topics-grouped-by-owner [controllers]
  (reduce-kv
   (fn [acc topic c]
     (if-let [owner (::parent c)]
       (update acc owner conj topic)
       acc))
   {}
   controllers))

(defn route-change-execution-plan [route-params running-controllers controllers]
  (let [topics-grouped-by-owner (get-topics-grouped-by-owner controllers)
        running-topics (set (keys running-controllers))
        plan {:stop          []
              :start         {}
              :wake          {}
              :route-changed []
              :deregister    #{}}]
    (reduce 
     (fn [acc [topic controller]]
       (if (contains? (:deregister acc) topic)
         acc
         (let [{:keys [stop start wake route-changed]} acc
               new-params                              (get-controller-params controller route-params) 
               running-controller                      (get running-controllers topic)
               prev-params                             (:params running-controller)
               actions                                 (cond
                                                         (= SerializedController (type running-controller)) #{:wake}
                                                         (and (nil? prev-params) (nil? new-params))         #{}
                                                         (and (nil? prev-params) (some? new-params))        #{:start}
                                                         (and (some? prev-params) (nil? new-params))        #{:stop} 
                                                         (not= new-params prev-params)                      #{:stop :start}
                                                         (= new-params prev-params)                         #{:route-changed}
                                                         :else                                              #{})
               descendant-controller-topics-to-stop (if (contains? actions :stop) (get-descendant-controller-topics topic topics-grouped-by-owner) nil)
               acc' (if descendant-controller-topics-to-stop
                      (-> acc
                          (update :deregister set/union (set descendant-controller-topics-to-stop))
                          (update :stop (fn [s] (vec (concat s (map (fn [t] [t nil]) (filter #(contains? running-topics %) descendant-controller-topics-to-stop)))))))
                      acc)] 

           (cond-> acc'
             (contains? actions :wake)          (assoc-in [:wake topic] new-params)
             (contains? actions :stop)          (update :stop conj [topic new-params])
             (contains? actions :start)         (assoc-in [:start topic] new-params)
             (contains? actions :route-changed) (update :route-changed conj topic))))) 
     plan
     ;; Process global controllers (without owner) first 
     (sort-by (fn [[_ c]] (::parent c)) controllers))))

(defn apply-stop-controller [app-db controller topic]
  (binding [*current-stopping-controller* controller]
    (-> (controller/stop controller (:params controller) app-db)
        (dissoc-in [:internal :running-controllers topic]))))

(defn apply-stop-controllers [app-db reporter stop]
  (let [running-controllers (get-in app-db [:internal :running-controllers])]
    (loop [stop stop
           app-db app-db]
      (if-let [s (first stop)]
        (let [[topic params] s
              controller (get running-controllers topic)
              app-out-cmd-info (reporter/cmd-info)
              controller-in-cmd-info (reporter/with-origin app-out-cmd-info)]
          (reporter :app :out :controller [topic [:keechma/lifecycle :stop]] params app-out-cmd-info :info)
          (reporter :controller :in topic [:keechma/lifecycle :stop] (:params controller) controller-in-cmd-info :info)
          (let [new-app-db (apply-stop-controller app-db controller topic)]
            (close! (:in-chan controller))
            (recur (rest stop) new-app-db)))
        app-db))))

(defn apply-start-or-wake-controller [app-db action controller topic params]
  (binding [*current-starting-controller* controller]
    (-> (action controller params app-db)
        (assoc-in [:internal :running-controllers topic] controller))))

(defn apply-start-or-wake-controllers
  [action reporter-action app-db reporter controllers commands-chan get-running active-topics start-or-wake]
  (loop [start-or-wake start-or-wake
         app-db app-db]
      (if-let [s (first start-or-wake)]
        (let [[topic params] s
              app-out-cmd-info (reporter/cmd-info)
              controller-in-cmd-info (reporter/with-origin app-out-cmd-info)]
          (reporter :app :out :controller [topic [:keechma/lifecycle reporter-action]] params app-out-cmd-info :info)
          (reporter :controller :in topic [:keechma/lifecycle reporter-action] params controller-in-cmd-info :info)
          (let [controller (assoc (get controllers topic)
                                  :in-chan (chan)
                                  :out-chan commands-chan
                                  :params params
                                  :route-params (:route app-db)
                                  :name topic
                                  :reporter reporter
                                  :running (partial get-running topic)
                                  :active-topics active-topics)
                new-app-db (apply-start-or-wake-controller app-db action controller topic params)]
            (recur (rest start-or-wake) new-app-db)))
        app-db)))

(def apply-start-controllers (partial apply-start-or-wake-controllers controller/start :start))
(def apply-wake-controllers (partial apply-start-or-wake-controllers controller/wake :wake))

(defn call-handler-on-started-controllers [app-db$ reporter start]
  (doseq [[topic _] start]
    (let [controller (get-in @app-db$ [:internal :running-controllers topic])
          app-out-cmd-info (reporter/cmd-info)
          controller-in-cmd-info (reporter/with-origin app-out-cmd-info)]
      (reporter :app :out :controller [topic [:keechma/lifecycle :handler]] nil app-out-cmd-info :info)
      (reporter :controller :in topic [:keechma/lifecycle :handler] nil controller-in-cmd-info :info)
      (controller/handler controller app-db$ (:in-chan controller) (:out-chan controller)))))

(defn send-route-changed-to-surviving-controllers [app-db$ reporter route-changed route-params]
  (doseq [topic route-changed]
    (let [controller (get-in @app-db$ [:internal :running-controllers topic])]
      (send-command-to reporter controller :route-changed route-params))))

(defn apply-route-change [reporter route-params app-db$ controllers$ commands-chan]
  (reporter :router :out nil :route-changed route-params (reporter/cmd-info) :info)
  (let [app-db @app-db$
        controllers @controllers$
        execution-plan (route-change-execution-plan route-params (get-in app-db [:internal :running-controllers]) controllers)
        {:keys [stop start wake route-changed deregister]} execution-plan
        get-running (partial get-running-controller-for-topic app-db$) 
        active-topics (partial get-active-topics app-db$)]
    (reset! app-db$
            (-> (assoc app-db :route route-params)
                (apply-stop-controllers reporter stop)
                (apply-start-controllers reporter controllers commands-chan get-running active-topics start)
                (apply-wake-controllers reporter controllers commands-chan get-running active-topics wake)))
    (deregister-controllers controllers$ deregister)
    (call-handler-on-started-controllers app-db$ reporter (concat start wake))
    (send-route-changed-to-surviving-controllers app-db$ reporter route-changed route-params))
  (reporter :app :in nil :running-controllers (report-running-controllers app-db$) (reporter/cmd-info) :info))

(defn call-ssr-handler-on-started-controllers [app-db$ reporter start ssr-handler-done-cb]
  (let [wait-chan (chan)
        wait-count (loop [wait-count 0
                          start start]
                     (if-let [s (first start)]
                       (let [[topic _] s
                             controller (get-in @app-db$ [:internal :running-controllers topic])]
                         (reporter :app :out :controller [topic :ssr-handler] (reporter/cmd-info) :info)
                         (let [ret-val (controller/ssr-handler
                                        controller
                                        app-db$
                                        #(put! wait-chan true)
                                        (:in-chan controller)
                                        (:out-chan controller))]
                           (if (= controller/not-implemented ret-val)
                             (recur wait-count (rest start))
                             (recur (inc wait-count) (rest start)))))
                       wait-count))]
    (go-loop [wait-count wait-count]
      (if (= 0 wait-count)
        (ssr-handler-done-cb)
        (let [msg (<! wait-chan)]
          (when msg
            (recur (dec wait-count))))))))

(defn get-children [app-db$ controllers$ controller]
  (let [controllers @controllers$
        running-controllers (get-in @app-db$ [:internal :running-controllers])
        controller-name (:name controller)
        child-controllers (map first (filter (fn [[_ c]] (= controller-name (::parent c))) controllers))]
    (reduce
     (fn [acc c-name]
       (assoc acc c-name {:params (get-in running-controllers [c-name :params])}))
     {}
     child-controllers)))

(defn get-register-child-controllers-plan [app-db$ parent-controller child-controllers-to-register]
  (when *current-starting-controller*
    (throw (keechma-ex-info "Registering child controllers is only possible from the handler function"
                            ::controller-lifecycle
                            {:controller/children (keys child-controllers-to-register)
                             :controller          (:name *current-starting-controller*)})))
  (let [app-db                          @app-db$
        parent-controller-name          (:name parent-controller)
        route-params                    (:route app-db)
        running-controllers             (get-in app-db [:internal :running-controllers])
        running-topics-grouped-by-owner (get-topics-grouped-by-owner running-controllers)
        plan                            {:start {}
                                         :stop  []}]
    (reduce-kv
     (fn [acc topic controller]
       (let [{:keys [stop start]} acc
             running              (get running-controllers topic)
             prev-params          (get running :params)
             new-params           (get-controller-params controller route-params)]
         
         (when (and running (not= (::parent running) (::parent controller)))
           (throw (keechma-ex-info "Trying to register a controller that is owned by a different parent" 
                                   ::controller-ownership
                                   {:controller/child topic
                                    :controller/owner (::parent running)
                                    :controller       parent-controller-name})))
         (let [actions 
               (cond
                 (and new-params prev-params (not= new-params prev-params)) #{:stop :start}
                 (and new-params (not prev-params))                         #{:start}
                 :else                                                      #{})
               descendant-controller-topics-to-stop (if (contains? actions :stop) (get-descendant-controller-topics topic running-topics-grouped-by-owner) nil)
               acc' (if descendant-controller-topics-to-stop
                      (update acc :stop (fn [s] (vec (concat s (map (fn [t] [t nil]) descendant-controller-topics-to-stop)))))
                      acc)]
             (cond-> acc'
               (contains? actions :stop)          (update :stop conj [topic new-params])
               (contains? actions :start)         (assoc-in [:start topic] new-params)))))
     plan
     child-controllers-to-register)))

(defn deregister-child-controllers [app-db$ controllers$ reporter commands-chan parent-controller child-controllers-names]
  (when *current-starting-controller*
    (throw (keechma-ex-info "Deregistering child controllers is only possible from the handler function. All registered controllers are automatically cleaned up when the parent controller is stopped."
                            ::controller-lifecycle
                            {:controller/children child-controllers-names
                             :controller (:name *current-starting-controller*)})))
  (let [controllers @controllers$
        parent-controller-name (:name parent-controller)
        topics-grouped-by-owner (get-topics-grouped-by-owner controllers)
        child-controllers (select-keys controllers child-controllers-names)]
    (doseq [[topic child-controller] child-controllers]
      (let [child-controller-owner-name (::parent child-controller)]
        (when (not= child-controller-owner-name parent-controller-name)
          (throw (keechma-ex-info "Trying to deregister a controller that is owned by a different parent"
                                  ::controller-ownership
                                  {:controller/child topic
                                   :controller/owner child-controller-owner-name
                                   :controller parent-controller-name})))))
    (let [app-db @app-db$
          topics-to-deregister 
          (mapcat 
           (fn [t] 
             (conj (get-descendant-controller-topics t topics-grouped-by-owner) t)) 
           (keys child-controllers))
          running-controllers (set (keys (get-in app-db [:internal :running-controllers])))
          stop-plan (map (fn [t] [t nil]) (filter #(contains? running-controllers %) topics-to-deregister))] 
      (swap! controllers$ #(apply dissoc % topics-to-deregister))
      (reset! app-db$ (apply-stop-controllers app-db reporter stop-plan)))))

(defn register-child-controllers [app-db$ controllers$ reporter commands-chan controller child-controllers-to-register]
  (let [get-running (partial get-running-controller-for-topic app-db$) 
        active-topics (partial get-active-topics app-db$)
        register-child-controllers' (partial register-child-controllers app-db$ controllers$ reporter commands-chan)
        deregister-child-controllers' (partial deregister-child-controllers app-db$ controllers$ reporter commands-chan)
        synchronize-child-controllers' (partial synchronize-child-controllers app-db$ controllers$ reporter commands-chan)
        get-children' (partial get-children app-db$ controllers$)
        child-controllers-to-register'
        (->> (map
             (fn [[c-name c-config]]
               (let [c-controller (:controller c-config)
                     c-params (:params c-config)
                     prepared (-> c-controller
                                  (controller/assoc-static-params c-params)
                                  (assoc ::parent (:name controller)
                                         ::register-child-controllers register-child-controllers'
                                         ::deregister-child-controllers deregister-child-controllers'
                                         ::synchronize-child-controllers synchronize-child-controllers'
                                         ::get-children get-children'))]
                 [c-name prepared]))
             child-controllers-to-register)
             (into {}))
        plan (get-register-child-controllers-plan app-db$ controller child-controllers-to-register')]
    (swap! controllers$ merge child-controllers-to-register')
    (reset! app-db$
            (-> @app-db$
                (apply-stop-controllers reporter (:stop plan))
                (apply-start-controllers reporter @controllers$ commands-chan get-running active-topics (:start plan))))
    (call-handler-on-started-controllers app-db$ reporter (:start plan))
    @app-db$))

(defn synchronize-child-controllers [app-db$ controllers$ reporter commands-chan controller new-children]
  (let [children (get-children app-db$ controllers$ controller)
        children-names (set (keys children))
        new-children-names (set (keys new-children))
        children-to-deregister (set/difference children-names new-children-names)]
    (deregister-child-controllers app-db$ controllers$ reporter commands-chan controller children-to-deregister)
    (register-child-controllers app-db$ controllers$ reporter commands-chan controller new-children)))

(defn register-controllers [app-db$ controllers$ reporter commands-chan controllers]
  (let [controllers-store @controllers$
        register-child-controllers' (partial register-child-controllers app-db$ controllers$ reporter commands-chan)
        deregister-child-controllers' (partial deregister-child-controllers app-db$ controllers$ reporter commands-chan)
        synchronize-child-controllers' (partial synchronize-child-controllers app-db$ controllers$ reporter commands-chan)
        get-children' (partial get-children app-db$ controllers$)
        prepared
        (reduce-kv
         (fn [controllers-store' controller-name controller]
           (let [controller' (assoc controller
                                    ::register-child-controllers register-child-controllers'
                                    ::deregister-child-controllers deregister-child-controllers'
                                    ::synchronize-child-controllers synchronize-child-controllers'
                                    ::get-children get-children')]
             (assoc controllers-store' controller-name controller')))
         controllers-store
         controllers)]
    (reset! controllers$ prepared)))

(defn deregister-controllers [controllers$ controllers]
  (let [controllers-store @controllers$]
    (reset! controllers$ (apply dissoc controllers-store controllers))))

(defn start-ssr [routes-chan commands-chan app-db$ controllers reporter done-cb]
  (let [controllers$ (atom {})
        app-db @app-db$
        route-params (:route app-db)
        get-running (fn [topic] (get-in @app-db$ [:internal :running-controllers topic]))
        active-topics #(keys (get-in @app-db$ [:internal :running-controllers]))
        ssr-handler-done-cb (fn []
                              (close! commands-chan)
                              (done-cb))]
    (register-controllers app-db$ controllers$ reporter commands-chan controllers)
    (let [controllers' @controllers$
          execution-plan (route-change-execution-plan route-params {} controllers')
          {:keys [start]} execution-plan]
      (reset! app-db$ (apply-start-controllers app-db reporter controllers' commands-chan get-running active-topics start))
      (go-loop []
        (when-let [command (<! commands-chan)]
          (let [[command-name command-args cmd-info] command
                running-controllers (get-in @app-db$ [:internal :running-controllers])]
            (route-command-to-controller reporter running-controllers command-name command-args cmd-info)
            (recur))))
      (call-ssr-handler-on-started-controllers app-db$ reporter start ssr-handler-done-cb))))

(defn start
  "Starts the controller manager. Controller manager is the central part
  of the application that manages the lifecycle of the controllers and routes
  the messages sent to them.

  `start` function receives the following parameters:
  
  - `route-chan` - Route changes will communicated through this channel
  - `route-processor` - Function that will be called on every route change. It can be used to process the route before it's written into app-db
  - `commands-chan` - User (UI) commands will be sent through this channel
  - `app-db` - application state atom
  - `controllers` map of controllers registered for the app
  - `reporter` - internal reporter function

  Each time when the new route data comes through the `route-chan` controller
  manager will do the following:

  - call the `params` function on each registered controller
  - compare the value returned by the `params` function with the value that
  was returned last time when the route changes
  - based on the comparison it will do one of the following:
    - if the last value was `nil` and the current value is `nil` - do nothing
    - if the last value was `nil` and the current value is not `nil` - start the controller
    - if the last value was not `nil` and the current value is `nil` - stop the controller
    - if the last value was not `nil` and the current value is not `nil` and these values are the same - do nothing
    - if the last value was not `nil` and the current value is not `nil` and these values are different - restart the controller (stop the current instance and start the new one)

  Controller manager also acts as a command router. Each time a command comes - through the `commands-chan`
  the name of the command should look like this `[:controller-key :command-name]`. Controller manager will route the `:command-name` command to the appropriate controller based on the `:controller-key`. Controller key is the key under which the controller was registered in the `controllers` argument.
  "

  [route-chan route-processor commands-chan app-db$ controllers reporter]
  (let [controllers$ (atom {})
        current-route-value (atom (:route @app-db$))
        stop-route-block (chan)
        stop-command-block (chan)]
    (register-controllers app-db$ controllers$ reporter commands-chan controllers)
    (reporter :app :in nil :start (vec (keys controllers)) (reporter/cmd-info) :info)
    (apply-route-change reporter (route-processor (:route @app-db$) @app-db$) app-db$ controllers$ commands-chan)
    (let [running-chans
          [(go-loop []
             ;; When route changes:
             ;;   - stop controllers that return nil from their params functions
             ;;   - start controllers that need to be started
             ;;   - restart controllers that were running with the different params (stop the old instance and start the new one)
             ;;   - send "route-changed" command to controllers that were already running 
             (let [[val channel] (alts! [stop-route-block route-chan])]
               (when (and (not= channel stop-route-block) val)
                 (reset! current-route-value val)
                 (let [route-params (route-processor val @app-db$)]
                   (when (not= route-params (:route @app-db$))
                     (apply-route-change reporter route-params app-db$ controllers$ commands-chan))
                   (recur)))))
           (go-loop []
             (let [[val channel] (alts! [stop-command-block commands-chan])]
               (when-not (= channel stop-command-block)
                 (let [[command-name command-args cmd-info] val 
                       running-controllers (get-in @app-db$ [:internal :running-controllers])]
                   (when (not (nil? command-name))
                     (if (= ::reroute command-name)
                       (put! route-chan @current-route-value)
                       (route-command-to-controller reporter running-controllers command-name command-args cmd-info)))
                   (recur)))))]]
      {:running-chans running-chans
       :stop (fn []
               (reporter :app :in nil :stop nil (reporter/cmd-info) :info)
               (let [running-controllers (get-in @app-db$ [:internal :running-controllers])]
                 (close! stop-route-block)
                 (close! stop-command-block)
                 (doseq [running running-chans] (close! running))
                 (reset! app-db$
                         (apply-stop-controllers @app-db$ reporter (reduce (fn [acc [key controller]] (assoc acc key nil)) {} running-controllers)))))})))

