.PHONY: default dev clean infra release release-client release-server test test-clj test-cljs coverage doc deploy

default:
	@echo "Usage:"
	@echo "  make clean     - clean temporary files"
	@echo "  make dev       - development REPL"
	@echo "  make infra     - ensure AWS resources are configured"
	@echo "  make release   - release build uberjar"
	@echo "  make deploy    - deploy the uberjar to prod"
	@echo "  make test      - run all tests (JVM and Node)"
	@echo "  make test-clj  - run Clojure tests on the JVM"
	@echo "  make test-cljs - run ClojureScript tests on Node"
	@echo "  make coverage  - JVM test coverage report (HTML in target/coverage)"
	@echo "  make doc       - API docs for all namespaces (HTML in target/docs)"

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

release: release-client release-era5-timeseries release-server

test-clj:
	clojure -M:test

test-cljs:
	npx shadow-cljs compile test && node target/node-tests.js

coverage:
	clojure -M:coverage

doc:
	clojure -X:doc

test: test-clj test-cljs

era:
	./resources/bin/era5-timeseries 198 1020 t2m

PROD_IP := 98.89.137.144
PROD_USER := root
PROD_DIR := /var/lib/climate-changed
SSH := ssh -o StrictHostKeyChecking=accept-new $(PROD_USER)@$(PROD_IP)

# Manual deployment (no nix packaging yet):
#   1) pick the newest timestamped uberjar in target/ and scp it to the box
#   2) repoint the `production.jar` symlink the systemd unit runs
#   3) restart the service so it picks up the new jar
# Assumes an ssh key/cert for $(PROD_USER)@$(PROD_IP) is already configured and
# that the climate-changed systemd unit exists (see infra/nixos/configuration.nix).
deploy:
	@JAR=$$(ls -t target/climate-changed-*.jar 2>/dev/null | head -n1); \
	if [ -z "$$JAR" ]; then \
		echo "No uberjar found in target/. Run 'make release' first." >&2; \
		exit 1; \
	fi; \
	BASENAME=$$(basename "$$JAR"); \
	echo "Deploying $$BASENAME to $(PROD_USER)@$(PROD_IP):$(PROD_DIR) ..."; \
	$(SSH) "mkdir -p $(PROD_DIR)"; \
	scp -o StrictHostKeyChecking=accept-new "$$JAR" "$(PROD_USER)@$(PROD_IP):$(PROD_DIR)/$$BASENAME"; \
	$(SSH) "ln -sfn $(PROD_DIR)/$$BASENAME $(PROD_DIR)/production.jar && systemctl restart climate-changed && systemctl --no-pager status climate-changed | head -n5"; \
	echo "Deployed $$BASENAME and restarted climate-changed."

infra:
	eval "$$(aws configure export-credentials --format env)" && cd infra/terraform && terraform apply
