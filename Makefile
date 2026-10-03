.PHONY: default dev clean infra release release-client release-server test test-clj test-cljs coverage doc check deploy

default:
	@echo "Usage, local development"
	@echo "  make clean     - clean temporary files"
	@echo "  make dev       - development REPL"
	@echo "  make test      - run all tests (JVM and Node)"
	@echo "  make test-clj  - run Clojure tests on the JVM"
	@echo "  make test-cljs - run ClojureScript tests on Node"
	@echo "  make coverage  - JVM test coverage report (HTML in target/coverage)"
	@echo "  make doc       - API docs for all namespaces (HTML in target/docs)"
	@echo "  make update    - Update all dependencies"
	@echo "  make check     - static linters (clj-kondo + cljfmt)"
	@echo "Usage, operations"
	@echo "  make infra     - ensure AWS resources are configured"
	@echo "  make release   - build the release uberjar"
	@echo "  make deploy    - deploy the uberjar to prod"

# === Local Development ===

dev:
	@echo "Running dev REPL ... see 'dev/user.clj' for instructions"
	PORT=8081 clojure -M:dev

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

test: test-clj test-cljs

doc:
	clojure -X:doc

update:
	neil dep update
	rm package-lock.json
	npm update

check:
	clj-kondo --lint src test
	cljfmt check src test

# === Operations ===

PROD_IP := 98.89.137.144
PROD_USER := root
PROD_DIR := /var/lib/climate-changed
SSH := ssh -o StrictHostKeyChecking=accept-new $(PROD_USER)@$(PROD_IP)

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
    $(SSH) "nix-collect-garbage -d" # " && nix-store --optimise"

release-client:
	npx shadow-cljs release app

release-server:
	clojure -T:build uberjar

MUSL_TARGET := x86_64-unknown-linux-musl

release-era5-timeseries:
	rustup target add $(MUSL_TARGET) && \
	nix shell --extra-experimental-features 'nix-command flakes' 'nixpkgs#pkgsCross.musl64.stdenv.cc' 'nixpkgs#cmake' -c bash -lc 'set -euo pipefail; cd src/rs/era5-timeseries; export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER=x86_64-unknown-linux-musl-gcc CC_x86_64_unknown_linux_musl=x86_64-unknown-linux-musl-gcc CXX_x86_64_unknown_linux_musl=x86_64-unknown-linux-musl-g++ AR_x86_64_unknown_linux_musl=x86_64-unknown-linux-musl-ar; cargo build --release --target $(MUSL_TARGET)' && \
	install -D -m 755 src/rs/era5-timeseries/target/$(MUSL_TARGET)/release/era5-timeseries resources/bin/era5-timeseries
	@echo resources/bin/era5-timeseries

logs:
	$(SSH) "journalctl -u climate-changed.service -f"

# everything to test, build and push to production... except infra
# it ain't continuous but it's more reliable than github actions
integrate-and-deliver: check test clean release deploy
	@echo "`make logs` to tail server logs"
