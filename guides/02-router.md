# Router

Keechma is a URL - centric system. URLs drive the application, and app-state is derived from the URL.

Router in Keechma is ported from CanJS and works in the similar way, your routes define patterns in which you transform urls to the map or a map to the URL.

Here's an example:

```clojure
;; define routes
(def routes [[":page", {:page "index"}]
              ":page/:id"
              ":page/:id/:action"]) 

(def expanded-routes (keechma.router/expand-routes routes))

(keechma.router/url->map expanded-routes "foo")
;; {:page "foo"}

(keechma.router/url->map expanded-routes "foo/1")
;; {:page "foo" :id 1}

(keechma.router/map->url expanded-routes {:page "foo"})
;; "foo"

(keechma.router/map->url expanded-routes {:page "foo" :id 1})
;; "foo/1"

(keechma.router/map->url expanded-routes {:page "foo" :id 1 :action "bar" :qux "baz"})
;; "foo/1/bar?qux=baz"
```

Few things to point out:

1. Everything is pure, router has no side effects
2. You never define actions for your routes
3. Data in - data out

Router has no knowledge of the URL bar in the browser, and it has no global state.
