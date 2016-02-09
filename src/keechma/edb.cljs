(ns keechma.edb
  (:require [keechma.edb.util :as util]
            [keechma.edb.relations :as relations]
            [keechma.util :refer [update-values]]
            [clojure.set :as set]))

(declare get-item-by-id)
(declare insert-named-item)
(declare insert-collection)
(declare insert-related)
(declare get-named-item)
(declare get-collection)
(declare insert-meta)
(declare remove-meta)
(declare get-collection-meta)
(declare remove-collection-or-named-item)

(def ^:private meta-store :__meta-store__)

(defn insert-item
  "Inserts an item into the EntityDB collection.

  ```clojure
  (def schema {:foos {:id :id}})
  (def entity-db-v1 {})

  (def item {:id 1 :name \"Foo\"})
  (def item-meta {:is-loading false})

  (def entity-db-v2 (insert-item schema entity-db-v1 :foos item item-meta))
  ;; Returns the new version of the entity-db with the item inserted
  ;; inserted into the store
  ```
  "
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

(defn insert-item-when-not-nil 
  "Inserts an entity into the EntityDB if the entity is not nil."
  [schema db entity-kw item]
  (if-not (nil? item)
    (insert-item schema db entity-kw item)
    db))

(defn insert-named-item
  "Inserts an item into the EntityDB, and references it from the named item slot.

  Item will be stored in the internal store, and named item slot will contain only 
  the identity of the item.
   
  ```clojure
  (def entity-db-v1 {})
  (def schema {:foos {:id :id}})

  (def entity-db-v2 (insert-named-item schema entity-db-v1 :foos :current {:id 1 :name \"foo\"}))
  ;; Returns the new version of the entity-db with the entity saved in the store and
  ;; referenced from the `:current` named item slot.

  (get-named-item schema entity-db-v2 :foos :current)
  ;; Returns the entity referenced from the `:current` named slot.

  ```
  " 
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

(defn insert-collection
  "Inserts a collection of items into the EntityDB. Each item will be
  stored in the internal store map, and the collection will be stored as a vector
  of entity identities.

  ```clojure
  (def entity-db-v1 {})
  (def schema {:foos {:id :id}})

  (def collection [{:id 1 :name \"foo\"} {:id 2 :name \"bar\"}])

  (def entity-db-v2 (insert-collection schema entity-db-v1 :foos :list collection))
  ;; Returns the new version of entity db. Each item will be stored
  ;; in the internal store map and collection will contain only the
  ;; item ids.

  (get-collection schema entity-db-v2 :foos :list)
  ;; Returns a collection of items named `:list`. Although internally collections
  ;; stores only a vector of ids, this function will return a vector of entities.
  ;;
  ;; [{:id 1 :name \"foo\"} {:id 2 :name \"bar\"}]
  
  ```
  "
  ([schema db entity-kw collection-key data]
   (insert-collection schema db entity-kw collection-key data nil))
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

(defn append-collection
  "Appends items to an existing collection.

  ```clojure
  (def entity-db-v1 {})
  (def schema {:foos {:id :id}})

  (def collection [{:id 1 :name \"foo\"} {:id 2 :name \"bar\"}])

  (def entity-db-v2 (insert-collection schema entity-db-v1 :foos :list collection))
  ;; Returns the new version of entity db. Each item will be stored
  ;; in the internal store map and collection will contain only the
  ;; item ids.

  (get-collection schema entity-db-v2 :foos :list)
  ;; Returns a collection of items named `:list`. Although internally collections
  ;; stores only a vector of ids, this function will return a vector of entities.
  ;;
  ;; [{:id 1 :name \"foo\"} {:id 2 :name \"bar\"}]

  
  (def entity-db-v3 (append-collection schema entity-db-v2 :foos :list [{:id 3 :name \"baz}]))
  
  (get-collection schema entity-db-v3 :foos :list)
  ;; Returns [{:id 1 :name \"foo\"} {:id 2 :name \"bar} {:id 3 :name \"baz\"}]
  
  ```
  "
  ([schema db entity-kw collection-key data]
   (let [current-meta (get-collection-meta schema db entity-kw collection-key)]
     (append-collection schema db entity-kw collection-key data current-meta)))
  ([schema db entity-kw collection-key data meta]
   (let [c-path [entity-kw :c-many collection-key]
         current-ids (get-in db c-path)
         db-with-items (insert-collection schema db entity-kw collection-key data meta)
         new-ids (get-in db-with-items c-path)]
     (assoc-in db-with-items c-path (flatten [current-ids new-ids])))))

(defn ^:private insert-related [schema db relations entity-kw id item]
  (reduce-kv (fn [db relation-kw [relation-type related-entity-kw]]
               (let [collection-key (relations/get-related-collection-key entity-kw id relation-kw)
                     relation-data (relation-kw item)
                     remove-collection-type-map {:one :c-one :many :c-many}
                     insert-collection-fn (if (= relation-type :one)
                                            insert-named-item
                                            insert-collection)]
                 (if (and (contains? item relation-kw) (nil? relation-data))
                   (remove-collection-or-named-item db related-entity-kw (relation-type remove-collection-type-map) collection-key)
                   (insert-collection-fn schema db related-entity-kw collection-key relation-data))))
             db relations))

(defn insert-meta
  "Inserts meta data for an entity or collection into the store."
  [db entity-kw meta-key meta]
  (let [schema {meta-store {:id (partial util/get-meta-id entity-kw meta-key)}}]
    (if (nil? meta)
      (remove-meta db entity-kw meta-key)
      (insert-item schema (util/add-empty-layout db meta-store) meta-store meta))))

(defn ^:private remove-item-id-from-named-items [collections id]
  (into {} (filter (fn [[key val]]
                     (not= val id)) collections)))

(defn ^:private remove-item-id-from-collections [collections id]
  (update-values collections (fn [val]
                               (filterv (partial not= id) val))))

(defn remove-item 
  "Removes item from the store. It will also remove it from any named-item slots or collections.

  ```clojure
  (def entity-db-v1 {})
  (def schema {:foos {:id :id}})

  (def foo-entity {:id 1 :name \"Bar\"})
  
  ;; insert `foo-entity` in the `:current` named item slot
  (def entity-db-v2 (insert-named-item schema entity-db-v1 :foos :current foo-entity))

  ;; insert `foo-entity` as a part of the `:list` collection
  (def entity-db-v3 (insert-collection schema entity-db-v2 :foos :list [foo-entity]))

  ;; get `foo-entity` from the entity-db
  (get-item-by-id schema entity-db-v3 :foos 1)
  ;; returns `foo-entity`

  (def entity-db-v4 (remove-item schema entity-db :foos 1))

  (get-named-item schema entity-db-v4 :foos :current)
  ;; returns `nil`

  (get-collection schema entity-db-v4 :foos :list)
  ;; returns []
  ```
  "
  [schema db entity-kw id]
  (let [c-one-without-item-id (remove-item-id-from-named-items (get-in db [entity-kw :c-one]) id)
        c-many-without-item-id (remove-item-id-from-collections (get-in db [entity-kw :c-many]) id)
        store-without-item (dissoc (get-in db [entity-kw :store]) id)
        db (-> db
               (remove-meta entity-kw id)
               (assoc-in [entity-kw :store] store-without-item)
               (assoc-in [entity-kw :c-one] c-one-without-item-id)
               (assoc-in [entity-kw :c-many] c-many-without-item-id))
        relations (relations/get-relations schema entity-kw)]
    (reduce-kv (partial relations/remove-related-collections entity-kw id) db relations)))

(defn ^:private remove-collection-or-named-item [db entity-kw collection-type collection-key]
  (let [collections-without (dissoc (get-in db [entity-kw collection-type]) collection-key)]
    (-> db
        (remove-meta entity-kw collection-key)
        (assoc-in [entity-kw collection-type] collections-without))))

(defn remove-named-item
  "Removes the named-item slot. Entity will still be stored in the internal store, but
  won't be available through the named-item slot.

  ```clojure
  (def entity-db-v1 {})
  (def schema {:foos {:id :id}})

  (def foo-entity {:id 1 :name \"bar\"})

  (def entity-db-v2 (insert-named-item schema entity-db-v1 :foos :current foo-entity))
  
  (get-named-item schema entity-db-v1 :foos :current)
  ;; Returns `{:id 1 :name \"bar\"}`

  (def entity-db-v3 (remove-named-item schema entity-db-v2 :foos :current))

  (get-named-item schema entity-db-v2 :foos :current)
  ;; Returns `nil`

  (get-item-by-id schema entity-db-v2 :foos 1)
  ;; Returns `{:id 1 :name \"bar\"}`
  ```
  "
  [db entity-kw collection-key]
  (remove-collection-or-named-item db entity-kw :c-one collection-key))

(defn remove-collection
  "Removes the collection. Entities referenced from the collection will still be stored in
  the internal store, but won't be available through the collection API.

  ```clojure
  (def entity-db-v1 {})
  (def schema {:foos {:id :id}})

  (def foo-entity {:id 1 :name \"bar\"})

  (def entity-db-v2 (insert-collection schema entity-db-v1 :foos :list [foo-entity]))
  
  (get-collection schema entity-db-v2 :foos :list)
  ;; Returns `[{:id 1 :name \"bar\"}]`

  (def entity-db-v3 (remove-collection schema entity-db-v2 :foos :list))

  (get-collection schema entity-db-v2 :foos :list)
  ;; Returns `nil`

  (get-item-by-id schema entity-db-v2 :foos 1)
  ;; Returns `{:id 1 :name \"bar\"}`
  ```
  "
  [db entity-kw collection-key]
  (remove-collection-or-named-item db entity-kw :c-many collection-key))

(defn remove-meta
  "Removes any meta data stored on the entity or collection"
  [db entity-kw id]
  (let [meta-key (util/get-meta-id entity-kw id)
        current-meta (get-in db [meta-store :store meta-key])]
    (if (nil? current-meta)
      db
      (let [store (get-in db [meta-store :store])
            store-without-item (dissoc store meta-key)]
        (assoc-in db [meta-store :store] (or store-without-item {}))))))

(defn get-item-meta
  "Gets meta data for an entity."
  [schema db entity-kw id]
  (if (= entity-kw meta-store)
    nil
    (get-item-by-id schema db meta-store (util/get-meta-id entity-kw id))))

(defn get-named-item-meta
  "Returns the meta data for an entity referenced in the named item slot."
  [schema db entity-kw collection-key]
  (let [item (get-named-item schema db entity-kw collection-key false)
        meta-key (if (nil? item)
                   collection-key
                   (util/get-item-id schema entity-kw item))]
    (get-item-meta schema db entity-kw meta-key)))

(def get-collection-meta
  "Returns the meta data for a collection."
  get-item-meta)

(defn ^:private get-related-items-fn [schema db entity-kw id]
  (fn [item relation-kw [relation-type related-entity-kw]]
    (let [collection-key (relations/get-related-collection-key entity-kw id relation-kw)
          get-collection-fn (if (= relation-type :one)
                              get-named-item
                              get-collection)
          data-fn (partial get-collection-fn schema db related-entity-kw collection-key) ]
      (assoc item relation-kw data-fn))))

(defn get-item-by-id
  "Gets an entity from the store by the id"
  [schema db entity-kw id]
  (let [relations (relations/get-relations schema entity-kw)
        item (get-in db [entity-kw :store id])]
    (-> item
        (with-meta (get-item-meta schema db entity-kw id))
        ((partial reduce-kv (get-related-items-fn schema db entity-kw id)) relations))))

(defn get-collection
  "Gets collection by it's key. Internally collections store only entity ids, but
  this function will return a collection of entities based on the ids stored in the collection

  
  ```clojure
  (def entity-db-v1 {})
  (def schema {:foos {:id :id}})

  (def collection [{:id 1 :name \"foo\"} {:id 2 :name \"bar\"}])

  (def entity-db-v2 (insert-collection schema entity-db-v1 :foos :list collection))
  ;; Returns the new version of entity db. Each item will be stored
  ;; in the internal store map and collection will contain only the
  ;; item ids.

  (get-collection schema entity-db-v2 :foos :list)
  ;; Returns a collection of items named `:list`. Although internally collections
  ;; stores only a vector of ids, this function will return a vector of entities.
  ;;
  ;; [{:id 1 :name \"foo\"} {:id 2 :name \"bar\"}]
  ```
  "
  [schema db entity-kw collection-key]
  (let [ids (get-in db [entity-kw :c-many collection-key])]
    (with-meta
      (into [] (map (partial get-item-by-id schema db entity-kw) ids))
      (get-collection-meta schema db entity-kw collection-key))))

(defn get-named-item
  "Gets an entity referenced from the named item slot. Internally named slots store
  only entity ids but this function will return an entity based on the id."
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

(defn ^:private vacuum-entity-db [db entity-kw]
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

(defn vacuum
  "Removes orphaned entities from the EntityDB. Any entity that is not referenced
  in a collection or in a named item slot will be removed from the EntityDB"
  [db]
  (let [entity-kws (keys db)
        entity-kws-without-meta (filterv (fn [k] (not (= k meta-store))) entity-kws)]
    (reduce vacuum-entity-db db entity-kws-without-meta)))

(defn make-dbal
  "Returns a map with all public functions. These functions will have `schema`
  partially applied to them so you don't have to pass the schema around."
  [schema]
  {:insert-item (partial (util/ensure-layout insert-item) schema)
   :insert-named-item (partial (util/ensure-layout insert-named-item) schema)
   :insert-collection (partial (util/ensure-layout insert-collection) schema)
   :append-collection (partial (util/ensure-layout append-collection) schema)
   :insert-meta insert-meta
   :remove-item (partial (util/ensure-layout remove-item) schema)
   :remove-named-item remove-named-item 
   :remove-collection remove-collection 
   :remove-meta remove-meta
   :get-item-by-id (partial get-item-by-id schema)
   :get-named-item (partial get-named-item schema)
   :get-collection (partial (util/ensure-layout get-collection) schema)
   :get-item-meta (partial get-item-meta schema)
   :get-named-item-meta (partial get-named-item-meta schema)
   :get-collection-meta (partial get-collection-meta schema)
   :vacuum vacuum})

