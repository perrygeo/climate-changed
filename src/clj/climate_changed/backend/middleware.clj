(ns climate-changed.backend.middleware
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
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

