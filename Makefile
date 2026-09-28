.PHONY: default dev clean release release-client release-server test test-clj test-cljs

default:
	@echo "Usage:"
	@echo "  make clean     - clean temporary files"
	@echo "  make dev       - development REPL"
	@echo "  make release   - production build"
	@echo "  make test      - run all tests (JVM and Node)"
	@echo "  make test-clj  - run Clojure tests on the JVM"
	@echo "  make test-cljs - run ClojureScript tests on Node"
	@echo "  make db-up|down - manage local Postgres database"

dev: db-up
	@echo "Running dev REPL ... see 'dev/user.clj' for instructions"
	PORT=8081 clojure -M:dev

release-client:
	npx shadow-cljs release app

release-server:
	clojure -T:build uberjar

# Rust icechunk download script integration:
# Why? There is no JVM alternative. It's Rust or Python. And we chose Rust.
# The era5-timeseries binary is built once in release mode and bundled into the uberjar.
# (see src/clj/climate_changed/era5/fetch.clj for how it's discovered at runtime).
# Warning: This makes the jar platform-specific!

release-era5-timeseries:
	cd src/rs/era5-timeseries && cargo build --release
	install -D -m 755 src/rs/era5-timeseries/target/release/era5-timeseries resources/bin/era5-timeseries

clean:
	rm -rf ./target
	rm -rf ./resources/public/js/
	rm -rf ./resources/bin/
	rm -rf ./src/rs/era5-timeseries/target/

release: clean release-client release-era5-timeseries release-server

test-clj:
	clojure -X:test

test-cljs:
	npx shadow-cljs compile test && node target/node-tests.js

test: db-up test-clj test-cljs

db-up:
	cd infra/ && docker compose up -d

db-down:
	cd infra/ && docker compose down

era:
	./resources/bin/era5-timeseries 198 1020 t2m


