-- ==================================================================
-- Indexes for flows table.
-- Separated from schema for flexibility:
--   * Can be dropped before bulk load for faster COPY
--   * Can be dropped and re-created for benchmark comparison
--   * Explicitly named for monitoring via pg_stat_user_indexes
-- ==================================================================

-- Primary access patterns for baseline detectors:
--  1. "All flows from X in window [t_start, t_end]"
--  2. "All flows to X in window [t_start, t_end]"
--  3. "All flows with (src, dst) pair in window"

CREATE INDEX IF NOT EXISTS idx_flows_src_time   ON flows (source_ip, t_start);
CREATE INDEX IF NOT EXISTS idx_flows_dst_time   ON flows (destination_ip, t_start);
CREATE INDEX IF NOT EXISTS idx_flows_pair_time  ON flows (source_ip, destination_ip, t_start);

-- Partial index: only attack rows — benign dominates (~99 %) so full-column
-- index would waste space. Used exclusively by evaluation queries.
CREATE INDEX IF NOT EXISTS idx_flows_label ON flows (label)
    WHERE label <> 'BENIGN';

-- Detection-result analysis (T14..T16).
CREATE INDEX IF NOT EXISTS idx_detections_detector
    ON baseline_detections (detector_name, t_window_start);
CREATE INDEX IF NOT EXISTS idx_detections_src
    ON baseline_detections (src_ip, t_window_start);

-- Hosts
CREATE INDEX IF NOT EXISTS idx_hosts_first_seen ON hosts (first_seen);

ANALYZE flows;
ANALYZE hosts;
ANALYZE baseline_detections;
