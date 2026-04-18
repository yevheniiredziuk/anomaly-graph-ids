#!/usr/bin/env bash
set -euo pipefail

# Installs and runs the PL/pgSQL baseline detectors (T14) on the loaded flows.
#
# Usage:
#   ./scripts/run_baseline_detectors.sh [--reset]
#
# Flags:
#   --reset  — TRUNCATE baseline_detections before the run (default: false)

RESET=false
for arg in "$@"; do
    if [[ "$arg" == "--reset" ]]; then
        RESET=true
    fi
done

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"
SQL_DIR="$REPO_ROOT/baseline/sql/detectors"

if [[ -f "$REPO_ROOT/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    . "$REPO_ROOT/.env"
    set +a
fi

PG_CONTAINER="${PG_CONTAINER:-agids-postgres}"
PG_USER="${POSTGRES_USER:-ids}"
PG_DB="${POSTGRES_DB:-ids}"

echo "=== Installing detector functions ==="
for sql in "$SQL_DIR/01_port_scan.sql" \
           "$SQL_DIR/02_brute_force.sql" \
           "$SQL_DIR/03_dos_flood.sql"; do
    echo "  Installing $(basename "$sql")..."
    docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -q -v ON_ERROR_STOP=1 < "$sql"
done

if [[ "$RESET" == "true" ]]; then
    echo ""
    echo "=== Resetting baseline_detections ==="
    docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 < "$SQL_DIR/00_reset_detections.sql"
fi

echo ""
echo "=== Running detectors across Tue+Wed (5-min windows) ==="
time docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 < "$SQL_DIR/04_run_all.sql"

echo ""
echo "=== Final statistics ==="
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" <<'SQL'
SELECT
    detector_name,
    COUNT(*)                       AS total_detections,
    COUNT(DISTINCT src_ip)         AS unique_sources,
    COUNT(DISTINCT dst_ip)         AS unique_destinations,
    ROUND(AVG(severity_score)::NUMERIC, 3) AS avg_severity
FROM baseline_detections
GROUP BY detector_name
ORDER BY total_detections DESC;

SELECT
    detector_name,
    src_ip,
    COUNT(*) AS detections,
    MAX(severity_score) AS max_severity
FROM baseline_detections
GROUP BY detector_name, src_ip
ORDER BY detector_name, detections DESC
LIMIT 20;
SQL

echo ""
echo "[done] Baseline detectors complete."
