(ns climate-changed.era5.duckdb
  "A sandboxed, read-only DuckDB used to read and aggregate the ERA5 parquet
  timeseries directly, replacing the parquet -> tech.ml.dataset -> tablecloth
  pipeline for the climate summary.

  Lifecycle (managed by the `:climate-changed/duckdb` Integrant component):

    (init!)       once at startup: load libduckdb, open one in-memory DB, and
                  lock down the global configuration (see `apply-sandbox!`).
    (query sql)   per request: opens a short-lived connection on the shared DB,
                  runs `sql`, and returns a tech.ml.dataset. A connection per
                  query keeps concurrent requests thread-safe; `connect` is cheap
                  compared to `open-db`.
    (shutdown!)   at halt: close the DB.

  Native library: ducktape binds libduckdb.so via the JDK Foreign Function API,
  so `libduckdb.so` must be discoverable on the library path (LD_LIBRARY_PATH on
  Linux/NixOS) and the JVM should run with `--enable-native-access=ALL-UNNAMED`."
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [ducktape.core :as duck]))

(set! *warn-on-reflection* true)

(def ^:private data-dir
  "Directory holding the ERA5 parquet cache, relative to the process cwd. This
  is the ONLY directory the sandboxed DuckDB is permitted to read."
  "era_ts")

(defonce ^:private state
  ;; {:db <db-handle>} once initialized, otherwise nil.
  (atom nil))

(defn initialized? []
  (some? @state))

(defn data-dir-abs
  "Absolute path of the ERA5 parquet directory."
  ^String []
  (.getAbsolutePath (io/file data-dir)))

(defn- apply-sandbox!
  "Lock the global DuckDB configuration down to a read-only view of `data-dir`.

  ORDER MATTERS: `allowed_directories` must be set *before*
  `enable_external_access=false` (false blocks all file access, and the allowed
  directories are carved back in as the only exceptions). `lock_configuration`
  must be last so no later query can relax any of these settings. These settings
  are global and are inherited by every connection opened on the DB afterwards."
  [conn {:keys [memory-limit threads] :or {memory-limit "1GB" threads 2}}]
  (doseq [stmt [(str "SET allowed_directories=['" (data-dir-abs) "']")
                "SET autoinstall_known_extensions=false"
                "SET autoload_known_extensions=false"
                (str "SET memory_limit='" memory-limit "'")
                (str "SET threads=" threads)
                "SET enable_external_access=false"
                "SET lock_configuration=true"]]
    (duck/run-query! conn stmt)))

(defn init!
  "Load DuckDB, open one shared in-memory database, and apply the sandbox.
  Idempotent: a second call while already initialized is a no-op. `opts` may
  contain `:memory-limit` (string, e.g. \"1GB\") and `:threads` (int)."
  ([] (init! {}))
  ([opts]
   (if (initialized?)
     @state
     (do
       (duck/initialize!)
       (let [db   (duck/open-db)
             conn (duck/connect db)]
         (try
           (apply-sandbox! conn opts)
           (finally
             (duck/disconnect conn)))
         (reset! state {:db db})
         (log/info (str "DuckDB initialized (sandboxed read-only access to "
                        (data-dir-abs) ")"))
         @state)))))

(defn shutdown!
  "Close the shared database and clear the state."
  []
  (when-let [{:keys [db]} @state]
    (duck/close-db db)
    (reset! state nil)
    (log/info "DuckDB shut down")))

(defn query
  "Run `sql` on a short-lived connection to the shared sandboxed DB and return a
  tech.ml.dataset with keyword column names."
  [sql]
  (let [{:keys [db]} (or @state
                         (throw (ex-info "DuckDB not initialized" {})))
        conn         (duck/connect db)]
    (try
      (duck/sql->dataset conn sql {:key-fn keyword})
      (finally
        (duck/disconnect conn)))))
