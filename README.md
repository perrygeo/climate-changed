# Climate Changed

A template for website using full-stack Clojure.

The goal is to provide a starter repo with everything wired together just like I like em :-)


> "Play the volume loud as you want to, but don't touch my levels now. I got them set just like I like 'em."


## Source code
6 key source files:

```
 src
├──  clj/climate_changed/
│   ├──  app.clj
│   ├──  components.clj
│   ├──  handlers.clj
│   └──  main.clj
├──  cljc/climate_changed/
│   └──  shared.cljc
└──  cljs/climate_changed/
    └──  client.cljs
```

### Server

The server side is JVM Clojure.

* bidi routing
* integrant components
* ring
* jetty9
* hikaricp database connection pooling
* migratus migrations (SQL files in `src/sql/migrations/`)

The server namespaces are split by concern:

* `main.clj` - integrant config and entry point
* `components.clj` - integrant init/halt methods for the components
* `app.clj` - bidi routes and the ring middleware stack
* `handlers.clj` - the individual ring handlers

### Client

The client side is ClojureScript compiled to JS

* reagant for components
* shadow-cljs builds
* garden for css

### Shared

When possible, define common functions and data using `.cljc` files


## Development

see `dev/user.clj`
