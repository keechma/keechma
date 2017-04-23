# Router

Keechma treats the URL as the single source of the truth. URL's are expected to contain all data needed to recreate the application's state.

The router in Keechma is implemented in a way that supports that design decision:

1. Routes are just a list of patterns that describe the transformation from URL to params and from params to an URL string.
2. There are no named routes, you give the router a params map and the router returns the URL that is the closest representation of the given data.
    - Params that weren't matched by the route placeholders will be encoded as the query params.
3. When the URL changes, it's transformed into a params map (based on the route pattern that is the closest match for a given URL).

Unlike other systems, you don't associate an action with a route. Each URL change will result in the following:

1. URL will be transformed into a params map
2. Route params will be sent to the Controller Manager
3. The Controller Manager will start or stop controllers according to the route params

You will never directly interact with the router, it's managed automatically when you start the application.

Keechma's router has no side-effects (these are set up when the application is started) and has no globally shared state. It is implemented in a purely functional way - it only transforms the data between formats.

Here are the router [API docs](/api/router/).

