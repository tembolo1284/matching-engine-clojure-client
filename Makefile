.PHONY: repl relay dev build test clean help

help:
	@echo "Available targets:"
	@echo "  repl      - Start REPL client"
	@echo "  relay     - Start relay server"
	@echo "  dev       - Start ClojureScript dev server"
	@echo "  build     - Build all JARs and JS"
	@echo "  test      - Run all tests"
	@echo "  clean     - Remove build artifacts"

repl:
	clojure -M:repl

relay:
	clojure -M:relay

dev:
	npm run dev

build: build-client build-relay build-js

build-client:
	clojure -X:build-client

build-relay:
	clojure -X:build-relay

build-js:
	npm run release

test:
	clojure -X:test

clean:
	rm -rf target/ resources/public/js/ .shadow-cljs/ .cpcache/
