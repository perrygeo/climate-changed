(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'climate-changed/climate-changed)
(def version (or (System/getenv "VERSION") "0.1.0-SNAPSHOT"))
(def class-dir "target/classes")

(defn uberjar
  "Build an uberjar that includes the production CLJS build output and the
  bundled Rust era5-timeseries binary. Run `make release-client` and
  `make release-era5-timeseries` first (or `make release` for everything)."
  [_]
  (println "\n=== Building climate-changed uberjar ===\n")

  (let [basis      (b/create-basis {:aliases [:build]})
        uber-file  (format "target/climate-changed-%s-standalone.jar" version)]

    ;; Check that client build exists
    (let [client-js "resources/public/js/main.js"]
      (when-not (.exists (java.io.File. client-js))
        (println "WARNING:" client-js "not found.")
        (println "Run 'make release-client' first to build client assets.")))

    ;; Check that the bundled Rust fetcher binary exists
    (let [fetcher-bin "resources/bin/era5-timeseries"]
      (when-not (.exists (java.io.File. fetcher-bin))
        (println "WARNING:" fetcher-bin "not found.")
        (println "Run 'make release-era5-timeseries' first to bundle the Rust binary.")))

    ;; Clean target directory
    (b/delete {:path "target"})

    ;; Copy server source and resource files into class-dir
    ;; (src/sql so the migrations end up in the uberjar)
    (b/copy-dir {:src-dirs   ["src/clj" "src/sql" "resources"]
                 :target-dir class-dir})

    ;; AOT-compile server namespaces so the main class is available
    (b/compile-clj {:basis     basis
                    :src-dirs  ["src/clj"]
                    :class-dir class-dir})

    ;; Write POM
    (b/write-pom {:class-dir class-dir
                  :lib       lib
                  :version   version
                  :basis     basis
                  :src-dirs  ["src/clj"]})

    ;; Build the uberjar (all deps + compiled classes)
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      'climate-changed.backend.main})

    (println "\n=== Uberjar built:" uber-file "===")))
