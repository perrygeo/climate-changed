(ns climate-changed.spatial-index
  "A cross-platform (cljc) R-tree spatial index for bbox lookups.

  The index is a persistent (immutable) data structure
  and should work identically on the JVM and in ClojureScript.

  Inspired by https://github.com/chrisulloa/archery which is CLJ only.
  and by https://github.com/janetacarr/quadtree-cljc,
  a Quadtree which solves a slightly different problem
  but has nice API made of plain maps."
  (:refer-clojure :exclude [abs]))

(def ^:private max-entries
  "Maximum number of entries per node. A node is split when it exceeds this."
  8)

(def ^:private min-entries
  "Minimum number of entries per node after a split."
  4)

;; ---------------------------------------------------------------------------
;; Bounding boxes
;; ---------------------------------------------------------------------------

(defn bbox
  "Create a normalized bounding box `[minx miny maxx maxy]` from any two
  opposite corners. Pass x/y in any order; mins and maxes are sorted here."
  [x0 y0 x1 y1]
  [(min x0 x1) (min y0 y1) (max x0 x1) (max y0 y1)])

(defn point-bbox
  "The degenerate bounding box of a single point."
  [x y]
  [x y x y])

(defn bbox-intersects?
  "True when two bounding boxes overlap. Touching edges count as intersecting,
  so a point exactly on the query boundary is included."
  [[ax0 ay0 ax1 ay1] [bx0 by0 bx1 by1]]
  (and (<= ax0 bx1) (<= bx0 ax1)
       (<= ay0 by1) (<= by0 ay1)))

(defn bbox-union
  "The smallest bounding box containing both inputs."
  [[ax0 ay0 ax1 ay1] [bx0 by0 bx1 by1]]
  [(min ax0 bx0) (min ay0 by0)
   (max ax1 bx1) (max ay1 by1)])

(defn bbox-area
  "Area of a bounding box. Degenerate (point or line) boxes have area 0."
  [[x0 y0 x1 y1]]
  (max 0 (* (- x1 x0) (- y1 y0))))

(defn- abs
  "Absolute value; avoids the JVM/JS platform split for Math/abs."
  [x]
  (if (neg? x) (- x) x))

(defn- enlargement
  "How much `bbox` grows if it must also cover `extra`."
  [bbox extra]
  (- (bbox-area (bbox-union bbox extra)) (bbox-area bbox)))

;; ---------------------------------------------------------------------------
;; Node / entry helpers
;;
;; A node is {:leaf? bool :entries [entry ...]}.
;; A leaf entry is {:bbox ... :location location}.
;; A branch entry is {:bbox ... :child node}.
;; ---------------------------------------------------------------------------

(defn- leaf-entry
  [location]
  {:bbox (:bbox location) :location location})

(defn- entries-bbox
  "Bounding box covering every entry in `entries` (non-empty)."
  [entries]
  (reduce (fn [acc e] (bbox-union acc (:bbox e)))
          (:bbox (first entries))
          (rest entries)))

(defn- branch-entry
  [node]
  {:bbox (entries-bbox (:entries node)) :child node})

(defn- choose-subtree
  "Index of the child entry whose bbox needs the least enlargement to cover
  `bbox`. Ties go to the smaller child area."
  [node bbox]
  (loop [entries   (:entries node)
         idx       0
         best-idx  0
         best-enl  nil
         best-area nil]
    (if (empty? entries)
      best-idx
      (let [enl     (enlargement (:bbox (first entries)) bbox)
            area    (bbox-area (:bbox (first entries)))
            better? (or (nil? best-enl)
                        (< enl best-enl)
                        (and (= enl best-enl) (< area best-area)))]
        (recur (rest entries)
               (inc idx)
               (if better? idx best-idx)
               (if better? enl best-enl)
               (if better? area best-area))))))

;; ---------------------------------------------------------------------------
;; Quadratic split (Guttman 1984)
;; ---------------------------------------------------------------------------

(defn- pair-waste
  "Area wasted by grouping two entries together: the dead space of their union."
  [e1 e2]
  (- (bbox-area (bbox-union (:bbox e1) (:bbox e2)))
     (bbox-area (:bbox e1))
     (bbox-area (:bbox e2))))

(defn- pick-seeds
  "The two entries whose union wastes the most area, returned as an index pair."
  [entries]
  (let [n (count entries)]
    (loop [i 0 j 1 best nil best-i 0 best-j 0]
      (cond
        (>= i (dec n)) [best-i best-j]
        (>= j n)       (recur (inc i) (+ i 2) best best-i best-j)
        :else
        (let [waste   (pair-waste (nth entries i) (nth entries j))
              better? (or (nil? best) (> waste best))]
          (recur i (inc j)
                 (if better? waste best)
                 (if better? i best-i)
                 (if better? j best-j)))))))

(defn- pick-next
  "Entry with the strongest preference between the two groups, returned with
  its enlargement into each group. Groups are non-empty."
  [g1 g2 remaining]
  (let [bb1 (entries-bbox g1)
        bb2 (entries-bbox g2)]
    (reduce (fn [best e]
              (let [d1   (enlargement bb1 (:bbox e))
                    d2   (enlargement bb2 (:bbox e))
                    pref (abs (- d1 d2))]
                (if (or (nil? best) (> pref (:pref best)))
                  {:pref pref :entry e :d1 d1 :d2 d2}
                  best)))
            nil
            remaining)))

(defn- split-entries
  "Split `entries` (which has `max-entries` + 1 elements) into two groups of at
  least `min-entries` each, using the quadratic split heuristic."
  [entries]
  (let [[i j]     (pick-seeds entries)
        remaining (vec (keep-indexed (fn [k e]
                                       (when-not (or (= k i) (= k j)) e))
                                     entries))]
    (loop [g1        [(nth entries i)]
           g2        [(nth entries j)]
           remaining remaining]
      (if (empty? remaining)
        [g1 g2]
        (let [need1 (- min-entries (count g1))
              need2 (- min-entries (count g2))]
          (cond
            (>= need1 (count remaining)) [(into g1 remaining) g2]
            (>= need2 (count remaining)) [g1 (into g2 remaining)]
            :else
            (let [next   (pick-next g1 g2 remaining)
                  bb1    (entries-bbox g1)
                  bb2    (entries-bbox g2)
                  to-g1? (or (< (:d1 next) (:d2 next))
                             (and (= (:d1 next) (:d2 next))
                                  (or (< (bbox-area bb1) (bbox-area bb2))
                                      (and (= (bbox-area bb1) (bbox-area bb2))
                                           (< (count g1) (count g2))))))]
              (recur (if to-g1? (conj g1 (:entry next)) g1)
                     (if to-g1? g2 (conj g2 (:entry next)))
                     (remove #(identical? % (:entry next)) remaining)))))))))

(defn- maybe-split
  "Return a vector of one node, or two nodes if the node overflows."
  [node]
  (if (> (count (:entries node)) max-entries)
    (let [[e1 e2] (split-entries (:entries node))]
      [(assoc node :entries e1) (assoc node :entries e2)])
    [node]))

;; ---------------------------------------------------------------------------
;; Insert
;; ---------------------------------------------------------------------------

(defn- insert*
  "Insert `entry` into `node`, returning a vector of one or two nodes. The
  two-node result means this node split, and the caller must reparent both."
  [node entry]
  (if (:leaf? node)
    (maybe-split (update node :entries conj entry))
    (let [idx   (choose-subtree node (:bbox entry))
          child (:child (nth (:entries node) idx))
          nodes (insert* child entry)]
      (if (= 1 (count nodes))
        (maybe-split (assoc-in node [:entries idx]
                               (branch-entry (first nodes))))
        (let [[c1 c2] nodes]
          (maybe-split (-> node
                           (assoc-in [:entries idx] (branch-entry c1))
                           (update :entries conj (branch-entry c2)))))))))

(defn empty-index
  "An empty spatial index."
  []
  {:root nil})

(defn insert
  "Return a new index with `location` added. `location` must be a map with a
  `:bbox` entry. The original index is not modified."
  [index location]
  (let [entry (leaf-entry location)]
    (if-let [root (:root index)]
      (let [nodes (insert* root entry)]
        (if (= 1 (count nodes))
          {:root (first nodes)}
          (let [[n1 n2] nodes]
            {:root {:leaf? false :entries [(branch-entry n1)
                                           (branch-entry n2)]}})))
      {:root {:leaf? true :entries [entry]}})))

(defn build
  "Build an index from a collection of locations. Each location must be a map
  with a `:bbox` entry of the form `[minx miny maxx maxy]`."
  [locations]
  (reduce insert (empty-index) locations))

;; ---------------------------------------------------------------------------
;; Search
;; ---------------------------------------------------------------------------

(defn search
  "Return a seq of every location whose bbox intersects `query-bbox`. Locations
  come back in no particular order; pass to `set` or `sort` if you need that."
  [index query-bbox]
  (letfn [(walk [node]
            (mapcat (fn [e]
                      (when (bbox-intersects? (:bbox e) query-bbox)
                        (if (:leaf? node)
                          [(:location e)]
                          (walk (:child e)))))
                    (:entries node)))]
    (if-let [root (:root index)]
      (walk root)
      [])))

(comment
  ;; Integration test: load the Natural Earth locations GeoJSON,
  ;; build a spatial index, and search it with a bbox.
  ;; Evaluate these forms in a JVM REPL (clj)

  ;; 1. Read and parse the bundled GeoJSON
  ;; CLJS can't reach files from the browser, potentially add :cljs with a fetch
  #?(:clj
     (do (require '[cheshire.core :as json]
                  '[clojure.java.io :as io])
         (def geojson
           (json/parse-string
            (slurp (io/resource "ne_50m_populated_places_simple.geojson"))
            #_(slurp "https://d2ad6b4ur7yvpq.cloudfront.net/naturalearth-3.3.0/ne_10m_populated_places_simple.geojson")
            true))))

  ;; 2. Turn each GeoJSON point feature into a location with a point :bbox.
  (def locations
    (mapv (fn [feature]
            (let [[lon lat] (get-in feature [:geometry :coordinates])]
              {:name      (get-in feature [:properties :name])
               :longitude lon
               :latitude  lat
               :bbox      (point-bbox lon lat)}))
          (:features geojson)))

  (count locations)

  ;; 3. Build the index and search
  (def index (build locations))

  (def query (bbox -5 45 7 48))

  (def matches (search index query))

  {:count (count matches)
   :names (map :name matches)}

  ;; 4. Benchmark the indexed search vs a naive loop over the locations.
  (defn naive-search
    "Linear-scan reference: every location whose bbox intersects `q`."
    [locs q]
    (doall (filter #(bbox-intersects? (:bbox %) q) locs)))

  ;; Sanity check: the R-tree and the linear scan return the same locations.
  (= (set (search index query))
     (set (naive-search locations query)))

  (let [n          1000
        now        #?(:clj  #(System/nanoTime)
                      :cljs #(.now js/performance))
        ms         #?(:clj  #(/ (- %2 %1) 1e6)
                      :cljs #(- %2 %1))
        bench      (fn [f]
                     (let [start (now)]
                       (dotimes [_ n] (doall (f)))
                       (ms start (now))))

        indexed-ms (bench #(search index query))
        naive-ms   (bench #(naive-search locations query))]
    {:n               n
     :indexed-ms      indexed-ms
     :naive-ms        naive-ms
     :index-advantage (/ naive-ms indexed-ms)}
    ;; results:
    ;; with the ne_10m_populated_places_simple (~7300 points), 10x speedup
    ;; with the ne_50m_populated_places_simple (~1200 points), 3x speedup
    ;; overhead of building the index is worth it for larger datasets.
    ))

