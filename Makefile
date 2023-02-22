develop: data
	STORAGE_PATH=data clojure -M:nrepl
.PHONY: develop

run: data
	STORAGE_PATH=data clojure -M -m blobcas.core
.PHONY: run

data:
	mkdir data
