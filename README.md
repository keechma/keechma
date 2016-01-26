# Keechma

Keechma is a set of ClojureScript libraries that function as a backbone for your frontend app.

> Kičma (lat. columna vertebralis) is a Croatian word for backbone / spine.

Yes, it’s a nod to BackboneJS and SpineJS.

## Why Keechma

I’ve been working on JavaScript apps for a long time (> 5 years) and most of that time I was working both on the application and framework code (CanJS).

I’ve seen the start of the structured JavaScript development when we were building decoupled jQuery components that were communicating through events, and I’ve been part of the whole evolution to the central application data structure that is prevalent in the frontend applications today.

Keechma is my attempt to formalise the stuff I’ve been reinventing in every project and to fix the problems I’ve found in other projects.

—

Keechma aims to provide a structured development for ClojureScript  (frontend) apps. All parts are modular, but together they create a way to write apps that have the following features:

- Data based routing
- Unidirectional data flow
- Decoupled UI
- Entity store
- Memory safety
- Lifecycle hooks

Keechma is built on top of Reagent, and is the spiritual follower of Re/Frame.

### Credits

Keechma is built on top of work of many that came before. I want to list these projects here:

- CanJS - Router is ported from CanJS, EntityDB is very similar to the CanJS model store
- Re/Frame - We keep everything in one big atom, subscriptions are basically copied from Re/Frame and handlers are working in a similar way
- OM / Reagent - without these projects there would be no Keechma

### Differences

So, how does Keechma differ and why you should care?

Keechma has the following components:

- Router - Pure data processing based routing
- Entity database - In memory store for business entities
- Controller system - A way to react to routes and mutate the world
- UI/Component system - A way to write Reagent components that have no global dependencies and are decoupled from data they show and child components they use to render the data.

Biggest and the most important difference of Keechma compared to other frameworks / libraries is that Keechma has no shared globals. Everything in Keechma is startable / stoppable and is contained into it’s own little world.

I want to mention that Keechma as a system is extremely opinionated, but each of the parts is built in a pure, functional processing way. You could take these ingredients and build a completely different thing. 

Everything is complected together in the `src/keechma/app_state.cljs` file. This file contains `start!` and `stop!` functions that glue or split your components.

## How does it work

In this part I’ll go over each of the components that make Keechma, and in the end I’ll show how they fit together.

### Everything starts with the URL

Keechma is a URL - centric system. URLs drive the application, and app-state is derived from the URL.

In Keechma app state is a function over the URL and UI is a function over the app state. All data flows downward, and the URL must be a minimal representation of your app state.

Router in Keechma is ported from CanJS and works in the same way, your routes define patterns in which you transform urls to the map or a map to the URL.

Here’s an example:

```clojure
;; define routes
(def routes [[“:page”, {:page “index”}]
             “:page/:id”
             “:page/:id/:action”]) 

(def expanded-routes (keechma.router/expand-routes routes))

(keechma.router/url->map expanded-routes “foo”)
;; {:page “foo”}

(keechma.router/url->map expanded-routes “foo/1”)
;; {:page “foo” :id 1}

(keechma.router/map->url expanded-routes {:page “foo”})
;; “foo”

(keechma.router/map->url expanded-routes {:page “foo” :id 1})
;; “foo/1”

(keechma.router/map->url expanded-routes {:page “foo” :id 1 :action “bar” :qux “baz”})
;; “foo/1/bar?qux=baz”
```

Few things to point out:

1. Everything is pure
2. You never define actions for your routes
3. Data in, data out

In the following parts, I’ll cover how this router fits in the Keechma system.

### Controllers

Keechma is not an MVC system, but it has controllers. Controllers in Keechma react to route changes and take care of any kind of side-effect code.

- Controllers make AJAX requests.
- Controllers mutate application state
- Controllers can connect to web sockets, etc.

Anything that has side effects goes to controllers. This is a place where you put all that ugly stuff that actually runs the application and they let the rest of the app to be beautiful and pure.

Controllers are subjects to the route. They have a strict lifecycle which depends on the current route. Contrary to the controllers in the other frameworks, controllers in Keechma operate on the route, instead of the UI components.

I know it seems confusing, but I’ll try to make it clearer soon. Before that I want to talk about application state, how that state gets built and what are the consequences (which will never be the same).

Every frontend app has to take care of it’s state, and there are multiple ways how you can enter that state. In Keechma state is strictly derived from the URL, but we still have the following situations:

1. User reloads the page and lands to the page with the URL ‘/news/1”
2. User goes from the route “/news” to route “/news/1”
3. User is on the route “/news/1” and is posting a comment

These situations are something that you will have to handle in every app you write. There are also different UI layouts that can affect how you react to those routes:

- You could have a “page” based app and transition from “/news” to “/news/1” basically replaces the whole page (except for the menu, and the rest of the chrome)
- You could have a master - detail layout where you show a list of news in one panel and a detail view of the news with the id 1 in the detail panel
- Commenting on the news could take you to a new page
- Commenting on the news could append the comment to the list of the comments

Anyway, this is just a subset of possible scenarios. You need a system that scales across all of them.

You need a system that allows you to do the following:

- When user transitions from “/news” to “/news/1” replace everything on the screen with the news with id 1 but, don’t load anything, you already have it in memory.
- When user refreshes on the “/news/1” page and you use master - detail layout, load both the news list and the news with id 1 and show them both on the screen
- When user posts a comment and it’s saved in the DB, send him to the “Thank you page”
- When user posts a comment and it’s saved in the DB, show that comment on top of the existing comments

It gets complicated fast.

This part of Keechma was the hardest to get right, but I believe I have a solution.

Controllers in Keechma are `clojure` records that implement the `keechma.controller/IController` protocol. This protocol implements the following methods (among others):

- `params` - based on the route params, return params needed for this controller to run or `nil`
- `start` - synchronously mutate App DB when controller is started
- `stop` - synchronously mutate App DB when controller is stopped
- `handler` - function that can asynchronously react to commands

#### Controller lifecycle

With these methods defined, we can make a system that works. Controllers are run by routes. Every time when the URL changes Keechma starts the cycle. This cycle is the core of how applications are built with Keechma.

When URL changes Keechma will do the following:

1. It will call `params` function of all registered controllers
2. It will compare returned value to the last returned value (from the previous cycle)
3. Based on the result it will do the following:
  1. If previous value was `nil` and current value is `nil` it will do nothing
  2. If previous value was `nil` and current value is not `nil` it will start the controller
  3. If previous value was not `nil` and current value is `nil` it will stop the controller
  4. If previous value was not `nil` and current value is not `nil` but those values are same it will do nothing
  5. If previous value was not `nil` and current value is not `nil` but those values are different it will restart the controller

Important thing to mention is that `params` function is a way for the controller to express interest in the current state and do something with it.

### Entity DB

Entity DB in Keechma is a place where you put your domain entities. Entity is anything that is “identifiable” by the `id` function (whatever that function is).

Anything you load from AJAX should be saved in EDB, anything that has any kind of meaning in your app should also go in there.

Entity DB solves the identity problem for you. As always, it’s easier to explain it with an example.

Let’s say that you have a master - detail view (like in Evernote). You have a list of notes, and when you click on the note you open that note in the detail view. Also let’s say that the note has a status (read, unread, read later).

When you change the `status` of the note (from `read` to `unread`) you want that status to be visible both in the list and in the detail view. But, how do you handle that? If your system keeps two copies of the note (one in the list, and one in the detail view) they will not be synchronised!

> A side note here: In JavaScript it’s not such a big problem, if your list and detail use the same object, you can change things in place, and it will change the `same` object, but in ClojureScript, everything is immutable, so that won’t work.

EntityDB takes care of that. The tradeoff is that you have to give a name to your stuff. You must name your collections, and you must name your “named” items.

Names you give to your collections and to your “named” items should be domain names (related to your app). In the example of our Evernote clone it would look like this:

```clojure
;; define edb schema
(def schema {:notes {}})

(keechma.edb/insert-collection schema app-db :notes :list […list  of notes…])

(keechma.edb/insert-named-item schema app-db :notes :current {…current note…})
```

This way, if you changed your note and saved it again:

```clojure
(keechma.edb/insert-named-item schema app-db :notes :current new-note)
```

It would update the note in the list named `:list`. EDB has many more cool features, but the most important thing is, it takes care of your domain objects for you. You can use a nicely defined interface to interact with your domain objects and be sure that they will stay in sync.

### UI system

In Keechma UI is completely decoupled. It’s decoupled from the data it renders and it’s decoupled from the child components that are used to render that data.

#### Why?

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
   [:div.sidebar (keechma.ui-controller/component ctx :sidebar)] ;; renders the sidebar component
   [:div.main]])

(def layout-component (keechma.ui-controller/constructor
                       {:renderer layout-renderer
                        :component-deps [:sidebar]}))

(defn sidebar-renderer [ctx]
  ...reagent code...)

(def sidebar-component (keechma.ui-controller/constructor
                        {:renderer sidebar-renderer
                         :subscription-deps [:menu]}))

(def system (keechma.ui-controller/system
             {:main layout-component
              :sidebar sidebar-component}
             {:menu (fn [app-state] ;; menu subscription
                     (:menu-items @app-state))})
```

System uses the `stuartsierra/component` library to resolve the component dependencies. That way you don't have to write too much boilerplate code if you use only the default mappings.

What are the benefits of this approach? Except of the obvious one, nothing is global, your components are completely reusable out of the box. When defining a system you can override the default component and subscription mappings for each component.

Let's say you have a generalized grid component, and you use it in a few places in your project, news list and user list. With Keechma it's trivial to create two versions of this component, each mapped to it's own dependencies:

```clojure
(def system {:user-grid (keechma.ui-controller/resolve-subscription-dep grid-component :list user-list
             :news-grid (keechma.ui-controller/resolve-subscription-dep grid-component :list news-list))})
```

When you manually resolve dependencies, all unresolved dependencies will still be automatically resolved.

Another property of the component systems is that they can be nested. For instance, you could create system for each of the main areas in your app, and then pass them to the main system which will use them to render those areas:

```
(def user-admin-system (keechma.ui-component/system {...components-here...}))
(def news-admin-system (keechma.ui-component/system {...components-here...}))

(def main-system (keechma.ui-component/system {:users user-admin-system
                                               :news news-admin-system})
```

UI system in Keechma allows you to write applications that encourage reuse of UI components, and by organizing them into sub-systems we can achieve code base scalability. You can also avoid the need to split your apps into smart and dumb components. All components are dumb and isolated, they get everything injected from the outside. 

### Glueing everything together

Each of the described parts allow you to write parts of the app that are decoupled from each other. Now we need to assemble it into the app. Keechma comes with the app state library that takes care of that.

It does the following:

1. It binds the routes to the history (right now pushState doesn't work, but it's coming soon)
2. It creates system from the components you registered
3. It starts the controller manager
4. It renders the app to the HTML element

Keechma apps can be started and stopped. Stopping the app will do the cleanup (unbind the router) and remove it from the page.

#### Communication between components

Since everything is decoupled, we need a way in which we can communicate. For instance, components need to handle user actions.

When starting the app, Keechma will create a commands channel that can be used by components to message the controllers. Keys used to register the controllers are topics on which they listen for messages.

Example:

```clojure
(def definition {:routes [[":page" {:page "home"}]
                          ":page/:slug"
                          ":page/:slug/:action"]
                 :controllers {:restaurants (c-restaurants/->Controller) ;; listens on the `restaurants` topic
                               :restaurant (c-restaurant/->Controller)
                               :order (c-order/->Controller)
                               :order-history (c-order-history/->Controller)
                               :vacuum (c-vacuum/->Controller)}
                 :html-element (.getElementById js/document "app")
                 :components {...map of components...}})
```

When you want a component to be able to send the messages to the controller, you have to `assoc` it a `:topic`:

```clojure
(def system (keechma.ui-component/system {:restaurants (assoc restaurants-component :topic :restaurants)}))

;; Now when you send a message from the component it will be picked by the :restaurants controller:

(defn restaurants-renderer [ctx]
  (keechma.ui-component/send-command :command-name command-args))
```

#### Architecture overview:

Architecture of the Keechma app looks like this

![Keechma Architecture](http://i.imgur.com/lk0ZhCU.png?1)

- Route changes are communicated through the route channel to the controller manager
    + Based on the route controller manager will start or stop controllers
- Controllers have `in-chan` and `out-chan` which they can use to communicate with the world (they can send messages to other controllers).
- Controllers can change the app-db which will trigger the re-rendering of the app
- UI communicates with the controllers by sending messages through the `commands-chan`. Those messages get routed to the controller (based on the topic) which receives them through the `in-chan`

### Example Application

You can find an example app [here](https://bitbucket.org/retro/place-my-order/). It is a ClojureScript rewrite of the http://place-my-order.com application which is written as a part of the [DoneJS Guides](http://donejs.com/place-my-order.html).
