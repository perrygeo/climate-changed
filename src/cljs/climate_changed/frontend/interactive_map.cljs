(ns climate-changed.frontend.interactive-map
  "Map interaction and styling: click handling, projection, coordinate
  helpers, flying to a location, and OS color-scheme tracking."
  (:require
   [cartoj.interop :as interop]
   [climate-changed.frontend.state :as state]))

(def light-style "/styles/light.json")

(def dark-style  "/styles/dark.json")

(defn click-handler [^js e]
  (swap! state/state assoc :selected-location (interop/coords-from-evt e)))

(defn set-globe! []
  (when-let [^js m (:map-ref @state/state)]
    (.setProjection m (clj->js {:type "globe"}))))

(defn watch-color-scheme!
  "Set the `:map-style` state key from the OS dark/light preference,
  updating on change."
  []
  (let [mql        (.matchMedia js/window "(prefers-color-scheme: dark)")
        set-style! (fn [^js m]
                     (swap! state/state assoc :map-style (if (.-matches m) dark-style light-style)))]
    (set-style! mql)
    (.addEventListener mql "change" set-style!)))

(defn coords-to-maplibre [p]
  (clj->js {:lng (:longitude p)
            :lat (:latitude p)}))

(defn offset-coords [{:keys [longitude latitude]} offset]
  {:longitude (+ longitude offset)
   :latitude  latitude})

(defn select-location! [loc]
  (swap! state/state assoc :selected-location loc)
  (when-let [^js m (:map-ref @state/state)]
    (.flyTo m (clj->js {:center   (coords-to-maplibre (offset-coords loc 1.2))
                        :zoom     7.5
                        :duration 12000}))))
