(ns keechma.edb
  (:require [keechma.edb.util :as util]
            [keechma.edb.relations :as relations]
            [keechma.util :refer [update-values]]
            [clojure.set :as set]))

(declare get-item-by-id)
(declare insert-named-item)
(declare insert-collection-many)
(declare insert-related)
(declare get-named-item)
(declare get-collection-many)
(declare insert-meta)
(declare remove-meta)
(declare get-collection-many-meta)
(declare remove-collection)

(def meta-store :__meta-store__)

(defn insert-item
  ([schema db entity-kw item] (insert-item schema db entity-kw item nil))
  ([schema db entity-kw item meta]
   (let [id (util/get-item-id schema entity-kw item) 
         relations (relations/get-relations schema entity-kw)
         db-with-inserted-relations (insert-related schema db relations entity-kw id item)
         processed-item (relations/remove-related-from-item
                         (keys relations)
                         (util/call-middleware-set schema entity-kw item))
         merged-item (merge (or (get-in db [entity-kw :store id]) {}) processed-item)]
     (-> db-with-inserted-relations 
         (insert-meta entity-kw id meta)
         (assoc-in [entity-kw :store id] merged-item)))))

(defn insert-item-when-not-nil [schema db entity-kw item]
  (if-not (nil? item)
    (insert-item schema db entity-kw item)
    db))

(defn insert-named-item
  ([schema db entity-kw collection-key item]
   (insert-named-item schema db entity-kw collection-key item nil))
  ([schema db entity-kw collection-key item meta]
   (if (and (nil? item) (nil? meta))
     db
     (let [id (util/get-item-id schema entity-kw item)
           meta-key (if (nil? item) collection-key id)]
       (-> db
           (remove-meta entity-kw collection-key)
           (assoc-in [entity-kw :c-one collection-key] id) 
           ((partial insert-item-when-not-nil schema) entity-kw item)
           (insert-meta entity-kw meta-key meta))))))

(defn insert-collection-many
  ([schema db entity-kw collection-key data]
   (insert-collection-many schema db entity-kw collection-key data nil))
  ([schema db entity-kw collection-key data meta]
   (if (and (empty? data) (nil? meta))
     db
     (let [id-fn (util/get-id-fn schema entity-kw)
           ids (into [] (map id-fn data))]
       (-> db
           (assoc-in [entity-kw :c-many collection-key] ids)
           (insert-meta entity-kw collection-key meta)
           ((partial reduce (fn [db item]
                              (insert-item schema db entity-kw item))) data))))))

(defn append-collection-many
  ([schema db entity-kw collection-key data]
   (let [current-meta (get-collection-many-meta schema db entity-kw collection-key)]
     (append-collection-many schema db entity-kw collection-key data current-meta)))
  ([schema db entity-kw collection-key data meta]
   (let [c-path [entity-kw :c-many collection-key]
         current-ids (get-in db c-path)
         db-with-items (insert-collection-many schema db entity-kw collection-key data meta)
         new-ids (get-in db-with-items c-path)]
     (assoc-in db-with-items c-path (flatten [current-ids new-ids])))))

(defn insert-related [schema db relations entity-kw id item]
  (reduce-kv (fn [db relation-kw [relation-type related-entity-kw]]
               (let [collection-key (relations/get-related-collection-key entity-kw id relation-kw)
                     relation-data (relation-kw item)
                     remove-collection-type-map {:one :c-one :many :c-many}
                     insert-collection-fn (if (= relation-type :one)
                                            insert-named-item
                                            insert-collection-many)]
                 (if (and (contains? item relation-kw) (nil? relation-data))
                   (remove-collection db related-entity-kw (relation-type remove-collection-type-map) collection-key)
                   (insert-collection-fn schema db related-entity-kw collection-key relation-data))))
             db relations))

(defn insert-meta [db entity-kw meta-key meta]
  (let [schema {meta-store {:id (partial util/get-meta-id entity-kw meta-key)}}]
    (if (nil? meta)
      (remove-meta db entity-kw meta-key)
      (insert-item schema (util/add-empty-layout db meta-store) meta-store meta))))

(defn remove-item-id-from-collections-one [collections id]
  (into {} (filter (fn [[key val]]
                     (not= val id)) collections)))

(defn remove-item-id-from-collections-many [collections id]
  (update-values collections (fn [val]
                               (filterv (partial not= id) val))))

(defn remove-item [schema db entity-kw id]
  (let [c-one-without-item-id (remove-item-id-from-collections-one (get-in db [entity-kw :c-one]) id)
        c-many-without-item-id (remove-item-id-from-collections-many (get-in db [entity-kw :c-many]) id)
        store-without-item (dissoc (get-in db [entity-kw :store]) id)
        db (-> db
               (remove-meta entity-kw id)
               (assoc-in [entity-kw :store] store-without-item)
               (assoc-in [entity-kw :c-one] c-one-without-item-id)
               (assoc-in [entity-kw :c-many] c-many-without-item-id))
        relations (relations/get-relations schema entity-kw)]
    (reduce-kv (partial relations/remove-related-collections entity-kw id) db relations)))

(defn remove-collection [db entity-kw collection-type collection-key]
  (let [collections-without (dissoc (get-in db [entity-kw collection-type]) collection-key)]
    (-> db
        (remove-meta entity-kw collection-key)
        (assoc-in [entity-kw collection-type] collections-without))))

(defn remove-named-item [db entity-kw collection-key]
  (remove-collection db entity-kw :c-one collection-key))

(defn remove-collection-many [db entity-kw collection-key]
  (remove-collection db entity-kw :c-many collection-key))

(defn remove-meta [db entity-kw id]
  (let [meta-key (util/get-meta-id entity-kw id)
        current-meta (get-in db [meta-store :store meta-key])]
    (if (nil? current-meta)
      db
      (let [store (get-in db [meta-store :store])
            store-without-item (dissoc store meta-key)]
        (assoc-in db [meta-store :store] (or store-without-item {}))))))

(defn get-item-meta [schema db entity-kw id]
  (if (= entity-kw meta-store)
    nil
    (get-item-by-id schema db meta-store (util/get-meta-id entity-kw id))))

(defn get-named-item-meta [schema db entity-kw collection-key]
  (let [item (get-named-item schema db entity-kw collection-key false)
        meta-key (if (nil? item)
                   collection-key
                   (util/get-item-id schema entity-kw item))]
    (get-item-meta schema db entity-kw meta-key)))

(def get-collection-many-meta get-item-meta)

(defn get-related-items-fn [schema db entity-kw id]
  (fn [item relation-kw [relation-type related-entity-kw]]
    (let [collection-key (relations/get-related-collection-key entity-kw id relation-kw)
          get-collection-fn (if (= relation-type :one)
                              get-named-item
                              get-collection-many)
          data-fn (partial get-collection-fn schema db related-entity-kw collection-key) ]
      (assoc item relation-kw data-fn))))

(defn get-item-by-id [schema db entity-kw id]
  (let [relations (relations/get-relations schema entity-kw)
        item (get-in db [entity-kw :store id])]
    (-> item
        (with-meta (get-item-meta schema db entity-kw id))
        ((partial reduce-kv (get-related-items-fn schema db entity-kw id)) relations))))

(defn get-collection-many [schema db entity-kw collection-key]
  (let [ids (get-in db [entity-kw :c-many collection-key])]
    (with-meta
      (into [] (map (partial get-item-by-id schema db entity-kw) ids))
      (get-collection-many-meta schema db entity-kw collection-key))))

(defn get-named-item 
  ([schema db entity-kw collection-key]
   (get-named-item schema db entity-kw collection-key true))
  ([schema db entity-kw collection-key include-meta]
   (let [id (get-in db [entity-kw :c-one collection-key])
         item (get-item-by-id schema db entity-kw id)]
     (if include-meta
       (with-meta
         item
         (get-named-item-meta schema db entity-kw collection-key))
       item))))

(defn vacuum-entity-db [db entity-kw]
  (let [store (get-in db [entity-kw :store])
        ids (keys store)
        locked-one-ids (vals (get-in db [entity-kw :c-one]))
        locked-many-ids (vals (get-in db [entity-kw :c-many]))
        locked-ids (flatten [locked-one-ids locked-many-ids])
        to-remove-ids (set/difference (set ids) (set locked-ids))
        db-without-meta (reduce (fn [db id]
                                  (remove-meta db entity-kw id))
                                db
                                to-remove-ids)]
    (assoc-in db-without-meta
              [entity-kw :store]
              (select-keys store locked-ids))))

(defn vacuum [db]
  (let [entity-kws (keys db)
        entity-kws-without-meta (filterv (fn [k] (not (= k meta-store))) entity-kws)]
    (reduce vacuum-entity-db db entity-kws-without-meta)))

(defn make-dbal [schema]
  {:insert-item (partial (util/ensure-layout insert-item) schema)
   :insert-named-item (partial (util/ensure-layout insert-named-item) schema)
   :insert-collection-many (partial (util/ensure-layout insert-collection-many) schema)
   :append-collection-many (partial (util/ensure-layout append-collection-many) schema)
   :insert-meta insert-meta
   :remove-item (partial (util/ensure-layout remove-item) schema)
   :remove-named-item remove-named-item 
   :remove-collection-many remove-collection-many 
   :remove-meta remove-meta
   :get-item-by-id (partial get-item-by-id schema)
   :get-named-item (partial get-named-item schema)
   :get-collection-many (partial (util/ensure-layout get-collection-many) schema)
   :get-item-meta (partial get-item-meta schema)
   :get-named-item-meta (partial get-named-item-meta schema)
   :get-collection-many-meta (partial get-collection-many-meta schema)
   :vacuum vacuum})
