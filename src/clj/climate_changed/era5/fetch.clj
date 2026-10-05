(ns climate-changed.era5.fetch
  (:require
   [climate-changed.era5.grid :as grid]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [tech.v3.dataset :as ds]
   [tech.v3.datatype :as dtype]
   [tech.v3.datatype.datetime :as dt-dt]
   [tech.v3.libs.parquet :as pq]))

(defn- stable-binary-path
  "A fixed, writable location to cache the extracted binary so we don't
   accumulate a new temp file on every fetch. Override with
   $ERA5_FETCHER_CACHE for deployments where the temp dir is unsuitable."
  []
  (or (System/getenv "ERA5_FETCHER_CACHE")
      (-> (io/file (System/getProperty "java.io.tmpdir") "era5-timeseries")
          .getPath)))

(defn- extract-binary!
  "Resolve `resource-path` on the classpath to a runnable binary.
   When the resource lives inside a jar (the uberjar),
   copy it once to a stable location and reuse it on subsequent calls,
   so we don't accumulate temp files in /tmp."
  [resource-path]
  (when-let [url (io/resource resource-path)]
    (case (.getProtocol url)
      "file" (let [f (java.io.File. (.toURI url))]
               (when (.isFile f) f))
      "jar"  (let [dest (io/file (stable-binary-path))
                   len  (.getContentLengthLong (.openConnection url))]
               ;; Reuse an existing copy only when it matches the
               ;; resource's size; otherwise (re)extract it.
               (when-not (and (.isFile dest)
                              (pos? len)
                              (= (.length dest) len))
                 (io/make-parents dest)
                 (with-open [in  (io/input-stream url)
                             out (io/output-stream dest)]
                   (io/copy in out))
                 (.setExecutable dest true))
               dest)
      nil)))

(defn- fetcher-path
  "Locate the era5-timeseries binary, in order:
   1. $ERA5_FETCHER - explicit override for unusual deployments
   2. bin/era5-timeseries on the classpath - the bundled release binary
   3. src/rs/era5-timeseries/target/release/era5-timeseries - dev build"
  []
  (or (System/getenv "ERA5_FETCHER")
      (some-> (extract-binary! "bin/era5-timeseries") .getPath)
      (let [f (io/file "src/rs/era5-timeseries/target/release/era5-timeseries")]
        (when (.isFile f) (.getPath f)))))

(defn- era-download-cmd [varname row col]
  (let [bin (or (fetcher-path)
                (throw (ex-info (str "era5-timeseries binary not found. "
                                     "Build it with `make release-era5-timeseries`, "
                                     "or point $ERA5_FETCHER at a prebuilt binary.")
                                {})))]
    [bin (str row) (str col) (str varname)]))

(defn- fix-valid-time [data]
  ;; tmd's parquet reader only honors the legacy TIMESTAMP_MILLIS/MICROS
  ;; converted types, so the modern logical-type timestamps (ns) that
  ;; pyarrow writes come through as raw int64 epoch-nanoseconds.
  ;; valid_time is UTC and hourly, so the ns -> us conversion is exact.
  ;; column names are strings in the dataset map
  (ds/update-column
   data "valid_time"
   (fn [col]
     (dt-dt/epoch->datetime nil :epoch-microseconds :instant
                            (dtype/emap (fn [^long ns] (quot ns 1000)) :int64 col)))))

(defn expected-path [varname row col]
  ;; Path must match exactly what the fetcher writes (see the Rust code).
  (str "era_ts/" row "/" col "/" varname ".parquet"))

(defn- run-cmd
  "Run `cmd` (vector of strings) synchronously, capturing stdout and stderr
  separately. Returns {:out :err :exit} like clojure.java.shell/sh."
  [cmd]
  (let [proc (.start (ProcessBuilder. ^java.util.List cmd))
        out  (with-open [r (io/reader (.getInputStream proc))] (slurp r))
        err  (with-open [r (io/reader (.getErrorStream proc))] (slurp r))
        exit (.waitFor proc)]
    {:out out :err err :exit exit}))

(def max-concurrent-fetches 3)

(defonce fetch-ts-status (atom {}))

(defn- try-acquire-fetch!
  "Try to reserve a slot in `fetch-ts-status` for this varname/row/col.
  Returns true when the lock was acquired, false when the same fetch is
  already running or `max-concurrent-fetches` slots are already taken."
  [varname row col]
  (let [k         [varname row col]
        [old new] (swap-vals! fetch-ts-status
                              (fn [status]
                                (if (or (contains? status k)
                                        (>= (count status) max-concurrent-fetches))
                                  status
                                  (assoc status k true))))]
    (and (not (contains? old k))
         (contains? new k))))

(defn- release-fetch! [varname row col]
  (swap! fetch-ts-status dissoc [varname row col]))

(defn ensure-ts!
  "Ensure the ERA5 parquet timeseries for the given pixel exists on disk,
  downloading it via the fetcher binary if missing, and return its path.

  Concurrency gate: keep at most `max-concurrent-fetches` in flight and never
  start a second fetch for the same pixel. Throws an ex-info with :status 423
  when a fetch can't be started because the gate is full."
  [varname row col]
  (let [parquet-path (expected-path varname row col)]
    (when-not (.exists (io/file parquet-path))
      (when-not (try-acquire-fetch! varname row col)
        (Thread/sleep 500)
        (when-not (try-acquire-fetch! varname row col) ;; one retry
          (throw (ex-info "ERA5 fetch already in progress; try again later"
                          {:status  423
                           :varname varname
                           :row     row
                           :col     col}))))
      (try
        (when-not (.exists (io/file parquet-path))
          ;; Query the Icechunk repository on S3 and cache to permanent storage
          (log/info (str "Fetching ERA5 timeseries for " varname " " row " " col))
          (let [cmd                    (era-download-cmd varname row col)
                {:keys [out err exit]} (run-cmd cmd)]
            (when (seq err) (binding [*out* *err*] (print err)))
            (when-not (zero? exit)
              (throw (ex-info "Command failed" {:exit exit :out out :err err :cmd cmd})))
            (assert (= (str/trim out) parquet-path))))
        (finally
          (release-fetch! varname row col))))
    parquet-path))

(defn fetch-ts
  "Fetch timeseries ERA5 data for the given pixel, returning a clean dataset."
  [varname row col]
  (-> (ensure-ts! varname row col)
      (pq/parquet->ds)
      (fix-valid-time)))

(comment ;; interactive test of fetch-ts
  (time (let [{:keys [row col]} (grid/snap-coords -105.0844 40.5853)
              data              (fetch-ts "t2m" row col)]
          (->
           data
           ds/descriptive-stats))))
