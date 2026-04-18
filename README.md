# anomaly-graph-ids

Prototype for scientific article: "Graph-based network anomaly detection using Neo4j GDS".

## Status

- T01 done — Maven multi-module skeleton initialized.
- T02 done — local infrastructure (Neo4j + GDS + APOC + PostgreSQL) via Docker Compose.
- T03 done — CICIDS2017 download + Engelen-style cleanup + 60s aggregation.

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

### 4. Dataset setup

Download raw CICIDS2017 (we use the **`GeneratedLabelledFlows.zip`** variant —
~430 MB compressed — because it preserves Source/Destination IP and Timestamp
fields required for graph construction; the `MachineLearningCSV.zip` variant
does **not** and cannot be used here):

```bash
./scripts/download_cicids2017.sh
```

**Direct CIC download is currently gated behind a UNB request form** — the script
will fail with an HTML response instead of a zip. In that case, follow the
manual-download instructions in [`docs/dataset.md`](docs/dataset.md#manual-download--required):
fill the form at https://www.unb.ca/cic/datasets/ids-2017.html, **request
`GeneratedLabelledFlows.zip` specifically**, save it as
`data/raw/cicids2017/GeneratedLabelledFlows.zip`, then re-run the script
(it will validate, extract, and normalize the path to `data/raw/cicids2017/flows/`).

Run preprocessing (creates cleaned + aggregated dataset):

```bash
./scripts/run_prepare.sh
```

First run creates Python venv in `scripts/.venv/` (~150 MB). The full pipeline
takes 3–10 minutes on a modern laptop.

Verify results:

```bash
ls -lh data/neo4j-import/
# Should contain cicids2017_mon_tue_wed.csv (~50–150 MB)
```

Quick sanity check (sample, under 30 s):

```bash
./scripts/run_prepare.sh --limit-rows 1000
```

### 5. Run ETL (later — see T06)

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
