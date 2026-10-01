(ns climate-changed.backend.handlers
  (:require
   [cheshire.core :as json]
   [climate-changed.common :as s]
   [climate-changed.era5.summary :as summary]
   [clojure.java.io :as io]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.util.response :as resp]))

(set! *warn-on-reflection* true)

(defn healthz-handler [_]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    "\"ok\""})

(defn locations-handler
  "GeoJSON endpoint: every location, served as RFC 7946 text from a bundled
  Natural Earth resource."
  [_req]
  {:status  200
   :headers {"Content-Type" "application/geo+json"}
   :body    (slurp (io/resource "ne_50m_populated_places_simple.geojson"))})

(defn hello-handler
  [_req]
  {:status 200
   :body   {:message (str "Hello from " s/appname "!")}})

(defn index-handler
  "serve the SPA entry point."
  [_req]
  (-> (resp/resource-response "index.html" {:root "public"})
      (resp/content-type "text/html")))

(def resources-handler
  "serve files from resources/public, 404 if not found."
  (wrap-resource (constantly (resp/not-found "Not found")) "public"))

(defn- ->long
  "Parse a path-param string to a long, or nil when it isn't an integer."
  [s]
  (try (Long/parseLong s)
       (catch NumberFormatException _ nil)))

(def default-varname "t2m")

(defn era5-summary-handler
  "ERA5 climate summary for a grid cell.
  Default var for now, returns its descriptive statistics as JSON."
  [{:keys [route-params]}]
  (let [row (->long (:row route-params))
        col (->long (:col route-params))]
    (if (and row col)
      (let [summary (summary/era5-summary default-varname row col)]
        {:status  200
         :headers {"Content-Type" "application/json"}
         :body    (json/generate-string summary)})
      {:status  400
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string {:error "row and col must be integers"})})))
