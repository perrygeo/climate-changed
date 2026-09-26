(ns user
  (:require
   [climate-changed.main :as main]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [integrant.core :as ig]
   [integrant.repl]
   [integrant.repl.state :as state]
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server]))

;; ==========================================================================
;;  Utility fns
;; ==========================================================================
(defn- open-browser
  "Cross-platform: uses `open` (macOS), `xdg-open` (Linux), or `start` (Windows)"
  [url]
  (let [os (System/getProperty "os.name")]
    (cond
      (str/starts-with? os "Mac") (shell/sh "open" url)
      (str/starts-with? os "Win") (shell/sh "cmd" "/c" "start" url)
      :else                        (shell/sh "xdg-open" url))))

;; ==========================================================================
;;  Fix for dead agent pools (RejectedExecutionException "... [Terminated ...]")
;; ==========================================================================
(defn revive-agents!
  "Re-create Clojure's agent executors after shadow-cljs (or anything else)
  has called `shutdown-agents` in this long-lived dev JVM.

  shadow-cljs's server shutdown path (remote-stop! / -main after
  wait-for-stop!) calls `shutdown-agents`, which permanently terminates
  clojure.lang.Agent/soloExecutor and /pooledExecutor. After that, `future`,
  `pmap`, `clojure.java.shell/sh`, etc. fail with RejectedExecutionException
  until the JVM is restarted — or until you call this fn, which swaps in
  fresh executors via reflection (the fields are volatile public static).

  Call this after any \"... [Terminated, pool size = 0 ...]\" error."
  []
  (let [solo   (.getField clojure.lang.Agent "soloExecutor")
        pooled (.getField clojure.lang.Agent "pooledExecutor")]
    (.set solo nil (java.util.concurrent.Executors/newCachedThreadPool))
    (.set pooled nil (java.util.concurrent.Executors/newFixedThreadPool
                      (+ 2 (.availableProcessors (Runtime/getRuntime)))))
    (println "agent executors revived:"
             (str clojure.lang.Agent/soloExecutor))))

;; ==========================================================================
;;  Automatically start the core services for local development
;; ==========================================================================
(integrant.repl/set-prep!
 (fn []
   (ig/load-namespaces main/config)
   main/config))
(integrant.repl/go)
(shadow.cljs.devtools.server/start!)
(shadow/watch :app)

;; ==========================================================================
;;  Manage the system interactively
;; ==========================================================================
(comment
  (integrant.repl/go)
  (integrant.repl/halt)
  (integrant.repl/reset)

  ;; Inspect the database connection pool from the current system
  (:climate-changed/datasource state/system)

  ;; ClojureScript Build process
  (shadow/watch :app)
  (open-browser "http://localhost:8081")

  ;; current namespace info, hack
  (symbol (namespace ::x))
  (->> (all-ns)
       (map ns-name)
       (filter #(str/starts-with? %1 "climate-changed"))
       (sort))

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
  ;; After editing deps.edn, call:
  (require 'clojure.repl.deps)
  (clojure.repl.deps/sync-deps) ; reads deps.edn and hot-loads any new/changed deps
  ;; CLJS deps require a reload

  ;; If `future`/`sh` throw RejectedExecutionException "... [Terminated ...]",
  ;; shadow-cljs's shutdown path called (shutdown-agents). Revive the pools:
  (revive-agents!)

  ;;
  )


