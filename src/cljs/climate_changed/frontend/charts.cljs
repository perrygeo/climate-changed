(ns climate-changed.frontend.charts
  "SVG heatmap chart for the ERA5 climate summary, built on d3-scale.

  The chart draws a 2D grid of time periods (x) and Celsius temperature bins
  (y). Cell fill encodes observation count as a theme-aware fill color with
  continuous opacity: more observations make a cell more opaque (solid dark
  in light mode, solid white in dark mode) while fewer fade toward the chart
  background, and zero-observation cells are fully transparent. A thin light
  grid separates the cells.

  The opacity scale is logarithmic so the rare bins at the extreme
  temperature tails stay visible: a single observation is clearly distinct
  from zero, low counts stay separated, and dense bins saturate toward the
  solid fill.

  Two horizontal threshold lines mark the cold (0 °C, blue) and hot (30 °C,
  red) day boundaries.

  The y-axis is always aligned to Celsius. Fahrenheit labels are shown on the
  right purely for reference; the °C/°F display-unit toggle is intentionally
  not applied here."
  (:require
   ["d3-scale" :as d3-scale]
   [climate-changed.frontend.units :as u]
   [clojure.string :as string]
   [reagent.core :as r]))

;; The side panel is ~40% of the viewport, so keep the chart compact and
;; responsive via viewBox; SVG scales to its container width.
(def ^:private chart-width  480)
(def ^:private margin {:top 20 :right 48 :bottom 44 :left 48})

(def ^:private cell-height-px
  "Preferred pixel height of a single temperature cell. Cells shrink below
  this when a location has enough bins that the chart would exceed
  `max-chart-height`."
  16)

(def ^:private max-chart-height
  "Upper bound for the chart's viewBox height. Wide temperature ranges use
  more bins; cells shrink instead of letting the chart grow unbounded."
  600)

(def ^:private min-cell-opacity
  "Opacity assigned to the faintest nonzero cell. Kept clearly above zero so
  a bin with a single observation reads as present rather than empty."
  0.22)

(defn- cell-opacity
  "Map an observation count onto a continuous 0\u20131 fill opacity.

  Zero-count cells are fully transparent. Nonzero counts follow a log curve
  so sparse, extreme-temperature bins stay visible: low counts are lifted
  well above zero and stay separated from each other while dense bins
  saturate toward the solid fill color (theme-aware, see --chart-cell)."
  [n max-n]
  (if (or (zero? max-n) (zero? n))
    0.0
    (min 1.0
         (+ min-cell-opacity
            (* (- 1.0 min-cell-opacity)
               (/ (Math/log (+ 1 n))
                  (Math/log (+ 1 max-n))))))))

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

(defn- bin-label
  "Tooltip label for a Celsius bin, e.g. \"-5–-4 °C\"."
  [bin]
  (str (.toFixed (:min bin) 0)
       "–"
       (.toFixed (:max bin) 0)
       " °C"))

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

(defn- fmt-signed
  "Format `x` with a leading + when positive, e.g. 0.9 -> \"+0.9\"."
  [x digits]
  (str (when (pos? x) "+") (.toFixed x digits)))

(defn- weighted-mean-c
  "Weighted mean Celsius temperature across `mean-periods` whose start year
  satisfies `year-pred`. Returns nil when there are no observations."
  [mean-periods year-pred]
  (let [ps      (filter #(when-let [y (period-year %)]
                           (and (some? (:mean %))
                                (pos? (:n %))
                                (year-pred y)))
                        mean-periods)
        total-n (reduce + 0 (map :n ps))]
    (when (pos? total-n)
      (/ (reduce + 0 (map (fn [p] (* (:mean p) (:n p))) ps))
         total-n))))

(defn summary-facts
  "Headline climate facts comparing a recent period (2000 \u2192 today) against a
  1940\u20131970 baseline.

  Hot/cold hours are summed from the binned ERA5 summary counts; the mean
  temperature row uses the per-decade `means` endpoint and reports the signed
  change from the baseline mean. Temperature readouts are formatted in
  `temp-unit` (:c or :f)."
  [summary means temp-unit]
  (let [bins           (:bins summary)
        periods        (:periods summary)
        mean-periods   (:periods means)
        unit-symbol    (u/temp-unit-symbol temp-unit)
        display-c      (fn [c] (u/celsius->display c temp-unit))
        threshold-label (fn [c] (str (.toFixed (display-c c) 0) " " unit-symbol))]
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
            cold-mod   (count-in-periods modern bins cold?)
            base-mean  (weighted-mean-c mean-periods #(<= 1940 % 1969))
            mod-mean   (weighted-mean-c mean-periods #(>= % 2000))]
        (when (and (seq baseline) (seq modern))
          [:div {:class "summary-facts"}
           [:p {:class "summary-facts__intro"}
            "1940-1970 baseline vs. 2000-today"]
           [:ul {:class "summary-facts__list"}
            [:li
             [:span {:class "summary-facts__label"} "Mean temperature: "]
             [:span {:class "summary-facts__value"}
              (if (and base-mean mod-mean)
                (let [base-d (display-c base-mean)
                      mod-d  (display-c mod-mean)]
                  (str (fmt-signed (- mod-d base-d) 1) unit-symbol))
                "\u2014")]
             [:span {:class "summary-facts__note"}
              (when (and base-mean mod-mean)
                (let [base-d (display-c base-mean)
                      mod-d  (display-c mod-mean)]
                  (str " (" (.toFixed base-d 1) " " unit-symbol
                       " \u2192 " (.toFixed mod-d 1) " " unit-symbol ")")))]]
            [:li
             [:span {:class "summary-facts__label"}
              (str "Hot hours (\u2265" (threshold-label hot-threshold-c) "): ")]
             [:span {:class "summary-facts__value"}
              (fmt-ratio (freq hot-base total-base) (freq hot-mod total-mod))]
             [:span {:class "summary-facts__note"}
              (str " (" (fmt-pct (freq hot-base total-base))
                   " \u2192 " (fmt-pct (freq hot-mod total-mod)) " likely)")]]
            [:li
             [:span {:class "summary-facts__label"}
              (str "Cold hours (\u2264" (threshold-label cold-threshold-c) "): ")]
             [:span {:class "summary-facts__value"}
              (fmt-ratio (freq cold-base total-base) (freq cold-mod total-mod))]
             [:span {:class "summary-facts__note"}
              (str " (" (fmt-pct (freq cold-base total-base))
                   " \u2192 " (fmt-pct (freq cold-mod total-mod)) " likely)")]]]])))))

(defn temperature-heatmap
  "Observation-count heatmap for an ERA5 summary.

  `summary` is the :era5-summary map from the API:
    :bins      [{:min :max} ...] temperature bin edges (Celsius for t2m)
    :periods   [{:start :end :counts [:n ...]} ...] one per decade
    :max-count denominator for the continuous opacity scale

  `means` is the :era5-means map from the API:
    :periods   [{:start :end :mean :n} ...] one per decade

  A per-decade mean temperature line is drawn over the cells. The y-axis stays
  in Celsius; Fahrenheit reference labels are drawn on the right, independent
  of the global display-unit toggle.

  Hover state is managed in a Reagent atom instead of native SVG `<title>`
  elements, so the tooltip appears and switches instantly as the pointer moves
  between cells."
  [_summary _means]
  (let [hover (r/atom nil)]
    (fn [summary means]
      (let [bins         (:bins summary)
            periods      (:periods summary)
            mean-periods (or (:periods means) [])
            max-count    (or (:max-count summary) 0)]
        (if (empty? bins)
          [:p {:class "chart-hint"} "No temperature observations for this cell"]
          (let [nbins         (count bins)
                cell-h        (min cell-height-px
                                   (/ (- max-chart-height (:top margin) (:bottom margin))
                                      (max 1 nbins)))
                chart-height  (+ (:top margin) (:bottom margin)
                                 (* cell-h nbins))
                labels        (mapv (fn [i p] (period-label p i)) (range) periods)
                inner-width   (- chart-width (:left margin) (:right margin))
                inner-height  (- chart-height (:top margin) (:bottom margin))
                x-scale       (doto (d3-scale/scaleBand)
                                (.domain (clj->js labels))
                                (.range (clj->js [0 inner-width]))
                                (.padding 0))
                [lo hi]       (let [lo (:min (first bins))
                                    hi (:max (last bins))]
                                (if (= lo hi) [(- lo 1) (+ hi 1)] [lo hi]))
                y-scale       (doto (d3-scale/scaleLinear)
                                (.domain (clj->js [lo hi]))
                                (.range (clj->js [inner-height 0])))
                y-ticks       (js->clj (.ticks y-scale 6))
                band-w        (.bandwidth x-scale)
                mean-by-start (into {} (map (juxt :start identity) mean-periods))
                mean-pts      (vec
                               (keep-indexed
                                (fn [i p]
                                  (when-let [{:keys [mean]} (get mean-by-start (:start p))]
                                    (when (some? mean)
                                      {:x (+ (x-scale (nth labels i)) (/ band-w 2))
                                       :y (y-scale mean)})))
                                periods))]
            [:div {:class "temperature-heatmap__wrap"}
             [:svg {:class          "temperature-heatmap"
                    :viewBox        (str "0 0 " chart-width " " chart-height)
                    :on-mouse-leave #(reset! hover nil)}
              [:g {:transform (str "translate(" (:left margin) "," (:top margin) ")")}
               ;; observation-count cells
               (for [i    (range (count periods))
                     j    (range (count bins))
                     :let [p      (nth periods i)
                           b      (nth bins j)
                           n      (nth (:counts p) j 0)
                           y-top  (y-scale (:max b))
                           y-bot  (y-scale (:min b))
                           height (max 0 (- y-bot y-top))]]
                 [:rect {:key            (str "cell-" i "-" j)
                         :x              (x-scale (nth labels i))
                         :y              y-top
                         :width          band-w
                         :height         height
                         :class          "temperature-heatmap__cell"
                         :fill           "var(--chart-cell)"
                         :fill-opacity   (cell-opacity n max-count)
                         :on-mouse-enter (fn [e]
                                           (reset! hover
                                                   {:label (nth labels i)
                                                    :bin   (bin-label b)
                                                    :n     n
                                                    :x     (.-clientX e)
                                                    :y     (.-clientY e)}))}])
               ;; cold (0 \u00b0C) and hot (30 \u00b0C) threshold lines
               (for [[t color] [[cold-threshold-c "#2166ac"] [hot-threshold-c "#b2182b"]]
                     :when     (<= lo t hi)]
                 [:line {:key             (str "threshold-" t)
                         :x1              0
                         :x2              inner-width
                         :y1              (y-scale t)
                         :y2              (y-scale t)
                         :stroke          color
                         :stroke-width    1.2
                         :shape-rendering "crispEdges"}])
               ;; per-decade mean temperature line (from the :era5-means API)
               (when (>= (count mean-pts) 2)
                 [:polyline {:key             "mean-line"
                             :points          (string/join " " (map (fn [{:keys [x y]}] (str x "," y)) mean-pts))
                             :class           "temperature-heatmap__mean-line"
                             :fill            "none"
                             :stroke          "#f4a261"
                             :stroke-width    2
                             :stroke-linejoin "round"
                             :stroke-linecap  "round"}])
               (for [[i {:keys [x y]}] (map-indexed vector mean-pts)]
                 [:circle {:key   (str "mean-pt-" i)
                           :cx    x
                           :cy    y
                           :r     2.5
                           :class "temperature-heatmap__mean-point"
                           :fill  "#f4a261"}])
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
                  l])]]
             (when-let [{:keys [x y label bin n]} @hover]
               (let [tip-x (min (+ x 12) (max 12 (- js/window.innerWidth 200)))
                     tip-y (min (+ y 12) (max 12 (- js/window.innerHeight 48)))]
                 [:div {:class "temperature-heatmap__tooltip"
                        :style {:left (str tip-x "px")
                                :top  (str tip-y "px")}}
                  (str label " " bin ": " n " obs")]))]))))))

(comment
  (temperature-heatmap
   {:bin-unit  "°C"
    :bins      [{:min -10 :max -5} {:min -5 :max 0} {:min 0 :max 5} {:min 5 :max 10}]
    :periods   [{:start "1940-01-01T00:00:00Z" :end "1950-01-01T00:00:00Z" :counts [0 2 5 1] :n 8}
                {:start "1950-01-01T00:00:00Z" :end "1960-01-01T00:00:00Z" :counts [0 3 8 2] :n 13}
                {:start "1960-01-01T00:00:00Z" :end nil :counts [1 4 10 3] :n 18}]
    :max-count 10}
   {:periods [{:start "1940-01-01T00:00:00Z" :end "1950-01-01T00:00:00Z" :mean 2.0 :n 8}
              {:start "1950-01-01T00:00:00Z" :end "1960-01-01T00:00:00Z" :mean 3.0 :n 13}
              {:start "1960-01-01T00:00:00Z" :end nil :mean 4.0 :n 18}]}))
