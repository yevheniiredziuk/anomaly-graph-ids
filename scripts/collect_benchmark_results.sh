#!/usr/bin/env bash
set -euo pipefail

# Parses JMH JSON output and prints a markdown table for Section 6.4 of the article.
# Requires jq.

RESULT_FILE="${1:?usage: collect_benchmark_results.sh <json file>}"
if [[ ! -f "$RESULT_FILE" ]]; then
    echo "File not found: $RESULT_FILE" >&2
    exit 1
fi
command -v jq >/dev/null || { echo "jq required. Install via 'brew install jq' or 'apt install jq'"; exit 2; }

echo ""
echo "## Table 6.6: Window-query latency (ms)"
echo ""
echo "| Benchmark | p50 | p95 | p99 |"
echo "|---|---:|---:|---:|"

jq -r '
  .[]
  | .benchmark as $name
  | .primaryMetric.scorePercentiles as $p
  | "| " + ($name | capture("(?<short>[^.]+Benchmark\\.[^.]+)$").short // $name)
  + " | " + (($p."50.0" // 0) | tostring | .[0:7])
  + " | " + (($p."95.0" // 0) | tostring | .[0:7])
  + " | " + (($p."99.0" // 0) | tostring | .[0:7])
  + " |"
' "$RESULT_FILE"

echo ""
echo "Source: $RESULT_FILE"
