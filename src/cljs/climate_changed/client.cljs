(ns climate-changed.client
  (:require
   [cljs.reader :as reader]
   [climate-changed.shared :as shared]
   [garden.core :refer [css]]
   [reagent.core :as r]
   [reagent.dom.client :as rdom]))

(defonce state
  (r/atom {:loading? false
           :message  nil
           :error    nil}))

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
     (when error   [:p {:class "error"} error])]))

;; ---------------------------------------------------------------------------
;; Render app to DOM
;; ---------------------------------------------------------------------------

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

  (swap! state assoc :message (meta #'init)))

