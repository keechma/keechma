# UI System

Keechma provides you with a way to write decoupled, reusable UI components. It's still using Reagent to implement and render the components but adds some structure around it to keep the things clean.

## Untangling the UI

UI is the messiest part of frontend application development. By default, the components are coupled to the application's state, (sometimes) to their parent components and (almost always) to their child components.

>Most of our components take the entire app state as their data. Parent components don’t pass their children sub cursor with just the bits they care about, they pass them the whole enchilada. Then we have a slew of paths defined in vars, which we use to extract the data we want. It’s not ideal. But it’s what we’ve had to do.
>
>From the [Why We Use Om, and Why We’re Excited for Om Next](http://blog.circleci.com/why-we-use-om-and-why-were-excited-for-om-next/) blog post

Keechma allows you to decouple UI components without falling into one of these traps:

- Passing data from parent to child components
  + it gets hard to maintain
  + moving components around is hard
  + each component must know about any component below it
- Global dependence on the application's state
  + no way to reuse the components
  + testing is hard

To achieve that, you'll need to write slightly more code but it pays off in the end. Let's say that you have a component that renders a list of users. That component is rendered inside the `user-page` component which is rendered inside the `layout` component.

Neither the `user-page` nor the `layout` component cares about the data that the `user-list` component needs. The user component declares it's dependencies in a Clojure record, and when it's rendered it will get it's dependencies injected from the application.

The problem with this approach is that the parent component has to be able to render the `user-list` component with the correct context. This means that the `user-page` can't just require the component. It needs to declare it's dependency on the `user-list` component which will allow it to render the correct **version** of the `user-list` component.

That's why Keechma implements the UI systems. UI systems allow components to get the right sub-component and data dependencies injected in.

### Data dependencies

Components declare dependencies on `subscriptions`. Subscriptions are functions that get the `app-state` atom passed in and return a subset of the data (They are almost identical to the [Re/Frame's subscriptions](https://github.com/Day8/re-frame#subscribe) although they are not global).

---

To reiterate, each component needs to declare dependencies on the data it needs to render, and on the child components, it needs to render - unless you're using pure components that have no dependencies, they can be required.

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

This way of building UI components has some other advantages too. For instance if later you build an alternative `user-list` component, that is rendering the user list differently, the only place where you must update the code is where you define the system:

```clojure
(def system
  (ui/system
    {:main layout-component ;; system must have the `:main` component defined
     :user-page user-page-component
     :user-table my-super-awesome-user-component}))
;; returns the bound `:main` component which can be mounted in the page
```

Both the `layout` and the `user-page` component will continue to work the same.

### Composing systems

Another advantage is that you can compose UI systems. If you had a big app with a lot of different areas, each area could be a system on it's own:

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

This allows you to easily scale your application, without ever building an unmanageable monolith.

### Manual dependency resolving

Let's say you have a generalized grid component and you use it in a few places in your project, eg. news list, and user list. With Keechma it's trivial to create two versions of this component, each mapped to it's own dependencies:

```clojure
(def system
  (ui/system
    {:user-grid (ui-component/resolve-subscription-dep
                  grid-component :list user-list)
     :news-grid (ui-component/resolve-subscription-dep
                  grid-component :list news-list))})
```

When you manually resolve dependencies, all unresolved dependencies will still be automatically resolved.

---

The UI system in Keechma allows you to write applications that encourage reuse of UI components, and by organizing them into sub-systems we can achieve code base scalability. You can also avoid the need to split your apps into smart and dumb components. All components are dumb and isolated, they get everything injected from the outside.

Read the UI system [API docs](api/keechma.ui-component.html).

