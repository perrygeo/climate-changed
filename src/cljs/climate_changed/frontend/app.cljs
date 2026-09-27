(ns climate-changed.frontend.app
  "Core business logic: location data loading and the top-level app view."
  (:require
   [cartoj.core :as cartoj]
   [cartoj.interop :as interop]
   [climate-changed.common :as shared]
   [climate-changed.era5.grid :as grid]
   [climate-changed.frontend.interactive-map :as imap]
   [climate-changed.frontend.search :as search]
   [climate-changed.frontend.state :as state]))

(defn feature->loc
  "Convert a keywordized GeoJSON feature into the flat location map used
  throughout the client: {:id :name :longitude :latitude}."
  [{:keys [id geometry properties]}]
  (let [[lon lat] (:coordinates geometry)]
    {:id        id
     :name      (:name properties)
     :longitude lon
     :latitude  lat}))

(defn fetch-locations!
  "Hit the locations API, parse the GeoJSON body, reset `all-locations`
  and update `state` accordingly."
  []
  (swap! state/state assoc :loading? true :error nil :message nil)
  (-> (js/fetch "/api/locations")
      (.then (fn [resp] (.json resp)))
      (.then (fn [^js geojson]
               (reset! state/all-locations
                       (mapv feature->loc (:features (js->clj geojson :keywordize-keys true))))
               (swap! state/state assoc :loading? false)))
      (.catch (fn [err]
                (swap! state/state assoc :loading? false :error (str err))))))
(comment
  (fetch-locations!))

(defn app []
  (let [{:keys [loading? message error]} @state/state]
    [:div
     [:header {:class "app-header"}
      [:h1 {:class "app-title"} shared/appname]
      [:span {:class "status"} (if loading? "Loading…" "Ready")]]
     [:div {:class "side-panel"}
      [search/location-typeahead]
      [:hr]
      [:div (str (grid/snap-coords (:longitude @state/selected-location) (:latitude @state/selected-location)))]
      [:hr]
      [:div {:class "dataview"} (str @state/selected-location)]
      (when message [:p {:class "message"} message])
      (when error   [:p {:class "error"} error])]
     [cartoj/interactive-map
      {:initial-view-state {:longitude 0 :latitude 16 :zoom 2.5}
       :on-click           imap/click-handler
       :projection         "globe"
       :style-diffing      false
       :map-style          @state/map-style}
      [interop/reset-map-ref! state/map-ref]
      [cartoj/source {:id   "cities"
                      :type "geojson"
                      :data "api/locations"}
       [cartoj/layer {:id     "cities-circles"
                      :type   "circle"
                      :source "cities"
                      :paint  {:circle-radius       4
                               :circle-color        "#ffb"
                               :circle-stroke-width 1
                               :circle-stroke-color "#a99"}}]]]]))
