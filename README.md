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

### era_ts storage (S3-backed)

The ERA5 timeseries parquet cache lives at
`/var/lib/climate-changed/era_ts`. It used to sit on the EC2 root volume,
which is small and destroyed when the instance is rebuilt. It is now backed by
the S3 bucket `climate-changed-era5-timeseries-v1`:

    S3 bucket --ZeroFS--> /dev/nbd0 (block device) --ZFS--> era_ts

`infra/nixos/configuration.nix` wires this up with systemd units:

- `zerofs.service` — runs [ZeroFS](https://www.zerofs.net/docs/), exposing the
  bucket as a filesystem, an NBD export, and a loopback 9P endpoint.
- `zerofs-secret.service` — fetches the ZeroFS encryption password from AWS SSM
  Parameter Store (it must survive instance rebuilds).
- `zerofs-export.service` — one-time bootstrap that creates the sparse
  `.nbd/era_ts` export file.
- `zerofs-nbd.service` — attaches the export as `/dev/nbd0` via `nbd-client`.
- `zerofs-zfs.service` — imports (or first-time creates) the `era_ts_pool` ZFS
  pool on `/dev/nbd0` and mounts it at `/var/lib/climate-changed/era_ts`.
- `climate-changed.service` — starts only after the era_ts mount is up.

The ZeroFS encryption password is a `SecureString` in SSM
(`/climate-changed/zerofs/encryption-password`), created by Terraform
(`infra/terraform/zerofs.tf`). To (re)create infrastructure, run
`terraform init` (new `random` provider) then `terraform apply` before the
NixOS rebuild, so the SSM parameter exists.
