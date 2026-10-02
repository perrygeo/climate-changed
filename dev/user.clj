(ns user
  (:require
   [climate-changed.backend.main :as main]
   [clojure.string :as str]
   [integrant.core :as ig]
   [integrant.repl]
   [integrant.repl.state :as state]
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server])
  (:import
   (java.io File)))

;; ==========================================================================
;;  Utility fns
;; ==========================================================================
(defn- open-browser
  "Cross-platform: opens `url` in the default browser. Fire-and-forget via
  ProcessBuilder rather than clojure.java.shell/sh, because sh uses `future`
  internally and fails with RejectedExecutionException once the agent thread
  pool has been shut down."
  [url]
  (let [os        (System/getProperty "os.name")
        cmd       (cond
                    (str/starts-with? os "Mac") ["open" url]
                    (str/starts-with? os "Win") ["cmd" "/c" "start" url]
                    :else                       ["xdg-open" url])
        null-file (File. (if (str/starts-with? os "Win") "NUL" "/dev/null"))]
    (.start (doto (ProcessBuilder. ^java.util.List cmd)
              (.redirectOutput null-file)
              (.redirectError null-file)))
    nil))

;; ==========================================================================
;;  Automatically start the core services for local development
;; ==========================================================================
(integrant.repl/set-prep!
 (fn []
   (ig/load-namespaces main/config)
   main/config))

(defonce ^:clj-reload/keep dev-auto-started?
  (when (nil? state/system)
    (integrant.repl/go)
    (shadow.cljs.devtools.server/start!)
    (shadow/watch :app) ; :already-watching
    (println "✅ dev server: 'http://localhost:8081'")
    true))

(comment ;; should always be true, set at first startup only
  dev-auto-started?)

;; ==========================================================================
;;  Manage the system interactively
;; ==========================================================================
(comment
  (integrant.repl/halt)
  (integrant.repl/reset)

  (open-browser "http://localhost:8081")  ;; Ctl-Shift-R to hard reload

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
  (+ 1 1) ;; if No available JS runtime, see "important" above
  (js/alert "Hello from the REPL") ;; cljs only
  :cljs/quit
  ;;
  ;; 3. Switch sessions to get back and forth between clj and cljs
  ;; using NeoVim and Conjure - <leader>ss
  ;;
  ;; After editing deps.edn, call:
  (require 'clojure.repl.deps)
  (clojure.repl.deps/sync-deps) ; reads deps.edn and hot-loads any new/changed deps

  ;; CLJS deps require a reload
  ;;
  )
