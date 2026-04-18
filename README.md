# anomaly-graph-ids

Prototype for scientific article: "Graph-based network anomaly detection using Neo4j GDS".

## Status

- T01 done — Maven multi-module skeleton initialized.

## Prerequisites

- JDK 21
- Docker Desktop
- Maven 3.9+ (or use the bundled `./mvnw`)

## Quick start

```bash
# 1. Start infrastructure (later — see T02)
docker compose up -d

# 2. Build
./mvnw clean install

# 3. Download dataset (see scripts/download_cicids2017.sh — T03)
# 4. Run ETL
./mvnw -pl etl spring-boot:run
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
