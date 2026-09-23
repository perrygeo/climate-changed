.PHONY: default dev clean release release-client release-server

default:
	@echo "Usage:"
	@echo "  make clean   - clean temporary files"
	@echo "  make dev     - development REPL"
	@echo "  make release - production build"

dev:
	@echo "Running dev REPL ... see 'dev/user.clj' for instructions"
	PORT=8081 clj -M:dev

release-client:
	npx shadow-cljs release app

release-server:
	clojure -T:build uberjar

clean:
	rm -rf ./target
	rm -rf ./resources/public/js/

release: clean release-client release-server
