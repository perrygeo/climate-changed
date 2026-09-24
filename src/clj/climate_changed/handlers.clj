(ns climate-changed.handlers
  (:require
   [cheshire.core :as json]
   [climate-changed.db :as db]
   [climate-changed.shared :as s]
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
