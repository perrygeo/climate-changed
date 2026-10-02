(ns climate-changed.backend.location-index-test
  (:require [climate-changed.backend.location-index :as location-index]
            [clojure.test :refer [deftest is testing]]))

(deftest prime-meridian-query-test
  (testing "a bbox straddling the prime meridian still finds London"
    (location-index/init!)
    (let [london-query [-0.125 51.375 0.125 51.625]
          names        (set (map :name (location-index/query london-query)))]
      (is (contains? names "London")
          (str "expected London in " names)))))
