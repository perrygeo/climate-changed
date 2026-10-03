(ns climate-changed.backend.server
  (:require
   [bidi.ring :refer [make-handler]]
   [climate-changed.backend.handlers :as h]
   [climate-changed.backend.middleware :refer [wrap-edn-response wrap-gzip-middleware wrap-request-logging wrap-security-headers]]
   [climate-changed.routes :as routes]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.not-modified :refer [wrap-not-modified]]
   [ring.middleware.params :refer [wrap-params]]))

(set! *warn-on-reflection* true)

(def handler-fn
  "Resolve a bidi route name to its Ring handler var."
  {:home               #'h/home-handler
   :map                #'h/spa-handler
   :location-stats     #'h/location-stats-handler
   :healthz            #'h/healthz-handler
   :era5-summary       #'h/era5-summary-handler
   :tiles-s2cloudless  #'h/tiles-s2cloudless-handler
   :locations          #'h/locations-handler
   :method-not-allowed #'h/method-not-allowed-handler})

(defn routes-or-resources
  "Try routes first; fall through to static resource serving."
  []
  (let [route-handler (make-handler routes/routes handler-fn)]
    (fn [request]
      (or (route-handler request)
          (h/resources-handler request)))))

(defn app-handler
  "The ring middleware stack around the routes."
  []
  (-> (routes-or-resources)
      wrap-security-headers
      wrap-edn-response
      wrap-params
      wrap-content-type
      wrap-not-modified
      wrap-gzip-middleware
      wrap-request-logging))
