(ns climate-changed.backend.components
  (:require
   [climate-changed.backend.location-index :as location-index]
   [climate-changed.backend.server :as server]
   [climate-changed.era5.duckdb :as duckdb]
   [integrant.core :as ig]
   [ring.adapter.jetty9 :as jetty])
  (:import [org.slf4j LoggerFactory]))

(set! *warn-on-reflection* true)

(def ^:private ^org.slf4j.Logger logger
  (LoggerFactory/getLogger "climate-changed.backend.components"))

(defmethod ig/init-key :climate-changed/duckdb
  [_ opts]
  (.debug logger "Initializing sandboxed DuckDB")
  (duckdb/init! opts)
  ::running)

(defmethod ig/halt-key! :climate-changed/duckdb
  [_ _]
  (.debug logger "Shutting down DuckDB")
  (duckdb/shutdown!))

(defmethod ig/init-key :climate-changed/location-index
  [_ _opts]
  (let [n (location-index/init!)]
    (.debug logger (str "Built location spatial index of " n " places"))
    n))

(defmethod ig/init-key :climate-changed/backfill-worker
  [_ _opts]
  (.debug logger "Starting location backfill worker")
  (location-index/start-worker!)
  ::running)

(defmethod ig/halt-key! :climate-changed/backfill-worker
  [_ _worker]
  (.debug logger "Stopping location backfill worker")
  (location-index/stop-worker!))

(defmethod ig/init-key :climate-changed/server
  [_ {:keys [handler port]}]
  (.debug logger (str "Starting Jetty HTTP server on port " port))
  (jetty/run-jetty handler {:port port :join? false :send-server-version? false}))

(defmethod ig/halt-key! :climate-changed/server
  [_ server]
  (.debug logger "Stopping Jetty HTTP server")
  (.stop ^org.eclipse.jetty.server.Server server))

(defmethod ig/init-key :climate-changed/handler
  [_ _opts]
  (server/app-handler))
