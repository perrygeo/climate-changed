(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'climate-changed/climate-changed)
(def version (or (System/getenv "VERSION") "0.1.0-SNAPSHOT"))
(def class-dir "target/classes")

(defn uberjar
  "Build an uberjar that includes the production CLJS build output.
  Run `make release-client` first to produce resources/public/js/main.js."
  [_]
  (println "\n=== Building climate-changed uberjar ===\n")

  (let [basis      (b/create-basis {:aliases [:build]})
        uber-file  (format "target/climate-changed-%s-standalone.jar" version)]

    ;; Check that client build exists
    (let [client-js "resources/public/js/main.js"]
      (when-not (.exists (java.io.File. client-js))
        (println "WARNING:" client-js "not found.")
        (println "Run 'make release-client' first to build client assets.")))

    ;; Clean target directory
    (b/delete {:path "target"})

    ;; Copy server source and resource files into class-dir
    (b/copy-dir {:src-dirs   ["src/clj" "resources"]
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
             :main      'climate-changed.main})

    (println "\n=== Uberjar built:" uber-file "===")))
