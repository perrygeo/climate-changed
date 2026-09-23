(ns user
  (:require
   [climate-changed.main :as main]
   [clojure.java.shell :as shell]
   [integrant.core :as ig]
   [integrant.repl :refer [go halt reset set-prep!]]
   [integrant.repl.state :as state]
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server]))

(set-prep!
 (fn []
   (ig/load-namespaces main/config)
   main/config))

(defn- open-browser [url]
  (shell/sh "xdg-open" url))

(comment
  ;; Manage the system
  (go)
  (halt)
  (reset)

  ;; Inspect the database connection from the current system
  (:climate-changed/datasource state/system)

  ;; ClojureScript Build process
  (shadow/watch :app)
  (open-browser "http://localhost:8081")

  ;; current namespace info
  (symbol (namespace ::x))
  (keys (ns-publics (symbol (namespace ::x)))) ;; symbols, clj only!

  ;; REPL mgmt
  ;; 1. Start a *second* REPL
  ;; using NeoVim and Conjure - <leader>sc
  ;; 
  ;; 2. Activate the clojurescript repl
  ;; using NeoVim and Conjure - :ConjureShadowSelect app - or
  (shadow/nrepl-select :app)
  ;;
  ;; 3. Switch sessions to get back and forth between clj and cljs
  ;; using NeoVim and Conjure - <leader>ss
  ;; 
  ;;
  )
