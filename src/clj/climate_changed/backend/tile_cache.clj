(ns climate-changed.backend.tile-cache
  (:import
   [java.net URI]
   [java.net.http HttpClient HttpRequest]
   [java.time Duration]))

(def ^:private s2cloudless-tile-url
  "Upstream tile URL template for the EOX Sentinel-2 cloudless WMTS. Note the
  z/y/x ordering (WMTS row/column order, unlike the x/y/z of XYZ tiles)."
  "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/%d/%d/%d.jpg")

;; In-memory cache of proxied Sentinel-2 tiles, keyed by [z y x].
(defonce tile-cache (atom {}))

(def ^:private tile-cache-max-entries 4096)

(def ^:private tile-cache-ttl-ms
  "Tiles are immutable, so cache them for a week in-process; CloudFront may
  add another cache layer in front in production."
  (* 7 24 60 60 1000))

(def s2cloudless-max-zoom 18)

(defn cache-get
  "Return cached tile bytes for key `k`, or nil when absent or expired."
  [k]
  (when-let [{:keys [expires-at bytes]} (get @tile-cache k)]
    (when (< (System/currentTimeMillis) expires-at)
      bytes)))

(defn cache-evict
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

(defn cache-put!
  "Store `bytes` under key `k`, evicting expired entries and trimming the
  least-recently-cached entries when the cache exceeds `tile-cache-max-entries`."
  [k bytes]
  (swap! tile-cache
         (fn [cache]
           (let [now   (System/currentTimeMillis)
                 cache (assoc cache k {:bytes      bytes
                                       :cached-at  now
                                       :expires-at (+ now tile-cache-ttl-ms)})]
             (if (> (count cache) tile-cache-max-entries)
               (cache-evict cache)
               cache)))))

(def ^:private http-client
  (delay (HttpClient/newHttpClient)))

;; === Public API ===

(defn fetch-tile
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

(defn current-cache-size-mb
  "Rough RAM footprint of the in-memory tile cache in MiB: the sum of the
  byte-array lengths of all cached tiles, ignoring per-entry map overhead."
  []
  (let [total-bytes (reduce + 0 (map (fn [^bytes b] (alength b))
                                     (keep :bytes (vals @tile-cache))))]
    (double (/ total-bytes 1048576.0))))

(comment
  (current-cache-size-mb))
