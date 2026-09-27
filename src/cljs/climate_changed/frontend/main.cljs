(ns climate-changed.frontend.main
  "Application entry point and render-to-DOM scaffolding."
  (:require
   ["maplibre-gl/dist/maplibre-gl.css"]
   [climate-changed.frontend.app :as app]
   [climate-changed.frontend.interactive-map :as cmap]
   [climate-changed.common :as shared]
   [climate-changed.frontend.state :as state]
   [garden.core :refer [css]]
   [reagent.core :as r]
   [reagent.dom.client :as rdom]))

(defonce root (atom nil))

(defn inject-styles!
  "Compile the garden styles in climate-changed.common and inject them into
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
  (.render ^js @root (r/as-element [app/app])))

(defn init []
  (reset! root (rdom/create-root (js/document.getElementById "app")))
  (cmap/watch-color-scheme!)
  (re-render)
  (app/fetch-locations!))

(comment
  state/state
  (swap! state/state assoc :message "Hello from the REPL")
  (swap! state/state update :message (fn [x] (str x "!")))
  (swap! state/state update :loading? (fn [x] (not x)))
  (meta #'init)

  (cmap/set-globe!)

  ;; clojurescript repl can reach into the browser
  js/document
  (js/alert "hello")
  ;;
  )
