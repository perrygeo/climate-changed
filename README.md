# Climate Changed

A template for website using full-stack Clojure.

The goal is to provide a starter repo with everything wired together just like I like em :-)


> "Play the volume loud as you want to, but don't touch my levels now. I got them set just like I like 'em."


## Source code

Key source files:

```
 src
├──  clj/climate_changed/
│   ├──  backend/
│   │   ├──  components.clj
│   │   ├──  handlers.clj
│   │   ├──  main.clj
│   │   └──  server.clj
│   ├──  era5/
│   │   └──  fetch.clj
│   └──  models/
│       └──  locations.clj
├──  cljc/climate_changed/
│   ├──  common.cljc
│   └──  era5/
│       ├──  grid.cljc
│       └──  variables.cljc
└──  cljs/climate_changed/
    └──  frontend/
        ├──  app.cljs
        ├──  interactive_map.cljs
        ├──  main.cljs
        ├──  search.cljs
        └──  state.cljs
```

### Server

The server side is JVM Clojure.

* bidi routing
* integrant components
* ring
* jetty9
* hikaricp database connection pooling
* migratus migrations (SQL files in `src/sql/migrations/`)
* honeysql queries (`models/locations.clj`)
* `GET /api/locations` returns all locations as GeoJSON (RFC 7946)

The server namespaces are split by concern:

* `backend/main.clj` - integrant config and entry point
* `backend/components.clj` - integrant init/halt methods for the components
* `backend/server.clj` - bidi routes and the ring middleware stack
* `backend/handlers.clj` - the individual ring handlers

### Client

The client side is ClojureScript compiled to JS

* reagent for components
* shadow-cljs builds
* garden for css

Client code lives in `frontend/`, split by concern: `main` (entry point),
`app` (top-level view), `search`, `interactive-map`, and `state` (ratoms).

### Shared

When possible, define common functions and data using `.cljc` files


## Development

see `dev/user.clj`
