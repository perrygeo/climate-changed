(ns climate-changed.era5.variables-test
  (:require [climate-changed.era5.variables :as vars]
            [clojure.test :refer [deftest is testing]]))

(deftest valid-varname-test
  (testing "accepts strings and keywords for known variables"
    (is (vars/valid-varname? "t2m"))
    (is (vars/valid-varname? :t2m))
    (is (vars/valid-varname? :sst)))
  (testing "rejects unknown names"
    (is (not (vars/valid-varname? "nope")))
    (is (not (vars/valid-varname? :nope)))))
