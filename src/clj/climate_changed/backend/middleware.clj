(ns climate-changed.backend.middleware
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ring.middleware.ssl :refer [wrap-hsts]]
   [ring.middleware.x-headers :refer [wrap-content-type-options wrap-frame-options]])
  (:import
   [java.io ByteArrayOutputStream File InputStream]
   [java.nio.charset StandardCharsets]
   [java.util.zip GZIPOutputStream]))

(set! *warn-on-reflection* true)

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

(def ^:private byte-array-class (Class/forName "[B"))
(def ^:private min-gzip-size 512)

(defn- accepts-gzip? [request]
  (some-> (get-in request [:headers "accept-encoding"])
          (str/lower-case)
          (str/includes? "gzip")))

(defn- gzipable-body? [body]
  (or (string? body)
      (instance? InputStream body)
      (instance? File body)
      (instance? byte-array-class body)))

(defn- body-size [body]
  (cond
    (string? body) (.length ^String body)
    (instance? File body) (.length ^File body)
    (instance? byte-array-class body) (alength ^bytes body)
    :else nil))

(defn- gzip-bytes ^bytes [body]
  (let [out (ByteArrayOutputStream.)]
    (with-open [gz (GZIPOutputStream. out)]
      (cond
        (string? body)
        (.write gz (.getBytes ^String body StandardCharsets/UTF_8))

        (instance? InputStream body)
        (io/copy ^InputStream body gz)

        (instance? File body)
        (with-open [in (io/input-stream ^File body)]
          (io/copy in gz))

        :else
        (.write gz ^bytes body)))
    (.toByteArray out)))

(defn- vary-accept-encoding [headers]
  (let [vary (get headers "Vary")]
    (cond
      (and vary (str/includes? (str/lower-case vary) "accept-encoding"))
      headers

      vary
      (assoc headers "Vary" (str vary ", Accept-Encoding"))

      :else
      (assoc headers "Vary" "Accept-Encoding"))))

(defn- gzip-response [{:keys [body] :as response}]
  (-> response
      (assoc :body (gzip-bytes body))
      (assoc-in [:headers "Content-Encoding"] "gzip")
      (update :headers vary-accept-encoding)
      (update :headers dissoc "Content-Length" "content-length")))

(defn wrap-gzip-middleware
  "Compress responses with gzip when the client accepts it via the
  Accept-Encoding header. Skips non-200 responses, HEAD requests, bodies that
  are already encoded, and bodies too small to benefit from compression."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (and (accepts-gzip? request)
               (not= :head (:request-method request))
               (= 200 (:status response))
               (nil? (get-in response [:headers "Content-Encoding"]))
               (nil? (get-in response [:headers "content-encoding"]))
               (gzipable-body? (:body response))
               (let [n (body-size (:body response))]
                 (or (nil? n) (> n min-gzip-size))))
        (gzip-response response)
        response))))

(defn wrap-referrer-policy
  "Adds a `Referrer-Policy` header. No Ring built-in exists for this one.
  `strict-origin-when-cross-origin` is the modern browser default and keeps
  the full path only on same-origin requests."
  [handler]
  (fn [request]
    (some-> (handler request)
            (assoc-in [:headers "Referrer-Policy"] "strict-origin-when-cross-origin"))))

(defn wrap-security-headers
  "Adds baseline security headers to every response.

  Deliberately does NOT set a Content-Security-Policy: the app is public and
  read-only, and a restrictive CSP would break shadow-cljs dev/hot-reload and
  require allowlisting MapLibre's blob workers plus external tile/glyph/sprite
  hosts. That is a separate, opt-in hardening task.

  Sets:

  - `Strict-Transport-Security` (1 year, includeSubDomains)
  - `X-Frame-Options: DENY` (the non-CSP equivalent of `frame-ancestors 'none'`)
  - `X-Content-Type-Options: nosniff`
  - `Referrer-Policy: strict-origin-when-cross-origin`"
  [handler]
  (-> handler
      (wrap-hsts {:max-age 31536000 :include-subdomains? true})
      (wrap-frame-options :deny)
      (wrap-content-type-options :nosniff)
      wrap-referrer-policy))
