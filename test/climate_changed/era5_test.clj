(ns climate-changed.era5-test
  (:require
   [climate-changed.era5 :as subject]
   [clojure.test :refer [deftest is]]))

(deftest snap-test
  (is (= (subject/snap-coords -105.0844 40.5853)
         {:row 198, :col 1020, :lat 40.5, :lon 255.0})))
