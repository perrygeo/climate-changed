(ns climate-changed.era5.summary
  (:require
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

(defn- descriptive-stats-row
  "The `ds/descriptive-stats` row for `col-name` as a plain map, or nil."
  [data col-name]
  (some #(when (= (:col-name %) col-name) %)
        (ds/rows (ds/descriptive-stats data))))

(defn- in-period?
  "True when instant `t` falls within [start end); nil end means unbounded."
  [^java.time.Instant t ^java.time.Instant start ^java.time.Instant end]
  (and (not (.isBefore t start))
       (or (nil? end) (.isBefore t end))))

(defn- period-data
  "Rows of `data` whose valid_time falls within [start end)."
  [data start end]
  (ds/filter data (fn [row] (in-period? (get row "valid_time") start end))))

(defn- period-stats
  "Descriptive stats for `varname` restricted to one period."
  [data varname {:keys [start end]}]
  (let [subset (period-data data start end)
        vstats (descriptive-stats-row subset varname)
        tstats (descriptive-stats-row subset "valid_time")]
    {:n     (:n-valid vstats)
     :min   (:min vstats)
     :mean  (:mean vstats)
     :max   (:max vstats)
     :sd    (:standard-deviation vstats)
     :start (some-> (:min tstats) str)
     :end   (some-> (:max tstats) str)}))

(defn era5-summary
  "Climate summary for a grid cell, broken into reference periods."
  [varname row col]
  (when-not (vars/valid-varname? varname)
    (throw (ex-info (str "Unknown ERA5 variable: " varname)
                    {:status 400 :varname varname})))
  (let [data              (fetch/fetch-ts varname row col)
        {:keys [lat lon]} (grid/cell-center row col)]
    {:row     row
     :col     col
     :lat     lat
     :lon     lon
     :var     varname
     :units   (get-in vars/era5-variables [(keyword varname) :units])
     :periods (mapv #(period-stats data varname %) climate-periods)}))
