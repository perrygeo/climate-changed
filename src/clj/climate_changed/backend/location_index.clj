(ns climate-changed.backend.location-index
  "Builds and holds the in-memory R-tree spatial index of Natural Earth
  locations, used to validate ERA5 grid-cell lookups."
  (:require
   [cheshire.core :as json]
   [climate-changed.spatial-index :as spatial]
   [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(defonce index (atom nil))

(def locations-resource "ne_50m_populated_places_simple.geojson")

(defn load-locations
  "Parse the bundled GeoJSON into a vector of location maps, each carrying a
  point :bbox in the grid's 0..360 longitude convention."
  []
  (let [geojson (json/parse-string (slurp (io/resource locations-resource)) true)]
    (mapv (fn [feature]
            (let [[lon lat] (get-in feature [:geometry :coordinates])
                  lon       (mod lon 360.0)]
              {:name      (get-in feature [:properties :name])
               :longitude lon
               :latitude  lat
               :bbox      (spatial/point-bbox lon lat)}))
          (:features geojson))))

(defn init!
  "Load the locations and build the R-tree in `index`.
   Returns the number of indexed locations."
  []
  (let [locations (load-locations)]
    (reset! index (spatial/build locations))
    (count locations)))

(defn query
  "Locations whose bbox intersects `query-bbox`.
   Returns () when no locations are found.
   Returns nil when the index has not been initialized."
  [query-bbox]
  (some-> @index (spatial/search query-bbox)))

(comment
  (query [5 45 7 48])
  (query [0 0 0 0]))
