(ns climate-changed.routes
  "Shared bidi route definitions.

  The right-hand side of each route is a vector of alternatives: GET
  resolves to the named route, while any other method resolves to
  `:method-not-allowed`. Route names are used for reverse URL generation
  with `bidi.bidi/path-for` on both the server and client; the backend
  resolves them to Ring handlers (see `climate-changed.backend.server`).")

(defn- get-only
  "A route target that accepts GET (resolving to `name`) and rejects all
  other methods with the `:method-not-allowed` route name."
  [name]
  [[:get name]
   [{:request-method (constantly true)} :method-not-allowed]])

(def routes
  "Named application routes, constrained to HTTP GET."
  ["/" {""                                  (get-only :home)
        "map"                               (get-only :map)
        "healthz"                           (get-only :healthz)
        "api/locations/stats"               (get-only :location-stats)
        "api/hello"                         (get-only :hello)
        ["api/era5-summary/" :row "/" :col] (get-only :era5-summary)
        "api/locations"                     (get-only :locations)}])
