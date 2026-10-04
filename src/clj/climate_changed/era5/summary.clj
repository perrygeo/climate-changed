(ns climate-changed.era5.summary
  (:require
   [climate-changed.era5.fetch :as fetch]
   [climate-changed.era5.grid :as grid]
   [climate-changed.era5.variables :as vars]
   [tech.v3.dataset :as ds]
   [tech.v3.datatype :as dtype]))

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

(defn- decade-start-year
  "The UTC decade containing `t`, expressed as its start year, e.g. 2020."
  ^long [^java.time.Instant t]
  (let [^java.time.ZonedDateTime zdt (.atZone t java.time.ZoneOffset/UTC)]
    (long (* 10 (quot (.getYear zdt) 10)))))

(defn- period-stats
  "Descriptive stats for `varname` and `valid_time` over one decade of `data`."
  [data varname]
  (let [subset (ds/select-columns data [varname "valid_time"])
        vstats (descriptive-stats-row subset varname)
        tstats (descriptive-stats-row subset "valid_time")]
    {:n     (:n-valid vstats)
     :min   (:min vstats)
     :mean  (:mean vstats)
     :max   (:max vstats)
     :sd    (:standard-deviation vstats)
     :start (some-> (:min tstats) str)
     :end   (some-> (:max tstats) str)}))

(defn- decade-stats
  "Map of decade start year -> `period-stats` for each decade present in
  `data`, computed in a single group-by pass."
  [data varname]
  (let [data (ds/add-or-update-column
              data "decade"
              (dtype/emap decade-start-year :int64 (get data "valid_time")))]
    (ds/group-by-column data "decade"
                        {:group-by-finalizer #(period-stats % varname)})))

(def ^:private empty-period-stats
  "Stats for a reference period with no observations."
  {:n nil :min nil :mean nil :max nil :sd nil :start nil :end nil})

(defn era5-summary
  "Climate summary for a grid cell, broken into reference periods."
  [varname row col]
  (when-not (vars/valid-varname? varname)
    (throw (ex-info (str "Unknown ERA5 variable: " varname)
                    {:status 400 :varname varname})))
  (let [data              (fetch/fetch-ts varname row col)
        {:keys [lat lon]} (grid/cell-center row col)
        by-decade         (decade-stats data varname)]
    (tap> by-decade)
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

  (time (era5-summary "t2m" 203 427)))
