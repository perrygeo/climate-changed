(ns climate-changed.frontend.units-test
  (:require [climate-changed.frontend.units :as u]
            [clojure.test :refer [deftest is testing]]))

(defn- close?
  "Floating-point equality within `epsilon`, for cross-platform float math."
  ([a b] (close? a b 1e-9))
  ([a b epsilon] (< (abs (- a b)) epsilon)))

(deftest kelvin->celsius-test
  (testing "converts Kelvin to Celsius"
    (is (close? 0.0 (u/kelvin->celsius 273.15)))
    (is (close? -273.15 (u/kelvin->celsius 0.0)))
    (is (close? 26.85 (u/kelvin->celsius 300.0)))
    (is (close? -40.0 (u/kelvin->celsius 233.15)))))

(deftest celsius->fahrenheit-test
  (testing "converts Celsius to Fahrenheit"
    (is (close? 32.0 (u/celsius->fahrenheit 0.0)))
    (is (close? 212.0 (u/celsius->fahrenheit 100.0)))
    (is (close? -40.0 (u/celsius->fahrenheit -40.0)))
    (is (close? 98.6 (u/celsius->fahrenheit 37.0)))))

(deftest temp-unit-symbol-test
  (testing "returns the display symbol for a temperature unit"
    (is (= "°F" (u/temp-unit-symbol :f)))
    (is (= "°C" (u/temp-unit-symbol :c)))
    (testing "defaults to Celsius for unknown units"
      (is (= "°C" (u/temp-unit-symbol :kelvin)))
      (is (= "°C" (u/temp-unit-symbol nil))))))

(deftest display-temp-test
  (testing "Kelvin values convert to the requested unit"
    (is (close? 0.0 (u/display-temp 273.15 "K" :c)))
    (is (close? 32.0 (u/display-temp 273.15 "K" :f)))
    (is (close? 26.85 (u/display-temp 300.0 "K" :c)))
    (is (close? 80.33 (u/display-temp 300.0 "K" :f))))
  (testing "unknown temperature units default to Celsius"
    (is (close? 0.0 (u/display-temp 273.15 "K" :kelvin))))
  (testing "non-Kelvin units pass through unchanged"
    (is (= 5.0 (u/display-temp 5.0 "m s⁻¹" :f)))
    (is (= 101325.0 (u/display-temp 101325.0 "Pa" :c)))
    (is (= "12" (u/display-temp "12" "(0-1)" :f)))))
