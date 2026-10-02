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

(defn- query-bboxes
  "Return the query bboxes (plural) in the index's 0..360 longitude convention.

   Indexed locations are normalized with `(mod lon 360.0)`, so a query whose
   longitudes come in the -180..180 convention (or one that crosses the 0/360
   seam) must be split at the prime meridian."
  [[minx miny maxx maxy]]
  (let [minx' (mod minx 360.0)
        maxx' (mod maxx 360.0)]
    (if (<= minx' maxx')
      [[minx' miny maxx' maxy]]
      [[minx' miny 360.0 maxy]
       [0.0 miny maxx' maxy]])))

(defn query
  "Locations whose bbox intersects `query-bbox`.

   The query longitudes may be in either the -180..180 or 0..360 convention;
   they are normalized to the index's 0..360 convention, splitting at the
   prime meridian when necessary.

   Returns () when no locations are found.
   Returns nil when the index has not been initialized."
  [query-bbox]
  (when-let [idx @index]
    (->> (query-bboxes query-bbox)
         (mapcat #(spatial/search idx %))
         distinct)))

(comment
  (query [-0.125 51.375 0.125 51.625])
  (query [5 45 7 48])
  (query [0 0 0 0]))
