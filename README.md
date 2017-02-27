# Keechma

[![Clojars Project](https://img.shields.io/clojars/v/keechma.svg)](https://clojars.org/keechma)

Keechma is a micro framework for Reagent written in ClojureScript. It gives you a set of utilites that allow you to build applications that have the following properties:

- **Deterministic and predictable behavior**
    + Based on the route, you can determine what the application's state will be
    + UI never "requests" data it's always provided to it
- **Unidirectional data flow**
    1. Route params are derived from the URL
    2. Application state is derived from the route params
    3. UI is derived from the application state
- **Loose coupling between components**
    + Communication is performed through `core.async` channels
- **Automatic synchronization of entities' states**
    + An entity is any data loaded into the app that has identity (e.g. `:id` column)
    + Entities are stored in the EntityDB
    + EntityDB propagates entity state to every place where that entity is displayed in the UI
- **Enforced lifecycle (and memory safety)**
    + Automatically load data on route change
    + Automatically clean up stale data on route change
    + Automatically set up event listeners on route change (e.g. listener on the WebSocket)
    + Automatically tear down event listeners on route change
- **Applications are first class citizens**
    + Applications can be started and stopped
    + Applications can be passed around
    + Applications can mount sub - applications
- **UI components are decoupled and reusable**
    + UI components can declare it's dependencies
    + Dependencies are injected when the application is started
    + Each component has it's own context
- **No shared globals**
    + Router is bound to the application context
    + App state is bound to the application context
    + Multiple apps can run at the same time, each with it's own state

## Documentation

Read the [guides](http://keechma.com/01-introduction.html) or the [API docs](http://keechma.com/api/index.html) to find out more about Keechma.

## Name

> Kičma (lat. columna vertebralis) is a Croatian word for backbone / spine.

Yes, it’s a nod to BackboneJS and SpineJS.


## License

Copyright &copy; 2016 Mihael Konjevic.

Distributed under the MIT License.
