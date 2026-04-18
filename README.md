# anomaly-graph-ids

Prototype for scientific article: "Graph-based network anomaly detection using Neo4j GDS".

## Status

- T01 done — Maven multi-module skeleton initialized.
- T02 done — local infrastructure (Neo4j + GDS + APOC + PostgreSQL) via Docker Compose.
- T03 done — CICIDS2017 download + Engelen-style cleanup + 60s aggregation.
- T04 done — PostgreSQL partitioned schema + indexes + COPY bulk-load.
- T05 done — Neo4j migration runner + V001/V002 constraints & indexes applied.
- T06 done — Java ETL for PostgreSQL via CopyManager (~275k rows/sec).
- T07 done — Java ETL for Neo4j via UNWIND batches (~19.5k edges/sec).
- T09 done — baseline BC profile (μ, σ per host) from Monday benign traffic.
- T10-T13 done — sliding-window detector with composite α₁+α₂+α₃ scoring.
- T14 done — PL/pgSQL rule-based detectors (port_scan, brute_force, dos_flood) on PostgreSQL baseline.

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

### 5. Apply Neo4j schema migrations

After `docker compose up -d` (and before running any ETL / detection jobs):

```bash
(cd detector && ../mvnw spring-boot:run \
    -Dspring-boot.run.main-class=ua.mitit.ids.detector.migration.MigrationCliApplication)
```

First run: applies V001 (constraints + range indexes) and V002 (baseline-property indexes).
Subsequent runs: no-op (idempotent — state tracked in a `:SchemaMigration` node).

Verify applied schema:

```bash
./scripts/verify-infra.sh
```

### 6. Load flows into PostgreSQL (T06)

Assumes T03 preprocessing produced `data/cleaned/flows_for_postgres.csv` and
T04 schema is already applied.

```bash
(cd etl && ../mvnw spring-boot:run \
    -Dspring-boot.run.main-class=ua.mitit.ids.etl.cli.PostgresEtlCliApplication \
    -Dspring-boot.run.arguments="--csv=../data/cleaned/flows_for_postgres.csv --truncate-first")
```

If the Postgres container uses a non-default host port (our local `.env` maps
it to `15432` to avoid collision with a host Postgres), pass it via env:

```bash
POSTGRES_PORT=15432 (cd etl && ../mvnw spring-boot:run ...)
```

Expected throughput: 200 000–300 000 rows/sec for the COPY stage on typical
laptop hardware (CICIDS2017 Mon+Tue+Wed = 1.67 M rows loads in ~6–10 seconds).

Verify after load:

```bash
docker exec -it agids-postgres psql -U ids -d ids -c \
    "SELECT COUNT(*) AS flows, COUNT(DISTINCT source_ip) AS uniq_src FROM flows;"
```

### 7a. Compute baseline BC profile (T09)

After the Neo4j graph is loaded, run the baseline computation over Monday:

```bash
(cd detector && ../mvnw spring-boot:run \
    -Dspring-boot.run.main-class=ua.mitit.ids.detector.baseline.BaselineCliApplication \
    -Dspring-boot.run.arguments="--start=2017-07-03T00:00:00Z --end=2017-07-04T00:00:00Z")
```

Writes `baseline_bc_mean`, `baseline_bc_std`, `baseline_samples` on each Host. Expected runtime: 5-15 min.

### 7b. Run sliding-window detector (T10-T13)

```bash
(cd detector && ../mvnw spring-boot:run \
    -Dspring-boot.run.main-class=ua.mitit.ids.detector.scoring.DetectorCliApplication \
    -Dspring-boot.run.arguments="--start=2017-07-04T00:00:00Z --end=2017-07-06T00:00:00Z")
```

Creates `:AnomalyEvent` nodes for every window where A(v,t) > θ_A. Each event
has `host_ip`, `t_window_start`, `score`, and the three component values.

Top anomalous hosts:

```cypher
MATCH (e:AnomalyEvent)
RETURN e.host_ip AS ip, COUNT(*) AS events, MAX(e.score) AS max_score
ORDER BY events DESC LIMIT 10;
```

### 7c. Run PL/pgSQL baseline detectors (T14)

Flow-centric rule-based detectors for fair comparison with the graph-based
method (Section 6.4). Installs PL/pgSQL functions (port_scan, brute_force,
dos_flood) and runs them across Tue+Wed in 5-minute sliding windows.

```bash
./scripts/run_baseline_detectors.sh --reset
```

Expected runtime: ~15 s on laptop hardware (partition pruning drops ~1.12 M
benign flows per window into a few tens of ms).

Integration tests against an isolated scratch database:

```bash
./baseline/sql/detectors/test_detectors.sh
```

Inspect:

```bash
docker exec -it agids-postgres psql -U ids -d ids -c "
    SELECT detector_name, COUNT(*) AS n, COUNT(DISTINCT src_ip) AS sources
    FROM baseline_detections GROUP BY 1 ORDER BY n DESC;"
```

### 8. Load aggregated edges into Neo4j (T07 reference)

Assumes T03 preprocessing produced `data/neo4j-import/cicids2017_mon_tue_wed.csv`
and T05 migrations applied.

```bash
(cd etl && ../mvnw spring-boot:run \
    -Dspring-boot.run.main-class=ua.mitit.ids.etl.cli.Neo4jEtlCliApplication \
    -Dspring-boot.run.arguments="--csv=../data/neo4j-import/cicids2017_mon_tue_wed.csv --wipe-first")
```

Expected throughput: ~20 000 edges/sec with UNWIND batch=5000. Full CICIDS2017
aggregated subset (~329k edges) loads in ~17 s.

Verify after load:

```bash
docker exec -it agids-neo4j cypher-shell -u neo4j -p changeme-local-only \
    "MATCH (h:Host) RETURN COUNT(h) AS hosts;
     MATCH ()-[r:CONNECTS_TO]->() RETURN COUNT(r) AS edges;"
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
