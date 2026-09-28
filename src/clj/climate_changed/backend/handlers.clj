(ns climate-changed.backend.handlers
  (:require
   [cheshire.core :as json]
   [climate-changed.common :as s]
   [climate-changed.era5.fetch :as fetch]
   [climate-changed.era5.grid :as grid]
   [climate-changed.era5.variables :as vars]
   [climate-changed.models.locations :as db]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.util.response :as resp]
   [tech.v3.dataset :as ds]))

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

(defn- descriptive-stats-row
  "The `ds/descriptive-stats` row for `col-name` as a plain map, or nil."
  [data col-name]
  (some #(when (= (:col-name %) col-name) %)
        (ds/rows (ds/descriptive-stats data))))

(def default-varname "t2m")

(defn era5-summary-handler
  "ERA5 climate summary for a grid cell. Reads :row and :col from the
  request's :route-params, fetches the hourly timeseries for the default
  variable (t2m), and returns its descriptive statistics as JSON."
  [{:keys [route-params]}]
  (let [row (->long (:row route-params))
        col (->long (:col route-params))]
    (if (and row col)
      (let [varname           default-varname
            data              (fetch/fetch-ts varname row col)
            vstats            (descriptive-stats-row data varname)
            tstats            (descriptive-stats-row data "valid_time")
            {:keys [lat lon]} (grid/cell-center row col)]
        {:status  200
         :headers {"Content-Type" "application/json"}
         :body    (json/generate-string
                   {:row   row
                    :col   col
                    :lat   lat
                    :lon   lon
                    :var   varname
                    :units (get-in vars/era5-variables [(keyword varname) :units])
                    :n     (:n-valid vstats)
                    :min   (:min vstats)
                    :mean  (:mean vstats)
                    :max   (:max vstats)
                    :sd    (:standard-deviation vstats)
                    :start (some-> (:min tstats) str)
                    :end   (some-> (:max tstats) str)})})
      {:status  400
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string {:error "row and col must be integers"})})))
