(ns climate-changed.frontend.app
  "Core app logic: location data loading fns and  views."
  (:require
   [cartoj.core :as cartoj]
   [cartoj.interop :as interop]
   [climate-changed.common :as shared]
   [climate-changed.era5.grid :as grid]
   [climate-changed.era5.variables :as vars]
   [climate-changed.frontend.interactive-map :as imap]
   [climate-changed.frontend.search :as search]
   [climate-changed.frontend.state :as state]
   [reagent.core :as r]))

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
               (swap! state/state assoc :all-locations
                      (mapv feature->loc (:features (js->clj geojson :keywordize-keys true))))
               (swap! state/state assoc :loading? false)))
      (.catch (fn [err]
                (swap! state/state assoc :loading? false :error (str err))))))
(comment
  (fetch-locations!))

(defn fetch-era5-summary!
  "Snap `loc` to the ERA5 grid and fetch that cell's climate summary into
  the `:era5-summary` key of the shared state atom."
  [{:keys [longitude latitude]}]
  (let [{:keys [row col]} (grid/snap-coords longitude latitude)]
    (swap! state/state assoc :era5-summary {:loading? true})
    (-> (js/fetch (str "/api/era5-summary/" row "/" col))
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.json resp)
                   (throw (js/Error. (str "HTTP " (.-status resp)))))))
        (.then (fn [^js data]
                 (swap! state/state assoc :era5-summary (js->clj data :keywordize-keys true))))
        (.catch (fn [err]
                  (swap! state/state assoc :era5-summary {:error (str err)}))))))

(defonce _era5-summary-watch
  ;; Whenever the selected location changes, (re)load the ERA5 summary for
  ;; its snapped grid cell; clear it when the selection is cleared.
  (add-watch state/state :era5-summary
             (fn [_ _ old new]
               (let [loc (:selected-location new)]
                 (when (not= (:selected-location old) loc)
                   (if (and loc (:longitude loc) (:latitude loc))
                     (fetch-era5-summary! loc)
                     (swap! state/state assoc :era5-summary nil)))))))

(defn- fmt-value
  "Format a summary statistic. Kelvin values render as °C; everything else
  shows two decimals with its unit."
  [x units]
  (case units
    "K" (str (.toFixed (- x 273.15) 1) " °C")
    (str (.toFixed x 2) " " units)))

(defn- fmt-coord [x]
  (.toFixed x 2))

(defn selected-location-view
  "Render the currently selected location as a small labeled card instead of
  dumping raw EDN."
  []
  (let [{:keys [name longitude latitude] :as loc} (:selected-location @state/state)]
    [:div {:class "dataview"}
     (if (nil? loc)
       [:p {:class "summary-hint"} "No location selected"]
       [:div {:class "location-card"}
        [:p {:class "location-name"} (or name "Unnamed location")]
        [:p {:class "location-coords"}
         (str (fmt-coord latitude) "°, " (fmt-coord longitude) "°")]])]))

(defn era5-summary-view []
  (let [s (:era5-summary @state/state)]
    [:div {:class "era5-summary"}
     (cond
       (nil? s)
       [:p {:class "summary-hint"} "Click a location to load its climate summary"]

       (:loading? s)
       [:p {:class "summary-hint"} "Loading climate summary…"]

       (:error s)
       [:p {:class "error"} (:error s)]

       :else
       [:div {:class "dataview"}
        [:p {:class "summary-title"}
         (str (get-in vars/era5-variables [(keyword (:var s)) :name] (:var s))
              " · cell (" (:row s) ", " (:col s) ") · "
              (fmt-coord (:lat s)) "°, " (fmt-coord (:lon s)) "°")]
        [:table {:class "summary-table"}
         [:tbody
          [:tr [:th "mean"] [:td (fmt-value (:mean s) (:units s))]]
          [:tr [:th "min"]  [:td (fmt-value (:min s) (:units s))]]
          [:tr [:th "max"]  [:td (fmt-value (:max s) (:units s))]]
          [:tr [:th "obs"]  [:td (:n s)]]
          [:tr [:th "period"] [:td (str (subs (:start s) 0 4) " – " (subs (:end s) 0 4))]]]]])]))

(defn app []
  (let [{:keys [loading? message error]} @state/state]
    [:div
     [:header {:class "app-header"}
      [:h1 {:class "app-title"} shared/appname]
      [:span {:class "status"} (if loading? "Loading…" "Ready")]]
     [:div {:class "side-panel"}
      [search/location-typeahead]
      [selected-location-view]
      [era5-summary-view]
      (when message [:p {:class "message"} message])
      (when error   [:p {:class "error"} error])]
     [cartoj/interactive-map
      {:initial-view-state {:longitude 0 :latitude 16 :zoom 2.5}
       :on-click           imap/click-handler
       :projection         "globe"
       :style-diffing      false
       :map-style          (:map-style @state/state)}
      [interop/reset-map-ref! (r/cursor state/state [:map-ref])]
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
