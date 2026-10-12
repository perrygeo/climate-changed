(ns climate-changed.era5.thresholds
  "Meaningful 'tipping points' for the ERA5 variables.")

(def t2m-thresholds
  "Temperature tipping points evaluable directly from an hourly `t2m` series.

  Each entry:
  - :key    keyword id
  - :k      threshold in Kelvin
  - :label  human-readable name
  - :source justification or citation"

  [{:key    :freezing
    :k      273.15
    :label  "Freezing point"
    :source "Water phase change; frost, biological niche boundaries"}

   {:key    :biological-zero
    :k      278.15
    :label  "Biological zero"
    :source "Base temperature for growth of many cool-season plants"}

   {:key    :warm-season-growth
    :k      283.15
    :label  "Warm-season growth base"
    :source "Lower developmental threshold for many warm-season crops/insects"}

   {:key    :summer-day
    :k      298.15
    :label  "Summer day"
    :source "WMO/ETCCDI 'summer day' (daily Tmax > 25 °C)"}

   {:key    :hot-day
    :k      303.15
    :label  "Hot day"
    :source "WMO/ETCCDI 'hot day' (daily Tmax > 30 °C)"}

   {:key    :us-hot
    :k      305.35
    :label  "Hot day (US 90 °F)"
    :source "US NWS convention, 90 °F"}

   {:key    :extreme-heat
    :k      308.15
    :label  "Extreme heat (95 °F)"
    :source "US NWS extreme-heat onset, 95 °F"}

   {:key    :core-body-temp
    :k      310.15
    :label  "Human core body temperature 98.2F"
    :source "Physiology; above this ambient temp passive cooling fails"}])

;; -----------------------------------------------------------------------------
;; Thresholds that need MORE than the `t2m` timeseries
;; -----------------------------------------------------------------------------
;;
;; Derived from daily Tmin/Tmax (needs resampling of hourly `t2m` to daily
;; min/max, not a single additional variable):
;;   - Frost day:      daily Tmin < 0 °C          (needs daily Tmin series)
;;   - Ice day:        daily Tmax < 0 °C          (needs daily Tmax series)
;;   - Tropical night: daily Tmin > 20 °C         (needs daily Tmin series)
;;
;; Needs humidity (dewpoint `d2m`) in addition to `t2m`:
;;   - Heat index               (needs humidity timeseries)
;;   - Wet-bulb temperature     (needs humidity timeseries)
;;   - 35 °C wet-bulb survivability limit (needs humidity timeseries)
;;   - Relative humidity        (needs humidity timeseries)
;;
;; Needs a chosen base temperature + accumulation over time (derivable from
;; `t2m`, but an aggregate rather than a single-value threshold):
;;   - Heating/Cooling degree days (base ~18.3 °C / 65 °F)
;;   - Growing degree days (base ~10 °C)
