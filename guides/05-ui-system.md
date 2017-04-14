# UI System

Keechma allows you to write decoupled, reusable UI components. It's still using Reagent to implement and render them — it just adds some structure to keep things clean.

## Untangling the UI

The UI is the messiest part of frontend application development. In most architectures, the components are coupled to the application's state, (sometimes) to their parent components and (almost always) to their child components.

>Most of our components take the entire app state as their data. Parent components don’t pass their children sub cursor with just the bits they care about, they pass them the whole enchilada. Then we have a slew of paths defined in vars, which we use to extract the data we want. It’s not ideal. But it’s what we’ve had to do.
>
>From the [Why We Use Om, and Why We’re Excited for Om Next](http://blog.circleci.com/why-we-use-om-and-why-were-excited-for-om-next/) by Peter Jaros

Peter understands. Most architectures have these two serious traps: 

- **Parent components pass data to their children** — Forcing parent components to be aware of the needs of their children makes the "data plumbing" complicated, making the system hard to maintain and difficult to change.
- **Global dependence on the application state** — This dependency makes testing difficult and reuse impossible.

<table>
<tr><td>You want this...</td><td>Not this...</td></tr>
<tr>
<td><img src="http://i.imgur.com/YVxxNUl.jpg" alt="home run plumbing" width=300/></td>
<td><img src="http://www.stephenadams.com/badplumbing/images/badplumbing_16.jpg" alt="cluster plumbing" width=300/></td>
</tr>
</table>

With just a little extra code, Keechma allows you to decouple all UI components. No more plumbing traps. 

For example, let's say that you have a component that renders a list of users (`user-list`). It is rendered inside a `user-page` component which, in turn, is rendered inside a `layout` component. In Keechma, neither `user-page` nor `layout` cares about the data that `user-list` needs. The user component simply declares its dependencies in a Clojure record. When it's rendered, its dependencies are injected directly.

Instead of passing data around, the only requirement is for the parent to declare its dependency upon each child. This provides context for the child component. In our example, `user-page` explicitly declares its dependency on `user-list` which will allow it to render the correct **version** of the component.

Keechma's UI system allows components to simply declare child components, each carrying its own data dependencies (if it has them). No more worrying about what data needs to be sent where.

### Data dependencies

Components declare dependencies on `subscriptions`. Subscriptions are functions that receive the `app-state` atom as a parameter and return a subset of the data (They are almost identical to the [Re/Frame's subscriptions](https://github.com/Day8/re-frame#subscribe) although they are not global).

---

Again, each component declares both its data and child-component dependencies. There is an exception: if a child component has no data dependencies, it can simply be required.

Example:

```clojure
(defn user-table-renderer [ctx]
  (fn []
    (let [user-list (ui/subscription ctx :user-list)]
      ;; Get the user list subscription, it will be injected from the outside
      [:table
        (for [user @user-list]
          ... render user ...)])))

(defn user-table-component
  (ui/constructor
    {:renderer user-list-renderer
     :subscription-deps [:user-list]}))
     ;; Declare that this component is dependent on the `:user-list` subscription

(defn user-page-renderer [ctx]
  [:div
    (ui/component ctx :user-table)])
    ;; Get the correctly bound `user-table` component, it will be injected from the outside

(defn user-page-component [ctx]
  (ui/constructor
    {:renderer user-page-renderer
     :component-deps [:user-table]}))

(defn layout-renderer [ctx]
  [:div
    (ui/component ctx :user-page)])
    ;; Get the correctly bound `user-page` component, that knows how to render the user list. It will be injected from the outside

(defn layout-component
  (ui/constructor
    {:renderer layout-renderer
     :component-deps [:user-page]}))

(def system
  (ui/system
    {:main layout-component ;; system must have the `:main` component defined
     :user-page user-page-component
     :user-table user-table-component}
    {:user-list (fn [app-state])})) ;; this will be injected to the `user-table` component as the `:user-list` subscription
;; returns the bound `:main` component which can be mounted in the page

(reagent/render-component [system] dom-element)
```

There you have it, a completely decoupled UI system. The tradeoff is that you must explicitly declare dependencies for each component.

This way of building your UI has other advantages too. For instance, if later you build a better `user-list` component, only the system definition needs to be changed:

```clojure
(def system
  (ui/system
    {:main layout-component ;; system must have the `:main` component defined
     :user-page user-page-component
     :user-table my-super-awesome-user-component}))
;; returns the bound `:main` component which can be mounted in the page
```

Neither `layout` nor `user-page` requires refactoring.

### Composing systems

Keechma also allows UI system composition. If your app has many different functional areas, each could be defined as its own system:

```clojure
(def user-page-system
  (ui/system {...}))

(def news-page-system
  (ui/system {...}))

(def main-app-system
  (ui/system
    {:user-page user-page-system
     :news-page news-page-system}))
```

You can easily scale your application. No more unmanageable monoliths.

### Resolving dependencies manually

Let's say you created a generalized grid component and want to reuse it in a few places in your project, e.g. news list, user list, etc. With Keechma it's trivial to create different versions of a component, each mapped to its own dependencies:

```clojure
(def system
  (ui/system
    {:user-grid (ui-component/resolve-subscription-dep
                  grid-component :list user-list)
     :news-grid (ui-component/resolve-subscription-dep
                  grid-component :list news-list))})
```

Any dependencies left unresolved manually will be handled automatically.

---

Keechma's UI system allows you to reuse components, organize them into sub-systems and to scale your code base — all without having to build both smart _and_ dumb components. All Keechma's components are both dumb and decoupled; everything is injected from outside.

Here are the UI system [API docs](api/keechma.ui-component.html).

