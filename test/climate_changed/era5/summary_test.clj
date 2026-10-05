(ns climate-changed.era5.summary-test
  (:require
   [climate-changed.era5.duckdb :as duckdb]
   [climate-changed.era5.fetch :as fetch]
   [climate-changed.era5.summary :as summary]
   [clojure.test :refer [deftest is testing]]
   [tech.v3.dataset :as ds]))

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

(deftest decade-summary-sql-test
  (let [decade-summary-sql #'summary/decade-summary-sql
        sql                (decade-summary-sql "t2m" "era_ts/182/104/t2m.parquet")]
    (testing "buckets by UTC decade with integer division"
      (is (re-find #"\(year\(valid_time\) // 10\) \* 10 AS decade" sql)))
    (testing "casts min/max to DOUBLE and uses sample stddev"
      (is (re-find #"min\(t2m\)::DOUBLE" sql))
      (is (re-find #"max\(t2m\)::DOUBLE" sql))
      (is (re-find #"stddev_samp\(t2m\)" sql)))
    (testing "reads the given parquet path"
      (is (re-find #"FROM 'era_ts/182/104/t2m\.parquet'" sql)))
    (testing "quotes reserved-word aliases"
      (is (re-find #"AS \"min\"" sql))
      (is (re-find #"AS \"start\"" sql)))))

(deftest row->period-stats-test
  (let [row->period-stats #'summary/row->period-stats
        stats             (row->period-stats {:decade 1940
                                              :n      2
                                              :min    280.0
                                              :mean   280.5
                                              :max    281.0
                                              :sd     0.7071
                                              :start  (java.time.Instant/parse "1940-01-01T00:00:00Z")
                                              :end    (java.time.Instant/parse "1941-01-01T00:00:00Z")})]
    (testing "passes through the numeric stats"
      (is (= 2 (:n stats)))
      (is (= 280.0 (:min stats)))
      (is (= 280.5 (:mean stats)))
      (is (= 281.0 (:max stats)))
      (is (= 0.7071 (:sd stats))))
    (testing "start and end are stringified from valid_time bounds"
      (is (= "1940-01-01T00:00:00Z" (:start stats)))
      (is (= "1941-01-01T00:00:00Z" (:end stats))))))

(deftest decade-stats-test
  (let [decade-stats #'summary/decade-stats
        result       (ds/->dataset {:decade [1940 1950 2020]
                                    :n      [2 1 1]
                                    :min    [280.0 282.0 283.0]
                                    :mean   [280.5 282.0 283.0]
                                    :max    [281.0 282.0 283.0]
                                    :sd     [0.7 0.0 0.0]
                                    :start  [(java.time.Instant/parse "1940-01-01T00:00:00Z")
                                             (java.time.Instant/parse "1950-06-01T00:00:00Z")
                                             (java.time.Instant/parse "2024-06-01T00:00:00Z")]
                                    :end    [(java.time.Instant/parse "1941-01-01T00:00:00Z")
                                             (java.time.Instant/parse "1950-06-01T00:00:00Z")
                                             (java.time.Instant/parse "2024-06-01T00:00:00Z")]})
        stats        (decade-stats result)]
    (testing "keys the stats by decade start year"
      (is (= #{1940 1950 2020} (set (keys stats))))
      (is (= 2 (get-in stats [1940 :n])))
      (is (= 1 (get-in stats [1950 :n])))
      (is (= 1 (get-in stats [2020 :n]))))
    (testing "per-decade stats carry through"
      (is (= 280.5 (get-in stats [1940 :mean])))
      (is (= 282.0 (get-in stats [1950 :mean])))
      (is (= 283.0 (get-in stats [2020 :mean]))))))

(deftest era5-summary-test
  (testing "rejects unknown varnames with a 400 ex-data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown ERA5 variable"
                          (summary/era5-summary "nope" 198 1020))))
  (testing "assembles grid metadata, units, and per-period stats"
    (let [result-ds (ds/->dataset {:decade [1940 2020]
                                   :n      [1 1]
                                   :min    [280.0 283.0]
                                   :mean   [280.0 283.0]
                                   :max    [280.0 283.0]
                                   :sd     [0.0 0.0]
                                   :start  [(java.time.Instant/parse "1940-01-01T00:00:00Z")
                                            (java.time.Instant/parse "2024-06-01T00:00:00Z")]
                                   :end    [(java.time.Instant/parse "1940-01-01T00:00:00Z")
                                            (java.time.Instant/parse "2024-06-01T00:00:00Z")]})
          result    (with-redefs [fetch/ensure-ts! (fn [varname _row _col]
                                                     (is (= "t2m" varname))
                                                     "era_ts/198/1020/t2m.parquet")
                                  duckdb/query     (fn [_sql] result-ds)]
                      (summary/era5-summary "t2m" 198 1020))]
      (is (= {:row 198 :col 1020 :lat 40.5 :lon 255.0 :var "t2m" :units "K"}
             (select-keys result [:row :col :lat :lon :var :units])))
      (is (= (count summary/climate-periods) (count (:periods result))))
      (testing "observations land in their decade; empty decades stay nil"
        (let [periods (:periods result)]
          (is (= 1 (:n (first periods))))
          (is (= "1940-01-01T00:00:00Z" (:start (first periods))))
          (is (= 280.0 (:mean (first periods))))
          (is (= 1 (:n (last periods))))
          (is (= "2024-06-01T00:00:00Z" (:start (last periods))))
          (is (= 283.0 (:mean (last periods))))
          (is (every? (fn [p] (and (nil? (:n p)) (nil? (:start p))))
                      (butlast (rest periods))))))
      (testing "every period carries the full stat shape"
        (is (every? #(and (contains? % :n)
                          (contains? % :min)
                          (contains? % :mean)
                          (contains? % :max)
                          (contains? % :sd)
                          (contains? % :start)
                          (contains? % :end))
                    (:periods result)))))))
