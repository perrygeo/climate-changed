(ns build.docs
  "Generate API documentation with Codox."
  (:require [codox.main :as codox]))

(def version (or (System/getenv "VERSION") "0.1.0-SNAPSHOT"))

(defn- base-opts [language source-paths output-path]
  {:name         "climate-changed"
   :description  "Full-stack application to analyze ERA5 weather data timeseries for select locations."
   :version      version
   :language     language
   :source-paths source-paths
   :output-path  output-path
   :metadata     {:doc/format :markdown}})

(defn generate
  "Generate API docs for every namespace:
   - Clojure (src/clj + src/cljc)      -> target/docs/clj
   - ClojureScript (src/cljs + src/cljc) -> target/docs/cljs"
  [_]
  (println "\n=== Generating Clojure API docs ===\n")
  (codox/generate-docs
   (base-opts :clojure ["src/clj" "src/cljc"] "target/docs/clj"))
  (println "\n=== Generating ClojureScript API docs ===\n")
  (codox/generate-docs
   (base-opts :clojurescript ["src/cljs" "src/cljc"] "target/docs/cljs"))
  (println "\n=== Docs generated in target/docs/ ===\n"))
