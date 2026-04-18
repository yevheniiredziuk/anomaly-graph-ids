#!/usr/bin/env bash
set -euo pipefail

# Load .env if present so NEO4J_PASSWORD / POSTGRES_* resolve
if [[ -f .env ]]; then
    # shellcheck disable=SC1091
    set -a
    . ./.env
    set +a
fi

# Neo4j password comes from NEO4J_AUTH (format: user/password)
if [[ -n "${NEO4J_AUTH:-}" ]]; then
    NEO4J_PASSWORD="${NEO4J_AUTH#*/}"
fi
NEO4J_PASSWORD="${NEO4J_PASSWORD:-changeme-local-only}"
POSTGRES_USER="${POSTGRES_USER:-ids}"
POSTGRES_DB="${POSTGRES_DB:-ids}"

echo "=== Checking docker compose status ==="
docker compose ps

echo ""
echo "=== Checking Neo4j health ==="
for i in {1..30}; do
    if docker exec agids-neo4j cypher-shell -u neo4j -p "${NEO4J_PASSWORD}" \
        "RETURN 1 AS ok;" > /dev/null 2>&1; then
        echo "✓ Neo4j is responsive"
        break
    fi
    echo "  Waiting for Neo4j... ($i/30)"
    sleep 3
done

echo ""
echo "=== Verifying GDS plugin ==="
docker exec agids-neo4j cypher-shell -u neo4j -p "${NEO4J_PASSWORD}" \
    "CALL gds.version() YIELD gdsVersion RETURN gdsVersion AS version;"

echo ""
echo "=== Verifying APOC plugin ==="
docker exec agids-neo4j cypher-shell -u neo4j -p "${NEO4J_PASSWORD}" \
    "RETURN apoc.version() AS apoc_version;"

echo ""
echo "=== Checking PostgreSQL health ==="
docker exec agids-postgres pg_isready -U "${POSTGRES_USER}" -d "${POSTGRES_DB}"

echo ""
echo "=== Verifying PostgreSQL schema (T04) ==="
docker exec agids-postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -c "
SELECT schemaname, tablename
FROM pg_tables
WHERE schemaname = 'public'
  AND tablename IN ('flows', 'hosts', 'baseline_detections')
   OR tablename LIKE 'flows\\_%'
ORDER BY tablename;"

echo ""
echo "=== Verifying PostgreSQL indexes ==="
docker exec agids-postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -c "
SELECT tablename, indexname
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename IN ('flows', 'hosts', 'baseline_detections')
ORDER BY tablename, indexname;"

echo ""
echo "✓ Infrastructure is up and healthy"
