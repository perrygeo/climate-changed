(ns climate-changed.backend.main
  (:require
   [climate-changed.backend.components]
   [climate-changed.common :as shared]
   [clojure.string :as str]
   [integrant.core :as ig])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn- database-url->jdbc-url
  "Convert a libpq/Heroku-style connection URI (e.g.
  postgresql://user:password@host:5432/db) into a JDBC URL
  (jdbc:postgresql://host:5432/db?user=user&password=password)."
  [url]
  (let [uri            (java.net.URI. url)
        scheme         (if (= "postgres" (.getScheme uri)) "postgresql" (.getScheme uri))
        [user password] (some-> (.getRawUserInfo uri) (str/split #":" 2))
        base           (str "jdbc:" scheme "://" (.getHost uri)
                            (when-not (neg? (.getPort uri)) (str ":" (.getPort uri)))
                            (.getPath uri))
        params         (cond-> []
                         user     (conj (str "user=" user))
                         password (conj (str "password=" password)))
        query          (.getQuery uri)]
    (cond-> base
      (or (seq params) query)
      (str "?" (str/join "&" (cond-> params query (conj query)))))))

(defn- jdbc-url
  "JDBC URL from DATABASE_URL. Accepts both proper JDBC URLs and
  libpq-style URIs without the `jdbc:` prefix."
  []
  (let [url (or (System/getenv "DATABASE_URL")
                "jdbc:postgresql://localhost:5432/main?user=postgres&password=password")]
    (if (str/starts-with? url "jdbc:")
      url
      (database-url->jdbc-url url))))

(def config
  (let [jdbc-url (jdbc-url)]
    {:climate-changed/datasource {:jdbc-url jdbc-url}
     ;; src/sql is on the classpath (see deps.edn :paths), so migrations
     ;; are resources at "migrations/" and ship inside the uberjar too.
     :climate-changed/migrations {:datasource    (ig/ref :climate-changed/datasource)
                                  :migration-dir "migrations"}
     ;; The :migrations ref ensures migrations run before the handler is
     ;; built, so the server never serves requests against a stale schema.
     :climate-changed/handler    {:datasource (ig/ref :climate-changed/datasource)
                                  :migrations (ig/ref :climate-changed/migrations)}
     :climate-changed/server     {:port    (Integer/parseInt (or (System/getenv "PORT") "8081"))
                                  :handler (ig/ref :climate-changed/handler)}}))

(defn -main
  "Start the server and block the main thread."
  [& _args]
  (let [system (ig/init config)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (when system  (ig/halt! system)))))
    (println (str shared/appname " started on http://localhost:" (:port (:climate-changed/server config))))
    @(promise)))
