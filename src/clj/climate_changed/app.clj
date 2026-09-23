(ns climate-changed.app
  (:require
   [bidi.ring :refer [make-handler]]
   [climate-changed.handlers :as h]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.not-modified :refer [wrap-not-modified]]
   [ring.middleware.params :refer [wrap-params]]))

(set! *warn-on-reflection* true)

(def routes
  ["/" [["" #'h/index-handler]
        ["healthz" #'h/healthz-handler]
        ["api/hello" #'h/hello-handler]]])

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

(def bidi-handler
  (make-handler routes))

(defn bidi-or-resources
  "Try bidi routes first; fall through to static resource serving."
  [request]
  (or (bidi-handler request)
      (h/resources-handler request)))

(def app-handler
  (-> bidi-or-resources
      wrap-edn-response
      wrap-params
      wrap-content-type
      wrap-not-modified))
