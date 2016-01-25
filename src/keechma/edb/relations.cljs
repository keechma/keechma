(ns keechma.edb.relations
  (:require [clojure.string :refer [join]]
            [keechma.edb.util :as util]))


(defn get-relations [schema entity-kw]
  (or (get-in schema [entity-kw :relations]) {}))

(defn get-related-collection-key [entity-kw id relation-kw]
  [entity-kw id relation-kw])

(defn remove-related-from-item [related-entity-kws item]
  (reduce (fn [item related-entity-kw]
            (dissoc item related-entity-kw)) item related-entity-kws))


(defn remove-related-collections [entity-kw id db relation-kw [relation-type related-entity-kw]]
  (let [collection-key (get-related-collection-key entity-kw id relation-kw)
        collection-type (if (= relation-type :one)
                          :c-one
                          :c-many)
        collections-without-related (dissoc (get-in db [related-entity-kw collection-type]) collection-key)]
    (assoc-in db [related-entity-kw collection-type] collections-without-related)))
