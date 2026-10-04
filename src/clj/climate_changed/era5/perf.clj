(ns climate-changed.era5.perf
  "Lightweight phase-timing instrumentation for the ERA5 request pipeline.

  Every timed phase emits a single greppable INFO line of the form:

    era5-perf phase=<phase> ms=<millis> <k=v ...>

  so production logs can be filtered with `grep 'era5-perf'` and the
  phase durations compared against local timings.

  Phase nesting (outer phases contain the inner ones):

    summary-total                 whole era5-summary compute
    |
    +-- fetch-ts                  fetch (or cache-hit) of the pixel timeseries
    |   |
    |   +-- fetch-s3              Rust binary's Icechunk/S3 query (cache MISS only)
    |   +-- parquet-read         parquet->ds + valid_time fixup (always)
    |
    +-- stats-compute            per-decade descriptive-stats loop

  So on a warm (cached) cell: summary-total ~= parquet-read + stats-compute.
  On a cold cell, fetch-s3 is added and usually dominates."
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]))

(set! *warn-on-reflection* true)

(defn- kvs->str
  "Render an ordered seq of [k v] pairs as `k=v k=v` for log lines."
  [kvs]
  (->> kvs
       (map (fn [[k v]] (str (name k) "=" v)))
       (str/join " ")))

(defn log-phase
  "Emit a single `era5-perf` timing line. `elapsed-ns` is a nanosecond
  duration; `kvs` is an optional seq of [key value] pairs appended verbatim."
  ([phase elapsed-ns] (log-phase phase elapsed-ns nil))
  ([phase elapsed-ns kvs]
   (let [ms (/ (double elapsed-ns) 1e6)]
     (log/info (str "era5-perf phase=" (name phase)
                    (format " ms=%.1f" ms)
                    (when (seq kvs) (str " " (kvs->str kvs))))))))

(defmacro timed
  "Evaluate `body`, log an `era5-perf` line for `phase`, and return the value.

  `kvs` (optional) is an expression yielding a seq of [key value] pairs to
  include on the log line; it is evaluated after `body` so it may reference
  the surrounding context but not the result."
  {:clj-kondo/lint-as 'clojure.core/let}
  [phase kvs & body]
  `(let [start# (System/nanoTime)
         result# (do ~@body)]
     (log-phase ~phase (- (System/nanoTime) start#) ~kvs)
     result#))
