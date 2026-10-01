(ns climate-changed.frontend.charts
  "SVG chart components for the climate summary, built on the modular d3
  libraries (d3-scale, d3-shape, d3-array).

  Chosen over React chart wrappers (Recharts, Victory, nivo, ...) because:
  - d3 is version-agnostic JS with no React peer dependency, so it cannot
    break on React 19 upgrades the way wrapper libraries can.
  - the modular packages are pure functions (scales, shapes, arrays) that
    compose cleanly with Reagent's declarative Hiccup — no imperative
    refs/effects or per-chart React components needed.
  - it scales from this basic line chart to the full exploration: histograms
    (d3-array/bin), ECDF/violin plots (d3-shape/area + curves), full time
    series (d3-scale/scaleTime), and anomaly ribbons (d3-shape/area)."
  (:require
   ["d3-array" :as d3-array]
   ["d3-scale" :as d3-scale]
   ["d3-shape" :as d3-shape]))

;; The side panel is ~40% of the viewport, so keep the chart compact and
;; responsive via viewBox; SVG scales to its container width.
(def ^:private chart-width  320)
(def ^:private chart-height 200)
(def ^:private margin {:top 16 :right 16 :bottom 44 :left 44})

(defn- kelvin->celsius [k]
  (- k 273.15))

(defn- display-temp
  "Raw temperature in display units (°C for Kelvin values)."
  [v units]
  (if (= units "K") (kelvin->celsius v) v))

(defn- period-temp
  "Temperature at key `k` of `period` in display units, or nil when absent."
  [period k units]
  (some-> (k period) (display-temp units)))

(defn- period-label
  "Human label for a climate period, e.g. \"1940–1970\". Falls back to the
  index for periods with no observations (nil start/end)."
  [period i]
  (let [start-year (some-> (:start period) (subs 0 4))
        end-year   (some-> (:end period) (subs 0 4))]
    (if start-year
      (str start-year "–" (or end-year "now"))
      (str i))))

(defn- period-series
  "Chart points for temperature key `k` across `periods`, dropping periods
  with no value."
  [periods k units]
  (->> periods
       (map-indexed (fn [i p]
                      {:label (period-label p i)
                       :value (period-temp p k units)}))
       (filterv :value)
       vec))

(defn mean-temperature-chart
  "Line chart of min, mean, and max temperature across the climate periods.
  `periods` is the :periods vector from an ERA5 summary; `units` its :units
  string."
  [periods units]
  (let [labels (mapv (fn [i p] (period-label p i)) (range) periods)
        series [{:id    :mean
                 :label "mean"
                 :class "chart-line--mean"
                 :pts   (period-series periods :mean units)}
                {:id    :min
                 :label "min"
                 :class "chart-line--min"
                 :pts   (period-series periods :min units)}
                {:id    :max
                 :label "max"
                 :class "chart-line--max"
                 :pts   (period-series periods :max units)}]
        mean-pts (:pts (first series))]
    (if (empty? mean-pts)
      [:p {:class "chart-hint"} "No mean temperature data for this cell"]
      (let [inner-width  (- chart-width (:left margin) (:right margin))
            inner-height (- chart-height (:top margin) (:bottom margin))
            ;; The y-axis spans the full observed range across min, mean, and
            ;; max for a realistic scale.
            [lo hi]      (let [all-vals (vec (mapcat (fn [s] (map :value (:pts s))) series))
                               [mn mx]   (js->clj (d3-array/extent (clj->js all-vals)))]
                           (if (= mn mx)
                             [(- mn 1) (+ mx 1)]
                             (let [pad (* 0.1 (- mx mn))]
                               [(- mn pad) (+ mx pad)])))
            x-scale      (doto (d3-scale/scalePoint)
                           (.domain (clj->js labels))
                           (.range (clj->js [0 inner-width]))
                           (.padding 0.5))
            y-scale      (doto (d3-scale/scaleLinear)
                           (.domain (clj->js [lo hi]))
                           (.range (clj->js [inner-height 0])))
            line-gen     (doto (d3-shape/line)
                           (.x (fn [^js d] (x-scale (.-label d))))
                           (.y (fn [^js d] (y-scale (.-value d)))))
            y-ticks      (js->clj (.ticks y-scale 5))]
        [:svg {:class   "mean-temperature-chart"
               :viewBox (str "0 0 " chart-width " " chart-height)}
         ;; legend
         [:g {:class     "chart-legend"
              :transform (str "translate(" (:left margin) ",6)")}
          (for [[i s] (map-indexed vector (filterv #(seq (:pts %)) series))]
            (let [x (* i 52)]
              [:g {:key       (str "legend-" (:id s))
                   :transform (str "translate(" x ",0)")}
               [:line {:x1 0 :y1 0 :x2 14 :y2 0 :class (str "chart-line " (:class s))}]
               [:text {:x 18 :y 3 :class "chart-axis-label"} (:label s)]]))]
         [:g {:transform (str "translate(" (:left margin) "," (:top margin) ")")}
          ;; y-axis gridlines + tick labels
          (for [t y-ticks]
            (let [y (y-scale t)]
              [:g {:key (str "ytick-" t)}
               [:line {:x1 0 :y1 y :x2 inner-width :y2 y :class "chart-gridline"}]
               [:text {:x     -6                 :y y :text-anchor "end" :dominant-baseline "middle"
                       :class "chart-axis-label"}
                (.toFixed t 1)]]))
          ;; x-axis period labels (rotated so adjacent decades stay legible)
          (for [l labels]
            [:text {:key         (str "xlabel-" l)
                    :x           (x-scale l)
                    :y           (+ inner-height 10)
                    :text-anchor "end"
                    :transform   (str "rotate(-45 " (x-scale l) " " (+ inner-height 10) ")")
                    :class       "chart-axis-label"}
             l])
          ;; one line per series
          (for [s series
                :when (seq (:pts s))]
            [:path {:key   (str "line-" (:id s))
                    :d     (line-gen (clj->js (:pts s)))
                    :class (str "chart-line " (:class s))}])
          ;; points, with a hover title
          (for [s series
                p (:pts s)]
            [:g {:key (str "point-" (:id s) "-" (:label p))}
             [:circle {:cx    (x-scale (:label p))
                       :cy    (y-scale (:value p))
                       :r     4
                       :class (str "chart-point chart-point--" (name (:id s)))}]
             [:title (str (:label s) " " (:label p) ": " (.toFixed (:value p) 1) " °C")]])]]))))

(comment
  (mean-temperature-chart
   [{:start "1940-01-01T00:00:00Z" :end "1970-01-01T00:00:00Z" :mean 281.5 :min 280.0 :max 283.0}
    {:start "1970-01-01T00:00:00Z" :end "2000-01-01T00:00:00Z" :mean 282.1 :min 279.5 :max 284.5}
    {:start "2000-01-01T00:00:00Z" :end nil :mean 283.0 :min 281.0 :max 285.5}]
   "K"))
