(ns climate-changed.frontend.app
  "Core app logic: location data loading fns and  views."
  (:require
   [bidi.bidi :as bidi]
   [climate-changed.common :as common]
   [climate-changed.era5.grid :as grid]
   [climate-changed.era5.variables :as vars]
   [climate-changed.frontend.charts :as charts]
   [climate-changed.frontend.interactive-map :as imap]
   [climate-changed.frontend.search :as search]
   [climate-changed.frontend.state :as state]
   [climate-changed.routes :as routes]
   [cljs.reader :as reader]
   [clojure.string :as str]))

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
  (-> (js/fetch (bidi/path-for routes/routes :locations))
      (.then (fn [resp] (.json resp)))
      (.then (fn [^js geojson]
               (swap! state/state assoc :all-locations
                      (mapv feature->loc (:features (js->clj geojson :keywordize-keys true))))
               (swap! state/state assoc :loading-locations? false)))
      (.catch (fn [err]
                (swap! state/state assoc :loading-locations? false :error (str err))))))
(comment
  (fetch-locations!))

(defn fetch-location-stats!
  "Fetch location-index stats from the :location-stats route and stash them in
  state. The response is EDN, so it is read with cljs.reader."
  []
  (-> (js/fetch (bidi/path-for routes/routes :location-stats))
      (.then (fn [resp]
               (if (.-ok resp)
                 (.text resp)
                 (throw (js/Error. (str "HTTP " (.-status resp)))))))
      (.then (fn [txt] (reader/read-string txt)))
      (.then (fn [stats]
               (swap! state/state assoc :location-stats stats)))
      (.catch (fn [err]
                (swap! state/state assoc :location-stats {:error (str err)})))))

(defn fetch-era5-summary!
  "Snap `loc` to the ERA5 grid and fetch that cell's climate summary into
  the `:era5-summary` key of the shared state atom."
  [{:keys [longitude latitude] :as loc}]
  (let [{:keys [row col]} (grid/snap-coords longitude latitude)]
    (swap! state/state assoc :era5-summary {:loading? true})
    (-> (js/fetch (bidi/path-for routes/routes :era5-summary :row row :col col))
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.json resp)
                   (throw (js/Error. (str "HTTP " (.-status resp)))))))
        (.then (fn [^js data]
                 (when (= loc (:selected-location @state/state))
                   (swap! state/state assoc :era5-summary (js->clj data :keywordize-keys true)))))
        (.then (fn [_] (fetch-location-stats!)))
        (.catch (fn [err]
                  (when (= loc (:selected-location @state/state))
                    (swap! state/state assoc :era5-summary {:error (str err)})))))))

(defonce era5-summary-watch
  ;; Whenever the selected location changes, (re)load the ERA5 summary for
  ;; its snapped grid cell; clear it when the selection is cleared.
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
  shows two decimals with its unit. Nil (a period with no observations)
  renders as an em dash."
  [x units]
  (if (nil? x)
    "—"
    (case units
      "K" (str (.toFixed (- x 273.15) 1) " °C")
      (str (.toFixed x 2) " " units))))

(defn- fmt-coord [x]
  (.toFixed x 2))

(defn- fmt-period [{:keys [start end]}]
  (cond
    (nil? start) "—"
    (nil? end)   (str (subs start 0 4) "–now")
    :else        (str (subs start 0 4) "–" (subs end 0 4))))

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
              ", lat: " (fmt-coord (:lat s)) "°, long: " (fmt-coord (:lon s)) "°")]
        [charts/mean-temperature-chart (:periods s) (:units s)]
        [:table {:class "summary-table"}
         [:thead
          [:tr [:th "period"] [:th "mean"] [:th "min"] [:th "max"] [:th "obs"]]]
         [:tbody
          (map-indexed
           (fn [i p]
             [:tr {:key i}
              [:td (fmt-period p)]
              [:td (fmt-value (:mean p) (:units s))]
              [:td (fmt-value (:min p) (:units s))]
              [:td (fmt-value (:max p) (:units s))]
              [:td (:n p)]])
           (:periods s))]]])]))

(defn app
  "Markup for the main application div, top level layout"
  []
  (let [{:keys [loading-locations? message error]} @state/state]
    [:div
     [:header {:class "app-header"}
      [:a {:class "app-title-link" :href "/"}
       [:h1 {:class "app-title"} common/appname]]
      (let [{:keys [n complete error]} (:location-stats @state/state)]
        [:span {:class "status"}
         (cond
           loading-locations? "Loading…"
           error              nil
           (and n (pos? n))
           (if (= n complete)
             (str n " locations indexed")
             (str complete " of " n " locations indexed"))
           :else nil)])]
     [:div {:class "side-panel"}
      [search/location-typeahead]
      [selected-location-view]
      [era5-summary-view]
      (when message [:p {:class "message"} message])
      (when error   [:p {:class "error"} error])]
     [imap/locations-map]]))
