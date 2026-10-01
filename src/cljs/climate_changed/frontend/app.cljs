(ns climate-changed.frontend.app
  "Core app logic: location data loading fns and  views."
  (:require
   [clojure.string :as str]
   [climate-changed.common :as common]
   [climate-changed.era5.grid :as grid]
   [climate-changed.era5.variables :as vars]
   [climate-changed.frontend.interactive-map :as imap]
   [climate-changed.frontend.search :as search]
   [climate-changed.frontend.state :as state]))

(defn feature->loc
  "Convert a keywordized GeoJSON feature into the flat location map used
  throughout the client:
  {:id :name :longitude :latitude :country :region :featurecla :population}."
  [{:keys [id geometry properties]}]
  (let [[lon lat] (:coordinates geometry)]
    {:id         id
     :name       (:name properties)
     :longitude  lon
     :latitude   lat
     :country    (:adm0name properties)
     :region     (:adm1name properties)
     :featurecla (:featurecla properties)
     :population (:pop_max properties)}))

(defn fetch-locations!
  "Hit the locations API, parse the GeoJSON body, reset `all-locations`
  and update `state` accordingly."
  []
  (swap! state/state assoc :loading-locations? true :error nil :message nil)
  (-> (js/fetch "/api/locations")
      (.then (fn [resp] (.json resp)))
      (.then (fn [^js geojson]
               (swap! state/state assoc :all-locations
                      (mapv feature->loc (:features (js->clj geojson :keywordize-keys true))))
               (swap! state/state assoc :loading-locations? false)))
      (.catch (fn [err]
                (swap! state/state assoc :loading-locations? false :error (str err))))))
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

(defonce era5-summary-watch
  ;; Whenever the selected location changes, (re)load the ERA5 summary for
  ;; its snapped grid cell; clear it when the selection is cleared.
  ;; performance implications? can we bail earlier and do less work if selected-locations hasn't changed
  (add-watch state/state :era5-summary
             (fn [_ _ old new]
               (let [loc (:selected-location new)]
                 (when (not= (:selected-location old) loc)
                   (if (and loc (:longitude loc) (:latitude loc))
                     (fetch-era5-summary! loc)
                     (swap! state/state assoc :era5-summary nil)))))))

(comment
  era5-summary-watch)

(defn- fmt-value
  "Format a summary statistic. Kelvin values render as °C; everything else
  shows two decimals with its unit."
  [x units]
  (case units
    "K" (str (.toFixed (- x 273.15) 1) " °C")
    (str (.toFixed x 2) " " units)))

(defn- fmt-coord [x]
  (.toFixed x 2))

(defn- fmt-period [{:keys [start end]}]
  (str (subs start 0 4) " – " (subs end 0 4)))

(defn- fmt-population
  "Render a population count with thousands separators, e.g. 1234567 -> \"1,234,567\"."
  [pop]
  (-> (str (js/Math.round pop))
      (.replace (js/RegExp. "\\B(?=(\\d{3})+(?!\\d))" "g") ",")))

(defn selected-location-view
  "Render the currently selected location as a small labeled card instead of
  dumping raw EDN."
  []
  (let [{:keys [name country region featurecla population] :as loc}
        (:selected-location @state/state)
        ;; Build a "Region, Country" subtitle, dropping blank/nil parts.
        place (->> [region country]
                   (remove (fn [s] (or (nil? s) (= "" s))))
                   (str/join ", "))]
    [:div {:class "dataview"}
     (if (nil? loc)
       [:p {:class "summary-hint"} "No location selected"]
       [:div {:class "location-card"}
        [:h3 {:class "location-name"} (or name "Unnamed location")]
        (when (not= "" place)
          [:p {:class "location-place"} place])
        (when (and featurecla (not= "" featurecla))
          [:p {:class "location-featurecla"} featurecla])
        (when (and population (pos? population))
          [:p {:class "location-population"}
           (str "Population: " (fmt-population population))])])]))

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
              ; " · cell (" (:row s) ", " (:col s) ") · "
              ", lat: " (fmt-coord (:lat s)) "°, long: " (fmt-coord (:lon s)) "°")]
        [:table {:class "summary-table"}
         [:thead
          [:tr [:th "period"] [:th "mean"] [:th "min"] [:th "max"] [:th "obs"]]]
         [:tbody
          (for [p (:periods s)]
            [:tr {:key (:start p)}
             [:td (fmt-period p)]
             [:td (fmt-value (:mean p) (:units s))]
             [:td (fmt-value (:min p) (:units s))]
             [:td (fmt-value (:max p) (:units s))]
             [:td (:n p)]])]]])]))

(defn app
  "Markup for the main application div, top level layout"
  []
  (let [{:keys [loading-locations? message error]} @state/state]
    [:div
     [:header {:class "app-header"}
      [:h1 {:class "app-title"} common/appname]
      [:span {:class "status"} (when loading-locations? "Loading…")]]
     [:div {:class "side-panel"}
      [search/location-typeahead]
      [selected-location-view]
      [era5-summary-view]
      (when message [:p {:class "message"} message])
      (when error   [:p {:class "error"} error])]
     [imap/locations-map]]))
