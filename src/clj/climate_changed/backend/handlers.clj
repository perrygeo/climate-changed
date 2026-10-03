(ns climate-changed.backend.handlers
  (:require
   [cheshire.core :as json]
   [climate-changed.backend.location-index :as loc]
   [climate-changed.common :as s]
   [climate-changed.era5.grid :as grid]
   [climate-changed.era5.summary :as summary]
   [climate-changed.era5.variables :as vars]
   [clojure.java.io :as io]
   [garden.core :as garden]
   [markdown.core :as md]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.util.response :as resp])
  (:import
   [java.net URI]
   [java.net.http HttpClient HttpRequest]
   [java.time Duration]))

(set! *warn-on-reflection* true)

(defn healthz-handler [_]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    "\"ok\""})

(defn method-not-allowed-handler
  "Respond 405 for paths that match a route but use a disallowed method."
  [_req]
  {:status  405
   :headers {"Content-Type" "application/json"
             "Allow"        "GET"}
   :body    (json/generate-string {:error "method not allowed"})})

(def geojson-payload
  (slurp (io/resource "ne_50m_populated_places_simple.geojson")))

(defn locations-handler
  "GeoJSON endpoint: every location, served as RFC 7946 text from a bundled
  Natural Earth resource."
  [_req]
  {:status  200
   :headers {"Content-Type" "application/geo+json"}
   :body    geojson-payload})

(defn location-stats-handler
  "Location fetch statistics: how many indexed locations have a cached ERA5
  timeseries, and what fraction that is of the total index."
  [_req]
  (let [n         (loc/n-locations)
        n-with-ts (loc/n-locations-with-ts)
        pct       (when (pos? n) (double (/ n-with-ts n)))]
    {:status 200
     :body   {:n        n
              :complete n-with-ts
              :pct      pct}}))

(defn spa-handler
  "Serve the map SPA entry point (index.html) at /map."
  [_req]
  (-> (resp/resource-response "index.html" {:root "public"})
      (resp/content-type "text/html")))

(defn- compile-shared-styles
  "Compile climate-changed.common/default-style to CSS. This is the same
  stylesheet the SPA injects at runtime, so the home page and the app share
  one source of styling truth."
  []
  (garden/css s/default-style))

(defn home-handler
  "Serve the hand-written markdown introduction at /, rendered into a full
  HTML page that inlines the shared stylesheet. The SPA itself lives at /map."
  [_req]
  (let [body-html (-> (io/resource "content/home.md")
                      (slurp)
                      (md/md-to-html-string))]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body    (str "<!DOCTYPE html>\n"
                   "<html lang=\"en\">\n"
                   "  <head>\n"
                   "    <meta charset=\"UTF-8\" />\n"
                   "    <title>" s/appname "</title>\n"
                   "    <link rel=\"icon\" type=\"image/svg+xml\" href=\"/favicon.svg\" />\n"
                   "    <style>" (compile-shared-styles) "</style>\n"
                   "  </head>\n"
                   "  <body class=\"home\">\n"
                   "    <header class=\"app-header\"><a class=\"app-title-link\" href=\"/\"><h1 class=\"app-title\">" s/appname "</h1></a></header>\n"
                   "    <main class=\"home-content\">\n"
                   body-html
                   "\n    </main>\n"
                   "  </body>\n"
                   "</html>\n")}))

(def resources-handler
  "serve files from resources/public, 404 if not found."
  (wrap-resource (constantly (resp/not-found "Not found")) "public"))

(defn- ->long
  "Parse a path-param string to a long, or nil when it isn't an integer."
  [s]
  (try (Long/parseLong s)
       (catch NumberFormatException _ nil)))

(defn era5-summary-handler
  "ERA5 climate summary for a grid cell.
  Default var for now, returns its descriptive statistics as JSON.

  The cell's bounding box is validated against the location spatial index:
  cells with no nearby locations are rejected with a 500 so arbitrary
  row/col requests can't fill the ever-growing era_ts cache. If the ERA5
  fetch is already in progress (or too many are running), returns 423."
  [{:keys [route-params]}]
  (let [row (->long (:row route-params))
        col (->long (:col route-params))]
    (if (and row col)
      (let [bbox (grid/cell-bbox row col)]
        (if (seq (loc/query bbox))
          (try
            (let [summary (summary/era5-summary vars/default-varname row col)]
              {:status  200
               :headers {"Content-Type" "application/json"}
               :body    (json/generate-string summary)})
            (catch clojure.lang.ExceptionInfo e
              (if-let [status (:status (ex-data e))]
                {:status  status
                 :headers {"Content-Type" "application/json"}
                 :body    (json/generate-string {:error (ex-message e)})}
                (throw e))))
          {:status  404
           :headers {"Content-Type" "application/json"}
           :body    (json/generate-string {:error "no locations in grid cell"})}))
      {:status  400
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string {:error "row and col must be integers"})})))

(comment
  (era5-summary-handler {:route-params {:row "10" :col "10"}})
  ;; {:status 500,
  ;;  :headers {"Content-Type" "application/json"},
  ;;  :body "{\"error\":\"no locations in grid cell\"}"}

  (era5-summary-handler {:route-params {:row "201" :col "1020"}})
  ;; {:status 200,
  ;;  :headers {"Content-Type" "application/json"},
  ;;  :body "..."}
  )

(def ^:private s2cloudless-tile-url
  "Upstream tile URL template for the EOX Sentinel-2 cloudless WMTS. Note the
  z/y/x ordering (WMTS row/column order, unlike the x/y/z of XYZ tiles)."
  "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/%d/%d/%d.jpg")

(def ^:private s2cloudless-max-zoom 18)

(defonce ^:private tile-cache
  ;; In-memory cache of proxied Sentinel-2 tiles, keyed by [z y x].
  (atom {}))

(def ^:private tile-cache-max-entries 4096)

(def ^:private tile-cache-ttl-ms
  "Tiles are immutable, so cache them for a week in-process; CloudFront may
  add another cache layer in front in production."
  (* 7 24 60 60 1000))

(defn- cache-get
  "Return cached tile bytes for key `k`, or nil when absent or expired."
  [k]
  (when-let [{:keys [expires-at bytes]} (get @tile-cache k)]
    (when (< (System/currentTimeMillis) expires-at)
      bytes)))

(defn- cache-evict
  "Trim `cache` to at most `tile-cache-max-entries`, dropping expired entries
  first, then the least-recently-cached."
  [cache]
  (let [now  (System/currentTimeMillis)
        live (into {} (remove (fn [[_ {:keys [expires-at]}]] (<= expires-at now)))
                   cache)]
    (if (<= (count live) tile-cache-max-entries)
      live
      (let [keep (->> live
                      (sort-by (comp :cached-at val))
                      (take-last tile-cache-max-entries)
                      (map key)
                      set)]
        (select-keys live keep)))))

(defn- cache-put!
  "Store `bytes` under key `k`, evicting old entries when at capacity."
  [k bytes]
  (swap! tile-cache
         (fn [cache]
           (let [now   (System/currentTimeMillis)
                 cache (if (>= (count cache) tile-cache-max-entries)
                         (cache-evict cache)
                         cache)]
             (assoc cache k {:bytes      bytes
                             :cached-at  now
                             :expires-at (+ now tile-cache-ttl-ms)})))))

(def ^:private http-client
  (delay (HttpClient/newHttpClient)))

(defn- fetch-tile
  "Fetch the EOX Sentinel-2 tile at [z y x], returning its bytes or nil when
  upstream responds non-200 or the request fails."
  ^bytes [z y x]
  (try
    (let [url  (format s2cloudless-tile-url (long z) (long y) (long x))
          req  (-> (HttpRequest/newBuilder (URI/create url))
                   (.timeout (Duration/ofSeconds 30))
                   (.header "User-Agent" "climate-changed")
                   (.build))
          resp (.send ^HttpClient @http-client req (java.net.http.HttpResponse$BodyHandlers/ofByteArray))]
      (when (= 200 (.statusCode resp))
        (.body resp)))
    (catch Exception _ nil)))

(defn tiles-s2cloudless-handler
  "Proxy Sentinel-2 cloudless satellite tiles from EOX through the
  `:tiles-s2cloudless` route, with an in-memory cache. Validates z/y/x so
  arbitrary paths can't fan out requests to the upstream tile server."
  [{:keys [route-params]}]
  (let [z       (->long (:z route-params))
        y       (->long (:y route-params))
        x       (->long (:x route-params))
        n-tiles (when (and z y x) (bit-shift-left 1 z))]
    (cond
      (or (nil? z) (nil? y) (nil? x))
      {:status  400
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string {:error "z, y, and x must be integers"})}

      (or (neg? z) (> z s2cloudless-max-zoom)
          (neg? y) (>= y n-tiles)
          (neg? x) (>= x n-tiles))
      {:status  404
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string {:error "tile out of range"})}

      :else
      (let [k     [z y x]
            bytes (or (cache-get k)
                      (when-let [b (fetch-tile z y x)]
                        (cache-put! k b)
                        b))]
        (if bytes
          {:status  200
           :headers {"Content-Type"  "image/jpeg"
                     "Cache-Control" "public, max-age=604800"}
           :body    bytes}
          {:status  502
           :headers {"Content-Type" "application/json"}
           :body    (json/generate-string {:error "upstream tile unavailable"})})))))
