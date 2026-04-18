#!/usr/bin/env bash
set -euo pipefail

# Bulk-load flows from prepared CSV into PostgreSQL.
# Uses \COPY for maximum throughput (10-100x faster than INSERT batches).
#
# Prerequisites:
#   * Docker Compose up (agids-postgres container running + schema applied)
#   * T03 preprocessing done, flows_for_postgres.csv exists

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$( cd "$SCRIPT_DIR/../../.." && pwd )"

CSV_PATH="${CSV_PATH:-$REPO_ROOT/data/cleaned/flows_for_postgres.csv}"
PG_CONTAINER="${PG_CONTAINER:-agids-postgres}"

# Resolve credentials from .env if present
if [[ -f "$REPO_ROOT/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    . "$REPO_ROOT/.env"
    set +a
fi
PG_USER="${POSTGRES_USER:-ids}"
PG_DB="${POSTGRES_DB:-ids}"

if [[ ! -f "$CSV_PATH" ]]; then
    echo "[ERROR] CSV not found: $CSV_PATH"
    echo "Run preprocessing first: ./scripts/run_prepare.sh"
    exit 1
fi

if ! docker exec "$PG_CONTAINER" pg_isready -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1; then
    echo "[ERROR] PostgreSQL container '$PG_CONTAINER' not ready."
    echo "Run: docker compose up -d"
    exit 1
fi

echo "[info] Loading $CSV_PATH into $PG_DB.flows"
echo "[info] CSV size: $(wc -l < "$CSV_PATH" | awk '{print $1-1}') data rows"

# Step 1 — drop indexes for faster COPY
echo "[step 1/4] Dropping indexes..."
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 <<'SQL'
DROP INDEX IF EXISTS idx_flows_src_time;
DROP INDEX IF EXISTS idx_flows_dst_time;
DROP INDEX IF EXISTS idx_flows_pair_time;
DROP INDEX IF EXISTS idx_flows_label;
-- Truncate children in case of re-run (preserves partitions).
TRUNCATE flows, hosts, baseline_detections;
SQL

# Step 2 — COPY. Use session TZ=UTC so any tz-less timestamps in CSV
# land in partitions as expected.
echo "[step 2/4] Running \\COPY..."
time docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 \
    -c "SET TIME ZONE 'UTC';" \
    -c "\\COPY flows(
            source_ip, source_port, destination_ip, destination_port, protocol,
            t_start, t_end, flow_duration_us,
            bytes_fwd, bytes_bwd, packets_fwd, packets_bwd,
            syn_count, rst_count, psh_count, ack_count, fin_count, urg_count,
            label
        ) FROM STDIN WITH (FORMAT csv, HEADER true)" < "$CSV_PATH"

# Step 3 — recreate indexes
echo "[step 3/4] Recreating indexes..."
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 \
    < "$REPO_ROOT/baseline/sql/init/02-indexes.sql"

# Step 4 — populate hosts table
echo "[step 4/4] Populating hosts..."
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO hosts (ip, first_seen, last_seen, total_flows)
SELECT ip, MIN(first_seen), MAX(last_seen), SUM(flow_count)
FROM (
    SELECT source_ip      AS ip, t_start AS first_seen, t_end AS last_seen, 1 AS flow_count FROM flows
    UNION ALL
    SELECT destination_ip AS ip, t_start AS first_seen, t_end AS last_seen, 1 AS flow_count FROM flows
) host_rows
GROUP BY ip
ON CONFLICT (ip) DO UPDATE
    SET first_seen  = LEAST(hosts.first_seen, EXCLUDED.first_seen),
        last_seen   = GREATEST(hosts.last_seen, EXCLUDED.last_seen),
        total_flows = hosts.total_flows + EXCLUDED.total_flows;

ANALYZE flows;
ANALYZE hosts;
SQL

echo ""
echo "[verify] Flow counts by partition:"
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" <<'SQL'
SELECT 'flows_mon'     AS partition, COUNT(*) AS rows FROM flows_mon
UNION ALL SELECT 'flows_tue',     COUNT(*) FROM flows_tue
UNION ALL SELECT 'flows_wed',     COUNT(*) FROM flows_wed
UNION ALL SELECT 'flows_default', COUNT(*) FROM flows_default
ORDER BY 1;

SELECT 'hosts' AS table, COUNT(*) AS rows FROM hosts;

SELECT label, COUNT(*) AS n
FROM flows
GROUP BY label
ORDER BY n DESC
LIMIT 20;
SQL

echo ""
echo "[done] Bulk load complete."
