# EntityDB

EntityDB is a library that handles storage of any kind of entities in your application. Entity is anything that is "identifiable". Usually the `id` attribute is used, but you can use anything that makes sense for your application.

## Why do you need EntityDB?

EntityDB solves two problems for you:

1. It allows you to store all entities in the central location 
2. It ensures the data consistency
    - If you have the same entity rendered in multiple places (for instance in a master - detail layout) - changes to entity in one place will update the other
    - If you have an entity inside some collection, removing it from the EntityDB store will remove it from the collection too

## How it works?

EntityDB functions are implemented in a pure functional way, and they operate on the Clojure `map`. 

EntityDB supports two ways to store the items:

1. In collections
2. In named item slots

### Collections

Collections are just lists of items that have name. Whenever you store or retreive the collections you use the collection name.

### Named items

Similar to collections, but they reference just one item.

#### Why do you need to use named collections and named items?

Since ClojureScript data structures are immutable, holding a reference to the list of items (or item) will always give you the same value. By using the names, EntityDB can internally update named items and collections when the data changes and ensure the data consistency.

---

Before you can store the entities you need to define the schema. Each entity type needs it's own schema:

```clojure
(def schema {:users {:id :id}
             :news {:id :slug}})
```

With the schema defined, you can store the entities:

```clojure
(def store-v1 {})

(def user {:id 1 :username "retro"})

(def store-v2 (edb/insert-named-item schema store-v1 :users :current user))
;; store the user under the name `:current`

(edb/get-named-item schema store-v2 :users :current)
;; Returns {:id 1 :username "retro"}

(def user-collection [{:id 1 :username "retro" :likes "programming"}
                      {:id 2 :username "neektza" :likes "wrestling"}])

(def store-v3
  (edb/insert-collection schema store-v2 :users :list user-collection))
;; Store the user collection. User with the `:id` 1 will be updated

(edb/get-named-item schema store-v3 :users :current)
;; Returns {:id 1 :username "retro" :likes "programming"}
```
As you can see the data is kept consistent, without any manual synchronization.

Read the EntityDB [API docs](api/keechma.edb.html).