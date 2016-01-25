(ns keechma.edb.util)

(defn passthrough-item [item] item)

(defn add-empty-layout [db entity-kw]
  (if (nil? (entity-kw db))
    (assoc db entity-kw {:store {}
                         :c-one {}
                         :c-many {}})
    db))

(defn ensure-layout [dbal-fn]
  (fn [schema db entity-kw & args]
    (let [db-with-layout (add-empty-layout db entity-kw)]
      (apply dbal-fn (concat [schema db-with-layout entity-kw] args)))))


(defn call-middleware [get-or-set schema entity-kw item]
  (let [middlewares (or (get-in schema [entity-kw :middleware get-or-set])
                        [passthrough-item])
        pipeline (apply comp middlewares)]
    (pipeline item)))


(def call-middleware-set (partial call-middleware :set))
(def call-middleware-get (partial call-middleware :get))

(defn get-id-fn [schema entity-kw]
  (or (get-in schema [entity-kw :id]) :id))

(defn get-item-id [schema entity-kw item]
  (let [id-fn (get-id-fn schema entity-kw)]
    (id-fn item)))

(defn get-meta-id [entity-kw id]
  [entity-kw id])
