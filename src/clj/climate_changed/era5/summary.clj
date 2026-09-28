(ns climate-changed.era5.summary
  (:require
   [climate-changed.era5.fetch :as fetch]
   [climate-changed.era5.grid :as grid]
   [climate-changed.era5.variables :as vars]
   [tech.v3.dataset :as ds]))

(defn- descriptive-stats-row
  "The `ds/descriptive-stats` row for `col-name` as a plain map, or nil."
  [data col-name]
  (some #(when (= (:col-name %) col-name) %)
        (ds/rows (ds/descriptive-stats data))))

(defn era5-summary [varname row col]
  (let [data              (fetch/fetch-ts varname row col)
        vstats            (descriptive-stats-row data varname)
        tstats            (descriptive-stats-row data "valid_time")
        {:keys [lat lon]} (grid/cell-center row col)]
    {:row   row
     :col   col
     :lat   lat
     :lon   lon
     :var   varname
     :units (get-in vars/era5-variables [(keyword varname) :units])
     :n     (:n-valid vstats)
     :min   (:min vstats)
     :mean  (:mean vstats)
     :max   (:max vstats)
     :sd    (:standard-deviation vstats)
     :start (some-> (:min tstats) str)
     :end   (some-> (:max tstats) str)}))
