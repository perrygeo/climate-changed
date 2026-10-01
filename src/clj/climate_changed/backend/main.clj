(ns climate-changed.backend.main
  (:require
   [climate-changed.backend.components]
   [climate-changed.common :as common]
   [integrant.core :as ig])
  (:gen-class))

(set! *warn-on-reflection* true)

(def config
  {:climate-changed/location-index {}
   :climate-changed/handler         {:location-index (ig/ref :climate-changed/location-index)}
   :climate-changed/server          {:port    (Integer/parseInt (or (System/getenv "PORT") "8081"))
                                     :handler (ig/ref :climate-changed/handler)}})

(defn -main
  "Start the server and block the main thread."
  [& _args]
  (let [system (ig/init config)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (when system  (ig/halt! system)))))
    (println (str common/appname " started on http://localhost:" (:port (:climate-changed/server config))))
    @(promise)))
