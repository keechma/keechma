# Application Walkthrough

The application I'll be using as an example can be found on [Github](http://github.com/keechma/keechma-place-my-order). It is a ClojureScript rewrite of the [DoneJS app](http://place-my-order.com) that is covered in the [DoneJS Guides](http://donejs.com/place-my-order.html).

I picked this as an example app for three reasons:

1. I have previous experience with DoneJS
2. It's an app that was created to showcase a different framework, so it was interesting to see how the ideas behind the frameworks differ
3. Place-my-order app implements the backend code (including the WebSockets) which allowed me to focus on frontend code only

I want to point out that DoneJS implementation has more functionality (like server-side rendering), and is implemented in a very different way. If you're doing JavaScript development, definitely check it out, it's pretty interesting.

## Let's start

Place-my-order app is a minimal implementation of the restaurant ordering service.

Before we start I recommend you to either install the application locally (you can find the instructions [here](https://github.com/keechma/keechma-place-my-order)) or go to http://place-my-order.com and click around to get the feeling of how the application works.

### High-level architecture overview

Here is the list of requirements for the app:

1. Landing page is static and shows the intro text and an image
2. Restaurants page:
    1. When user loads the restaurant page it shows two select elements
        - State select box should show a list of states
        - City select box is populated based on the selected state
    2. When user selects the state and the city it should load the list of restaurants
    3. When user clicks on the "Place my order" button it should show the restaurant landing page
    4. When user clicks on the "Order from this restaurant" button it should show the order form
        1. Order form has two tabs: "Lunch menu" and "Dinner menu"
        2. Each tab has a list of meals
        3. Toggling the meal should update the order total
    5. When user places the order it is saved and user can place another order
3. Order history page
    1. Order history page shows all placed orders
    2. Users can change the order status (`new -> preparing -> delivery -> delivered`)
    3. Order history should be synchronized on all open pages through WebSockets

### Routes

Translating the requirements to routes gives us the following:

- `/` - Landing page
- `/restaurants` - restaurants page with filtering
- `/restaurants/:restaurant-slug` - restaurant landing page
- `/restaurants/:restaurant-slug/order` - restaurant ordering page
- `/order-history` - order history page

Routes are the central place in Keechma apps, and they are treated as a minimal representation of the application state. Based on the route we know what data needs to be loaded when a user lands on that route:

- `/` - Nothing, landing page is static
- `/restaurants` - list of US states, everything else is incrementally loaded based on user's actions
- `/restaurants/:restaurant-slug` - restaurant entity, based on the `restaurant-slug`
- `/restaurants/:restaurant-slug/order` - restaurant entity, based on the `restaurant-slug`
- `/order-history` - Order history list

This way of thinking allows us to enforce strict top-down data flow. UI components never request any data, it is provided to them by controllers, based on the route params.

### Controllers

Controllers in Keechma apps have the following purposes:

- Mutate app state based on the route
- React to user actions by listening to commands sent from the UI
- React to any other commands (for instance WebSocket messages)

Controllers communicate with the outer world through channels and are the only place in the application where you can mutate the application state.

Place-my-order app is implemented with five controllers:

1. Restaurants controller
    + Loads the state's list
    + Based on user selection loads the cities list (for the selected state)
    + Based on user selection loads the restaurants' list (for the selected city)
2. Restaurant controller
    + Loads the selected restaurant
3. Order controller
    + Manages the order creation
    + Manages the order status change (`new -> preparing -> delivery -> delivered`)
    + Manages order deletion
4. Order history controller
    + Loads the order history
5. Vacuum controller - I'll talk about this at the end of this article

There can be multiple controllers running for each route, each one of them managing a subset of the app state.

For instance, order controller is active both on the route `/restaurants/:restaurant-slug/order` and on the route `/order-history`. I'll talk more about it later in the article.

### EntityDB

The purpose of the entity database is to give you a place where you can store the entity data. Anything that has some kind of identity (for instance the `id` column) should be managed by the EntityDB.

It can also handle relations between entities, but this is out of the scope of this article.

EntityDB is set up in the [`client/src/edb.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/edb.cljs) file but we only care about lines 4 - 8. The rest of the file is syntactic sugar that allows a bit nicer API to app state mutation.

```clojure
(def dbal (edb/make-dbal {:states {:id :short}
                          :cities {:id :name}
                          :restaurants {:id :slug}
                          :orders {:id :_id}}))
```

This defines the structure of the entity database, it has four stores - states, cities, restaurants and orders, and we define the identity function for each of them.

### UI components

Keechma is designed to work with Reagent, and the fact that Reagent components are implemented as functions gives us some interesting possibilities.

When developing frontend applications (in most cases) there are two options:

- Each component can get its data from the parent
- Each component depends on some global store and gets its data from there

Both of these have their own problems, but Keechma takes the middle road. Each component gets the data injected from the outside but without global dependencies. The way it works is the following:

1. Each component defines the component record which lists the component dependencies, both for data and for the children component it renders
2. When the application is started each component's renderer function (which is a Reagent component) is partially applied with the context that can be used to resolve data and children components.

This allows us to define components that are completely decoupled from the rest of the system. They don't care about the data they render or about the children components they might use to render that data.

For example, this is the restaurants list component:

```clojure
(defn render-restaurant [ctx restaurant]
  [:div.restaurant {:key (:slug restaurant)}
   [:img {:src (get-in restaurant [:images :thumbnail])}]
   [:h3 (:name restaurant)]
   (render-address (:address restaurant))
   (render-hours)
   [:a.btn {:href (ui/url ctx {:page "restaurants" :slug (:slug restaurant)})}
    "Place My Order"]
   [:br]])

(defn render [ctx]
  (let [restaurants-sub (ui/subscription ctx :restaurants)]
    (fn []
      (let [restaurants @restaurants-sub
            restaurants-meta (meta restaurants)]
        [:div.restaurants
         [:h2.page-header "Restaurants"]
         [:form.form
          [(ui/component ctx :states)]
          [(ui/component ctx :cities)]]
         (if (:is-loading? restaurants-meta)
           [:div.restaurants.loading]
           (map (partial render-restaurant ctx) restaurants))]))))

(def component (ui/constructor
                {:subscription-deps [:restaurants]
                 :component-deps [:cities :states]
                 :renderer render}))
```

This component manages the entire workflow of the restaurant selection:

- It renders the `states` select element
- It renders the `cities` select element
- It renders the list of restaurants

Notice that it resolves `states` and `cities` components from the context:

```clojure
[(ui/component ctx :states)]
[(ui/component ctx :cities)]
```

It never passes any data to them, they have their own context partially applied and know how to render themselves based on the current app state.

This way `restaurants-list` component is reusable from the start, we can easily replace components that are used to select the state or the city by remapping the dependencies.

#### UI system

Components are composed to systems. In place-my-order app this happens in the [`src/client/component_system.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/component_system.cljs) file:

```clojure
(ns client.component-system
  (:require [client.components.app :as app]
            [client.components.landing :as landing]
            [client.components.restaurant-list :as restaurant-list]
            [client.components.restaurant-detail :as restaurant-detail]
            [client.components.cities :as cities]
            [client.components.states :as states]
            [client.components.order :as order]
            [client.components.order-form :as order-form]
            [client.components.order-report :as order-report]
            [client.components.order-history :as order-history]
            [client.components.order-list-item :as order-list-item]))

(def system
   {:main app/component
    :landing landing/component
    :cities (assoc cities/component :topic :restaurants)
    :states (assoc states/component :topic :restaurants)
    :restaurant-list (assoc restaurant-list/component :topic :restaurants)
    :restaurant-detail restaurant-detail/component
    :order order/component
    :order-report (assoc order-report/component :topic :order)
    :order-form (assoc order-form/component :topic :order)
    :order-list-item (assoc order-list-item/component :topic :order)
    :order-history order-history/component})
```

All we have to do is to require all components and map them to their keys in the `system` map. When starting the app Keechma will resolve the dependencies (using the excellent [`dependency`](https://github.com/stuartsierra/dependency) library by Stuart Sierra) and partially apply them to each component.

In place-my-order app, there is no need for it, but you could manually override some of the dependencies and automatically resolve the rest.

You might notice that some components have `:topic` assoc-ed to them. This tells component on which topic it should send commands. The topic is the key that you used to register the controller (more about that later).

### Subscriptions

Subscriptions in Keechma work similar to Re/Frame's subscriptions.

```clojure
(ns client.subscriptions
  (:require [client.edb :as edb])
  (:require-macros [reagent.ratom :refer [reaction]]))

(defn states [app-db]
  (reaction
   (edb/get-collection @app-db :states :list)))

(defn cities [app-db]
  (reaction
   (edb/get-collection @app-db :cities :list)))

(defn restaurants [app-db]
  (reaction
   (edb/get-collection @app-db :restaurants :list)))

(defn current-restaurant [app-db]
  (reaction
   (let [slug (get-in @app-db [:route :data :slug])]
     (when slug
       (edb/get-item-by-id @app-db :restaurants slug)))))

(defn current-order [app-db]
  (reaction
   (edb/get-named-item @app-db :orders :current)))

(defn order-history [app-db]
  (reaction
   (edb/get-collection @app-db :orders :history)))

(def all {:states states
          :cities cities
          :restaurants restaurants
          :current-restaurant current-restaurant
          :current-order current-order
          :order-history order-history})
```

Each subscription is a function that takes the app's state atom as an argument and returns the subset of data from the app state.

### Defining the app

You can find the app definition in the [`src/client/app.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/app.cljs). The `definition` map holds the following information:

- Which route patterns are used
- Which controllers are used in the app
- What element to use to mount the app
- What components are used in the app
- What subscriptions are available in the app

This is a place where all of the decoupled parts come together. Controllers are passed in as a map:

```clojure
{
    ...
    :controllers {:restaurants (c-restaurants/->Controller)
                  :restaurant (c-restaurant/->Controller)
                  :order (c-order/->Controller)
                  :order-history (c-order-history/->Controller)
                  :vacuum (c-vacuum/->Controller)}
    ...
}
```

Keys in the `:controllers` map are topics on which each controller listens to commands. Those are the same topics that were assoc-ed to the components in the component system file.

## App functionality

The previous part was a high-level overview of the application. In the next part, I'll go through each functionality and show you how it was implemented.

### Landing page (`/`)

A landing page is static, it only shows the text and the image, so it's just rendered in the [`src/client/components/landing.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/components/landing.cljs) component.

### Restaurants page (`/restaurants`)

Restaurants page is managed by the [`src/client/controllers/restaurants.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/controllers/restaurants.cljs) controller.

The controller file has a bunch of comments which explain what's going on. I recommend you to read them before you continue.

The restaurants' controller does the following:

- Loads the `states` list
- Waits for the user command to load the `cities` list (when user selects the state)
- Waits for the user command to load the `restaurants` list (when user selects the city)

The components used to render this page are:

- [`src/client/components/restaurant_list.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/components/restaurant_list.cljs) - Main component, renders the `states` and `cities` components, renders the list of restaurants
- [`src/client/components/states.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/components/states.cljs) - Renders the select state component, when the user selects the state it sends the `select-state` command to the controller
- [`src/client/components/cities`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/components/cities.cljs) - Renders the select city component, when the user selects the state it sends the `select-city` command to the controller

Each rendered restaurant will have the "Place my order" link which will take us to the `/restaurants/:slug` page where we'll render the restaurant landing page.

### Restaurant landing page (`/restaurants/:slug`)

Restaurants page is managed by the [`src/client/controllers/restaurant.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/controllers/restaurant.cljs) controller.

The controller file has a bunch of comments which explain what's going on. I recommend you to read them before you continue.

The only thing that the `restaurant` controller does is the loading of the restaurant based on the slug.

It is rendered by the [`src/client/components/restaurant_detail.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/components/restaurant_detail.cljs) component.

On the page, there will be a link that will take us to the restaurant order page.

### Restaurant order page (`/restaurants/:slug/order`)

The restaurant order page is managed by two controllers:

1. [`src/client/controllers/restaurant.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/controllers/restaurant.cljs) controller
2. [`src/client/controllers/order.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/controllers/order.cljs) controller

Both controller files have a bunch of comments which explain what's going on. I recommend you to read them before you continue.

The restaurant controller is responsible for the loading of the restaurant information, and the order controller manages the creation of new order.

The components used to render this page are:

- [`src/client/components/order.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/components/order.cljs) - this component will render the `order_form` or `order_report` component based on the existence of the `current_order`
- [`src/client/components/order_form.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/components/order_form.cljs) - this component renders the order form:
  - It will render the tabbed menu list (Lunch menu and Dinner menu)
  - It will collect the order data in it's local state
  - When the user clicks the "Place my order!" button it will validate the form data and send the `save-order` command to the controller
- [`src/client/components/order_report.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/components/order_report.cljs) - this component renders the details of the `current_order`. If the user clicks on the "Place another order!" link, it will send the `clear-order` command which will cause the current order to be cleared from the app state, so the `order_form` component will be rendered again.

### Order history page (`/order-history`)

The order history page is managed by two controllers:

1. [`src/client/controllers/order_history.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/controllers/order_history.cljs) controller
2. [`src/client/controllers/order.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/controllers/order.cljs) controller

Both controller files have a bunch of comments which explain what's going on. I recommend you to read them before you continue.

The order history controller is responsible for the loading of the order history, and the order controller manages the `status` change for each of the rendered orders.

The components used to render this page are:

- [`src/client/components/order_history.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/components/order_history.cljs) - this component partitions the order history list based on the order status. It also renders all of the orders with the `order_list_item` component.
- [`src/client/components/order_list_item.cljs`](https://github.com/keechma/keechma-place-my-order/blob/master/client/src/client/components/order_list_item.cljs) - this component renders one order. It will also send the `mark-order` or `delete-order` command to the `order` controller based on the user actions.

## Vacuuming the entity db

I've mentioned the `vacuum` controller previously. The purpose of this controller is to ensure that the entity db holds only the data that is needed to render the current page.

When you have a single page app, it is easy to accumulate bunch of garbage in your app state db. This problem is even worse when you use the Keechma's EntityDB.

### How EntityDB works

EntityDB ensures that when you render an entity multiple times on the page, you always get the same object. This way if you change the entity in one place, it is automatically updated anywhere that entity is rendered. You don't have to do any synchronization, everything is taken care of automatically.

This is a feature I've used for years in [CanJS](http://canjs.com), it's extremely convenient and it allows you to avoid a whole class of bugs related to the data synchronization.

In CanJS it works in the following way:

- When the model instance is loaded from the server it's placed in the global store
- Each time this model instance is rendered in the page, CanJS increments the reference count for that model instance
- If that same model instance is loaded from some other place, the data will be merged with the one in the global store, and the same object will be returned
- When the user updates the model instance, it is mutated in place and changes are applied everywhere that model instance was rendered.
- When the DOM changes and the model instance is removed from the screen, CanJS decrements the reference count for that model instance
- When the reference count is zero, item is removed from the global store

This works for CanJS because it uses the mutable data structures and because CanJS controls the whole stack, from models to the rendering.

In ClojureScript, we can't do that. ClojureScript data structures are immutable, so we can't hold the reference to the entity. Also, Reagent (and React) don't do any book keeping when the item is rendered on the page.

EntityDB solves this problem in the following way:

- You must give names to your collections or items, this way you can load the items by the name from your subscriptions
- When you call `edb/insert-collection` or `edb/insert-named-item` it will put the entity in the store, and replace items in the collection or in the named item with the `id` of that item
- When you call `edb/get-collection` or `edb/get-named-item` it will return whatever is in the global store based on the `id`.

All of this happens under the hood, and we can still use the nice API to interact with the EntityDB, but it comes at a cost: When you call `edb/remove-collection` or `edb/remove-named-item` it doesn't really remove anything from the store, it just removes those collections or named items. It works in this way because each item in the store can exist in multiple collections or named items.

That's why the EntityDB implements the `vacuum` function. This function will go through all stores and remove items that are not referenced in any collections or named items.

Since Keechma knows what data is needed based on the route, the vacuum controller can clean the database on each route and remove the obsolete items.

## Conclusion

Keechma is a small framework, but I believe it brings a lot of value to the development. I believe that the problems it solves are the problems that each developer encounters while developing single page apps. Even if you don't end up using Keechma, I hope you got some ideas how to approach these problems and solve them.

