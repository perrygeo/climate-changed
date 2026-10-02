(ns climate-changed.backend.location-index-test
  (:require [climate-changed.backend.location-index :as location-index]
            [climate-changed.era5.fetch :as fetch]
            [clojure.test :refer [deftest is testing]]))

(deftest prime-meridian-query-test
  (testing "a bbox straddling the prime meridian still finds London"
    (location-index/init!)
    (let [london-query [-0.125 51.375 0.125 51.625]
          names        (set (map :name (location-index/query london-query)))]
      (is (contains? names "London")
          (str "expected London in " names)))))

(deftest location-stats-test
  (location-index/init!)
  (testing "n-locations reflects the initialized index"
    (is (pos? (location-index/n-locations))))
  (testing "n-locations-with-ts never exceeds the indexed location count"
    (is (<= (location-index/n-locations-with-ts)
            (location-index/n-locations)))))

(deftest locations-to-be-fetched-test
  (location-index/init!)
  (let [cached (location-index/cached-cells)]
    (testing "the queue is a set of [varname row col] tuples"
      (is (set? @location-index/locations-to-be-fetched))
      (is (every? (fn [[varname row col]]
                    (and (string? varname)
                         (integer? row)
                         (integer? col)))
                  @location-index/locations-to-be-fetched)))
    (testing "no queued tuple is already in the era_ts cache"
      (is (every? (fn [[_varname row col]]
                    (not (contains? cached [row col])))
                  @location-index/locations-to-be-fetched)))))

(deftest consume-location-fetch-test
  (testing "an empty queue returns nil"
    (reset! location-index/locations-to-be-fetched #{})
    (is (nil? (location-index/consume-location-fetch))))
  (testing "a successful fetch pops the tuple from the queue"
    (reset! location-index/locations-to-be-fetched #{["t2m" 10 20]})
    (with-redefs [fetch/fetch-ts (fn [varname row col]
                                   (is (= ["t2m" 10 20] [varname row col]))
                                   :ok)]
      (is (= ["t2m" 10 20] (location-index/consume-location-fetch)))
      (is (empty? @location-index/locations-to-be-fetched)))))
