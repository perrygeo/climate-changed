(ns climate-changed.common
  (:require [garden.stylesheet :refer [at-media]]))

(def appname "Climate, changed")

(def default-style
  ;; Compiled to CSS by the client at startup (see climate-changed.frontend.main/init).
  ;; Written in .cljc for potential server-side rendering
  [[":root"
    {:--bg-primary       "#ffffff"
     :--bg-secondary     "#f6f8fa"
     :--bg-selected      "#eef"
     :--text-primary     "#333333"
     :--text-secondary   "#656d76"
     :--text-muted       "#999999"
     :--text-link        "#111188"
     :--border-primary   "#dddddd"
     :--border-secondary "#cccccc"
     :--border-tertiary  "#eeeeee"
     :--color-success    "#1a7f37"
     :--color-error      "#cf222e"}]
   (at-media {:prefers-color-scheme "dark"}
             [":root"
              {:--bg-primary       "#1a1a2e"
               :--bg-secondary     "#16213e"
               :--bg-selected      "#1f2d4a"
               :--text-primary     "#e1e4e8"
               :--text-secondary   "#8b949e"
               :--text-muted       "#586069"
               :--text-link        "#79b8ff"
               :--border-primary   "#30363d"
               :--border-secondary "#30363d"
               :--border-tertiary  "#21262d"
               :--color-success    "#3fb950"
               :--color-error      "#f85149"}])
   [:body
    {:margin           0
     :overflow         "hidden"
     :background-color "var(--bg-primary)"
     :color            "var(--text-primary)"
     :font-family      "system-ui, sans-serif"}]
   ;; Header floats over the full-screen map: translucent theme background so
   ;; the map shows through, title left / status right.
   [:.app-header
    {:position        "fixed"
     :top             0
     :left            0
     :right           0
     :z-index         10
     :display         "flex"
     :align-items     "center"
     :justify-content "space-between"
     :padding         "0.5rem 1rem"
     :background      "color-mix(in srgb, var(--bg-primary) 72%, transparent)"
     :backdrop-filter "blur(4px)"}]
   [:.app-title
    {:margin    0
     :font-size "1.1rem"}]
   [:a.app-title-link
    {:color           "inherit"
     :text-decoration "none"}]
   [:.status
    {:font-size "0.85rem"
     :color     "var(--text-secondary)"}]
   ;; Floating translucent panel on the right holding the dataview + controls.
   [:.side-panel
    {:position        "fixed"
     :top             "4rem"
     :right           "1rem"
     :z-index         10
     :width           "40%"
     :padding         "1rem"
     :border-radius   "8px"
     :border          "1px solid var(--border-primary)"
     :background      "color-mix(in srgb, var(--bg-primary) 80%, transparent)"
     :backdrop-filter "blur(4px)"
     :box-shadow      "0 4px 16px rgba(0,0,0,0.12)"}]
   [:.dataview
    {:font-family   "monospace"
     :font-size     "0.85rem"
     :margin-top    "0.85rem"
     :margin-bottom "0.85rem"
     :word-break    "break-all"}]
   [:.message {:color "var(--text-link)"}]
   [:.error {:color "var(--color-error)"}]
   [:.btn
    {:padding       "0.4rem 0.8rem"
     :border        "1px solid var(--border-secondary)"
     :border-radius "6px"
     :background    "var(--bg-secondary)"
     :color         "var(--text-primary)"
     :cursor        "pointer"
     :font-size     "0.95rem"}]
   [:.btn:hover {:background "var(--bg-selected)"}]
   [:.btn:disabled
    {:opacity 0.5
     :cursor  "default"}]
   ;; Typeahead location search in the side panel: input + dropdown of matches.
   [:.typeahead
    {:position "relative"
     :width    "100%"}]
   [:.typeahead-row
    {:display "flex"
     :gap     "0.5rem"}]
   [:.typeahead-row :button
    {:flex-shrink "0"}]
   [:.typeahead-input
    {:width         "100%"
     :box-sizing    "border-box"
     :padding       "0.4rem 0.8rem"
     :border        "1px solid var(--border-secondary)"
     :border-radius "6px"
     :background    "var(--bg-secondary)"
     :color         "var(--text-primary)"
     :font-size     "0.95rem"}]
   [:.typeahead-input:focus
    {:outline      "none"
     :border-color "var(--text-link)"}]
   [:.typeahead-input:disabled {:opacity 0.6}]
   [:.typeahead-list
    {:position      "absolute"
     :top           "100%"
     :left          0
     :right         0
     :z-index       20
     :max-height    "16rem"
     :overflow-y    "auto"
     :margin        "0.25rem 0 0 0"
     :padding       0
     :list-style    "none"
     :border        "1px solid var(--border-primary)"
     :border-radius "6px"
     :background    "var(--bg-primary)"
     :box-shadow    "0 4px 16px rgba(0,0,0,0.12)"}]
   [:.typeahead-item
    {:padding   "0.4rem 0.8rem"
     :cursor    "pointer"
     :font-size "0.9rem"}]
   [:.typeahead-item:hover :.typeahead-item.selected
    {:background "var(--bg-selected)"}]
   [:.typeahead-more
    {:padding   "0.4rem 0.8rem"
     :font-size "0.85rem"
     :color     "var(--text-muted)"}]
   ;; ERA5 climate summary card in the side panel.
   [:.era5-summary {:margin-bottom "0.75rem"}]
   [:.summary-title
    {:font-weight "600"
     :margin      "0 0 0.5rem 0"}]
   [:.summary-hint {:color "var(--text-muted)"}]
   [:.summary-table
    {:border-collapse "collapse"
     :width           "100%"}]
   [:.summary-table :th
    {:text-align  "center"
     :color       "var(--text-secondary)"
     :font-weight "400"
     :padding     "0.15rem 0.5rem 0.15rem 0"}]
   [:.summary-table :td
    {:text-align "center"
     :padding    "0.15rem 0"}]
   ;; Selected-location card in the side panel.
   [:.location-card {:margin-bottom "0.75rem"}]
   [:.location-name
    {:font-weight "600"
     :margin      "0 0 0.25rem 0"}]
   [:.location-place
    {:margin "0 0 0.15rem 0"
     :color  "var(--text-secondary)"}]
   [:.location-featurecla
    {:margin     "0 0 0.15rem 0"
     :color      "var(--text-muted)"
     :font-style "italic"
     :font-size  "0.8rem"}]
   [:.location-population
    {:margin "0"
     :color  "var(--text-muted)"}]
   ;; Sizing for the cartoj/react-map-gl container (the map itself has no
   ;; intrinsic height, so this class is required for it to render). Fixed to
   ;; the viewport so the map fills the whole screen behind the overlays.
   [:.cartoj-interactive-map
    {:position  "fixed"
     :inset     "0"
     :z-index   0
     :margin    0
     :height    "100vh"
     :width     "100vw"
     :max-width "none"}]
   ;; Home page: server-rendered markdown introduction at / (SPA lives at /map).
   ;; Shares this stylesheet; the body is scrollable here since there is no map.
   ;; The fixed .app-header overlays the top of the viewport, so pad the body
   ;; enough to keep .home-content clear of it.
   [:body.home {:overflow    "auto"
                :padding-top "6rem"}]
   [".home-content"
    {:max-width "48rem"
     :margin    "0 auto 2rem auto"
     :padding   "0 1.5rem"}]
   [".home-content h1"
    {:margin-top "0"}]
   [".home-content a"
    {:display         "inline-block"
     :padding         "0.4rem 0.8rem"
     :border          "1px solid var(--border-secondary)"
     :border-radius   "6px"
     :background      "var(--bg-secondary)"
     :color           "var(--text-link)"
     :text-decoration "none"
     :font-size       "0.95rem"}]
   [".home-content a:hover"
    {:background "var(--bg-selected)"}]])
