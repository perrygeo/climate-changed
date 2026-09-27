(ns climate-changed.frontend.interactive-map
  "Map interaction and styling: click handling, projection, coordinate
  helpers, flying to a location, and OS color-scheme tracking."
  (:require
   [cartoj.interop :as interop]
   [climate-changed.frontend.state :as state]))

(defn click-handler [^js e]
  (reset! state/selected-location (interop/coords-from-evt e)))

(defn set-globe! []
  (when-let [^js m @state/map-ref]
    (.setProjection m (clj->js {:type "globe"}))))

(def light-style "https://pmtiles.perrygeo.com/styles/light.json")
(def dark-style  "https://pmtiles.perrygeo.com/styles/dark.json")

(defn watch-color-scheme!
  "Set `state/map-style` from the OS dark/light preference, updating on change."
  []
  (let [mql        (.matchMedia js/window "(prefers-color-scheme: dark)")
        set-style! (fn [^js m]
                     (reset! state/map-style (if (.-matches m) dark-style light-style)))]
    (set-style! mql)
    (.addEventListener mql "change" set-style!)))

(defn coords-to-maplibre [p]
  (clj->js {:lng (:longitude p)
            :lat (:latitude p)}))

(defn offset-coords [{:keys [longitude latitude]} offset]
  {:longitude (+ longitude offset)
   :latitude  latitude})

(defn select-location! [loc]
  (reset! state/selected-location loc)
  (when-let [^js m @state/map-ref]
    (.flyTo m (clj->js {:center   (coords-to-maplibre (offset-coords loc 1.2))
                        :zoom     7.5
                        :duration 5000}))))
