(ns climate-changed.client
  (:require
   ["maplibre-gl/dist/maplibre-gl.css"]
   [cartoj.core :as cartoj]
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

(defn app []
  (let [{:keys [loading? message error]} @state]
    [:div
     [:h1 {:class "app-header"} shared/appname]
     [:button {:class    "btn"
               :on-click #(fetch-hello!)
               :disabled loading?}
      (if loading? "Loading…" "Say hello to the server")]
     (when message [:p {:class "message"} message])
     (when error   [:p {:class "error"} error])
     [cartoj/interactive-map
      {:initial-view-state {:longitude 0 :latitude 16 :zoom 1}
       :map-style          "https://tiles.openfreemap.org/styles/positron"}
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

(defn ^:dev/after-load re-render []
  (.render ^js @root (r/as-element [app])))

(defn init []
  ;; Compile the garden styles in climate-changed.shared and inject them into <head>.
  (let [style (.createElement js/document "style")]
    (set! (.-textContent style) (css shared/styles))
    (.appendChild (.-head js/document) style))
  (reset! root (rdom/create-root (js/document.getElementById "app")))
  (re-render))

(comment
  (fetch-hello!)
  state
  (swap! state assoc :message "Hello from the REPL")
  (swap! state update :message (fn [x] (str x "!")))
  (swap! state update :loading? (fn [x] (not x)))
  (meta #'init)

  ;; clojurescript repl can reach into the browser
  js/document
  (js/alert "hello")
  ;;
  )

