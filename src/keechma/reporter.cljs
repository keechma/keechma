(ns keechma.reporter)

(defn now []
  (.getTime (js/Date.)))

(defn cmd-info
  ([] (cmd-info {}))
  ([info]
   (merge
    {:id (gensym :cmd)
     :created-at (now)
     :duration 0
     :status :done}
    info)))

(defn update-duration [info]
  (let [now-time (now)]
    (assoc info :duration (- now-time (:created-at info)))))

(defn with-origin
  ([origin] (cmd-info {:origin-id (:id origin)}))
  ([origin payload] (cmd-info (assoc payload :origin-id (:id origin)))))

(defn with-parent
  ([parent] (cmd-info {:parent-id (:id parent)}))
  ([parent payload] (cmd-info (assoc payload :parent-id (:id parent)))))
