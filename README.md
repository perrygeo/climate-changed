# Climate Changed

A fullstack application to analyze ERA5 weather data timeseries for select locations.


## Source code

Key source directories

```
 build                                        ;; uberjar build script
 dev                                          ;; utils for local development
 resources                                    ;; static files shipped with release
 test                                         ;; unit/integration tests
 src/rs/era5-timeseries/                      ;; rust command line utility for fetching ERA5
 src/clj/climate_changed/backend/             ;; web server
 src/clj/climate_changed/era5/                ;; core weather data actions
 src/cljc/climate_changed/                    ;; shared utils, common math, clj/cljs agnostic
 src/cljs/climate_changed/frontend/           ;; interactive web map application
```

### Server

The server side is JVM Clojure.

* bidi routing
* integrant components
* ring
* jetty9
* `GET /api/locations` returns all locations as GeoJSON (RFC 7946), served
  from a bundled Natural Earth resource

The server namespaces split by concern:

* `backend/main.clj` - Configuration and entry point
* `backend/components.clj` - init/halt methods for the components
* `backend/server.clj` - routes and the ring middleware stack
* `backend/handlers.clj` - the individual ring handlers (HTTP logic, calls domain logic defined in other namespaces)

### Client

The client side is ClojureScript compiled to JS

* reagent for components
* shadow-cljs builds
* garden for css

Client code is in `frontend/`, split by concern: `main` (entry point),
`app` (top-level view), `search`, `interactive-map`, and `state` (ratoms).

### Shared

When possible, define common functions and data using `.cljc` files


## Development

Use the `Makefile` and the utility fns in `dev/user.clj`

```
$ make
Usage:
  make clean     - clean temporary files
  make dev       - development REPL
  make release   - production build
  make test      - run all tests (JVM and Node)
  make test-clj  - run Clojure tests on the JVM
  make test-cljs - run ClojureScript tests on Node
```
