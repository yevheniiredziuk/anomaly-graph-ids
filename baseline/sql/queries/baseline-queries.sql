-- Read-only sanity checks for the baseline schema.
-- Use: docker exec -i agids-postgres psql -U ids -d ids < baseline/sql/queries/baseline-queries.sql

-- 1. Partition sizes
\echo '=== 1. Partition sizes ==='
SELECT
    c.relname                                           AS partition_name,
    pg_size_pretty(pg_total_relation_size(c.oid))       AS total_size
FROM pg_class c
WHERE c.relname LIKE 'flows%'
  AND c.relkind IN ('r', 'p')
ORDER BY c.relname;

-- 2. Basic aggregates per day (partition pruning kicks in via t_start filter)
\echo ''
\echo '=== 2. Per-day aggregates ==='
SELECT
    DATE_TRUNC('day', t_start)                                      AS day,
    COUNT(*)                                                        AS total_flows,
    COUNT(DISTINCT source_ip)                                       AS unique_sources,
    COUNT(DISTINCT destination_ip)                                  AS unique_destinations,
    ROUND(100.0 * SUM(CASE WHEN label <> 'BENIGN' THEN 1 ELSE 0 END) / COUNT(*), 2) AS pct_attack
FROM flows
GROUP BY 1
ORDER BY 1;

-- 3. Top talkers on Tuesday (candidate targets for port-scan-like detectors)
\echo ''
\echo '=== 3. Tuesday top talkers ==='
SELECT source_ip,
       COUNT(*)                          AS flows,
       COUNT(DISTINCT destination_port)  AS unique_dst_ports
FROM flows
WHERE t_start >= '2017-07-04 00:00:00+00'
  AND t_start <  '2017-07-05 00:00:00+00'
GROUP BY source_ip
HAVING COUNT(*) > 100
ORDER BY unique_dst_ports DESC
LIMIT 20;

-- 4. Label distribution (reality check vs T03 numbers)
\echo ''
\echo '=== 4. Label distribution ==='
SELECT label,
       COUNT(*)                                                        AS n,
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2)              AS pct
FROM flows
GROUP BY label
ORDER BY n DESC;

-- 5. Partition pruning verification — plan must touch only flows_tue
\echo ''
\echo '=== 5. Partition pruning (should touch only flows_tue) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(*) FROM flows
WHERE t_start >= '2017-07-04 09:00:00+00'
  AND t_start <  '2017-07-04 10:00:00+00';

-- 6. Index usage (meaningful after at least one detector run; blank right after load)
\echo ''
\echo '=== 6. Index usage ==='
SELECT
    indexrelname                           AS index_name,
    idx_scan                               AS scans,
    idx_tup_read                           AS tuples_read,
    pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
ORDER BY idx_scan DESC, index_name;
