(ns climate-changed.frontend.state
  "Frontend application state."
  (:require
   [reagent.core :as r]))

(defonce state
  (r/atom {:loading?          false
           :message           nil
           :error             nil
           :all-locations     nil
           :selected-location nil
           :map-ref           nil
           :map-style         nil
           :era5-summary      nil}))
