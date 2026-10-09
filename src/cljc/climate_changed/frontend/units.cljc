(ns climate-changed.frontend.units
  "Temperature unit conversion at the UI level. The API reports temperature
  variables in Kelvin; display values are converted here to the user's chosen
  unit (°C or °F) without touching the wire format.")

(defn kelvin->celsius [k]
  (- k 273.15))

(defn celsius->fahrenheit [c]
  ;; 9/5 is a ratio on the JVM but not in CLJS, so use the float form
  ;; for consistent cross-platform behavior.
  (+ 32 (* c 1.8)))

(defn temp-unit-symbol
  "Display symbol for a temperature unit keyword, e.g. :f -> \"°F\"."
  [unit]
  (case unit
    :f "°F"
    "°C"))

(defn display-temp
  "Temperature in the user's display units. `units` is the API unit string for
  the variable; Kelvin values are converted to °C or °F per `temp-unit`, all
  other units pass through unchanged."
  [v units temp-unit]
  (if (= units "K")
    (let [c (kelvin->celsius v)]
      (case temp-unit
        :f (celsius->fahrenheit c)
        c))
    v))
