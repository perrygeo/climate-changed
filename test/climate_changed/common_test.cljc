(ns climate-changed.common-test
  (:require [climate-changed.common :as shared]
            [clojure.test :refer [deftest is testing]]))

(deftest appname-test
  (is (= "Climate Changed" shared/appname)))

(deftest styles-test
  (testing "styles is a non-empty vector of garden data"
    (is (vector? shared/styles))
    (is (seq shared/styles)))
  (testing "light-theme :root block comes first"
    (is (= ":root" (first (first shared/styles))))
    (is (= "#ffffff" (get-in shared/styles [0 1 :--bg-primary]))))
  (testing "dark-mode media query is present"
    (is (= :media (get-in shared/styles [1 :identifier])))
    (is (= "dark" (get-in shared/styles [1 :value :media-queries :prefers-color-scheme]))))
  (testing "contains a :body rule"
    (is (some #(and (vector? %) (= :body (first %))) shared/styles))))
