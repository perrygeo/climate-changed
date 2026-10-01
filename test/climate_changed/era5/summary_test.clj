(ns climate-changed.era5.summary-test
  (:require
   [climate-changed.era5.summary :as summary]
   [clojure.test :refer [deftest is testing]]))

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
