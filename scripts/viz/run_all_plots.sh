#!/usr/bin/env bash
set -euo pipefail

# Orchestrates all plot generation.
# Assumes Java-side CSV exports and JMH benchmarks are done.

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"
VENV="$REPO_ROOT/scripts/.venv"

if [[ ! -d "$VENV" ]]; then
    echo "[error] Python venv missing. Create via:"
    echo "  python3 -m venv $VENV && $VENV/bin/pip install -r $REPO_ROOT/scripts/requirements.txt -r $SCRIPT_DIR/requirements.txt"
    exit 1
fi

"$VENV/bin/pip" install -q -r "$SCRIPT_DIR/requirements.txt"

cd "$REPO_ROOT"
mkdir -p results/figures

JMH_LATEST=$(ls -t results/benchmark-*.json 2>/dev/null | head -1 || true)

echo "=== Generating figures ==="

if [[ -f results/roc_data.csv ]]; then
    "$VENV/bin/python" "$SCRIPT_DIR/plot_roc_curves.py"
else
    echo "[skip] results/roc_data.csv missing (run evaluation --mode=export-roc)"
fi

if [[ -f results/window_size_sweep.csv ]]; then
    "$VENV/bin/python" "$SCRIPT_DIR/plot_window_size_sweep.py"
else
    echo "[skip] results/window_size_sweep.csv missing"
fi

if [[ -f results/weight_simplex.csv ]]; then
    "$VENV/bin/python" "$SCRIPT_DIR/plot_weight_simplex_heatmap.py"
else
    echo "[skip] results/weight_simplex.csv missing (run evaluation --mode=export-simplex)"
fi

if [[ -f results/per_attack_recall.csv ]]; then
    "$VENV/bin/python" "$SCRIPT_DIR/plot_per_attack_recall.py"
else
    echo "[skip] results/per_attack_recall.csv missing (run evaluation --mode=export-per-attack)"
fi

if [[ -n "$JMH_LATEST" ]]; then
    "$VENV/bin/python" "$SCRIPT_DIR/plot_latency_comparison.py" --input "$JMH_LATEST"
else
    echo "[skip] No JMH JSON results in results/"
fi

echo ""
echo "=== Summary ==="
ls -la results/figures/ 2>/dev/null || echo "No figures generated"
