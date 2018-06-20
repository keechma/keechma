(ns keechma.controller
  "Controllers in Keechma are the place where you put the code
  that has side-effects. They are managed by the [[keechma.controller-manager]]
  which will start them or stop them based on the current route.

  Each controller implements the `params` function. `params` function returns
  a subset of the route params that are the controller is interested in.

  For instance let's say that you have a `UserController` which should be
  running only when the user is on the route `/users`:

  ```clojure
  ;; let's say that your routes are defined like this:
  
  (def routes [\":page\"]) ;; Routes are managed by the app-state library.

  ;; When user goes to the url `/users` the params function would receive
  ;; something that looks like this:

  {:data {:page \"users\"}}

  ;; `params` function returns `true` only when user is on the `:page` \"users\"
  (defrecord UserController [])

  (defmethod controller/params UserController [_ route-params]
    (when (= \"users\" (get-in route-params [:data :page]))
     true))
  ```

  When `params` function returns a non `nil` value the controller will be started:

  1. Controller's `start` function will be synchronously called with the current
  application state. This function returns a new version of the state if needed.
  (if the `start` function is not doing any changes to the app-state it must return
  the received version)
  2. Controller's `handler` function will be called - this function will receive
  application state atom, channel through which the controller receives the commands
  (`in-chan`) and the channel through which the controller can send commends to
  other controllers (`out-chan`).

  When `params` function returns a `nil` value that instance of the controller will
  be stopped:

  1. Controller's `stop` function will be synchronously called with the current
  application state. This function returns a new version of the state if needed - 
  use this function to clean up any data loaded by the controller (if the `stop` 
  function is not doing any changes to the app-state it must return the received
  version).
  2. Controller's `in-chan` (through which it can receive commands) will be closed.

  Controller's `start` and `stop` functions can asynchronuously send commends to the
  controller. Calling `(execute controller-instance :command)` will put that command
  on the controller's `in-chan`. Controller can react to these commands from the 
  `handler` function."

  (:require [cljs.core.async :refer [put! <!]]
            [keechma.reporter :as reporter])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def not-implemented ::not-implemented)

(defn dispatcher
  "Helper function to dispatch commands from the `handler` function.

  Most of the time, handler function will just dispatch the commands
  to other functions. This functions provides a shortcut for that case.

  ```clojure
  (defrecord Controller [])
    
  (defmethod handler Controller [_ app-db-atom in-chan _]
    (dispatcher app-db-atom in-chan {:command-name some-fn}))
  ```"
  [app-db-atom in-chan actions]
  (go (loop []
        (let [[command args] (<! in-chan)
              action-fn (get actions command)]
          (when action-fn (action-fn app-db-atom args))
          (when command (recur))))))

(defrecord SerializedController [params])

(defn record-type [record & args] (type record))

(defmulti params
  "Receives the `route-params` and returns either the `params` for the controller or `nil`"
  record-type)
(defmulti start
  "Called when the controller is started. Receives the controller `params` (returned by the
  `params` function) and the application state. It must return the application state."
  record-type)
(defmulti wake
   "Called when the controller is started from the saved state stored on the server. It will be
   called instead of the `start` function if the `ssr-handler` function is implemented. This
   allows you to manually revive the serialized data if needed. Usually this function is not
   needed, but if you for instance start the inner application from the controller, you can
   use this function to wake the inner app."
  record-type)
(defmulti stop
  "Called when the controller is stopped. Receives the controller `params` (returned by the
  `params` function) and the application state. It must return the application state."
  record-type)
(defmulti handler
  "Called after the `start` function. You can listen to the commands on the `in-chan` 
  inside the `go` block. This is the function in which you implement anything that reacts
  to the user commands (coming from the UI)."
  record-type)
(defmulti ssr-handler
  "Called in after the `start` (instead of the `handler` function) function in the server
  side context. This function should call the `done` callback when it has completed the 
  server side data loading. Returning `::not-implemented` which is a default behavior will
  mark the controller as non server side."
  record-type)
(defmulti context
  "Return the context passed to the application."
  record-type)
(defmulti report record-type)
(defmulti execute
  "Puts the command on the controller's `in-chan` which is passed as an argument to the 
  `handler` function. Can be called from the `start` and `stop` functions."
  record-type)
(defmulti send-command
  "Sends a command to another controller"
  record-type)
(defmulti broadcast
  "Sends a command to all other running controllers"
  record-type)
(defmulti is-running?
  "Returns `true` if this controller is still running. You can use this if you have some
  kind of async action, and you want to make sure that the controller is still running 
  when you receive the results."
  record-type)
(defmulti redirect
  "Redirects the page to the URL based on the params."
  record-type)
(defmulti reroute
  "Restarts the route process. This is useful in combination with the `:route-processor`.
  In some cases route processor might use info from the app-db to determine the current route,
  which means that the value from the route processor might be different without the actual
  route change happening."
  record-type)
(defmulti router
  "Returns the app's router"
  record-type)


(defmethod params :default [controller route-params] route-params)
(defmethod start :default [controller params app-db] app-db)
(defmethod wake :default [controller params app-db] app-db)
(defmethod stop :default [controller params app-db] app-db)
(defmethod handler :default [controller app-db-atom in-chan out-chan])
(defmethod ssr-handler :default [controller app-db-atom done in-chan out-chan] not-implemented)
(defmethod context :default
  ([controller] (:context controller))
  ([controller key]
   (let [key-vec (if (vector? key) key [key])]
       (get-in controller (into [:context] key-vec)))))
(defmethod report :default
  ([controller direction name payload] (report controller direction name payload (reporter/cmd-info) :info))
  ([controller direction name payload cmd-info] (report controller direction name payload cmd-info :info))
  ([controller direction name payload cmd-info severity]
     (let [reporter (or (:reporter controller) (fn [_ _ _ _ _ _ _ _]))
           topic (:name controller)]
       (reporter :controller direction topic name payload cmd-info severity))))
(defmethod execute :default
  ([controller command-name]
   (execute controller command-name nil))
  ([controller command-name args]
   (let [cmd-info (reporter/cmd-info)]
     (report controller :in command-name args cmd-info)
     (put! (:in-chan controller) [command-name args cmd-info]))))
(defmethod send-command :default
  ([controller command-name]
   (send-command controller command-name nil nil))
  ([controller command-name args]
   (send-command controller command-name args nil))
  ([controller command-name args origin]
   (let [out-chan (:out-chan controller)
         cmd-info (reporter/cmd-info)]
     (report controller :out command-name args cmd-info)
     (put! out-chan [command-name args cmd-info])
     controller)))
(defmethod broadcast :default
  ([controller command-name]
   (broadcast controller command-name nil nil))
  ([controller command-name args]
   (broadcast controller command-name args nil))
  ([controller command-name args origin]
   (let [active-topics ((:active-topics controller))
         current-topic (:name controller)]
     (doseq [t active-topics]
       (when (not= t current-topic)
         (send-command controller [t command-name] args origin))))))
(defmethod is-running? :default [controller]
  (= controller ((:running controller))))
(defmethod redirect :default [controller params & args]
  (let [action (boolean (first args))]
    ((:redirect-fn controller) params action)))
(defmethod reroute :default [controller]
  (let [out-chan (:out-chan controller)
        cmd-info (reporter/cmd-info)
        cmd-name :keechma.controller-manager/reroute]
     (report controller :out cmd-name nil cmd-info)
     (put! out-chan [cmd-name nil cmd-info])
     controller))
(defmethod router :default [controller]
  (:router controller))
