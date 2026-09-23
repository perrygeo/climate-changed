(ns climate-changed.main
  (:require
   [climate-changed.components]
   [climate-changed.shared :as shared]
   [integrant.core :as ig])
  (:gen-class))

(set! *warn-on-reflection* true)

(def config
  (let [jdbc-url (or (System/getenv "DATABASE_URL")
                     "jdbc:postgresql://localhost:5432/main?user=postgres&password=password")]
    {:climate-changed/datasource {:jdbc-url jdbc-url}
     :climate-changed/handler    {:datasource (ig/ref :climate-changed/datasource)}
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
