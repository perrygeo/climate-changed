(ns climate-changed.era5.fetch-test
  (:require [climate-changed.era5.fetch :as fetch]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(defn- reset-fetch-status [f]
  (reset! fetch/fetch-ts-status {})
  (f))

(use-fixtures :each reset-fetch-status)

(deftest try-acquire-and-release-test
  (let [acquire #'fetch/try-acquire-fetch!
        release #'fetch/release-fetch!]
    (testing "acquires an unused slot"
      (is (acquire "t2m" 10 20)))
    (testing "rejects a duplicate fetch for the same pixel"
      (is (not (acquire "t2m" 10 20))))
    (testing "release frees the slot for a retry"
      (release "t2m" 10 20)
      (is (acquire "t2m" 10 20)))))

(deftest max-concurrent-fetches-test
  (let [acquire #'fetch/try-acquire-fetch!
        release #'fetch/release-fetch!]
    (testing "allows up to max-concurrent-fetches distinct fetches"
      (is (acquire "t2m" 1 1))
      (is (acquire "t2m" 1 2))
      (is (acquire "t2m" 1 3)))
    (testing "rejects fetches beyond capacity"
      (is (not (acquire "t2m" 1 4))))
    (testing "freeing one slot admits another fetch"
      (release "t2m" 1 1)
      (is (acquire "t2m" 1 4)))))

;; future ideas:
;; - era-download-cmd: with-redefs fetcher-path to assert [bin row col varname]
;;   arg order and the missing-binary ex-info.
;; - expected-path: contract test that the path matches what the Rust fetcher writes.
;; - fix-valid-time: build a dataset with int64 nanosecond valid_time and assert
;;   the ns->us->Instant conversion.
