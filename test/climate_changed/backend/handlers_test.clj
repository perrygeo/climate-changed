(ns climate-changed.backend.handlers-test
  (:require [cheshire.core :as json]
            [climate-changed.backend.handlers :as handlers]
            [climate-changed.backend.location-index :as loc]
            [climate-changed.era5.grid :as grid]
            [climate-changed.era5.summary :as summary]
            [clojure.test :refer [deftest is testing]]))

(deftest ->long-test
  (let [->long #'handlers/->long]
    (testing "parses integer strings"
      (is (= 42 (->long "42")))
      (is (= -1 (->long "-1"))))
    (testing "returns nil for non-integer and nil input"
      (is (nil? (->long "abc")))
      (is (nil? (->long nil))))))

(deftest era5-summary-handler-test
  (testing "rejects missing or non-integer row/col"
    (is (= 400 (:status (handlers/era5-summary-handler {:route-params {}}))))
    (is (= 400 (:status (handlers/era5-summary-handler {:route-params {:row "abc" :col "10"}})))))

  (testing "404 when no indexed locations fall in the cell"
    (with-redefs [grid/cell-bbox (fn [_ _] [0 0 1 1])
                  loc/query      (fn [_] nil)]
      (let [resp (handlers/era5-summary-handler {:route-params {:row "10" :col "20"}})]
        (is (= 404 (:status resp)))
        (is (= "no locations in grid cell"
               (:error (json/parse-string (:body resp) true)))))))

  (testing "200 returns the summary as JSON"
    (with-redefs [grid/cell-bbox       (fn [_ _] [0 0 1 1])
                  loc/query            (fn [_] [:london])
                  summary/era5-summary (fn [varname row col]
                                         {:varname varname :row row :col col})]
      (let [resp (handlers/era5-summary-handler {:route-params {:row "10" :col "20"}})
            body (json/parse-string (:body resp) true)]
        (is (= 200 (:status resp)))
        (is (= "application/json" (get-in resp [:headers "Content-Type"])))
        (is (= "t2m" (:varname body)))
        (is (= 10 (:row body)))
        (is (= 20 (:col body))))))

  (testing "passes through ex-data :status errors"
    (with-redefs [grid/cell-bbox       (fn [_ _] [0 0 1 1])
                  loc/query            (fn [_] [:london])
                  summary/era5-summary (fn [_ _ _]
                                         (throw (ex-info "ERA5 fetch already in progress"
                                                         {:status 423})))]
      (let [resp (handlers/era5-summary-handler {:route-params {:row "10" :col "20"}})]
        (is (= 423 (:status resp)))
        (is (= "ERA5 fetch already in progress"
               (:error (json/parse-string (:body resp) true)))))))

  (testing "rethrows errors without ex-data :status"
    (with-redefs [grid/cell-bbox       (fn [_ _] [0 0 1 1])
                  loc/query            (fn [_] [:london])
                  summary/era5-summary (fn [_ _ _]
                                         (throw (ex-info "boom" {})))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (handlers/era5-summary-handler {:route-params {:row "10" :col "20"}}))))))

(deftest tiles-s2cloudless-handler-test
  (testing "rejects non-integer params"
    (is (= 400 (:status (handlers/tiles-s2cloudless-handler {:route-params {}}))))
    (is (= 400 (:status (handlers/tiles-s2cloudless-handler {:route-params {:z "a" :y "0" :x "0"}})))))

  (testing "rejects out-of-range tiles"
    (is (= 404 (:status (handlers/tiles-s2cloudless-handler {:route-params {:z "19" :y "0" :x "0"}}))))
    (is (= 404 (:status (handlers/tiles-s2cloudless-handler {:route-params {:z "2" :y "4" :x "0"}})))))

  (testing "returns 502 when the upstream tile is unavailable"
    (with-redefs [handlers/fetch-tile (fn [_ _ _] nil)]
      (let [resp (handlers/tiles-s2cloudless-handler {:route-params {:z "0" :y "0" :x "0"}})]
        (is (= 502 (:status resp)))
        (is (= "upstream tile unavailable"
               (:error (json/parse-string (:body resp) true)))))))

  (testing "proxies upstream tiles and serves subsequent requests from cache"
    (let [k [1 0 0]]
      (with-redefs [handlers/fetch-tile (fn [z y x]
                                          (when (= [z y x] k)
                                            (byte-array [(byte 1) (byte 2)])))]
        (let [resp (handlers/tiles-s2cloudless-handler {:route-params {:z "1" :y "0" :x "0"}})]
          (is (= 200 (:status resp)))
          (is (= "image/jpeg" (get-in resp [:headers "Content-Type"])))
          (is (= "public, max-age=604800" (get-in resp [:headers "Cache-Control"])))
          (is (some? ((var handlers/cache-get) k)))
          ;; second request is served from cache without another upstream fetch
          (is (= 200 (:status (handlers/tiles-s2cloudless-handler
                               {:route-params {:z "1" :y "0" :x "0"}})))))))))

;; future ideas:
;; - location-stats-handler: with-redefs loc/n-locations and loc/n-locations-with-ts
;;   to assert {:n :complete :pct} (including the nil pct when n is zero).
;; - static handlers (healthz, hello, method-not-allowed, locations): smoke-test
;;   status / content-type / body.
