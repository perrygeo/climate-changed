(ns climate-changed.backend.handlers
  (:require
   [cheshire.core :as json]
   [climate-changed.common :as s]
   [climate-changed.era5.summary :as summary]
   [climate-changed.models.locations :as db]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.util.response :as resp]))

(set! *warn-on-reflection* true)

(defn healthz-handler [_]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    "\"ok\""})

(defn locations-handler
  "GeoJSON endpoint: every location in the database, serialised to
  RFC 7946 text. The datasource is captured at system init."
  [ds]
  (fn [_req]
    {:status  200
     :headers {"Content-Type" "application/geo+json"}
     :body    (json/generate-string (db/locations-feature-collection ds))}))

(defn hello-handler
  "Demo API endpoint."
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
