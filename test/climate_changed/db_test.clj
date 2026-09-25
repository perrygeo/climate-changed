(ns climate-changed.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [climate-changed.db :as db]))

(deftest locations-sql-test
  (let [[sql] db/locations-sql]          ; honey.sql/format returns [sql & params]
    (is (string? sql))
    (is (re-find #"FROM locations" sql))
    (is (re-find #"(?i)ST_X" sql))
    (is (re-find #"(?i)ST_Y" sql))))

(deftest feature-test
  (let [feature #'climate-changed.db/feature]   ; defn- -> access via var
    (testing "row with coordinates becomes a GeoJSON Point feature"
      (is (= {:type       "Feature"
              :id         7
              :geometry   {:type "Point" :coordinates [10.0 20.0]}
              :properties {:name "Somewhere"}}
             (feature {:locations/id 7 :locations/name "Somewhere"
                       :lon 10.0 :lat 20.0}))))
    (testing "row without coordinates has no geometry"
      (is (= {:type       "Feature"
              :id         8
              :geometry   nil
              :properties {:name "Nowhere"}}
             (feature {:locations/id 8 :locations/name "Nowhere"
                       :lon nil :lat nil}))))))
