(ns climate-changed.models.locations
  (:require
   [honey.sql :as sql]
   [next.jdbc :as jdbc]))

(set! *warn-on-reflection* true)

(def locations-sql
  "Select every location with its WGS84 coordinates from PostGIS."
  (sql/format {:select   [:id :name [[:st_x :geom] :lon] [[:st_y :geom] :lat]]
               :from     [:locations]
               :order-by [:id]}))

(defn- feature
  "One GeoJSON Feature (RFC 7946) as a plain map, built from a `locations` row."
  [{:locations/keys [id name] :keys [lon lat]}]
  {:type       "Feature"
   :id         id
   :geometry   (when (and lon lat)
                 {:type "Point" :coordinates [lon lat]})
   :properties {:name name}})

(defn locations-feature-collection
  "Every location in the database as a GeoJSON FeatureCollection map."
  [ds]
  {:type     "FeatureCollection"
   :features (mapv feature (jdbc/execute! ds locations-sql))})

(comment
  (require '[integrant.repl.state :as state])
  (let [db   (:climate-changed/datasource state/system)
        locs (jdbc/execute! db locations-sql)]
    (mapv feature locs)))
