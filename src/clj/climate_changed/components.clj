(ns climate-changed.components
  (:require
   [climate-changed.app :refer [app-handler]]
   [integrant.core :as ig]
   [migratus.core :as migratus]
   [next.jdbc.connection :as jdbc-conn]
   [ring.adapter.jetty9 :as jetty])
  (:import
   (com.zaxxer.hikari HikariDataSource)))

(set! *warn-on-reflection* true)

(defmethod ig/init-key :climate-changed/datasource
  [_ {:keys [jdbc-url hikari]}]
  (println "Initialize HikariCP connection pool")
  ;; HikariCP is on the classpath, so next.jdbc's ->pool builds a pooled
  ;; datasource from it. Extra pool settings can be passed as the :hikari
  ;; map (camelCase Hikari property names, e.g. :maximumPoolSize).
  (jdbc-conn/->pool HikariDataSource
                    (merge {:jdbcUrl  jdbc-url
                            :poolName "climate-changed"}
                           hikari)))

(defmethod ig/halt-key! :climate-changed/datasource
  [_ ds]
  (println "Halt database connection pool")
  (when (instance? java.io.Closeable ds)
    (.close ^java.io.Closeable ds)))

(defmethod ig/init-key :climate-changed/migrations
  [_ {:keys [datasource migration-dir]}]
  (println "Run database migrations")
  (let [config {:store        :database
                :db           {:datasource datasource}
                :migration-dir (or migration-dir "migrations")}]
    ;; Migratus returns :failure (rather than throwing) when a migration
    ;; fails, so convert that into an exception to abort system startup.
    (case (migratus/migrate config)
      :failure (throw (ex-info "Database migrations failed" config))
      ;; Hand the config back so the REPL can call e.g.
      ;; (migratus.core/rollback (:climate-changed/migrations system)).
      config)))

(defmethod ig/halt-key! :climate-changed/migrations
  [_ _config]
  ;; Nothing to close; migrations only run once at startup.
  nil)

(defmethod ig/init-key :climate-changed/server
  [_ {:keys [handler port]}]
  (println "Starting Jetty HTTP server on port" port)
  (jetty/run-jetty handler {:port port :join? false}))

(defmethod ig/halt-key! :climate-changed/server
  [_ server]
  (println "Halt Jetty HTTP server")
  (.stop ^org.eclipse.jetty.server.Server server))

(defmethod ig/init-key :climate-changed/handler
  ;; Handlers that need the database can destructure it from `opts`; the
  ;; reference below keeps the datasource initialized as part of the system.
  [_ _opts]
  app-handler)
