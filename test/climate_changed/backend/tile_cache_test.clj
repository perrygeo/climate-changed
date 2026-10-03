(ns climate-changed.backend.tile-cache-test
  (:require [climate-changed.backend.tile-cache :as tile-cache]
            [clojure.test :refer [deftest is testing]]))

(deftest cache-get-test
  (testing "returns nil when the key is absent"
    (reset! tile-cache/tile-cache {})
    (is (nil? (tile-cache/cache-get [0 0 0]))))

  (testing "round-trips bytes through cache-put! and cache-get"
    (reset! tile-cache/tile-cache {})
    (let [b (byte-array [(byte 1) (byte 2) (byte 3)])]
      (tile-cache/cache-put! [0 0 0] b)
      (is (identical? b (tile-cache/cache-get [0 0 0]))))))

(deftest cache-evict-test
  (testing "drops expired entries and keeps live ones"
    (let [now   (System/currentTimeMillis)
          cache {[0 0 0] {:bytes (byte-array 1) :cached-at now :expires-at (- now 1000)}
                 [1 0 0] {:bytes (byte-array 1) :cached-at now :expires-at (+ now 60000)}}]
      (is (= #{[1 0 0]} (set (keys (tile-cache/cache-evict cache)))))))

  (testing "trims to max-entries keeping the most recently cached"
    (with-redefs [tile-cache/tile-cache-max-entries 2]
      (let [now   (System/currentTimeMillis)
            cache {[0 0 0] {:bytes (byte-array 1) :cached-at (- now 3000) :expires-at (+ now 60000)}
                   [1 0 0] {:bytes (byte-array 1) :cached-at (- now 2000) :expires-at (+ now 60000)}
                   [2 0 0] {:bytes (byte-array 1) :cached-at (- now 1000) :expires-at (+ now 60000)}}]
        (is (= #{[1 0 0] [2 0 0]} (set (keys (tile-cache/cache-evict cache)))))))))

(deftest cache-put!-eviction-test
  (testing "caps the cache at max-entries, evicting the least recently cached"
    (reset! tile-cache/tile-cache {})
    (with-redefs [tile-cache/tile-cache-max-entries 2]
      (tile-cache/cache-put! [0 0 0] (byte-array 1))
      (tile-cache/cache-put! [1 0 0] (byte-array 1))
      (tile-cache/cache-put! [2 0 0] (byte-array 1))
      (is (= 2 (count @tile-cache/tile-cache)))
      (is (nil? (tile-cache/cache-get [0 0 0])))
      (is (some? (tile-cache/cache-get [1 0 0])))
      (is (some? (tile-cache/cache-get [2 0 0]))))))

(deftest current-cache-size-mb-test
  (testing "returns 0.0 MiB for an empty cache"
    (reset! tile-cache/tile-cache {})
    (is (= 0.0 (tile-cache/current-cache-size-mb))))

  (testing "sums cached byte-array lengths in MiB"
    (reset! tile-cache/tile-cache
            {[0 0 0] {:bytes (byte-array (* 1024 1024)) :cached-at 0 :expires-at 1}
             [1 0 0] {:bytes (byte-array (* 1024 1024)) :cached-at 0 :expires-at 1}})
    (is (= 2.0 (tile-cache/current-cache-size-mb)))))
