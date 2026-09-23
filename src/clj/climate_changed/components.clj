(ns climate-changed.components
  (:require
   [climate-changed.app :refer [app-handler]]
   [integrant.core :as ig]
   [next.jdbc :as jdbc]
   [ring.adapter.jetty9 :as jetty]))

(set! *warn-on-reflection* true)

(defmethod ig/init-key :climate-changed/datasource
  [_ {:keys [jdbc-url]}]
  (println "Initialize JDBC datasource")
  (jdbc/get-datasource {:jdbcUrl jdbc-url}))

(defmethod ig/halt-key! :climate-changed/datasource
  [_ ds]
  (println "Halt database connection pool")
  (when (instance? java.io.Closeable ds)
    (.close ^java.io.Closeable ds)))

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
