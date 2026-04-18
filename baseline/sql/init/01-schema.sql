-- ==================================================================
-- PostgreSQL schema for flow-centric baseline.
-- Executed by docker-entrypoint-initdb.d on first container start.
--
-- Design notes:
--   * INET for IP addresses (not TEXT): proper type, efficient storage,
--     built-in operators. 7 bytes per IPv4, 19 for IPv6.
--   * TIMESTAMPTZ for timestamps: timezone-aware, 8 bytes.
--   * BIGINT for counters: CICIDS2017 has individual flows with 10^9+ bytes;
--     INT overflow possible on high-volume DDoS traffic.
--   * Partition by t_start: baseline detectors scan specific time windows;
--     partition pruning gives us "free" index on time.
-- ==================================================================

-- Force UTC for deterministic day-boundary partitioning.
SET TIME ZONE 'UTC';

-- Extensions (pg_stat_statements is nice-to-have for later tuning;
-- require preload to fully collect, but CREATE is safe even without it).
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- ==================================================================
-- Core fact table: raw network flows
-- ==================================================================

CREATE TABLE flows (
    flow_id           BIGSERIAL       NOT NULL,
    source_ip         INET            NOT NULL,
    source_port       INT             NOT NULL,
    destination_ip    INET            NOT NULL,
    destination_port  INT             NOT NULL,
    protocol          SMALLINT        NOT NULL,
    t_start           TIMESTAMPTZ     NOT NULL,
    t_end             TIMESTAMPTZ     NOT NULL,
    flow_duration_us  BIGINT          NOT NULL,
    bytes_fwd         BIGINT          NOT NULL DEFAULT 0,
    bytes_bwd         BIGINT          NOT NULL DEFAULT 0,
    packets_fwd       BIGINT          NOT NULL DEFAULT 0,
    packets_bwd       BIGINT          NOT NULL DEFAULT 0,
    syn_count         INT             NOT NULL DEFAULT 0,
    rst_count         INT             NOT NULL DEFAULT 0,
    psh_count         INT             NOT NULL DEFAULT 0,
    ack_count         INT             NOT NULL DEFAULT 0,
    fin_count         INT             NOT NULL DEFAULT 0,
    urg_count         INT             NOT NULL DEFAULT 0,
    label             TEXT            NOT NULL DEFAULT 'BENIGN'
    -- Note: we intentionally do NOT add a generated time_bucket_60s column here.
    -- EXTRACT(EPOCH FROM timestamptz) is STABLE, not IMMUTABLE, so PostgreSQL
    -- rejects it as a generated-column expression. Queries compute the bucket
    -- on the fly: EXTRACT(EPOCH FROM t_start)::BIGINT / 60.
) PARTITION BY RANGE (t_start);

-- One partition per CICIDS2017 day (Mon/Tue/Wed 2017-07-03..05 UTC) + default.
CREATE TABLE flows_mon PARTITION OF flows
    FOR VALUES FROM ('2017-07-03 00:00:00+00') TO ('2017-07-04 00:00:00+00');
CREATE TABLE flows_tue PARTITION OF flows
    FOR VALUES FROM ('2017-07-04 00:00:00+00') TO ('2017-07-05 00:00:00+00');
CREATE TABLE flows_wed PARTITION OF flows
    FOR VALUES FROM ('2017-07-05 00:00:00+00') TO ('2017-07-06 00:00:00+00');
CREATE TABLE flows_default PARTITION OF flows DEFAULT;

-- Partition key must be part of PK on partitioned tables.
ALTER TABLE flows ADD PRIMARY KEY (flow_id, t_start);

-- ==================================================================
-- Dimension table: hosts (populated post-load from flows; no FK —
-- partitioned tables don't support incoming FKs that cross partitions).
-- ==================================================================

CREATE TABLE hosts (
    ip            INET         PRIMARY KEY,
    first_seen    TIMESTAMPTZ  NOT NULL,
    last_seen     TIMESTAMPTZ  NOT NULL,
    total_flows   BIGINT       NOT NULL DEFAULT 0,
    inferred_role TEXT         NOT NULL DEFAULT 'unknown'
                               CHECK (inferred_role IN
                                      ('unknown', 'client', 'server', 'gateway'))
);

-- ==================================================================
-- Results table: baseline detector outputs.
-- One row per detection event; lets us compute P/R/F1 vs ground truth.
-- ==================================================================

CREATE TABLE baseline_detections (
    detection_id   BIGSERIAL        PRIMARY KEY,
    detector_name  TEXT             NOT NULL,
    src_ip         INET             NOT NULL,
    dst_ip         INET             NULL,
    t_window_start TIMESTAMPTZ      NOT NULL,
    t_window_end   TIMESTAMPTZ      NOT NULL,
    severity_score DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    details_jsonb  JSONB            NULL
);

COMMENT ON TABLE flows IS
    'Raw network flows from CICIDS2017. Partitioned by t_start, one partition per day.';
COMMENT ON COLUMN flows.label IS
    'Ground truth label from CICIDS2017. USE ONLY FOR EVALUATION, never in detection logic.';
COMMENT ON TABLE baseline_detections IS
    'Output of PL/pgSQL rule-based detectors (T14). One row per detection event.';
