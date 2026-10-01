(ns climate-changed.spatial-index-test
  (:require [climate-changed.spatial-index :as idx]
            [clojure.test :refer [deftest is testing]]))

(defn- point
  "A test location with a degenerate point bbox."
  [id x y]
  {:id id :bbox (idx/point-bbox x y)})

(defn- brute-ids
  "Naive linear-scan reference implementation for a bbox query."
  [locations query]
  (set (map :id (filter #(idx/bbox-intersects? (:bbox %) query) locations))))

(defn- search-ids
  [tree query]
  (set (map :id (idx/search tree query))))

;; ---------------------------------------------------------------------------
;; bbox helpers
;; ---------------------------------------------------------------------------

(deftest bbox-helper-test
  (is (= [0 0 10 10] (idx/bbox 10 10 0 0)))
  (is (= [-3 -4 2 1] (idx/bbox 2 1 -3 -4)))
  (is (= [1 2 1 2] (idx/point-bbox 1 2)))
  (is (== 0 (idx/bbox-area (idx/point-bbox 3 3))))
  (is (== 4 (idx/bbox-area [0 0 2 2])))
  (is (= [0 0 10 10] (idx/bbox-union [0 0 5 5] [5 5 10 10])))
  (is (= [-1 -2 6 7] (idx/bbox-union [-1 -2 3 4] [0 5 6 7]))))

(deftest bbox-intersects-test
  (testing "overlapping boxes intersect"
    (is (idx/bbox-intersects? [0 0 2 2] [1 1 3 3]))
    (is (idx/bbox-intersects? [1 1 3 3] [0 0 2 2])))
  (testing "touching edges count as intersecting"
    (is (idx/bbox-intersects? [0 0 1 1] [1 1 2 2])))
  (testing "disjoint boxes do not intersect"
    (is (not (idx/bbox-intersects? [0 0 1 1] [1.0001 0 2 2])))
    (is (not (idx/bbox-intersects? [0 0 1 1] [0 2 1 3])))))

;; ---------------------------------------------------------------------------
;; search
;; ---------------------------------------------------------------------------

(deftest empty-index-test
  (is (= [] (idx/search (idx/empty-index) [0 0 1 1]))))

(deftest single-point-test
  (let [tree (idx/build [(point :a 5 5)])]
    (is (= #{:a} (search-ids tree (idx/bbox 4 4 6 6))))
    (is (= #{:a} (search-ids tree (idx/point-bbox 5 5))))
    (is (= #{} (search-ids tree (idx/bbox 0 0 1 1))))))

(deftest bbox-extent-test
  (let [locations [(point :sw 0 0)
                   (point :center 5 5)
                   (point :ne 10 10)]
        tree (idx/build locations)]
    (is (= #{:sw :center} (search-ids tree (idx/bbox 0 0 5 5))))
    (is (= #{:center :ne} (search-ids tree (idx/bbox 5 5 10 10))))
    (is (= #{:center} (search-ids tree (idx/bbox 4 4 6 6))))
    (is (= #{:sw :center :ne} (search-ids tree (idx/bbox -10 -10 10 10))))
    (is (= #{} (search-ids tree (idx/bbox -10 -10 -5 -5))))))

(deftest rectangle-location-test
  (let [locations [{:id :a :bbox (idx/bbox 0 0 2 2)}
                   {:id :b :bbox (idx/bbox 3 3 4 4)}
                   {:id :c :bbox (idx/bbox 1 1 5 5)}]
        tree (idx/build locations)]
    (is (= #{:a :c} (search-ids tree (idx/bbox 1 1 2 2))))
    (is (= #{:b :c} (search-ids tree (idx/bbox 3 3 4 4))))
    (is (= #{:a :b :c} (search-ids tree (idx/bbox 0 0 5 5))))
    (is (= #{} (search-ids tree (idx/bbox 10 10 11 11))))))

(deftest immutability-test
  (let [empty (idx/empty-index)
        t1 (idx/insert empty (point :a 1 1))
        t2 (idx/insert t1 (point :b 2 2))]
    (is (= [] (idx/search empty (idx/bbox 0 0 5 5))))
    (is (= #{:a} (search-ids t1 (idx/bbox 0 0 5 5))))
    (is (= #{:a :b} (search-ids t2 (idx/bbox 0 0 5 5))))))

;; ---------------------------------------------------------------------------
;; Many points: force node splits and compare against a linear scan
;; ---------------------------------------------------------------------------

(defn- grid-locations
  [n]
  (vec (for [x (range n) y (range n)]
         (point [x y] x y))))

(deftest grid-search-test
  (let [locations (grid-locations 16)
        tree (idx/build locations)
        queries [[0 0 0 0]
                 [0 0 4 4]
                 [3.5 3.5 7.5 7.5]
                 [0 0 15 15]
                 [8 8 8 8]
                 [16 16 20 20]
                 [-1 -1 0.5 0.5]]]
    (doseq [q queries]
      (is (= (brute-ids locations q) (search-ids tree q))
          (str "query " q)))))

(deftest full-extent-test
  (let [locations (grid-locations 32)          ; 1024 points, deep tree
        tree (idx/build locations)
        results (idx/search tree (idx/bbox 0 0 31 31))]
    (is (= 1024 (count results)))
    (is (= (count results) (count (distinct results))))
    (is (= (brute-ids locations (idx/bbox 0 0 31 31))
           (set (map :id results))))))

(deftest rectangle-grid-search-test
  ;; Overlapping rectangles (not just points) that force many node splits.
  (let [locations (vec (for [i (range 10) j (range 10)]
                         (let [x (* i 4) y (* j 3)]
                           {:id [i j] :bbox (idx/bbox x y (+ x 5) (+ y 4))})))
        tree (idx/build locations)
        queries [[0 0 5 4]
                 [4 3 9 7]
                 [1 1 8 6]
                 [0 0 100 100]
                 [50 50 60 60]
                 [-1 -1 0.5 0.5]]]
    (doseq [q queries]
      (is (= (brute-ids locations q) (search-ids tree q)) (str q)))))

(deftest identical-points-test
  (let [locations [(point :a 5 5) (point :b 5 5) (point :c 5 5)]
        tree (idx/build locations)]
    (is (= #{:a :b :c} (search-ids tree (idx/point-bbox 5 5))))
    (is (= #{} (search-ids tree (idx/point-bbox 6 6))))))
