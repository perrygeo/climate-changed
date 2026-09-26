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

clean:
	rm -rf ./target
	rm -rf ./resources/public/js/

release: clean release-client release-server

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
	./src/py/era5-timeseries.py


