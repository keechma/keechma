(ns keechma.app-state.core)

(defprotocol IRouter
  (redirect! [this params])
  (start! [this])
  (stop! [this])
  (url [this params])
  (wrap-component [this]))

(extend-type default
  IRouter
  (redirect! [this params] this)
  (start! [this] this)
  (stop! [this] this)
  (url [this params] params)
  (wrap-component [this] nil))

(defn reg-on-fn [type config on-fn]
  (let [on-fns (or (get-in config [:on type]) [])]
    (assoc-in config [:on type] (conj on-fns on-fn))))

(def reg-on-start (partial reg-on-fn :start))
(def reg-on-stop (partial reg-on-fn :stop))
