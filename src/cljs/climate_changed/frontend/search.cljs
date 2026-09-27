(ns climate-changed.frontend.search
  "Location typeahead: filters the loaded locations by name and flies the
  map to the selected match."
  (:require
   [climate-changed.frontend.interactive-map :as cmap]
   [climate-changed.frontend.state :as state]
   [clojure.string :as str]
   [reagent.core :as r]))

(defn- loc-matches
  "Loaded locations whose name contains `query` (case-insensitive)."
  [query]
  (when (seq query)
    (let [q (str/lower-case query)]
      (filter #(str/includes? (str/lower-case (:name %)) q) @state/all-locations))))

(defn- pick-location!
  "Select `loc` from the typeahead,
  clear the input, close the dropdown, fly to the location."
  [query open? loc]
  (reset! query "")
  (reset! open? false)
  (cmap/select-location! loc))

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
                   :disabled    (nil? @state/all-locations)
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
                    :disabled (nil? @state/all-locations)
                    :on-click #(when-let [locs @state/all-locations]
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
