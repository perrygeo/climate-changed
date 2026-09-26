(ns climate-changed.client
  (:require
   ["maplibre-gl/dist/maplibre-gl.css"]
   [cartoj.core :as cartoj]
   [cartoj.interop :as interop]
   [climate-changed.era5-grid :as grid]
   [climate-changed.shared :as shared]
   [cljs.reader :as reader]
   [garden.core :refer [css]]
   [reagent.core :as r]
   [reagent.dom.client :as rdom]))

(defonce state
  (r/atom {:loading? false
           :message  nil
           :error    nil}))

(defn fetch-locations!
  "Hit the locations API, parse the Geo body and update `state` accordingly."
  []
  (swap! state assoc :loading? true :error nil :message nil)
  (-> (js/fetch "/api/locations")
      (.then (fn [resp] (.text resp)))
      (.then (fn [text]
               (swap! state assoc
                      :loading? false
                      :message (try
                                 (:message text)
                                 (catch :default _ text)))))
      (.catch (fn [err]
                (swap! state assoc :loading? false :error (str err))))))

(comment
  (fetch-locations!))

(defn fetch-hello!
  "Hit the hello API, parse the EDN body and update `state` accordingly."
  []
  (swap! state assoc :loading? true :error nil :message nil)
  (-> (js/fetch shared/hello-path)
      (.then (fn [resp] (.text resp)))
      (.then (fn [text]
               (swap! state assoc
                      :loading? false
                      :message (try
                                 (:message (reader/read-string text))
                                 (catch :default _ text)))))
      (.catch (fn [err]
                (swap! state assoc :loading? false :error (str err))))))

(defonce last-point (r/atom nil))

(defonce map-ref (r/atom nil))

(defn click-handler [^js e]
  (js/console.log e)
  (reset! last-point (interop/coords-from-evt e)))

(defn set-globe! []
  (when-let [^js m @map-ref]
    (.setProjection m (clj->js {:type "globe"}))))

(defn coords-to-maplibre [p]
  (clj->js {:lng (:longitude p)
            :lat (:latitude p)}))
(defn select-location! [_city]
  (when-let [^js m @map-ref]
    (.flyTo m (clj->js {:center   (coords-to-maplibre @last-point)
                        :zoom     4
                        :duration 3000}))))

(defn app []
  (let [{:keys [loading? message error]} @state]
    [:div
     [:header {:class "app-header"}
      [:h1 {:class "app-title"} shared/appname]
      [:span {:class "status"} (if loading? "Loading…" "Ready")]]
     [:div {:class "side-panel"}
      [:button {:class    "btn select-location"
                :on-click #(select-location! "foo")
                :disabled loading?}
       "Zoom to locations"]
      [:hr]
      [:div (str (grid/snap-coords (:longitude @last-point) (:latitude @last-point)))]
      [:hr]
      [:div {:class "dataview"} (str @last-point)]
      (when message [:p {:class "message"} message])
      (when error   [:p {:class "error"} error])]
     [cartoj/interactive-map
      {:initial-view-state {:longitude 0 :latitude 16 :zoom 2.5}
       :on-click           click-handler
       :projection         "globe"
       :map-style          "https://pmtiles.perrygeo.com/styles/dark.json"}
      [interop/reset-map-ref! map-ref]
      [cartoj/source {:id   "cities"
                      :type "geojson"
                      :data "api/locations"}
       [cartoj/layer {:id     "cities-circles"
                      :type   "circle"
                      :source "cities"
                      :paint  {:circle-radius       6
                               :circle-color        "#ff2"
                               :circle-stroke-width 1
                               :circle-stroke-color "#a99"}}]
       [cartoj/layer {:id     "cities-labels"
                      :type   "symbol"
                      :source "cities"
                      :layout {:text-field  ["get" "name"]
                               :text-size   11
                               :text-offset [0 0]
                               :text-anchor "top"}
                      :paint  {:text-color      "#333"
                               :text-halo-color "rgba(255,255,235,0.85)"
                               :text-halo-width 2
                               :text-halo-blur  1}}]]]]))

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
  (re-render))

(comment
  (fetch-hello!)
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

