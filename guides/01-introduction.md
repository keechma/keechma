# Introduction to Keechma

Keechma is an attempt to formalize solutions to the most common problems in frontend application development. It is implemented in ClojureScript, using the [Reagent](https://github.com/reagent-project/reagent) library, and is heavily inspired by [Re/Frame](https://github.com/Day8/re-frame). 

All parts are built in modular, decoupled way, but together they allow you to write apps that have the following properties:

- Data based routing
- Unidirectional data flow
- Decoupled UI
- Entity store
- Memory safety
- Lifecycle hooks

## Credits and Inspiration

The libraries that had the biggest impact on Keechma:

- [CanJS](http://canjs.com) - Keechma's router is ported from CanJS and EntityDB is inspired by the CanJS model store
- [Re/Frame](https://github.com/Day8/re-frame). - One-big-atom store, subscriptions and handlers are based of my experience with Re/Frame
- [Reagent](https://github.com/reagent-project/reagent) - Keechma would be much harder to implement without Reagent's approach to components
- [Om](https://github.com/omcljs/om) - Without Om I probably wouldn't try ClojureScript, so Keechma wouldn't exist.

## Differences

So, how does Keechma differ and why you should care?

Keechma has the following components:

- [Router](02-router.html)  - Pure data processing based routing
- [Entity database](03-controllers.html)  - In memory store for business entities
- [Controller system](04-entitydb.html) - A way to react to routes and mutate the world
- [UI System](05-ui-system.html) - A way to write Reagent components that have no global dependencies and are decoupled from data they show and child components they use to render the data.

Biggest and the most important difference of Keechma compared to other frameworks / libraries is that Keechma has no shared globals. All of the Keechma's building blocks are built in pure functionaly way, and parts that are not implement explicit `start` and `stop` which allows them to clean up after themselves.

Keechma as a system is extremely opinionated, but since each of the parts is built in a pure, functional processing way you could take the ingredients and build a completely different thing. 

Everything is complected together in the `src/keechma/app_state.cljs` file. This file implements functions that know how to start and stop the application.
