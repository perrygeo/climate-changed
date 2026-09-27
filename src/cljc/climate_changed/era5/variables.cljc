(ns climate-changed.era5.variables)

(def era5-variables
  {:t2m  {:name  "2 Metre Temperature"
          :units "K"}
   :d2m  {:name  "2 Metre Dewpoint Temperature"
          :units "K"}
   :u10  {:name  "10 Metre U Wind Component"
          :units "m s⁻¹"}
   :v10  {:name  "10 Metre V Wind Component"
          :units "m s⁻¹"}
   :sp   {:name  "Surface Pressure"
          :units "Pa"}
   :msl  {:name  "Mean Sea Level Pressure"
          :units "Pa"}
   :tp   {:name  "Total Precipitation"
          :units "m"}
   :sst  {:name  "Sea Surface Temperature"
          :units "K"}
   :tcc  {:name  "Total Cloud Cover"
          :units "(0-1)"}
   :tcwv {:name  "Total Column Water Vapour"
          :units "kg m⁻²"}
   :ssrd {:name  "Surface Solar Radiation Downwards"
          :units "J m⁻²"}
   :strd {:name  "Surface Thermal Radiation Downwards"
          :units "J m⁻²"}
   :ssr  {:name  "Surface Net Solar Radiation"
          :units "J m⁻²"}
   :str  {:name  "Surface Net Thermal Radiation"
          :units "J m⁻²"}})
