(ns climate-changed.era5.summary-test
  (:require
   [climate-changed.era5.duckdb :as duckdb]
   [climate-changed.era5.fetch :as fetch]
   [climate-changed.era5.summary :as summary]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [tech.v3.dataset :as ds]))

(defn- find-period
  "The climate period whose `:start` ISO string begins with `year`."
  [periods year]
  (first (filter #(str/starts-with? (:start %) (str year "-")) periods)))

(deftest climate-periods-decade-test
  (let [ps summary/climate-periods]
    (testing "starts at the ERA5 record start"
      (is (= (java.time.Instant/parse "1940-01-01T00:00:00Z")
             (:start (first ps)))))
    (testing "has more than the old three buckets"
      (is (> (count ps) 3)))
    (testing "final period is open-ended"
      (is (nil? (:end (last ps)))))
    (testing "all earlier periods are contiguous, one-decade buckets"
      (doseq [[p next-p] (map vector ps (rest ps))]
        (is (= (:end p) (:start next-p)))
        (is (= 10 (.between java.time.temporal.ChronoUnit/YEARS
                            (.atZone ^java.time.Instant (:start p) java.time.ZoneOffset/UTC)
                            (.atZone ^java.time.Instant (:end p) java.time.ZoneOffset/UTC))))))))

(deftest decade-start-year-test
  (let [decade-start-year #'summary/decade-start-year]
    (testing "buckets instants into their UTC decade start year"
      (is (= 1940 (decade-start-year (java.time.Instant/parse "1940-01-01T00:00:00Z"))))
      (is (= 1940 (decade-start-year (java.time.Instant/parse "1949-12-31T23:59:59Z"))))
      (is (= 1950 (decade-start-year (java.time.Instant/parse "1950-01-01T00:00:00Z"))))
      (is (= 2020 (decade-start-year (java.time.Instant/parse "2024-06-01T00:00:00Z")))))))

(deftest bin-width-c-test
  (testing "every temperature cell spans a fixed 2 °C"
    (is (= 2.0 @#'summary/bin-width-c))))

(deftest bin-edges-test
  (let [bin-edges #'summary/bin-edges]
    (testing "edges are whole degrees and enclose the data"
      (is (= [0.0 1.0] (bin-edges 0.0 1.0 1.0)))
      (is (= [-1.0 0.0 1.0] (bin-edges -0.5 0.5 1.0)))
      (is (= [0.0 5.0 10.0] (bin-edges 3.0 7.0 5.0))))
    (testing "0 °C is always an edge"
      (is (some #(= 0.0 %) (bin-edges -7.0 8.0 5.0))))
    (testing "2 °C bins keep 0 °C and 30 °C as edges"
      (let [edges (bin-edges -7.0 32.0 2.0)]
        (is (some #(= 0.0 %) edges))
        (is (some #(= 30.0 %) edges))))
    (testing "a max exactly on an edge becomes the final edge (no empty top bin)"
      (is (= [-20.0 -15.0 -10.0 -5.0 0.0 5.0 10.0 15.0 20.0 25.0 30.0]
             (bin-edges -20.0 30.0 5.0))))))

(deftest edges->bins-test
  (is (= [{:min 0.0 :max 5.0} {:min 5.0 :max 10.0}]
         (#'summary/edges->bins [0.0 5.0 10.0]))))

(deftest bounds-sql-test
  (let [bounds-sql #'summary/bounds-sql
        sql        (bounds-sql "t2m" "era_ts/182/104/t2m.parquet")]
    (testing "reads min/max/count for the given parquet path"
      (is (re-find #"min\(t2m\)::DOUBLE" sql))
      (is (re-find #"max\(t2m\)::DOUBLE" sql))
      (is (re-find #"count\(\*\) AS \"n\"" sql))
      (is (re-find #"FROM 'era_ts/182/104/t2m\.parquet'" sql)))
    (testing "ignores missing observations"
      (is (re-find #"WHERE t2m IS NOT NULL" sql)))))

(deftest decade-histogram-sql-test
  (let [decade-histogram-sql #'summary/decade-histogram-sql
        sql                  (decade-histogram-sql "t2m" "era_ts/182/104/t2m.parquet" -20.0 5.0 9)]
    (testing "buckets by UTC decade with integer division"
      (is (re-find #"\(year\(valid_time\) // 10\) \* 10 AS decade" sql)))
    (testing "bins Celsius values into whole-degree buckets"
      (is (re-find #"t2m - 273\.15" sql))
      (is (re-find #"-20\.0" sql))
      (is (re-find #"5\.0" sql)))
    (testing "clamps values into [0 max-bin]"
      (is (re-find #"least\(greatest\(0, floor" sql))
      (is (re-find #"9" sql)))
    (testing "reads the given parquet path"
      (is (re-find #"FROM 'era_ts/182/104/t2m\.parquet'" sql)))))

(deftest decade-bin-counts-test
  (let [decade-bin-counts #'summary/decade-bin-counts
        result            (ds/->dataset {:decade [1940 1940 1950 1960]
                                         :bin    [0 1 0 9]
                                         :n      [2 3 5 7]})
        counts            (decade-bin-counts result 2)]
    (testing "keys by decade then bin"
      (is (= {1940 {0 2 1 3}
              1950 {0 5}}
             counts)))
    (testing "out-of-range bins are dropped"
      (is (not (contains? counts 1960))))))

(deftest fill-periods-test
  (let [fill-periods #'summary/fill-periods
        periods      (fill-periods {1940 {0 1 1 2}} 3)]
    (testing "the observed decade gets its counts and total"
      (let [p1940 (find-period periods 1940)]
        (is (= [1 2 0] (:counts p1940)))
        (is (= 3 (:n p1940)))
        (is (= "1940-01-01T00:00:00Z" (:start p1940)))
        (is (= "1950-01-01T00:00:00Z" (:end p1940)))))
    (testing "decades without observations are zero-filled"
      (is (= [0 0 0] (:counts (last periods))))
      (is (zero? (:n (last periods)))))))

(deftest decade-mean-sql-test
  (let [decade-mean-sql #'summary/decade-mean-sql
        sql             (decade-mean-sql "t2m" "era_ts/182/104/t2m.parquet")]
    (testing "groups by UTC decade"
      (is (re-find #"\(year\(valid_time\) // 10\) \* 10 AS decade" sql)))
    (testing "averages the Kelvin column and counts observations"
      (is (re-find #"avg\(t2m\)::DOUBLE AS mean_k" sql))
      (is (re-find #"count\(\*\) AS n" sql)))
    (testing "reads the given parquet path and ignores nulls"
      (is (re-find #"FROM 'era_ts/182/104/t2m\.parquet'" sql))
      (is (re-find #"WHERE t2m IS NOT NULL" sql)))))

(deftest decade-means-test
  (let [decade-means #'summary/decade-means
        result       (ds/->dataset {:decade [1940 1950 2020]
                                    :mean_k [283.15 293.15 273.15]
                                    :n      [100 200 50]})
        means        (decade-means result)]
    (testing "converts Kelvin means to Celsius and keeps observation counts"
      (is (< (Math/abs (- 10.0 (:mean (get means 1940)))) 1e-9))
      (is (= 100 (:n (get means 1940))))
      (is (< (Math/abs (- 20.0 (:mean (get means 1950)))) 1e-9))
      (is (= 200 (:n (get means 1950))))
      (is (< (Math/abs (- 0.0 (:mean (get means 2020)))) 1e-9))
      (is (= 50 (:n (get means 2020)))))))

(deftest fill-mean-periods-test
  (let [fill-mean-periods #'summary/fill-mean-periods
        periods           (fill-mean-periods {1940 {:mean 10.0 :n 3}
                                              2020 {:mean 0.0 :n 5}})]
    (testing "observed decades get their mean and count"
      (let [p1940 (find-period periods 1940)]
        (is (= 10.0 (:mean p1940)))
        (is (= 3 (:n p1940))))
      (let [p2020 (find-period periods 2020)]
        (is (= 0.0 (:mean p2020)))
        (is (= 5 (:n p2020)))))
    (testing "decades without observations get nil mean and zero n"
      (let [p1950 (find-period periods 1950)]
        (is (nil? (:mean p1950)))
        (is (zero? (:n p1950)))))))

(deftest era5-means-test
  (testing "rejects unknown varnames with a 400 ex-data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown ERA5 variable"
                          (summary/era5-means "nope" 198 1020))))

  (testing "rejects non-Kelvin variables with a 400 ex-data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require a Kelvin variable"
                          (summary/era5-means "sp" 198 1020))))

  (testing "assembles grid metadata and per-period means"
    (let [means-ds (ds/->dataset {:decade [1940 1950 2020]
                                  :mean_k [283.15 293.15 273.15]
                                  :n      [100 200 50]})
          result   (with-redefs [fetch/ensure-ts! (fn [varname _row _col]
                                                    (is (= "t2m" varname))
                                                    "era_ts/198/1020/t2m.parquet")
                                 duckdb/query     (fn [_] means-ds)]
                     (summary/era5-means "t2m" 198 1020))]
      (is (= {:row 198 :col 1020 :lat 40.5 :lon 255.0 :var "t2m" :units "K" :mean-unit "°C"}
             (select-keys result [:row :col :lat :lon :var :units :mean-unit])))
      (is (= (count summary/climate-periods) (count (:periods result))))
      (testing "means are filled by decade and converted from Kelvin"
        (let [periods (:periods result)
              p1940   (find-period periods 1940)
              p1950   (find-period periods 1950)
              p2020   (find-period periods 2020)
              p1960   (find-period periods 1960)]
          (is (< (Math/abs (- 10.0 (:mean p1940))) 1e-9))
          (is (= 100 (:n p1940)))
          (is (< (Math/abs (- 20.0 (:mean p1950))) 1e-9))
          (is (= 200 (:n p1950)))
          (is (< (Math/abs (- 0.0 (:mean p2020))) 1e-9))
          (is (= 50 (:n p2020)))
          (is (nil? (:mean p1960)))
          (is (zero? (:n p1960))))))))

(deftest era5-summary-test
  (testing "rejects unknown varnames with a 400 ex-data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown ERA5 variable"
                          (summary/era5-summary "nope" 198 1020))))

  (testing "rejects non-Kelvin variables with a 400 ex-data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require a Kelvin variable"
                          (summary/era5-summary "sp" 198 1020))))

  (testing "assembles grid metadata, units, bins, and per-period count grids"
    (let [bounds-ds (ds/->dataset {:min [253.15] :max [303.15] :n [3]})
          hist-ds   (ds/->dataset {:decade [1940 1940 2020]
                                   :bin    [0 1 0]
                                   :n      [1 2 5]})
          result    (with-redefs [fetch/ensure-ts! (fn [varname _row _col]
                                                     (is (= "t2m" varname))
                                                     "era_ts/198/1020/t2m.parquet")
                                  duckdb/query     (fn [sql]
                                                     (if (re-find #"min\(t2m\)" sql)
                                                       bounds-ds
                                                       hist-ds))]
                      (summary/era5-summary "t2m" 198 1020))]
      (is (= {:row 198 :col 1020 :lat 40.5 :lon 255.0 :var "t2m" :units "K" :bin-unit "°C"}
             (select-keys result [:row :col :lat :lon :var :units :bin-unit])))
      (is (= (count summary/climate-periods) (count (:periods result))))
      (testing "bins cover -20 °C .. 30 °C in 2 °C whole-degree intervals"
        (is (= 25 (count (:bins result))))
        (is (= -20.0 (:min (first (:bins result)))))
        (is (= 30.0 (:max (last (:bins result))))))
      (testing "observations land in their decade and bin"
        (let [periods (:periods result)
              p1940   (find-period periods 1940)
              p2020   (find-period periods 2020)]
          (is (= (into [1 2] (repeat 23 0)) (:counts p1940)))
          (is (= 3 (:n p1940)))
          (is (= 5 (nth (:counts p2020) 0)))
          (is (= 5 (:n p2020)))))
      (testing "opacity denominator is the max period × bin count"
        (is (= 5 (:max-count result))))))

  (testing "cells with no observations return empty bins and zeroed periods"
    (let [empty-bounds (ds/->dataset {:min [nil] :max [nil] :n [0]})
          result       (with-redefs [fetch/ensure-ts! (fn [_ _ _] "era_ts/198/1020/t2m.parquet")
                                     duckdb/query     (fn [_] empty-bounds)]
                         (summary/era5-summary "t2m" 198 1020))]
      (is (= [] (:bins result)))
      (is (every? (fn [p] (and (= [] (:counts p)) (zero? (:n p))))
                  (:periods result)))
      (is (zero? (:max-count result))))))
