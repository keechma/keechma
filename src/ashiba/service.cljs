(ns ashiba.service)

(defprotocol IService
  (params [this route])
  (enter [this params db])
  (leave [this params db])
  (handler [this read-db command-chan]))

(extend-type js/Object
  IService
  (params [_ route] route)
  (enter [_ params db] db)
  (leave [_ params db] db)
  (handler [_ _ _]))

(defn make-default-defs [service-name]
  {:param (fn [route] route)
   :enter (fn [db params]
            (do
              (.log js/console (str "Entering service:" service-name))
              db))
   :leave (fn [db params]
            (do
              (.log js/console (str "Exiting service:" service-name))
              db))
   :handlers {:after-enter 
              (fn [is-running? params db-snapshot out-chan]
                (.log js/console (str "After entering service: " service-name)))
              :before-leave 
              (fn [is-running? params db-snapshot out-chan]
                (.log js/console (str "Before leaving service: " service-name)))}})

(defn make-service [service-name defs]
  (let [default-defs (make-default-defs service-name)]
    (merge default-defs defs {:service-name service-name})))
