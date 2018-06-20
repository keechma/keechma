(ns keechma.app-state.core)

(defprotocol IRouter
  (redirect! [this params] [this params replace?])
  (start! [this])
  (stop! [this])
  (url [this params])
  (wrap-component [this])
  (linkable? [this]))

(extend-type default
  IRouter
  (redirect!
    ([this params] this)
    ([this params replace?] this))
  (start! [this] this)
  (stop! [this] this)
  (url [this params] params)
  (wrap-component [this] nil)
  (linkable? [this] false))

(defn reg-on-fn [type config on-fn]
  (let [on-fns (or (get-in config [:on type]) [])]
    (assoc-in config [:on type] (conj on-fns on-fn))))

(def reg-on-start (partial reg-on-fn :start))
(def reg-on-stop (partial reg-on-fn :stop))
