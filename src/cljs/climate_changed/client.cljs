(ns climate-changed.client
  (:require
   ["maplibre-gl/dist/maplibre-gl.css"]
   [cartoj.core :as cartoj]
   [cartoj.interop :as interop]
   [climate-changed.era5-grid :as grid]
   [climate-changed.shared :as shared]
   [clojure.string :as str]
   [garden.core :refer [css]]
   [reagent.core :as r]
   [reagent.dom.client :as rdom]))

(defonce state
  (r/atom {:loading? false
           :message  nil
           :error    nil}))

(defonce all-locations (r/atom nil))

(defonce selected-location (r/atom nil))

(defonce map-ref (r/atom nil))

;; ================================================================

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
  (swap! state assoc :loading? true :error nil :message nil)
  (-> (js/fetch "/api/locations")
      (.then (fn [resp] (.json resp)))
      (.then (fn [^js geojson]
               (reset! all-locations
                       (mapv feature->loc (:features (js->clj geojson :keywordize-keys true))))
               (swap! state assoc :loading? false)))
      (.catch (fn [err]
                (swap! state assoc :loading? false :error (str err))))))
(comment
  (fetch-locations!))

(defn click-handler [^js e]
  (reset! selected-location (interop/coords-from-evt e)))

(defn set-globe! []
  (when-let [^js m @map-ref]
    (.setProjection m (clj->js {:type "globe"}))))

(def light-style "https://pmtiles.perrygeo.com/styles/light.json")
(def dark-style  "https://pmtiles.perrygeo.com/styles/dark.json")

(defonce map-style (r/atom nil))

(defn watch-color-scheme!
  "Set `map-style` from the OS dark/light preference, updating on change."
  []
  (let [mql        (.matchMedia js/window "(prefers-color-scheme: dark)")
        set-style! (fn [^js m]
                     (reset! map-style (if (.-matches m) dark-style light-style)))]
    (set-style! mql)
    (.addEventListener mql "change" set-style!)))

(defn coords-to-maplibre [p]
  (clj->js {:lng (:longitude p)
            :lat (:latitude p)}))

(defn select-location! [loc]
  (reset! selected-location loc)
  (when-let [^js m @map-ref]
    (.flyTo m (clj->js {:center   (coords-to-maplibre loc)
                        :zoom     5
                        :duration 3000}))))

(defn- loc-matches
  "Loaded locations whose name contains `query` (case-insensitive)."
  [query]
  (when (seq query)
    (let [q (str/lower-case query)]
      (filter #(str/includes? (str/lower-case (:name %)) q) @all-locations))))

(defn- pick-location!
  "Select `loc` from the typeahead,
  clear the input, close the dropdown, fly to the location."
  [query open? loc]
  (reset! query "")
  (reset! open? false)
  (select-location! loc))

(defn location-typeahead
  "Search field that filters the loaded `all-locations` by name. Selecting a
  match flies the map to it and sets `selected-location`."
  []
  (let [query (r/atom "")
        open? (r/atom false)
        hi    (r/atom 0)]
    (fn []
      (let [matches (loc-matches @query)
            results (take 10 matches)
            more    (- (count matches) (count results))]
        [:div {:class "typeahead"}
         [:div {:class "typeahead-row"}
          [:input {:class       "typeahead-input"
                   :type        "text"
                   :value       @query
                   :placeholder "Search locations…"
                   :disabled    (nil? @all-locations)
                   :on-change   (fn [e]
                                  (reset! query (.. e -target -value))
                                  (reset! hi 0)
                                  (reset! open? true))
                   :on-focus    #(reset! open? true)
                   :on-blur     #(reset! open? false)
                   :on-key-down (fn [e]
                                  (case (.-key e)
                                    "ArrowDown" (when (seq results)
                                                  (.preventDefault e)
                                                  (swap! hi #(mod (inc %) (count results))))
                                    "ArrowUp"   (when (seq results)
                                                  (.preventDefault e)
                                                  (swap! hi #(mod (dec %) (count results))))
                                    "Enter"     (when-let [loc (nth results @hi nil)]
                                                  (.preventDefault e)
                                                  (pick-location! query open? loc))
                                    "Escape"    (reset! open? false)
                                    nil))}]
          [:button {:class    "btn"
                    :title    "I'm feeling lucky"
                    :disabled (nil? @all-locations)
                    :on-click #(when-let [locs @all-locations]
                                 (pick-location! query open? (rand-nth locs)))}
           "🎲"]]
         (when (and @open? (seq results))
           [:ul {:class "typeahead-list"}
            (doall
             (map-indexed
              (fn [i loc]
                [:li {:class         (str "typeahead-item" (when (= i @hi) " selected"))
                      :key           (:id loc)
                      :on-mouse-down (fn [e]
                                       (.preventDefault e)
                                       (pick-location! query open? loc))}
                 (:name loc)])
              results))
            (when (pos? more)
              [:li {:class "typeahead-more"} (str more " more…")])])]))))

(defn app []
  (let [{:keys [loading? message error]} @state]
    [:div
     [:header {:class "app-header"}
      [:h1 {:class "app-title"} shared/appname]
      [:span {:class "status"} (if loading? "Loading…" "Ready")]]
     [:div {:class "side-panel"}
      [location-typeahead]
      [:hr]
      [:div (str (grid/snap-coords (:longitude @selected-location) (:latitude @selected-location)))]
      [:hr]
      [:div {:class "dataview"} (str @selected-location)]
      (when message [:p {:class "message"} message])
      (when error   [:p {:class "error"} error])]
     [cartoj/interactive-map
      {:initial-view-state {:longitude 0 :latitude 16 :zoom 2.5}
       :on-click           click-handler
       :projection         "globe"
       :style-diffing      false
       :map-style          @map-style}
      [interop/reset-map-ref! map-ref]
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

;; Render app to DOM

(defonce root (atom nil))

(defn inject-styles!
  "Compile the garden styles in climate-changed.shared and inject them into
  <head>. Called on every hot reload so style edits apply without a refresh."
  []
  (let [style (or (js/document.querySelector "style#climate-changed-styles")
                  (doto (.createElement js/document "style")
                    (.setAttribute "id" "climate-changed-styles")))]
    (when (and (.-head js/document)
               (not (.-parentNode style)))
      (.appendChild (.-head js/document) style))
    (set! (.-textContent style) (css shared/styles))))

(defn ^:dev/after-load re-render []
  (inject-styles!)
  (.render ^js @root (r/as-element [app])))

(defn init []
  (reset! root (rdom/create-root (js/document.getElementById "app")))
  (watch-color-scheme!)
  (re-render)
  (fetch-locations!))

(comment
  state
  (swap! state assoc :message "Hello from the REPL")
  (swap! state update :message (fn [x] (str x "!")))
  (swap! state update :loading? (fn [x] (not x)))
  (meta #'init)

  (set-globe!)

  ;; clojurescript repl can reach into the browser
  js/document
  (js/alert "hello")
  ;;
  )

