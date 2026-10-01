(ns climate-changed.backend.handlers
  (:require
   [cheshire.core :as json]
   [climate-changed.common :as s]
   [climate-changed.era5.summary :as summary]
   [clojure.java.io :as io]
   [garden.core :as garden]
   [markdown.core :as md]
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
