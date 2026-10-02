//! Read a single ERA5 grid-cell timeseries from the public Earthmover
//! icechunk repository and write it to parquet.
//!
//!   icechunk (Rust)        - opens the S3 repo + readonly session
//!   zarrs_icechunk         - exposes the session as a zarrs async store
//!   zarrs (pcodec codec)   - decodes the `numcodecs.pcodec` zarr v3 chunks
//!   arrow / parquet        - writes the `valid_time` + `<var>` columns
//!
//! The ERA5 grid is assumed to be the 0.25-deg global grid:
//!   latitude:  721 points,  90.0 .. -90.0 (row 0 = north pole)
//!   longitude: 1440 points,  0.0 .. 359.75 (col 0 = prime meridian)

use std::sync::Arc;

use anyhow::{Context as _, Result};
use arrow::array::{Float32Array, TimestampNanosecondArray};
use arrow::datatypes::{DataType, Field, Schema, TimeUnit};
use arrow::record_batch::RecordBatch;
use chrono::{TimeZone, Utc};
use icechunk::{
    new_s3_storage,
    repository::VersionInfo,
    storage::{S3Credentials, S3Options},
    Repository,
};
use parquet::arrow::ArrowWriter;
use parquet::basic::Compression;
use parquet::file::properties::WriterProperties;
use zarrs::array::{Array, ArraySubset};
use zarrs_icechunk::AsyncIcechunkStore;

const BUCKET: &str = "earthmover-icechunk-era5";
const PREFIX: &str = "icechunkV2";
const REGION: &str = "us-east-1";
const BRANCH: &str = "main";

/// Nanoseconds between hourly ERA5 timesteps.
const HOUR_NS: i64 = 3_600_000_000_000;

fn parse_args() -> (usize, usize, String) {
    let args: Vec<String> = std::env::args().collect();
    let row = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(198); // Fort Collins
    let col = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(1020); // Fort Collins
    let var = args.get(3).cloned().unwrap_or_else(|| "t2m".to_string());
    (row, col, var)
}

#[tokio::main]
async fn main() -> Result<()> {
    let (row, col, var) = parse_args();

    // 1. Open the public icechunk repository on S3 (anonymous read).
    let storage = new_s3_storage(
        S3Options::default()
            .with_region(REGION)
            .with_anonymous(true),
        BUCKET.to_string(),
        Some(PREFIX.to_string()),
        Some(S3Credentials::Anonymous),
        Vec::new(),
        Vec::new(),
        None,
    )
    .context("opening S3 storage")?;

    let repo = Repository::open(None, storage, Default::default())
        .await
        .context("opening icechunk repository")?;

    // 2. A readonly session on `main` (the default branch).
    let session = repo
        .readonly_session(&VersionInfo::BranchTipRef(BRANCH.to_string()))
        .await
        .context("opening readonly session")?;

    // 3. Expose the session as a zarrs store and open the variable array.
    let store = Arc::new(AsyncIcechunkStore::new(session));
    let array_path = format!("/single/temporal/{var}");
    let array = Array::async_open(store.clone(), &array_path)
        .await
        .with_context(|| format!("opening zarr array {array_path}"))?;

    let shape = array.shape().to_vec();
    // eprintln!("reading {var} at (row {row}, col {col}) from {BUCKET}/{PREFIX} :: shape {shape:?}");

    // 4. Decode just this grid cell across the full time dimension.
    //    zarrs handles the chunk lookups and pcodec decoding.
    let n_time = shape[0];
    let subset = ArraySubset::new_with_ranges(&[
        0..n_time,
        row as u64..row as u64 + 1,
        col as u64..col as u64 + 1,
    ]);
    let data: ndarray::ArrayD<f32> = array
        .async_retrieve_array_subset(&subset)
        .await
        .context("reading array subset")?;
    let series: Vec<f32> = data.iter().copied().collect();

    // 5. Reconstruct the hourly UTC timestamps (hours since 1940-01-01T00:00Z).
    let epoch_ns = Utc
        .with_ymd_and_hms(1940, 1, 1, 0, 0, 0)
        .single()
        .context("building epoch")?
        .timestamp_nanos_opt()
        .context("epoch out of nanosecond range")?;
    let timestamps: Vec<i64> = (0..series.len() as i64)
        .map(|i| epoch_ns + i * HOUR_NS)
        .collect();

    // 6. Write the parquet file (gzip, matching the Python script).
    let schema = Arc::new(Schema::new(vec![
        Field::new(
            "valid_time",
            DataType::Timestamp(TimeUnit::Nanosecond, None),
            true,
        ),
        Field::new(&var, DataType::Float32, true),
    ]));
    let batch = RecordBatch::try_new(
        schema.clone(),
        vec![
            Arc::new(TimestampNanosecondArray::from(timestamps)),
            Arc::new(Float32Array::from(series)),
        ],
    )
    .context("building record batch")?;

    let path = std::path::PathBuf::from("era_ts")
        .join(row.to_string())
        .join(col.to_string())
        .join(format!("{var}.parquet"));
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).context("creating output dir")?;
    }
    let file = std::fs::File::create(&path).context("creating parquet file")?;
    let props = WriterProperties::builder()
        .set_compression(Compression::GZIP(Default::default()))
        .build();
    let mut writer =
        ArrowWriter::try_new(file, schema, Some(props)).context("building parquet writer")?;
    writer.write(&batch).context("writing parquet")?;
    writer.close().context("closing parquet writer")?;

    println!("{}", path.display());
    Ok(())
}
