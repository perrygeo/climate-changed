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

(defn celsius->display
  "Convert a Celsius temperature to the user's display unit (:c or :f)."
  [c temp-unit]
  (case temp-unit
    :f (celsius->fahrenheit c)
    c))

(defn display-temp
  "Temperature in the user's display units. `units` is the unit string for the
  value; Kelvin is converted to °C/°F and Celsius to °C/°F per `temp-unit`,
  all other units pass through unchanged."
  [v units temp-unit]
  (cond
    (= units "K")  (let [c (kelvin->celsius v)]
                     (case temp-unit
                       :f (celsius->fahrenheit c)
                       c))
    (= units "°C") (celsius->display v temp-unit)
    :else v))
