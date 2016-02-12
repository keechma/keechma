# EntityDB

Entity DB in Keechma is a place where you put your domain entities. Entity is anything that is "identifiable" by the `id` function (whatever that function is).

Anything you load from AJAX should be saved in EDB, anything that has any kind of meaning in your app should also go in there.

Entity DB solves the identity problem for you. As always, it’s easier to explain it with an example.

Let’s say that you have a master - detail view (like in Evernote). You have a list of notes, and when you click on the note you open that note in the detail view. Also let’s say that the note has a status (read, unread, read later).

When you change the `status` of the note (from `read` to `unread`) you want that status to be visible both in the list and in the detail view. But, how do you handle that? If your system keeps two copies of the note (one in the list, and one in the detail view) they will not be synchronised!

> ### A side note:
>
> "In JavaScript it’s not such a big problem, if your list and detail use the same object, you can change things in place, and it will change the `same` object, but in ClojureScript, everything is immutable, so that won’t work."

Names you give to your collections and to your "named" items should be domain names (related to your app). In the example of our Evernote clone it would look like this:

```clojure
(def schema {:notes {}})

(keechma.edb/insert-collection schema app-db :notes :list […list  of notes…])

(keechma.edb/insert-named-item schema app-db :notes :current {…current note…})
```

This way, if you changed the note and saved it again:

```clojure
(keechma.edb/insert-named-item schema app-db :notes :current new-note)
```

It would update the note in the list named `:list`. EDB has many more cool features, but the most important thing is, it takes care of your domain objects for you. You can use a nicely defined interface to interact with your domain objects and be sure that they will stay in sync.
