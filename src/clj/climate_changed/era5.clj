(ns climate-changed.era5
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

;; TODO def era-variables
;; TODO

(defn snap-coords
  "Snap an arbitrary lat/lon point to the nearest cell of the ERA5
  0.25-degree global grid, returned as a map.

  The ERA5 store's grid is
      latitude:  721 points,  90.0 .. -90.0 (row 0 = north pole)
      longitude: 1440 points, 0.0 .. 359.75 (col 0 = prime meridian)

  `lat` is degrees north; `lon` is degrees east, in either -180..180 or
  0..360. Out-of-range latitudes are clamped to the nearest pole row.
  Points exactly halfway between rows or columns snap to the even index,
  matching Python's `round` (Math/rint).

  Returns {:row grid-row, :col grid-col, :lat latitude of the cell center,
  :lon longitude of the cell center}.
  "
  [lon lat]
  (let [lat0  90.0
        lon0  0.0
        step  0.25
        nrows 721
        ncols 1440
        lon   (mod lon 360.0)                ; normalize to [0, 360)
        row   (long (min (dec nrows)         ; clamp to the pole rows
                         (max 0.0 (Math/rint (/ (- lat0 lat) step)))))
        col   (long (mod (Math/rint (/ (- lon lon0) step)) ncols))]
    {:row row
     :col col
     :lat (- lat0 (* row step))
     :lon (+ lon0 (* col step))}))

;; TODO fetch-ts should take (var row col)
;; TODO stdout should be file path, must match precalculated as sanity check
(defn era-cmd [varname row col]
  ["src/py/era5-timeseries.py"
   "--row" (str row)
   "--col" (str col)
   "--var" (str varname)])

(defn expected-path [varname row col]
  ;; TODO precalculate expected output path
  )

(defn- validate [p]
  ;; todo check if p is a valid parquet timeseries
  p)

(defn fetch-ts
  "Fetch timeseries ERA5 data for the given pixel."
  [varname row col]
  ;; TODO check cache first, expected-path
  (let [{:keys [out err exit]} (apply sh (era-cmd varname row col))]
    (when (seq err) (binding [*out* *err*] (print err)))
    (when-not (zero? exit) (throw (ex-info "Command failed" {:exit exit :err err})))
    (validate (str/trim out))
    ;; TODO check LRU cache of ts?
    ;; TODO otherwise (fetch-ts varname row col)
    ;; TODO load parquet to dataframe
    ))

(comment
  ;; TODO effectively, this should happen when a location is selected
  (let [{:keys [row col]} (snap-coords -105.0844 40.5853)]
    (println "fetching " row col)
    (fetch-ts "t2m" row col))
  ;; wip
  )
