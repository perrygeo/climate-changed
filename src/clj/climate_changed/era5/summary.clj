(ns climate-changed.era5.summary
  "Climate summary for an ERA5 grid cell as a 2D observation-count heatmap.

  Observations are bucketed into a grid of UTC decades (x) and equal-interval
  temperature bins (y). Temperature bins are computed in Celsius: their edges
  are whole degrees and the bin width is fixed at 2 °C, so every temperature
  cell spans the same 2 °C range regardless of location. Locations with large
  annual temperature swings therefore produce taller grids (more bins) rather
  than coarser ones; 0 °C stays a bin edge."
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

(def ^:private kelvin->celsius
  "Convert a Kelvin temperature to Celsius."
  (fn ^double [^double k] (- k 273.15)))

(def ^:private bin-width-c
  "Fixed Celsius bin width: every temperature cell is 2 °C tall. Fixed (rather
  than adaptive) so the grid is comparable across locations; a location with a
  huge temperature swing simply gets more bins (a taller grid) instead of
  coarser bins. 2 °C keeps 0 °C on a bin edge, and the 30 °C hot-day threshold
  is even so it too remains a bin edge."
  2.0)

(defn- floor-to-multiple
  "Largest multiple of `width` that is <= `x`."
  ^double [^double x ^double width]
  (* width (Math/floor (/ x width))))

(defn- bin-edges
  "Equal-interval, whole-degree bin edges covering `[min-c max-c]`. The first
  edge is the largest multiple of `width` <= min-c (so 0 °C is always an edge)
  and the last edge is the first multiple of `width` >= max-c. The top bin is
  closed at its upper edge so a max exactly on an edge does not create an
  empty trailing bin (the histogram SQL clamps that edge into the last bin)."
  [^double min-c ^double max-c ^double width]
  (let [lo (floor-to-multiple min-c width)
        n  (max 1 (long (Math/ceil (/ (- max-c lo) width))))]
    (mapv (fn [i] (+ lo (* i width))) (range (inc n)))))

(defn- edges->bins
  "Convert a vector of bin edges into closed-at-the-lower-end bin maps."
  [edges]
  (mapv (fn [lo hi] {:min lo :max hi}) edges (rest edges)))

(defn- bounds-sql
  "DuckDB SQL returning the cell's overall min/max/count for `varname`."
  [varname parquet-path]
  (str "SELECT min(" varname ")::DOUBLE AS \"min\","
       " max(" varname ")::DOUBLE AS \"max\","
       " count(*) AS \"n\""
       " FROM '" parquet-path "'"
       " WHERE " varname " IS NOT NULL"))

(defn- decade-histogram-sql
  "DuckDB SQL counting observations in each UTC decade × temperature bin.

  `lo-c` is the first bin edge in Celsius, `width` is the whole-degree bin
  width, and `max-bin` is the last valid bin index. A value's bin index is
  `floor((t2m_c - lo-c) / width)`; a tiny epsilon guards against floating-
  point values landing just below `lo-c`, and `least` clamps a value exactly
  on the top edge into the last bin."
  [varname parquet-path lo-c width max-bin]
  (str "SELECT (year(valid_time) // 10) * 10 AS decade,"
       " CAST(least(greatest(0, floor(((" varname " - 273.15) - " lo-c ") / "
       width " + 1e-9)), " max-bin ") AS INTEGER) AS bin,"
       " count(*) AS \"n\""
       " FROM '" parquet-path "'"
       " WHERE " varname " IS NOT NULL"
       " GROUP BY decade, bin"
       " ORDER BY decade, bin"))

(defn- decade-bin-counts
  "Map of decade start year -> {bin index -> observation count}, from the
  histogram query result. Bins outside `[0 nbins)` are dropped defensively."
  [result nbins]
  (reduce (fn [acc row]
            (let [decade (long (:decade row))
                  bin    (long (:bin row))
                  n      (long (:n row))]
              (if (and (<= 0 bin) (< bin nbins))
                (update-in acc [decade bin] (fnil + 0) n)
                acc)))
          {}
          (ds/rows result)))

(defn- fill-periods
  "Fill a count vector for every climate period. Periods with no histogram rows
  get a zero for every bin. `:start`/`:end` are the fixed reference-period
  bounds (stringified ISO-8601), not the first/last observation times."
  [by-decade nbins]
  (mapv (fn [{:keys [start end]}]
          (let [decade (decade-start-year start)
                counts (get by-decade decade)
                row    (mapv #(get counts % 0) (range nbins))]
            {:start  (str start)
             :end    (some-> end str)
             :counts row
             :n      (reduce + row)}))
        climate-periods))

(defn- matrix-max-count
  "The largest period × bin observation count, for normalizing the continuous
  opacity scale."
  [periods]
  (reduce max 0 (mapcat :counts periods)))

(def ^:private empty-periods
  "Reference periods with no observation counts, used when the cell has no data."
  (mapv (fn [{:keys [start end]}]
          {:start  (str start)
           :end    (some-> end str)
           :counts []
           :n      0})
        climate-periods))

(defn- decade-mean-sql
  "DuckDB SQL returning each UTC decade's mean Kelvin temperature and
  observation count for `varname`."
  [varname parquet-path]
  (str "SELECT (year(valid_time) // 10) * 10 AS decade,"
       " avg(" varname ")::DOUBLE AS mean_k,"
       " count(*) AS n"
       " FROM '" parquet-path "'"
       " WHERE " varname " IS NOT NULL"
       " GROUP BY decade"
       " ORDER BY decade"))

(defn- decade-means
  "Map of decade start year -> {:mean :n}, from the mean query result.
  `mean_k` is converted from Kelvin to Celsius."
  [result]
  (reduce (fn [acc row]
            (assoc acc
                   (long (:decade row))
                   {:mean (kelvin->celsius (double (:mean_k row)))
                    :n    (long (:n row))}))
          {}
          (ds/rows result)))

(defn- fill-mean-periods
  "Fill a per-period mean map for every climate period. Periods with no
  observations get a nil :mean and zero :n."
  [by-decade]
  (mapv (fn [{:keys [start end]}]
          (let [decade (decade-start-year start)
                {:keys [mean n]} (get by-decade decade)]
            {:start (str start)
             :end   (some-> end str)
             :mean  mean
             :n     (or n 0)}))
        climate-periods))

(defn era5-means
  "Per-decade mean temperature for a grid cell's ERA5 timeseries.

  Returns:
    :row, :col, :lat, :lon   grid metadata
    :var, :units             the ERA5 variable and its native unit
    :mean-unit               unit of the :mean values (\"°C\")
    :periods                 [{:start :end :mean :n} ...] one per decade,
                             with :mean in °C and :n the observation count;
                             periods without data have a nil :mean and 0 :n."
  [varname row col]
  (when-not (vars/valid-varname? varname)
    (throw (ex-info (str "Unknown ERA5 variable: " varname)
                    {:status 400 :varname varname})))
  (let [units (get-in vars/era5-variables [(keyword varname) :units])]
    (when-not (= "K" units)
      (throw (ex-info (str "ERA5 mean-temperature summaries require a Kelvin variable, got " units)
                      {:status 400 :varname varname :units units})))
    (let [parquet-path      (fetch/ensure-ts! varname row col)
          {:keys [lat lon]} (grid/cell-center row col)
          by-decade         (-> (decade-mean-sql varname parquet-path)
                                (duckdb/query)
                                (decade-means))
          periods           (fill-mean-periods by-decade)]
      {:row       row
       :col       col
       :lat       lat
       :lon       lon
       :var       varname
       :units     units
       :mean-unit "°C"
       :periods   periods})))

(defn era5-summary
  "Climate summary for a grid cell as a temperature-bin observation grid.

  Returns:
    :row, :col, :lat, :lon   grid metadata
    :var, :units             the ERA5 variable and its native unit
    :bin-unit                unit of the :bins edges (\"°C\")
    :bins                    [{:min :max} ...] whole-degree Celsius bin edges
    :periods                 [{:start :end :counts :n} ...] one per decade,
                             with :counts aligned to :bins
    :max-count               max period × bin count (continuous opacity
                             denominator)"
  [varname row col]
  (when-not (vars/valid-varname? varname)
    (throw (ex-info (str "Unknown ERA5 variable: " varname)
                    {:status 400 :varname varname})))
  (let [units (get-in vars/era5-variables [(keyword varname) :units])]
    (when-not (= "K" units)
      (throw (ex-info (str "ERA5 temperature-bin summaries require a Kelvin variable, got " units)
                      {:status 400 :varname varname :units units})))
    (let [parquet-path      (fetch/ensure-ts! varname row col)
          {:keys [lat lon]} (grid/cell-center row col)
          bin-unit          "°C"
          bounds            (-> (bounds-sql varname parquet-path)
                                (duckdb/query)
                                (ds/rows)
                                (first))
          min-k             (:min bounds)
          max-k             (:max bounds)
          n                 (long (or (:n bounds) 0))]
      (if (or (nil? min-k) (nil? max-k) (zero? n))
        {:row       row
         :col       col
         :lat       lat
         :lon       lon
         :var       varname
         :units     units
         :bin-unit  bin-unit
         :bins      []
         :periods   empty-periods
         :max-count 0}
        (let [min-c     (kelvin->celsius (double min-k))
              max-c     (kelvin->celsius (double max-k))
              width     bin-width-c
              edges     (bin-edges min-c max-c width)
              bins      (edges->bins edges)
              nbins     (count bins)
              by-decade (-> (decade-histogram-sql varname parquet-path (first edges) width (dec nbins))
                            (duckdb/query)
                            (decade-bin-counts nbins))
              periods   (fill-periods by-decade nbins)]
          {:row       row
           :col       col
           :lat       lat
           :lon       lon
           :var       varname
           :units     units
           :bin-unit  bin-unit
           :bins      bins
           :periods   periods
           :max-count (matrix-max-count periods)})))))

(comment
  (duckdb/init!) ;; idempotent
  (era5-summary "t2m" 204 427))
