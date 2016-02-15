# Router

Keechma treats the URL as the single source of the truth. URL's are expected to contain all the data needed to recreate the application state.

Router in Keechma is implemented in a way that supports that design decision:

1. Routes are just a list of patterns that describe the transformation from URL to the params and from the params to the URL string.
2. There are no named routes, you give the router a params map and the router returns the URL that is the closest representation of the given data.
    - Params that weren't matched by the route placeholders will be encoded as the query params.
3. When the URL is changed the URL will be transformed to the params map (based on the route pattern that is the closest match for a given URL).

Unlike the other systems, you don't associate the action with the route. Each URL change will results with the following:

1. URL will be transformed into the params map
2. Route params will be sent to the Controller Manager
3. Controller Manager will start or stop the controllers according to the route params

You will never directly interact with the router, it is managed automatically when you start the application.

Keechma's router has no side - effects (these are set up when the application is started), and has no global shared state. It is implemented in a pure functional way - it only transforms the data between formats.

Read the router [API docs](api/keechma.router.html).








