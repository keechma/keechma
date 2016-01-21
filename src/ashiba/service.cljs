(ns ashiba.service
  (:require [cljs.core.async :refer [put!]]))

(defprotocol IService
  (params [this route])
  (start [this params db])
  (stop [this params db])
  (execute [this command-name] [this command-name args])
  (handler [this app-db in-chan out-chan])
  (send-command [this command-name] [this command-name args])
  (is-running? [this]))

(extend-type default
  IService
  (params [_ route] route)
  (start [_ params db] db)
  (stop [_ params db] db)
  (handler [_ _ _ _])
  (execute
    ([this command-name]
     (execute this command-name nil))
    ([this command-name args]
     (put! (:in-chan this) [command-name args])))
  (send-command
    ([this command-name]
     (send-command this command-name nil))
    ([this command-name args]
     (let [out-chan (:out-chan this)]
       (put! out-chan [command-name args])
       this)))
  (is-running? [this]
    (let [currently-running-service (:currently-running-service this)]
      (identical? this (currently-running-service)))))
