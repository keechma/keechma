# Gluing everything together

Each of the described parts allow you to write parts of the app that are decoupled from each other. Now we need to assemble it into the app. Keechma comes with the app state library that takes care of that.

It does the following:

1. It binds the routes to the history (right now pushState doesn't work, but it's coming soon)
2. It creates system from the components you registered
3. It starts the controller manager
4. It renders the app to the HTML element

Keechma apps can be started and stopped. Stopping the app will do the cleanup (unbind the router) and remove it from the page.

## Communication between components

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
                 :subscriptions {...map of component subscriptions...}
                 :components {...map of components...}})
```

When you want a component to be able to send the messages to the controller, you have to `assoc` it a `:topic`:

```clojure
(def system (keechma.ui-component/system {:restaurants (assoc restaurants-component :topic :restaurants)}))

;; Now when you send a message from the component it will be picked by the :restaurants controller:

(defn restaurants-renderer [ctx]
  (keechma.ui-component/send-command :command-name command-args))
```
