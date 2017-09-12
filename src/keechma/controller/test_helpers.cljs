(ns keechma.controller.test-helpers
  (:require [keechma.controller :as controller]
            [cljs.core.async :refer [put! close! chan mult tap]])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defn log-chan [log-atom logging-chan name]
  (go-loop []
    (let [cmd (<! logging-chan)]
      (when cmd
        (let [log @log-atom
              chan-log (or (get log name) [])]
          (reset! log-atom (assoc log name (conj chan-log (take 2 cmd)))))
        (recur)))))

(defn make-redirect [log-atom]
  (fn [params]
    (let [log @log-atom
          route-log (:route log)]
      (reset! log-atom (assoc log :route (conj route-log params))))))

(defn log-app-db-changes [log-atom app-db-atom]
  (add-watch app-db-atom :keechma/test-watcher
             (fn [key app-db-atom old-state new-state]
               (let [log @log-atom
                     app-db-log (:app-db log)]
                 (reset! log-atom (assoc log :app-db (conj app-db-log new-state)))))))

(defn start!
  ([controller] (start! controller {} {}))
  ([controller initial-state] (start! controller initial-state {}))
  ([controller initial-state context]
   (let [app-db-atom (atom initial-state)
         route-params (get-in initial-state [:route :data])
         params (controller/params controller route-params)
         running-atom? (atom true)
         log-atom (atom {:params params
                         :app-db [@app-db-atom]})]

     (when params
       (let [in-chan (chan)
             out-chan (chan)
             in-mult (mult in-chan)
             in-tap (tap in-mult (chan))
             inited (assoc controller
                           :in-chan in-chan
                           :out-chan out-chan
                           :params params
                           :route-params route-params
                           :name :keechma/test-controller
                           :reporter (fn [& args])
                           :running (fn [& args] @running-atom?)
                           :redirect-fn (make-redirect log-atom)
                           :context context
                           :keechma/test-state {:running? running-atom?
                                                :in-chan in-chan
                                                :out-chan out-chan
                                                :log log-atom
                                                :app-db app-db-atom})]
         
         (log-chan log-atom (tap in-mult (chan)) :in-chan)
         (log-chan log-atom out-chan :out-chan)
         (log-app-db-changes log-atom app-db-atom)

         (reset! app-db-atom (controller/start inited params @app-db-atom))
         (controller/handler inited app-db-atom in-tap out-chan)
         inited)))))

(defn send-command!
  ([ctrl command])
  ([ctrl command payload]
   (controller/execute ctrl command payload)))

(defn stop! [controller]
  (when controller
    (let [app-db-atom (get-in controller [:keechma/test-state :app-db])
          in-chan (get-in controller [:keechma/test-state :in-chan])
          out-chan (get-in controller [:keechma/test-state :out-chan])]
      (reset! app-db-atom (controller/stop controller (:params controller) @app-db-atom))
      (reset! (get-in controller [:keechma/test-state :running?]) false)
      (close! in-chan)
      (close! out-chan))))

(defn teardown! [controller]
  (remove-watch (get-in controller [:keechma/test-state :app-db]) :keechma/test-watcher))

(defn log [ctrl]
  @(get-in ctrl [:keechma/test-state :log]))

(defn app-db [ctrl]
  @(get-in ctrl [:keechma/test-state :app-db]))
