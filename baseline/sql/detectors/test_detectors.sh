#!/usr/bin/env bash
set -euo pipefail

# Integration test for PL/pgSQL baseline detectors.
#
# Isolates all work in a throwaway database (ids_detector_test) so the main
# `ids` DB is never modified. The test DB is dropped at the end regardless
# of whether the assertions passed.

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$( cd "$SCRIPT_DIR/../../.." && pwd )"

if [[ -f "$REPO_ROOT/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    . "$REPO_ROOT/.env"
    set +a
fi

PG_CONTAINER="${PG_CONTAINER:-agids-postgres}"
PG_USER="${POSTGRES_USER:-ids}"
TEST_DB="ids_detector_test"

psql() { docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -v ON_ERROR_STOP=1 "$@"; }

cleanup() {
    echo ""
    echo "=== Cleanup: dropping $TEST_DB ==="
    psql -d postgres -c "DROP DATABASE IF EXISTS $TEST_DB;" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "=== Setup: creating scratch database $TEST_DB ==="
psql -d postgres -c "DROP DATABASE IF EXISTS $TEST_DB;" >/dev/null 2>&1 || true
psql -d postgres -c "CREATE DATABASE $TEST_DB;"

echo "=== Setup: applying schema + indexes + detector functions ==="
# Schema files (strip CREATE EXTENSION pg_stat_statements — may require superuser preload).
for f in \
    "$REPO_ROOT/baseline/sql/init/01-schema.sql" \
    "$REPO_ROOT/baseline/sql/init/02-indexes.sql" \
    "$REPO_ROOT/baseline/sql/detectors/01_port_scan.sql" \
    "$REPO_ROOT/baseline/sql/detectors/02_brute_force.sql" \
    "$REPO_ROOT/baseline/sql/detectors/03_dos_flood.sql"; do
    grep -vi 'pg_stat_statements' "$f" | psql -d "$TEST_DB" -q
done

FAIL=0

run_assertion() {
    local name="$1"
    local sql="$2"
    local result
    result=$(psql -d "$TEST_DB" -At -c "$sql")
    if [[ "$result" == "PASS" ]]; then
        echo "✓ $name"
    else
        echo "✗ $name — got: $result"
        FAIL=1
    fi
}

echo ""
echo "=== Test 1: Port scan detection ==="
psql -d "$TEST_DB" <<'SQL' >/dev/null
TRUNCATE baseline_detections, flows;
INSERT INTO flows (
    source_ip, source_port, destination_ip, destination_port, protocol,
    t_start, t_end, flow_duration_us, bytes_fwd, bytes_bwd,
    packets_fwd, packets_bwd, syn_count, rst_count, psh_count,
    ack_count, fin_count, urg_count, label
)
SELECT
    '172.16.0.100'::INET,
    40000 + g,
    ('10.0.0.' || (1 + (g % 20)))::INET,
    ((80 + g * 7) % 50000) + 1,
    6::SMALLINT,
    '2017-07-04 12:00:00+00'::TIMESTAMPTZ + (g * INTERVAL '1 second'),
    '2017-07-04 12:00:00+00'::TIMESTAMPTZ + (g * INTERVAL '1 second') + INTERVAL '500 milliseconds',
    500000,
    64, 0, 1, 0, 1, 0, 0, 1, 0, 0,
    'PortScan'
FROM generate_series(1, 60) g;

INSERT INTO flows (
    source_ip, source_port, destination_ip, destination_port, protocol,
    t_start, t_end, flow_duration_us, bytes_fwd, bytes_bwd,
    packets_fwd, packets_bwd, syn_count, rst_count, psh_count,
    ack_count, fin_count, urg_count, label
)
VALUES
    ('10.0.0.50', 54321, '10.0.0.1', 443, 6,
     '2017-07-04 12:00:00+00', '2017-07-04 12:00:05+00', 5000000,
     1024, 2048, 10, 20, 1, 0, 5, 10, 1, 0, 'BENIGN');

SELECT * FROM detect_port_scan('2017-07-04 12:00:00+00'::TIMESTAMPTZ,
                                '2017-07-04 12:05:00+00'::TIMESTAMPTZ, 50);
SQL
run_assertion "port_scan detects 172.16.0.100 exactly once" \
    "SELECT CASE WHEN COUNT(*) = 1 AND MIN(src_ip) = '172.16.0.100'::INET
                 THEN 'PASS' ELSE 'FAIL count=' || COUNT(*) END
     FROM baseline_detections WHERE detector_name = 'port_scan';"

echo ""
echo "=== Test 2: Brute force detection ==="
psql -d "$TEST_DB" <<'SQL' >/dev/null
TRUNCATE baseline_detections, flows;
INSERT INTO flows (
    source_ip, source_port, destination_ip, destination_port, protocol,
    t_start, t_end, flow_duration_us, bytes_fwd, bytes_bwd,
    packets_fwd, packets_bwd, syn_count, rst_count, psh_count,
    ack_count, fin_count, urg_count, label
)
SELECT
    '172.16.0.200'::INET,
    40000 + g,
    '10.0.0.5'::INET,
    22,
    6::SMALLINT,
    '2017-07-04 13:00:00+00'::TIMESTAMPTZ + (g * INTERVAL '1 second'),
    '2017-07-04 13:00:00+00'::TIMESTAMPTZ + (g * INTERVAL '1 second') + INTERVAL '800 milliseconds',
    800000,
    128, 256, 2, 4, 1, 0, 1, 2, 1, 0, 'SSH-Patator'
FROM generate_series(1, 120) g;

SELECT * FROM detect_brute_force('2017-07-04 13:00:00+00'::TIMESTAMPTZ,
                                  '2017-07-04 13:05:00+00'::TIMESTAMPTZ, 100, 0.7);
SQL
run_assertion "brute_force detects 172.16.0.200 exactly once" \
    "SELECT CASE WHEN COUNT(*) = 1 AND MIN(src_ip) = '172.16.0.200'::INET
                 THEN 'PASS' ELSE 'FAIL count=' || COUNT(*) END
     FROM baseline_detections WHERE detector_name = 'brute_force';"

echo ""
echo "=== Test 3: DoS-flood detection ==="
psql -d "$TEST_DB" <<'SQL' >/dev/null
TRUNCATE baseline_detections, flows;
INSERT INTO flows (
    source_ip, source_port, destination_ip, destination_port, protocol,
    t_start, t_end, flow_duration_us, bytes_fwd, bytes_bwd,
    packets_fwd, packets_bwd, syn_count, rst_count, psh_count,
    ack_count, fin_count, urg_count, label
)
SELECT
    '172.16.0.150'::INET,
    30000 + g,
    '10.0.0.100'::INET,
    80,
    6::SMALLINT,
    '2017-07-05 14:00:00+00'::TIMESTAMPTZ + (g * INTERVAL '100 milliseconds'),
    '2017-07-05 14:00:00+00'::TIMESTAMPTZ + (g * INTERVAL '100 milliseconds') + INTERVAL '50 milliseconds',
    50000,
    64, 0, 1, 0, 1, 0, 0, 1, 0, 0, 'DoS Hulk'
FROM generate_series(1, 1500) g;

INSERT INTO flows (
    source_ip, source_port, destination_ip, destination_port, protocol,
    t_start, t_end, flow_duration_us, bytes_fwd, bytes_bwd,
    packets_fwd, packets_bwd, syn_count, rst_count, psh_count,
    ack_count, fin_count, urg_count, label
)
SELECT
    ('10.0.0.' || (1 + (g % 50)))::INET,
    50000 + g,
    '10.0.0.100'::INET,
    80,
    6::SMALLINT,
    '2017-07-05 14:00:00+00'::TIMESTAMPTZ + (g * INTERVAL '1 second'),
    '2017-07-05 14:00:00+00'::TIMESTAMPTZ + (g * INTERVAL '1 second') + INTERVAL '2 seconds',
    2000000,
    1024, 2048, 10, 20, 1, 0, 5, 10, 1, 0, 'BENIGN'
FROM generate_series(1, 50) g;

SELECT * FROM detect_dos_flood('2017-07-05 14:00:00+00'::TIMESTAMPTZ,
                                '2017-07-05 14:05:00+00'::TIMESTAMPTZ, 1000, 0.7);
SQL
run_assertion "dos_flood detects 172.16.0.150 exactly once" \
    "SELECT CASE WHEN COUNT(*) = 1 AND MIN(src_ip) = '172.16.0.150'::INET
                 THEN 'PASS' ELSE 'FAIL count=' || COUNT(*) END
     FROM baseline_detections WHERE detector_name = 'dos_flood';"

echo ""
if [[ $FAIL -eq 0 ]]; then
    echo "✓ All tests passed"
else
    echo "✗ Some tests failed"
    exit 1
fi
