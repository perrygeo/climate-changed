.PHONY: default dev clean release release-client release-server test test-clj test-cljs

default:
	@echo "Usage:"
	@echo "  make clean     - clean temporary files"
	@echo "  make dev       - development REPL"
	@echo "  make release   - production build"
	@echo "  make test      - run all tests (JVM and Node)"
	@echo "  make test-clj  - run Clojure tests on the JVM"
	@echo "  make test-cljs - run ClojureScript tests on Node"

dev:
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
# We cross-compile to musl so the binary is fully static. A glibc-linked build
# embeds an absolute /nix/store/.../ld-linux interpreter path that only exists on
# the machine that built it, so it dies with "No such file or directory" on other
# NixOS hosts (see infra/README.md).

MUSL_TARGET := x86_64-unknown-linux-musl

release-era5-timeseries:
	rustup target add $(MUSL_TARGET) && \
	nix shell --extra-experimental-features 'nix-command flakes' 'nixpkgs#pkgsCross.musl64.stdenv.cc' 'nixpkgs#cmake' -c bash -lc 'set -euo pipefail; cd src/rs/era5-timeseries; export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER=x86_64-unknown-linux-musl-gcc CC_x86_64_unknown_linux_musl=x86_64-unknown-linux-musl-gcc CXX_x86_64_unknown_linux_musl=x86_64-unknown-linux-musl-g++ AR_x86_64_unknown_linux_musl=x86_64-unknown-linux-musl-ar; cargo build --release --target $(MUSL_TARGET)' && \
	install -D -m 755 src/rs/era5-timeseries/target/$(MUSL_TARGET)/release/era5-timeseries resources/bin/era5-timeseries

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

test: test-clj test-cljs

era:
	./resources/bin/era5-timeseries 198 1020 t2m


