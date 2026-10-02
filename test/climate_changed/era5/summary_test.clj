(ns climate-changed.era5.summary-test
  (:require
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

(deftest in-period-test
  (let [in-period? #'summary/in-period?
        t0         (java.time.Instant/parse "1940-01-01T00:00:00Z")
        t1         (java.time.Instant/parse "1945-01-01T00:00:00Z")
        t2         (java.time.Instant/parse "1950-01-01T00:00:00Z")]
    (testing "start is inclusive and end is exclusive"
      (is (in-period? t0 t0 t2))
      (is (not (in-period? t2 t0 t2))))
    (testing "nil end means unbounded"
      (is (in-period? t1 t0 nil))
      (is (not (in-period? t0 t1 nil))))
    (testing "instants outside [start end) are rejected"
      (is (not (in-period? t0 t1 t2)))
      (is (not (in-period? t2 t0 t1))))))

(deftest period-stats-test
  (let [period-stats #'summary/period-stats
        data         (ds/->dataset {"valid_time" [(java.time.Instant/parse "1940-01-01T00:00:00Z")
                                                  (java.time.Instant/parse "1941-01-01T00:00:00Z")
                                                  (java.time.Instant/parse "1950-06-01T00:00:00Z")
                                                  (java.time.Instant/parse "2024-06-01T00:00:00Z")]
                                    "t2m"        [280.0 281.0 282.0 283.0]})
        period       {:start (java.time.Instant/parse "1940-01-01T00:00:00Z")
                      :end   (java.time.Instant/parse "1950-01-01T00:00:00Z")}
        stats        (period-stats data "t2m" period)]
    (testing "restricts the dataset to rows in [start end)"
      (is (= 2 (:n stats))))
    (testing "computes descriptive statistics for the varname column"
      (is (= 280.0 (:min stats)))
      (is (= 280.5 (:mean stats)))
      (is (= 281.0 (:max stats)))
      (is (< 0.7 (:sd stats) 0.8)))
    (testing "start and end are stringified from the period's valid_time bounds"
      (is (= "1940-01-01T00:00:00Z" (:start stats)))
      (is (= "1941-01-01T00:00:00Z" (:end stats))))))

(deftest era5-summary-test
  (testing "rejects unknown varnames with a 400 ex-data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown ERA5 variable"
                          (summary/era5-summary "nope" 198 1020))))
  (testing "assembles grid metadata, units, and per-period stats"
    (let [data (ds/->dataset {"valid_time" [(java.time.Instant/parse "1940-01-01T00:00:00Z")
                                            (java.time.Instant/parse "2024-06-01T00:00:00Z")]
                              "t2m"        [280.0 283.0]})
          result (with-redefs [fetch/fetch-ts (fn [varname _row _col]
                                                (is (= "t2m" varname))
                                                data)]
                   (summary/era5-summary "t2m" 198 1020))]
      (is (= {:row 198 :col 1020 :lat 40.5 :lon 255.0 :var "t2m" :units "K"}
             (select-keys result [:row :col :lat :lon :var :units])))
      (is (seq (:periods result)))
      (is (every? #(and (contains? % :n)
                        (contains? % :min)
                        (contains? % :mean)
                        (contains? % :max)
                        (contains? % :sd)
                        (contains? % :start)
                        (contains? % :end))
                  (:periods result))))))
