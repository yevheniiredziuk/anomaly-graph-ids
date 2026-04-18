#!/usr/bin/env bash
set -euo pipefail

# Runs the JMH benchmark suite.
#   default: full (2-4 hours)
#   --quick: 2 warmup × 3 s, 5 meas × 3 s (~5-10 min total)

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"
JAR="$REPO_ROOT/benchmark/target/benchmarks.jar"

# Resolve .env overrides (POSTGRES_PORT etc).
if [[ -f "$REPO_ROOT/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    . "$REPO_ROOT/.env"
    set +a
fi

QUICK_FLAGS=()
if [[ "${1:-}" == "--quick" ]]; then
    QUICK_FLAGS=(-wi 2 -i 5 -w 3s -r 3s -to 120s)
    echo "[info] Quick mode: 2 warmup × 3 s, 5 meas × 3 s"
fi

if [[ ! -f "$JAR" ]]; then
    echo "[build] benchmarks.jar not found, building..."
    (cd "$REPO_ROOT" && ./mvnw -pl benchmark -am clean package -DskipTests)
fi

mkdir -p "$REPO_ROOT/results"
STAMP=$(date +%Y%m%d-%H%M%S)
RESULT_FILE="$REPO_ROOT/results/benchmark-$STAMP.json"

echo ""
echo "=== Sanity check: infrastructure has data ==="
docker exec agids-neo4j cypher-shell -u neo4j -p "${NEO4J_PASSWORD:-changeme-local-only}" \
    "MATCH (h:Host) RETURN count(h) AS n" 2>/dev/null | grep -qE '[0-9]+' \
    || { echo "[error] Neo4j empty — run ETL first"; exit 1; }

docker exec agids-postgres psql -U "${POSTGRES_USER:-ids}" -d "${POSTGRES_DB:-ids}" \
    -c "SELECT count(*) FROM flows" 2>/dev/null | grep -qE '[0-9]+' \
    || { echo "[error] Postgres empty — run ETL first"; exit 1; }

# Expose connection env to the benchmark JVM.
export NEO4J_URI="${NEO4J_URI:-bolt://localhost:${NEO4J_BOLT_PORT:-7687}}"
export NEO4J_USER="${NEO4J_USER:-neo4j}"
export NEO4J_PASSWORD="${NEO4J_PASSWORD:-changeme-local-only}"
export POSTGRES_URL="${POSTGRES_URL:-jdbc:postgresql://${POSTGRES_HOST:-localhost}:${POSTGRES_PORT:-5432}/${POSTGRES_DB:-ids}}"
export POSTGRES_USER="${POSTGRES_USER:-ids}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-changeme-local-only}"

echo ""
echo "=== Running benchmarks (output: $RESULT_FILE) ==="
java -jar "$JAR" "${QUICK_FLAGS[@]}" -rf json -rff "$RESULT_FILE" -foe true

echo ""
echo "[done] Results: $RESULT_FILE"
"$SCRIPT_DIR/collect_benchmark_results.sh" "$RESULT_FILE"
