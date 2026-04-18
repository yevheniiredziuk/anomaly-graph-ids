#!/usr/bin/env bash
set -euo pipefail

# Canadian Institute for Cybersecurity — CIC-IDS2017 dataset
# Official page: https://www.unb.ca/cic/datasets/ids-2017.html
#
# We use the "GeneratedLabelledFlows" variant (a.k.a. "TrafficLabelling_"),
# which preserves flow-identity fields required for graph construction:
#   Flow ID, Source IP, Source Port, Destination IP, Destination Port,
#   Protocol, Timestamp  (+ 79 flow features + Label).
#
# NOTE: the other common variant, "MachineLearningCSV.zip" (folder
# "MachineLearningCVE/"), is feature-only — it strips Source/Destination IPs
# and Timestamp, so it cannot be used for graph construction.

DATA_DIR="${DATA_DIR:-./data/raw/cicids2017}"
ZIP_NAME="${ZIP_NAME:-GeneratedLabelledFlows.zip}"
CSV_URL="${CICIDS2017_CSV_URL:-http://cicresearch.ca/CICDataset/CIC-IDS-2017/Dataset/CIC-IDS-2017/CSVs/${ZIP_NAME}}"

mkdir -p "$DATA_DIR"
# Resolve to absolute *after* mkdir so realpath/cd-pwd works whether or not coreutils is present.
DATA_DIR="$(cd "$DATA_DIR" && pwd)"
# Canonical extracted location used by prepare_dataset.py.
# Whatever folder name the archive unpacks into (TrafficLabelling_,
# GeneratedLabelledFlows, …) — we normalize it to data/raw/cicids2017/flows/.
FLOWS_DIR="${DATA_DIR}/flows"

cd "$DATA_DIR"

if [[ -f "$ZIP_NAME" ]]; then
    echo "[skip] $ZIP_NAME already present"
else
    echo "[download] Fetching $ZIP_NAME (~430 MB compressed)..."
    if command -v wget >/dev/null 2>&1; then
        wget --progress=dot:giga --tries=3 -O "$ZIP_NAME" "$CSV_URL" || {
            rm -f "$ZIP_NAME"
            echo "[ERROR] Automated download failed. CIC requires a request form."
            echo "  1) Visit https://www.unb.ca/cic/datasets/ids-2017.html"
            echo "  2) Fill the form and request 'GeneratedLabelledFlows' (CSVs)"
            echo "  3) Place the archive at $DATA_DIR/$ZIP_NAME"
            echo "  4) Re-run this script"
            exit 1
        }
    else
        curl -fL --retry 3 -o "$ZIP_NAME" "$CSV_URL" || {
            rm -f "$ZIP_NAME"
            echo "[ERROR] Automated download failed — fill the form manually (see script header)."
            exit 1
        }
    fi
fi

# Validate archive
if ! unzip -tq "$ZIP_NAME" >/dev/null 2>&1; then
    echo "[ERROR] $ZIP_NAME is not a valid zip archive (might be an HTML error page)."
    echo "        Delete it and re-download manually via the UNB form."
    exit 1
fi

# Extract into a temp location, then normalize to $FLOWS_DIR
TMP_EXTRACT="$(mktemp -d)"
trap 'rm -rf "$TMP_EXTRACT"' EXIT

echo "[extract] Extracting $ZIP_NAME..."
unzip -q -o "$ZIP_NAME" -d "$TMP_EXTRACT"

# Find the directory containing Monday-WorkingHours.pcap_ISCX.csv
# (internal dir may be TrafficLabelling_, GeneratedLabelledFlows, or root).
MONDAY="$(find "$TMP_EXTRACT" -type f -name 'Monday-WorkingHours.pcap_ISCX.csv' -print -quit || true)"
if [[ -z "$MONDAY" ]]; then
    echo "[ERROR] Archive does not contain the expected CSVs."
    echo "        Expected: Monday-WorkingHours.pcap_ISCX.csv (and 7 others)."
    echo "        Contents:"
    find "$TMP_EXTRACT" -maxdepth 2 -type f -name '*.csv'
    exit 1
fi
INNER_DIR="$(dirname "$MONDAY")"

# Verify this is the flows variant (must have Source IP column)
HEADER="$(head -1 "$MONDAY")"
if ! grep -q -i 'source ip' <<<"$HEADER"; then
    echo "[ERROR] This archive looks like the MachineLearningCSV variant — no Source IP column."
    echo "        We need GeneratedLabelledFlows (a.k.a. TrafficLabelling) for graph construction."
    echo "        Actual header starts with: $(echo "$HEADER" | cut -c1-80)..."
    exit 1
fi

# Move CSVs into canonical location
rm -rf "$FLOWS_DIR"
mkdir -p "$FLOWS_DIR"
mv "$INNER_DIR"/*.csv "$FLOWS_DIR/"

echo "[verify] CSV files in $FLOWS_DIR:"
ls -lh "$FLOWS_DIR"

echo ""
echo "[done] Flow-level dataset ready at $FLOWS_DIR"
echo "       Next step: ./scripts/run_prepare.sh"
