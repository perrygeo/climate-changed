#!/usr/bin/env bb
;; Analyze climate-changed systemd logs over SSH.
;;
;; Pulls the request-logging lines emitted by `wrap-request-logging`
;; (logger "climate-changed.http") out of journalctl on the prod host and
;; computes some meaningful operational metrics: traffic, errors, latency
;; (the "golden signals") plus IP-address and endpoint usage.
;;
;; Usage:
;;   infra/analyze-logs.clj <ssh-target> [--since "<journalctl since>"] [--top N]
;;
;; Examples:
;;   infra/analyze-logs.clj root@98.89.137.144
;;   infra/analyze-logs.clj root@98.89.137.144 --since "today"
;;   infra/analyze-logs.clj root@98.89.137.144 --since "2 hours ago" --top 20
;;
;; The log line format produced by the middleware is:
;;   <IP> <METHOD> <TARGET> -> <STATUS> (<N> ms)[ <error message>]
;; wrapped by logback as:
;;   HH:mm:ss.SSS [thread] LEVEL climate-changed.http -- <message>

(require '[babashka.process :as p]
         '[clojure.string :as str])

;; ---------------------------------------------------------------------------
;; CLI parsing
;; ---------------------------------------------------------------------------

(defn parse-args [args]
  (loop [opts {:since "24 hours ago" :top 15}
         [a & more :as argv] args]
    (cond
      (nil? a) opts
      (= a "--since") (recur (assoc opts :since (first more)) (rest more))
      (= a "--top")   (recur (assoc opts :top (parse-long (first more))) (rest more))
      (str/starts-with? a "--") (binding [*out* *err*]
                                  (println "unknown arg:" a)
                                  (System/exit 2))
      :else (recur (assoc opts :ssh-target a) more))))

;; ---------------------------------------------------------------------------
;; Log fetching + parsing
;; ---------------------------------------------------------------------------

(def line-re
  ;; Everything after " -- ": <IP> <METHOD> <TARGET> -> <STATUS> (<N> ms)...
  #".* -- (\S+) (\S+) (\S+) -> (\d+) \((\d+) ms\)")

(defn parse-line [line]
  (when-let [[_ ip method target status ms] (re-find line-re line)]
    {:ip ip
     :method method
     ;; Normalize endpoint: drop query string for grouping.
     :endpoint (str/replace target #"\?.*" "")
     :status (parse-long status)
     :status-class (str (subs status 0 1) "xx")
     :ms (parse-long ms)}))

(defn fetch-logs [ssh-target since]
  (let [cmd (format "journalctl -u climate-changed.service --since '%s' --no-pager -o cat" since)
        {:keys [out exit err]}
        (p/sh "ssh" "-o" "StrictHostKeyChecking=accept-new" ssh-target cmd)]
    (when-not (zero? exit)
      (binding [*out* *err*] (println "ssh failed:" err))
      (System/exit 1))
    (->> (str/split-lines out)
         (filter #(and (str/includes? % "climate-changed.http --")
                       (str/includes? % " -> ")))
         (keep parse-line))))

;; ---------------------------------------------------------------------------
;; Metrics
;; ---------------------------------------------------------------------------

(defn percentile [sorted-coll q]
  (nth sorted-coll (int (* (count sorted-coll) q))))

(defn freq-by [reqs k]
  (->> reqs (map k) frequencies))

(defn top-n [freqs n]
  (->> freqs (sort-by val >) (take n)))

(defn compute-metrics [reqs]
  (let [total (count reqs)
        by-class (freq-by reqs :status-class)
        latencies (->> reqs (keep :ms) sort vec)
        ep-latency (->> reqs
                        (filter :ms)
                        (group-by :endpoint)
                        (map (fn [[ep rs]]
                               (let [ms (map :ms rs)]
                                 [ep {:avg (/ (reduce + ms) (double (count ms)))
                                      :n (count ms)}])))
                        (into {}))]
    {:total total
     :err4 (get by-class "4xx" 0)
     :err5 (get by-class "5xx" 0)
     :latencies latencies
     :by-status (freq-by reqs :status)
     :by-method (freq-by reqs :method)
     :by-ip (freq-by reqs :ip)
     :by-endpoint (freq-by reqs :endpoint)
     :ep-latency ep-latency}))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn pct [n total] (if (pos? total) (* 100.0 (/ n total)) 0.0))

(defn section [title] (printf "\n================ %s ================\n" title))

(defn report [{:keys [total err4 err5 latencies by-status by-method
                      by-ip by-endpoint ep-latency]} top]
  (section "GOLDEN SIGNALS")
  (printf "Total requests        : %d\n" total)
  (printf "4xx (client errors)   : %d (%.2f%%)\n" err4 (pct err4 total))
  (printf "5xx (server errors)   : %d (%.2f%%)\n" err5 (pct err5 total))

  (when (seq latencies)
    (printf "Latency ms avg        : %.1f\n" (/ (reduce + latencies) (double (count latencies))))
    (printf "Latency ms p50/p90    : %d / %d\n" (percentile latencies 0.50) (percentile latencies 0.90))
    (printf "Latency ms p95/p99    : %d / %d\n" (percentile latencies 0.95) (percentile latencies 0.99))
    (printf "Latency ms max        : %d\n" (apply max latencies)))

  (section "STATUS CODES")
  (doseq [[s c] (sort-by val > by-status)]
    (printf "  %-5s %8d\n" (str s) c))

  (section "METHODS")
  (doseq [[m c] (sort-by val > by-method)]
    (printf "  %-7s %8d\n" m c))

  (section (format "TOP %d IP ADDRESSES" top))
  (doseq [[ip c] (top-n by-ip top)]
    (printf "  %8d  %s\n" c ip))

  (section (format "TOP %d ENDPOINTS" top))
  (doseq [[ep c] (top-n by-endpoint top)]
    (printf "  %8d  %s\n" c ep))

  (section (format "TOP %d SLOWEST ENDPOINTS (avg ms)" top))
  (doseq [[ep {:keys [avg n]}] (->> ep-latency (sort-by (comp :avg val) >) (take top))]
    (printf "  %8.1f  (%d reqs)  %s\n" avg n ep))

  (println))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(let [{:keys [ssh-target since top]} (parse-args *command-line-args*)]
  (when-not ssh-target
    (binding [*out* *err*]
      (println "usage: analyze-logs.clj <ssh-target> [--since \"<since>\"] [--top N]"))
    (System/exit 2))

  (println (format "==> Fetching climate-changed.http logs from %s (since: %s)" ssh-target since))
  (let [reqs (fetch-logs ssh-target since)]
    (if (empty? reqs)
      (println "No request log lines found for the given window.")
      (report (compute-metrics reqs) top))))
