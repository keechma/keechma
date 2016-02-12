# UI System

In Keechma UI is completely decoupled. It’s decoupled from the data it renders and it’s decoupled from the child components that are used to render that data.

## Why?

In most of the other UI systems, components depend on the global store and react to it’s changes. Keechma uses no globals, so everything gets passed to components when that app starts.

**What does that mean?** When you define a component in Keechma, along the renderer (reagent) function, you define a record that declares that component’s dependencies.

- It’s data dependencies
- It’s child component dependencies

In building component based UIs there are two extremes:

`I get everything from my parent <——————> I depend on the globals and I don’t care`

Keechma takes another (middle) approach. Every component (that is stateful) declares it’s dependencies and they get passed to it when the app starts. 

In Keechma components are defined in terms of systems. A system looks like this:

```clojure
(defn layout-renderer [ctx]
  [:div.app
   [:div.sidebar (keechma.ui-component/component ctx :sidebar)] ;; renders the sidebar component
   [:div.main]])

(def layout-component (keechma.ui-component/constructor
                       {:renderer layout-renderer
                        :component-deps [:sidebar]}))

(defn sidebar-renderer [ctx]
  ...reagent code...)

(def sidebar-component (keechma.ui-component/constructor
                        {:renderer sidebar-renderer
                         :subscription-deps [:menu]}))

(def system (keechma.ui-component/system
             {:main layout-component
              :sidebar sidebar-component}
             {:menu (fn [app-state] ;; menu subscription
                     (:menu-items @app-state))})
```

System uses the `stuartsierra/component` library to resolve the component dependencies. That way you don't have to write too much boilerplate code if you use only the default mappings.

What are the benefits of this approach? Except of the obvious one, nothing is global, your components are completely reusable out of the box. When defining a system you can override the default component and subscription mappings for each component.

Let's say you have a generalized grid component, and you use it in a few places in your project, news list and user list. With Keechma it's trivial to create two versions of this component, each mapped to it's own dependencies:

```clojure
(def system {:user-grid (keechma.ui-component/resolve-subscription-dep grid-component :list user-list
             :news-grid (keechma.ui-component/resolve-subscription-dep grid-component :list news-list))})
```

When you manually resolve dependencies, all unresolved dependencies will still be automatically resolved.

Another property of the component systems is that they can be nested. For instance, you could create system for each of the main areas in your app, and then pass them to the main system which will use them to render those areas:

```clojure
(def user-admin-system (keechma.ui-component/system {...components-here...}))
(def news-admin-system (keechma.ui-component/system {...components-here...}))

(def main-system (keechma.ui-component/system {:users user-admin-system
                                               :news news-admin-system})
```

UI system in Keechma allows you to write applications that encourage reuse of UI components, and by organizing them into sub-systems we can achieve code base scalability. You can also avoid the need to split your apps into smart and dumb components. All components are dumb and isolated, they get everything injected from the outside.
