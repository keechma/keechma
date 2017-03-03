(ns keechma.controller-manager
  (:require [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.util :refer [dissoc-in]]
            [keechma.controller :as controller])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(defn ^:private send-command-to [reporter controller command-name args] 
  (reporter :controller :in (:name controller) command-name args :info)
  (put! (:in-chan controller) [command-name args])
  controller)

(defn ^:private route-command-to-controller [reporter controllers command-name command-args]
  (let [[controller-name command-name] command-name
        controller (get controllers controller-name)]
    (if controller
      (send-command-to reporter controller command-name command-args)
      (throw (ex-info "Trying to send command to a controller that is not running" {:controller controller-name :command command-name :args command-args})))))

(defn route-change-execution-plan [route-params running-controllers controllers]
  (let [plan {:stop          {}
              :start         {}
              :wake          {}
              :route-changed []}
        running-controller-keys-set (set (keys running-controllers))]
    (reduce (fn [acc [topic controller]]
              (let [{:keys [stop start wake route-changed]} acc
                    new-params (controller/params controller route-params)
                    prev-params (get-in running-controllers [topic :params])]
                (cond
                  (and (nil? prev-params)       (nil? new-params)) acc
                  (and (nil? prev-params)       (boolean new-params)) (assoc acc :start (assoc start topic new-params))
                  (and (boolean prev-params)    (nil? new-params)) (assoc acc :stop (assoc stop topic new-params))
                  (not= new-params prev-params) (assoc acc :stop (assoc stop topic new-params) :start (assoc start topic new-params))
                  (= new-params prev-params)    (assoc acc :route-changed (conj route-changed topic))
                  :else acc))) plan controllers)))

(defn apply-stop-controllers [app-db reporter stop]
  (let [running-controllers (get-in app-db [:internal :running-controllers])]
    (loop [stop stop
           app-db app-db]
      (if-let [s (first stop)]
        (let [[topic params] s
              controller (get running-controllers topic)
              new-app-db (-> (controller/stop controller (:params controller) app-db)
                             (dissoc-in [:internal :running-controllers topic]))]
          (reporter :app :out :controller [topic :stop] nil :info)
          (recur (rest stop) new-app-db))
        app-db))))

(defn apply-start-controllers [app-db reporter controllers commands-chan get-running start]
  (loop [start start
         app-db app-db]
      (if-let [s (first start)]
        (let [[topic params] s
              controller (assoc (get controllers topic)
                                :in-chan (chan)
                                :out-chan commands-chan
                                :params params
                                :route-params (:route app-db)
                                :name topic
                                :reporter reporter
                                :running (partial get-running topic))
              new-app-db (-> (controller/start controller params app-db)
                             (assoc-in [:internal :running-controllers topic] controller))]
          (reporter :app :out :controller [topic :start] params :info)
          (recur (rest start) new-app-db))
        app-db)))

(defn call-handler-on-started-controllers [app-db-atom reporter start]
  (doseq [[topic _] start]
    (let [controller (get-in @app-db-atom [:internal :running-controllers topic])]
      (reporter :app :out :controller [topic :handler] nil :info)
      (controller/handler controller app-db-atom (:in-chan controller) (:out-chan controller)))))

(defn send-route-changed-to-surviving-controllers [app-db-atom reporter route-changed route-params]
  (doseq [topic route-changed]
    (let [controller (get-in @app-db-atom [:internal :running-controllers topic])]
      (send-command-to reporter controller :route-changed route-params))))

(defn apply-route-change [reporter route-params app-db-atom commands-chan controllers]
  (let [app-db @app-db-atom
        execution-plan (route-change-execution-plan route-params (get-in app-db [:internal :running-controllers]) controllers)
        {:keys [stop start wake route-changed]} execution-plan
        get-running (fn [topic] (get-in @app-db-atom [:internal :running-controllers topic]))]
    (reset! app-db-atom
            (-> (assoc app-db :route route-params)
                (apply-stop-controllers reporter stop)
                (apply-start-controllers reporter controllers commands-chan get-running start)))
    (call-handler-on-started-controllers app-db-atom reporter start)
    (send-route-changed-to-surviving-controllers app-db-atom reporter route-changed route-params)))

(defn start
  "Starts the controller manager. Controller manager is the central part
  of the application that manages the lifecycle of the controllers and routes
  the messages sent to them.

  `start` function receives the following parameters:
  
  - `route-chan` - Route changes will communicated through this channel
  - `commands-chan` - User (UI) commands will be sent through this channel
  - `app-db` - application state atom
  - `controllers` map of controllers registered for the app

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

  [route-chan commands-chan app-db-atom controllers reporter]
  (reporter :app :in nil :start nil :info)
  (apply-route-change reporter (:route @app-db-atom) app-db-atom commands-chan controllers)
  (let [stop-route-block (chan)
        stop-command-block (chan)
        running-chans
        [(go
           (loop []
             ;; When route changes:
             ;;   - stop controllers that return nil from their params functions
             ;;   - start controllers that need to be started
             ;;   - restart controllers that were running with the different params (stop the old instance and start the new one)
             ;;   - send "route-changed" command to controllers that were already running
             (let [[val channel] (alts! [stop-route-block route-chan])]
               (when (and (not= channel stop-route-block) val)
                 (let [route-params val]
                   (when (not= route-params (:route @app-db-atom))
                     (apply-route-change reporter route-params app-db-atom commands-chan controllers))
                   (recur))))))
         (go
           (loop []
             (let [[val channel] (alts! [stop-command-block commands-chan])]
               (when-not (= channel stop-command-block)
                 (let [[command-name command-args] val 
                       running-controllers (get-in @app-db-atom [:internal :running-controllers])]
                   (when (not (nil? command-name))
                     (route-command-to-controller reporter running-controllers command-name command-args))
                   (recur))))))]]
    {:running-chans running-chans
     :stop (fn []
             (reporter :app :in nil :stop nil :info)
             (let [controllers (get-in @app-db-atom [:internal :running-controllers])]
               (close! stop-route-block)
               (close! stop-command-block)
               (doseq [running running-chans] (close! running))
               (reset! app-db-atom
                       (apply-stop-controllers @app-db-atom reporter (reduce (fn [acc c] (assoc acc (:name c) {})) {} controllers)))))}))

