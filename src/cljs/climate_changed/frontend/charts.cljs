(ns climate-changed.frontend.charts
  "SVG heatmap chart for the ERA5 climate summary, built on d3-scale.

  The chart draws a 2D grid of time periods (x) and Celsius temperature bins
  (y). Cell fill encodes observation count on a sequential green ramp. In
  light mode few observations are the lightest green and the most are the
  darkest. In dark mode the ramp is reversed so sparse cells recede into the
  dark background instead of glaring white, and busy cells stand out bright.

  Two horizontal threshold lines mark the cold (0 °C, blue) and hot (30 °C,
  red) day boundaries.

  The y-axis is always aligned to Celsius. Fahrenheit labels are shown on the
  right purely for reference; the °C/°F display-unit toggle is intentionally
  not applied here."
  (:require
   ["d3-scale" :as d3-scale]
   [climate-changed.frontend.units :as u]
   [reagent.core :as r]))

;; The side panel is ~40% of the viewport, so keep the chart compact and
;; responsive via viewBox; SVG scales to its container width.
(def ^:private chart-width  480)
(def ^:private margin {:top 20 :right 48 :bottom 44 :left 48})

(def ^:private cell-height-px
  "Pixel height of a single 2 °C temperature cell. The chart grows taller with
  the number of bins so every cell keeps this height regardless of the
  location's temperature range (large-swing cells get a large grid)."
  16)

(def ^:private green-ramp
  "Sequential green color ramp (ColorBrewer YlGn, 9-class) for observation
  counts: lightest for few observations, darkest for the most."
  ["#ffffe5" "#f7fcb9" "#d9f0a3" "#addd8e" "#78c679"
   "#41ab5d" "#238443" "#006837" "#004529"])

(defonce ^:private dark-mode?
  ;; Tracks the OS/browser color scheme so the heatmap ramp can adapt. Kept in
  ;; a reagent atom with a media-query listener so a theme switch re-renders.
  (if (exists? js/window)
    (let [mq (js/window.matchMedia "(prefers-color-scheme: dark)")
          a  (r/atom (.-matches mq))]
      (.addEventListener mq "change" (fn [e] (reset! a (.-matches e))))
      a)
    (r/atom false)))

(defn- ramp
  "The active color ramp, ordered few → many observations. Reversed in dark
  mode so few observations are dark (recede) and many are bright (stand out)."
  []
  (if @dark-mode? (vec (rseq green-ramp)) green-ramp))

(def ^:private cold-threshold-c
  "Cold-day boundary in Celsius (freezing point)." 0)

(def ^:private hot-threshold-c
  "Hot-day boundary in Celsius (WMO/ETCCDI 'hot day', 30 °C)." 30)

(defn- period-label
  "Human label for a climate period, e.g. \"1940s\". Falls back to the index
  for periods with no start (which the reference periods always have)."
  [period i]
  (if-let [start-year (some-> (:start period) (subs 0 4))]
    (str start-year "s")
    (str i)))

(defn- cell-color
  "Map an observation count onto the active ramp. Empty bins get the
  few-observations end; the max-count bin gets the many-observations end."
  [n max-n]
  (let [colors (ramp)]
    (if (or (zero? max-n) (zero? n))
      (first colors)
      (let [last-i (dec (count colors))
            idx    (-> (/ n max-n) (* last-i) Math/round int)]
        (nth colors (min idx last-i))))))

(defn- bin-label
  "Tooltip label for a Celsius bin, e.g. \"-5–-4 °C\"."
  [bin]
  (str (.toFixed (:min bin) 0)
       "–"
       (.toFixed (:max bin) 0)
       " °C"))

(defn- ramp-legend
  "A small 0 → max-count green ramp in the top margin."
  [max-count x y bar-w bar-h]
  (let [steps 10]
    [:g {:class "temperature-heatmap__legend"}
     (for [i (range steps)]
       [:rect {:key    i
               :x      (+ x (* i (/ bar-w steps)))
               :y      y
               :width  (/ bar-w steps)
               :height bar-h
               :style  {:fill (cell-color (* max-count (/ i (dec steps)))
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

(defn- period-year
  "Start year of a period as an integer, or nil for reference periods."
  [period]
  (some-> (:start period) (subs 0 4) js/parseInt))

(defn- count-in-periods
  "Sum observation counts across `periods`, keeping only the bins for which
  `bin-pred` is truthy."
  [periods bins bin-pred]
  (reduce
   +
   0
   (for [p     periods
         j     (range (count bins))
         :when (bin-pred (nth bins j))]
     (nth (:counts p) j 0))))

(defn- freq
  "Fraction of `total` observed hours represented by `n` (0 when total is 0)."
  [n total]
  (if (zero? total) 0 (/ n total)))

(defn- fmt-pct
  "Format a 0\u20131 fraction as a percentage string with sensible precision.

  A nonzero fraction that rounds to less than 0.01% is rendered as \"<0.01%\"
  rather than \"0.00%\", so a tiny-but-real rate is visually distinct from an
  actually-zero one (otherwise the note contradicts the \"new\"/\"eliminated\"
  ratio labels)."
  [frac]
  (let [x (* 100 frac)]
    (cond
      (zero? frac) "0.00%"
      (< x 0.01)   "<0.01%"
      (< x 1)      (str (.toFixed x 2) "%")
      :else        (str (.toFixed x 1) "%"))))

(defn- fmt-multiplier
  "Format a >=1 multiplier. Uses 2 decimals when the multiplier is close
  enough to 1.0 that one decimal would read \"1.0\" (a real but minor
  change), and 1 decimal otherwise."
  [m]
  (if (< m 1.05)
    (.toFixed m 2)
    (.toFixed m 1)))

(defn- fmt-ratio
  "Describe the change from `base-rate` to `mod-rate` (frequencies, so the
  comparison is unaffected by the two periods covering different numbers of
  observed hours) as a multiplier, e.g. \"4.5\u00d7 more\" / \"2.0\u00d7 fewer\". Handles
  zero rates gracefully."
  [base-rate mod-rate]
  (cond
    (and (zero? base-rate) (zero? mod-rate)) "no change"
    (zero? base-rate)                        "new (none in baseline)"
    (zero? mod-rate)                         "none (eliminated)"
    :else
    (let [r (/ mod-rate base-rate)]
      (if (>= r 1)
        (str (fmt-multiplier r) "\u00d7 more")
        (str (fmt-multiplier (/ 1 r)) "\u00d7 fewer")))))

(defn summary-facts
  "Headline climate facts comparing a recent period (2000 \u2192 today) against a
  1940\u20131970 baseline, computed purely from the binned ERA5 summary counts.

  Hot/cold hours are summed from the temperature bins at or beyond the hot
  (30 \u00b0C) and cold (0 \u00b0C) thresholds. The recent period includes an
  incomplete current decade (data only runs to the latest observation, ~2026),
  so raw totals would understate it; instead we compare the FREQUENCY of
  hot/cold hours (count \u00f7 total observed hours in the period). That
  normalization also absorbs any data gaps.

  Mean temperature can't be recovered from binned counts, so it's left as a
  TODO."
  [summary]
  (let [bins    (:bins summary)
        periods (:periods summary)]
    (when (and (seq bins) (seq periods))
      (let [baseline   (filter #(when-let [y (period-year %)]
                                  (<= 1940 y 1969))
                               periods)
            modern     (filter #(when-let [y (period-year %)]
                                  (>= y 2000))
                               periods)
            hot?       (fn [b] (>= (:min b) hot-threshold-c))
            cold?      (fn [b] (<= (:max b) cold-threshold-c))
            all?       (constantly true)
            total-base (count-in-periods baseline bins all?)
            total-mod  (count-in-periods modern bins all?)
            hot-base   (count-in-periods baseline bins hot?)
            hot-mod    (count-in-periods modern bins hot?)
            cold-base  (count-in-periods baseline bins cold?)
            cold-mod   (count-in-periods modern bins cold?)]
        (when (and (seq baseline) (seq modern))
          [:div {:class "summary-facts"}
           [:p {:class "summary-facts__intro"}
            "2000\u2013today vs. the 1940\u20131970 baseline"]
           [:ul {:class "summary-facts__list"}
            [:li
             [:span {:class "summary-facts__label"} "Hot hours (\u226530 \u00b0C): "]
             [:span {:class "summary-facts__value"}
              (fmt-ratio (freq hot-base total-base) (freq hot-mod total-mod))]
             [:span {:class "summary-facts__note"}
              (str " (" (fmt-pct (freq hot-base total-base))
                   " \u2192 " (fmt-pct (freq hot-mod total-mod)) " likely)")]]
            [:li
             [:span {:class "summary-facts__label"} "Cold hours (\u22640 \u00b0C): "]
             [:span {:class "summary-facts__value"}
              (fmt-ratio (freq cold-base total-base) (freq cold-mod total-mod))]
             [:span {:class "summary-facts__note"}
              (str " (" (fmt-pct (freq cold-base total-base))
                   " \u2192 " (fmt-pct (freq cold-mod total-mod)) " likely)")]]
            [:li
             [:span {:class "summary-facts__label"} "Mean temperature: "]
             ;; TODO: mean can't be computed from binned counts; needs the
             ;; underlying series (or per-bin sums) from the server.
             [:span {:class "summary-facts__todo"} "TODO (not derivable from binned data)"]]]])))))

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
      (let [chart-height (+ (:top margin) (:bottom margin)
                            (* cell-height-px (count bins)))
            labels       (mapv (fn [i p] (period-label p i)) (range) periods)
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
                    :style  {:fill (cell-color n max-count)}}
             [:title (str (nth labels i) " " (bin-label b) ": " n " obs")]])
          ;; cold (0 \u00b0C) and hot (30 \u00b0C) threshold lines
          (for [[t color] [[cold-threshold-c "#2166ac"] [hot-threshold-c "#b2182b"]]
                :when     (<= lo t hi)]
            [:line {:key             (str "threshold-" t)
                    :x1              0
                    :x2              inner-width
                    :y1              (y-scale t)
                    :y2              (y-scale t)
                    :stroke          color
                    :stroke-width    1
                    :shape-rendering "crispEdges"}])
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
