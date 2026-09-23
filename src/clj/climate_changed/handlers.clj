(ns climate-changed.handlers
  (:require
   [climate-changed.shared :as s]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.util.response :as resp]))

(set! *warn-on-reflection* true)

(defn healthz-handler [_]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    "\"ok\""})

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
