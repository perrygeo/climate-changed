#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.14"
# dependencies = [
#     "icechunk",
#     "numpy",
#     "numcodecs[pcodec]",
#     "pyarrow",
#     "xarray",
# ]
# ///

# The icechunk -> parquet conversion can be done in pure Rust (no xarray):
# see src/rs/era5-timeseries/ for a verified Rust implementation.

# Smoke test (2026-09-28, t2m @ row 198 col 1020, 3 warmed runs each):
#   rust (release) ~9.5–10.1 s vs python (uv + xarray) ~11.2–13.3 s wall-clock.
#   Rust is ~18% faster; both are dominated by S3 reads + pcodec decode, and
#   Rust also reconstructs valid_time arithmetically instead of reading it.

import argparse
import os

import icechunk
import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq
import xarray as xr

"""
Note: Arbitrary lat/lon are not handled!

Assumed everything is already converted to rows/cols
on the ERA5 0.25-deg global grid pixel.

The ERA5 store's grid is
    latitude:  721 points,  90.0 .. -90.0 (row 0 = north pole)
    longitude: 1440 points, 0.0 .. 359.75 (col 0 = prime meridian)
"""


storage = icechunk.s3_storage(
    bucket="earthmover-icechunk-era5",
    prefix="icechunkV2",
    region="us-east-1",
    anonymous=True,
)
repo = icechunk.Repository.open(storage)
session = repo.readonly_session("main")
ds = xr.open_zarr(
    session.store,
    group="single/temporal",
    consolidated=False,
)


# ERA5 variable descriptions:
# t2m  - 2-metre temperature (K)
# tp   - Total precipitation (m)
# u10  - 10-metre U wind component (m/s, eastward)
# v10  - 10-metre V wind component (m/s, northward)
# ssrd - Surface solar radiation downwards (J/m²)
# d2m  - 2-metre dewpoint temperature (K)
# msl  - Mean sea level pressure (Pa)
KEY_VARIABLES = ["t2m"]


def gen_timeseries(var: str, row: int, col: int) -> np.ndarray:
    """Timeseries of `var` at the grid cell (row, col)."""
    da = ds[var].isel(latitude=row, longitude=col)
    return da.values


# See https://registry.opendata.aws/earthmover-era5/

# <xarray.Dataset> Size: 119TB
# Dimensions:     (valid_time: 756048, latitude: 721, longitude: 1440)
# Coordinates:
#   * valid_time  (valid_time) datetime64[ns] 6MB 1940-01-01 ... 2026-03-31T23:...
#   * latitude    (latitude) float64 6kB 90.0 89.75 89.5 ... -89.5 -89.75 -90.0
#   * longitude   (longitude) float64 12kB 0.0 0.25 0.5 0.75 ... 359.2 359.5 359.8
#     lsm         (latitude, longitude) float32 4MB ...
# Data variables: (12/38)
#     blh         (valid_time, latitude, longitude) float32 3TB ...
#     fdir        (valid_time, latitude, longitude) float32 3TB ...
#     d2m         (valid_time, latitude, longitude) float32 3TB ...
#     fg10        (valid_time, latitude, longitude) float32 3TB ...
#     hcc         (valid_time, latitude, longitude) float32 3TB ...
#     cp          (valid_time, latitude, longitude) float32 3TB ...
#     ...          ...
#     u100        (valid_time, latitude, longitude) float32 3TB ...
#     v100        (valid_time, latitude, longitude) float32 3TB ...
#     zust        (valid_time, latitude, longitude) float32 3TB ...
#     u10         (valid_time, latitude, longitude) float32 3TB ...
#     v10         (valid_time, latitude, longitude) float32 3TB ...
#     tsr         (valid_time, latitude, longitude) float32 3TB ...
# Attributes: (12/46)
#     Conventions:                CF-1.7
#     title:                      ERA5 Hourly Global Reanalysis - chunked for s...
#     summary:                    ERA5 is the fifth generation ECMWF atmospheri...
#     keywords:                   ERA5, reanalysis, atmosphere, climate, ECMWF,...
#     keywords_vocabulary:        GCMD Science Keywords
#     id:                         era5
#     ...                         ...
#     proj:code:                  EPSG:4326
#     proj:epsg:                  4326
#     GRIB_centre:                ecmf
#     GRIB_centreDescription:     European Centre for Medium-Range Weather Fore...
#     GRIB_subCentre:             0
#     history:                    2026-07-07T11:48 GRIB to CDM+CF via cfgrib-0....


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description=__doc__ or "Fetch ERA5 point timeseries."
    )
    parser.add_argument(
        "--row", type=int, default=198, help="latitude row (default: Fort Collins)"
    )
    parser.add_argument(
        "--col", type=int, default=1020, help="longitude col (default: Fort Collins)"
    )
    parser.add_argument("--var", type=str, default="t2m", help="ERA5 variable")
    args = parser.parse_args()

    row, col, var = args.row, args.col, args.var
    series = gen_timeseries(var, row, col)
    table = pa.table({"valid_time": ds.valid_time.values, var: series})

    # GZIP is good enoough based on this experiment
    # compression_types = ["snappy", "gzip", "brotli", "lz4", "zstd", None]
    # for compression in compression_types:
    #     path = f"era_ts/{compression}_{parquet_filename(var, lat, lon)}"
    #     print(path)
    #     pq.write_table(table, path, compression=compression)
    #
    #        ./era_ts
    # 6.8M  ├──  brotli_timeseries_t2m_1020_198.parquet
    # 7.1M  ├──  gzip_timeseries_t2m_1020_198.parquet
    # 9.9M  ├──  lz4_timeseries_t2m_1020_198.parquet
    # 9.9M  ├──  None_timeseries_t2m_1020_198.parquet
    # 9.2M  ├──  snappy_timeseries_t2m_1020_198.parquet
    # 8.4M  └──  zstd_timeseries_t2m_1020_198.parquet
    compression = "gzip"
    path = f"era_ts/{row}/{col}/{var}.parquet"
    os.makedirs(os.path.dirname(path), exist_ok=True)
    pq.write_table(table, path, compression=compression)

    # print(
    #     f"{var}: n={np.isfinite(series).sum()} mean={np.nanmean(series):.4g} "
    #     f"min={np.nanmin(series):.4g} max={np.nanmax(series):.4g}"
    #     f"path={path}"
    # )
    print(path)
