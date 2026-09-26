(ns climate-changed.era5
  (:require
   [climate-changed.era5-grid :as grid]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]))

;; TODO def era-variables
;; TODO

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
  (let [{:keys [row col]} (grid/snap-coords -105.0844 40.5853)]
    (println "fetching " row col)
    (fetch-ts "t2m" row col))
  ;; wip
  )
