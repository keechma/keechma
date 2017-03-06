(ns keechma.controller
  (:require [cljs.core.async :refer [put!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def not-implemented ::not-implemented)

(defprotocol IController
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
  (defrecord UserController []
    IController
    (params [_ route-params]
      (when (= \"users\" (get-in route-params [:data :page]))
       true)))
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
  `handler` function. 

  "
  (params [this route-params]
    "Receives the `route-params` and returns either the `params` for the controller or `nil`")
  (report [this direction name payload] [this direction name payload severity])
  (context [this] [this key]
    "Return the context passed to application.")
  (start [this params app-db]
    "Called when the controller is started. Receives the controller `params` (returned by the
    `params` function) and the application state. It must return the application state.")
  (wake [this params app-db]
    "Called when the controller is started from the saved state stored on the server. It will be
     called instead of the `start` function if the `ssr-handler` function is implemented. This
     allows you to manually revive the serialized data if needed. Usually this function is not
     needed, but if you for instance start the inner application from the controller, you can
     use this function to wake the inner app.")
  (stop [this params app-db]
    "Called when the controller is stopped. Receives the controller `params` (returned by the
    `params` function) and the application state. It must return the application state.")
  (execute [this command-name] [this command-name args]
    "Puts the command on the controller's `in-chan` which is passed as an argument to the 
    `handler` function. Can be called from the `start` and `stop` functions.")
  (handler [this app-db-atom in-chan out-chan]
    "Called after the `start` function. You can listen to the commands on the `in-chan` 
    inside the `go` block. This is the function in which you implement anything that reacts
    to the user commands (coming from the UI).")
  (ssr-handler [this app-db-atom done in-chan out-chan]
    "Called in after the `start` (instead of the `handler` function) function in the server
     side context. This function should call the `done` callback when it has completed the 
     server side data loading. Returning `::not-implemented` which is a default behavior will
     mark the controller as non server side.")
  (send-command [this command-name] [this command-name args]
    "Sends a command to another controller")
  (is-running? [this]
    "Returns `true` if this controller is still running. You can use this if you have some
    kind of async action, and you want to make sure that the controller is still running 
    when you receive the results.")
  (redirect [this params]
    "Redirects the page to the URL based on the params."))

(extend-type default
  IController
  (params [_ route-params] route-params)
  (start [_ params app-db] app-db)
  (wake [_ params app-db] app-db)
  (stop [_ params app-db] app-db)
  (handler [_ _ _ _])
  (ssr-handler [_ _ _ _ _]
    not-implemented)
  (context 
    ([this] (:context this))
    ([this key]
     (let [key-vec (if (vector? key) key [key])]
       (get-in this (into [:context] key-vec)))))
  (report
    ([this direction name payload] (report this direction name payload :info))
    ([this direction name payload severity]
     (let [reporter (or (:reporter this) (fn [_ _ _ _ _ _ _]))
           topic (:name this)]
       (reporter :controller direction topic name payload severity))))
  (execute
    ([this command-name]
     (execute this command-name nil))
    ([this command-name args]
     (report this :in command-name args)
     (put! (:in-chan this) [command-name args])))
  (send-command
    ([this command-name]
     (send-command this command-name nil))
    ([this command-name args]
     (let [out-chan (:out-chan this)]
       (report this :out command-name args)
       (put! out-chan [command-name args])
       this)))
  (is-running? [this]
    (= this ((:running this))))
  (redirect [this params]
    ((:redirect-fn this) params)))

(defn dispatcher
  "Helper function to dispatch commands from the `handler` function.

  Most of the time, handler function will just dispatch the commands
  to other functions. This functions provides a shortcut for that case.

  ```clojure
  (defrecord Controller []
    IController
    (handler [_ app-db-atom in-chan _]
      (dispatcher app-db-atom in-chan {:command-name some-fn})))
  ```"
  [app-db-atom in-chan actions]
  (go (loop []
        (let [[command args] (<! in-chan)
              action-fn (get actions command)]
          (when action-fn (action-fn app-db-atom args))
          (when command (recur))))))

(defrecord SerializedController [params])
