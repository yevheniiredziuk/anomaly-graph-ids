// ============================================================================
// V002: Indexes on baseline-computed properties.
// These properties are populated by the baseline service (T09);
// indexes speed up queries during the detection window.
// ============================================================================

CREATE INDEX host_current_community IF NOT EXISTS
    FOR (h:Host) ON (h.current_community);

CREATE INDEX host_baseline_bc_mean IF NOT EXISTS
    FOR (h:Host) ON (h.baseline_bc_mean);

// Composite index for scoring queries that combine current_bc + baseline stats
CREATE INDEX host_scoring IF NOT EXISTS
    FOR (h:Host) ON (h.current_anomaly_score);
