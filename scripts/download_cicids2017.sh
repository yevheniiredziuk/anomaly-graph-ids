#!/usr/bin/env bash
set -euo pipefail

# Canadian Institute for Cybersecurity — CIC-IDS2017 dataset
# Original: https://www.unb.ca/cic/datasets/ids-2017.html
# We use MachineLearningCSV variant (pre-processed flows in CSV format)

DATA_DIR="${DATA_DIR:-./data/raw/cicids2017}"
CSV_URL="${CICIDS2017_CSV_URL:-http://cicresearch.ca/CICDataset/CIC-IDS-2017/Dataset/CIC-IDS-2017/CSVs/MachineLearningCSV.zip}"

mkdir -p "$DATA_DIR"
cd "$DATA_DIR"

if [[ -f "MachineLearningCSV.zip" ]]; then
    echo "[skip] MachineLearningCSV.zip already present"
else
    echo "[download] Fetching MachineLearningCSV.zip (~240 MB compressed, ~2 GB extracted)..."
    if command -v wget >/dev/null 2>&1; then
        wget --progress=dot:giga --tries=3 -O MachineLearningCSV.zip "$CSV_URL" || {
            rm -f MachineLearningCSV.zip
            echo "[ERROR] Download failed. CIC sometimes restricts access; alternatives:"
            echo "  1) Manual download from https://www.unb.ca/cic/datasets/ids-2017.html"
            echo "     (requires filling a form; place file at $DATA_DIR/MachineLearningCSV.zip)"
            echo "  2) Mirror: https://ieee-dataport.org/documents/cicids2017 (needs IEEE account)"
            echo "  3) Kaggle: https://www.kaggle.com/datasets/cicdataset/cicids2017"
            exit 1
        }
    else
        curl -fL --retry 3 -o MachineLearningCSV.zip "$CSV_URL" || {
            rm -f MachineLearningCSV.zip
            echo "[ERROR] Download failed (see script for alternatives)"
            exit 1
        }
    fi
fi

if [[ ! -d "MachineLearningCVE" ]]; then
    echo "[extract] Extracting..."
    unzip -q MachineLearningCSV.zip
fi

echo "[verify] Expected files:"
ls -la MachineLearningCVE/ 2>/dev/null || ls -la . | grep -i "\.csv$"

echo ""
echo "[done] Dataset available at $DATA_DIR/MachineLearningCVE/"
echo "       Total 8 CSV files, one per day-fragment"
