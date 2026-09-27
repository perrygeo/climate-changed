(ns climate-changed.frontend.state
  "Shared application state atoms."
  (:require
   [reagent.core :as r]))

(defonce state
  (r/atom {:loading? false
           :message  nil
           :error    nil}))

(defonce all-locations (r/atom nil))

(defonce selected-location (r/atom nil))

(defonce map-ref (r/atom nil))

(defonce map-style (r/atom nil))

(defonce era5-summary (r/atom nil))
