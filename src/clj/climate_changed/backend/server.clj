(ns climate-changed.backend.server
  (:require
   [bidi.ring :refer [make-handler]]
   [climate-changed.backend.handlers :as h]
   [climate-changed.backend.middleware :refer [wrap-edn-response wrap-gzip-middleware]]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.not-modified :refer [wrap-not-modified]]
   [ring.middleware.params :refer [wrap-params]]))

(set! *warn-on-reflection* true)

(def routes
  "bidi routes."
  ["/" [["" #'h/home-handler]
        ["map" #'h/spa-handler]
        ["healthz" #'h/healthz-handler]
        ["api/hello" #'h/hello-handler]
        [["api/era5-summary/" :row "/" :col] #'h/era5-summary-handler]
        ["api/locations" #'h/locations-handler]]])

(defn routes-or-resources
  "Try bidi routes first; fall through to static resource serving."
  []
  (let [route-handler (make-handler routes)]
    (fn [request]
      (or (route-handler request)
          (h/resources-handler request)))))

(defn app-handler
  "The ring middleware stack around the routes."
  []
  (-> (routes-or-resources)
      wrap-edn-response
      wrap-params
      wrap-content-type
      wrap-not-modified
      wrap-gzip-middleware))
