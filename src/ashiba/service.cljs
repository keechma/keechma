(ns ashiba.service)

(defprotocol IService
  (params [this route])
  (start [this params db])
  (stop [this params db])
  (handler [this in-chan out-chan]))

(extend-type js/Object
  IService
  (params [_ route] route)
  (start [_ params db] db)
  (stop [_ params db] db)
  (handler [_ _ _]))

