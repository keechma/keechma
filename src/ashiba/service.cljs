(ns ashiba.service
  (:require [cljs.core.async :refer [put!]]))

(defprotocol IService
  (params [this route])
  (start [this params db])
  (stop [this params db])
  (handler [this in-chan out-chan])
  (send-command [this command-name args])
  (send-update [this updater-fn] [this updater-fn immediate?])
  (is-running? [this]))

(extend-type js/Object
  IService
  (params [_ route] route)
  (start [_ params db] db)
  (stop [_ params db] db)
  (handler [_ _ _])
  (send-command [this command-name args]
    (let [out-chan (:out-chan this)]
      (put! out-chan [command-name args])
      this))
  (send-update
    ([this updater-fn]
     (send-update this updater-fn false))
    ([this updater-fn immediate?]
     (let [command-name (if immediate? :immediate-update :schedule-update)]
       (send-command this command-name updater-fn))))
  (is-running? [this]
    (let [currently-running-service (:currently-running-service this)]
      (identical? this (currently-running-service)))))

