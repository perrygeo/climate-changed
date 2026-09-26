(ns climate-changed.era5-grid)

;; TODO def era-variables

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

