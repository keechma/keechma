# EntityDB

EntityDB is a library that handles storage of any kind of entities in your application. An entity is anything that is "identifiable". Usually, the `id` attribute is used, but you can use anything that makes sense for your application.

## Why do you need EntityDB?

EntityDB solves two problems for you:

1. It allows you to store all entities in a central location
2. It ensures data consistency
    - If you have the same entity rendered in multiple places (for instance in a master - detail layout) - changes to entity in one place will update the other
    - If you have an entity inside some collection, removing it from the EntityDB store will remove it from the collection too

## How does it work?

EntityDB functions are implemented in a purely functional way, and they operate on the Clojure `map`.

EntityDB supports two ways to store items:

1. In collections
2. In named item slots

### Collections

Collections are just named lists of items. Whenever you store or retrieve a collection you use the collection's name.

### Named items

Similar to collections, but they reference just one item.

#### Why do you need to use named collections and named items?

Since ClojureScript data structures are immutable, holding a reference to a list of items (or item) will always give you the same value. By using names, EntityDB can internally update named items and collections when the data changes and ensure data consistency.

---

Before you can store the entities you need to define the schema. Each entity type needs it's own schema:

```clojure
(def schema {:users {:id :id}
             :news {:id :slug}})
```

With the schema defined, you can store entities:

```clojure
(def store-v1 {})

(def user {:id 1 :username "retro"})

(def store-v2 (insert-named-item schema store-v1 :users :current user))
;; store the user under the name `:current`

(get-named-item schema store-v2 :users :current)
;; Returns {:id 1 :username "retro"}

(def user-collection [{:id 1 :username "retro" :likes "programming"}
                      {:id 2 :username "neektza" :likes "wrestling"}])

(def store-v3
  (insert-collection schema store-v2 :users :list user-collection))
;; Store the user collection. User with the `:id` 1 will be updated

(get-named-item schema store-v3 :users :current)
;; Returns {:id 1 :username "retro" :likes "programming"}
```
As you can see the data is kept consistent, without any manual synchronisation.

## Relations

### One to One relations

EntityDB can handle simple relations between entities. Relations are defined in the schema:

```clojure
(def schema {:users {:id :id}
             :news {:relations {:author [:one :users]}}})
```

EntityDB will now expect a nested `author` object for each news entity. That object will be unpacked and stored in the `users` store.

When you insert an entity into the EntityDB multiple times, it's data will be merged with the previous version's data. This allows you to write code that looks like this:

```clojure
(def schema {:users {:id :id}
             :news {:relations {:author [:one :users]}}})

(def store-v1 {})

(def users [{:id 1 :username "retro"}
            {:id 2 :username "mihaelkonjevic"}])

(def news [{:id 1 :title "First News" :author {:id 1}}
           {:id 2 :title "Second News" :author {:id 2}}])

(def store-v2 (insert-collection schema store-v1 :news :list news))
;; Insert news into the store

(get-item-by-id schema store-v2 :users 1)
;; Returns {:id 1}

(def store-v3 (insert-collection schema store-v2 :users :list users))
;; Insert users into the store

(def news-1 (get-item-by-id schema store-v3 :news 1))
;; Returns {:id 1 :title "First News" :author (fn [])}

((:author news-1))
;; Returns {:id 1 :username "retro"}
```

When you access a nested relation stored in EntityDB, it will be wrapped inside a function. This ensures that circular relations can be resolved.

### One to Many relations

EntityDB supports one to many relations too:

```clojure
(def schema {:users {:id :id
                     :relations {:news [:many :news]}}
             :news {:id :id
                    :relations {:author [:one :users]}}})

(def store-v1 {})

(def user {:id 1 :username "retro" :news [{:id 1} {:id 2}]})

(def news [{:id 1 :title "First News" :author {:id 1}}])

(def store-v2 (insert-named-item schema store-v1 :users :current user))
;; Inserts user into the store

(get-item-by-id schema store-v2 :news 1)
;; Returns {:id 1}

(def store-v3 (insert-collection schema store-v2 :news :list news))
;; Inserts news into the store

(def user-1 (get-item-by-id schema store-v3 :users 1))
;; Returns {:id 1 :username "retro" :news (fn[])}

((:news user-1))
;; Returns [{:id 1 :title "First News" :author (fn[])}]
```

---

Relations support in EntityDB provides you with an effortless way to keep your data consistent.

Here are the EntityDB [API docs](api/keechma.edb.html).

