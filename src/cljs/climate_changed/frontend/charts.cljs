(ns climate-changed.frontend.charts
  "SVG heatmap chart for the ERA5 climate summary, built on d3-scale.

  The chart draws a 2D grid of time periods (x) and Celsius temperature bins
  (y). Cell opacity encodes observation count on a neutral ramp — the theme's
  text color at low opacity for empty bins and full opacity for the period ×
  bin with the most observations. Opacity (not hue) is used deliberately so
  the ramp reads as MORE OBSERVATIONS rather than hot or cold.

  The y-axis is always aligned to Celsius. Fahrenheit labels are shown on the
  right purely for reference; the °C/°F display-unit toggle is intentionally
  not applied here."
  (:require
   ["d3-scale" :as d3-scale]
   [climate-changed.frontend.units :as u]))

;; The side panel is ~40% of the viewport, so keep the chart compact and
;; responsive via viewBox; SVG scales to its container width.
(def ^:private chart-width  480)
(def ^:private chart-height 440)
(def ^:private margin {:top 20 :right 48 :bottom 44 :left 48})

(defn- period-label
  "Human label for a climate period, e.g. \"1940s\". Falls back to the index
  for periods with no start (which the reference periods always have)."
  [period i]
  (if-let [start-year (some-> (:start period) (subs 0 4))]
    (str start-year "s")
    (str i)))

(defn- cell-opacity
  "Neutral ramp from empty (barely visible) to full-intensity text color."
  [n max-n]
  (if (or (zero? max-n) (zero? n))
    0.04
    (+ 0.08 (* 0.87 (/ n max-n)))))

(defn- bin-label
  "Tooltip label for a Celsius bin, e.g. \"-5–-4 °C\"."
  [bin]
  (str (.toFixed (:min bin) 0)
       "–"
       (.toFixed (:max bin) 0)
       " °C"))

(defn- ramp-legend
  "A small 0 → max-count neutral ramp in the top margin."
  [max-count x y bar-w bar-h]
  (let [steps 10]
    [:g {:class "temperature-heatmap__legend"}
     (for [i (range steps)]
       [:rect {:key    i
               :x      (+ x (* i (/ bar-w steps)))
               :y      y
               :width  (/ bar-w steps)
               :height bar-h
               :style  {:fill    "var(--text-primary)"
                        :opacity (cell-opacity (* max-count (/ i (dec steps)))
                                               max-count)}}])
     [:text {:x         (+ x bar-w 6)
             :y         (+ y bar-h 1)
             :class     "chart-axis-label"
             :font-size "0.65rem"}
      (str max-count " obs")]
     [:text {:x           (- x 4)
             :y           (+ y bar-h 1)
             :text-anchor "end"
             :class       "chart-axis-label"
             :font-size   "0.65rem"}
      "0"]]))

(defn temperature-heatmap
  "Observation-count heatmap for an ERA5 summary.

  `summary` is the :era5-summary map from the API:
    :bins      [{:min :max} ...] temperature bin edges (Celsius for t2m)
    :periods   [{:start :end :counts [:n ...]} ...] one per decade
    :max-count denominator for the color ramp

  The y-axis stays in Celsius; Fahrenheit reference labels are drawn on the
  right, independent of the global display-unit toggle."
  [summary]
  (let [bins      (:bins summary)
        periods   (:periods summary)
        max-count (or (:max-count summary) 0)]
    (if (empty? bins)
      [:p {:class "chart-hint"} "No temperature observations for this cell"]
      (let [labels       (mapv (fn [i p] (period-label p i)) (range) periods)
            inner-width  (- chart-width (:left margin) (:right margin))
            inner-height (- chart-height (:top margin) (:bottom margin))
            x-scale      (doto (d3-scale/scaleBand)
                           (.domain (clj->js labels))
                           (.range (clj->js [0 inner-width]))
                           (.padding 0))
            [lo hi]      (let [lo (:min (first bins))
                               hi (:max (last bins))]
                           (if (= lo hi) [(- lo 1) (+ hi 1)] [lo hi]))
            y-scale      (doto (d3-scale/scaleLinear)
                           (.domain (clj->js [lo hi]))
                           (.range (clj->js [inner-height 0])))
            y-ticks      (js->clj (.ticks y-scale 6))
            band-w       (.bandwidth x-scale)]
        [:svg {:class   "temperature-heatmap"
               :viewBox (str "0 0 " chart-width " " chart-height)}
         [:g {:transform (str "translate(" (:left margin) ",6)")}
          [ramp-legend max-count 0 0 64 8]]
         [:g {:transform (str "translate(" (:left margin) "," (:top margin) ")")}
          ;; observation-count grid, drawn as a continuous raster
          (for [i    (range (count periods))
                j    (range (count bins))
                :let [p      (nth periods i)
                      b      (nth bins j)
                      n      (nth (:counts p) j 0)
                      y-top  (y-scale (:max b))
                      y-bot  (y-scale (:min b))
                      height (max 0 (- y-bot y-top))]]
            [:rect {:key    (str "cell-" i "-" j)
                    :x      (x-scale (nth labels i))
                    :y      y-top
                    :width  band-w
                    :height height
                    :class  "temperature-heatmap__cell"
                    :style  {:fill    "var(--text-primary)"
                             :opacity (cell-opacity n max-count)}}
             [:title (str (nth labels i) " " (bin-label b) ": " n " obs")]])
          ;; y-axis Celsius labels on the left
          (for [t y-ticks]
            (let [y (y-scale t)]
              [:text {:key               (str "yl-" t)
                      :x                 -6
                      :y                 y
                      :text-anchor       "end"
                      :dominant-baseline "middle"
                      :class             "chart-axis-label"}
               (str (.toFixed t 0) "°C")]))
          ;; y-axis Fahrenheit reference labels on the right
          (for [t y-ticks]
            (let [y (y-scale t)]
              [:text {:key               (str "yr-" t)
                      :x                 (+ inner-width 6)
                      :y                 y
                      :text-anchor       "start"
                      :dominant-baseline "middle"
                      :class             "chart-axis-label"}
               (str (.toFixed (u/celsius->fahrenheit t) 0) "°F")]))
          ;; x-axis period labels (rotated so adjacent decades stay legible)
          (for [l    labels
                :let [label-x (+ (x-scale l) (/ band-w 2))]]
            [:text {:key         (str "xlabel-" l)
                    :x           label-x
                    :y           (+ inner-height 10)
                    :text-anchor "end"
                    :transform   (str "rotate(-45 " label-x " " (+ inner-height 10) ")")
                    :class       "chart-axis-label"}
             l])]]))))

(comment
  (temperature-heatmap
   {:bin-unit  "°C"
    :bins      [{:min -10 :max -5} {:min -5 :max 0} {:min 0 :max 5} {:min 5 :max 10}]
    :periods   [{:start "1940-01-01T00:00:00Z" :end "1950-01-01T00:00:00Z" :counts [0 2 5 1] :n 8}
                {:start "1950-01-01T00:00:00Z" :end "1960-01-01T00:00:00Z" :counts [0 3 8 2] :n 13}
                {:start "1960-01-01T00:00:00Z" :end nil :counts [1 4 10 3] :n 18}]
    :max-count 10}))
