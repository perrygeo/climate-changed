(ns climate-changed.era5.fetch
  (:require
   [climate-changed.era5.grid :as grid]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [tech.v3.dataset :as ds]
   [tech.v3.datatype :as dtype]
   [tech.v3.datatype.datetime :as dt-dt]
   [tech.v3.libs.parquet :as pq]))

(defn- era-download-cmd [varname row col]
  ["src/py/era5-timeseries.py"
   "--row" (str row)
   "--col" (str col)
   "--var" (str varname)])

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
  ;; Must match what the python script says!
  ;; path = f"era_ts/{row}/{col}/{var}.parquet"
  (str "era_ts/" row "/" col "/" varname ".parquet"))

(defn- run-cmd
  "Run `cmd` (vector of strings) synchronously, returning a map with
  :out, :err and :exit like `clojure.java.shell/sh`.

  `sh` is deliberately avoided: it uses `future` internally, which submits
  to Clojure's agent thread pool. Once that pool has been shut down (e.g.
  after an integrant.repl reset) every `sh` call throws
  RejectedExecutionException. ProcessBuilder does not use the agent pool.
  stderr is merged into stdout so the single stream can be read to EOF
  without the deadlock risk that normally forces concurrent reads."
  [cmd]
  (let [proc (.start (doto (ProcessBuilder. ^java.util.List cmd)
                       (.redirectErrorStream true)))]
    {:out  (with-open [r (io/reader (.getInputStream proc))]
             (slurp r))
     :err  ""
     :exit (.waitFor proc)}))

(defn fetch-ts
  "Fetch timeseries ERA5 data for the given pixel."
  [varname row col]
  (let [parquet-path (expected-path varname row col)
        exists?      (.exists (io/file parquet-path))]
    (when-not exists?
      ;; Query from the Icechunk repository on s3 -> permanent storage (TBD)
      ;; using a python script because there are no JVM options
      ;; effectively, this acts as an ever-growing cache in storage.
      ;; hopefully bound by the fact that callers won't try anything stupid.
      ;; like downloading all rows x cols x vars timeseries ... 100+ TB easy
      (let [{:keys [out exit]} (run-cmd (era-download-cmd varname row col))]
        (when-not (zero? exit)
          (throw (ex-info "Command failed"
                          {:exit exit
                           :out  out
                           :cmd  (era-download-cmd varname row col)})))
        (assert (= (str/trim out) parquet-path))))
    ;; fetch from permanent storage and return a clean dataset
    (->
     (pq/parquet->ds parquet-path)
     (fix-valid-time))))

(comment ;; integration test for fetch-ts
  (time (let [{:keys [row col]} (grid/snap-coords -105.0844 40.5853)
              data              (fetch-ts "t2m" row col)]
          (->
           data
           ds/descriptive-stats))))
