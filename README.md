# anomaly-graph-ids

Prototype for scientific article: "Graph-based network anomaly detection using Neo4j GDS".

## Status

- T01 done — Maven multi-module skeleton initialized.
- T02 done — local infrastructure (Neo4j + GDS + APOC + PostgreSQL) via Docker Compose.

## Prerequisites

- JDK 21
- Docker Desktop
- Maven 3.9+ (or use the bundled `./mvnw`)

## Quick start

### 1. Configure environment

```bash
cp .env.example .env
# Edit .env if you need custom ports or memory settings
```

### 2. Start infrastructure

```bash
docker compose up -d
```

Wait ~60 seconds for Neo4j to fully initialize (it downloads GDS plugin on first start).
Check status:

```bash
docker compose ps
docker compose logs neo4j -f
```

Verify GDS is loaded:

```bash
docker exec -it agids-neo4j cypher-shell -u neo4j -p changeme-local-only \
    "CALL gds.version() YIELD gdsVersion RETURN gdsVersion;"
```

Expected output: a version string like `2.13.x` (or `2.xx.x` for newer Neo4j releases).

Or run the full health check:

```bash
./scripts/verify-infra.sh
```

### 3. Build the project

```bash
./mvnw clean install
```

### 4. Run ETL (later — see T06)

```bash
./mvnw -pl etl spring-boot:run
```

### 5. Stop infrastructure

```bash
docker compose down           # stop, keep data
docker compose down -v        # stop and WIPE all data (use with care)
```

## Modules

- `common` — shared domain & utilities
- `etl` — CSV → Neo4j + PostgreSQL loader
- `detector` — graph-based anomaly detection
- `baseline` — PostgreSQL rule-based baseline
- `benchmark` — JMH performance tests

## Author

Yevhenii Redziuk, MITIT

## License

TBD
