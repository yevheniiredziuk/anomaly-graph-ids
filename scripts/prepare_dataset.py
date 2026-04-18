#!/usr/bin/env python3
"""
CICIDS2017 preprocessing script.

Based on cleaning rules from:
  Engelen, G., Rimmer, V., Joosen, W. (2021).
  Troubleshooting an Intrusion Detection Dataset: the CICIDS2017 Case Study.
  IEEE Security and Privacy Workshops (SPW).

Pipeline:
  1. Load raw CSVs from data/raw/cicids2017/flows/
     (flow-identity variant = GeneratedLabelledFlows / TrafficLabelling_;
      feature-only "MachineLearningCVE" variant cannot be used — no Source IP / Timestamp)
  2. Normalize column names (strip whitespace — CICFlowMeter artefact)
  3. Remove duplicates (exact full-row match)
  4. Remove rows with NaN or Infinity in numeric columns
  5. Filter to Tuesday + Wednesday subset (Monday used as benign baseline)
  6. Aggregate flows into edges: (src_ip, dst_ip, protocol, time_bucket_60s)
  7. Save per-step outputs + final Neo4j-ready CSV

Usage:
  ./scripts/run_prepare.sh [--raw-dir PATH] [--out-dir PATH] [--bucket-seconds N]
"""
from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import numpy as np
import pandas as pd

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------

LOG = logging.getLogger("prepare_dataset")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)

# Files that make up our experimental subset (Tue + Wed + benign baseline Mon)
SUBSET_FILES = {
    "monday":    "Monday-WorkingHours.pcap_ISCX.csv",
    "tuesday":   "Tuesday-WorkingHours.pcap_ISCX.csv",
    "wednesday": "Wednesday-workingHours.pcap_ISCX.csv",
}

# Columns we actually need for graph construction + evaluation.
# Full CICIDS2017 CSV has 79 columns; most are flow statistics we don't use at ETL stage.
KEEP_COLUMNS = [
    "Flow ID",
    "Source IP",
    "Source Port",
    "Destination IP",
    "Destination Port",
    "Protocol",
    "Timestamp",
    "Flow Duration",
    "Total Fwd Packets",
    "Total Backward Packets",
    "Total Length of Fwd Packets",
    "Total Length of Bwd Packets",
    "Fwd PSH Flags",
    "Bwd PSH Flags",
    "Fwd URG Flags",
    "Bwd URG Flags",
    "SYN Flag Count",
    "RST Flag Count",
    "PSH Flag Count",
    "ACK Flag Count",
    "URG Flag Count",
    "FIN Flag Count",
    "Label",
]

# -----------------------------------------------------------------------------
# Cleaning helpers
# -----------------------------------------------------------------------------

def normalize_columns(df: pd.DataFrame) -> pd.DataFrame:
    """
    CICFlowMeter emits column names with leading spaces (known artefact).
    Strip all whitespace and normalize.
    """
    df.columns = [c.strip() for c in df.columns]
    return df


def drop_duplicates_report(df: pd.DataFrame, context: str) -> pd.DataFrame:
    before = len(df)
    df = df.drop_duplicates()
    after = len(df)
    removed = before - after
    if removed > 0:
        LOG.info("  [%s] Removed %d duplicates (%.2f%%)", context, removed, 100 * removed / before)
    return df


def drop_invalid_numeric(df: pd.DataFrame, numeric_cols: list) -> pd.DataFrame:
    """
    Remove rows with NaN/Infinity in numeric columns.
    Per Engelen et al. (2021): certain flows get garbage stats from CICFlowMeter edge cases.
    """
    before = len(df)
    mask = np.isfinite(df[numeric_cols].astype(float).to_numpy()).all(axis=1)
    df = df[mask].copy()
    after = len(df)
    removed = before - after
    if removed > 0:
        LOG.info("  Removed %d rows with NaN/Inf in numeric cols (%.2f%%)", removed, 100 * removed / before)
    return df


def parse_timestamp(df: pd.DataFrame) -> pd.DataFrame:
    """
    CICIDS2017 timestamp format is 'd/m/yyyy H:MM:SS AM/PM' (locale-dependent,
    inconsistent between files). Use pandas' flexible parsing with dayfirst=True.
    """
    df["Timestamp"] = pd.to_datetime(df["Timestamp"], dayfirst=True, errors="coerce")
    bad = df["Timestamp"].isna().sum()
    if bad > 0:
        LOG.warning("  Dropping %d rows with unparseable Timestamp", bad)
        df = df.dropna(subset=["Timestamp"]).copy()
    return df


# -----------------------------------------------------------------------------
# Loading & cleaning
# -----------------------------------------------------------------------------

def load_and_clean_day(csv_path: Path) -> pd.DataFrame:
    LOG.info("Loading %s", csv_path.name)
    # low_memory=False: CICIDS2017 has mixed dtypes in columns
    df = pd.read_csv(csv_path, low_memory=False, encoding="latin-1")
    LOG.info("  Raw rows: %d", len(df))

    df = normalize_columns(df)

    missing = [c for c in KEEP_COLUMNS if c not in df.columns]
    if missing:
        LOG.warning("  Missing columns in %s: %s", csv_path.name, missing)
    df = df[[c for c in KEEP_COLUMNS if c in df.columns]].copy()

    df = drop_duplicates_report(df, context=csv_path.stem)

    numeric_cols = [c for c in df.columns if c not in (
        "Flow ID", "Source IP", "Destination IP", "Timestamp", "Label"
    )]
    df = drop_invalid_numeric(df, numeric_cols)

    df = parse_timestamp(df)

    LOG.info("  Clean rows: %d", len(df))
    return df


# -----------------------------------------------------------------------------
# Aggregation (Section 4.2 of the article)
# -----------------------------------------------------------------------------

def aggregate_flows_to_edges(df: pd.DataFrame, bucket_seconds: int) -> pd.DataFrame:
    """
    Aggregate flows into "super-edges" per the formula in Section 4.2:
      key = (src_ip, dst_ip, protocol, floor(t_start / Δt_a))

    This reduces graph size by 5–10x without losing structural information
    needed for anomaly detection.
    """
    LOG.info("Aggregating flows into edges (bucket = %d sec)...", bucket_seconds)

    df["time_bucket"] = (df["Timestamp"].astype("int64") // 10**9 // bucket_seconds).astype("int64")

    # Resolve label ambiguity within a bucket: if any flow in bucket is attack,
    # whole bucket is labelled with that attack. "BENIGN" wins only if all flows benign.
    def resolve_label(labels: pd.Series) -> str:
        non_benign = labels[labels != "BENIGN"]
        if len(non_benign) == 0:
            return "BENIGN"
        return non_benign.mode().iloc[0]

    agg = df.groupby(
        ["Source IP", "Destination IP", "Protocol", "time_bucket"],
        as_index=False,
    ).agg(
        t_start=("Timestamp", "min"),
        t_end=("Timestamp", "max"),
        src_port_first=("Source Port", "first"),
        dst_port_first=("Destination Port", "first"),
        bytes_fwd=("Total Length of Fwd Packets", "sum"),
        bytes_bwd=("Total Length of Bwd Packets", "sum"),
        packets_fwd=("Total Fwd Packets", "sum"),
        packets_bwd=("Total Backward Packets", "sum"),
        syn_count=("SYN Flag Count", "sum"),
        rst_count=("RST Flag Count", "sum"),
        psh_count=("PSH Flag Count", "sum"),
        ack_count=("ACK Flag Count", "sum"),
        fin_count=("FIN Flag Count", "sum"),
        flow_count=("Flow ID", "count"),
        Label=("Label", resolve_label),
    )

    LOG.info("  Aggregated: %d edges (reduction %.1fx)", len(agg), len(df) / max(1, len(agg)))

    return agg


# -----------------------------------------------------------------------------
# Main pipeline
# -----------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--raw-dir", type=Path, default=Path("data/raw/cicids2017/flows"))
    parser.add_argument("--out-dir", type=Path, default=Path("data/cleaned"))
    parser.add_argument("--neo4j-import-dir", type=Path, default=Path("data/neo4j-import"))
    parser.add_argument("--bucket-seconds", type=int, default=60)
    parser.add_argument("--limit-rows", type=int, default=None,
                        help="Debug: limit each day to N rows")
    args = parser.parse_args()

    if not args.raw_dir.exists():
        LOG.error("Raw dir not found: %s", args.raw_dir)
        LOG.error("Run scripts/download_cicids2017.sh first.")
        return 1

    args.out_dir.mkdir(parents=True, exist_ok=True)
    args.neo4j_import_dir.mkdir(parents=True, exist_ok=True)

    # --- Load days ---
    dfs = {}
    for day, fname in SUBSET_FILES.items():
        path = args.raw_dir / fname
        if not path.exists():
            LOG.error("Missing file: %s", path)
            return 1
        df = load_and_clean_day(path)
        if args.limit_rows:
            df = df.head(args.limit_rows)
            LOG.info("  [debug] Limited to %d rows", args.limit_rows)
        df["day"] = day
        dfs[day] = df

        out_path = args.out_dir / f"{day}.parquet"
        df.to_parquet(out_path, index=False)
        LOG.info("  Saved %s", out_path)

    combined = pd.concat(dfs.values(), ignore_index=True)
    LOG.info("Combined total: %d flows", len(combined))

    edges = aggregate_flows_to_edges(combined, args.bucket_seconds)

    LOG.info("\n" + "=" * 60)
    LOG.info("LABEL DISTRIBUTION (aggregated edges, Mon+Tue+Wed):")
    LOG.info("=" * 60)
    label_counts = edges["Label"].value_counts()
    for lbl, cnt in label_counts.items():
        LOG.info("  %-40s %d (%.2f%%)", lbl, cnt, 100 * cnt / len(edges))
    LOG.info("")

    neo4j_csv = args.neo4j_import_dir / "cicids2017_mon_tue_wed.csv"
    edges_export = edges.copy()
    edges_export["t_start"] = edges_export["t_start"].dt.strftime("%Y-%m-%dT%H:%M:%S")
    edges_export["t_end"] = edges_export["t_end"].dt.strftime("%Y-%m-%dT%H:%M:%S")
    edges_export.to_csv(neo4j_csv, index=False)
    LOG.info("Saved Neo4j import CSV: %s (%d edges)", neo4j_csv, len(edges_export))

    parquet_out = args.out_dir / "edges_aggregated.parquet"
    edges.to_parquet(parquet_out, index=False)
    LOG.info("Saved aggregated edges: %s", parquet_out)

    # --- Save raw cleaned flows for PostgreSQL (non-aggregated) ---
    # Flow-centric baseline needs individual flows, not super-edges.
    postgres_csv = args.out_dir / "flows_for_postgres.csv"
    LOG.info("Saving non-aggregated flows for PostgreSQL baseline...")

    # CICIDS2017 timestamps are naive; treat as UTC so day-boundary partitions
    # (flows_mon/tue/wed) align with the t_start date.
    ts_utc = combined["Timestamp"].dt.tz_localize("UTC")
    duration_us = combined["Flow Duration"].clip(lower=0).astype("int64")
    t_end_utc = ts_utc + pd.to_timedelta(duration_us, unit="us")

    iso_fmt = "%Y-%m-%dT%H:%M:%S%z"
    t_start_str = ts_utc.dt.strftime(iso_fmt).str.replace(
        r"([+-]\d{2})(\d{2})$", r"\1:\2", regex=True
    )
    t_end_str = t_end_utc.dt.strftime(iso_fmt).str.replace(
        r"([+-]\d{2})(\d{2})$", r"\1:\2", regex=True
    )

    pg_df_out = pd.DataFrame({
        "source_ip":        combined["Source IP"],
        "source_port":      combined["Source Port"].astype("int32"),
        "destination_ip":   combined["Destination IP"],
        "destination_port": combined["Destination Port"].astype("int32"),
        "protocol":         combined["Protocol"].astype("int16"),
        "t_start":          t_start_str,
        "t_end":            t_end_str,
        "flow_duration_us": duration_us,
        "bytes_fwd":        combined["Total Length of Fwd Packets"].astype("int64"),
        "bytes_bwd":        combined["Total Length of Bwd Packets"].astype("int64"),
        "packets_fwd":      combined["Total Fwd Packets"].astype("int64"),
        "packets_bwd":      combined["Total Backward Packets"].astype("int64"),
        "syn_count":        combined["SYN Flag Count"].astype("int32"),
        "rst_count":        combined["RST Flag Count"].astype("int32"),
        "psh_count":        combined["PSH Flag Count"].astype("int32"),
        "ack_count":        combined["ACK Flag Count"].astype("int32"),
        "fin_count":        combined["FIN Flag Count"].astype("int32"),
        "urg_count":        combined["URG Flag Count"].astype("int32"),
        "label":            combined["Label"],
    })
    pg_df_out.to_csv(postgres_csv, index=False)
    LOG.info("Saved PostgreSQL CSV: %s (%d flows)", postgres_csv, len(pg_df_out))

    LOG.info("\n[done] Dataset preparation complete.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
