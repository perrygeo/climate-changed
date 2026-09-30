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
## Production Release


### Infrastructure

AWS infrastructure is defined with Terraform in [`infra/terraform/`](infra/terraform/README.md):

* EC2 `t3.small` running NixOS (configured by the flake in `infra/flake.nix`),
  SSH-able as `root`, with an instance profile for S3
* CloudFront distribution pointing at the EC2 instance on port 9000 — caching is
  disabled for now but structured to be turned on once the app is stable
* S3 bucket to hold the timeseries data

See [`infra/README.md`](infra/terraform/README.md) for how to run
Terraform and how to (manually) configure DNS to point `climate-changed.org` at
the CloudFront distribution.

To verify, SSH into the ec2, run something on port 9000
(e.g. `nix-shell -p python3 --run 'python3 -m http.server 9000'`), and see the
results at `climate-changed.org`

### Systemd
The files live in `/var/lib/climate-changed/` on the live server.
