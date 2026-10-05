(ns climate-changed.era5.summary
  (:require
   [climate-changed.era5.duckdb :as duckdb]
   [climate-changed.era5.fetch :as fetch]
   [climate-changed.era5.grid :as grid]
   [climate-changed.era5.variables :as vars]
   [tech.v3.dataset :as ds]))

(set! *warn-on-reflection* true)

(def ^:private era5-start-year
  "First year of the ERA5 record."
  1940)

(defn- utc-start-of-year
  "UTC instant at the start of `year`."
  [year]
  (java.time.Instant/parse (str year "-01-01T00:00:00Z")))

(defn- current-decade-start-year
  "First year of the current UTC decade, e.g. 2020."
  []
  (let [^java.time.Year now (java.time.Year/now java.time.ZoneOffset/UTC)]
    (* 10 (quot (.getValue now) 10))))

(def climate-periods
  "Contiguous decade reference periods for the climate summary, as [start end)
  instants. Decades run from `era5-start-year` through the current decade; the
  final (current) decade has a nil :end, meaning through the latest observation."
  (let [end-year (current-decade-start-year)]
    (mapv (fn [start-year]
            {:start (utc-start-of-year start-year)
             :end   (when (< start-year end-year)
                      (utc-start-of-year (+ start-year 10)))})
          (range era5-start-year (+ end-year 10) 10))))

(defn- decade-start-year
  "The UTC decade containing `t`, expressed as its start year, e.g. 2020."
  ^long [^java.time.Instant t]
  (let [^java.time.ZonedDateTime zdt (.atZone t java.time.ZoneOffset/UTC)]
    (long (* 10 (quot (.getYear zdt) 10)))))

(defn- decade-summary-sql
  "DuckDB SQL computing per-decade descriptive stats for `varname` over the
  parquet file at `parquet-path`.

  Notes:
  - `//` is integer division in DuckDB (plain `/` is float), so
    `(year // 10) * 10` buckets observations into their UTC decade start year.
  - min/max are cast to DOUBLE so their JSON representation matches the old
    float-widened Clojure output exactly.
  - `stddev_samp` is the sample standard deviation, matching tech.ml.dataset.
  - column aliases are double-quoted because `min`, `max`, `start`, and `end`
    are reserved words in DuckDB."
  [varname parquet-path]
  (str "SELECT (year(valid_time) // 10) * 10 AS decade,"
       " count(*) AS \"n\","
       " min(" varname ")::DOUBLE AS \"min\","
       " avg(" varname ") AS \"mean\","
       " max(" varname ")::DOUBLE AS \"max\","
       " stddev_samp(" varname ") AS \"sd\","
       " min(valid_time) AS \"start\","
       " max(valid_time) AS \"end\""
       " FROM '" parquet-path "'"
       " GROUP BY decade"
       " ORDER BY decade"))

(defn- row->period-stats
  "Convert a DuckDB result row (keyword keys) into a period-stats map, with
  valid_time bounds stringified to ISO-8601."
  [{:keys [n min mean max sd start end]}]
  {:n     n
   :min   min
   :mean  mean
   :max   max
   :sd    sd
   :start (some-> start str)
   :end   (some-> end str)})

(defn- decade-stats
  "Map of decade start year -> period-stats for each decade present in the
  DuckDB `result` dataset."
  [result]
  (reduce (fn [acc row]
            (assoc acc (:decade row) (row->period-stats row)))
          {}
          (ds/rows result)))

(def ^:private empty-period-stats
  "Stats for a reference period with no observations."
  {:n nil :min nil :mean nil :max nil :sd nil :start nil :end nil})

(defn era5-summary
  "Climate summary for a grid cell, broken into reference periods. Ensures the
  pixel's parquet timeseries exists (downloading it if necessary), then uses the
  sandboxed DuckDB to compute per-decade descriptive statistics."
  [varname row col]
  (when-not (vars/valid-varname? varname)
    (throw (ex-info (str "Unknown ERA5 variable: " varname)
                    {:status 400 :varname varname})))
  (let [parquet-path      (fetch/ensure-ts! varname row col)
        {:keys [lat lon]} (grid/cell-center row col)
        by-decade         (-> (decade-summary-sql varname parquet-path)
                              (duckdb/query)
                              (decade-stats))]
    {:row     row
     :col     col
     :lat     lat
     :lon     lon
     :var     varname
     :units   (get-in vars/era5-variables [(keyword varname) :units])
     :periods (mapv (fn [{:keys [start]}]
                      (get by-decade (decade-start-year start) empty-period-stats))
                    climate-periods)}))

(comment
  (duckdb/init!) ;; idempotent, I think
  (time (era5-summary "t2m" 203 427)))
