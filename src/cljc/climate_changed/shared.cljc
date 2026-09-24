(ns climate-changed.shared
  (:require [garden.stylesheet :refer [at-media]]))

(def appname "Climate Changed")

(def hello-path "/api/hello")

;; ---------------------------------------------------------------------------
;; App styles as garden data
;; ---------------------------------------------------------------------------
;;
;; Compiled to CSS by the client at startup (see climate-changed.client/init). Living in
;; .cljc means the server could also render them (e.g. for server-side
;; rendering) from the same source of truth.

(def styles
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
    {:margin "1.5rem"
     :background-color "var(--bg-primary)"
     :color "var(--text-primary)"
     :font-family      "system-ui, sans-serif"}]
   [:.app-header {:margin-bottom "0.5rem"}]
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
   ;; Sizing for the cartoj/react-map-gl container (the map itself has no
   ;; intrinsic height, so this class is required for it to render).
   [:.cartoj-interactive-map
    {:margin    0
     :height    "480px"
     :width     "100%"
     :max-width "960px"}]])
