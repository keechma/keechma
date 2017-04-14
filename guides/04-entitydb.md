# EntityDB

The big idea behind EntityDB is consistency. Since it ensures that there is only one instance of each entity in your application, consistency is inherent. This enables you to write simpler code and avoid a whole class of bugs related to data synchronization. Gone too is the requirement to model your application state in a quickly-unmanageable tree. EntityDB provides:

1. **Data Consistency** — collections of entities are kept up-to-date: remove an entity from EntityDB and it is removed from any collections that contain it
2. **Rendering Consistency** — any change to an entity is reflected everywhere it is rendered (e.g. a master-detail layout)
3. **Simplicity** — all entities are in a central location

## How does it work?

Implemented as a separate library, EntityDB tracks all of your application's entities. An entity is anything that is "identifiable". While the `:id` attribute is normally used for this purpose, your application can identify entities in whatever way makes sense.

Operating on a Clojure `map`, access to EntityDB is purely functional and supports two types of structures:

1. **Collections** — lists of entities stored and retrieved by the collection's name
2. **Named entity slots** — individual named entities 

Since ClojureScript data structures are immutable, holding a reference to a list of entities (or an entity) will always give you the same value. By using names, EntityDB can internally update named entities and collections when the data changes and ensure data consistency.

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
Data is kept consistent — no manual synchronisation required.

## Relations

### One-to-One relations

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

### One-to-Many relations

EntityDB supports one-to-many relations too:

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

Relations support in EntityDB provides you with a flexible way to keep your data consistent.

Here are the EntityDB [API docs](api/keechma.edb.html).

