(ns climate-changed.era5.summary
  (:require
   [climate-changed.era5.fetch :as fetch]
   [climate-changed.era5.grid :as grid]
   [climate-changed.era5.variables :as vars]
   [tech.v3.dataset :as ds]))

(set! *warn-on-reflection* true)

(def climate-periods
  "Contiguous reference periods for the climate summary, as [start end)
  instants. A nil :end means through the latest observation."
  [{:start (java.time.Instant/parse "1940-01-01T00:00:00Z")
    :end   (java.time.Instant/parse "1970-01-01T00:00:00Z")}
   {:start (java.time.Instant/parse "1970-01-01T00:00:00Z")
    :end   (java.time.Instant/parse "2000-01-01T00:00:00Z")}
   {:start (java.time.Instant/parse "2000-01-01T00:00:00Z")
    :end   nil}])

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
  (let [data              (fetch/fetch-ts varname row col)
        {:keys [lat lon]} (grid/cell-center row col)]
    {:row     row
     :col     col
     :lat     lat
     :lon     lon
     :var     varname
     :units   (get-in vars/era5-variables [(keyword varname) :units])
     :periods (mapv #(period-stats data varname %) climate-periods)}))
