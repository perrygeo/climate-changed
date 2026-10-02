(ns climate-changed.backend.location-index
  "Builds and holds the in-memory R-tree spatial index of Natural Earth
  locations, used to validate ERA5 grid-cell lookups."
  (:require
   [cheshire.core :as json]
   [climate-changed.era5.fetch :as fetch]
   [climate-changed.era5.grid :as grid]
   [climate-changed.era5.variables :as vars]
   [climate-changed.spatial-index :as spatial]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.logging :as log]))

(set! *warn-on-reflection* true)

(defonce index (atom nil))

(defonce locations (atom []))

;; Set of [varname row col] tuples for indexed grid cells whose ERA5
;; timeseries has not been fetched into the era_ts cache yet.
(defonce locations-to-be-fetched (atom #{}))

(defn consume-location-fetch
  "Fetch the next queued [varname row col] tuple.

   Picks an arbitrary tuple from the set (sets are unordered), calls
   `fetch-ts` synchronously, and only removes the tuple from the queue once
   the fetch succeeds. Locking is left to `fetch-ts`, which rejects duplicate
   or over-capacity concurrent fetches."
  []
  (when-let [[varname row col :as item] (first @locations-to-be-fetched)]
    (fetch/fetch-ts varname row col)
    (swap! locations-to-be-fetched disj item)
    item))

(comment
  (count @locations-to-be-fetched)
  ;; manually draw down the queue, for testing
  (consume-location-fetch)
  ;;
  )

(def ^:private fetch-interval-ms
  "Pause between successive backfill fetches, to keep load on the upstream
   Icechunk repository gentle."
  10000)

;; Holds the running background worker, or nil when stopped.
;; Shape: {:thread Thread :running? (atom boolean) :monitor Object}
(defonce ^:private worker (atom nil))

(defn- drain-queue!
  "Consume queued fetches one at a time, pausing `fetch-interval-ms` between
   each, until the queue drains or `running?` flips to false.

   A failing fetch is logged and left queued; the inter-fetch sleep keeps a
   persistently-failing item from spinning the CPU."
  [running?]
  (while (and @running? (seq @locations-to-be-fetched))
    (try
      (consume-location-fetch)
      (catch Exception e
        (log/warn e "Backfill fetch failed; leaving item queued")))
    (when (and @running? (seq @locations-to-be-fetched))
      (Thread/sleep ^long fetch-interval-ms))))

(defn- worker-loop
  "Drain the queue, then park on `monitor` until a refill wakes us. Repeats
   until `running?` is cleared."
  [running? ^Object monitor]
  (while @running?
    (drain-queue! running?)
    (locking monitor
      (when (and @running? (empty? @locations-to-be-fetched))
        (.wait monitor)))))

(defn start-worker!
  "Start the background backfill worker if one is not already running.

   The worker watches `locations-to-be-fetched`: it drains the queue (one
   fetch every `fetch-interval-ms`), then sleeps until the atom transitions
   from empty to non-empty, at which point it resumes draining. Returns the
   worker thread, or nil if one was already running."
  []
  (when-not @worker
    (let [running? (atom true)
          monitor  (Object.)
          thread   (Thread.
                    ^Runnable (fn []
                                (try
                                  (worker-loop running? monitor)
                                  (catch InterruptedException _ nil)
                                  (catch Throwable t
                                    (log/error t "Backfill worker died"))))
                    "location-backfill-worker")]
      (add-watch locations-to-be-fetched ::worker
                 (fn [_ _ old new]
                   (when (and (empty? old) (seq new))
                     (locking monitor (.notifyAll monitor)))))
      (.setDaemon thread true)
      (.start thread)
      (reset! worker {:thread thread :running? running? :monitor monitor})
      (log/info "Started location backfill worker")
      thread)))

(defn stop-worker!
  "Stop the background backfill worker if one is running. Returns true when a
   worker was stopped, false otherwise."
  []
  (if-let [{:keys [^Thread thread running? ^Object monitor]} @worker]
    (do
      (remove-watch locations-to-be-fetched ::worker)
      (reset! running? false)
      (locking monitor (.notifyAll monitor))
      (.interrupt thread)
      (reset! worker nil)
      (log/info "Stopped location backfill worker")
      true)
    false))

(def ^:private era-ts-root "era_ts")

(def locations-resource "ne_50m_populated_places_simple.geojson")

(declare refresh-locations-to-fetch!)

(defn load-locations
  "Parse the bundled GeoJSON into a vector of location maps, each carrying a
  point :bbox in the grid's 0..360 longitude convention."
  []
  (let [geojson (json/parse-string (slurp (io/resource locations-resource)) true)]
    (mapv (fn [feature]
            (let [[lon lat] (get-in feature [:geometry :coordinates])
                  lon       (mod lon 360.0)]
              {:name      (get-in feature [:properties :name])
               :longitude lon
               :latitude  lat
               :bbox      (spatial/point-bbox lon lat)}))
          (:features geojson))))

(defn init!
  "Load the locations and build the R-tree in `index`.
   Returns the number of indexed locations."
  []
  (let [locs (load-locations)]
    (reset! index (spatial/build locs))
    (reset! locations locs)
    (refresh-locations-to-fetch!)
    (count locs)))

(defn- query-bboxes
  "Return the query bboxes (plural) in the index's 0..360 longitude convention.

   Indexed locations are normalized with `(mod lon 360.0)`, so a query whose
   longitudes come in the -180..180 convention (or one that crosses the 0/360
   seam) must be split at the prime meridian."
  [[minx miny maxx maxy]]
  (let [minx' (mod minx 360.0)
        maxx' (mod maxx 360.0)]
    (if (<= minx' maxx')
      [[minx' miny maxx' maxy]]
      [[minx' miny 360.0 maxy]
       [0.0 miny maxx' maxy]])))

(defn n-locations
  "Number of locations currently held in the spatial index."
  []
  (count @locations))

(defn- location-cell
  "The ERA5 grid cell [row col] that `location` snaps to."
  [{:keys [longitude latitude]}]
  (let [{:keys [row col]} (grid/snap-coords longitude latitude)]
    [row col]))

(defn- location-cells
  "Set of ERA5 grid cells [row col] containing at least one indexed location."
  []
  (into #{} (map location-cell) @locations))

(defn- parquet-file->cell
  "Parse an `era_ts/<row>/<col>/<var>.parquet` file into its [row col] cell.
   Returns nil for files outside the expected layout."
  [^java.io.File f]
  (let [col-dir (.getParentFile f)
        row-dir (some-> col-dir .getParentFile)]
    (when (and row-dir col-dir)
      (try
        [(Long/parseLong (.getName row-dir))
         (Long/parseLong (.getName col-dir))]
        (catch NumberFormatException _ nil)))))

(defn cached-cells
  "Set of ERA5 grid cells [row col] that have a cached timeseries parquet under
   `era_ts/`. Walks directory names only; it never opens a parquet file."
  []
  (let [root (io/file era-ts-root)]
    (into #{}
          (keep (fn [^java.io.File f]
                  (when (and (.isFile f)
                             (str/ends-with? (.getName f) ".parquet"))
                    (parquet-file->cell f))))
          (file-seq root))))

(defn n-locations-with-ts
  "Number of indexed locations whose ERA5 grid cell has a cached timeseries.

   Builds the cached-cell set once, then counts each location whose cell is a
   member of it. This is a pure set-membership check per location, so a cache
   entry for a cell with no indexed locations is never counted as complete."
  []
  (let [cached (cached-cells)]
    (count (filter #(contains? cached (location-cell %)) @locations))))

(defn refresh-locations-to-fetch!
  "Repopulate `locations-to-be-fetched` with a [varname row col] tuple for
   every indexed grid cell that is not yet in the era_ts cache.

   Computes the set difference between indexed location cells and cached
   cells; leftover cache entries are therefore ignored, and cells with
   multiple locations collapse to a single tuple (one fetch serves all)."
  []
  (let [missing (set/difference (location-cells) (cached-cells))]
    (reset! locations-to-be-fetched
            (into #{}
                  (map (fn [[row col]] [vars/default-varname row col]))
                  missing))))

(defn query
  "Locations whose bbox intersects `query-bbox`.

   The query longitudes may be in either the -180..180 or 0..360 convention;
   they are normalized to the index's 0..360 convention, splitting at the
   prime meridian when necessary.

   Returns () when no locations are found.
   Returns nil when the index has not been initialized."
  [query-bbox]
  (when-let [idx @index]
    (->> (query-bboxes query-bbox)
         (mapcat #(spatial/search idx %))
         distinct)))

(comment
  (query [-0.125 51.375 0.125 51.625])
  (query [5 45 7 48])
  (query [0 0 0 0]))
