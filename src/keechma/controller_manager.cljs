(ns keechma.controller-manager
  (:require [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.util :refer [dissoc-in]]
            [keechma.controller :as controller :refer [SerializedController]]
            [keechma.reporter :as reporter])
  (:require-macros [cljs.core.async.macros :as m :refer [go go-loop]]))

(defn ^:private send-command-to
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

(defn ^:private route-command-to-controller [reporter controllers command-name command-args cmd-info]
  (let [[controller-name command-name] command-name
        controller (get controllers controller-name)]
    (if controller
      (send-command-to reporter controller command-name command-args cmd-info)
      (when (.-DEBUG js/goog)
        (.warn js/console "Trying to send command to a controller that is not running" {:controller controller-name :command command-name :args command-args})))))

(defn report-running-controllers [app-db-atom]
  (let [running-controllers (get-in @app-db-atom [:internal :running-controllers])]
    (reduce (fn [acc [k v]] (assoc acc k (:params v))) {} running-controllers)))

(defn route-change-execution-plan [route-params running-controllers controllers]
  (let [plan {:stop          {}
              :start         {}
              :wake          {}
              :route-changed []}]
    (reduce 
     (fn [acc [topic controller]]
       (let [{:keys [stop start wake route-changed]} acc
             new-params                              (controller/params controller route-params)
             running-controller                      (get running-controllers topic)
             prev-params                             (:params running-controller)]
         (cond
           (= SerializedController (type running-controller)) (assoc acc :wake (assoc wake topic new-params))
           (and (nil? prev-params) (nil? new-params))         acc
           (and (nil? prev-params) (some? new-params))        (assoc acc :start (assoc start topic new-params))
           (and (some? prev-params) (nil? new-params))        (assoc acc :stop (assoc stop topic new-params))
           (not= new-params prev-params)                      (assoc acc :stop (assoc stop topic new-params) :start (assoc start topic new-params))
           (= new-params prev-params)                         (assoc acc :route-changed (conj route-changed topic))
           :else                                              acc))) 
     plan 
     controllers)))

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
          (let [new-app-db (-> (controller/stop controller (:params controller) app-db)
                               (dissoc-in [:internal :running-controllers topic]))]
            (close! (:in-chan controller))
            (recur (rest stop) new-app-db)))
        app-db))))

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
                new-app-db (-> (action controller params app-db)
                               (assoc-in [:internal :running-controllers topic] controller))]
            (recur (rest start-or-wake) new-app-db)))
        app-db)))

(def apply-start-controllers (partial apply-start-or-wake-controllers controller/start :start))
(def apply-wake-controllers (partial apply-start-or-wake-controllers controller/wake :wake))

(defn call-handler-on-started-controllers [app-db-atom reporter start]
  (doseq [[topic _] start]
    (let [controller (get-in @app-db-atom [:internal :running-controllers topic])
          app-out-cmd-info (reporter/cmd-info)
          controller-in-cmd-info (reporter/with-origin app-out-cmd-info)]
      (reporter :app :out :controller [topic [:keechma/lifecycle :handler]] nil app-out-cmd-info :info)
      (reporter :controller :in topic [:keechma/lifecycle :handler] nil controller-in-cmd-info :info)
      (controller/handler controller app-db-atom (:in-chan controller) (:out-chan controller)))))

(defn send-route-changed-to-surviving-controllers [app-db-atom reporter route-changed route-params]
  (doseq [topic route-changed]
    (let [controller (get-in @app-db-atom [:internal :running-controllers topic])]
      (send-command-to reporter controller :route-changed route-params))))

(defn apply-route-change [reporter route-params app-db-atom commands-chan controllers]
  (reporter :router :out nil :route-changed route-params (reporter/cmd-info) :info)
  (let [app-db @app-db-atom
        execution-plan (route-change-execution-plan route-params (get-in app-db [:internal :running-controllers]) controllers)
        {:keys [stop start wake route-changed]} execution-plan
        get-running (fn [topic] (get-in @app-db-atom [:internal :running-controllers topic]))
        active-topics #(keys (get-in @app-db-atom [:internal :running-controllers]))]
    (reset! app-db-atom
            (-> (assoc app-db :route route-params)
                (apply-stop-controllers reporter stop)
                (apply-start-controllers reporter controllers commands-chan get-running active-topics start)
                (apply-wake-controllers reporter controllers commands-chan get-running active-topics wake)))
    (call-handler-on-started-controllers app-db-atom reporter (concat start wake))
    (send-route-changed-to-surviving-controllers app-db-atom reporter route-changed route-params))
  (reporter :app :in nil :running-controllers (report-running-controllers app-db-atom) (reporter/cmd-info) :info))

(defn call-ssr-handler-on-started-controllers [app-db-atom reporter start ssr-handler-done-cb]
  (let [wait-chan (chan)
        wait-count (loop [wait-count 0
                          start start]
                     (if-let [s (first start)]
                       (let [[topic _] s
                             controller (get-in @app-db-atom [:internal :running-controllers topic])]
                         (reporter :app :out :controller [topic :ssr-handler] (reporter/cmd-info) :info)
                         (let [ret-val (controller/ssr-handler
                                        controller
                                        app-db-atom
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

(defn start-ssr [routes-chan commands-chan app-db-atom controllers reporter done-cb]
  (let [app-db @app-db-atom
        route-params (:route app-db)
        execution-plan (route-change-execution-plan route-params {} controllers)
        {:keys [start]} execution-plan
        get-running (fn [topic] (get-in @app-db-atom [:internal :running-controllers topic]))
        active-topics #(keys (get-in @app-db-atom [:internal :running-controllers]))
        ssr-handler-done-cb (fn []
                              (close! commands-chan)
                              (done-cb))]
    (reset! app-db-atom (apply-start-controllers app-db reporter controllers commands-chan get-running active-topics start))
    (go-loop []
      (when-let [command (<! commands-chan)]
        (let [[command-name command-args cmd-info] command
              running-controllers (get-in @app-db-atom [:internal :running-controllers])]
          (route-command-to-controller reporter running-controllers command-name command-args cmd-info)
          (recur))))
    (call-ssr-handler-on-started-controllers app-db-atom reporter start ssr-handler-done-cb)))

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
  the name of the command should look like this `[:controlnler-key :command-name]`. Controller manager will route the `:command-name` command to the appropriate controller based on the `:controller-key`. Controller key is the key under which the controller was registered in the `controllers` argument.
  "

  [route-chan route-processor commands-chan app-db-atom controllers reporter]
  (reporter :app :in nil :start (vec (keys controllers)) (reporter/cmd-info) :info)
  (apply-route-change reporter (route-processor (:route @app-db-atom) @app-db-atom) app-db-atom commands-chan controllers)
  (let [current-route-value (atom (:route @app-db-atom))
        stop-route-block (chan)
        stop-command-block (chan)
        running-chans
        [(go-loop []
           ;; When route changes:
           ;;   - stop controllers that return nil from their params functions
           ;;   - start controllers that need to be started
           ;;   - restart controllers that were running with the different params (stop the old instance and start the new one)
           ;;   - send "route-changed" command to controllers that were already running 
           (let [[val channel] (alts! [stop-route-block route-chan])]
             (when (and (not= channel stop-route-block) val)
               (reset! current-route-value val)
               (let [route-params (route-processor val @app-db-atom)]
                 (when (not= route-params (:route @app-db-atom))
                   (apply-route-change reporter route-params app-db-atom commands-chan controllers))
                 (recur)))))
         (go-loop []
           (let [[val channel] (alts! [stop-command-block commands-chan])]
             (when-not (= channel stop-command-block)
               (let [[command-name command-args cmd-info] val 
                     running-controllers (get-in @app-db-atom [:internal :running-controllers])]
                 (when (not (nil? command-name))
                   (if (= ::reroute command-name)
                     (put! route-chan @current-route-value)
                     (route-command-to-controller reporter running-controllers command-name command-args cmd-info)))
                 (recur)))))]]
    {:running-chans running-chans
     :stop (fn []
             (reporter :app :in nil :stop nil (reporter/cmd-info) :info)
             (let [controllers (get-in @app-db-atom [:internal :running-controllers])]
               (close! stop-route-block)
               (close! stop-command-block)
               (doseq [running running-chans] (close! running))
               (reset! app-db-atom
                       (apply-stop-controllers @app-db-atom reporter (reduce (fn [acc [key controller]] (assoc acc key nil)) {} controllers)))))}))

