(ns climate-changed.backend.server
  (:require
   [bidi.ring :refer [make-handler]]
   [climate-changed.backend.handlers :as h]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.not-modified :refer [wrap-not-modified]]
   [ring.middleware.params :refer [wrap-params]]))

(set! *warn-on-reflection* true)

(defn routes
  "bidi routes; handlers that need the database close over the datasource."
  [datasource]
  ["/" [["" #'h/index-handler]
        ["healthz" #'h/healthz-handler]
        ["api/hello" #'h/hello-handler]
        [["api/era5-summary/" :row "/" :col] #'h/era5-summary-handler]
        ["api/locations" (h/locations-handler datasource)]]])

(defn routes-or-resources
  "Try bidi routes first; fall through to static resource serving."
  [datasource]
  (let [route-handler (make-handler (routes datasource))]
    (fn [request]
      (or (route-handler request)
          (h/resources-handler request)))))

(defn wrap-edn-response
  "Middleware that serialises Clojure collection body to EDN
  and sets the Content-Type header to application/edn.
  Leaves string/stream bodies untouched."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (and (coll? (:body response))
               (not (string? (:body response))))
        (-> response
            (assoc :body (pr-str (:body response)))
            (assoc-in [:headers "Content-Type"] "application/edn"))
        response))))

(defn app-handler
  "The ring middleware stack around the routes; `datasource` is handed to
  any handler that needs the database."
  [datasource]
  (-> (routes-or-resources datasource)
      wrap-edn-response
      wrap-params
      wrap-content-type
      wrap-not-modified))
