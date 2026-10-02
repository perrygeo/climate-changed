(ns climate-changed.frontend.state
  "Frontend application state."
  (:require
   [reagent.core :as r]))

(defonce state
  (r/atom {:loading-locations? false
           :message            nil
           :error              nil
           :all-locations      nil
           :selected-location  nil
           :map-style          nil
           :era5-summary       nil
           :location-stats     nil}))

;; The maplibre Map instance is a mutable JS object; keep it in its own atom
;; so the shared `state` atom's watchers don't fire on map mount/unmount.
(defonce map-ref (r/atom nil))
