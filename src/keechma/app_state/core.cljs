(ns keechma.app-state.core)

(defprotocol IRouter
  (redirect! [this params])
  (start! [this])
  (stop! [this])
  (url [this params]))

(extend-type default
  IRouter
  (redirect! [this params] this)
  (start! [this] this)
  (stop! [this] this)
  (url [this params] params))
