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
