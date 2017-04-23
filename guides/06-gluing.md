# Gluing everything together

In previous articles, we covered everything you need to understand to build an application with Keechma. There's one last question: how do we assemble everything and render it on the page?

Keechma comes with a library that brings it all together; it:

1. Binds the router to the history change event
2. Creates a system from the components you registered and resolves their dependencies
3. Starts the controller manager
4. Mounts the application to the DOM element

## Starting and stopping

With `start!` and `stop!`, Keechma applications can simply be turned on and off. Stopping the app will do the cleanup (unbind the router) and remove it from the page.

```clojure
(def app {[...list of routes...]
          :controllers {...map of controllers...}
          :components {...map of components...}
          :subscriptions {...map of component subscriptions...}})

(def running-app (app-state/start! app))
;; Starts the application and mounts it to the DOM

(stop! running-app)
;; Stops the application and unmounts it from the DOM
```

## Communication between the application parts

When starting the app, Keechma creates a commands channel that can be used by the UI components to send commands to the controllers. Keys used to register the controllers are topics on which they listen for messages.

Example:

```clojure
(def definition {:controllers {:restaurants (c-restaurants/->Controller) ;; listens on the `restaurants` topic}
                 :html-element (.getElementById js/document "app")
                 :subscriptions {...map of component subscriptions...}
                 :components {...map of components...}})
```

When you want a component to be able to send a messages to a controller, you have to `assoc` it a `:topic`:

```clojure
(def system
  (ui-component/system
    {:restaurants (assoc restaurants-component :topic :restaurants)}))

;; Now when you send a message from the component it will be picked by the :restaurants controller:

(defn restaurants-renderer [ctx]
  (ui-component/send-command :command-name command-args))
```

## Where to go next

You have lots of options for learning more about building a Keechma app. Here are a few:

- [API docs](/api/)
- [Example applications](/annotated/)
- [Whole application walkthrough](/guides/application-walkthrough/)

