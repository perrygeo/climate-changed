(ns climate-changed.era5.grid-test
  (:require
   [climate-changed.era5.grid :as grid]
   [clojure.test :refer [are deftest is testing]]))

;; ---------------------------------------------------------------------------
;; Scientific interpretation of `snap-coords`
;;
;; ERA5 stores a regular 0.25-degree global grid:
;;
;;     latitude:  721 points, 90.0 .. -90.0   (row 0 = north pole)
;;     longitude: 1440 points, 0.0 .. 359.75  (col 0 = prime meridian)
;;
;; Point extraction (e.g. matching a weather station or city to a grid cell)
;; uses *nearest-neighbour* semantics:
;;
;;     row = round((90 - lat) / 0.25), clamped to [0, 720]
;;     col = mod(round(lon / 0.25), 1440)
;;
;; That is the correct interpretation for a single-location timeseries: a
;; point is assigned to the cell whose *centre* is closest to it. Ties (points
;; exactly halfway between two centres) round to the even row/col, matching
;; `Math/rint`. Longitudes are normalised to [0, 360) before snapping, so
;; -180 and 180 (the antimeridian) resolve to the same column, and the last
;; column (359.75) is contiguous with the first (0.0).
;; ---------------------------------------------------------------------------

(deftest snap-test
  ;; Fort Collins, CO — the reference point used throughout the project.
  (is (= (grid/snap-coords -105.0844 40.5853)
         {:row 198, :col 1020, :lat 40.5, :lon 255.0})))

(deftest poles-test
  (testing "north pole snaps to row 0"
    (is (= (grid/snap-coords 0 90)
           {:row 0, :col 0, :lat 90.0, :lon 0.0})))
  (testing "south pole snaps to the last row"
    (is (= (grid/snap-coords 0 -90)
           {:row 720, :col 0, :lat -90.0, :lon 0.0})))
  (testing "poles keep their longitude column"
    (is (= (grid/snap-coords 180 90)
           {:row 0, :col 720, :lat 90.0, :lon 180.0}))))

(deftest equator-prime-meridian-test
  (is (= (grid/snap-coords 0 0)
         {:row 360, :col 0, :lat 0.0, :lon 0.0})))

(deftest antimeridian-test
  (testing "180 and -180 are the same longitude column"
    (are [lon] (= (grid/snap-coords lon 0)
                  {:row 360, :col 720, :lat 0.0, :lon 180.0})
      180
      -180)))

(deftest longitude-wrap-test
  (testing "last column centre (359.75) maps to col 1439"
    (is (= (grid/snap-coords 359.75 0)
           {:row 360, :col 1439, :lat 0.0, :lon 359.75})))
  (testing "360 wraps to the prime meridian"
    (is (= (grid/snap-coords 360 0)
           {:row 360, :col 0, :lat 0.0, :lon 0.0})))
  (testing "the half-step between 359.75 and 0.0 ties to even, wrapping to col 0"
    (is (= (grid/snap-coords 359.875 0)
           {:row 360, :col 0, :lat 0.0, :lon 0.0}))))

(deftest exact-cell-centre-test
  (testing "a point already at a cell centre is unchanged (idempotent)"
    (are [lon lat row col] (= (grid/snap-coords lon lat)
                              {:row row, :col col, :lat lat, :lon lon})
      255.0  40.5    198  1020
      10.25  -33.75  495  41
      0.0    0.0     360  0)))

(deftest ties-to-even-test
  (testing "halfway between longitude columns rounds to the even column"
    (are [lon col lon'] (= (grid/snap-coords lon 0)
                           {:row 360, :col col, :lat 0.0, :lon lon'})
      0.125 0 0.0
      0.375 2 0.5))
  (testing "halfway between latitude rows rounds to the even row"
    (are [lat row lat'] (= (grid/snap-coords 0 lat)
                           {:row row, :col 0, :lat lat', :lon 0.0})
      40.625 198 40.5
      40.125 200 40.0)))

(deftest out-of-range-lat-clamps-test
  (testing "latitudes beyond the poles clamp to the pole rows"
    (are [lat row lat'] (= (grid/snap-coords 0 lat)
                           {:row row, :col 0, :lat lat', :lon 0.0})
      95  0   90.0
      -95 720 -90.0)))

(deftest round-trip-test
  (testing "snapping a cell centre returns that cell across the whole grid"
    (doseq [row [0 1 198 360 719 720]
            col [0 1 720 1020 1438 1439]
            :let [lat (- 90.0 (* row 0.25))
                  lon (* col 0.25)]]
      (is (= (grid/snap-coords lon lat)
             {:row row, :col col, :lat lat, :lon lon})))))

(deftest cell-bbox-test
  (testing "bbox spans 0.25 degrees centred on the cell centre"
    (is (= (grid/cell-bbox 198 1020)
           [254.875 40.375 255.125 40.625])))
  (testing "longitude uses the grid's 0..360 convention"
    (is (= (grid/cell-bbox 360 720)
           [179.875 -0.125 180.125 0.125])))
  (testing "the north-pole row's bbox extends a half-cell above the pole"
    (is (= (grid/cell-bbox 0 0)
           [-0.125 89.875 0.125 90.125]))))

;; ---------------------------------------------------------------------------
;; era5-pixel-geojson
;; ---------------------------------------------------------------------------

(defn- normalize-lon
  "Independent re-implementation of the display-convention longitude
  normalization used by `era5-pixel-geojson`: [0, 360) -> [-180, 180)."
  [lon]
  (if (> lon 180.0) (- lon 360.0) lon))

(defn- pixel-ring
  "The single polygon ring of `geojson`'s first feature."
  [geojson]
  (get-in geojson [:features 0 :geometry :coordinates 0]))

(defn- expected-pixel-ring
  "The polygon ring `era5-pixel-geojson` should produce for `loc`, derived
  from the public `snap-coords`/`cell-bbox` plus the documented longitude
  normalization."
  [loc]
  (let [{:keys [row col]} (grid/snap-coords (:longitude loc) (:latitude loc))
        [min-lon min-lat max-lon max-lat] (grid/cell-bbox row col)
        min-lon (normalize-lon min-lon)
        max-lon (normalize-lon max-lon)]
    [[min-lon min-lat]
     [max-lon min-lat]
     [max-lon max-lat]
     [min-lon max-lat]
     [min-lon min-lat]]))

(deftest era5-pixel-geojson-nil-test
  (testing "a nil location yields an empty FeatureCollection"
    (is (= {:type "FeatureCollection" :features []}
           (grid/era5-pixel-geojson nil)))))

(deftest era5-pixel-geojson-fort-collins-test
  (testing "the reference point snaps to its 0.25-degree cell, with longitudes
            expressed in the [-180, 180) display convention"
    (is (= {:type     "FeatureCollection"
            :features [{:type     "Feature"
                        :geometry {:type        "Polygon"
                                   :coordinates [[[-105.125 40.375]
                                                  [-104.875 40.375]
                                                  [-104.875 40.625]
                                                  [-105.125 40.625]
                                                  [-105.125 40.375]]]}}]}
           (grid/era5-pixel-geojson {:longitude -105.0844 :latitude 40.5853})))))

(deftest era5-pixel-geojson-prime-meridian-test
  (testing "a cell straddling the prime meridian normalizes to a small
            polygon spanning -0.125..0.125 degrees of longitude"
    (is (= [[-0.125 -0.125]
            [0.125 -0.125]
            [0.125 0.125]
            [-0.125 0.125]
            [-0.125 -0.125]]
           (pixel-ring (grid/era5-pixel-geojson {:longitude 0 :latitude 0}))))))

(deftest era5-pixel-geojson-antimeridian-test
  ;; NOTE: this pins the *current* behavior. The antimeridian cell is not
  ;; split into two polygons, so the ring spans from 179.875 to -179.875
  ;; (nearly the whole globe). A caller rendering this cell would need to
  ;; split it at the antimeridian itself.
  (testing "the antimeridian cell currently normalizes to a globe-spanning ring"
    (is (= [[179.875 -0.125]
            [-179.875 -0.125]
            [-179.875 0.125]
            [179.875 0.125]
            [179.875 -0.125]]
           (pixel-ring (grid/era5-pixel-geojson {:longitude 180 :latitude 0}))))))

(deftest era5-pixel-geojson-structure-test
  (testing "output is a well-formed single-feature Polygon FeatureCollection"
    (doseq [loc [{:longitude -105.0844 :latitude 40.5853}   ; Fort Collins
                 {:longitude 151.2093  :latitude -33.8688}   ; Sydney
                 {:longitude 36.8219   :latitude -1.2921}    ; Nairobi
                 {:longitude -0.1276   :latitude 51.5072}    ; London
                 {:longitude -78.4678  :latitude -0.1807}    ; Quito
                 {:longitude -149.9003 :latitude 61.2181}    ; Anchorage
                 {:longitude 139.6917  :latitude 35.6895}    ; Tokyo
                 {:longitude -46.6333  :latitude -23.5505}] ; São Paulo
            :let [fc (grid/era5-pixel-geojson loc)
                  ring (pixel-ring fc)]]
      (testing (str "location " loc)
        (is (= "FeatureCollection" (:type fc)))
        (is (= 1 (count (:features fc))))
        (is (= "Feature" (get-in fc [:features 0 :type])))
        (is (= "Polygon" (get-in fc [:features 0 :geometry :type])))
        (is (= 1 (count (get-in fc [:features 0 :geometry :coordinates]))))
        (is (vector? ring))
        (is (= 5 (count ring)))
        (is (= (first ring) (last ring)) "ring is closed")
        (is (every? (fn [pt] (and (vector? pt)
                                  (= 2 (count pt))
                                  (number? (first pt))
                                  (number? (second pt))))
                    ring))))))

(deftest era5-pixel-geojson-matches-snapped-cell-test
  (testing "the ring equals the cell that `loc` snaps to, with the bbox
            normalized to the [-180, 180) display convention"
    (doseq [loc [{:longitude -105.0844 :latitude 40.5853}
                 {:longitude 151.2093  :latitude -33.8688}
                 {:longitude 36.8219   :latitude -1.2921}
                 {:longitude -0.1276   :latitude 51.5072}
                 {:longitude -78.4678  :latitude -0.1807}
                 {:longitude -149.9003 :latitude 61.2181}
                 {:longitude 139.6917  :latitude 35.6895}
                 {:longitude -46.6333  :latitude -23.5505}
                 {:longitude 0         :latitude 90}
                 {:longitude 0         :latitude -90}]]
      (testing (str "location " loc)
        (is (= (expected-pixel-ring loc)
               (pixel-ring (grid/era5-pixel-geojson loc))))))))
