# era5-timeseries (Rust)

Pure-Rust counterpart to `src/py/era5-timeseries.py`: reads a single ERA5
grid-cell timeseries from the public [Earthmover icechunk] repository on S3 and
writes it to parquet, with no Python or xarray in the middle.

## How it works

```text
icechunk (Rust)      opens the S3 repo anonymously + a readonly session on `main`
zarrs_icechunk       exposes the icechunk session as a zarrs async store
zarrs (pcodec)       decodes the zarr v3 `numcodecs.pcodec` chunks
arrow / parquet      writes the `valid_time` + `<var>` columns (gzip)
```

The ERA5 store (`single/temporal`) is zarr v3; variables are float32 with
chunk shape `(8736, 12, 12)` and are compressed with `numcodecs.pcodec`
(level 8), which `zarrs` decodes via the `pcodec` feature (the `pco` crate).

## Usage

```bash
cargo run --release -- <row> <col> [var]
# e.g. Fort Collins (default 198 1020 t2m):
cargo run --release
```

Writes `era_ts/<row>/<col>/<var>.parquet` (same layout and gzip compression as
the Python script).

The output is verified to be value-identical to the Python pipeline: for
`t2m` at (198, 1020) both produce 756,048 rows of `valid_time` timestamp[ns]
and `t2m` float32, with equal values.

[Earthmover icechunk]: https://github.com/earth-mover/icechunk
