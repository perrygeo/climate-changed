(ns climate-changed.frontend.interactive-map
  "Interactive map interaction and styling. Primarily using Maplibre API via cartoj."
  (:require
   [cartoj.core :as cartoj]
   [cartoj.interop :as interop]
   [climate-changed.frontend.state :as state]))

(def light-style "/styles/light.json")

(def dark-style  "/styles/dark.json")

(def locations-layer-id "locations-layer")

(def locations-hitbox-layer-id "locations-hitbox")

(defn coords-to-maplibre [p]
  (clj->js {:lng (:longitude p)
            :lat (:latitude p)}))

(defn offset-coords [{:keys [longitude latitude]} offset]
  {:longitude (+ longitude offset)
   :latitude  latitude})

(defn- fly-to-location!
  "Fly the map to `loc` with the same animation used for typeahead selection."
  [loc]
  (when-let [^js m @state/map-ref]
    (.flyTo m (clj->js {:center   (coords-to-maplibre (offset-coords loc 1.2))
                        :zoom     7.5
                        :duration 12000}))))

(defn select-location!
  "Select `loc` and fly the map to it."
  [loc]
  (swap! state/state assoc :selected-location loc)
  (fly-to-location! loc))

(defn- layer-click-handler
  "Click handler for the locations layer: select the clicked feature and fly
  to it, just like typeahead selection."
  [^js e]
  (when-let [^js feature (aget (.-features e) 0)]
    (let [^js props  (.-properties feature)
          ^js coords (.-coordinates (.-geometry feature))]
      (select-location! {:name      (.-name props)
                         :longitude (aget coords 0)
                         :latitude  (aget coords 1)}))))

(defn- set-map-cursor!
  "Set the CSS cursor on the maplibre canvas."
  [^js m cursor]
  (let [^js canvas (.getCanvas m)]
    (set! (.-cursor (.-style canvas)) cursor)))

(defn- register-layer-events!
  "Register maplibre event listeners for the locations hitbox layer on `m`:
  click selects a location; mouseenter/leave toggle a pointer cursor. The
  hitbox layer is invisible but larger than the visible circle, giving a
  bigger click/hover target without changing the point's appearance."
  [^js m]
  (.on m "click" locations-hitbox-layer-id layer-click-handler)
  (.on m "mouseenter" locations-hitbox-layer-id (fn [_] (set-map-cursor! m "pointer")))
  (.on m "mouseleave" locations-hitbox-layer-id (fn [_] (set-map-cursor! m ""))))

(defonce locations-layer-events-watch
  ;; Once the maplibre Map is available in `state/map-ref`, attach the layer
  ;; events. Watching a dedicated atom means unrelated state swaps never fire
  ;; this watch.
  (add-watch state/map-ref :locations-layer-events
             (fn [_ _ old new]
               (when (and new (not (identical? old new)))
                 (register-layer-events! new)))))

(comment
  locations-layer-events-watch)

(defn set-globe! []
  (when-let [^js m @state/map-ref]
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

(defn locations-map
  "Render the interactive globe map with the locations GeoJSON source"
  []
  [cartoj/interactive-map {:initial-view-state {:longitude 0 :latitude 16 :zoom 2.5}
                           :projection         "globe"
                           :style-diffing      false
                           :map-style          (:map-style @state/state)}
   [interop/reset-map-ref! state/map-ref]
   [cartoj/source {:id   "locations"
                   :type "geojson"
                   :data "api/locations"}
    [cartoj/layer {:id     locations-layer-id
                   :type   "circle"
                   :source "locations"
                   ;; Scale point radius with zoom: ~2px at globe view, ~6px when flown in.
                   :paint  {:circle-radius       ["interpolate" ["linear"] ["zoom"]
                                                  2 2
                                                  7.5 6]
                            :circle-color        "#66f4"
                            :circle-stroke-width 1
                            :circle-stroke-color "#fff3"}}]
    [cartoj/layer {:id     locations-hitbox-layer-id
                   :type   "circle"
                   :source "locations"
                   :paint  {:circle-radius  16
                            :circle-opacity 0}}]]])
