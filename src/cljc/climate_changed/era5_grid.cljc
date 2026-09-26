(ns climate-changed.era5-grid)

;; TODO define era-variables

(defn- rint
  "Round to the nearest integer, ties to even.
  Should match Java `Math/rint`, which JS lacks."
  [x]
  #?(:clj (Math/rint x)
     :cljs (let [f (js/Math.floor x)
                 d (- x f)]
             (cond
               (< d 0.5) f
               (> d 0.5) (inc f)
               (odd? (long f)) (inc f)
               :else f))))

(defn snap-coords
  "Snap an arbitrary lat/lon point to the nearest cell 
  of the ERA5 0.25-degree global grid.

  The ERA5 store's grid is
      latitude:  721 points,  90.0 .. -90.0 (row 0 = north pole)
      longitude: 1440 points, 0.0 .. 359.75 (col 0 = prime meridian)

  Inputs:
    `lat` degrees north, -90..90
    `lon` degrees east, -180..180 (will be returned in 0..360)

  Returns {:row grid-row
           :col grid-col
           :lat latitude of the snapped cell center
           :lon longitude of the snapped cell center}"
  [lon lat]
  (let [lat0  90.0
        lon0  0.0
        step  0.25
        nrows 721
        ncols 1440
        lon   (mod lon 360.0)                ; normalize to [0, 360)
        row   (long (min (dec nrows)         ; clamp to the pole rows
                         (max 0.0 (rint (/ (- lat0 lat) step)))))
        col   (long (mod (rint (/ (- lon lon0) step)) ncols))]
    {:row row
     :col col
     :lat (- lat0 (* row step))
     :lon (+ lon0 (* col step))}))
